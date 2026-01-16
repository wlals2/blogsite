---
title: "Cilium 실전 활용 가이드"
date: 2026-01-16T11:00:00+09:00
draft: false
tags: ["Kubernetes", "Cilium", "eBPF", "Networking", "CNI", "kube-proxy"]
categories: ["Docs", "Networking"]
description: "Cilium 1.18.4 기반 - eBPF로 Kubernetes 네트워킹 업그레이드하기"
weight: 1
showToc: true
TocOpen: true
---

# Cilium 실전 활용 가이드

> Cilium 1.18.4 기반 - eBPF로 Kubernetes 네트워킹 업그레이드하기

---

## 현재 상태

```bash
kubectl -n kube-system exec -it $(kubectl get pods -n kube-system -l k8s-app=cilium -o name | head -1) -- cilium status
```

**현재 설정:**
```
Cilium:                  1.18.4
KubeProxyReplacement:    False       # ← kube-proxy 여전히 사용 중
Hubble:                  Ok          # ← 관측성 활성화됨
Routing:                 Tunnel [vxlan]
Encryption:              Disabled    # ← 암호화 비활성화
BandwidthManager:        Disabled
```

---

## 1. Cilium 핵심 기능 활성화

### 1.1 kube-proxy Replacement (eBPF 기반 서비스 로드밸런싱)

**왜 필요한가?**
- kube-proxy는 iptables 규칙 기반 → 성능 병목
- Cilium은 eBPF 기반 → **10배 빠른 서비스 로드밸런싱**
- iptables 규칙 수천 개 → eBPF 프로그램 (메모리 절약)

**현재 상태 확인:**
```bash
kubectl get ds -n kube-system kube-proxy
# NAME         DESIRED   CURRENT   READY   UP-TO-DATE   AVAILABLE
# kube-proxy   3         3         3       3            3
```

**활성화 방법:**

```bash
# 1. Cilium Helm values 수정
helm get values cilium -n kube-system > cilium-values.yaml

# 2. kube-proxy replacement 활성화
cat <<EOF >> cilium-values.yaml
# kube-proxy Replacement (eBPF 기반)
kubeProxyReplacement: true
k8sServiceHost: 192.168.1.187  # API Server IP
k8sServicePort: 6443           # API Server Port

# eBPF Host Routing (더 빠른 성능)
bpf:
  masquerade: true  # SNAT을 eBPF로
  tproxy: true      # Transparent Proxy
EOF

# 3. Cilium 업그레이드
helm upgrade cilium cilium/cilium \
  --namespace kube-system \
  --values cilium-values.yaml

# 4. kube-proxy 삭제
kubectl delete ds kube-proxy -n kube-system
```

**검증:**
```bash
# Cilium 상태 확인
kubectl -n kube-system exec -it $(kubectl get pods -n kube-system -l k8s-app=cilium -o name | head -1) -- cilium status | grep KubeProxyReplacement
# KubeProxyReplacement:    True   [eth0 (Direct Routing)]

# Service 연결 확인
kubectl run test --image=nginx --port=80
kubectl expose pod test --port=80
kubectl run -it --rm debug --image=busybox -- wget -O- test
```

---

### 1.2 Hubble UI (네트워크 관측성 시각화)

**왜 필요한가?**
- Pod 간 트래픽 흐름 시각화
- Network Policy 디버깅
- 보안 이슈 탐지 (비정상 트래픽)

**활성화 방법:**

```bash
# Hubble UI 설치
helm upgrade cilium cilium/cilium \
  --namespace kube-system \
  --reuse-values \
  --set hubble.relay.enabled=true \
  --set hubble.ui.enabled=true

# Hubble UI 접속
kubectl port-forward -n kube-system svc/hubble-ui 12000:80

# 브라우저에서 http://localhost:12000 접속
```

**Hubble CLI 설치:**
```bash
# Hubble CLI 다운로드
HUBBLE_VERSION=$(curl -s https://raw.githubusercontent.com/cilium/hubble/master/stable.txt)
curl -L --remote-name-all https://github.com/cilium/hubble/releases/download/$HUBBLE_VERSION/hubble-linux-amd64.tar.gz{,.sha256sum}
sha256sum --check hubble-linux-amd64.tar.gz.sha256sum
sudo tar xzvfC hubble-linux-amd64.tar.gz /usr/local/bin
rm hubble-linux-amd64.tar.gz{,.sha256sum}

# Hubble 포트포워딩
kubectl port-forward -n kube-system svc/hubble-relay 4245:80 &

# Hubble 사용 예시
hubble status
hubble observe --namespace default
hubble observe --pod my-pod --follow
hubble observe --verdict DROPPED  # 드롭된 패킷 확인
```

---

### 1.3 WireGuard 암호화 (Pod 간 통신 암호화)

**왜 필요한가?**
- Pod 간 통신이 평문으로 전송됨 → 스니핑 가능
- WireGuard로 암호화 → **중간자 공격 방지**
- IPsec보다 빠르고 간단

**활성화 방법:**

```bash
# 1. WireGuard 커널 모듈 확인
lsmod | grep wireguard
# 없으면 설치: sudo apt install wireguard

# 2. Cilium WireGuard 활성화
helm upgrade cilium cilium/cilium \
  --namespace kube-system \
  --reuse-values \
  --set encryption.enabled=true \
  --set encryption.type=wireguard

# 3. 재시작
kubectl rollout restart ds/cilium -n kube-system
```

**검증:**
```bash
# WireGuard 인터페이스 확인
kubectl -n kube-system exec -it $(kubectl get pods -n kube-system -l k8s-app=cilium -o name | head -1) -- cilium status | grep Encryption
# Encryption:              WireGuard   [eth0, NodeEncryption: Disabled]

# WireGuard 터널 확인
kubectl -n kube-system exec -it $(kubectl get pods -n kube-system -l k8s-app=cilium -o name | head -1) -- wg
```

---

## 2. Network Policy 실전 사용

**시나리오:** WAS Pod는 DB Pod에만 접근, WEB Pod는 접근 불가

**1. 기본 Deny All:**
```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: default-deny-all
  namespace: petclinic
spec:
  podSelector: {}
  policyTypes:
  - Ingress
  - Egress
```

**2. WAS → DB 허용:**
```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-was-to-db
  namespace: petclinic
spec:
  podSelector:
    matchLabels:
      app: mysql
  policyTypes:
  - Ingress
  ingress:
  - from:
    - podSelector:
        matchLabels:
          app: was
    ports:
    - protocol: TCP
      port: 3306
```

**3. WEB → WAS 허용:**
```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-web-to-was
  namespace: petclinic
spec:
  podSelector:
    matchLabels:
      app: was
  policyTypes:
  - Ingress
  ingress:
  - from:
    - podSelector:
        matchLabels:
          app: web
    ports:
    - protocol: TCP
      port: 8080
```

**적용 및 확인:**
```bash
kubectl apply -f network-policy.yaml

# Hubble로 확인
hubble observe --namespace petclinic --verdict DROPPED
```

---

## 3. Cilium CLI로 트러블슈팅

**Cilium CLI 설치:**
```bash
CILIUM_CLI_VERSION=$(curl -s https://raw.githubusercontent.com/cilium/cilium-cli/main/stable.txt)
CLI_ARCH=amd64
curl -L --fail --remote-name-all https://github.com/cilium/cilium-cli/releases/download/${CILIUM_CLI_VERSION}/cilium-linux-${CLI_ARCH}.tar.gz{,.sha256sum}
sha256sum --check cilium-linux-${CLI_ARCH}.tar.gz.sha256sum
sudo tar xzvfC cilium-linux-${CLI_ARCH}.tar.gz /usr/local/bin
rm cilium-linux-${CLI_ARCH}.tar.gz{,.sha256sum}
```

**유용한 명령어:**

```bash
# 1. Cilium 상태 확인
cilium status

# 2. Connectivity Test (Pod 간 연결 테스트)
cilium connectivity test

# 3. Node 간 통신 확인
cilium connectivity test --node-to-node

# 4. Network Policy 테스트
cilium connectivity test --include-unsafe-tests

# 5. 특정 Pod 트래픽 확인
cilium hubble observe --pod my-pod --follow

# 6. 서비스 엔드포인트 확인
cilium service list

# 7. Identity 확인 (Network Policy용)
cilium identity list
```

---

## 4. BandwidthManager (Pod 대역폭 제한)

**왜 필요한가?**
- 특정 Pod가 네트워크 대역폭 독점 방지
- QoS (Quality of Service) 보장

**활성화:**
```bash
helm upgrade cilium cilium/cilium \
  --namespace kube-system \
  --reuse-values \
  --set bandwidthManager.enabled=true
```

**사용 예시:**
```yaml
apiVersion: v1
kind: Pod
metadata:
  name: bandwidth-limited
  annotations:
    kubernetes.io/ingress-bandwidth: "10M"  # 10 Mbps
    kubernetes.io/egress-bandwidth: "10M"   # 10 Mbps
spec:
  containers:
  - name: nginx
    image: nginx
```

---

## 5. Service Mesh (Envoy 통합)

**왜 필요한가?**
- L7 트래픽 관리 (HTTP, gRPC)
- Canary, A/B 테스팅
- mTLS (상호 TLS 인증)

**활성화:**
```bash
helm upgrade cilium cilium/cilium \
  --namespace kube-system \
  --reuse-values \
  --set envoy.enabled=true
```

**L7 Network Policy 예시:**
```yaml
apiVersion: "cilium.io/v2"
kind: CiliumNetworkPolicy
metadata:
  name: l7-http-policy
spec:
  endpointSelector:
    matchLabels:
      app: api
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
        - method: "GET"
          path: "/api/.*"
        - method: "POST"
          path: "/api/create"
```

---

## 6. 성능 모니터링

**Prometheus + Grafana 통합:**

```bash
# Cilium Prometheus Metrics 활성화
helm upgrade cilium cilium/cilium \
  --namespace kube-system \
  --reuse-values \
  --set prometheus.enabled=true \
  --set operator.prometheus.enabled=true

# ServiceMonitor 생성 (Prometheus Operator 사용 시)
kubectl apply -f - <<EOF
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: cilium
  namespace: kube-system
spec:
  selector:
    matchLabels:
      k8s-app: cilium
  endpoints:
  - port: prometheus
    interval: 30s
EOF
```

**주요 메트릭:**
- `cilium_datapath_conntrack_gc_runs_total`: Conntrack GC 횟수
- `cilium_drop_count_total`: 드롭된 패킷 수
- `cilium_forward_count_total`: 포워딩된 패킷 수
- `cilium_policy_l7_parse_errors_total`: L7 파싱 에러

---

## 7. 트러블슈팅 시나리오

### 시나리오 1: Pod 간 통신 안 됨

```bash
# 1. Cilium Agent 로그 확인
kubectl -n kube-system logs -l k8s-app=cilium --tail=100

# 2. Endpoint 상태 확인
kubectl -n kube-system exec -it $(kubectl get pods -n kube-system -l k8s-app=cilium -o name | head -1) -- cilium endpoint list

# 3. BPF Map 확인
kubectl -n kube-system exec -it $(kubectl get pods -n kube-system -l k8s-app=cilium -o name | head -1) -- cilium bpf lb list

# 4. Hubble로 패킷 드롭 확인
hubble observe --verdict DROPPED
```

### 시나리오 2: Network Policy가 적용 안 됨

```bash
# 1. Policy 상태 확인
kubectl -n kube-system exec -it $(kubectl get pods -n kube-system -l k8s-app=cilium -o name | head -1) -- cilium policy get

# 2. Endpoint Identity 확인
cilium identity list

# 3. Policy Trace
kubectl -n kube-system exec -it $(kubectl get pods -n kube-system -l k8s-app=cilium -o name | head -1) -- cilium policy trace -s <source-id> -d <dest-id>
```

---

## 8. 롤백 (kube-proxy로 복귀)

**문제 발생 시 롤백 방법:**

```bash
# 1. kube-proxy 재설치
kubectl apply -f https://raw.githubusercontent.com/kubernetes/kubernetes/master/cluster/addons/kube-proxy/kube-proxy.yaml

# 2. Cilium kube-proxy replacement 비활성화
helm upgrade cilium cilium/cilium \
  --namespace kube-system \
  --reuse-values \
  --set kubeProxyReplacement=false

# 3. Cilium 재시작
kubectl rollout restart ds/cilium -n kube-system
```

---

## 9. 다음 단계

| 기능 | 우선순위 | 난이도 | 효과 |
|------|----------|--------|------|
| **kube-proxy Replacement** | P0 (필수) | ⭐⭐⭐ | 성능 10배 향상 |
| **Hubble UI** | P1 (권장) | ⭐ | 관측성 향상 |
| **Network Policy** | P1 (권장) | ⭐⭐ | 보안 강화 |
| **WireGuard 암호화** | P2 (선택) | ⭐⭐ | 암호화 보안 |
| **Service Mesh** | P3 (고급) | ⭐⭐⭐⭐ | L7 트래픽 제어 |

---

## 10. 참고 자료

- [Cilium 공식 문서](https://docs.cilium.io)
- [Hubble 가이드](https://docs.cilium.io/en/stable/gettingstarted/hubble/)
- [Network Policy 예시](https://docs.cilium.io/en/stable/policy/)
- [kube-proxy Replacement 가이드](https://docs.cilium.io/en/stable/network/kubernetes/kubeproxy-free/)

---

**작성일**: 2026-01-13
**Cilium 버전**: 1.18.4
**읽는 시간**: 20분
