---
title: "Falco-Wazuh 연동 트러블슈팅: Helm Wrapper Chart의 함정"
date: 2026-02-12T19:00:00+09:00
categories:
  - study
  - Troubleshooting
tags:
  - Falco
  - Wazuh
  - Helm
  - Wrapper Chart
  - Subchart
  - GitOps
  - ArgoCD
  - SIEM
  - Kubernetes
  - Sealed Secrets
---

Falco 런타임 보안 이벤트를 Wazuh SIEM으로 연동하는 과정에서 4시간 동안 values.yaml 설정이 ConfigMap에 반영되지 않는 문제와 씨름했다. Chart 버전 문제, ArgoCD auto-sync 충돌, 수동 패치 시도 등 여러 시행착오를 거쳐 최종적으로 **Helm Wrapper Chart의 Subchart values 전달 규칙**을 잘못 이해한 설정 오류였음을 발견했다.

<!--more-->

## 목표

**구축하려는 아키텍처**:
```
Falco (eBPF 이벤트 탐지)
  → Falcosidekick (이벤트 라우터)
  → Wazuh SIEM (Syslog 수신)
  → Wazuh Dashboard (시각화)
```

**목적**:
- Kubernetes 런타임 보안 이벤트 중앙화
- 의심스러운 활동 실시간 탐지 및 경고
- 통합 보안 모니터링 대시보드 구축

---

## 1단계: Wazuh 배포 (Helm Chart Deprecated 문제)

### 문제 발견

Wazuh 공식 Helm Chart를 사용하려고 했으나:

```bash
helm repo add wazuh https://wazuh.github.io/wazuh-kubernetes
helm search repo wazuh
# Error: Chart deprecated!
```

공식 Helm Chart가 deprecated 되었고, 대신 **Kustomize 기반 YAML**로 관리하도록 변경되었다.

### 해결: Kustomize로 배포

```bash
# Wazuh 공식 manifest 다운로드
git clone https://github.com/wazuh/wazuh-kubernetes.git
cd wazuh-kubernetes

# Kustomize 구조 확인
ls -la
# kustomization.yaml
# wazuh_managers/
# indexer_stack/
# ...
```

**kustomization.yaml 구조**:
```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

namespace: security

secretGenerator:
  - name: indexer-certs
    files:
      - certs/indexer_cluster/root-ca.pem
      - certs/indexer_cluster/node.pem
      # ...

resources:
  - base/wazuh-ns.yaml
  - wazuh_managers/wazuh-master-sts.yaml
  - wazuh_managers/wazuh-worker-sts.yaml
  - indexer_stack/wazuh-indexer/cluster/indexer-sts.yaml
  - indexer_stack/wazuh-dashboard/dashboard-deploy.yaml
```

배포 확인:
```bash
kubectl get pods -n security
# wazuh-manager-master-0    1/1   Running
# wazuh-manager-worker-0    1/1   Running
# wazuh-indexer-0           1/1   Running
# wazuh-dashboard-xxx       1/1   Running
```

---

## 2단계: Sealed Secrets 변환 (보안 강화)

### 문제: 평문 Secret이 Git에 노출됨

Wazuh manifest의 Secret들이 base64 인코딩만 되어 있었다:

```yaml
# wazuh-api-cred-secret.yaml
apiVersion: v1
kind: Secret
metadata:
  name: wazuh-api-cred
data:
  username: d2F6dWg=  # base64("wazuh")
  password: V2F6dWgxMjM0  # base64("Wazuh1234")
```

base64는 암호화가 아니라 단순 인코딩이므로 Git에 커밋하면 안 된다.

### 해결: Sealed Secrets로 변환

**Sealed Secrets란?**:
- Bitnami Sealed Secrets Controller 사용
- 공개키로 암호화하여 Git에 안전하게 저장
- Controller가 클러스터에서 복호화

**변환 과정**:
```bash
# 1. 평문 Secret 생성
kubectl create secret generic wazuh-api-cred \
  --from-literal=username=wazuh \
  --from-literal=password='Wazuh1234' \
  --dry-run=client -o yaml > wazuh-api-cred-secret.yaml

# 2. SealedSecret으로 변환
kubeseal < wazuh-api-cred-secret.yaml \
  --format yaml \
  --controller-name=sealed-secrets \
  --controller-namespace=kube-system \
  > sealed-secrets/wazuh-api-cred-sealedsecret.yaml

# 3. 평문 Secret 삭제
rm wazuh-api-cred-secret.yaml
```

**생성된 SealedSecret**:
```yaml
apiVersion: bitnami.com/v1alpha1
kind: SealedSecret
metadata:
  name: wazuh-api-cred
  namespace: security
spec:
  encryptedData:
    username: AgBX7k2F... (암호화된 문자열)
    password: AgCY9mK3... (암호화된 문자열)
```

5개 Secret 모두 변환 완료:
- `wazuh-api-cred-sealedsecret.yaml`
- `wazuh-authd-pass-sealedsecret.yaml`
- `wazuh-cluster-key-sealedsecret.yaml`
- `dashboard-cred-sealedsecret.yaml`
- `indexer-cred-sealedsecret.yaml`

**kustomization.yaml 수정**:
```yaml
resources:
  # Secret Generator 제거
  # secretGenerator:
  #   - name: wazuh-api-cred
  #     ...

  # SealedSecret 추가
  - sealed-secrets/wazuh-api-cred-sealedsecret.yaml
  - sealed-secrets/wazuh-authd-pass-sealedsecret.yaml
  - sealed-secrets/wazuh-cluster-key-sealedsecret.yaml
  - sealed-secrets/dashboard-cred-sealedsecret.yaml
  - sealed-secrets/indexer-cred-sealedsecret.yaml
```

Git commit 후 ArgoCD가 자동으로 배포하고, Sealed Secrets Controller가 복호화하여 Secret 생성.

---

## 3단계: Falco values.yaml 설정 (첫 번째 실패)

### Falco 배포

```yaml
# apps/falco/Chart.yaml
apiVersion: v2
name: my-falco
version: 0.1.0
dependencies:
  - name: falco
    version: 7.2.1
    repository: https://falcosecurity.github.io/charts
```

```yaml
# apps/falco/values.yaml
http_output:
  enabled: true
  url: http://falco-falcosidekick:2801/
  insecure: true

grpc:
  enabled: true
  bind_address: unix:///run/falco/falco.sock
  threadiness: 0

grpc_output:
  enabled: true

falcosidekick:
  enabled: true
  config:
    syslog:
      host: wazuh.security.svc.cluster.local
      port: "515"
      protocol: tcp
      format: json
```

Git push → ArgoCD Sync → Falco 배포 완료.

### 문제: ConfigMap에 설정 반영 안 됨

```bash
kubectl get configmap falco -n falco -o yaml | grep -A 5 "http_output:"
```

```yaml
http_output:
  enabled: false  # ❌ 왜 false?
  url: ""
```

values.yaml에는 `enabled: true`인데 ConfigMap에는 `false`로 되어 있었다.

---

## 4단계: ArgoCD Auto-Sync와의 싸움

### 시도 1: 수동 ConfigMap 패치

"ArgoCD가 잘못 Sync한 것일 수도 있으니 직접 수정해보자"

```bash
# ConfigMap 추출
kubectl get configmap falco -n falco -o yaml > /tmp/falco-config.yaml

# 수동 수정
vi /tmp/falco-config.yaml
# http_output:
#   enabled: true  # false → true로 변경

# 적용
kubectl apply -f /tmp/falco-config.yaml
configmap/falco configured
```

Pod 재시작:
```bash
kubectl delete pods -n falco -l app.kubernetes.io/name=falco
```

**결과**: 10초 후 ConfigMap이 다시 `enabled: false`로 되돌아감!

### 원인: GitOps 원칙

**ArgoCD Auto-Sync**:
- Git을 Single Source of Truth로 관리
- ConfigMap이 Git과 다르면 자동으로 재동기화
- 수동 변경은 즉시 덮어씌워짐

### 시도 2: ArgoCD Auto-Sync 비활성화

```bash
kubectl patch application falco -n argocd --type merge -p '{"spec":{"syncPolicy":{"automated":null}}}'
```

다시 ConfigMap 수동 패치 시도:
```bash
kubectl apply -f /tmp/falco-config.yaml
kubectl delete pods -n falco -l app.kubernetes.io/name=falco
```

**결과**: ConfigMap 여전히 되돌아감!

ArgoCD auto-sync를 비활성화했는데도 ConfigMap이 되돌아간다? 무엇이 ConfigMap을 재생성하고 있는가?

### 원인 추적: Helm Release

```bash
# Helm release history 확인
helm history falco -n falco
# REVISION  UPDATED                   STATUS      CHART
# 5         2026-02-11 21:24:57       deployed    falco-7.2.1
```

**발견**: ArgoCD가 Helm Chart를 사용하여 배포하고 있었다!

```
ArgoCD → Helm Chart → ConfigMap 생성
```

ConfigMap의 owner를 확인:
```bash
kubectl get configmap falco -n falco -o yaml | grep -A 5 "metadata:"
```

```yaml
metadata:
  labels:
    app.kubernetes.io/managed-by: Helm
  annotations:
    meta.helm.sh/release-name: falco
```

**Helm이 ConfigMap을 관리**하고 있었다! 그래서 수동 패치가 소용없었던 것이다.

**결론**: ConfigMap을 바꾸려면 values.yaml을 고쳐야 한다. 하지만 values.yaml은 이미 올바르게 설정되어 있는데 왜 반영이 안 되는가?

---

## 5단계: Chart 버전 업그레이드 시도

### 가설

"Chart 7.2.1이 2023년 버전이라 오래되었다. values.yaml 구조가 변경되었거나 버그가 있을 것이다."

### Chart 버전 확인

```bash
helm search repo falcosecurity/falco --versions | head -10
```

```
NAME                    CHART VERSION  APP VERSION
falcosecurity/falco     8.0.0          0.43.0      # 최신!
falcosecurity/falco     7.2.1          0.42.1      # 현재
```

### Chart.yaml 수정

```yaml
dependencies:
  - name: falco
    version: 8.0.0  # 7.2.1 → 8.0.0
    repository: https://falcosecurity.github.io/charts
```

Git commit & push → ArgoCD Sync → Helm upgrade 시작.

### 문제: Pod CrashLoopBackOff

```bash
kubectl get pods -n falco -l app.kubernetes.io/name=falco
# NAME          READY   STATUS             RESTARTS
# falco-8jbsn   0/2     CrashLoopBackOff   1 (5s ago)
```

로그 확인:
```bash
kubectl logs falco-8jbsn -n falco -c falco
```

```
Thu Feb 12 10:04:57 2026: Falco version: 0.43.0
Thu Feb 12 10:04:58 2026: Loading rules from:
Thu Feb 12 10:04:58 2026:    /etc/falco/falco_rules.yaml | schema validation: ok
Error: could not initialize inotify handler
```

---

## 6단계: inotify Handler 크래시 해결

### 원인 분석

**inotify란?**:
- Linux 파일 시스템 모니터링 메커니즘
- Falco가 `watch_config_files: true` 설정으로 설정 파일 변경을 실시간 감지
- 노드의 inotify 리소스 제한 초과 시 초기화 실패

**왜 Chart 7.2.1은 괜찮았는가?**:
- Chart 7.2.1 (Falco 0.42.1): inotify 사용량 적음
- Chart 8.0.0 (Falco 0.43.0): inotify 사용 방식 변경으로 리소스 초과

### 해결: watch_config_files 비활성화

```yaml
# apps/falco/values.yaml
watch_config_files: false  # 추가
```

Git commit & push → ArgoCD Sync.

**결과**: Pod 정상 시작!

```bash
kubectl get pods -n falco -l app.kubernetes.io/name=falco
# NAME          READY   STATUS    RESTARTS
# falco-8jbsn   2/2     Running   0
```

### ConfigMap 확인

```bash
kubectl get configmap falco -n falco -o yaml | grep -A 5 "http_output:"
```

```yaml
http_output:
  enabled: false  # ❌ 여전히 false!
```

Chart를 8.0.0으로 올렸는데도 values.yaml 설정이 반영되지 않았다!

---

## 7단계: 전문가 조언 - 근본 원인 발견

막막한 상황에서 외부 전문가에게 상황을 설명했다.

### 전문가의 분석

> "지금 겪고 계신 문제는 Falco 차트의 버그가 아닙니다. 이것은 Helm의 **Subchart(Dependency) 동작 원리**를 오해해서 생긴 **설정 오류(Misconfiguration)**입니다."

### 프로젝트 구조 재확인

```
apps/falco/
├── Chart.yaml       # Wrapper Chart!
│   └── dependencies:
│         - name: falco
│           version: 8.0.0
└── values.yaml      # 여기에 설정 작성
```

**Wrapper Chart를 사용하고 있었다!**

### Helm Subchart Values 전달 규칙

**부모 Chart(Wrapper)의 values.yaml에서 자식 Chart(Subchart)의 설정을 바꾸려면**:
- 반드시 **자식 Chart 이름**으로 감싸야 함
- Top-level에 작성하면 부모 Chart 자체 설정으로 인식됨

**잘못된 설정 (현재)**:
```yaml
# apps/falco/values.yaml
http_output:         # ← Top-level
  enabled: true

grpc:
  enabled: true
```

**부모 Chart의 해석**:
- "음, `http_output`이라는 변수가 생겼군. 근데 난 이걸 쓰는 템플릿이 없어. 무시해야지."

**자식 Chart(falco)의 해석**:
- "부모님이 나한테(`falco:` 섹션) 아무 설정도 안 내려주셨네? 내 기본값(`enabled: false`)을 써야지."

### 올바른 설정

```yaml
# apps/falco/values.yaml
falco:  # ← Subchart 이름!
  http_output:
    enabled: true

  grpc:
    enabled: true
```

---

## 8단계: 최종 해결 - Subchart 형식으로 수정

### values.yaml 전체 재구성

values.yaml의 **모든 내용**을 `falco:` 키 아래로 들여쓰기:

```yaml
# apps/falco/values.yaml
falco:  # ← Subchart 이름
  collectors:
    containerEngine:
      kind: auto

  customRules:
    blog-rules.yaml: |
      - rule: Java Process Spawning Shell
        desc: Detect java process spawning a shell (RCE attack)
        condition: >
          spawned_process and
          proc.pname in (java, javac) and
          proc.name in (bash, sh, ksh, zsh, dash) and
          container
        output: >
          🚨 CRITICAL: Java 프로세스가 Shell을 실행했습니다!
        priority: CRITICAL

      - rule: Launch Package Management Process in Container
        desc: Package manager ran inside container
        condition: >
          spawned_process and
          container and
          proc.name in (apk, apt, apt-get, yum, rpm, dnf, pip, npm)
        output: >
          ⚠️ WARNING: 컨테이너 내부에서 패키지 관리자 실행!
        priority: WARNING

  driver:
    kind: modern_ebpf

  http_output:
    enabled: true
    url: http://falco-falcosidekick:2801/
    insecure: true

  json_output: true
  json_include_output_property: true

  log_level: info
  priority: info

  watch_config_files: false

  grpc:
    enabled: true
    bind_address: unix:///run/falco/falco.sock
    threadiness: 0

  grpc_output:
    enabled: true

  falcosidekick:
    enabled: true
    config:
      loki:
        hostport: http://loki-stack.monitoring.svc.cluster.local:3100
        minimumpriority: warning
      talon:
        address: http://falco-talon.falco.svc.cluster.local:2803
        minimumpriority: warning
      syslog:
        host: wazuh.security.svc.cluster.local
        port: "515"
        protocol: tcp
        format: json
        minimumpriority: info
    resources:
      limits:
        cpu: 100m
        memory: 128Mi
      requests:
        cpu: 20m
        memory: 64Mi
    webui:
      enabled: true
      replicaCount: 1

  resources:
    limits:
      cpu: 500m
      memory: 512Mi
    requests:
      cpu: 100m
      memory: 256Mi

  serviceAccount:
    create: true
    name: falco

  tolerations:
    - effect: NoSchedule
      key: node-role.kubernetes.io/control-plane
      operator: Exists
```

Git commit:
```bash
git add apps/falco/values.yaml
git commit -m "fix(falco): Fix Wrapper Chart values.yaml structure (Subchart format)

Why:
- Wrapper Chart의 values.yaml에서 Subchart 설정을 변경하려면
  반드시 Subchart 이름(falco:) 아래에 작성해야 함
- 기존: Top-level에 직접 작성 (Wrapper Chart 설정으로 인식됨)
- 수정: 모든 설정을 'falco:' 키 아래로 들여쓰기

Root Cause:
- Helm Subchart values 전달 규칙 오해 (Misconfiguration)"

git push origin main
```

ArgoCD Sync → Helm upgrade.

### ConfigMap 검증

```bash
kubectl get configmap falco -n falco -o jsonpath='{.data.falco\.yaml}' | grep -A 15 "http_output:"
```

```yaml
http_output:
  ca_bundle: ""
  ca_cert: ""
  ca_path: /etc/falco/certs/
  client_cert: /etc/falco/certs/client/client.crt
  client_key: /etc/falco/certs/client/client.key
  compress_uploads: false
  echo: false
  enabled: true  # ✅ 드디어 true!
  insecure: true
  keep_alive: false
  max_consecutive_timeouts: 5
  mtls: false
  url: http://falco-falcosidekick:2801
  user_agent: falcosecurity/falco
```

**성공!** 4시간 만에 `http_output.enabled: true`가 ConfigMap에 반영되었다!

---

## 9단계: 연동 테스트

### 테스트 시나리오

컨테이너에서 패키지 관리자 실행 (Falco 규칙 위반):

```bash
kubectl run test-falco-success --image=alpine:latest --restart=Never -n default \
  --overrides='{"spec":{"nodeSelector":{"kubernetes.io/hostname":"k8s-worker1"}}}' \
  --command -- sh -c "apk add vim && sleep 3600"
```

### Falco 이벤트 탐지 확인

```bash
kubectl logs -n falco -l app.kubernetes.io/name=falco --tail=20 | grep "Package Manager"
```

**출력**:
```json
{
  "hostname": "k8s-worker1",
  "output": "10:20:53.425148392: Warning ⚠️ WARNING: 컨테이너 내부에서 패키지 관리자가 실행되었습니다!\n  (user=root pod=test-falco-success namespace=default\n   cmd=apk add vim container=test-falco-success)",
  "priority": "Warning",
  "rule": "Launch Package Management Process in Container",
  "source": "syscall",
  "tags": ["T1059", "container", "maturity_stable", "mitre_execution", "process"],
  "time": "2026-02-12T10:20:53.425148392Z",
  "output_fields": {
    "container.id": "a1b2c3d4e5f6",
    "container.name": "test-falco-success",
    "k8s.ns.name": "default",
    "k8s.pod.name": "test-falco-success",
    "proc.cmdline": "apk add vim",
    "user.name": "root"
  }
}
```

✅ Falco가 이벤트를 탐지했다!

### Falcosidekick 로그 확인

```bash
kubectl logs -n falco deployment/falco-falcosidekick --tail=30
```

**출력**:
```
2026/02/12 10:19:55 [INFO]  : Falcosidekick version: 2.32.0
2026/02/12 10:19:55 [INFO]  : Enabled Outputs: [Loki WebUI Syslog Talon]
2026/02/12 10:19:55 [INFO]  : Falcosidekick is up and listening on :2801

2026/02/12 10:20:53 [INFO]  : Loki - POST OK (204)
2026/02/12 10:20:53 [INFO]  : WebUI - POST OK (200)
2026/02/12 10:20:53 [INFO]  : Talon - POST OK (200)
```

✅ Falcosidekick이 이벤트를 수신하고 Loki, WebUI, Talon으로 전송했다!

### Falcosidekick 환경 변수 확인

```bash
kubectl exec -n falco deployment/falco-falcosidekick -- env | grep -i syslog
```

**출력**:
```
SYSLOG_HOST=wazuh.security.svc.cluster.local
SYSLOG_PORT=515
SYSLOG_FORMAT=json
SYSLOG_PROTOCOL=tcp
SYSLOG_MINIMUMPRIORITY=info
```

✅ Syslog 설정도 모두 반영되었다!

### 연결 테스트

```bash
kubectl exec -n falco deployment/falco-falcosidekick -- nc -zv wazuh.security.svc.cluster.local 515
```

**출력**:
```
wazuh.security.svc.cluster.local (10.105.111.17:515) open
```

✅ Wazuh Syslog 포트 연결 가능!

---

## 결과 정리

### 성공한 부분

| 항목 | 상태 | 검증 방법 |
|------|------|----------|
| **Wazuh 배포** | ✅ | Kustomize + Sealed Secrets |
| **Falco 이벤트 탐지** | ✅ | JSON 형식 로그 출력 |
| **Falco → Falcosidekick** | ✅ | HTTP 전송 (http_output.enabled: true) |
| **Loki 연동** | ✅ | POST OK (204) |
| **WebUI 연동** | ✅ | POST OK (200) |
| **Talon 연동** | ✅ | POST OK (200) |
| **Syslog 설정** | ✅ | 환경 변수 확인 |

### 진행 중

| 항목 | 상태 | 다음 단계 |
|------|------|----------|
| **Falcosidekick → Wazuh Syslog** | 설정 완료 | 전송 로그 확인 필요 |
| **Wazuh Dashboard 시각화** | 대기 중 | Syslog 수신 후 진행 |

---

## 교훈

### 1. Helm Wrapper Chart의 함정

**Wrapper Chart 사용 이유**:
- 외부 Chart + 커스텀 리소스 함께 관리
- 버전 고정 및 일관성 유지
- ArgoCD Application 하나로 전체 스택 배포

**함정**:
- Subchart values 전달 규칙이 직관적이지 않음
- Top-level에 작성하면 Wrapper Chart 자체 설정으로 인식
- 디버깅 어려움 (Chart 버그인지 설정 오류인지 구분 어려움)

**올바른 사용법**:
```yaml
# Chart.yaml
dependencies:
  - name: prometheus
    version: 25.0.0

# values.yaml
prometheus:  # ← Subchart 이름 필수!
  server:
    retention: 30d
  alertmanager:
    enabled: true
```

### 2. 문제 해결 접근법

**잘못된 접근** (오늘의 실수):
1. "Chart가 오래되었을 것" → 업그레이드 (시간 낭비)
2. "Chart에 버그가 있을 것" → 디버깅 (시간 낭비)
3. "수동으로 고치면 되겠지" → GitOps 충돌 (시간 낭비)

**올바른 접근**:
1. **Chart 구조 확인** (Wrapper인가? Standalone인가?)
2. **values 전달 규칙 확인** (공식 문서 읽기)
3. **근본 원인 파악** → 해결

### 3. GitOps 원칙

**ArgoCD Auto-Sync**:
- Git이 Single Source of Truth
- 클러스터 상태는 항상 Git과 일치해야 함
- 수동 변경은 즉시 되돌려짐

**교훈**:
- 클러스터에서 직접 수정하지 말 것
- Git Manifest를 수정하고 ArgoCD Sync 기다릴 것
- 긴급 수정 필요 시 ArgoCD auto-sync 일시 비활성화

### 4. Helm의 ConfigMap 관리

**Helm이 리소스를 관리할 때**:
- `kubectl edit`/`kubectl apply`로 수동 변경 불가
- Helm release가 계속 원래 상태로 되돌림
- values.yaml을 수정하고 `helm upgrade`로 반영

**확인 방법**:
```bash
kubectl get <resource> -o yaml | grep "app.kubernetes.io/managed-by"
# app.kubernetes.io/managed-by: Helm
```

### 5. 트러블슈팅 시간 단축

**4시간 → 30분으로 단축하려면**:
1. **Chart 구조부터 확인** (Wrapper인지 먼저 파악)
2. **공식 문서 읽기** (values.yaml 구조 예시 확인)
3. **작은 단위로 테스트** (전체 배포 말고 ConfigMap만 먼저 확인)
4. **커뮤니티 활용** (비슷한 사례 검색, 전문가 조언)

---

## 다음 단계

### 단기 (1주일)
- [ ] Falcosidekick → Wazuh Syslog 전송 디버깅
- [ ] Wazuh Dashboard에서 Falco 이벤트 시각화
- [ ] Falco 커스텀 규칙 튜닝 (False Positive 감소)

### 중기 (1개월)
- [ ] Falco → Discord 알람 연동
- [ ] Wazuh Rule 커스터마이징 (Falco 이벤트 전용)
- [ ] SLO 정의 (보안 이벤트 탐지율, 응답 시간)

### 장기 (3개월)
- [ ] Wrapper Chart → Standalone Chart 전환 검토
- [ ] Helm Chart 버전 자동 업그레이드 파이프라인
- [ ] 통합 보안 대시보드 구축

---

## 참고 자료

### Helm
- [Helm Subchart Values](https://helm.sh/docs/chart_template_guide/subcharts_and_globals/)
- [Helm Values Files](https://helm.sh/docs/chart_template_guide/values_files/)

### Falco
- [Falco Helm Chart](https://github.com/falcosecurity/charts/tree/master/charts/falco)
- [Falcosidekick](https://github.com/falcosecurity/falcosidekick)
- [Falco Rules](https://falco.org/docs/rules/)

### Wazuh
- [Wazuh Kubernetes Deployment](https://github.com/wazuh/wazuh-kubernetes)
- [Wazuh Syslog Integration](https://documentation.wazuh.com/current/user-manual/manager/manual-syslog-output.html)

### GitOps
- [ArgoCD Best Practices](https://argo-cd.readthedocs.io/en/stable/user-guide/best_practices/)
- [Sealed Secrets](https://github.com/bitnami-labs/sealed-secrets)

---

## 결론

4시간의 트러블슈팅 끝에 얻은 가장 중요한 교훈:

> **Chart 버그를 의심하기 전에, 내 설정 형식이 올바른지 먼저 확인하자.**

오늘의 문제는 Falco Chart 7.2.1도, 8.0.0도 아닌, **Helm Wrapper Chart의 Subchart values 전달 규칙**을 잘못 이해한 설정 오류였다. Chart를 업그레이드하고, ArgoCD와 싸우고, 수동 패치를 시도한 모든 시간이 결국 "values.yaml 첫 줄에 `falco:`를 추가하는 것"으로 해결되었다.

실수는 반복하지 않기 위해 이 글을 남긴다.
