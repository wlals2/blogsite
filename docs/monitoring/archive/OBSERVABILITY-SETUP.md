# Observability 시스템 완전 가이드

> Datadog 수준의 무료 오픈소스 모니터링 시스템 구축
>
> **프로젝트 목표**: Datadog과 동일한 기능을 PLG Stack(Prometheus + Loki + Grafana)으로 구현하여 프로덕션급 Observability 달성

**최종 업데이트:** 2026-01-19
**문서 버전:** 2.0 (상세 통합 버전)
**시스템 상태:** ⏳ 50% 완료 (Monitoring 구축 완료, Observability 설정 필요)

---

## 📋 목차

1. [Observability란 무엇인가](#observability란-무엇인가)
2. [왜 이렇게 구축했는가](#왜-이렇게-구축했는가)
3. [기술 스택 상세](#기술-스택-상세)
4. [시스템 아키텍처](#시스템-아키텍처)
5. [현재 구축 상태](#현재-구축-상태)
6. [완전한 Observability 구축](#완전한-observability-구축)
7. [실제 시나리오](#실제-시나리오)
8. [Datadog vs PLG Stack 비교](#datadog-vs-plg-stack-비교)
9. [다음 단계](#다음-단계)

---

## Observability란 무엇인가?

### 정의

**Monitoring (모니터링)**: 시스템의 상태를 **보는** 것
```
예: CPU 사용률 80% → "CPU가 높네" → 수동으로 대응
```

**Observability (관측 가능성)**: 시스템의 문제를 **자동으로 탐지, 진단, 대응**하는 것
```
예: CPU 사용률 80% → 자동 탐지 → Slack 알람 → HPA 자동 스케일 → 문제 해결 → SLO 기록
```

### 3가지 핵심 요소 (3 Pillars of Observability)

| 요소 | 설명 | 도구 | 상태 |
|------|------|------|------|
| **Metrics (메트릭)** | 숫자로 측정 가능한 지표 (CPU, 메모리, 요청 수) | Prometheus + Grafana | ✅ 완료 |
| **Logs (로그)** | 시간순 이벤트 기록 (에러, 경고, 정보) | Loki + Promtail | ✅ 완료 |
| **Traces (트레이스)** | 요청의 전체 흐름 추적 (분산 트레이싱) | Tempo | ⏳ 선택 사항 |

### 왜 Observability가 필요한가?

#### 문제 상황

**시나리오 1: Pod Crash**
```bash
# Monitoring만 있을 때
1. 사용자가 "사이트가 안 열려요" 신고
2. Grafana 대시보드 확인
3. Pod가 Crash된 것 발견
4. 수동으로 재시작
5. 원인 분석 시작
# 총 소요 시간: 10분 (다운타임 10분)
```

```bash
# Observability가 있을 때
1. Pod Crash 발생 (0초)
2. Liveness Probe가 자동 재시작 (30초)
3. AlertManager가 Slack 알람 (1초)
4. Loki에서 Crash 원인 로그 자동 수집
5. Grafana에서 SLO 영향도 자동 기록
# 총 소요 시간: 30초 (다운타임 30초)
```

**시나리오 2: 트래픽 급증**
```bash
# Monitoring만 있을 때
1. 사이트 느려짐
2. Grafana에서 CPU 90% 확인
3. 수동으로 Pod 개수 증가
4. 트래픽 감소 후 수동으로 감소
# 총 소요 시간: 5분 (느린 응답 5분)
```

```bash
# Observability가 있을 때
1. CPU 70% 초과 (0초)
2. HPA가 자동으로 Pod 증가 (60초)
3. AlertManager가 스케일링 알람 (1초)
4. 트래픽 감소 시 자동 스케일 인 (5분 후)
# 총 소요 시간: 60초 (느린 응답 1분)
```

### 시스템 규모

| 항목 | 현재 상태 | 목표 |
|------|----------|------|
| **메트릭 수집** | ✅ Prometheus (15초 간격) | ✅ 완료 |
| **로그 수집** | ✅ Loki (실시간) | ✅ 완료 |
| **대시보드** | ✅ Grafana | ✅ 완료 |
| **자동 스케일링** | ✅ HPA (CPU 기반) | ✅ 완료 |
| **알람** | ❌ 없음 | ⏳ AlertManager 설정 필요 |
| **자동 복구** | ❌ 수동 재시작 | ⏳ Liveness Probe 설정 필요 |
| **SLO 추적** | ❌ 없음 | ⏳ Grafana SLO Dashboard 필요 |
| **예측 분석** | ❌ 없음 | 🔜 선택 사항 |

### 프로젝트 목적

**학습 목표:**
1. **Observability 개념 이해** - Monitoring과의 차이점 체득
2. **오픈소스 도구 활용** - Prometheus, Grafana, Loki 실전 경험
3. **자동화 구현** - 알람, 자동 복구, 스케일링 자동화
4. **SLO/SLI 관리** - 서비스 레벨 목표 설정 및 추적
5. **비용 절감** - Datadog 대비 월 $100+ 절약

**비즈니스 목표:**
1. **프로덕션 신뢰성** - 99.9% 가용성 달성
2. **빠른 장애 대응** - MTTR(평균 복구 시간) 10분 → 1분
3. **비용 효율** - 무료 오픈소스 활용
4. **포트폴리오** - Observability 구축 경험 증빙

---

## 왜 이렇게 구축했는가?

### 1. 왜 PLG Stack을 선택했는가?

**선택한 기술: PLG Stack (Prometheus + Loki + Grafana)**

#### 대안 분석

| 기술 | 장점 | 단점 | 월 비용 | 선택 이유 |
|------|------|------|---------|----------|
| **PLG Stack** | 완전 무료<br>오픈소스<br>Kubernetes 네이티브<br>커스터마이징 자유 | 초기 설정 복잡<br>AI 이상 탐지 없음 | $0 | ✅ **선택** |
| Datadog | AI 이상 탐지<br>쉬운 설정<br>SaaS 관리 불필요 | 매우 비쌈<br>벤더 종속 | $100-500+ | ❌ 비용 |
| New Relic | APM 강력<br>SaaS 편의성 | 비쌈<br>로그 비용 추가 | $100+ | ❌ 비용 |
| Elastic APM | 강력한 검색<br>ELK Stack 통합 | 메모리 많이 사용<br>복잡한 설정 | $0-95 | ❌ 리소스 과다 |
| AWS CloudWatch | AWS 통합<br>쉬운 시작 | AWS 종속<br>쿼리 제한적<br>로그 비용 높음 | $10-50 | ❌ 벤더 종속 |
| Grafana Cloud | 쉬운 설정<br>PLG 관리형 | 무료 플랜 제한적<br>프로덕션은 유료 | $0-49 | ❌ 제한적 |

#### 선택 이유 (Why PLG Stack?)

1. **완전 무료 ($0/월)**
   ```bash
   # Datadog 비용 예상 (호스트 3대 기준)
   3 hosts × $15/host = $45/월 = $540/년

   # PLG Stack 비용
   $0/월 = $0/년

   # 절약: $540/년
   ```

2. **Kubernetes 네이티브**
   - Helm Chart로 5분 내 설치
   - ServiceMonitor로 자동 메트릭 수집
   - Kubernetes API 완전 통합

   ```yaml
   # Prometheus Operator 설치
   helm install kube-prometheus-stack prometheus-community/kube-prometheus-stack \
     --namespace monitoring \
     --create-namespace
   ```

3. **커스터마이징 자유**
   - Alert Rules를 YAML로 완전 제어
   - Grafana Dashboard를 JSON으로 버전 관리
   - 데이터 보관 기간 무제한 (스토리지만 있으면)

4. **오픈소스 생태계**
   - CNCF (Cloud Native Computing Foundation) 졸업 프로젝트
   - 활발한 커뮤니티
   - 풍부한 Exporter (MySQL, nginx, Redis 등)

5. **벤더 종속 없음**
   - 언제든지 다른 도구로 마이그레이션 가능
   - 데이터 소유권 완전히 보유

#### 트레이드오프

**PLG Stack의 단점:**
- ❌ AI 이상 탐지 없음 (Datadog의 Watchdog 같은 기능)
- ❌ 초기 설정 복잡 (Alert Rules, Dashboard 직접 구성)
- ❌ 자체 서버 필요 (SaaS 아님)

**하지만:**
- ✅ 학습 목표에 부합 (직접 구축 경험)
- ✅ 비용 절감 ($540/년 절약)
- ✅ Kubernetes 학습에 최적

---

### 2. 왜 Prometheus를 선택했는가?

**선택한 기술: Prometheus (메트릭 수집)**

#### 대안 분석

| 기술 | 장점 | 단점 | 선택 이유 |
|------|------|------|----------|
| **Prometheus** | Pull 방식 (서버 부하 ↓)<br>PromQL 강력<br>Kubernetes 네이티브 | 장기 저장 약함<br>고가용성 복잡 | ✅ **선택** |
| InfluxDB | Time-series DB 특화<br>장기 저장 강력 | Pull 방식 아님<br>라이센스 복잡 | ❌ Kubernetes 비친화적 |
| Graphite | 오래된 생태계<br>안정적 | PromQL 없음<br>Kubernetes 통합 약함 | ❌ 구식 |
| Victoria Metrics | Prometheus 호환<br>압축률 높음 | 상대적으로 신생 | 🔜 장기 저장용 고려 |

#### 선택 이유

1. **Pull 방식** - 서버가 능동적으로 메트릭 수집
   ```yaml
   # Prometheus가 Pod를 자동 발견하여 메트릭 수집
   - job_name: 'kubernetes-pods'
     kubernetes_sd_configs:
       - role: pod
   ```

2. **PromQL** - 강력한 쿼리 언어
   ```promql
   # CPU 사용률 상위 5개 Pod
   topk(5, sum(rate(container_cpu_usage_seconds_total[5m])) by (pod))

   # 에러율 계산
   sum(rate(nginx_http_requests_total{status=~"5.."}[5m]))
   /
   sum(rate(nginx_http_requests_total[5m]))
   ```

3. **ServiceMonitor** - Kubernetes CRD로 자동 설정
   ```yaml
   apiVersion: monitoring.coreos.com/v1
   kind: ServiceMonitor
   metadata:
     name: blog-web-monitor
   spec:
     selector:
       matchLabels:
         app: web
     endpoints:
       - port: metrics
   ```

---

### 3. 왜 Loki를 선택했는가?

**선택한 기술: Loki (로그 수집)**

#### 대안 분석

| 기술 | 장점 | 단점 | 저장 비용 | 선택 이유 |
|------|------|------|-----------|----------|
| **Loki** | 인덱스 최소화<br>저비용<br>Grafana 통합 | 전문 검색 약함 | 매우 낮음 | ✅ **선택** |
| Elasticsearch | 강력한 전문 검색<br>Kibana 시각화 | 메모리 많이 사용<br>인덱스 비용 높음 | 높음 (GB당 $0.10) | ❌ 리소스 과다 |
| CloudWatch Logs | AWS 통합<br>쉬운 설정 | 로그 비용 높음<br>쿼리 제한적 | 매우 높음 (GB당 $0.50) | ❌ 비용 |
| Fluentd + S3 | 저비용 저장<br>유연함 | 실시간 쿼리 어려움 | 낮음 (GB당 $0.023) | ❌ 실시간성 부족 |

#### 선택 이유

1. **인덱스 최소화** - 레이블만 인덱싱, 로그 본문은 압축 저장
   ```bash
   # Elasticsearch: 로그 전체 인덱싱 → 1GB 로그 = 1GB 인덱스
   # Loki: 레이블만 인덱싱 → 1GB 로그 = 10MB 인덱스
   ```

2. **LogQL** - PromQL과 유사한 쿼리 언어
   ```logql
   # blog-system 네임스페이스의 에러 로그
   {namespace="blog-system"} |= "ERROR"

   # WAS Pod의 최근 5분 로그
   {namespace="blog-system", app="was"} [5m]

   # 에러율 계산
   sum(rate({namespace="blog-system"} |= "ERROR" [5m]))
   ```

3. **Grafana 통합** - 메트릭과 로그를 한 화면에서
   ```
   Grafana Dashboard에서:
   상단: CPU 그래프 (Prometheus)
   하단: 해당 시간대 에러 로그 (Loki)
   ```

---

### 4. 왜 AlertManager를 선택했는가?

**선택한 기술: AlertManager (알람 관리)**

#### 대안 분석

| 기술 | 장점 | 단점 | 선택 이유 |
|------|------|------|----------|
| **AlertManager** | Prometheus 네이티브<br>그룹화, 억제 기능<br>다양한 통합 | 설정 복잡 | ✅ **선택** |
| PagerDuty | On-call 관리 강력<br>SaaS 편의성 | 유료 ($19/사용자/월) | ❌ 비용 |
| Opsgenie | 알람 라우팅 강력<br>SLA 보장 | 유료 ($9/사용자/월) | ❌ 비용 |
| Grafana Alerting | Grafana 통합<br>쉬운 설정 | 복잡한 규칙 제한적 | 🔜 추가 고려 |

#### 선택 이유

1. **알람 그룹화** - 동일한 문제는 한 번만 알람
   ```yaml
   route:
     group_by: ['alertname', 'namespace']
     group_wait: 10s        # 10초 대기 후 그룹화
     repeat_interval: 12h   # 12시간 후 재알람
   ```

2. **알람 억제** - CPU 알람 중 Pod Crash 알람 억제
   ```yaml
   inhibit_rules:
     - source_match:
         severity: 'critical'
       target_match:
         severity: 'warning'
       equal: ['alertname', 'namespace']
   ```

3. **다양한 통합** - Slack, Email, Webhook 등
   ```yaml
   receivers:
     - name: 'slack'
       slack_configs:
         - api_url: 'YOUR_SLACK_WEBHOOK'
           channel: '#alerts'
           title: '🚨 {{ .GroupLabels.alertname }}'
   ```

---

## 기술 스택 상세

### Prometheus (메트릭 수집)

**버전**: v2.47.0
**리소스**: CPU 200m, Memory 512Mi
**데이터 보관**: 15일

#### 주요 기능

| 기능 | 설명 | 예제 |
|------|------|------|
| **Service Discovery** | Kubernetes API로 자동 타겟 발견 | Pod, Service, Node 자동 수집 |
| **Pull 방식** | 서버가 능동적으로 메트릭 수집 | 15초 간격으로 /metrics 호출 |
| **PromQL** | 강력한 쿼리 언어 | `rate()`, `histogram_quantile()` |
| **Alert Rules** | YAML로 알람 규칙 정의 | CPU > 80% 2분 이상 |

#### 수집 메트릭

```promql
# 1. Container 메트릭
container_cpu_usage_seconds_total
container_memory_working_set_bytes
container_network_receive_bytes_total

# 2. Kubernetes 메트릭
kube_pod_status_phase
kube_deployment_status_replicas
kube_node_status_condition

# 3. Application 메트릭 (예: nginx)
nginx_http_requests_total
nginx_http_request_duration_seconds
```

#### PromQL 예제

```promql
# CPU 사용률 계산
sum(rate(container_cpu_usage_seconds_total{namespace="blog-system"}[5m])) by (pod)

# 메모리 사용률 계산 (%)
sum(container_memory_working_set_bytes{namespace="blog-system"}) by (pod)
/
sum(container_spec_memory_limit_bytes{namespace="blog-system"}) by (pod)
* 100

# P95 응답 시간
histogram_quantile(0.95,
  sum(rate(nginx_http_request_duration_seconds_bucket[5m])) by (le)
) * 1000  # ms로 변환
```

---

### Loki (로그 수집)

**버전**: v2.9.0
**리소스**: CPU 100m, Memory 256Mi
**데이터 보관**: 7일

#### 주요 기능

| 기능 | 설명 | 예제 |
|------|------|------|
| **레이블 인덱싱** | 레이블만 인덱싱, 본문은 압축 저장 | `{namespace="blog-system", app="was"}` |
| **LogQL** | PromQL과 유사한 쿼리 언어 | `\|= "ERROR"`, `\|~ ".*timeout.*"` |
| **Grafana 통합** | 메트릭과 로그 한 화면에서 | Explore 모드에서 즉시 확인 |
| **저비용** | 인덱스 최소화로 저장 비용 ↓ | 1GB 로그 = 10MB 인덱스 |

#### LogQL 예제

```logql
# 1. 기본 필터링
{namespace="blog-system"} |= "ERROR"

# 2. 정규식 필터링
{namespace="blog-system"} |~ "timeout|error|exception"

# 3. 로그 파싱 (JSON)
{namespace="blog-system"} | json | level="error"

# 4. 메트릭 추출 (에러율 계산)
sum(rate({namespace="blog-system"} |= "ERROR" [5m]))

# 5. 시간 범위 지정
{namespace="blog-system", app="was"} [5m]

# 6. 라인 포맷
{namespace="blog-system"} | line_format "{{.timestamp}} {{.level}} {{.message}}"
```

---

### Grafana (시각화)

**버전**: v10.1.0
**리소스**: CPU 100m, Memory 128Mi
**접속 URL**: http://grafana.blog-system.svc.cluster.local:3000

#### 주요 기능

| 기능 | 설명 | 예제 |
|------|------|------|
| **대시보드** | 메트릭, 로그 시각화 | CPU, 메모리, 요청 수 그래프 |
| **Explore 모드** | 즉시 쿼리 실행 | PromQL, LogQL 테스트 |
| **Alert Rules** | 대시보드에서 알람 생성 | CPU > 80% 시 알람 |
| **Variables** | 동적 대시보드 | Namespace 선택 시 Pod 자동 변경 |

#### 추천 대시보드

**1. Kubernetes Cluster 대시보드 (ID: 7249)**
```
- Node CPU/메모리 사용률
- Pod 개수 추이
- Namespace별 리소스 사용
```

**2. nginx Ingress 대시보드 (ID: 9614)**
```
- 요청 수 (RPS)
- 응답 시간 (P50, P95, P99)
- 에러율 (5xx)
```

**3. Loki 로그 대시보드**
```
- 최근 에러 로그
- 로그 레벨별 통계
- Pod별 로그 양
```

---

### AlertManager (알람 관리)

**버전**: v0.26.0
**리소스**: CPU 50m, Memory 128Mi
**상태**: ⏳ 설정 필요

#### 주요 기능

| 기능 | 설명 | 예제 |
|------|------|------|
| **그룹화** | 동일한 알람 한 번에 전송 | CPU 알람 10개 → 1개로 묶음 |
| **억제** | 상위 알람 발생 시 하위 알람 억제 | Pod Crash 발생 시 CPU 알람 억제 |
| **라우팅** | 심각도별 다른 채널 | Critical → Slack, Warning → Email |
| **Silence** | 유지보수 시 알람 일시 중지 | 배포 중 알람 무시 |

#### 알람 라우팅 예제

```yaml
route:
  receiver: 'default'
  routes:
    # Critical 알람은 Slack으로
    - match:
        severity: critical
      receiver: 'slack'
      continue: true

    # Warning 알람은 Email로
    - match:
        severity: warning
      receiver: 'email'
```

---

## 시스템 아키텍처

### 전체 아키텍처

```
┌─────────────────────────────────────────────────────────────┐
│                      Grafana Dashboard                       │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │  CPU 그래프   │  │ 요청 수 그래프 │  │  에러 로그    │      │
│  │ (Prometheus) │  │ (Prometheus) │  │   (Loki)     │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
└─────────────────────────────────────────────────────────────┘
          ▲                    ▲                    ▲
          │                    │                    │
          │ PromQL             │ PromQL             │ LogQL
          │                    │                    │
┌─────────┴────────┐  ┌────────┴────────┐  ┌────────┴─────────┐
│   Prometheus     │  │  AlertManager   │  │      Loki        │
│                  │  │                 │  │                  │
│  - Metrics 수집  │  │  - 알람 그룹화  │  │  - Logs 수집     │
│  - Alert Rules  │──┤  - 알람 라우팅  │  │  - 압축 저장     │
│  - 15일 보관     │  │  - Slack 전송   │  │  - 7일 보관      │
└─────────┬────────┘  └─────────────────┘  └────────┬─────────┘
          │ 15초 간격 Pull                           │ Push
          │                                           │
┌─────────┴────────────────────────────────────────┬─┴─────────┐
│              Kubernetes Cluster                  │           │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐      │ Promtail  │
│  │ WEB Pod  │  │ WAS Pod  │  │ Node 1   │      │ (Logs)    │
│  │ /metrics │  │ /metrics │  │ /metrics │      │           │
│  └──────────┘  └──────────┘  └──────────┘      │           │
└──────────────────────────────────────────────────┴───────────┘
```

### 메트릭 수집 플로우

```
1. Prometheus가 15초마다 Pod의 /metrics 엔드포인트 호출 (Pull)
   ↓
2. Pod가 현재 메트릭 반환 (CPU, Memory, Requests 등)
   ↓
3. Prometheus가 시계열 데이터베이스에 저장 (15일간 보관)
   ↓
4. Grafana가 PromQL로 Prometheus에 쿼리
   ↓
5. 그래프로 시각화
```

### 로그 수집 플로우

```
1. Pod가 stdout/stderr로 로그 출력
   ↓
2. Kubernetes가 /var/log/pods/에 로그 저장
   ↓
3. Promtail(DaemonSet)이 로그 파일 읽기
   ↓
4. Loki로 로그 전송 (Push)
   ↓
5. Loki가 레이블만 인덱싱, 본문은 압축 저장 (7일간 보관)
   ↓
6. Grafana가 LogQL로 Loki에 쿼리
   ↓
7. 로그를 시간순으로 표시
```

### 알람 플로우

```
1. Prometheus가 Alert Rules 평가 (매 15초)
   ↓
2. 조건 만족 시 (예: CPU > 80% 2분 이상) Firing 상태로 변경
   ↓
3. AlertManager로 알람 전송
   ↓
4. AlertManager가 알람 그룹화 (10초 대기)
   ↓
5. Slack Webhook으로 알람 전송
   ↓
6. Slack 채널에 알람 표시
```

---

## 현재 구축 상태

### ✅ 완료된 것 (Monitoring)

| 컴포넌트 | 상태 | 버전 | 리소스 | 비고 |
|----------|------|------|--------|------|
| **Prometheus** | ✅ | v2.47.0 | 200m/512Mi | kube-prometheus-stack |
| **Grafana** | ✅ | v10.1.0 | 100m/128Mi | admin/prom-operator |
| **Loki** | ✅ | v2.9.0 | 100m/256Mi | 7일 보관 |
| **Promtail** | ✅ | v2.9.0 | 50m/128Mi | DaemonSet |
| **kube-state-metrics** | ✅ | v2.10.0 | 50m/64Mi | Kubernetes 메트릭 |
| **node-exporter** | ✅ | v1.6.1 | 50m/64Mi | Node 메트릭 |
| **HPA** | ✅ | v2 | - | CPU 기반 자동 스케일링 |

#### 확인 방법

```bash
# 1. Prometheus 접속
kubectl port-forward -n monitoring svc/kube-prometheus-stack-prometheus 9090:9090
# http://localhost:9090

# 2. Grafana 접속
kubectl port-forward -n monitoring svc/kube-prometheus-stack-grafana 3000:80
# http://localhost:3000
# 계정: admin / prom-operator

# 3. Loki 로그 확인
# Grafana → Explore → Loki → {namespace="blog-system"}

# 4. 메트릭 확인
curl localhost:9090/api/v1/query?query=up

# 5. HPA 상태 확인
kubectl get hpa -n blog-system
```

---

### ❌ 부족한 것 (True Observability)

| 기능 | 현재 상태 | 문제점 | 목표 |
|------|----------|--------|------|
| **알람** | ❌ 없음 | 문제 발생해도 모름 | AlertManager + Slack 연동 |
| **자동 복구** | ❌ 수동 재시작 | Pod Crash 시 수동 개입 | Liveness Probe 설정 |
| **SLO 추적** | ❌ 없음 | 가용성 목표 불명확 | Grafana SLO Dashboard |
| **예측 분석** | ❌ 없음 | 문제를 미리 알 수 없음 | PromQL로 트렌드 분석 |
| **자동 스케일링 (고급)** | ⚠️ CPU만 | 메모리, 요청 수 기반 불가 | KEDA 설정 |

#### 현재의 문제 시나리오

**시나리오 1: Pod Crash**
```bash
# 현재
1. Pod Crash 발생
2. 사용자가 "사이트 안 열려요" 신고
3. Grafana 확인
4. kubectl delete pod로 수동 재시작
# 다운타임: 10분

# 목표 (Liveness Probe 설정 후)
1. Pod Crash 발생
2. Kubernetes가 30초 후 자동 재시작
3. Slack 알람 수신
4. Loki에서 Crash 원인 확인
# 다운타임: 30초
```

**시나리오 2: 메모리 부족**
```bash
# 현재
1. 메모리 사용률 90%
2. Grafana에서 확인
3. 수동으로 Pod 증가
# 소요 시간: 5분

# 목표 (KEDA 설정 후)
1. 메모리 사용률 80% 초과
2. KEDA가 자동으로 Pod 증가
3. Slack 알람 수신
# 소요 시간: 1분
```

---

## 완전한 Observability 구축

### 1단계: 실시간 알람 (AlertManager)

#### 🎯 목표
- Pod Crash, CPU 급증, 서비스 다운 시 **즉시 Slack 알람**
- 알람 그룹화로 스팸 방지
- 심각도별 다른 채널로 라우팅

#### 📋 Alert Rules 생성

**파일: `k8s-manifests/monitoring/alert-rules.yaml`**

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: prometheus-alert-rules
  namespace: monitoring
  labels:
    app: kube-prometheus-stack
    release: kube-prometheus-stack
data:
  blog-system-alerts.yml: |
    groups:
      - name: blog-system-alerts
        interval: 30s
        rules:
          # ================================================================
          # 1. Pod Crash Alert (Critical)
          # ================================================================
          # 조건: 최근 15분간 재시작 발생
          # 대기: 5분 이상 지속 시 알람
          # 효과: Pod가 계속 Crash하는 문제 즉시 감지
          # ================================================================
          - alert: PodCrashLooping
            expr: rate(kube_pod_container_status_restarts_total{namespace="blog-system"}[15m]) > 0
            for: 5m
            labels:
              severity: critical
              namespace: blog-system
            annotations:
              summary: "🔴 Pod {{ $labels.pod }}가 계속 재시작됩니다"
              description: |
                Pod: {{ $labels.pod }}
                Container: {{ $labels.container }}
                최근 15분간 재시작 횟수: {{ $value | printf "%.0f" }}회
                Namespace: {{ $labels.namespace }}
              action: |
                1. 로그 확인: kubectl logs {{ $labels.pod }} -n {{ $labels.namespace }} --tail=100
                2. 이벤트 확인: kubectl describe pod {{ $labels.pod }} -n {{ $labels.namespace }}
                3. 이전 로그: kubectl logs {{ $labels.pod }} -n {{ $labels.namespace }} --previous

          # ================================================================
          # 2. High CPU Alert (Warning)
          # ================================================================
          # 조건: CPU 사용률 80% 초과
          # 대기: 2분 이상 지속 시 알람
          # 효과: HPA가 작동하기 전에 미리 알람
          # ================================================================
          - alert: HighCPUUsage
            expr: |
              sum(rate(container_cpu_usage_seconds_total{namespace="blog-system", container!=""}[5m])) by (pod)
              /
              sum(container_spec_cpu_quota{namespace="blog-system", container!=""}/container_spec_cpu_period{namespace="blog-system", container!=""}) by (pod)
              * 100 > 80
            for: 2m
            labels:
              severity: warning
              namespace: blog-system
            annotations:
              summary: "⚠️ {{ $labels.pod }} CPU 사용률 높음"
              description: |
                Pod: {{ $labels.pod }}
                현재 CPU 사용률: {{ $value | printf "%.1f" }}%
                임계값: 80%
                Namespace: {{ $labels.namespace }}
              action: |
                1. HPA가 자동으로 Pod를 증가시킵니다 (60초 후)
                2. Pod 개수 확인: kubectl get pods -n {{ $labels.namespace }}
                3. HPA 상태 확인: kubectl get hpa -n {{ $labels.namespace }}

          # ================================================================
          # 3. Service Down Alert (Critical)
          # ================================================================
          # 조건: Pod가 1분 이상 응답 없음
          # 대기: 1분
          # 효과: 서비스 다운 즉시 감지
          # ================================================================
          - alert: ServiceDown
            expr: up{job="kubernetes-pods", namespace="blog-system"} == 0
            for: 1m
            labels:
              severity: critical
              namespace: blog-system
            annotations:
              summary: "🔴 {{ $labels.kubernetes_pod_name }} 서비스 다운"
              description: |
                Pod: {{ $labels.kubernetes_pod_name }}
                지속 시간: 1분 이상
                Namespace: {{ $labels.namespace }}
              action: |
                1. Pod 상태 확인: kubectl get pods -n {{ $labels.namespace }}
                2. Pod 상세 정보: kubectl describe pod {{ $labels.kubernetes_pod_name }} -n {{ $labels.namespace }}
                3. 로그 확인: kubectl logs {{ $labels.kubernetes_pod_name }} -n {{ $labels.namespace }}

          # ================================================================
          # 4. High Error Rate Alert (Critical)
          # ================================================================
          # 조건: 5xx 에러율 5% 초과
          # 대기: 2분
          # 효과: 서비스 품질 저하 즉시 감지
          # ================================================================
          - alert: HighErrorRate
            expr: |
              (
                sum(rate(nginx_http_requests_total{namespace="blog-system", status=~"5.."}[5m]))
                /
                sum(rate(nginx_http_requests_total{namespace="blog-system"}[5m]))
              ) > 0.05
            for: 2m
            labels:
              severity: critical
              namespace: blog-system
            annotations:
              summary: "🔴 에러율 {{ $value | humanizePercentage }}"
              description: |
                현재 에러율: {{ $value | humanizePercentage }}
                임계값: 5%
                Namespace: blog-system
              action: |
                1. Loki에서 에러 로그 확인: {namespace="blog-system"} |= "ERROR"
                2. nginx 로그: kubectl logs -n blog-system -l app=web --tail=100
                3. WAS 로그: kubectl logs -n blog-system -l app=was --tail=100

          # ================================================================
          # 5. High Memory Alert (Warning)
          # ================================================================
          # 조건: 메모리 사용률 85% 초과
          # 대기: 5분
          # 효과: OOMKilled 되기 전에 미리 알람
          # ================================================================
          - alert: HighMemoryUsage
            expr: |
              sum(container_memory_working_set_bytes{namespace="blog-system", container!=""}) by (pod)
              /
              sum(container_spec_memory_limit_bytes{namespace="blog-system", container!=""}) by (pod)
              * 100 > 85
            for: 5m
            labels:
              severity: warning
              namespace: blog-system
            annotations:
              summary: "⚠️ {{ $labels.pod }} 메모리 사용률 높음"
              description: |
                Pod: {{ $labels.pod }}
                현재 메모리 사용률: {{ $value | printf "%.1f" }}%
                임계값: 85%
                Namespace: {{ $labels.namespace }}
              action: |
                1. Pod 재시작 고려
                2. 메모리 누수 확인 필요
                3. Limit 값 조정 고려

          # ================================================================
          # 6. Pod Not Ready Alert (Warning)
          # ================================================================
          # 조건: Pod가 5분 이상 Ready 상태 아님
          # 대기: 5분
          # 효과: 배포 실패, Probe 실패 등 감지
          # ================================================================
          - alert: PodNotReady
            expr: kube_pod_status_ready{namespace="blog-system", condition="true"} == 0
            for: 5m
            labels:
              severity: warning
              namespace: blog-system
            annotations:
              summary: "⚠️ {{ $labels.pod }} Pod가 Ready 상태가 아닙니다"
              description: |
                Pod: {{ $labels.pod }}
                지속 시간: 5분 이상
                Namespace: {{ $labels.namespace }}
              action: |
                1. Pod 이벤트 확인: kubectl describe pod {{ $labels.pod }} -n {{ $labels.namespace }}
                2. Readiness Probe 확인
                3. 로그 확인: kubectl logs {{ $labels.pod }} -n {{ $labels.namespace }}
```

#### 📋 AlertManager 설정

**파일: `k8s-manifests/monitoring/alertmanager-config.yaml`**

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: alertmanager-config
  namespace: monitoring
data:
  alertmanager.yml: |
    global:
      resolve_timeout: 5m
      slack_api_url: 'YOUR_SLACK_WEBHOOK_URL'  # ⚠️ 실제 Webhook URL로 변경

    # ================================================================
    # Route: 알람 라우팅 규칙
    # ================================================================
    route:
      receiver: 'default'
      group_by: ['alertname', 'namespace']
      group_wait: 10s        # 첫 알람 후 10초 대기 (동일 그룹 알람 묶기)
      group_interval: 5m     # 그룹화된 알람을 5분마다 재전송
      repeat_interval: 12h   # 동일 알람을 12시간마다 반복

      routes:
        # Critical 알람은 즉시 Slack으로
        - match:
            severity: critical
          receiver: 'slack-critical'
          continue: true

        # Warning 알람은 Slack으로 (덜 긴급)
        - match:
            severity: warning
          receiver: 'slack-warning'

    # ================================================================
    # Receivers: 알람 수신자 설정
    # ================================================================
    receivers:
      # 기본 수신자
      - name: 'default'
        slack_configs:
          - channel: '#alerts'
            title: '📊 Monitoring Alert'
            text: '{{ range .Alerts }}{{ .Annotations.summary }}{{ end }}'

      # Critical 알람 수신자
      - name: 'slack-critical'
        slack_configs:
          - channel: '#alerts-critical'
            title: '🚨 CRITICAL: {{ .GroupLabels.alertname }}'
            text: |
              {{ range .Alerts }}
              *요약:* {{ .Annotations.summary }}
              *설명:* {{ .Annotations.description }}
              *조치:* {{ .Annotations.action }}
              *시작 시간:* {{ .StartsAt.Format "2006-01-02 15:04:05" }}
              {{ if .EndsAt }}*종료 시간:* {{ .EndsAt.Format "2006-01-02 15:04:05" }}{{ end }}
              {{ end }}
            color: 'danger'

      # Warning 알람 수신자
      - name: 'slack-warning'
        slack_configs:
          - channel: '#alerts'
            title: '⚠️ WARNING: {{ .GroupLabels.alertname }}'
            text: |
              {{ range .Alerts }}
              *요약:* {{ .Annotations.summary }}
              *설명:* {{ .Annotations.description }}
              *조치:* {{ .Annotations.action }}
              {{ end }}
            color: 'warning'

    # ================================================================
    # Inhibit Rules: 알람 억제 규칙
    # ================================================================
    # Critical 알람 발생 시 동일 Pod의 Warning 알람 억제
    inhibit_rules:
      - source_match:
          severity: 'critical'
        target_match:
          severity: 'warning'
        equal: ['pod', 'namespace']
```

#### 🚀 배포 방법

```bash
# 1. Alert Rules 적용
kubectl apply -f k8s-manifests/monitoring/alert-rules.yaml

# 2. AlertManager Config 적용
kubectl apply -f k8s-manifests/monitoring/alertmanager-config.yaml

# 3. Prometheus가 Alert Rules를 로드하도록 재시작
kubectl rollout restart deployment kube-prometheus-stack-operator -n monitoring

# 4. AlertManager 확인
kubectl port-forward -n monitoring svc/kube-prometheus-stack-alertmanager 9093:9093
# http://localhost:9093

# 5. Slack Webhook URL 설정
# https://api.slack.com/messaging/webhooks에서 Webhook 생성
# alertmanager-config.yaml의 YOUR_SLACK_WEBHOOK_URL 변경
```

#### ✅ 테스트 방법

```bash
# 1. CPU 부하 테스트 (HighCPUUsage 알람 발생)
kubectl run stress --image=polinux/stress -n blog-system -- stress --cpu 2

# 2. Pod Crash 테스트 (PodCrashLooping 알람 발생)
kubectl run crasher --image=busybox -n blog-system -- sh -c "exit 1"

# 3. 알람 확인
# Slack 채널에서 알람 확인
# 또는 AlertManager UI에서 확인: http://localhost:9093
```

---

### 2단계: 자동 복구 (Self-Healing)

#### 🎯 목표
- Pod Crash 시 **30초 내 자동 재시작**
- 문제 있는 Pod는 **즉시 트래픽에서 제외**
- 배포/업데이트 중에도 **최소 가용성 보장**

#### 📋 Liveness Probe (자동 재시작)

**원리**: Kubernetes가 주기적으로 Pod 상태 확인, 3번 실패 시 자동 재시작

**파일: `k8s-manifests/blog-system/was-deployment.yaml`에 추가**

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: was
  namespace: blog-system
spec:
  replicas: 2
  selector:
    matchLabels:
      app: was
  template:
    metadata:
      labels:
        app: was
    spec:
      containers:
      - name: spring-boot
        image: ghcr.io/wlals2/board-was:latest
        ports:
        - containerPort: 8080

        # ================================================================
        # Liveness Probe: 응답 없으면 자동 재시작
        # ================================================================
        livenessProbe:
          httpGet:
            path: /actuator/health        # Spring Boot Actuator 엔드포인트
            port: 8080
            scheme: HTTP
          initialDelaySeconds: 60         # 시작 후 60초 대기 (부팅 시간)
          periodSeconds: 10               # 10초마다 확인
          timeoutSeconds: 3               # 3초 내 응답 없으면 실패
          failureThreshold: 3             # 3번 연속 실패 시 재시작
          successThreshold: 1             # 1번 성공 시 정상

        # ================================================================
        # Readiness Probe: 준비 안 되면 트래픽 차단
        # ================================================================
        readinessProbe:
          httpGet:
            path: /actuator/health
            port: 8080
            scheme: HTTP
          initialDelaySeconds: 50         # 시작 후 50초 대기
          periodSeconds: 5                # 5초마다 확인
          timeoutSeconds: 3               # 3초 내 응답 없으면 실패
          failureThreshold: 2             # 2번 연속 실패 시 트래픽 차단
          successThreshold: 1             # 1번 성공 시 트래픽 허용

        resources:
          requests:
            cpu: 250m
            memory: 512Mi
          limits:
            cpu: 500m
            memory: 1Gi
```

**효과**:
```
1. WAS가 응답 없음 (예: DB 연결 끊김, 메모리 부족)
2. Liveness Probe 3번 실패 (30초)
3. Kubernetes가 자동으로 Pod 재시작
4. AlertManager가 Slack 알람 전송
```

#### 📋 WEB (nginx) Probe 설정

**파일: `k8s-manifests/blog-system/web-deployment.yaml`에 추가**

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: web
  namespace: blog-system
spec:
  replicas: 2
  selector:
    matchLabels:
      app: web
  template:
    metadata:
      labels:
        app: web
    spec:
      containers:
      - name: nginx
        image: ghcr.io/wlals2/blog-web:latest
        ports:
        - containerPort: 80

        livenessProbe:
          httpGet:
            path: /                      # 홈페이지 확인
            port: 80
            scheme: HTTP
          initialDelaySeconds: 30        # nginx는 빠르게 시작
          periodSeconds: 10
          timeoutSeconds: 3
          failureThreshold: 3

        readinessProbe:
          httpGet:
            path: /
            port: 80
            scheme: HTTP
          initialDelaySeconds: 20
          periodSeconds: 5
          timeoutSeconds: 3
          failureThreshold: 2

        resources:
          requests:
            cpu: 100m
            memory: 128Mi
          limits:
            cpu: 200m
            memory: 256Mi
```

#### 📋 Pod Disruption Budget (최소 가용성 보장)

**원리**: 배포/업데이트 중에도 최소 N개의 Pod를 항상 Running 상태로 유지

**파일: `k8s-manifests/blog-system/was-pdb.yaml`**

```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: was-pdb
  namespace: blog-system
spec:
  minAvailable: 1         # 항상 최소 1개 Pod는 Running
  selector:
    matchLabels:
      app: was

---
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: web-pdb
  namespace: blog-system
spec:
  minAvailable: 1         # 항상 최소 1개 Pod는 Running
  selector:
    matchLabels:
      app: web
```

**효과**:
```
1. kubectl rollout restart deployment/was 실행
2. Kubernetes가 새 Pod 생성
3. 새 Pod가 Ready 상태가 될 때까지 기존 Pod 유지
4. 새 Pod Ready 후 기존 Pod 종료
5. 최소 1개 Pod는 항상 Running → 무중단 배포
```

#### 🚀 배포 방법

```bash
# 1. Probe 설정 적용
kubectl apply -f k8s-manifests/blog-system/was-deployment.yaml
kubectl apply -f k8s-manifests/blog-system/web-deployment.yaml

# 2. PDB 설정 적용
kubectl apply -f k8s-manifests/blog-system/was-pdb.yaml

# 3. 배포 상태 확인
kubectl rollout status deployment/was -n blog-system
kubectl rollout status deployment/web -n blog-system

# 4. Probe 동작 확인
kubectl describe pod <pod-name> -n blog-system | grep -A 10 "Liveness:"
```

#### ✅ 테스트 방법

```bash
# 1. Liveness Probe 테스트 (Pod 재시작)
# WAS의 /actuator/health 엔드포인트를 일시적으로 비활성화
kubectl exec -it <was-pod> -n blog-system -- sh
# 컨테이너 내부에서
kill 1  # 메인 프로세스 종료

# 2. Readiness Probe 테스트 (트래픽 차단)
# Pod는 Running이지만 Ready 상태가 아님 확인
kubectl get pods -n blog-system
# NAME    READY   STATUS    RESTARTS
# was-x   0/1     Running   0        # 0/1 = 트래픽 차단

# 3. PDB 테스트 (무중단 배포)
kubectl rollout restart deployment/was -n blog-system
# 새 Pod가 Ready가 될 때까지 기존 Pod 유지 확인
kubectl get pods -n blog-system -w
```

---

### 3단계: SLO/SLI 추적

#### 🎯 목표
- **가용성 99.9%** (월 43분 다운타임 허용) 추적
- **응답 시간 P95 < 200ms** 추적
- **에러율 < 0.1%** 추적
- SLO 위반 시 즉시 시각적으로 확인

#### 📊 SLO 정의

**Service Level Objectives (서비스 레벨 목표)**

| SLO | 목표 | 측정 방법 | 허용 한계 |
|-----|------|----------|----------|
| **가용성** | 99.9% | `(성공 요청 / 전체 요청) × 100` | 월 43분 다운타임 |
| **응답 시간 (P95)** | < 200ms | `histogram_quantile(0.95, ...)` | 200ms 초과 시 경고 |
| **에러율** | < 0.1% | `(5xx 요청 / 전체 요청) × 100` | 0.1% 초과 시 경고 |

#### 📋 Grafana SLO Dashboard

**Dashboard JSON: `k8s-manifests/monitoring/grafana-slo-dashboard.json`**

```json
{
  "dashboard": {
    "title": "Blog System SLO Dashboard",
    "tags": ["slo", "blog-system"],
    "timezone": "Asia/Seoul",
    "panels": [
      {
        "id": 1,
        "title": "가용성 (목표: 99.9%)",
        "type": "stat",
        "targets": [
          {
            "expr": "(1 - (sum(rate(nginx_http_requests_total{namespace=\"blog-system\", status=~\"5..\"}[30d])) / sum(rate(nginx_http_requests_total{namespace=\"blog-system\"}[30d])))) * 100",
            "legendFormat": "가용성"
          }
        ],
        "fieldConfig": {
          "defaults": {
            "unit": "percent",
            "thresholds": {
              "mode": "absolute",
              "steps": [
                { "value": 0, "color": "red" },
                { "value": 99.0, "color": "yellow" },
                { "value": 99.9, "color": "green" }
              ]
            }
          }
        },
        "gridPos": { "h": 4, "w": 6, "x": 0, "y": 0 }
      },
      {
        "id": 2,
        "title": "P95 응답 시간 (목표: < 200ms)",
        "type": "stat",
        "targets": [
          {
            "expr": "histogram_quantile(0.95, sum(rate(nginx_http_request_duration_seconds_bucket{namespace=\"blog-system\"}[5m])) by (le)) * 1000",
            "legendFormat": "P95 응답 시간"
          }
        ],
        "fieldConfig": {
          "defaults": {
            "unit": "ms",
            "thresholds": {
              "mode": "absolute",
              "steps": [
                { "value": 0, "color": "green" },
                { "value": 200, "color": "yellow" },
                { "value": 500, "color": "red" }
              ]
            }
          }
        },
        "gridPos": { "h": 4, "w": 6, "x": 6, "y": 0 }
      },
      {
        "id": 3,
        "title": "에러율 (목표: < 0.1%)",
        "type": "stat",
        "targets": [
          {
            "expr": "(sum(rate(nginx_http_requests_total{namespace=\"blog-system\", status=~\"5..\"}[5m])) / sum(rate(nginx_http_requests_total{namespace=\"blog-system\"}[5m]))) * 100",
            "legendFormat": "에러율"
          }
        ],
        "fieldConfig": {
          "defaults": {
            "unit": "percent",
            "thresholds": {
              "mode": "absolute",
              "steps": [
                { "value": 0, "color": "green" },
                { "value": 0.1, "color": "yellow" },
                { "value": 1.0, "color": "red" }
              ]
            }
          }
        },
        "gridPos": { "h": 4, "w": 6, "x": 12, "y": 0 }
      },
      {
        "id": 4,
        "title": "Error Budget (남은 다운타임)",
        "type": "stat",
        "targets": [
          {
            "expr": "(43 * 60) - (sum(rate(nginx_http_requests_total{namespace=\"blog-system\", status=~\"5..\"}[30d])) / sum(rate(nginx_http_requests_total{namespace=\"blog-system\"}[30d]))) * (30 * 24 * 60 * 60)",
            "legendFormat": "남은 다운타임"
          }
        ],
        "fieldConfig": {
          "defaults": {
            "unit": "s",
            "thresholds": {
              "mode": "absolute",
              "steps": [
                { "value": 0, "color": "red" },
                { "value": 1290, "color": "yellow" },
                { "value": 2580, "color": "green" }
              ]
            }
          }
        },
        "gridPos": { "h": 4, "w": 6, "x": 18, "y": 0 }
      }
    ]
  }
}
```

#### 🚀 배포 방법

```bash
# 1. Grafana에 Dashboard Import
kubectl port-forward -n monitoring svc/kube-prometheus-stack-grafana 3000:80

# 2. Grafana UI에서 (http://localhost:3000)
# Dashboards → Import → Upload JSON file
# k8s-manifests/monitoring/grafana-slo-dashboard.json 선택

# 3. 또는 kubectl로 ConfigMap 생성
kubectl create configmap grafana-slo-dashboard \
  --from-file=k8s-manifests/monitoring/grafana-slo-dashboard.json \
  -n monitoring
```

---

### 4단계: 이벤트 기반 자동 스케일링 (KEDA)

#### 🎯 목표
- **CPU 기반** 외에 **메모리, 요청 수, 로그 에러율** 기반 자동 스케일링
- 야간에는 자동으로 Pod 감소 (비용 절감)
- 에러가 급증하면 자동으로 Pod 증가 (부하 분산)

#### 📋 KEDA 설치

```bash
# 1. KEDA Helm Chart 설치
helm repo add kedacore https://kedacore.github.io/charts
helm install keda kedacore/keda --namespace keda --create-namespace

# 2. 설치 확인
kubectl get pods -n keda
```

#### 📋 메모리 기반 스케일링

**파일: `k8s-manifests/blog-system/was-keda-memory.yaml`**

```yaml
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata:
  name: was-memory-scaler
  namespace: blog-system
spec:
  scaleTargetRef:
    name: was                    # Deployment 이름
  minReplicaCount: 2             # 최소 2개
  maxReplicaCount: 10            # 최대 10개
  pollingInterval: 30            # 30초마다 확인
  cooldownPeriod: 300            # 5분 후 스케일 인 가능

  triggers:
    - type: prometheus
      metadata:
        serverAddress: http://kube-prometheus-stack-prometheus.monitoring:9090
        metricName: memory_usage
        threshold: '80'           # 메모리 80% 초과 시
        query: |
          sum(container_memory_working_set_bytes{namespace="blog-system", pod=~"was-.*"})
          /
          sum(container_spec_memory_limit_bytes{namespace="blog-system", pod=~"was-.*"})
          * 100
```

#### 📋 로그 에러율 기반 스케일링

**파일: `k8s-manifests/blog-system/was-keda-errors.yaml`**

```yaml
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata:
  name: was-error-scaler
  namespace: blog-system
spec:
  scaleTargetRef:
    name: was
  minReplicaCount: 2
  maxReplicaCount: 10
  pollingInterval: 30
  cooldownPeriod: 300

  triggers:
    - type: prometheus
      metadata:
        serverAddress: http://kube-prometheus-stack-prometheus.monitoring:9090
        metricName: error_rate
        threshold: '10'           # 초당 에러 10개 넘으면 스케일 아웃
        query: |
          sum(rate({namespace="blog-system", app="was"} |= "ERROR" [1m]))
```

#### 📋 시간대 기반 스케일링

**파일: `k8s-manifests/blog-system/was-keda-cron.yaml`**

```yaml
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata:
  name: was-cron-scaler
  namespace: blog-system
spec:
  scaleTargetRef:
    name: was
  minReplicaCount: 2
  maxReplicaCount: 5

  triggers:
    # 평일 오전 9시-오후 6시: 5개
    - type: cron
      metadata:
        timezone: Asia/Seoul
        start: 0 9 * * 1-5         # 월-금 오전 9시
        end: 0 18 * * 1-5          # 월-금 오후 6시
        desiredReplicas: "5"

    # 그 외 시간: 2개
    - type: cron
      metadata:
        timezone: Asia/Seoul
        start: 0 18 * * 1-5        # 월-금 오후 6시
        end: 0 9 * * 1-5           # 월-금 오전 9시
        desiredReplicas: "2"
```

#### 🚀 배포 방법

```bash
# 1. KEDA ScaledObject 적용
kubectl apply -f k8s-manifests/blog-system/was-keda-memory.yaml
kubectl apply -f k8s-manifests/blog-system/was-keda-errors.yaml
kubectl apply -f k8s-manifests/blog-system/was-keda-cron.yaml

# 2. KEDA 상태 확인
kubectl get scaledobject -n blog-system

# 3. HPA 확인 (KEDA가 자동으로 HPA 생성)
kubectl get hpa -n blog-system
```

---

## 실제 시나리오

### 시나리오 1: WAS Pod Crash

**상황**: WAS Pod가 메모리 부족으로 OOMKilled

#### Observability 없을 때
```
1. 사용자: "사이트가 안 열려요" 신고 (0분)
2. 관리자: Grafana 대시보드 확인 (2분)
3. 관리자: Pod가 OOMKilled된 것 발견 (3분)
4. 관리자: kubectl delete pod로 수동 재시작 (4분)
5. 관리자: Loki에서 로그 확인하여 원인 분석 (10분)
6. 관리자: Memory Limit 증가 (15분)

총 다운타임: 15분
사용자 경험: 매우 나쁨 (15분간 사이트 이용 불가)
```

#### Observability 있을 때
```
1. WAS Pod OOMKilled 발생 (0초)
2. Liveness Probe 3번 실패 감지 (30초)
3. Kubernetes가 자동으로 Pod 재시작 (30초)
4. AlertManager가 Slack 알람 전송 (31초)
   "🔴 was-abc123 Pod가 재시작됩니다 (OOMKilled)"
5. 관리자: Slack 알람 확인 (1분)
6. 관리자: Loki에서 자동 수집된 Crash 로그 확인 (2분)
7. 관리자: Memory Limit 증가 (5분)
8. Grafana SLO Dashboard에 다운타임 자동 기록 (즉시)

총 다운타임: 30초
사용자 경험: 거의 영향 없음 (30초 동안만 일부 요청 실패)
```

**Loki 쿼리로 Crash 원인 자동 확인**
```logql
# OOMKilled 직전 로그
{namespace="blog-system", pod=~"was-.*"} |= "OutOfMemoryError" [5m]

# 메모리 사용량 증가 추세
{namespace="blog-system", pod=~"was-.*"} |= "memory" [1h]
```

---

### 시나리오 2: 트래픽 급증

**상황**: 블로그 글이 인기를 얻어 트래픽 10배 증가

#### Observability 없을 때
```
1. 사이트 느려짐 (0분)
2. 사용자들 불만 (5분)
3. 관리자: Grafana에서 CPU 90% 확인 (10분)
4. 관리자: kubectl scale deployment/was --replicas=5 (11분)
5. 트래픽 감소 후 관리자가 수동으로 스케일 인 (다음 날)

총 느린 응답 시간: 11분
리소스 낭비: 트래픽 감소 후에도 5개 Pod 유지
```

#### Observability 있을 때
```
1. 트래픽 급증 (0초)
2. CPU 70% 초과 (30초)
3. HPA가 60초 후 Pod 증가 2→4 (60초)
4. AlertManager가 CPU 경고 알람 전송 (61초)
   "⚠️ WAS CPU 사용률 75%"
5. 관리자: Slack 알람 확인, Grafana에서 트래픽 급증 확인 (2분)
6. CPU 80% 초과, HPA가 Pod 추가 증가 4→6 (2분 30초)
7. 트래픽 처리 완료, CPU 정상 (5분)
8. HPA가 5분 후 자동 스케일 인 6→4→2 (10분)

총 느린 응답 시간: 1분
리소스 효율: 트래픽 감소 시 자동으로 스케일 인
```

**Prometheus 쿼리로 트래픽 패턴 분석**
```promql
# 시간대별 요청 수
sum(rate(nginx_http_requests_total{namespace="blog-system"}[5m])) by (hour)

# 스케일링 이력
kube_deployment_status_replicas{namespace="blog-system", deployment="was"}
```

---

### 시나리오 3: 에러율 급증

**상황**: WAS와 MySQL 연결이 끊김

#### Observability 없을 때
```
1. 사용자들: "Database connection refused" 에러 (0분)
2. 관리자: 사용자 신고로 문제 인지 (5분)
3. 관리자: Loki에서 로그 확인 (10분)
4. 관리자: MySQL Pod 상태 확인 (12분)
5. 관리자: MySQL 재시작 (15분)
6. WAS 자동 재연결 (16분)

총 에러 시간: 16분
사용자 영향: 매우 큼 (16분간 모든 요청 실패)
```

#### Observability 있을 때
```
1. MySQL 연결 끊김 (0초)
2. 5xx 에러율 5% 초과 (10초)
3. AlertManager가 즉시 Slack 알람 (11초)
   "🔴 에러율 8% (임계값: 5%)"
4. 관리자: Slack 알람 확인 (1분)
5. Loki에서 "Connection refused" 로그 자동 필터링 (1분 30초)
6. MySQL Pod 상태 확인, 재시작 (2분)
7. WAS Liveness Probe가 MySQL 재연결 확인 (2분 30초)
8. 에러율 정상 (3분)
9. Grafana SLO Dashboard에 SLO 위반 기록 (즉시)

총 에러 시간: 3분
사용자 영향: 최소화 (3분간 일부 요청 실패)
```

**Loki 쿼리로 에러 원인 자동 확인**
```logql
# 에러 로그만 필터링
{namespace="blog-system"} |= "ERROR" or "Exception"

# MySQL 연결 에러 검색
{namespace="blog-system"} |~ ".*MySQL.*Connection.*refused.*"

# 에러 발생 빈도
sum(rate({namespace="blog-system"} |= "ERROR" [1m]))
```

---

## Datadog vs PLG Stack 비교

### 기능 비교

| 기능 | Datadog | PLG Stack | 결과 |
|------|---------|-----------|------|
| **실시간 메트릭 수집** | ✅ 15초 간격 | ✅ 15초 간격 (Prometheus) | ✅ 동일 |
| **로그 검색** | ✅ 강력한 전문 검색 | ✅ LogQL 쿼리 | ✅ 동일 (95%) |
| **대시보드** | ✅ 사전 정의된 대시보드 | ✅ 커뮤니티 대시보드 + 커스텀 | ✅ 동일 |
| **실시간 알람** | ✅ AI 이상 탐지 포함 | ✅ Alert Rules 기반 | ⚠️ PLG는 수동 규칙 |
| **자동 스케일링** | ❌ Kubernetes HPA 필요 | ✅ HPA + KEDA 통합 | ✅ PLG 유리 |
| **SLO 추적** | ✅ SLO Dashboard | ✅ Grafana SLO Dashboard | ✅ 동일 |
| **분산 트레이싱** | ✅ APM | ✅ Tempo 추가 필요 | ⚠️ 선택 사항 |
| **AI 이상 탐지** | ✅ Watchdog | ❌ 없음 | ❌ Datadog 전용 |
| **설정 복잡도** | ✅ 쉬움 (SaaS) | ⚠️ 복잡 (자체 구축) | ❌ PLG 불리 |
| **데이터 소유권** | ❌ Datadog 소유 | ✅ 완전히 소유 | ✅ PLG 유리 |
| **비용** | ❌ $100-500+/월 | ✅ $0/월 | ✅ PLG 압승 |

### 비용 비교 (호스트 3대 기준)

| 항목 | Datadog | PLG Stack | 절약 |
|------|---------|-----------|------|
| **Infrastructure Monitoring** | $15/호스트/월 = $45/월 | $0 | $45 |
| **Log Management** | $0.10/GB | $0 | $10 (100GB 기준) |
| **APM** | $31/호스트/월 = $93/월 | $0 (Tempo) | $93 |
| **합계** | $148/월 = $1,776/년 | $0/년 | **$1,776/년** |

### 실제 사용 시나리오

| 시나리오 | Datadog | PLG Stack | 비고 |
|----------|---------|-----------|------|
| **Pod Crash 탐지** | 즉시 Slack 알람 | 즉시 Slack 알람 (AlertManager) | 동일 |
| **CPU 급증 대응** | 알람 + 수동 스케일 | 알람 + HPA 자동 스케일 | PLG 유리 |
| **로그 검색** | 강력한 AI 검색 | LogQL 쿼리 | Datadog 약간 유리 |
| **SLO 추적** | SLO Dashboard | Grafana SLO Dashboard | 동일 |
| **이상 행동 탐지** | AI Watchdog | 수동 규칙 | Datadog 유리 |
| **비용** | 월 $148 | 월 $0 | PLG 압승 |

---

## 다음 단계

### ⏳ 30분 내 완료 가능

1. **AlertManager 설정** (20분)
   ```bash
   kubectl apply -f k8s-manifests/monitoring/alert-rules.yaml
   kubectl apply -f k8s-manifests/monitoring/alertmanager-config.yaml
   ```

2. **Slack Webhook 설정** (10분)
   - https://api.slack.com/messaging/webhooks 접속
   - Webhook URL 생성
   - alertmanager-config.yaml에 URL 입력

### ⏳ 1시간 내 완료 가능

3. **Liveness/Readiness Probe 설정** (30분)
   ```bash
   kubectl apply -f k8s-manifests/blog-system/was-deployment.yaml
   kubectl apply -f k8s-manifests/blog-system/web-deployment.yaml
   ```

4. **Pod Disruption Budget 설정** (10분)
   ```bash
   kubectl apply -f k8s-manifests/blog-system/was-pdb.yaml
   ```

5. **Grafana SLO Dashboard 생성** (20분)
   - Grafana → Import → grafana-slo-dashboard.json

### 🔜 선택 사항 (2시간 이상)

6. **KEDA 설치 및 설정** (1시간)
   ```bash
   helm install keda kedacore/keda --namespace keda --create-namespace
   kubectl apply -f k8s-manifests/blog-system/was-keda-memory.yaml
   ```

7. **Tempo 분산 트레이싱** (2시간)
   - 필요 시 추가 (현재는 단일 서비스라 불필요)

8. **Victoria Metrics 장기 저장** (1시간)
   - Prometheus 데이터를 장기 저장 (현재는 15일)

---

## 체크리스트

### ✅ 구축 완료
- [x] Prometheus 메트릭 수집
- [x] Grafana 대시보드
- [x] Loki 로그 수집
- [x] Promtail 로그 전송
- [x] HPA 자동 스케일링 (CPU 기반)
- [x] kube-state-metrics
- [x] node-exporter

### ⏳ 30분 내 설정 가능
- [ ] AlertManager 설정
- [ ] Alert Rules 생성
- [ ] Slack Webhook 연동
- [ ] Liveness Probe 설정
- [ ] Readiness Probe 설정
- [ ] Pod Disruption Budget 설정

### 🔜 1시간 내 설정 가능
- [ ] Grafana SLO Dashboard 생성
- [ ] KEDA 메모리 기반 스케일링
- [ ] KEDA 로그 에러율 기반 스케일링
- [ ] KEDA 시간대 기반 스케일링

### 🎁 선택 사항
- [ ] Tempo 분산 트레이싱
- [ ] Victoria Metrics 장기 저장
- [ ] Grafana OnCall 온콜 관리

---

## 핵심 정리

### Monitoring (현재 상태)
```
문제 발생 → Grafana에서 확인 → 수동 대응 → 원인 분석
```
**문제점**: 관리자가 Grafana를 주기적으로 확인해야 함

### Observability (목표)
```
문제 발생 → 자동 탐지 → Slack 알람 → 자동 대응 (HPA/Probe) → SLO 기록
```
**효과**: 관리자 개입 최소화, 빠른 복구, SLO 기반 의사 결정

### 왜 Observability가 중요한가?

**MTTR (Mean Time To Recovery) 단축**
```
Monitoring만:   10분 (문제 인지 5분 + 수동 대응 5분)
Observability: 1분 (자동 탐지 1초 + 자동 대응 60초)

개선: 90% 단축
```

**SLO 달성 가능**
```
99.9% 가용성 = 월 43분 다운타임 허용
10분 다운타임 × 5회 = 50분 (SLO 실패)
1분 다운타임 × 43회 = 43분 (SLO 달성)

개선: SLO 위반 → SLO 달성
```

**비용 절감**
```
Datadog: $1,776/년
PLG Stack: $0/년

절약: $1,776/년
```

---

**작성일**: 2026-01-19
**작성자**: Jimin
**문서 버전**: 2.0 (README.md 스타일 상세 버전)
**다음 단계**: AlertManager 설정 → Liveness Probe 설정 → Grafana SLO Dashboard 생성
