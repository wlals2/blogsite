---
title: "Tekton vs Jenkins: 컨테이너 환경에서의 CI/CD 도구 비교"
date: 2025-11-16
draft: false
tags: ["CI/CD", "Tekton", "Jenkins", "Kubernetes", "DevOps", "Container"]
categories: ["study", "Development"]
series: ["CI/CD"]
description: "Jenkins 컨테이너의 문제점과 Tekton이 이를 어떻게 해결하는지 비교 분석"
---

## 들어가며

Hugo 블로그의 CI/CD 파이프라인을 구축하면서, GitHub Actions self-hosted runner에서 벗어나 독립적인 CI/CD 시스템을 고민하게 되었습니다.

처음엔 "Jenkins 컨테이너로 돌리면 되지 않을까?"라고 생각했어요. 근데 알고 보니... 그게 아니더라구요.

**현재 환경:**
- Hugo 정적 사이트 생성기
- Kubernetes 클러스터 (3노드)
- Private 블로그 (로컬 저장)
- Docker Compose로 여러 서비스 운영

**고민:**
- Jenkins를 컨테이너로 띄우면 문제가 많다는 경험
- Tekton이라는 새로운 도구는 어떻게 다른가?
- 컨테이너 환경에서 CI 도구를 어떻게 선택해야 하나?

이 글에서는 **Jenkins 컨테이너의 문제점**과 **Tekton의 차별점**을 실전 경험을 바탕으로 비교합니다.

---

## 1. Jenkins 컨테이너의 문제점

### 1.1 Docker-in-Docker (DinD) 문제

Jenkins 컨테이너에서 Docker 이미지를 빌드하려면 두 가지 방법이 있습니다. 둘 다 문제가 있어요...

#### 방법 1: Docker 소켓 마운트 (보안 위험)

```yaml
# docker-compose.yml
services:
  jenkins:
    image: jenkins/jenkins:lts
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock  # 🔴 위험!
```

처음엔 "간단하네!"라고 생각했는데, 알고 보니 이게 얼마나 위험한지...

**문제점:**
- **호스트의 Docker 데몬 전체를 노출**
- 컨테이너가 호스트의 모든 컨테이너 조작 가능
- 권한 에스컬레이션 공격 가능
- 보안 감사에서 RED FLAG

**실제 시나리오:**
```bash
# Jenkins 컨테이너 안에서
docker run --privileged --pid=host --net=host \
  -v /:/host alpine chroot /host /bin/bash
# → 호스트 시스템 전체 접근 가능!
```

이거 보고 정말 놀랐어요. 컨테이너 안에서 호스트 전체를 장악할 수 있다니...

#### 방법 2: DinD 컨테이너 (복잡도 증가)

```yaml
services:
  jenkins:
    image: jenkins/jenkins:lts
    environment:
      DOCKER_HOST: tcp://docker:2376
      DOCKER_TLS_VERIFY: 1

  docker:
    image: docker:dind
    privileged: true  # 🔴 privileged 필수
    environment:
      DOCKER_TLS_CERTDIR: /certs
    volumes:
      - docker-certs:/certs
```

**문제점:**
- `privileged: true` 필요 (보안 위험)
- TLS 인증서 관리 복잡
- 네트워크 설정 복잡
- 디버깅 어려움
- 리소스 중복 사용 (Docker-in-Docker)

이것도 한참 고생했어요. TLS 인증서 때문에 계속 연결이 안 되더라구요.

---

### 1.2 권한 문제의 늪

#### UID/GID 불일치

```bash
# 호스트: jimin (uid=1000, gid=1000)
# Jenkins 컨테이너: jenkins (uid=1000, gid=1000) ← 같아 보이지만...

# 문제 발생 상황:
docker run --rm \
  -v /home/jimin/blogsite:/workspace \
  jenkins/jenkins:lts \
  ls -la /workspace

# 결과: Permission Denied!
# 이유: 컨테이너 내부의 uid 1000과 호스트의 uid 1000은 다른 사용자
```

처음엔 "UID가 같은데 왜 안 되지?"라고 한참 고민했어요.

**해결 시도 1: user 옵션**
```yaml
services:
  jenkins:
    user: "${UID}:${GID}"  # 환경변수로 전달
```

→ Jenkins 내부 파일 권한 깨짐

**해결 시도 2: chown 지옥**
```bash
# 볼륨 마운트할 때마다
docker run --rm -v /path:/data alpine chown -R 1000:1000 /data
```

→ 매번 수동 작업, 자동화 불가

**해결 시도 3: 777 권한 (최악)**
```bash
chmod -R 777 /home/jimin/blogsite  # 🔴 절대 안됨!
```

이건 정말 최악이에요. 보안 구멍이 활짝...

---

### 1.3 플러그인 지옥

#### 문제 1: 플러그인 재설치

```bash
# 컨테이너 재시작할 때마다
docker restart jenkins

# → 모든 플러그인 재다운로드
# → 의존성 해결 다시 시작
# → 초기화 시간 5-10분
```

한 번은 플러그인 때문에 Jenkins가 30분 동안 안 떴어요...

#### 문제 2: 플러그인 충돌

```groovy
// 실제 겪은 사례
plugins {
  'kubernetes:1.30.0'         // Kubernetes 플러그인
  'docker-workflow:1.28'      // Docker 파이프라인
  'pipeline-stage-view:2.33'  // 파이프라인 뷰
}

// 업데이트 후
Error: kubernetes:1.31.0 requires workflow-step-api:2.25
       but docker-workflow:1.28 requires workflow-step-api:2.24
       → 의존성 충돌!
```

이거 때문에 한참 헤맸어요. 어떤 플러그인을 먼저 업데이트해야 하는지...

#### 문제 3: 플러그인 버전 관리

```dockerfile
# Dockerfile로 플러그인 고정 시도
FROM jenkins/jenkins:lts
RUN jenkins-plugin-cli --plugins \
  kubernetes:1.30.0 \
  docker-workflow:1.28 \
  git:4.11.0 \
  # ... 100개 이상의 플러그인

# → Dockerfile 600줄 넘어감
# → 빌드 시간 30분 이상
# → 업데이트할 때마다 재빌드
```

---

### 1.4 영구 스토리지 문제

#### 데이터 유실 위험

```yaml
services:
  jenkins:
    image: jenkins/jenkins:lts
    # volumes 없으면 → 재시작 시 모든 설정 삭제!
```

한 번 volumes를 빼먹고 재시작했다가... 모든 Job 설정이 날아갔어요.

**저장해야 할 데이터:**
- Jenkins 설정 (`/var/jenkins_home/config.xml`)
- 플러그인 (`/var/jenkins_home/plugins/`)
- Job 설정 (`/var/jenkins_home/jobs/`)
- 빌드 히스토리 (`/var/jenkins_home/builds/`)
- 워크스페이스 (`/var/jenkins_home/workspace/`)
- 시크릿 (`/var/jenkins_home/secrets/`)

**필요한 볼륨:**
```yaml
volumes:
  - jenkins_home:/var/jenkins_home           # 메인 데이터
  - jenkins_plugins:/var/jenkins_home/plugins # 플러그인만 분리?
  - jenkins_jobs:/var/jenkins_home/jobs       # Job 설정만 분리?
  # → 관리 복잡도 증가
```

#### 백업/복구 복잡도

```bash
# 백업
docker exec jenkins tar czf - /var/jenkins_home > jenkins-backup.tar.gz

# 복구
docker exec jenkins tar xzf - < jenkins-backup.tar.gz

# → Job 100개 있으면 tar 파일 수 GB
# → 복구 시간 길어짐
```

---

### 1.5 설치 지옥

#### Jenkinsfile에서 도구 설치

```groovy
pipeline {
  agent {
    docker {
      image 'jenkins/jenkins:lts'
    }
  }
  stages {
    stage('Build') {
      steps {
        sh '''
          # 매번 설치해야 함
          apt-get update
          apt-get install -y hugo
          hugo --version
          hugo build
        '''
      }
    }
  }
}
```

**문제:**
- 매 빌드마다 `apt-get install` 실행 (느림)
- 캐싱해도 컨테이너 재시작하면 초기화
- 네트워크 장애 시 빌드 실패

#### 커스텀 이미지 만들기

```dockerfile
FROM jenkins/jenkins:lts

USER root

# Hugo 설치
RUN apt-get update && \
    apt-get install -y wget && \
    wget https://github.com/gohugoio/hugo/releases/download/v0.146.0/hugo_extended_0.146.0_linux-amd64.deb && \
    dpkg -i hugo_extended_0.146.0_linux-amd64.deb

# Node.js 설치
RUN curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \
    apt-get install -y nodejs

# Docker CLI 설치
RUN apt-get install -y docker.io

# kubectl 설치
RUN curl -LO https://dl.k8s.io/release/v1.31.0/bin/linux/amd64/kubectl && \
    install -o root -g root -m 0755 kubectl /usr/local/bin/kubectl

USER jenkins

# → 이미지 크기 2GB 넘어감
# → 빌드 시간 10분+
# → 버전 업데이트할 때마다 재빌드
```

이거 만들 때 정말 힘들었어요. 이미지가 너무 커져서...

---

## 2. Tekton의 차별점

### 2.1 Kubernetes Native 아키텍처

#### 핵심 개념: "Task마다 독립적인 Pod"

```yaml
# Jenkins 방식
┌─────────────────────────┐
│   Jenkins 컨테이너       │  ← 계속 실행 중
│   - Master 프로세스      │
│   - 플러그인 로딩        │
│   - Job 실행 환경        │
└─────────────────────────┘
      ↓ 모든 빌드가 여기서 실행

# Tekton 방식
Git Clone → Pod 생성 → 작업 완료 → Pod 삭제
Hugo Build → Pod 생성 → 작업 완료 → Pod 삭제
Deploy     → Pod 생성 → 작업 완료 → Pod 삭제
           ↑ 각 Task마다 새로운 Pod
           ↑ 필요한 이미지만 사용
           ↑ 완료 후 자동 정리
```

처음 이 개념을 알았을 때 "아, 이게 진짜 Cloud Native구나"라고 느꼈어요.

**Tekton의 철학:**
- **No permanent daemon** (영구 실행 데몬 없음)
- **Ephemeral execution** (일회성 실행)
- **Kubernetes as Platform** (K8s가 플랫폼)

---

### 2.2 Docker 이미지 빌드: Kaniko와 Buildah

#### Jenkins의 DinD 문제 해결

Tekton은 **Docker 데몬 없이 이미지를 빌드**합니다! 처음엔 "그게 가능해?"라고 의심했는데, 정말 되더라구요.

#### 방법 1: Kaniko (추천)

```yaml
apiVersion: tekton.dev/v1beta1
kind: Task
metadata:
  name: build-docker-image
spec:
  params:
    - name: image
      description: 빌드할 이미지 이름
  steps:
    - name: build-and-push
      image: gcr.io/kaniko-project/executor:latest
      args:
        - "--dockerfile=Dockerfile"
        - "--context=."
        - "--destination=$(params.image)"
      # ✅ Docker 데몬 필요 없음!
      # ✅ privileged 필요 없음!
      # ✅ 보안 안전!
```

**Kaniko 동작 원리:**
1. Dockerfile을 파싱
2. 각 레이어를 userspace에서 구성
3. 이미지 레지스트리에 직접 푸시
4. **Docker 데몬 전혀 사용 안 함**

처음 Kaniko를 써봤을 때 정말 신기했어요. Docker 없이 이미지를 빌드하다니!

#### 방법 2: Buildah

```yaml
apiVersion: tekton.dev/v1beta1
kind: Task
metadata:
  name: buildah-build
spec:
  steps:
    - name: build
      image: quay.io/buildah/stable:latest
      script: |
        buildah bud -t myimage:latest .
        buildah push myimage:latest docker://registry.example.com/myimage:latest
      # ✅ rootless 모드 지원
      # ✅ OCI 호환
```

**Jenkins vs Tekton 이미지 빌드 비교:**

| 항목 | Jenkins (DinD) | Tekton (Kaniko) |
|------|---------------|----------------|
| Docker 데몬 | 필요 (DinD 컨테이너) | **불필요** |
| privileged | 필수 | **불필요** |
| 보안 | 위험 | 안전 |
| 복잡도 | 높음 | 낮음 |
| 리소스 | 중복 사용 | 효율적 |

---

### 2.3 권한 분리와 보안

#### Task마다 독립적인 권한

```yaml
# Tekton: Task마다 ServiceAccount 분리
apiVersion: tekton.dev/v1beta1
kind: Task
metadata:
  name: git-clone
spec:
  # ✅ 이 Task는 Git만 클론
  steps:
    - name: clone
      image: alpine/git
      # → 최소 권한 (Git 클론만 가능)

---
apiVersion: tekton.dev/v1beta1
kind: Task
metadata:
  name: deploy-k8s
spec:
  # ✅ 이 Task는 K8s 배포만
  steps:
    - name: deploy
      image: bitnami/kubectl
      # → kubectl 권한만 (Git 접근 불가)
```

이게 진짜 좋은 점이에요. Jenkins는 한 Job이 뚫리면 모든 권한이 노출되는데, Tekton은 Task마다 권한이 분리돼 있어요.

**Jenkins는?**
```groovy
// Jenkins: 모든 Job이 같은 권한 공유
pipeline {
  agent any  // ← 모든 권한 공유
  stages {
    stage('Clone') { ... }
    stage('Build') { ... }
    stage('Deploy') { ... }
  }
}
// → 한 Job이 뚫리면 모든 시크릿 노출 위험
```

---

### 2.4 플러그인 없는 세상

#### Jenkins의 플러그인 vs Tekton의 Task Catalog

**Jenkins:**
```groovy
// 플러그인 필요 목록
plugins {
  'kubernetes:1.30.0'
  'docker-workflow:1.28'
  'git:4.11.0'
  'pipeline-stage-view:2.33'
  'credentials:2.6.1'
  'workflow-aggregator:2.7'
  // ... 100개 이상
}
```

**Tekton:**
```yaml
# Tekton Hub에서 Task 가져오기 (플러그인 아님!)
apiVersion: tekton.dev/v1beta1
kind: Pipeline
spec:
  tasks:
    - name: git-clone
      taskRef:
        name: git-clone
        # ✅ Tekton Hub에서 제공
        # ✅ YAML로 정의
        # ✅ 재시작 필요 없음
```

**차이점:**

| Jenkins 플러그인 | Tekton Task |
|----------------|-------------|
| JAR 파일 다운로드 | YAML 정의 |
| 재시작 필요 | 즉시 사용 |
| 의존성 충돌 | 독립적 실행 |
| 업데이트 복잡 | Git으로 관리 |
| 바이너리 (보안 위험) | 소스 공개 |

Tekton Hub에서 Task를 가져와서 바로 쓸 수 있다는 게 정말 편하더라구요.

---

### 2.5 영구 스토리지? 필요 없음!

#### Tekton의 상태 관리

```yaml
# Jenkins: /var/jenkins_home 전체 저장
volumes:
  - jenkins_home:/var/jenkins_home  # 수 GB

# Tekton: Kubernetes etcd에 상태 저장
kubectl get pipelineruns
# → K8s가 알아서 관리
# → 별도 볼륨 불필요
# → 백업은 K8s 백업과 동일
```

**실행 히스토리:**
```bash
# Jenkins: 파일시스템에 저장
/var/jenkins_home/jobs/myblog/builds/
├── 1/
├── 2/
├── 3/
# → 디스크 용량 계속 증가

# Tekton: K8s CRD로 저장
kubectl get pipelineruns
NAME                  SUCCEEDED   REASON      STARTTIME   COMPLETIONTIME
blog-deploy-run-1     True        Succeeded   5m          4m
blog-deploy-run-2     True        Succeeded   3m          2m
# → etcd에 저장 (자동 정리 가능)
```

---

## 3. 실전 비교: Hugo 블로그 빌드

### 3.1 Jenkins 컨테이너 방식

```yaml
# docker-compose.yml
version: '3.8'
services:
  jenkins:
    image: jenkins/jenkins:lts
    user: root  # ← 권한 문제 회피 (위험!)
    volumes:
      - jenkins_home:/var/jenkins_home
      - /var/run/docker.sock:/var/run/docker.sock  # ← DinD
      - /home/jimin/blogsite:/workspace/blogsite   # ← 볼륨 마운트
    ports:
      - "8080:8080"

volumes:
  jenkins_home:
```

```groovy
// Jenkinsfile
pipeline {
  agent any
  stages {
    stage('Build') {
      steps {
        sh '''
          cd /workspace/blogsite

          # Hugo 설치 체크 (매번)
          which hugo || {
            wget https://github.com/gohugoio/hugo/.../hugo.deb
            dpkg -i hugo.deb
          }

          # 빌드
          hugo --minify

          # 권한 문제 해결 (매번)
          chown -R www-data:www-data public/
        '''
      }
    }

    stage('Deploy') {
      steps {
        sh '''
          rsync -av public/ /var/www/blog/
          systemctl reload nginx
        '''
      }
    }
  }
}
```

**문제점:**
- ❌ `user: root` 사용 (보안 위험)
- ❌ Docker 소켓 마운트 (보안 위험)
- ❌ Hugo 매번 설치 체크
- ❌ 권한 문제로 `chown` 매번 실행
- ❌ Jenkins 컨테이너에서 호스트 systemd 접근 불가 → **실패!**

---

### 3.2 Tekton 방식

```yaml
# 1. Task: Hugo 빌드
apiVersion: tekton.dev/v1beta1
kind: Task
metadata:
  name: hugo-build
  namespace: hugo-system
spec:
  workspaces:
    - name: source
      description: Hugo 소스 코드
    - name: output
      description: 빌드 결과물
  steps:
    - name: build
      image: klakegg/hugo:0.146.0-ext-alpine
      workingDir: $(workspaces.source.path)
      script: |
        #!/bin/sh
        hugo --minify --destination $(workspaces.output.path)
        echo "Build completed"
        ls -lah $(workspaces.output.path)

---
# 2. Task: 배포
apiVersion: tekton.dev/v1beta1
kind: Task
metadata:
  name: deploy-to-nginx
  namespace: hugo-system
spec:
  workspaces:
    - name: output
  steps:
    - name: deploy
      image: alpine
      script: |
        #!/bin/sh
        # rsync 대신 kubectl cp 사용
        # 또는 NFS/Longhorn 볼륨 직접 쓰기
        cp -r $(workspaces.output.path)/* /var/www/blog/

---
# 3. Pipeline: 전체 파이프라인
apiVersion: tekton.dev/v1beta1
kind: Pipeline
metadata:
  name: blog-ci-cd
  namespace: hugo-system
spec:
  workspaces:
    - name: shared-workspace
  tasks:
    - name: fetch-repo
      taskRef:
        name: git-clone
      workspaces:
        - name: output
          workspace: shared-workspace
      params:
        - name: url
          value: http://gitea.hugo-system:3000/jimin/blog.git

    - name: build-hugo
      taskRef:
        name: hugo-build
      runAfter:
        - fetch-repo
      workspaces:
        - name: source
          workspace: shared-workspace
        - name: output
          workspace: shared-workspace

    - name: deploy
      taskRef:
        name: deploy-to-nginx
      runAfter:
        - build-hugo
      workspaces:
        - name: output
          workspace: shared-workspace

---
# 4. PipelineRun: 실행
apiVersion: tekton.dev/v1beta1
kind: PipelineRun
metadata:
  name: blog-deploy-run-1
  namespace: hugo-system
spec:
  pipelineRef:
    name: blog-ci-cd
  workspaces:
    - name: shared-workspace
      persistentVolumeClaim:
        claimName: hugo-workspace-pvc
```

**장점:**
- ✅ 각 Task가 독립적인 Pod
- ✅ Hugo 이미지만 사용 (설치 불필요)
- ✅ 권한 분리 (각 Task마다 ServiceAccount)
- ✅ PVC로 워크스페이스 공유
- ✅ Kubernetes Native (kubectl로 관리)

Tekton으로 바꾸고 나서 정말 편해졌어요. 더 이상 권한 문제로 고생하지 않아도 되니까요!

---

## 4. 볼륨 마운트 전략

### 4.1 Jenkins 컨테이너

```yaml
services:
  jenkins:
    volumes:
      # 호스트 → 컨테이너
      - /home/jimin/blogsite:/workspace/blogsite
      # ↑ 권한 문제 발생 지점
```

**문제:**
```bash
# 호스트
-rw-r--r-- 1 jimin jimin config.toml

# Jenkins 컨테이너 내부
-rw-r--r-- 1 1000 1000 config.toml
# ↑ UID는 같지만 username이 다름
# → Jenkins가 쓰기 못함
```

**해결 시도:**
```yaml
services:
  jenkins:
    user: "1000:1000"  # 호스트 UID 맞추기
    volumes:
      - /home/jimin/blogsite:/workspace/blogsite
# → Jenkins 내부 권한 깨짐
```

---

### 4.2 Tekton (Kubernetes)

```yaml
# PVC 생성
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: hugo-content-pvc
  namespace: hugo-system
spec:
  accessModes:
    - ReadWriteMany  # ✅ 여러 Pod에서 접근
  storageClassName: longhorn
  resources:
    requests:
      storage: 3Gi

---
# Task에서 사용
apiVersion: tekton.dev/v1beta1
kind: Task
spec:
  workspaces:
    - name: source
  steps:
    - name: build
      image: klakegg/hugo:ext-alpine
      workingDir: $(workspaces.source.path)
      # ✅ PVC 경로가 자동 마운트
      # ✅ Kubernetes가 권한 관리
```

**Longhorn PVC 사용 시:**
```bash
# 호스트에서 직접 수정
vim /home/jimin/blogsite/content/post.md

# Git push
git add .
git commit -m "Update post"
git push

# Tekton Pipeline 트리거
# → PVC에 Git clone
# → 최신 코드로 빌드
# → 같은 PVC에 결과 저장
```

Kubernetes의 PVC를 쓰니까 권한 문제가 사라지더라구요!

---

## 5. 컨테이너 환경에서의 근본적 차이

### 5.1 아키텍처 비교

#### Jenkins

```

┌─────────────────────────────────────────┐
│         Jenkins Master Container        │
│  ┌───────────────────────────────────┐  │
│  │    Jenkins 프로세스 (Java)        │  │
│  │    - Jetty 서버                   │  │
│  │    - 플러그인 로딩                 │  │
│  │    - Job 스케줄러                 │  │
│  │    - 빌드 실행 환경                │  │
│  └───────────────────────────────────┘  │
│                                         │
│  /var/jenkins_home (영구 저장)          │
│  ├── jobs/                              │
│  ├── builds/                            │
│  ├── plugins/                           │
│  └── secrets/                           │
└─────────────────────────────────────────┘
      ↑ 항상 실행 중 (메모리 상주)
      ↑ 재시작 시 플러그인 재로딩

```

#### Tekton

```

┌────────────────────────────────────────────┐
│       Tekton Controller (K8s 내부)         │
│  - Pipeline 감시                           │
│  - PipelineRun 생성                        │
│  - Task 스케줄링                           │
└──────────────┬─────────────────────────────┘
               ↓
    ┌──────────┴────────────┐
    ↓                        ↓
┌─────────┐            ┌─────────┐
│ Task 1  │            │ Task 2  │
│ (Pod)   │  완료 후   │ (Pod)   │
│         │  ────→     │         │
└─────────┘  삭제      └─────────┘
   ↑                       ↑
   독립 실행              독립 실행
   필요한 이미지만        필요한 이미지만

```

---

### 5.2 리소스 사용 비교

#### Jenkins 컨테이너

```bash
# 항상 실행
docker stats jenkins

CONTAINER   CPU %   MEM USAGE / LIMIT
jenkins     5.2%    850MB / 2GB
# ↑ 빌드 안 해도 항상 850MB 사용
# ↑ 플러그인 많으면 1.5GB+
```

#### Tekton

```bash
# 빌드 없을 때
kubectl top pods -n tekton-pipelines
NAME                    CPU   MEMORY
tekton-controller       5m    50Mi
tekton-webhook          3m    30Mi
# ↑ 컨트롤러만 80MB

# 빌드 실행 중
kubectl top pods -n hugo-system
NAME                CPU   MEMORY
git-clone-pod       10m   20Mi   # Git 클론 중
hugo-build-pod      50m   150Mi  # Hugo 빌드 중
# ↑ 필요할 때만 Pod 생성
# ↑ 완료 후 자동 삭제
```

**비교:**
- Jenkins: 850MB (항상)
- Tekton: 80MB (대기) + 170MB (빌드 시) = 최대 250MB
- **절감: 70% 메모리 절감!**

정말 놀라운 차이예요. 리소스가 제한된 홈서버에서는 이게 정말 중요해요.

---

### 5.3 보안 비교

#### Jenkins 컨테이너

```yaml
# 보안 취약점
services:
  jenkins:
    user: root  # ❌ root 권한
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock  # ❌ Docker 전체 접근
    environment:
      - JENKINS_ADMIN_PASSWORD=admin  # ❌ 환경변수에 시크릿
```

**공격 시나리오:**
```bash
# Jenkins Job이 해킹당하면
pipeline {
  steps {
    sh '''
      # Docker 소켓 접근 → 호스트 전체 접근
      docker run -v /:/host alpine chroot /host /bin/bash
      # → 게임 끝
    '''
  }
}
```

#### Tekton

```yaml
# 보안 강화
apiVersion: tekton.dev/v1beta1
kind: Task
spec:
  steps:
    - name: build
      image: klakegg/hugo:ext-alpine
      # ✅ Docker 데몬 접근 불가
      # ✅ 호스트 파일시스템 접근 불가
      # ✅ PVC 경로만 접근
      securityContext:
        runAsNonRoot: true
        runAsUser: 1000
        allowPrivilegeEscalation: false
```

**공격 시도 시:**
```yaml
# 악의적인 Task
steps:
  - name: attack
    image: alpine
    script: |
      #!/bin/sh
      cat /var/run/docker.sock
      # → 파일 없음 (마운트 안됨)

      docker ps
      # → 명령어 없음

      chroot /host
      # → 권한 없음 (Pod 격리)
```

Tekton의 보안 모델이 훨씬 안전하다는 걸 실감했어요.

---

## 6. Hugo 블로그 적용 시나리오

### 시나리오 1: Jenkins 로컬 설치 (추천)

```bash
# 로컬에 Jenkins 설치
sudo apt install jenkins

# 장점:
# ✅ 컨테이너 문제 회피
# ✅ 안정적 운영
# ✅ sudo 권한 사용 가능
# ✅ /var/www/blog 직접 접근
```

결국 저는 이 방법을 선택했어요. 간단한 블로그 빌드에는 이게 최적이더라구요.

**Jenkinsfile:**
```groovy
pipeline {
  agent any
  stages {
    stage('Build') {
      steps {
        sh '''
          docker run --rm \
            -v $PWD:/src \
            -v /var/www/blog:/target \
            klakegg/hugo:ext-alpine \
            hugo --destination /target --minify
        '''
      }
    }

    stage('Deploy') {
      steps {
        sh '''
          sudo chown -R www-data:www-data /var/www/blog
          sudo systemctl reload nginx
        '''
      }
    }
  }
}
```

---

### 시나리오 2: Tekton on Kubernetes

```yaml
# Tekton 설치
kubectl apply -f https://storage.googleapis.com/tekton-releases/pipeline/latest/release.yaml

# Hugo Pipeline 생성
kubectl apply -f hugo-pipeline.yaml

# Gitea Webhook 설정
# → Git push하면 자동 빌드
```

**장점:**
- ✅ Kubernetes Native
- ✅ 리소스 효율적
- ✅ GitOps 가능
- ✅ 권한 분리

**단점:**
- ❌ 학습 곡선
- ❌ Kubernetes 필요
- ❌ 간단한 블로그에는 오버엔지니어링?

나중에 Kubernetes를 완전히 이해하게 되면 Tekton으로 전환할 생각이에요.

---

## 7. 결론 및 추천

### Jenkins 컨테이너를 쓰지 말아야 할 이유

1. **Docker-in-Docker 지옥**
   - 보안 위험 (privileged 또는 소켓 마운트)
   - 복잡한 설정 (TLS 인증서, 네트워크)
   - 디버깅 어려움

2. **권한 문제의 늪**
   - UID/GID 불일치
   - 매번 chown 필요
   - root 권한 남용 유혹

3. **플러그인 지옥**
   - 의존성 충돌
   - 재시작 필요
   - 버전 관리 복잡

4. **영구 스토리지 복잡도**
   - 볼륨 관리
   - 백업/복구
   - 데이터 유실 위험

---

### Tekton의 장점

1. **Docker 데몬 불필요**
   - Kaniko, Buildah 사용
   - 보안 안전
   - privileged 불필요

2. **권한 분리**
   - Task마다 ServiceAccount
   - 최소 권한 원칙
   - Pod 격리

3. **플러그인 없음**
   - YAML로 정의
   - 재시작 불필요
   - Git으로 관리

4. **Kubernetes Native**
   - etcd에 상태 저장
   - 별도 볼륨 불필요
   - 리소스 효율적

---

### 내 선택: Jenkins 로컬 설치

**이유:**
- 블로그 빌드는 복잡하지 않음
- 컨테이너의 복잡도 회피
- 안정적 운영 중요
- sudo 권한 필요 (`/var/www/blog` 접근)

**Tekton을 선택해야 하는 경우:**
- 여러 마이크로서비스 빌드
- Kubernetes에 완전히 올인
- GitOps 방식 원함
- 리소스 효율 중요

---

## 8. 다음 단계

### Jenkins 로컬 설치 가이드

```bash
# 1. Java 설치
sudo apt install -y openjdk-17-jdk

# 2. Jenkins 설치
curl -fsSL https://pkg.jenkins.io/debian-stable/jenkins.io-2023.key | \
  sudo tee /usr/share/keyrings/jenkins-keyring.asc > /dev/null

echo deb [signed-by=/usr/share/keyrings/jenkins-keyring.asc] \
  https://pkg.jenkins.io/debian-stable binary/ | \
  sudo tee /etc/apt/sources.list.d/jenkins.list > /dev/null

sudo apt update
sudo apt install -y jenkins

# 3. 시작
sudo systemctl start jenkins
sudo systemctl enable jenkins

# 4. 초기 비밀번호
sudo cat /var/lib/jenkins/secrets/initialAdminPassword

# 5. 접속
# http://localhost:8080
```

### Tekton 체험하기

```bash
# 1. Tekton 설치
kubectl apply -f https://storage.googleapis.com/tekton-releases/pipeline/latest/release.yaml

# 2. Tekton Dashboard (웹 UI)
kubectl apply -f https://storage.googleapis.com/tekton-releases/dashboard/latest/release.yaml

# 3. Dashboard 접속
kubectl port-forward -n tekton-pipelines svc/tekton-dashboard 9097:9097

# 4. 브라우저
# http://localhost:9097
```

---

## 참고 자료

### 공식 문서
- [Tekton 공식 문서](https://tekton.dev/)
- [Jenkins 공식 문서](https://www.jenkins.io/doc/)
- [Kaniko GitHub](https://github.com/GoogleContainerTools/kaniko)

### 관련 글
- [Docker-in-Docker 보안 위험](https://jpetazzo.github.io/2015/09/03/do-not-use-docker-in-docker-for-ci/)
- [Kubernetes Native CI/CD](https://kubernetes.io/blog/2018/05/01/developing-on-kubernetes/)

### 내 환경
- Kubernetes v1.31.13 (3노드 클러스터)
- Longhorn 분산 스토리지
- Cilium CNI
- Hugo v0.146.0-extended

---

## 요약

| 항목 | Jenkins 컨테이너 | Jenkins 로컬 | Tekton |
|------|-----------------|-------------|---------|
| **복잡도** | ⚠️ 높음 | ✅ 낮음 | ⚠️ 중간 |
| **보안** | ❌ 위험 | ✅ 안전 | ✅ 매우 안전 |
| **리소스** | ❌ 많음 | ⚠️ 중간 | ✅ 적음 |
| **안정성** | ❌ 낮음 | ✅ 높음 | ✅ 높음 |
| **학습 곡선** | ⚠️ 중간 | ✅ 낮음 | ⚠️ 높음 |
| **추천** | ❌ | ✅ | ⚠️ |

**결론:**
- **블로그 용도** → Jenkins 로컬 설치
- **학습 목적** → Tekton 체험
- **프로덕션** → 요구사항에 따라 선택

---

*이 글은 실제 Hugo 블로그 CI/CD 구축 과정에서 겪은 경험을 바탕으로 작성되었습니다.*
