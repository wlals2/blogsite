# Kubernetes CI/CD 트러블슈팅 가이드

> Jenkins, GitHub Actions, ArgoCD 운영 중 발생한 모든 이슈와 해결 방법
> 최종 업데이트: 2026-01-26

---

## 목차

1. [kubectl Connection Refused 오류](#1-kubectl-connection-refused-오류)
2. [kubectl이 Jenkins HTML 반환 오류](#2-kubectl이-jenkins-html-반환-오류)
3. [Runner가 Job을 가져가지 않는 오류](#3-runner가-job을-가져가지-않는-오류)
4. [Cloudflare 캐시 퍼지 실패](#4-cloudflare-캐시-퍼지-실패)
5. [WAS Docker 빌드 경로 오류](#5-was-docker-빌드-경로-오류)
6. [ArgoCD 트러블슈팅](#6-argocd-트러블슈팅)
7. [Longhorn 스토리지 트러블슈팅](#7-longhorn-스토리지-트러블슈팅)
8. [MySQL 백업 CronJob 트러블슈팅](#8-mysql-백업-cronjob-트러블슈팅)
9. [Falco 런타임 보안 트러블슈팅](#9-falco-런타임-보안-트러블슈팅)
11. [Grafana Alloy 통합 트러블슈팅](#11-grafana-alloy-통합-트러블슈팅)
12. [Study 카테고리 필터 트러블슈팅](#12-study-카테고리-필터-트러블슈팅)

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

GitHub Actions의 `ubuntu-latest` 러너는 GitHub의 퍼블릭 클라우드에서 실행됩니다. 하지만 우리 Kubernetes 클러스터는 사설 네트워크(192.168.X.187:6443)에 있습니다.

```
[ GitHub 클라우드 ]                  [ 홈 네트워크 ]
  ubuntu-latest runner   ❌ 접근 불가   192.168.X.187 (k8s-cp)
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
    server: https://192.168.X.187:6443
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
# server: https://192.168.X.187:6443
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
    service: https://192.168.X.200:443
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
    service: http://192.168.X.200:80
  - hostname: argocd.jiminhome.shop
    service: https://192.168.X.200:443
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
사용자 → DNS (argocd → tunnel) → Ingress 규칙 (tunnel → 192.168.X.200) → ArgoCD
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
curl -k -I -H "Host: argocd.jiminhome.shop" https://192.168.X.200/
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
curl -I http://192.168.X.200/ -H "Host: blog.jiminhome.shop"
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
curl -I http://192.168.X.200/ -H "Host: blog.jiminhome.shop"
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
curl -I http://192.168.X.200/ -H "Host: blog.jiminhome.shop"

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
curl -I http://192.168.X.200/ -H "Host: blog.jiminhome.shop"
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

## 7. Longhorn 스토리지 트러블슈팅

### 7.1 CSI Plugin CrashLoopBackOff

**발생일**: 2026-01-22

#### 증상

```bash
kubectl get pods -A | grep -v Running | grep -v Completed

# 출력:
# longhorn-system   longhorn-csi-plugin-bb6t8   2/3   CrashLoopBackOff   37   57d
```

```bash
kubectl logs -n longhorn-system longhorn-csi-plugin-bb6t8 --all-containers --tail=30

# 출력:
# E0122 03:14:23.017453  1 main.go:68] "Failed to establish connection to CSI driver" err="context deadline exceeded"
# ... (반복)
```

---

#### 원인 분석

**1. Longhorn Manager 로그 확인**

```bash
kubectl logs -n longhorn-system -l app=longhorn-manager --tail=20

# 출력:
# level=warning msg="Precheck failed for creating new replica: disks are unavailable: no disk candidates found"
# ... node=k8s-worker1 volume=pvc-xxx
```

**2. Volume 상태 확인**

```bash
kubectl get volumes.longhorn.io -n longhorn-system

# 출력:
# NAME          STATE      ROBUSTNESS   SIZE          NODE
# pvc-xxx       attached   degraded     5368709120    k8s-worker1
# pvc-yyy       attached   degraded     10737418240   k8s-worker1
```

**핵심 문제**: Volume이 `degraded` 상태

---

**3. 근본 원인: Replica 수 vs 노드 수 불일치**

```bash
kubectl get volumes.longhorn.io -n longhorn-system -o yaml | grep numberOfReplicas

# 출력:
# numberOfReplicas: 3  # ← 3개 필요
```

```bash
kubectl get nodes

# 출력:
# k8s-cp        Ready   control-plane
# k8s-worker1   Ready   worker
# k8s-worker2   Ready   worker
#
# → Worker 노드: 2개
```

**문제**:
- 설정: `numberOfReplicas: 3` (replica 3개 필요)
- 현실: Worker 노드 2개 (replica 최대 2개 가능)
- Longhorn Anti-Affinity: 같은 노드에 같은 볼륨의 replica 2개 불가
- 결과: 3번째 replica 생성 불가 → `degraded` 상태 → CSI 연결 문제

```
┌─────────────────────────────────────────────────────────┐
│                Longhorn Replica 분산                     │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  numberOfReplicas: 3                                    │
│                                                         │
│  ┌─────────────┐    ┌─────────────┐    ┌──────────┐   │
│  │ k8s-worker1 │    │ k8s-worker2 │    │    ???   │   │
│  │  Replica 1  │    │  Replica 2  │    │ Replica 3│   │
│  │     ✅      │    │     ✅      │    │    ❌    │   │
│  └─────────────┘    └─────────────┘    └──────────┘   │
│                                                         │
│  → 3번째 replica 만들 노드 없음 → degraded              │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

---

#### 해결 방법

**Step 1: 기존 볼륨 Replica 수 변경 (kubectl patch)**

```bash
# 볼륨 1
kubectl patch volume pvc-1427badd-727a-4907-9005-01b5fb42f727 \
  -n longhorn-system \
  --type merge \
  -p '{"spec":{"numberOfReplicas":2}}'

# 볼륨 2
kubectl patch volume pvc-ea4dd4e0-dfd3-4161-96b5-b88aad5697b9 \
  -n longhorn-system \
  --type merge \
  -p '{"spec":{"numberOfReplicas":2}}'
```

**Step 2: Longhorn Helm Values 업데이트 (새 PVC용)**

```bash
# values.yaml 생성/수정
cat > ~/k8s-manifests/docs/helm/longhorn/values.yaml << 'EOF'
defaultSettings:
  defaultReplicaCount: 2
EOF

# Helm upgrade
cd ~/k8s-manifests/docs/helm/longhorn && ./install.sh
```

**Step 3: Git 커밋**

```bash
cd ~/k8s-manifests
git add docs/helm/longhorn/values.yaml
git commit -m "fix: Set Longhorn defaultReplicaCount to 2 (match node count)"
git push
```

---

#### 확인 방법

```bash
# 1. Volume 상태 확인
kubectl get volumes.longhorn.io -n longhorn-system

# 예상 결과:
# NAME          STATE      ROBUSTNESS   SIZE          NODE
# pvc-xxx       attached   healthy      5368709120    k8s-worker1   ✅
# pvc-yyy       attached   healthy      10737418240   k8s-worker1   ✅

# 2. CSI Plugin 상태 확인
kubectl get pods -n longhorn-system | grep csi-plugin

# 예상 결과:
# longhorn-csi-plugin-bb6t8   3/3   Running   0   1m   ✅

# 3. Replica 확인
kubectl get replicas.longhorn.io -n longhorn-system

# 각 볼륨당 2개 replica (worker1, worker2에 분산)
```

---

#### 배운 점

**1. Longhorn Replica 수 = 노드 수 이하로 설정**

| 노드 수 | 권장 Replica 수 | 이유 |
|---------|----------------|------|
| 1 | 1 | 분산 불가 |
| 2 | 2 | 최대 분산 |
| 3+ | 2~3 | 3개 권장 |

**2. Degraded vs Healthy**

| 상태 | 의미 | 데이터 안전성 |
|------|------|--------------|
| **healthy** | 모든 replica 정상 | ✅ 안전 |
| **degraded** | replica 부족 | ⚠️ 일부만 보호 |
| **faulted** | 복구 불가 | ❌ 위험 |

**3. Helm Values 관리 중요성**

```
문제 발생 시:
1. 기존 리소스: kubectl patch (긴급)
2. 새 리소스: Helm values 수정 (영구)
3. Git 커밋: 변경 이력 추적 (필수)
```

**4. 운영 점검 체크리스트 추가**

```bash
# Longhorn 상태 확인 (주기적)
kubectl get volumes.longhorn.io -n longhorn-system
# → 모두 healthy여야 함

kubectl get pods -n longhorn-system | grep -v Running
# → 출력 없어야 함
```

---

### 7.2 StorageClass numberOfReplicas 불일치

**발생일**: 2026-01-22

#### 증상

Longhorn 볼륨은 healthy로 변경됐지만, StorageClass는 여전히 replica 3으로 설정됨.

```bash
kubectl get storageclass longhorn -o yaml | grep numberOfReplicas

# 출력:
# numberOfReplicas: "3"  # ← 새 PVC 생성 시 문제 발생
```

---

#### 해결 방법

**Helm Upgrade로 StorageClass 설정 변경**

```bash
cd ~/k8s-manifests/docs/helm/longhorn
./install.sh
```

**또는 직접 Patch (Helm 사용 안 할 경우)**

```bash
kubectl patch storageclass longhorn -p '{"parameters":{"numberOfReplicas":"2"}}'
```

---

#### 확인

```bash
kubectl get storageclass longhorn -o yaml | grep numberOfReplicas

# 예상:
# numberOfReplicas: "2"  ✅
```

---

### 7.3 운영 점검 체크리스트

**Longhorn 상태 확인 명령어**

```bash
# 1. 전체 Pod 상태
kubectl get pods -n longhorn-system | grep -v Running

# 2. Volume 상태 (healthy 확인)
kubectl get volumes.longhorn.io -n longhorn-system

# 3. Replica 분산 확인
kubectl get replicas.longhorn.io -n longhorn-system

# 4. Node 디스크 상태
kubectl get nodes.longhorn.io -n longhorn-system -o yaml | grep -A5 "diskStatus"

# 5. StorageClass 설정
kubectl get storageclass longhorn -o yaml | grep numberOfReplicas
```

**정상 상태 기준**

| 항목 | 정상 값 |
|------|--------|
| CSI Plugin | 3/3 Running |
| Volume Robustness | healthy |
| Replica 수 | 노드 수 이하 |
| Disk Status | schedulable: true |

---

## 8. MySQL 백업 CronJob 트러블슈팅

**발생일**: 2026-01-22

### 8.1 CiliumNetworkPolicy로 인한 MySQL 연결 거부

#### 증상

MySQL 백업 CronJob이 MySQL 서버에 연결할 수 없음:

```
ERROR 2003 (HY000): Can't connect to MySQL server on 'mysql-service:3306' (110)
```

#### 원인 분석

**왜 발생했는가?**

CiliumNetworkPolicy `mysql-isolation`이 `app: was` 레이블을 가진 Pod에서만 MySQL 접근을 허용하도록 설정됨. 백업 Job의 레이블 `app: mysql-backup`은 허용 목록에 없었음.

```yaml
# 기존 mysql-isolation 정책 (문제)
ingress:
- fromEndpoints:
  - matchLabels:
      app: was  # ← mysql-backup 없음
```

**Hubble CLI로 확인한 결과:**
```bash
hubble observe --namespace blog-system --protocol TCP --port 3306

# 출력:
# mysql-backup → mysql-service:3306 DROPPED (policy denied)
```

#### 해결 방법

**1. mysql-isolation 정책에 mysql-backup 허용 추가**

```yaml
# cilium-netpol.yaml
ingress:
# Rule 3: mysql-backup → mysql:3306 허용 (CronJob 백업)
- fromEndpoints:
  - matchLabels:
      app: mysql-backup
      io.kubernetes.pod.namespace: blog-system
  toPorts:
  - ports:
    - port: "3306"
      protocol: TCP
```

**2. mysql-backup-isolation 정책 생성 (Egress 허용)**

```yaml
apiVersion: cilium.io/v2
kind: CiliumNetworkPolicy
metadata:
  name: mysql-backup-isolation
  namespace: blog-system
spec:
  endpointSelector:
    matchLabels:
      app: mysql-backup
  egress:
  # MySQL 접근
  - toEndpoints:
    - matchLabels:
        app: mysql
    toPorts:
    - ports:
      - port: "3306"
        protocol: TCP
  # DNS 허용
  - toEndpoints:
    - matchLabels:
        io.kubernetes.pod.namespace: kube-system
        k8s-app: kube-dns
    toPorts:
    - ports:
      - port: "53"
        protocol: UDP
  # S3 접근 (HTTPS)
  - toEntities:
    - world
    toPorts:
    - ports:
      - port: "443"
        protocol: TCP
```

#### 확인 방법

```bash
# 1. 정책 적용 확인
kubectl get ciliumnetworkpolicies -n blog-system

# 2. Hubble로 트래픽 확인
hubble observe --namespace blog-system -l app=mysql-backup

# 3. 수동 백업 테스트
kubectl create job --from=cronjob/mysql-backup mysql-backup-test -n blog-system
kubectl logs job/mysql-backup-test -n blog-system -c mysqldump
```

---

### 8.2 Istio Sidecar 주입으로 인한 Job 실패

#### 증상

MySQL 백업 Job이 `Init:0/2` 상태에서 멈춤:

```bash
kubectl get pods -n blog-system -l job-name=mysql-backup-manual

# 출력:
# mysql-backup-manual-xxxxx   Init:0/2   0          2m
```

#### 원인 분석

**왜 발생했는가?**

Istio sidecar가 Job Pod에 자동 주입되어 2개의 initContainer가 생성됨:
1. `istio-init` (Istio가 주입)
2. `mysqldump` (우리 설정)

Istio sidecar는 장기 실행 서비스용으로 설계되어 Job과 호환되지 않음.

**잘못된 annotation 위치:**
```yaml
# ❌ 잘못된 위치 (spec 레벨)
spec:
  annotations:
    sidecar.istio.io/inject: "false"  # 무시됨
```

#### 해결 방법

**annotation을 template.metadata 레벨로 이동:**

```yaml
apiVersion: batch/v1
kind: CronJob
spec:
  jobTemplate:
    spec:
      template:
        metadata:
          labels:
            app: mysql-backup
          annotations:  # ✅ 올바른 위치
            sidecar.istio.io/inject: "false"
        spec:
          # ...
```

#### 확인 방법

```bash
# 1. Pod에 sidecar가 없는지 확인
kubectl get pod -l app=mysql-backup -n blog-system -o jsonpath='{.items[*].spec.containers[*].name}'

# 예상 출력: s3-upload (istio-proxy 없어야 함)

# 2. initContainer 개수 확인
kubectl get pod -l app=mysql-backup -n blog-system -o jsonpath='{.items[*].spec.initContainers[*].name}'

# 예상 출력: mysqldump (istio-init 없어야 함)
```

---

### 8.3 Longhorn PVC Attach 타임아웃

#### 증상

백업 Job이 PVC 연결 대기 중 타임아웃:

```
Warning  FailedAttachVolume  Multi-Attach error for volume "pvc-xxx"
AttachVolume.Attach failed for volume "pvc-xxx": rpc error: code = DeadlineExceeded
```

#### 원인 분석

**왜 발생했는가?**

1. Longhorn 볼륨이 다른 노드에 attach된 상태
2. 2노드 클러스터에서 replica 3 설정으로 인한 스케줄링 실패
3. 노드 간 볼륨 마이그레이션 지연

#### 해결 방법

**emptyDir 사용 (권장)**

백업 파일은 S3에 영구 저장되므로 로컬 스토리지가 불필요:

```yaml
volumes:
- name: backup-storage
  emptyDir: {}  # Job 종료 시 자동 정리
```

**장점:**
- ✅ PVC 연결 문제 없음
- ✅ 노드 간 스케줄링 자유로움
- ✅ S3가 primary storage이므로 데이터 손실 없음

**단점:**
- ❌ Pod 재시작 시 임시 파일 손실 (S3 업로드 전 실패 시)

#### 대안: RWX PVC 사용

여러 Pod에서 동시 접근이 필요한 경우:

```yaml
apiVersion: v1
kind: PersistentVolumeClaim
spec:
  accessModes:
    - ReadWriteMany  # RWX
  storageClassName: longhorn
```

**주의**: Longhorn RWX는 NFS 기반으로 성능 저하 가능

---

### 8.4 AWS S3 인증 실패

#### 증상

S3 업로드 시 인증 오류:

```
An error occurred (InvalidAccessKeyId) when calling the PutObject operation
```

#### 원인 분석

1. AWS 자격 증명 Secret이 없거나 잘못됨
2. IAM 사용자에 S3 권한 없음
3. 버킷 정책이 접근 차단

#### 해결 방법

**1. Secret 생성**

```bash
kubectl create secret generic aws-s3-credentials \
  -n blog-system \
  --from-literal=AWS_ACCESS_KEY_ID=AKIA... \
  --from-literal=AWS_SECRET_ACCESS_KEY=... \
  --from-literal=AWS_DEFAULT_REGION=ap-northeast-2
```

**2. IAM 권한 확인**

최소 권한 정책:
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "s3:PutObject",
        "s3:GetObject",
        "s3:ListBucket"
      ],
      "Resource": [
        "arn:aws:s3:::jimin-mysql-backup",
        "arn:aws:s3:::jimin-mysql-backup/*"
      ]
    }
  ]
}
```

**3. 버킷 존재 확인**

```bash
aws s3 ls s3://jimin-mysql-backup/
```

#### 확인 방법

```bash
# 수동 S3 업로드 테스트
kubectl run s3-test --rm -it --image=amazon/aws-cli:2.15.0 \
  --env="AWS_ACCESS_KEY_ID=$(kubectl get secret aws-s3-credentials -n blog-system -o jsonpath='{.data.AWS_ACCESS_KEY_ID}' | base64 -d)" \
  --env="AWS_SECRET_ACCESS_KEY=$(kubectl get secret aws-s3-credentials -n blog-system -o jsonpath='{.data.AWS_SECRET_ACCESS_KEY}' | base64 -d)" \
  --env="AWS_DEFAULT_REGION=ap-northeast-2" \
  -- s3 ls s3://jimin-mysql-backup/
```

---

### 8.5 운영 점검 체크리스트

**MySQL 백업 상태 확인**

```bash
# 1. CronJob 상태
kubectl get cronjob mysql-backup -n blog-system

# 2. 최근 Job 히스토리
kubectl get jobs -n blog-system -l app=mysql-backup --sort-by=.metadata.creationTimestamp

# 3. 마지막 백업 로그
kubectl logs job/$(kubectl get jobs -n blog-system -l app=mysql-backup -o name | tail -1 | cut -d/ -f2) -n blog-system --all-containers

# 4. S3 백업 목록 (최근 5개)
aws s3 ls s3://jimin-mysql-backup/ | tail -5
```

**정상 상태 기준**

| 항목 | 정상 값 |
|------|--------|
| CronJob LAST SCHEDULE | 24시간 이내 |
| Job STATUS | Succeeded |
| S3 파일 개수 | 1-7개 (Lifecycle 정책) |
| 백업 파일 크기 | > 1KB |

---

## 9. Argo Rollouts 배포 트러블슈팅

### 9.1 블로그 포스트가 라이브 사이트에 안 보이는 문제

**발생일**: 2026-01-22

#### 증상

```bash
# Git에 커밋 완료, Hugo 빌드 완료
# 하지만 https://blog.jiminhome.shop/study/ 에서 새 포스트가 안 보임

curl -s https://blog.jiminhome.shop/study/ | grep "Istio Traffic"
# 출력 없음 (새 포스트 안 보임)
```

**착각한 원인:**
- 브라우저 캐시?
- Cloudflare 캐시?
- Hugo 빌드 문제?

---

#### 원인 분석

**1. 잘못된 배포 경로 이해**

```bash
# 처음 시도한 방법 (잘못됨)
hugo --minify
sudo rsync -av --delete public/ /var/www/blog/

# 왜 안 됐는가?
```

**nginx 설정 확인:**
```bash
cat /etc/nginx/sites-enabled/blog | grep proxy_pass

# 출력:
# proxy_pass http://192.168.X.187:31852;
```

**문제 발견:** nginx가 `/var/www/blog`를 직접 서빙하지 않고, **Kubernetes Ingress로 프록시**하고 있었음!

**실제 배포 경로:**
```
Git Push
    ↓
GitHub Actions (deploy-web.yml)
    ↓
Docker 이미지 빌드 (Hugo 포함)
    ↓
GHCR에 Push (ghcr.io/wlals2/blog-web:vXX)
    ↓
k8s-manifests 업데이트 (GitOps)
    ↓
ArgoCD 자동 동기화
    ↓
Argo Rollouts Canary 배포
    ↓
web Pods 업데이트
```

**2. 배포 상태 확인**

```bash
# 현재 배포된 이미지 확인
kubectl get rollout -n blog-system web -o jsonpath='{.spec.template.spec.containers[0].image}'

# 출력: ghcr.io/wlals2/blog-web:v47
```

`/var/www/blog`에 파일을 올려도 K8s Pod에는 영향이 없음!

---

#### 해결 방법

**올바른 배포 방법:**

1. **Git Push** (블로그 포스트 포함)
2. **GitHub Actions 자동 실행** (deploy-web.yml)
3. **ArgoCD 동기화 대기** (자동)

```bash
# 배포 상태 확인
kubectl argo rollouts get rollout web -n blog-system

# Rollout이 Paused 상태면 promote
kubectl argo rollouts promote web -n blog-system --full
```

---

#### 배운 점

**1. 배포 아키텍처 이해 필수**

| 환경 | 배포 방법 | 파일 위치 |
|------|----------|----------|
| **로컬 개발** | `hugo server` | 메모리 |
| **K8s 외부 nginx** | rsync to `/var/www/blog` | 서버 파일시스템 |
| **K8s 배포 (현재)** | Docker 이미지 빌드 | 컨테이너 내부 |

**2. 항상 실제 배포 경로 확인**

```bash
# nginx가 어디로 프록시하는지 확인
grep -r "proxy_pass" /etc/nginx/

# K8s Service 확인
kubectl get svc -A | grep <NodePort>
```

---

### 9.2 Canary Pod Pending (TopologySpreadConstraints)

**발생일**: 2026-01-22

#### 증상

```bash
kubectl get pods -n blog-system -l app=web

# 출력:
# NAME                   READY   STATUS    AGE
# web-56956db584-q2xxg   2/2     Running   3h
# web-56956db584-xtkc7   2/2     Running   4h
# web-6c7c9fb85d-8lqpc   0/2     Pending   5m   ← 새 canary pod
```

```bash
kubectl describe pod web-6c7c9fb85d-8lqpc -n blog-system | grep -A 5 "Events:"

# 출력:
# Warning  FailedScheduling  default-scheduler
# 0/3 nodes are available: 2 node(s) didn't match pod topology spread constraints
```

---

#### 원인 분석

**TopologySpreadConstraints 설정:**

```yaml
# web-rollout.yaml
topologySpreadConstraints:
  - maxSkew: 1
    topologyKey: kubernetes.io/hostname
    whenUnsatisfiable: DoNotSchedule  # ← Hard Constraint
    labelSelector:
      matchLabels:
        app: web
```

**문제:**
- Worker 노드: 2개 (k8s-worker1, k8s-worker2)
- 기존 stable pods: 각 노드에 1개씩 (총 2개)
- 새 canary pod: 배치할 노드 없음!

```
┌─────────────────────────────────────────────────────────┐
│           TopologySpreadConstraints 문제                 │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  maxSkew: 1, whenUnsatisfiable: DoNotSchedule           │
│                                                         │
│  ┌─────────────┐    ┌─────────────┐    ┌──────────┐   │
│  │ k8s-worker1 │    │ k8s-worker2 │    │    ???   │   │
│  │  stable-1   │    │  stable-2   │    │ canary-1 │   │
│  │     ✅      │    │     ✅      │    │    ❌    │   │
│  └─────────────┘    └─────────────┘    └──────────┘   │
│                                                         │
│  → 3번째 pod 배치 불가 (maxSkew 위반)                   │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

---

#### 해결 방법

**TopologySpreadConstraints를 Soft Constraint로 변경:**

```yaml
# web-rollout.yaml (수정 후)
topologySpreadConstraints:
  - maxSkew: 1
    topologyKey: kubernetes.io/hostname
    whenUnsatisfiable: ScheduleAnyway  # ← Soft Constraint
    labelSelector:
      matchLabels:
        app: web
```

**GitOps 배포:**
```bash
cd ~/k8s-manifests
git add blog-system/web-rollout.yaml
git commit -m "fix: Change topologySpreadConstraints to ScheduleAnyway"
git push
```

---

#### DoNotSchedule vs ScheduleAnyway

| 설정 | 동작 | 사용 시점 |
|------|------|----------|
| **DoNotSchedule** | 제약 만족 불가 시 배치 안 함 | 강력한 HA 필요, 노드 수 충분 |
| **ScheduleAnyway** | 제약 만족 불가 시에도 배치 | Canary 배포, 노드 수 제한적 |

**트레이드오프:**
- `DoNotSchedule`: HA 보장 vs 배포 실패 가능
- `ScheduleAnyway`: 배포 유연성 vs 일시적 불균형 허용

---

### 9.3 nginx CrashLoopBackOff (SecurityContext)

**발생일**: 2026-01-22

#### 증상

TopologySpreadConstraints 해결 후 새 pod 배치됐지만 CrashLoopBackOff:

```bash
kubectl get pods -n blog-system -l app=web

# 출력:
# NAME                   READY   STATUS             AGE
# web-7cc569d77c-hphf2   1/2     CrashLoopBackOff   30s
```

```bash
kubectl logs web-7cc569d77c-hphf2 -n blog-system -c nginx --tail=10

# 출력:
# nginx: [emerg] chown("/var/cache/nginx/client_temp", 101) failed (1: Operation not permitted)
```

---

#### 원인 분석

**SecurityContext 설정 확인:**

```yaml
# web-rollout.yaml
securityContext:
  allowPrivilegeEscalation: false
  capabilities:
    drop:
      - ALL
    add:
      - NET_BIND_SERVICE  # 80 포트 바인딩만 허용
```

**문제:**
- nginx 시작 시 `/var/cache/nginx` 디렉토리 소유권 변경 필요
- `CHOWN` capability가 drop되어 있어 실패
- `SETUID`, `SETGID`도 worker process 전환에 필요

**nginx 시작 과정:**
```
1. master process (root) 시작
2. /var/cache/nginx 디렉토리 생성 및 chown ← 실패!
3. worker process 생성 (nginx user로 전환) ← SETUID/SETGID 필요
4. 80 포트 바인딩 ← NET_BIND_SERVICE 필요
```

---

#### 해결 방법

**필요한 capabilities 추가:**

```yaml
# web-rollout.yaml (수정 후)
securityContext:
  allowPrivilegeEscalation: false
  capabilities:
    drop:
      - ALL
    add:
      - NET_BIND_SERVICE  # 80 포트 바인딩 허용
      - CHOWN             # nginx cache 디렉토리 소유권 변경
      - SETUID            # worker process 권한 전환
      - SETGID            # worker process 그룹 전환
```

**GitOps 배포:**
```bash
cd ~/k8s-manifests
git add blog-system/web-rollout.yaml
git commit -m "fix: Add CHOWN, SETUID, SETGID capabilities for nginx startup"
git push
```

---

#### Linux Capabilities 설명

| Capability | 용도 | nginx 필요 여부 |
|------------|------|----------------|
| **NET_BIND_SERVICE** | 1024 이하 포트 바인딩 | ✅ 80 포트 |
| **CHOWN** | 파일 소유권 변경 | ✅ cache 디렉토리 |
| **SETUID** | UID 변경 (root → nginx) | ✅ worker process |
| **SETGID** | GID 변경 | ✅ worker process |
| **DAC_OVERRIDE** | 파일 권한 무시 | ❌ 불필요 |
| **SYS_ADMIN** | 관리자 권한 | ❌ 위험 |

**보안 고려:**
- `drop: ALL` 후 필요한 것만 추가 (최소 권한 원칙)
- `allowPrivilegeEscalation: false` 유지
- 대안: `nginx:unprivileged` 이미지 사용 (1024+ 포트)

---

### 9.4 Argo Rollouts 트러블슈팅 체크리스트

**Canary 배포 문제 발생 시:**

```bash
# 1. Rollout 상태 확인
kubectl argo rollouts get rollout web -n blog-system

# 2. Pod 상태 확인
kubectl get pods -n blog-system -l app=web

# 3. Pending이면 → Events 확인
kubectl describe pod <pod-name> -n blog-system | tail -20

# 4. CrashLoopBackOff면 → 로그 확인
kubectl logs <pod-name> -n blog-system -c nginx --tail=50

# 5. Rollout Paused면 → Promote
kubectl argo rollouts promote web -n blog-system --full

# 6. Rollout 실패면 → Abort 후 재시도
kubectl argo rollouts abort web -n blog-system
kubectl argo rollouts retry rollout web -n blog-system
```

---

### 9.5 최종 상태

**수정된 web-rollout.yaml:**

```yaml
topologySpreadConstraints:
  - maxSkew: 1
    topologyKey: kubernetes.io/hostname
    whenUnsatisfiable: ScheduleAnyway  # ✅ Soft constraint

securityContext:
  allowPrivilegeEscalation: false
  capabilities:
    drop:
      - ALL
    add:
      - NET_BIND_SERVICE  # ✅ 80 포트
      - CHOWN             # ✅ cache 디렉토리
      - SETUID            # ✅ worker process
      - SETGID            # ✅ worker process
```

**배포 결과:**

```bash
kubectl argo rollouts get rollout web -n blog-system

# 출력:
# Name:            web
# Status:          ✔ Healthy
# Images:          ghcr.io/wlals2/blog-web:v47 (stable)
# Replicas:        2/2/2
```

**블로그 확인:**

```bash
curl -s https://blog.jiminhome.shop/study/ | grep "Istio Traffic"

# 출력: ✅ 새 포스트 보임
```

---

### 9.6 WAS Docker 빌드 실패 (MySQL Connection Refused)

**발생일**: 2026-01-22

#### 증상

```bash
# GitHub Actions 또는 로컬 Docker 빌드 시
docker build -t test-was .

# 에러:
# java.net.ConnectException: Connection refused
# BoardApplicationTests > contextLoads() FAILED
```

---

#### 원인 분석

**Maven 빌드 과정:**
```
1. ./mvnw clean package
   ↓
2. 컴파일 완료
   ↓
3. 테스트 실행 (기본 동작)
   ↓
4. BoardApplicationTests.contextLoads()
   ↓
5. Spring Context 로드 → MySQL 연결 시도
   ↓
6. Docker 빌드 환경에 MySQL 없음 → Connection refused
   ↓
7. 빌드 실패
```

**프로덕션 vs Docker 빌드 환경:**

| 환경 | MySQL | 테스트 결과 |
|------|-------|------------|
| **K8s (프로덕션)** | mysql-service:3306 | ✅ 연결 성공 |
| **Docker 빌드** | 없음 | ❌ Connection refused |
| **로컬 개발** | localhost:3306 | ✅ (MySQL 설치 시) |

---

#### 해결 방법

**Dockerfile 수정:**

```dockerfile
# blog-k8s-project/was/Dockerfile (Line 34)

# Before (문제)
RUN ./mvnw clean package

# After (해결)
RUN ./mvnw clean package -DskipTests
```

**주석 추가 (명확한 의도 표시):**
```dockerfile
# Maven 빌드 (테스트 스킵)
# -DskipTests: Docker 빌드 환경에 MySQL 없음 → 통합 테스트 실패 방지
# 테스트는 별도 CI 단계에서 testcontainers 또는 H2로 실행 권장
RUN ./mvnw clean package -DskipTests
```

---

#### 향후 개선 (P1)

**테스트 활성화 방법:**

| 방법 | 설명 | 복잡도 |
|------|------|--------|
| **Testcontainers** | Docker-in-Docker로 임시 MySQL 생성 | 중 |
| **H2 In-Memory** | application-test.properties에 H2 설정 | 낮음 |
| **별도 CI 단계** | 빌드와 테스트 분리 | 중 |

**H2 설정 예시:**
```properties
# src/test/resources/application-test.properties
spring.datasource.url=jdbc:h2:mem:testdb
spring.datasource.driver-class-name=org.h2.Driver
spring.jpa.hibernate.ddl-auto=create-drop
```

**참고:** [DEVSECOPS-ARCHITECTURE.md](../k8s-manifests/docs/DEVSECOPS-ARCHITECTURE.md) - P1 선택 항목

---

#### Trivy와의 관계

**오해:** "Trivy 보안 스캔이 빌드를 실패시키는 것 아닌가?"

**실제:**
```yaml
# deploy-was.yml (Line 99)
- name: Scan image with Trivy
  uses: aquasecurity/trivy-action@0.28.0
  with:
    exit-code: '0'  # ← 취약점 발견해도 빌드 실패 안 함
```

- Trivy는 Docker 빌드 **후**에 실행됨
- `exit-code: '0'` 설정으로 경고만 출력
- 실제 빌드 실패는 Maven 테스트 단계에서 발생

---

## 9. Falco 런타임 보안 트러블슈팅

> Falco (eBPF 기반 IDS) 운영 중 발생하는 이슈와 해결 방법

**관련 문서:** [infrastructure/security-falco.md](infrastructure/security-falco.md)

---

### 9.1 inotify 초기화 실패

#### 증상

```bash
kubectl logs -n falco -l app.kubernetes.io/name=falco

# 에러 메시지:
Error: could not initialize inotify handler
```

**Pod 상태:**
```bash
kubectl get pods -n falco
# NAME           READY   STATUS             RESTARTS
# falco-xxxxx    0/2     CrashLoopBackOff   5
```

#### 원인 분석

**왜 발생했는가?**

Linux 커널의 `inotify` 시스템은 파일 시스템 이벤트를 모니터링합니다. Falco는 룰 파일 변경을 감지하기 위해 inotify를 사용합니다.

```
┌─────────────────────────────────────────────────────────────────┐
│  inotify 리소스 제한                                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  max_user_watches    = 감시할 수 있는 파일/디렉터리 수           │
│  max_user_instances  = 생성할 수 있는 inotify 인스턴스 수        │
│                                                                  │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │  기본값                                                    │  │
│  │  max_user_watches:    8192                                │  │
│  │  max_user_instances:  128                                 │  │
│  │                                                           │  │
│  │  Kubernetes 환경에서는 부족할 수 있음                      │  │
│  │  - 다수의 컨테이너 실행                                   │  │
│  │  - Falco, Prometheus 등 모니터링 도구                     │  │
│  └───────────────────────────────────────────────────────────┘  │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

#### 해결 방법

**1. 현재 값 확인:**
```bash
# 해당 노드에 SSH 접속 후
cat /proc/sys/fs/inotify/max_user_watches
cat /proc/sys/fs/inotify/max_user_instances
```

**2. 임시 적용 (재부팅 시 초기화):**
```bash
sudo sysctl -w fs.inotify.max_user_watches=524288
sudo sysctl -w fs.inotify.max_user_instances=512
```

**3. 영구 적용:**
```bash
echo "fs.inotify.max_user_watches=524288" | sudo tee -a /etc/sysctl.conf
echo "fs.inotify.max_user_instances=512" | sudo tee -a /etc/sysctl.conf
sudo sysctl -p
```

**4. Falco Pod 재시작:**
```bash
# 특정 노드의 Falco Pod 삭제 (DaemonSet이 재생성)
kubectl delete pod -n falco -l app.kubernetes.io/name=falco --field-selector spec.nodeName=<노드명>
```

#### 확인 방법

```bash
# Pod 상태 확인
kubectl get pods -n falco -o wide
# NAME           READY   STATUS    NODE
# falco-xxxxx    2/2     Running   k8s-worker1  ← 정상

# 로그 확인
kubectl logs -n falco -l app.kubernetes.io/name=falco -c falco | head -20
```

---

### 9.2 Falcosidekick 연결 실패

#### 증상

```bash
kubectl logs -n falco deploy/falco-falcosidekick

# 에러 메시지:
[ERROR] Loki - Post "http://loki-stack.monitoring.svc.cluster.local:3100/loki/api/v1/push": dial tcp: lookup loki-stack.monitoring.svc.cluster.local: no such host
```

#### 원인 분석

Falcosidekick이 Loki 서비스에 연결할 수 없습니다. 가능한 원인:

1. **Loki 서비스가 실행 중이 아님**
2. **서비스 이름이 잘못됨**
3. **Namespace가 다름**
4. **NetworkPolicy가 차단**

#### 해결 방법

**1. Loki 서비스 확인:**
```bash
kubectl get svc -n monitoring | grep loki
# NAME         TYPE        CLUSTER-IP     PORT(S)
# loki-stack   ClusterIP   10.96.xxx.xx   3100/TCP
```

**2. values.yaml 설정 확인:**
```yaml
# /home/jimin/k8s-manifests/docs/helm/falco/values.yaml
falcosidekick:
  config:
    loki:
      hostport: "http://loki-stack.monitoring.svc.cluster.local:3100"
```

**3. DNS 해상도 테스트:**
```bash
kubectl run -it --rm debug --image=busybox --restart=Never -- nslookup loki-stack.monitoring.svc.cluster.local
```

**4. Helm upgrade (설정 변경 시):**
```bash
helm upgrade falco falcosecurity/falco \
  -n falco \
  -f /home/jimin/k8s-manifests/docs/helm/falco/values.yaml
```

---

### 9.3 modern_ebpf 드라이버 실패

#### 증상

```bash
kubectl logs -n falco -l app.kubernetes.io/name=falco -c falco

# 에러 메시지:
Error: failed to load modern BPF probe
```

#### 원인 분석

**커널 버전이 modern_ebpf를 지원하지 않습니다.**

| 드라이버 | 최소 커널 버전 | 비고 |
|----------|---------------|------|
| modern_ebpf | 5.8+ | 권장 (CO-RE 기반) |
| ebpf (classic) | 4.14+ | 폭넓은 호환성 |
| kmod | 2.6+ | 커널 모듈 필요 |

#### 해결 방법

**1. 커널 버전 확인:**
```bash
uname -r
# 5.8 이상이어야 modern_ebpf 사용 가능
```

**2. 드라이버 변경 (커널이 5.8 미만인 경우):**
```yaml
# values.yaml 수정
driver:
  kind: ebpf  # modern_ebpf → ebpf로 변경
```

**3. Helm upgrade:**
```bash
helm upgrade falco falcosecurity/falco \
  -n falco \
  -f /home/jimin/k8s-manifests/docs/helm/falco/values.yaml
```

---

### 9.4 Alert가 Loki에 저장되지 않음

#### 증상

Grafana에서 Falco alert를 조회해도 결과가 없음:
```
{job="falco"} | json
# 결과 없음
```

#### 확인 순서

**1. Falco가 이벤트를 탐지하는지 확인:**
```bash
# 테스트 이벤트 발생 (shell 실행)
kubectl exec -it $(kubectl get pod -l app=web -o name | head -1) -n blog-system -- /bin/sh -c "exit"

# Falco 로그 확인
kubectl logs -n falco -l app.kubernetes.io/name=falco -c falco | grep -i "terminal"
```

**2. Falcosidekick 로그 확인:**
```bash
kubectl logs -n falco deploy/falco-falcosidekick | tail -20
# [INFO] Loki - Post OK (204)  ← 정상
# [ERROR] Loki - ...           ← 문제
```

**3. Loki에서 직접 확인:**
```bash
kubectl port-forward -n monitoring svc/loki-stack 3100:3100 &
curl -G "http://localhost:3100/loki/api/v1/query" --data-urlencode 'query={job="falco"}'
```

**4. minimumpriority 확인:**
```yaml
# values.yaml
config:
  loki:
    minimumpriority: "warning"  # notice 이하는 전송 안 됨
```

---

### 9.5 Falco UI 접속 불가

#### 증상

```bash
kubectl port-forward -n falco svc/falco-falcosidekick-ui 2802:2802
# 브라우저에서 http://localhost:2802 접속 실패
```

#### 해결 방법

**1. UI Pod 상태 확인:**
```bash
kubectl get pods -n falco | grep ui
# falco-falcosidekick-ui-xxx   1/1   Running
```

**2. 서비스 확인:**
```bash
kubectl get svc -n falco | grep ui
# falco-falcosidekick-ui   ClusterIP   10.96.xxx.xx   2802/TCP
```

**3. UI가 비활성화된 경우:**
```yaml
# values.yaml 확인
falcosidekick:
  webui:
    enabled: true  # false면 UI 없음
```

---

### 9.6 BuildKit Alert (False Positive)

> ⭐ 이 섹션은 실제 테스트 중 발견된 False Positive 사례입니다 (2026-01-23)

#### 증상

**GitHub Actions에서 WAS 이미지 빌드 시 CRITICAL Alert 발생:**

```bash
# Grafana Loki 쿼리: {priority="Critical"}

🚨 Alert:
rule="Drop and execute new binary in container"
priority=Critical
k8s_ns_name=default
container_image=moby/buildkit:buildx-stable-1
proc_name=/check
proc_cmdline=/check
```

**Alert 메시지:**
```
Drift Detection: New executable created and immediately executed

File: /check
Process: /check
Container: moby/buildkit
```

#### 원인 분석

**왜 발생했는가?**

1. **GitHub Actions 워크플로우**:
   ```yaml
   # .github/workflows/deploy-was.yml
   - name: Build and Push Docker Image
     uses: docker/build-push-action@v5
   ```

2. **BuildKit 동작 방식**:
   ```
   ┌─────────────────────────────────────────────────────────────────┐
   │  Docker Build 과정 (BuildKit 사용)                              │
   ├─────────────────────────────────────────────────────────────────┤
   │                                                                  │
   │  1. buildx가 BuildKit 컨테이너 생성                              │
   │     container_image: moby/buildkit:buildx-stable-1               │
   │     ↓                                                            │
   │  2. BuildKit이 Dockerfile 실행                                   │
   │     - RUN 명령 실행                                              │
   │     - 레이어 생성                                                │
   │     ↓                                                            │
   │  3. 헬스체크용 바이너리 생성 및 실행 ← Falco 탐지!               │
   │     - /check 바이너리 생성 (컨테이너 내부)                       │
   │     - /check 즉시 실행 (헬스체크)                                │
   │     ↓                                                            │
   │  4. 이미지 완성 및 레지스트리 푸시                                │
   │                                                                  │
   └─────────────────────────────────────────────────────────────────┘
   ```

3. **Falco 룰이 탐지한 이유**:
   ```yaml
   # Falco 기본 룰: "Drop and execute new binary in container"
   - rule: Drop and execute new binary in container
     desc: Detect new binary created and executed immediately (Drift Detection)
     condition: >
       spawned_process and
       proc.is_exe_from_memfd=true and  # 메모리에서 실행파일 생성
       container
     output: >
       Drift Detection: New executable created and immediately executed
       (file=%proc.exepath container=%container.name)
     priority: CRITICAL
   ```

   **"Drop"의 의미**:
   - ❌ Falco가 차단했다는 뜻 아님
   - ✅ 공격자가 악성 바이너리를 "떨어뜨렸다" (Drop = 생성/설치)는 뜻
   - 보안 용어: "Dropper" = 악성코드 설치 프로그램

4. **왜 문제처럼 보이는가?**:
   ```
   정상 시나리오: 컨테이너 이미지는 "불변(Immutable)"
   → 컨테이너 내부에서 새 바이너리 생성 안 함
   → 모든 바이너리는 이미지에 미리 포함

   BuildKit 시나리오: 빌드 과정의 특수성
   → 컨테이너 내부에서 /check 바이너리 동적 생성
   → 정상 동작이지만 Falco 룰에 걸림
   ```

#### 판단: False Positive (정상 동작)

**이유:**

| 판단 근거 | 설명 |
|-----------|------|
| ✅ **정상 프로세스** | GitHub Actions BuildKit은 공식 Docker 빌드 도구 |
| ✅ **신뢰할 수 있는 이미지** | `moby/buildkit:buildx-stable-1` (Docker 공식 이미지) |
| ✅ **헬스체크 용도** | `/check` 바이너리는 BuildKit 헬스체크 전용 |
| ✅ **격리된 환경** | GitHub Actions Runner는 격리된 빌드 환경 |
| ✅ **공격 벡터 없음** | 악의적 코드 실행 아님, 정상 빌드 프로세스 |

**실제 공격과 차이점:**

| 항목 | BuildKit (정상) | 실제 공격 |
|------|----------------|----------|
| **이미지** | moby/buildkit (공식) | 악의적 이미지 또는 침투된 Pod |
| **프로세스** | /check (헬스체크) | /tmp/backdoor, /var/tmp/malware |
| **목적** | 빌드 도구 동작 | 백도어, rootkit, 크립토마이너 |
| **네트워크** | 레지스트리 푸시만 | C&C 서버 연결 시도 |
| **지속성** | 빌드 완료 후 삭제 | 영구 설치 시도 |

#### 해결 방법

**옵션 1: 무시 (권장)**

이 Alert는 정상 동작이므로 무시합니다.

```bash
# Grafana에서 BuildKit Alert 필터링
{priority="Critical"} | json | container_image !~ "buildkit"
```

**옵션 2: 예외 규칙 추가**

Falco values.yaml에 예외 추가:

```yaml
# /home/jimin/k8s-manifests/docs/helm/falco/values.yaml
customRules:
  blog-rules.yaml: |-
    - rule: Drop and execute new binary in container
      append: true
      exceptions:
        - name: buildkit_binaries
          fields:
            - container.image.repository
          comps:
            - startswith
          values:
            - moby/buildkit
```

**적용:**
```bash
helm upgrade falco falcosecurity/falco \
  -n falco \
  -f /home/jimin/k8s-manifests/docs/helm/falco/values.yaml
```

**옵션 3: Priority 변경**

BuildKit Alert를 CRITICAL에서 NOTICE로 낮춤:

```yaml
- rule: Drop and execute new binary in container for buildkit
  desc: BuildKit normal operation
  condition: >
    spawned_process and
    proc.is_exe_from_memfd=true and
    container and
    container.image.repository startswith "moby/buildkit"
  output: >
    BuildKit normal operation (헬스체크)
    (file=%proc.exepath container=%container.name)
  priority: NOTICE  # CRITICAL → NOTICE
```

#### 권장 조치

**현재 상황**:
- BuildKit은 GitHub Actions에서만 실행
- 운영 클러스터 내부 Pod가 아님
- False Positive 빈도: 이미지 빌드 시에만 (하루 1-5회)

**권장**:
1. ✅ **옵션 1 (무시)** - 가장 간단, 실제 운영에 영향 없음
2. 향후 IPS 활성화 시 **옵션 2 (예외 추가)** 적용
3. 실제 공격과 구분 가능:
   - 실제 공격: blog-system Pod에서 발생
   - BuildKit: default namespace, moby/buildkit 이미지

#### 핵심 교훈

**Alert 판단 프로세스**:

```
1. Alert 발생
   ↓
2. 컨텍스트 확인
   - container_image: 신뢰할 수 있는가?
   - k8s_ns_name: 예상된 namespace인가?
   - proc_name: 정상 프로세스인가?
   ↓
3. 판단
   - False Positive → 무시 또는 예외 추가
   - 실제 공격 → 즉시 대응
```

**False Positive 학습**:
- IDS/IPS 운영 초기에는 False Positive 많이 발생
- 패턴 학습을 통해 예외 규칙 추가
- 1-2주 운영 후 노이즈 감소

---

### Quick Reference

| 증상 | 원인 | 해결 |
|------|------|------|
| `CrashLoopBackOff` + inotify 에러 | inotify 제한 | sysctl 설정 증가 |
| Loki no such host | DNS/서비스 문제 | Loki 서비스 확인 |
| BPF probe 실패 | 커널 버전 낮음 | ebpf 드라이버로 변경 |
| Alert 없음 | priority 필터 | minimumpriority 확인 |
| UI 접속 불가 | UI 비활성화 | webui.enabled: true |
| BuildKit CRITICAL Alert | False Positive | 무시 또는 예외 추가 |

---

---

## 10. Istio Gateway 마이그레이션 트러블슈팅

### 배경

**작업**: Nginx Ingress 제거, Istio Gateway로 모든 외부 트래픽 통합 (2026-01-24)

**목표**: MetalLB LoadBalancer IP (192.168.1.200)를 Nginx Ingress에서 Istio Gateway로 이전

---

### 이슈 1: MetalLB IP 할당 실패 (loadBalancerIP vs annotation 충돌)

#### 증상

```bash
kubectl get svc -n istio-system istio-ingressgateway
# NAME                   TYPE           EXTERNAL-IP   PORT(S)
# istio-ingressgateway   LoadBalancer   <pending>     ...
```

**MetalLB Controller 로그**:
```
service can not have both metallb.universe.tf/loadBalancerIPs and svc.Spec.LoadBalancerIP
```

#### 원인 분석

**왜 발생했는가?**

MetalLB v0.13부터 IP 지정 방식이 변경되었습니다:

| MetalLB 버전 | IP 지정 방식 | 상태 |
|--------------|--------------|------|
| **< v0.13** | `spec.loadBalancerIP` | Deprecated |
| **≥ v0.13** | `metallb.universe.tf/loadBalancerIPs` annotation | 권장 |

**잘못된 manifest (두 가지 동시 사용)**:
```yaml
apiVersion: v1
kind: Service
metadata:
  name: istio-ingressgateway
  annotations:
    metallb.universe.tf/loadBalancerIPs: 192.168.1.200  # 신규 방식
spec:
  type: LoadBalancer
  loadBalancerIP: 192.168.1.200  # 구 방식 (deprecated)
```

**MetalLB 에러**: 두 가지 방식을 동시에 사용할 수 없음

#### 해결 방법

**방법 1: annotation만 사용 (권장)**

```yaml
apiVersion: v1
kind: Service
metadata:
  name: istio-ingressgateway
  namespace: istio-system
  annotations:
    metallb.universe.tf/ip-allocated-from-pool: local-pool
    metallb.universe.tf/loadBalancerIPs: 192.168.1.200  # ✅
spec:
  type: LoadBalancer
  # loadBalancerIP 제거 ✅
  selector:
    app: istio-ingressgateway
    istio: ingressgateway
  ports:
  - name: http2
    port: 80
    targetPort: 8080
```

**적용**:
```bash
kubectl apply -f istio-ingressgateway-svc.yaml
```

**검증**:
```bash
kubectl get svc -n istio-system istio-ingressgateway
# NAME                   TYPE           EXTERNAL-IP     PORT(S)
# istio-ingressgateway   LoadBalancer   192.168.1.200   80:XXX/TCP,443:XXX/TCP ✅
```

#### 배운 점

1. **MetalLB 버전 확인 필수**: v0.13 이상이면 annotation 방식 사용
2. **Deprecated 필드 제거**: `spec.loadBalancerIP` 사용 금지
3. **IP Pool 설정 확인**: `metallb.universe.tf/ip-allocated-from-pool` annotation으로 Pool 지정 가능

---

### 이슈 2: VirtualService 503 Error (no healthy upstream)

#### 증상

```bash
curl -I http://192.168.1.200/ -H "Host: blog.jiminhome.shop"
# HTTP/1.1 503 Service Unavailable
```

**Istio Gateway 로그**:
```
no healthy upstream
```

#### 원인 분석

**VirtualService destination에 subset 누락**:
```yaml
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: blog-routes
spec:
  http:
  - route:
    - destination:
        host: web-service
        # subset: stable  # ❌ 누락
        port:
          number: 80
```

**DestinationRule**:
```yaml
apiVersion: networking.istio.io/v1beta1
kind: DestinationRule
metadata:
  name: web-dest-rule
spec:
  host: web-service
  subsets:
  - name: stable  # subset 정의됨
  - name: canary
```

**문제**: VirtualService가 subset을 지정하지 않아 라우팅 실패

#### 해결 방법

**VirtualService에 subset 추가**:
```yaml
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: blog-routes
spec:
  http:
  - route:
    - destination:
        host: web-service
        subset: stable  # ✅ 추가
        port:
          number: 80
```

**검증**:
```bash
curl -I http://192.168.1.200/ -H "Host: blog.jiminhome.shop"
# HTTP/1.1 200 OK ✅
```

---

### 이슈 3: TLS_error WRONG_VERSION_NUMBER (mTLS 강제 적용)

#### 증상

```bash
curl -I http://192.168.1.200/ -H "Host: blog.jiminhome.shop"
# curl: (52) Empty reply from server
```

**Istio Gateway 로그**:
```
TLS_error:|268435703:SSL_routines:OPENSSL_internal:WRONG_VERSION_NUMBER:TLS_error_end
upstream_reset_before_response_started{remote_connection_failure}
```

#### 원인 분석

**DestinationRule에서 mTLS 강제 적용**:
```yaml
apiVersion: networking.istio.io/v1beta1
kind: DestinationRule
metadata:
  name: web-dest-rule
spec:
  host: web-service
  trafficPolicy:
    tls:
      mode: ISTIO_MUTUAL  # ❌ mTLS 강제
```

**문제**:
1. Istio Gateway → Service 트래픽은 **평문 HTTP**
2. DestinationRule이 **모든 트래픽에 mTLS 강제**
3. Gateway가 TLS 핸드셰이크 시도 → Service는 평문만 지원 → 연결 실패

#### 해결 방법

**DestinationRule mTLS 비활성화**:
```yaml
apiVersion: networking.istio.io/v1beta1
kind: DestinationRule
metadata:
  name: web-dest-rule
spec:
  host: web-service
  trafficPolicy:
    tls:
      mode: DISABLE  # ✅ Gateway → Service는 평문
```

**검증**:
```bash
curl -I http://192.168.1.200/ -H "Host: blog.jiminhome.shop"
# HTTP/1.1 200 OK ✅
```

#### 배운 점

**Istio Gateway 트래픽 플로우**:
```
[External] → [Gateway (평문 HTTP)] → [Service (평문)] → [Pod]
              ↑ mTLS DISABLE 필요
```

**Service ↔ Service 트래픽**:
```
[web Pod] → [was Pod]
    ↑ mTLS ISTIO_MUTUAL 가능 (Istio sidecar 존재)
```

**중요**: Gateway → Service는 항상 평문, Service ↔ Service는 mTLS 가능

---

### 이슈 4: Cross-Namespace VirtualService 404 Error

#### 증상

```bash
curl -I http://192.168.1.200/ -H "Host: monitoring.jiminhome.shop"
# HTTP/1.1 404 Not Found
```

**Istio Gateway 로그**:
```
route_not_found
```

#### 원인 분석

**잘못된 VirtualService (FQDN 사용)**:
```yaml
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: monitoring-routes
  namespace: monitoring
spec:
  gateways:
  - blog-system/blog-gateway  # Cross-namespace Gateway 참조
  http:
  - route:
    - destination:
        host: grafana.monitoring.svc.cluster.local  # ❌ FQDN
        port:
          number: 3000
```

**문제**: Istio가 FQDN을 해석하지 못함

#### 해결 방법

**Short name 사용 (Same namespace)**:
```yaml
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: monitoring-routes
  namespace: monitoring
spec:
  gateways:
  - blog-system/blog-gateway  # ✅ Cross-namespace Gateway
  http:
  - route:
    - destination:
        host: grafana  # ✅ Short name (same namespace)
        port:
          number: 3000
```

**검증**:
```bash
curl -I http://192.168.1.200/ -H "Host: monitoring.jiminhome.shop"
# HTTP/1.1 302 Found (Grafana login) ✅
```

#### 배운 점

**VirtualService host 규칙**:
- ✅ Same namespace: Short name (`grafana`)
- ✅ Cross-namespace: FQDN (`grafana.monitoring.svc.cluster.local`)
- ⚠️  **주의**: Gateway는 Cross-namespace 참조 가능 (`blog-system/blog-gateway`)

---

### 요약: Istio Gateway 마이그레이션 체크리스트

**1. MetalLB Service**
- [x] `spec.loadBalancerIP` 제거
- [x] `metallb.universe.tf/loadBalancerIPs` annotation 사용
- [x] IP 192.168.1.200 할당 확인

**2. Gateway 리소스**
- [x] `*.jiminhome.shop` wildcard host 설정
- [x] `istio: ingressgateway` selector 확인

**3. VirtualService**
- [x] Destination subset 지정 (DestinationRule과 일치)
- [x] Cross-namespace Gateway 참조 (`blog-system/blog-gateway`)
- [x] Short name 사용 (same namespace)

**4. DestinationRule**
- [x] Gateway → Service: `tls.mode: DISABLE`
- [x] Service ↔ Service: `tls.mode: ISTIO_MUTUAL` (선택)

**5. 검증**
- [x] 모든 서비스 HTTP 200/302 응답 확인
- [x] Nginx Ingress namespace 삭제 확인


---

## 11. Grafana Alloy 통합 트러블슈팅

> **프로젝트**: Promtail + node-exporter + cadvisor → Grafana Alloy 완전 통합
> **구축 일자**: 2026-01-26
> **목표**: 3개 모니터링 Agent를 1개로 통합하여 운영 복잡도 67% 감소

### 배경

**Promtail EOL 대응**:
- Promtail은 2026년 3월 2일에 End-of-Life (구축 시점: 37일 남음)
- Grafana Labs 공식 권장: Promtail → Alloy 마이그레이션

**통합 결정**:
- 단순히 Promtail만 교체할 경우 → 여전히 12 Pods 운영 (promtail 4 + node-exporter 4 + cadvisor 4)
- 완전 통합 시 → **4 Pods로 감소 (67% 감소)**

**Before vs After**:
```
Before (12 Pods):
  Promtail DaemonSet        4 Pods  (로그 수집)
  node-exporter DaemonSet   4 Pods  (시스템 메트릭)
  cadvisor DaemonSet        4 Pods  (컨테이너 메트릭)

After (4 Pods):
  Alloy DaemonSet           4 Pods  (All-in-One)
    ├─ 로그 수집 → Loki
    ├─ 시스템 메트릭 → Prometheus (node_exporter 역할)
    └─ Alloy 자체 메트릭 → Prometheus
```

---

### 문제 1: Alloy 로그 수집 권한 에러

#### 증상
```
ts=2026-01-26T00:17:30Z level=error msg="error getting pod logs"
component_path=/ component_id=loki.source.kubernetes.pods
err="pods \"was-5bb794b9f9-dxnxb\" is forbidden:
User \"system:serviceaccount:monitoring:alloy\" cannot get resource \"pods/log\"
in API group \"\" in the namespace \"blog-system\""
```

#### 원인 분석

**왜 발생했는가?**

Alloy의 `loki.source.kubernetes` 컴포넌트는 Kubernetes API를 통해 Pod 로그를 읽습니다. 하지만 초기 ClusterRole에 `pods/log` 리소스 권한이 없었습니다.

**Kubernetes RBAC 구조**:
```
ServiceAccount (alloy)
  ↓
ClusterRoleBinding (alloy)
  ↓
ClusterRole (alloy)
  ├─ pods (get, list, watch) ✅
  ├─ pods/log (get, list, watch) ❌ 누락
  └─ ...
```

#### 해결 방법

**ClusterRole에 pods/log 권한 추가**:

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: alloy
  labels:
    app: alloy
rules:
  - apiGroups: [""]
    resources:
      - nodes
      - nodes/proxy
      - nodes/metrics
      - services
      - endpoints
      - pods
      - pods/log  # ← 추가
    verbs: ["get", "list", "watch"]
  - apiGroups: [""]
    resources:
      - configmaps
    verbs: ["get"]
  - nonResourceURLs:
      - /metrics
      - /metrics/cadvisor
    verbs: ["get"]
```

**적용**:
```bash
cd /home/jimin/k8s-manifests/monitoring
kubectl apply -f alloy-daemonset.yaml

# Alloy Pod 재시작
kubectl rollout restart daemonset/alloy -n monitoring
```

#### 검증

```bash
# 로그 수집 성공 메시지 확인
kubectl logs -n monitoring alloy-xxxxx | grep "opened log stream"

# 출력 예시:
ts=2026-01-26T00:18:15Z level=info msg="opened log stream"
  target=blog-system/was-5bb794b9f9-dxnxb:spring-boot
  component_path=/ component_id=loki.source.kubernetes.pods

ts=2026-01-26T00:18:16Z level=info msg="opened log stream"
  target=blog-system/web-859d9ddfc8-k7m8q:nginx
  component_path=/ component_id=loki.source.kubernetes.pods
```

#### 배운 점

**Kubernetes RBAC 권한 계층**:
- `pods`: Pod 메타데이터 조회 (name, status, labels)
- `pods/log`: Pod 로그 조회 (`kubectl logs` 명령어 수준)
- `pods/exec`: Pod 내부 명령 실행 (더 강력한 권한)

---

### 문제 2: Prometheus Remote Write 미지원

#### 증상
```
ts=2026-01-26T00:20:45Z level=error
component_path=/ component_id=prometheus.remote_write.default
msg="server returned HTTP status 404 Not Found:
remote write receiver needs to be enabled with --web.enable-remote-write-receiver"
```

#### 원인 분석

**왜 발생했는가?**

초기 Alloy 설정에서 `prometheus.remote_write`를 사용하여 메트릭을 Prometheus로 **Push** 방식으로 전송하려 했습니다:

```alloy
// ❌ 이 방식을 시도함
prometheus.exporter.unix "system" {
  include_exporter_metrics = true
}

prometheus.remote_write "default" {
  endpoint {
    url = "http://prometheus:9090/api/v1/write"
  }
}

prometheus.scrape "system" {
  targets    = prometheus.exporter.unix.system.targets
  forward_to = [prometheus.remote_write.default.receiver]  // Push to Prometheus
}
```

하지만 현재 Prometheus 인스턴스는 **Remote Write Receiver가 비활성화** 상태였습니다:

```bash
# Prometheus 시작 옵션 확인
kubectl describe deployment prometheus -n monitoring | grep args

# 출력:
--storage.tsdb.path=/prometheus/
--config.file=/etc/prometheus/prometheus.yml
# --web.enable-remote-write-receiver 플래그 없음 ❌
```

**Prometheus의 두 가지 메트릭 수집 방식**:
```
┌─────────────────────────────────────────────────┐
│ 1. Pull (Scrape) - 전통적 방식                   │
│   Prometheus → (HTTP GET) → Exporter/Agent      │
│   장점: Prometheus가 타겟 상태 제어              │
│   단점: Exporter가 HTTP 서버여야 함              │
└─────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────┐
│ 2. Push (Remote Write) - 신규 방식              │
│   Agent → (HTTP POST) → Prometheus               │
│   장점: Agent가 능동적으로 전송 가능              │
│   단점: Prometheus에 추가 설정 필요               │
│         (--web.enable-remote-write-receiver)     │
└─────────────────────────────────────────────────┘
```

#### 해결 방법

**Pull 방식으로 변경 (전통적 Prometheus 방식)**:

1. **Alloy 설정 수정** - `forward_to = []`로 HTTP endpoint 노출:

```alloy
// ✅ 최종 작동 방식
prometheus.exporter.unix "system" {
  include_exporter_metrics = true
}

// forward_to가 빈 배열 → 메트릭이 HTTP endpoint에 노출됨
prometheus.scrape "system" {
  targets    = prometheus.exporter.unix.system.targets
  forward_to = []  // ← 핵심: Push하지 않고 HTTP 노출
}
```

2. **Prometheus 설정** - Alloy를 scrape:

```yaml
scrape_configs:
  - job_name: 'alloy'
    metrics_path: '/api/v0/component/prometheus.exporter.unix.system/metrics'
    kubernetes_sd_configs:
      - role: pod
        namespaces:
          names:
            - monitoring
    relabel_configs:
      - source_labels: [__meta_kubernetes_pod_label_app]
        action: keep
        regex: alloy
      - source_labels: [__meta_kubernetes_pod_node_name]
        target_label: instance
      - source_labels: [__address__]
        target_label: __address__
        regex: '([^:]+)(?::\d+)?'
        replacement: '${1}:12345'
```

#### 검증

```bash
# Prometheus 타겟 확인
kubectl exec -n monitoring deployment/prometheus -- \
  wget -qO- 'http://localhost:9090/api/v1/targets?state=active' | grep alloy

# 출력:
"job": "alloy"
"health": "up"
"scrapeUrl": "http://192.168.1.187:12345/api/v0/component/prometheus.exporter.unix.system/metrics"
```

#### 배운 점

**Prometheus 수집 방식 선택 기준**:

| 상황 | 추천 방식 | 이유 |
|------|----------|------|
| **기존 Prometheus 인프라** | Pull (Scrape) | 설정 간단, 타겟 상태 제어 가능 |
| **클라우드 환경 (Grafana Cloud)** | Push (Remote Write) | 동적 IP, 방화벽 문제 해결 |
| **Edge/IoT 디바이스** | Push (Remote Write) | 단방향 통신 가능 |

---

### 문제 3: Prometheus ConfigMap Apply Conflict

#### 증상
```bash
kubectl apply -f prometheus-config.yaml

# 에러:
error when patching "prometheus-config.yaml":
the object has been modified; please apply your changes to the latest version and try again
```

#### 원인 분석

**왜 발생했는가?**

다른 프로세스(또는 이전 `kubectl apply`)가 동일한 ConfigMap을 수정한 상태에서, 로컬 파일 기준으로 `apply`를 시도하면 발생하는 충돌입니다.

**Kubernetes Apply의 3-way Merge**:
```
1. Live Object (현재 클러스터 상태)
   ↓
2. Last Applied Configuration (annotation에 저장된 이전 상태)
   ↓
3. Local File (로컬 YAML 파일)
   ↓
→ 3-way merge 시도 → 충돌 발생
```

#### 해결 방법

**kubectl replace --force 사용**:

```bash
cd /home/jimin/k8s-manifests/monitoring
kubectl replace -f prometheus-config.yaml --force

# 출력:
configmap "prometheus-config" deleted
configmap/prometheus-config replaced
```

**동작 방식**:
1. 기존 ConfigMap 삭제
2. 새 ConfigMap 생성
3. Prometheus Pod는 ConfigMap이 마운트된 상태이므로, 파일시스템 변경 감지

#### 주의사항

**ConfigMap 삭제 순간**:
```
kubectl replace --force 실행
  ↓
기존 ConfigMap 삭제 (1초)
  ↓
신규 ConfigMap 생성 (1초)
  ↓
✅ Prometheus는 메모리에 설정 로드되어 있어 영향 없음
```

**더 안전한 방법 (선택)**:
```bash
# 1. 수동 edit (실시간 충돌 없음)
kubectl edit configmap prometheus-config -n monitoring

# 2. patch 사용 (부분 업데이트)
kubectl patch configmap prometheus-config -n monitoring --type=json \
  -p='[{"op": "add", "path": "/data/prometheus.yml", "value": "..."}]'
```

#### 검증

```bash
# ConfigMap 변경 확인
kubectl get configmap prometheus-config -n monitoring -o yaml | grep -A 5 "job_name: 'alloy'"

# 출력:
- job_name: 'alloy'
  metrics_path: '/api/v0/component/prometheus.exporter.unix.system/metrics'
  kubernetes_sd_configs:
    - role: pod
      namespaces:
        names:
          - monitoring
```

---

### 문제 4: Prometheus CrashLoopBackOff - Storage Lock

#### 증상
```
kubectl get pods -n monitoring

# 출력:
NAME                          READY   STATUS             RESTARTS   AGE
prometheus-57b448ccd6-ftbpw   1/1     Running            0          10m   (구버전)
prometheus-75d8b9c6d4-xyz12   0/1     CrashLoopBackOff   5          2m    (신버전)

kubectl logs prometheus-75d8b9c6d4-xyz12 -n monitoring

# 에러:
level=ERROR ts=2026-01-26T00:25:30.123Z caller=main.go:456
msg="Fatal error" err="opening storage failed:
lock DB directory: resource temporarily unavailable"
```

#### 원인 분석

**왜 발생했는가?**

Prometheus Pod를 재시작(`kubectl rollout restart`)할 때, **구버전 Pod와 신버전 Pod가 동시에 같은 PersistentVolume에 접근**하면서 storage lock 충돌이 발생했습니다.

**플로우**:
```
kubectl rollout restart deployment/prometheus
  ↓
1. 신규 Pod 생성 (prometheus-75d8b9c6d4-xyz12)
  ↓
2. 신규 Pod가 PVC 마운트 시도
  ↓
3. ❌ 구버전 Pod (prometheus-57b448ccd6-ftbpw)가 여전히 PVC를 lock한 상태
  ↓
4. 신규 Pod CrashLoopBackOff
  ↓
5. Kubernetes는 구버전 Pod가 Ready 상태가 아니면 종료하지 않음
   (하지만 신규 Pod가 crash → 구버전 Pod는 계속 Running)
```

**TSDB Lock 메커니즘**:
Prometheus는 Time Series Database(TSDB) 디렉터리를 열 때 `flock` 시스템 콜로 파일 잠금을 수행합니다:

```c
// Prometheus TSDB 내부
fd = open("/prometheus/lock", O_CREAT|O_RDWR, 0644);
if (flock(fd, LOCK_EX | LOCK_NB) == -1) {
  return "resource temporarily unavailable";  // ← 우리가 본 에러
}
```

#### 시도한 해결 방법 (실패)

**1. 구버전 Pod 수동 삭제**:
```bash
kubectl delete pod prometheus-57b448ccd6-ftbpw -n monitoring

# 결과: ❌ 신규 Pod 여전히 crash
# 이유: PVC lock이 즉시 해제되지 않음 (kernel caching)
```

**2. ReplicaSet Scale Down**:
```bash
kubectl scale replicaset prometheus-57b448ccd6 --replicas=0 -n monitoring

# 결과: ❌ 신규 Pod 여전히 crash
# 이유: Deployment controller가 자동으로 롤백
```

**3. 신규 Pod 삭제 후 재생성**:
```bash
kubectl delete pod prometheus-75d8b9c6d4-xyz12 -n monitoring

# 결과: ❌ 재생성된 Pod도 crash
# 이유: 근본 원인(PVC lock) 미해결
```

#### 최종 해결 방법

**Rollback + HTTP Reload API 사용**:

1. **Deployment 롤백**:
```bash
kubectl rollout undo deployment/prometheus -n monitoring

# 구버전 Pod로 복구
prometheus-57b448ccd6-ftbpw   1/1  Running  (안정화)
```

2. **HTTP Reload API로 설정 재로드** (Pod 재시작 불필요):
```bash
kubectl exec -n monitoring deployment/prometheus -- \
  wget --post-data='' -O- http://localhost:9090/-/reload

# 출력:
Connecting to localhost:9090 (127.0.0.1:9090)
writing to stdout
written to stdout
```

3. **변경사항 확인**:
```bash
# Prometheus 타겟에 alloy job 추가됨
kubectl exec -n monitoring deployment/prometheus -- \
  wget -qO- 'http://localhost:9090/api/v1/targets?state=active' | grep alloy

# 출력:
"job": "alloy"
"health": "up"
```

#### 검증

```bash
# Prometheus Pod 상태 (재시작 없이 설정 반영)
kubectl get pod -n monitoring -l app=prometheus

# 출력:
NAME                          READY   STATUS    RESTARTS   AGE
prometheus-57b448ccd6-ftbpw   1/1     Running   0          30m

# 메트릭 수집 확인
kubectl exec -n monitoring deployment/prometheus -- \
  wget -qO- 'http://localhost:9090/api/v1/query?query=node_cpu_seconds_total{job="alloy"}' \
  | python3 -c "import json,sys; print(len(json.load(sys.stdin)['data']['result']), 'time series')"

# 출력:
176 time series ✅
```

#### 배운 점

**Prometheus 설정 변경 Best Practice**:

| 방법 | 장점 | 단점 | 추천 |
|------|------|------|------|
| **kubectl rollout restart** | 완전 재시작, 깨끗한 상태 | PVC lock 문제, 다운타임 | ❌ |
| **HTTP Reload API** | 다운타임 없음, 빠름 | 일부 설정은 재시작 필요 | ✅ |
| **ConfigMap 변경 + 대기** | 자동 reload (inotify) | 반영 지연 (10-60초) | ⚠️ |

**HTTP Reload API 사용법**:
```bash
# 방법 1: wget
kubectl exec -n monitoring deployment/prometheus -- \
  wget --post-data='' -O- http://localhost:9090/-/reload

# 방법 2: curl (없을 수 있음)
kubectl exec -n monitoring deployment/prometheus -- \
  curl -X POST http://localhost:9090/-/reload

# 방법 3: kill -HUP (SIGHUP 시그널)
kubectl exec -n monitoring deployment/prometheus -- kill -HUP 1
```

**재시작이 필요한 경우**:
- `--storage.tsdb.path` 변경
- `--web.listen-address` 변경
- 플래그 추가/제거

**Reload로 충분한 경우**:
- `scrape_configs` 변경 (대부분의 경우)
- `rule_files` 변경
- `alerting` 설정 변경

---

### 문제 5: Alloy가 node_* 메트릭을 기본 /metrics에 노출하지 않음

#### 증상
```bash
# Alloy 메트릭 엔드포인트 확인
kubectl port-forward -n monitoring alloy-xxxxx 12345:12345 &
curl http://localhost:12345/metrics | grep node_cpu

# 출력: (없음)
# alloy_* 메트릭만 존재, node_* 메트릭 없음
```

```bash
# Prometheus에서 메트릭 확인
kubectl exec -n monitoring deployment/prometheus -- \
  wget -qO- 'http://localhost:9090/api/v1/query?query=node_cpu_seconds_total{job="alloy"}'

# 결과:
{
  "data": {
    "result": []  // ← 메트릭 0개
  }
}
```

#### 원인 분석

**왜 발생했는가?**

**Alloy v2의 Component API 아키텍처**:

Grafana Alloy v2에서는 각 컴포넌트가 **독립적인 HTTP endpoint**를 가집니다. `prometheus.exporter.unix` 메트릭은 기본 `/metrics` 경로에 노출되지 않고, **Component별 API 경로**를 통해서만 접근할 수 있습니다:

```
기본 /metrics:
  http://alloy:12345/metrics
  ↓
  alloy_build_info
  alloy_component_controller_running_components
  alloy_resources_process_cpu_seconds_total
  ... (Alloy 자체 메트릭만)

Component API (prometheus.exporter.unix.system):
  http://alloy:12345/api/v0/component/prometheus.exporter.unix.system/metrics
  ↓
  node_cpu_seconds_total
  node_memory_MemAvailable_bytes
  node_disk_io_time_seconds_total
  ... (node_exporter 메트릭)
```

**조사 과정**:

1. **Alloy 로그 확인** - exporter는 정상 작동 중:
```bash
kubectl logs -n monitoring alloy-xxxxx | grep "prometheus.exporter.unix"

# 출력:
ts=2026-01-26T00:31:29Z level=info
  msg="Enabled node_exporter collectors"
  component_path=/ component_id=prometheus.exporter.unix.system
ts=2026-01-26T00:31:29Z level=info
  component_path=/ component_id=prometheus.exporter.unix.system
  collector=cpu
ts=2026-01-26T00:31:29Z level=info
  component_path=/ component_id=prometheus.exporter.unix.system
  collector=filesystem
...
```

2. **Prometheus 타겟 확인** - UP 상태지만 메트릭 없음:
```bash
kubectl exec -n monitoring deployment/prometheus -- \
  wget -qO- 'http://localhost:9090/api/v1/targets?state=active' | grep alloy

# 출력:
"job": "alloy"
"health": "up"
"scrapeUrl": "http://192.168.1.187:12345/metrics"  // ← 잘못된 경로
```

3. **웹 검색** - Component API 발견:
   - [How to retrieve metrics from all processes using Grafana Alloy](https://www.claudiokuenzler.com/blog/1474/how-to-retrieve-metrics-all-processes-grafana-alloy)
   - Alloy Component API 경로 패턴: `/api/v0/component/<component_id>/metrics`

#### 해결 방법

**Prometheus가 Component API 경로를 scrape하도록 설정**:

```yaml
scrape_configs:
  - job_name: 'alloy'
    # ✅ Component API 경로 명시
    metrics_path: '/api/v0/component/prometheus.exporter.unix.system/metrics'
    kubernetes_sd_configs:
      - role: pod
        namespaces:
          names:
            - monitoring
    relabel_configs:
      - source_labels: [__meta_kubernetes_pod_label_app]
        action: keep
        regex: alloy
      - source_labels: [__meta_kubernetes_pod_node_name]
        target_label: instance
      - source_labels: [__address__]
        target_label: __address__
        regex: '([^:]+)(?::\d+)?'
        replacement: '${1}:12345'
```

**적용**:
```bash
cd /home/jimin/k8s-manifests/monitoring
kubectl replace -f prometheus-config.yaml --force

# Prometheus 재로드
kubectl exec -n monitoring deployment/prometheus -- \
  wget --post-data='' -O- http://localhost:9090/-/reload
```

#### 검증

```bash
# 20초 대기 후 메트릭 확인
sleep 20

kubectl exec -n monitoring deployment/prometheus -- \
  wget -qO- 'http://localhost:9090/api/v1/query?query=node_cpu_seconds_total{job="alloy"}' \
  > /tmp/alloy_node_cpu.json

python3 -c "
import json
with open('/tmp/alloy_node_cpu.json') as f:
    data = json.load(f)
results = data['data']['result']
print(f'✅ node_cpu_seconds_total: {len(results)} time series')

instances = sorted(set(r['metric'].get('instance') for r in results))
print(f'   Nodes: {instances}')

cpu_modes = sorted(set(r['metric'].get('mode') for r in results[:100]))
print(f'   CPU modes: {cpu_modes}')
"

# 출력:
✅ node_cpu_seconds_total: 176 time series
   Nodes: ['k8s-cp', 'k8s-worker1', 'k8s-worker2', 'k8s-worker3']
   CPU modes: ['idle', 'iowait', 'irq', 'nice', 'softirq', 'steal', 'system', 'user']
```

```bash
# 모든 node_* 메트릭 카테고리 확인
kubectl exec -n monitoring deployment/prometheus -- \
  wget -qO- 'http://localhost:9090/api/v1/label/__name__/values' \
  | grep -o 'node_[a-z_]*' | sort | uniq | wc -l

# 출력: 130+ 개 메트릭 ✅
```

#### Component API 경로 패턴

**일반 형식**:
```
/api/v0/component/<component_id>/metrics
```

**예제**:

| Component 정의 | Component ID | Metrics 경로 |
|---------------|-------------|-------------|
| `prometheus.exporter.unix "system"` | `prometheus.exporter.unix.system` | `/api/v0/component/prometheus.exporter.unix.system/metrics` |
| `prometheus.exporter.process "apps"` | `prometheus.exporter.process.apps` | `/api/v0/component/prometheus.exporter.process.apps/metrics` |
| `prometheus.scrape "kubernetes"` | `prometheus.scrape.kubernetes` | `/api/v0/component/prometheus.scrape.kubernetes/metrics` |

**Component ID 규칙**:
```alloy
prometheus.exporter.unix "system" {  // ← component_id = prometheus.exporter.unix.system
  ...
}

prometheus.exporter.unix "custom_name" {  // ← component_id = prometheus.exporter.unix.custom_name
  ...
}
```

#### 배운 점

**Alloy v2 vs v1 (Grafana Agent Flow)**:

| 항목 | Alloy v1 (Agent Flow) | Alloy v2 |
|------|----------------------|----------|
| **Exporter 메트릭 노출** | `/metrics`에 자동 통합 | Component API 경로 필요 |
| **설정 복잡도** | 낮음 | 높음 (경로 명시) |
| **유연성** | 낮음 | 높음 (Component별 격리) |
| **학습 곡선** | 낮음 | 높음 |

**Component API의 장점**:
- Component별 메트릭 격리 → 충돌 방지
- 동일한 exporter 타입을 여러 개 실행 가능 (예: `prometheus.exporter.unix "system1"`, `"system2"`)
- 세밀한 모니터링 가능 (Component별 health)

---

### 문제 6: Loki "Entry Too Far Behind" 경고

#### 증상
```
ts=2026-01-26T00:31:38Z level=error
msg="final error sending batch, no retries left, dropping data"
component_path=/ component_id=loki.write.default
component=client host=loki-stack.monitoring.svc.cluster.local:3100
status=400 error="server returned HTTP status 400 Bad Request (400):
entry with timestamp 2026-01-22 11:32:18.897976786 +0000 UTC ignored,
reason: 'entry too far behind' for stream: {instance=\"blog-system/was-xxx\", job=\"loki.source.kubernetes.pods\"}"
```

#### 원인 분석

**왜 발생했는가?**

Alloy가 재시작되면서 `/var/log/pods/` 디렉터리에 남아있던 **오래된 Pod 로그 파일**을 수집하려 했으나, Loki의 **retention 정책**에 의해 거부되었습니다.

**Loki Retention 정책**:
```
현재 시간: 2026-01-26 00:31:38
로그 타임스탬프: 2026-01-22 11:32:18
차이: 3일 13시간

Loki 기본 설정:
  - reject_old_samples: true
  - reject_old_samples_max_age: 168h (7일)
  - ❌ 하지만 "Entry Too Far Behind" 에러 발생

실제 원인:
  - Loki가 TSDB block 단위로 데이터 관리
  - 현재 활성 block 범위를 벗어난 타임스탬프는 거부
```

**플로우**:
```
1. Alloy 재시작
   ↓
2. /var/log/pods/ 스캔
   ├─ was-xxx/0.log (2026-01-26) ✅
   ├─ was-xxx/1.log (2026-01-25) ✅
   └─ was-xxx/2.log (2026-01-22) ❌ "too far behind"
   ↓
3. Loki로 전송 시도
   ↓
4. Loki: HTTP 400 Bad Request
```

#### 이것은 정상 동작입니다

**왜 문제가 아닌가?**

1. **오래된 로그는 이미 수집됨**:
   - Promtail이 이미 해당 로그를 Loki에 전송함
   - Alloy 재시작 시 중복 전송 시도 → Loki가 정확히 거부

2. **최신 로그는 정상 수집됨**:
```bash
kubectl logs -n monitoring alloy-xxxxx | grep "opened log stream"

# 출력:
ts=2026-01-26T00:31:40Z level=info msg="opened log stream"
  target=blog-system/was-5bb794b9f9-dxnxb:spring-boot
  start_time=2026-01-26T00:31:35Z  ✅ 최신 로그

ts=2026-01-26T00:31:41Z level=info msg="opened log stream"
  target=blog-system/web-859d9ddfc8-k7m8q:nginx
  start_time=2026-01-26T00:31:36Z  ✅ 최신 로그
```

3. **에러가 1회성**:
   - Alloy 시작 시 1회 발생
   - 이후 정상 동작

#### 검증

```bash
# Grafana Loki에서 최신 로그 확인
# http://grafana:30300 → Explore → Loki

# LogQL 쿼리:
{namespace="blog-system"} | json | line_format "{{.log}}"

# 결과:
2026-01-26 00:32:15  POST /api/posts 200 (34ms)  ✅
2026-01-26 00:32:20  GET / 200 (12ms)  ✅
2026-01-26 00:32:25  GET /api/posts/123 200 (45ms)  ✅
```

#### 조치 불필요

**무시해도 되는 이유**:
- ✅ 최신 로그는 정상 수집됨
- ✅ 오래된 로그는 이미 Loki에 저장됨
- ✅ 1회성 에러 (재발 없음)

**만약 지속 발생한다면** (드문 경우):
```yaml
# Loki 설정 변경 (일반적으로 불필요)
limits_config:
  reject_old_samples_max_age: 336h  # 7일 → 14일로 증가
```

---

### 요약: Grafana Alloy 통합 트러블슈팅 체크리스트

#### ✅ 필수 확인 사항

**1. RBAC 권한**
- [x] ServiceAccount `alloy` 생성
- [x] ClusterRole에 `pods/log` 권한 추가
- [x] ClusterRoleBinding 생성

**2. Alloy 설정**
- [x] `loki.source.kubernetes` 로그 수집
- [x] `prometheus.exporter.unix` 시스템 메트릭
- [x] `prometheus.scrape` with `forward_to = []` (HTTP 노출)

**3. Prometheus 설정**
- [x] `job_name: 'alloy'` 추가
- [x] `metrics_path: '/api/v0/component/prometheus.exporter.unix.system/metrics'`
- [x] HTTP Reload API 사용 (재시작 대신)

**4. 검증**
- [x] Alloy Pods: 4/4 Running
- [x] Prometheus Targets: alloy UP
- [x] Metrics: 176+ time series (`node_cpu_seconds_total`)
- [x] Logs: Loki에 정상 전송

#### 🔧 자주 발생하는 문제

| 문제 | 증상 | 해결 |
|------|------|------|
| **RBAC 권한 없음** | `pods/log forbidden` | ClusterRole에 `pods/log` 추가 |
| **Remote Write 미지원** | `404 Not Found: remote write` | `forward_to = []` 사용 (Pull 방식) |
| **ConfigMap 충돌** | `object has been modified` | `kubectl replace --force` |
| **PVC Lock** | `CrashLoopBackOff: lock DB` | HTTP Reload API 사용 (재시작 금지) |
| **메트릭 없음** | `node_* metrics: 0` | Component API 경로 설정 |
| **Loki 에러** | `entry too far behind` | 무시 (정상 동작) |

#### 📚 참고 자료

**공식 문서**:
- [Grafana Alloy Documentation](https://grafana.com/docs/alloy/latest/)
- [prometheus.exporter.unix Reference](https://grafana.com/docs/alloy/latest/reference/components/prometheus/prometheus.exporter.unix/)
- [loki.source.kubernetes Reference](https://grafana.com/docs/alloy/latest/reference/components/loki/loki.source.kubernetes/)

**커뮤니티**:
- [How to retrieve metrics from all processes using Grafana Alloy](https://www.claudiokuenzler.com/blog/1474/how-to-retrieve-metrics-all-processes-grafana-alloy)
- [How to scrape local Prometheus node exporter metrics running in Grafana Alloy](https://www.claudiokuenzler.com/blog/1462/how-to-scrape-node-exporter-metrics-grafana-alloy)

**완전한 가이드**:
- `/home/jimin/k8s-manifests/docs/monitoring/GRAFANA-ALLOY-INTEGRATION.md`



---

## 12. Study 카테고리 필터 트러블슈팅

> Study 페이지 카테고리 필터 구현 시 발생한 5가지 문제와 해결 방법
> 관련 문서: [`docs/blog-design/STUDY-CATEGORY-FILTER.md`](blog-design/STUDY-CATEGORY-FILTER.md)

### 12.1. Hugo 빌드 실패 - nil categories

#### 증상
```bash
Error: error building site: render: failed to render pages:
template: study/list.html:89:50: executing "main" at
<delimit $page.Params.categories ",">: error calling delimit:
can't iterate over <nil>
```

#### 원인 분석

**왜 발생했는가?**

일부 포스트가 `categories` front matter를 가지고 있지 않아 nil을 반환했습니다:

```yaml
# 정상 포스트
---
title: "Kubernetes 가이드"
categories: ["study", "Kubernetes"]
---

# 문제 포스트 (categories 없음)
---
title: "포스트 제목"
date: 2026-01-26
---
```

Hugo 템플릿에서:
```html
<!-- $page.Params.categories → nil -->
<article data-categories='{{ delimit $page.Params.categories "," }}'>
                                          ↑
                                    nil을 delimit 불가! ❌
```

#### 해결 방법

**`.default slice` 파이프 추가**:

```html
<!-- Before (에러) -->
<article data-categories='{{ delimit $page.Params.categories "," }}'>

<!-- After (수정) -->
<article data-categories='{{ delimit ($page.Params.categories | default slice) "," }}'>
```

**작동 원리**:
```go
$page.Params.categories | default slice
         ↓
만약 nil이면 빈 배열([])로 대체
         ↓
delimit [] "," → "" (빈 문자열)
         ↓
빌드 성공! ✅
```

#### 검증
```bash
# 빌드 테스트
hugo --minify

# 결과
Start building sites ...
                   | EN
-------------------+-----
  Pages            | 198
  Paginator pages  |   0
  Non-page files   |  17
  Static files     | 113
  Processed images |   0
  Aliases          |  53
  Sitemaps         |   1
  Cleaned          |   0

Total in 2847 ms
```

**커밋**: `62ac4ee` fix: Handle nil categories in study list template

---

### 12.2. 카테고리 필터 작동 안 함 - 공백 문제

#### 증상
- 사용자가 "Service Mesh" 버튼 클릭
- 아무 포스트도 표시되지 않음
- Console 에러 없음

#### 원인 분석

**왜 발생했는가?**

Hugo 템플릿의 `delimit` 함수가 공백을 포함하여 생성:

```html
<!-- Hugo 생성 HTML -->
<article data-categories='study,Service Mesh,Networking'>
                                 ↑ 여기 공백!
```

JavaScript에서 split 시:
```javascript
const categories = categoriesStr.split(',');
// → ["study", " Service Mesh", " Networking"]
//              ↑ 앞에 공백!

// 버튼:
<button data-category="Service Mesh">

// 매칭 시도:
categories.includes("Service Mesh")  // false! ❌
// 왜? " Service Mesh" !== "Service Mesh"
```

#### 해결 방법

**`.trim()` 추가**:

```javascript
// Before (버그)
const categories = categoriesStr.split(',');

// After (수정)
const categories = categoriesStr.split(',').map(c => c.trim());
// → ["study", "Service Mesh", "Networking"]
//              ↑ 공백 제거됨!
```

#### 검증
```javascript
// 테스트
const categoriesStr = "study, Service Mesh, Networking";

// Before
categoriesStr.split(',')
// → ["study", " Service Mesh", " Networking"] ❌

// After
categoriesStr.split(',').map(c => c.trim())
// → ["study", "Service Mesh", "Networking"] ✅
```

**커밋**: `058eb21` fix: Category filter 및 페이지네이션 상태 유지 개선

---

### 12.3. 페이지네이션 시 필터 초기화

#### 증상
1. Kubernetes 필터 선택 (37개 포스트 표시)
2. 스크롤 아래로 → "다음 페이지" 버튼 클릭
3. 필터가 "All"로 초기화됨 ❌

#### 원인 분석

**왜 발생했는가?**

페이지네이션 링크가 카테고리 파라미터를 유지하지 않음:

```html
<!-- Hugo 생성 링크 -->
<a href="/study/page/2/">다음 페이지</a>
                        ↑
                   ?category=Kubernetes 누락!
```

클릭 시:
```
현재 URL: /study/?category=Kubernetes
    ↓ 클릭
다음 URL: /study/page/2/
    ↓
JavaScript: urlParams.get('category') → null
    ↓
currentFilter = 'all' (기본값)
```

#### 해결 방법

**페이지네이션 링크 동적 업데이트**:

```javascript
// 페이지 로드 시 실행
const paginationLinks = document.querySelectorAll('.pagination a');
paginationLinks.forEach(link => {
  if (currentFilter !== 'all') {
    const url = new URL(link.href);
    url.searchParams.set('category', currentFilter);
    link.href = url.toString();
  }
});
```

**Before / After**:
```html
<!-- Before -->
<a href="/study/page/2/">다음 페이지</a>

<!-- After (JavaScript가 수정) -->
<a href="/study/page/2/?category=Kubernetes">다음 페이지</a>
                        ↑
                   파라미터 추가됨! ✅
```

#### 검증
```javascript
// 테스트
currentFilter = 'Kubernetes'
link.href = '/study/page/2/'

// JavaScript 실행 후
console.log(link.href)
// → '/study/page/2/?category=Kubernetes' ✅
```

**커밋**: `058eb21` fix: Category filter 및 페이지네이션 상태 유지 개선

---

### 12.4. CSS 스타일이 안 보임 - Cloudflare 캐시

#### 증상
- 카테고리 필터 박스가 평문으로 표시
- 버튼 스타일, 배경색, 호버 효과 없음
- HTML은 정상, CSS는 로드되지만 스타일 적용 안 됨

**사용자가 본 화면**:
```
All (96) Cloud & Terraform (15) Development (10) Elasticsearch (6) ...
```
(박스 형태 없이 일반 텍스트로만 표시)

#### 원인 분석

**왜 발생했는가?**

1. **파일은 정상 배포됨**:
```bash
# Pod 안의 파일 확인
kubectl exec -n blog-system web-xxx -- \
  cat /usr/share/nginx/html/css/custom.css | grep "category-filter-box"

# 결과: CSS 정상 존재 ✅
.category-filter-box {
    background: var(--entry);
    border: 1px solid var(--border);
    ...
}
```

2. **하지만 Cloudflare가 이전 버전 캐시**:
```bash
curl -I https://blog.jiminhome.shop/css/custom.css | grep age
# age: 79 ← 79초 전 캐시된 버전 (카테고리 스타일 없음)
```

3. **사용자 브라우저도 이전 버전 캐시**:
- Cloudflare → 사용자 브라우저 → 오래된 CSS
- 새로운 HTML (카테고리 박스 있음) + 오래된 CSS (스타일 없음) = 평문 표시

**플로우**:
```
배포 파이프라인 실행
    ↓
새 Docker 이미지 빌드 (custom.css 포함)
    ↓
Kubernetes Pod 업데이트 ✅
    ↓
Cloudflare 캐시 삭제 실행 (purge_everything)
    ↓
하지만 사용자가 이미 페이지를 열어둠
    ↓
사용자 브라우저: 오래된 CSS 계속 사용 ❌
```

#### 해결 방법

**방법 1: 즉시 해결 (사용자 측)**

브라우저 하드 새로고침:
```
Windows/Linux: Ctrl + Shift + R
Mac:           Cmd + Shift + R
```

**방법 2: 장기 해결 (배포 측)**

빈 커밋으로 재배포 트리거:
```bash
git commit --allow-empty -m "chore: Purge Cloudflare cache for category filter styles"
git push
```

GitHub Actions 실행:
```yaml
# .github/workflows/deploy-web.yml
- name: Purge Cloudflare Cache
  run: |
    curl -X POST "https://api.cloudflare.com/client/v4/zones/${{ secrets.CLOUDFLARE_ZONE_ID }}/purge_cache" \
      -H "Authorization: Bearer ${{ secrets.CLOUDFLARE_API_TOKEN }}" \
      -H "Content-Type: application/json" \
      --data '{"purge_everything":true}'
```

#### 검증
```bash
# 1. 캐시 상태 확인
curl -I https://blog.jiminhome.shop/css/custom.css | grep -i cache

# Before
cf-cache-status: HIT
age: 79

# After (캐시 삭제 후)
cf-cache-status: MISS
age: 2

# 2. CSS 내용 확인
curl -s https://blog.jiminhome.shop/css/custom.css | tail -100 | grep "category-filter-box"

# 결과: 카테고리 스타일 포함 ✅
.category-filter-box {
```

**커밋**: `41860ef` chore: Purge Cloudflare cache for category filter styles

#### 교훈

**정적 파일 캐시 관리**:
- ✅ 배포 시 자동 캐시 삭제 (CI/CD 파이프라인)
- ✅ 사용자에게 하드 새로고침 안내
- 🔜 향후 개선: CSS 파일명에 해시 추가 (`custom.abc123.css`)

---

### 12.5. 카테고리 선택 시 일부만 표시 - 페이지네이션

#### 증상
- Observability (6개) 버튼 클릭
- 1개 포스트만 표시됨
- 나머지 5개는 어디로?

#### 원인 분석

**왜 발생했는가?**

Hugo 페이지네이션 설정:
```toml
# config.toml
[pagination]
  pagerSize = 10
```

Hugo 빌드 시:
```
96개 포스트
    ↓ pagerSize = 10
/study/index.html        → 10개 포스트 (1-10)
/study/page/2/index.html → 10개 포스트 (11-20)
/study/page/3/index.html → 10개 포스트 (21-30)
...
/study/page/10/index.html → 6개 포스트 (91-96)
```

사용자가 1페이지 접속:
```
1. HTML 로드: 10개 포스트만 포함
   <article data-categories="study,Kubernetes">
   <article data-categories="study,Observability"> ← 1개만!
   <article data-categories="study,Storage">
   ...

2. JavaScript 필터링:
   articles.forEach(article => {  // 10개만 순회!
     if (categories.includes('Observability')) {
       article.style.display = '';
     }
   })

3. 결과: 1개만 표시 ❌
```

**문제의 핵심**:
- JavaScript는 **DOM에 있는 요소만** 조작 가능
- 2-10페이지에 있는 Observability 포스트 5개는 HTML에 없음
- 따라서 필터링 불가능

#### 해결 방법

**pagerSize 증가**:

```toml
# config.toml

# Before
[pagination]
  pagerSize = 10

# After
[pagination]
  pagerSize = 100  # 96개 포스트 모두 한 페이지에
```

**효과**:
```
Hugo 빌드 시:
    ↓
/study/index.html → 96개 포스트 모두 포함
    ↓
JavaScript 필터링:
    ↓
articles.forEach(article => {  // 96개 모두 순회!
  if (categories.includes('Observability')) {
    article.style.display = '';  // 6개 모두 표시 ✅
  }
})
```

**+ 페이지네이션 동적 표시/숨김**:

```javascript
// layouts/study/list.html
function applyFilter(selectedCategory) {
  // ... 필터링 로직 ...

  // 페이지네이션 처리
  const pagination = document.querySelector('.page-footer');
  if (pagination) {
    if (selectedCategory === 'all') {
      pagination.style.display = '';  // "All"이면 표시
    } else {
      pagination.style.display = 'none';  // 필터 사용 시 숨김
    }
  }
}
```

**왜 페이지네이션을 숨기는가?**
- 카테고리 필터 사용 시: 모든 결과를 한 페이지에 표시
- 페이지네이션 불필요 (Observability 6개 → 1페이지로 충분)

#### 검증
```bash
# 빌드 후 확인
hugo --minify

ls -lh public/study/
# index.html만 존재 (page/ 디렉터리 없음)

# HTML 크기 확인
du -h public/study/index.html
# Before: 30K (10개 포스트)
# After:  60K (96개 포스트)

# 크기 증가는 허용 가능 (이미지 1개 수준)
```

**브라우저 테스트**:
```
1. Observability 클릭
   → 6개 포스트 모두 표시 ✅

2. Kubernetes 클릭
   → 37개 포스트 모두 표시 ✅

3. "All" 클릭
   → 96개 포스트 모두 표시 ✅
   → 페이지네이션 버튼 없음 (1페이지만 존재)
```

**커밋**: `b714420` feat: 카테고리 필터링 시 모든 포스트 표시

#### 트레이드오프

| 항목 | Before (pagerSize=10) | After (pagerSize=100) |
|------|----------------------|----------------------|
| **초기 로드** | 빠름 (30KB) | 약간 느림 (60KB) |
| **필터링** | 일부만 표시 ❌ | 전체 표시 ✅ |
| **페이지네이션** | 필요 (10페이지) | 불필요 (1페이지) |
| **UX** | 불편함 (페이지 이동 필요) | 편리함 (즉시 필터링) |

**결론**: 60KB는 매우 작음 (이미지 1개 수준) → UX 개선 효과가 훨씬 큼!

---

### 12.6. 요약 및 교훈

#### 해결한 문제 5가지

| 문제 | 원인 | 해결 방법 | 커밋 |
|------|------|----------|------|
| **Hugo 빌드 실패** | nil categories | `\| default slice` 추가 | `62ac4ee` |
| **필터 작동 안 함** | 공백 포함 매칭 실패 | `.trim()` 추가 | `058eb21` |
| **페이지네이션 초기화** | URL 파라미터 누락 | 링크 동적 업데이트 | `058eb21` |
| **CSS 스타일 없음** | Cloudflare 캐시 | 하드 새로고침 + 재배포 | `41860ef` |
| **일부만 표시** | pagerSize=10 제한 | pagerSize=100 증가 | `b714420` |

#### 핵심 교훈

1. **Hugo 정적 사이트의 한계**:
   - JavaScript는 DOM에 있는 요소만 조작 가능
   - 페이지네이션 시 다른 페이지 요소 접근 불가
   - 해결: 필터링 대상을 모두 한 페이지에 렌더링

2. **공백 처리의 중요성**:
   - `"Service Mesh"` vs `" Service Mesh"` → 매칭 실패
   - 항상 `.trim()` 사용 필수

3. **Cloudflare 캐시 관리**:
   - 정적 파일(CSS, JS) 변경 시 캐시 삭제 필요
   - CI/CD 파이프라인에 자동화 필수

4. **URL 상태 관리**:
   - `window.history.pushState()` 로 새로고침 없이 URL 변경
   - 페이지네이션 링크에 파라미터 추가 필수

#### 참고 자료

**관련 문서**:
- [`docs/blog-design/STUDY-CATEGORY-FILTER.md`](blog-design/STUDY-CATEGORY-FILTER.md) - 완전한 구현 가이드
- [`CLAUDE.md`](../CLAUDE.md) - Study 카테고리 관리 규칙

**변경된 파일**:
- `config.toml` - pagerSize 증가
- `layouts/study/list.html` - 필터 UI + JavaScript
- `static/css/custom.css` - 카테고리 필터 스타일
