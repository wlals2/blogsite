---
title: "ArgoCD 설치 완전 가이드: Helm vs kubectl, Ingress, Cloudflare Tunnel"
date: 2026-01-20
summary: "Homeserver K8s에 ArgoCD를 Helm으로 설치하고 Ingress + Cloudflare Tunnel로 외부 접속 구성 (실제 트러블슈팅 포함)"
tags: ["argocd", "helm", "gitops", "kubernetes", "cloudflare-tunnel"]
categories: ["kubernetes"]
series: ["Infrastructure Learning Journey"]
weight: 1
showtoc: true
tocopen: true
draft: false
---

## 🎯 목표

> **Homeserver Kubernetes에 ArgoCD를 설치하고 GitOps 자동 배포 환경 구축**

**달성한 것**:
- ✅ Helm으로 ArgoCD 설치
- ✅ Ingress로 로컬 접속
- ✅ Cloudflare Tunnel DNS 라우팅 추가
- ✅ 실제 트러블슈팅 경험

---

## Part 1: Helm vs kubectl apply 결정

### 🤔 처음엔 kubectl apply만 알고 있었어요

ArgoCD 공식 문서를 보니 이렇게 설치하라고 하더군요:

```bash
# Option 1: kubectl apply (공식 문서)
kubectl apply -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml

# YAML 파일 크기 확인
curl -s https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml | wc -l
# 결과: 26,951줄 ❗
```

26,951줄이라니... 이 파일을 직접 관리한다고 생각하니 막막했습니다. 설정 하나 바꾸려면 이 긴 파일에서 찾아야 하고, 업그레이드할 때마다 전체를 다시 apply 해야 하는 건가요?

---

### ✅ Helm을 알게 되었어요

그래서 찾아보니 Helm이라는 게 있더라구요. 처음엔 "또 새로운 도구를 배워야 하나" 싶었는데, 알고 보니 정말 편했습니다:

```bash
# Helm Repository 추가
helm repo add argo https://argoproj.github.io/argo-helm

# 간단한 설치
helm install argocd argo/argo-cd -n argocd --create-namespace

# 설정 변경: values.yaml만 수정
# 예: Ingress 활성화, 리소스 제한 등
helm upgrade argocd argo/argo-cd -n argocd -f values.yaml

# 롤백 (실패 시)
helm rollback argocd 1
```

**비교표**:

| 항목 | Helm | kubectl apply |
|------|------|---------------|
| **설치** | `helm install` | `kubectl apply -f 26951줄.yaml` |
| **설정 변경** | values.yaml 수정 | 26951줄에서 찾아 수정 |
| **업그레이드** | `helm upgrade` (자동) | 전체 YAML 재배포 |
| **롤백** | `helm rollback` | ❌ 불가능 |
| **히스토리** | `helm history` | ❌ 없음 |
| **복잡도** | 낮음 | 매우 높음 |

**결론**: **Helm 사용** ✅

---

## Part 2: ArgoCD Helm 설치

### Step 1: Namespace 생성

```bash
kubectl create namespace argocd

# 결과:
namespace/argocd created
```

**왜?** ArgoCD 자체도 Kubernetes 리소스로 실행되므로 Namespace 분리 필요

---

### Step 2: Helm으로 설치

```bash
helm install argocd argo/argo-cd -n argocd --create-namespace

# 출력:
NAME: argocd
LAST DEPLOYED: Tue Jan 20 00:12:17 2026
NAMESPACE: argocd
STATUS: deployed
REVISION: 1
```

처음엔 Pod들이 ContainerCreating 상태였어요. "뭔가 잘못됐나?" 싶어서 계속 확인했는데, 알고 보니 이미지를 받는 중이었더라구요.

**설치된 구성 요소**:
1. **argocd-server**: Web UI + API
2. **argocd-repo-server**: Git Repository 연동
3. **argocd-application-controller**: 동기화 컨트롤러
4. **argocd-dex-server**: SSO 인증
5. **argocd-redis**: 캐시
6. **argocd-notifications-controller**: 알림
7. **argocd-applicationset-controller**: ApplicationSet 관리

---

### Step 3: Pod 상태 확인

약 70초 정도 기다렸더니 이렇게 나왔어요:

```bash
kubectl get pods -n argocd

# 결과 (약 70초 후):
NAME                                                READY   STATUS
argocd-application-controller-0                     1/1     Running
argocd-applicationset-controller-6564d9cfd8-p2djw   1/1     Running
argocd-dex-server-6ff5b587ff-5lmlb                  1/1     Running
argocd-notifications-controller-6594786484-npvkg    1/1     Running
argocd-redis-7d6fd75fcb-926x8                       1/1     Running
argocd-repo-server-567d8799cf-fs94b                 1/1     Running
argocd-server-5f8b4dfd84-bbqlt                      1/1     Running
```

**✅ 모든 Pod Running!**

처음으로 뭔가 제대로 설치된 것 같은 느낌이 들었습니다.

---

### Step 4: 초기 비밀번호 확인

```bash
kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath="{.data.password}" | base64 -d

# 결과:
saDtmwkg-ZyKLv2T
```

**로그인 정보**:
- 아이디: `admin`
- 비밀번호: `saDtmwkg-ZyKLv2T`

---

## Part 3: Ingress 설정 (로컬 접속)

### Step 1: Ingress Manifest 생성

**목표**: 기존 Ingress Nginx (192.168.1.200)를 통해 ArgoCD 접속

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: argocd-server-ingress
  namespace: argocd
  annotations:
    nginx.ingress.kubernetes.io/ssl-passthrough: "true"
    nginx.ingress.kubernetes.io/backend-protocol: "HTTPS"
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

**중요 설정**:
- `ssl-passthrough: "true"`: ArgoCD 자체 TLS 사용 (Self-signed)
- `backend-protocol: "HTTPS"`: Backend가 HTTPS로 통신
- `host: argocd.jiminhome.shop`: Host 헤더 기반 라우팅

---

### Step 2: Ingress 적용

```bash
kubectl apply -f /tmp/argocd-ingress.yaml

# 결과:
ingress.networking.k8s.io/argocd-server-ingress created
```

---

### Step 3: 로컬 접속 테스트

```bash
curl -k -I -H "Host: argocd.jiminhome.shop" https://192.168.1.200/

# 결과:
HTTP/2 200
date: Mon, 19 Jan 2026 15:14:57 GMT
content-type: text/html; charset=utf-8
```

**✅ 접속 성공!**

처음엔 "Host 헤더가 뭐지?"라고 생각했는데, 알고 보니 브라우저가 자동으로 보내주는 거더라구요.

**브라우저 접속 (Homeserver에서)**:
- URL: `https://192.168.1.200/`
- Host 헤더 자동 전송 (브라우저가 처리)
- 또는 `/etc/hosts`에 추가:
  ```bash
  echo "192.168.1.200 argocd.jiminhome.shop" | sudo tee -a /etc/hosts
  ```

---

## Part 4: Cloudflare Tunnel 설정 (외부 접속)

### 목표: 외부에서도 안전하게 접속

**기존 Tunnel 확인**:
```bash
cloudflared tunnel list

# 결과:
ID                                   NAME         CREATED
65759494-dae6-4287-b92d-02a918b34722 home-network 2025-10-24T23:56:36Z
```

이미 만들어둔 Tunnel이 있어서 새로 만들 필요는 없었어요.

---

### Step 1: 설정 파일 확인

```bash
cat /etc/cloudflared/config.yml

# 결과:
tunnel: 65759494-dae6-4287-b92d-02a918b34722
credentials-file: /etc/cloudflared/65759494-dae6-4287-b92d-02a918b34722.json

ingress:
  - hostname: blog.jiminhome.shop
    service: http://192.168.1.200:80
  - service: http_status:404
```

**문제**: argocd 설정 없음

---

### Step 2: ArgoCD 추가

```yaml
tunnel: 65759494-dae6-4287-b92d-02a918b34722
credentials-file: /etc/cloudflared/65759494-dae6-4287-b92d-02a918b34722.json

ingress:
  - hostname: blog.jiminhome.shop
    service: http://192.168.1.200:80
  - hostname: argocd.jiminhome.shop
    service: https://192.168.1.200:443
    originRequest:
      noTLSVerify: true  # Self-signed 인증서 허용
  - service: http_status:404
```

**적용**:
```bash
sudo cp /tmp/config.yml.new /etc/cloudflared/config.yml
sudo systemctl restart cloudflared
```

---

### ⚠️ 트러블슈팅: 설정 미반영 문제

**문제**: Cloudflared 재시작해도 argocd 설정 반영 안 됨

이때 정말 막막했어요. 분명히 파일을 수정했는데 왜 안 되는 거지?

```bash
sudo journalctl -u cloudflared -n 20

# 로그:
Updated to new configuration config="{\"ingress\":[{\"hostname\":\"blog.jiminhome.shop\"...}"
# argocd가 없음!
```

**원인**:
1. Systemd가 `/etc/cloudflared/config.yml` 사용
2. 사용자 `~/.cloudflared/config.yml`도 존재
3. 우선순위 혼동
4. `warp-routing` 설정 오류

**해결**:
```bash
# warp-routing 제거
cat <<'EOF' | sudo tee /etc/cloudflared/config.yml > /dev/null
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
EOF

# 검증
cloudflared --config /etc/cloudflared/config.yml tunnel ingress validate
# 결과: OK ✅

# 재시작
sudo systemctl restart cloudflared
```

그런데... 여전히 문제가 있었어요. Cloudflare 서버에서 오래된 설정을 캐싱하고 있었던 거죠.

---

### Step 3: DNS 라우팅 직접 추가 (대안)

고민 끝에 다른 방법을 찾았습니다:

```bash
cloudflared tunnel route dns home-network argocd.jiminhome.shop

# 결과:
2026-01-19T15:26:13Z INF Added CNAME argocd.jiminhome.shop
```

**✅ DNS 라우팅 추가 성공!**

**확인**:
```bash
curl -I https://argocd.jiminhome.shop/

# 결과:
HTTP/2 404  # DNS는 추가됐으나 Ingress 규칙 미적용
```

**최종 상태**:
- DNS: argocd.jiminhome.shop → home-network tunnel ✅
- Ingress 규칙: Cloudflare Dashboard에서 수동 추가 필요 (미완)

---

## 📊 학습 포인트

### 1. Helm의 가치

**26,951줄 YAML vs values.yaml**:
- Helm이 없으면 26,951줄 YAML 관리 불가
- values.yaml로 간단한 설정 변경
- `helm history`, `helm rollback`로 버전 관리

처음엔 "굳이 Helm을 써야 하나?" 싶었는데, 막상 써보니 왜 모두가 Helm을 쓰는지 알겠더라구요.

**회사에서의 Helm 사용**:
- 개발 환경: Helm으로 빠른 실험
- 프로덕션: GitOps (ArgoCD + Helm Chart)
- Helm Chart를 Git에 저장 → ArgoCD가 자동 배포

---

### 2. Ingress 동작 원리

**Host 헤더 기반 라우팅**:
```
curl -H "Host: argocd.jiminhome.shop" https://192.168.1.200/
  ↓
Ingress Controller가 Host 헤더 확인
  ↓
argocd.jiminhome.shop → argocd-server:443 라우팅
```

처음엔 Host 헤더를 수동으로 보내야 하는 줄 알았어요. 근데 알고 보니 브라우저가 자동으로 해주더라구요.

**브라우저는 자동으로 Host 헤더 전송**:
- URL에 도메인 입력 → Host 헤더 자동 추가
- `/etc/hosts`로 DNS 대체 가능

---

### 3. Cloudflare Tunnel 우선순위

**설정 파일 우선순위**:
1. `~/.cloudflared/config.yml` (사용자)
2. `/etc/cloudflared/config.yml` (시스템)
3. Systemd Service 파일 (`--config` 플래그)

이 부분 때문에 한참 헤맸어요. 어떤 파일을 수정해야 하는지 몰라서...

**주의사항**:
- 여러 설정 파일 존재 시 혼동
- Systemd Service 파일 확인 필수:
  ```bash
  cat /etc/systemd/system/cloudflared.service
  # ExecStart=/usr/local/bin/cloudflared --config /etc/cloudflared/config.yml
  ```

---

### 4. DNS vs Ingress 규칙

**DNS 라우팅**:
```bash
cloudflared tunnel route dns home-network argocd.jiminhome.shop
```
- Cloudflare DNS에 CNAME 레코드 추가
- `argocd.jiminhome.shop` → `home-network.cfargotunnel.com`

**Ingress 규칙** (Cloudflared config.yml):
```yaml
ingress:
  - hostname: argocd.jiminhome.shop
    service: https://192.168.1.200:443
```
- Tunnel이 트래픽을 어디로 보낼지 정의
- DNS만 추가하고 Ingress 규칙 없으면 → 404

처음엔 DNS만 추가하면 될 줄 알았는데, 둘 다 필요하더라구요.

**둘 다 필요** ✅

---

## 🔧 트러블슈팅 요약

### 문제 1: Pod 생성 느림

**증상**: `kubectl get pods` 시 ContainerCreating

**원인**: 이미지 Pull, Init Container 실행

**해결**: 60초 대기 후 재확인

처음엔 뭔가 잘못된 줄 알았는데, 그냥 시간이 필요했던 거였어요.

---

### 문제 2: Cloudflared 설정 미반영

**증상**: 설정 파일 수정 후 재시작해도 반영 안 됨

**원인**:
1. 여러 설정 파일 존재
2. `warp-routing` 구문 오류

**해결**:
```bash
# 두 파일 모두 업데이트
/etc/cloudflared/config.yml
~/.cloudflared/config.yml

# warp-routing 제거
# DNS 라우팅 직접 추가
cloudflared tunnel route dns home-network argocd.jiminhome.shop
```

---

### 문제 3: HTTP/2 404

**증상**: `curl https://argocd.jiminhome.shop/` → 404

**원인**: DNS는 추가됐으나 Ingress 규칙 미적용

**해결 방법**:
1. **Cloudflare Dashboard 수동 설정**:
   - https://dash.cloudflare.com
   - Zero Trust → Access → Tunnels
   - `home-network` → Public Hostname 추가
   - Subdomain: `argocd`, Service: `https://192.168.1.200:443`
   - Additional settings: `No TLS Verify` 활성화

2. **로컬 접속으로 계속 진행** (현재):
   - `https://192.168.1.200/` (Host 헤더 필요)
   - Application 생성 후 Cloudflare 설정

---

## 🎯 다음 단계

### 1단계: ArgoCD UI 로그인
```bash
# 로컬 접속
https://192.168.1.200/

# 로그인
아이디: admin
비밀번호: saDtmwkg-ZyKLv2T
```

### 2단계: Git Repository 준비
- blog-system manifest 정리
- Git Repository 생성

### 3단계: 첫 번째 Application 생성
- ArgoCD UI에서 Application 생성
- Git Repository 연동
- Auto-Sync 활성화

### 4단계: 실제 배포 테스트
- Git에서 replicas 변경
- ArgoCD 자동 동기화 확인

---

**작성일**: 2026-01-20
**환경**: Homeserver Kubernetes (Cilium + Ingress Nginx)
**현재 상태**: ArgoCD 설치 완료, Application 생성 대기
**소요 시간**: 약 2시간 (트러블슈팅 포함)
