---
title: "Blackbox Exporter 대시보드 복구: 메트릭이 수집되지 않는 문제"
date: 2026-02-10T09:30:00+09:00
draft: false
categories:
  - study
  - Troubleshooting
tags:
  - Prometheus
  - Blackbox Exporter
  - Grafana
  - Kubernetes
  - Monitoring
  - Istio
  - Cilium
description: "Blackbox Exporter 대시보드에 데이터가 표시되지 않는 문제를 해결한 과정. Prometheus Operator의 Probe CRD, Istio AuthorizationPolicy, Cilium NetworkPolicy의 계층적 보안 구조를 이해하고 적용."
---

## 문제 상황

Grafana에 Blackbox Exporter 대시보드를 배포했지만, 모든 패널에 "No data"가 표시되었다. WEB, WAS, MySQL 서비스의 가용성을 모니터링하기 위해 구축한 대시보드였지만, 실제로는 어떤 메트릭도 수집되지 않고 있었다.

## 증상 분석

### 대시보드 쿼리
```promql
# WEB 서비스
probe_success{job="blackbox-http",instance=~".*web-service.*"}

# WAS 서비스
probe_success{job="blackbox-http",instance=~".*was-service.*"}

# MySQL TCP
probe_success{job="blackbox-tcp",instance=~".*mysql.*"}
```

### Prometheus 메트릭 확인
```bash
kubectl exec -n monitoring prometheus-xxx -c prometheus -- \
  wget -q -O- "http://localhost:9090/api/v1/query?query=probe_success"

# 결과: {"data":{"result":[]}}
```

메트릭이 전혀 수집되지 않고 있었다. Blackbox Exporter Pod는 정상 실행 중이었으므로, 설정 문제임을 파악했다.

## 근본 원인

### 문제 1: Probe 리소스 구조 불일치

기존 Probe 리소스를 확인했다.

```bash
kubectl get probe -n monitoring
# NAME              AGE
# blog-http-probe   3d18h

kubectl get probe blog-http-probe -n monitoring -o yaml
```

```yaml
apiVersion: monitoring.coreos.com/v1
kind: Probe
metadata:
  name: blog-http-probe
  namespace: monitoring
spec:
  interval: 30s
  module: http_2xx
  prober:
    url: blackbox-exporter.monitoring.svc.cluster.local:9115
    path: /probe
  targets:
    staticConfig:
      static:
      - http://blog.jiminhome.shop
```

**문제점**:
1. Probe가 1개만 존재 (WEB, WAS, MySQL 분리 필요)
2. 타겟이 외부 URL (`http://blog.jiminhome.shop`)
3. `jobName` 필드 없음 → 자동 생성: `probe/monitoring/blog-http-probe`

대시보드는 `job="blackbox-http"`를 기대하지만, 실제 메트릭은 `job="probe/monitoring/blog-http-probe"`로 생성되어 매칭되지 않았다.

### 문제 2: WAS Probe 실패 (403 Forbidden)

WAS 서비스에 대한 HTTP probe를 생성하고 테스트했다.

```bash
kubectl exec -n monitoring blackbox-exporter-xxx -- \
  wget "http://was-service.blog-system.svc.cluster.local:8080"

# 결과: HTTP/1.1 403 Forbidden
```

Istio AuthorizationPolicy를 확인했다.

```yaml
# authz-was.yaml (기존)
apiVersion: security.istio.io/v1beta1
kind: AuthorizationPolicy
metadata:
  name: was-authz
  namespace: blog-system
spec:
  selector:
    matchLabels:
      app: was
  action: ALLOW
  rules:
  - to:
    - operation:
        ports: ["8080"]
        paths: ["/api/*", "/actuator/*", "/auth", "/auth/*"]
```

Blackbox Exporter는 기본적으로 `/` 경로로 HTTP GET 요청을 보내는데, AuthorizationPolicy는 `/api/*`, `/actuator/*`, `/auth*`만 허용했다. `/` 경로는 허용 목록에 없어서 403 Forbidden이 발생했다.

### 문제 3: MySQL TCP Probe 차단

MySQL TCP probe를 테스트했다.

```bash
kubectl exec -n monitoring blackbox-exporter-xxx -- \
  nc -zv mysql-service.blog-system.svc.cluster.local 3306

# 결과: Connection refused
```

Cilium NetworkPolicy를 확인했다.

```yaml
# cilium-netpol.yaml - mysql-isolation (기존)
apiVersion: cilium.io/v2
kind: CiliumNetworkPolicy
metadata:
  name: mysql-isolation
  namespace: blog-system
spec:
  endpointSelector:
    matchLabels:
      app: mysql
  ingress:
  - fromEndpoints:
    - matchLabels:
        app: was
        io.kubernetes.pod.namespace: blog-system
    toPorts:
    - ports:
      - port: "3306"
        protocol: TCP
  # Rule 2-4: mysql-backup, mysql-exporter
```

NetworkPolicy는 `was`, `mysql-backup`, `mysql-exporter`만 허용하고, `blackbox-exporter` (monitoring namespace)는 차단했다.

## 해결 과정

### 해결 1: Probe 리소스 재구성

3개의 Probe 리소스로 분리하고, `jobName`을 명시했다.

```yaml
# configs/monitoring/probes/blog-system.yaml
---
# WEB Service HTTP Probe
apiVersion: monitoring.coreos.com/v1
kind: Probe
metadata:
  name: web-http-probe
  namespace: monitoring
  labels:
    prometheus: kube-prometheus
spec:
  jobName: blackbox-http  # 대시보드 쿼리와 일치
  interval: 30s
  scrapeTimeout: 10s
  prober:
    url: blackbox-exporter.monitoring.svc.cluster.local:9115
    path: /probe
  module: http_2xx
  targets:
    staticConfig:
      static:
      # 내부 Service DNS 사용
      - http://web-service.blog-system.svc.cluster.local:80

---
# WAS Service HTTP Probe
apiVersion: monitoring.coreos.com/v1
kind: Probe
metadata:
  name: was-http-probe
  namespace: monitoring
  labels:
    prometheus: kube-prometheus
spec:
  jobName: blackbox-http
  interval: 30s
  scrapeTimeout: 10s
  prober:
    url: blackbox-exporter.monitoring.svc.cluster.local:9115
    path: /probe
  module: http_2xx
  targets:
    staticConfig:
      static:
      # Health check 엔드포인트 명시
      - http://was-service.blog-system.svc.cluster.local:8080/actuator/health

---
# MySQL Service TCP Probe
apiVersion: monitoring.coreos.com/v1
kind: Probe
metadata:
  name: mysql-tcp-probe
  namespace: monitoring
  labels:
    prometheus: kube-prometheus
spec:
  jobName: blackbox-tcp  # TCP probe는 별도 job
  interval: 30s
  scrapeTimeout: 10s
  prober:
    url: blackbox-exporter.monitoring.svc.cluster.local:9115
    path: /probe
  module: tcp_connect
  targets:
    staticConfig:
      static:
      - mysql-service.blog-system.svc.cluster.local:3306
```

**핵심 변경사항**:
1. `jobName` 명시 → 대시보드 쿼리 `job="blackbox-http"`, `job="blackbox-tcp"`와 일치
2. 내부 Service DNS 사용 → `instance` label에 서비스 이름 포함
3. WAS는 `/actuator/health` 경로 지정 → AuthorizationPolicy 통과

### 해결 2: Istio AuthorizationPolicy 수정

monitoring namespace에서 WAS health check 접근을 허용했다.

```yaml
# services/blog-system/was/authz-was.yaml
apiVersion: security.istio.io/v1beta1
kind: AuthorizationPolicy
metadata:
  name: was-authz
  namespace: blog-system
spec:
  selector:
    matchLabels:
      app: was
  action: ALLOW
  rules:
  # Rule 1: 기존 애플리케이션 트래픽 (변경 없음)
  - to:
    - operation:
        ports: ["8080"]
        paths: ["/api/*", "/actuator/*", "/auth", "/auth/*"]

  # Rule 2: Blackbox Exporter Health Check (신규 추가)
  - from:
    - source:
        namespaces: ["monitoring"]
    to:
    - operation:
        ports: ["8080"]
        paths: ["/actuator/health"]

  # Rule 3: Prometheus 메트릭 수집 (기존)
  - to:
    - operation:
        ports: ["15090"]
```

**보안 유지**: monitoring namespace만, health check 엔드포인트만 허용

### 해결 3: Cilium NetworkPolicy 수정

blackbox-exporter가 MySQL TCP 포트에 연결할 수 있도록 허용했다.

```yaml
# services/blog-system/common/cilium-netpol.yaml
apiVersion: cilium.io/v2
kind: CiliumNetworkPolicy
metadata:
  name: mysql-isolation
  namespace: blog-system
spec:
  endpointSelector:
    matchLabels:
      app: mysql
  ingress:
  # Rule 1-4: 기존 규칙 (was, mysql-backup, mysql-exporter)

  # Rule 5: Blackbox Exporter TCP Probe (신규 추가)
  - fromEndpoints:
    - matchLabels:
        app: blackbox-exporter
        io.kubernetes.pod.namespace: monitoring
    toPorts:
    - ports:
      - port: "3306"
        protocol: TCP
```

**보안 유지**: TCP handshake만 허용, 데이터 접근 불가

## 검증

### Prometheus 메트릭 확인
```bash
kubectl exec -n monitoring prometheus-xxx -c prometheus -- \
  wget -q -O- "http://localhost:9090/api/v1/query?query=probe_success"
```

```json
{
  "data": {
    "result": [
      {
        "metric": {
          "job": "blackbox-http",
          "instance": "http://web-service.blog-system.svc.cluster.local:80"
        },
        "value": [1770715204, "1"]
      },
      {
        "metric": {
          "job": "blackbox-http",
          "instance": "http://was-service.blog-system.svc.cluster.local:8080/actuator/health"
        },
        "value": [1770715204, "1"]
      },
      {
        "metric": {
          "job": "blackbox-tcp",
          "instance": "mysql-service.blog-system.svc.cluster.local:3306"
        },
        "value": [1770715204, "1"]
      }
    ]
  }
}
```

### HTTP 상태 코드 확인
```bash
kubectl exec -n monitoring prometheus-xxx -c prometheus -- \
  wget -q -O- "http://localhost:9090/api/v1/query?query=probe_http_status_code"
```

```
✅ WEB:   HTTP 200
✅ WAS:   HTTP 200
✅ MySQL: TCP Connected
```

### 대시보드 쿼리 매칭 확인

| 패널 | 쿼리 | 실제 메트릭 | 매칭 |
|------|------|-----------|------|
| WEB 서비스 | `instance=~".*web-service.*"` | `http://web-service.blog-system...` | ✅ |
| WAS 서비스 | `instance=~".*was-service.*"` | `http://was-service.blog-system...` | ✅ |
| MySQL (TCP) | `instance=~".*mysql.*"` | `mysql-service.blog-system...` | ✅ |

모든 쿼리가 정상적으로 메트릭과 매칭되었다.

## 학습 내용

### Prometheus Operator의 Probe CRD

Prometheus Operator는 Probe 리소스를 감지하여 자동으로 Prometheus 설정을 생성한다.

**jobName 필드의 중요성**:
```yaml
# jobName 없을 때
spec:
  # 자동 생성: job_name = "probe/<namespace>/<probe-name>"
  targets: [...]

# jobName 있을 때
spec:
  jobName: blackbox-http  # job_name = "blackbox-http"
  targets: [...]
```

대시보드 쿼리와 메트릭 label을 일치시키려면 `jobName`을 명시해야 한다.

### Blackbox Exporter의 Probe 경로

타겟 URL에 경로를 포함시키면 해당 경로로 프로브한다.

```yaml
# "/" 경로로 프로브
targets:
  - http://was-service:8080

# "/actuator/health" 경로로 프로브
targets:
  - http://was-service:8080/actuator/health
```

AuthorizationPolicy의 `paths` 제약과 일치시켜야 한다.

### 계층적 보안 구조

**Cilium NetworkPolicy (L3/L4)**:
- IP 주소, 포트, 프로토콜 기반
- Namespace 간 네트워크 격리
- TCP handshake 단계에서 적용

**Istio AuthorizationPolicy (L7)**:
- HTTP 메서드, 경로, 헤더 기반
- mTLS, JWT 인증
- HTTP 요청 파싱 후 적용

**디버깅 순서**:
1. Cilium NetworkPolicy 확인 → TCP connection refused
2. Istio AuthorizationPolicy 확인 → HTTP 403 Forbidden

### Platform Conformity 원칙

Helm Chart가 정한 표준 label을 사용해야 Prometheus가 리소스를 인식한다.

```yaml
# kube-prometheus-stack values.yaml
prometheus:
  prometheusSpec:
    probeSelector:
      matchLabels:
        prometheus: kube-prometheus

# Probe 리소스
apiVersion: monitoring.coreos.com/v1
kind: Probe
metadata:
  labels:
    prometheus: kube-prometheus  # 필수!
```

## 체크리스트

Blackbox Exporter 설정 시 확인 사항:

**Probe 리소스 작성**:
- [ ] `prometheus: kube-prometheus` label 추가
- [ ] `jobName` 필드 명시
- [ ] 내부 Service DNS 사용
- [ ] HTTP probe는 경로 지정

**접근 제어 설정**:
- [ ] Cilium NetworkPolicy: Blackbox Exporter → Target Pod (L3/L4)
- [ ] Istio AuthorizationPolicy: monitoring namespace → Target Service (L7)
- [ ] Health check 엔드포인트만 허용

**배포 후 검증**:
- [ ] Prometheus Target: `health: "up"`
- [ ] 메트릭 수집: `probe_success = 1`
- [ ] HTTP 상태: `probe_http_status_code = 200`
- [ ] 대시보드: 모든 패널에 데이터 표시

## 결론

Blackbox Exporter 대시보드 복구 과정에서 Prometheus Operator의 Probe CRD 동작 방식, 계층적 보안 구조(Cilium + Istio), Platform Conformity 원칙의 중요성을 이해했다.

**핵심 교훈**:
1. 대시보드 작성 전 Prometheus에서 메트릭 확인 필수
2. 대시보드 쿼리와 실제 메트릭 label 일치 확인
3. 접근 제어는 L3/L4 (Cilium) → L7 (Istio) 순서로 디버깅
4. Helm Chart 표준 label 사용 (Platform Conformity)

메트릭 수집이 안 되는 문제는 설정 불일치가 대부분이다. 체계적인 검증 절차를 따르면 원인을 빠르게 찾을 수 있다.
