# 다음 구축 계획

> 현재 구축 완료: PLTG Stack (Full Observability) + 4 Dashboards + 8 Alert Rules

---

## 📊 현재 완료 상태

| 항목 | 상태 |
|------|------|
| **Observability 3 Pillars** | |
| Prometheus | ✅ 메트릭 수집, Alert Rules 8개 |
| Loki | ✅ 로그 수집 (7-day retention) |
| Tempo | ✅ 분산 추적 (48h retention) 🆕 2026-01-26 |
| **Visualization & Alerting** | |
| Grafana | ✅ Dashboard 4개, Datasources 3개 (Prometheus/Loki/Tempo) |
| AlertManager | ✅ 실행 중 (Slack 연동 대기) |
| **Agents & Exporters** | |
| Grafana Alloy | ✅ All-in-One Agent (67% Pod 감소) 🆕 |
| Exporters | ✅ nginx, mysql, node, kube-state-metrics |
| **Instrumentation** | |
| WAS OpenTelemetry | ✅ Java Agent v1.32.0, trace_id logging 🆕 |
| Istio Telemetry | ✅ 100% sampling, Tempo provider 🆕 |

---

## 🎯 우선순위별 계획

### 🔥 단기 (1-2시간)

#### 1. Slack 알림 통합 ⭐⭐⭐
**목적**: Alert 발생 시 즉시 Slack으로 알림

**현재 상태**: AlertManager 템플릿 준비됨 (주석 처리)

**작업 내용**:
```bash
# 1. Slack Incoming Webhook 생성
# https://api.slack.com/messaging/webhooks

# 2. AlertManager ConfigMap 수정
kubectl edit configmap -n monitoring alertmanager-config

# 3. webhook_url 설정 후 재시작
kubectl rollout restart deployment -n monitoring alertmanager
```

**효과**: Alert 놓침 방지, 빠른 대응
**소요 시간**: 15분

---

#### 2. Prometheus Recording Rules ⭐⭐
**목적**: 복잡한 쿼리 사전 계산 → Dashboard 로딩 속도 향상

**예시**:
```yaml
# recording-rules.yml
groups:
  - name: blog-system-recordings
    interval: 30s
    rules:
      # CPU 사용률 사전 계산
      - record: blog_system:pod_cpu_usage:percent
        expr: |
          sum(rate(container_cpu_usage_seconds_total{namespace="blog-system"}[5m])) by (pod)
          / sum(container_spec_cpu_quota{namespace="blog-system"}) by (pod) * 100
```

**효과**: Dashboard 로딩 시간 단축 (10초 → 1초)
**소요 시간**: 30분

---

### 📅 중기 (1-2일)

#### 3. ✅ 완료: Distributed Tracing (Tempo) ⭐⭐⭐
**목적**: Request 추적 (WEB → WAS → MySQL)

**완료 작업** (2026-01-26):
- ✅ Grafana Tempo 배포 (OTLP gRPC/HTTP receiver)
- ✅ WAS OpenTelemetry 계측 (Java Agent v1.32.0)
- ✅ Istio Telemetry 설정 (100% sampling, Tempo provider)
- ✅ Log-Trace Correlation (trace_id in logback)
- ✅ Grafana Datasources 연동 (Traces ↔ Logs ↔ Metrics)

**효과**: 병목 구간 파악, 디버깅 시간 10분 → 10초

**다음 단계** (선택 사항):
- ⏳ Istio Ingress Gateway trace 시작점 설정
- ⏳ Nginx (WEB) trace context propagation
- ⏳ End-to-End Trace 검증 (Gateway → WEB → WAS → MySQL)
- ⏳ Unified Dashboard (Service Map + Golden Signals)
- ⏳ Trace Sampling 조정 (100% → 10%)

---

#### 4. Service Mesh Observability (Istio) ⭐⭐
**목적**: mTLS, Circuit Breaker, Retry 메트릭 수집

**필요 작업**:
1. Istio Prometheus integration 활성화
2. Kiali Dashboard 설치 (Service Mesh 시각화)

**효과**: Service Mesh 동작 가시화

---

#### 5. Custom Business Metrics ⭐
**목적**: 비즈니스 지표 수집

**예시 메트릭**:
- 게시글 작성 횟수
- API 응답 시간 (endpoint별)
- 사용자 활동 (페이지뷰, 체류시간)

**필요 작업**:
1. Spring Boot Actuator 활성화
2. Micrometer 커스텀 메트릭 추가
3. Grafana Dashboard 생성

---

### 🌟 장기 (1주~)

#### 6. Log Aggregation 고도화 ⭐
**목적**: 로그 검색 및 분석 개선

**작업 내용**:
- Loki Query 최적화
- 로그 보관 기간 조정 (30일 → 90일)
- Error 로그 자동 Alert 설정

---

#### 7. Performance Dashboard ⭐
**목적**: 성능 분석 전용 Dashboard

**포함 메트릭**:
- P50, P95, P99 Response Time
- Apdex Score (사용자 만족도)
- Throughput (req/s)
- Error Rate (%)

---

#### 8. Synthetic Monitoring ⭐
**목적**: 외부에서 주기적으로 Health Check

**도구 옵션**:
- Blackbox Exporter (HTTP probe)
- Uptime Kuma (간단한 UI)

**효과**: 사용자 관점의 가용성 모니터링

---

## 🛠️ 권장 순서

```
✅ 완료: Distributed Tracing (Tempo) - Full Observability 구축
    ↓
1단계 (즉시): Slack 알림 → Recording Rules
    ↓
2단계 (선택): Trace 고도화 (Entry Point, Unified Dashboard)
    ↓
3단계 (필요 시): Service Mesh Observability
    ↓
4단계 (비즈니스 요구 시): Custom Business Metrics
    ↓
5단계 (시스템 안정화 후): 나머지 고도화 작업
```

---

## 📝 참고 자료

### Slack 연동
- [Alertmanager Slack Configuration](https://prometheus.io/docs/alerting/latest/configuration/#slack_config)

### Recording Rules
- [Prometheus Recording Rules Guide](https://prometheus.io/docs/prometheus/latest/configuration/recording_rules/)

### Distributed Tracing
- [OpenTelemetry Java Guide](https://opentelemetry.io/docs/instrumentation/java/)
- [Jaeger Installation](https://www.jaegertracing.io/docs/latest/getting-started/)

### Service Mesh
- [Istio Prometheus Integration](https://istio.io/latest/docs/ops/integrations/prometheus/)
- [Kiali Dashboard](https://kiali.io/)

---

## 📚 관련 문서

- **현재 상태**: [CURRENT-STATUS.md](./CURRENT-STATUS.md)
- **트러블슈팅**: [TROUBLESHOOTING.md](./TROUBLESHOOTING.md)
- **메인 가이드**: [README.md](./README.md)

---

**최근 완료**: Distributed Tracing (Tempo) - Full Observability ✅
**우선 작업**: Slack 알림 통합 (15분)
**다음 작업**: Recording Rules (30분)
