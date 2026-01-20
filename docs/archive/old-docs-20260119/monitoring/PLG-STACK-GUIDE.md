# PLG Stack 모니터링 시스템 가이드

## ✅ 설치 완료

### 설치된 컴포넌트

| 컴포넌트 | 버전 | 역할 | 상태 |
|----------|------|------|------|
| **Prometheus** | latest | 메트릭 수집 | ✅ Running |
| **Loki** | 2.9.3 | 로그 수집 | ✅ Running |
| **Grafana** | latest | 통합 대시보드 | ✅ Running (NodePort 30300) |
| **Promtail** | 2.9.3 | 로그 전송 (DaemonSet) | ✅ Running (모든 노드) |
| **Node Exporter** | latest | 노드 메트릭 | ✅ Running (모든 노드) |
| **cAdvisor** | latest | 컨테이너 메트릭 | ✅ Running (모든 노드) |

---

## 🌐 Grafana 접속

### 접속 URL
```
http://192.168.1.61:30300
또는
http://<any-node-ip>:30300
```

### 로그인 정보
```
ID: admin
PW: admin
```

---

## 📊 실시간 로그 검색 (Loki)

### Grafana Explore 사용법

1. **Grafana 로그인** → 왼쪽 메뉴 → **Explore** (돋보기 아이콘)
2. **상단 드롭다운**에서 **Loki** 선택
3. **LogQL 쿼리** 입력

### LogQL 쿼리 예시

#### 1. 특정 namespace 로그 보기
```
{namespace="blog-system"}
```

#### 2. 특정 Pod 로그 보기
```
{pod="was-56446798d8-dxh74"}
```

#### 3. ERROR 로그만 필터링
```
{namespace="blog-system"} |= "ERROR"
```

#### 4. WAS 에러 로그 검색
```
{namespace="blog-system", app="was"} |~ "ERROR|Exception"
```

#### 5. 특정 시간대 404 에러
```
{namespace="blog-system"} |= "404" | json
```

#### 6. 여러 조건 조합
```
{namespace="blog-system", pod=~"was-.*"} |= "ERROR" != "health"
```

### 자주 사용하는 필터

| 필터 | 설명 | 예시 |
|------|------|------|
| `\|=` | 포함 | `\|= "ERROR"` |
| `!=` | 불포함 | `!= "health"` |
| `\|~` | 정규식 포함 | `\|~ "ERROR\|WARN"` |
| `!~` | 정규식 불포함 | `!~ "GET\|POST"` |

---

## 📈 메트릭 모니터링 (Prometheus)

### Grafana Explore에서 Prometheus 쿼리

1. **Explore** → **Prometheus** 선택
2. **PromQL 쿼리** 입력

### PromQL 쿼리 예시

#### 1. Pod CPU 사용률
```
rate(container_cpu_usage_seconds_total{namespace="blog-system"}[5m]) * 100
```

#### 2. Pod 메모리 사용량
```
container_memory_usage_bytes{namespace="blog-system"} / 1024 / 1024
```

#### 3. HTTP 요청 수 (nginx-exporter)
```
rate(nginx_http_requests_total[5m])
```

#### 4. Pod 재시작 횟수
```
kube_pod_container_status_restarts_total{namespace="blog-system"}
```

#### 5. 노드별 CPU 사용률
```
100 - (avg(irate(node_cpu_seconds_total{mode="idle"}[5m])) by (instance) * 100)
```

---

## 🎨 통합 대시보드 Import

### Kubernetes 클러스터 대시보드

1. **Grafana** → 왼쪽 메뉴 → **Dashboards** → **Import**
2. **Dashboard ID 입력**:
   - **15661** - Kubernetes Cluster Monitoring (Prometheus)
   - **12019** - Kubernetes Cluster (Prometheus)
   - **13639** - Logs Dashboard (Loki)
   - **7249** - Node Exporter Full
3. **Prometheus** 데이터소스 선택 → **Import**

### 커스텀 대시보드 생성

#### 메트릭 + 로그 통합 패널 예시

**Row 1: WAS 성능**
- CPU Usage (Prometheus)
- Memory Usage (Prometheus)
- Pod Restart Count (Prometheus)
- Error Logs (Loki: `{app="was"} |= "ERROR"`)

**Row 2: WEB 성능**
- Nginx Request Rate (nginx-exporter)
- HTTP Status Codes (nginx-exporter)
- Access Logs (Loki: `{app="web"}`)

---

## 🔔 Alert 설정

### AlertManager 설정 (Prometheus)

#### 예시: Pod Crash Alert

```yaml
# prometheus-alerts.yaml
groups:
  - name: kubernetes
    interval: 30s
    rules:
      - alert: PodCrashLooping
        expr: rate(kube_pod_container_status_restarts_total[15m]) > 0
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "Pod {{ $labels.namespace }}/{{ $labels.pod }} is crash looping"
          description: "Pod has restarted {{ $value }} times in the last 5 minutes"

      - alert: HighErrorRate
        expr: |
          sum(rate(nginx_http_requests_total{status=~"5.."}[5m])) by (namespace)
          /
          sum(rate(nginx_http_requests_total[5m])) by (namespace)
          > 0.05
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "High error rate in {{ $labels.namespace }}"
          description: "Error rate is {{ $value | humanizePercentage }}"
```

### Grafana Alert 설정

1. **Dashboard 패널** → **Edit** → **Alert 탭**
2. **조건 설정**:
   - Metric: `container_memory_usage_bytes`
   - Threshold: `> 800MB`
   - For: `5m`
3. **알람 채널**:
   - Email
   - Slack (Webhook URL 필요)
   - Telegram

---

## 💡 Datadog과 비교

| 기능 | Datadog | PLG Stack | 상태 |
|------|---------|-----------|------|
| **실시간 로그 검색** | ✅ | ✅ Loki Explore | 동일 |
| **메트릭 대시보드** | ✅ | ✅ Prometheus + Grafana | 동일 |
| **로그 + 메트릭 통합** | ✅ | ✅ Grafana 패널 조합 | 동일 |
| **알람** | ✅ | ✅ AlertManager + Grafana | 동일 |
| **APM (트레이스)** | ✅ | ⚠️ Tempo 추가 필요 | 확장 가능 |
| **AI 이상 탐지** | ✅ | ❌ | Datadog 전용 |
| **비용** | 💰 $15/host/월 | 🆓 무료 | PLG 유리 |

---

## 🔥 실전 사용 예시

### 시나리오 1: WAS에서 에러 발생 시

```
1. Grafana → Explore → Loki
2. 쿼리: {namespace="blog-system", app="was"} |= "ERROR"
3. 시간대 선택: Last 15 minutes
4. 에러 로그 확인 → 특정 Pod 식별
5. kubectl logs <pod-name> -n blog-system --tail=100
```

### 시나리오 2: 메모리 사용량 급증 알림

```
1. Grafana → Dashboard → Kubernetes Cluster
2. Memory Usage 패널 확인
3. Explore → Prometheus 쿼리:
   container_memory_usage_bytes{namespace="blog-system"} / 1024 / 1024 / 1024
4. 메모리 사용량 추이 분석
5. 필요 시 HPA 설정 조정
```

### 시나리오 3: Pod가 계속 재시작될 때

```
1. Grafana → Explore → Prometheus
2. 쿼리: kube_pod_container_status_restarts_total{namespace="blog-system"}
3. 재시작 횟수 확인
4. Explore → Loki 쿼리: {pod="<restarting-pod>"}
5. 재시작 직전 로그 확인
6. kubectl describe pod <pod-name> -n blog-system
```

---

## 📚 다음 단계

### ✅ 완료된 것
- ✅ Prometheus 메트릭 수집
- ✅ Loki 로그 수집 (모든 노드)
- ✅ Grafana 대시보드
- ✅ Node/Container 메트릭

### ⏳ 추가 가능한 것
- [ ] APM (Tempo) - 분산 트레이싱
- [ ] AlertManager Slack 연동
- [ ] Longterm Storage (S3/MinIO)
- [ ] Grafana Loki Ruler (로그 기반 알람)

---

## 🛠️ 트러블슈팅

### Loki에서 로그가 안 보일 때
```bash
# Promtail Pod 로그 확인
kubectl logs -n monitoring -l app.kubernetes.io/name=promtail --tail=50

# Loki Pod 로그 확인
kubectl logs -n monitoring loki-stack-0 --tail=50
```

### Grafana 데이터소스 연결 실패
```bash
# Loki 서비스 확인
kubectl get svc -n monitoring loki-stack

# Grafana에서 Test 버튼 클릭 → "Data source is working" 확인
```

---

**작성일**: 2026-01-19
**문서 버전**: 1.0
**관련 프로젝트**: blogsite (Hugo Blog in Kubernetes)
