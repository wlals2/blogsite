---
title: "Nginx Ingress 제거 및 Istio Gateway 일원화"
date: 2026-01-24
description: "중복 L7 라우팅 레이어 제거로 아키텍처 단순화 및 성능 개선"
tags: ["kubernetes", "istio", "gateway", "ingress", "service-mesh", "metallb", "architecture"]
categories: ["study", "Service Mesh", "Networking"]
---

## 개요

Nginx Ingress Controller와 Istio Gateway의 중복 L7 라우팅 레이어를 제거하고, Istio Gateway로 일원화한 마이그레이션 과정:

| 항목 | Before (2026-01-23) | After (2026-01-24) |
|------|---------------------|---------------------|
| **진입점** | Cloudflare → MetalLB → **Nginx Ingress** → Istio Gateway | Cloudflare → MetalLB → **Istio Gateway** |
| **L7 Hop** | 2개 (Nginx + Istio) | 1개 (Istio) |
| **관리 포인트** | Ingress + VirtualService | VirtualService만 |
| **레이턴시** | Hop 2개 | Hop 1개 감소 ✅ |
| **Istio 기능** | 제한적 (Nginx 경유) | 완전 활용 ✅ |

**개선 효과**:
- 중복 레이어 제거 (Nginx Ingress 삭제)
- 아키텍처 단순화
- Istio 기능 완전 활용 (Retry, Timeout, Circuit Breaker)
- 관리 복잡도 감소

---

## 1. 왜 변경했는가?

### 문제 상황

**기존 아키텍처 (2026-01-23)**:

```
[Cloudflare CDN]
  ↓ HTTPS
[MetalLB: 192.168.1.200]
  ↓
[Nginx Ingress Controller] ← L7 라우팅 #1
  ├─ Host: blog.jiminhome.shop
  ├─ Host: monitoring.jiminhome.shop
  └─ Path: /, /api
  ↓
[Istio Gateway] ← L7 라우팅 #2 (중복!)
  ├─ VirtualService: blog-routes
  ├─ VirtualService: monitoring-routes
  └─ DestinationRule: retry, timeout
  ↓
[Kubernetes Services]
```

### 문제점

| 문제 | 설명 |
|------|------|
| **중복 L7 라우팅** | Nginx Ingress와 Istio Gateway 모두 HTTP 라우팅 |
| **불필요한 Hop** | 트래픽이 Nginx를 거쳐 Istio로 전달 (레이턴시 증가) |
| **Istio 기능 제한** | Nginx Ingress가 트래픽을 먼저 받아 Istio 기능 제한적 |
| **설정 분산** | Ingress 리소스 + VirtualService 2곳 관리 |
| **복잡도 증가** | 문제 발생 시 Nginx와 Istio 둘 다 확인 필요 |

### 선택 이유: Istio Gateway

| 비교 항목 | Nginx Ingress | Istio Gateway |
|-----------|---------------|---------------|
| **L7 라우팅** | ✅ 지원 | ✅ 지원 |
| **Retry 정책** | 제한적 | ✅ 완전 제어 |
| **Timeout** | 제한적 | ✅ Per-route 설정 |
| **Circuit Breaker** | ❌ 없음 | ✅ DestinationRule |
| **Canary 배포** | 복잡함 | ✅ Argo Rollouts 통합 |
| **mTLS** | ❌ 없음 | ✅ Istio mesh 통합 |
| **Cross-Namespace** | 제한적 | ✅ 완전 지원 |
| **Observability** | 제한적 | ✅ Kiali, Jaeger |

**결론**: Istio Gateway가 모든 요구사항을 충족하므로 Nginx Ingress는 불필요

---

## 2. 변경 절차

### 2-1. MetalLB IP를 Istio Gateway로 이동

**현재 상태 확인**:

```bash
kubectl get svc -n istio-system istio-ingressgateway
```

```
NAME                   TYPE           EXTERNAL-IP   PORT(S)
istio-ingressgateway   LoadBalancer   <pending>     80:32000/TCP,443:32001/TCP
```

**문제**: MetalLB IP (192.168.1.200)가 Nginx Ingress에 할당되어 있음

```bash
kubectl get svc -n ingress-nginx ingress-nginx-controller
```

```
NAME                       TYPE           EXTERNAL-IP      PORT(S)
ingress-nginx-controller   LoadBalancer   192.168.1.200    80:30080/TCP
```

**해결**: Istio Gateway에 MetalLB IP 할당

```bash
# Nginx Ingress에서 IP 해제
kubectl delete svc -n ingress-nginx ingress-nginx-controller

# Istio Gateway에 IP 할당
kubectl patch svc -n istio-system istio-ingressgateway -p '
{
  "metadata": {
    "annotations": {
      "metallb.universe.tf/ip-allocated-from-pool": "local-pool",
      "metallb.universe.tf/loadBalancerIPs": "192.168.1.200"
    }
  }
}'
```

**확인**:

```bash
kubectl get svc -n istio-system istio-ingressgateway
```

```
NAME                   TYPE           EXTERNAL-IP      PORT(S)
istio-ingressgateway   LoadBalancer   192.168.1.200    80:8080/TCP,443:8443/TCP
```

✅ MetalLB IP가 Istio Gateway로 이동 완료

---

### 2-2. Istio Gateway 리소스 생성

**파일**: `k8s-manifests/blog-system/istio-gateway.yaml`

```yaml
apiVersion: networking.istio.io/v1beta1
kind: Gateway
metadata:
  name: blog-gateway
  namespace: blog-system
spec:
  selector:
    istio: ingressgateway  # istio-system의 istio-ingressgateway Pod 사용
  servers:
  - port:
      number: 80
      name: http
      protocol: HTTP
    hosts:
    - "*.jiminhome.shop"  # 모든 서브도메인 (blog, monitoring, argocd, kiali)
```

**적용**:

```bash
cd ~/k8s-manifests
git add blog-system/istio-gateway.yaml
git commit -m "feat: Add Istio Gateway for all subdomains"
git push
```

**확인**:

```bash
kubectl get gateway -n blog-system
```

```
NAME           AGE
blog-gateway   10s
```

---

### 2-3. VirtualService 생성 (블로그)

**파일**: `k8s-manifests/blog-system/blog-routes.yaml`

```yaml
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: blog-routes
  namespace: blog-system
spec:
  hosts:
  - "blog.jiminhome.shop"  # 외부 도메인
  gateways:
  - blog-gateway  # 위에서 만든 Gateway 참조

  http:
  # Route 1: API 요청 → WEB Service (WEB nginx가 WAS로 프록시)
  - match:
    - uri:
        prefix: "/api"
    route:
    - destination:
        host: web-service  # WEB nginx가 /api를 WAS로 프록시
        subset: stable
        port:
          number: 80
    retries:
      attempts: 3
      perTryTimeout: 3s
      retryOn: 5xx,reset,connect-failure
    timeout: 15s

  # Route 2: 정적 파일 → WEB Service
  - match:
    - uri:
        prefix: "/"
    route:
    - destination:
        host: web-service
        subset: stable
        port:
          number: 80
    timeout: 10s
```

**적용**:

```bash
git add blog-system/blog-routes.yaml
git commit -m "feat: Add VirtualService for blog routing"
git push
```

---

### 2-4. 다른 서비스 VirtualService 생성

**Grafana (Monitoring)**:

`k8s-manifests/monitoring/monitoring-routes.yaml`:

```yaml
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: monitoring-routes
  namespace: monitoring
spec:
  hosts:
  - "monitoring.jiminhome.shop"
  gateways:
  - blog-system/blog-gateway  # Cross-namespace 참조
  http:
  - route:
    - destination:
        host: grafana
        port:
          number: 3000
```

**ArgoCD**:

`k8s-manifests/argocd/argocd-routes.yaml`:

```yaml
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: argocd-routes
  namespace: argocd
spec:
  hosts:
  - "argocd.jiminhome.shop"
  gateways:
  - blog-system/blog-gateway
  http:
  - route:
    - destination:
        host: argocd-server
        port:
          number: 80
```

**Kiali**:

`k8s-manifests/istio-system/kiali-routes.yaml`:

```yaml
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: kiali-routes
  namespace: istio-system
spec:
  hosts:
  - "kiali.jiminhome.shop"
  gateways:
  - blog-system/blog-gateway
  http:
  - route:
    - destination:
        host: kiali
        port:
          number: 20001
```

---

### 2-5. DestinationRule 수정 (mTLS 설정)

**이슈**: Gateway → Service는 평문 HTTP여야 함

**파일**: `k8s-manifests/blog-system/web-destinationrule.yaml`

**Before**:
```yaml
trafficPolicy:
  tls:
    mode: ISTIO_MUTUAL  # ❌ Gateway가 mTLS 요구 → 연결 실패
```

**After**:
```yaml
trafficPolicy:
  tls:
    mode: DISABLE  # ✅ Gateway → Service는 평문 HTTP
```

**이유**:
- Istio Gateway는 Ingress 역할이므로 외부 트래픽(평문 HTTP) 받음
- Gateway → Service 구간은 mTLS 불필요 (같은 클러스터 내부)
- Service ↔ Service (web → was)는 `ISTIO_MUTUAL` 사용 가능

---

### 2-6. Nginx Ingress 완전 제거

```bash
# Ingress 리소스 삭제
kubectl delete ingress -n blog-system --all

# Nginx Ingress Controller 삭제
kubectl delete namespace ingress-nginx

# Manifest 파일 삭제
cd ~/k8s-manifests
rm -rf ingress-nginx/
git add .
git commit -m "chore: Remove Nginx Ingress completely"
git push
```

---

## 3. 테스트 및 검증

### 3-1. 서비스 접근 테스트

```bash
# Blog (Hugo 정적 파일)
curl -I http://192.168.1.200/ -H "Host: blog.jiminhome.shop"
# HTTP/1.1 200 OK ✅

# Blog API
curl http://192.168.1.200/api/posts -H "Host: blog.jiminhome.shop"
# [{"id":1,"title":"테스트"}] ✅

# Monitoring (Grafana)
curl -I http://192.168.1.200/ -H "Host: monitoring.jiminhome.shop"
# HTTP/1.1 302 Found (로그인 페이지로 리다이렉트) ✅

# ArgoCD
curl -I http://192.168.1.200/ -H "Host: argocd.jiminhome.shop"
# HTTP/1.1 200 OK ✅

# Kiali
curl -I http://192.168.1.200/ -H "Host: kiali.jiminhome.shop"
# HTTP/1.1 302 Found ✅
```

**모든 서비스 정상 동작 확인!**

---

### 3-2. Kiali 트래픽 시각화

```bash
# 트래픽 생성
for i in {1..50}; do
  curl -s http://blog.jiminhome.shop/ > /dev/null
  curl -s http://blog.jiminhome.shop/api/posts > /dev/null
  sleep 1
done
```

**Kiali 확인** (http://kiali.jiminhome.shop):

```
Graph → Namespace: blog-system

[istio-ingressgateway]
  ↓
[web-service] (Canary 10% + Stable 90%)
  ├─ web-stable-xxx (90%)
  ├─ web-canary-xxx (10%)
  └─ /api 요청 → [was-service]
                   ↓
                 [was-xxx]
                   ↓
                 [mysql] (mesh 제외)
```

✅ Istio Gateway → Service 트래픽 플로우 정상 확인

---

### 3-3. Retry 정책 동작 확인

**WAS Pod 1개 강제 종료**:

```bash
kubectl delete pod -n blog-system -l app=was --field-selector metadata.name=was-xxx
```

**API 요청 테스트**:

```bash
time curl http://blog.jiminhome.shop/api/posts
```

```
[{"id":1,"title":"테스트"}]

real    0m0.523s  # Retry로 인한 약간의 지연
```

**Envoy 로그 확인**:

```bash
kubectl logs -n blog-system <web-pod> -c istio-proxy | grep "upstream_rq_retry"
```

```
upstream_rq_retry: 2  # 2번 재시도 후 성공
```

✅ Retry 정책 정상 동작 (3 attempts, 3s per try)

---

## 4. 개선 효과 측정

### 4-1. 레이턴시 비교

**측정 방법**:

```bash
# Before (Nginx Ingress + Istio Gateway)
for i in {1..100}; do
  curl -o /dev/null -s -w "%{time_total}\n" http://blog.jiminhome.shop/
done | awk '{sum+=$1} END {print "Average:", sum/NR, "s"}'

# After (Istio Gateway만)
# (동일한 명령어 실행)
```

**결과**:

| 구간 | Before | After | 개선 |
|------|--------|-------|------|
| **평균 응답 시간** | 0.085s | 0.067s | **21% 감소** ✅ |
| **Hop 수** | 2 (Nginx + Istio) | 1 (Istio) | 1개 감소 |

---

### 4-2. 리소스 사용량 비교

**Nginx Ingress Controller 제거 전**:

```bash
kubectl top pod -n ingress-nginx
```

```
NAME                        CPU    MEMORY
ingress-nginx-controller    50m    128Mi  # 불필요한 리소스 소비
```

**제거 후**:

```bash
kubectl top pod -n istio-system -l app=istio-ingressgateway
```

```
NAME                        CPU    MEMORY
istio-ingressgateway-xxx    80m    196Mi  # 통합되어 효율적
```

**개선**:
- Pod 1개 감소 (Nginx Ingress 제거)
- 메모리 사용량 128Mi 절감
- 관리 포인트 1개 감소

---

### 4-3. 설정 파일 비교

**Before**:

```
k8s-manifests/
├── ingress-nginx/
│   ├── ingress-blog.yaml      # Ingress 리소스
│   ├── ingress-monitoring.yaml
│   └── ingress-argocd.yaml
└── blog-system/
    ├── blog-virtualservice.yaml  # VirtualService 중복!
    └── web-destinationrule.yaml
```

**After**:

```
k8s-manifests/
├── blog-system/
│   ├── istio-gateway.yaml       # Gateway (1개만)
│   ├── blog-routes.yaml         # VirtualService
│   └── web-destinationrule.yaml
├── monitoring/
│   └── monitoring-routes.yaml
└── argocd/
    └── argocd-routes.yaml
```

**개선**:
- Ingress 리소스 제거 (중복 해소)
- 설정 파일 구조 단순화
- Cross-namespace VirtualService 지원

---

## 5. 트러블슈팅

### 5-1. Gateway → Service 연결 실패

**상황**:

```bash
curl -I http://blog.jiminhome.shop/
```

```
HTTP/1.1 503 Service Unavailable
```

**원인**: DestinationRule의 mTLS 설정 오류

```yaml
# web-destinationrule.yaml (잘못된 설정)
trafficPolicy:
  tls:
    mode: ISTIO_MUTUAL  # ❌ Gateway가 mTLS 인증서 없음
```

**해결**:

```yaml
trafficPolicy:
  tls:
    mode: DISABLE  # ✅ Gateway → Service는 평문 HTTP
```

**이유**:
- Istio Gateway는 외부 트래픽 진입점 (Ingress 역할)
- Cloudflare → Gateway 구간은 평문 HTTP
- Gateway → Service 구간도 평문 허용 필요

---

### 5-2. Cross-Namespace VirtualService 인식 안 됨

**상황**:

```bash
curl -I http://monitoring.jiminhome.shop/
```

```
HTTP/1.1 404 Not Found
```

**원인**: Gateway 참조 오류

```yaml
# monitoring/monitoring-routes.yaml (잘못된 설정)
spec:
  gateways:
  - blog-gateway  # ❌ Same namespace에 없음
```

**해결**:

```yaml
spec:
  gateways:
  - blog-system/blog-gateway  # ✅ Namespace 명시
```

**확인**:

```bash
kubectl describe virtualservice -n monitoring monitoring-routes
```

```
Gateways:
  blog-system/blog-gateway  # ✅ Cross-namespace 참조 성공
```

---

### 5-3. ArgoCD 무한 리다이렉트

**상황**: ArgoCD 접속 시 무한 리다이렉트 발생

**원인**: ArgoCD가 HTTPS를 요구하지만 Gateway는 HTTP로 전달

**해결**: ArgoCD에 `--insecure` 플래그 추가

```yaml
# argocd/argocd-server-deployment.yaml
containers:
- name: argocd-server
  command:
  - argocd-server
  - --insecure  # ✅ HTTP 허용
```

**또는** VirtualService에 Header 추가:

```yaml
http:
- route:
  - destination:
      host: argocd-server
  headers:
    request:
      set:
        x-forwarded-proto: https  # ✅ ArgoCD가 HTTPS로 인식
```

---

## 6. 정리

### 변경 사항 요약

| 구분 | 변경 내용 |
|------|-----------|
| **제거** | Nginx Ingress Controller, Ingress 리소스 |
| **추가** | Istio Gateway (`blog-gateway`), VirtualService 4개 |
| **수정** | DestinationRule mTLS `DISABLE`, MetalLB IP 이동 |

### 개선 효과

| 항목 | 개선 |
|------|------|
| **레이턴시** | 21% 감소 (0.085s → 0.067s) |
| **Hop 수** | 1개 감소 (2 → 1) |
| **관리 포인트** | Ingress + VirtualService → VirtualService만 |
| **Istio 기능** | 제한적 → 완전 활용 (Retry, Timeout, Circuit Breaker) |
| **리소스** | Pod 1개 감소, 메모리 128Mi 절감 |

### 다음 단계

1. **Service ↔ Service mTLS 활성화** (선택)
   - web → was 구간 `ISTIO_MUTUAL` 적용
   - 내부 트래픽 암호화

2. **HTTPS 443 포트 추가** (선택)
   - 현재는 Cloudflare에서 SSL 종료
   - Kubernetes 내부에서 SSL 종료도 가능

3. **AuthorizationPolicy 적용** (선택)
   - IP 기반 접근 제어
   - Namespace 간 트래픽 제어

---

**작성일**: 2026-01-24
**태그**: kubernetes, istio, gateway, ingress, service-mesh
**관련 문서**: [완전한 Istio 아키텍처](../2026-01-24-complete-istio-architecture/)
