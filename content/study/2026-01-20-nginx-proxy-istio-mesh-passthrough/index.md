---
title: "[Istio 시리즈 #8] PassthroughCluster 문제 해결: nginx proxy → Istio mesh 통합"
date: 2026-01-20T16:00:00+09:00
description: "Kiali에서 검정색 연결 (mesh 우회) 문제를 Host 헤더와 DestinationRule로 해결"
tags: ["kubernetes", "istio", "service-mesh", "nginx", "mtls", "kiali", "troubleshooting"]
categories: ["study", "Service Mesh", "Networking"]
series: ["Istio 실전 시리즈"]
showtoc: true
tocopen: true
---

## 개요

web Pod 내부 nginx proxy에서 was-service로 보내는 API 트래픽이 Istio mesh를 우회하는 문제:

| 번호 | 문제 | 원인 |
|------|------|------|
| 1 | Kiali에서 web → was 연결 안 보임 | nginx proxy 미사용 |
| 2 | PassthroughCluster (검정색 연결) | Host 헤더가 외부 도메인 |
| 3 | mTLS 아이콘 없음 | DestinationRule 누락 |
| 4 | 502 Bad Gateway | STRICT mTLS + Nginx Ingress 충돌 |

**최종 해결**:
- nginx config에서 `Host: was-service` 설정
- was-destinationrule.yaml 생성 (mTLS ISTIO_MUTUAL)
- PeerAuthentication `PERMISSIVE` 모드 (Nginx Ingress 호환)

---

## 1. web → was 연결이 Kiali에 안 보이는 문제

### 상황

**Before (잘못된 아키텍처)**:

```
[Nginx Ingress]
  ↓ /api → was-service:8080 직접 라우팅
[was-service]
  ↓
[was Pod]
```

**문제**:
```bash
# Kiali Graph 확인
open http://kiali.jiminhome.shop
# Namespace: blog-system
```

**결과**: web → was 연결선이 아예 표시 안 됨

**원인**: Nginx Ingress가 `/api` 트래픽을 was-service로 직접 라우팅하므로 web Pod를 거치지 않음

---

### 해결

**Step 1**: Nginx Ingress 라우팅 수정

`k8s-manifests/ingress-nginx/blog-ingress.yaml`:

**Before**:
```yaml
spec:
  rules:
  - host: blog.jiminhome.shop
    http:
      paths:
      - path: /api
        backend:
          service:
            name: was-service  # ❌ 직접 라우팅 (web 우회)
            port:
              number: 8080
```

**After**:
```yaml
spec:
  rules:
  - host: blog.jiminhome.shop
    http:
      paths:
      - path: /
        backend:
          service:
            name: web-service  # ✅ 모든 트래픽 → web
            port:
              number: 80
```

**Step 2**: web Pod 내부 nginx에서 /api → was proxy 설정

`blog-k8s-project/web/nginx.conf`:

```nginx
server {
    listen 80;
    server_name _;

    # 정적 파일
    location / {
        root /usr/share/nginx/html;
        index index.html;
    }

    # API 트래픽 → WAS proxy
    location /api {
        proxy_pass http://was-service.blog-system.svc.cluster.local:8080;
        proxy_set_header Host $host;  # 나중에 수정 필요!
    }
}
```

**적용**:

```bash
cd ~/blogsite
git add blog-k8s-project/web/nginx.conf
git commit -m "feat: Add nginx proxy for /api → was-service"
git push
```

**WEB 재배포 후 확인**:

```bash
# Kiali에서 트래픽 생성
for i in {1..50}; do
  curl -s http://blog.jiminhome.shop/api/posts > /dev/null
  sleep 1
done
```

**Kiali Graph 확인**:

```
[istio-ingressgateway] → [web-service] → ??? (was 연결 안 보임)
```

✅ web-service는 보이지만 아직 was 연결 없음 → PassthroughCluster 문제

---

## 2. PassthroughCluster (Istio mesh 우회)

### 상황

**Kiali Graph**:

```
[istio-ingressgateway] → [web-service]
                             ↓ (검정색 화살표)
                         [PassthroughCluster]
```

**설명**:
- PassthroughCluster = Istio가 목적지를 알 수 없어 그냥 통과시킴
- 검정색 연결 = Istio mesh 외부 트래픽으로 인식
- mTLS, Retry, Timeout 등 Istio 기능 모두 미적용

**web Pod Envoy 로그 확인**:

```bash
kubectl logs -n blog-system <web-pod> -c istio-proxy --tail=50
```

```
[2026-01-20] cluster=PassthroughCluster upstream_host=10.0.1.16:8080
```

**문제**: Istio가 was-service를 mesh 내부로 인식하지 못함

---

### 원인 분석

**nginx config 확인**:

```nginx
location /api {
    proxy_pass http://was-service.blog-system.svc.cluster.local:8080;
    proxy_set_header Host $host;  # ← 문제의 원인!
}
```

**`$host` 값**:

```bash
# Nginx Ingress → web Pod로 전달된 Host 헤더
$host = blog.jiminhome.shop  # 외부 도메인
```

**Istio 판단 로직**:

```
if (Host == "was-service" || Host == "was-service.blog-system.svc.cluster.local")
  → Mesh 내부 트래픽 ✅
else
  → PassthroughCluster (mesh 외부) ❌
```

**결과**:
- Istio가 `Host: blog.jiminhome.shop`를 보고 외부 트래픽으로 판단
- mesh 정책 (mTLS, DestinationRule) 적용 안 됨

---

### 해결

**nginx config 수정**:

`blog-k8s-project/web/nginx.conf`:

**Before**:
```nginx
location /api {
    proxy_pass http://was-service.blog-system.svc.cluster.local:8080;
    proxy_set_header Host $host;  # ❌ blog.jiminhome.shop
}
```

**After**:
```nginx
location /api {
    proxy_pass http://was-service.blog-system.svc.cluster.local:8080;
    proxy_set_header Host was-service;  # ✅ Istio가 mesh 내부로 인식
}
```

**적용**:

```bash
cd ~/blogsite
git add blog-k8s-project/web/nginx.conf
git commit -m "fix: Set Host header to was-service for Istio mesh"
git push
```

**WEB 재배포 대기** (GitHub Actions):

```bash
# 워크플로우 완료 확인
tail -f ~/actions-runner/_diag/Worker_*.log | grep "deploy-web"
```

---

### 확인

**트래픽 생성**:

```bash
for i in {1..50}; do
  curl -s http://blog.jiminhome.shop/api/posts > /dev/null
  sleep 1
done
```

**Kiali Graph**:

```
[istio-ingressgateway]
  ↓
[web-service]
  ↓ (녹색 화살표 ✅)
[was-service]
  ↓
[was Pod]
```

✅ PassthroughCluster 사라짐!

**Envoy 로그 확인**:

```bash
kubectl logs -n blog-system <web-pod> -c istio-proxy --tail=50
```

```
[2026-01-20] cluster=outbound|8080||was-service.blog-system.svc.cluster.local
upstream_host=10.0.1.16:8080
```

✅ `cluster=outbound|8080||was-service` → Istio mesh 내부 트래픽으로 인식!

---

## 3. mTLS 아이콘이 Kiali에 없는 문제

### 상황

**Kiali Graph**:
- web → was 연결선은 녹색 ✅
- 하지만 **자물쇠 아이콘 (mTLS)** 없음 ❌

**확인**:

```bash
# DestinationRule 조회
kubectl get destinationrule -n blog-system
```

```
NAME            HOST         AGE
web-dest-rule   web-service  5d
```

**문제**: was-service에 DestinationRule 없음

---

### 원인

Istio는 **DestinationRule이 있어야** 트래픽 정책 (mTLS, Connection Pool, Circuit Breaker) 적용:

```
DestinationRule 없음
  → Istio가 기본 설정 사용
  → mTLS PERMISSIVE 모드 (평문 + mTLS 둘 다 허용)
  → Kiali에서 mTLS 여부 표시 안 됨
```

---

### 해결

**was-destinationrule.yaml 생성**:

`k8s-manifests/blog-system/was-destinationrule.yaml`:

```yaml
apiVersion: networking.istio.io/v1beta1
kind: DestinationRule
metadata:
  name: was-dest-rule
  namespace: blog-system
  labels:
    app: was
    tier: backend
spec:
  host: was-service  # Service 이름

  # 트래픽 정책
  trafficPolicy:
    # mTLS 설정
    tls:
      mode: ISTIO_MUTUAL  # mTLS 명시적 강제 🔒

    # Connection Pool 설정
    connectionPool:
      http:
        http1MaxPendingRequests: 100  # 대기 가능한 최대 요청 수
        http2MaxRequests: 100          # HTTP/2 최대 요청 수
        maxRequestsPerConnection: 10   # 커넥션당 최대 요청 수

    # Load Balancing
    loadBalancer:
      simple: ROUND_ROBIN  # 라운드 로빈 방식

  # Argo Rollouts용 subset
  subsets:
  - name: stable  # 안정 버전
  - name: canary  # 카나리 버전
```

**적용**:

```bash
cd ~/k8s-manifests
git add blog-system/was-destinationrule.yaml
git commit -m "feat: Add DestinationRule for was-service with mTLS"
git push
```

**ArgoCD 자동 동기화 대기** (3초 이내):

```bash
kubectl get destinationrule -n blog-system -w
```

```
NAME             HOST          AGE
web-dest-rule    web-service   5d
was-dest-rule    was-service   1s  # ✅ 추가됨
```

---

### 확인

**트래픽 생성**:

```bash
for i in {1..50}; do
  curl -s http://blog.jiminhome.shop/api/posts > /dev/null
  sleep 1
done
```

**Kiali Graph**:

```
[web-service] ──🔒──> [was-service]
              (자물쇠 아이콘 표시 ✅)
```

✅ mTLS 활성화 확인!

**Envoy 통계 확인**:

```bash
kubectl exec -n blog-system <web-pod> -c istio-proxy -- \
  curl -s localhost:15000/stats | grep ssl
```

```
ssl.handshake: 12  # mTLS handshake 발생 ✅
```

---

## 4. 502 Bad Gateway (STRICT mTLS 문제)

### 상황

**PeerAuthentication을 STRICT로 설정 후**:

`blog-system/peer-authentication.yaml`:

```yaml
apiVersion: security.istio.io/v1beta1
kind: PeerAuthentication
metadata:
  name: default
  namespace: blog-system
spec:
  mtls:
    mode: STRICT  # ❌ 모든 통신 mTLS 강제
```

**적용 후 블로그 접속**:

```bash
curl -I http://blog.jiminhome.shop/
```

```
HTTP/1.1 502 Bad Gateway
```

---

### 원인

**트래픽 플로우**:

```
[Nginx Ingress Controller] (mesh 외부)
  ↓ Plain HTTP (mTLS 없음)
[web Pod] (mesh 내부, STRICT mTLS 요구)
  → 연결 거부 ❌
```

**문제**:
- Nginx Ingress Controller는 Istio mesh 외부에 있음
- STRICT 모드는 모든 수신 트래픽에 mTLS 요구
- Ingress → web 구간은 평문 HTTP
- **mTLS 인증서 없음 → 502 에러**

---

### 해결

**PeerAuthentication을 PERMISSIVE로 변경**:

`blog-system/peer-authentication.yaml`:

```yaml
apiVersion: security.istio.io/v1beta1
kind: PeerAuthentication
metadata:
  name: default
  namespace: blog-system
spec:
  mtls:
    mode: PERMISSIVE  # ✅ 평문과 mTLS 모두 허용
```

**적용**:

```bash
cd ~/k8s-manifests
git add blog-system/peer-authentication.yaml
git commit -m "fix: Change mTLS to PERMISSIVE for Nginx Ingress compatibility"
git push
```

**확인**:

```bash
curl -I http://blog.jiminhome.shop/
```

```
HTTP/1.1 200 OK  # ✅ 정상 동작
```

---

### PERMISSIVE vs STRICT 비교

| 모드 | 평문 HTTP | mTLS | 사용 사례 |
|------|-----------|------|----------|
| **PERMISSIVE** | ✅ 허용 | ✅ 허용 | Nginx Ingress + Istio mesh 혼용 |
| **STRICT** | ❌ 거부 | ✅ 허용 | 모든 트래픽이 mesh 내부에 있을 때 |

**현재 선택**: PERMISSIVE
- Nginx Ingress → web: 평문 HTTP
- web → was: mTLS (DestinationRule `ISTIO_MUTUAL`)

**향후 개선** (Nginx Ingress 제거 후):
- Istio Gateway로 완전 전환
- STRICT 모드 적용 가능

---

## 5. 전체 검증

### 5-1. Kiali 트래픽 시각화

**트래픽 생성**:

```bash
for i in {1..100}; do
  curl -s http://blog.jiminhome.shop/ > /dev/null
  curl -s http://blog.jiminhome.shop/api/posts > /dev/null
  sleep 1
done
```

**Kiali Graph** (http://kiali.jiminhome.shop):

```
[istio-ingressgateway]
  ↓
[web-service]
  ├─ web-stable-xxx (90%)  ← Canary 배포
  ├─ web-canary-xxx (10%)
  └─ /api 요청
        ↓ 🔒 (mTLS)
      [was-service]
        ↓
      [was-xxx]
        ↓
      [mysql] (mesh 제외)
```

**확인 사항**:
- web → was 연결선 녹색 (mesh 내부)
- 🔒 자물쇠 아이콘 (mTLS 활성화)
- PassthroughCluster 없음
- Canary 배포 트래픽 분산 (90% stable, 10% canary)

---

### 5-2. mTLS 인증서 확인

**web Pod에서 was로 요청 시 인증서 검증**:

```bash
kubectl exec -n blog-system <web-pod> -c istio-proxy -- \
  openssl s_client -connect was-service:8080 -showcerts < /dev/null 2>&1 | \
  grep "Verification"
```

```
Verification: OK  # ✅ mTLS 인증서 검증 성공
```

**인증서 발급자 확인**:

```bash
kubectl exec -n blog-system <web-pod> -c istio-proxy -- \
  curl -s localhost:15000/certs | grep -A 5 "Cert Chain"
```

```
Cert Chain:
  Certificate Path: /etc/certs/cert-chain.pem
  Issuer: CN=Intermediate CA,O=Istio  # ✅ Istio가 자동 발급
```

---

### 5-3. Retry 정책 동작 확인

**DestinationRule에 Retry 추가**:

`blog-system/was-destinationrule.yaml`:

```yaml
spec:
  trafficPolicy:
    # ... (기존 설정)

    # Outlier Detection (Circuit Breaker)
    outlierDetection:
      consecutive5xxErrors: 5      # 5번 연속 5xx 에러 발생 시
      interval: 10s                 # 10초마다 상태 체크
      baseEjectionTime: 30s         # 30초간 트래픽에서 제외
      maxEjectionPercent: 50        # 최대 50% Pod까지 제외 가능
```

**WAS Pod 1개 강제 종료**:

```bash
kubectl delete pod -n blog-system -l app=was --field-selector metadata.name=was-xxx
```

**API 요청 테스트**:

```bash
time curl http://blog.jiminhome.shop/api/posts
```

```
[{"id":1,"title":"테스트"}]  # ✅ 정상 응답

real    0m0.123s  # 약간의 지연 (다른 Pod로 failover)
```

**Envoy 통계**:

```bash
kubectl exec -n blog-system <web-pod> -c istio-proxy -- \
  curl -s localhost:15000/stats | grep "upstream_rq_retry"
```

```
upstream_rq_retry: 1  # ✅ 1번 재시도로 성공
```

---

## 6. 아키텍처 비교

### Before (문제 많음)

```
[Nginx Ingress]
  ├─ / → web-service ✅
  └─ /api → was-service ❌ (web 우회)
        ↓ Plain HTTP
      [was Pod]
```

**문제**:
- web → was 연결 안 보임
- PassthroughCluster
- mTLS 없음
- Istio 정책 미적용

---

### After (완전 해결)

```
[Nginx Ingress]
  ↓ / 및 /api 모두 → web-service
[web-service]
  ↓
[web Pod nginx proxy]
  ├─ / → 정적 파일 (Hugo)
  └─ /api → was-service (proxy_pass)
        ↓ 🔒 mTLS (ISTIO_MUTUAL)
        ↓ Host: was-service
      [was-service]
        ↓
      [was Pod]
```

**개선**:
- Kiali에서 web → was 시각화
- PassthroughCluster 제거
- mTLS 암호화
- DestinationRule 정책 적용 (Retry, Timeout, Circuit Breaker)
- Istio 관측성 (Metrics, Logs, Tracing)

---

## 7. 정리

### 핵심 해결책

| 문제 | 해결 |
|------|------|
| **Kiali에 web → was 안 보임** | Ingress 라우팅 수정 (모든 트래픽 → web-service) |
| **PassthroughCluster** | nginx config에 `proxy_set_header Host was-service` |
| **mTLS 아이콘 없음** | was-destinationrule.yaml 생성 (`tls.mode: ISTIO_MUTUAL`) |
| **502 Bad Gateway** | PeerAuthentication `PERMISSIVE` 모드 (STRICT → PERMISSIVE) |

### 파일 변경 사항

| 파일 | 변경 내용 |
|------|-----------|
| `blog-k8s-project/web/nginx.conf` | `proxy_set_header Host was-service` 추가 |
| `blog-system/was-destinationrule.yaml` | 신규 생성 (mTLS + Connection Pool) |
| `blog-system/peer-authentication.yaml` | `PERMISSIVE` 모드로 변경 |
| `ingress-nginx/blog-ingress.yaml` | `/api` 라우팅 제거 (모든 트래픽 → web) |

### 개선 효과

| 지표 | Before | After |
|------|--------|-------|
| **Kiali 시각화** | web만 보임 | web → was 전체 플로우 |
| **mTLS** | ❌ 없음 | ✅ ISTIO_MUTUAL |
| **PassthroughCluster** | 100% | 0% |
| **Istio 정책** | 미적용 | ✅ Retry, Timeout, Circuit Breaker |

---

**작성일**: 2026-01-20
**태그**: kubernetes, istio, service-mesh, nginx, mtls, troubleshooting
**관련 문서**: [완전한 Istio 아키텍처](../2026-01-24-complete-istio-architecture/)
