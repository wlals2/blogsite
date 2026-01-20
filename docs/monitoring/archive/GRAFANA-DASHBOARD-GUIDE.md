# Grafana 대시보드 구축 가이드

> 실전 모니터링 대시보드 - 5분 안에 데이터가 표시되는 실용적인 가이드

**최종 업데이트**: 2026-01-19
**난이도**: ⭐ 초급
**소요 시간**: 5-10분

---

## 📋 현재 시스템 상태

### 모니터링 스택

| 컴포넌트 | 상태 | 접속 정보 |
|----------|------|----------|
| **Prometheus** | ✅ Running | http://192.168.1.61:30090 |
| **Grafana** | ✅ Running | http://192.168.1.61:30300 |
| **Loki** | ✅ Running | ClusterIP (내부) |
| **kube-state-metrics** | ✅ Running | - |
| **Promtail** | ✅ Running | DaemonSet (3 nodes) |

### 로그인 정보

```
Grafana:
- URL: http://192.168.1.61:30300
- ID: admin
- PW: admin
```

---

## 🚀 빠른 시작 (5분)

### Step 1: Grafana 접속 (1분)

```
1. 브라우저에서 http://192.168.1.61:30300 접속
2. 로그인: admin / admin
3. (첫 로그인 시) 비밀번호 변경 요청 → Skip 가능
```

---

### Step 2: Datasource 확인 (2분)

```
1. 왼쪽 메뉴 → Configuration (⚙️) → Data Sources

2. Prometheus 확인:
   - Name: Prometheus
   - URL: http://prometheus:9090
   - "Save & Test" → "Data source is working" 확인

3. Loki 추가 (없다면):
   - "Add data source" → "Loki" 선택
   - Name: Loki
   - URL: http://loki-stack:3100
   - "Save & Test" → "Data source connected" 확인
```

---

### Step 3: 대시보드 생성 방법 선택

#### 방법 A: 커뮤니티 대시보드 Import (2분) ⭐ 추천

**장점**:
- ✅ 즉시 사용 가능
- ✅ 검증된 디자인 (수백만 다운로드)
- ✅ 데이터 수집 문제 없음

**Top 3 추천 대시보드**:

```
1. Kubernetes Cluster Monitoring (ID: 15661)
   - Dashboards → New → Import
   - ID 입력: 15661
   - Datasource: Prometheus
   - Import

   제공 정보:
   - Cluster CPU/Memory
   - Node 상태
   - Pod 개수 (Running/Pending/Failed)
   - Namespace별 리소스

2. Node Exporter Full (ID: 1860)
   - Import ID: 1860

   제공 정보:
   - CPU 코어별 사용률
   - 메모리 세부 정보
   - 디스크/Network I/O

3. Loki & Promtail (ID: 13639)
   - Import ID: 13639
   - Datasource: Loki

   제공 정보:
   - 로그 수집 추세
   - Namespace별 로그 양
   - 에러 로그 카운트
```

---

#### 방법 B: blog-system 전용 대시보드 (이미 생성됨!)

**접속**:
```
http://192.168.1.61:30300/d/823ecbeb-0257-438f-9467-1404c7544a4a/blog-system-quick-start
```

**포함 내용**:
- 🎯 WAS Pod 개수
- 🌐 WEB Pod 개수
- 💾 MySQL Pod 상태
- 🔄 Pod Restart (24시간)
- ⚡ CPU 사용률 그래프
- 💾 메모리 사용량 그래프
- 📋 Pod 상태 테이블
- 📝 실시간 로그

---

## 🔍 데이터 검증 & 트러블슈팅

### ✅ 정상 상태

**Panel 표시**:
```
WAS Pod: 2 (녹색)
WEB Pod: 2 (녹색)
CPU 그래프: 선이 그려짐
로그: 실시간 스트리밍
```

---

### ❌ "No data" 문제 해결

#### 문제 1: Datasource 미연결

**증상**: 모든 Panel에 "No data"

**해결**:
```
1. Panel → Edit (연필 아이콘)
2. Query 탭 → Datasource 확인
3. Prometheus로 변경
4. Apply → Save
```

---

#### 문제 2: 메트릭 이름 불일치 ⚠️ 중요!

**증상**: 특정 Panel만 "No data"

**원인**: 대시보드의 메트릭 변수 ≠ Prometheus 실제 메트릭

**확인 방법**:

```bash
# 1. Prometheus에서 사용 가능한 메트릭 확인
curl -s 'http://192.168.1.61:30090/api/v1/label/__name__/values' | jq -r '.data[]' | grep kube_pod

# 2. 대시보드 Query와 비교
# 예: kube_pod_status_phase vs kube_pod_info
```

**해결 절차**:

```
1. Explore 모드로 테스트:
   - 왼쪽 메뉴 → Explore
   - Datasource: Prometheus
   - Query 입력 (예: up)
   - Run query

2. 메트릭 이름 확인:
   # blog-system Pod 확인
   kube_pod_status_phase{namespace="blog-system"}

   # 만약 안 나오면 다른 메트릭 시도
   kube_pod_info{namespace="blog-system"}
   container_cpu_usage_seconds_total{namespace="blog-system"}

3. 작동하는 Query를 Panel에 복사:
   - Panel → Edit
   - Query 수정
   - Apply → Save
```

**자주 사용하는 메트릭**:

```promql
# Pod 개수
count(kube_pod_status_phase{namespace="blog-system",phase="Running"})

# CPU 사용률 (%)
sum(rate(container_cpu_usage_seconds_total{namespace="blog-system",container!=""}[5m])) * 100

# 메모리 사용량 (MB)
sum(container_memory_usage_bytes{namespace="blog-system"}) / 1024 / 1024

# Pod Restart 횟수
sum(increase(kube_pod_container_status_restarts_total{namespace="blog-system"}[24h]))
```

---

#### 문제 3: kube-state-metrics 없음

**증상**: kube_* 메트릭 전체 없음

**확인**:
```bash
kubectl get pods -n monitoring | grep kube-state-metrics
```

**해결**:
```bash
# 설치
helm install kube-state-metrics prometheus-community/kube-state-metrics \
  --namespace monitoring

# 확인
kubectl get pods -n monitoring | grep kube-state
```

---

#### 문제 4: Loki 로그 없음

**증상**: Logs Panel 비어있음

**확인**:
```bash
# Promtail Pod 확인 (각 노드에 1개씩)
kubectl get pods -n monitoring | grep promtail

# Promtail 로그 확인
kubectl logs -n monitoring -l app.kubernetes.io/name=promtail --tail=50
```

**해결**:
```bash
# Promtail 재시작
kubectl rollout restart daemonset/loki-stack-promtail -n monitoring

# Loki 재시작
kubectl rollout restart statefulset/loki-stack -n monitoring
```

---

## 🎨 UI로 Panel 직접 만들기

### Panel 1: Pod 개수

```
1. Dashboard → Add panel → Add new panel

2. Query:
   count(kube_pod_status_phase{namespace="blog-system",pod=~"was-.*",phase="Running"})

3. Panel options:
   - Title: 🎯 WAS Pod 개수
   - Visualization: Stat

4. Thresholds:
   - 0: Red
   - 1: Yellow
   - 2: Green

5. Apply → Save
```

---

### Panel 2: CPU 그래프

```
1. Add panel

2. Query:
   sum(rate(container_cpu_usage_seconds_total{namespace="blog-system",pod=~"was-.*",container!=""}[5m])) by (pod) * 100

3. Panel options:
   - Title: ⚡ WAS CPU 사용률
   - Visualization: Graph

4. Axes:
   - Unit: Percent (0-100)
   - Min: 0, Max: 100

5. Legend:
   - Show: ✅
   - As table: ✅
   - Values: Current, Max, Avg

6. Apply → Save
```

---

### Panel 3: 실시간 로그

```
1. Add panel

2. Query:
   - Datasource: Loki
   - Query: {namespace="blog-system"}

3. Panel options:
   - Title: 📝 최근 로그
   - Visualization: Logs

4. Options:
   - Show time: ✅
   - Wrap lines: ✅
   - Order: Newest first

5. Apply → Save
```

---

## 🔧 Explore 모드 활용

### Prometheus 쿼리 테스트

```
1. 왼쪽 메뉴 → Explore
2. Datasource: Prometheus
3. Query 입력:

# 모든 타겟 상태
up

# blog-system Pod 개수
count(kube_pod_status_phase{namespace="blog-system",phase="Running"})

# WAS CPU 사용률
sum(rate(container_cpu_usage_seconds_total{namespace="blog-system",pod=~"was-.*"}[5m])) * 100

# 메모리 사용량
sum(container_memory_usage_bytes{namespace="blog-system"}) / 1024 / 1024

4. Run query → 결과 확인
```

---

### Loki 로그 쿼리 테스트

```
1. Explore → Datasource: Loki
2. Query:

# 전체 로그
{namespace="blog-system"}

# WAS 로그만
{namespace="blog-system",pod=~"was-.*"}

# 에러 로그만
{namespace="blog-system"} |= "ERROR"

# 특정 시간 범위
{namespace="blog-system"} [5m]

3. Run query → 로그 스트림 확인
```

---

## 💡 실전 팁

### 1. Variables로 동적 대시보드

**설정**:
```
Dashboard → Settings → Variables → Add variable

Name: namespace
Type: Custom
Options: blog-system,monitoring,kube-system

→ 상단에 드롭다운 생성
```

**Panel에서 사용**:
```promql
# 기존
{namespace="blog-system"}

# Variable 사용
{namespace="$namespace"}

→ 드롭다운에서 선택한 namespace로 자동 변경
```

---

### 2. Refresh Interval 조정

```
Dashboard 우측 상단 → Refresh 드롭다운

추천:
- 실시간: 5s (부하 높음)
- 일반: 30s ← 추천!
- 분석: Off (수동)
```

---

### 3. Time Range 설정

```
Dashboard 우측 상단 → Time Range

용도별:
- 실시간: Last 15 minutes
- 일반: Last 1 hour
- 트렌드: Last 24 hours
- 장애 분석: Last 7 days
```

---

### 4. Alert 설정

```
Panel → Edit → Alert 탭 → Create Alert

조건 예시:
- WHEN avg() OF query(A, 5m, now)
- IS ABOVE 80
- FOR 2m

→ CPU 80% 2분 이상 시 알람
```

---

## 📊 PromQL & LogQL 치트시트

### PromQL 기본

```promql
# 현재값
메트릭_이름

# 증가율 (초당)
rate(메트릭_이름[5m])

# 합계
sum(메트릭_이름) by (label)

# 평균
avg(메트릭_이름) by (label)

# 백분율
(메트릭A / 메트릭B) * 100

# P95 응답 시간
histogram_quantile(0.95, 메트릭_이름)
```

---

### LogQL 기본

```logql
# 라벨 필터
{label="value"}

# 문자열 포함
{label="value"} |= "text"

# 정규식
{label="value"} |~ "pattern"

# 제외
{label="value"} != "text"

# 메트릭 추출
sum(rate({label="value"} [5m]))
```

---

## 📋 완료 체크리스트

- [ ] Grafana 접속 성공
- [ ] Prometheus Datasource 연결 확인
- [ ] Loki Datasource 연결 확인
- [ ] 커뮤니티 대시보드 Import (최소 1개)
- [ ] blog-system 대시보드 확인
- [ ] Panel에 데이터 표시 확인
- [ ] Explore 모드로 메트릭 테스트
- [ ] 메트릭 이름 불일치 확인
- [ ] Variables 추가 (선택)
- [ ] Refresh Interval 설정

---

## 🆘 빠른 트러블슈팅

| 증상 | 원인 | 해결 |
|------|------|------|
| "No data" 전체 | Datasource 미연결 | Datasource를 Prometheus로 변경 |
| "No data" 일부 | 메트릭 이름 불일치 | Explore 모드로 실제 메트릭 확인 |
| kube_* 메트릭 없음 | kube-state-metrics 없음 | Helm으로 설치 |
| 로그 없음 | Promtail 문제 | Promtail Pod 재시작 |
| 느린 대시보드 | 긴 시간 범위 | Time range를 1h로 축소 |
| Query 에러 | 문법 오류 | Explore에서 테스트 |

---

## 📚 참고 자료

- [Grafana 공식 문서](https://grafana.com/docs/)
- [PromQL 가이드](https://prometheus.io/docs/prometheus/latest/querying/basics/)
- [LogQL 가이드](https://grafana.com/docs/loki/latest/logql/)
- [커뮤니티 대시보드](https://grafana.com/grafana/dashboards/)

---

**작성일**: 2026-01-19
**관련 문서**: [OBSERVABILITY-SETUP.md](./OBSERVABILITY-SETUP.md) - 완전한 Observability 구축
