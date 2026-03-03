---
title: "[Istio 실전 시리즈 #0] 왜 Linkerd도 Consul도 아닌 Istio인가 — Service Mesh 선택의 기준"
date: 2026-03-03T08:30:00+09:00
description: "Linkerd, Consul Connect, Istio 세 Service Mesh를 직접 비교하고, 홈랩에서 Istio를 선택한 구체적인 이유를 기술한다"
tags: ["istio", "linkerd", "consul", "service-mesh", "kubernetes", "mtls", "envoy"]
categories: ["study", "Service Mesh"]
series: ["Istio 실전 시리즈"]
draft: false
---

## 왜 이 글이 필요한가

Istio 시리즈의 다른 글들은 "이미 Istio를 쓰고 있다"는 전제에서 시작한다.

그런데 처음 Service Mesh를 도입하려 할 때 이런 상황이 된다:

- CNCF 문서에 Istio, Linkerd, Consul, Kuma, Cilium Mesh 등이 나열되어 있다
- 각 도구의 공식 문서는 자기 도구가 최고라고 한다
- 뭘 기준으로 고르는지 모른다

이 글은 **"왜 Istio인가"**를 직접 비교를 통해 설명한다.

---

## 1. Service Mesh가 필요한 이유부터

### 마이크로서비스의 공통 문제

서비스가 여러 개로 쪼개지면, 각 서비스 코드에 아래를 반복 구현해야 한다:

```
web → was 호출 시:
  - TLS 암호화
  - 재시도 (3번 실패하면?)
  - 타임아웃 설정
  - Circuit Breaker (was가 죽으면 기다리지 않고 빠르게 실패)
  - 메트릭 수집 (이 호출이 몇 ms 걸렸나?)

was → mysql 호출 시:
  - 동일한 것들을 반복...
```

5개 서비스에서 이것을 반복하면, **비즈니스 로직과 네트워크 로직이 뒤섞인다.**

### Service Mesh의 해결: "코드 밖으로 꺼내기"

```
기존 (Application이 모두 처리):
  [web 코드]
  TLS 연결 → 재시도 로직 → Circuit Breaker → 호출 → 메트릭 기록

Service Mesh (Sidecar가 처리):
  [web 코드]
  그냥 was에 HTTP 요청
    │
    ▼
  [Envoy Sidecar] ← 모든 네트워크 기능이 여기
  TLS + 재시도 + Circuit Breaker + 메트릭
```

Application 코드는 "그냥 HTTP 요청"만 하면 된다. 나머지는 Sidecar가 투명하게 처리한다.

---

## 2. 세 가지 대표 Service Mesh 비교

### Linkerd — "가볍고 단순하게"

```
Linkerd 아키텍처:
  Pod
  ├── 애플리케이션
  └── linkerd-proxy (Rust 기반, 경량)
       → mTLS, 메트릭, 재시도만 집중
```

**장점:**
- 설치가 5분 안에 끝난다
- 리소스 사용량이 Istio의 1/3 수준
- 운영이 단순하다 (CRD가 적다)
- Rust 기반 proxy로 메모리 안전성

**단점:**
- L7 라우팅 기능이 제한적 (VirtualService 같은 세밀한 제어 없음)
- Traffic shifting이 HTTPRoute만 가능 (Istio의 VirtualService보다 표현력 낮음)
- Canary 배포 설정이 Istio보다 복잡
- AuthorizationPolicy가 Istio보다 단순

**언제 선택하는가:** 운영 단순성 최우선, 리소스 제약 있는 환경, mTLS와 기본 관측성만 필요

---

### Consul Connect — "HashiCorp 생태계와 통합"

```
Consul Connect 아키텍처:
  Consul Agent (DaemonSet) → Envoy Proxy (Sidecar)
  → HashiCorp Vault, Terraform과 통합
```

**장점:**
- Kubernetes 외 VM, 베어메탈 환경도 Mesh에 포함 가능
- HashiCorp Vault와 네이티브 통합 (Secret 관리)
- Multi-DC, Multi-Cloud Mesh 지원
- Service Registry + Service Mesh 동시에

**단점:**
- Kubernetes-only 환경에서는 오버스펙
- 아키텍처가 복잡하다 (Consul Server + Agent + Envoy)
- 리소스 사용량 높음
- 학습 비용이 Istio보다 높음

**언제 선택하는가:** Kubernetes + VM 혼합 환경, HashiCorp 스택 이미 사용 중, Multi-Cloud

---

### Istio — "기능이 가장 풍부한 Service Mesh"

```
Istio 아키텍처:
  Control Plane (istiod):
    Pilot → 서비스 디스커버리, 라우팅 규칙 배포
    Citadel → 인증서 발급 (mTLS)
    Galley → 설정 검증

  Data Plane:
    각 Pod의 Envoy Sidecar
    → L7 라우팅, mTLS, 메트릭, 재시도, Circuit Breaker
```

**장점:**
- **VirtualService**: 세밀한 트래픽 라우팅 (Header, URI, Weight 등)
- **DestinationRule**: Pod subset 정의, Circuit Breaker 설정
- **AuthorizationPolicy**: HTTP Method, Path, JWT 클레임까지 제어
- Canary 배포 가장 세밀하게 설정 가능
- Kiali 통합 (Service Dependency Map 시각화)
- CNCF Graduated — 가장 많이 쓰이는 Service Mesh

**단점:**
- 설치와 설정이 복잡하다 (CRD 50개 이상)
- 리소스 사용량이 높다 (각 Pod마다 Envoy sidecar)
- 트러블슈팅이 어렵다 (PERMISSIVE/STRICT mTLS, AuthorizationPolicy 충돌 등)
- 성능 오버헤드: Envoy sidecar당 약 50ms 추가 지연

**언제 선택하는가:** L7 정책 세밀하게 필요, Canary 배포 고도화, 관측성 중요, 대형 팀

---

## 3. 직접 비교표

| 항목 | Linkerd | Consul Connect | **Istio** |
|------|---------|----------------|-----------|
| **mTLS** | ✅ | ✅ | ✅ |
| **L7 라우팅** | 기본 | 기본 | ✅ **세밀** |
| **AuthorizationPolicy** | 기본 | 기본 | ✅ **세밀** |
| **Canary 배포** | 가능 | 가능 | ✅ **정밀** |
| **관측성** | 기본 | 기본 | ✅ Kiali |
| **Proxy 기반** | Rust (linkerd2-proxy) | Envoy | **Envoy** |
| **Kubernetes 외 지원** | ❌ | ✅ | 부분 |
| **리소스 사용** | **낮음** | 높음 | 높음 |
| **학습 비용** | **낮음** | 높음 | 높음 |
| **CNCF 성숙도** | Graduated | Incubating | **Graduated** |
| **설치 복잡도** | **쉬움** | 복잡 | 복잡 |

---

## 4. 홈랩에서 Istio를 선택한 이유

### 이유 1: VirtualService로 Canary 배포를 세밀하게

홈랩에서 Argo Rollouts + Istio를 함께 쓴다. 이 조합에서 VirtualService가 핵심이다:

```yaml
# Istio VirtualService로 20% → stable, 80% → canary
http:
- route:
  - destination:
      host: was-service
      subset: stable
    weight: 80
  - destination:
      host: was-service
      subset: canary
    weight: 20
```

Linkerd의 HTTPRoute로도 가능하지만, Argo Rollouts와의 통합은 Istio가 더 성숙하다.

### 이유 2: AuthorizationPolicy — HTTP Method와 Path까지 제어

Cilium이 L7 정책을 지원하지만, Istio의 AuthorizationPolicy는 **JWT 클레임**까지 포함한 더 풍부한 제어를 지원한다:

```yaml
# Istio AuthorizationPolicy (실제 사용 중)
rules:
- from:
  - source:
      principals: ["cluster.local/ns/blog-system/sa/web-sa"]
  to:
  - operation:
      methods: ["GET", "POST"]
      paths: ["/api/*", "/auth/*"]
```

Cilium(L7)과 Istio(AuthorizationPolicy)를 함께 쓰면 이중 방어층이 된다.

### 이유 3: Envoy 이해가 포트폴리오에 필요하다

Envoy는 Istio의 Sidecar이자, 독립적으로도 수천 개의 회사에서 사용하는 핵심 인프라 컴포넌트다. Istio를 운영하면 Envoy를 이해하게 된다:

- `EnvoyFilter`로 직접 Envoy 설정 수정
- Envoy access log로 트래픽 디버깅
- PassthroughCluster 문제 해결 (실제 트러블슈팅 경험)

### 이유 4: Istio가 업계 표준

"Service Mesh 경험 있으신가요?" 질문에 Linkerd보다 Istio가 더 유의미하다. CNCF Survey에서 Service Mesh 도입 기업 중 Istio 점유율이 가장 높다.

---

## 5. 솔직한 트레이드오프

Istio 도입 후 실제로 겪은 어려움:

```
트러블슈팅 목록 (실제 발생):
  1. PERMISSIVE vs STRICT mTLS 충돌 → Gateway 외부 접근 차단
  2. MySQL sidecar 주입 → Protocol Detection 데드락 → 연결 불가
  3. PassthroughCluster → nginx proxy가 Mesh를 우회하는 문제
  4. AuthorizationPolicy hosts 목록 누락 → 403 Forbidden
  5. Envoy sidecar 메모리 사용량 → 노드 OOM
```

이 트러블슈팅들은 모두 블로그 시리즈로 기록했다. 배움이 됐지만, 처음 도입할 때는 2-3주를 이 문제들에 쏟았다.

**Istio를 선택하지 말아야 할 때:**
- 소규모 팀, 운영 인력이 부족할 때
- 리소스가 빠듯한 환경 (Linkerd가 낫다)
- Service Mesh 자체가 처음이고 빠르게 써야 할 때

**홈랩에서 Istio가 맞는 이유:**
- 학습 자체가 목적 → 복잡한 문제를 만나도 배움
- Canary 배포 고도화 필요 → VirtualService가 필수
- Envoy 이해 → 포트폴리오 차별점

---

## 정리

| 상황 | 추천 Service Mesh |
|------|-------------------|
| 단순하고 가볍게, 운영 부담 최소화 | Linkerd |
| Kubernetes + VM 혼합, HashiCorp 스택 | Consul Connect |
| L7 정책 세밀하게 + Canary + Envoy 학습 | **Istio** |

---

**작성일**: 2026-03-03
**태그**: istio, linkerd, consul, service-mesh, kubernetes, mtls, envoy
**다음 글**: [[Istio 시리즈 #1] Service Mesh는 왜 탄생했나](/study/2026-01-15-why-service-mesh/)
