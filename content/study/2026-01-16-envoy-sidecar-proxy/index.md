---
title: "Envoy — Istio의 심장, Sidecar 프록시 동작 원리"
date: 2026-01-16T10:00:00+09:00
summary: "Istio에서 실제 트래픽을 처리하는 것은 Envoy다. iptables로 트래픽을 가로채고, xDS API로 설정을 받아 L7 라우팅을 수행한다. Envoy가 어떻게 모든 트래픽을 알아채는지 살펴본다."
tags: ["envoy", "istio", "service-mesh", "sidecar", "proxy", "kubernetes", "iptables", "xds"]
categories: ["study", "Service Mesh"]
series: ["Istio 실전 시리즈"]
showtoc: true
tocopen: true
---

## Envoy는 무엇인가

Envoy는 C++로 작성된 고성능 L7 프록시다. Lyft에서 마이크로서비스 네트워크 문제를 해결하기 위해 만들었고, 현재는 CNCF 졸업 프로젝트다.

Istio에서 Envoy는 **Data Plane**을 담당한다. Control Plane(istiod)이 라우팅 규칙을 관리하고, 실제 트래픽을 처리하는 것은 각 Pod에 붙은 Envoy Sidecar다.

```
kubectl get pods -n blog-system
# 출력:
# NAME                    READY   STATUS    RESTARTS
# web-xxx                 2/2     Running   0        ← 2/2: app + envoy sidecar
# was-xxx                 2/2     Running   0
```

`2/2`는 컨테이너 2개가 Ready 상태라는 의미다. 하나는 애플리케이션, 다른 하나는 Envoy Sidecar다.

---

## Sidecar 패턴: 어떻게 트래픽을 가로채나

의문이 생긴다. 애플리케이션 코드는 `http://was-service/api`로 요청을 보내는데, Envoy가 어떻게 이 요청을 가로채나?

### iptables 규칙

Pod가 시작될 때 `istio-init` 초기화 컨테이너가 먼저 실행되어 iptables 규칙을 설정한다.

```bash
# Pod 내부 iptables 규칙 확인 (실제 홈랩 출력)
$ kubectl exec -n blog-system web-xxx -c istio-proxy -- iptables -t nat -L
# 출력:
# Chain PREROUTING
#   REDIRECT  all  --  anywhere  anywhere  /* istio-inbound */
#   → 모든 인바운드 트래픽을 15006 포트(Envoy)로 리다이렉트
#
# Chain OUTPUT
#   REDIRECT  all  --  anywhere  !127.0.0.1/32  /* istio-output */
#   → 모든 아웃바운드 트래픽을 15001 포트(Envoy)로 리다이렉트
```

**모든 인바운드/아웃바운드 트래픽이 Envoy를 통과**하도록 iptables가 강제한다. 애플리케이션은 이 사실을 모른다.

```
애플리케이션이 보는 것:
  app → was-service:8080 (직접 연결처럼 보임)

실제 흐름:
  app → [iptables] → Envoy(15001) → was-service Envoy(15006) → app
```

### Envoy 포트 구조

```bash
$ kubectl exec -n blog-system web-xxx -c istio-proxy -- netstat -tlnp
# 출력:
# Proto  Port   설명
# tcp    15000  Admin API (Envoy 설정/통계 조회)
# tcp    15001  아웃바운드 트래픽 수신
# tcp    15006  인바운드 트래픽 수신
# tcp    15010  XDS (gRPC, 평문)
# tcp    15020  통합 헬스체크 + Prometheus 메트릭
# tcp    15021  헬스체크 전용
# tcp    15090  Prometheus 메트릭 (Envoy 통계)
```

Envoy Admin API로 현재 설정 상태를 직접 볼 수 있다:

```bash
$ kubectl exec -n blog-system web-xxx -c istio-proxy -- curl localhost:15000/clusters
# 출력: Envoy가 알고 있는 업스트림 서비스 목록
# was-service|default|outbound|8080||was-service.blog-system.svc.cluster.local
# ...
```

---

## xDS API: Control Plane이 설정을 배포하는 방식

Envoy는 설정 파일을 읽는 전통적인 프록시와 다르다. **동적으로 설정을 받아** 재시작 없이 적용한다.

istiod가 Envoy에게 설정을 밀어 넣는 API가 **xDS (Discovery Service)**다.

```
xDS API 종류:
  LDS (Listener Discovery Service) → 어느 포트에서 들을 것인가
  RDS (Route Discovery Service)    → 요청을 어디로 보낼 것인가
  CDS (Cluster Discovery Service)  → 업스트림 서비스 목록
  EDS (Endpoint Discovery Service) → 각 서비스의 실제 Pod IP 목록
```

VirtualService YAML을 `kubectl apply`하면 어떤 일이 일어나는가:

```
1. kubectl apply -f virtualservice.yaml
   ↓
2. istiod가 변경 감지
   ↓
3. istiod → Envoy 전체에 xDS Push
   ↓
4. Envoy가 새 라우팅 규칙 적용 (재시작 없음)
   ↓
5. 다음 요청부터 새 규칙 적용
```

재시작이 필요 없기 때문에 Canary 배포에서 트래픽 비율을 바꿔도 즉시 적용된다.

---

## Envoy가 처리하는 것들

### L7 라우팅

Envoy는 L4(TCP/IP)가 아닌 L7(HTTP/gRPC)에서 동작한다. HTTP 헤더, 메서드, URL 경로를 보고 라우팅한다.

```bash
# HTTP 헤더 기반 라우팅 설정 확인
$ kubectl exec -n blog-system web-xxx -c istio-proxy -- curl -s localhost:15000/config_dump | \
  python3 -c "import json,sys; d=json.load(sys.stdin); print([r for r in d['configs'] if 'routes' in str(r)[:100]][:1])"
# → VirtualService 규칙이 Envoy 내부 라우팅 테이블로 변환된 것을 볼 수 있음
```

### 메트릭 자동 수집

코드 변경 없이 자동으로 수집된다:

```bash
$ kubectl exec -n blog-system web-xxx -c istio-proxy -- curl -s localhost:15090/stats/prometheus | \
  grep -E "istio_requests_total.*was-service"
# 출력:
# istio_requests_total{connection_security_policy="mutual_tls",
#   destination_service="was-service.blog-system.svc.cluster.local",
#   response_code="200",...} 1842
```

### mTLS 자동 처리

인증서 관리는 istiod가 한다. Envoy는 자동으로 인증서를 받아 TLS 핸드셰이크를 처리한다.

```bash
# 현재 Envoy가 보유한 인증서 확인
$ kubectl exec -n blog-system web-xxx -c istio-proxy -- curl -s localhost:15000/certs
# 출력:
# {
#   "certificates": [{
#     "cert_chain": [{
#       "subject_alt_names": ["spiffe://cluster.local/ns/blog-system/sa/default"]
#     }],
#     "valid_from": "2026-01-23T...",
#     "expiration_time": "2026-01-24T..."  ← 24시간마다 자동 갱신
#   }]
# }
```

istiod가 24시간마다 인증서를 자동 갱신한다. 서비스 재시작 없이.

---

## Envoy가 없었다면

홈랩 blog-system에서 web → was 호출이 Envoy를 통과하지 않았을 때:

```
Before (PassthroughCluster):
  Kiali에서 web → was 연결이 검정색 (mesh 우회)
  mTLS 없음, 메트릭 없음, 트래픽 제어 불가

After (Envoy 통과):
  Kiali에서 초록색 연결 (mTLS 적용됨)
  istio_requests_total 메트릭 자동 수집
  VirtualService로 Retry/Timeout 적용 가능
```

실제 이 문제를 해결한 과정은 [PassthroughCluster 트러블슈팅](/study/2026-01-20-nginx-proxy-istio-mesh-passthrough/) 글에서 다룬다.

---

## Control Plane (istiod) 간단 정리

Envoy(Data Plane)에 설정을 배포하는 istiod는 세 컴포넌트를 통합한 것이다.

| 컴포넌트 | 역할 |
|---------|------|
| **Pilot** | 라우팅 규칙 관리, xDS API로 Envoy에 배포 |
| **Citadel** | 인증서 발급/갱신, SPIFFE 기반 서비스 ID 관리 |
| **Galley** | 설정 검증, CRD(VirtualService 등) 처리 |

```bash
$ kubectl get pods -n istio-system
# 출력:
# NAME                            READY   STATUS    RESTARTS
# istiod-xxx                      1/1     Running   0  ← 세 역할을 하나의 Pod에서
# istio-ingressgateway-xxx        1/1     Running   0  ← 외부 트래픽 진입점
```

Istio 1.5 이전에는 Pilot, Citadel, Galley가 별도 Pod였다. 1.5부터 istiod 하나로 통합됐다.

---

## 다음 글

다음 편에서는 istiod가 Envoy에게 배포하는 설정의 핵심인 **VirtualService와 DestinationRule**을 다룬다. L7 라우팅을 세밀하게 제어하는 이 두 CRD가 왜 분리되어 있는지, 각각 무엇을 담당하는지를 살펴본다.

- 이전: [Service Mesh는 왜 탄생했나](/study/2026-01-15-why-service-mesh/)
- 다음: [VirtualService & DestinationRule — L7 라우팅의 두 축](/study/2026-01-17-virtualservice-destinationrule/)
