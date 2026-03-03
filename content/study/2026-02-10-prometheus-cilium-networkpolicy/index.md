---
title: "Prometheus Targets DOWN 문제 해결: Cilium NetworkPolicy Cross-Namespace 이슈"
date: 2026-02-10T15:30:00+09:00
draft: false
categories:
  - study
  - Troubleshooting
tags:
  - Prometheus
  - Cilium
  - NetworkPolicy
  - Istio
  - Kubernetes
  - Monitoring
---

Prometheus가 Istio Proxy 메트릭을 수집하지 못하는 문제가 발생했다. Targets 페이지를 확인하니 7개 중 4개가 DOWN 상태였다. "context deadline exceeded" 에러와 함께 blog-system namespace의 Pod들이 모두 실패하고 있었다.

## 문제 상황

새로운 PodMonitor를 생성하여 Istio Proxy 메트릭(15090 포트)을 수집하려 했으나, Prometheus Targets에서 다음과 같은 상태가 확인되었다:

- ✅ istio-ingressgateway (istio-system namespace): UP
- ❌ was pods (blog-system namespace): DOWN
- ❌ web pods (blog-system namespace): DOWN

에러 메시지는 "context deadline exceeded"로, 타임아웃이 발생하고 있었다.

## 증상 분석

### 1. Pod 자체는 정상

```bash
kubectl exec was-56975b7586-r8tr5 -n blog-system -c istio-proxy -- netstat -tlnp | grep 15090
# 출력: tcp 0 0 0.0.0.0:15090 0.0.0.0:* LISTEN 13/envoy
```

Istio Proxy는 15090 포트에서 정상적으로 리스닝하고 있었다. Pod 내부에서 직접 curl 요청 시 2ms 만에 응답이 왔다.

### 2. 하지만 Prometheus에서는 타임아웃

Prometheus Pod(monitoring namespace)에서 was pod(blog-system namespace)의 15090 포트로 접근 시 타임아웃이 발생했다:

```bash
kubectl exec prometheus-kube-prometheus-stack-prometheus-0 -n monitoring -- \
  wget -O- http://10.0.1.194:15090/stats/prometheus
# 결과: Connecting to 10.0.1.194:15090 ... Terminated
```

### 3. 흥미로운 패턴 발견

- ✅ Prometheus → istio-system namespace pods: 성공
- ❌ Prometheus → blog-system namespace pods: 실패

**Namespace 차이**가 핵심이었다.

## 원인 분석

### 계층별 진단

#### 1. Istio AuthorizationPolicy 확인

```yaml
# was-authz.yaml
- to:
  - operation:
      ports: ["15090"]
```

15090 포트는 허용되어 있었다.

#### 2. Cilium NetworkPolicy 확인

```yaml
# was-isolation
ingress:
  - fromEndpoints:
    - {}  # 모든 endpoint 허용?
    toPorts:
    - ports:
      - port: "15090"
```

**문제 발견!** `fromEndpoints: - {}`는 "모든 endpoint"가 아니라 **"같은 namespace의 모든 endpoint"**만 허용한다.

즉:
- ✅ blog-system namespace → blog-system pods: 허용
- ❌ monitoring namespace (Prometheus) → blog-system pods: **차단!**

### Cilium의 fromEndpoints vs fromEntities

Cilium NetworkPolicy는 두 가지 방식으로 source를 지정할 수 있다:

#### fromEndpoints (Namespace-scoped)

```yaml
fromEndpoints:
  - {}  # 같은 namespace만!
  - matchLabels:
      app: prometheus
      io.kubernetes.pod.namespace: monitoring  # 명시적 지정
```

#### fromEntities (Cluster-wide)

```yaml
fromEntities:
  - cluster  # 클러스터 전체 허용
  - world    # 외부 트래픽 허용
```

15090 포트는 메트릭 전용이므로 `fromEntities: cluster`가 적절하다.

## 해결 과정

### 1. Cilium NetworkPolicy 수정

```yaml
# was-isolation, web-isolation
ingress:
  # Rule 3: Istio sidecar 메트릭/헬스체크 (클러스터 전체 허용)
  # Why: Prometheus가 monitoring namespace에서 15090 포트 스크랩
  - fromEntities:
    - cluster  # 클러스터 내부 모든 namespace 허용
    toPorts:
    - ports:
      - port: "15090"  # Istio Proxy 메트릭
        protocol: TCP
      - port: "15021"  # Istio health check
        protocol: TCP
```

**변경 사항:**
- `fromEndpoints: - {}` → `fromEntities: - cluster`

### 2. Git Commit & ArgoCD Sync

```bash
git add services/blog-system/common/cilium-netpol.yaml
git commit -m "Fix Cilium NetworkPolicy to allow Prometheus cross-namespace scraping"
git push
```

ArgoCD가 자동으로 sync하여 Cilium NetworkPolicy를 업데이트했다.

### 3. 결과 확인

30초 후 Prometheus Targets를 확인하니 모든 blog-system pods가 UP 상태로 변경되었다:

```
✅ 7/7 Targets UP
- istio-ingressgateway
- was-56975b7586-r8tr5
- was-56975b7586-wmjxb
- web-d96db7f88-6hnhm
- web-d96db7f88-gfcn4
```

메트릭도 정상적으로 수집되기 시작했다:

```promql
istio_requests_total{destination_service_namespace="blog-system"}
# 결과: 8개 메트릭 반환
```

## 근본 원인

Cilium NetworkPolicy의 `fromEndpoints: - {}`는 **같은 namespace 내부만 허용**한다. Cross-namespace 통신이 필요한 경우 다음 중 하나를 사용해야 한다:

1. **fromEntities: cluster** (클러스터 전체 허용)
2. **fromEndpoints with namespace label** (특정 namespace 지정)

```yaml
# Option 1: 클러스터 전체 허용 (메트릭 포트에 적합)
- fromEntities:
  - cluster

# Option 2: 특정 namespace만 허용 (보안 강화)
- fromEndpoints:
  - matchLabels:
      io.kubernetes.pod.namespace: monitoring
```

15090 포트는 메트릭 전용으로 민감 정보가 없으므로, `fromEntities: cluster`가 적절했다.

## 추가로 발견한 문제들

### 1. PodMonitor가 없었음

Istio Proxy 메트릭을 수집하려면 PodMonitor가 필요하다:

```yaml
apiVersion: monitoring.coreos.com/v1
kind: PodMonitor
metadata:
  name: istio-proxies
  namespace: monitoring
  labels:
    release: kube-prometheus-stack  # 필수!
spec:
  selector:
    matchExpressions:
    - key: istio-prometheus-ignore
      operator: DoesNotExist
  podMetricsEndpoints:
  - port: http-envoy-prom
    path: /stats/prometheus
    interval: 15s
  namespaceSelector:
    any: true
```

**중요:** `release: kube-prometheus-stack` label이 없으면 Prometheus가 PodMonitor를 인식하지 못한다.

### 2. Istio AuthorizationPolicy도 수정

처음에는 namespace 기반 제한을 시도했으나:

```yaml
# 실패한 시도
- from:
  - source:
      namespaces: ["monitoring"]
  to:
  - operation:
      ports: ["15090"]
```

**문제:** Prometheus Pod에는 Istio sidecar가 없어서 `source.namespaces` 조건이 작동하지 않는다.

**해결:** `from` 조건 제거 (메트릭 포트는 민감 정보 없음)

```yaml
# 최종 해결
- to:
  - operation:
      ports: ["15090"]
```

## 교훈

### 1. NetworkPolicy 디버깅 순서

```
1. Pod 자체 확인 (netstat, curl localhost)
2. 같은 노드에서 접근 테스트
3. 다른 노드에서 접근 테스트
4. 다른 namespace에서 접근 테스트  ← 여기서 실패!
```

Cross-namespace 통신이 실패하면 NetworkPolicy의 scope를 의심해야 한다.

### 2. Cilium의 fromEndpoints는 Namespace-scoped

`fromEndpoints: - {}`는 "모든 endpoint"가 아니다. **같은 namespace의 모든 endpoint**만 허용한다. 클러스터 전체를 허용하려면 `fromEntities: cluster`를 사용해야 한다.

### 3. 메트릭 포트는 보안 제약 완화 가능

15090 포트는 다음과 같은 이유로 클러스터 전체 허용이 적절하다:

- 메트릭만 노출 (민감 정보 없음)
- Prometheus가 여러 namespace를 스크랩해야 함
- 성능 영향 없음 (read-only)

### 4. Platform Conformity 원칙

Prometheus Operator를 사용할 때는 **표준 label**을 따라야 한다:

```yaml
labels:
  release: kube-prometheus-stack  # Helm Chart 이름
```

이 label이 없으면 `podMonitorSelector`가 PodMonitor를 발견하지 못한다.

## 참고 자료

- [Cilium Network Policies - fromEntities](https://docs.cilium.io/en/stable/policy/language/#entities-based)
- [Prometheus Operator - PodMonitor](https://prometheus-operator.dev/docs/operator/api/#monitoring.coreos.com/v1.PodMonitor)
- [Istio Metrics Configuration](https://istio.io/latest/docs/reference/config/metrics/)

---

**트러블슈팅 소요 시간:** 약 2시간
**핵심 해결 키워드:** fromEntities vs fromEndpoints, cross-namespace communication
