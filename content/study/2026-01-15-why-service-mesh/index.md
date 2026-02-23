---
title: "Service Mesh는 왜 탄생했나 — 마이크로서비스의 숨겨진 복잡성"
date: 2026-01-15T10:00:00+09:00
summary: "모놀리스에서 마이크로서비스로 넘어가면 비즈니스 로직 외에 해결해야 할 문제들이 쏟아진다. 재시도, 타임아웃, 인증, 관측성. Service Mesh는 이 문제들을 애플리케이션 밖에서 해결한다."
tags: ["service-mesh", "microservices", "istio", "kubernetes", "architecture"]
categories: ["study", "Service Mesh"]
series: ["Istio 실전 시리즈"]
showtoc: true
tocopen: true
---

## 모놀리스에서는 없던 문제

모놀리스 애플리케이션에서 서비스 A가 서비스 B를 호출하는 코드는 단순하다.

```java
// 모놀리스: 같은 JVM 안에서 메서드 호출
OrderService orderService = new OrderService();
orderService.createOrder(cart);
```

실패할 이유가 거의 없다. 네트워크를 타지 않기 때문이다.

마이크로서비스로 전환하면 같은 로직이 HTTP 호출로 바뀐다.

```java
// 마이크로서비스: 네트워크 너머로 HTTP 호출
RestTemplate restTemplate = new RestTemplate();
restTemplate.post("http://order-service/orders", cart);
```

이 순간부터 다음 질문들이 생긴다.

- order-service가 응답이 느리면? → **타임아웃** 필요
- 일시적 네트워크 오류면? → **재시도** 필요
- order-service가 다운됐으면? → **Circuit Breaking** 필요
- 요청이 실제로 갔는지 어떻게 확인? → **분산 추적** 필요
- 이 호출이 인증된 서비스에서 온 건지? → **서비스 간 인증** 필요

서비스 수가 3개면 직접 구현할 수 있다. 30개가 되면?

---

## 각 팀이 직접 해결했을 때의 문제

마이크로서비스 초기에는 이 문제들을 각 서비스 코드 안에서 해결했다.

```java
// 각 서비스 코드에 흩어진 네트워크 처리 로직
@Retryable(maxAttempts = 3)
@CircuitBreaker(name = "order-service")
@HystrixCommand(fallbackMethod = "fallback")
public Order createOrder(Cart cart) {
    return restTemplate.post("http://order-service/orders", cart);
}
```

문제는 이 코드가 **모든 서비스에 반복**된다는 것이다.

| 문제 | 결과 |
|------|------|
| 재시도 정책 변경 | 30개 서비스 코드 전부 수정 |
| TLS 인증서 교체 | 모든 서비스 재배포 |
| 타임아웃 기준 조정 | 팀마다 다른 값 사용 |
| 특정 서비스만 추적 활성화 | 불가능 (전부 아니면 전무) |

Netflix가 Hystrix, Ribbon 같은 라이브러리를 Java 전용으로 만들었는데, 마이크로서비스가 Java만 쓰지 않으면 의미가 없다. Python 서비스, Go 서비스에는 다른 라이브러리가 필요했다.

---

## 해결책: 네트워크 처리를 애플리케이션 밖으로

Service Mesh의 아이디어는 단순하다.

**"네트워크 처리 로직을 애플리케이션 코드에서 꺼내서, 별도 프록시에게 맡긴다."**

각 Pod 옆에 **Sidecar 프록시**를 붙인다. 모든 네트워크 트래픽은 이 프록시를 통과한다.

```
Before:
  Service A ──────── HTTP ────────> Service B

After:
  Service A → [Envoy] ──── HTTP ────> [Envoy] → Service B
               ↑                         ↑
           Sidecar 프록시          Sidecar 프록시
```

이제 재시도, 타임아웃, 인증, 추적은 **프록시가 처리**한다. 애플리케이션 코드는 비즈니스 로직에만 집중한다.

---

## Service Mesh가 해결하는 것들

### 신뢰성

서비스 간 호출에서 발생할 수 있는 장애를 프록시 레벨에서 처리한다.

| 기능 | 동작 |
|------|------|
| **Retry** | 5xx 오류 → 자동 재시도 (N회) |
| **Timeout** | N초 이상 응답 없으면 오류 반환 |
| **Circuit Breaking** | 오류율 임계값 초과 → 해당 서비스 격리 |

애플리케이션 코드 변경 없이 YAML 설정만으로 적용된다.

### 보안

```
Before: HTTP (평문)
  Pod A ──────── 평문 HTTP ────────> Pod B

After: mTLS (암호화 + 인증)
  Pod A → [Envoy] ── TLS 터널 ──> [Envoy] → Pod B
```

Sidecar 프록시 간에 자동으로 mTLS(상호 TLS 인증)가 설정된다. 각 서비스가 인증서를 관리할 필요 없이, Control Plane이 인증서를 자동 발급하고 교체한다.

### 관측성

모든 트래픽이 프록시를 통과하기 때문에, 프록시 레벨에서 자동으로 데이터가 수집된다.

```bash
# Istio가 자동으로 수집하는 메트릭 예시 (Prometheus)
$ kubectl exec -n blog-system web-... -c istio-proxy -- curl localhost:15090/stats/prometheus | grep requests_total
# 출력:
# istio_requests_total{app="web",destination_service="was-service",...} 1247
```

코드에 `@Tracing` 어노테이션을 달지 않아도 Jaeger/Zipkin 트레이스가 자동 생성된다.

---

## Istio의 구조

Service Mesh를 실제로 구현한 것이 Istio다. 두 부분으로 나뉜다.

```
┌─────────────────────────────────────────┐
│              Control Plane              │
│                 istiod                  │
│  ┌──────────┐ ┌───────────┐ ┌────────┐ │
│  │  Pilot   │ │  Citadel  │ │Galley  │ │
│  │(라우팅 설정)│ │(인증서 발급)│ │(설정검증)│ │
│  └──────────┘ └───────────┘ └────────┘ │
└─────────────────────────────────────────┘
            ↕ 설정 배포 (xDS API)
┌─────────────────────────────────────────┐
│               Data Plane                │
│  ┌──────────────────────────────────┐   │
│  │ Pod A                            │   │
│  │  ┌────────────┐ ┌─────────────┐  │   │
│  │  │ App 컨테이너 │ │ Envoy Sidecar│  │   │
│  │  └────────────┘ └─────────────┘  │   │
│  └──────────────────────────────────┘   │
│  ┌──────────────────────────────────┐   │
│  │ Pod B                            │   │
│  │  ┌────────────┐ ┌─────────────┐  │   │
│  │  │ App 컨테이너 │ │ Envoy Sidecar│  │   │
│  │  └────────────┘ └─────────────┘  │   │
│  └──────────────────────────────────┘   │
└─────────────────────────────────────────┘
```

**Control Plane (istiod)**:
- 라우팅 규칙, 인증서, 정책을 관리
- Data Plane의 프록시들에게 설정을 배포

**Data Plane (Envoy Sidecar)**:
- 실제 트래픽을 처리하는 프록시
- Pod마다 하나씩 붙어서 모든 인바운드/아웃바운드 트래픽을 가로챔

---

## 모든 서비스에 Mesh가 필요한 건 아니다

Service Mesh는 **복잡성을 추가**한다. 모든 Pod에 Sidecar가 붙으면 메모리와 CPU를 소비한다.

홈랩에서 측정한 Envoy Sidecar 오버헤드:

```bash
$ kubectl top pods -n blog-system
# 출력:
# NAME                        CPU(cores)   MEMORY(bytes)
# web-xxx (app만)             5m           45Mi
# web-xxx (app + sidecar)     8m           92Mi  ← sidecar가 3m CPU, 47Mi 메모리 추가
```

단일 서비스나 소규모 시스템에서는 Nginx 설정만으로 충분하다. Service Mesh는 다음 상황에서 가치가 있다:

- 서비스 수가 5개 이상이고 서로 복잡하게 통신할 때
- 팀이 나뉘어 있어 중앙집중적 트래픽 제어가 필요할 때
- 서비스 간 인증/암호화가 규정으로 요구될 때
- 분산 추적으로 병목을 찾아야 할 때

---

## 다음 글

다음 편에서는 Data Plane의 핵심인 **Envoy**가 실제로 어떻게 동작하는지 살펴본다. Sidecar 패턴이 어떻게 모든 트래픽을 가로채는지, xDS API로 설정을 받아 적용하는 구조를 다룬다.

- 다음: [Envoy — Istio의 심장, Sidecar 프록시 동작 원리](/study/2026-01-16-envoy-sidecar-proxy/)
