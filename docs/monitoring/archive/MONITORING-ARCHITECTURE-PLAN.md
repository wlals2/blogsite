# Blog System 통합 모니터링 아키텍처 설계

> 체계적인 모니터링 시스템 구축을 위한 전체 설계 문서

**작성일:** 2026-01-20
**목적:** 문제 즉시 감지 + 성능 최적화 + 장애 예방

---

## 📋 목차

1. [현재 상태 분석](#1-현재-상태-분석)
2. [모니터링 목표 및 정책](#2-모니터링-목표-및-정책)
3. [아키텍처 설계](#3-아키텍처-설계)
4. [Dashboard 구조](#4-dashboard-구조)
5. [Alert 정책](#5-alert-정책)
6. [구현 로드맵](#6-구현-로드맵)

---

## 1. 현재 상태 분석

### 📊 인프라 현황

```
Blog System (blog-system namespace)
├── WEB (Nginx)
│   ├── Pods: 2/2 Running ✅
│   ├── CPU: 1% (매우 낮음)
│   ├── Memory: 50Mi
│   └── HPA: 2-5 pods (CPU 60% 목표)
│
├── WAS (Spring Boot)
│   ├── Pods: 2/2 Running ⚠️ (0/2 Ready - 문제!)
│   ├── CPU: 191% (목표 70% 초과!) 🔴
│   ├── Memory: 29% (정상)
│   ├── HPA: 2-10 pods (CPU 70%, Memory 80% 목표)
│   └── 문제: Readiness Probe 실패, CPU 과부하
│
└── MySQL
    ├── Pods: 1/1 Running ✅
    ├── CPU: 6m (정상)
    ├── Memory: 393Mi
    └── MySQL Exporter: 0/1 Ready 🔴 (재시작 중)
```

### 🚨 발견된 문제점

#### 1. **WAS Pod - Critical**
```
문제: Running이지만 Ready 아님 (1/2 컨테이너)
영향: 트래픽 받지 못함, Service Endpoint에서 제외됨
원인: Istio sidecar 또는 Readiness Probe 실패
우선순위: 🔴 Critical
```

#### 2. **WAS CPU 과부하 - High**
```
문제: CPU 191% (목표 70%의 2.7배)
영향: 응답 지연, HPA 스케일 아웃 필요
원인: 부하 증가 or 성능 저하
우선순위: 🔴 High
```

#### 3. **MySQL Exporter 재시작 - Medium**
```
문제: 4회 재시작, Ready 아님
영향: MySQL 메트릭 수집 불가
원인: 설정 오류 또는 MySQL 연결 실패
우선순위: 🟡 Medium
```

#### 4. **Nginx Metrics 미수집 - Medium**
```
문제: nginx-exporter가 nginx에 연결 실패
영향: HTTP 트래픽 메트릭 없음
원인: localhost 접근 불가 (다른 Pod)
우선순위: 🟡 Medium
```

---

## 2. 모니터링 목표 및 정책

### 🎯 모니터링 목표

#### Primary Goals (1순위)
1. **장애 즉시 감지**: Pod Down, Service 불가 → 1분 내 Alert
2. **성능 저하 조기 경보**: CPU/Memory 임계값 도달 → 5분 내 Alert
3. **사용자 영향 최소화**: 에러율 증가 → 즉시 Alert

#### Secondary Goals (2순위)
4. **리소스 최적화**: 과다/과소 프로비저닝 감지
5. **트렌드 분석**: 장기 성능 추세 파악
6. **용량 계획**: 성장률 기반 확장 계획

### 📏 모니터링 정책

#### Golden Signals (가장 중요한 4가지 지표)

```
1. Latency (지연시간)
   - WEB: HTTP Response Time < 200ms (P95)
   - WAS: API Response Time < 500ms (P95)
   - MySQL: Query Time < 100ms (P95)

2. Traffic (트래픽)
   - HTTP Requests/sec
   - API Calls/sec
   - DB Queries/sec

3. Errors (에러율)
   - HTTP 5xx < 1%
   - WAS Exception Rate < 0.1%
   - MySQL Connection Errors = 0

4. Saturation (포화도)
   - CPU < 70% (warning), < 85% (critical)
   - Memory < 80% (warning), < 90% (critical)
   - Disk < 80% (warning), < 90% (critical)
```

#### SLI/SLO 정의

| 서비스 | SLI (측정 지표) | SLO (목표) | 측정 방법 |
|--------|----------------|-----------|----------|
| **Web** | HTTP 가용성 | 99.9% | `up{job="web-nginx"} == 1` |
| **Web** | 응답 시간 (P95) | < 200ms | `histogram_quantile(0.95, nginx_http_request_duration_seconds)` |
| **WAS** | API 가용성 | 99.5% | `up{job="was-service"} == 1` |
| **WAS** | 응답 시간 (P95) | < 500ms | Spring Boot Actuator metrics |
| **MySQL** | DB 가용성 | 99.9% | `mysql_up == 1` |
| **MySQL** | 쿼리 시간 (P95) | < 100ms | `mysql_global_status_slow_queries` |

#### Alert Severity 기준

```yaml
🔴 Critical (P1):
  - 서비스 Down (1분 이상)
  - 에러율 > 5%
  - 데이터 손실 위험
  - 응답: 즉시 (24/7)

🟠 High (P2):
  - CPU/Memory > 85%
  - 에러율 1-5%
  - 성능 저하 (P95 > 2x 목표)
  - 응답: 30분 이내

🟡 Warning (P3):
  - CPU/Memory 70-85%
  - Pod 재시작 빈번
  - 디스크 사용률 > 80%
  - 응답: 업무 시간 내

🔵 Info (P4):
  - 배포 알림
  - 스케일링 이벤트
  - 응답: 참고용
```

---

## 3. 아키텍처 설계

### 🏗️ 전체 아키텍처

```
┌─────────────────────────────────────────────────────────────┐
│                    Monitoring Stack                          │
│                                                               │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │   Grafana    │  │  Prometheus  │  │     Loki     │      │
│  │  (시각화)     │  │ (메트릭 수집) │  │  (로그 수집)  │      │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘      │
│         │                  │                  │               │
│         └──────────┬───────┴─────────┬────────┘              │
│                    │                 │                       │
│         ┌──────────▼─────────────────▼───────┐              │
│         │    Alertmanager (알림)              │              │
│         │    - Slack, Email, PagerDuty        │              │
│         └─────────────────────────────────────┘              │
└─────────────────────────────────────────────────────────────┘
                          │
         ┌────────────────┼────────────────┐
         │                │                │
┌────────▼────────┐ ┌────▼───────┐ ┌─────▼──────┐
│   WEB Layer     │ │ WAS Layer  │ │ Data Layer │
├─────────────────┤ ├────────────┤ ├────────────┤
│ Nginx (2 pods)  │ │ Spring Boot│ │ MySQL (1)  │
│                 │ │ (2-10 pods)│ │            │
│ + nginx-exporter│ │ + JMX      │ │ + exporter │
│   (sidecar)     │ │   exporter │ │            │
└─────────────────┘ └────────────┘ └────────────┘
         │                │                │
         └────────────────┼────────────────┘
                          │
                 ┌────────▼────────┐
                 │  Ingress Nginx  │
                 │  + exporter     │
                 └─────────────────┘
```

### 📦 메트릭 수집 전략

#### Layer별 메트릭

**1. Infrastructure Layer (Kubernetes)**
```yaml
Exporter: kube-state-metrics, node-exporter, cadvisor
수집 주기: 15s
메트릭:
  - Pod 상태 (Running, Pending, Failed)
  - Node 리소스 (CPU, Memory, Disk)
  - Container 메트릭
  - HPA 상태
```

**2. Application Layer (Web/WAS)**
```yaml
WEB:
  Exporter: nginx-prometheus-exporter (sidecar)
  수집 주기: 15s
  메트릭:
    - nginx_http_requests_total
    - nginx_http_request_duration_seconds
    - nginx_connections_active
    - nginx_http_response_code

WAS:
  Exporter: Spring Boot Actuator + Prometheus endpoint
  수집 주기: 15s
  메트릭:
    - http_server_requests_seconds (latency)
    - jvm_memory_used_bytes
    - jvm_gc_pause_seconds
    - hikaricp_connections (DB connection pool)
    - application_ready_time
```

**3. Data Layer (MySQL)**
```yaml
Exporter: mysqld-exporter
수집 주기: 15s
메트릭:
  - mysql_up
  - mysql_global_status_connections
  - mysql_global_status_slow_queries
  - mysql_global_status_threads_running
  - mysql_global_status_queries
```

**4. Network Layer (Ingress)**
```yaml
Exporter: ingress-nginx built-in metrics
수집 주기: 15s
메트릭:
  - nginx_ingress_controller_requests
  - nginx_ingress_controller_response_duration_seconds
  - nginx_ingress_controller_request_size
  - nginx_ingress_controller_ssl_expire_time_seconds
```

---

## 4. Dashboard 구조

### 🎨 Dashboard 계층 구조

```
Level 1: Executive Dashboard (경영진/관리자용)
  └─ System Health Overview
     - 전체 시스템 상태 한눈에
     - 핵심 SLO 달성률
     - 활성 Alert 수
     - 비용 효율성

Level 2: Service Dashboard (운영팀용)
  ├─ Web Service Dashboard
  ├─ WAS Service Dashboard
  └─ MySQL Service Dashboard
     각각 Golden Signals + 상세 메트릭

Level 3: Component Dashboard (개발팀용)
  ├─ JVM Monitoring
  ├─ Database Query Analysis
  └─ Network Traffic Analysis
     심층 분석용
```

### 📊 Level 1: System Health Overview (메인)

**레이아웃:**
```
┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓
┃ 🚨 Active Alerts                                      ┃
┃ Critical: 0 | High: 0 | Warning: 2 | Info: 3         ┃
┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛

┏━━━━━━━━━━┳━━━━━━━━━━┳━━━━━━━━━━┳━━━━━━━━━━┳━━━━━━━━━┓
┃ 🌐 WEB    ┃ ⚙️ WAS   ┃ 🗄️ MySQL ┃ 📊 Traffic┃🔄 Uptime┃
┃ ✅ UP     ┃ ⚠️ WARN  ┃ ✅ UP    ┃ 45 req/s  ┃ 99.8%   ┃
┃ 2/2 Pods  ┃ 0/2 Ready┃ 1/1 Pods ┃ ↑ 12%    ┃ (24h)   ┃
┃ CPU: 1%   ┃ CPU: 191%┃ CPU: 6m  ┃ Err: 0.1%┃         ┃
┗━━━━━━━━━━┻━━━━━━━━━━┻━━━━━━━━━━┻━━━━━━━━━━┻━━━━━━━━━┛

┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┳━━━━━━━━━━━━━━━━━━━━━━━━━┓
┃ 📈 Response Time (P95)        ┃ 📉 Error Rate            ┃
┃ [시계열 그래프]                  ┃ [시계열 그래프]            ┃
┃ - WEB: 45ms                   ┃ - HTTP 5xx: 0.05%       ┃
┃ - WAS: 250ms                  ┃ - WAS Exception: 0.01%  ┃
┃ - MySQL: 15ms                 ┃                          ┃
┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┻━━━━━━━━━━━━━━━━━━━━━━━━━┛

┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┳━━━━━━━━━━━━━━━━━━━━━━━━━┓
┃ 💻 CPU Usage by Component     ┃ 🧠 Memory Usage          ┃
┃ [스택 그래프]                   ┃ [스택 그래프]             ┃
┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┻━━━━━━━━━━━━━━━━━━━━━━━━━┛

┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓
┃ 📋 Recent Events & Logs (Last 10 mins)                ┃
┃ [로그 테이블 - ERROR/WARN만 표시]                       ┃
┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛
```

**주요 패널 쿼리:**

```promql
# WEB Status
up{job="web-nginx"} or on() vector(0)

# WAS Status (Ready Pods 비율)
sum(kube_pod_status_ready{namespace="blog-system",pod=~"was-.*",condition="true"})
/
sum(kube_pod_status_ready{namespace="blog-system",pod=~"was-.*"})
or on() vector(0)

# MySQL Status
mysql_up or on() vector(0)

# Traffic (HTTP Requests/sec)
sum(rate(nginx_http_requests_total[1m]))

# Error Rate (5xx %)
sum(rate(nginx_http_requests_total{status=~"5.."}[5m]))
/
sum(rate(nginx_http_requests_total[5m]))
* 100

# Response Time P95
histogram_quantile(0.95,
  sum(rate(nginx_http_request_duration_seconds_bucket[5m])) by (le)
)
```

### 📱 Level 2: Service Dashboards

#### WAS Service Dashboard

**목적:** Spring Boot 애플리케이션 성능 모니터링

**주요 섹션:**
1. **Application Health**
   - Ready Pods 수
   - Restart 횟수
   - Liveness/Readiness Probe 상태

2. **HTTP Traffic**
   - Requests/sec (by endpoint)
   - Response Time Distribution
   - Error Rate (by endpoint)

3. **JVM Metrics**
   - Heap Memory Usage
   - GC Pause Time
   - Thread Count
   - Class Loaded

4. **Database Connection Pool**
   - Active Connections
   - Idle Connections
   - Wait Time

5. **Top 10 Slow Requests**
   - Endpoint별 P95 latency

#### MySQL Dashboard

**목적:** 데이터베이스 성능 및 상태 모니터링

**주요 섹션:**
1. **MySQL Status**
   - Up/Down
   - Uptime
   - Version

2. **Query Performance**
   - Queries/sec
   - Slow Queries/sec
   - Query Cache Hit Rate

3. **Connections**
   - Active Connections
   - Max Connections Usage %
   - Connection Errors

4. **InnoDB**
   - Buffer Pool Usage
   - Disk Reads vs Cache Reads
   - Row Lock Time

5. **Replication** (if applicable)
   - Replication Lag
   - Slave Status

---

## 5. Alert 정책

### 🚨 Alert Rules 설계

#### Group 1: Service Availability (가용성)

```yaml
- alert: WebServiceDown
  expr: up{job="web-nginx"} == 0
  for: 1m
  labels:
    severity: critical
    component: web
    slo: availability
  annotations:
    summary: "WEB 서비스 다운"
    description: "{{ $labels.pod }}가 1분 이상 응답하지 않습니다"
    runbook: "https://wiki/runbooks/web-service-down"

- alert: WASServiceDown
  expr: |
    sum(kube_pod_status_ready{namespace="blog-system",pod=~"was-.*",condition="true"})
    /
    sum(kube_pod_status_ready{namespace="blog-system",pod=~"was-.*"})
    < 0.5
  for: 2m
  labels:
    severity: critical
    component: was
  annotations:
    summary: "WAS 서비스 50% 이상 다운"
    description: "Ready Pod 비율: {{ $value | humanizePercentage }}"

- alert: MySQLDown
  expr: mysql_up == 0
  for: 1m
  labels:
    severity: critical
    component: mysql
  annotations:
    summary: "MySQL 데이터베이스 다운"
```

#### Group 2: Performance Degradation (성능 저하)

```yaml
- alert: HighResponseTime
  expr: |
    histogram_quantile(0.95,
      sum(rate(http_server_requests_seconds_bucket{uri!~".*/actuator/.*"}[5m])) by (le, uri)
    ) > 0.5
  for: 5m
  labels:
    severity: warning
    component: was
    slo: latency
  annotations:
    summary: "API 응답 시간 증가"
    description: "{{ $labels.uri }} P95 latency: {{ $value }}s (목표: 0.5s)"

- alert: HighErrorRate
  expr: |
    sum(rate(nginx_http_requests_total{status=~"5.."}[5m]))
    /
    sum(rate(nginx_http_requests_total[5m]))
    > 0.01
  for: 5m
  labels:
    severity: high
    component: web
    slo: errors
  annotations:
    summary: "HTTP 5xx 에러율 1% 초과"
    description: "현재 에러율: {{ $value | humanizePercentage }}"
```

#### Group 3: Resource Saturation (리소스 포화)

```yaml
- alert: HighCPUUsage
  expr: |
    sum(rate(container_cpu_usage_seconds_total{namespace="blog-system",pod=~"was-.*"}[5m])) by (pod)
    /
    sum(kube_pod_container_resource_limits{namespace="blog-system",pod=~"was-.*",resource="cpu"}) by (pod)
    > 0.85
  for: 10m
  labels:
    severity: high
    component: was
  annotations:
    summary: "{{ $labels.pod }} CPU 사용률 85% 초과"
    description: "현재: {{ $value | humanizePercentage }}"

- alert: MemoryPressure
  expr: |
    sum(container_memory_working_set_bytes{namespace="blog-system",pod=~"was-.*"}) by (pod)
    /
    sum(kube_pod_container_resource_limits{namespace="blog-system",pod=~"was-.*",resource="memory"}) by (pod)
    > 0.9
  for: 5m
  labels:
    severity: critical
    component: was
  annotations:
    summary: "{{ $labels.pod }} 메모리 부족"
    description: "현재: {{ $value | humanizePercentage }}"
```

#### Group 4: Anomaly Detection (이상 감지)

```yaml
- alert: PodCrashLooping
  expr: |
    rate(kube_pod_container_status_restarts_total{namespace="blog-system"}[15m]) > 0
  for: 5m
  labels:
    severity: high
  annotations:
    summary: "{{ $labels.pod }} 반복 재시작"
    description: "15분 내 재시작 발생"

- alert: MySQLSlowQueries
  expr: |
    rate(mysql_global_status_slow_queries[5m]) > 10
  for: 10m
  labels:
    severity: warning
    component: mysql
  annotations:
    summary: "MySQL Slow Query 급증"
    description: "{{ $value }} queries/sec"

- alert: DatabaseConnectionPoolExhaustion
  expr: |
    hikaricp_connections_active / hikaricp_connections_max > 0.8
  for: 5m
  labels:
    severity: warning
    component: was
  annotations:
    summary: "DB Connection Pool 고갈 위험"
    description: "사용률: {{ $value | humanizePercentage }}"
```

### 📬 Alertmanager 라우팅

```yaml
route:
  receiver: 'default'
  group_by: ['alertname', 'component']
  group_wait: 10s
  group_interval: 10s
  repeat_interval: 12h

  routes:
    # Critical alerts → Slack + PagerDuty (24/7)
    - match:
        severity: critical
      receiver: 'critical-alerts'
      repeat_interval: 5m

    # High alerts → Slack
    - match:
        severity: high
      receiver: 'high-alerts'
      repeat_interval: 1h

    # Warning alerts → Slack (업무시간만)
    - match:
        severity: warning
      receiver: 'warning-alerts'
      repeat_interval: 4h
      active_time_intervals:
        - business-hours

receivers:
  - name: 'critical-alerts'
    slack_configs:
      - channel: '#alerts-critical'
        title: '🚨 CRITICAL: {{ .GroupLabels.alertname }}'
        text: '{{ range .Alerts }}{{ .Annotations.description }}{{ end }}'
    pagerduty_configs:
      - service_key: 'YOUR_PD_KEY'

  - name: 'high-alerts'
    slack_configs:
      - channel: '#alerts-high'
        title: '🟠 HIGH: {{ .GroupLabels.alertname }}'

  - name: 'warning-alerts'
    slack_configs:
      - channel: '#alerts-warning'
        title: '🟡 WARNING: {{ .GroupLabels.alertname }}'

time_intervals:
  - name: 'business-hours'
    time_intervals:
      - times:
        - start_time: '09:00'
          end_time: '18:00'
        weekdays: ['monday:friday']
```

---

## 6. 구현 로드맵

### 🗓️ Phase 1: 기반 구축 (Week 1)

**목표:** 메트릭 수집 정상화 + 기본 Dashboard

#### Task 1.1: 메트릭 수집 수정 (Day 1-2)

- [ ] **WAS Readiness 문제 해결**
  ```bash
  # 1. WAS Pod 로그 확인
  kubectl logs -n blog-system <was-pod> -c spring-boot

  # 2. Readiness Probe 설정 확인/수정
  kubectl edit deployment -n blog-system was

  # 3. Istio sidecar 문제 확인
  kubectl logs -n blog-system <was-pod> -c istio-proxy
  ```

- [ ] **Nginx Exporter Sidecar 추가**
  ```yaml
  # web deployment 수정
  # 1. nginx-exporter sidecar 추가
  # 2. stub_status 엔드포인트 활성화
  # 3. Service에 metrics port 추가
  ```

- [ ] **MySQL Exporter 재시작 문제 해결**
  ```bash
  # 1. 로그 확인
  kubectl logs -n blog-system mysql-exporter-xxx

  # 2. MySQL 연결 정보 확인
  kubectl get secret -n blog-system mysql-exporter-secret -o yaml
  ```

- [ ] **Prometheus Scrape 설정 완료**
  - WAS: Spring Boot Actuator endpoint
  - WEB: Nginx exporter
  - MySQL: mysqld-exporter

#### Task 1.2: System Health Overview Dashboard (Day 3-4)

- [ ] Dashboard 생성 (Grafana UI)
- [ ] 5개 핵심 Status 패널
- [ ] Golden Signals 그래프
- [ ] Alert 요약 패널
- [ ] 로그 통합 패널 (Loki)

#### Task 1.3: 기본 Alert Rules (Day 5)

- [ ] Service Availability alerts
- [ ] Critical Resource alerts (CPU > 85%, Memory > 90%)
- [ ] Alertmanager ConfigMap 생성
- [ ] Slack Webhook 연동

**검증:**
```bash
# 모든 targets UP 확인
kubectl exec -n monitoring prometheus-xxx -- \
  wget -qO- http://localhost:9090/api/v1/targets

# Dashboard 접속 확인
curl -I http://monitoring.jiminhome.shop

# Test alert 발생
kubectl scale deployment -n blog-system was --replicas=0
# → Alert 수신 확인
```

---

### 🗓️ Phase 2: 상세 모니터링 (Week 2)

**목표:** Service별 상세 Dashboard + 고급 Alert

#### Task 2.1: Service Dashboards (Day 6-8)

- [ ] WAS Service Dashboard
  - HTTP Traffic 분석
  - JVM Metrics
  - DB Connection Pool

- [ ] MySQL Dashboard
  - Query Performance
  - InnoDB Metrics
  - Slow Query Analysis

- [ ] Web (Nginx) Dashboard
  - Request Rate by path
  - Response Code Distribution
  - Connection Metrics

#### Task 2.2: 고급 Alert Rules (Day 9-10)

- [ ] Performance Degradation alerts
- [ ] Anomaly Detection alerts
- [ ] SLO-based alerts

**검증:**
- 부하 테스트로 Alert 검증
- Dashboard 정확도 확인

---

### 🗓️ Phase 3: 최적화 및 자동화 (Week 3)

**목표:** Alert 정교화 + Runbook 작성

#### Task 3.1: Alert 최적화

- [ ] Alert 임계값 튜닝 (실제 데이터 기반)
- [ ] False positive 제거
- [ ] Alert routing 최적화

#### Task 3.2: 문서화

- [ ] Runbook 작성 (각 Alert별)
- [ ] Dashboard 사용 가이드
- [ ] 장애 대응 절차

#### Task 3.3: 자동화

- [ ] Alert → Slack → Auto-scaling trigger
- [ ] Grafana Dashboard 백업 자동화
- [ ] 주간 리포트 자동 생성

---

## 7. 즉시 실행 체크리스트

### 🚀 우선순위 1: Critical 문제 해결

**지금 바로 해야 할 것:**

```bash
# 1. WAS Readiness 문제 확인
kubectl describe pod -n blog-system <was-pod> | grep -A 10 "Readiness:"
kubectl logs -n blog-system <was-pod> -c spring-boot --tail=50

# 2. WAS CPU 과부하 대응
# HPA가 제대로 작동하는지 확인
kubectl get hpa -n blog-system was-hpa
# 수동 스케일 아웃 (임시)
kubectl scale deployment -n blog-system was --replicas=4

# 3. MySQL Exporter 재시작 원인 파악
kubectl logs -n blog-system mysql-exporter-xxx --previous

# 4. Prometheus targets 확인
kubectl port-forward -n monitoring svc/prometheus 9090:9090 &
# 브라우저: http://localhost:9090/targets
```

---

## 📝 다음 단계

**이 문서를 기반으로:**

1. **즉시 실행 체크리스트**부터 시작
2. **Phase 1 (Week 1)** 작업 착수
3. 매주 리뷰 및 조정

**필요한 의사결정:**
- [ ] Slack Webhook URL 제공
- [ ] PagerDuty 사용 여부
- [ ] Alert 수신자/채널 정의
- [ ] SLO 목표값 최종 승인

---

**이 설계를 바탕으로 단계별로 구현하시겠습니까?**
**먼저 어떤 Phase부터 시작할까요?**
