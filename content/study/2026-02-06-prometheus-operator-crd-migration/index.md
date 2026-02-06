---
title: "Prometheus 모니터링을 Raw ConfigMap에서 CRD 기반으로 전환하기"
date: 2026-02-06T10:00:00+09:00
categories: ["study", "Observability"]
tags: ["prometheus", "kubernetes", "gitops", "servicemonitor", "prometheusrule", "argocd", "operator"]
summary: "ArgoCD OutOfSync 문제를 해결하기 위해 Prometheus 모니터링 시스템을 Raw ConfigMap 방식에서 Prometheus Operator CRD 기반으로 전환한 과정"
---

## 1. Background: 왜 CRD 기반 모니터링이 필요한가?

### 현재 상황 (As-Is)

홈랩 Kubernetes 클러스터에서 Prometheus 모니터링 시스템을 다음과 같이 운영 중이었습니다:

- **prometheus-config.yaml**: Prometheus 설정 (scrape_configs)을 ConfigMap으로 관리
- **prometheus-alert-rules.yaml**: Alert Rules를 ConfigMap으로 관리
- **GitOps**: ArgoCD로 모든 리소스를 Git에서 배포

### 문제점 (Pain Points)

**1. ArgoCD OutOfSync 지속 발생**

ArgoCD Application이 항상 "OutOfSync" 상태로 표시되었습니다:

```bash
kubectl get application monitoring -n argocd
# STATUS: OutOfSync
```

**근본 원인**: Prometheus Operator가 runtime에 ConfigMap을 자동으로 수정하기 때문입니다.

```
Git (prometheus-config.yaml)
  ↓ ArgoCD 배포
ConfigMap (클러스터)
  ↓ Prometheus Operator가 자동 수정 (runtime)
ConfigMap (클러스터, 변경됨)
  ↓ ArgoCD Drift 감지
OutOfSync!
```

**2. GitOps 원칙 위반**

Git = Source of Truth 원칙이 깨졌습니다:
- Git에 있는 내용과 클러스터 상태가 항상 다름
- 변경 이력을 Git에서 추적 불가능
- ArgoCD selfHeal이 작동하지 않음 (Operator가 다시 수정)

**3. 수동 관리의 어려움**

새로운 메트릭 수집 대상을 추가할 때마다:
1. prometheus-config.yaml 수정 (scrape_configs 추가)
2. Git 커밋
3. ArgoCD Sync
4. Prometheus reload 대기
5. 그래도 OutOfSync 상태 유지

### 해결하고 싶은 것 (Goals)

- ✅ **ArgoCD Always Synced**: Git과 클러스터 상태 100% 일치
- ✅ **GitOps 100% 달성**: 모든 변경 사항을 Git에서 추적
- ✅ **자동화**: ServiceMonitor CRD 생성 → Prometheus 자동 설정 업데이트
- ✅ **관리 용이성**: 컴포넌트별로 독립적인 파일 관리

---

## 2. Background Knowledge

### Prometheus Operator란?

Prometheus Operator는 Kubernetes-native 방식으로 Prometheus를 관리하는 Operator입니다.

**핵심 개념**:
- **ServiceMonitor CRD**: 메트릭 수집 대상을 선언적으로 정의
- **PrometheusRule CRD**: Alert Rules를 선언적으로 정의
- **Prometheus CRD**: Prometheus 인스턴스 자체를 선언적으로 정의

### 작동 원리

```
ServiceMonitor CRD 생성 (Git)
  ↓ ArgoCD 배포
ServiceMonitor (클러스터)
  ↓ Prometheus Operator 감지
Operator가 prometheus.yml 자동 업데이트
  ↓
Prometheus Pod 자동 reload
  ↓
메트릭 수집 시작 ✅
```

**핵심**: ConfigMap을 직접 수정하지 않고, CRD를 생성하면 Operator가 알아서 처리합니다.

### 필수 사전 지식

- **Kubernetes CRD (Custom Resource Definition)**: Kubernetes API를 확장하는 방법
- **GitOps 워크플로우**: Git → ArgoCD → 클러스터 자동 배포
- **Helm Wrapper Chart**: 외부 Helm Chart를 커스터마이징하는 패턴
- **ArgoCD ignoreDifferences**: 동적으로 변경되는 필드를 무시하는 설정

---

## 3. Raw ConfigMap vs CRD 기반: Trade-offs

| 항목 | Raw ConfigMap | CRD 기반 (Prometheus Operator) |
|------|---------------|-------------------------------|
| **GitOps 호환성** | ❌ OutOfSync 지속 | ✅ Always Synced |
| **관리 방식** | prometheus-config.yaml 수동 수정 | ServiceMonitor CRD 생성 |
| **파일 구조** | 단일 파일 (모든 scrape_configs) | 컴포넌트별 독립 파일 |
| **Prometheus reload** | 수동 또는 sidecar 필요 | Operator가 자동 처리 |
| **변경 이력 추적** | Git commit (하지만 runtime 수정됨) | Git commit (100% 추적) |
| **러닝 커브** | 낮음 (Prometheus 설정만) | 중간 (CRD 개념 이해 필요) |
| **복잡도** | 낮음 | 중간 (Operator 추가) |
| **에러 격리** | 단일 파일 오류 시 전체 실패 | 파일별 독립 (한 ServiceMonitor 오류가 다른 것에 영향 없음) |
| **확장성** | 단일 파일이 커짐 | 파일 추가만 하면 됨 |

### 선택 이유: CRD 기반 (Prometheus Operator)

**장점**:
1. **GitOps 100% 달성**: ArgoCD OutOfSync 문제 완전 해결
2. **자동화**: ServiceMonitor 생성 → 자동 반영 (수동 작업 0)
3. **관리 용이성**: mysql-exporter.yaml, alloy.yaml 등 컴포넌트별로 독립 관리
4. **변경 이력 100% 추적**: Git commit = 실제 적용

**단점 (Trade-off)**:
1. **복잡도 증가**: Prometheus Operator, CRD 개념 학습 필요
2. **디버깅 어려움**: Operator 로그 확인 필요
3. **의존성 추가**: Prometheus Operator가 필수 컴포넌트가 됨

**결론**: GitOps 환경에서는 CRD 기반이 필수적입니다.

---

## 4. 구축 과정 (7단계)

### Step 1: kube-prometheus-stack Helm Chart 설치 (Wrapper Chart)

**목적**: Prometheus Operator + Prometheus + Grafana + AlertManager를 한 번에 설치

**파일 구조**:
```
k8s-manifests/apps/kube-prometheus-stack/
├── Chart.yaml       # Helm dependency 정의
├── values.yaml      # 커스텀 설정
└── templates/       # 추가 커스텀 리소스
```

**Chart.yaml**:
```yaml
apiVersion: v2
name: my-kube-prometheus-stack
version: 1.0.0
dependencies:
  - name: kube-prometheus-stack
    version: 65.0.0
    repository: https://prometheus-community.github.io/helm-charts
```

**중요**: Wrapper Chart에서는 dependency name을 prefix로 사용해야 합니다!

**values.yaml**:
```yaml
kube-prometheus-stack:  # ← CRITICAL: dependency name prefix
  prometheusOperator:
    enabled: true
    namespaces: {}  # 모든 namespace 감시
    admissionWebhooks:
      enabled: false  # 홈랩에서는 불필요

  prometheus:
    prometheusSpec:
      serviceMonitorSelector:
        matchLabels:
          prometheus: kube-prometheus  # ← ServiceMonitor 라벨 규칙
      retention: 7d
      storageSpec:
        volumeClaimTemplate:
          spec:
            accessModes:
              - ReadWriteOnce
            resources:
              requests:
                storage: 20Gi

  nodeExporter:
    enabled: false  # alloy와 중복 방지
```

**ArgoCD Application**:
```yaml
# argocd/kube-prometheus-stack-app.yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: kube-prometheus-stack
  namespace: argocd
spec:
  source:
    repoURL: https://github.com/wlals2/k8s-manifests.git
    targetRevision: main
    path: apps/kube-prometheus-stack
  destination:
    namespace: monitoring
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
```

---

### Step 2: ServiceMonitor 작성 (4개)

**목적**: 메트릭 수집 대상을 CRD로 선언

**파일 위치**: `configs/monitoring/servicemonitors/`

#### 2-1. MySQL Exporter ServiceMonitor

**파일**: `mysql-exporter.yaml`

```yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: mysql-exporter
  namespace: monitoring
  labels:
    prometheus: kube-prometheus  # ← CRITICAL: Prometheus Selector와 일치
spec:
  selector:
    matchLabels:
      app: mysql-exporter  # Service 라벨
  namespaceSelector:
    matchNames:
      - blog-system  # Service가 있는 namespace
  endpoints:
    - port: metrics
      interval: 30s
      scrapeTimeout: 10s
```

#### 2-2. Blackbox Exporter ServiceMonitor

외부 서비스 프로빙 (HTTP/TCP):

```yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: blackbox-exporter
  namespace: monitoring
  labels:
    prometheus: kube-prometheus
spec:
  selector:
    matchLabels:
      app: blackbox-exporter
  namespaceSelector:
    matchNames:
      - monitoring
  endpoints:
    - port: http
      interval: 30s
```

#### 2-3. Grafana Alloy ServiceMonitor

Node Exporter 대체 (DaemonSet):

```yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: alloy
  namespace: monitoring
  labels:
    prometheus: kube-prometheus
spec:
  selector:
    matchLabels:
      app.kubernetes.io/name: alloy
  endpoints:
    - port: http-metrics
      interval: 30s
      relabelings:
        - sourceLabels: [__meta_kubernetes_pod_node_name]
          targetLabel: node
```

#### 2-4. NGINX Exporter ServiceMonitor

```yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: nginx-exporter
  namespace: monitoring
  labels:
    prometheus: kube-prometheus
spec:
  selector:
    matchLabels:
      app: nginx-exporter
  endpoints:
    - port: metrics
      interval: 30s
```

---

### Step 3: PrometheusRule 작성 (Alert Rules)

**목적**: Alert Rules를 CRD로 선언

**파일**: `configs/monitoring/prometheusrules/mysql-alerts.yaml`

```yaml
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: mysql-alerts
  namespace: monitoring
  labels:
    prometheus: kube-prometheus  # ← CRITICAL
spec:
  groups:
    - name: mysql-critical
      interval: 30s
      rules:
        - alert: MySQLExporterDown
          expr: up{job="mysql-exporter"} == 0
          for: 1m
          labels:
            severity: critical
          annotations:
            summary: "MySQL Exporter가 다운되었습니다"
            description: "MySQL Exporter가 1분 동안 응답하지 않습니다."
```

---

### Step 4: ArgoCD Application 생성 (monitoring)

**목적**: ServiceMonitor/PrometheusRule을 ArgoCD로 배포

**파일**: `argocd/monitoring-app.yaml`

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: monitoring
  namespace: argocd
spec:
  source:
    repoURL: https://github.com/wlals2/k8s-manifests.git
    targetRevision: main
    path: configs/monitoring
    directory:
      recurse: true  # ← CRITICAL: subdirectory까지 읽기
  destination:
    namespace: monitoring
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
```

**주의**: `directory.recurse: true`가 없으면 subdirectory (servicemonitors/, prometheusrules/)를 무시합니다!

---

### Step 5: 기존 Raw ConfigMap 제거

**삭제할 파일**:
- `configs/monitoring/prometheus-config.yaml`
- `configs/monitoring/prometheus-alert-rules.yaml`
- `configs/monitoring/prometheus-recording-rules.yaml`

**이유**: CRD 기반으로 완전히 전환하므로 Raw ConfigMap은 불필요합니다.

---

### Step 6: Git 커밋 및 배포

```bash
# 1. Git 커밋
git add apps/kube-prometheus-stack/
git add configs/monitoring/servicemonitors/
git add configs/monitoring/prometheusrules/
git add argocd/kube-prometheus-stack-app.yaml
git add argocd/monitoring-app.yaml
git commit -m "feat: Prometheus 모니터링을 CRD 기반으로 전환"
git push

# 2. ArgoCD 자동 Sync (3초 이내)
kubectl get application -n argocd
# kube-prometheus-stack: Synced
# monitoring: Synced
```

---

### Step 7: 검증

#### 7-1. ServiceMonitor 인식 확인

```bash
kubectl get servicemonitor -n monitoring
# alloy, blackbox-exporter, mysql-exporter, nginx-exporter
```

#### 7-2. PrometheusRule 인식 확인

```bash
kubectl get prometheusrule -n monitoring
# mysql-alerts
```

#### 7-3. Prometheus Target 확인

```bash
kubectl port-forward -n monitoring svc/kube-prometheus-stack-prometheus 9090:9090
# 브라우저: http://localhost:9090/targets
# → alloy, blackbox-exporter, mysql-exporter, nginx-exporter 확인
```

---

## 5. Troubleshooting (핵심 7가지)

### 문제 1: Wrapper Chart values.yaml 구조 오류

**증상**:
```bash
kubectl get pods -n monitoring | grep node-exporter
# kube-prometheus-stack-prometheus-node-exporter-xxx (4개 Pending)
```

`nodeExporter.enabled: false`가 적용되지 않고 node-exporter Pod가 생성됨.

**원인**: Wrapper Chart에서 dependency name prefix 누락

```yaml
# ❌ 잘못된 구조
nodeExporter:
  enabled: false

# ✅ 올바른 구조
kube-prometheus-stack:  # dependency name prefix
  nodeExporter:
    enabled: false
```

**해결**: values.yaml 전체를 `kube-prometheus-stack:` 아래로 들여쓰기

---

### 문제 2: node-exporter Pod Pending (포트 9100 충돌)

**증상**:
```bash
kubectl describe pod kube-prometheus-stack-prometheus-node-exporter-xxx
# Warning: FailedScheduling: 0/4 nodes available: port 9100 already in use
```

**원인**: alloy DaemonSet이 이미 9100 포트를 사용 중

**해결**:
```bash
# DaemonSet 수동 삭제
kubectl delete daemonset kube-prometheus-stack-prometheus-node-exporter -n monitoring
```

**근본 원인**: values.yaml 구조 오류로 `nodeExporter.enabled: false`가 미적용

---

### 문제 3: ArgoCD 무한 대기 (Syncing 상태)

**증상**:
```bash
kubectl get application kube-prometheus-stack -n argocd
# HEALTH: Progressing (10분 경과)
```

**원인**: node-exporter DaemonSet이 Pending 상태여서 Healthy가 되지 않음

**해결**:
```bash
# Operation 취소
kubectl patch application kube-prometheus-stack -n argocd \
  --type merge -p '{"operation":null}'
```

---

### 문제 4: ServiceMonitor 미배포

**증상**:
```bash
kubectl get servicemonitor -n monitoring
# No resources found
```

**원인**: ArgoCD가 subdirectory를 기본적으로 무시함

**해결**: `argocd/monitoring-app.yaml`에 `directory.recurse: true` 추가

```yaml
spec:
  source:
    directory:
      recurse: true  # ← 추가
```

---

### 문제 5: ServiceMonitor 미인식 (라벨 불일치)

**증상**:
```bash
kubectl get servicemonitor mysql-exporter -n monitoring  # 존재함
kubectl exec prometheus-0 -- wget http://localhost:9090/api/v1/targets
# → mysql-exporter가 Target 목록에 없음
```

**원인**: Prometheus의 `serviceMonitorSelector`와 ServiceMonitor 라벨 불일치

```bash
# Prometheus의 실제 Selector 확인
kubectl get prometheus -n monitoring -o yaml | grep -A 3 serviceMonitorSelector
# matchLabels:
#   prometheus: kube-prometheus  ← Prometheus가 찾는 라벨

# ServiceMonitor의 라벨 확인
kubectl get servicemonitor mysql-exporter -n monitoring -o yaml | grep -A 3 'metadata:'
# labels:
#   release: kube-prometheus-stack  ← 불일치!
```

**해결**: ServiceMonitor 라벨을 `prometheus: kube-prometheus`로 변경

---

### 문제 6: storageClassName "null" 오류

**증상**:
```bash
kubectl get prometheus -n monitoring
# Error: storageClassName: Invalid value: "null"
```

**원인**: `storageClassName: null`이 문자열 "null"로 해석됨

**해결**: `storageClassName` 필드 자체를 제거 (기본 SC 자동 사용)

```yaml
# ❌ 잘못된 설정
storageSpec:
  volumeClaimTemplate:
    spec:
      storageClassName: null

# ✅ 올바른 설정
storageSpec:
  volumeClaimTemplate:
    spec:
      # storageClassName 필드 제거
      accessModes:
        - ReadWriteOnce
```

---

### 문제 7: Prometheus 설정 파일이 비어있음

**증상**:
```bash
kubectl exec prometheus-0 -- cat /etc/prometheus/config_out/prometheus.env.yaml
# scrape_configs: []  ← 비어있음!
```

**원인**: Prometheus Operator가 아직 ServiceMonitor를 감지하지 못함 (Pod 재시작 직후)

**해결**: 2-3분 대기 후 재확인 (Operator가 자동으로 업데이트)

---

## 6. 성과 (Achievements)

### Before (Raw ConfigMap 방식)

| 작업 | 방법 | 소요 시간 | 상태 |
|------|------|----------|------|
| 새 메트릭 수집 추가 | prometheus-config.yaml 수동 수정 | 5분 | OutOfSync |
| Alert Rule 추가 | prometheus-alert-rules.yaml 수동 수정 | 3분 | OutOfSync |
| 변경 이력 추적 | Git commit (하지만 runtime 수정됨) | N/A | 불가능 |
| ArgoCD Sync 상태 | 항상 OutOfSync | N/A | ❌ |
| Prometheus reload | 수동 또는 sidecar 필요 | 1분 | 수동 |

**총 ArgoCD Sync 성공률**: **0%** (항상 OutOfSync)

---

### After (CRD 기반 방식)

| 작업 | 방법 | 소요 시간 | 상태 |
|------|------|----------|------|
| 새 메트릭 수집 추가 | ServiceMonitor YAML 생성 → Git commit | 2분 | Synced |
| Alert Rule 추가 | PrometheusRule YAML 생성 → Git commit | 1분 | Synced |
| 변경 이력 추적 | Git commit = 실제 적용 | 즉시 | ✅ 100% |
| ArgoCD Sync 상태 | 항상 Synced | N/A | ✅ |
| Prometheus reload | Operator가 자동 처리 | 0분 (자동) | 자동 |

**총 ArgoCD Sync 성공률**: **100%** (Always Synced)

---

### 정량적 성과

- ✅ **ArgoCD Sync 성공률 100% 달성** (0% → 100%)
- ✅ **GitOps 100% 달성** (모든 변경 사항 Git 추적)
- ✅ **메트릭 추가 작업 시간 60% 단축** (5분 → 2분)
- ✅ **4개 ServiceMonitor 자동화** (수동 작업 0)
- ✅ **PrometheusRule 자동화** (수동 reload 불필요)

---

### 비정량적 효과

- 컴포넌트별로 독립적인 파일 관리 (mysql-exporter.yaml, alloy.yaml 등)
- 한 ServiceMonitor 오류가 다른 것에 영향 없음 (에러 격리)
- Prometheus reload 자동화 (수동 작업 제거)
- Git 이력으로 모든 변경 사항 추적 가능
- ArgoCD selfHeal 정상 작동 (Drift 자동 복구)

---

## 7. Security Considerations

### Prometheus Operator가 보호하는 것

✅ **CRD 기반 선언**: ServiceMonitor/PrometheusRule이 Git에 안전하게 저장
✅ **RBAC**: Prometheus Operator가 RBAC으로 권한 제어

### Prometheus Operator가 보호하지 않는 것

⚠️ **메트릭 데이터**: Prometheus에 저장된 메트릭은 평문
⚠️ **Grafana 접근**: Grafana 비밀번호는 평문 (SealedSecret 권장)

### 권장 추가 조치

1. **Grafana Admin Secret 암호화** (P0 작업)
   - SealedSecret으로 adminPassword 관리
   - 현재: 평문 저장 (`adminPassword: admin`)

2. **RBAC 강화** (P1 작업)
   - 개발자는 ServiceMonitor 읽기만 허용
   - SRE만 ServiceMonitor 생성/수정 허용

3. **Network Policy** (P2 작업)
   - Prometheus → Exporter 통신만 허용
   - 불필요한 트래픽 차단

---

## 8. 결론

### 핵심 메시지

Prometheus 모니터링을 Raw ConfigMap 방식에서 CRD 기반(Prometheus Operator)으로 전환하여 **ArgoCD Sync 성공률 100% 달성**과 **GitOps 100% 구현**을 이뤘습니다.

### 배운 점

1. **Wrapper Chart는 dependency name prefix 필수**: `kube-prometheus-stack:` 아래로 모든 설정 들여쓰기
2. **ArgoCD directory.recurse: true 필수**: subdirectory (servicemonitors/, prometheusrules/)를 읽으려면
3. **라벨 일치 확인 필수**: Prometheus의 `serviceMonitorSelector`와 ServiceMonitor 라벨이 정확히 일치해야 함
4. **storageClassName 필드 제거**: null 대신 필드 자체를 생략하면 기본 SC 자동 사용
5. **Operator는 시간이 필요**: Pod 재시작 후 2-3분 대기 필요 (설정 자동 업데이트)
6. **GitOps 환경에서는 CRD 기반이 필수**: Raw ConfigMap은 runtime 수정으로 OutOfSync 발생
7. **에러 격리의 중요성**: 하나의 ServiceMonitor 오류가 다른 것에 영향 없음

### 다음 단계

- [ ] Grafana Admin Secret을 SealedSecret으로 암호화 (P0)
- [ ] 나머지 Exporter들도 ServiceMonitor로 전환 (node-exporter 등)
- [ ] PrometheusRule 추가 작성 (pod-alerts, node-alerts)
- [ ] Grafana Dashboard를 ConfigMap으로 버전 관리
- [ ] AlertManager를 Discord/Telegram과 연동

---

**작성일**: 2026-02-06
**카테고리**: Observability, Kubernetes, GitOps
**태그**: prometheus, servicemonitor, prometheusrule, operator, argocd, gitops, kubernetes

**참고 문서**:
- [Prometheus Operator 공식 문서](https://prometheus-operator.dev/)
- [kube-prometheus-stack Helm Chart](https://github.com/prometheus-community/helm-charts/tree/main/charts/kube-prometheus-stack)
- [ServiceMonitor CRD Spec](https://github.com/prometheus-operator/prometheus-operator/blob/main/Documentation/api.md#servicemonitor)
