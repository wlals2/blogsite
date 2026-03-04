---
title: "[Istio 시리즈 #4] VirtualService & DestinationRule — Istio L7 라우팅의 두 축"
date: 2026-01-17T10:00:00+09:00
summary: "VirtualService는 '어디로 보낼지'를 결정하고, DestinationRule은 '어떻게 연결할지'를 결정한다. 이 둘이 왜 분리되어 있는지, 실제로 어떤 역할을 하는지를 살펴본다."
tags: ["istio", "virtualservice", "destinationrule", "service-mesh", "kubernetes", "traffic-management", "mtls", "gateway"]
categories: ["Service Mesh"]
series: ["Istio 실전 시리즈"]
showtoc: true
tocopen: true
---

## Kubernetes Service만으로 부족한 이유

Kubernetes Service는 트래픽을 Pod들에게 로드밸런싱해준다. 이것만으로 충분하지 않은가?

| 원하는 것 | Kubernetes Service | Istio |
|----------|-------------------|-------|
| 특정 헤더 있으면 canary Pod으로 | ❌ | ✅ VirtualService |
| 요청 실패 시 자동 재시도 | ❌ | ✅ VirtualService |
| 트래픽 10%만 canary로 (Pod 비율 무관) | ❌ (Pod 비율로만) | ✅ VirtualService 가중치 |
| 연결 시 mTLS 강제 | ❌ | ✅ DestinationRule |
| Pod 그룹을 레이블로 구분 | ❌ | ✅ DestinationRule subset |

Istio는 이 기능들을 두 CRD로 분리했다.

```
VirtualService   → "어디로, 어떤 조건으로" (라우팅)
DestinationRule  → "목적지에 어떻게 연결할지" (연결 정책)
```

---

## VirtualService

VirtualService는 Kubernetes Service 앞에 놓이는 **L7 라우팅 레이어**다.

### 기본 구조

```yaml
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: was-vs
  namespace: blog-system
spec:
  hosts:
    - was-service          # 이 이름으로 오는 트래픽 제어
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
      attempts: 3           # 최대 3회
      perTryTimeout: 2s     # 시도당 2초 제한
      retryOn: "5xx,reset"  # 5xx 오류 또는 연결 리셋 시
```

코드에 `@Retryable` 없이 프록시 레벨에서 재시도한다.

### Timeout

```yaml
http:
  - route:
      - destination:
          host: was-service
    timeout: 10s    # 10초 초과 → 오류 반환
```

### 가중치 기반 Canary 라우팅

Pod 수와 무관하게 정확한 비율로 분배한다.

```yaml
http:
  - route:
      - destination:
          host: was-service
          subset: stable
        weight: 90        # 90% → stable
      - destination:
          host: was-service
          subset: canary
        weight: 10        # 10% → canary
```

### 헤더 기반 라우팅

```yaml
http:
  - match:
      - headers:
          x-canary:
            exact: "true"   # x-canary: true 헤더가 있으면
    route:
      - destination:
          host: was-service
          subset: canary    # canary 버전으로
  - route:
      - destination:
          host: was-service
          subset: stable    # 나머지는 stable
```

---

## DestinationRule

DestinationRule은 VirtualService가 트래픽을 보낸 **목적지에서의 동작**을 정의한다.

### 기본 구조

```yaml
apiVersion: networking.istio.io/v1beta1
kind: DestinationRule
metadata:
  name: was-dr
  namespace: blog-system
spec:
  host: was-service
  trafficPolicy:
    tls:
      mode: ISTIO_MUTUAL    # Envoy 인증서로 mTLS
  subsets:
    - name: stable
      labels:
        version: stable     # version=stable 레이블 Pod들
    - name: canary
      labels:
        version: canary
```

### trafficPolicy: 연결 방식 제어

**mTLS 모드 비교**:

| 모드 | 설명 | 언제 사용 |
|------|------|----------|
| `ISTIO_MUTUAL` | Envoy 자동 발급 인증서로 mTLS | 권장, Sidecar 간 통신 |
| `SIMPLE` | 단방향 TLS (클라이언트 인증 없음) | 외부 서비스 연결 |
| `DISABLE` | 평문 HTTP | Sidecar 없는 서비스 |

**로드밸런싱**:

```yaml
trafficPolicy:
  loadBalancer:
    simple: ROUND_ROBIN    # 기본값
    # LEAST_CONN          → 연결 수 적은 Pod 우선
    # RANDOM              → 랜덤
```

**Circuit Breaking**:

```yaml
trafficPolicy:
  outlierDetection:
    consecutiveErrors: 5      # 5회 연속 5xx
    interval: 30s             # 30초 윈도우
    baseEjectionTime: 30s     # 해당 Pod 30초 격리
```

### subset: Pod 그룹 정의

`subset: canary`는 Kubernetes에는 없는 개념이다. DestinationRule이 레이블로 Pod를 구분한다.

```yaml
subsets:
  - name: stable
    labels:
      version: stable    # kubectl get pods -l version=stable 에 해당
  - name: canary
    labels:
      version: canary
```

---

## VirtualService와 DestinationRule의 관계

왜 하나로 합치지 않았나?

```
VirtualService  = 요청의 특성으로 "어디로?"
                  (헤더, 경로, 메서드, 가중치)

DestinationRule = 목적지 서비스의 특성으로 "어떻게?"
                  (mTLS 모드, 로드밸런싱, Circuit Breaking)
```

실제 흐름:

```
요청 도착
    ↓
VirtualService: "x-canary 헤더 없음 → stable subset으로, 10초 타임아웃"
    ↓
DestinationRule: "stable subset = version=stable Pod들, ISTIO_MUTUAL mTLS"
    ↓
Kubernetes: version=stable Pod들로 로드밸런싱
```

---

## Istio Gateway: 외부 트래픽 진입점

Kubernetes Ingress와 비슷하지만 Envoy 기반이라 더 세밀하게 제어한다.

```yaml
apiVersion: networking.istio.io/v1beta1
kind: Gateway
metadata:
  name: blog-gateway
  namespace: blog-system
spec:
  selector:
    istio: ingressgateway     # istio-ingressgateway Pod에 적용
  servers:
    - port:
        number: 80
        name: http
        protocol: HTTP
      hosts:
        - "blog.jiminhome.shop"   # 이 도메인 수신
```

Gateway는 **포트/도메인을 열어주는 역할**만 한다. 실제 라우팅은 VirtualService가 담당한다.

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
    - blog-system/blog-gateway    # 이 Gateway를 통해 들어온 트래픽
  http:
    - route:
        - destination:
            host: web-service
            port:
              number: 80
```

---

## 세 CRD 역할 정리

```
외부 요청: blog.jiminhome.shop
    ↓
[Gateway]        어떤 도메인·포트를 수신할지
    ↓
[VirtualService] 어느 subset으로, Retry/Timeout
    ↓
[DestinationRule] mTLS, 로드밸런싱, Circuit Breaking, subset 정의
    ↓
Pod
```

| CRD | 핵심 질문 | 예시 |
|-----|----------|------|
| Gateway | 어떤 트래픽을 받나? | port 80, blog.jiminhome.shop |
| VirtualService | 어디로 보내나? | 90% stable, 10% canary |
| DestinationRule | 어떻게 연결하나? | ISTIO_MUTUAL + LEAST_CONN |

---

## 홈랩에서 확인

```bash
# 현재 적용된 VirtualService 목록
$ kubectl get virtualservice -n blog-system
# 출력:
# NAME       GATEWAYS                    HOSTS                        AGE
# blog-vs    ["blog-system/blog-gw"]     ["blog.jiminhome.shop"]      25d
# was-vs     <none>                      ["was-service"]              25d

# DestinationRule 목록
$ kubectl get destinationrule -n blog-system
# 출력:
# NAME      HOST          AGE
# was-dr    was-service   25d
```

Kiali 대시보드에서 각 서비스 간 연결에 VirtualService/DestinationRule이 적용됐는지 시각적으로 확인할 수 있다.

---

## 다음 글

개념을 이해했다면, 실제 홈랩에서 이 설정들이 만들어진 과정 — Kiali에서 PassthroughCluster(mesh 우회) 문제를 발견하고 DestinationRule로 해결한 트러블슈팅을 살펴본다.

- 이전: [Envoy — Istio의 심장, Sidecar 프록시 동작 원리](/study/2026-01-16-envoy-sidecar-proxy/)
- 다음: [PassthroughCluster 문제 해결 — nginx proxy와 Istio mesh 통합](/study/2026-01-20-nginx-proxy-istio-mesh-passthrough/)
