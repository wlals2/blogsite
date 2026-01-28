# Observability 플랫폼

> Prometheus + Loki + Tempo + Grafana (PLTG Stack)
>
> Datadog 수준의 Full Observability - Metrics + Logs + Traces

---

## 📚 문서 구조

### 🔥 핵심 문서

| 문서 | 내용 | 언제 보나 |
|------|------|-----------|
| **[CURRENT-STATUS.md](./CURRENT-STATUS.md)** | 현재 시스템 상태, 접근 방법, Dashboard 목록 | 처음 시작할 때 |
| **[TROUBLESHOOTING.md](./TROUBLESHOOTING.md)** | 문제 해결 가이드 | 문제 발생 시 |
| **[NEXT-STEPS.md](./NEXT-STEPS.md)** | 다음 구축 계획 | 시스템 확장 시 |

---

## ⚡ 빠른 시작

### 1. 접근 URL

| 서비스 | URL | 로그인 |
|--------|-----|--------|
| **Grafana** | http://monitoring.jiminhome.shop | admin / admin |
| **Prometheus** | http://prometheus.jiminhome.shop | (없음) |

### 2. DNS 설정 (필수!)

```bash
# Windows (PowerShell 관리자 권한)
Add-Content -Path C:\Windows\System32\drivers\etc\hosts -Value "`n192.168.X.200 monitoring.jiminhome.shop"

# Linux/Mac
echo "192.168.X.200 monitoring.jiminhome.shop" | sudo tee -a /etc/hosts
```

### 3. 접근 제한

- **허용 네트워크**: 192.168.X.0/24
- **외부 접근**: 차단 (403 Forbidden)

---

## 🎯 Observability 3 Pillars

```
┌────────────────────────────────────────────────────────┐
│           Full Observability Platform                  │
│                                                         │
│  ┌──────────────┐  ┌──────────────┐  ┌─────────────┐ │
│  │   METRICS    │  │     LOGS     │  │   TRACES    │ │
│  │              │  │              │  │             │ │
│  │  Prometheus  │  │     Loki     │  │    Tempo    │ │
│  │              │  │              │  │             │ │
│  │  130+ node   │  │   7-day      │  │  48h        │ │
│  │  metrics     │  │   retention  │  │  retention  │ │
│  └──────┬───────┘  └──────┬───────┘  └──────┬──────┘ │
│         │                 │                  │         │
│         └─────────────────┼──────────────────┘         │
│                           │                            │
│                  ┌────────┴────────┐                   │
│                  │    Grafana      │                   │
│                  │  통합 시각화     │                   │
│                  └─────────────────┘                   │
└────────────────────────────────────────────────────────┘
```

### 1. Metrics (메트릭) - Prometheus
- **목적**: 시스템 상태 수치화
- **수집 대상**: CPU, Memory, Request Rate, Error Rate
- **보관 기간**: 15일
- **Scrape 주기**: 15초

### 2. Logs (로그) - Loki
- **목적**: 이벤트 기록 및 분석
- **수집 대상**: WEB, WAS, MySQL 로그
- **보관 기간**: 7일
- **Agent**: Grafana Alloy (All-in-One)

### 3. Traces (추적) - Tempo 🆕
- **목적**: 분산 시스템 요청 경로 추적
- **수집 대상**: WAS (OpenTelemetry), Istio Service Mesh
- **보관 기간**: 48시간
- **프로토콜**: OTLP gRPC/HTTP, Jaeger

### Correlation (상관관계)
- **Trace → Logs**: trace_id 기반 로그 검색
- **Trace → Metrics**: Service Map에서 메트릭 표시
- **Logs → Trace**: 로그에서 trace_id 클릭 → Tempo로 이동

---

## 📊 시스템 현황 (요약)

### 운영 상태
- **운영 기간**: 60일 (2024-11-27~)
- **상태**: ✅ 정상 작동
- **Dashboard**: 4개 (System Health, Nginx, MySQL, WAS)
- **Alert Rules**: 8개 (Critical 3, Warning 5)

### Observability 3 Pillars
- ✅ **Prometheus** (메트릭 수집 - 130+ node metrics)
- ✅ **Loki** (로그 수집 - 7-day retention)
- ✅ **Tempo** (분산 추적 - 48h retention) 🆕 2026-01-26
- ✅ **Grafana** (통합 시각화 - Metrics + Logs + Traces)

### Agent & Exporters
- ✅ **Grafana Alloy** (All-in-One Agent, 67% Pod 감소) 🆕
- ✅ **AlertManager** (알림)
- ✅ **Exporters** (nginx, mysql, node, kube-state-metrics)

---

## 🔍 자주 찾는 문제

### Dashboard에 "No data" 표시
→ [TROUBLESHOOTING.md - 메트릭 표시 문제](./TROUBLESHOOTING.md#2-메트릭-표시-문제-no-data)

### 403 Forbidden 에러
→ [TROUBLESHOOTING.md - Dashboard 접근 문제](./TROUBLESHOOTING.md#1-dashboard-접근-문제)

### Prometheus CrashLoopBackOff
→ [TROUBLESHOOTING.md - Prometheus PVC Lock](./TROUBLESHOOTING.md#문제-3-1-crashloopbackoff-pvc-lock)

---

## 🛠️ 시스템 구조

```
┌─────────────────────────────────────────────────┐
│  External (192.168.X.0/24)                      │
│                                                 │
│  Windows PC (192.168.X.195)                    │
│      ↓ DNS: monitoring.jiminhome.shop          │
│      ↓ hosts file                              │
└──────┼──────────────────────────────────────────┘
       │
       ↓ HTTP
┌──────────────────────────────────────────────────┐
│  Kubernetes Cluster                              │
│                                                  │
│  ┌────────────────────────────────┐             │
│  │  Ingress (192.168.X.200)       │             │
│  │  - IP Whitelist: 192.168.X.0/24│             │
│  └────────┬───────────────────────┘             │
│           │                                       │
│  ┌────────┴───────────────────┐                 │
│  │  Monitoring Namespace      │                 │
│  │                            │                 │
│  │  ┌──────────────┐          │                 │
│  │  │  Grafana     │ :3000   │                 │
│  │  │  (Datasources: 3개)    │                 │
│  │  └──────┬───────┘          │                 │
│  │         │                   │                 │
│  │  ┌──────┴──────────────────┐                │
│  │  │  Prometheus  │ :9090    │                │
│  │  │  Loki        │ :3100    │                │
│  │  │  Tempo 🆕    │ :3200    │                │
│  │  └──────┬──────────────────┘                │
│  └─────────┼──────────────────┘                 │
│            ↑                                     │
│            │ Metrics/Logs/Traces 수집           │
│  ┌────────┴───────────────────┐                 │
│  │  Blog System Namespace     │                 │
│  │                            │                 │
│  │  ┌──────┐  ┌──────┐       │                 │
│  │  │ WEB  │  │ WAS  │       │                 │
│  │  └──────┘  └──────┘       │                 │
│  │  ┌──────────────────┐     │                 │
│  │  │  Exporters       │     │                 │
│  │  │  - nginx         │     │                 │
│  │  │  - mysql         │     │                 │
│  │  └──────────────────┘     │                 │
│  └────────────────────────────┘                 │
└──────────────────────────────────────────────────┘
```

---

## 📈 데이터 수집 현황

### 1. Metrics (Prometheus Targets - 11개)
- kubernetes-nodes (3개)
- kubernetes-pods (~20개)
- nginx-exporter
- mysql-exporter
- kube-state-metrics

**보관 기간**: 15일 (기본)

### 2. Logs (Loki)
- WEB (nginx) 로그
- WAS (spring-boot) 로그
- MySQL 로그
- Kubernetes system 로그

**보관 기간**: 7일 (168h)
**자동 삭제**: 매일 UTC 00:00

### 3. Traces (Tempo) 🆕
- WAS (OpenTelemetry Java Agent v1.32.0)
- Istio Service Mesh (CLIENT_AND_SERVER mode)

**보관 기간**: 48시간
**프로토콜**: OTLP gRPC (4317), OTLP HTTP (4318), Jaeger (14250, 14268)
**Sampling**: 100% (always_on)

---

## 🚨 Alert Rules

### Critical (즉시 대응 필요)
- PodDown: Pod가 5분 이상 다운
- HighCPUUsage: CPU 80% 이상 (10분)
- MySQLDown: MySQL 서비스 정지

### Warning (모니터링 필요)
- HighMemoryUsage: Memory 80% 이상 (5분)
- HighRequestRate: Request > 1000 req/s
- SlowQueries: Slow queries > 10
- HighErrorRate: 5xx errors > 10%
- DiskSpaceWarning: Disk 80% 이상

---

## 🔗 유용한 링크

### Grafana Dashboards
- System Health Overview
- Nginx Dashboard
- MySQL Dashboard
- WAS Dashboard

### Prometheus Queries
```promql
# Pod 상태
kube_pod_status_phase{namespace="blog-system"}

# CPU 사용률
sum(rate(container_cpu_usage_seconds_total{namespace="blog-system"}[5m])) by (pod)

# Request Rate
rate(nginx_http_requests_total{namespace="blog-system"}[5m])
```

### 관리 명령어
```bash
# Pod 상태 확인
kubectl get pods -n monitoring

# Prometheus Targets 확인
kubectl exec -n monitoring <prometheus-pod> -- \
  wget -qO- 'http://localhost:9090/api/v1/targets'

# Grafana 로그 확인
kubectl logs -n monitoring -l app=grafana --tail=50
```

---

## 📚 상세 문서

| 문서 | 설명 |
|------|------|
| **[CURRENT-STATUS.md](./CURRENT-STATUS.md)** | 시스템 현황, 접근 방법, Dashboard 목록, Alert Rules |
| **[TROUBLESHOOTING.md](./TROUBLESHOOTING.md)** | 문제 해결 가이드 (접근, 메트릭, Prometheus, Grafana, Loki, Alert) |
| **[NEXT-STEPS.md](./NEXT-STEPS.md)** | 다음 구축 계획 (Slack 연동, Recording Rules, Tracing 등) |

---

## 📝 최근 업데이트

**2026-01-26** 🆕
- ✅ **Full Observability 플랫폼 완성** (PLG → PLTG Stack)
  - Grafana Tempo 배포 (분산 추적 백엔드)
  - WAS OpenTelemetry 계측 (Java Agent v1.32.0)
  - Istio Telemetry 설정 (100% sampling)
  - Log-Trace Correlation (trace_id in logback)
- ✅ **Grafana Alloy 마이그레이션**
  - Promtail + node-exporter 대체
  - 67% Pod 감소 (9개 → 3개)
- ✅ **문서 업데이트**
  - Observability 3 Pillars 구조 반영
  - CURRENT-STATUS.md, NEXT-STEPS.md, README.md 전면 개편

**2026-01-20**
- ✅ 트러블슈팅 문서 통합
- ✅ 현재 상태 문서 신규 작성
- ✅ 문서 구조 재정리 (트러블슈팅 중심)

---

**시스템 상태**: ✅ Full Observability 정상 작동 중 (60일)
**마지막 점검**: 2026-01-26
**Observability Pillars**: Metrics ✅ | Logs ✅ | Traces ✅
