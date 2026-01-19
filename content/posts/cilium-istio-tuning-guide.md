---
title: "Homeserver K8s: Cilium & Istio 튜닝 실전 가이드"
date: 2026-01-19
summary: "베어메탈 Kubernetes에서 Cilium eBPF 튜닝과 Istio Service Mesh 구축 방법"
tags: ["kubernetes", "cilium", "istio", "service-mesh", "ebpf", "homelab"]
categories: ["kubernetes"]
series: ["Infrastructure Learning Journey"]
weight: 1
showtoc: true
tocopen: true
draft: false
---

## 📌 개요

> **환경**: Homeserver Kubernetes (베어메탈, Cilium CNI 사용 중)
> **목표**: Cilium 성능 튜닝 + Istio Service Mesh 추가
> **전제**: Phase 4 Homeserver K8s 환경 (kubeadm, Cilium, Longhorn)

---

## 🎯 Cilium vs Istio 역할

```
Cilium (Layer 3-4)          Istio (Layer 7)
   ↓                            ↓
네트워킹 + 보안             트래픽 관리 + 관찰성
   ↓                            ↓
Pod IP 라우팅               서비스 메시 (mTLS, 트래픽 분할)
eBPF 기반 고성능            Envoy Sidecar 기반
```

**핵심**: 둘 다 사용 가능 (상호 보완적)

---

## Part 1: Cilium 튜닝 (성능 & 관찰성)

### 1. 현재 상태 확인

```bash
### 🔍 Cilium 상태 확인

kubectl get pods -n kube-system -l k8s-app=cilium

# 왜? Cilium이 모든 노드에서 실행 중인지 확인
# 예상: DaemonSet으로 각 노드마다 1개씩
# 주의: NOT READY 상태면 CNI 장애 → Pod 통신 불가
```

```bash
### 🔍 Cilium 버전 및 상태 확인

cilium status --wait

# 왜? eBPF Map 사용률, Health 상태 확인
# 확인 항목: "BPF Maps" 섹션에서 사용률
# 주의: 90% 이상이면 확장 필요
```

---

### 2. eBPF Map 크기 튜닝 (성능 개선)

**문제 상황**: Pod 개수가 많아지면 eBPF Map 부족으로 연결 실패

#### 2.1. ConfigMap 수정

```bash
### 🔧 Cilium ConfigMap 수정

kubectl edit configmap cilium-config -n kube-system

# 다음 값 추가/수정:
data:
  bpf-map-dynamic-size-ratio: "0.0025"  # 기본값 유지
  bpf-ct-global-tcp-max: "524288"       # 기본값: 262144 (2배 증가)
  bpf-ct-global-any-max: "262144"       # 기본값: 131072 (2배 증가)

# 왜? Pod 수가 100개 이상이면 기본값으론 부족
# 전/후: 262K → 524K (연결 추적 테이블 2배)
# 주의: 메모리 사용량 증가 (노드당 약 50MB 추가)
```

#### 2.2. 설정 적용

```bash
### 🔄 Cilium Agent 재시작 (설정 적용)

kubectl rollout restart daemonset/cilium -n kube-system

# 왜? ConfigMap 변경은 재시작해야 적용됨
# 주의: DaemonSet이므로 순차적으로 재시작 (서비스 중단 최소화)
# 예상 시간: 노드당 30초씩 (3노드면 1.5분)
```

#### 2.3. 검증

```bash
### 🔍 변경사항 확인

cilium status --wait

# BPF Maps 섹션에서 확인:
#   CT (Connection Tracking) 테이블 크기 증가 확인
# 예상: 524288/524288 (최대값)
```

---

### 3. Hubble 활성화 (네트워크 관찰성)

**Hubble = Cilium의 네트워크 관찰 도구 (Service Map, Flow 로그)**

#### 3.1. Hubble 활성화

```bash
### 🔧 Hubble 활성화

cilium hubble enable

# 왜? 네트워크 플로우를 실시간으로 볼 수 있음
# 효과: kubectl logs 없이도 Pod 간 통신 디버깅
# 주의: 메트릭 저장으로 메모리 사용량 증가 (약 100MB)
```

#### 3.2. Hubble CLI 설치

```bash
### 📦 Hubble CLI 설치

export HUBBLE_VERSION=$(curl -s https://raw.githubusercontent.com/cilium/hubble/master/stable.txt)
curl -L --remote-name-all https://github.com/cilium/hubble/releases/download/$HUBBLE_VERSION/hubble-linux-amd64.tar.gz{,.sha256sum}
sha256sum --check hubble-linux-amd64.tar.gz.sha256sum
tar xzvf hubble-linux-amd64.tar.gz
sudo mv hubble /usr/local/bin

# 왜? Hubble CLI로 실시간 플로우 조회
# 확인: hubble version
```

#### 3.3. Hubble UI 설치 (선택 사항)

```bash
### 📊 Hubble UI 설치

cilium hubble enable --ui

# 왜? 브라우저에서 Service Map 시각화
# 접속 방법:
kubectl port-forward -n kube-system svc/hubble-ui 12000:80

# 브라우저: http://localhost:12000
```

#### 3.4. 실전 활용 예시

```bash
### 🔍 전체 네트워크 플로우 확인

hubble observe

# 왜? 모든 Pod의 네트워크 통신 실시간 확인
# 예상 출력:
#   web-pod -> was-service:8080 (ALLOWED)
#   was-pod -> mysql-service:3306 (ALLOWED)
#   web-pod -> mysql-service:3306 (DENIED by NetworkPolicy)
```

```bash
### 🔍 특정 Pod의 플로우만 확인

hubble observe --pod blog-system/web-pod-xxxxx

# 왜? 특정 Pod가 어디로 트래픽을 보내는지 확인
# 활용: NetworkPolicy 디버깅 시 필수
```

```bash
### 🔍 dropped 패킷만 확인

hubble observe --verdict DROPPED

# 왜? NetworkPolicy로 차단된 트래픽 확인
# 활용: "왜 연결이 안 되지?" 디버깅 시 사용
```

---

### 4. Bandwidth Manager (네트워크 QoS)

**목적**: Pod별 대역폭 제한으로 노이지 네이버(noisy neighbor) 방지

#### 4.1. Bandwidth Manager 활성화

```bash
### 🔧 Bandwidth Manager 활성화

kubectl edit configmap cilium-config -n kube-system

data:
  enable-bandwidth-manager: "true"

kubectl rollout restart daemonset/cilium -n kube-system

# 왜? 특정 Pod가 대역폭을 독점하면 다른 Pod 느려짐
# 전/후: 무제한 → Pod별 상한선 설정 가능
# 주의: Linux kernel 5.1+ 필요 (확인: uname -r)
```

#### 4.2. Pod에 대역폭 제한 적용

```yaml
### 📝 WAS Deployment에 annotation 추가

apiVersion: apps/v1
kind: Deployment
metadata:
  name: was-deployment
  namespace: blog-system
spec:
  template:
    metadata:
      annotations:
        kubernetes.io/egress-bandwidth: 100M   # 100 Mbps 송신 제한
        kubernetes.io/ingress-bandwidth: 100M  # 100 Mbps 수신 제한
    spec:
      containers:
      - name: was
        image: springboot-app:latest
```

```bash
### 🚀 Deployment 적용

kubectl apply -f was-deployment.yaml

# 왜? annotation 추가 후 재배포해야 적용
# 확인: kubectl describe pod was-pod-xxxxx | grep -A 2 "Annotations"
```

#### 4.3. 검증

```bash
### 🔍 대역폭 제한 동작 확인

# Pod 내부에서 속도 테스트
kubectl exec -it was-pod-xxxxx -- sh
apk add iperf3
iperf3 -c 외부서버IP -t 30

# 왜? 100 Mbps로 제한되는지 확인
# 예상: 약 100 Mbps (12.5 MB/s) 수렴
# 주의: 네트워크 품질에 따라 ±10% 오차
```

---

### 5. Cilium 성능 측정

#### 5.1. 튜닝 전 성능 측정

```bash
### 🔍 Pod 간 네트워크 성능 측정 (튜닝 전)

# iperf3 서버 실행
kubectl run iperf-server --image=networkstatic/iperf3 -- iperf3 -s

# iperf3 클라이언트 실행
kubectl run iperf-client --image=networkstatic/iperf3 -- iperf3 -c iperf-server -t 30

# 왜? 튜닝 효과를 정량적으로 측정
# 예상 결과 (기본): 약 5-10 Gbps (로컬 네트워크)
# 주의: 노드 간 테스트는 물리적 네트워크에 의존
```

#### 5.2. 튜닝 후 재측정

```bash
### 🔍 eBPF Map 확장 후 재측정

# 동일한 iperf3 테스트 반복
# 예상 효과: 연결 수 제한 해소 (대규모 테스트 가능)
# 예시: 동시 100개 연결 시 튜닝 전 실패 → 튜닝 후 성공
```

---

## Part 2: Istio 설치 (Service Mesh)

### 1. Istio가 필요한 이유

**Homeserver 블로그 환경에서 Istio 활용:**
- **Canary 배포**: Hugo 블로그 업데이트 시 10% 트래픽만 신규 버전으로
- **mTLS**: WEB ↔ WAS ↔ MySQL 통신 암호화
- **Distributed Tracing**: 요청 경로 추적 (디버깅 편리)
- **Circuit Breaker**: MySQL 장애 시 WAS 보호
- **학습**: 실제 Service Mesh 운영 경험

---

### 2. Istio 설치

#### 2.1. istioctl 설치

```bash
### 📦 istioctl 설치

curl -L https://istio.io/downloadIstio | ISTIO_VERSION=1.20.2 sh -
cd istio-1.20.2
export PATH=$PWD/bin:$PATH

# 영구 적용 (bashrc에 추가)
echo 'export PATH=$HOME/istio-1.20.2/bin:$PATH' >> ~/.bashrc
source ~/.bashrc

# 확인
istioctl version
# 예상: 1.20.2
```

#### 2.2. Istio 설치 (Minimal 프로파일)

```bash
### 🚀 Istio 설치 (homeserver용 minimal)

istioctl install --set profile=minimal -y

# 왜?
#   - minimal: Control Plane만 설치 (istiod)
#   - demo: Ingress/Egress Gateway 포함 (불필요)
# 전/후: Istio Control Plane (istiod) 설치
# 예상 시간: 1-2분
# 주의: 메모리 4GB 이상 권장
```

```bash
### 🔍 Istio 설치 확인

kubectl get pods -n istio-system

# 예상 출력:
#   istiod-xxxxx  1/1  Running

# 왜? istiod = Service Mesh Control Plane (핵심)
# 주의: istiod가 CrashLoopBackOff면 메모리 부족 가능성
```

---

### 3. Sidecar Injection 설정

#### 3.1. Namespace에 자동 Injection 활성화

```bash
### 🔧 blog-system Namespace에 자동 Injection 활성화

kubectl label namespace blog-system istio-injection=enabled

# 왜? 이제부터 이 Namespace의 Pod는 Envoy Sidecar 자동 주입
# 전/후: Pod 1개 → Pod 2개 (앱 컨테이너 + istio-proxy)
# 주의: 기존 Pod는 재시작해야 적용됨
```

#### 3.2. Pod 재시작 (Sidecar 주입)

```bash
### 🔄 WEB/WAS Deployment 재시작

kubectl rollout restart deployment web-deployment -n blog-system
kubectl rollout restart deployment was-deployment -n blog-system

# 왜? Sidecar는 Pod 생성 시에만 주입됨
# 확인:
kubectl get pods -n blog-system

# 예상: READY 2/2 (앱 + istio-proxy)
```

```bash
### 🔍 Sidecar 주입 확인

kubectl describe pod web-pod-xxxxx -n blog-system

# 확인 사항:
#   - Containers: 2개 (web, istio-proxy)
#   - Init Containers: istio-init (iptables 설정)
# 왜? istio-proxy가 모든 트래픽을 가로채서 처리
```

---

### 4. mTLS 활성화 (Pod 간 암호화)

#### 4.1. PeerAuthentication 생성

```yaml
### 📝 mTLS STRICT 모드 적용

apiVersion: security.istio.io/v1beta1
kind: PeerAuthentication
metadata:
  name: default
  namespace: blog-system
spec:
  mtls:
    mode: STRICT  # 모든 Pod 간 통신 암호화 강제
```

```bash
### 🔒 mTLS 적용

kubectl apply -f peer-authentication.yaml

# 왜? WEB → WAS → MySQL 통신도 TLS로 암호화 (보안 강화)
# 전/후: 평문 통신 → TLS 1.3 암호화
# 주의: Istio Sidecar 없는 Pod는 통신 불가
```

#### 4.2. 검증

```bash
### 🔍 mTLS 동작 확인

kubectl exec -it was-pod-xxxxx -c istio-proxy -n blog-system -- sh

# Envoy admin 인터페이스 확인
curl localhost:15000/stats | grep ssl

# 예상 출력:
#   ssl.handshake: 152 (TLS Handshake 횟수)
#   ssl.connection_error: 0

# 왜? Envoy가 자동으로 mTLS 처리 중인지 확인
```

---

### 5. Canary 배포 (트래픽 분할)

**시나리오**: Hugo 블로그 업데이트 시 10% 트래픽만 신규 버전으로

#### 5.1. DestinationRule 생성

```yaml
### 📝 DestinationRule (v1, v2 서브셋 정의)

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

#### 5.2. VirtualService 생성

```yaml
### 📝 VirtualService (트래픽 분할)

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
      weight: 90  # 90% 트래픽
    - destination:
        host: web-service
        subset: v2
      weight: 10  # 10% 트래픽 (Canary)
```

```bash
### 🚀 Canary 배포 시작

kubectl apply -f destination-rule.yaml
kubectl apply -f virtual-service.yaml

# 왜? 10% 트래픽만 v2로 전송 → 안전한 배포
# 전/후: 모든 트래픽 v1 → 10% v2 + 90% v1
```

#### 5.3. 트래픽 비율 조정

```bash
### 📊 10분 후 에러율 확인 (정상이면 50%로 증가)

kubectl edit virtualservice web-canary -n blog-system

# weight: 10 → 50으로 변경
# 왜? 점진적으로 증가하며 안정성 확인
# 최종: 100% v2로 전환 후 v1 삭제
```

#### 5.4. 검증

```bash
### 🔍 트래픽 분산 확인 (Prometheus 쿼리)

# Grafana에서 실행:
sum(rate(istio_requests_total{
  destination_service="web-service.blog-system.svc.cluster.local",
  destination_version="v2"
}[1m])) / sum(rate(istio_requests_total{
  destination_service="web-service.blog-system.svc.cluster.local"
}[1m]))

# 예상: 0.1 (10%)
# 왜? 실제 트래픽 비율이 설정과 일치하는지 확인
```

---

### 6. Distributed Tracing (Jaeger 연동)

#### 6.1. Jaeger 설치

```bash
### 📦 Jaeger All-in-One 설치

kubectl apply -f https://raw.githubusercontent.com/istio/istio/release-1.20/samples/addons/jaeger.yaml

# 왜? Distributed Tracing으로 요청 경로 추적
# 예상 시간: 1분
```

```bash
### 🔍 Jaeger UI 접속

kubectl port-forward -n istio-system svc/tracing 16686:80

# 브라우저: http://localhost:16686
# 활용: 블로그 접속 → Jaeger에서 요청 경로 확인
#   Ingress → WEB → WAS → MySQL 전체 흐름
```

#### 6.2. Tracing 확인

```bash
### 🔍 요청 추적 테스트

# 블로그 접속
curl http://blog.jiminhome.shop/

# Jaeger UI에서 확인:
#   - Service: web-service
#   - Spans: Ingress → web-pod → was-service → mysql-service
#   - Duration: 각 구간별 지연시간

# 왜? 병목 구간 파악 (어디서 느린지)
# 활용: "왜 느리지?" 디버깅 시 사용
```

---

### 7. Circuit Breaker (장애 차단)

**시나리오**: MySQL 장애 시 WAS 보호

```yaml
### 📝 DestinationRule (Circuit Breaker)

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
        maxConnections: 10  # 최대 10개 연결
      http:
        http1MaxPendingRequests: 1
        maxRequestsPerConnection: 1
    outlierDetection:
      consecutiveErrors: 3     # 3회 연속 실패 시
      interval: 30s
      baseEjectionTime: 30s    # 30초간 차단
      maxEjectionPercent: 100
```

```bash
### 🚀 Circuit Breaker 적용

kubectl apply -f circuit-breaker.yaml

# 왜? MySQL이 느리거나 장애 시 WAS가 계속 대기하지 않도록
# 전/후: 무한 대기 → 3회 실패 후 즉시 에러 반환
# 효과: Cascading Failure 방지
```

---

## 📊 성능 측정 및 트레이드오프

### Istio Sidecar Latency

```bash
### 🔍 Istio 추가 지연시간 측정

# Istio 설치 전:
curl -o /dev/null -s -w '%{time_total}\n' http://blog.jiminhome.shop/

# Istio 설치 후:
curl -o /dev/null -s -w '%{time_total}\n' http://blog.jiminhome.shop/

# 예상: +2-5ms (Envoy Overhead)
# 트레이드오프: 지연 증가 vs 보안/관찰성 향상
```

### 메모리 사용량

```bash
### 🔍 Pod 메모리 사용량 확인

kubectl top pods -n blog-system

# Istio 없을 때:
#   web-pod: 50MB
# Istio 있을 때:
#   web-pod: 100MB (앱 50MB + istio-proxy 50MB)

# 트레이드오프: 메모리 2배 증가 vs Service Mesh 기능
```

---

## 🎯 튜닝 우선순위 (추천 순서)

### 1단계: Cilium Hubble (가장 간단, 즉시 효과)
- 예상 시간: 30분
- 효과: 네트워크 플로우 시각화
- 트레이드오프: 메모리 +100MB

### 2단계: Cilium eBPF 튜닝
- 예상 시간: 1시간
- 효과: 대규모 Pod 환경 대비
- 트레이드오프: 메모리 +50MB/노드

### 3단계: Istio 설치 + mTLS
- 예상 시간: 2시간
- 효과: Pod 간 통신 보안 강화
- 트레이드오프: 메모리 +50MB/Pod, 지연 +2-5ms

### 4단계: Canary 배포 + Distributed Tracing
- 예상 시간: 2시간
- 효과: 안전한 배포 + 디버깅 편리
- 트레이드오프: 복잡도 증가

---

## 📝 트러블슈팅

### Cilium Agent 재시작 후 Pod 통신 안 됨

```bash
### 🔍 원인 확인

cilium status
kubectl get pods -A | grep -v Running

# 원인: eBPF 프로그램 로드 실패
# 해결: 노드 재부팅 (eBPF 상태 초기화)
```

### Istio Sidecar 주입 안 됨

```bash
### 🔍 원인 확인

kubectl get namespace blog-system --show-labels

# 원인: istio-injection=enabled 라벨 누락
# 해결: kubectl label namespace blog-system istio-injection=enabled
```

### mTLS 적용 후 통신 실패

```bash
### 🔍 원인 확인

kubectl logs was-pod-xxxxx -c istio-proxy

# 원인: MySQL Pod에 Sidecar 없음 (StatefulSet)
# 해결: MySQL Deployment도 재시작하여 Sidecar 주입
```

---

**작성일**: 2026-01-19
**환경**: Homeserver Kubernetes (Cilium + kubeadm)
**난이도**: ⭐⭐⭐⭐ (Advanced)
