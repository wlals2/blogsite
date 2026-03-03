---
title: "[Istio 시리즈 #2.5] 우리 홈랩의 Istio 구성 — 실제 환경 살펴보기"
date: 2026-02-24T10:00:00+09:00
summary: "이론을 봤으니 실제 환경을 보자. 4노드 베어메탈 클러스터에서 Gateway, VirtualService, DestinationRule, AuthorizationPolicy를 어떻게 구성했는지, YAML과 함께 살펴본다."
tags: ["istio", "service-mesh", "kubernetes", "homelab", "virtualservice", "gateway", "destinationrule"]
categories:
  - study
  - Service Mesh
series: ["Istio 실전 시리즈"]
showtoc: true
tocopen: true
draft: false
cover:
  image: "cover.jpg"
  alt: "[Istio 시리즈 #2.5] 우리 홈랩의 Istio 구성 — 실제 환경 살펴보기"
  relative: true
---

## 배경 — 왜 실제 환경을 먼저 보는가

[#2 Istio 아키텍처](/study/2026-01-22-istio-service-mesh-architecture/)에서 Istio의 구조를 이론으로 봤다. Control Plane(istiod)이 설정을 배포하고, Data Plane(Envoy)이 트래픽을 처리한다는 것까지.

그런데 이론만으로는 **"그래서 내 서비스에 어떻게 적용하는데?"** 라는 질문에 답할 수 없다. Gateway는 어떤 도메인을 받고, VirtualService는 어떤 경로를 어디로 보내고, DestinationRule은 왜 필요한지 — 실제 YAML을 보면서 이해하는 것이 가장 빠르다.

이 글에서는 홈랩 클러스터의 실제 Istio 구성을 YAML과 함께 살펴본다.

---

## 1. 클러스터 구성

```
┌─────────────────────────────────────────────────────────────┐
│  Windows 워크스테이션 (192.168.1.195)                        │
│  └─ VMware Workstation                                       │
│     ├─ k8s-worker1 (192.168.1.61)                           │
│     ├─ k8s-worker2 (192.168.1.62)                           │
│     ├─ k8s-worker3 (192.168.1.60)                           │
│     └─ k8s-worker4 (192.168.1.64)                           │
│                                                              │
│  k8s-cp (192.168.1.187) ← 베어메탈 서버, Control Plane       │
│  MetalLB (192.168.1.200) ← Istio Ingress Gateway IP         │
└─────────────────────────────────────────────────────────────┘
```

| 구성 요소 | 버전 |
|-----------|------|
| Kubernetes | v1.31.13 |
| Istio | 1.28.2 |
| CNI | Cilium |
| Load Balancer | MetalLB |
| GitOps | ArgoCD |

핵심은 **MetalLB가 192.168.1.200 IP를 Istio Ingress Gateway에 할당**한다는 것이다. Windows 워크스테이션의 `hosts` 파일에서 `*.jiminhome.shop → 192.168.1.200`으로 매핑해서, 브라우저에서 `blog.jiminhome.shop`을 입력하면 Gateway까지 도달한다.

---

## 2. 트래픽 흐름 전체 그림

```
브라우저 (blog.jiminhome.shop)
    │
    ▼
Windows hosts 파일 → 192.168.1.200
    │
    ▼
MetalLB LoadBalancer
    │
    ▼
┌──────────────────────────────────┐
│ Istio Ingress Gateway (envoy)    │  ← Gateway 리소스가 여기 적용
│ - *.jiminhome.shop:80 수신       │
│ - Host 헤더 기반 라우팅           │
└──────────────────────────────────┘
    │
    ├─ blog.jiminhome.shop → VirtualService (blog-routes)
    │       │
    │       ▼
    │   web-service (Nginx)  ← DestinationRule (Circuit Breaker)
    │       │
    │       ▼
    │   was-service (Spring Boot)  ← AuthorizationPolicy (namespace 제한)
    │       │
    │       ▼
    │   mysql-service  ← DestinationRule (TCP 연결 제한)
    │
    ├─ grafana.jiminhome.shop → VirtualService (grafana-routes)
    ├─ prom.jiminhome.shop    → VirtualService (prometheus-routes)
    ├─ kiali.jiminhome.shop   → VirtualService (kiali-routes)
    ├─ argocd.jiminhome.shop  → VirtualService (argocd-routes)
    └─ alert.jiminhome.shop   → VirtualService (alertmanager-routes)
```

하나의 Gateway가 모든 `*.jiminhome.shop` 트래픽을 받고, **Host 헤더**에 따라 각각 다른 VirtualService로 분기한다.

---

## 3. Gateway — 하나로 모든 도메인 처리

```yaml
apiVersion: networking.istio.io/v1beta1
kind: Gateway
metadata:
  name: blog-gateway
  namespace: blog-system
spec:
  selector:
    istio: ingressgateway  # istio-system의 istio-ingressgateway Pod 선택
  servers:
  - port:
      number: 80
      name: http
      protocol: HTTP
    hosts:
    - "*.jiminhome.shop"  # 와일드카드로 모든 서브도메인 수신
```

**왜 이렇게 했는가:**

- Gateway를 서비스마다 만들면 관리 포인트가 늘어난다. **하나의 Gateway + 여러 VirtualService** 구조가 단순하다.
- `*.jiminhome.shop` 와일드카드로 새 서비스 추가 시 **Gateway 수정 없이 VirtualService만 추가**하면 된다.
- HTTP 80만 사용한다. 내부 네트워크(192.168.1.0/24)이므로 HTTPS 없이도 안전하고, self-signed 인증서의 복잡도를 피했다.

```bash
kubectl get gateway -n blog-system
# 출력:
# NAME           AGE
# blog-gateway   31d
```

---

## 4. VirtualService — 경로별 라우팅

### 4.1 blog-routes (외부 → 블로그)

```yaml
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: blog-routes
  namespace: blog-system
spec:
  hosts:
  - "blog.jiminhome.shop"
  gateways:
  - blog-gateway

  http:
  # /api → Nginx가 받아서 WAS로 프록시
  - match:
    - uri:
        prefix: "/api"
    route:
    - destination:
        host: web-service
        subset: stable        # Argo Rollouts canary 배포 지원
        port:
          number: 80
    retries:
      attempts: 3             # 실패 시 3번 재시도
      perTryTimeout: 3s
      retryOn: 5xx,reset,connect-failure
    timeout: 15s

  # / → 정적 파일 (Hugo 블로그)
  - match:
    - uri:
        prefix: "/"
    route:
    - destination:
        host: web-service
        subset: stable
        port:
          number: 80
    retries:
      attempts: 3
      perTryTimeout: 3s
      retryOn: 5xx,reset,connect-failure
    timeout: 10s
```

**왜 이렇게 했는가:**

- 모든 트래픽이 먼저 `web-service` (Nginx)로 간다. Nginx가 `/api/*`를 WAS로 프록시한다.
- `subset: stable`은 [Argo Rollouts](/study/2025-12-29-canary-strategy-comparison-alb-traffic-routing/)와 연동하기 위해 설정했다. canary 배포 시 weight를 조절해서 트래픽을 점진적으로 이동한다.
- `retries`와 `timeout`은 WAS 콜드스타트(Spring Boot 초기 로딩)에 대비한 설정이다.

### 4.2 모니터링 도구 VirtualService (Grafana 예시)

```yaml
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: grafana-routes
  namespace: monitoring
spec:
  hosts:
  - "grafana.jiminhome.shop"
  gateways:
  - blog-system/blog-gateway    # 다른 namespace의 Gateway 참조
  http:
  - match:
    - uri:
        prefix: "/"
    route:
    - destination:
        host: kube-prometheus-stack-grafana.monitoring.svc.cluster.local
        port:
          number: 80
    timeout: 30s
```

**포인트:** Gateway가 `blog-system` namespace에 있지만, `blog-system/blog-gateway`처럼 **namespace/name** 형식으로 다른 namespace에서 참조할 수 있다.

현재 이 패턴으로 7개 도메인을 라우팅하고 있다:

```bash
kubectl get virtualservice -A --no-headers | awk '{print $3, $5}'
# 출력:
# ["blog-system/blog-gateway"]   ["blog.jiminhome.shop"]
# ["blog-system/blog-gateway"]   ["grafana.jiminhome.shop"]
# ["blog-system/blog-gateway"]   ["prom.jiminhome.shop"]
# ["blog-system/blog-gateway"]   ["kiali.jiminhome.shop"]
# ["blog-system/blog-gateway"]   ["argocd.jiminhome.shop"]
# ["blog-system/blog-gateway"]   ["alert.jiminhome.shop"]
# ["blog-system/blog-gateway"]   ["hubble.jiminhome.shop"]
```

---

## 5. DestinationRule — 연결 방식과 장애 대응

DestinationRule은 "목적지에 **어떻게** 연결할지"를 정한다. 서비스별로 다르게 설정했다.

### 5.1 web (Nginx) — Connection Pool + Circuit Breaker

```yaml
apiVersion: networking.istio.io/v1beta1
kind: DestinationRule
metadata:
  name: web-dest-rule
  namespace: blog-system
spec:
  host: web-service
  trafficPolicy:
    tls:
      mode: DISABLE          # Gateway → web은 평문 HTTP
    connectionPool:
      http:
        http1MaxPendingRequests: 100
        http2MaxRequests: 100
        maxRequestsPerConnection: 10
      tcp:
        maxConnections: 100
    loadBalancer:
      simple: ROUND_ROBIN
    outlierDetection:
      consecutive5xxErrors: 5   # 5xx 5번 연속 시
      interval: 10s
      baseEjectionTime: 30s     # 30초간 트래픽에서 제외
      maxEjectionPercent: 50
      minHealthPercent: 30      # 최소 30%는 활성 유지
  subsets:
  - name: stable
  - name: canary
```

### 5.2 MySQL — TCP 연결 제한

```yaml
apiVersion: networking.istio.io/v1beta1
kind: DestinationRule
metadata:
  name: mysql-circuit-breaker
  namespace: blog-system
spec:
  host: mysql-service
  trafficPolicy:
    connectionPool:
      tcp:
        maxConnections: 20       # MySQL은 TCP만 (HTTP 섹션 제거)
    outlierDetection:
      consecutive5xxErrors: 3    # MySQL은 더 민감하게: 3회면 차단
      interval: 30s
      baseEjectionTime: 30s
      maxEjectionPercent: 50
      minHealthPercent: 30
```

**왜 서비스별로 다르게 했는가:**

| 서비스 | maxConnections | 5xx 차단 기준 | 이유 |
|--------|:-:|:-:|------|
| web (Nginx) | 100 | 5회 | Nginx는 동시 연결이 많고, 일시적 5xx가 올 수 있다 |
| MySQL | 20 | 3회 | DB 연결은 비싸고, 5xx는 심각한 문제 신호다 |

---

## 6. 보안 — AuthorizationPolicy + PeerAuthentication

### 6.1 Gateway 레벨 접근 제어

가장 중요한 보안 설정이다. **Gateway에서 Host별로 접근을 제어**한다:

```yaml
apiVersion: security.istio.io/v1beta1
kind: AuthorizationPolicy
metadata:
  name: monitoring-whitelist
  namespace: istio-system       # Gateway가 있는 namespace에 적용
spec:
  selector:
    matchLabels:
      app: istio-ingressgateway
  action: ALLOW
  rules:
  # blog 정적 페이지: 전세계 공개
  - to:
    - operation:
        hosts: ["blog.jiminhome.shop"]
        notPaths: ["/api/*", "/board/*", "/auth", "/auth/*"]

  # blog API/Board/Auth: 내부 네트워크만
  - to:
    - operation:
        hosts: ["blog.jiminhome.shop"]
        paths: ["/api/*", "/board/*", "/auth", "/auth/*"]
    from:
    - source:
        remoteIpBlocks: ["192.168.1.0/24"]

  # 모니터링 도구: 내부 네트워크만
  - to:
    - operation:
        hosts:
        - "grafana.jiminhome.shop"
        - "prom.jiminhome.shop"
        - "kiali.jiminhome.shop"
        - "argocd.jiminhome.shop"
        - "alert.jiminhome.shop"
        - "wazuh.jiminhome.shop"
    from:
    - source:
        remoteIpBlocks: ["192.168.1.0/24"]
```

**설계 의도:**

- **블로그 글 자체**는 공개. 누구나 읽을 수 있다.
- **블로그 API** (`/api/*`, 게시글 CRUD)는 내부 IP만. 외부에서 게시글을 생성/수정할 수 없다.
- **모니터링 도구** (Grafana, Prometheus 등)는 내부 IP만. 클러스터 상태가 외부에 노출되지 않는다.

### 6.2 mTLS 설정

```yaml
# PeerAuthentication: PERMISSIVE
apiVersion: security.istio.io/v1beta1
kind: PeerAuthentication
metadata:
  name: default
  namespace: blog-system
spec:
  mtls:
    mode: PERMISSIVE  # mTLS와 plain text 둘 다 허용
```

**왜 STRICT가 아닌 PERMISSIVE인가:**

- Istio Gateway에서 Pod로 들어오는 첫 번째 hop은 **평문 HTTP**다. STRICT로 하면 Gateway → web 트래픽이 거부된다.
- 대신 **DestinationRule에서 `ISTIO_MUTUAL`을 명시**하면, mesh 내부 통신(web → was)은 mTLS가 강제된다.
- 처음에 STRICT로 설정했다가 502 에러가 발생해서 PERMISSIVE로 변경한 이력이 있다.

---

## 7. 전체 구성 정리

```bash
# Istio 리소스 전체 현황
kubectl get gateway,virtualservice,destinationrule,peerauthentication,authorizationpolicy -A 2>/dev/null | grep -v "^$"
```

| 리소스 | 개수 | 역할 |
|--------|:---:|------|
| Gateway | 1 | `*.jiminhome.shop:80` 수신 |
| VirtualService | 13 | 도메인별 라우팅 (blog, grafana, prom 등) |
| DestinationRule | 3 | Circuit Breaker, Connection Pool |
| PeerAuthentication | 2 | mTLS PERMISSIVE |
| AuthorizationPolicy | 3 | IP 기반 접근 제어 |

**구성 원칙:**

1. **Gateway는 하나** — 모든 도메인을 와일드카드로 수신
2. **서비스 추가 시 VirtualService만 추가** — Gateway 수정 불필요
3. **보안은 AuthorizationPolicy로** — IP + Host + Path 조합으로 세밀하게 제어
4. **Circuit Breaker는 서비스 성격에 맞게** — Nginx는 여유롭게, MySQL은 민감하게

---

## 다음 단계

다음 실습편에서는 개별 리소스를 더 깊이 다룬다:

- **[#4.5]** blog-system의 VirtualService/DestinationRule 상세 — Canary 배포와 retry/timeout 설정
- **[#6.5]** mTLS와 AuthorizationPolicy 실전 — STRICT에서 502가 발생한 이유와 해결 과정

---

*이 글은 [Istio 실전 시리즈](/series/istio-실전-시리즈/)의 일부입니다.*
