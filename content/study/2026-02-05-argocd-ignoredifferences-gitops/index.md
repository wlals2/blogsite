---
title: "ArgoCD ignoreDifferences로 GitOps 동적 필드 관리하기"
date: 2026-02-05T20:00:00+09:00
draft: false
categories:
  - study
  - Kubernetes
tags:
  - argocd
  - gitops
  - kubernetes
  - argo-rollouts
  - istio
  - troubleshooting
---

## 문제 발견

ArgoCD로 GitOps를 운영하다 보면 피할 수 없는 상황이 있다. Git에 정의한 리소스와 클러스터의 실제 상태가 계속 달라지는 경우다.

```
ArgoCD Application: OutOfSync
Resource: DestinationRule/blog-system/web-dest-rule

Git (Desired State):          Cluster (Live State):
  subsets:                       subsets:
    - name: stable                 - name: stable
    - name: canary                   labels:
                                       rollouts-pod-template-hash: 5fcf974d78
                                   - name: canary
                                     labels:
                                       rollouts-pod-template-hash: 5fcf974d78
```

`selfHeal: true`로 설정했는데도 OutOfSync 상태가 지속된다. ArgoCD가 Git 상태로 되돌리면, 잠시 후 다시 클러스터 상태가 변경된다.

**원인**: Argo Rollouts가 Canary 배포를 위해 런타임에 label을 자동으로 추가하고 있었다.

---

## 근본 원인 분석

### Argo Rollouts의 동적 필드 관리

Argo Rollouts는 Canary 배포를 위해 DestinationRule의 subset labels를 동적으로 관리한다.

**배포 과정**:
```yaml
# 1. 새 버전 Rollout 시작
apiVersion: argoproj.io/v1alpha1
kind: Rollout
spec:
  strategy:
    canary:
      canaryService: web-canary
      stableService: web-stable
      trafficRouting:
        istio:
          destinationRule:
            name: web-dest-rule
            canarySubsetName: canary
            stableSubsetName: stable
```

**Argo Rollouts가 하는 일**:
```yaml
# Step 1: ReplicaSet 생성 시 고유 hash 생성
metadata:
  labels:
    rollouts-pod-template-hash: 5fcf974d78  # 배포마다 변경됨

# Step 2: DestinationRule에 자동으로 label 추가
spec:
  subsets:
  - name: stable
    labels:
      rollouts-pod-template-hash: 5fcf974d78  # 런타임에 추가!
  - name: canary
    labels:
      rollouts-pod-template-hash: 789abc123   # 새 버전 hash
```

**왜 이렇게 하는가?**
- Istio DestinationRule의 subset은 label selector로 Pod를 구분
- Canary 배포 시 stable/canary Pod를 명확히 분리해야 함
- 배포마다 hash가 변경되므로 Git에 고정값으로 넣을 수 없음

---

### Kubernetes의 기본값 자동 추가

Sealed Secrets Controller에서도 유사한 문제가 발생했다.

```yaml
# Git에 작성한 YAML
env:
  - name: GOMAXPROCS
    valueFrom:
      resourceFieldRef:
        resource: limits.cpu

# Kubernetes API가 저장한 실제 상태
env:
  - name: GOMAXPROCS
    valueFrom:
      resourceFieldRef:
        divisor: '0'  # ← 자동 추가됨!
        resource: limits.cpu
```

**Kubernetes가 기본값을 추가하는 이유**:
- API validation 단계에서 명시되지 않은 필드에 기본값 설정
- `resourceFieldRef.divisor`의 기본값은 `'0'` (나누지 않음)
- 클러스터 내부 일관성 유지

---

## ignoreDifferences 개념

ArgoCD의 `ignoreDifferences`는 특정 필드의 차이를 무시하도록 지시하는 설정이다.

### 기본 구조

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
spec:
  ignoreDifferences:
  - group: networking.istio.io      # API Group
    kind: DestinationRule             # 리소스 타입
    name: web-dest-rule               # 특정 리소스 이름 (선택 사항)
    namespace: blog-system            # Namespace (선택 사항)
    jsonPointers:                     # 무시할 필드 (JSONPath)
    - /spec/subsets/0/labels
    - /spec/subsets/1/labels
```

### JSONPointer 문법

**JSONPointer**는 JSON 문서 내 특정 위치를 가리키는 RFC 6901 표준이다.

```yaml
# YAML 구조
spec:
  subsets:
    - name: stable      # index 0
      labels:
        key: value
    - name: canary      # index 1
      labels:
        key: value

# JSONPointer 표현
/spec/subsets/0/labels  → spec.subsets[0].labels
/spec/subsets/1/labels  → spec.subsets[1].labels
```

**규칙**:
- `/` 로 시작
- 객체 필드는 `/field_name`
- 배열 인덱스는 `/0`, `/1` 등
- 중첩된 경로는 `/parent/child/grandchild`

---

## 실제 적용 사례

### Case 1: Argo Rollouts + Istio DestinationRule

**문제**: Rollout이 DestinationRule subset labels를 동적으로 변경

**해결**:
```yaml
# argocd/blog-system-app.yaml
spec:
  ignoreDifferences:
  - group: networking.istio.io
    kind: DestinationRule
    name: web-dest-rule
    jsonPointers:
    - /spec/subsets/0/labels
    - /spec/subsets/1/labels
  - group: networking.istio.io
    kind: DestinationRule
    name: was-dest-rule
    jsonPointers:
    - /spec/subsets/0/labels
    - /spec/subsets/1/labels
```

**효과**:
- ArgoCD가 subset labels 차이는 무시
- Argo Rollouts가 자유롭게 labels 관리
- 다른 필드 변경은 여전히 감지됨

---

### Case 2: Kubernetes 기본값 (divisor)

**문제**: Kubernetes가 `resourceFieldRef.divisor`를 자동으로 `'0'` 추가

**해결**:
```yaml
# argocd/sealed-secrets-app.yaml
spec:
  ignoreDifferences:
  - group: apps
    kind: Deployment
    name: sealed-secrets-controller
    jsonPointers:
    - /spec/template/spec/containers/0/env/0/valueFrom/resourceFieldRef/divisor
    - /spec/template/spec/containers/0/env/1/valueFrom/resourceFieldRef/divisor
```

**JSONPointer 경로 분석**:
```yaml
spec:
  template:
    spec:
      containers:
        - name: controller      # index 0
          env:
            - name: GOMAXPROCS  # index 0
              valueFrom:
                resourceFieldRef:
                  divisor: '0'  # ← 이 필드 무시
            - name: GOMEMLIMIT  # index 1
              valueFrom:
                resourceFieldRef:
                  divisor: '0'  # ← 이 필드 무시
```

---

## 트러블슈팅: kubectl apply가 필요한 이유

Git에 ignoreDifferences를 추가하고 push했는데도 OutOfSync가 계속되었다.

### 문제 발견

```bash
# Git에는 ignoreDifferences 있음
cat argocd/blog-system-app.yaml | grep -A 10 "ignoreDifferences"
# → 출력됨 ✅

# 클러스터의 Application CRD에는 없음
kubectl get application blog-system -n argocd -o yaml | grep "ignoreDifferences"
# → 출력 없음 ❌
```

**원인**: ArgoCD Application manifest 자체는 GitOps로 관리되지 않음!

---

### ArgoCD의 2계층 구조

```
┌─────────────────────────────────────────┐
│ 계층 1: Application CRD 자체            │
│   (argocd/blog-system-app.yaml)         │
│   → GitOps 자동 배포 ❌                 │
│   → kubectl apply 수동 필요 ⚠️          │
└─────────────────────────────────────────┘
              ↓ 관리
┌─────────────────────────────────────────┐
│ 계층 2: Application이 관리하는 리소스   │
│   (services/blog-system/*.yaml)         │
│   → GitOps 자동 배포 ✅                 │
└─────────────────────────────────────────┘
```

**ArgoCD Application manifest는**:
- Git에 push해도 클러스터에 자동 적용 안 됨
- **수동으로 kubectl apply 필요**
- "App of Apps" 패턴을 사용하면 GitOps 가능 (향후 개선)

---

### 해결 과정

```bash
# 1. Git 변경사항 push (완료)
git add argocd/blog-system-app.yaml argocd/sealed-secrets-app.yaml
git commit -m "fix: Add ignoreDifferences"
git push origin main

# 2. 클러스터에 수동 적용 (필수!)
cd /home/jimin/k8s-manifests
kubectl apply -f argocd/blog-system-app.yaml
kubectl apply -f argocd/sealed-secrets-app.yaml

# 3. 확인
kubectl get application -n argocd
# NAME             SYNC STATUS   HEALTH STATUS
# blog-system      Synced        Progressing
# sealed-secrets   Synced        Healthy
```

**kubectl apply가 하는 일**:
```
1. YAML 파일 읽기
2. Kubernetes API에 PATCH 요청
3. Application CRD의 spec.ignoreDifferences 업데이트
4. ArgoCD Controller가 변경 감지
5. Git vs Live 재비교 (ignoreDifferences 적용됨)
6. → Synced! ✅
```

---

## ignoreDifferences 사용 시나리오

### 언제 사용하는가?

**리소스가 런타임에 자동으로 변경되는 경우**:

| 시나리오 | 필드 | 변경 주체 | ignoreDifferences |
|---------|------|----------|-------------------|
| **Argo Rollouts** | subset labels | Argo Rollouts | `/spec/subsets/*/labels` |
| **HPA** | replicas | HPA Controller | `/spec/replicas` |
| **PVC** | volumeName | Kubernetes | `/spec/volumeName` |
| **Service** | clusterIP | Kubernetes | `/spec/clusterIP` |
| **Kubernetes 기본값** | divisor | Kubernetes API | `/spec/.../divisor` |

### 사용 시 주의사항

**❌ 남용 금지**:
```yaml
# 나쁜 예: 모든 차이 무시
ignoreDifferences:
- kind: Deployment
  jsonPointers:
  - /spec  # 전체 spec 무시 → 위험!
```

**✅ 최소 범위 지정**:
```yaml
# 좋은 예: 특정 필드만 무시
ignoreDifferences:
- kind: Deployment
  name: my-app  # 특정 리소스만
  jsonPointers:
  - /spec/replicas  # 특정 필드만
```

**원칙**:
1. **최소 권한 원칙**: 필요한 필드만 무시
2. **명시적 이름 지정**: `name` 필드로 대상 리소스 한정
3. **문서화**: 왜 무시하는지 주석으로 설명

---

## 대안: Git에서 필드 제거

ignoreDifferences 대신 Git YAML에서 해당 필드를 아예 제거하는 방법도 있다.

### DestinationRule 예시

**Before** (ignoreDifferences 사용):
```yaml
# Git: web-destinationrule.yaml
spec:
  subsets:
  - name: stable
    labels:  # 빈 객체
  - name: canary
    labels:  # 빈 객체

# ArgoCD Application
ignoreDifferences:
- jsonPointers:
  - /spec/subsets/0/labels
  - /spec/subsets/1/labels
```

**After** (필드 제거):
```yaml
# Git: web-destinationrule.yaml
spec:
  subsets:
  - name: stable  # labels 키 자체 제거
  - name: canary

# ArgoCD Application
# ignoreDifferences 불필요
```

**장점**:
- ArgoCD 설정 간소화
- Git은 구조만 정의, 런타임 값은 외부 컨트롤러가 관리
- Argo Rollouts 공식 문서 권장 방식

**언제 사용하는가?**:
- 해당 필드를 완전히 외부 컨트롤러가 관리할 때
- Git에 기본값도 넣을 필요 없을 때

**언제 사용하지 않는가?**:
- Kubernetes 기본값처럼 필드 자체는 필수인데 값만 달라질 때
- Git에 초기값을 명시하고 싶을 때

---

## 검증 및 확인

### ArgoCD Application 상태 확인

```bash
# 1. Sync 상태 확인
kubectl get application -n argocd
NAME             SYNC STATUS   HEALTH STATUS
blog-system      Synced        Healthy
sealed-secrets   Synced        Healthy

# 2. ignoreDifferences 적용 확인
kubectl get application blog-system -n argocd -o yaml \
  | grep -A 20 "ignoreDifferences"

# 3. 특정 리소스 diff 확인
kubectl get application blog-system -n argocd -o yaml \
  | grep -A 10 "comparisonResult"
```

### 실제 리소스 비교

```bash
# 1. Git 상태 확인
cat services/blog-system/web/web-destinationrule.yaml

# 2. Live 상태 확인
kubectl get destinationrule web-dest-rule -n blog-system -o yaml

# 3. diff 비교
diff <(cat web-destinationrule.yaml) \
     <(kubectl get destinationrule web-dest-rule -n blog-system -o yaml)
```

---

## Best Practices

### 1. 문서화 및 주석

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: blog-system
spec:
  # Argo Rollouts가 자동으로 관리하는 필드 무시
  # - rollouts-pod-template-hash는 배포마다 변경됨
  # - Git에 고정값으로 넣을 수 없음
  # - Rollouts가 Canary 배포를 위해 런타임에 추가
  ignoreDifferences:
  - group: networking.istio.io
    kind: DestinationRule
    name: web-dest-rule
    jsonPointers:
    - /spec/subsets/0/labels  # stable subset
    - /spec/subsets/1/labels  # canary subset
```

### 2. 정기적 검토

```bash
# ArgoCD Application의 ignoreDifferences 목록 확인
kubectl get applications -n argocd -o json \
  | jq -r '.items[] | select(.spec.ignoreDifferences != null) |
    {name: .metadata.name, ignoreDifferences: .spec.ignoreDifferences}'
```

**검토 사항**:
- 아직 필요한 설정인가?
- 더 좋은 해결 방법은 없는가? (필드 제거, 컨트롤러 설정 변경 등)
- 범위를 더 좁힐 수 있는가?

### 3. Git History 활용

```bash
# Application manifest 변경 이력 확인
cd /home/jimin/k8s-manifests
git log --oneline --all -- argocd/blog-system-app.yaml

# 특정 커밋의 ignoreDifferences 확인
git show <commit-hash>:argocd/blog-system-app.yaml | grep -A 20 "ignoreDifferences"
```

---

## 참고 자료

- [ArgoCD - Diffing Customization](https://argo-cd.readthedocs.io/en/stable/user-guide/diffing/)
- [JSONPointer RFC 6901](https://datatracker.ietf.org/doc/html/rfc6901)
- [Argo Rollouts - Istio Integration](https://argoproj.github.io/argo-rollouts/features/traffic-management/istio/)
- [Kubernetes API Conventions - Default Values](https://github.com/kubernetes/community/blob/master/contributors/devel/sig-architecture/api-conventions.md#defaulting)

---

## 결론

ArgoCD의 `ignoreDifferences`는 GitOps와 런타임 동적 관리의 충돌을 해결하는 강력한 도구다.

**핵심 교훈**:

1. **런타임 변경 필드 이해**: Argo Rollouts, HPA 등이 동적으로 관리하는 필드 파악
2. **최소 권한 원칙**: 필요한 필드만 무시, 전체 리소스 무시 금지
3. **Application manifest는 수동 적용**: kubectl apply 필요 (App of Apps 패턴 전까지)
4. **대안 검토**: ignoreDifferences vs 필드 제거 중 선택
5. **문서화**: 왜 무시하는지 주석으로 명확히 기록

GitOps의 이상과 현실의 간극을 이해하고, 적절한 타협점을 찾는 것이 성공적인 운영의 핵심이다.
