---
title: "[Kyverno 시리즈 #2] 4개 ClusterPolicy 구현기 — latest 태그부터 root 실행까지"
date: 2026-03-02T14:00:00+09:00
categories:
  - study
  - Security
tags: ["kubernetes", "kyverno", "clusterpolicy", "securitycontext", "policy-as-code", "argocd", "devsecops"]
summary: "latest 태그 차단, CPU/Memory limits 강제, 특권 컨테이너 금지, root 실행 차단 — 4개의 ClusterPolicy를 YAML로 구현하고 ArgoCD로 GitOps 배포한 과정을 정리한다. 각 정책이 막는 공격 시나리오와 예외를 둔 이유를 함께 설명한다."
series: ["Kyverno 실전 시리즈"]
showtoc: true
tocopen: true
draft: false
cover:
  image: "cover.jpg"
  alt: "Kyverno 4개 ClusterPolicy 구현기"
  relative: true
---

## 배경: 어떤 정책이 필요한가

[Part 1](/study/2026-03-02-kyverno-admission-controller-concept/)에서 Kyverno의 원리를 살펴봤다. 이제 실제로 어떤 정책을 구현했는지 하나씩 살펴본다.

구현한 4개 정책은 모두 **Validate** 유형이다. 위반 시 Pod 생성을 거부한다.

| # | 정책 | 막는 것 | 공격 시나리오 |
|---|------|---------|-------------|
| 1 | `disallow-latest-tag` | 이미지 태그 없음 / latest | 어떤 버전인지 모른 채 배포 → 재현 불가능한 장애 |
| 2 | `require-resource-limits` | CPU/Memory limits 누락 | 한 Pod이 노드 전체 자원 독점 (noisy neighbor) |
| 3 | `disallow-privileged` | privileged: true | 컨테이너에서 호스트 커널 직접 접근 → 노드 장악 |
| 4 | `require-non-root` | root(UID 0) 실행 | 컨테이너 탈출 시 호스트에서도 root 권한 |

---

## 공통 구조: 모든 정책의 뼈대

4개 정책은 모두 같은 구조를 따른다. 먼저 이 구조를 이해하면 개별 정책은 `validate` 부분만 다르다.

```yaml
apiVersion: kyverno.io/v1
kind: ClusterPolicy        # 클러스터 전역 정책 (namespace 지정 불필요)
metadata:
  name: <정책-이름>
spec:
  # 기본: 전체 클러스터에서 Audit (기록만)
  validationFailureAction: Audit

  # blog-system만 Enforce (실제 차단)
  validationFailureActionOverrides:
    - action: Enforce
      namespaces:
        - blog-system

  # 기존 리소스도 Background Scan으로 위반 여부 기록
  background: true

  rules:
    - name: <규칙-이름>
      match:
        any:
        - resources:
            kinds: [Pod]       # Pod 생성 시 검사

      exclude:
        any:
        - resources:
            namespaces:         # 시스템 namespace 제외
              - kube-system
              - istio-system
              - monitoring
              - security
              - argocd
              - kyverno

      validate:
        message: "위반 시 표시될 에러 메시지"
        pattern:
          # ... 각 정책별 검증 패턴
```

**공통 설계 결정**:

1. **Audit + Enforce override**: 시스템 namespace는 기록만, `blog-system`만 차단
2. **시스템 namespace 제외**: Falco(privileged 필요), Cilium(root 필요), Prometheus(latest 사용하는 sidecar) 등 — 우리가 제어할 수 없는 공식 이미지
3. **background: true**: 기존 리소스의 잠복 위반을 PolicyReport로 미리 발견

---

## 정책 1: disallow-latest-tag — 이미지 태그 강제

### 왜 필요한가

```bash
# 이 두 명령어가 완전히 다른 버전을 배포할 수 있다
kubectl run test --image=nginx         # 태그 없음 → latest 자동
kubectl run test --image=nginx:latest  # 명시적 latest
```

`latest`는 **변하는 태그**이다. 어제의 `nginx:latest`와 오늘의 `nginx:latest`가 다른 이미지일 수 있다. 장애가 발생했을 때 "어떤 버전을 배포했는지" 추적이 불가능하다.

### 구현: 2개의 규칙으로 이중 차단

이 정책은 규칙이 **2개**이다. 각각 다른 케이스를 막는다.

```yaml
# 규칙 1: 태그 자체가 없는 경우 차단
# nginx (태그 없음) → 차단
# nginx:1.25 → 통과
- name: require-image-tag
  validate:
    message: "latest 태그 사용 금지: 명시적 버전 태그를 사용하세요 (예: ghcr.io/wlals2/board-was:v29)"
    pattern:
      spec:
        containers:
          - image: "*:*"       # "이미지:태그" 형식 강제
        =(initContainers):     # initContainer도 검사 (있는 경우에만)
          - image: "*:*"
```

```yaml
# 규칙 2: latest 태그를 명시적으로 쓴 경우 차단
# nginx:latest → 차단
# nginx:1.25 → 통과
- name: disallow-latest
  validate:
    message: "latest 태그 사용 금지: 명시적 버전 태그를 사용하세요"
    pattern:
      spec:
        containers:
          - image: "!*:latest"  # latest가 아닌 것만 허용
        =(initContainers):
          - image: "!*:latest"
```

**왜 2개로 나눴는가**: `"!*:latest"` 패턴은 `nginx` (태그 없음)을 잡지 못한다. 콜론이 없기 때문이다. 따라서 `"*:*"` (태그 존재 여부)와 `"!*:latest"` (latest 금지)를 분리했다.

**`=(initContainers)`의 의미**: 앞의 `=` 기호는 Kyverno의 "있으면 검사, 없으면 무시" 문법이다. 모든 Pod에 initContainer가 있는 것은 아니므로, initContainer가 없는 Pod에서 검증 실패가 나지 않도록 한다.

### 우리 환경에서의 적용

```
WAS 이미지: ghcr.io/<OWNER>/board-was:v29    ← CI/CD가 자동으로 버전 태그 부여 ✅
WEB 이미지: ghcr.io/<OWNER>/blog-web:v15     ← CI/CD가 자동으로 버전 태그 부여 ✅
MySQL:      mysql:8.0                        ← 명시적 버전 ✅
```

CI/CD 파이프라인에서는 이미 `v{run_number}` 태그를 사용한다. 이 정책은 **kubectl로 직접 배포할 때**의 안전망이다.

---

## 정책 2: require-resource-limits — CPU/Memory 제한 강제

### 왜 필요한가

```yaml
# limits 없이 배포하면?
containers:
  - name: my-app
    image: my-app:v1
    # resources: 없음 → 이 Pod이 노드의 CPU/Memory를 모두 점유 가능
```

limits가 없는 Pod 하나가 노드의 전체 자원을 소비하면, 같은 노드의 다른 Pod들이 자원 부족으로 OOMKilled되거나 CPU Throttling을 겪는다. 이것을 **noisy neighbor 문제**라고 한다.

### 구현

```yaml
- name: require-limits
  validate:
    message: "CPU/Memory limits를 반드시 설정하세요 (resources.limits.cpu, resources.limits.memory)"
    pattern:
      spec:
        containers:
          - resources:
              limits:
                cpu: "?*"      # 어떤 값이든 존재해야 함
                memory: "?*"   # 어떤 값이든 존재해야 함
```

**`"?*"` 패턴의 의미**: `?`는 1글자 이상, `*`는 0글자 이상. 합치면 "빈 문자열이 아닌 어떤 값이든"이다. `100m`이든 `1Gi`이든 값만 있으면 통과한다.

### 예외: MySQL

```yaml
exclude:
  any:
  - resources:
      namespaces:
        - blog-system
      selector:
        matchLabels:
          app: mysql
```

MySQL은 공식 이미지에 기본 limits 설정이 없고, 데이터베이스 워크로드 특성상 limits 설정이 까다롭다 (JVM처럼 미리 메모리를 크게 잡아야 하는 경우). 별도 관리 대상으로 예외 처리했다.

### 우리 환경 적용 상태

```yaml
# WAS (Spring Boot) — 실제 적용 중인 값
resources:
  requests:
    cpu: 200m
    memory: 512Mi
  limits:
    cpu: 1000m
    memory: 1Gi

# WEB (Nginx) — 실제 적용 중인 값
resources:
  requests:
    cpu: 50m
    memory: 64Mi
  limits:
    cpu: 200m
    memory: 256Mi
```

---

## 정책 3: disallow-privileged — 특권 컨테이너 차단

### 왜 필요한가

```yaml
# privileged: true가 의미하는 것
securityContext:
  privileged: true   # 이 컨테이너는 호스트 커널에 직접 접근 가능
```

privileged 컨테이너는 **호스트와 동일한 권한**을 가진다. 커널 모듈 로드, 디바이스 접근, 네트워크 스택 조작이 모두 가능하다. 공격자가 이 컨테이너를 장악하면 컨테이너를 벗어나지 않아도 이미 호스트 권한을 가진 것과 같다.

### 구현

```yaml
- name: disallow-privileged-containers
  validate:
    message: "privileged 컨테이너 금지: securityContext.privileged: true 제거하세요"
    pattern:
      spec:
        containers:
          - =(securityContext):
              =(privileged): "false | null"
        =(initContainers):
          - =(securityContext):
              =(privileged): "false | null"
```

**`=(securityContext)` + `=(privileged)`의 의미**:
- `=(필드)`: "이 필드가 있으면 검사, 없으면 통과"
- securityContext 자체가 없으면? → privileged도 없으므로 안전 (기본값이 false)
- securityContext가 있는데 privileged: true면? → 차단

**`"false | null"` 패턴**: false이거나 명시되지 않은(null) 경우만 허용. true만 차단한다.

### 정당한 privileged 사용 — 시스템 namespace 제외

```yaml
exclude:
  any:
  - resources:
      namespaces:
        - kube-system     # Cilium Agent: eBPF 프로그램 로드
        - security        # Falco: eBPF syscall 감시
        - monitoring      # node-exporter: 호스트 메트릭 수집
```

이 시스템 컴포넌트들은 정당한 이유로 privileged가 필요하다:
- **Falco**: eBPF 프로그램을 커널에 로드해서 syscall을 감시 (IDS)
- **Cilium**: eBPF 기반 네트워크 정책 적용 (CNI)
- **node-exporter**: 호스트 파일시스템 읽기 (메트릭 수집)

---

## 정책 4: require-non-root — root 실행 차단

### 왜 필요한가

Linux에서 root(UID 0)는 모든 권한을 가진다. 컨테이너가 root로 실행되면, **Container Escape**(컨테이너 탈출) 취약점 발생 시 호스트에서도 root 권한을 획득할 수 있다.

```
컨테이너 내부 (root, UID 0)
  ↓ Container Escape 취약점
호스트 (root, UID 0) → 노드 전체 장악
```

non-root(예: UID 65534)로 실행하면:

```
컨테이너 내부 (nobody, UID 65534)
  ↓ Container Escape 취약점
호스트 (nobody, UID 65534) → 일반 유저 권한만 → 피해 제한적
```

### 구현

```yaml
- name: require-non-root-pod
  validate:
    message: "root(UID 0) 실행 금지: securityContext.runAsNonRoot: true 또는 runAsUser를 0이 아닌 값으로 설정하세요"
    anyPattern:
      # Pod 레벨에서 설정하거나
      - spec:
          securityContext:
            runAsNonRoot: true
      # Container 레벨에서 설정
      - spec:
          containers:
            - securityContext:
                runAsNonRoot: true
```

**`anyPattern`의 의미**: `pattern`은 모든 조건을 만족해야 하지만, `anyPattern`은 **하나라도 만족하면 통과**이다. runAsNonRoot를 Pod 레벨에서 설정하든, Container 레벨에서 설정하든 어느 쪽이든 허용한다.

### 예외 1: MySQL (공식 이미지가 root로 실행)

```yaml
- resources:
    namespaces:
      - blog-system
    selector:
      matchLabels:
        app: mysql
```

MySQL 8.0 공식 이미지는 내부적으로 root로 시작한 후 `mysql` 유저(UID 999)로 전환한다. `runAsNonRoot: true`를 설정하면 **시작 자체가 실패**한다. non-root MySQL 이미지를 커스텀 빌드하는 것은 범위 밖이므로 예외 처리했다.

### 예외 2: WEB nginx (80포트 바인딩에 root 필요)

```yaml
- resources:
    namespaces:
      - blog-system
    selector:
      matchLabels:
        app: web
```

Linux에서 1024 미만 포트(well-known ports)는 root만 바인딩할 수 있다. nginx는 80번 포트를 사용하므로 master process가 root로 시작해야 한다 (worker process는 non-root).

**근본 해결 방법**: `nginxinc/nginx-unprivileged` 이미지로 전환하면 8080 등 비특권 포트를 사용하여 완전한 non-root 실행이 가능하다. 이 경우 이 예외를 제거할 수 있다.

### 잠복 문제: mysql-exporter

이 정책이 배포될 때, mysql-exporter Pod는 이미 실행 중이었다. securityContext가 완전히 누락되어 있었지만, Kyverno는 **기존 Pod을 종료하지 않는다** (Background Scan은 기록만).

이 잠복 위반이 노드 장애 후 Pod 재생성 시 폭발한다. 이 사건의 전말은 [Part 3 Incident Report](/study/2026-03-02-kyverno-incident-report-cascading-failure/)에서 다룬다.

---

## ArgoCD 배포 구성

### 엔진과 정책을 분리하는 이유

Kyverno는 2개의 ArgoCD Application으로 분리해서 배포한다:

```
argocd/
├── kyverno-app.yaml           # Wave 0: Kyverno 엔진 (Helm)
└── kyverno-policies-app.yaml  # Wave 1: 4개 ClusterPolicy (Plain YAML)
```

**왜 분리하는가**:
1. **배포 순서**: Kyverno 엔진이 먼저 떠야 ClusterPolicy CRD를 인식할 수 있다
2. **독립 관리**: 정책만 수정할 때 엔진 재배포 없이 가능
3. **장애 격리**: 정책 YAML 오류가 엔진에 영향을 주지 않음

### sync-wave로 순서 보장

```yaml
# kyverno-app.yaml — Wave 0 (기본값, 가장 먼저 배포)
metadata:
  name: kyverno

# kyverno-policies-app.yaml — Wave 1 (엔진 뜬 후 배포)
metadata:
  name: kyverno-policies
  annotations:
    argocd.argoproj.io/sync-wave: "1"
```

ArgoCD의 `sync-wave`는 숫자가 작은 것부터 배포한다. Wave 0(엔진)이 완전히 Ready된 후 Wave 1(정책)이 배포된다.

### ServerSideApply — CRD 크기 제한 우회

```yaml
# kyverno-app.yaml
syncOptions:
  - ServerSideApply=true
```

Kyverno의 CRD는 매우 크다 (262KB 이상). Kubernetes는 기본적으로 `kubectl.kubernetes.io/last-applied-configuration` annotation에 전체 매니페스트를 저장하는데, 이 annotation의 크기 제한이 262,144 bytes이다.

`ServerSideApply`는 이 annotation을 사용하지 않으므로 크기 제한을 우회한다.

### 파일 구조 정리

```
k8s-manifests/
├── apps/kyverno/
│   ├── Chart.yaml          # Kyverno Helm Chart 3.7.1 dependency
│   └── values.yaml         # replicas: 1, forceFailurePolicyIgnore: true
├── configs/kyverno-policies/
│   ├── kustomization.yaml  # 4개 정책 파일 목록
│   ├── disallow-latest-tag.yaml
│   ├── require-resource-limits.yaml
│   ├── disallow-privileged.yaml
│   └── require-non-root.yaml
└── argocd/
    ├── kyverno-app.yaml           # Wave 0
    └── kyverno-policies-app.yaml  # Wave 1
```

---

## 성과

| 항목 | Before | After |
|------|--------|-------|
| kubectl 직접 배포 시 보안 검사 | 0개 (무방비) | 4개 정책 자동 검증 |
| 정책 위반 시 동작 | 알 수 없음 (사후 발견) | 즉시 차단 + 에러 메시지 |
| 정책 관리 방식 | 없음 | GitOps (YAML → Git → ArgoCD) |
| 커버 범위 (blog-system) | CI/CD 경로만 | CI/CD + kubectl + 모든 배포 경로 |

---

## 다음 글에서는

[Part 3](/study/2026-03-02-kyverno-incident-report-cascading-failure/)에서는 이 정책들이 실전에서 어떻게 **연쇄 장애**를 일으켰는지를 사고 보고서(Incident Report) 형식으로 다룬다:

- 3일 전 배포된 정책이 조용히 잠복하다가
- 노드 장애 → Istio Webhook 실패 → Exponential Backoff
- 노드 복구 후 Kyverno가 mysql-exporter를 차단
- 해결: securityContext 추가 + rollout restart로 Backoff 우회

---

*이 글은 [Kyverno 실전 시리즈](/series/kyverno-실전-시리즈/)의 일부입니다.*
- [Part 1: Kyverno 개념 + Kubernetes Admission Controller 원리](/study/2026-03-02-kyverno-admission-controller-concept/)
- **Part 2 (현재 글)**: 4개 ClusterPolicy 구현기
- [Part 3: Incident Report — 정책 변경과 네트워크 장애가 빚어낸 연쇄 배포 실패](/study/2026-03-02-kyverno-incident-report-cascading-failure/)
