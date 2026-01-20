# Dashboard 메트릭 표시 문제 해결 및 개선 가이드

> "An unexpected error" 및 "No data" 문제 해결 + 문제 즉시 감지 방법

---

## 🚨 현재 발견된 문제

### 1. Nginx Exporter 연결 실패

**증상:**
```
Dashboard 패널:
- WEB Status: "An unexpected error happened"
- Total HTTP Request Rate: "No data"
- Nginx 관련 모든 메트릭 없음
```

**근본 원인:**
```bash
# nginx-exporter 로그
Error getting stats: dial tcp 127.0.0.1:80: connection refused
```

**문제 분석:**
- nginx-exporter가 `localhost:80/nginx_status`에 접근 시도
- 하지만 nginx는 다른 Pod(web pod)에서 실행 중
- DaemonSet으로 배포된 exporter는 nginx Pod와 같은 네트워크 namespace가 아님
- **결과:** Nginx 메트릭 수집 실패

---

## 🔧 해결 방법

### 방법 1: Nginx Exporter를 Sidecar로 재배포 (권장)

nginx-exporter를 web Pod의 sidecar container로 추가하여 localhost 접근 가능하게 합니다.

**장점:**
- ✅ localhost로 nginx 접근 가능
- ✅ 설정 간단
- ✅ 각 web Pod마다 메트릭 수집

#### 1-1. Web Deployment 수정

```yaml
# web deployment에 sidecar 추가
apiVersion: apps/v1
kind: Deployment
metadata:
  name: web
  namespace: blog-system
spec:
  template:
    spec:
      containers:
      - name: nginx
        image: your-nginx-image
        ports:
        - containerPort: 80
        # ... 기존 설정 ...

      # Nginx Exporter Sidecar 추가 ⭐
      - name: nginx-exporter
        image: nginx/nginx-prometheus-exporter:latest
        args:
          - --nginx.scrape-uri=http://localhost:80/nginx_status
        ports:
        - containerPort: 9113
          name: metrics
```

#### 1-2. Web Service에 Metrics Port 추가

```yaml
apiVersion: v1
kind: Service
metadata:
  name: web-service
  namespace: blog-system
  annotations:
    prometheus.io/scrape: "true"
    prometheus.io/port: "9113"
    prometheus.io/path: "/metrics"
spec:
  selector:
    app: web
  ports:
  - name: http
    port: 80
    targetPort: 80
  - name: metrics  # 추가
    port: 9113
    targetPort: 9113
```

#### 1-3. Nginx에 stub_status 활성화

Nginx 설정 파일에 추가:

```nginx
# /etc/nginx/nginx.conf 또는 site config
server {
    listen 80;

    # 기존 설정...

    # Stub Status for Prometheus ⭐
    location /nginx_status {
        stub_status on;
        access_log off;
        allow 127.0.0.1;  # localhost만 허용
        deny all;
    }
}
```

#### 1-4. Prometheus Scrape 설정 업데이트

```yaml
# Prometheus ConfigMap 수정
scrape_configs:
  # 기존 nginx-exporter job 제거

  # 새로운 설정 (Kubernetes Service Discovery)
  - job_name: 'web-nginx'
    kubernetes_sd_configs:
      - role: pod
        namespaces:
          names:
            - blog-system
    relabel_configs:
      - source_labels: [__meta_kubernetes_pod_label_app]
        action: keep
        regex: web
      - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_scrape]
        action: keep
        regex: true
      - source_labels: [__address__, __meta_kubernetes_pod_annotation_prometheus_io_port]
        action: replace
        regex: ([^:]+)(?::\\d+)?;(\\d+)
        replacement: $1:$2
        target_label: __address__
      - source_labels: [__meta_kubernetes_pod_name]
        target_label: pod
      - source_labels: [__meta_kubernetes_namespace]
        target_label: namespace
```

---

### 방법 2: 기존 DaemonSet 설정 수정 (간단)

nginx-exporter가 web service를 통해 nginx에 접근하도록 수정합니다.

**단점:**
- ❌ 각 web Pod별 메트릭 구분 어려움
- ❌ Service를 통한 접근이라 약간의 오버헤드

```yaml
# nginx-exporter DaemonSet 수정
args:
  - --nginx.scrape-uri=http://web-service.blog-system.svc.cluster.local:80/nginx_status
```

---

## 🎯 문제 즉시 감지: Alert 설정

### Alert Rule 생성

```yaml
# alert-rules.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: prometheus-alert-rules
  namespace: monitoring
data:
  alert-rules.yml: |
    groups:
      # 📊 서비스 다운 감지
      - name: service_availability
        interval: 30s
        rules:
          - alert: WebPodDown
            expr: up{job="web-nginx"} == 0
            for: 1m
            labels:
              severity: critical
              component: web
            annotations:
              summary: "WEB Pod가 다운되었습니다"
              description: "{{ $labels.pod }}가 1분 이상 응답하지 않습니다"

          - alert: WASPodDown
            expr: up{job="kubernetes-pods",namespace="blog-system",app="was"} == 0
            for: 1m
            labels:
              severity: critical
              component: was
            annotations:
              summary: "WAS Pod가 다운되었습니다"
              description: "{{ $labels.kubernetes_pod_name }}가 응답하지 않습니다"

          - alert: MySQLDown
            expr: mysql_up == 0
            for: 1m
            labels:
              severity: critical
              component: mysql
            annotations:
              summary: "MySQL이 다운되었습니다"
              description: "MySQL 데이터베이스가 응답하지 않습니다"

      # 🔥 성능 문제 감지
      - name: performance_issues
        interval: 1m
        rules:
          - alert: HighCPUUsage
            expr: |
              sum(rate(container_cpu_usage_seconds_total{namespace="blog-system",pod=~"web-.*|was-.*"}[5m])) by (pod)
              /
              sum(container_spec_cpu_quota{namespace="blog-system",pod=~"web-.*|was-.*"} / 100000) by (pod)
              > 0.8
            for: 5m
            labels:
              severity: warning
              component: resource
            annotations:
              summary: "Pod CPU 사용률 80% 초과"
              description: "{{ $labels.pod }} CPU 사용률이 5분간 80% 이상입니다"

          - alert: HighMemoryUsage
            expr: |
              sum(container_memory_working_set_bytes{namespace="blog-system",pod=~"web-.*|was-.*"}) by (pod)
              /
              sum(container_spec_memory_limit_bytes{namespace="blog-system",pod=~"web-.*|was-.*"}) by (pod)
              > 0.8
            for: 5m
            labels:
              severity: warning
              component: resource
            annotations:
              summary: "Pod Memory 사용률 80% 초과"
              description: "{{ $labels.pod }} 메모리 사용률이 80% 이상입니다"

          - alert: MySQLSlowQueries
            expr: rate(mysql_global_status_slow_queries[5m]) > 10
            for: 5m
            labels:
              severity: warning
              component: mysql
            annotations:
              summary: "MySQL Slow Query 급증"
              description: "초당 {{ $value }}개의 slow query가 발생하고 있습니다"

      # 🔄 Pod Restart 감지
      - name: pod_issues
        interval: 1m
        rules:
          - alert: PodFrequentlyRestarting
            expr: |
              rate(kube_pod_container_status_restarts_total{namespace="blog-system"}[15m]) > 0
            for: 5m
            labels:
              severity: warning
              component: stability
            annotations:
              summary: "Pod가 자주 재시작됩니다"
              description: "{{ $labels.pod }}가 15분 내에 재시작되었습니다"

          - alert: PodCrashLooping
            expr: |
              kube_pod_container_status_waiting_reason{namespace="blog-system",reason="CrashLoopBackOff"} == 1
            for: 2m
            labels:
              severity: critical
              component: stability
            annotations:
              summary: "Pod가 CrashLoopBackOff 상태입니다"
              description: "{{ $labels.pod }}가 반복적으로 실패하고 있습니다"

      # 📈 트래픽 이상 감지
      - name: traffic_anomalies
        interval: 1m
        rules:
          - alert: NoIncomingTraffic
            expr: rate(nginx_http_requests_total[5m]) == 0
            for: 10m
            labels:
              severity: warning
              component: traffic
            annotations:
              summary: "Nginx에 트래픽이 없습니다"
              description: "10분간 HTTP 요청이 없습니다. 서비스 확인이 필요합니다"

          - alert: HighErrorRate
            expr: |
              rate(nginx_http_requests_total{status=~"5.."}[5m])
              /
              rate(nginx_http_requests_total[5m])
              > 0.05
            for: 5m
            labels:
              severity: critical
              component: traffic
            annotations:
              summary: "HTTP 5xx 에러율 5% 초과"
              description: "에러율: {{ $value | humanizePercentage }}"
```

### Alert Rule 적용

```bash
# ConfigMap 생성
kubectl apply -f alert-rules.yaml

# Prometheus ConfigMap에 rule 파일 경로 추가
kubectl edit configmap -n monitoring prometheus-config
```

Prometheus 설정에 추가:
```yaml
rule_files:
  - '/etc/prometheus/rules/*.yml'
```

Prometheus deployment에 마운트 추가:
```bash
kubectl patch deployment -n monitoring prometheus --type='json' -p='[
  {
    "op": "add",
    "path": "/spec/template/spec/volumes/-",
    "value": {
      "name": "alert-rules",
      "configMap": {"name": "prometheus-alert-rules"}
    }
  },
  {
    "op": "add",
    "path": "/spec/template/spec/containers/0/volumeMounts/-",
    "value": {
      "name": "alert-rules",
      "mountPath": "/etc/prometheus/rules"
    }
  }
]'
```

---

## 📊 Dashboard 개선: 한눈에 보기

### 개선 포인트

#### 1. **Status 패널 쿼리 수정**

현재 에러가 나는 쿼리를 다음으로 변경:

**WEB Status:**
```promql
# 기존 (오류)
up{component="web"}

# 수정 (작동)
up{job="web-nginx"} or on() vector(0)
```

**WAS Status:**
```promql
# 수정
max(kube_pod_status_phase{namespace="blog-system",pod=~"was-.*",phase="Running"}) or on() vector(0)
```

**MySQL Status:**
```promql
# 수정
mysql_up or on() vector(0)
```

#### 2. **색상 코드 기반 Status Panel**

Grafana에서 Stat 패널 사용:
- 🟢 **초록색 (1)**: 정상
- 🔴 **빨간색 (0)**: 다운
- ⚠️ **노란색 (0.5)**: 경고

**Value Mapping 설정:**
```
1 → "✅ Running" (Green)
0 → "❌ Down" (Red)
0.5 → "⚠️ Degraded" (Yellow)
```

#### 3. **Alert Panel 추가**

Dashboard 최상단에 Alert 요약 패널 추가:

```promql
# Active Alerts 수
count(ALERTS{alertstate="firing"})

# Critical Alerts
count(ALERTS{alertstate="firing",severity="critical"})
```

#### 4. **Pod Restart Counter 추가**

최근 1시간 내 재시작 횟수:

```promql
sum(increase(kube_pod_container_status_restarts_total{namespace="blog-system"}[1h])) by (pod)
```

#### 5. **Log Panel 추가 (Loki)**

에러 로그를 Dashboard에 직접 표시:

```logql
{namespace="blog-system"} |= "error" or "ERROR" or "exception"
```

---

## 🎨 Dashboard 레이아웃 권장 구조

```
┌─────────────────────────────────────────────────────────┐
│ 🚨 ALERTS (빨간색으로 강조)                                │
│ Critical: 2 | Warning: 5 | Info: 1                      │
└─────────────────────────────────────────────────────────┘

┌──────────────┬──────────────┬──────────────┬────────────┐
│ 🌐 WEB       │ ⚙️ WAS       │ 🗄️ MySQL     │ 🔄 Restarts│
│ ✅ Running   │ ✅ Running   │ ✅ Running   │ 2          │
│ 2/2 Pods     │ 2/4 Pods     │ 1/1 Pods     │ (1h)       │
└──────────────┴──────────────┴──────────────┴────────────┘

┌──────────────────────────────┬────────────────────────────┐
│ 📊 HTTP Request Rate          │ 📈 MySQL Query Rate        │
│ [그래프]                       │ [그래프]                    │
└──────────────────────────────┴────────────────────────────┘

┌──────────────────────────────┬────────────────────────────┐
│ 💻 CPU Usage                  │ 🧠 Memory Usage            │
│ [컴포넌트별 스택 그래프]          │ [컴포넌트별 스택 그래프]       │
└──────────────────────────────┴────────────────────────────┘

┌──────────────────────────────────────────────────────────┐
│ 📋 Recent Error Logs (Loki)                               │
│ [로그 테이블 - 최근 20줄]                                   │
└──────────────────────────────────────────────────────────┘
```

---

## ⚡ 빠른 문제 확인 방법

### 1. Dashboard에서 Alert 패널 클릭
- Firing alerts 목록 확인
- 심각도(Critical/Warning) 구분

### 2. Status 패널 색상 확인
- 🔴 빨간색 → 즉시 해당 서비스 로그 확인
- ⚠️ 노란색 → 성능 지표 확인

### 3. Log Panel에서 에러 검색
- Exception, Error 키워드 자동 필터링
- 클릭하면 전체 로그 컨텍스트 표시

### 4. Drill-down 링크 추가

각 패널에 링크 추가:
```
WEB Status 클릭 → Nginx Detailed Dashboard
WAS Status 클릭 → WAS Detailed Dashboard
MySQL Status 클릭 → MySQL Detailed Dashboard
```

---

## 🔧 적용 순서

### 1단계: Nginx 연결 수정 (15분)
```bash
# Web deployment에 sidecar 추가
# Nginx에 stub_status 활성화
# Prometheus scrape 설정 업데이트
```

### 2단계: Alert Rule 생성 (10분)
```bash
kubectl apply -f alert-rules.yaml
# Prometheus 재시작
```

### 3단계: Dashboard 쿼리 수정 (20분)
```
# Grafana UI에서 각 패널 수정
# Status 패널: 쿼리 + Value Mapping
# Alert 패널 추가
# Log 패널 추가
```

### 4단계: 테스트 (10분)
```bash
# Pod 강제 종료하여 Alert 발생 테스트
kubectl delete pod -n blog-system was-xxx

# Dashboard에서 Alert 표시 확인
# Alert 복구 확인
```

---

## 📝 체크리스트

### Nginx Exporter 수정
- [ ] Web deployment에 sidecar 추가
- [ ] Nginx stub_status 활성화
- [ ] Service에 metrics port 추가
- [ ] Prometheus scrape 설정 업데이트
- [ ] Prometheus 재시작
- [ ] nginx_up 메트릭 확인

### Alert 설정
- [ ] Alert rules ConfigMap 생성
- [ ] Prometheus rule_files 설정
- [ ] Prometheus에 ConfigMap 마운트
- [ ] Alert 발생 테스트
- [ ] Alert 복구 테스트

### Dashboard 개선
- [ ] Status 패널 쿼리 수정
- [ ] Value Mapping 설정
- [ ] Alert 요약 패널 추가
- [ ] Log 패널 추가 (Loki)
- [ ] Drill-down 링크 추가
- [ ] Dashboard 저장

---

## 🎓 핵심 요약

### 문제 원인
1. **Nginx Exporter**: localhost 접근 불가 (다른 Pod)
2. **Dashboard 쿼리**: 잘못된 label/metric 사용
3. **Alert 없음**: 문제 발생해도 모름

### 해결 방법
1. **Sidecar 패턴**: Exporter를 같은 Pod에 배치
2. **올바른 쿼리**: 실제 수집되는 메트릭 사용
3. **Alert 설정**: 문제 즉시 감지

### 개선 효과
- ✅ 모든 메트릭 정상 수집
- ✅ Alert로 문제 즉시 인지
- ✅ Dashboard 한눈에 파악
- ✅ Log 통합 조회

---

**다음 작업이 필요하시면 말씀해주세요!**
