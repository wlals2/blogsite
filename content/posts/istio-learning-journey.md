---
title: "Istio 구축하면서 진짜 배우는 것들 (실패 → 이해 → 해결)"
date: 2026-01-19
summary: "Homeserver에서 Istio 구축하며 겪는 문제들과 그 과정에서 배우는 Service Mesh 핵심 개념"
tags: ["istio", "service-mesh", "kubernetes", "troubleshooting", "learning"]
categories: ["kubernetes"]
series: ["Infrastructure Learning Journey"]
weight: 1
showtoc: true
tocopen: true
draft: false
---

## 🎯 이 글의 목적

> **구축만 하면 의미 없다. 문제를 만나고, 이해하고, 해결하면서 배워야 한다.**

단순히 `istioctl install` 명령어만 따라하면 아무것도 배우지 못합니다.

이 글은 **Homeserver에서 Istio를 구축하며 필연적으로 만나게 되는 문제들**과, 그 과정에서 **진짜 배우게 되는 것들**을 정리한 실전 기록입니다.

---

## 📋 학습 로드맵

```
1단계: Istio 설치          → "왜 Pod가 2개가 되는가?" 이해
2단계: Sidecar Injection   → "Webhook이 뭔지" 이해
3단계: mTLS 활성화         → "TLS 인증서가 어떻게 발급되는가" 이해
4단계: 트래픽 관리         → "VirtualService vs DestinationRule" 이해
5단계: 리소스 부족 경험    → "Istio는 왜 무거운가" 체감
6단계: 성능 측정           → "Envoy 오버헤드" 정량 측정
```

---

## 1단계: Istio 설치 - "Control Plane이 뭔가요?"

### 1.1. 설치 전 의문

**Q: Kubernetes만으로도 Service, Ingress가 있는데 왜 Istio가 필요한가?**

Kubernetes의 기본 기능:
- Service: Pod 간 통신 (L4 로드밸런싱)
- Ingress: 외부 트래픽 라우팅 (L7 라우팅)

**한계**:
- ❌ 트래픽의 10%만 신규 버전으로 보내기 (Canary) → 불가능
- ❌ Pod 간 통신을 암호화 (mTLS) → 불가능
- ❌ API 호출 경로 추적 (Distributed Tracing) → 불가능
- ❌ 특정 서비스 장애 시 자동 차단 (Circuit Breaker) → 불가능

**Istio가 해결하는 것**:
- ✅ L7 트래픽 관리 (세밀한 라우팅)
- ✅ 보안 (자동 mTLS)
- ✅ 관찰성 (Tracing, Metrics)
- ✅ 탄력성 (Retry, Timeout, Circuit Breaker)

---

### 1.2. 설치 과정

```bash
### 📦 Istioctl 설치

curl -L https://istio.io/downloadIstio | ISTIO_VERSION=1.20.2 sh -
cd istio-1.20.2
export PATH=$PWD/bin:$PATH

istioctl version
# client version: 1.20.2
```

```bash
### 🚀 Istio 설치 (minimal 프로파일)

istioctl install --set profile=minimal -y

# 왜 minimal?
#   - demo: Ingress/Egress Gateway 포함 (메모리 많이 먹음)
#   - minimal: Control Plane만 (istiod)
# Homeserver는 리소스가 제한적이므로 minimal 선택
```

**설치 결과 확인**:

```bash
kubectl get pods -n istio-system

# 예상 출력:
NAME                     READY   STATUS    RESTARTS   AGE
istiod-7d6b4c8c9-xxxxx   1/1     Running   0          2m
```

---

### 1.3. 여기서 배우는 것

#### ✅ Control Plane vs Data Plane

**Control Plane (istiod)**:
- 역할: 정책을 정의하고 배포
- 예시: "WAS의 10% 트래픽만 v2로 보내라"는 규칙을 각 Proxy에 전달
- 위치: istio-system Namespace에 1개만 존재

**Data Plane (Envoy Proxy)**:
- 역할: 실제 트래픽을 가로채서 정책 적용
- 예시: WAS Pod 옆에 붙어서 트래픽을 10% v2, 90% v1로 분할
- 위치: 각 Pod 옆에 Sidecar로 주입

```
사용자 → Ingress → [WEB Pod + Envoy] → [WAS Pod + Envoy] → MySQL
                      ↑                    ↑
                      |                    |
                  istiod가 정책 전달 (Control Plane)
```

**핵심 깨달음**:
- Istio = "중앙에서 정책 관리(Control Plane) + 각 Pod에서 실행(Data Plane)" 구조
- Kubernetes의 Deployment가 여러 Pod를 관리하는 것과 비슷한 구조

---

## 2단계: Sidecar Injection - "왜 Pod가 안 떠요?"

### 2.1. Namespace에 Label 추가

```bash
kubectl label namespace blog-system istio-injection=enabled
```

### 2.2. Deployment 재시작

```bash
kubectl rollout restart deployment web-deployment -n blog-system
kubectl rollout restart deployment was-deployment -n blog-system
```

### 2.3. 문제 발생: Pod가 안 뜬다

```bash
kubectl get pods -n blog-system

NAME                    READY   STATUS    RESTARTS   AGE
web-pod-xxxxx           1/2     Running   0          1m
was-pod-xxxxx           1/2     Running   0          1m
```

**READY가 1/2**: 2개 컨테이너 중 1개만 준비됨

```bash
### 🔍 원인 파악

kubectl describe pod web-pod-xxxxx -n blog-system

# Events 섹션:
#   Readiness probe failed: connection refused
```

**왜 이런 일이?**

---

### 2.4. 여기서 배우는 것

#### ✅ Mutating Webhook의 동작 원리

**Istio는 어떻게 Sidecar를 주입하는가?**

1. 사용자가 Pod를 생성 요청
2. Kubernetes API Server가 Mutating Webhook 호출
3. Istio의 Webhook이 Pod 정의를 수정 (Sidecar 추가)
4. 수정된 Pod 정의로 생성됨

```yaml
# 원본 Deployment:
spec:
  containers:
  - name: web
    image: nginx:alpine

# Istio가 수정한 Pod:
spec:
  initContainers:
  - name: istio-init        # iptables 규칙 설정
  containers:
  - name: web
    image: nginx:alpine
  - name: istio-proxy       # Envoy Sidecar
    image: istio/proxyv2
```

**문제의 원인**:
- `istio-init`이 iptables를 설정하는데 시간이 걸림
- 이 과정이 끝나기 전에 `web` 컨테이너가 먼저 시작
- `web` 컨테이너가 외부 통신 시도 → iptables 미완성 → 실패

**해결 방법**:

```yaml
# Deployment에 holdApplicationUntilProxyStarts 추가
spec:
  template:
    metadata:
      annotations:
        proxy.istio.io/config: '{ "holdApplicationUntilProxyStarts": true }'
```

**배우는 것**:
- Mutating Webhook = "Pod 생성 시 가로채서 수정하는 기능"
- Init Container = "메인 컨테이너 전에 실행되는 준비 작업"
- iptables = "모든 트래픽을 Envoy로 리다이렉트하는 규칙"

---

## 3단계: mTLS 활성화 - "인증서는 어디서 오나요?"

### 3.1. mTLS 적용

```yaml
apiVersion: security.istio.io/v1beta1
kind: PeerAuthentication
metadata:
  name: default
  namespace: blog-system
spec:
  mtls:
    mode: STRICT
```

```bash
kubectl apply -f peer-authentication.yaml
```

### 3.2. 문제 발생: MySQL 통신이 끊김

```bash
kubectl logs was-pod-xxxxx -c was

# 에러:
# com.mysql.jdbc.exceptions.jdbc4.CommunicationsException:
# Communications link failure
```

**WAS → MySQL 연결 실패**

```bash
### 🔍 원인 파악

kubectl get pods -n blog-system -o wide

NAME                    READY   STATUS
web-pod-xxxxx           2/2     Running
was-pod-xxxxx           2/2     Running
mysql-pod-xxxxx         1/1     Running  ← Sidecar 없음!
```

**문제**:
- WAS Pod: Sidecar 있음 (TLS로 통신 시도)
- MySQL Pod: Sidecar 없음 (평문 통신만 가능)
- mTLS STRICT 모드: "무조건 TLS로만 통신해라"
- 결과: 통신 실패

---

### 3.3. 여기서 배우는 것

#### ✅ mTLS의 동작 원리

**1. 인증서는 어디서 발급되는가?**

```bash
### 🔍 Envoy 컨테이너 내부 확인

kubectl exec -it was-pod-xxxxx -c istio-proxy -n blog-system -- sh

ls /etc/certs/
# cert-chain.pem
# key.pem
# root-cert.pem
```

**인증서 발급 흐름**:
1. istiod가 Certificate Authority (CA) 역할
2. Envoy Proxy 시작 시 istiod에게 인증서 요청
3. istiod가 서명된 인증서 발급
4. Envoy가 인증서를 `/etc/certs/`에 저장

**2. mTLS Handshake 과정**

```
WAS Pod (Envoy)          MySQL Pod (Envoy)
      |                        |
      |--- TLS Client Hello -->|
      |<-- TLS Server Hello ---|
      |--- 인증서 제시 -------->|
      |<-- 인증서 제시 ---------|
      |--- 검증 완료 ---------->|
      |<-- 검증 완료 -----------|
      |=== 암호화 통신 시작 ===|
```

**3. Sidecar 없는 Pod는?**

```
WAS Pod (Envoy)          MySQL Pod (No Sidecar)
      |                        |
      |--- TLS Client Hello -->| ← MySQL은 TLS 이해 못함
      |<-- (응답 없음) --------|
      |--- 연결 실패! ---------|
```

**해결 방법**:

```bash
# MySQL StatefulSet도 재시작하여 Sidecar 주입
kubectl rollout restart statefulset mysql -n blog-system
```

**배우는 것**:
- mTLS = "양쪽 모두 인증서를 제시하는 TLS"
- Istio의 CA = istiod가 자체 인증서 발급
- STRICT 모드 = "Sidecar 없는 Pod는 통신 불가"

---

## 4단계: 트래픽 관리 - "VirtualService가 안 먹혀요"

### 4.1. Canary 배포 시도

```yaml
# VirtualService 생성
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: web-canary
  namespace: blog-system
spec:
  hosts:
  - web-service
  http:
  - route:
    - destination:
        host: web-service
        subset: v1
      weight: 90
    - destination:
        host: web-service
        subset: v2
      weight: 10
```

```bash
kubectl apply -f virtual-service.yaml

# Error from server (BadRequest):
# error when creating "virtual-service.yaml":
# VirtualService.networking.istio.io "web-canary" is invalid:
# spec.http[0].route[0].destination.subset: Not found: "v1"
```

**에러**: "subset v1이 없다"

---

### 4.2. 문제 원인: DestinationRule 누락

**Istio 트래픽 관리 구조**:

```
VirtualService           DestinationRule
    ↓                         ↓
"어디로 보낼까?"         "어떻게 구분할까?"
    ↓                         ↓
v1: 90%, v2: 10%        v1 = label: version=v1
                        v2 = label: version=v2
```

**DestinationRule 생성**:

```yaml
apiVersion: networking.istio.io/v1beta1
kind: DestinationRule
metadata:
  name: web-destination
  namespace: blog-system
spec:
  host: web-service
  subsets:
  - name: v1
    labels:
      version: v1
  - name: v2
    labels:
      version: v2
```

```bash
kubectl apply -f destination-rule.yaml
# destinationrule.networking.istio.io/web-destination created

kubectl apply -f virtual-service.yaml
# virtualservice.networking.istio.io/web-canary created
```

---

### 4.3. 여기서 배우는 것

#### ✅ VirtualService vs DestinationRule의 역할

**VirtualService**: "트래픽 라우팅 규칙"
- 어떤 요청을 어디로 보낼지
- 예: `/api` → was-service, `/` → web-service
- 예: Header `canary: true` → v2, 나머지 → v1

**DestinationRule**: "서비스의 버전 정의 + 트래픽 정책"
- v1, v2가 뭔지 정의 (label selector)
- 예: v1 = `version: v1` label을 가진 Pod
- 예: v2 = `version: v2` label을 가진 Pod
- 추가로: Load Balancing, Circuit Breaker 설정

**왜 둘로 나눴는가?**
- 관심사 분리 (Separation of Concerns)
- VirtualService: "어디로?" (라우팅 로직)
- DestinationRule: "어떻게?" (버전 정의, 정책)

**실전 활용**:
```bash
# Canary 배포 시나리오:
# 1. DestinationRule로 v1, v2 정의
# 2. VirtualService로 10% → v2
# 3. 10분 후 문제 없으면 50% → v2
# 4. 최종적으로 100% → v2
```

---

## 5단계: 리소스 부족 - "Homeserver가 죽어요"

### 5.1. 문제 발생: OOMKilled

```bash
kubectl get pods -n blog-system

NAME                    READY   STATUS      RESTARTS   AGE
web-pod-xxxxx           1/2     OOMKilled   3          5m
was-pod-xxxxx           2/2     Running     1          5m
```

**OOMKilled = Out Of Memory Killed (메모리 부족으로 강제 종료)**

```bash
### 🔍 원인 파악

kubectl top pods -n blog-system

NAME                    CPU     MEMORY
web-pod-xxxxx           50m     150Mi
  - web:                20m     50Mi
  - istio-proxy:        30m     100Mi  ← Sidecar가 100MB 먹음

was-pod-xxxxx           80m     200Mi
  - was:                50m     120Mi
  - istio-proxy:        30m     80Mi
```

**문제**:
- Homeserver 메모리: 8GB
- 기존 Pod 메모리: 50MB × 10개 = 500MB
- Istio 추가 후: (50MB + 100MB) × 10개 = 1500MB
- **메모리 사용량 3배 증가!**

---

### 5.2. 해결: Envoy 리소스 제한

```yaml
# istio-system/istio-sidecar-injector ConfigMap 수정
apiVersion: v1
kind: ConfigMap
metadata:
  name: istio-sidecar-injector
  namespace: istio-system
data:
  values: |-
    global:
      proxy:
        resources:
          requests:
            cpu: 10m
            memory: 64Mi
          limits:
            cpu: 100m
            memory: 128Mi
```

```bash
kubectl rollout restart deployment/istiod -n istio-system

# 모든 Pod 재시작 (새로운 Sidecar 리소스 제한 적용)
kubectl rollout restart deployment -n blog-system
```

---

### 5.3. 여기서 배우는 것

#### ✅ Istio의 메모리 오버헤드

**왜 Sidecar가 메모리를 많이 먹는가?**

1. **Envoy Proxy 자체 메모리**:
   - Envoy는 C++로 작성된 고성능 프록시
   - 기본 메모리 풋프린트: 50-100MB

2. **통계 정보 저장**:
   - 모든 HTTP 요청/응답 메트릭
   - 예: 초당 100 RPS면 초당 100개 메트릭
   - Prometheus가 scrape할 때까지 메모리에 저장

3. **TLS 인증서 캐시**:
   - 각 서비스별 인증서
   - Root CA, Cert Chain 저장

**트레이드오프**:
- ✅ 장점: 세밀한 트래픽 관리, 보안, 관찰성
- ❌ 단점: 메모리 +50-100MB/Pod, CPU +10-30m/Pod

**Homeserver 생존 전략**:
1. **리소스 제한 강화**: CPU 10m, Memory 64Mi로 줄임
2. **필요한 Pod만 Injection**: Label로 선택적 주입
3. **Ambient Mesh 고려**: Sidecar 없는 방식 (차세대 Istio)

---

## 6단계: 성능 측정 - "Envoy 오버헤드 체감"

### 6.1. Istio 설치 전 성능 측정

```bash
### 🔍 기본 K8s Service 지연시간

kubectl run curl-test --image=curlimages/curl --rm -it -- sh

curl -o /dev/null -s -w '%{time_total}\n' http://web-service/

# 평균: 0.012초 (12ms)
```

### 6.2. Istio 설치 후 성능 측정

```bash
# 동일한 테스트 반복
curl -o /dev/null -s -w '%{time_total}\n' http://web-service/

# 평균: 0.017초 (17ms)
```

**증가한 지연시간: +5ms**

---

### 6.3. 왜 느려졌는가?

#### ✅ Envoy Proxy의 처리 단계

```
기존 (Kubernetes Service):
curl → Service (iptables) → Pod
           ↑ (1 hop)

Istio (Envoy Sidecar):
curl → Service → Envoy (source) → Envoy (destination) → Pod
         ↑ (3 hops)
```

**Envoy가 하는 일**:
1. **TLS Handshake**: mTLS 인증서 검증 (첫 연결 시)
2. **L7 파싱**: HTTP 헤더 파싱 (path, method, headers)
3. **정책 적용**: VirtualService 규칙 확인 (v1? v2?)
4. **메트릭 수집**: 응답 시간, 상태 코드 기록
5. **Tracing**: Jaeger에 보낼 Span 생성

**각 단계별 오버헤드**:
- TLS Handshake: +2-3ms (첫 연결만)
- L7 파싱: +1-2ms
- 정책 적용: +0.5ms
- 메트릭/Tracing: +1ms

**합계: 약 5ms**

---

### 6.4. 여기서 배우는 것

#### ✅ Latency vs Feature 트레이드오프

**Istio 없이 (12ms)**:
- ✅ 빠름
- ❌ Canary 배포 불가
- ❌ 암호화 없음
- ❌ 트래픽 추적 불가

**Istio 있음 (17ms)**:
- ❌ +5ms 느림 (약 40% 증가)
- ✅ Canary 배포 가능
- ✅ 자동 mTLS
- ✅ Distributed Tracing

**언제 Istio를 쓸까?**:
- 🟢 마이크로서비스 10개 이상: 필수 (복잡도 관리)
- 🟡 서비스 3-10개: 선택 (보안/관찰성 필요 시)
- 🔴 단일 서비스: 불필요 (오버킬)

---

## 7단계: 실전 트러블슈팅 경험

### 7.1. "503 Service Unavailable"

**증상**:
```bash
curl http://web-service/
# 503 Service Unavailable
```

**원인 체크리스트**:

1. **Envoy가 Backend를 찾지 못함**:
```bash
kubectl exec -it web-pod-xxxxx -c istio-proxy -- sh
curl localhost:15000/clusters | grep web-service

# healthy: 0/2  ← Backend가 없음!
```

**해결**: DestinationRule의 subset label이 잘못됨

2. **Circuit Breaker 발동**:
```bash
curl localhost:15000/stats | grep outlier_detection

# outlier_detection.ejections_active: 2  ← 2개 Pod 차단됨
```

**해결**: Circuit Breaker 설정 완화 또는 Backend 수정

---

### 7.2. "Connection Reset by Peer"

**증상**:
```bash
kubectl logs was-pod-xxxxx -c was

# java.net.SocketException: Connection reset by peer
```

**원인**: mTLS STRICT 모드인데 Legacy Pod (Sidecar 없음)와 통신 시도

**해결**:
```yaml
# PeerAuthentication을 PERMISSIVE로 변경
spec:
  mtls:
    mode: PERMISSIVE  # mTLS 선택적 허용
```

---

### 7.3. "Too Many Requests"

**증상**:
```bash
curl http://web-service/
# 429 Too Many Requests
```

**원인**: Circuit Breaker의 Connection Pool 초과

```yaml
# DestinationRule에서 확인
trafficPolicy:
  connectionPool:
    tcp:
      maxConnections: 10  ← 너무 적음
```

**해결**: `maxConnections`을 100으로 증가

---

## 🎯 최종 정리: Istio 구축하면서 진짜 배우는 것

### 1. Control Plane vs Data Plane
- istiod = 정책 관리자
- Envoy = 실행자
- xDS Protocol = 둘 사이의 통신

### 2. Mutating Webhook
- Pod 생성 시 가로채서 Sidecar 주입
- Init Container로 iptables 설정
- Kubernetes의 확장 메커니즘 이해

### 3. mTLS 동작 원리
- istiod가 CA 역할 (인증서 발급)
- Envoy가 자동으로 TLS Handshake
- STRICT vs PERMISSIVE 차이

### 4. VirtualService vs DestinationRule
- VirtualService = 라우팅 규칙 ("어디로?")
- DestinationRule = 버전 정의 + 정책 ("어떻게?")
- 관심사 분리의 설계 철학

### 5. 리소스 오버헤드
- Sidecar = +50-100MB 메모리/Pod
- Envoy = +5ms 지연시간
- 트레이드오프 체감 (성능 vs 기능)

### 6. 트러블슈팅 능력
- 503 = Backend 찾기 실패
- Connection Reset = mTLS 불일치
- 429 = Connection Pool 부족
- Envoy Admin API 활용 (`localhost:15000`)

---

## 💡 Homeserver에서 Istio 구축이 의미 있는 이유

### 1. 비용 0원으로 무한 실험
- AWS EKS: Istio 테스트 = $100+/월
- Homeserver: 전기세만 (약 5,000원/월)
- 실패해도 괜찮음 (무한 재시도)

### 2. 리소스 제약 경험
- 메모리 부족 → 튜닝 경험
- "왜 무거운지" 체감 → 트레이드오프 이해
- 프로덕션에서도 똑같은 문제 발생

### 3. 트러블슈팅 실력
- "에러 만나기 → 원인 파악 → 해결" 반복
- Envoy Admin API, istioctl analyze 활용
- 면접에서 "실제 해결한 문제" 설명 가능

### 4. MSA 아키텍처 이해
- 여러 서비스 간 복잡한 통신
- Service Mesh가 해결하는 문제
- "왜 필요한지" 진짜 이해

---

## 🚀 다음 단계: Ambient Mesh 도전

**기존 Istio (Sidecar 방식)**:
- Pod마다 Envoy 주입
- 메모리 3배 증가
- Homeserver에 부담

**Ambient Mesh (Sidecar-less)**:
- 노드마다 Ztunnel 1개만
- 메모리 절감 (노드당 100MB vs Pod당 100MB)
- L4 기능만 Ztunnel, L7은 Waypoint Proxy

**Homeserver에 딱!**
- 리소스 절약
- Istio 핵심 기능 유지
- "최신 기술 적용" 어필

---

**작성일**: 2026-01-19
**환경**: Homeserver Kubernetes (8GB RAM)
**난이도**: ⭐⭐⭐⭐⭐ (Expert - 트러블슈팅 필수)
**예상 소요 시간**: 10-15시간 (문제 해결 포함)
