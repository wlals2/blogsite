# 📊 Blog System Observability - 빠른 시작 가이드

> Datadog 수준의 무료 오픈소스 모니터링 스택 (PLG Stack)

---

## 🎯 개요

이 가이드는 Prometheus + Loki + Grafana를 사용한 완전한 Observability 시스템의 **실제 사용 방법**을 다룹니다.

### 구성 요소

| 컴포넌트 | 역할 | 접근 URL |
|----------|------|----------|
| **Grafana** | 시각화 & Dashboard | `monitoring.jiminhome.shop` |
| **Prometheus** | 메트릭 수집 & Alert | `prometheus.jiminhome.shop` |
| **Loki** | 로그 수집 & 검색 | (Internal) |
| **AlertManager** | Alert 발송 & 관리 | (Internal) |

---

## 📈 Dashboards

### 1. Nginx Dashboard (WEB Layer)
**URL:** `monitoring.jiminhome.shop/d/e556538a-2ac3-4662-99c2-ad6748ffda33/nginx-web-server-monitoring`

**주요 패널:**
- 🌐 Request Rate (req/s) - 실시간 요청 수
- 🔗 Active Connections - 현재 활성 연결
- 📊 Total Requests - 누적 요청 수
- ✅ Nginx Status - UP/DOWN 상태
- 📝 Access Logs - 실시간 액세스 로그 (Loki)

**사용 시나리오:**
- 트래픽 급증 감지
- 연결 상태 모니터링
- 에러 로그 확인

---

### 2. WAS Dashboard (Application Layer)
**URL:** `monitoring.jiminhome.shop/d/c714ed80-f770-4078-b8ce-d7fd721020b5/was-spring-boot-monitoring-dashboard`

**주요 패널:**
- 🟢 WAS Pod Status - Running Pod 개수
- 🔄 Pod Restarts - 최근 1시간 재시작 횟수
- 💾 Average Memory Usage - 평균 메모리 사용률
- ⚡ Average CPU Usage - 평균 CPU 사용률
- 📊 CPU Usage per Pod - Pod별 CPU 추이
- 💾 Memory Usage per Pod - Pod별 메모리 추이
- 🌐 HTTP Requests to /board - Nginx를 통한 요청
- 📡 Network I/O - 네트워크 송수신
- 📝 WAS Error Logs - ERROR level 로그 (Loki)

**사용 시나리오:**
- Pod 재시작 원인 분석
- 메모리 누수 감지
- CPU 스파이크 조사

**중요 참고:**
- WAS는 Spring Boot Actuator가 없어 **컨테이너 레벨 메트릭만 수집**
- HTTP Request Rate, JVM 메트릭은 수집 불가
- 대신 Nginx 메트릭으로 간접 확인

---

### 3. MySQL Dashboard (Database Layer)
**URL:** `monitoring.jiminhome.shop/d/4efa51bd-162a-4707-b733-817a2a2efdb7/mysql-database-monitoring-dashboard`

**주요 패널:**
- ✅ MySQL Status - UP/DOWN 상태
- 🔗 Current Connections - 현재 연결 수
- 📊 Query Rate - 초당 쿼리 실행 수
- 🐌 Slow Queries - 느린 쿼리 수
- 📈 Query Rate Over Time - 쿼리 처리율 추이
- 🔗 Connections Over Time - 연결 수 추이
- 💾 InnoDB Buffer Pool Usage - 버퍼 풀 사용률
- 📖 Table Operations - SELECT/INSERT/UPDATE/DELETE
- 📝 MySQL Error Logs - ERROR level 로그 (Loki)

**사용 시나리오:**
- Slow Query 최적화
- 연결 풀 크기 조정
- InnoDB 버퍼 튜닝

---

### 4. Blog System Overview (전체 시스템)
**URL:** `monitoring.jiminhome.shop/d/be1f8087-43f6-45ac-85a2-028cf125b5c5/f09f938a-blog-system-full-stack-overview`

**주요 섹션:**
1. **🎯 System Health Overview**
   - WEB, WAS, MySQL 상태
   - 전체 Pod Restarts

2. **📈 Traffic & Performance**
   - 전체 HTTP Request Rate
   - MySQL Query Rate

3. **💻 Resource Usage**
   - CPU Usage by Component
   - Memory Usage by Component

4. **📝 System Logs**
   - 전체 시스템 에러 로그 통합

**사용 시나리오:**
- 일일 시스템 체크
- 장애 발생 시 전체 영향 파악
- 리소스 증설 계획

---

## 🚨 Alert Rules

### 설정된 Alert

| Alert 이름 | Severity | 조건 | 지속 시간 |
|------------|----------|------|-----------|
| **PodDown** | Critical | Pod 상태가 Running이 아님 | 2분 |
| **HighCPUUsage** | Warning | CPU 사용률 > 80% | 5분 |
| **HighMemoryUsage** | Warning | 메모리 사용률 > 85% | 5분 |
| **FrequentPodRestarts** | Warning | 1시간 내 재시작 > 5회 | 5분 |
| **MySQLDown** | Critical | MySQL 연결 불가 | 1분 |
| **HighSlowQueries** | Warning | Slow Query > 1/sec | 5분 |
| **MySQLHighConnections** | Warning | 연결 수 > 100 | 5분 |
| **HighRequestRate** | Info | 요청 수 > 100 req/s | 5분 |

### Slack 알림 설정 (선택)

AlertManager Slack 알림을 사용하려면:

```bash
# 1. Slack Webhook URL 생성
# https://api.slack.com/messaging/webhooks

# 2. AlertManager ConfigMap 수정
kubectl edit configmap -n monitoring alertmanager-config

# 3. 주석 해제 및 webhook_url 설정
# slack_configs:
#   - api_url: 'YOUR_SLACK_WEBHOOK_URL_HERE'
#     channel: '#alerts-critical'

# 4. AlertManager 재시작
kubectl rollout restart deployment -n monitoring alertmanager
```

---

## 🔍 Grafana Explore - 빠른 트러블슈팅

### Prometheus (메트릭 조회)

#### 1. Pod CPU 사용률 확인
```promql
sum(rate(container_cpu_usage_seconds_total{namespace="blog-system",pod=~"was-.*",container!="POD"}[5m])) by (pod)
/
sum(container_spec_cpu_quota{namespace="blog-system",pod=~"was-.*",container!="POD"}/container_spec_cpu_period{namespace="blog-system",pod=~"was-.*",container!="POD"}) by (pod) * 100
```

#### 2. Pod 메모리 사용률 확인
```promql
sum(container_memory_working_set_bytes{namespace="blog-system",pod=~"was-.*",container!="POD"}) by (pod)
/
sum(container_spec_memory_limit_bytes{namespace="blog-system",pod=~"was-.*",container!="POD"}) by (pod) * 100
```

#### 3. HTTP Request Rate
```promql
rate(nginx_http_requests_total{namespace="blog-system"}[5m])
```

#### 4. MySQL Query Rate
```promql
rate(mysql_global_status_queries{namespace="blog-system"}[5m])
```

#### 5. Pod Restart 확인
```promql
increase(kube_pod_container_status_restarts_total{namespace="blog-system"}[1h])
```

---

### Loki (로그 검색)

#### 1. 전체 ERROR 로그
```logql
{namespace="blog-system"} |= "ERROR" or "error" or "Exception"
```

#### 2. WAS 에러 로그만
```logql
{namespace="blog-system",pod=~"was-.*"} |= "ERROR" or "Exception"
```

#### 3. MySQL 에러 로그만
```logql
{namespace="blog-system",pod=~"mysql-.*"} |= "ERROR" or "error"
```

#### 4. 특정 시간대 로그 검색 (LogQL Parser 사용)
```logql
{namespace="blog-system"}
|= "ERROR"
| json
| line_format "{{.timestamp}} {{.level}} {{.message}}"
```

#### 5. 에러율 계산 (메트릭 변환)
```logql
sum(rate({namespace="blog-system"} |= "ERROR" [5m])) by (pod)
```

---

## 🛠️ 일반적인 문제 해결

### 시나리오 1: Pod가 자꾸 재시작됨

**1. Dashboard 확인**
- WAS Dashboard → "🔄 Pod Restarts" 패널

**2. 원인 파악**
```bash
# Pod 상태 확인
kubectl get pods -n blog-system

# 이벤트 확인
kubectl describe pod -n blog-system <pod-name>

# 로그 확인
kubectl logs -n blog-system <pod-name> --previous
```

**3. Grafana Explore에서 로그 검색**
- Loki: `{namespace="blog-system",pod="<pod-name>"} |= "ERROR" or "OOMKilled"`

**4. 리소스 확인**
- Prometheus: `container_memory_working_set_bytes{pod="<pod-name>"}`

---

### 시나리오 2: 사이트가 느림

**1. 전체 시스템 확인**
- Blog System Overview Dashboard

**2. 병목 지점 찾기**

**WEB Layer:**
```promql
# Nginx Request Rate 급증?
rate(nginx_http_requests_total[5m])

# Nginx Active Connections
nginx_connections_active
```

**WAS Layer:**
```promql
# WAS CPU 높음?
sum(rate(container_cpu_usage_seconds_total{pod=~"was-.*"}[5m])) by (pod)

# WAS Memory 높음?
sum(container_memory_working_set_bytes{pod=~"was-.*"}) by (pod)
```

**DB Layer:**
```promql
# Slow Query 급증?
rate(mysql_global_status_slow_queries[5m])

# MySQL Connections 높음?
mysql_global_status_threads_connected
```

**3. 로그 분석**
- Loki: `{namespace="blog-system"} |= "slow" or "timeout" or "deadlock"`

---

### 시나리오 3: MySQL 연결 오류

**1. MySQL 상태 확인**
- MySQL Dashboard → "✅ MySQL Status"

**2. 연결 수 확인**
```promql
mysql_global_status_threads_connected
mysql_global_variables_max_connections
```

**3. MySQL Exporter 로그 확인**
```bash
kubectl logs -n blog-system -l app=mysql-exporter
```

**4. MySQL 직접 접속 테스트**
```bash
kubectl exec -it -n blog-system <mysql-pod> -- mysql -u root -p
```

---

## 📊 Dashboard 해석 가이드

### CPU 사용률 패널

**정상 범위:**
- WEB: 10-30%
- WAS: 20-50%
- MySQL: 15-40%

**주의 (Yellow):**
- 60-80% → HPA 스케일아웃 준비

**경고 (Red):**
- 80%+ → Alert 발생, 즉시 대응 필요

**대응 방법:**
1. HPA 설정 확인: `kubectl get hpa -n blog-system`
2. Pod 수 증설 또는 리소스 limit 증가
3. 코드 최적화 검토

---

### 메모리 사용률 패널

**정상 범위:**
- WEB: 30-50%
- WAS: 50-70% (JVM 특성상 높음)
- MySQL: 60-80% (InnoDB Buffer Pool 사용)

**주의 (Yellow):**
- 85%+ → 메모리 누수 의심

**경고 (Red):**
- 95%+ → OOMKilled 위험

**대응 방법:**
1. 메모리 누수 분석 (Heap Dump 필요)
2. JVM 옵션 튜닝 (-Xmx, -Xms)
3. Pod Memory Limit 증가

---

### Slow Query 패널

**정상 범위:**
- 0-1 slow query/min

**주의:**
- 5+ slow queries/min → 인덱스 검토

**경고:**
- 10+ slow queries/min → 즉시 최적화 필요

**대응 방법:**
```bash
# Slow Query 확인
kubectl exec -it -n blog-system <mysql-pod> -- mysql -u root -p -e "SHOW FULL PROCESSLIST;"

# Slow Query Log 활성화
SET GLOBAL slow_query_log = 'ON';
SET GLOBAL long_query_time = 1;

# 로그 확인
kubectl exec -n blog-system <mysql-pod> -- cat /var/log/mysql/slow.log
```

---

## 🎓 PromQL 치트시트

### Rate vs irate
```promql
# rate: 평균 증가율 (smooth, alert에 적합)
rate(nginx_http_requests_total[5m])

# irate: 순간 증가율 (volatile, 그래프에 적합)
irate(nginx_http_requests_total[5m])
```

### Aggregation
```promql
# sum: 전체 합계
sum(container_memory_working_set_bytes{namespace="blog-system"})

# avg: 평균
avg(container_memory_working_set_bytes{namespace="blog-system"}) by (pod)

# max: 최대값
max(container_cpu_usage_seconds_total{namespace="blog-system"}) by (pod)

# count: 개수
count(kube_pod_status_phase{namespace="blog-system",phase="Running"})
```

### Label Matching
```promql
# 정확히 일치
{namespace="blog-system"}

# Regex 매칭
{pod=~"was-.*"}

# 제외
{container!="POD"}

# 여러 값 매칭
{phase=~"Running|Pending"}
```

---

## 📦 Maintenance

### ConfigMap 수정 후 적용

```bash
# Prometheus Config 수정
kubectl edit configmap -n monitoring prometheus-config

# Prometheus 재시작
kubectl rollout restart deployment -n monitoring prometheus

# AlertManager Config 수정
kubectl edit configmap -n monitoring alertmanager-config

# AlertManager 재시작
kubectl rollout restart deployment -n monitoring alertmanager
```

### Dashboard 백업

```bash
# Grafana Dashboard Export (API)
curl -u admin:admin http://monitoring.jiminhome.shop/api/dashboards/uid/<dashboard-uid> > dashboard-backup.json

# 모든 Dashboard 백업
for uid in e556538a-2ac3-4662-99c2-ad6748ffda33 c714ed80-f770-4078-b8ce-d7fd721020b5 4efa51bd-162a-4707-b733-817a2a2efdb7 be1f8087-43f6-45ac-85a2-028cf125b5c5; do
  curl -s -u admin:admin "http://monitoring.jiminhome.shop/api/dashboards/uid/$uid" > "dashboard-$uid.json"
done
```

### Alert Rule 추가

```bash
# 1. Alert Rule ConfigMap 수정
kubectl edit configmap -n monitoring prometheus-alert-rules

# 2. 새로운 rule 추가 예시
# - alert: MyNewAlert
#   expr: my_metric > 100
#   for: 5m
#   labels:
#     severity: warning
#   annotations:
#     summary: "My alert summary"
#     description: "My alert description"

# 3. Prometheus 재시작
kubectl rollout restart deployment -n monitoring prometheus

# 4. Rule 로드 확인
kubectl run curl-test --image=curlimages/curl:latest --rm -i --restart=Never -n monitoring -- curl -s http://prometheus:9090/api/v1/rules | grep MyNewAlert
```

---

## 🔗 참고 자료

### 공식 문서
- [Prometheus 공식 문서](https://prometheus.io/docs/)
- [Grafana 공식 문서](https://grafana.com/docs/)
- [Loki 공식 문서](https://grafana.com/docs/loki/latest/)
- [AlertManager 공식 문서](https://prometheus.io/docs/alerting/latest/alertmanager/)

### 유용한 쿼리 모음
- [Awesome Prometheus Alerts](https://awesome-prometheus-alerts.grep.to/)
- [LogQL Examples](https://grafana.com/docs/loki/latest/logql/examples/)

---

## 💡 Best Practices

### 1. Dashboard 사용 습관
- 매일 아침 "Blog System Overview" 체크
- 배포 전/후 "WAS Dashboard" 비교
- 성능 이슈 발생 시 "MySQL Dashboard" 우선 확인

### 2. Alert 관리
- Critical Alert는 즉시 대응
- Warning Alert는 1시간 내 검토
- Info Alert는 주간 리뷰

### 3. 로그 검색 팁
- Loki 검색 시 시간 범위를 좁게 설정 (성능)
- 정규표현식보다 `|=` (contains) 사용 권장
- 자주 사용하는 쿼리는 Grafana에 저장

### 4. 메트릭 보관
- Prometheus 기본 보관: 15일
- 장기 보관 필요 시 Thanos 또는 Cortex 고려
- 중요 메트릭은 주기적으로 Dashboard Export

---

## 🎉 요약

축하합니다! 이제 Datadog 수준의 완전한 Observability 시스템을 갖추었습니다.

**구축 완료:**
- ✅ Nginx Dashboard (WEB Layer)
- ✅ WAS Dashboard (Application Layer)
- ✅ MySQL Dashboard (Database Layer)
- ✅ Blog System Overview (전체 시스템)
- ✅ 8개 Alert Rules
- ✅ AlertManager (Slack 연동 가능)
- ✅ Grafana Explore (빠른 트러블슈팅)

**다음 단계:**
1. Slack 알림 설정 (선택)
2. 주간 Dashboard 리뷰 루틴 확립
3. SLO/SLI 목표 설정 (예: 99.9% uptime)
4. 장기 메트릭 보관 전략 (Thanos 고려)

문의사항이 있으면 `docs/monitoring/OBSERVABILITY-SETUP.md`를 참고하세요!
