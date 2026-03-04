---
title: "[ArgoCD/GitOps 시리즈 #1] GitOps란 무엇인가 — Git이 유일한 진실의 원천이 될 때"
date: 2026-03-03T10:00:00+09:00
description: "GitOps가 왜 등장했는지, kubectl apply와 무엇이 다른지, ArgoCD가 어떤 문제를 해결하는지 개념부터 이해한다"
tags: ["gitops", "argocd", "kubernetes", "cicd", "devops", "single-source-of-truth"]
categories: ["Kubernetes"]
series: ["ArgoCD/GitOps 시리즈"]
draft: false
---

## 이 글을 읽기 전에

ArgoCD 시리즈의 다른 글들은 대부분 **설치와 운영**에 집중한다:

- ArgoCD 설치 완전 가이드
- GitOps CI/CD 파이프라인 구축
- ArgoCD IgnoreDifferences 설정

그런데 막상 ArgoCD를 설치하고 나서 이런 생각이 들었다:

> **"kubectl apply로도 되는데, 왜 굳이 ArgoCD를 쓰는 건가?"**

이 글은 그 질문에 답한다.

---

## 1. 문제의 시작: 수동 배포의 한계

### kubectl apply로 충분하지 않은가?

처음 Kubernetes를 배울 때는 이렇게 배포했다:

```bash
# 개발자가 직접 배포
kubectl apply -f deployment.yaml
kubectl apply -f service.yaml
kubectl apply -f ingress.yaml
```

소규모에서는 작동한다. 그런데 팀이 생기고 서비스가 늘어나면:

```
팀원 A: kubectl apply -f deployment.yaml  # v2.0 배포
팀원 B: kubectl apply -f deployment.yaml  # v1.9로 롤백? 실수?
팀원 C: kubectl edit deployment web       # 직접 수정 (Git에 없음!)
        → 재시작 시 사라짐 (Ghost Config)
```

**3가지 실제 문제가 생긴다:**

| 문제 | 발생 상황 | 결과 |
|------|----------|------|
| **누가 뭘 바꿨는지 모름** | 장애 발생 시 원인 추적 | 30분짜리 장애가 3시간으로 |
| **클러스터 실제 상태 ≠ Git 상태** | `kubectl edit` 직접 수정 | 재배포 시 설정 초기화 |
| **환경별 설정 관리 불가** | dev/staging/prod 다른 값 필요 | 파일 복사본이 난무 |

---

## 2. GitOps: Git을 "유일한 진실의 원천"으로

### GitOps의 핵심 원칙

**GitOps**는 2017년 Weaveworks가 정의한 운영 방식이다. 핵심은 하나다:

> **"시스템의 모든 상태를 Git에 선언적으로 저장하고, 자동화 도구가 Git 상태와 실제 상태를 일치시킨다."**

기존 방식 vs GitOps:

```
기존 방식 (Imperative / 명령형):
  개발자 → kubectl apply -f → 클러스터 변경
  "이걸 이렇게 바꿔라" (명령)
  → 클러스터 상태를 사람이 추적해야 함

GitOps 방식 (Declarative / 선언형):
  개발자 → Git commit + push → ArgoCD가 자동 동기화
  "원하는 상태는 이거야" (선언)
  → ArgoCD가 항상 Git 상태로 맞춰줌
```

### Single Source of Truth (SSOT)

GitOps에서 Git은 **Single Source of Truth(SSOT)**, 즉 "유일한 진실의 원천"이다.

```
Git Repository (진실의 원천)
  k8s-manifests/
  ├── services/blog-system/
  │   ├── deployment.yaml  ← "web Pod는 replica 2개, image v2.1"
  │   ├── service.yaml
  │   └── ...
  └── configs/
      └── ...

ArgoCD (감시자)
  ↕ 계속 비교 중
  "Git 상태" vs "클러스터 실제 상태"
  → 다르면? 자동으로 Git 상태로 복원

Kubernetes Cluster (실제 상태)
  실제로 동작 중인 Pod, Service 등
```

누군가 `kubectl edit`으로 직접 수정해도, ArgoCD가 **자동으로 되돌린다(selfHeal)**. Git만 건드리면 된다.

---

## 3. ArgoCD: GitOps를 구현하는 도구

### ArgoCD가 하는 일

ArgoCD는 Kubernetes 클러스터에 설치되어, Git Repository를 **지속적으로 감시**한다.

```
Git Repository
  │
  │ (3초마다 polling 또는 webhook)
  │
  ▼
ArgoCD (Application Controller)
  ├─ Git 상태 파악: "Git에는 replica: 3이라고 돼 있음"
  ├─ 클러스터 상태 파악: "실제는 replica: 2로 동작 중"
  └─ 차이 발견 → Sync 실행
       → kubectl apply -f ... (내부적으로)
       → 클러스터 상태를 Git 상태로 맞춤
```

### ArgoCD 핵심 개념

**Application** — "어떤 Git 경로를 어느 클러스터에 배포할지" 정의:

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: blog-system
  namespace: argocd
spec:
  source:
    repoURL: https://github.com/wlals2/k8s-manifests.git
    targetRevision: main          # 어떤 브랜치/태그
    path: services/blog-system    # 어떤 경로
  destination:
    server: https://kubernetes.default.svc
    namespace: blog-system        # 어느 namespace에 배포
  syncPolicy:
    automated:
      prune: true      # Git에서 삭제된 리소스 → 클러스터에서도 삭제
      selfHeal: true   # 직접 수정 감지 시 → 자동 복원
```

**SyncPolicy**가 핵심이다:
- `automated`: 자동으로 동기화 (수동 Sync 불필요)
- `selfHeal`: 클러스터 상태가 Git과 달라지면 자동 복원
- `prune`: Git에서 파일 삭제 시 클러스터 리소스도 삭제

---

## 4. App of Apps: 전체 클러스터를 Git으로 관리

### 문제: Application이 많아지면?

ArgoCD Application을 직접 생성하면:

```bash
kubectl apply -f argocd-app-blog-system.yaml
kubectl apply -f argocd-app-monitoring.yaml
kubectl apply -f argocd-app-security.yaml
# ... 30개의 Application을 손으로 등록
```

이것도 결국 수동이다. "ArgoCD Application 자체"도 Git으로 관리할 수 없는가?

### App of Apps 패턴

```
argocd/root-app.yaml (최상위 Application)
  │
  │ (Git에서 argocd/ 폴더 감시)
  │
  ├─ apps/
  │   ├── argocd-app-blog-system.yaml   → blog-system Application 자동 생성
  │   ├── argocd-app-monitoring.yaml    → monitoring Application 자동 생성
  │   └── argocd-app-security.yaml      → security Application 자동 생성
  │
  └─ 각 Application이 자신의 폴더를 감시
      ├── services/blog-system/ → Pod, Service 배포
      ├── configs/monitoring/   → Grafana, Prometheus 배포
      └── apps/security/        → Falco, Wazuh 배포
```

한 번만 `kubectl apply -f argocd/root-app.yaml` 하면,
나머지는 ArgoCD가 Git을 읽어서 **스스로 Application을 생성하고 배포**한다.

**클러스터 전체 복구도 가능:**
```bash
# 클러스터가 완전히 망가졌을 때
kubectl apply -f argocd/root-app.yaml
# → ArgoCD가 Git을 읽어 전체 클러스터 복원 (3-5분)
```

---

## 5. 실제로 얼마나 달라지는가

### Before: 수동 배포 시대

```bash
# 배포할 때마다 직접 실행
kubectl apply -f services/blog-system/was/deployment.yaml
kubectl apply -f services/blog-system/was/service.yaml

# 이미지 업데이트
kubectl set image deployment/was was=ghcr.io/wlals2/was:v2.1

# 문제: kubectl edit으로 누군가 직접 수정
kubectl edit deployment was
# → Git에 기록 없음, 재배포 시 사라짐
```

**발생하는 문제:**
- 클러스터 실제 상태가 Git과 달라도 모른다
- 장애 시 "누가 뭘 바꿨는지" 추적 불가
- `kubectl edit` 수정은 재배포 시 사라짐

### After: GitOps 시대

```bash
# 배포하려면? Git만 수정
vim services/blog-system/was/deployment.yaml
# image: ghcr.io/wlals2/was:v2.1 → v2.2로 수정

git add .
git commit -m "feat: was image v2.2 업데이트"
git push

# ArgoCD가 3초 내 감지 → 자동 배포
# 배포 추적: git log / ArgoCD UI에서 확인
```

**달라진 것:**
- 모든 변경사항이 Git 히스토리에 기록
- 누가, 언제, 무엇을, 왜 바꿨는지 추적 가능
- `kubectl edit` 시도 → ArgoCD가 자동으로 Git 상태로 복원
- 클러스터 상태 = Git 상태 (보장)

### 홈랩에서 관측한 수치

```
GitOps 도입 전:
  배포 후 직접 수정으로 Git 상태와 불일치 발생 빈도: 주 2-3회
  장애 원인 추적 평균 시간: 약 20분

GitOps 도입 후:
  Git 상태와 불일치: 0회 (selfHeal이 즉시 복원)
  장애 원인 추적: git log에서 즉시 확인
  배포 명령어 단순화: git push 1개로 완결
```

---

## 6. GitOps의 한계와 주의사항

### 알아야 할 트레이드오프

**좋은 점:**
- 모든 변경이 Git에 기록 → 감사(Audit) 가능
- 자동 동기화 → 설정 드리프트 방지
- 클러스터 복구가 `kubectl apply -f root-app.yaml` 1개로

**주의할 점:**
- `kubectl edit/apply` 직접 사용 금지 → Git 먼저 수정해야 함
- Secret 관리 별도 필요 → Git에 평문 Secret 커밋 금지
  (→ 해결책: SealedSecret으로 암호화 후 커밋)
- 긴급 상황에서 Git push를 기다려야 함
  (→ 예외: 디버깅 시 직접 수정 허용, 24시간 내 Git 반영)

---

## 7. 다음에 다룰 것

이 글은 GitOps와 ArgoCD의 **개념과 왜 필요한가**를 다뤘다.

시리즈 다음 글들에서는 실제 구축을 다룬다:

| 글 | 내용 |
|----|------|
| **[#2] ArgoCD 설치 완전 가이드** | Helm 설치, Ingress, 외부 접속 구성 |
| **[#3] GitOps CI/CD 파이프라인 구축** | GitHub Actions + ArgoCD 연동 |
| **[#4] Helm Wrapper Chart로 외부 솔루션 관리** | App of Apps 실전 |
| **[#5] ArgoCD IgnoreDifferences 설정** | Drift 감지 예외 처리 |

---

## 정리

- **GitOps**: Git을 Single Source of Truth로, 자동화 도구가 클러스터 상태를 Git에 맞춤
- **기존 수동 배포 문제**: 상태 드리프트, 추적 불가, 환경별 관리 어려움
- **ArgoCD**: GitOps를 구현하는 도구, Git과 클러스터 상태를 지속적으로 동기화
- **App of Apps**: ArgoCD Application 자체도 Git으로 관리 → 클러스터 전체 복구 가능
- **핵심 원칙**: kubectl edit/apply 금지, Git만 수정

---

**작성일**: 2026-03-03
**태그**: gitops, argocd, kubernetes, cicd, single-source-of-truth
**다음 글**: [[ArgoCD/GitOps 시리즈 #2] ArgoCD 설치 완전 가이드](/study/2026-01-20-argocd-installation-complete-guide/)
