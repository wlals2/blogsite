# Monitoring 트러블슈팅 가이드

> PLG Stack (Prometheus + Loki + Grafana) 문제 해결

---

## 목차

1. [Dashboard 접근 문제](#1-dashboard-접근-문제)
2. [메트릭 표시 문제 (No Data)](#2-메트릭-표시-문제-no-data)
3. [Prometheus 관련 문제](#3-prometheus-관련-문제)
4. [Grafana 관련 문제](#4-grafana-관련-문제)
5. [Loki 로그 수집 문제](#5-loki-로그-수집-문제)
6. [Alert 발송 문제](#6-alert-발송-문제)

---

## 1. Dashboard 접근 문제

### 문제 1-1: 사이트를 찾을 수 없음 (DNS 오류)

**증상:**
```
브라우저: "사이트를 찾을 수 없음"
ping monitoring.jiminhome.shop → 실패
```

**원인:**
- `monitoring.jiminhome.shop`은 로컬 네트워크 전용 도메인
- 공개 DNS에 등록되지 않음

**해결:**

```bash
# Windows (PowerShell 관리자 권한)
Add-Content -Path C:\Windows\System32\drivers\etc\hosts -Value "`n192.168.1.200 monitoring.jiminhome.shop"
Add-Content -Path C:\Windows\System32\drivers\etc\hosts -Value "192.168.1.200 prometheus.jiminhome.shop"

# Linux/Mac
sudo bash -c 'cat >> /etc/hosts << EOF
192.168.1.200 monitoring.jiminhome.shop
192.168.1.200 prometheus.jiminhome.shop
EOF'
```

**검증:**
```bash
ping monitoring.jiminhome.shop
# 예상: 192.168.1.200에서 응답
```

---

### 문제 1-2: 403 Forbidden (IP Whitelist 차단)

**증상:**
```bash
curl -I http://monitoring.jiminhome.shop
# HTTP/1.1 403 Forbidden
```

**원인:**
- IP Whitelist 설정: 192.168.1.0/24만 허용
- 클라이언트 IP가 범위 밖
- 또는 LoadBalancer SNAT로 원본 IP 손실

**네트워크 흐름 이해:**
```
Windows PC (192.168.1.195)
    ↓
LoadBalancer Service (192.168.1.200)
    ↓ [externalTrafficPolicy: Cluster] ← SNAT 발생!
    ↓ 원본 IP 손실 (10.0.1.22로 변경)
    ↓
Ingress Controller
    ↓ client IP: 10.0.1.22
    ↓
IP Whitelist 체크: 10.0.1.22 ∉ 192.168.1.0/24
    ↓
❌ 403 Forbidden
```

**해결 1: LoadBalancer 설정 변경 (권장)**

```bash
# externalTrafficPolicy를 Local로 변경 (원본 IP 보존)
kubectl patch svc -n ingress-nginx ingress-nginx-controller \
  -p '{"spec":{"externalTrafficPolicy":"Local"}}'

# 검증
kubectl get svc -n ingress-nginx ingress-nginx-controller -o jsonpath='{.spec.externalTrafficPolicy}'
# 예상: Local
```

**해결 2: IP Whitelist 확장**

```bash
# 단일 IP → 서브넷 전체로 확장
kubectl annotate ingress -n monitoring grafana-ingress \
  nginx.ingress.kubernetes.io/whitelist-source-range="192.168.1.0/24" --overwrite
```

**검증:**
```bash
# Ingress 로그에서 client IP 확인
kubectl logs -n ingress-nginx -l app.kubernetes.io/name=ingress-nginx --tail=10 | grep monitoring.jiminhome.shop
# 예상: client: 192.168.1.195 (원본 IP 보존됨)
```

---

## 2. 메트릭 표시 문제 (No Data)

### 문제 2-1: MySQL Dashboard "No data"

**증상:**
```
Grafana Dashboard:
- MySQL Status: No data
- Query Rate: No data
- Connections: No data
```

**진단 순서:**

**1단계: Exporter Pod 확인**
```bash
kubectl get pods -n blog-system | grep mysql-exporter
# 예상: mysql-exporter-xxx   1/1   Running
```

**2단계: 메트릭 엔드포인트 확인**
```bash
kubectl exec -n blog-system <mysql-exporter-pod> -- wget -qO- localhost:9104/metrics | head -20
# 예상: mysql_up 1, mysql_global_status_connections 등
```

**3단계: Prometheus Targets 확인**
```bash
kubectl exec -n monitoring <prometheus-pod> -- \
  wget -qO- 'http://localhost:9090/api/v1/targets' | jq '.data.activeTargets[] | select(.labels.job=="mysql-exporter")'

# 예상:
# {
#   "labels": {"job": "mysql-exporter"},
#   "health": "up",
#   "lastScrape": "..."
# }
```

**4단계: Prometheus ConfigMap 확인**
```bash
kubectl get configmap -n monitoring prometheus-config -o yaml | grep -A 10 "job_name: 'mysql"
# 예상: scrape job 설정 있어야 함
```

**원인:**
- Prometheus ConfigMap에 scrape job이 없음
- Exporter는 실행 중이지만 Prometheus가 수집하지 않음

**해결:**

```yaml
# Prometheus ConfigMap 수정
kubectl edit configmap -n monitoring prometheus-config

# 추가할 내용:
scrape_configs:
  - job_name: 'mysql-exporter'
    static_configs:
      - targets: ['mysql-exporter.blog-system.svc.cluster.local:9104']
        labels:
          instance: mysql
          namespace: blog-system
```

**Prometheus 재시작:**
```bash
# Scale down → Scale up (PVC lock 방지)
kubectl scale deployment -n monitoring prometheus --replicas=0
sleep 5
kubectl scale deployment -n monitoring prometheus --replicas=1

# 검증
kubectl get pods -n monitoring -l app=prometheus
# 예상: Running
```

---

### 문제 2-2: Pod 상태 메트릭 없음

**증상:**
```
System Health Overview:
- WEB Status: No data
- WAS Status: No data
```

**원인:**
- kube-state-metrics가 Prometheus에 등록되지 않음

**해결:**
```yaml
kubectl edit configmap -n monitoring prometheus-config

# 추가:
scrape_configs:
  - job_name: 'kube-state-metrics'
    static_configs:
      - targets: ['kube-state-metrics.monitoring.svc.cluster.local:8080']
```

---

## 3. Prometheus 관련 문제

### 문제 3-1: CrashLoopBackOff (PVC Lock)

**증상:**
```bash
kubectl get pods -n monitoring
# prometheus-xxx   0/1   CrashLoopBackOff
```

**로그:**
```
Error: lock DB directory: resource temporarily unavailable
```

**원인:**
- Rolling update로 이전 Pod와 새 Pod가 동시에 PVC 접근
- PVC mode: ReadWriteOnce → 단일 Pod만 마운트 가능
- 이전 Pod가 lock 유지

**해결:**
```bash
# 순차적 재시작 (Scale down → Scale up)
kubectl scale deployment -n monitoring prometheus --replicas=0
sleep 5  # Lock 해제 대기
kubectl scale deployment -n monitoring prometheus --replicas=1
```

---

### 문제 3-2: Targets가 "down" 상태

**증상:**
```
Prometheus UI → Status → Targets
Job "mysql-exporter": down
```

**원인 및 해결:**

**1. Service가 없는 경우:**
```bash
kubectl get svc -n blog-system mysql-exporter
# Not found → Service 생성 필요
```

**2. Cross-namespace 접근 문제:**
```yaml
# 잘못된 설정:
targets: ['mysql-exporter:9104']  # 같은 namespace만

# 올바른 설정:
targets: ['mysql-exporter.blog-system.svc.cluster.local:9104']  # FQDN
```

**3. Port 불일치:**
```bash
# Service port 확인
kubectl get svc -n blog-system mysql-exporter -o jsonpath='{.spec.ports[0].port}'

# Prometheus targets와 일치해야 함
```

---

## 4. Grafana 관련 문제

### 문제 4-1: Datasource 연결 실패

**증상:**
```
Grafana UI: Data source is not working
Test 버튼 클릭 → Error: Unable to connect
```

**진단:**
```bash
# Grafana Pod에서 Prometheus 연결 테스트
kubectl exec -n monitoring <grafana-pod> -- \
  wget -qO- http://prometheus:9090/api/v1/query?query=up | head

# 실패 시:
# - Prometheus Service 확인
# - 같은 namespace에 있는지 확인
```

**해결:**
```bash
# Prometheus Service 확인
kubectl get svc -n monitoring prometheus
# NAME         TYPE        CLUSTER-IP      PORT(S)
# prometheus   ClusterIP   10.96.xx.xx     9090/TCP

# Grafana datasource 설정 확인
kubectl exec -n monitoring <grafana-pod> -- \
  cat /etc/grafana/provisioning/datasources/datasources.yaml
```

---

### 문제 4-2: Datasource가 Provisioning되지 않음

**증상:**
```
Grafana UI: Datasources 메뉴 → 비어있음
```

**원인:**
- Grafana ConfigMap이 마운트되지 않음

**해결:**

**1. Datasource ConfigMap 생성:**
```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: grafana-datasources
  namespace: monitoring
data:
  datasources.yaml: |
    apiVersion: 1
    datasources:
      - name: Prometheus
        type: prometheus
        access: proxy
        url: http://prometheus:9090
        isDefault: true
        editable: false

      - name: Loki
        type: loki
        access: proxy
        url: http://loki-stack:3100
        isDefault: false
        editable: false
```

**2. Grafana Deployment에 마운트:**
```bash
kubectl patch deployment -n monitoring grafana --type='json' -p='[
  {
    "op": "add",
    "path": "/spec/template/spec/volumes/-",
    "value": {
      "name": "grafana-datasources",
      "configMap": {"name": "grafana-datasources"}
    }
  },
  {
    "op": "add",
    "path": "/spec/template/spec/containers/0/volumeMounts/-",
    "value": {
      "name": "grafana-datasources",
      "mountPath": "/etc/grafana/provisioning/datasources"
    }
  }
]'
```

---

## 5. Loki 로그 수집 문제

### 문제 5-1: 로그가 수집되지 않음

**증상:**
```
Grafana Explore → Loki → {namespace="blog-system"}
No logs found
```

**진단:**
```bash
# Promtail Pod 확인 (로그 수집 Agent)
kubectl get pods -n monitoring -l app=promtail

# Promtail 로그 확인
kubectl logs -n monitoring -l app=promtail --tail=50
```

**일반적인 원인:**
- Promtail이 로그 파일 경로를 찾지 못함
- Loki Service 연결 실패

---

## 6. Alert 발송 문제

### 문제 6-1: Alert가 발동하지 않음

**증상:**
```
Prometheus → Alerts 탭 → 모든 Alert "Inactive"
```

**진단:**
```bash
# Alert Rules 확인
kubectl get configmap -n monitoring prometheus-config -o yaml | grep -A 20 "groups:"

# Prometheus 로그 확인
kubectl logs -n monitoring -l app=prometheus --tail=50 | grep -i "alert\|rule"
```

**검증:**
```bash
# Prometheus UI에서 Alert 쿼리 직접 실행
# 예: rate(nginx_http_requests_total[5m]) > 1000
```

---

### 문제 6-2: AlertManager로 전송되지 않음

**증상:**
```
Prometheus: Alert "Firing"
AlertManager: Alert 없음
```

**진단:**
```bash
# AlertManager Service 확인
kubectl get svc -n monitoring alertmanager

# Prometheus → AlertManager 연결 확인
kubectl exec -n monitoring <prometheus-pod> -- \
  wget -qO- http://alertmanager:9093/-/healthy
```

---

## 🔍 일반 트러블슈팅 체크리스트

### Dashboard "No data" 문제
```bash
# 1. Pod 상태
kubectl get pods -n <namespace>

# 2. Prometheus Targets
kubectl exec -n monitoring <prometheus-pod> -- \
  wget -qO- 'http://localhost:9090/api/v1/targets'

# 3. Grafana Datasource
# Grafana UI: Configuration → Data Sources → Test

# 4. 시간 범위
# Grafana 우측 상단 시간 범위 확인 (Last 15 minutes 등)
```

### 네트워크 연결 문제
```bash
# 1. DNS 확인
ping <service-name>.<namespace>.svc.cluster.local

# 2. Service 확인
kubectl get svc -n <namespace> <service-name>

# 3. Endpoints 확인
kubectl get endpoints -n <namespace> <service-name>

# 4. Pod 로그
kubectl logs -n <namespace> <pod-name>
```

---

## 📚 관련 문서

- **현재 상태**: [CURRENT-STATUS.md](./CURRENT-STATUS.md)
- **다음 계획**: [NEXT-STEPS.md](./NEXT-STEPS.md)
- **메인 가이드**: [README.md](./README.md)
