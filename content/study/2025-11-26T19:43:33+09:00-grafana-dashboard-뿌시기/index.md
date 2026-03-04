---
title: "Grafana Dashboard 뿌시기"
date: 2025-11-26T19:43:33+09:00
draft: false
categories: ["Observability"]
tags: ["Grafana","Proemetheus","Dashboard"]
description: "Grafana Dashboard 뿌시기"
author: "늦찌민"
serises: [monitoring]
---


## 들어가며

홈서버에서 Hugo 블로그를 운영하면서 "블로그가 제대로 작동하고 있나?"를 확인하고 싶었습니다. 단순히 접속해보는 것이 아니라, **실시간으로 상태를 모니터링하고, 문제가 생기면 즉시 알 수 있는 시스템**을 만들고 싶었죠.

이 글에서는 Prometheus와 Grafana를 사용해 Nginx 블로그를 완벽하게 모니터링하는 시스템을 구축한 과정을 공유합니다.

<!--more-->

## 목표

- **블로그 상태 실시간 모니터링** (UP/DOWN)
- **접속자 및 요청 통계** (활성 연결, RPS)
- **서버 리소스 모니터링** (CPU, Memory)
- **안정적인 대시보드** (refresh해도 값이 변하지 않음)
- **Grafana 공식 대시보드 활용**

## 환경

```yaml
Kubernetes: v1.32.0 (3 nodes)
Nginx: blog.jiminhome.shop (Hugo)
Prometheus: 이미 배포됨
Grafana: 이미 배포됨
Node Exporter: 이미 배포됨

```

## 전체 아키텍처

```

Nginx (:80/443)
    ↓ stub_status
Nginx Exporter (:9113)
    ↓ metrics (15초마다)
Prometheus (:9090)
    ↓ API
Grafana (:3000)

```

간단하죠? 하지만 이 과정에서 생각보다 많은 문제를 만났습니다.

---

## 1단계: Nginx stub_status 활성화

먼저 Nginx가 메트릭을 제공하도록 설정합니다.

### Nginx 설정

`/etc/nginx/sites-available/blog`:

```nginx
server {
    listen 80;
    server_name blog.jiminhome.shop;

    # Prometheus 메트릭 엔드포인트
    location /nginx_status {
        stub_status on;
        access_log off;
        allow 127.0.0.1;       # localhost
        allow 192.168.0.0/16;  # 내부망
        deny all;
    }

    # HTTPS로 리다이렉트
    location / {
        return 301 https://$host$request_uri;
    }
}
```

### 확인

```bash
curl http://localhost/nginx_status

```

출력:

```

Active connections: 1
server accepts handled requests
 245 245 245
Reading: 0 Writing: 1 Waiting: 0

```

✅ **stub_status가 정상 작동합니다!**

---

## 2단계: Nginx Exporter 배포

stub_status를 Prometheus 형식으로 변환하는 exporter를 배포합니다.

### DaemonSet 배포

`nginx-exporter.yaml`:

```yaml
apiVersion: apps/v1
kind: DaemonSet
metadata:
  name: nginx-exporter
  namespace: monitoring
spec:
  selector:
    matchLabels:
      app: nginx-exporter
  template:
    metadata:
      labels:
        app: nginx-exporter
    spec:
      hostNetwork: true  # localhost 접근 위해
      nodeSelector:
        node-role.kubernetes.io/control-plane: ""
      tolerations:
      - key: node-role.kubernetes.io/control-plane
        operator: Exists
        effect: NoSchedule
      containers:
      - name: nginx-exporter
        image: nginx/nginx-prometheus-exporter:1.1.0
        args:
          - "--nginx.scrape-uri=http://localhost/nginx_status"
        ports:
        - name: metrics
          containerPort: 9113

```

**핵심 포인트:**
- `hostNetwork: true` - Pod가 호스트 네트워크를 사용해 localhost에 접근
- `nodeSelector` - Nginx가 control-plane에만 있으므로 해당 노드에만 배포

### 확인

```bash
kubectl get pods -n monitoring -l app=nginx-exporter
kubectl logs -n monitoring -l app=nginx-exporter

```

메트릭 확인:

```bash
curl http://<node-ip>:9113/metrics | grep nginx_up
# nginx_up 1

```

---

## 3단계: Prometheus 설정

Prometheus가 nginx-exporter를 스크랩하도록 설정합니다.

### prometheus.yml 업데이트

```yaml
scrape_configs:
  - job_name: 'nginx-exporter'
    kubernetes_sd_configs:
      - role: pod
        namespaces:
          names:
            - monitoring
    relabel_configs:
      - source_labels: [__meta_kubernetes_pod_label_app]
        action: keep
        regex: nginx-exporter
      - source_labels: [__meta_kubernetes_pod_node_name]
        target_label: instance
      - source_labels: [__address__]
        target_label: __address__
        regex: '([^:]+)(?::\d+)?'
        replacement: '${1}:9113'

```

### ConfigMap 업데이트 및 재시작

```bash
kubectl create configmap prometheus-config \
  --from-file=prometheus.yml \
  --dry-run=client -o yaml | \
  kubectl apply -n monitoring -f -

kubectl rollout restart -n monitoring deployment prometheus

```

### Prometheus UI 확인

```

http://<prometheus-url>/targets
→ nginx-exporter (1/1 up) ✅

```

쿼리 테스트:

```promql
nginx_up
# Result: 1

```

---

## 4단계: Grafana 대시보드 구성

여기서부터 재미있는(?) 문제들이 시작됩니다.

### 시도 1: Grafana 공식 대시보드 사용

[Dashboard 14900](https://grafana.com/grafana/dashboards/14900-nginx/)을 다운로드해서 임포트했습니다.

**결과: No data** 😱

### 문제 발견: 메트릭 이름 불일치

공식 대시보드가 사용하는 메트릭 이름이 우리와 달랐습니다:

| 대시보드 | 우리 메트릭 |
|---------|-----------|
| `nginx_accepts` | `nginx_connections_accepted` |
| `nginx_active` | `nginx_connections_active` |
| `nginx_requests` | `nginx_http_requests_total` |

### 해결: Python 스크립트로 자동 변환

```python
import json

# 메트릭 매핑 테이블
mappings = {
    'nginx_accepts': 'nginx_connections_accepted',
    'nginx_active': 'nginx_connections_active',
    'nginx_handled': 'nginx_connections_handled',
    'nginx_reading': 'nginx_connections_reading',
    'nginx_writing': 'nginx_connections_writing',
    'nginx_waiting': 'nginx_connections_waiting',
    'nginx_requests': 'nginx_http_requests_total',
}

# 대시보드 JSON 로드
with open('dashboard.json', 'r') as f:
    dashboard = json.load(f)

# 모든 패널의 쿼리 자동 변환
for panel in dashboard['panels']:
    if 'targets' in panel:
        for target in panel['targets']:
            if 'expr' in target:
                for old, new in mappings.items():
                    target['expr'] = target['expr'].replace(old, new)

```

✅ **대시보드가 작동했습니다!**

---

## 5단계: 치명적 문제 발견 - 값이 흔들린다

대시보드를 만들고 기뻐한 것도 잠시, **refresh할 때마다 값이 30% 이상 변동**하는 문제를 발견했습니다.

```

15:00 → 가용성 50%
15:01 → 가용성 90%
15:02 → 가용성 30%
심지어 -3%도 발생! 😱

```

### 원인 분석 1: avg_over_time + 데이터 부족

초기 대시보드의 쿼리:

```promql
avg_over_time(nginx_up[6h]) * 100

```

**문제:**
- Exporter를 2시간 전에 배포
- 6시간 범위 중 4시간은 데이터 없음
- Prometheus가 빈 시간을 **0으로 계산**

계산:

```

현재: 15:00
범위: 09:00 ~ 15:00 (6시간)

09:00~13:00 → 데이터 없음 = 0
13:00~15:00 → nginx_up = 1

평균 = (0×4 + 1×2) / 6 = 33.3%

```

### 원인 분석 2: Grafana의 reduceOptions

더 큰 문제는 Grafana 설정이었습니다:

```json
{
  "options": {
    "reduceOptions": {
      "calcs": ["mean"]  // ← 이게 문제!
    }
  }
}
```

**무슨 일이 일어나나:**

1. Prometheus가 240개 데이터 포인트 반환 (1시간, 15초마다)
2. Grafana가 `mean`으로 평균 계산
3. Refresh할 때마다 time range가 1분씩 이동
4. **다른 240개 포인트 → 다른 평균값**

```

15:00 refresh:
  Range: 14:00 ~ 15:00
  240개 포인트 평균 = 50%

15:01 refresh:
  Range: 14:01 ~ 15:01  ← 1분 이동!
  240개 포인트 평균 = 52%

```

### 해결책: lastNotNull 사용

```json
{
  "options": {
    "reduceOptions": {
      "calcs": ["lastNotNull"]  // ← 정답!
    }
  }
}
```

**효과:**
- 240개 중 **마지막 값만** 사용
- Refresh해도 항상 최신 값
- **절대 흔들리지 않음!**

---

## 최종 대시보드 구성

### 상단 Stat 패널 (6개)

```yaml
1. 블로그 상태:
   Query: nginx_up
   Calc: lastNotNull
   Mapping: 0=DOWN, 1=UP

2. 활성 연결:
   Query: nginx_connections_active
   Calc: lastNotNull

3. 총 요청:
   Query: nginx_http_requests_total
   Calc: lastNotNull

4. 초당 요청:
   Query: rate(nginx_http_requests_total[5m])
   Calc: lastNotNull
   Unit: reqps

5. CPU 사용률:
   Query: 100 - (avg(rate(node_cpu_seconds_total{mode="idle"}[5m])) * 100)
   Calc: lastNotNull
   Unit: percent

6. 메모리 사용률:
   Query: (1 - (node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes)) * 100
   Calc: lastNotNull
   Unit: percent

```

### 그래프 패널 (5개)

1. **블로그 상태 추이** - Step after, 1=UP/0=DOWN
2. **연결 상태 상세** - active/reading/writing/waiting 4개 라인
3. **요청 처리 속도** - RPS + 연결/초
4. **CPU 추이** - Smooth line, threshold colors
5. **메모리 추이** - Smooth line, threshold colors

---

## 핵심 교훈

### 1. Grafana의 Reduce 함수는 매우 중요

**절대 규칙:**
```

Stat 패널 = lastNotNull만 사용
mean, sum 절대 금지

```

### 2. 단순한 쿼리가 최고

```promql
# 복잡하고 불안정
avg_over_time(nginx_up[6h]) * 100

# 단순하고 안정적
nginx_up * 100

```

### 3. Time Range ≠ Query Range

```promql
# Time Range: 그래프 X축 (화면 표시)
# Query Range: 계산 범위 ([30m])
avg_over_time(nginx_up[30m])

```

이 둘은 **완전히 별개**입니다!

### 4. 간단함이 최선

**처음 시도:**
- nginx-log-exporter (Response Code 분석)
- mtail (로그 파싱)
- grok_exporter (패턴 매칭)

**최종 선택:**
- stub_status만 사용
- 설정 간단
- 부하 없음
- **충분히 유용함**

---

## 수집되는 메트릭

### Nginx (nginx-exporter)

```promql
nginx_up                        # 0 or 1
nginx_connections_active        # 현재 활성 연결
nginx_connections_reading       # 읽기 중
nginx_connections_writing       # 쓰기 중
nginx_connections_waiting       # 대기 중 (keep-alive)
nginx_connections_accepted      # 총 연결 (누적)
nginx_http_requests_total       # 총 요청 (누적)

```

### System (node-exporter)

```promql
node_cpu_seconds_total          # CPU 시간
node_memory_MemTotal_bytes      # 총 메모리
node_memory_MemAvailable_bytes  # 사용 가능 메모리

```

---

## 트러블슈팅

### Q: "No data" 표시

**확인 순서:**

1. Prometheus에서 데이터 수집 중?
   ```bash
   curl "http://<prometheus>/api/v1/query?query=nginx_up"
   ```

2. nginx-exporter Pod 상태?
   ```bash
   kubectl get pods -n monitoring -l app=nginx-exporter
   ```

3. Grafana data source 연결?
   - Grafana → Data sources → Prometheus → Save & test

### Q: 값이 계속 변함

**해결:**
- Query Inspector 열기 (패널 → Inspect → Query)
- Calculation이 "Last (not null)"인지 확인
- 쿼리가 단순한지 확인

### Q: -3% 같은 이상한 값

**원인:**
- `mean` 계산 + null 값 처리 오류

**해결:**
- `lastNotNull` 사용
- 단순한 쿼리로 변경

---

## 추후 개선 사항

### 1. Alerting

```yaml
- alert: BlogDown
  expr: nginx_up == 0
  for: 1m
  annotations:
    summary: "블로그 다운!"

```

### 2. Response Code 분석 (선택)

필요시 grok_exporter로 access.log 파싱:
- 200, 404, 500 통계
- 요청 경로별 분석

### 3. SLO/SLI

```promql
# 30일 가용성
(sum_over_time(nginx_up[30d]) / count_over_time(nginx_up[30d])) * 100

```

---

## 결론

**3가지 핵심:**

1. **모니터링은 단순하게**
   - stub_status만으로 충분
   - 복잡한 설정은 오히려 독

2. **Grafana는 신중하게**
   - lastNotNull 필수
   - refresh 영향 고려

3. **기존 리소스 활용**
   - node-exporter는 이미 있었음
   - CPU/Memory 무료 추가

**최종 결과:**
- 블로그 상태 실시간 모니터링
- 성능 지표 (RPS, 연결)
- 시스템 리소스
- **안정적 (절대 흔들리지 않음)**
- 확장 가능

완벽한 블로그 모니터링 시스템, 완성! 🎉

---

## 참고 자료

- [Nginx Prometheus Exporter](https://github.com/nginxinc/nginx-prometheus-exporter)
- [Grafana Dashboard 14900](https://grafana.com/grafana/dashboards/14900-nginx/)
- [Prometheus Documentation](https://prometheus.io/docs/)
- [Node Exporter](https://github.com/prometheus/node_exporter)

---

**질문이나 피드백은 댓글로 남겨주세요!** 💬

