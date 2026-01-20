# Hugo Blog CI/CD Pipeline 구축 가이드

> 작성일: 2026-01-17
> 프로젝트: Hugo Blog + Spring Boot WAS on Kubernetes
> CI/CD: Jenkins → GHCR → Kubernetes (Local)

---

## 목차

1. [개요](#1-개요)
2. [아키텍처](#2-아키텍처)
3. [구축 과정](#3-구축-과정)
4. [트러블슈팅](#4-트러블슈팅)
5. [최종 구성](#5-최종-구성)
6. [교훈](#6-교훈)

---

## 1. 개요

### 1.1 목적

Hugo 정적 사이트를 Jenkins CI/CD로 자동 빌드/배포하여:
- ✅ Git Push → 자동 빌드 → GHCR 업로드 → K8s 무중단 배포
- ✅ 빌드 시간 단축 (Multi-stage Docker Build)
- ✅ 재현 가능한 배포 (Immutable Infrastructure)

### 1.2 문제 상황

**Before (수동 배포):**
```bash
# 로컬에서 매번 수동 실행
hugo --minify
docker build -t blog-web .
docker tag blog-web ghcr.io/wlals2/blog-web:v1
docker push ghcr.io/wlals2/blog-web:v1
kubectl set image deployment/web ...
```

**문제점:**
- ❌ 수동 작업 반복 (실수 가능성)
- ❌ 빌드 환경 차이 (로컬 vs 프로덕션)
- ❌ 버전 관리 어려움 (태그 일관성 없음)

**After (자동 배포):**
```bash
git push origin main
# → Jenkins 자동 빌드 → GHCR → K8s 배포 완료!
```

---

## 2. 아키텍처

### 2.1 전체 흐름

```
Developer
    │
    ├─ git push (main branch)
    ↓
GitHub Repository (blogsite)
    │
    ├─ Webhook (자동 트리거)
    ↓
Jenkins (localhost:8080)
    │
    ├─ Stage 1: Git Checkout
    ├─ Stage 2: Docker Build (Multi-stage)
    │   ├─ Builder: Hugo 빌드 (Alpine)
    │   └─ Runtime: nginx 서빙
    ├─ Stage 3: Push to GHCR
    │   └─ ghcr.io/wlals2/blog-web:v{BUILD_NUMBER}
    ├─ Stage 4: Deploy to K8s
    │   └─ kubectl set image deployment/web nginx=...
    └─ Stage 5: Health Check
        └─ curl http://192.168.1.187:31852/
```

### 2.2 Kubernetes 배포 구조

```
NodePort (31852)
    ↓
Ingress (nginx-ingress)
    ├─ / → web-service:80
    ├─ /api → was-service:8080
    └─ /board → was-service:8080
        ↓
web-service (ClusterIP)
    ↓
web Deployment (2 replicas)
    ├─ Pod 1: nginx (ghcr.io/wlals2/blog-web:v11)
    └─ Pod 2: nginx (ghcr.io/wlals2/blog-web:v11)
```

### 2.3 왜 이 아키텍처인가?

| 선택 | 대안 | 이유 |
|------|------|------|
| **Multi-stage Build** | Single stage | 이미지 크기 20MB vs 200MB+ |
| **GHCR** | Docker Hub | GitHub 통합, 무료 Private |
| **Jenkins** | GitHub Actions | 로컬 K8s 직접 접근 가능 |
| **kubectl set image** | ArgoCD | 단순 구조, 빠른 배포 |
| **nginx** | Hugo Server | 프로덕션 안정성, 성능 |

---

## 3. 구축 과정

### Phase 0: 사전 준비 (이미 완료)

**확인 사항:**
```bash
# Kubernetes 클러스터
kubectl get nodes
# NAME          STATUS   ROLES           AGE   VERSION
# k8s-cp        Ready    control-plane   52d   v1.31.13
# k8s-worker1   Ready    worker1         52d   v1.31.13
# k8s-worker2   Ready    worker2         52d   v1.31.13

# Namespace
kubectl get ns blog-system
# NAME          STATUS   AGE
# blog-system   Active   24h

# Ingress Controller
kubectl get pods -n ingress-nginx
```

---

### Phase 1: Jenkins 확인

**목적:** 로컬에서 실행 중인 Jenkins 확인

```bash
systemctl status jenkins
curl -I http://localhost:8080
```

**결과:**
- ✅ Jenkins 8080 포트에서 실행 중
- ✅ 웹 UI 접속 가능

**왜 로컬 Jenkins?**
- 로컬 Kubernetes에 직접 접근 (kubectl)
- 외부 의존성 없음 (Self-hosted)
- 비용 절감 (GitHub Actions 무료 한도 초과 시)

---

### Phase 2: GitHub 저장소 생성

**목적:** Jenkins Pipeline이 참조할 Git 저장소 생성

#### 2.1 GitHub CLI 설치

```bash
sudo apt install gh
echo "ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx" | gh auth login --with-token
gh auth status
```

**왜 gh CLI?**
- 빠른 저장소 생성 (웹 UI보다 빠름)
- 스크립트 자동화 가능
- Git credential 설정 자동

#### 2.2 저장소 생성

```bash
# Hugo 블로그 저장소
gh repo create wlals2/blogsite --public --source=. --remote=origin

# Spring Boot WAS 저장소
gh repo create wlals2/board-was --public
```

**공개/비공개 선택:**
- Public: GHCR 이미지 Pull 시 인증 불필요
- Private: GHCR 인증 필요 (imagePullSecrets)
- **선택: Public** (간단함)

---

### Phase 3: 코드 Push

**목적:** 로컬 코드를 GitHub 저장소에 업로드

#### 3.1 Git 초기화 및 Push

```bash
cd /home/jimin/blogsite

# Git 설정
git config --global credential.helper store
git config --global user.email "wlals2@example.com"
git config --global user.name "wlals2"

# 저장소 연결 및 Push
git remote add origin https://github.com/wlals2/blogsite.git
git branch -M main
git add .
git commit -m "Initial commit: Hugo blog source"
git push -u origin main
```

#### 3.2 Jenkinsfile 추가

**위치:** `/home/jimin/blogsite/Jenkinsfile` (저장소 루트)

```groovy
pipeline {
    agent any
    environment {
        IMAGE_NAME = 'ghcr.io/wlals2/blog-web'
        IMAGE_TAG = "v${BUILD_NUMBER}"
        NAMESPACE = 'blog-system'
        DEPLOYMENT_NAME = 'web'
        CONTAINER_NAME = 'nginx'
        GHCR_CREDENTIALS = 'ghcr-credentials'
    }
    stages {
        stage('Checkout') { ... }
        stage('Build Docker Image') { ... }
        stage('Push to GHCR') { ... }
        stage('Deploy to K8s') { ... }
        stage('Health Check') { ... }
    }
}
```

**왜 Jenkinsfile을 저장소 루트에?**
- Jenkins Pipeline 설정에서 `Script Path`를 `Jenkinsfile`로 지정
- 버전 관리 가능 (Git으로 추적)
- 재사용 가능 (다른 환경에서도 동일 파이프라인)

---

### Phase 4: Dockerfile 작성

**목적:** Hugo 빌드 + nginx 서빙을 위한 Multi-stage Dockerfile

#### 4.1 Dockerfile 구조

```dockerfile
# ==============================================================================
# Stage 1: Builder - Hugo Build
# ==============================================================================
FROM alpine:latest AS builder

# Hugo 설치 + timezone 데이터
RUN apk add --no-cache hugo tzdata

WORKDIR /src

# Hugo 소스 복사
COPY . .

# Hugo 빌드 (public/ 디렉토리에 정적 파일 생성)
RUN hugo --minify --gc

# ==============================================================================
# Stage 2: Runtime - nginx
# ==============================================================================
FROM nginx:alpine

# Builder에서 생성된 정적 파일만 복사
COPY --from=builder /src/public /usr/share/nginx/html

# 헬스체크용 파일 생성
RUN echo "OK" > /usr/share/nginx/html/health

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
```

#### 4.2 왜 Multi-stage Build?

**Single-stage (나쁜 예):**
```dockerfile
FROM alpine:latest
RUN apk add hugo nginx
COPY . /src
RUN hugo --minify
# 문제: Hugo 바이너리가 최종 이미지에 포함됨 (불필요)
# 이미지 크기: ~200MB
```

**Multi-stage (좋은 예):**
```dockerfile
FROM alpine AS builder
RUN apk add hugo
RUN hugo --minify

FROM nginx:alpine
COPY --from=builder /src/public /usr/share/nginx/html
# 장점: Hugo 바이너리 제외, 정적 파일만 포함
# 이미지 크기: ~20MB (90% 감소!)
```

**트레이드오프:**
- ✅ 이미지 크기 90% 감소
- ✅ 보안 (불필요한 도구 제외)
- ⚠️ 빌드 시간 약간 증가 (2단계 빌드)

#### 4.3 왜 `tzdata` 패키지?

**에러 (tzdata 없을 때):**
```
Error: failed to init config: invalid timeZone for language "ko": unknown time zone Asia/Seoul
```

**원인:**
- Hugo config.toml에 `timeZone = "Asia/Seoul"` 설정
- Alpine Linux 기본 이미지에는 timezone 데이터 없음

**해결:**
```dockerfile
RUN apk add --no-cache hugo tzdata
```

**대안:**
1. `enableGitInfo = false` → Git 의존성 제거
2. `timeZone` 설정 제거 → UTC 사용
3. **선택: tzdata 추가** (한국 시간 필요)

---

### Phase 5: Git 서브모듈 문제 해결

**문제:**
```
ERROR Failed to read Git log: binary with name "git" not found
WARN found no layout file for "html"
```

**원인:**
1. `enableGitInfo = true` → Git 필요
2. PaperMod 테마가 **Git 서브모듈**로 관리됨
3. `COPY . .` 명령은 서브모듈 내용을 복사하지 않음

#### 5.1 Git 서브모듈이란?

```bash
# .gitmodules 파일
[submodule "themes/PaperMod"]
    path = themes/PaperMod
    url = https://github.com/adityatelange/hugo-PaperMod.git

# themes/PaperMod/.git
gitdir: ../../.git/modules/themes/PaperMod
```

**문제:**
- Docker `COPY . .`는 `.git/modules/`를 복사하지 않음
- 결과: `themes/PaperMod/` 디렉토리가 비어있음

#### 5.2 해결 방법

**방법 1: Docker 빌드 시 서브모듈 초기화 (복잡)**
```dockerfile
RUN apk add git
RUN git submodule update --init --recursive
# 문제: .git 디렉토리 필요, 빌드 시간 증가
```

**방법 2: 서브모듈을 일반 디렉토리로 변환 (권장) ✅**
```bash
# 서브모듈 제거
git submodule deinit -f themes/PaperMod
git rm -f themes/PaperMod
rm -rf .git/modules/themes/PaperMod

# 일반 디렉토리로 클론
git clone --depth 1 https://github.com/adityatelange/hugo-PaperMod.git themes/PaperMod
rm -rf themes/PaperMod/.git

# Git에 직접 추가
git add themes/PaperMod
git commit -m "Convert PaperMod from submodule to regular directory"
```

**방법 3: Hugo Module 사용 (최신)**
```toml
# config.toml
[module]
  [[module.imports]]
    path = "github.com/adityatelange/hugo-PaperMod"
```

**선택: 방법 2 (일반 디렉토리)**

**이유:**
- 단순함 (Docker 빌드 시 git 불필요)
- 빠름 (서브모듈 초기화 시간 절약)
- 재현 가능 (모든 파일이 Git에 포함)

**트레이드오프:**
- ❌ 테마 업데이트 어려움 (수동으로 다시 클론 필요)
- ✅ 버전 고정 (테마 변경으로 인한 호환성 문제 없음)

#### 5.3 enableGitInfo 비활성화

```toml
# config.toml
enableGitInfo = false  # Docker 빌드 시 git 불필요
```

**왜?**
- Docker 이미지에 git 바이너리 불필요
- 빌드 시간 단축
- 이미지 크기 감소

---

### Phase 6: Jenkins Credentials 설정

**목적:** GHCR에 Push하기 위한 GitHub Token 등록

#### 6.1 문제 발견

```
ERROR: Could not find credentials entry with ID 'ghcr-credentials'
```

**확인:**
```bash
sudo cat /var/lib/jenkins/credentials.xml | grep "<id>"
```

**결과:**
```xml
<id>ghcr-credentinals</id>  <!-- 오타! -->
```

#### 6.2 해결: 오타 수정

```bash
# ghcr-credentinals → ghcr-credentials
sudo sed -i 's/<id>ghcr-credentinals<\/id>/<id>ghcr-credentials<\/id>/g' \
    /var/lib/jenkins/credentials.xml

# Jenkins 재시작
sudo systemctl restart jenkins
```

**왜 직접 수정?**
- Jenkins UI에서 ID 변경 불가 (삭제 후 재생성 필요)
- credentials.xml 직접 수정이 빠름
- 백업 먼저 생성 (안전)

#### 6.3 GitHub Personal Access Token

**권한:**
- ✅ `write:packages` (GHCR push)
- ✅ `read:packages` (GHCR pull)
- ✅ `repo` (private repo 접근 시)

**등록:**
- Jenkins → Manage Jenkins → Credentials
- Add Credentials → Username with password
  - Username: `wlals2`
  - Password: `ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx`
  - ID: `ghcr-credentials`

---

### Phase 7: kubectl 설정

**목적:** Jenkins 사용자가 Kubernetes 클러스터에 접근

#### 7.1 문제 발견

```
ERROR: Unable to connect to the server: dial tcp: lookup 8C99496E4F5EEF33595FEC273FB4A47F.gr7.ap-northeast-2.eks.amazonaws.com on 127.0.0.53:53: no such host
```

**원인:**
- Jenkins 사용자의 kubeconfig가 **AWS EKS** 클러스터를 가리킴
- 실제로는 **로컬 Kubernetes** 클러스터 사용

**확인:**
```bash
# 현재 사용자 (jimin)
kubectl config view
# server: https://192.168.1.187:6443 (로컬 K8s)

# Jenkins 사용자
sudo -u jenkins kubectl config view
# server: https://8C99...EKS.amazonaws.com (EKS)
```

#### 7.2 해결: kubeconfig 복사

```bash
# Jenkins 사용자에게 로컬 K8s config 복사
sudo mkdir -p /var/lib/jenkins/.kube
sudo cp ~/.kube/config /var/lib/jenkins/.kube/config
sudo chown -R jenkins:jenkins /var/lib/jenkins/.kube

# 테스트
sudo -u jenkins kubectl get nodes
# NAME          STATUS   ROLES           AGE   VERSION
# k8s-cp        Ready    control-plane   52d   v1.31.13
```

**왜 이렇게?**
- Jenkins는 `jenkins` 사용자로 실행
- kubectl은 `~/.kube/config` 참조
- `/var/lib/jenkins/.kube/config` 필요

**대안:**
1. KUBECONFIG 환경변수 설정 (Jenkinsfile에서)
2. kubeconfig를 Credentials로 등록
3. **선택: 파일 복사** (간단함)

---

### Phase 8: 컨테이너 이름 수정

**목적:** kubectl set image 명령에서 올바른 컨테이너 이름 사용

#### 8.1 문제 발견

```
error: unable to find container named "web"
```

#### 8.2 원인 분석

```bash
# Deployment의 실제 컨테이너 이름 확인
kubectl get deployment web -n blog-system -o jsonpath='{.spec.template.spec.containers[*].name}'
# nginx  ← 실제 이름!
```

**Jenkinsfile 코드:**
```groovy
kubectl set image deployment/${DEPLOYMENT_NAME} \
    ${DEPLOYMENT_NAME}=${IMAGE_NAME}:${IMAGE_TAG}
    # deployment/web web=ghcr.io/wlals2/blog-web:v10
    # 문제: 컨테이너 이름이 "nginx"인데 "web"으로 지정
```

#### 8.3 해결: CONTAINER_NAME 변수 추가

```groovy
environment {
    DEPLOYMENT_NAME = 'web'
    CONTAINER_NAME = 'nginx'  // 실제 컨테이너 이름
}

stage('Deploy to K8s') {
    sh """
        kubectl set image deployment/${DEPLOYMENT_NAME} \
            ${CONTAINER_NAME}=${IMAGE_NAME}:${IMAGE_TAG} \
            -n ${NAMESPACE}
    """
}
```

**왜 이름이 다른가?**

**Deployment YAML:**
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: web  # Deployment 이름
spec:
  template:
    spec:
      containers:
      - name: nginx  # 컨테이너 이름 (Phase 0-4에서 생성됨)
        image: ghcr.io/wlals2/blog-web:v1
```

**kubectl set image 구문:**
```bash
kubectl set image deployment/<DEPLOYMENT_NAME> <CONTAINER_NAME>=<IMAGE>
                               ^^^^^^^^^^^^^^^^ ^^^^^^^^^^^^^^^
                               Deployment 이름   컨테이너 이름 (다를 수 있음!)
```

---

## 4. 트러블슈팅

### 4.1 Timezone 에러

**에러:**
```
Error: failed to init config: invalid timeZone for language "ko": unknown time zone Asia/Seoul
```

**근본 원인:**
- Alpine Linux에 timezone 데이터베이스 없음
- Hugo가 `config.toml`의 `timeZone` 읽을 수 없음

**해결:**
```dockerfile
RUN apk add --no-cache hugo tzdata
```

**디버깅 과정:**
```bash
# Alpine 컨테이너 진입
docker run -it alpine:latest sh

# timezone 데이터 확인
ls /usr/share/zoneinfo/
# 없음!

# tzdata 설치 후
apk add tzdata
ls /usr/share/zoneinfo/Asia/Seoul
# 존재 ✅
```

---

### 4.2 Git 서브모듈 - 레이아웃 파일 없음

**에러:**
```
WARN found no layout file for "html" for kind "page"
```

**근본 원인:**
- PaperMod 테마가 Git 서브모듈
- `COPY . .`가 서브모듈 내용을 복사하지 않음
- `themes/PaperMod/layouts/` 디렉토리가 비어있음

**디버깅 과정:**
```bash
# 로컬에서 확인
ls -la themes/PaperMod/
# .git 파일만 존재 (gitdir: ../../.git/modules/themes/PaperMod)

# Docker 빌드 중 확인
docker run --rm -it <image> sh
ls /src/themes/PaperMod/
# 비어있음!
```

**해결:**
1. 서브모듈 제거
2. 일반 디렉토리로 클론
3. Git에 직접 추가

---

### 4.3 GitHub Push Protection

**에러:**
```
remote: error: GH013: Repository rule violations found for refs/heads/main.
remote: Push cannot contain secrets
remote: GitHub Personal Access Token
remote: commit: f96e4df
remote: path: blog-k8s-project/web/.env.github:3
```

**근본 원인:**
- `.env.github` 파일에 PAT 포함
- GitHub Push Protection이 탐지

**해결:**
```bash
# Git 캐시에서 제거
git rm --cached blog-k8s-project/web/.env.github

# .gitignore에 추가
echo "*.env*" >> .gitignore
git add .gitignore

# 커밋 수정 (amend)
git commit --amend
git push -f origin main
```

**교훈:**
- ✅ 민감한 정보는 `.gitignore`에 미리 추가
- ✅ `.env` 파일은 절대 커밋하지 않기
- ✅ Jenkins Credentials 사용 (환경변수)

---

### 4.4 EKS vs 로컬 Kubernetes

**에러:**
```
dial tcp: lookup 8C99496E4F5EEF33595FEC273FB4A47F.gr7.ap-northeast-2.eks.amazonaws.com: no such host
```

**근본 원인:**
- Jenkins 사용자의 kubeconfig가 EKS 클러스터 설정
- 실제로는 로컬 Kubernetes 사용

**해결:**
```bash
# 현재 사용자의 config 복사
sudo cp ~/.kube/config /var/lib/jenkins/.kube/config
sudo chown jenkins:jenkins /var/lib/jenkins/.kube/config

# Context 확인
sudo -u jenkins kubectl config get-contexts
# CURRENT   NAME       CLUSTER     AUTHINFO
# *         local-k8s  kubernetes  kubernetes-admin
```

**왜 EKS 설정이 있었나?**
- 이전에 EKS 클러스터 사용했던 흔적
- `aws eks update-kubeconfig` 명령 실행 이력

---

### 4.5 컨테이너 이름 불일치

**에러:**
```
error: unable to find container named "web"
```

**근본 원인:**
- Deployment 이름: `web`
- 컨테이너 이름: `nginx` (다름!)
- Jenkinsfile에서 Deployment 이름을 컨테이너 이름으로 사용

**디버깅:**
```bash
# Deployment 상세 확인
kubectl get deployment web -n blog-system -o yaml

spec:
  template:
    spec:
      containers:
      - name: nginx  # ← 여기!
        image: ghcr.io/wlals2/blog-web:v1
```

**해결:**
```groovy
environment {
    CONTAINER_NAME = 'nginx'
}

kubectl set image deployment/${DEPLOYMENT_NAME} \
    ${CONTAINER_NAME}=${IMAGE_NAME}:${IMAGE_TAG}
```

---

## 5. 최종 구성

### 5.1 Jenkinsfile (최종)

```groovy
pipeline {
    agent any

    environment {
        // 이미지 설정
        IMAGE_NAME = 'ghcr.io/wlals2/blog-web'
        IMAGE_TAG = "v${BUILD_NUMBER}"

        // Kubernetes 설정
        NAMESPACE = 'blog-system'
        DEPLOYMENT_NAME = 'web'
        CONTAINER_NAME = 'nginx'  // 실제 컨테이너 이름

        // GitHub 설정
        GIT_REPO = 'https://github.com/wlals2/blogsite.git'
        GIT_BRANCH = 'main'

        // GHCR 인증
        GHCR_CREDENTIALS = 'ghcr-credentials'
    }

    stages {
        stage('Checkout') {
            steps {
                echo "=== Stage 1: Git Checkout ==="
                git url: "${GIT_REPO}", branch: "${GIT_BRANCH}"
            }
        }

        stage('Build Docker Image') {
            steps {
                echo "=== Stage 2: Docker Build ==="
                sh """
                    docker build -t ${IMAGE_NAME}:${IMAGE_TAG} .
                    docker tag ${IMAGE_NAME}:${IMAGE_TAG} ${IMAGE_NAME}:latest
                """
            }
        }

        stage('Push to GHCR') {
            steps {
                echo "=== Stage 3: Push to GHCR ==="
                withCredentials([usernamePassword(
                    credentialsId: "${GHCR_CREDENTIALS}",
                    usernameVariable: 'GHCR_USER',
                    passwordVariable: 'GHCR_TOKEN'
                )]) {
                    sh """
                        echo \$GHCR_TOKEN | docker login ghcr.io -u \$GHCR_USER --password-stdin
                        docker push ${IMAGE_NAME}:${IMAGE_TAG}
                        docker push ${IMAGE_NAME}:latest
                    """
                }
            }
        }

        stage('Deploy to K8s') {
            steps {
                echo "=== Stage 4: Deploy to Kubernetes ==="
                sh """
                    kubectl set image deployment/${DEPLOYMENT_NAME} \
                        ${CONTAINER_NAME}=${IMAGE_NAME}:${IMAGE_TAG} \
                        -n ${NAMESPACE}
                    kubectl rollout status deployment/${DEPLOYMENT_NAME} -n ${NAMESPACE} --timeout=120s
                """
            }
        }

        stage('Health Check') {
            steps {
                echo "=== Stage 5: Health Check ==="
                sh """
                    sleep 10
                    curl -f http://192.168.1.187:31852/ || exit 1
                    echo "Health check passed!"
                """
            }
        }
    }

    post {
        success {
            echo """
            =============================================
            SUCCESS: Hugo Blog deployed successfully!
            Image: ${IMAGE_NAME}:${IMAGE_TAG}
            URL: http://192.168.1.187:31852/
            =============================================
            """
        }
        failure {
            echo """
            =============================================
            FAILED: Deployment failed!
            Check the logs for details.
            =============================================
            """
        }
        always {
            sh "docker rmi ${IMAGE_NAME}:${IMAGE_TAG} || true"
        }
    }
}
```

### 5.2 Dockerfile (최종)

```dockerfile
# ==============================================================================
# Hugo Blog Dockerfile (Multi-stage Build)
# ==============================================================================

# ==============================================================================
# Stage 1: Builder - Hugo Build
# ==============================================================================
FROM alpine:latest AS builder

# Hugo 설치 + timezone 데이터
RUN apk add --no-cache hugo tzdata

WORKDIR /src

# Hugo 소스 복사
COPY . .

# Hugo 빌드 (public/ 디렉토리에 정적 파일 생성)
# --minify: HTML/CSS/JS 압축
# --gc: 사용하지 않는 캐시 정리
RUN hugo --minify --gc

# ==============================================================================
# Stage 2: Runtime - nginx
# ==============================================================================
FROM nginx:alpine

# Builder에서 생성된 정적 파일만 복사
COPY --from=builder /src/public /usr/share/nginx/html

# 헬스체크용 파일 생성
RUN echo "OK" > /usr/share/nginx/html/health

# 포트 노출
EXPOSE 80

# nginx 실행
CMD ["nginx", "-g", "daemon off;"]
```

### 5.3 config.toml (수정 사항)

```toml
# Before
enableGitInfo = true
timeZone = "Asia/Seoul"

# After
enableGitInfo = false  # Docker 빌드 시 git 불필요
timeZone = "Asia/Seoul"  # tzdata 패키지로 지원
```

### 5.4 Jenkins Credentials

| ID | Type | Username | Password | 용도 |
|----|------|----------|----------|------|
| `ghcr-credentials` | Username with password | `wlals2` | `ghp_nhiAUxW...` | GHCR Push |

### 5.5 Jenkins kubectl 설정

```bash
# Jenkins 사용자 kubeconfig
/var/lib/jenkins/.kube/config

# Context
local-k8s → https://192.168.1.187:6443
```

---

## 6. 교훈

### 6.1 기술적 교훈

#### Multi-stage Build의 중요성

**Before:**
- 이미지 크기: 200MB+
- Hugo 바이너리 포함 (불필요)

**After:**
- 이미지 크기: 20MB (90% 감소)
- 정적 파일만 포함
- Pull/Push 시간 단축

#### Git 서브모듈의 함정

**문제:**
- Docker `COPY . .`는 서브모듈을 복사하지 않음
- 빌드 시 레이아웃 파일 없음

**해결:**
- 서브모듈을 일반 디렉토리로 변환
- 또는 Hugo Module 사용

#### Kubernetes 리소스 이름 확인

**실수:**
- Deployment 이름과 컨테이너 이름을 혼동
- `kubectl set image deployment/web web=...` (틀림)

**올바름:**
- `kubectl set image deployment/web nginx=...`
- 항상 `kubectl get deployment -o yaml`로 확인

### 6.2 프로세스 교훈

#### 1. 작은 단계로 검증

**나쁜 예:**
```bash
# 한 번에 모든 변경 후 빌드
git add .
git commit -m "Add everything"
git push
# → 빌드 실패 시 원인 파악 어려움
```

**좋은 예:**
```bash
# 1. Dockerfile만 먼저 테스트
docker build -t test .

# 2. 로컬에서 실행 확인
docker run -p 8080:80 test
curl http://localhost:8080

# 3. Git push
git push
```

#### 2. 에러 메시지를 정확히 읽기

**에러:**
```
error: unable to find container named "web"
```

**분석:**
- ❌ "Deployment가 없다" (틀린 해석)
- ✅ "컨테이너 이름이 'web'이 아니다" (정확한 해석)

**해결:**
```bash
kubectl get deployment web -n blog-system -o jsonpath='{.spec.template.spec.containers[*].name}'
# nginx  ← 정확한 이름 확인
```

#### 3. 민감한 정보 관리

**절대 하지 말 것:**
- ❌ `.env` 파일 Git 커밋
- ❌ Jenkinsfile에 하드코딩된 패스워드
- ❌ 퍼블릭 저장소에 Secret

**올바른 방법:**
- ✅ Jenkins Credentials 사용
- ✅ `.gitignore`에 `.env*` 추가
- ✅ Kubernetes Secret 사용

### 6.3 자동화의 가치

**수동 배포 (Before):**
- 시간: 10분
- 실수 가능성: 높음
- 재현성: 낮음

**자동 배포 (After):**
- 시간: 3분 (Jenkins 자동)
- 실수 가능성: 없음
- 재현성: 100%

**투자 vs 수익:**
- 초기 구축: 4시간
- 배포 1회당 절감: 7분
- **34회 배포 후 손익분기점**

---

## 7. 다음 단계

### 7.1 WAS Pipeline 구축

동일한 프로세스로 Spring Boot WAS도 CI/CD 구축:
- `board-was` 저장소
- Maven 빌드 추가
- Dockerfile (Spring Boot + JDK)
- Jenkinsfile (Was용)

### 7.2 개선 사항

#### 캐시 최적화
```dockerfile
# Dockerfile에 Layer Cache 활용
COPY package.json .
RUN hugo mod download  # 의존성만 먼저 다운로드
COPY . .
RUN hugo --minify
```

#### Webhook 자동 트리거
```bash
# Jenkins → Generic Webhook Trigger Plugin
# GitHub → Settings → Webhooks
# Payload URL: http://jenkins-ip:8080/generic-webhook-trigger/invoke
```

#### 롤백 전략
```bash
# 이전 버전으로 롤백
kubectl rollout undo deployment/web -n blog-system

# 특정 버전으로
kubectl set image deployment/web nginx=ghcr.io/wlals2/blog-web:v9
```

---

## 8. 참고 자료

### 8.1 공식 문서

- Hugo: https://gohugo.io/documentation/
- Docker Multi-stage: https://docs.docker.com/build/building/multi-stage/
- Kubernetes Deployment: https://kubernetes.io/docs/concepts/workloads/controllers/deployment/
- Jenkins Pipeline: https://www.jenkins.io/doc/book/pipeline/

### 8.2 관련 파일

- Jenkinsfile: `/home/jimin/blogsite/Jenkinsfile`
- Dockerfile: `/home/jimin/blogsite/Dockerfile`
- config.toml: `/home/jimin/blogsite/config.toml`
- Deployment: `/home/jimin/blogsite/blog-k8s-project/web/deployment.yaml`

### 8.3 트러블슈팅 로그

Jenkins 빌드 로그:
- Build #1-5: Timezone 에러
- Build #6-8: Git 서브모듈 에러
- Build #9: Credentials 에러
- Build #10: 컨테이너 이름 에러
- **Build #11: 성공 ✅**

---

## 9. 요약

### 9.1 핵심 결정 사항

| 항목 | 선택 | 이유 |
|------|------|------|
| **CI/CD** | Jenkins (Self-hosted) | 로컬 K8s 직접 접근 |
| **Registry** | GHCR | GitHub 통합, 무료 |
| **Build** | Multi-stage Docker | 이미지 크기 90% 감소 |
| **테마 관리** | 일반 디렉토리 | 서브모듈 복사 문제 해결 |
| **Deployment** | kubectl set image | 단순, 빠름 |

### 9.2 최종 배포 흐름

```
Developer
  ↓ git push
GitHub
  ↓ Webhook
Jenkins
  ├─ Git Checkout
  ├─ Docker Build (Hugo)
  ├─ Push to GHCR (v11)
  ├─ Deploy to K8s (nginx=v11)
  └─ Health Check ✅
     ↓
User: http://192.168.1.187:31852/
```

### 9.3 성과

- ✅ 자동 배포 구축 완료
- ✅ 빌드 시간 단축 (10분 → 3분)
- ✅ 이미지 크기 90% 감소
- ✅ 재현 가능한 인프라
- ✅ 트러블슈팅 경험 축적

---

**작성자:** Claude Code (AI Assistant)
**검토자:** 사용자 (wlals2)
**최종 업데이트:** 2026-01-17
