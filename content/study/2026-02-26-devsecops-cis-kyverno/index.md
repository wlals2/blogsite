---
title: "[DevSecOps 시리즈 #3] kube-bench 오탐 분석 + kubelet CA 취약점 개선 + Kyverno Policy as Code"
date: 2026-02-26T15:00:00+09:00
categories:
  - study
  - Security
tags: ["kubernetes", "kube-bench", "cis-benchmark", "kyverno", "admission-controller", "policy-as-code", "devsecops", "kubelet"]
summary: "kube-bench 재실행으로 FAIL 5개 중 4개가 오탐임을 규명하고 실제 취약점인 kubelet CA 미검증을 개선했다. 이후 Kyverno Admission Controller로 kubectl 직접 배포 시에도 정책을 강제하는 Policy as Code를 구현했다."
showtoc: true
tocopen: true
draft: false
series: ["DevSecOps 시리즈"]
---

## 배경: GitOps만으로 충분한가

DevSecOps 파이프라인을 구축하면서 한 가지 맹점을 발견했다.

```
git push → GitLeaks → Trivy → ArgoCD → 배포
```

이 흐름은 **Git을 거치는 배포**에만 보안 검사가 적용된다. `kubectl apply`로 직접 배포하면 GitLeaks도, Trivy도 건너뛴다. 현재 1인 운영이라 실제로 우회할 사람은 없지만, **"정책이 Git push에만 의존한다"는 구조적 허점**은 포트폴리오 관점에서도, 실무 관점에서도 해소해야 했다.

두 가지를 동시에 진행했다:
1. **kube-bench 재실행** — CIS Kubernetes Benchmark 결과를 다시 검토하여 오탐 규명 + 실제 취약점 개선
2. **Kyverno 구현** — Admission Controller로 모든 배포 경로에 정책 강제

---

## Part 1: kube-bench 재실행 — 오탐 4개 규명

### 기존 결과와 의문점

이전에 Audit Log 관련 4개 항목을 개선한 후 kube-bench를 재실행했더니 **5개의 FAIL**이 남아 있었다.

```bash
sudo docker run --rm \
  -v /etc:/etc:ro \
  -v /var/lib/kubelet:/var/lib/kubelet:ro \
  -v /usr/bin/kubectl:/usr/bin/kubectl:ro \
  -v /proc:/proc:ro \
  --pid=host \
  aquasec/kube-bench:latest run --targets master

# 출력:
[FAIL] 1.1.11 Ensure that the etcd data directory permissions are set to 700 or more restrictive
[FAIL] 1.1.12 Ensure that the etcd data directory ownership is set to etcd:etcd
[FAIL] 1.2.6  Ensure that the --kubelet-certificate-authority argument is set as appropriate
[FAIL] 1.2.16 Ensure that the --profiling argument is set to false
[FAIL] 1.2.19 Ensure that the --service-account-lookup argument is set to true
```

FAIL이 5개라는 것보다 **숫자가 예상과 달랐다**는 점이 먼저 의심스러웠다. 이전에 CIS Benchmark 관련 자료를 보면서 1.2.16이 PSP 관련이고 1.2.19가 insecure-port 관련이라고 파악했는데, 지금 결과의 1.2.16은 `--profiling`이고 1.2.19는 `--service-account-lookup`이다.

**CIS Benchmark 버전이 달랐다.** kube-bench 이미지 버전에 따라 다른 CIS 버전을 적용한다.

### Docker 명령어 오류 — etcd 오탐

1.1.11, 1.1.12 (etcd 데이터 디렉토리 권한/소유자)는 처음부터 의심스러웠다.

```bash
# FAIL이 난 명령어: /var/lib/etcd 볼륨 마운트 없음
sudo docker run --rm \
  -v /etc:/etc:ro \
  ...
  aquasec/kube-bench:latest run --targets master
```

```bash
# 올바른 명령어: /var/lib/etcd 마운트 추가
sudo docker run --rm \
  -v /etc:/etc:ro \
  -v /var/lib/etcd:/var/lib/etcd:ro \  # 이 줄이 빠져 있었다
  ...
  aquasec/kube-bench:latest run --targets master
```

kube-bench는 컨테이너 안에서 실행되므로 호스트의 `/var/lib/etcd`를 마운트하지 않으면 해당 디렉토리가 존재하지 않는 것처럼 보인다. 권한을 확인할 대상이 없으니 FAIL이 나는 것이다.

```bash
# /var/lib/etcd 마운트 추가 후 재실행 결과:
[PASS] 1.1.11 Ensure that the etcd data directory permissions are set to 700 or more restrictive
[PASS] 1.1.12 Ensure that the etcd data directory ownership is set to etcd:etcd
```

도구 실행 방법의 문제였다. 실제 etcd 권한은 이미 올바르게 설정되어 있었다.

### K8s 버전 불일치 오탐 — PSP와 insecure-port

두 항목은 CIS Benchmark 항목 번호 불일치에서 비롯된 오탐이다.

| CIS 항목 | 내용 | 실제 상황 |
|----------|------|----------|
| 1.2.16 (구 버전) | PodSecurityPolicy 미사용 | **K8s 1.25+에서 PSP 제거됨** — 설정 자체가 불가능 |
| 1.2.19 (구 버전) | `--insecure-port` 미설정 | **K8s 1.24+에서 해당 옵션 제거됨** — 적용 불가 |

kube-bench 도구가 오래된 CIS 버전 기준으로 점검하면서 이미 K8s에서 제거된 기능을 체크한 것이다. 현재 클러스터(v1.31.14)에서는 수정 자체가 불가능하다.

### 실제 취약점 — kubelet CA 미검증 (1.2.6)

나머지 항목(1.2.6)은 진짜 취약점이었다.

```
[FAIL] 1.2.6 Ensure that the --kubelet-certificate-authority argument is set as appropriate
```

kube-apiserver가 kubelet과 통신할 때 kubelet의 TLS 인증서를 검증하지 않는 상태였다. 이는 중간자 공격(MITM)에 취약한 구조다. 공격자가 kubelet을 사칭해 API 서버와 통신할 수 있다.

```yaml
# /etc/kubernetes/manifests/kube-apiserver.yaml 수정
spec:
  containers:
  - command:
    - kube-apiserver
    # ... 기존 인자들 ...
    - --kubelet-certificate-authority=/etc/kubernetes/pki/ca.crt  # 이 줄 추가
```

```bash
# 적용 후 확인 (Static Pod 자동 재시작)
kubectl get pod -n kube-system kube-apiserver-k8s-cp

# 출력:
NAME                    READY   STATUS    RESTARTS   AGE
kube-apiserver-k8s-cp   1/1     Running   0          2m

# 재실행 결과
[PASS] 1.2.6 Ensure that the --kubelet-certificate-authority argument is set as appropriate
```

### 최종 결과 요약

| 항목 | FAIL 원인 | 판정 | 조치 |
|------|----------|------|------|
| 1.1.11 etcd 권한 | Docker 마운트 누락 | 오탐 | 마운트 추가 후 PASS |
| 1.1.12 etcd 소유자 | Docker 마운트 누락 | 오탐 | 마운트 추가 후 PASS |
| 1.2.6 kubelet CA | 실제 미설정 | **실제 취약점** | `--kubelet-certificate-authority` 추가 |
| 1.2.16 PSP | PSP K8s 1.25+에서 제거 | 오탐 (버전 불일치) | 수정 불가 — 문서화 |
| 1.2.19 insecure-port | 옵션 K8s 1.24+에서 제거 | 오탐 (버전 불일치) | 수정 불가 — 문서화 |

**Before**: FAIL 5개
**After**: FAIL 2개 (오탐 3개 PASS 전환, 실제 취약점 1개 개선, 오탐 2개 문서화)

kube-bench 결과를 맹신하지 않고 **왜 FAIL인지를 분석하는 과정**이 중요하다는 것을 배웠다.

---

## Part 2: Kyverno — Policy as Code

### Admission Controller가 필요한 이유

Kubernetes의 보안 계층을 정리하면 이렇다.

```
RBAC           → "누가 API를 호출할 수 있는가"
Admission      → "API 요청의 내용이 정책에 맞는가"
Runtime (Falco)→ "Pod가 실행 중에 이상한 행동을 하는가"
```

RBAC가 `kubectl apply` 실행 권한을 제어한다면, **Admission Controller는 그 apply의 내용을 검증한다**. `kubectl apply`를 할 수 있는 사람도 `securityContext.privileged: true`나 `image: nginx:latest` 같은 정책 위반 YAML을 배포할 수 없게 만드는 것이다.

기존 파이프라인은 CI/CD를 거치는 배포에만 Trivy나 GitLeaks가 적용됐다. Admission Controller를 추가하면 **배포 경로에 관계없이** API 서버 진입점에서 정책이 강제된다.

### Kyverno vs OPA Gatekeeper 선택

두 가지 주요 선택지를 비교했다.

| 비교 항목 | Kyverno | OPA Gatekeeper |
|-----------|---------|----------------|
| **정책 언어** | YAML | Rego (별도 학습 필요) |
| **K8s 친화성** | Kubernetes-native | 범용 정책 엔진 |
| **진입 장벽** | 낮음 | 높음 |
| **실무 사용** | 증가 추세 | 오래된 표준 |
| **뮤테이션** | 지원 (자동 수정) | 지원 |
| **감사 모드** | 지원 | 지원 |

Kyverno를 선택한 이유는 두 가지다. 첫째, **YAML로 정책을 작성한다** — 기존 K8s 리소스와 동일한 방식이라 추가 언어 학습 없이 즉시 정책을 작성할 수 있다. 둘째, **Kubernetes-native** — K8s 리소스처럼 동작하므로 GitOps로 관리하기 자연스럽다.

### Kyverno 아키텍처

```
클러스터
├── kyverno namespace
│   ├── admission-controller (핵심 — ValidatingWebhookConfiguration)
│   ├── background-controller (기존 리소스 스캔)
│   ├── cleanup-controller (TTL 기반 리소스 삭제)
│   └── reports-controller (PolicyReport 생성)
└── kyverno-policies namespace 없음 — ClusterPolicy는 클러스터 전체
```

ValidatingWebhookConfiguration이 핵심이다. Kubernetes API 서버가 create/update/delete 요청을 받으면 Kyverno admission-controller에게 먼저 물어본다. Kyverno가 "거부"하면 요청 자체가 실패한다.

### GitOps로 Kyverno 배포

```
k8s-manifests/
├── apps/kyverno/           # Helm Wrapper Chart (엔진)
│   ├── Chart.yaml
│   └── values.yaml
├── argocd/
│   ├── kyverno-app.yaml         # ArgoCD Application (엔진)
│   └── kyverno-policies-app.yaml # ArgoCD Application (정책)
└── configs/kyverno-policies/    # ClusterPolicy 4개
    ├── kustomization.yaml
    ├── disallow-latest-tag.yaml
    ├── require-non-root.yaml
    ├── require-resource-limits.yaml
    └── disallow-privileged.yaml
```

엔진과 정책을 **별도 ArgoCD Application**으로 분리했다. 정책만 수정할 때 엔진을 재배포하지 않아도 되고, `sync-wave`로 엔진이 먼저 뜬 후 정책이 적용되는 순서를 보장한다.

```yaml
# argocd/kyverno-app.yaml (핵심 부분)
syncPolicy:
  syncOptions:
  - CreateNamespace=true
  - ServerSideApply=true  # Why: Kyverno CRD가 262144 bytes 초과 → annotation 저장 회피
```

`ServerSideApply=true`는 트러블슈팅 과정에서 발견한 설정이다. Kyverno CRD 크기가 kubectl의 annotation 한도(262144 bytes)를 초과해서 일반 Apply 방식으로는 ArgoCD가 CRD를 설치할 수 없었다. Server-Side Apply는 annotation에 전체 manifest를 저장하지 않아서 이 한도를 우회한다.

### 4가지 ClusterPolicy 구현

#### 1. latest 태그 차단

```yaml
# disallow-latest-tag.yaml
spec:
  validationFailureAction: Audit        # 전체: 위반 기록만
  validationFailureActionOverrides:
    - action: Enforce                   # blog-system: 실제 차단
      namespaces: [blog-system]
  rules:
    - name: disallow-latest
      validate:
        message: "latest 태그 사용 금지: 명시적 버전 태그를 사용하세요"
        pattern:
          spec:
            containers:
              - image: "!*:latest"
```

#### 2. Non-root 실행 강제

```yaml
# require-non-root.yaml
spec:
  validationFailureAction: Audit
  validationFailureActionOverrides:
    - action: Enforce
      namespaces: [blog-system]
  rules:
    - name: require-non-root
      exclude:
        any:
        - resources:
            selector:
              matchLabels:
                app: mysql  # Why: MySQL 공식 이미지가 root 사용
      validate:
        pattern:
          spec:
            securityContext:
              runAsNonRoot: true
```

#### 3. Resource Limits 강제

```yaml
# require-resource-limits.yaml
spec:
  validationFailureAction: Audit
  validationFailureActionOverrides:
    - action: Enforce
      namespaces: [blog-system]
  rules:
    - name: require-limits
      validate:
        message: "resources.limits.cpu와 resources.limits.memory를 설정하세요"
        pattern:
          spec:
            containers:
              - resources:
                  limits:
                    cpu: "?*"
                    memory: "?*"
```

#### 4. Privileged 컨테이너 차단

```yaml
# disallow-privileged.yaml
spec:
  validationFailureAction: Audit
  validationFailureActionOverrides:
    - action: Enforce
      namespaces: [blog-system]
  rules:
    - name: disallow-privileged-containers
      exclude:
        any:
        - resources:
            namespaces:
              - kube-system
              - security    # Why: Falco가 eBPF를 위해 privileged 필요
              - monitoring
              # ... 기타 시스템 namespace
      validate:
        pattern:
          spec:
            containers:
              - =(securityContext):
                  =(privileged): "false | null"
```

### 정책 검증

```bash
# latest 태그로 Pod 실행 시도
kubectl run test-latest --image=nginx:latest -n blog-system

# 출력:
Error from server: admission webhook "validate.kyverno.svc-fail" denied the request:
resource Pod/blog-system/test-latest was blocked due to the following policies

disallow-latest-tag:
  disallow-latest: 'validation error: latest 태그 사용 금지: 명시적 버전 태그를 사용하세요.
    rule disallow-latest failed at path /spec/containers/0/image/'
```

ArgoCD를 통하지 않고 `kubectl run`으로 직접 배포해도 정책이 차단하는 것을 확인했다.

```bash
# 현재 활성 정책 확인
kubectl get clusterpolicies

# 출력:
NAME                      ADMISSION   BACKGROUND   VALIDATE ACTION   READY   AGE
disallow-latest-tag       true        true         Audit             True    2d
disallow-privileged       true        true         Audit             True    2d
require-non-root          true        true         Audit             True    2d
require-resource-limits   true        true         Audit             True    2d
```

### 트러블슈팅: CRD 설치 실패

Kyverno 배포 과정에서 CRD 설치에 실패했다. 문제는 두 가지가 연쇄적으로 발생했다.

1. **ArgoCD annotation 한도 초과** → `ServerSideApply=true` 추가
2. **CRD가 설치되기 전 namespace가 없었던 문제** → 수동으로 namespace 생성 후 재시도

```bash
# CRD 수동 설치 (annotation 한도 우회)
helm template my-kyverno . \
  --include-crds \
  --namespace kyverno | kubectl apply --server-side -f -

# 출력:
customresourcedefinition.apiextensions.k8s.io/admissionreports.kyverno.io serverside-applied
customresourcedefinition.apiextensions.k8s.io/backgroundscanreports.kyverno.io serverside-applied
customresourcedefinition.apiextensions.k8s.io/clusterpolicies.kyverno.io serverside-applied
# ...
```

이후 ArgoCD Sync가 정상 동작했다.

---

## 성과

### kube-bench 개선

| 항목 | Before | After | 결과 |
|------|--------|-------|------|
| FAIL 개수 | 5개 | 2개 | FAIL 3개 감소 |
| 실제 취약점 | kubelet CA 미검증 | 개선 완료 | PASS |
| 오탐 (마운트 누락) | 2개 FAIL | 2개 PASS | 도구 실행 방법 수정 |
| 오탐 (버전 불일치) | 2개 FAIL | 2개 FAIL | 수정 불가 — 문서화 |

### Kyverno Policy as Code

| 항목 | Before | After |
|------|--------|-------|
| Admission 정책 수 | 0개 | 4개 ClusterPolicy |
| kubectl 직접 배포 차단 | 불가 | latest 태그, 권한 없는 이미지 등 차단 |
| 정책 관리 방식 | YAML 주석/권고 | GitOps로 버전 관리 + 자동 적용 |

---

## 다음 단계

DevSecOps 파이프라인이 한 단계 강해졌다.

```
git push → GitLeaks → Docker Build → Trivy → ArgoCD → Kyverno(신규) → Falco → Wazuh
```

`Kyverno`가 추가되면서 **어떤 경로로 배포하더라도 정책이 적용**된다. 다음에는 OWASP TOP 10 실습 환경을 구축해서 웹 취약점 진단 실무 워크플로우를 직접 경험해볼 계획이다.
