---
title: "Datadog 수준의 Observability 시스템 구축 완료 (PLG Stack)"
date: 2026-01-20
summary: "Prometheus + Loki + Grafana로 완전한 모니터링 시스템 구축 - Nginx/WAS/MySQL Dashboard, AlertManager, 트러블슈팅 가이드"
tags: ["observability", "prometheus", "grafana", "loki", "monitoring", "alertmanager"]
categories: ["study", "Observability"]
series: ["Infrastructure Learning Journey"]
weight: 2
showtoc: true
tocopen: true
draft: false
---

## 프로젝트 개요

Homeserver Kubernetes 클러스터에 **Datadog 수준의 완전한 Observability 시스템**을 구축했습니다.

처음엔 "그냥 로그 좀 보면 되지 않나?"라고 생각했는데, 막상 문제가 생기면 어디서부터 봐야 할지 막막하더라구요. 그래서 제대로 된 모니터링 시스템을 만들기로 했습니다.

### 구축 목표

- Full Stack 모니터링 (WEB → WAS → DB)
- 실시간 메트릭 & 로그 통합
- 실용적 Alert 시스템
- 빠른 트러블슈팅 환경
- 100% 무료 오픈소스

---

## 구축 완료 현황

### 1. 모니터링 스택 (PLG)

| 컴포넌트 | 역할 | 접근 URL |
|----------|------|----------|
| **Prometheus** | 메트릭 수집 & Alert | `prometheus.jiminhome.shop` |
| **Loki** | 로그 수집 & 검색 | Internal |
| **Grafana** | 시각화 Dashboard | `monitoring.jiminhome.shop` |
| **AlertManager** | Alert 발송 & 관리 | Internal |

### 2. Exporters

| Exporter | 상태 | 수집 메트릭 |
|----------|------|-------------|
| nginx-exporter | ✅ Running | HTTP Requests, Connections |
| mysql-exporter | ✅ Running | Query Rate, Slow Queries |
| node-exporter | ✅ Running | Node CPU, Memory, Disk |
| cadvisor | ✅ Running | Container Metrics |
| kube-state-metrics | ✅ Running | Pod Status, Restarts |

---

## 🎨 구축한 Dashboards (4개)

### 1. Nginx Dashboard (WEB Layer)

**URL:** `monitoring.jiminhome.shop/d/e556538a.../nginx-web-server-monitoring`

처음엔 "Nginx 메트릭이 뭐가 있지?"라고 고민했어요. 근데 알고 보니 필요한 건 딱 몇 가지더라구요.

**주요 패널 (8개):**
- 🌐 Request Rate (req/s)
- 🔗 Active Connections
- Total Requests
- Nginx Status
- 📈 Request Rate Over Time (그래프)
- 🔌 Connection States (Reading/Writing/Waiting)
- Connections Accepted vs Handled
- Nginx Access Logs (Loki 통합)

**사용 사례:**
- 트래픽 급증 감지
- 연결 상태 실시간 모니터링
- 액세스 로그 즉시 확인

---

### 2. WAS Dashboard (Application Layer)

**URL:** `monitoring.jiminhome.shop/d/c714ed80.../was-spring-boot-monitoring-dashboard`

WAS 모니터링은 좀 까다로웠어요. Spring Boot Actuator가 없어서...

**주요 패널 (9개):**
- 🟢 WAS Pod Status (Running/DOWN)
- 🔄 Pod Restarts (최근 1시간)
- 💾 Average Memory Usage (%)
- Average CPU Usage (%)
- CPU Usage per Pod (그래프)
- 💾 Memory Usage per Pod (그래프)
- 🌐 HTTP Requests to /board (Nginx 경유)
- 📡 Network I/O (RX/TX bytes/s)
- WAS Error Logs (Loki 통합)

**제한사항 & 해결:**
- WAS 컨테이너에 Spring Boot Actuator 없음
- 컨테이너 레벨 메트릭으로 대체 (CPU, Memory, Network)
- HTTP 트래픽은 Nginx 메트릭으로 간접 확인

처음엔 "Actuator 없으면 어떡하지?"라고 걱정했는데, cadvisor로도 충분히 모니터링할 수 있더라구요.

---

### 3. MySQL Dashboard (Database Layer)

**URL:** `monitoring.jiminhome.shop/d/4efa51bd.../mysql-database-monitoring-dashboard`

MySQL Exporter 설정하는 게 제일 어려웠어요. 계속 CrashLoopBackOff가 뜨는 거예요...

**주요 패널 (9개):**
- MySQL Status (UP/DOWN)
- 🔗 Current Connections
- Query Rate (queries/sec)
- 🐌 Slow Queries
- 📈 Query Rate Over Time (그래프)
- 🔗 Connections Over Time (그래프)
- 💾 InnoDB Buffer Pool Usage (%)
- 📖 Table Operations (SELECT/INSERT/UPDATE/DELETE)
- MySQL Error Logs (Loki 통합)

**기술적 난관 & 해결:**

**문제:** MySQL Exporter CrashLoopBackOff
```
error: no user specified in section or parent
```

처음엔 환경변수로 설정했는데 안 되더라구요. 알고 보니 ConfigMap으로 `.my.cnf` 파일을 만들어줘야 했어요.

**해결:** ConfigMap으로 `.my.cnf` 생성
```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: mysql-exporter-config
data:
  .my.cnf: |
    [client]
    user=exporter
    password=exporter_password
    host=mysql-service
    port=3306
```

**결과:** Pod Running, `mysql_up 1` 확인 ✅

---

### 4. Blog System Overview (Full Stack)

**URL:** `monitoring.jiminhome.shop/d/be1f8087.../blog-system-full-stack-overview`

전체 시스템을 한눈에 보고 싶어서 만든 대시보드예요.

**섹션 구성 (13개 패널):**

**🎯 System Health Overview**
- WEB Status (Running Pod 수)
- WAS Status (Running Pod 수)
- MySQL Status (UP/DOWN)
- Total Pod Restarts (1시간)

**📈 Traffic & Performance**
- Total HTTP Request Rate (그래프)
- MySQL Query Rate (그래프)

**💻 Resource Usage**
- CPU Usage by Component (WEB/WAS/MySQL)
- Memory Usage by Component (WEB/WAS/MySQL)

**📝 System Logs**
- Recent Error Logs (전체 시스템 통합)

**용도:**
- 일일 시스템 체크
- 장애 발생 시 전체 영향 파악
- 리소스 증설 계획 수립

---

## 🚨 Alert Rules 설정 (8개)

### Critical Alerts (즉시 대응)

| Alert 이름 | 조건 | 지속 시간 |
|-----------|------|-----------|
| **PodDown** | Pod 상태가 Running이 아님 | 2분 |
| **MySQLDown** | MySQL 연결 불가 | 1분 |

### Warning Alerts (1시간 내 검토)

| Alert 이름 | 조건 | 지속 시간 |
|-----------|------|-----------|
| **HighCPUUsage** | CPU > 80% | 5분 |
| **HighMemoryUsage** | Memory > 85% | 5분 |
| **FrequentPodRestarts** | 1시간 내 재시작 > 5회 | 5분 |
| **HighSlowQueries** | Slow Query > 1/sec | 5분 |
| **MySQLHighConnections** | 연결 수 > 100 | 5분 |

### Info Alerts (주간 리뷰)

| Alert 이름 | 조건 | 지속 시간 |
|-----------|------|-----------|
| **HighRequestRate** | 요청 수 > 100 req/s | 5분 |

### AlertManager 설정

```yaml
# Slack 알림 템플릿 포함 (주석 처리)
receivers:
  - name: 'critical-alerts'
    # slack_configs:
    #   - api_url: 'YOUR_SLACK_WEBHOOK_URL'
    #     channel: '#alerts-critical'
    #     title: '🚨 Critical Alert: {{ .GroupLabels.alertname }}'
```

**Slack 연동 방법:**
1. Slack Incoming Webhook 생성
2. AlertManager ConfigMap 수정
3. webhook_url 설정 후 재시작

---

## 트러블슈팅 가이드

### 시나리오 1: Pod가 자꾸 재시작됨

처음 이런 상황이 생겼을 때 정말 당황했어요. 근데 이제는 체계적으로 접근할 수 있게 됐어요.

**1단계: Dashboard 확인**
- WAS Dashboard → "🔄 Pod Restarts" 패널 확인

**2단계: 원인 파악**
```bash
# Pod 상태 확인
kubectl get pods -n blog-system

# 이벤트 확인
kubectl describe pod -n blog-system <pod-name>

# 이전 로그 확인 (OOMKilled?)
kubectl logs -n blog-system <pod-name> --previous
```

**3단계: Grafana Explore에서 로그 검색**
```logql
{namespace="blog-system",pod="<pod-name>"} |= "ERROR" or "OOMKilled"
```

**4단계: 메모리 사용률 확인**
```promql
container_memory_working_set_bytes{pod="<pod-name>"}
```

---

### 시나리오 2: 사이트가 느림

"왜 이렇게 느리지?"라고 할 때 어디서부터 봐야 할지 알게 됐어요.

**1단계: 전체 시스템 확인**
- Blog System Overview Dashboard 확인

**2단계: 병목 지점 찾기**

**WEB Layer 확인:**
```promql
# Request Rate 급증?
rate(nginx_http_requests_total[5m])

# Active Connections 과다?
nginx_connections_active
```

**WAS Layer 확인:**
```promql
# CPU 높음?
sum(rate(container_cpu_usage_seconds_total{pod=~"was-.*"}[5m])) by (pod)

# Memory 높음?
sum(container_memory_working_set_bytes{pod=~"was-.*"}) by (pod)
```

**DB Layer 확인:**
```promql
# Slow Query 급증?
rate(mysql_global_status_slow_queries[5m])

# Connections 과다?
mysql_global_status_threads_connected
```

**3단계: 로그 분석**
```logql
{namespace="blog-system"} |= "slow" or "timeout" or "deadlock"
```

---

### 시나리오 3: MySQL 연결 오류

**1단계: MySQL 상태 확인**
- MySQL Dashboard → "✅ MySQL Status" 패널

**2단계: 연결 수 확인**
```promql
mysql_global_status_threads_connected
mysql_global_variables_max_connections
```

**3단계: MySQL Exporter 로그 확인**
```bash
kubectl logs -n blog-system -l app=mysql-exporter
```

**4단계: MySQL 직접 접속 테스트**
```bash
kubectl exec -it -n blog-system <mysql-pod> -- mysql -u root -p
```

---

## 유용한 PromQL 쿼리 모음

### CPU 사용률 (Pod별)
```promql
sum(rate(container_cpu_usage_seconds_total{namespace="blog-system",pod=~"was-.*",container!="POD"}[5m])) by (pod)
/
sum(container_spec_cpu_quota{namespace="blog-system",pod=~"was-.*",container!="POD"}/container_spec_cpu_period{namespace="blog-system",pod=~"was-.*",container!="POD"}) by (pod) * 100
```

### 메모리 사용률 (Pod별)
```promql
sum(container_memory_working_set_bytes{namespace="blog-system",pod=~"was-.*",container!="POD"}) by (pod)
/
sum(container_spec_memory_limit_bytes{namespace="blog-system",pod=~"was-.*",container!="POD"}) by (pod) * 100
```

### HTTP Request Rate
```promql
rate(nginx_http_requests_total{namespace="blog-system"}[5m])
```

### MySQL Query Rate
```promql
rate(mysql_global_status_queries{namespace="blog-system"}[5m])
```

### Pod Restart 횟수 (1시간)
```promql
increase(kube_pod_container_status_restarts_total{namespace="blog-system"}[1h])
```

---

## 유용한 LogQL 쿼리 모음

### 전체 ERROR 로그
```logql
{namespace="blog-system"} |= "ERROR" or "error" or "Exception"
```

### WAS 에러 로그만
```logql
{namespace="blog-system",pod=~"was-.*"} |= "ERROR" or "Exception"
```

### MySQL 에러 로그만
```logql
{namespace="blog-system",pod=~"mysql-.*"} |= "ERROR" or "error"
```

### 에러율 계산 (메트릭 변환)
```logql
sum(rate({namespace="blog-system"} |= "ERROR" [5m])) by (pod)
```

---

## Dashboard 해석 가이드

### CPU 사용률 기준

| 컴포넌트 | 정상 범위 | 주의 (Yellow) | 경고 (Red) |
|----------|-----------|---------------|------------|
| WEB | 10-30% | 60-80% | 80%+ |
| WAS | 20-50% | 60-80% | 80%+ |
| MySQL | 15-40% | 60-80% | 80%+ |

처음엔 "어느 정도가 정상이지?"라고 궁금했는데, 며칠 동안 관찰하면서 기준을 정했어요.

**대응 방법:**
1. HPA 설정 확인: `kubectl get hpa -n blog-system`
2. Pod 수 증설 또는 리소스 limit 증가
3. 코드 최적화 검토

---

### 메모리 사용률 기준

| 컴포넌트 | 정상 범위 | 주의 (Yellow) | 경고 (Red) |
|----------|-----------|---------------|------------|
| WEB | 30-50% | 70-85% | 85%+ |
| WAS | 50-70% | 70-85% | 85%+ (OOMKilled 위험) |
| MySQL | 60-80% | 80-90% | 90%+ |

**대응 방법:**
1. 메모리 누수 분석 (Heap Dump)
2. JVM 옵션 튜닝 (-Xmx, -Xms)
3. Pod Memory Limit 증가

---

### Slow Query 기준

| 상태 | 기준 | 대응 |
|------|------|------|
| 정상 | 0-1 slow query/min | - |
| 주의 | 5+ slow queries/min | 인덱스 검토 |
| 경고 | 10+ slow queries/min | 즉시 최적화 |

**대응 방법:**
```bash
# Slow Query 확인
kubectl exec -it -n blog-system <mysql-pod> -- mysql -u root -p -e "SHOW FULL PROCESSLIST;"

# Slow Query Log 활성화
SET GLOBAL slow_query_log = 'ON';
SET GLOBAL long_query_time = 1;
```

---

## 🎓 주요 학습 내용

### 1. Prometheus 메트릭 수집의 핵심

**문제:** Dashboard에서 `namespace="blog-system"` 필터링 시 메트릭 없음

처음엔 "왜 namespace가 안 나오지?"라고 한참 고민했어요.

**원인:** 기본 cadvisor는 namespace 레이블을 제공하지 않음

**해결:** `kubernetes-cadvisor` job 추가
```yaml
- job_name: 'kubernetes-cadvisor'
  kubernetes_sd_configs:
    - role: node
  scheme: https
  tls_config:
    ca_file: /var/run/secrets/kubernetes.io/serviceaccount/ca.crt
  bearer_token_file: /var/run/secrets/kubernetes.io/serviceaccount/token
  relabel_configs:
    - target_label: __metrics_path__
      replacement: /api/v1/nodes/${1}/proxy/metrics/cadvisor
  metric_relabel_configs:
    - source_labels: [namespace]
      target_label: kubernetes_namespace
```

**결과:** `container_cpu_usage_seconds_total{kubernetes_namespace="blog-system"}` 메트릭 수집 가능 ✅

---

### 2. MySQL Exporter 설정

**문제:** CrashLoopBackOff "no user specified"

이거 해결하는 데 정말 오래 걸렸어요...

**시도 1:** 환경변수 `DATA_SOURCE_NAME` 사용
```yaml
env:
  - name: DATA_SOURCE_NAME
    value: "exporter:password@tcp(mysql:3306)/"
```
❌ 실패: Exporter가 환경변수를 읽지 못함

**시도 2:** ConfigMap으로 `.my.cnf` 생성
```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: mysql-exporter-config
data:
  .my.cnf: |
    [client]
    user=exporter
    password=exporter_password
    host=mysql-service
    port=3306
```

**Deployment 설정:**
```yaml
args:
  - '--config.my-cnf=/etc/mysql-exporter/.my.cnf'
volumeMounts:
  - name: mysql-exporter-config
    mountPath: /etc/mysql-exporter
volumes:
  - name: mysql-exporter-config
    configMap:
      name: mysql-exporter-config
```

✅ 성공: Pod Running, `mysql_up 1` 확인

**교훈:** Exporter 공식 문서를 따르고, 컨테이너 로그를 꼼꼼히 분석할 것

---

### 3. WAS Actuator 부재 시 대응

**상황:** WAS 컨테이너는 사전 빌드된 이미지 (`ghcr.io/wlals2/board-was:v3`)

**문제:** Spring Boot Actuator 엔드포인트 없음
- `/actuator/health` → 404
- `/actuator/prometheus` → 404

처음엔 "Actuator 없으면 어떡하지?"라고 걱정했어요. 근데 알고 보니 대안이 있더라구요.

**제한사항:**
- HTTP Request Rate 수집 불가
- JVM 메트릭 수집 불가
- Endpoint별 성능 수집 불가

**대안:**
1. **컨테이너 메트릭 활용**
   - CPU, Memory, Network I/O는 cadvisor로 수집

2. **Nginx 메트릭으로 간접 확인**
   - HTTP 트래픽은 Nginx에서 확인

3. **Loki 로그 분석**
   - ERROR level 로그로 애플리케이션 상태 파악

**향후 개선 방향:**
- WAS 소스 코드 수정 + 재빌드
- Micrometer + Actuator 의존성 추가
- Prometheus 메트릭 엔드포인트 활성화

---

## 📦 생성된 파일 목록

### Kubernetes Manifests
```
/tmp/mysql-exporter-final.yaml        # MySQL Exporter (ConfigMap + Deployment + Service)
/tmp/prometheus-alert-rules.yaml      # Alert Rules ConfigMap (8개 Rule)
/tmp/alertmanager-setup.yaml          # AlertManager (ConfigMap + Deployment + Service)
/tmp/prometheus.yml                    # Prometheus 설정 (alerting + rule_files 추가)
```

### Grafana Dashboards
```
/tmp/nginx-dashboard.json              # Nginx Dashboard (8 panels)
/tmp/was-dashboard.json                # WAS Dashboard (9 panels)
/tmp/mysql-dashboard.json              # MySQL Dashboard (9 panels)
/tmp/blog-system-overview-dashboard.json  # Overview Dashboard (13 panels)
```

### 문서
```
/home/jimin/blogsite/docs/monitoring/QUICK-START-GUIDE.md  # 완전한 사용자 가이드
```

---

## 다음 구축 계획

### 1. Slack 알림 통합 (우선순위: 높음)
- AlertManager Slack Webhook 설정
- Alert 수준별 채널 분리 (#critical, #warning)
- 예상 소요 시간: 30분

### 2. SLO/SLI 정의 및 모니터링 (우선순위: 중간)
- Availability: 99.9% (월 43분 다운타임 허용)
- Latency: P95 < 200ms
- Error Rate: < 0.1%
- SLO Dashboard 생성

### 3. WAS Spring Boot Actuator 추가 (우선순위: 낮음)
- WAS 소스 코드 수정 필요
- Micrometer + Actuator 의존성 추가
- HTTP Request Rate, JVM 메트릭 수집
- 예상 소요 시간: 2-3시간

### 4. 장기 메트릭 보관 (우선순위: 낮음)
- Prometheus 기본 보관: 15일
- Thanos 또는 Cortex 도입 검토
- S3 호환 스토리지 연동

### 5. Grafana 사용자 권한 관리 (우선순위: 낮음)
- Viewer/Editor 역할 분리
- Anonymous Access 설정
- LDAP/OAuth 연동 (선택)

---

## 결론

**달성한 것:**
- Datadog 수준의 Full Stack Observability
- 4개 Dashboard (WEB/WAS/DB/Overview)
- 8개 Alert Rules + AlertManager
- 완전한 트러블슈팅 가이드
- 100% 무료 오픈소스

**소요 시간:** 약 2시간

**비용:** $0 (Datadog 대비 월 $100+ 절약)

처음엔 "이거 너무 복잡한 거 아닌가?"라고 생각했는데, 막상 만들고 나니 정말 유용하더라구요. 이제 뭔가 문제가 생기면 Dashboard부터 확인하게 됐어요.

**다음 단계:**
1. Slack 알림 설정 (5분)
2. 일일 Dashboard 체크 루틴 확립
3. SLO 목표 정의 및 모니터링

Kubernetes 환경에서 완전한 Observability를 구축할 수 있다는 것을 증명했습니다!

---

## 📖 참고 자료

- [Prometheus 공식 문서](https://prometheus.io/docs/)
- [Grafana 공식 문서](https://grafana.com/docs/)
- [Loki 공식 문서](https://grafana.com/docs/loki/latest/)
- [AlertManager 공식 문서](https://prometheus.io/docs/alerting/latest/alertmanager/)
- [Awesome Prometheus Alerts](https://awesome-prometheus-alerts.grep.to/)
- [Quick Start Guide](/home/jimin/blogsite/docs/monitoring/QUICK-START-GUIDE.md)
