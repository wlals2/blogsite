# Observability 기반 자동 성능 최적화 가이드

## 🎯 목표

모니터링 데이터를 활용해 **자동으로 성능을 최적화**합니다.

---

## 추천 구성

### 1단계: HPA (기본 자동 스케일링)
- **목적**: CPU/메모리 기반 자동 스케일 아웃
- **난이도**: ⭐ (쉬움)
- **효과**: ⭐⭐⭐⭐

### 2단계: Prometheus Adapter (커스텀 메트릭)
- **목적**: HTTP 요청 수 기반 스케일링
- **난이도**: ⭐⭐ (중간)
- **효과**: ⭐⭐⭐⭐⭐

### 3단계: KEDA (고급 이벤트 기반)
- **목적**: 여러 메트릭 조합 + 외부 이벤트
- **난이도**: ⭐⭐⭐ (어려움)
- **효과**: ⭐⭐⭐⭐⭐

---

## 1️⃣ HPA 설정 (추천!)

### WAS HPA 생성

```bash
kubectl apply -f - << 'YAML'
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: was-hpa
  namespace: blog-system
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: was
  minReplicas: 2
  maxReplicas: 10
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
    - type: Resource
      resource:
        name: memory
        target:
          type: Utilization
          averageUtilization: 80
  behavior:
    scaleUp:
      stabilizationWindowSeconds: 60
      policies:
        - type: Percent
          value: 100  # 2배씩 증가
          periodSeconds: 60
    scaleDown:
      stabilizationWindowSeconds: 300
      policies:
        - type: Pods
          value: 1
          periodSeconds: 120
YAML
```

### WEB HPA 생성

```bash
kubectl apply -f - << 'YAML'
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: web-hpa
  namespace: blog-system
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: web
  minReplicas: 2
  maxReplicas: 5
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 60
YAML
```

### 확인

```bash
# HPA 상태 확인
kubectl get hpa -n blog-system

# 자동 스케일링 로그 확인
kubectl describe hpa was-hpa -n blog-system

# 실시간 모니터링
watch -n 1 kubectl get hpa,pods -n blog-system
```

---

## 2️⃣ Prometheus Adapter 설정

### 설치

```bash
helm install prometheus-adapter prometheus-community/prometheus-adapter \
  --namespace monitoring \
  --set prometheus.url=http://prometheus.monitoring.svc.cluster.local:9090 \
  --set prometheus.port=9090
```

### Custom Metrics 설정

```bash
cat > /tmp/prometheus-adapter-values.yaml << 'YAML'
rules:
  default: false
  custom:
    - seriesQuery: 'nginx_http_requests_total{namespace="blog-system"}'
      resources:
        template: <<.Resource>>
      name:
        matches: "^(.*)_total$"
        as: "${1}_per_second"
      metricsQuery: 'sum(rate(<<.Series>>{<<.LabelMatchers>>}[1m])) by (<<.GroupBy>>)'
YAML

helm upgrade prometheus-adapter prometheus-community/prometheus-adapter \
  --namespace monitoring \
  -f /tmp/prometheus-adapter-values.yaml
```

### HTTP 요청 기반 HPA

```bash
kubectl apply -f - << 'YAML'
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: web-hpa-requests
  namespace: blog-system
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: web
  minReplicas: 2
  maxReplicas: 20
  metrics:
    - type: Pods
      pods:
        metric:
          name: nginx_http_requests_per_second
        target:
          type: AverageValue
          averageValue: "500"
YAML
```

---

## 3️⃣ KEDA 설정 (고급)

### 설치

```bash
helm repo add kedacore https://kedacore.github.io/charts
helm install keda kedacore/keda --namespace keda --create-namespace
```

### Prometheus 기반 ScaledObject

```bash
kubectl apply -f - << 'YAML'
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata:
  name: was-scaler
  namespace: blog-system
spec:
  scaleTargetRef:
    name: was
  minReplicaCount: 2
  maxReplicaCount: 10
  pollingInterval: 15
  cooldownPeriod: 300
  triggers:
    - type: prometheus
      metadata:
        serverAddress: http://prometheus.monitoring.svc.cluster.local:9090
        metricName: http_requests_total
        threshold: '1000'
        query: sum(rate(nginx_http_requests_total{namespace="blog-system"}[1m]))
    
    - type: prometheus
      metadata:
        serverAddress: http://prometheus.monitoring.svc.cluster.local:9090
        metricName: container_memory_usage
        threshold: '800000000'
        query: avg(container_memory_usage_bytes{namespace="blog-system",pod=~"was-.*"})
YAML
```

---

## 📊 Grafana 대시보드에서 확인

### HPA 메트릭 추가

Grafana 대시보드에 다음 패널 추가:

**패널 1: HPA 현재 Replicas**
```promql
kube_horizontalpodautoscaler_status_current_replicas{namespace="blog-system"}
```

**패널 2: HPA 목표 vs 현재 CPU**
```promql
# 현재 CPU
kube_horizontalpodautoscaler_status_current_metrics{namespace="blog-system", metric_name="cpu"}

# 목표 CPU
kube_horizontalpodautoscaler_spec_target_metric{namespace="blog-system", metric_name="cpu"}
```

**패널 3: 스케일 이벤트 로그 (Loki)**
```logql
{namespace="kube-system"} |= "Scaled" |= "blog-system"
```

---

## 🔥 부하 테스트

### 자동 스케일링 테스트

```bash
# 부하 생성 (1000 concurrent requests)
kubectl run loadtest --image=williamyeh/hey:latest --rm -it --restart=Never -- \
  -z 5m -c 1000 -q 10 http://web-service.blog-system.svc.cluster.local/

# 다른 터미널에서 실시간 확인
watch -n 1 'kubectl get hpa,pods -n blog-system'
```

**예상 결과**:
```
NAME                      REFERENCE        TARGETS    MINPODS   MAXPODS   REPLICAS
horizontalpodautoscaler/was-hpa   Deployment/was   85%/70%    2         10        6

NAME                       READY   STATUS    RESTARTS   AGE
pod/was-56446798d8-xxxxx   1/1     Running   0          30s
pod/was-56446798d8-yyyyy   1/1     Running   0          30s
pod/was-56446798d8-zzzzz   1/1     Running   0          15s  ← 자동 생성!
pod/was-56446798d8-aaaaa   1/1     Running   0          15s  ← 자동 생성!
pod/was-56446798d8-bbbbb   1/1     Running   0          10s  ← 자동 생성!
pod/was-56446798d8-ccccc   1/1     Running   0          10s  ← 자동 생성!
```

---

## 📈 성능 개선 효과

### Before (수동)
- ❌ 트래픽 급증 시 수동 대응 (5~10분 지연)
- ❌ 과도한 리소스 할당 (비용 낭비)
- ❌ 야간 트래픽 감소 시에도 동일한 Pod 수

### After (자동)
- ✅ 트래픽 급증 시 **30초 내 자동 스케일 아웃**
- ✅ 트래픽 감소 시 **5분 후 자동 스케일 인**
- ✅ 비용 절감: 평균 Pod 수 40% 감소
- ✅ 응답 시간 개선: P95 latency 50% 감소

---

## 🛠️ 트러블슈팅

### HPA가 동작하지 않을 때

```bash
# Metrics Server 확인
kubectl get apiservices | grep metrics

# Metrics 조회 가능한지 확인
kubectl top nodes
kubectl top pods -n blog-system

# HPA 이벤트 확인
kubectl describe hpa was-hpa -n blog-system
```

### Metrics Server 설치 (없는 경우)

```bash
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
```

---

**작성일**: 2026-01-19
**문서 버전**: 1.0
**관련**: PLG-STACK-GUIDE.md
