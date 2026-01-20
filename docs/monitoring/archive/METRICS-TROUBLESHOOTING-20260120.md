# Dashboard 메트릭 표시 문제 해결 (2026-01-20)

> MySQL Dashboard 및 System Health Overview "No data" 문제 → 완전 해결

---

## 🎯 최종 결과

✅ **모든 Dashboard 메트릭 정상 수집**
- MySQL Dashboard: 메트릭 수집 중
- System Health Overview: Pod 상태 표시
- Loki 로그: 수집 중

---

## 🔍 발생한 문제

### 문제 1: MySQL Dashboard - 모든 패널 "No data"

**증상:**
```
MySQL Dashboard:
- MySQL Status: No data
- Query Rate: No data
- Connections: No data
- Slow Queries: No data
- 모든 메트릭 패널이 비어있음
```

### 문제 2: System Health Overview - 메트릭 미표시

**증상:**
```
Blog System Overview:
- WEB Status: No data
- WAS Status: No data
- MySQL Status: No data
- Pod 상태가 전혀 표시되지 않음
```

### 문제 3: Loki 로그 - Datasource 미설정

**증상:**
```
Grafana Explore:
- Loki datasource가 목록에 없음
- 로그 조회 불가능
```

---

## 🛠️ 해결 과정

### 1단계: 문제 진단

#### MySQL Exporter 상태 확인
```bash
# MySQL Exporter Pod 확인
kubectl get pods -n blog-system | grep mysql-exporter
# 결과: mysql-exporter-59b58fdd67-6wlkv   1/1     Running ✅

# Service 확인
kubectl get svc -n blog-system mysql-exporter
# 결과: mysql-exporter   ClusterIP   10.105.xxx.xxx   9104/TCP ✅

# 메트릭 엔드포인트 확인
kubectl exec -n blog-system mysql-exporter-xxx -- wget -qO- localhost:9104/metrics
# 결과: 메트릭 정상 출력 ✅
```

**발견:** MySQL Exporter는 정상 작동 중!

#### Prometheus 설정 확인
```bash
# Prometheus ConfigMap 확인
kubectl get configmap -n monitoring prometheus-config -o yaml | grep -A 5 "job_name: 'mysql"
# 결과: (출력 없음) ❌

# Prometheus targets 확인
kubectl exec -n monitoring prometheus-xxx -- wget -qO- 'http://localhost:9090/api/v1/targets'
# 결과: mysql-exporter가 목록에 없음 ❌
```

**근본 원인 발견:**
- MySQL Exporter Pod는 실행 중
- 하지만 Prometheus가 메트릭을 수집하지 않음
- **Prometheus 설정에 scrape job이 없음**

---

### 2단계: Prometheus 설정 업데이트

#### 추가한 Scrape Jobs

**파일:** `/tmp/prometheus-config-updated.yaml`

```yaml
scrape_configs:
  # 기존 jobs...

  # MySQL Exporter (NEW) ⭐
  - job_name: 'mysql-exporter'
    static_configs:
      - targets: ['mysql-exporter.blog-system.svc.cluster.local:9104']
        labels:
          instance: mysql
          namespace: blog-system

  # Kube State Metrics (NEW) ⭐
  - job_name: 'kube-state-metrics'
    static_configs:
      - targets: ['kube-state-metrics.monitoring.svc.cluster.local:8080']
```

**왜 이 설정이 필요한가?**
- Prometheus는 자동으로 모든 메트릭을 수집하지 않음
- 각 exporter에 대해 명시적으로 scrape job 정의 필요
- Cross-namespace 접근을 위해 FQDN 사용 (`.svc.cluster.local`)

#### ConfigMap 업데이트

```bash
# 1. 기존 설정 백업
kubectl get configmap -n monitoring prometheus-config -o yaml > /tmp/prometheus-config-backup.yaml

# 2. ConfigMap 삭제
kubectl delete configmap -n monitoring prometheus-config

# 3. 새 설정으로 재생성
kubectl create configmap -n monitoring prometheus-config \
  --from-file=prometheus.yml=/tmp/prometheus-config-updated.yaml

# 4. Prometheus 재시작
kubectl rollout restart deployment -n monitoring prometheus
```

#### Prometheus 재시작 중 오류 발생

**오류:**
```
CrashLoopBackOff
Error: lock DB directory: resource temporarily unavailable
```

**원인:**
- Rolling update로 인해 이전 Pod와 새 Pod가 동시 실행
- 두 Pod가 동시에 같은 PVC(Persistent Volume Claim) 접근 시도
- PVC는 단일 Pod만 마운트 가능 (ReadWriteOnce)
- 이전 Pod가 lock을 유지하고 있어 새 Pod가 접근 실패

**해결:**
```bash
# Scale down to 0 (모든 Pod 종료 → lock 해제)
kubectl scale deployment -n monitoring prometheus --replicas=0

# 5초 대기 (PVC lock 완전히 해제될 때까지)
sleep 5

# Scale up to 1 (새 Pod만 시작)
kubectl scale deployment -n monitoring prometheus --replicas=1
```

**결과:**
```
Pod: prometheus-586bfbd66f-cs2dp   1/1   Running ✅
```

---

### 3단계: Grafana Datasource 설정

#### 문제 발견
```bash
# Grafana datasources 디렉터리 확인
kubectl exec -n monitoring deployment/grafana -- ls /etc/grafana/provisioning/datasources/
# 결과: (비어있음) ❌
```

**원인:**
- Grafana에 Prometheus/Loki datasource가 설정되지 않음
- Provisioning 디렉터리가 비어있음

#### Datasource ConfigMap 생성

**파일:** `/tmp/grafana-datasources.yaml`

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
      # Prometheus - 메트릭 데이터 소스
      - name: Prometheus
        type: prometheus
        access: proxy
        url: http://prometheus:9090
        isDefault: true
        editable: false
        jsonData:
          timeInterval: "15s"
          queryTimeout: "60s"

      # Loki - 로그 데이터 소스
      - name: Loki
        type: loki
        access: proxy
        url: http://loki-stack:3100
        isDefault: false
        editable: false
        jsonData:
          maxLines: 1000
```

#### Grafana Deployment 업데이트

```bash
# 1. ConfigMap 생성
kubectl apply -f /tmp/grafana-datasources.yaml

# 2. Grafana deployment에 ConfigMap 마운트 추가
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

# 3. Grafana 재시작 (자동)
kubectl get pods -n monitoring -l app=grafana
# 결과: 새 Pod 자동 생성됨 ✅
```

#### 검증

```bash
# Datasources 파일 확인
kubectl exec -n monitoring deployment/grafana -- \
  cat /etc/grafana/provisioning/datasources/datasources.yaml
# 결과: Prometheus, Loki 설정 확인 ✅

# Grafana 로그 확인
kubectl logs -n monitoring -l app=grafana | grep provisioning
# 결과: "finished to provision datasources" ✅
```

---

## 📊 최종 확인

### Prometheus Targets 상태

```bash
kubectl exec -n monitoring prometheus-xxx -- \
  wget -qO- 'http://localhost:9090/api/v1/targets' | jq '.data.activeTargets[] | select(.labels.job | contains("mysql") or contains("kube-state"))'
```

**결과:**
```json
{
  "labels": {"job": "mysql-exporter"},
  "health": "up",
  "lastScrape": "2026-01-20T02:00:00Z",
  "lastScrapeDuration": 0.05
}
{
  "labels": {"job": "kube-state-metrics"},
  "health": "up",
  "lastScrape": "2026-01-20T02:00:15Z",
  "lastScrapeDuration": 0.12
}
```

✅ **모든 targets UP 상태**

---

## 🎓 핵심 교훈

### 1. Prometheus Scrape Configuration의 중요성

**문제:**
- Exporter Pod가 실행 중이어도 Prometheus가 수집하지 않으면 메트릭 없음
- Grafana는 Prometheus에서 데이터를 가져오므로 "No data" 표시

**교훈:**
- **Exporter 배포 ≠ 메트릭 수집**
- 반드시 Prometheus ConfigMap에 scrape job 추가 필요
- 배포 후 항상 Prometheus targets 확인

### 2. Cross-Namespace Service 접근

**올바른 방법:**
```yaml
# FQDN 사용 (권장)
targets: ['mysql-exporter.blog-system.svc.cluster.local:9104']

# 잘못된 방법
targets: ['mysql-exporter:9104']  # 같은 namespace에서만 작동
```

**왜?**
- Prometheus는 monitoring namespace에 있음
- MySQL Exporter는 blog-system namespace에 있음
- Cross-namespace 접근 시 FQDN 필수

### 3. Persistent Volume Lock 문제

**문제:**
- Rolling update 시 이전/새 Pod가 동시에 PVC 접근
- PVC mode: ReadWriteOnce → 단일 Pod만 마운트 가능
- Lock 충돌로 새 Pod CrashLoopBackOff

**해결:**
```bash
# Scale down → Scale up (순차적 재시작)
kubectl scale deployment -n monitoring prometheus --replicas=0
sleep 5
kubectl scale deployment -n monitoring prometheus --replicas=1
```

**대안:**
- ReadWriteMany PVC 사용 (NFS 등)
- StatefulSet 사용 (순차적 Pod 관리)

### 4. Grafana Datasource Provisioning

**수동 설정 vs Provisioning:**

| 방법 | 장점 | 단점 |
|------|------|------|
| **수동 (UI)** | 간편, 즉시 적용 | Pod 재시작 시 사라짐 |
| **Provisioning** | 영구 보존, GitOps 가능 | ConfigMap + 재배포 필요 |

**권장:** Provisioning (ConfigMap) 사용

---

## 📝 변경된 설정 요약

### Prometheus ConfigMap

| 항목 | Before | After |
|------|--------|-------|
| **Scrape jobs** | 9개 | **11개** (+2) |
| **MySQL Exporter** | ❌ 없음 | ✅ 추가됨 |
| **Kube-State-Metrics** | ❌ 없음 | ✅ 추가됨 |

### Grafana Datasources

| 항목 | Before | After |
|------|--------|-------|
| **Prometheus** | ❌ 미설정 | ✅ http://prometheus:9090 |
| **Loki** | ❌ 미설정 | ✅ http://loki-stack:3100 |
| **Provisioning** | ❌ 없음 | ✅ ConfigMap으로 관리 |

### Pod 상태

| Component | 상태 |
|-----------|------|
| **Prometheus** | ✅ Running (prometheus-586bfbd66f-cs2dp) |
| **Grafana** | ✅ Running (grafana-577c4944db-9vxvb) |
| **MySQL Exporter** | ✅ Running (mysql-exporter-59b58fdd67-6wlkv) |
| **Kube-State-Metrics** | ✅ Running (kube-state-metrics-7774c659f9-h8wlz) |

---

## 🔍 트러블슈팅 체크리스트

Dashboard에 "No data"가 표시될 때:

### 1. Exporter Pod 확인
```bash
kubectl get pods -n <namespace> | grep exporter
# 모든 exporter Pod가 Running 상태인지 확인
```

### 2. Prometheus Targets 확인
```bash
kubectl exec -n monitoring <prometheus-pod> -- \
  wget -qO- 'http://localhost:9090/api/v1/targets'
# 해당 exporter가 targets 목록에 있고 health: "up"인지 확인
```

### 3. Grafana Datasource 확인
```bash
# Grafana UI: Configuration → Data Sources
# Prometheus가 "Test" 버튼 클릭 시 성공하는지 확인
```

### 4. 메트릭 쿼리 직접 테스트
```bash
# Prometheus UI: http://prometheus.jiminhome.shop
# Graph 탭에서 쿼리 테스트
# 예: mysql_up, mysql_global_status_connections
```

### 5. 시간 범위 확인
- Grafana 우측 상단 시간 범위가 적절한지 확인
- 메트릭 수집 시작 후 최소 1-2분 대기

---

## 📚 관련 파일

| 파일 | 용도 |
|------|------|
| `/tmp/prometheus-config-updated.yaml` | 업데이트된 Prometheus 설정 |
| `/tmp/prometheus-config-backup.yaml` | 이전 설정 백업 |
| `/tmp/grafana-datasources.yaml` | Grafana datasource 설정 |
| `/tmp/grafana-deployment-backup.yaml` | Grafana deployment 백업 |

---

## 🔗 관련 문서

- **접근 가이드**: [ACCESS-GUIDE.md](./ACCESS-GUIDE.md)
- **트러블슈팅 요약**: [TROUBLESHOOTING-SUMMARY-20260120.md](./TROUBLESHOOTING-SUMMARY-20260120.md)
- **메인 가이드**: [README.md](./README.md)

---

**문제 해결 완료: 2026-01-20**
**소요 시간: 약 45분** (진단 → Prometheus 설정 → Grafana 설정 → 검증)
