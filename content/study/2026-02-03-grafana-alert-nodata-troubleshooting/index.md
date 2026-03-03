---
title: "Grafana Alert NoData 트러블슈팅: kube-state-metrics 장애 진단"
date: 2026-02-03T16:15:00+09:00
draft: false
categories:
  - study
  - Troubleshooting
tags:
  - kubernetes
  - grafana
  - prometheus
  - monitoring
  - kube-state-metrics
  - observability
---

## 문제 발견

Grafana에서 "Pod Not Ready" alert가 발화했다. blog-system namespace의 web pod 2개에 대한 alert였다.

```
Alert: Pod Not Ready
Namespace: blog-system
Pods:
  - web-65b89ccfbd-5s77b
  - web-7577c6bdf4-s4c8z
Value: A=-1, B=-1, C=-1
State: NoData
```

일반적인 Pod Not Ready alert는 `kube_pod_status_ready` 메트릭이 0을 반환하지만, 이번 경우는 **-1 값**이었다. 이것은 메트릭 자체가 수집되지 않는다는 의미였다.

---

## 증상 분석

### Alert 상세 정보

```yaml
Labels:
  alertname: Pod Not Ready
  instance: kube-state-metrics.monitoring.svc.cluster.local:8080
  job: kube-state-metrics
  namespace: blog-system
  severity: warning

Annotations:
  description: Running 중인 Pod가 10분 동안 Ready 상태가 아닙니다
  grafana_state_reason: NoData
  datasource_uid: PBFA97CFB590B2093
```

### 핵심 단서

1. **Value: A=-1, B=-1, C=-1**
   - -1 값은 Grafana에서 NoData를 의미
   - Alert rule의 모든 query(A, B, C)가 데이터를 받지 못함

2. **instance: kube-state-metrics.monitoring.svc.cluster.local:8080**
   - 메트릭 소스가 kube-state-metrics
   - kube-state-metrics에 문제가 있을 가능성

3. **grafana_state_reason: NoData**
   - 메트릭 수집 자체가 중단됨

---

## 진단 과정

### 가설 수립

**가설**: kube-state-metrics pod가 down되어 메트릭 수집이 중단되었다.

**근거**:
- NoData 상태 (-1 값)
- instance label에 kube-state-metrics 명시
- 2개의 서로 다른 pod에서 동일한 증상 (공통 원인)

### 1단계: kube-state-metrics 상태 확인

```bash
kubectl get pods -n monitoring -l app.kubernetes.io/name=kube-state-metrics
```

**목적**: kube-state-metrics pod의 현재 상태 확인

**결과**: Pod가 Running 상태였지만 최근에 재시작됨을 의심

### 2단계: kube-state-metrics 로그 확인

```bash
kubectl logs -n monitoring -l app.kubernetes.io/name=kube-state-metrics --tail=100
```

**로그 분석**:
```
I0203 07:15:43 - "Starting kube-state-metrics"
I0203 07:15:44 - "Started metrics server" metricsServerAddress="[::]:8080"
I0203 07:15:44 - "Active resources" (pods 포함)
```

**중요 발견**:
- kube-state-metrics가 **07:15:43에 시작**됨
- 즉, 이전에 down 상태였다가 **최근에 재시작**된 것

### 3단계: Web Pod 상태 확인

```bash
kubectl get pods -n blog-system -l app=web -o wide
```

**결과**:
```
NAME                   READY   STATUS    RESTARTS   AGE
web-7577c6bdf4-4h7ht   2/2     Running   0          42m
web-7577c6bdf4-s4c8z   2/2     Running   0          41m
```

**중요 발견**:
- Alert에 표시된 `web-7577c6bdf4-s4c8z`가 **현재 존재하고 정상** (READY 2/2)
- AGE가 41분 → kube-state-metrics 재시작(07:15) 이전부터 존재
- Pod 자체는 문제가 없었음

### 4단계: 타임라인 재구성

```
[06:33] web pod들 시작 (AGE 42m, 41m)
    ↓
[추정: 06:35~07:05] kube-state-metrics down
    ↓
[10분간] kube_pod_status_ready 메트릭 수집 중단
    ↓
[Alert 발화] NoData 상태로 "Pod Not Ready" alert
    ↓
[07:15] kube-state-metrics 재시작
    ↓
[현재] Pod는 정상, Alert는 해소 대기 중
```

---

## 근본 원인 분석

### 메트릭 수집 체인

```
kube-state-metrics (메트릭 export)
    ↓
Prometheus (scraping, 15s 간격)
    ↓
Grafana (evaluation, 1m 간격)
    ↓
Alert Rule (condition 평가)
```

### 장애 메커니즘

1. **kube-state-metrics 장애**
   - kube-state-metrics pod가 crash 또는 restart
   - `kube_pod_status_ready` 메트릭 export 중단

2. **Prometheus 스크래핑 실패**
   - Target이 없어져서 메트릭 수집 불가
   - 기존 메트릭도 시간 경과로 stale 처리됨

3. **Grafana Alert Rule NoData 처리**
   - Alert rule의 `noDataState: "Alerting"` 설정
   - NoData를 장애 상황으로 간주
   - 10분간 NoData 지속 → Alert 발화

4. **False Alert 발생**
   - **실제로는 Pod가 정상**이었음
   - 단지 메트릭 수집이 안 되어서 상태를 알 수 없었던 것

---

## 해결 과정

### 즉시 조치

**조치 불필요**: Pod는 정상, kube-state-metrics는 이미 재시작됨

### Alert 해소 대기

**Alert Resolution 조건**:
```
1. kube-state-metrics 메트릭 export 재개
2. Prometheus scraping 성공
3. Grafana evaluation (1분 간격)
4. Alert condition이 false로 평가
5. for: 10m 대기 (10분간 정상 상태 유지)
6. Alert Resolved
```

**예상 해소 시간**: kube-state-metrics 재시작 후 약 10~15분

---

## 교훈 및 개선 방안

### 1. NoData Alert의 의미 이해

**NoData vs Normal Alert**:

| 상태 | 의미 | 값 | 원인 |
|------|------|-----|------|
| Normal Alert | 메트릭이 조건 위반 | 0 (Not Ready) | Pod 실제 문제 |
| NoData | 메트릭 수집 안 됨 | -1 | 모니터링 시스템 문제 |

**핵심**: NoData alert는 **모니터링 대상이 아닌 모니터링 시스템 자체**의 문제를 의미한다.

### 2. 계층별 진단 순서의 중요성

**올바른 진단 순서**:
```
1. 메트릭 수집 계층 (kube-state-metrics)
2. 전송 계층 (Prometheus scraping)
3. 평가 계층 (Grafana datasource)
4. 애플리케이션 계층 (실제 Pod)
```

**잘못된 접근**:
- Pod 로그부터 확인 → 시간 낭비
- 바로 재시작 시도 → 근본 원인 파악 실패

### 3. 모니터링 시스템을 모니터링하라

**현재 문제점**:
- kube-state-metrics가 down되어도 즉시 알 수 없음
- 다른 alert가 발화하고 나서야 간접적으로 알게 됨

**개선 방안**:

#### (1) kube-state-metrics HA 구성
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: kube-state-metrics
spec:
  replicas: 2  # HA 구성
  strategy:
    type: RollingUpdate
```

#### (2) Prometheus Target Down Alert 추가
```yaml
- alert: PrometheusTargetDown
  expr: up{job="kube-state-metrics"} == 0
  for: 5m
  annotations:
    summary: "kube-state-metrics target is down"
    description: "Prometheus cannot scrape kube-state-metrics"
```

#### (3) Alert Rule noDataState 설정 변경
```yaml
# 현재 (문제 있음)
noDataState: "Alerting"  # NoData를 장애로 간주 → False Alert

# 개선 (권장)
noDataState: "OK"  # NoData를 정상으로 간주
```

**Trade-off**:
- `Alerting`: 메트릭 수집 문제를 즉시 알 수 있지만 False Alert 발생
- `OK`: False Alert는 없지만 메트릭 수집 문제를 늦게 알 수 있음

**Best Practice**: `noDataState: "OK"` + 별도의 Target Down alert 추가

### 4. Alert Rule 설계 원칙

**원칙 1: 관심사 분리**
- 애플리케이션 장애 alert ≠ 모니터링 시스템 장애 alert
- 각각 별도의 alert rule로 관리

**원칙 2: 명확한 alert 메시지**
```yaml
# 나쁜 예
description: "Pod가 Ready 상태가 아닙니다"

# 좋은 예
description: "{{ if eq .Values.A -1 }}메트릭 수집 안 됨 (kube-state-metrics 확인){{ else }}Pod가 Not Ready{{ end }}"
```

**원칙 3: Runbook 링크 제공**
```yaml
annotations:
  runbook_url: "https://wiki/troubleshooting/pod-not-ready"
```

### 5. 문서화의 중요성

**이번 사례처럼**:
- 트러블슈팅 과정을 문서화
- 진단 순서를 체계화
- 다음 장애 시 빠른 대응 가능

---

## 참고 명령어

### 진단 명령어

```bash
# 1. kube-state-metrics 상태 확인
kubectl get pods -n monitoring -l app.kubernetes.io/name=kube-state-metrics

# 2. kube-state-metrics 로그 확인
kubectl logs -n monitoring -l app.kubernetes.io/name=kube-state-metrics --tail=100

# 3. 대상 Pod 상태 확인
kubectl get pods -n <namespace> -l app=<app-name> -o wide

# 4. Prometheus target 확인
kubectl port-forward -n monitoring svc/prometheus 9090:9090
# 브라우저: http://localhost:9090/targets

# 5. Prometheus 메트릭 쿼리
# 브라우저: http://localhost:9090/graph
# Query: kube_pod_status_ready{namespace="blog-system"}

# 6. Grafana datasource 테스트
kubectl port-forward -n monitoring svc/grafana 3000:3000
# 브라우저: http://localhost:3000
# Configuration > Data sources > Prometheus > Test
```

### 확인 체크리스트

```bash
# Alert 발생 시 체크리스트
1. [ ] Alert value가 -1 (NoData)인가? 0 (실제 장애)인가?
2. [ ] instance label에 어떤 컴포넌트가 명시되어 있는가?
3. [ ] 해당 컴포넌트(kube-state-metrics)가 Running인가?
4. [ ] 최근에 재시작되었는가? (로그 시간 확인)
5. [ ] Prometheus target이 UP인가?
6. [ ] 대상 리소스(Pod 등)가 실제로 존재하는가?
7. [ ] 대상 리소스가 정상 상태인가?
```

---

## 결론

이번 트러블슈팅에서 배운 핵심 교훈:

1. **NoData는 모니터링 시스템의 문제다**
   - 대상이 아닌 모니터링 체인부터 확인

2. **계층별 진단 순서를 지켜라**
   - 메트릭 수집 → 전송 → 평가 → 애플리케이션

3. **모니터링 시스템을 모니터링하라**
   - kube-state-metrics, Prometheus 자체를 모니터링
   - HA 구성으로 SPOF 제거

4. **Alert Rule 설계를 신중히**
   - noDataState 설정 검토
   - 관심사 분리
   - 명확한 메시지와 Runbook 제공

5. **트러블슈팅을 문서화하라**
   - 다음 장애 시 빠른 대응
   - 팀 전체의 역량 향상

---

## 참고 자료

- [Grafana Alerting - NoData and Error Handling](https://grafana.com/docs/grafana/latest/alerting/fundamentals/alert-rules/state-and-health/#nodata-and-error-handling)
- [kube-state-metrics Documentation](https://github.com/kubernetes/kube-state-metrics)
- [Prometheus Alerting Best Practices](https://prometheus.io/docs/practices/alerting/)
