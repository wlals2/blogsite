---
title: "ArgoCD 트러블슈팅 모음"
date: 2026-01-23
description: "ArgoCD 설치 및 운영 중 발생한 문제들 해결 기록"
tags: ["argocd", "kubernetes", "gitops", "cloudflare-tunnel", "troubleshooting"]
categories: ["study", "Troubleshooting"]
---

## 1. Cloudflared 설정 미반영

### 증상

```bash
# config.yml에 argocd 추가 후 재시작
sudo systemctl restart cloudflared

# 로그에서 argocd 설정 없음
sudo journalctl -u cloudflared -n 20
# 출력에 argocd가 없음!
```

### 원인

| 문제 | 설명 |
|------|------|
| 설정 파일 우선순위 | 시스템 vs 사용자 설정 |
| warp-routing 구문 오류 | validation 실패 |

**설정 파일 우선순위:**
1. Systemd Service의 `--config` 플래그
2. `/etc/cloudflared/config.yml` (시스템)
3. `~/.cloudflared/config.yml` (사용자)

### 해결

```yaml
# /etc/cloudflared/config.yml
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
```

```bash
# Validation
cloudflared --config /etc/cloudflared/config.yml tunnel ingress validate
# OK

# DNS 라우팅 추가
cloudflared tunnel route dns home-network argocd.jiminhome.shop
```

---

## 2. Ingress TLS Passthrough

### 증상

```bash
curl -I https://argocd.jiminhome.shop/
# HTTP/2 502 Bad Gateway
```

### 원인

| 항목 | 값 |
|------|-----|
| ArgoCD 인증서 | Self-signed |
| Ingress 동작 | TLS 종료 시도 |
| 결과 | ArgoCD가 HTTP 요청 거부 |

### 해결

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

| Annotation | 설명 |
|------------|------|
| ssl-passthrough | TLS를 종료하지 않고 전달 |
| backend-protocol | Backend HTTPS 통신 |

---

## 3. Pod 생성 느림 (ContainerCreating)

### 증상

```bash
kubectl get pods -n argocd
# NAME                   READY   STATUS              RESTARTS   AGE
# argocd-server-xxx      0/1     ContainerCreating   0          5m
```

### 원인

| 문제 | 설명 |
|------|------|
| 이미지 Pull | 최초 다운로드 시간 |
| 네트워크 | Self-hosted 환경 인터넷 속도 |

### 해결

```bash
# 이미지 미리 Pull
docker pull quay.io/argoproj/argocd:v2.9.3
docker pull redis:7.0.11-alpine
docker pull ghcr.io/dexidp/dex:v2.37.0
```

---

## 4. 초기 비밀번호 찾기 실패

### 증상

```bash
kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath="{.data.password}"
# Error: secrets "argocd-initial-admin-secret" not found
```

### 원인

| 상황 | 설명 |
|------|------|
| Helm 설치 | secret 자동 생성 안 됨 |
| 기존 설치 | admin 계정 비활성화 |

### 해결

**방법 1: Secret 확인**
```bash
kubectl get secrets -n argocd | grep admin
```

**방법 2: 비밀번호 재설정**
```bash
# bcrypt 해시 생성
htpasswd -nbBC 10 "" "newpassword" | tr -d ':\n'

# argocd-secret 업데이트
kubectl -n argocd patch secret argocd-secret \
  -p '{"stringData": {"admin.password": "<bcrypt-hash>", "admin.passwordMtime": "'$(date +%FT%T%Z)'"}}'

# Pod 재시작
kubectl -n argocd rollout restart deployment argocd-server
```

---

## 5. Istio mTLS로 인한 502 Bad Gateway

### 증상

```bash
curl https://argocd.jiminhome.shop/
# 502 Bad Gateway
```

### 원인

| 항목 | 값 |
|------|-----|
| Istio mTLS | STRICT 모드 |
| ArgoCD | mTLS 미지원 |
| 결과 | 연결 거부 |

### 해결

```yaml
# PeerAuthentication (argocd namespace)
apiVersion: security.istio.io/v1beta1
kind: PeerAuthentication
metadata:
  name: argocd-mtls-disable
  namespace: argocd
spec:
  mtls:
    mode: DISABLE  # ArgoCD는 mTLS 제외
```

---

## 정리

### ArgoCD 접속 체크리스트

| 확인 항목 | 명령어 |
|----------|--------|
| DNS 확인 | `dig argocd.jiminhome.shop` |
| Cloudflared | `journalctl -u cloudflared -n 20` |
| Ingress | `kubectl get ingress -n argocd` |
| Pod 상태 | `kubectl get pods -n argocd` |
| mTLS | `kubectl get peerauthentication -n argocd` |

### 접속 정보

```bash
# 외부 접속
https://argocd.jiminhome.shop/

# 로컬 접속
curl -k -H "Host: argocd.jiminhome.shop" https://192.168.X.200/

# 로그인 (admin)
kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath="{.data.password}" | base64 -d
```
