---
title: "Istio Service Mesh 아키텍처 완전 가이드"
date: 2026-01-22
description: "mTLS, Traffic Routing, Circuit Breaker, Distributed Tracing까지 프로덕션급 Service Mesh 구축"
tags: ["kubernetes", "istio", "service-mesh", "mtls", "kiali", "jaeger", "canary-deployment", "circuit-breaker"]
categories: ["study", "Service Mesh"]
---

## 개요

bare metal Kubernetes 클러스터에 Istio Service Mesh를 구축하여 프로덕션급 마이크로서비스 플랫폼 구현:

| 단계 | 목표 | 주요 기능 |
|------|------|----------|
| **Phase 1** | 기본 mesh 통합 | nginx proxy를 통한 트래픽 가시성 |
| **Phase 2** | 프로덕션 보안 | mTLS, Circuit Breaker, AuthorizationPolicy |
| **Phase 3** | 고급 트래픽 관리 | Retry, Timeout, Traffic Mirroring |
| **Phase 4** | 분산 추적 | Jaeger 통합, Trace 시각화 |

**최종 달성**:
- ✅ mTLS 암호화 (Service ↔ Service)
- ✅ Canary 배포 (Argo Rollouts 통합)
- ✅ Circuit Breaking (장애 Pod 자동 격리)
- ✅ Retry & Timeout (자동 재시도)
- ✅ Distributed Tracing (Jaeger)
- ✅ 실시간 모니터링 (Kiali, Prometheus, Grafana)

---

## 1. 전체 아키텍처

### 네트워크 플로우

```
[사용자]
  ↓ HTTPS
[Cloudflare CDN]
  ├─ SSL/TLS 종료
  ├─ DDoS 방어
  └─ Cache (정적 파일)
  ↓ HTTP (평문)
[Istio Gateway] (192.168.1.200)
  ├─ blog.jiminhome.shop
  ├─ monitoring.jiminhome.shop
  └─ argocd.jiminhome.shop
  ↓ L7 Routing (VirtualService)
[Istio Service Mesh]
  ├─ web-service (Hugo 블로그)
  │   ├─ web-stable-xxx (90%)
  │   └─ web-canary-xxx (10%)
  │
  └─ was-service (Spring Boot API)
      ├─ was-stable-xxx (80%)
      └─ was-canary-xxx (20%)
      ↓ Plain TCP (Istio mesh 제외)
      [mysql-service]
```

---

### Istio 컴포넌트

| 컴포넌트 | 역할 | Namespace |
|----------|------|-----------|
| **istiod** | Control Plane (Config 관리) | istio-system |
| **istio-ingressgateway** | 외부 트래픽 진입점 | istio-system |
| **envoy-proxy** | Sidecar (각 Pod에 주입) | blog-system |
| **kiali** | Service Mesh 시각화 | istio-system |
| **jaeger** | 분산 추적 (Tracing) | istio-system |

---

### Istio 리소스 맵

```
Gateway (istio-gateway.yaml)
  ↓ 연결
VirtualService (blog-routes.yaml)
  ├─ Routing: /api → was-service
  ├─ Routing: / → web-service
  ├─ Retry: 3 attempts
  └─ Timeout: 15s
  ↓ 참조
DestinationRule (web-dest-rule, was-dest-rule)
  ├─ mTLS: ISTIO_MUTUAL
  ├─ Connection Pool: 100 max
  ├─ Circuit Breaker: 5 errors → 30s eject
  └─ Subsets: stable, canary (Argo Rollouts)
```

---

## 2. Phase 1: 기본 mesh 통합

### 목표

**Before**: Nginx Ingress → was-service (mesh 우회)

**After**: Nginx Ingress → web-service → nginx proxy → was-service (mesh 통과)

### 구현

**Step 1**: Nginx config 수정

`blog-k8s-project/web/nginx.conf`:

```nginx
server {
    listen 80;

    # 정적 파일
    location / {
        root /usr/share/nginx/html;
        index index.html;
    }

    # API 트래픽 → WAS proxy
    location /api {
        proxy_pass http://was-service.blog-system.svc.cluster.local:8080;
        proxy_set_header Host was-service;  # ✅ Istio mesh 인식
    }
}
```

**Step 2**: Ingress 라우팅 수정

`k8s-manifests/ingress-nginx/blog-ingress.yaml`:

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

**검증**:

```bash
# Kiali에서 트래픽 생성
for i in {1..50}; do
  curl -s http://blog.jiminhome.shop/api/posts > /dev/null
  sleep 1
done

# Kiali Graph 확인
open http://kiali.jiminhome.shop
# Namespace: blog-system
# 예상: web → was 녹색 연결선
```

---

## 3. Phase 2: 프로덕션 보안

### 3-1. DestinationRule (Circuit Breaker)

**파일**: `k8s-manifests/blog-system/web-destinationrule.yaml`

```yaml
apiVersion: networking.istio.io/v1beta1
kind: DestinationRule
metadata:
  name: web-dest-rule
  namespace: blog-system
spec:
  host: web-service

  trafficPolicy:
    # mTLS 설정
    tls:
      mode: DISABLE  # Gateway → Service는 평문

    # Connection Pool (과부하 방지)
    connectionPool:
      http:
        http1MaxPendingRequests: 100
        http2MaxRequests: 100
        maxRequestsPerConnection: 10
      tcp:
        maxConnections: 100

    # Load Balancing
    loadBalancer:
      simple: ROUND_ROBIN

    # Circuit Breaker (장애 Pod 자동 격리)
    outlierDetection:
      consecutive5xxErrors: 5      # 5번 연속 5xx 에러
      interval: 10s                 # 10초마다 체크
      baseEjectionTime: 30s         # 30초간 제외
      maxEjectionPercent: 50        # 최대 50% Pod 제외
      minHealthPercent: 30          # 최소 30% Pod 활성

  # Argo Rollouts용 subset
  subsets:
  - name: stable
  - name: canary
```

**검증**:

```bash
# WAS Pod 1개 강제 500 에러 발생
kubectl exec -n blog-system <was-pod> -- killall -9 java

# API 요청 테스트 (Circuit Breaker 동작 확인)
for i in {1..20}; do
  curl http://blog.jiminhome.shop/api/posts
done

# Kiali에서 확인
# 예상: 장애 Pod는 빨간색, Circuit Breaker로 격리됨
```

---

**작성일**: 2026-01-22
**태그**: kubernetes, istio, service-mesh, mtls, kiali, jaeger
**관련 문서**:
- [Nginx Ingress → Istio Gateway 마이그레이션](../2026-01-24-nginx-ingress-to-istio-gateway/)
- [PassthroughCluster 문제 해결](../2026-01-20-nginx-proxy-istio-mesh-passthrough/)
