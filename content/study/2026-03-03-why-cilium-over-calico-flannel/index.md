---
title: "[Cilium 시리즈 #0] 왜 Flannel도 Calico도 아닌 Cilium인가 — CNI 선택의 기준"
date: 2026-03-03T08:00:00+09:00
description: "Flannel, Calico, Cilium 세 CNI를 실제로 비교해보고, 홈랩 Kubernetes에 Cilium을 선택한 구체적인 이유를 기술한다"
tags: ["cilium", "calico", "flannel", "kubernetes", "cni", "networking", "ebpf"]
categories: ["Networking"]
series: ["Cilium 시리즈"]
draft: false
---

## 왜 이 글이 필요한가

Cilium 시리즈의 다른 글들은 "이미 Cilium을 선택했다"는 전제에서 시작한다.

그런데 처음 Kubernetes CNI를 선택할 때는 이런 상황이다:

- `kubeadm init` 실행하면 CNI를 설치하라고 한다
- 공식 문서에 Flannel, Calico, Cilium, Weave 등이 나열되어 있다
- 무엇을 선택해야 하는지 기준이 없다

이 글은 **"왜 Cilium인가"**를 직접 비교를 통해 설명한다.

---

## 1. CNI가 무엇을 해주는가

**CNI (Container Network Interface)**는 Kubernetes에서 Pod 간 통신을 담당하는 네트워크 플러그인이다.

CNI 없이는:
- Pod에 IP를 할당할 수 없다
- 다른 노드의 Pod와 통신할 수 없다
- Service 로드밸런싱이 동작하지 않는다

**CNI는 단순한 "배관 공사"가 아니다.** 어떤 CNI를 선택하느냐에 따라 아래가 달라진다:

| 결정되는 것 | 예시 |
|-------------|------|
| 네트워크 정책 수준 | L3/L4만 vs L7(HTTP)까지 |
| 성능 | iptables 방식 vs eBPF |
| 관측성 | 없음 vs Hubble 실시간 플로우 |
| kube-proxy 대체 가능 여부 | Flannel은 불가, Cilium은 가능 |

---

## 2. 세 가지 대표 CNI 비교

### Flannel — "일단 연결만 되면 된다"

```
Flannel 동작 방식:
  Pod A (Node1) → VXLAN 터널 → Pod B (Node2)
  구현: User Space에서 패킷 캡슐화/역캡슐화
```

**장점:**
- 설정이 가장 단순하다 (10분이면 끝)
- 안정적이고 오래됐다
- 학습 비용 없음

**단점:**
- NetworkPolicy 자체를 지원하지 않는다
  - `kubectl apply -f network-policy.yaml` 해도 효과 없음
  - 별도 플러그인(kube-router 등) 없이는 Pod 간 접근 제어 불가
- kube-proxy 대체 불가
- 관측성 도구 없음

**언제 선택하는가:** 학습 목적, 단순한 개발 환경, 네트워크 정책이 필요 없는 경우

---

### Calico — "성능과 정책의 균형"

```
Calico 동작 방식 (BGP 모드):
  Pod A (Node1) → BGP 라우팅 → Pod B (Node2)
  구현: Linux 네이티브 라우팅 (오버레이 없음)
```

**장점:**
- L3/L4 NetworkPolicy 완전 지원
- BGP 모드에서 오버레이 없이 고성능
- 대규모 클러스터(수천 노드) 검증된 안정성
- 운영 이력이 길고 커뮤니티 방대

**단점:**
- L7(HTTP Method/Path) 정책 지원 없음
  - "was Pod는 8080 포트에만 접근 가능" → O
  - "was Pod는 GET /api/* 요청만 허용" → X
- kube-proxy 대체는 eBPF 모드에서 부분적
- 관측성 도구 없음 (별도 Hubble 같은 것 없음)
- BGP 모드는 네트워크 장비 설정 필요

**언제 선택하는가:** 대규모 프로덕션, L3/L4 정책으로 충분한 경우, 안정성 우선

---

### Cilium — "eBPF로 커널 레벨에서 처리"

```
Cilium 동작 방식:
  Pod A → eBPF Hook (커널 내부) → Pod B
  구현: Linux 커널 eBPF 프로그램 (User Space 전환 없음)
```

**장점:**
- L3/L4 + **L7(HTTP Method, Path, Header) NetworkPolicy**
- **kube-proxy 완전 대체** 가능 (O(1) BPF Map 로드밸런싱)
- **Hubble**: 실시간 네트워크 플로우 관측성 (Service Dependency Map)
- eBPF 기반 고성능 (iptables 대비 30-40% 처리량 향상)
- Kubernetes 1.21+ Cluster Mesh 지원

**단점:**
- Linux Kernel 5.4 이상 필요 (구형 OS 불가)
- 학습 비용이 높다 (eBPF 개념 이해 필요)
- 트러블슈팅이 복잡하다 (eBPF 디버깅)
- Flannel/Calico 대비 리소스 사용량 높음

**언제 선택하는가:** L7 정책 필요, 관측성 중요, 보안 중심, eBPF 학습 목적

---

## 3. 직접 비교표

| 항목 | Flannel | Calico | **Cilium** |
|------|---------|--------|------------|
| **L3/L4 NetworkPolicy** | ❌ | ✅ | ✅ |
| **L7 NetworkPolicy** | ❌ | ❌ | ✅ |
| **kube-proxy 대체** | ❌ | 부분 | ✅ 완전 |
| **관측성 (플로우)** | ❌ | ❌ | ✅ Hubble |
| **기반 기술** | VXLAN | BGP/iptables | **eBPF** |
| **성능** | 보통 | 높음 | **최고** |
| **최소 Kernel** | 3.x | 3.x | **5.4+** |
| **학습 비용** | 낮음 | 중간 | **높음** |
| **리소스 사용** | 낮음 | 중간 | 높음 |
| **커뮤니티/안정성** | 성숙 | 성숙 | 빠르게 성장 |

---

## 4. 홈랩에서 Cilium을 선택한 이유

### 이유 1: 보안 엔지니어 포트폴리오 목적

"네트워크 정책으로 Pod 간 접근을 제어했다"와
"HTTP Method와 Path까지 제어했다"는 포트폴리오에서 전혀 다른 무게를 가진다.

```yaml
# Calico로 할 수 있는 것 (L3/L4)
ingress:
  - ports:
    - port: 8080   # 포트만 제어

# Cilium으로 할 수 있는 것 (L7)
ingress:
  - toPorts:
    - ports:
      - port: "8080"
      rules:
        http:
          - method: GET        # HTTP Method
            path: "/api/.*"    # URL Path
```

실제로 구현한 정책:
- `web → was`: GET/POST /api/*, /auth/* 허용
- `was → mysql`: TCP 3306만 허용 (MySQL 프로토콜 직접)
- `외부 → web 외 나머지`: 전면 차단

### 이유 2: Hubble로 실제로 "보이는" 네트워크

Calico나 Flannel에는 Hubble 같은 관측성 도구가 없다. 문제가 생겼을 때 추측해야 한다.

```bash
# Hubble로 문제를 즉시 확인한 실제 사례
hubble observe --namespace blog-system --verdict DROPPED

# 출력: was → mysql DROPPED (Cilium NetworkPolicy가 차단)
# → "아, mysql NetworkPolicy 설정이 잘못됐구나" 즉시 파악
```

Flannel/Calico였으면 kubectl logs와 tcpdump를 병행해야 했을 것이다.

### 이유 3: eBPF 자체를 이해하고 싶었다

Cilium, Falco, 최신 Linux 성능 도구들이 모두 eBPF를 사용한다. "왜 Falco가 sidecar 없이도 컨테이너를 감시할 수 있는가"를 이해하려면 eBPF를 알아야 한다.

Cilium을 직접 운영하면서 eBPF 개념을 체감할 수 있었다:
- BPF Map이 왜 O(1)인지
- eBPF hook이 커널 어느 지점에 붙는지
- kube-proxy iptables와 무엇이 다른지

### 이유 4: kube-proxy를 언제든 대체할 수 있다

처음에는 kube-proxy를 유지했지만, 언제든 `kubeProxyReplacement: true`로 전환할 수 있다는 점이 중요했다. Flannel이었으면 이 선택지가 없다.

---

## 5. 솔직한 트레이드오프

Cilium이 항상 정답은 아니다.

**Cilium을 선택하지 말아야 할 때:**
- Kernel 5.4 미만 환경 (CentOS 7, Ubuntu 18 등)
- 팀이 eBPF 학습에 시간을 쓸 수 없을 때
- 수천 노드 이상의 대규모 클러스터 (Calico BGP가 더 검증됨)
- 단순한 개발 환경 (Flannel이면 충분)

**홈랩에서 Cilium이 맞는 이유:**
- 최신 Ubuntu 22.04 (Kernel 6.8) — 조건 충족
- 학습이 목적이므로 복잡해도 괜찮다
- 5노드 소규모 — Calico BGP의 장점이 필요 없다
- 보안 엔지니어 포트폴리오 — L7 정책과 관측성이 핵심 차별점

---

## 정리

| 상황 | 추천 CNI |
|------|----------|
| 학습/개발 환경, 단순하게 | Flannel |
| 대규모 프로덕션, 안정성 최우선 | Calico |
| L7 정책 + 관측성 + eBPF 학습 | **Cilium** |

---

**작성일**: 2026-03-03
**태그**: cilium, calico, flannel, kubernetes, cni, networking, ebpf
**다음 글**: [[Cilium 시리즈 #1] eBPF란 무엇인가](/study/2026-03-03-cilium-ebpf-concept/)
