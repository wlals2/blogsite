---
title: "Jenkins로 구축한 GitOps CI/CD 파이프라인 실전 구축기"
date: 2025-12-23T14:00:00+09:00
tags: ["Jenkins", "GitOps", "CI/CD", "Docker", "ArgoCD", "Kubernetes"]
categories: ["CI/CD & GitOps"]
description: "Jenkins와 ArgoCD로 멱등성과 재현성을 보장하는 GitOps CI/CD 파이프라인을 구축한 실전 경험을 공유합니다. Docker 기반 빌드, ECR 이미지 관리, 자동 배포까지 전 과정을 다룹니다."
---

## 들어가며

CI/CD 파이프라인을 처음 구축할 때 가장 고민했던 건 "어떻게 하면 같은 코드를 빌드할 때마다 똑같은 결과가 나올까?"였어요. 개발자 A의 Mac에서 빌드한 결과와 Jenkins 서버에서 빌드한 결과가 다르면 안 되니까요.

이 글에서는 Jenkins와 ArgoCD를 사용해 **멱등성(Idempotency)**과 **재현성(Reproducibility)**을 보장하는 GitOps CI/CD 파이프라인을 어떻게 구축했는지 공유하려고 합니다.

---

## 아키텍처 개요

먼저 전체 흐름을 보면 이렇게 됩니다:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           CI/CD 파이프라인 흐름                              │
└─────────────────────────────────────────────────────────────────────────────┘

  개발자                Source Repo              Jenkins               ECR
    │                      │                       │                    │
    │  git push            │                       │                    │
    ├─────────────────────►│                       │                    │
    │                      │   Webhook 트리거      │                    │
    │                      ├──────────────────────►│                    │
    │                      │                       │                    │
    │                      │   1. Workspace 정리   │                    │
    │                      │   2. Source Clone     │                    │
    │                      │   3. Docker Build     │                    │
    │                      │   4. ECR Push         │                    │
    │                      │   ├──────────────────►│────────────────────►
    │                      │   │                   │                    │
    │                      │   5. Manifest 업데이트│                    │
    │                      │   6. Cleanup          │                    │
    │                      │                       │                    │

                        Manifest Repo            ArgoCD               EKS
                             │                      │                   │
    Jenkins                  │                      │                   │
      │  git push            │                      │                   │
      ├─────────────────────►│                      │                   │
      │                      │   Auto Sync 감지     │                   │
      │                      ├─────────────────────►│                   │
      │                      │                      │  kubectl apply    │
      │                      │                      ├──────────────────►│
      │                      │                      │                   │
```

코드를 push하면 Jenkins가 자동으로 Docker 이미지를 빌드하고, ECR에 올린 다음, Kubernetes manifest를 업데이트하고, ArgoCD가 이를 감지해서 자동으로 배포하는 구조예요.

---

## 저장소 구조

프로젝트를 시작하면서 저장소를 2개로 분리했어요:

### Source Repository (sourece-repo)

```
sourece-repo/
├── was/                    # WAS 소스 (Spring PetClinic)
│   ├── src/
│   ├── pom.xml
│   └── Dockerfile
└── web/                    # WEB 소스 (Nginx)
    ├── nginx.conf
    └── Dockerfile
```

실제 애플리케이션 코드가 들어있는 저장소예요. 여기에 코드를 push하면 빌드가 트리거됩니다.

### Manifest Repository (manifestrepo)

```
manifestrepo/
├── Jenkinsfile-was         # WAS 빌드 파이프라인
├── Jenkinsfile-web         # WEB 빌드 파이프라인
├── Jenkinsfile-was-dr      # Azure DR용 WAR 빌드
├── was/
│   └── rollout.yaml        # WAS Argo Rollout
└── web/
    └── deployment.yaml     # WEB Deployment
```

Kubernetes manifest 파일들이 들어있어요. Jenkins가 여기 있는 이미지 태그를 업데이트하면 ArgoCD가 자동으로 배포합니다.

---

## 핵심 원칙: 멱등성과 재현성

파이프라인을 설계할 때 가장 중요하게 생각한 원칙들이에요:

| 원칙 | 구현 방법 | 왜 중요한가? |
|------|----------|------------|
| **멱등성** | 같은 커밋 SHA → 같은 이미지 (ECR 체크 후 스킵) | 불필요한 빌드 방지, 리소스 절약 |
| **재현성** | Docker 내부 빌드 (환경 고정) + Workspace 정리 | 누가 빌드해도 같은 결과 |
| **격리** | 매 빌드마다 fresh git clone | 이전 빌드 영향 제거 |
| **추적성** | 커밋 SHA 기반 이미지 태그 | 문제 발생 시 원인 추적 가능 |
| **정리** | 빌드 후 source/manifest/image 삭제 | 디스크 공간 관리 |

---

## Jenkinsfile 구조

파이프라인은 6개의 Stage로 구성했어요:

```
Stage 0: Clean Workspace
    └── deleteDir() - 이전 빌드 잔재 완전 삭제

Stage 1: Checkout Source
    └── git clone SOURCE_REPO

Stage 2: Check Existing Image (멱등성)
    └── ECR에 동일 SHA 태그 존재하면 빌드 스킵

Stage 3: Docker Build
    └── Docker 컨테이너 내에서 빌드 (재현성 보장)

Stage 4: Push to ECR
    └── SHA 태그 + latest 태그

Stage 5: Update Manifest
    └── rollout.yaml 이미지 태그 업데이트
    └── git push to manifestrepo

Post - Cleanup
    └── Docker 이미지 삭제
    └── source/, manifest/ 디렉토리 삭제
```

### 멱등성 체크: 같은 커밋은 다시 빌드하지 않기

가장 먼저 고민한 건 "같은 코드를 실수로 두 번 빌드하면 어떻게 하지?"였어요. 이걸 해결하기 위해 ECR에 이미 같은 커밋의 이미지가 있는지 확인하는 로직을 넣었습니다:

```
┌─────────────────────────────────────────────────────────────┐
│  ECR에서 SHA를 포함한 태그 검색                              │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  aws ecr describe-images                                    │
│    --query "imageDetails[?contains(imageTags, 'c342530')]"  │
│                                                             │
│  결과 예시:                                                 │
│  - v25-c342530 ← 이미 존재하면 빌드 스킵                    │
│                                                             │
│  동작:                                                      │
│  1. 같은 커밋(c342530) 재빌드 시도                          │
│  2. ECR에서 c342530 포함 태그 검색                          │
│  3. v25-c342530 발견 → 빌드 스킵 (멱등성)                   │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

이렇게 하면 Jenkins Job을 실수로 두 번 눌러도 괜찮아요. 리소스 낭비 없이 "이미 빌드됨"이라고 알려주고 끝나니까요.

---

## 재현성: 누가 빌드해도 같은 결과

### 문제: 로컬 빌드는 믿을 수 없다

처음에는 이런 고민이 있었어요:

```
개발자 A (Mac, JDK 17)     → WAR 결과 X
개발자 B (Windows, JDK 21) → WAR 결과 Y  ← 다른 결과!
개발자 C (Linux, JDK 11)   → WAR 결과 Z
```

같은 코드를 빌드해도 환경이 다르면 결과가 달라질 수 있거든요. JDK 버전이 다르거나, Maven 버전이 다르거나, OS가 다르면 생기는 문제였어요.

### 해결: Docker가 환경을 고정한다

이 문제를 Docker로 해결했습니다:

```dockerfile
# was/Dockerfile
FROM maven:3.8-amazoncorretto-17 AS builder   # 환경 고정
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn clean package -P MySQL               # 같은 명령 = 같은 결과
```

Jenkins는 이제 Maven을 직접 실행하지 않아요. 그냥 Docker만 실행합니다:

```
Jenkins가 하는 일:
docker build -t ${IMAGE} .   ← Docker만 실행
                             ← Maven은 Docker 안에서 실행됨

결과:
- Jenkins 서버에 Maven 불필요
- 어떤 Jenkins에서 빌드해도 동일 결과
```

이렇게 하면 Mac에서 빌드하든, Linux에서 빌드하든, Windows에서 빌드하든 **항상 같은 결과**가 나와요. Dockerfile에 명시된 환경에서만 빌드되니까요.

---

## 이미지 태그 전략

이미지 태그는 이렇게 정했어요:

```
v${BUILD_NUMBER}-${SHORT_SHA}

예: v25-c342530
```

| 요소 | 의미 | 왜 필요한가? |
|------|------|------------|
| `v${BUILD_NUMBER}` | Jenkins 빌드 번호 | 시간 순서 명확 (v28 > v25) |
| `${SHORT_SHA}` | Git 커밋 SHA (짧은 버전) | 어떤 코드인지 추적 가능 |

이렇게 하면 좋은 점이:

- **롤백이 직관적**: "v23으로 롤백해줘"라고 말하면 됨
- **커밋 추적 가능**: SHA로 `git log`에서 정확한 코드 찾기
- **멱등성 유지**: SHA 기반으로 중복 빌드 체크

---

## Jenkins Job 설정

실제 Jenkins Job은 이렇게 설정했어요:

| Job | Jenkinsfile | 트리거 |
|-----|-------------|--------|
| eks-3tier-was-gitops | Jenkinsfile-was | was/ 변경 시 |
| eks-3tier-web-gitops | Jenkinsfile-web | web/ 변경 시 |
| petclinic-was-dr | Jenkinsfile-was-dr | 매주 일요일 03:00 |

### Job 설정 방법

```
Definition: Pipeline script from SCM
SCM: Git
Repository URL: https://github.com/wlals2/manifestrepo.git
Credentials: git-key
Branch: */main
Script Path: Jenkinsfile-was (또는 Jenkinsfile-web)
```

Jenkinsfile을 Git에 저장하고, Jenkins는 그걸 가져다 쓰는 방식이에요. 이렇게 하면 파이프라인 코드도 버전 관리가 되니까 좋았어요.

---

## 트러블슈팅: 겪었던 문제들

### 1. git push rejected (non-fast-forward)

Jenkins가 manifest를 업데이트하고 push했는데, 로컬에서 pull 안 하고 push하면 이런 에러가 나요:

```bash
! [rejected]        main -> main (non-fast-forward)
```

**해결 방법:**
```bash
# Jenkins가 manifestrepo에 커밋해서 로컬과 다름
git fetch origin main
git diff HEAD origin/main --stat   # 충돌 파일 확인
git pull --rebase origin main
git push
```

### 2. 빌드가 이전 커밋으로 됨

Jenkins workspace에 이전 소스가 캐시되어 있으면 최신 코드로 빌드가 안 되는 문제가 있었어요.

**해결 방법:**

Jenkinsfile 첫 번째 Stage에 `deleteDir()` 추가:

```groovy
stage('Clean Workspace') {
    steps {
        deleteDir()  // 이전 빌드 잔재 완전 삭제
    }
}
```

이러면 매번 fresh한 상태에서 시작하니까 문제가 없었어요.

### 3. ECR 이미지가 안 보임

빌드는 성공했는데 ECR에서 이미지가 안 보이면:

```bash
# 확인
aws ecr describe-images --repository-name eks-3tier-dev-was \
    --query 'imageDetails[*].imageTags' --output text
```

보통 IAM 권한 문제거나, repository 이름이 잘못된 경우였어요.

---

## 수동 작업: 배포 흐름

실제로 배포할 때는 이렇게 했어요:

### 1. 빌드 트리거

```bash
# Source 변경 후 push (자동 트리거)
cd ~/CICD/sourece-repo
# 코드 수정
git add . && git commit -m "message"
git push origin main

# 또는 Jenkins UI에서 수동 빌드
# http://localhost:8080/job/eks-3tier-was-gitops/build
```

### 2. ArgoCD Sync

대부분 자동으로 sync되는데, 안 되면:

```bash
# 자동 sync 안 되면 수동 실행
argocd app sync petclinic

# 또는 kubectl로
kubectl patch application petclinic -n argocd \
    --type merge -p '{"operation":{"sync":{}}}'
```

---

## 배운 점

### 멱등성이 왜 중요한가

처음에는 "같은 커밋 두 번 빌드하면 안 되나?"라고 생각했는데, 실제로 운영하다 보니 **리소스 낭비**가 심각했어요. ECR 체크를 추가하고 나니 불필요한 빌드가 30% 줄어들었습니다.

### 재현성은 신뢰의 문제

"개발 환경에서는 되는데 프로덕션에서는 안 돼요"라는 말을 안 하려면 재현성이 꼭 필요해요. Docker로 빌드 환경을 고정하고 나니 이런 문제가 완전히 사라졌습니다.

### GitOps는 진짜 편하다

코드만 push하면 Jenkins가 빌드하고, ArgoCD가 배포하고, 끝. 수동으로 kubectl 칠 일이 거의 없어요. manifest도 Git에 있으니까 롤백도 쉽고, 히스토리 추적도 쉽고, 정말 좋았어요.

---

## 마무리

Jenkins와 ArgoCD로 GitOps CI/CD 파이프라인을 구축하면서 **멱등성**, **재현성**, **추적성**의 중요성을 배웠습니다. 처음엔 복잡해 보였는데, 막상 구축하고 나니 운영이 정말 편해졌어요.

특히 Docker로 빌드 환경을 고정한 게 가장 큰 성과였던 것 같아요. "내 환경에서는 되는데"라는 말을 이제 안 해도 되니까요.

다음 글에서는 Argo Rollouts로 안전한 Canary 배포를 구현한 경험을 공유할게요!
