---
title: "[Cilium 시리즈 #1] eBPF란 무엇인가 — 커널을 바꾸지 않고 커널을 프로그래밍하다"
date: 2026-03-03T09:00:00+09:00
description: "eBPF가 Linux 커널에서 어떻게 동작하는지, 왜 Cilium이 eBPF를 선택했는지, 그리고 kube-proxy와 iptables 방식과 무엇이 다른지 개념부터 이해한다"
tags: ["cilium", "ebpf", "kubernetes", "cni", "networking", "linux-kernel"]
categories: ["study", "Networking"]
series: ["Cilium 시리즈"]
draft: false
---

## 이 글을 읽기 전에

Cilium 시리즈에서 다루는 글들은 대부분 **어떻게 구축했는가**에 집중되어 있었다.

- Cilium으로 kube-proxy 대체하기
- Hubble로 네트워크 트래픽 관찰하기
- CiliumNetworkPolicy 실전 적용

그런데 막상 구축하면서 이런 질문이 남았다:

> **"eBPF가 정확히 뭐길래, Cilium이 이걸로 kube-proxy보다 빠르다는 건가?"**

이 글은 그 질문에 답한다.

---

## 1. 먼저 알아야 할 것: Linux 커널과 User Space

### 두 개의 세계

Linux는 동작 공간을 두 영역으로 나눈다:

```
┌─────────────────────────────────────────────────────┐
│                   User Space                        │
│  (애플리케이션, nginx, kubectl, 내가 짠 코드 등)       │
│                                                     │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐             │
│  │ nginx   │  │ kubectl │  │ python  │             │
│  └────┬────┘  └────┬────┘  └────┬────┘             │
│       │            │            │                   │
│  "파일 열어줘" "네트워크 보내줘" "메모리 줘"          │
│       │            │            │                   │
├───────┼────────────┼────────────┼───────────────────┤
│       ▼            ▼            ▼  System Call 경계  │
├─────────────────────────────────────────────────────┤
│                   Kernel Space                      │
│  (네트워크, 파일시스템, 스케줄러, 드라이버 등)         │
│                                                     │
│  ┌──────────────────────────────────────────┐       │
│  │  TCP/IP Stack  │  File System  │  ...    │       │
│  └──────────────────────────────────────────┘       │
└─────────────────────────────────────────────────────┘
```

**왜 나뉘는가?**
- 커널은 하드웨어를 직접 제어한다 — 버그 하나가 전체 시스템 다운
- User Space 프로그램이 커널에 직접 접근하면 위험하다
- 그래서 "System Call"이라는 창구를 통해서만 커널 기능을 요청한다

**이게 네트워킹에서 문제가 되는 이유:**

패킷이 네트워크 카드에서 들어와 애플리케이션까지 도달하는 과정에서, **User Space ↔ Kernel Space 전환**이 수십 번 발생한다. 이 전환 자체가 CPU 비용이다.

---

## 2. 기존 Kubernetes 네트워킹의 문제: iptables

### kube-proxy는 어떻게 동작하는가

Service 리소스를 만들면, `kube-proxy`가 각 노드에 **iptables 규칙**을 자동으로 생성한다.

```bash
# 100개 Pod가 있을 때 생성되는 iptables 규칙 수 (실제 확인)
iptables-save | wc -l
# 출력: 약 3,000개
```

패킷이 처리되는 흐름:

```
패킷 도착
  │
  ▼
PREROUTING chain (iptables 규칙 순회 시작)
  │
  ├─ 규칙 1: 해당 없음 (통과)
  ├─ 규칙 2: 해당 없음 (통과)
  ├─ 규칙 3: 해당 없음 (통과)
  │  ... (수천 개 규칙 순회)
  ├─ 규칙 2847: 매칭! (Service IP → Pod IP DNAT)
  │
  ▼
FORWARD chain → OUTPUT chain → 패킷 전달
```

**문제**:
- Service가 1개 늘어날 때마다 iptables 규칙이 수십 개 추가된다
- 규칙이 3,000개면 패킷마다 3,000번 순회한다 (O(n) 복잡도)
- 클러스터가 커질수록 선형으로 느려진다
- 규칙 업데이트 시 전체 규칙 테이블을 잠그고 재작성 → CPU spike

---

## 3. eBPF: 커널 안에서 안전하게 실행되는 프로그램

### eBPF란

**eBPF (extended Berkeley Packet Filter)**는 Linux 커널 내부에서 사용자 정의 프로그램을 실행할 수 있는 기술이다.

```
기존 방식 (User Space에서 제어):
  패킷 → Kernel → [복사] → User Space (iptables, nginx 등) → [복사] → Kernel → 처리
  ↑ 비용: 컨텍스트 스위치 × 2, 메모리 복사 × 2

eBPF 방식 (Kernel 안에서 직접 처리):
  패킷 → Kernel → [eBPF 프로그램 실행] → 처리 완료
  ↑ 비용: 없음 (Kernel 내부에서 끝)
```

**왜 안전한가?**

eBPF 프로그램은 커널에 직접 로드되지만, 실행 전에 **Verifier**가 검사한다:

```
개발자가 eBPF 프로그램 작성
  │
  ▼
컴파일 (clang + llvm → BPF bytecode)
  │
  ▼
커널 Verifier 검사
  ├─ 무한루프 없는가? (실행 시간 제한 존재)
  ├─ 허용된 메모리 영역만 접근하는가?
  ├─ 커널 크래시를 유발할 수 있는가?
  └─ 모든 체크 통과 → 커널 JIT 컴파일 → 실행
       실패 → 로드 거부
```

커널을 재컴파일하거나 재시작하지 않고, 동적으로 로드/언로드 가능하다.

### eBPF Hook 포인트

eBPF 프로그램은 커널 내 여러 지점에 "hook"으로 붙을 수 있다:

```
네트워크 카드
  │
  ▼ ← XDP (eXpress Data Path) — 가장 빠름, 패킷이 커널 스택 진입 전
  │
  ▼ ← TC (Traffic Control) — Cilium이 주로 사용
  │
  ├─ iptables PREROUTING
  ├─ 라우팅
  ├─ iptables FORWARD
  └─ iptables OUTPUT
  │
  ▼ ← Socket 레벨 — 소켓 생성/연결 시점
  │
  ▼
애플리케이션
```

Cilium은 TC(Traffic Control) hook에 eBPF 프로그램을 붙여서, iptables보다 **앞 단계에서** 패킷을 처리한다.

---

## 4. Cilium이 eBPF로 무엇을 바꾸는가

### kube-proxy 대체: O(1) 로드밸런싱

iptables는 O(n) 선형 탐색이었다. Cilium의 eBPF는 **BPF Map**이라는 해시 테이블을 사용한다:

```
iptables 방식 (O(n)):
  패킷 → 규칙1 → 규칙2 → ... → 규칙2847 (매칭!)
  → Service 100개면 규칙 수천 개 순회

Cilium eBPF 방식 (O(1)):
  패킷 → BPF Map 해시 조회 → 즉시 목적지 Pod IP 확인
  → Service 100개여도 1번 조회로 끝
```

Service가 10,000개로 늘어도 조회 시간은 동일하다.

### CiliumNetworkPolicy: L7까지 제어

기존 NetworkPolicy는 IP/Port만 제어 (L3/L4):
```yaml
# 기존 Kubernetes NetworkPolicy (L3/L4)
ingress:
  - ports:
    - port: 8080    # 포트까지만 제어
```

Cilium은 HTTP Method, Path, Header까지 제어 (L7):
```yaml
# CiliumNetworkPolicy (L7)
ingress:
  - toPorts:
    - ports:
      - port: "8080"
      rules:
        http:
          - method: GET         # HTTP Method 제어
            path: "/api/.*"     # URL Path 제어
```

이게 가능한 이유: eBPF가 패킷 내용을 Kernel 안에서 직접 파싱하기 때문.

---

## 5. 실제로 어떤 차이가 있는가

### 홈랩 환경에서 관측한 수치

```
Cilium v1.18.4 / K8s v1.31 / 5노드 (베어메탈 1 + VMware VM 4)

kube-proxy 기반:
  Service 개수: 67개
  iptables 규칙: 약 2,800개
  노드당 CPU 사용: ~3% (kube-proxy 단독)

eBPF 기반 (kube-proxy 완전 제거):
  BPF Map 크기: 일정 (Service 수 무관)
  노드당 CPU 사용: ~0.8% (Cilium agent 내에서 처리)
```

### 개념 정리: Cilium 아키텍처 전체

```
┌────────────────────────────────────────────────┐
│              Kubernetes Control Plane           │
│                                                │
│  etcd → API Server → Cilium Operator           │
│                           │                    │
│              CRD 감시 (CiliumNetworkPolicy 등)  │
└───────────────────────────┬────────────────────┘
                            │ 정책 배포
                            │
         ┌──────────────────┼──────────────────┐
         │                  │                  │
┌────────▼───┐   ┌────────▼────┐   ┌─────────▼──┐
│  Worker1   │   │   Worker2   │   │  Worker3   │
│            │   │             │   │            │
│ Cilium     │   │ Cilium      │   │ Cilium     │
│ Agent      │   │ Agent       │   │ Agent      │
│ (DaemonSet)│   │ (DaemonSet) │   │ (DaemonSet)│
│            │   │             │   │            │
│ eBPF 프로그램 로드           │   │            │
│ ┌─────────┐│   │             │   │            │
│ │BPF Map  ││   │             │   │            │
│ │(해시테이블)│   │             │   │            │
│ └─────────┘│   │             │   │            │
└────────────┘   └─────────────┘   └────────────┘
```

**각 컴포넌트 역할**:

| 컴포넌트 | 역할 | 실행 위치 |
|---------|------|----------|
| **Cilium Agent** | eBPF 프로그램 커널 로드, 정책 적용 | 각 노드 (DaemonSet) |
| **Cilium Operator** | IPAM (IP 할당), CRD 관리 | Control Plane |
| **Hubble Agent** | 네트워크 플로우 수집 (eBPF hook) | 각 노드 |
| **Hubble Relay** | 플로우 중앙 집계 | 클러스터 |
| **BPF Map** | Service→Pod 매핑 해시 테이블 | 커널 메모리 |

---

## 6. 왜 홈랩에 Cilium을 선택했는가

### Flannel vs Calico vs Cilium

| 항목 | Flannel | Calico | Cilium |
|------|---------|--------|--------|
| **기술 기반** | VXLAN | iptables/eBPF | eBPF |
| **네트워크 정책** | ❌ | L3/L4 | L3/L4/**L7** |
| **kube-proxy 대체** | ❌ | 일부 | ✅ 완전 |
| **관측성** | ❌ | 제한적 | **Hubble** (L7 플로우) |
| **복잡도** | 낮음 | 중간 | 높음 |
| **학습 비용** | 낮음 | 중간 | 높음 |

**선택 이유:**
- 보안 엔지니어 포트폴리오 목적 — "L7 정책까지 제어한다"는 게 의미 있다
- Hubble 관측성 — 네트워크 트래픽을 눈으로 볼 수 있다
- eBPF 원리를 직접 이해하고 싶었다

---

## 7. 다음에 다룰 것

이 글은 eBPF와 Cilium의 **개념**을 다뤘다.

시리즈 다음 글들에서는 실제 구축과 적용을 다룬다:

| 글 | 내용 |
|----|------|
| **[#2] Cilium eBPF 네트워킹 & Hubble 관측성** | 실제 설치, VXLAN 구성, Hubble 사용 |
| **[#3] Cilium eBPF로 kube-proxy 대체하기** | kube-proxy 완전 제거, BPF 로드밸런싱 |
| **[#4] CiliumNetworkPolicy 실전** | L7 정책 작성, 트러블슈팅 |

---

## 정리

- **eBPF**: Linux 커널 내부에서 사용자 정의 프로그램을 안전하게 실행하는 기술
- **기존 iptables 문제**: O(n) 선형 탐색 — Service가 늘어날수록 느려짐
- **eBPF의 해결**: BPF Map(해시 테이블)으로 O(1) 조회
- **Cilium**: eBPF를 Kubernetes CNI에 적용한 구현체
- **추가로 얻는 것**: L7 정책, Hubble 관측성, kube-proxy 대체 가능

---

**작성일**: 2026-03-03
**태그**: cilium, ebpf, kubernetes, cni, linux-kernel, networking
**다음 글**: [[Cilium 시리즈 #2] Cilium eBPF 네트워킹 & Hubble 관측성](/study/2026-01-14-cilium-ebpf-networking/)
