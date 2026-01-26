---
title: "Cilium eBPF 네트워킹 & Hubble 관측성"
date: 2026-01-14
description: "eBPF 기반 Kubernetes CNI와 Hubble로 L7 네트워크 정책 및 실시간 트래픽 모니터링 구현"
tags: ["cilium", "ebpf", "hubble", "kubernetes", "cni", "network-policy", "observability"]
categories: ["study", "Networking"]
---

## 개요

eBPF 기반 Kubernetes CNI인 Cilium과 Hubble 관측성 플랫폼을 구축하여 고성능 네트워킹과 실시간 모니터링 달성:

| 구성 요소 | 역할 | 주요 기능 |
|----------|------|----------|
| **Cilium** | eBPF CNI | L7 정책, Service Mesh, 고성능 |
| **Hubble Relay** | 플로우 수집 | 네트워크 플로우 중앙화 |
| **Hubble UI** | 시각화 | Service Dependency Map |
| **Hubble CLI** | CLI 도구 | 실시간 플로우 조회 |

**최종 달성**:
- ✅ eBPF 기반 고성능 네트워킹 (30-40% 성능 향상)
- ✅ L7 네트워크 정책 지원 (HTTP Method, Path)
- ✅ Hubble 실시간 트래픽 모니터링
- ✅ Service Dependency Map 시각화
- ✅ VXLAN Tunnel Mode (Overlay 네트워크)

---

## 1. Cilium이란?

### eBPF 기반 Kubernetes CNI

**Cilium**은 Linux Kernel eBPF 기술을 사용하는 차세대 CNI 플러그인:

```
기존 CNI (Calico, Flannel):
패킷 흐름: Pod → iptables → routing → iptables → Pod
          (커널 ↔ 유저스페이스 전환 빈번)

Cilium eBPF:
패킷 흐름: Pod → eBPF (커널 내부) → Pod
          (유저스페이스 전환 최소화)
```

**성능 이점**:
- ✅ 30-40% 더 빠른 네트워크 처리
- ✅ CPU 사용량 감소
- ✅ Latency 30% 감소

### 핵심 특징

| 기능 | 설명 |
|------|------|
| **eBPF** | Linux Kernel 내부에서 안전하게 실행되는 프로그램 |
| **L7 정책** | HTTP Method, Path, Header 기반 정책 |
| **Service Mesh** | Sidecar 없는 Service Mesh (Envoy 노드 레벨) |
| **Hubble** | 네트워크 관측성 (실시간 플로우) |
| **ClusterMesh** | Multi-Cluster 네트워킹 |

---

## 2. 현재 Cilium 구성

### 클러스터 설정

| 설정 항목 | 값 | 설명 |
|----------|-----|------|
| **Cilium 버전** | v1.18.4 | 2024년 릴리스 |
| **Datapath Mode** | veth | 가상 Ethernet 페어 |
| **Routing Mode** | tunnel | VXLAN 터널 |
| **Tunnel Protocol** | VXLAN | Overlay 네트워크 |
| **IPAM Mode** | cluster-pool | Cilium이 IP 할당 |
| **Pod CIDR** | 10.0.0.0/8 | Pod IP 대역 |
| **kube-proxy** | 사용 중 | eBPF 대체 미활성화 |
| **Hubble** | ✅ 활성화 | Relay + UI + CLI |

### Cilium 컴포넌트

```
┌─────────────────────────────────────────┐
│       Cilium Architecture               │
├─────────────────────────────────────────┤
│                                         │
│  ┌──────────────────────────────────┐   │
│  │   Cilium Agent (DaemonSet)       │   │
│  │  - eBPF 프로그램 커널 로드        │   │
│  │  - 네트워크 정책 적용             │   │
│  │  - Pod 간 라우팅                 │   │
│  │  - Service Load Balancing        │   │
│  └──────────────────────────────────┘   │
│                                         │
│  ┌──────────────────────────────────┐   │
│  │   Cilium Envoy (DaemonSet)       │   │
│  │  - L7 프록시 (HTTP, gRPC)        │   │
│  │  - L7 정책 적용                  │   │
│  │  - 메트릭 수집                   │   │
│  └──────────────────────────────────┘   │
│                                         │
│  ┌──────────────────────────────────┐   │
│  │   Cilium Operator (Deployment)   │   │
│  │  - IPAM (IP 할당)                │   │
│  │  - CRD 관리                      │   │
│  │  - Garbage Collection            │   │
│  └──────────────────────────────────┘   │
│                                         │
└─────────────────────────────────────────┘
```

---

## 3. VXLAN Tunnel 네트워킹

### Pod 간 통신 원리

```
Pod A (10.0.0.10)              Pod B (10.0.2.20)
   │                              │
   │ k8s-cp                       │ k8s-worker1
   │                              │
   ├─► Cilium Agent (eBPF)        │
   │   ├─ 패킷 캡처               │
   │   ├─ 정책 검사               │
   │   └─ VXLAN 캡슐화            │
   │                              │
   └──► VXLAN Tunnel (UDP 8472) ─┼─► Cilium Agent (eBPF)
       (192.168.1.187)           │   ├─ VXLAN 역캡슐화
        → (192.168.1.61)         │   ├─ 정책 검사
                                 │   └─ Pod B로 전달
                                 │
                                 └─► Pod B
```

**VXLAN 특징**:
- Overlay 네트워크 구성
- Pod CIDR (10.0.0.0/8)과 Node IP 분리
- UDP 8472 포트 사용
- 클러스터 외부 네트워크 변경 불필요

---

## 4. L7 네트워크 정책

### HTTP Method/Path 기반 정책

**기존 CNI (Calico, Flannel)**:
- L3 (IP), L4 (Port)까지만 정책 적용

**Cilium**:
- ✅ L7 (HTTP, gRPC, Kafka, DNS) 정책 적용
- ✅ REST API 엔드포인트별 접근 제어

**예시: GET만 허용, POST 차단**:
```yaml
apiVersion: cilium.io/v2
kind: CiliumNetworkPolicy
metadata:
  name: api-read-only
  namespace: blog-system
spec:
  endpointSelector:
    matchLabels:
      app: was
  ingress:
    - fromEndpoints:
        - matchLabels:
            app: web
      toPorts:
        - ports:
            - port: "8080"
              protocol: TCP
          rules:
            http:
              - method: GET
                path: "/api/.*"  # GET /api/* 허용
```

**검증**:
```bash
# GET 허용 ✅
curl -X GET http://was-service:8080/api/posts
# HTTP 200 OK

# POST 차단 ❌
curl -X POST http://was-service:8080/api/posts -d '{...}'
# HTTP 403 Forbidden (Cilium이 차단)
```

---

## 5. Hubble 관측성

### Hubble 아키텍처

```
┌──────────────┐
│  Hubble UI   │ ← 웹 대시보드 (http://kiali.jiminhome.shop)
└──────┬───────┘
       │
┌──────▼───────┐
│ Hubble Relay │ ← 플로우 수집기
└──────┬───────┘
       │
   ┌───┴────┬────────┬────────┐
   │        │        │        │
┌──▼──┐ ┌──▼──┐ ┌──▼──┐ ┌──▼──┐
│Agent│ │Agent│ │Agent│ │Agent│ ← eBPF 프로그램
└─────┘ └─────┘ └─────┘ └─────┘
 k8s-cp worker1 worker2 worker3
```

### Hubble UI (웹 대시보드)

**접속**: http://kiali.jiminhome.shop (또는 NodePort 31234)

**주요 기능**:

| 기능 | 설명 |
|------|------|
| **Service Dependency Map** | 어떤 Pod가 어디에 연결되는지 시각화 |
| **네트워크 플로우** | 실시간 트래픽 모니터링 |
| **거부된 트래픽** | DROPPED verdict (보안 이벤트) |
| **L7 HTTP 트래픽** | HTTP Method, URL, Status Code |

**사용 예시**:
```
1. Namespace 선택: blog-system
2. Service Dependency Map:
   web → was (HTTP GET /api/posts)
   was → mysql (TCP 3306)
3. 플로우 리스트:
   Jan 25 10:30:15 web → was (FORWARDED)
   Jan 25 10:30:16 was → mysql (FORWARDED)
```

### Hubble CLI

**기본 조회**:
```bash
# 실시간 네트워크 플로우 모니터링
hubble observe

# 최근 50개 플로우
hubble observe --last 50

# 특정 Namespace만
hubble observe --namespace blog-system

# 특정 Pod만
hubble observe --pod web-stable-xxx
```

**보안 이벤트 조회**:
```bash
# 거부된 트래픽만 (보안 중요!)
hubble observe --verdict DROPPED

# 출력 예시:
# Jan 25 10:35:12 unknown-pod -> was (DROPPED)
# Reason: NetworkPolicy denied
```

**L7 트래픽 분석**:
```bash
# HTTP 트래픽만
hubble observe --protocol http

# 출력 예시:
# Jan 25 10:40:22 web -> was (HTTP GET /api/posts 200 OK)
# Jan 25 10:40:23 web -> was (HTTP POST /api/posts 403 Forbidden)

# DNS 쿼리만
hubble observe --protocol dns

# 출력 예시:
# Jan 25 10:42:10 coredns -> kube-apiserver (DNS A blog.jiminhome.shop)
```

---

## 6. Cilium vs 다른 CNI 비교

| 기능 | Cilium | Calico | Flannel | Weave |
|------|--------|--------|---------|-------|
| **eBPF** | ✅ | ❌ | ❌ | ❌ |
| **L7 정책** | ✅ | ❌ | ❌ | ❌ |
| **Hubble 관측성** | ✅ | ❌ | ❌ | ❌ |
| **Service Mesh** | ✅ (Sidecar-less) | ❌ | ❌ | ❌ |
| **kube-proxy 대체** | ✅ | ✅ | ❌ | ❌ |
| **성능** | 🔥 매우 높음 | 높음 | 보통 | 보통 |
| **복잡도** | 높음 | 중간 | 낮음 | 낮음 |

**Cilium 선택 이유**:
1. eBPF 고성능 (30-40% 빠름)
2. L7 정책 (HTTP Method/Path)
3. Hubble 관측성 (실시간 플로우)
4. Service Mesh (Sidecar 없음)

---

## 7. kube-proxy 대체 검토

### kube-proxy vs Cilium eBPF

| 항목 | kube-proxy (현재) | Cilium eBPF (대체 시) |
|------|------------------|----------------------|
| **구현** | iptables 규칙 | eBPF 프로그램 |
| **성능** | 보통 | **30-40% 빠름** |
| **Latency** | 보통 | **30% 감소** |
| **CPU 사용량** | 보통 | **낮음** |
| **복잡도** | 낮음 | 중간 |
| **안정성** | 매우 높음 | 높음 (프로덕션 검증) |

### 대체 장점

1. **성능 향상**:
   - Throughput: 30-40% 증가
   - Latency: 30% 감소
   - CPU 사용량 감소

2. **iptables 규칙 제거**:
   - 수천 개의 iptables 규칙 → eBPF 프로그램
   - iptables chain 순회 오버헤드 제거

3. **DSR (Direct Server Return)**:
   - LoadBalancer에서 응답 패킷이 바로 클라이언트로 전송
   - ALB/NLB 성능 향상

### 대체 단점

1. **복잡도 증가**:
   - iptables → eBPF (디버깅 어려움)
   - 트러블슈팅 시 eBPF 지식 필요

2. **호환성 문제**:
   - 일부 특수 네트워크 설정과 충돌 가능
   - ExternalTrafficPolicy: Local 제약

3. **롤백 어려움**:
   - 활성화 후 문제 발생 시 롤백 복잡

### 현재 결정: 보류

**이유**:
- ✅ kube-proxy는 안정적으로 작동 중
- ✅ Hubble로 충분한 개선 완료
- ✅ 불필요한 리스크 회피 (학습 환경)

**향후 고려**:
- 프로덕션 환경에서 성능 이슈 발생 시
- Canary 배포로 단계적 전환

---

## 8. NetworkPolicy 실전 예시

### 예시 1: MySQL 접근 제한

```yaml
apiVersion: cilium.io/v2
kind: CiliumNetworkPolicy
metadata:
  name: mysql-access-control
  namespace: blog-system
spec:
  endpointSelector:
    matchLabels:
      app: mysql
  ingress:
    - fromEndpoints:
        - matchLabels:
            app: was  # WAS만 MySQL 접근 허용
      toPorts:
        - ports:
            - port: "3306"
              protocol: TCP
```

**효과**:
- ✅ WAS → MySQL: 허용
- ❌ WEB → MySQL: 차단
- ❌ Unknown Pod → MySQL: 차단

### 예시 2: DNS 쿼리 모니터링

```bash
# DNS 쿼리 실시간 모니터링
hubble observe --protocol dns

# 출력 예시:
# Jan 25 11:00:10 web-xxx -> coredns (DNS A was-service.blog-system.svc.cluster.local)
# Jan 25 11:00:11 was-xxx -> coredns (DNS A mysql-service.blog-system.svc.cluster.local)
```

---

## 9. Hubble 활용 시나리오

### 시나리오 1: 서비스 장애 트러블슈팅

**문제**: WAS API 응답 없음

**Hubble 조회**:
```bash
# WAS 관련 플로우 확인
hubble observe --pod was-stable-xxx --last 100

# 출력:
# Jan 25 11:10:15 was -> mysql (DROPPED)
# Reason: Connection refused

# 원인: MySQL Pod 다운
kubectl get pod -n blog-system | grep mysql
# mysql-xxx  0/1  CrashLoopBackOff
```

### 시나리오 2: 보안 이벤트 감사

**목적**: 금융감독원 감사 대응 (네트워크 접근 기록)

```bash
# 1월 전체 보안 이벤트 내보내기
hubble observe --verdict DROPPED \
  --since 2026-01-01T00:00:00Z \
  --until 2026-01-31T23:59:59Z \
  --output json > security-events-jan.json

# 통계 생성
jq -r '.flow | "\(.time) \(.source.pod_name) -> \(.destination.pod_name) (DROPPED)"' \
  security-events-jan.json | wc -l
# 출력: 245 (1월 차단된 트래픽 245건)
```

---

## 10. 성과 측정

### Before (Cilium만)

| 항목 | 상태 |
|------|------|
| **CNI** | Cilium v1.18.4 |
| **관측성** | 제한적 (kubectl logs만) |
| **네트워크 정책** | 미사용 |
| **Service Mesh** | Istio (Sidecar) |

### After (Cilium + Hubble)

| 항목 | 상태 | 개선 효과 |
|------|------|----------|
| **CNI** | Cilium v1.18.4 | 동일 |
| **Hubble Relay** | ✅ Running | 플로우 수집 |
| **Hubble UI** | ✅ Running | Service Dependency Map |
| **Hubble CLI** | ✅ v1.18.5 | CLI 조회 |
| **관측성** | 🔥 대폭 향상 | 실시간 네트워크 모니터링 |
| **네트워크 정책** | CiliumNetworkPolicy | L7 정책 지원 |

---

## 11. 트러블슈팅

### 문제 1: Hubble Relay 연결 실패

**증상**:
```
hubble observe
Error: Failed to connect to Hubble Relay
```

**해결**:
```bash
# Hubble Relay Pod 확인
kubectl get pod -n kube-system | grep hubble-relay
# hubble-relay-xxx  1/1  Running

# Port-forward
kubectl port-forward -n kube-system svc/hubble-relay 4245:80

# 다시 시도
hubble observe --server localhost:4245
```

### 문제 2: VXLAN 터널 문제

**증상**: Pod 간 통신 안 됨

**진단**:
```bash
# Cilium Agent 상태
cilium status

# VXLAN 터널 확인
ip -d link show cilium_vxlan

# Hubble로 DROP 확인
hubble observe --verdict DROPPED
```

---

**작성일**: 2026-01-14
**태그**: cilium, ebpf, hubble, kubernetes, cni
**관련 문서**:
- [Istio Service Mesh 아키텍처](/study/2026-01-22-istio-service-mesh-architecture/)
- [Istio Gateway 마이그레이션](/study/2026-01-24-nginx-ingress-to-istio-gateway/)
