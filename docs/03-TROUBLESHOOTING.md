# Kubernetes CI/CD 트러블슈팅 가이드

> Jenkins, GitHub Actions, ArgoCD 운영 중 발생한 모든 이슈와 해결 방법

---

## 목차

1. [kubectl Connection Refused 오류](#1-kubectl-connection-refused-오류)
2. [kubectl이 Jenkins HTML 반환 오류](#2-kubectl이-jenkins-html-반환-오류)
3. [Runner가 Job을 가져가지 않는 오류](#3-runner가-job을-가져가지-않는-오류)
4. [Cloudflare 캐시 퍼지 실패](#4-cloudflare-캐시-퍼지-실패)
5. [WAS Docker 빌드 경로 오류](#5-was-docker-빌드-경로-오류)
6. [ArgoCD 트러블슈팅](#6-argocd-트러블슈팅)

---

## 1. kubectl Connection Refused 오류

### 증상
```
Error from server (InternalError): Internal error occurred:
failed calling webhook "validate.nginx.ingress.kubernetes.io"
The connection to the server localhost:8080 was refused - did you specify the right host or port?
```

### 원인 분석

**왜 발생했는가?**

GitHub Actions의 `ubuntu-latest` 러너는 GitHub의 퍼블릭 클라우드에서 실행됩니다. 하지만 우리 Kubernetes 클러스터는 사설 네트워크(192.168.1.187:6443)에 있습니다.

```
[ GitHub 클라우드 ]                  [ 홈 네트워크 ]
  ubuntu-latest runner   ❌ 접근 불가   192.168.1.187 (k8s-cp)
  (퍼블릭 IP)                          (RFC 1918 사설 IP)
```

**사설 IP 범위 (RFC 1918):**
- 10.0.0.0/8
- 172.16.0.0/12
- **192.168.0.0/16** ← 우리 클러스터

이 IP 대역은 인터넷을 통해 라우팅되지 않으므로, GitHub 클라우드에서 접근 불가능합니다.

### 해결 방법

**Self-hosted runner 사용**

k8s-cp 노드에 러너를 설치하여 같은 네트워크에서 실행:

```yaml
# .github/workflows/deploy-web.yml
jobs:
  build-and-deploy:
    runs-on: self-hosted  # ✅ ubuntu-latest → self-hosted로 변경
```

**설정 방법:**
```bash
# 1. k8s-cp 노드에서 러너 설정
cd ~/actions-runner
./config.sh --url https://github.com/wlals2/blogsite --token <TOKEN>

# 2. 서비스 등록
sudo ./svc.sh install
sudo ./svc.sh start
```

### 확인 방법
```bash
# Self-hosted runner 상태 확인
sudo ./svc.sh status

# 워크플로우 로그에서 러너 확인
# 로그에 "Runner name: 'k8s-cp'" 표시되어야 함
```

---

## 2. kubectl이 Jenkins HTML 반환 오류

### 증상
```bash
kubectl get deployments -n blog-system

# 출력:
<!doctype html>
<html>
<head>
<title>Authentication required</title>
...Jenkins login page...
</html>
```

### 원인 분석

**왜 발생했는가?**

워크플로우의 kubeconfig 설정 단계가 문제였습니다:

```yaml
# ❌ 문제가 있던 코드
- name: Set up kubeconfig
  run: |
    mkdir -p ~/.kube
    echo "${{ secrets.KUBECONFIG_BASE64 }}" | base64 -d > ~/.kube/config
    chmod 600 ~/.kube/config
```

**문제점:**
1. `KUBECONFIG_BASE64` 시크릿이 비어있거나 잘못된 내용 포함
2. 기존의 정상 작동하던 `~/.kube/config` 파일을 덮어씀
3. kubectl이 인증 실패 → 기본 포트(8080)로 폴백 → Jenkins 서비스 응답

**Before (정상):**
```bash
$ cat ~/.kube/config
apiVersion: v1
clusters:
- cluster:
    server: https://192.168.1.187:6443
    certificate-authority-data: <VALID_CERT>
```

**After (손상됨):**
```bash
$ cat ~/.kube/config
# 빈 파일 또는 잘못된 내용
```

### 해결 방법

**1. kubeconfig 복구**
```bash
# k8s-cp 노드에서 복구
sudo cp /etc/kubernetes/admin.conf ~/.kube/config
sudo chown $(id -u):$(id -g) ~/.kube/config

# 확인
kubectl get nodes
```

**2. 워크플로우 수정**

kubeconfig 설정 단계를 완전히 제거했습니다:

```yaml
# ✅ 수정 후 (kubeconfig 설정 단계 삭제)
jobs:
  build-and-deploy:
    runs-on: self-hosted

    steps:
      - name: Checkout code
        uses: actions/checkout@v4

      # Note: Self-hosted runner already has kubeconfig at ~/.kube/config
      - name: Deploy to Kubernetes
        run: |
          kubectl set image deployment/${{ env.DEPLOYMENT_NAME }} \
            nginx=${{ env.IMAGE_NAME }}:v${{ github.run_number }} \
            -n ${{ env.NAMESPACE }}
```

**이유:**
- Self-hosted runner는 k8s-cp 노드에서 실행
- 이미 `/etc/kubernetes/admin.conf`에서 복사된 유효한 kubeconfig 보유
- 덮어쓸 필요 없음

### 확인 방법
```bash
# 워크플로우 실행 전/후 kubeconfig 확인
kubectl config view

# 정상 출력:
# current-context: kubernetes-admin@kubernetes
# server: https://192.168.1.187:6443
```

---

## 3. Runner가 Job을 가져가지 않는 오류

### 증상
```
Waiting for a runner to pick up this job...
```

워크플로우가 무한정 대기 상태로 머뭄.

### 원인 분석

**왜 발생했는가?**

러너가 잘못된 저장소에 등록되어 있었습니다:

```bash
$ cat ~/actions-runner/.runner
{
  "gitHubUrl": "https://github.com/wlals2/my-hugo-blog",  # ❌ 잘못된 저장소
  "agentName": "k8s-cp"
}
```

**문제:**
- 워크플로우는 `wlals2/blogsite` 저장소에서 실행
- 러너는 `wlals2/my-hugo-blog`에 등록됨
- 저장소 불일치 → 러너가 Job을 인식하지 못함

### 해결 방법

**1. 러너 서비스 중지 및 설정 삭제**
```bash
cd ~/actions-runner

# 서비스 중지 및 제거
sudo ./svc.sh stop
sudo ./svc.sh uninstall

# 설정 파일 삭제
rm -f .runner .credentials .credentials_rsaparams
```

**2. 올바른 저장소로 재등록**
```bash
# GitHub에서 새 토큰 발급
# Settings → Actions → Runners → New self-hosted runner

# 러너 재등록
./config.sh --url https://github.com/wlals2/blogsite --token <NEW_TOKEN>

# 서비스 재시작
sudo ./svc.sh install
sudo ./svc.sh start
```

**3. 등록 확인**
```bash
cat .runner | jq '.gitHubUrl'
# 출력: "https://github.com/wlals2/blogsite"  ✅
```

### 확인 방법
```bash
# 러너 상태 확인
sudo ./svc.sh status

# GitHub UI에서 확인
# Settings → Actions → Runners
# "k8s-cp" 러너가 Idle 상태로 표시되어야 함

# 워크플로우 트리거 후 로그 확인
tail -f ~/actions-runner/_diag/Worker_*.log
# "Running job: build-and-deploy" 메시지 확인
```

---

## 4. Cloudflare 캐시 퍼지 실패

### 증상
```
curl: (3) URL using bad/illegal format or missing URL
```

### 원인 분석

**왜 발생했는가?**

Cloudflare API 호출 시 `CLOUDFLARE_ZONE_ID` 시크릿이 비어있거나 잘못된 값이었습니다:

```yaml
# 워크플로우 코드
- name: Purge Cloudflare Cache
  run: |
    curl -X POST "https://api.cloudflare.com/client/v4/zones/${{ secrets.CLOUDFLARE_ZONE_ID }}/purge_cache" \
      -H "Authorization: Bearer ${{ secrets.CLOUDFLARE_API_TOKEN }}" \
      --data '{"purge_everything":true}'
```

**문제:**
- `${{ secrets.CLOUDFLARE_ZONE_ID }}`가 빈 문자열
- URL이 `zones//purge_cache`로 변환됨 → 잘못된 형식

### 해결 방법

**1. Zone ID 조회**

Cloudflare API로 직접 조회:
```bash
curl -X GET "https://api.cloudflare.com/client/v4/zones" \
  -H "Authorization: Bearer <CLOUDFLARE_API_TOKEN>" \
  -H "Content-Type: application/json" | jq -r '.result[] | select(.name=="jiminhome.shop") | .id'

# 출력: 7895fe2aef761351db71892fb7c22b52
```

**또는 Cloudflare 대시보드:**
1. Cloudflare 대시보드 로그인
2. jiminhome.shop 도메인 선택
3. 오른쪽 사이드바 하단 "Zone ID" 복사

**2. GitHub Secret 업데이트**
```
Repository Settings → Secrets and variables → Actions → New repository secret

Name: CLOUDFLARE_ZONE_ID
Value: 7895fe2aef761351db71892fb7c22b52
```

**3. 워크플로우 재실행**

### 확인 방법
```bash
# 로컬에서 직접 테스트
curl -X POST "https://api.cloudflare.com/client/v4/zones/7895fe2aef761351db71892fb7c22b52/purge_cache" \
  -H "Authorization: Bearer $CLOUDFLARE_API_TOKEN" \
  -H "Content-Type: application/json" \
  --data '{"purge_everything":true}'

# 성공 응답:
# {"success":true,"errors":[],"messages":[],"result":{"id":"..."}}

# 워크플로우 로그에서 확인
# "Cloudflare cache purged successfully" 메시지 확인
```

---

## 5. WAS Docker 빌드 경로 오류

### 증상
```
ERROR: failed to solve: failed to read dockerfile:
open Dockerfile: no such file or directory
path "./blog-k8s-project/was" not found
```

### 원인 분석

**왜 발생했는가?**

WAS 소스코드가 `.gitignore`에 포함되어 있어 Git에 커밋되지 않습니다:

```bash
# .gitignore
blog-k8s-project/was/
```

**문제:**
1. `actions/checkout@v4`는 Git 저장소만 클론
2. WAS 소스는 로컬 디렉터리(`~/blogsite/blog-k8s-project/was`)에만 존재
3. 워크플로우 워킹 디렉터리(`~/actions-runner/_work/blogsite/blogsite`)에는 없음

**디렉터리 구조:**
```
~/blogsite/                          # 실제 소스 위치 (로컬)
├── blog-k8s-project/
│   └── was/                        ✅ 여기에 존재
│       ├── Dockerfile
│       └── src/

~/actions-runner/_work/blogsite/blogsite/  # 워크플로우 워킹 디렉터리
├── blog-k8s-project/
│   └── was/                        ❌ 여기에 없음 (Git에 없으므로)
```

### 해결 방법

**워크플로우에 복사 단계 추가**

```yaml
# .github/workflows/deploy-was.yml
steps:
  # Step 1: Git 코드 클론 (WAS 소스 제외)
  - name: Checkout code
    uses: actions/checkout@v4

  # Step 2: WAS 소스 로컬에서 복사
  - name: Copy WAS source code
    run: |
      cp -r ~/blogsite/blog-k8s-project/was ./blog-k8s-project/
      ls -la ./blog-k8s-project/was/

  # Step 3: Docker 빌드 (이제 소스 존재)
  - name: Build and push Docker image
    uses: docker/build-push-action@v5
    with:
      context: ./blog-k8s-project/was
      file: ./blog-k8s-project/was/Dockerfile
      push: true
      tags: |
        ${{ env.IMAGE_NAME }}:v${{ github.run_number }}
        ${{ env.IMAGE_NAME }}:latest
```

**왜 WAS는 Git에 포함하지 않는가?**
- 민감한 정보 포함 가능 (API 키, DB 패스워드)
- 퍼블릭 저장소에서 소스 노출 방지
- 로컬에서만 관리

### 확인 방법
```bash
# 워크플로우 실행 중 로그 확인
# "Copy WAS source code" 단계에서:
# total 48
# -rw-r--r-- 1 runner runner  1234 Jan 18 12:00 Dockerfile
# drwxr-xr-x 3 runner runner  4096 Jan 18 12:00 src
# ...

# Docker 빌드 성공 확인
# "Build and push Docker image" 단계에서:
# Successfully built and pushed ghcr.io/wlals2/board-was:v2
```

---

## 최종 결과

### ✅ 해결된 워크플로우

**WEB 배포 (.github/workflows/deploy-web.yml):**
```yaml
name: Deploy WEB to Kubernetes

on:
  push:
    branches: [ main ]

env:
  IMAGE_NAME: ghcr.io/wlals2/blog-web
  NAMESPACE: blog-system
  DEPLOYMENT_NAME: web

jobs:
  build-and-deploy:
    runs-on: self-hosted  # ✅

    steps:
      - name: Checkout code
        uses: actions/checkout@v4

      - name: Build and push Docker image
        uses: docker/build-push-action@v5
        with:
          context: .
          file: ./Dockerfile
          push: true
          tags: |
            ${{ env.IMAGE_NAME }}:v${{ github.run_number }}
            ${{ env.IMAGE_NAME }}:latest

      # ✅ kubeconfig 설정 단계 제거됨
      - name: Deploy to Kubernetes
        run: |
          kubectl set image deployment/${{ env.DEPLOYMENT_NAME }} \
            nginx=${{ env.IMAGE_NAME }}:v${{ github.run_number }} \
            -n ${{ env.NAMESPACE }}

      - name: Purge Cloudflare Cache
        run: |
          curl -X POST "https://api.cloudflare.com/client/v4/zones/${{ secrets.CLOUDFLARE_ZONE_ID }}/purge_cache" \
            -H "Authorization: Bearer ${{ secrets.CLOUDFLARE_API_TOKEN }}" \
            --data '{"purge_everything":true}'
```

**WAS 배포 (.github/workflows/deploy-was.yml):**
```yaml
name: Deploy WAS to Kubernetes

on:
  workflow_dispatch:
  push:
    branches: [ main ]
    paths:
      - '.github/workflows/deploy-was.yml'

env:
  IMAGE_NAME: ghcr.io/wlals2/board-was
  NAMESPACE: blog-system
  DEPLOYMENT_NAME: was

jobs:
  build-and-deploy:
    runs-on: self-hosted  # ✅

    steps:
      - name: Checkout code
        uses: actions/checkout@v4

      # ✅ WAS 소스 복사 단계 추가됨
      - name: Copy WAS source code
        run: |
          cp -r ~/blogsite/blog-k8s-project/was ./blog-k8s-project/
          ls -la ./blog-k8s-project/was/

      - name: Build and push Docker image
        uses: docker/build-push-action@v5
        with:
          context: ./blog-k8s-project/was
          file: ./blog-k8s-project/was/Dockerfile
          push: true
          tags: |
            ${{ env.IMAGE_NAME }}:v${{ github.run_number }}
            ${{ env.IMAGE_NAME }}:latest

      - name: Deploy to Kubernetes
        run: |
          kubectl set image deployment/${{ env.DEPLOYMENT_NAME }} \
            spring-boot=${{ env.IMAGE_NAME }}:v${{ github.run_number }} \
            -n ${{ env.NAMESPACE }}
```

### 배포 상태 확인
```bash
# WEB 배포 확인
kubectl get deployment web -n blog-system
# NAME   READY   UP-TO-DATE   AVAILABLE   AGE
# web    2/2     2            2           5d

# WAS 배포 확인
kubectl get deployment was -n blog-system
# NAME   READY   UP-TO-DATE   AVAILABLE   AGE
# was    2/2     2            2           5d

# Pod 상태 확인
kubectl get pods -n blog-system
# NAME                    READY   STATUS    RESTARTS   AGE
# web-7f8b9c5d6f-abc12    1/1     Running   0          10m
# web-7f8b9c5d6f-def34    1/1     Running   0          10m
# was-8c9d0e6f7g-ghi56    1/1     Running   0          5m
# was-8c9d0e6f7g-jkl78    1/1     Running   0          5m
```

---

## 핵심 교훈

### 1. 네트워크 접근성
- 퍼블릭 러너는 사설 IP 접근 불가
- Self-hosted runner 필요 시점 판단 중요

### 2. 기존 설정 보존
- 정상 작동하는 kubeconfig 덮어쓰지 말 것
- Self-hosted runner의 기존 환경 활용

### 3. Git과 로컬 파일 분리
- 민감한 코드는 .gitignore 처리
- 빌드 시 로컬에서 복사하는 전략 필요

### 4. 시크릿 관리
- 시크릿 값 정확성 사전 검증
- 로컬에서 API 직접 테스트 후 시크릿 등록

### 5. 러너 등록
- 저장소 URL 정확히 확인
- 재등록 시 기존 설정 파일 완전 삭제

---

## 6. ArgoCD 트러블슈팅

### 6.1 Cloudflared 설정 미반영 문제

**발생일**: 2026-01-19

#### 증상
```bash
# config.yml에 argocd 추가 후 재시작
sudo systemctl restart cloudflared

# 하지만 로그에서 argocd 설정 없음
sudo journalctl -u cloudflared -n 20
# 출력: config="{\"ingress\":[{\"hostname\":\"blog.jiminhome.shop\"...}"
# argocd가 보이지 않음!
```

---

#### 원인 분석

**1. 설정 파일 우선순위 혼동**
```bash
# Systemd Service 파일 확인
cat /etc/systemd/system/cloudflared.service
# ExecStart=/usr/local/bin/cloudflared --config /etc/cloudflared/config.yml

# 하지만 사용자 설정 파일도 존재
ls ~/.cloudflared/config.yml
# 존재함!
```

**우선순위**:
1. Systemd Service 파일의 `--config` 플래그
2. `/etc/cloudflared/config.yml` (시스템 전역)
3. `~/.cloudflared/config.yml` (사용자)

**문제**: 한 파일만 수정하고 다른 파일은 수정하지 않음

---

**2. warp-routing 구문 오류**
```yaml
# 잘못된 설정
warp-routing:
  enabled: true  # ← 구문 오류 (validation 실패)

ingress:
  - hostname: argocd.jiminhome.shop
    service: https://192.168.1.200:443
```

**문제**: `warp-routing` 구문 오류로 전체 설정 파일 validation 실패

---

#### 해결 방법

**Step 1: warp-routing 제거**
```bash
cat <<'YAML' | sudo tee /etc/cloudflared/config.yml > /dev/null
tunnel: 65759494-dae6-4287-b92d-02a918b34722
credentials-file: /etc/cloudflared/65759494-dae6-4287-b92d-02a918b34722.json

ingress:
  - hostname: blog.jiminhome.shop
    service: http://192.168.1.200:80
  - hostname: argocd.jiminhome.shop
    service: https://192.168.1.200:443
    originRequest:
      noTLSVerify: true
  - service: http_status:404
YAML
```

**Step 2: 사용자 설정 파일도 동일하게 업데이트**
```bash
cp /etc/cloudflared/config.yml ~/.cloudflared/config.yml
```

**Step 3: Validation**
```bash
cloudflared --config /etc/cloudflared/config.yml tunnel ingress validate
# 출력: OK ✅
```

**Step 4: 재시작**
```bash
sudo systemctl restart cloudflared
sudo journalctl -u cloudflared -n 20
```

---

#### 대안: DNS 라우팅 직접 추가

**문제**: Cloudflare 서버가 오래된 Ingress 규칙 캐싱

**해결**:
```bash
cloudflared tunnel route dns home-network argocd.jiminhome.shop

# 출력:
# 2026-01-19T15:26:13Z INF Added CNAME argocd.jiminhome.shop ✅
```

**DNS 라우팅 vs Ingress 규칙**:

| 항목 | DNS 라우팅 | Ingress 규칙 |
|------|-----------|--------------|
| **역할** | 도메인 → Tunnel 연결 | Tunnel → 백엔드 라우팅 |
| **설정** | `cloudflared tunnel route dns` | config.yml의 ingress 섹션 |
| **필수 여부** | ✅ 필수 | ✅ 필수 |
| **현재 상태** | ✅ 완료 | ⏳ Cloudflare 서버 미반영 |

**둘 다 필요**:
```
사용자 → DNS (argocd → tunnel) → Ingress 규칙 (tunnel → 192.168.1.200) → ArgoCD
```

---

#### 확인 방법

```bash
# DNS 확인
dig +short argocd.jiminhome.shop
# CNAME home-network.cfargotunnel.com ✅

# 접속 확인
curl -I https://argocd.jiminhome.shop/
# HTTP/2 404 (DNS는 OK, Ingress 규칙 미적용)
```

---

### 6.2 Ingress TLS Passthrough 문제

**발생일**: 2026-01-20

#### 증상
```bash
# Ingress 생성 후 접속 시도
curl -I https://argocd.jiminhome.shop/
# HTTP/2 502 Bad Gateway
```

---

#### 원인 분석

**ArgoCD는 Self-signed 인증서 사용**:
```bash
# ArgoCD Server Pod 확인
kubectl describe pod argocd-server-xxx -n argocd | grep TLS
# argocd-server uses self-signed certificate
```

**문제**: Ingress가 TLS를 종료하려고 시도 → ArgoCD가 HTTP 요청 거부

---

#### 해결 방법

**Annotation 추가**:
```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: argocd-server-ingress
  namespace: argocd
  annotations:
    nginx.ingress.kubernetes.io/ssl-passthrough: "true"  # ← 추가
    nginx.ingress.kubernetes.io/backend-protocol: "HTTPS" # ← 추가
spec:
  ingressClassName: nginx
  rules:
  - host: argocd.jiminhome.shop
    http:
      paths:
      - path: /
        pathType: Prefix
        backend:
          service:
            name: argocd-server
            port:
              number: 443
```

**설명**:
- `ssl-passthrough: "true"` - Ingress가 TLS를 종료하지 않고 ArgoCD로 전달
- `backend-protocol: "HTTPS"` - Backend가 HTTPS로 통신

---

#### 확인 방법

```bash
# Ingress 확인
kubectl get ingress -n argocd

# 로컬 접속 테스트
curl -k -I -H "Host: argocd.jiminhome.shop" https://192.168.1.200/
# HTTP/2 200 ✅
```

---

### 6.3 Pod 생성 느림 (ContainerCreating)

**발생일**: 2026-01-20

#### 증상
```bash
# ArgoCD 설치 후 Pod 확인
kubectl get pods -n argocd

# 결과:
NAME                          READY   STATUS
argocd-server-xxx             0/1     ContainerCreating  # ← 오래 걸림
```

---

#### 원인 분석

**정상 동작**:
- 이미지 Pull (ghcr.io에서 다운로드)
- Init Container 실행
- 볼륨 마운트

**대기 시간**:
- ArgoCD 이미지 크기: ~200MB
- 네트워크 속도: ~10MB/s
- 예상 시간: 60-70초

---

#### 해결 방법

**1. 대기**:
```bash
# 60초 후 재확인
sleep 60
kubectl get pods -n argocd
# STATUS: Running ✅
```

**2. 상세 로그 확인** (필요 시):
```bash
kubectl describe pod argocd-server-xxx -n argocd | tail -20
# Events:
#   Pulling image "quay.io/argoproj/argocd:v2.8.4"
#   Successfully pulled image
#   Created container argocd-server
```

---

### 6.4 초기 비밀번호 찾기 실패

**발생일**: 2026-01-20

#### 증상
```bash
# 초기 비밀번호 확인 시도
kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath="{.data.password}" | base64 -d

# 오류:
# Error from server (NotFound): secrets "argocd-initial-admin-secret" not found
```

---

#### 원인 분석

**Helm Chart 설정에 따라 Secret 이름이 다를 수 있음**:
```bash
# ArgoCD Helm values.yaml 확인
helm get values argocd -n argocd
```

또는 **이미 비밀번호를 변경한 경우 Secret 삭제됨**

---

#### 해결 방법

**1. 모든 Secret 확인**:
```bash
kubectl get secrets -n argocd | grep admin
# argocd-secret  # ← 여기에 비밀번호 있음
```

**2. argocd-secret에서 비밀번호 확인**:
```bash
kubectl -n argocd get secret argocd-secret -o jsonpath="{.data.admin\.password}" | base64 -d
```

**3. 비밀번호 재설정** (필요 시):
```bash
# ArgoCD CLI 설치 후
argocd account update-password
```

---

### 배운 점 (ArgoCD)

#### 1. Cloudflare Tunnel 설정 파일 관리

**문제**: 여러 설정 파일 존재 시 혼동
**교훈**:
- Systemd Service 파일 확인 (`--config` 플래그)
- `/etc`와 `~/` 두 곳 모두 일관되게 유지
- validation 명령어로 사전 검증

---

#### 2. DNS vs Ingress 규칙

**문제**: DNS만 추가하고 Ingress 규칙 없으면 404
**교훈**:
- DNS 라우팅: 도메인 → Tunnel
- Ingress 규칙: Tunnel → Backend
- **둘 다 필요**

---

#### 3. TLS Passthrough

**문제**: Self-signed 인증서 사용 시 Ingress 설정 필요
**교훈**:
- `ssl-passthrough: "true"` 필수
- `backend-protocol: "HTTPS"` 명시
- Cloudflare: `noTLSVerify: true`

---

#### 4. Pod 초기화 시간

**문제**: ContainerCreating 상태 오래 지속
**교훈**:
- 이미지 Pull 시간 고려 (60-70초)
- Init Container 실행 시간 포함
- 급하게 재시작하지 말고 대기

---

### 6.5 Istio mTLS로 인한 502 Bad Gateway

**발생일**: 2026-01-20

#### 증상

```bash
# 브라우저에서 블로그 접속 시도
https://blog.jiminhome.shop/
# Cloudflare 502 Bad Gateway 에러 페이지 표시

# 로컬에서 직접 확인
curl -I http://192.168.1.200/ -H "Host: blog.jiminhome.shop"
# HTTP/1.1 502 Bad Gateway
```

**상황**:
- WAS MySQL 연결 문제를 해결한 직후 발생
- Cloudflare는 정상 작동 (CDN 통과)
- 모든 Pod는 Running 상태
- Ingress Controller는 정상 작동

---

#### 원인 분석

**1. 문제 발생 시점**

WAS → MySQL 연결 문제 해결을 위해 **Istio mTLS 설정을 추가**했습니다:

```bash
# WAS MySQL 연결 문제 해결 시 실행한 명령어
kubectl apply -f /tmp/mysql-peer-auth.yaml

# 내용:
apiVersion: security.istio.io/v1
kind: PeerAuthentication
metadata:
  name: mysql-mtls-exception
  namespace: blog-system
spec:
  selector:
    matchLabels:
      app: mysql
  mtls:
    mode: PERMISSIVE  # MySQL만 PERMISSIVE
```

하지만 **Global PeerAuthentication이 STRICT 모드**였습니다:

```yaml
apiVersion: security.istio.io/v1
kind: PeerAuthentication
metadata:
  name: default
  namespace: blog-system
spec:
  mtls:
    mode: STRICT  # ← 문제의 원인
```

---

**2. 왜 502가 발생했는가?**

**Istio mTLS STRICT 모드의 동작:**

```
외부 요청 흐름:
Cloudflare
    ↓
Ingress Controller (nginx-ingress)
    ├─ mTLS 인증서 없음 ❌
    ↓
WEB/WAS Pods (Istio Sidecar)
    ├─ STRICT 모드: mTLS 인증서 필수
    ↓
거부 → Connection reset by peer → 502 Bad Gateway
```

**Ingress Controller 로그 확인:**
```bash
kubectl logs -n ingress-nginx ingress-nginx-controller-xxx --tail=50

# 출력:
# recv() failed (104: Connection reset by peer) while reading response header from upstream
# upstream: "http://10.0.2.213:80/", host: "blog.jiminhome.shop"
# 502 Bad Gateway
```

**핵심 문제**:
- Ingress Controller는 **Istio 외부**에서 실행
- mTLS 인증서 없이 WEB/WAS Pod에 접근 시도
- STRICT 모드가 plain text 트래픽을 차단

---

**3. kubectl patch가 적용되지 않은 이유**

처음에 PERMISSIVE로 변경 시도:

```bash
kubectl patch peerauthentication -n blog-system default \
  --type='merge' -p='{"spec":{"mtls":{"mode":"PERMISSIVE"}}}'
# peerauthentication.security.istio.io/default patched
```

하지만 **실제로 적용되지 않음**:

```bash
kubectl get peerauthentication -n blog-system default -o yaml | grep -A 3 "mtls:"
#   mtls:
#     mode: STRICT  # ← 여전히 STRICT!
```

**가능한 원인**:
1. **ArgoCD selfHeal**: Git 상태로 자동 복구
2. **Admission Webhook**: 변경 차단
3. **Operator 컨트롤러**: 자동으로 원래 상태로 되돌림

---

#### 해결 방법

**최종 해결책: PeerAuthentication 삭제**

```bash
# STRICT 모드 PeerAuthentication 삭제
kubectl delete peerauthentication -n blog-system default

# 확인
kubectl get peerauthentication -n blog-system
# NAME                   MODE         AGE
# mysql-mtls-exception   PERMISSIVE   10m  # ← MySQL용만 남음
```

**삭제 후 즉시 복구:**
```bash
curl -I http://192.168.1.200/ -H "Host: blog.jiminhome.shop"
# HTTP/1.1 200 OK ✅

curl -I https://blog.jiminhome.shop/
# HTTP/2 200 ✅
```

---

#### 왜 삭제로 해결되었는가?

**Istio mTLS 기본 동작:**

| PeerAuthentication 존재 | mTLS 모드 | 동작 |
|------------------------|----------|------|
| **없음** | - | Plain text 허용 (Istio가 자동 처리) |
| **STRICT** | 강제 | mTLS 인증서 필수, plain text 차단 ❌ |
| **PERMISSIVE** | 선택적 | mTLS/plain text 둘 다 허용 ✅ |

**삭제 후 상태:**
```
Ingress Controller (plain text)
    ↓
WEB/WAS Pods (Istio Sidecar)
    ├─ Global Policy 없음 → plain text 허용 ✅
    ├─ Pod 간 통신은 Istio가 자동으로 mTLS 적용
    ↓
정상 응답
```

**MySQL은 어떻게 되었나?**
```bash
kubectl get peerauthentication -n blog-system
# NAME                   MODE         AGE
# mysql-mtls-exception   PERMISSIVE   15m

# WAS → MySQL 연결은 여전히 정상 작동 ✅
```

---

#### 시도했지만 실패한 방법들

**1. PERMISSIVE로 변경 시도 (실패)**
```bash
kubectl patch peerauthentication -n blog-system default \
  --type='merge' -p='{"spec":{"mtls":{"mode":"PERMISSIVE"}}}'
# 명령은 성공했지만 실제로 적용되지 않음
```

**2. Deployment 재시작 (효과 없음)**
```bash
kubectl rollout restart deployment -n blog-system was
# WAS는 재시작되었지만 502는 계속됨
```

**3. Ingress Controller 재시작 (효과 없음)**
```bash
kubectl delete pod -n ingress-nginx ingress-nginx-controller-xxx
# 새 Pod 생성되었지만 502는 계속됨
```

**근본 원인을 해결해야 했음**: Global PeerAuthentication STRICT 모드

---

#### 확인 방법

**1. 현재 mTLS 설정 확인**
```bash
# 모든 PeerAuthentication 확인
kubectl get peerauthentication -A

# 특정 Namespace 확인
kubectl get peerauthentication -n blog-system -o yaml
```

**2. Ingress → Pod 연결 테스트**
```bash
# 로컬에서 MetalLB IP로 직접 접근
curl -I http://192.168.1.200/ -H "Host: blog.jiminhome.shop"

# 502면 mTLS 문제 가능성 높음
```

**3. Ingress Controller 로그 확인**
```bash
kubectl logs -n ingress-nginx -l app.kubernetes.io/name=ingress-nginx --tail=50 | grep -i "error\|502"

# "Connection reset by peer" 메시지 → mTLS 차단
```

**4. Pod 내부에서 테스트**
```bash
# WEB Pod 내부에서 직접 확인 (정상 작동해야 함)
kubectl exec -n blog-system <web-pod> -c nginx -- curl -I http://localhost:80
# HTTP/1.1 200 OK ✅

# 만약 이것도 실패하면 Pod 자체 문제
```

---

#### 트러블슈팅 순서

**502 Bad Gateway 발생 시 체크리스트:**

```bash
# 1. Pod 상태 확인
kubectl get pods -n blog-system
# 모두 Running이어야 함

# 2. Service Endpoints 확인
kubectl get endpoints -n blog-system web-service
# Endpoints가 있어야 함

# 3. Ingress Controller 로그 확인
kubectl logs -n ingress-nginx -l app.kubernetes.io/name=ingress-nginx --tail=50 | grep 502

# 4. mTLS 설정 확인 (Istio 사용 시)
kubectl get peerauthentication -A

# 5. 직접 테스트
curl -I http://192.168.1.200/ -H "Host: blog.jiminhome.shop"
```

---

#### 최종 상태

**현재 설정:**
```bash
kubectl get peerauthentication -n blog-system
# NAME                   MODE         AGE
# mysql-mtls-exception   PERMISSIVE   30m
```

**효과:**
- ✅ Ingress → WEB/WAS: plain text 허용
- ✅ WAS → MySQL: PERMISSIVE 모드 (연결 정상)
- ✅ Pod 간 통신: Istio가 자동으로 mTLS 적용
- ✅ 블로그 정상 작동

**배포 확인:**
```bash
# WEB 정상
curl -I https://blog.jiminhome.shop/
# HTTP/2 200 ✅

# WAS 정상
curl -I https://blog.jiminhome.shop/api/boards/
# HTTP/2 200 ✅ (MySQL 연결 필요)
```

---

#### 배운 점

**1. Istio mTLS 모드 이해**

| 모드 | 용도 | Ingress 접근 |
|------|------|-------------|
| **STRICT** | 보안 중요한 환경 (인증서 필수) | ❌ 차단 |
| **PERMISSIVE** | 전환 기간, 외부 접근 필요 | ✅ 허용 |
| **DISABLE** | mTLS 완전 비활성화 | ✅ 허용 |

**2. Global vs Selector 기반 Policy**

```yaml
# Global Policy (전체 Namespace)
apiVersion: security.istio.io/v1
kind: PeerAuthentication
metadata:
  name: default
  namespace: blog-system
spec:
  mtls:
    mode: STRICT  # ← 모든 Pod에 적용

---
# Selector 기반 (특정 Pod만)
apiVersion: security.istio.io/v1
kind: PeerAuthentication
metadata:
  name: mysql-mtls-exception
  namespace: blog-system
spec:
  selector:
    matchLabels:
      app: mysql  # ← MySQL Pod만 적용
  mtls:
    mode: PERMISSIVE
```

**교훈**: Global Policy는 신중하게 설정해야 함

---

**3. kubectl patch가 항상 적용되는 것은 아님**

**문제**:
- ArgoCD selfHeal 활성화 시 Git 상태로 자동 복구
- Operator가 변경사항 되돌림
- Admission Webhook이 변경 차단

**해결**:
- 변경 후 반드시 확인: `kubectl get ... -o yaml`
- GitOps 환경에서는 Git Repository 수정 필요
- 긴급 상황에서는 리소스 삭제가 더 확실

---

**4. 문제 해결 시 근본 원인 파악**

**실패한 접근**:
- Ingress 재시작 → 효과 없음
- Pod 재시작 → 효과 없음
- Network 확인 → 정상

**성공한 접근**:
- Ingress Controller **로그 확인** → "Connection reset"
- mTLS 설정 **직접 확인** → STRICT 모드 발견
- **근본 원인 제거** → 문제 해결

---

**5. Istio 도입 시 고려사항**

**장점**:
- mTLS로 Pod 간 암호화 통신
- Traffic Management (Canary, Blue/Green)
- Observability (Metrics, Tracing)

**주의점**:
- **외부 접근 경로 고려 필요** (Ingress → Pod)
- Global Policy는 신중하게 설정
- 문제 발생 시 디버깅 복잡도 증가
- PeerAuthentication 없이도 Istio는 자동으로 mTLS 적용

**권장 설정 (외부 접근 필요 시)**:
```yaml
# Global Policy 없음 (기본 동작 활용)
# 특정 서비스만 STRICT 필요 시 Selector 사용

# 예: 내부 API만 STRICT
apiVersion: security.istio.io/v1
kind: PeerAuthentication
metadata:
  name: internal-api-strict
  namespace: blog-system
spec:
  selector:
    matchLabels:
      app: internal-api
  mtls:
    mode: STRICT
```

---
