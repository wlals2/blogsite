# CI/CD 통합 가이드

> Hugo 블로그 자동 배포: GitHub Actions + ArgoCD + Jenkins
> 최종 업데이트: 2026-01-20

---

## 목차

1. [개요](#개요)
2. [GitHub Actions CI/CD](#github-actions-cicd)
3. [Jenkins CI/CD](#jenkins-cicd)
4. [ArgoCD (GitOps CD)](#argocd-gitops-cd)
5. [비교 및 선택 가이드](#비교-및-선택-가이드)
6. [배포 플로우 검증](#배포-플로우-검증)

---

## 개요

### 목적

Hugo 정적 블로그와 Spring Boot WAS를 Kubernetes에 자동 배포하는 CI/CD 시스템 구축

### 아키텍처

#### 현재 운영 중 (CI/CD 분리)

```
[ CI: GitHub Actions ]
Developer
    │
    ├─ git push (main)
    ↓
GitHub Actions (Self-hosted)
    │
    ├─ Docker Build
    ├─ Push to ghcr.io/wlals2/blog-web:v{VERSION}
    └─ kubectl set image (직접 배포)
        ↓
Kubernetes Cluster
    └─ Deployments 업데이트

[ CD: ArgoCD (구축 중) ]
Developer
    │
    ├─ git push (manifests repo)
    ↓
ArgoCD (Pull 모델)
    │
    ├─ Git Poll (3초)
    ├─ Desired State 비교
    └─ Auto Sync
        ↓
Kubernetes Cluster
    └─ Deployments 자동 배포
```

#### 최종 목표 아키텍처 (GitOps)

```
Developer
    │
    ├─ git push (코드)
    ↓
┌─────────────────────────────────────────┐
│ CI: GitHub Actions (이미지 빌드만)      │
│  1. Docker Build                        │
│  2. Push to ghcr.io                     │
│  3. manifestrepo 업데이트 (image tag)   │
└─────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────┐
│ CD: ArgoCD (Git → K8s 자동 동기화)      │
│  1. manifestrepo Poll (3초)             │
│  2. Desired State 확인                  │
│  3. kubectl apply (자동)                │
└─────────────────────────────────────────┘
    ↓
Kubernetes Cluster (192.168.1.187)
    │
    ├─ Namespace: blog-system, argocd
    ├─ Deployments: web (nginx), was (spring-boot)
    ├─ Services: web-service:80, was-service:8080
    └─ Ingress: blog.jiminhome.shop
        ↓
Cloudflare CDN
    └─ https://blog.jiminhome.shop/
```

**핵심 차이점**:
- **현재 (Push)**: GitHub Actions가 kubectl로 직접 배포
- **목표 (Pull)**: ArgoCD가 Git 감시 → 자동 배포

---

## GitHub Actions CI/CD

### 개념

**GitHub Actions**는 GitHub 저장소에서 발생하는 이벤트(push, PR)에 반응하여 자동으로 작업을 실행하는 CI/CD 도구입니다.

```
코드 Push → GitHub → Actions 트리거 → 빌드 → 배포 → 완료
```

### 왜 GitHub Actions를 사용하는가?

**수동 배포의 문제:**
```bash
# 매번 반복해야 하는 작업
cd ~/blogsite
git pull
hugo --minify
docker build -t blog-web .
docker push ghcr.io/wlals2/blog-web:v1
kubectl set image deployment/web ...
```

**GitHub Actions 도입 후:**
```bash
git push  # 끝! 나머지는 자동
```

**장점:**
- ✅ Git Push 즉시 빌드 (0초 지연)
- ✅ GitHub에서 빌드 로그 확인
- ✅ 클라우드 CI/CD 경험
- ✅ 설정 파일이 Git에 포함 (버전 관리)
- ✅ Self-hosted runner 사용 시 무제한 빌드 시간

---

### GitHub-Hosted vs Self-Hosted Runner

#### 비교표

| 구분 | GitHub-Hosted | Self-Hosted (현재) |
|------|---------------|-------------------|
| **실행 위치** | GitHub 클라우드 | k8s-cp 노드 |
| **비용** | 무료 2,000분/월 | 무제한 (내 서버) |
| **속도** | 느림 (VM 부팅 20초) | 빠름 (0초) |
| **네트워크** | 퍼블릭 IP만 접근 | 사설 네트워크 접근 가능 |
| **배포** | SSH 필요 | 로컬 접근 |
| **캐시** | GitHub 캐시 | 로컬 영구 캐시 |

#### 배포 시간 비교

| 단계 | GitHub-Hosted | Self-Hosted | 개선 |
|------|---------------|-------------|------|
| VM 부팅 | ~20초 | 0초 | **-20초** |
| Checkout | ~15초 | ~5초 | **-10초** |
| Hugo 빌드 | ~40초 | ~5초 | **-35초** |
| 배포 | ~15초 | ~5초 | **-10초** |
| **총 시간** | **90초** | **35초** | **-55초 (61% 개선)** |

#### 왜 Self-Hosted를 선택했는가?

**핵심 이유: 네트워크 접근성**

```
[ GitHub 클라우드 ]                  [ 홈 네트워크 ]
  ubuntu-latest runner   ❌ 접근 불가   192.168.1.187 (k8s-cp)
  (퍼블릭 IP)                          (RFC 1918 사설 IP)
```

GitHub-hosted runner는 사설 IP 범위(192.168.0.0/16)에 접근할 수 없습니다. Kubernetes API 서버가 192.168.1.187:6443에 있으므로 Self-hosted runner가 필수입니다.

**추가 이점:**
- ✅ 배포 속도 61% 개선
- ✅ 로컬 캐시 활용 (Hugo 리소스 캐시)
- ✅ 무제한 빌드 시간
- ✅ 비용 $0

---

### WEB 배포 워크플로우

#### 파일 위치
`.github/workflows/deploy-web.yml`

#### 전체 플로우

```yaml
name: Deploy WEB to Kubernetes

on:
  push:
    branches: [ main ]
  workflow_dispatch:

env:
  IMAGE_NAME: ghcr.io/wlals2/blog-web
  NAMESPACE: blog-system
  DEPLOYMENT_NAME: web

jobs:
  build-and-deploy:
    runs-on: self-hosted

    steps:
      # 1. Git 코드 체크아웃
      - name: Checkout code
        uses: actions/checkout@v4

      # 2. Docker Buildx 설정 (캐시 활용)
      - name: Set up Docker Buildx
        uses: docker/setup-buildx-action@v3

      # 3. GHCR 로그인
      - name: Login to GHCR
        uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GHCR_TOKEN }}

      # 4. Docker 이미지 빌드 및 푸시
      - name: Build and push Docker image
        uses: docker/build-push-action@v5
        with:
          context: .
          file: ./Dockerfile
          push: true
          tags: |
            ${{ env.IMAGE_NAME }}:v${{ github.run_number }}
            ${{ env.IMAGE_NAME }}:latest
          cache-from: type=gha
          cache-to: type=gha,mode=max

      # 5. Kubernetes 배포
      # Note: Self-hosted runner는 이미 ~/.kube/config를 가지고 있음
      - name: Deploy to Kubernetes
        run: |
          kubectl set image deployment/${{ env.DEPLOYMENT_NAME }} \
            nginx=${{ env.IMAGE_NAME }}:v${{ github.run_number }} \
            -n ${{ env.NAMESPACE }}

          kubectl rollout status deployment/${{ env.DEPLOYMENT_NAME }} \
            -n ${{ env.NAMESPACE }} \
            --timeout=120s

      # 6. Health Check
      - name: Health Check
        run: |
          sleep 10
          kubectl get deployment ${{ env.DEPLOYMENT_NAME }} -n ${{ env.NAMESPACE }}
          kubectl get pods -n ${{ env.NAMESPACE }} -l app=web

      # 7. Cloudflare 캐시 퍼지
      - name: Purge Cloudflare Cache
        if: success()
        run: |
          curl -X POST "https://api.cloudflare.com/client/v4/zones/${{ secrets.CLOUDFLARE_ZONE_ID }}/purge_cache" \
            -H "Authorization: Bearer ${{ secrets.CLOUDFLARE_API_TOKEN }}" \
            -H "Content-Type: application/json" \
            --data '{"purge_everything":true}'
          echo "Cloudflare cache purged successfully"

      # 8. 빌드 정보 출력
      - name: Build Summary
        if: success()
        run: |
          echo "### ✅ Deployment Successful!" >> $GITHUB_STEP_SUMMARY
          echo "" >> $GITHUB_STEP_SUMMARY
          echo "- **Image**: ${{ env.IMAGE_NAME }}:v${{ github.run_number }}" >> $GITHUB_STEP_SUMMARY
          echo "- **Namespace**: ${{ env.NAMESPACE }}" >> $GITHUB_STEP_SUMMARY
          echo "- **Deployment**: ${{ env.DEPLOYMENT_NAME }}" >> $GITHUB_STEP_SUMMARY
          echo "- **URL**: https://blog.jiminhome.shop/" >> $GITHUB_STEP_SUMMARY
          echo "- **Cloudflare Cache**: Purged ✅" >> $GITHUB_STEP_SUMMARY
```

#### 각 단계 설명

**1. Checkout Code**
```yaml
- uses: actions/checkout@v4
```
- Git 저장소 코드를 러너의 워킹 디렉터리로 클론
- 경로: `~/actions-runner/_work/blogsite/blogsite/`

**2. Docker Buildx**
```yaml
- uses: docker/setup-buildx-action@v3
```
- 멀티 플랫폼 빌드 지원
- BuildKit 활성화 (빌드 성능 향상)
- GitHub Actions 캐시 통합

**3. GHCR 로그인**
```yaml
- uses: docker/login-action@v3
  with:
    registry: ghcr.io
    password: ${{ secrets.GHCR_TOKEN }}
```
- GitHub Container Registry 인증
- `GHCR_TOKEN`: Personal Access Token (write:packages 권한)

**4. Docker Build & Push**
```yaml
cache-from: type=gha
cache-to: type=gha,mode=max
```
- **Multi-stage build**로 이미지 크기 최소화 (Hugo builder + nginx)
- GitHub Actions 캐시로 레이어 재사용 (빌드 시간 단축)
- 2개 태그: `v{run_number}`, `latest`

**5. Kubernetes 배포**
```yaml
kubectl set image deployment/web nginx=...
```
- **왜 kubeconfig 설정 안 하는가?**
  - Self-hosted runner는 k8s-cp 노드에서 실행
  - 이미 `~/.kube/config`에 유효한 인증 정보 보유
  - 덮어쓰면 오히려 인증 실패 발생 (트러블슈팅 참조)

**6. Health Check**
```bash
kubectl get pods -n blog-system -l app=web
```
- 배포 후 Pod 상태 확인
- `Running` 상태 및 `READY` 컬럼 검증

**7. Cloudflare 캐시 퍼지**
```bash
curl -X POST ".../purge_cache" --data '{"purge_everything":true}'
```
- 배포 후 CDN 캐시 즉시 삭제
- 사용자에게 최신 콘텐츠 제공
- Zone ID: `7895fe2aef761351db71892fb7c22b52`

---

### WAS 배포 워크플로우

#### 파일 위치
`.github/workflows/deploy-was.yml`

#### WEB과의 차이점

**1. 트리거 조건**
```yaml
on:
  workflow_dispatch:  # 수동 실행 (WAS 코드는 Git에 없음)
  push:
    branches: [ main ]
    paths:
      - '.github/workflows/deploy-was.yml'  # 워크플로우 파일만
```

**왜 다른가?**
- WAS 소스코드는 `.gitignore`에 포함 (민감 정보)
- Git push로 트리거하면 소스 없어서 빌드 실패
- 워크플로우 파일 변경 시만 자동 실행

**2. WAS 소스 복사**
```yaml
- name: Copy WAS source code
  run: |
    cp -r ~/blogsite/blog-k8s-project/was ./blog-k8s-project/
    ls -la ./blog-k8s-project/was/
```

**왜 필요한가?**
- WAS 소스는 로컬에만 존재: `~/blogsite/blog-k8s-project/was/`
- `actions/checkout`은 Git 저장소만 클론 → WAS 소스 없음
- 빌드 전 로컬에서 수동 복사 필요

**디렉터리 구조:**
```
~/blogsite/                          # 실제 소스
├── blog-k8s-project/was/  ✅

~/actions-runner/_work/blogsite/blogsite/  # 워크플로우 워킹 디렉터리
├── blog-k8s-project/was/  ❌ (복사 전)
```

**3. Docker 빌드 컨텍스트**
```yaml
context: ./blog-k8s-project/was
file: ./blog-k8s-project/was/Dockerfile
```

**4. 컨테이너 이름**
```yaml
kubectl set image deployment/was \
  spring-boot=${{ env.IMAGE_NAME }}:v${{ github.run_number }}
```
- WEB: `nginx` 컨테이너
- WAS: `spring-boot` 컨테이너

---

### GitHub Secrets 설정

#### 필요한 Secrets (4개)

| Secret | 용도 | 생성 방법 |
|--------|------|----------|
| **GHCR_TOKEN** | Docker 이미지 Push | GitHub PAT (write:packages) |
| **KUBECONFIG_BASE64** | Kubernetes 배포 | (사용 안 함 - Self-hosted에 이미 존재) |
| **CLOUDFLARE_ZONE_ID** | 캐시 퍼지 | `7895fe2aef761351db71892fb7c22b52` |
| **CLOUDFLARE_API_TOKEN** | 캐시 퍼지 | Cloudflare API Token |

#### 1. GHCR_TOKEN 생성

**절차:**
1. https://github.com/settings/tokens
2. **Generate new token (classic)**
3. 설정:
   - Note: `ghcr-actions-blog`
   - Scopes: ✅ `write:packages`, ✅ `read:packages`
4. Token 복사

**등록:**
```
Repository Settings → Secrets → Actions → New repository secret
Name: GHCR_TOKEN
Value: ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

#### 2. CLOUDFLARE_ZONE_ID 조회

**API로 조회:**
```bash
curl -X GET "https://api.cloudflare.com/client/v4/zones" \
  -H "Authorization: Bearer <CLOUDFLARE_API_TOKEN>" \
  | jq -r '.result[] | select(.name=="jiminhome.shop") | .id'

# 출력: 7895fe2aef761351db71892fb7c22b52
```

**대시보드:**
1. Cloudflare 로그인
2. jiminhome.shop 선택
3. 오른쪽 사이드바 하단 "Zone ID" 복사

#### 3. CLOUDFLARE_API_TOKEN 생성

**절차:**
1. Cloudflare 대시보드 → API Tokens
2. **Create Token**
3. Template: "Edit zone DNS"
4. Permissions:
   - Zone - Cache Purge - Purge
5. Zone Resources:
   - Include - Specific zone - jiminhome.shop

---

### Self-Hosted Runner 설정

#### 설치

**k8s-cp 노드에서:**
```bash
# 1. 러너 다운로드
mkdir ~/actions-runner && cd ~/actions-runner
curl -o actions-runner-linux-x64-2.311.0.tar.gz \
  -L https://github.com/actions/runner/releases/download/v2.311.0/actions-runner-linux-x64-2.311.0.tar.gz
tar xzf ./actions-runner-linux-x64-2.311.0.tar.gz

# 2. 등록 (GitHub에서 토큰 발급)
# Repository Settings → Actions → Runners → New self-hosted runner
./config.sh --url https://github.com/wlals2/blogsite --token <TOKEN>

# 3. 서비스 등록
sudo ./svc.sh install
sudo ./svc.sh start

# 4. 상태 확인
sudo ./svc.sh status
```

#### 확인

```bash
# 러너 설정 파일
cat .runner | jq
# {
#   "gitHubUrl": "https://github.com/wlals2/blogsite",
#   "agentName": "k8s-cp"
# }

# 로그 모니터링
tail -f _diag/Worker_*.log
```

---

## Jenkins CI/CD

### 개요

**Jenkins**는 로컬 서버에서 실행되는 오픈소스 CI/CD 도구입니다.

**왜 Jenkins도 유지하는가?**
- ✅ GitHub Actions 백업 (장애 대비)
- ✅ 복잡한 파이프라인 구성 가능
- ✅ 로컬 Kubernetes 직접 접근
- ✅ 플러그인 생태계

### 아키텍처

```
GitHub Repository
    │
    ├─ Webhook (Push 이벤트)
    ↓
Jenkins (localhost:8080)
    │
    ├─ Stage 1: Git Checkout
    ├─ Stage 2: Docker Build (Multi-stage)
    ├─ Stage 3: Push to GHCR
    ├─ Stage 4: Deploy to K8s (kubectl)
    └─ Stage 5: Health Check
        ↓
Kubernetes Cluster
    └─ blog-system namespace
```

### Jenkinsfile (WEB)

```groovy
pipeline {
    agent any

    environment {
        GHCR_REPO = 'ghcr.io/wlals2/blog-web'
        K8S_NAMESPACE = 'blog-system'
        DEPLOYMENT_NAME = 'web'
    }

    stages {
        stage('Checkout') {
            steps {
                git branch: 'main',
                    url: 'https://github.com/wlals2/blogsite.git'
            }
        }

        stage('Build Docker Image') {
            steps {
                script {
                    sh """
                        docker build -t ${GHCR_REPO}:v${BUILD_NUMBER} .
                        docker tag ${GHCR_REPO}:v${BUILD_NUMBER} ${GHCR_REPO}:latest
                    """
                }
            }
        }

        stage('Push to GHCR') {
            steps {
                withCredentials([usernamePassword(
                    credentialsId: 'ghcr-token',
                    usernameVariable: 'GHCR_USER',
                    passwordVariable: 'GHCR_TOKEN'
                )]) {
                    sh """
                        echo \$GHCR_TOKEN | docker login ghcr.io -u \$GHCR_USER --password-stdin
                        docker push ${GHCR_REPO}:v${BUILD_NUMBER}
                        docker push ${GHCR_REPO}:latest
                    """
                }
            }
        }

        stage('Deploy to Kubernetes') {
            steps {
                sh """
                    kubectl set image deployment/${DEPLOYMENT_NAME} \
                        nginx=${GHCR_REPO}:v${BUILD_NUMBER} \
                        -n ${K8S_NAMESPACE}

                    kubectl rollout status deployment/${DEPLOYMENT_NAME} \
                        -n ${K8S_NAMESPACE} \
                        --timeout=120s
                """
            }
        }

        stage('Health Check') {
            steps {
                sh """
                    kubectl get deployment ${DEPLOYMENT_NAME} -n ${K8S_NAMESPACE}
                    kubectl get pods -n ${K8S_NAMESPACE} -l app=web
                """
            }
        }
    }

    post {
        success {
            echo "Deployment successful: ${GHCR_REPO}:v${BUILD_NUMBER}"
        }
        failure {
            echo "Deployment failed!"
        }
    }
}
```

### Jenkins 설정

**1. Credentials 등록**
```
Jenkins → Manage Jenkins → Credentials → Add Credentials

Kind: Username with password
ID: ghcr-token
Username: wlals2
Password: <GHCR_TOKEN>
```

**2. Pipeline Job 생성**
```
New Item → Pipeline
Definition: Pipeline script from SCM
SCM: Git
Repository URL: https://github.com/wlals2/blogsite.git
Branch: */main
Script Path: Jenkinsfile
```

**3. Webhook 설정**
```
GitHub Repository → Settings → Webhooks → Add webhook
Payload URL: http://<JENKINS_IP>:8080/github-webhook/
Content type: application/json
Events: Just the push event
```

---

## ArgoCD (GitOps CD)

### 상태

**구축 상황:**
- ✅ ArgoCD 설치 완료 (Helm Chart, 2026-01-20)
- ✅ Ingress 설정 완료 (argocd.jiminhome.shop)
- ⏳ Application 생성 대기
- ⏳ Git Repository 연동 대기

**접속 정보:**
- URL: `https://argocd.jiminhome.shop/`
- 아이디: `admin`
- 비밀번호: `saDtmwkg-ZyKLv2T`

---

### 왜 ArgoCD인가?

#### 문제: GitHub Actions의 한계 (Push 모델)

**현재 GitHub Actions 배포 방식:**
```yaml
# .github/workflows/deploy-web.yml
- name: Deploy to Kubernetes
  run: |
    kubectl set image deployment/web nginx=ghcr.io/wlals2/blog-web:v11
```

**문제점:**
1. **Drift 발생** (Git ↔ Kubernetes 불일치)
   ```bash
   # Git: replicas=5
   # Kubernetes: 누군가 kubectl scale --replicas=2 실행
   # 결과: Git과 실제 상태 불일치
   ```

2. **배포 이력 추적 어려움**
   ```bash
   # 누가 언제 replicas를 변경했는지?
   # GitHub Actions 로그? kubectl history? → 찾기 어려움
   ```

3. **롤백 복잡**
   ```bash
   # 이전 이미지 태그가 뭐였지?
   # GitHub Actions 로그에서 찾아야 함
   ```

4. **여러 환경 관리 어려움**
   ```bash
   # dev, staging, prod 각각 다른 replicas, resources
   # 워크플로우 파일 3개? 복잡함
   ```

---

#### 해결: GitOps with ArgoCD (Pull 모델)

**GitOps 원칙:**
1. **Git이 Single Source of Truth** - 모든 설정이 Git에
2. **선언적 배포** - Desired State를 Git에 정의
3. **자동 동기화** - Git 변경 → Kubernetes 자동 반영
4. **Pull 모델** - ArgoCD가 Git 감시 (보안)

**ArgoCD 도입 후:**
```yaml
# Git Repository: k8s-manifests/blog-system/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: web
spec:
  replicas: 5  # ← Git이 유일한 진실
  template:
    spec:
      containers:
      - name: nginx
        image: ghcr.io/wlals2/blog-web:v11
```

```bash
# 1. Git Push
git commit -m "scale: replicas 5 → 10"
git push

# 2. ArgoCD 자동 감지 (3초 이내)
# 3. ArgoCD가 Kubernetes에 자동 동기화
# 4. Slack 알림: "Application synced"

# 5. 누군가 kubectl scale --replicas=2 실행
# 6. ArgoCD 감지: "Git과 불일치!"
# 7. selfHeal 활성화 시 → 자동으로 replicas=10으로 복구
```

**장점:**
- ✅ Git이 단일 진실 원천
- ✅ 모든 변경 Git 히스토리로 추적
- ✅ Git revert로 즉시 롤백
- ✅ Pull Request로 배포 승인 프로세스
- ✅ selfHeal로 Drift 자동 복구

---

### CI와 CD의 분리

**현재 (GitHub Actions가 CI+CD 모두 수행):**
```yaml
# .github/workflows/deploy-web.yml
jobs:
  build-and-deploy:
    steps:
      - name: Build Docker image  # ← CI
      - name: Push to GHCR        # ← CI
      - name: Deploy to K8s       # ← CD
```

**문제점:**
- GitHub Actions가 Kubernetes credential 필요 (보안 위험)
- CI와 CD가 결합 (빌드 실패 시 배포도 실패)
- 배포 이력 관리 어려움

---

**목표 (CI/CD 분리):**

```
[ CI: GitHub Actions ]
- Docker 이미지 빌드
- GHCR Push
- manifestrepo에 image 태그 업데이트
- Kubernetes credential 불필요 ✅

[ CD: ArgoCD ]
- Git Repository 감시
- Desired State 확인
- Kubernetes 자동 동기화
- Pull 모델 (보안) ✅
```

**장점:**
- ✅ 보안: GitHub Actions는 Kubernetes credential 불필요
- ✅ 관심사 분리: CI는 빌드, CD는 배포
- ✅ 배포 이력: Git 히스토리 = 배포 이력
- ✅ 롤백: `git revert` 한 번으로 즉시 롤백

---

### Pull vs Push 모델

#### Push 모델 (GitHub Actions, Jenkins)

```
GitHub Actions
    │
    ├─ kubectl apply (Push)
    ↓
Kubernetes Cluster
```

**장점:**
- ✅ 간단함 (kubectl만 있으면 됨)

**단점:**
- ❌ Kubernetes credential 외부 노출
- ❌ Drift 발생 가능
- ❌ 자동 복구 없음

---

#### Pull 모델 (ArgoCD)

```
ArgoCD (Kubernetes 내부)
    │
    ├─ Git Pull (3초마다)
    ├─ Desired vs Actual 비교
    ├─ Auto Sync
    ↓
Kubernetes Cluster
```

**장점:**
- ✅ Kubernetes credential 외부 노출 불필요 (ArgoCD가 내부에서 실행)
- ✅ selfHeal로 Drift 자동 복구
- ✅ Git이 Single Source of Truth

**단점:**
- ❌ 초기 설정 복잡 (ArgoCD 설치 필요)

---

### 배포 흐름 비교

#### 현재 (Push 모델)

```
1. 개발자: 코드 수정
2. git push
3. GitHub Actions:
   - Docker Build
   - Push to GHCR
   - kubectl set image (직접 배포)
4. Kubernetes: 배포 완료

문제: Git과 Kubernetes 분리됨
```

#### 목표 (Pull 모델 + GitOps)

```
1. 개발자: 코드 수정
2. git push (소스 코드)
3. GitHub Actions:
   - Docker Build
   - Push to ghcr.io/wlals2/blog-web:v12
   - manifestrepo에 image: v12 업데이트 (git commit)
4. ArgoCD:
   - manifestrepo 변경 감지 (3초)
   - kubectl apply (자동)
5. Kubernetes: 배포 완료

장점: Git이 모든 진실의 원천
```

---

### Application 생성 (다음 단계)

**1. Git Repository 준비**
```bash
# 디렉토리 구조
k8s-manifests/
├── blog-system/
│   ├── deployment.yaml
│   ├── service.yaml
│   └── ingress.yaml
└── README.md
```

**2. ArgoCD Application 생성**
```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: blog-system
  namespace: argocd
spec:
  project: default
  source:
    repoURL: https://github.com/wlals2/k8s-manifests.git
    targetRevision: main
    path: blog-system
  destination:
    server: https://kubernetes.default.svc
    namespace: blog-system
  syncPolicy:
    automated:
      prune: true        # 삭제된 리소스 자동 제거
      selfHeal: true     # Drift 자동 복구
      allowEmpty: false
    syncOptions:
    - CreateNamespace=true
```

**3. Sync Policy 설명**

| 옵션 | 설명 | 예시 |
|------|------|------|
| **automated** | Git Push 시 자동 동기화 | Git push → 3초 내 배포 |
| **prune** | Git에서 삭제된 리소스 자동 제거 | Git에서 service.yaml 삭제 → K8s Service 삭제 |
| **selfHeal** | kubectl로 수정해도 Git 상태로 복구 | kubectl scale → 3초 후 Git replicas로 복구 |
| **allowEmpty** | 빈 디렉토리 허용 여부 | false: 디렉토리 비면 에러 |

**4. 배포 확인**
```bash
# ArgoCD UI에서 확인
https://argocd.jiminhome.shop/

# CLI로 확인
kubectl get applications -n argocd
# NAME          SYNC STATUS   HEALTH STATUS
# blog-system   Synced        Healthy
```

---

### 배경지식: GitOps란?

**GitOps 정의 (Weaveworks):**
- Git을 배포 자동화의 단일 진실 원천으로 사용
- 모든 인프라/애플리케이션 설정을 Git에 선언적으로 정의
- Git 변경 → 자동으로 클러스터에 반영

**GitOps 4원칙:**
1. **선언적 (Declarative)** - Desired State를 YAML로 정의
2. **버전 관리 (Versioned)** - 모든 변경이 Git 히스토리에
3. **자동 반영 (Automatically Applied)** - Git Push → 자동 배포
4. **지속적 조정 (Continuously Reconciled)** - Drift 자동 복구

**GitOps 장점:**
- ✅ Git이 감사 로그 (Audit Log)
- ✅ Pull Request로 코드 리뷰 가능
- ✅ Git revert로 즉시 롤백
- ✅ 재현 가능한 배포 (Reproducible)
- ✅ 여러 환경 관리 (dev, staging, prod)

---

### 트레이드오프

**ArgoCD 도입 장점:**
- ✅ Git이 Single Source of Truth
- ✅ selfHeal로 Drift 자동 복구
- ✅ 보안 (Pull 모델)
- ✅ 배포 이력 추적 용이
- ✅ 롤백 간편 (git revert)

**ArgoCD 도입 단점:**
- ❌ 초기 설정 복잡 (7개 Pod 설치)
- ❌ 리소스 사용량 증가 (Redis, Dex 등)
- ❌ 학습 곡선 (GitOps 개념 이해 필요)
- ❌ Git Repository 관리 필요 (manifestrepo)

**언제 ArgoCD를 도입해야 하는가?**
- ✅ 여러 환경 관리 (dev, staging, prod)
- ✅ 팀 협업 (Pull Request 리뷰)
- ✅ Drift 문제 발생 (kubectl로 수동 수정)
- ✅ 배포 이력 추적 필요
- ✅ GitOps 도입 목표

**언제 ArgoCD가 불필요한가?**
- ❌ 단일 환경만 운영
- ❌ 혼자 개발 (협업 불필요)
- ❌ 간단한 배포 (kubectl apply로 충분)

---

### 다음 단계

**⏳ 30분 내 완료 가능**

1. **Git Repository 생성** (10분)
   ```bash
   # GitHub에 k8s-manifests 저장소 생성
   # blog-system 디렉토리 추가
   # deployment.yaml, service.yaml, ingress.yaml 추가
   ```

2. **ArgoCD Application 생성** (10분)
   ```bash
   # ArgoCD UI 로그인
   # New App 클릭
   # Git Repository 연동
   # Sync Policy 설정 (automated, prune, selfHeal)
   ```

3. **첫 배포 테스트** (10분)
   ```bash
   # Git에서 replicas 변경: 2 → 3
   # git push
   # ArgoCD UI에서 자동 Sync 확인
   # kubectl get pods 확인 (3개로 증가)
   ```

**참고 문서:**
- [02-INFRASTRUCTURE.md](02-INFRASTRUCTURE.md#gitops-argocd) - ArgoCD 설치 상세
- [ArgoCD 공식 문서](https://argo-cd.readthedocs.io/)

---

## 비교 및 선택 가이드

### GitHub Actions vs Jenkins vs ArgoCD

| 기준 | GitHub Actions | Jenkins | ArgoCD |
|------|----------------|---------|--------|
| **역할** | CI + CD | CI + CD | **CD 전용** |
| **배포 모델** | Push | Push | **Pull** |
| **설정 난이도** | 쉬움 (YAML) | 어려움 (Groovy) | 중간 (YAML) |
| **유지보수** | GitHub 관리 | 직접 관리 | 직접 관리 |
| **Git 의존성** | 낮음 | 낮음 | **높음 (GitOps)** |
| **Drift 복구** | ❌ | ❌ | **✅ (selfHeal)** |
| **배포 이력** | Actions 로그 | Jenkins 로그 | **Git 히스토리** |
| **롤백** | 재실행 | 재실행 | **git revert** |
| **보안** | K8s credential 필요 | K8s credential 필요 | **불필요 (Pull)** |
| **학습 곡선** | 낮음 | 높음 | 중간 |
| **리소스 사용** | 낮음 | 중간 | 높음 (7 pods) |

---

### 배포 모델 비교

#### Push 모델 (GitHub Actions, Jenkins)

**장점:**
- ✅ 간단함 (kubectl만 있으면 됨)
- ✅ 즉시 배포 (빌드 후 바로 배포)

**단점:**
- ❌ Kubernetes credential 외부 노출
- ❌ Drift 발생 가능 (Git ↔ K8s 불일치)
- ❌ 자동 복구 없음

**언제 사용:**
- 단일 환경 운영
- 빠른 개발 사이클
- GitOps 불필요

---

#### Pull 모델 (ArgoCD)

**장점:**
- ✅ Git이 Single Source of Truth
- ✅ selfHeal로 Drift 자동 복구
- ✅ 보안 (Pull 모델)
- ✅ 배포 이력 = Git 히스토리

**단점:**
- ❌ 초기 설정 복잡
- ❌ Git Repository 관리 필요
- ❌ 리소스 사용량 높음

**언제 사용:**
- 여러 환경 관리 (dev, staging, prod)
- 팀 협업 (Pull Request 리뷰)
- Drift 문제 발생
- GitOps 도입 목표

---

### 언제 무엇을 사용하는가?

#### GitHub Actions 사용 (현재)

**역할: CI (이미지 빌드)**
```yaml
# .github/workflows/build-web.yml
- Docker Build
- Push to ghcr.io
- manifestrepo 이미지 태그 업데이트 (향후)
```

**장점:**
- ✅ Git Push 즉시 빌드
- ✅ GitHub 통합
- ✅ Self-hosted runner로 무제한 빌드

**사용 시나리오:**
- WEB, WAS Docker 이미지 빌드
- PR 자동 테스트
- 이미지 태그 자동 업데이트

---

#### ArgoCD 사용 (구축 중)

**역할: CD (Kubernetes 배포)**
```yaml
# argocd/applications/blog-system.yaml
- Git Poll (3초)
- Desired State 비교
- kubectl apply (자동)
- selfHeal (Drift 복구)
```

**장점:**
- ✅ Git이 유일한 진실
- ✅ Drift 자동 복구
- ✅ 롤백 간편 (git revert)

**사용 시나리오:**
- Kubernetes 리소스 배포 (Deployment, Service, Ingress)
- 여러 환경 관리 (dev, staging, prod)
- 배포 승인 프로세스 (Pull Request)

---

#### Jenkins 사용 (백업)

**역할: 복잡한 파이프라인**
```groovy
// Jenkinsfile
- 조건부 배포
- 승인 단계 (input)
- 플러그인 활용 (SonarQube, Nexus)
```

**장점:**
- ✅ 복잡한 워크플로우
- ✅ 다양한 플러그인

**사용 시나리오:**
- GitHub Actions 장애 시 백업
- 복잡한 파이프라인 필요 시
- 플러그인 활용 필요 시

---

### 최종 권장 아키텍처

**목표 (CI/CD 분리):**

```
┌──────────────────────────────────────┐
│ CI: GitHub Actions                   │
│  - Docker Build                      │
│  - Push to ghcr.io                   │
│  - manifestrepo 이미지 태그 업데이트 │
└──────────────────────────────────────┘
             ↓
┌──────────────────────────────────────┐
│ CD: ArgoCD                           │
│  - Git Poll (3초)                    │
│  - Desired State 비교                │
│  - kubectl apply (자동)              │
│  - selfHeal (Drift 복구)             │
└──────────────────────────────────────┘
             ↓
┌──────────────────────────────────────┐
│ Kubernetes Cluster                   │
│  - blog-system namespace             │
│  - web, was Deployments              │
└──────────────────────────────────────┘
```

**역할 분담:**
- **GitHub Actions**: 이미지 빌드만 담당 (CI)
- **ArgoCD**: Kubernetes 배포 담당 (CD)
- **Jenkins**: 백업 (GitHub Actions 장애 시)

---

### 현재 설정 (2026-01-20)

**활성화:**
- ✅ **GitHub Actions** (WEB, WAS 이미지 빌드 + 직접 배포)
- ✅ **ArgoCD** (설치 완료, Application 생성 대기)
- ✅ Cloudflare 캐시 자동 퍼지

**비활성화:**
- ⏸️ Jenkins (필요 시 수동 실행)

**다음 단계:**
1. ⏳ Git Repository (k8s-manifests) 생성
2. ⏳ ArgoCD Application 생성 (blog-system)
3. ⏳ GitHub Actions → manifestrepo 업데이트 로직 추가
4. ⏳ CI/CD 분리 완료 (GitOps 달성)

---

## 배포 플로우 검증

### 실제 운영 환경

**질문: https://blog.jiminhome.shop/ 이게 Kubernetes로 뜨고 있는거야 로컬꺼야?**

**답변: ✅ Kubernetes에서 실행 중!**

**증거:**
```bash
# 1. Kubernetes web Pod 확인
kubectl get pods -n blog-system -l app=web
# NAME                   READY   STATUS    RESTARTS   AGE     IP
# web-795b44bf96-2qbdj   1/1     Running   0          5h      10.0.2.2
# web-795b44bf96-67822   1/1     Running   0          5h      10.0.1.188

# 2. 이미지 버전 확인
kubectl get deployment web -n blog-system -o jsonpath='{.spec.template.spec.containers[0].image}'
# ghcr.io/wlals2/blog-web:v11

# 3. Service 엔드포인트 확인
kubectl get endpoints web-service -n blog-system
# ENDPOINTS: 10.0.1.188:80,10.0.2.2:80
```

### 배포 플로우

```
사용자
    ↓
Cloudflare CDN (캐시)
    ↓
blog.jiminhome.shop (HTTPS)
    ↓
로컬 nginx (192.168.1.187:443)
    │
    ├─ SSL Termination (HTTPS → HTTP)
    ├─ Reverse Proxy
    ↓
Kubernetes Ingress (192.168.1.187:80)
    │
    ├─ / → web-service:80
    ├─ /api → was-service:8080
    ↓
web-service (ClusterIP)
    │
    ├─ Load Balancing (2 Pods)
    ↓
web Pods (nginx)
    │
    ├─ Pod 1: 10.0.1.188:80
    └─ Pod 2: 10.0.2.2:80
        ↓
    Hugo 정적 파일 (/usr/share/nginx/html/)
```

### 로컬 nginx의 역할

**❌ 잘못된 이해:**
> "로컬에서 먼저 뜨고 다시 Kubernetes가 뜬다"

**✅ 올바른 이해:**
> "로컬 nginx는 SSL 처리만 하고, 실제 콘텐츠는 Kubernetes에서 제공"

**로컬 nginx 설정:**
```nginx
server {
    listen 443 ssl;
    server_name blog.jiminhome.shop;

    ssl_certificate /etc/letsencrypt/live/jiminhome.shop/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/jiminhome.shop/privkey.pem;

    location / {
        proxy_pass http://192.168.1.187:80;  # Kubernetes Ingress로 전달
        proxy_set_header Host $host;
    }
}
```

**확인:**
```bash
# 로컬 nginx는 Hugo 파일 없음
ls /var/www/blog/  # 비어있음

# Kubernetes Pod에 Hugo 파일 존재
kubectl exec -it web-795b44bf96-2qbdj -n blog-system -- ls /usr/share/nginx/html/
# index.html  css/  js/  images/  ...
```

### 배포 확인 방법

**1. GitHub Actions 로그**
```
Repository → Actions → 최근 워크플로우 실행
```

**2. Kubernetes 상태**
```bash
# Deployment 상태
kubectl get deployment -n blog-system

# Pod 상태
kubectl get pods -n blog-system

# 최근 배포 이벤트
kubectl describe deployment web -n blog-system | tail -20
```

**3. 실제 이미지 버전**
```bash
kubectl get deployment web -n blog-system -o yaml | grep image:
# image: ghcr.io/wlals2/blog-web:v11
```

**4. 사이트 접속**
```bash
curl -I https://blog.jiminhome.shop/
# HTTP/2 200
# server: nginx
# cf-cache-status: HIT
```

---

## 요약

### 현재 운영 중인 CI/CD

**WEB (Hugo 블로그):**
- ✅ GitHub Actions Self-hosted runner
- ✅ 파일: `.github/workflows/deploy-web.yml`
- ✅ 트리거: main 브랜치 push
- ✅ 이미지: `ghcr.io/wlals2/blog-web:v{run_number}`
- ✅ 배포 시간: 35초
- ✅ Cloudflare 캐시 자동 퍼지

**WAS (Spring Boot):**
- ✅ GitHub Actions Self-hosted runner
- ✅ 파일: `.github/workflows/deploy-was.yml`
- ✅ 트리거: 수동 실행 (`workflow_dispatch`)
- ✅ 이미지: `ghcr.io/wlals2/board-was:v{run_number}`
- ✅ 로컬 소스 자동 복사

### 핵심 교훈

1. **Self-hosted runner 필수**: 사설 네트워크 Kubernetes 접근
2. **kubeconfig 덮어쓰지 말 것**: 기존 설정 활용
3. **민감 코드는 .gitignore**: 빌드 시 로컬 복사
4. **캐시 자동화**: Cloudflare API로 배포 후 즉시 퍼지
5. **버전 관리**: `v{run_number}` 태그로 롤백 가능

### 다음 단계

- [ ] 블로그 디자인 개선 (CSS, 레이아웃)
- [ ] 콘텐츠 작성 (프로젝트 포트폴리오)
- [ ] 모니터링 추가 (Prometheus + Grafana)
- [ ] 자동 테스트 (링크 검증, lighthouse)
