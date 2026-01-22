---
title: "Cilium eBPF로 kube-proxy 대체하기: 성능 최적화 가이드"
date: 2026-01-22
description: "kube-proxy를 Cilium eBPF로 대체하여 Kubernetes 네트워크 성능을 30-40% 향상시키는 방법"
tags: ["cilium", "ebpf", "kubernetes", "kube-proxy", "performance"]
categories: ["study"]
---

## 개요

이 글은 실제 로컬 Kubernetes 클러스터에서 **kube-proxy 대체** 옵션을 검토한 경험을 정리한 것입니다.

**실제 환경:**
- 클러스터: local-k8s (3 노드)
- Cilium 버전: v1.18.4
- kube-proxy: Running (3 pods)
- kube-proxy-replacement: **false** (비활성화)

---

## kube-proxy 대체란?

Cilium이 kube-proxy의 역할을 eBPF로 대체하여 성능을 향상시키는 기능입니다.

### 비교표

| 항목 | kube-proxy (현재) | Cilium eBPF (대체 시) |
|------|------------------|----------------------|
| **구현** | iptables 규칙 | eBPF 프로그램 |
| **성능** | 보통 | **30-40% 빠름** |
| **Latency** | 보통 | **30% 감소** |
| **CPU 사용량** | 보통 | **낮음** |
| **Service 타입** | ClusterIP, NodePort, LoadBalancer | 모두 지원 + DSR |
| **복잡도** | 낮음 | 중간 |
| **안정성** | 매우 높음 | 높음 (프로덕션 검증됨) |

---

## 장점

### 1. 성능 향상
- Throughput: 30-40% 증가
- Latency: 30% 감소
- CPU 사용량 감소

### 2. iptables 규칙 제거
- 수천 개의 iptables 규칙 → eBPF 프로그램
- iptables chain 순회 오버헤드 제거

### 3. DSR (Direct Server Return)
- LoadBalancer에서 응답 패킷이 바로 클라이언트로 전송
- ALB/NLB 성능 향상

### 4. kube-proxy Pod 제거
- 리소스 절약 (3 pods × CPU/Memory)

---

## 단점

### 1. 복잡도 증가
- iptables → eBPF (디버깅 어려움)
- 트러블슈팅 시 eBPF 지식 필요

### 2. 호환성 문제 가능성
- 일부 특수한 네트워크 설정과 충돌 가능
- ExternalTrafficPolicy: Local 등 일부 기능 제약

### 3. 롤백 어려움
- 활성화 후 문제 발생 시 롤백 복잡
- 서비스 중단 가능

### 4. 로컬 클러스터 특성
- 프로덕션이 아닌 실험 환경
- 성능보다 안정성이 중요할 수 있음

---

## 현재 선택: 유지 (Option 1)

**로컬 클러스터 환경:**
- 3노드 클러스터 (homelab)
- 실험 및 학습 목적
- 프로덕션 트래픽 없음

**선택 이유:**
- kube-proxy는 안정적으로 작동 중
- Hubble UI/Relay로 충분한 개선 완료
- 불필요한 리스크 회피

---

## 적용 방법 (참고용)

실제 적용이 필요할 경우 아래 절차를 따릅니다:

### 1. 백업
```bash
# 현재 Cilium 설정 백업
helm get values cilium -n kube-system > cilium-values-backup.yaml
```

### 2. kube-proxy 대체 활성화
```bash
helm upgrade cilium cilium/cilium --version 1.18.4 \
  --namespace kube-system \
  --reuse-values \
  --set kubeProxyReplacement=true \
  --set k8sServiceHost=192.168.1.187 \
  --set k8sServicePort=6443
```

### 3. kube-proxy 중지
```bash
kubectl delete ds kube-proxy -n kube-system
```

### 4. 검증
```bash
# Service 접근 테스트
kubectl get svc -A
kubectl run test --image=nginx --port=80
kubectl expose pod test --port=80 --type=NodePort
curl <NodeIP>:<NodePort>
```

### 5. 문제 발생 시 롤백
```bash
helm upgrade cilium cilium/cilium --version 1.18.4 \
  --namespace kube-system \
  --reuse-values \
  --set kubeProxyReplacement=false

# kube-proxy 재시작
kubectl apply -f /etc/kubernetes/manifests/kube-proxy.yaml
```

---

## 프로덕션 도입 시 고려사항

1. **Canary 배포**
   - 일부 노드에서만 먼저 테스트
   - 트래픽 일부만 전환

2. **충분한 테스트**
   - LoadBalancer, NodePort, ClusterIP 모두 테스트
   - ExternalTrafficPolicy, Session Affinity 테스트

3. **모니터링 강화**
   - Cilium 메트릭 모니터링
   - Service 응답 시간 추적

4. **롤백 계획**
   - 명확한 롤백 절차
   - 긴급 상황 대응 계획

---

## 결론

| 항목 | 선택 |
|------|------|
| **로컬/학습 환경** | 현재 상태 유지 (kube-proxy 사용) |
| **프로덕션 환경** | 충분한 테스트 후 단계적 적용 |

현재 로컬 클러스터에서는 kube-proxy를 유지하고, Hubble UI/Relay로 관측성을 확보하는 것이 적합한 선택입니다.

---

## 참고 자료

- [Cilium kube-proxy Replacement](https://docs.cilium.io/en/stable/network/kubernetes/kubeproxy-free/)
- 내부 문서: `docs/cilium/CILIUM-IMPROVEMENT-COMPLETE.md`
