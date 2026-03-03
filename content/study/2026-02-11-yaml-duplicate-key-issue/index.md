---
title: "YAML 중복 키 함정: 280줄의 설정이 13줄에 의해 무효화된 사건"
date: 2026-02-11T14:00:00+09:00
draft: false
categories:
  - study
  - Troubleshooting
tags:
  - YAML
  - Helm
  - Kubernetes
  - kube-prometheus-stack
  - ArgoCD
  - Debugging
---

홈랩 Kubernetes 클러스터에서 Prometheus 모니터링을 구축하던 중, ArgoCD Application이 **9시간 동안 Syncing 상태**에서 멈춰있는 문제가 발생했다. 조사 결과, values.yaml에서 같은 키가 중복 선언되어 **280줄의 설정이 13줄에 의해 완전히 무효화**되었던 것이 원인이었다.

## 문제 상황

### 증상

```bash
# ArgoCD Application 상태 확인
kubectl get application kube-prometheus-stack -n argocd

# NAME                      HEALTH       STATUS
# kube-prometheus-stack     Progressing  Syncing (9시간 경과)
```

ArgoCD가 9시간째 Syncing 상태에서 멈춰있었다. Healthy 상태가 되지 않고 계속 "Progressing"만 표시되었다.

### 리소스 확인

```bash
# Pod 상태 확인
kubectl get pods -n monitoring | grep node-exporter

# kube-prometheus-stack-prometheus-node-exporter-4pvhw  0/1  Pending
# kube-prometheus-stack-prometheus-node-exporter-7x2nm  0/1  Pending
# kube-prometheus-stack-prometheus-node-exporter-k9s8t  0/1  Pending
# kube-prometheus-stack-prometheus-node-exporter-wd5lr  0/1  Pending
```

Node Exporter DaemonSet이 4개 모두 Pending 상태였다.

```bash
# DaemonSet 상세 확인
kubectl describe pod kube-prometheus-stack-prometheus-node-exporter-4pvhw -n monitoring

# Events:
#   Warning  FailedScheduling  0/4 nodes available: 4 node(s) didn't have free ports for the requested pod ports
#   Port 9100 already in use
```

**원인**: 9100 포트가 이미 사용 중이었다. Grafana Alloy DaemonSet이 이미 9100 포트를 사용하고 있었기 때문에 Node Exporter가 스케줄링되지 못했다.

### 의문점

values.yaml에서 분명히 Node Exporter를 비활성화했는데 왜 DaemonSet이 생성되었을까?

```yaml
# apps/kube-prometheus-stack/values.yaml (12줄~)
kube-prometheus-stack:
  nodeExporter:
    enabled: false  # ← 명백히 false인데?
```

---

## 근본 원인 분석

### values.yaml 파일 검토

```bash
# values.yaml에서 "kube-prometheus-stack" 키 검색
grep -n "^kube-prometheus-stack:" apps/kube-prometheus-stack/values.yaml

# 12:kube-prometheus-stack:
# 290:kube-prometheus-stack:  ← 중복!
```

**발견**: `kube-prometheus-stack:` 키가 **두 번 선언**되어 있었다!

#### 첫 번째 블록 (12줄~289줄)

```yaml
# 12줄
kube-prometheus-stack:
  # Prometheus Operator
  prometheusOperator:
    enabled: true
    # ... (280줄 분량)

  # Node Exporter 비활성화
  nodeExporter:
    enabled: false  # ← 이 설정이 무효화됨!

  # Prometheus
  prometheus:
    prometheusSpec:
      serviceMonitorSelector:
        matchLabels:
          prometheus: kube-prometheus
      # ... (더 많은 설정)

  # Grafana
  grafana:
    enabled: true
    adminPassword: "admin"
    # ... (더 많은 설정)

  # ... (총 280줄)
```

#### 두 번째 블록 (290줄~302줄)

```yaml
# 290줄
kube-prometheus-stack:
  # Subchart: kube-state-metrics
  kube-state-metrics:
    prometheus:
      monitor:
        enabled: true
        additionalLabels:
          prometheus: kube-prometheus
```

**총 13줄**만 있는 블록이었다.

---

### YAML 파싱 규칙의 함정

YAML에서 같은 키가 중복되면 **마지막 값만 유효**하다.

```yaml
# 예시
person:
  name: "Alice"
  age: 30
  job: "Engineer"

person:  # ← 중복!
  city: "Seoul"
```

**파싱 결과**:
```yaml
# 첫 번째 블록이 완전히 무효화됨
person:
  city: "Seoul"  # ← 이것만 남음
```

`name`, `age`, `job`은 모두 사라지고 **두 번째 블록만 유효**하게 된다.

---

### 우리 케이스에 적용

```yaml
# 첫 번째 블록 (12줄~289줄)
kube-prometheus-stack:
  nodeExporter:
    enabled: false  # ← 무효!
  prometheus: ...   # ← 무효!
  grafana: ...      # ← 무효!
  # ... 280줄 모두 무효!

# 두 번째 블록 (290줄~302줄)
kube-prometheus-stack:  # ← 이것만 유효!
  kube-state-metrics:
    prometheus:
      monitor:
        enabled: true
```

**결과**:
- 첫 번째 블록의 280줄이 **완전히 무효화**됨
- Helm은 Chart 기본값(`nodeExporter.enabled: true`)을 사용
- Node Exporter DaemonSet이 생성됨

---

## 왜 ArgoCD가 9시간 동안 멈췄는가?

### ArgoCD Sync 프로세스

ArgoCD는 다음 순서로 작동한다:

```
1. Git에서 Manifest 읽기
2. Kubernetes에 리소스 적용
3. 모든 리소스가 Healthy가 될 때까지 대기 ← 여기서 멈춤!
4. Synced 상태 표시
```

### 우리 케이스

```
1. values.yaml 읽기
   → nodeExporter.enabled: false (첫 번째 블록) 무효화
   → Helm Chart 기본값 사용 (nodeExporter.enabled: true)

2. Node Exporter DaemonSet 생성 시도
   → 4개 Pod 모두 Pending (9100 포트 충돌)

3. ArgoCD는 Pod가 Ready가 될 때까지 무한 대기
   → 9시간 동안 "Progressing" 상태
```

**근본 원인**: ArgoCD는 Healthy 조건을 기다렸지만, Pod가 영원히 Pending 상태여서 완료되지 못했다.

---

## 해결 과정

### Step 1: 중복 블록 발견

```bash
# values.yaml에서 중복 확인
grep -n "^kube-prometheus-stack:" apps/kube-prometheus-stack/values.yaml

# 12:kube-prometheus-stack:
# 290:kube-prometheus-stack:  ← 발견!
```

### Step 2: 블록 병합

**전략**: 두 번째 블록의 내용을 첫 번째 블록에 병합

```yaml
# apps/kube-prometheus-stack/values.yaml (230줄~)
kube-prometheus-stack:
  # ... (기존 280줄)

  # Subchart: kube-state-metrics
  # Why: ServiceMonitor에 prometheus label 추가
  kube-state-metrics:  # ← 두 번째 블록 내용 추가
    prometheus:
      monitor:
        enabled: true
        additionalLabels:
          prometheus: kube-prometheus

  # Default Rules
  defaultRules:
    # ...
```

### Step 3: 중복 블록 삭제

```yaml
# 290줄~302줄 완전 삭제
# ==============================================================================
# Dependency Chart: kube-prometheus-stack
# ==============================================================================
kube-prometheus-stack:  # ← 삭제!
  kube-state-metrics:
    prometheus:
      monitor:
        enabled: true
        additionalLabels:
          prometheus: kube-prometheus
```

### Step 4: 검증

```bash
# Helm Template 렌더링 결과 확인
helm template kube-prometheus-stack apps/kube-prometheus-stack \
  | grep -A 5 "kind: DaemonSet"

# 결과: DaemonSet 0개 (이전: 1개)
```

**확인**: Node Exporter DaemonSet이 생성되지 않았다!

### Step 5: Git Commit & Push

```bash
git add apps/kube-prometheus-stack/values.yaml
git commit -m "fix(kube-prometheus-stack): YAML 중복 블록 제거 + nodeExporter 비활성화"
git push
```

### Step 6: ArgoCD Sync

```bash
# ArgoCD 자동 Sync (3초 이내)
kubectl get application kube-prometheus-stack -n argocd

# NAME                      HEALTH   STATUS
# kube-prometheus-stack     Healthy  Synced  ← 성공!
```

**결과**:
- Node Exporter DaemonSet 자동 삭제 (prune: true)
- ArgoCD stuck operation 해결
- 9시간 걸리던 Sync가 30초 만에 완료

---

## 교훈

### 1. YAML 중복 키는 "Merge"가 아니라 "Replace"

**잘못된 기대**:
```yaml
# "병합될 줄 알았다"
kube-prometheus-stack:
  nodeExporter:
    enabled: false
  prometheus: ...

kube-prometheus-stack:
  kube-state-metrics: ...

# 예상: 두 블록이 합쳐질 것
```

**실제 동작**:
```yaml
# 마지막 블록만 유효
kube-prometheus-stack:
  kube-state-metrics: ...  # ← 이것만 남음
```

### 2. Helm은 YAML 파싱 오류를 감지하지 못함

Helm은 다음만 검증한다:
- ✅ YAML 문법 오류 (Syntax Error)
- ✅ Chart 스키마 위반

Helm은 다음을 검증하지 **못한다**:
- ❌ 중복 키
- ❌ 의도하지 않은 값 덮어쓰기

**결과**: 중복 키 문제는 런타임에서만 발견된다.

### 3. ArgoCD가 멈춘 이유를 먼저 확인

ArgoCD가 Syncing 상태에서 멈췄다면:

```bash
# 1. 어떤 리소스가 Unhealthy인지 확인
kubectl get application kube-prometheus-stack -n argocd -o yaml \
  | grep -A 20 "status:"

# 2. 해당 리소스 상태 확인
kubectl get pods -n monitoring
kubectl describe pod <pod-name> -n monitoring

# 3. Events 확인
kubectl get events -n monitoring --sort-by='.lastTimestamp'
```

우리 케이스에서는 Node Exporter Pod가 Pending이었고, Events를 보니 "port already in use"였다.

### 4. `helm template`로 사전 검증 필수

Helm Chart를 배포하기 전에 렌더링 결과를 반드시 확인해야 한다:

```bash
# 의도하지 않은 리소스가 생성되는지 확인
helm template kube-prometheus-stack apps/kube-prometheus-stack \
  | grep "kind: DaemonSet"

# 예상: 0개 (nodeExporter.enabled: false)
# 실제: 1개 (중복 키 문제!) ← 발견!
```

### 5. 대용량 YAML 파일은 섹션별로 분리

values.yaml이 302줄이나 되다 보니 중복 키를 눈으로 발견하기 어려웠다.

**권장 방식**:
```
apps/kube-prometheus-stack/
├── values.yaml               # 메인 설정
├── values-prometheus.yaml    # Prometheus 전용
├── values-grafana.yaml       # Grafana 전용
├── values-alertmanager.yaml  # AlertManager 전용
```

또는 Helm의 `--values` 플래그로 여러 파일 병합:
```bash
helm install kube-prometheus-stack \
  --values values.yaml \
  --values values-prometheus.yaml \
  --values values-grafana.yaml
```

### 6. YAML Linter 사용

`yamllint`는 중복 키를 감지한다:

```bash
# yamllint 설치
pip install yamllint

# 중복 키 검사
yamllint apps/kube-prometheus-stack/values.yaml

# 출력:
# 290:1  error  duplication of key "kube-prometheus-stack" in mapping
```

**교훈**: CI/CD 파이프라인에 yamllint 추가 필수!

---

## 검증 방법

### YAML 중복 키 테스트

```bash
# 테스트용 YAML 생성
cat > test-duplicate.yaml <<EOF
person:
  name: "Alice"
  age: 30

person:
  city: "Seoul"
EOF

# Python으로 파싱
python3 << 'PYTHON'
import yaml
with open('test-duplicate.yaml') as f:
    data = yaml.safe_load(f)
    print(data)
# {'person': {'city': 'Seoul'}}  ← name, age 사라짐!
PYTHON
```

### yamllint로 중복 키 자동 감지

```bash
# yamllint 설정 (.yamllint)
cat > .yamllint <<EOF
extends: default
rules:
  key-duplicates:
    level: error  # 중복 키는 에러로 처리
EOF

# 검사 실행
yamllint test-duplicate.yaml

# 출력:
# test-duplicate.yaml
#   4:1       error    duplication of key "person" in mapping  (key-duplicates)
```

---

## 정량적 성과

### Before (중복 키 존재)

| 항목 | 값 |
|------|-----|
| ArgoCD Sync 시간 | 9시간+ (완료 안 됨) |
| Node Exporter DaemonSet | 4개 (Pending) |
| 9100 포트 충돌 | ❌ 발생 |
| values.yaml 유효 줄 수 | 13줄 (280줄 무효) |

### After (중복 키 제거)

| 항목 | 값 |
|------|-----|
| ArgoCD Sync 시간 | 30초 |
| Node Exporter DaemonSet | 0개 (의도대로) |
| 9100 포트 충돌 | ✅ 해결 |
| values.yaml 유효 줄 수 | 293줄 (100%) |

**개선 효과**:
- ✅ ArgoCD Sync 시간 **99.9% 단축** (9시간 → 30초)
- ✅ 280줄의 설정 복구 (nodeExporter, prometheus, grafana 등)
- ✅ 9100 포트 충돌 해결
- ✅ Helm Chart 의도대로 동작

---

## 재발 방지 대책

### 1. Pre-commit Hook에 yamllint 추가

```bash
# .git/hooks/pre-commit
#!/bin/bash
yamllint apps/kube-prometheus-stack/values.yaml || {
  echo "❌ YAML 중복 키 발견!"
  exit 1
}
```

### 2. CI/CD 파이프라인에 검증 단계 추가

```yaml
# .github/workflows/validate-yaml.yml
name: Validate YAML

on: [push, pull_request]

jobs:
  yamllint:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - name: Install yamllint
        run: pip install yamllint
      - name: Check for duplicate keys
        run: yamllint -s apps/
```

### 3. helm template 자동 검증

```bash
# CI/CD에서 실행
helm template kube-prometheus-stack apps/kube-prometheus-stack \
  | grep "kind: DaemonSet" \
  | grep "node-exporter" && {
    echo "❌ 의도하지 않은 Node Exporter DaemonSet 발견!"
    exit 1
  }
```

### 4. ArgoCD Notification 설정

ArgoCD가 1시간 이상 Syncing 상태면 알림:

```yaml
# argocd-notifications-cm
triggers:
  - name: sync-stuck
    condition: app.status.operationState.phase == 'Running' && time.Now().Sub(time.Parse(app.status.operationState.startedAt)) > time.Hour
    template: sync-stuck-alert
```

---

## 결론

### 핵심 메시지

YAML에서 **같은 키가 중복되면 마지막 값만 유효**하며, 이전 값은 완전히 무효화된다. 280줄의 설정이 13줄에 의해 덮어씌워지는 치명적인 문제가 발생할 수 있다.

### 배운 점

1. **YAML 중복 키는 "Merge"가 아니라 "Replace"**: 병합이 아니라 덮어쓰기
2. **Helm은 중복 키를 감지하지 못함**: yamllint 등 별도 도구 필요
3. **helm template로 사전 검증 필수**: 의도하지 않은 리소스 생성 방지
4. **ArgoCD가 멈췄다면 Events 먼저 확인**: Pod Pending 원인 파악
5. **대용량 YAML은 섹션별로 분리**: 가독성과 관리 용이성
6. **CI/CD에 yamllint 추가**: 중복 키 자동 감지

### 다음 단계

- [x] values.yaml 중복 블록 제거
- [x] ArgoCD Sync 정상화
- [ ] Pre-commit Hook에 yamllint 추가 (P0)
- [ ] CI/CD에 YAML 검증 단계 추가 (P0)
- [ ] values.yaml을 섹션별로 분리 검토 (P2)
- [ ] ArgoCD Notification 설정 (P1)

---

**작성일**: 2026-02-11
**트러블슈팅 소요 시간**: 약 3시간
**핵심 해결 키워드**: YAML duplicate keys, Helm values.yaml, ArgoCD stuck

**참고 자료**:
- [YAML Specification - Duplicate Keys](https://yaml.org/spec/1.2.2/#example-mapping-duplicate-keys)
- [yamllint - Key Duplicates Rule](https://yamllint.readthedocs.io/en/stable/rules.html#module-yamllint.rules.key_duplicates)
- [Helm Chart Best Practices - Values Files](https://helm.sh/docs/chart_best_practices/values/)
