# Monitoring 시스템 현재 상태

> PLG Stack (Prometheus + Loki + Grafana) 운영 현황
> 최종 업데이트: 2026-01-23

---

## 📊 시스템 개요

### 운영 기간
- **시작일**: 2024-11-27
- **운영 일수**: 58일
- **안정성**: ✅ 정상 작동 중

### 구축된 컴포넌트
- ✅ Prometheus (메트릭 수집)
- ✅ Grafana (시각화)
- ✅ Loki (로그 수집)
- ✅ AlertManager (알림)
- ✅ Exporters (nginx, mysql, node, kube-state-metrics)

---

## 🔗 접근 방법

### 1. DNS 설정 (필수!)

**로컬 네트워크 전용 도메인**이므로 hosts 파일 설정 필요:

```bash
# Windows (PowerShell 관리자 권한)
Add-Content -Path C:\Windows\System32\drivers\etc\hosts -Value "`n192.168.X.200 monitoring.jiminhome.shop"
Add-Content -Path C:\Windows\System32\drivers\etc\hosts -Value "192.168.X.200 prometheus.jiminhome.shop"

# Linux/Mac
sudo bash -c 'cat >> /etc/hosts << EOF
192.168.X.200 monitoring.jiminhome.shop
192.168.X.200 prometheus.jiminhome.shop
EOF'
```

### 2. 접근 URL

| 서비스 | URL | 로그인 |
|--------|-----|--------|
| **Grafana** | http://monitoring.jiminhome.shop | admin / admin |
| **Prometheus** | http://prometheus.jiminhome.shop | (없음) |
| **AlertManager** | http://monitoring.jiminhome.shop:9093 | (없음) |

### 3. 접근 제한

**허용 네트워크**: 192.168.X.0/24

```bash
# 접근 테스트
curl -I http://monitoring.jiminhome.shop

# 성공: HTTP/1.1 200 OK
# 실패: HTTP/1.1 403 Forbidden
```

**LoadBalancer 설정**:
- externalTrafficPolicy: **Local** (원본 IP 보존)
- loadBalancerIP: 192.168.X.200

---

## 🏗️ 인프라 상태

### Pod 상태 (2026-01-20 기준)

```bash
kubectl get pods -n monitoring
```

| Pod | 상태 | 노드 | 재시작 | 실행 시간 |
|-----|------|------|--------|-----------|
| prometheus-586bfbd66f-zh24m | Running | k8s-worker2 | 0 | 74분 |
| grafana-577c4944db-9vxvb | Running | k8s-worker2 | 0 | 6시간 |
| loki-stack-0 | Running | k8s-worker1 | 0 | 17시간 |
| alertmanager-6df68c4764-5f62d | Running | k8s-worker2 | 0 | 19시간 |
| loki-stack-promtail-xxx (3개) | Running | 모든 노드 | 0 | - |

### Service 상태

```bash
kubectl get svc -n monitoring
```

| Service | Type | Port | 용도 |
|---------|------|------|------|
| grafana | NodePort | 3000:30300 | Dashboard |
| prometheus | NodePort | 9090:30090 | 메트릭 쿼리 |
| loki-stack | ClusterIP | 3100 | 로그 수집 |
| alertmanager | ClusterIP | 9093 | 알림 발송 |

### Exporter 상태

| Exporter | Namespace | Port | 수집 대상 |
|----------|-----------|------|-----------|
| nginx-exporter | blog-system | 9113 | WEB Pod 메트릭 |
| mysql-exporter | blog-system | 9104 | MySQL 메트릭 |
| kube-state-metrics | monitoring | 8080 | Pod/Deployment 상태 |
| node-exporter | kube-system | 9100 | 노드 리소스 |

---

## 📈 Dashboard 목록

### 1. System Health Overview
- **목적**: 전체 시스템 상태 한눈에 보기
- **메트릭**:
  - WEB/WAS/MySQL Pod 상태
  - CPU/Memory 사용률
  - Request Rate
- **URL**: Grafana → Dashboards → System Health Overview

### 2. Nginx Dashboard
- **목적**: WEB 서버 모니터링
- **메트릭**:
  - Active Connections
  - Request Rate
  - Response Time
  - HTTP Status Codes (2xx, 4xx, 5xx)
- **Alert**: Request Rate > 1000 req/s

### 3. MySQL Dashboard
- **목적**: 데이터베이스 모니터링
- **메트릭**:
  - MySQL Status (Up/Down)
  - Connections (Current/Max)
  - Query Rate
  - Slow Queries
- **Alert**: MySQL Down, Slow Queries > 10

### 4. WAS Dashboard
- **목적**: Spring Boot 애플리케이션 모니터링
- **메트릭**:
  - JVM Heap Memory
  - Thread Count
  - GC Duration
  - API Response Time

---

## 🚨 Alert Rules (8개)

### Critical (3개)

| Alert | 조건 | 설명 |
|-------|------|------|
| **PodDown** | Pod 다운 5분 이상 | Pod가 Running 상태가 아님 |
| **HighCPUUsage** | CPU 사용률 > 80% (10분) | Pod CPU 사용률 임계값 초과 |
| **MySQLDown** | MySQL 다운 | MySQL 서비스 정지 |

### Warning (5개)

| Alert | 조건 | 설명 |
|-------|------|------|
| **HighMemoryUsage** | Memory > 80% (5분) | Pod 메모리 사용률 높음 |
| **HighRequestRate** | Request > 1000 req/s | 트래픽 급증 |
| **SlowQueries** | Slow queries > 10 (5분) | 느린 쿼리 발생 |
| **HighErrorRate** | 5xx errors > 10% (5분) | 서버 에러 비율 높음 |
| **DiskSpaceWarning** | Disk 사용률 > 80% | 디스크 공간 부족 |

### AlertManager 설정
- **상태**: 실행 중
- **발송 대상**: (설정 필요)
- **템플릿**: Slack 연동 준비됨 (주석 처리)

---

## 📊 데이터 수집 현황

### Prometheus Targets (11개)

```bash
kubectl exec -n monitoring <prometheus-pod> -- \
  wget -qO- 'http://localhost:9090/api/v1/targets'
```

| Job | Targets | 상태 | Scrape 주기 |
|-----|---------|------|------------|
| kubernetes-nodes | 3 | UP | 15s |
| kubernetes-pods | ~20 | UP | 15s |
| kubernetes-cadvisor | 3 | UP | 15s |
| nginx-exporter | 1 | UP | 15s |
| mysql-exporter | 1 | UP | 15s |
| kube-state-metrics | 1 | UP | 30s |

### Loki 로그 수집

- **Promtail Agent**: 3개 (모든 노드)
- **로그 소스**:
  - WEB (nginx)
  - WAS (spring-boot)
  - MySQL
  - Kubernetes system logs
- **보관 기간**: 30일 (기본)

---

## 🔧 설정 파일 위치

### Prometheus
```bash
# ConfigMap
kubectl get configmap -n monitoring prometheus-config

# Scrape 설정 확인
kubectl get configmap -n monitoring prometheus-config -o yaml | grep -A 5 "scrape_configs:"
```

### Grafana
```bash
# Datasources
kubectl exec -n monitoring <grafana-pod> -- \
  cat /etc/grafana/provisioning/datasources/datasources.yaml

# Dashboards
Grafana UI → Dashboards → Browse
```

### Loki
```bash
# Loki 설정
kubectl get configmap -n monitoring loki-stack -o yaml
```

---

## 💾 스토리지 사용량

### Persistent Volume Claims

```bash
kubectl get pvc -n monitoring
```

| PVC | 크기 | 사용률 | 용도 |
|-----|------|--------|------|
| prometheus-storage | 20Gi | ~5Gi | 메트릭 데이터 |
| loki-stack-storage | 10Gi | ~2Gi | 로그 데이터 |

### 데이터 보관 기간
- **Prometheus**: 15일 (기본)
- **Loki**: 7일 (retention_period: 168h) 🆕 2026-01-23 적용

---

## 🔍 주요 메트릭 예시

### 시스템 리소스
```promql
# CPU 사용률
sum(rate(container_cpu_usage_seconds_total{namespace="blog-system"}[5m])) by (pod)

# Memory 사용률
sum(container_memory_working_set_bytes{namespace="blog-system"}) by (pod)
```

### 애플리케이션
```promql
# Request Rate
rate(nginx_http_requests_total{namespace="blog-system"}[5m])

# MySQL Connections
mysql_global_status_threads_connected

# Pod 상태
kube_pod_status_phase{namespace="blog-system"}
```

---

## 📚 관련 문서

- **트러블슈팅**: [TROUBLESHOOTING.md](./TROUBLESHOOTING.md)
- **다음 계획**: [NEXT-STEPS.md](./NEXT-STEPS.md)
- **메인 가이드**: [README.md](./README.md)

---

## 🔄 최근 변경 사항

### 2026-01-20
- ✅ MySQL Exporter scrape job 추가
- ✅ Kube-State-Metrics scrape job 추가
- ✅ Grafana Datasource Provisioning 설정
- ✅ LoadBalancer externalTrafficPolicy: Local 설정
- ✅ IP Whitelist 확장 (192.168.X.0/24)

### 운영 이슈
- Prometheus PVC lock 문제 발생 → 해결 (순차적 재시작)
- Dashboard "No data" 문제 → 해결 (scrape job 추가)
- 403 Forbidden 문제 → 해결 (LoadBalancer 설정 변경)

---

**시스템 상태: ✅ 정상**
**다음 점검: 자동 (Alert Rules 동작 중)**
