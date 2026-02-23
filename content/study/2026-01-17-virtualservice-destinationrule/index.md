---
title: "VirtualService & DestinationRule — Istio L7 라우팅의 두 축"
date: 2026-01-17T10:00:00+09:00
summary: "VirtualService는 '어디로 보낼지'를 결정하고, DestinationRule은 '어떻게 연결할지'를 결정한다. 이 둘이 왜 분리되어 있는지, 실제로 어떤 역할을 하는지를 살펴본다."
tags: ["istio", "virtualservice", "destinationrule", "service-mesh", "kubernetes", "traffic-management", "mtls"]
categories: ["study", "Service Mesh"]
series: ["Istio 실전 시리즈"]
showtoc: true
tocopen: true
---

## 왜 두 개인가

Kubernetes에는 Service가 있다. 트래픽을 특정 Pod들로 로드밸런싱해준다. 이것만으로 충분하지 않은가?

Kubernetes Service의 한계:

| 원하는 것 | Kubernetes Service | Istio |
|----------|-------------------|-------|
| 특정 헤더가 있으면 다른 버전으로 | ❌ 불가 | ✅ VirtualService |
| 요청 실패 시 자동 재시도 | ❌ 불가 | ✅ VirtualService |
| 트래픽 10%만 canary로 | ❌ Pod 비율로만 | ✅ VirtualService 가중치 |
| 연결 시 mTLS 강제 | ❌ 불가 | ✅ DestinationRule |
| 동시 연결 수 제한 | ❌ 불가 | ✅ DestinationRule |

Istio는 이 기능들을 두 CRD로 분리했다.

- **VirtualService**: 트래픽을 어디로, 어떤 조건으로 보낼지 (라우팅)
- **DestinationRule**: 목적지에 어떻게 연결할지 (연결 정책)

---

## VirtualService

VirtualService는 Kubernetes Service 앞에 놓이는 **L7 라우팅 레이어**다.

### 기본 구조

```yaml
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: was-service
  namespace: blog-system
spec:
  hosts:
    - was-service          # 이 서비스로 오는 트래픽을 제어
  http:
    - route:
        - destination:
            host: was-service
            port:
              number: 8080
```

`hosts`에 지정된 이름으로 오는 HTTP 요청을 가로채서, `route`에 정의된 곳으로 보낸다.

### Retry: 실패 시 자동 재시도

```yaml
http:
  - route:
      - destination:
          host: was-service
    retries:
      attempts: 3               # 최대 3회 재시도
      perTryTimeout: 2s         # 시도당 2초 타임아웃
      retryOn: "5xx,reset"      # 5xx 오류 or 연결 리셋 시
```

애플리케이션 코드에 `@Retryable` 없이도 프록시 레벨에서 재시도된다.

### Timeout

```yaml
http:
  - route:
      - destination:
          host: was-service
    timeout: 10s    # 10초 이상 응답 없으면 오류 반환
```

### 헤더 기반 라우팅 (Canary 배포)

특정 헤더가 있는 요청만 canary 버전으로 보낸다.

```yaml
http:
  - match:
      - headers:
          x-canary:
            exact: "true"    # x-canary: true 헤더가 있으면
    route:
      - destination:
          host: was-service
          subset: canary     # canary 버전으로
  - route:
      - destination:
          host: was-service
          subset: stable     # 나머지는 stable로
```

### 가중치 기반 라우팅

Pod 수와 무관하게 정확한 비율로 트래픽을 분배한다.

```yaml
http:
  - route:
      - destination:
          host: was-service
          subset: stable
        weight: 90        # 90%는 stable
      - destination:
          host: was-service
          subset: canary
        weight: 10        # 10%는 canary
```

`subset`은 DestinationRule에서 정의한다. 이것이 두 CRD가 연결되는 지점이다.

---

## DestinationRule

DestinationRule은 VirtualService가 트래픽을 보낸 **목적지에서의 동작**을 정의한다.

### 기본 구조

```yaml
apiVersion: networking.istio.io/v1beta1
kind: DestinationRule
metadata:
  name: was-service
  namespace: blog-system
spec:
  host: was-service
  trafficPolicy:
    tls:
      mode: ISTIO_MUTUAL    # mTLS 강제
  subsets:
    - name: stable
      labels:
        version: stable     # version: stable 레이블을 가진 Pod들
    - name: canary
      labels:
        version: canary
```

### trafficPolicy: 연결 방식 제어

**mTLS 설정**:

```yaml
trafficPolicy:
  tls:
    mode: ISTIO_MUTUAL    # Envoy 인증서로 mTLS (권장)
    # mode: SIMPLE       → 단방향 TLS (클라이언트 인증 없음)
    # mode: DISABLE      → 평문 HTTP
```

**로드밸런싱**:

```yaml
trafficPolicy:
  loadBalancer:
    simple: ROUND_ROBIN    # 기본값
    # simple: LEAST_CONN  → 연결 수가 적은 Pod 우선
    # simple: RANDOM      → 랜덤
```

**Circuit Breaking (연결 수 제한)**:

```yaml
trafficPolicy:
  connectionPool:
    http:
      http1MaxPendingRequests: 100    # 대기 중 요청 최대 100개
      http2MaxRequests: 1000          # 동시 요청 최대 1000개
  outlierDetection:
    consecutiveErrors: 5              # 5회 연속 5xx
    interval: 30s                     # 30초 윈도우 내
    baseEjectionTime: 30s             # 해당 Pod를 30초 격리
```

### subset: Pod 그룹 정의

VirtualService가 `subset: canary`로 보내도, Kubernetes는 subset 개념이 없다. DestinationRule의 subset이 레이블로 Pod를 구분한다.

```yaml
subsets:
  - name: stable
    labels:
      version: stable    # version=stable 레이블 Pod들만
  - name: canary
    labels:
      version: canary    # version=canary 레이블 Pod들만
```

이렇게 되면:

```
VirtualService: 10% → canary subset
DestinationRule: canary subset = version=canary 레이블 Pod들
Kubernetes: version=canary Pod들로 로드밸런싱
```

---

## VirtualService와 DestinationRule의 관계

왜 하나로 합치지 않고 두 개인가?

**관심사가 다르다.**

```
VirtualService = "어디로?" (라우팅 규칙)
  → 요청의 특성 (헤더, 경로, 메서드) 기반
  → 여러 VirtualService가 같은 서비스를 대상으로 가능

DestinationRule = "어떻게?" (연결 정책)
  → 목적지 서비스의 특성
  → 서비스당 하나
```

실제 예시:

- **blog-system**에서 `was-service`를 호출할 때: mTLS + Retry + 90/10 분배
- **monitoring** 네임스페이스에서 `was-service`를 health check할 때: mTLS + 타임아웃만

DestinationRule은 `was-service`에 대한 mTLS 정책 하나만 정의한다. VirtualService가 namespace별로 다른 라우팅 규칙을 적용한다.

---

## Istio Gateway

Istio Gateway는 클러스터 **외부 트래픽의 진입점**이다. Kubernetes Ingress와 비슷하지만 Envoy 기반이라 더 세밀한 제어가 가능하다.

```yaml
apiVersion: networking.istio.io/v1beta1
kind: Gateway
metadata:
  name: blog-gateway
  namespace: blog-system
spec:
  selector:
    istio: ingressgateway    # istio-ingressgateway Pod에 적용
  servers:
    - port:
        number: 80
        name: http
        protocol: HTTP
      hosts:
        - "blog.jiminhome.shop"    # 이 도메인으로 오는 요청 수신
```

Gateway는 **포트와 도메인을 열어주는 역할**만 한다. 실제 라우팅은 VirtualService가 담당한다.

```yaml
# Gateway와 연결되는 VirtualService
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: blog-vs
spec:
  hosts:
    - "blog.jiminhome.shop"
  gateways:
    - blog-system/blog-gateway    # 이 Gateway를 통해 들어온 트래픽에 적용
  http:
    - route:
        - destination:
            host: web-service
            port:
              number: 80
```

```
외부 요청: blog.jiminhome.shop
  ↓
Cloudflare → MetalLB(192.168.1.200) → istio-ingressgateway
  ↓ (Gateway: "blog.jiminhome.shop 포트 80을 열어놓음")
VirtualService: "blog.jiminhome.shop → web-service:80"
  ↓
web-service Pod
```

홈랩에서 Nginx Ingress를 제거하고 Istio Gateway로 일원화한 이유가 여기 있다. Nginx Ingress + Istio Gateway의 이중 L7 레이어를 단일 Envoy로 합쳤다.

---

## 정리: 세 CRD의 역할 분리

```
외부 트래픽 → [Gateway] → [VirtualService] → [DestinationRule] → Pod

Gateway:          도메인/포트 수신
VirtualService:   라우팅 결정 (어느 subset으로, 재시도, 타임아웃)
DestinationRule:  연결 정책 (mTLS, 로드밸런싱, Circuit Breaking, subset 정의)
```

| CRD | 질문 | 예시 |
|-----|------|------|
| Gateway | 어떤 트래픽을 받을까? | port 80, host: blog.jiminhome.shop |
| VirtualService | 어디로 보낼까? | canary 10%, stable 90% |
| DestinationRule | 어떻게 연결할까? | mTLS + ROUND_ROBIN |

---

## 다음 글

이 개념들이 실제 홈랩에 어떻게 적용되는지, 전체 아키텍처를 살펴본다.

- 이전: [Envoy — Istio의 심장, Sidecar 프록시 동작 원리](/study/2026-01-16-envoy-sidecar-proxy/)
- 다음: [Istio Service Mesh 아키텍처 완전 가이드](/study/2026-01-22-istio-service-mesh-architecture/)
