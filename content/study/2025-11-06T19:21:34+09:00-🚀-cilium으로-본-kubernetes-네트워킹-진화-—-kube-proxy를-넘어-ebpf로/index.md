---
title: "🚀 Cilium으로 본 Kubernetes 네트워킹 진화 — kube-proxy를 넘어 eBPF로"
date: 2025-11-06T19:21:34+09:00
draft: false
categories: ["study", "Networking"]
tags: ["k8s","network","cilium","kube-proxy"]
description: "🚀 Cilium으로 본 Kubernetes 네트워킹 진화 — kube-proxy를 넘어 eBPF로"
author: "늦찌민"
---

# 🚀 Cilium으로 본 Kubernetes 네트워킹 진화 — kube-proxy를 넘어 eBPF로

쿠버네티스의 네트워크 트래픽은 기본적으로 `kube-proxy` 가 관리한다. \
Service의 `ClusterIP`를 생성하고, `iptables` 또는 `IPVS` 규칙을 통해 Pod로 패킷을 라우팅한다. \
하지만 이 구조는 노드와 Pod가 많아질수록 성능 저하, 관리 복잡도, 가시성 한계를 보이기 시작한다.

이 문제를 근본적으로 해결한 것이 Cilium이다.
**Cilium은 Linux eBPF 기술을 활용해 커널 레벨에서 네트워크 처리를 수행하며,
기존 kube-proxy를 완전히 대체할 수 있는 차세대 CNI 플러그인이다.**

---

### 🔍 내가 사용하는 클러스터는 Cilium? kube-proxy?
- Cilium이 kube-proxy를 대체 중인지, 아니면 함께 공존 중인지는 아래 명령어로 알 수 있다.
```bash
kubectl -n kube-system exec -it \
$(kubectl -n kube-system get pod -l k8s-app=cilium -o jsonpath='{.itmes[0].metadata.name}') \
-- cilium status --verbose
```

결과 중 다음 부분을 확인한다.

```

KubeProxyReplacement:   True   [eno1   192.168.1.187 ... (Direct Routing)]

```
- `True` / `strict` → Cilium이 kube-proxy를 완전히 대체
(Service 트래픽을 eBPF 로드밸런서가 처리)

- `partial` → 일부 기능만 Cilium이 처리, 나머지는 kube-proxy가 병행

- `disabled` / `False` → ***기존 kube-proxy가 모든 Service 처리 담당***

즉, 내 환경처럼 `KubeProxyReplacement: True` 로 표시되면 \
***kube-proxy DaemonSet이 떠 있더라도 실제 트래픽 경로는 eBPF***를 거친다.

---

### 🧩 Cilium이 kube-proxy를 대체하는 방식

| 계층          | 기존 (kube-proxy)           | Cilium (eBPF)                  |
| ----------- | ------------------------- | ------------------------------ |
| Service 트래픽 | iptables/IPVS 규칙 체인에서 라우팅 | eBPF 맵에서 직접 라우팅 (커널 내부)        |
| 처리 위치       | 사용자 공간 ↔ 커널 반복 전환         | 커널 내부에서 즉시 결정                  |
| 성능 특성       | Pod/Service 수가 많을수록 느려짐   | 수만 개 Service도 일정한 성능 (O(1))    |
| 재시작 복원      | 노드 리부팅 시 규칙 재적용 필요        | eBPF 맵이 자동 복원                  |
| 관찰 기능       | 제한적 (conntrack, logs)     | Hubble + Envoy 기반 L3~L7 가시성 제공 |

### ⚙️ 동작 구조

```

[Pod A] → [Kernel eBPF Hook] → [Cilium BPF LB Map] → [Pod B]
                   │
                   └─▶ [Envoy Proxy: L7 Policy / Metrics]

```
- *** L3/L4 트래픽*** : eBPF가 커널 내에서 직접 라우팅

- ***L7 트래픽(HTTP/gRPC 등) ***: Envoy Proxy가 분석 및 정책 적용

- ***Hubble : 이 모든 흐름을 실시간으로 관찰, 시각화***
---

### 💪 Cilium 도입 후 달라진 점

| 구분      | Before (kube-proxy)   | After (Cilium + eBPF)            |
| ------- | --------------------- | -------------------------------- |
| 트래픽 처리  | iptables 규칙 기반, 성능 저하 | 커널 내부 eBPF, 빠른 라우팅               |
| 구성 요소   | kube-proxy 필수         | kube-proxy 제거 가능                 |
| 정책 제어   | L3/L4 수준              | L3~L7 세밀한 정책 가능 (HTTP, DNS 등)    |
| 가시성     | 제한적 로그 중심             | Hubble/Envoy로 실시간 트래픽 분석         |
| 장애 복원   | 규칙 재적용 필요             | eBPF 맵 자동 복원                     |
| Mesh 연동 | Istio 필요              | Cilium Service Mesh (사이드카 없음) 가능 |
---
### 도입 효과 정리
1. 성능 향상 — eBPF로 커널 내에서 바로 패킷 처리 → \
iptables 체인 탐색 소모 제거, CPU 사용률 감소

2. 보안 강화 — HTTP Method, Path, DNS 이름 단위 정책 가능

3. 가시성 확보 — Hubble로 Pod 간 트래픽 흐름, L7 요청까지 실시간 추적

4. 운영 단순화 — kube-proxy 제거, 정책·라우팅 일원화

5. 확장성 확보 — Service Mesh 없이 L7 정책/가시성까지 통합 가능

---
>이번 설정을 통해 KubeProxyReplacement: True 로 확인되었고, \
실제 클러스터의 Service 트래픽은 kube-proxy가 아닌 Cilium eBPF가 처리하고 있다.

>결과적으로 iptables의 복잡한 체인을 모두 걷어내고, \
커널 수준에서 트래픽을 즉시 라우팅하는 고성능 네트워킹 구조로 개선되었다. \
또한 Envoy와 Hubble을 통한 L7 가시성과 세밀한 정책 제어로 \
운영, 보안, 관찰성 모두 한 단계 업그레이드된 환경을 구축할 수 있었다.