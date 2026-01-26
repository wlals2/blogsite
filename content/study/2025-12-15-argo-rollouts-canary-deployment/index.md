---
title: "Argo Rollouts로 안전한 Canary 배포 구현하기"
date: 2025-12-15T10:00:00+09:00
tags: ["Argo Rollouts", "Canary", "Kubernetes", "GitOps", "Blue-Green"]
categories: ["study", "Kubernetes"]
description: "Kubernetes Deployment의 한계를 극복하고 Argo Rollouts로 단계별 트래픽 제어와 자동 롤백이 가능한 Canary 배포를 구현한 경험을 공유합니다."
---

## 들어가며

처음에는 Kubernetes의 기본 Rolling Update로 배포했어요. 그런데 문제가 생겼을 때 "아, 이미 늦었구나"라는 생각이 들더라고요. 새 버전이 50% 배포되고 나서야 에러를 발견하는 경우가 많았거든요.

"조금씩 배포하면서 지켜볼 수 있으면 좋을 텐데"라는 생각으로 Argo Rollouts를 도입했습니다. 이 글에서는 Argo Rollouts로 어떻게 안전한 Canary 배포를 구현했는지 공유하려고 합니다.

---

## 기존 Deployment의 한계

Kubernetes 기본 Rolling Update는 이런 방식이에요:

```
Kubernetes 기본 Rolling Update:
┌────────────────────────────────────────┐
│  Old Pod  →  Old Pod  →  New Pod       │
│  Old Pod  →  New Pod  →  New Pod       │
│                                        │
│  문제점:                                │
│  - 트래픽 제어 불가 (모든 Pod 동등 취급)  │
│  - 단계별 검증 불가                      │
│  - 빠른 롤백 어려움                      │
└────────────────────────────────────────┘
```

새 Pod와 Old Pod가 섞여 있어도 Kubernetes는 둘을 똑같이 취급해요. 새 버전이 문제가 있어도 트래픽의 50%가 이미 그쪽으로 가고 있을 수 있다는 거죠.

---

## Argo Rollouts의 장점

Argo Rollouts는 이렇게 달라요:

```
Argo Rollouts Canary 배포:
┌────────────────────────────────────────┐
│  트래픽 20% → 신버전 (테스트)           │
│  트래픽 50% → 신버전 (관찰)             │
│  트래픽 80% → 신버전 (최종 확인)        │
│  트래픽 100% → 신버전 (완료)            │
│                                        │
│  장점:                                  │
│  - 단계별 트래픽 제어                   │
│  - 문제 시 자동/수동 롤백               │
│  - 각 단계에서 메트릭 분석 가능         │
└────────────────────────────────────────┘
```

20%만 신버전으로 보내고 지켜보다가, 문제 없으면 50%, 80%, 100% 이렇게 점진적으로 늘려가는 거예요. 중간에 문제가 생기면 바로 롤백할 수 있고요.

---

## 설치 방법

### Argo Rollouts Controller 설치

먼저 컨트롤러를 설치했어요:

```bash
# 1. 네임스페이스 생성
kubectl create namespace argo-rollouts

# 2. Argo Rollouts 컨트롤러 설치
kubectl apply -n argo-rollouts -f https://github.com/argoproj/argo-rollouts/releases/latest/download/install.yaml

# 3. 설치 확인
kubectl get pods -n argo-rollouts
```

예상 결과:
```
NAME                             READY   STATUS    RESTARTS   AGE
argo-rollouts-xxxxxxxxx-xxxxx    1/1     Running   0          1m
```

### kubectl 플러그인 설치 (권장)

상태 확인할 때 엄청 편해요:

```bash
# Linux/Mac
curl -LO https://github.com/argoproj/argo-rollouts/releases/latest/download/kubectl-argo-rollouts-linux-amd64

# 실행 권한 부여 및 설치
chmod +x kubectl-argo-rollouts-linux-amd64
sudo mv kubectl-argo-rollouts-linux-amd64 /usr/local/bin/kubectl-argo-rollouts

# 설치 확인
kubectl argo rollouts version
```

---

## 기본 개념

### Rollout vs Deployment

| 항목 | Kubernetes Deployment | Argo Rollouts |
|------|----------------------|---------------|
| API 버전 | `apps/v1` | `argoproj.io/v1alpha1` |
| Kind | `Deployment` | `Rollout` |
| 배포 전략 | RollingUpdate, Recreate | **Canary**, **Blue-Green** |
| 트래픽 제어 | 불가 | **가능** |
| 단계별 배포 | 불가 | **가능** |
| 자동 롤백 | 제한적 | **완벽 지원** |

Deployment를 Rollout으로 바꾸면 Canary 배포가 가능해져요.

### 핵심 용어

처음 봤을 때 헷갈렸던 용어들을 정리해볼게요:

| 용어 | 설명 | 예시 |
|------|------|------|
| **stable** | 현재 안정적으로 운영 중인 버전 (기존 버전) | v1 이미지 |
| **canary** | 새로 배포 중인 버전 (신버전) | v2 이미지 |
| **revision** | 배포 이력 번호 | revision:1, revision:2 |
| **step** | Canary 배포 단계 | Step 1/6, Step 2/6 |
| **weight** | 트래픽 비율 | 20%, 50%, 80%, 100% |
| **pause** | 배포 일시정지 (수동/자동) | 1분 대기 |
| **promote** | 다음 단계로 진행 | kubectl argo rollouts promote |
| **abort** | 배포 중단 및 롤백 | kubectl argo rollouts abort |

---

## Rollout 상태 읽는 법

### 기본 명령어

```bash
kubectl argo rollouts get rollout was -n petclinic
```

### 출력 결과 해석

처음 봤을 때 무슨 뜻인지 몰랐는데, 이렇게 읽으면 돼요:

```
Name:            was                        ← Rollout 이름
Namespace:       petclinic                  ← 네임스페이스
Status:          ✔ Healthy                  ← 현재 상태 (정상)
Strategy:        Canary                     ← 배포 전략
  Step:          6/6                        ← 현재 단계 / 전체 단계
  SetWeight:     100                        ← 설정된 트래픽 비율
  ActualWeight:  100                        ← 실제 트래픽 비율
Images:          ...was:latest (stable)     ← 이미지와 역할
Replicas:
  Desired:       3                          ← 원하는 Pod 수
  Current:       3                          ← 현재 Pod 수
  Updated:       3                          ← 업데이트된 Pod 수
  Ready:         3                          ← 준비된 Pod 수
  Available:     3                          ← 사용 가능한 Pod 수
```

### 트리 구조 해석

배포 진행 중일 때는 이렇게 보여요:

```
NAME                             KIND        STATUS         AGE    INFO
⟳ was                            Rollout     ✔ Healthy      30m
├──# revision:4                                                    ← 최신 리비전
│  └──⧉ was-66799f85fb           ReplicaSet  ✔ Healthy      23m    stable ← 안정 버전
│     ├──□ was-66799f85fb-jp6cq  Pod         ✔ Running      23m    ready:1/1
│     ├──□ was-66799f85fb-qxvdx  Pod         ✔ Running      17m    ready:1/1
│     └──□ was-66799f85fb-gn6xj  Pod         ✔ Running      4m     ready:1/1
└──# revision:3                                                    ← 이전 리비전
   └──⧉ was-645f5b6448           ReplicaSet  • ScaledDown   30m    ← 축소됨
```

**아이콘 설명:**
- ⟳ = Rollout 리소스
- # = Revision (배포 버전)
- ⧉ = ReplicaSet
- □ = Pod
- ✔ = 정상
- • = 비활성 (ScaledDown)

---

## Canary 배포 전략 구현

### Rollout YAML 예시

실제로 사용한 설정이에요:

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Rollout
metadata:
  name: was
  namespace: petclinic
spec:
  replicas: 2
  selector:
    matchLabels:
      app: petclinic
      tier: was
  template:
    # ... Pod 템플릿 (Deployment와 동일)
  strategy:
    canary:
      steps:
        # Step 1: 20% 트래픽을 신버전으로
        - setWeight: 20
        # Step 2: 1분 대기 (관찰 시간)
        - pause: {duration: 1m}
        # Step 3: 50% 트래픽을 신버전으로
        - setWeight: 50
        # Step 4: 1분 대기
        - pause: {duration: 1m}
        # Step 5: 80% 트래픽을 신버전으로
        - setWeight: 80
        # Step 6: 30초 대기
        - pause: {duration: 30s}
        # 완료 후 자동으로 100% 전환

      maxSurge: 1         # 추가 Pod 수
      maxUnavailable: 0   # 최소 가용 Pod 보장
```

### 배포 단계 시각화

이렇게 진행돼요:

```
시간 ───────────────────────────────────────────────────►

      ┌─────┐  ┌─────┐  ┌─────┐  ┌─────┐  ┌─────┐
      │ 20% │  │ 50% │  │ 80% │  │100% │  │완료 │
      │     │  │     │  │     │  │     │  │     │
      └─────┘  └─────┘  └─────┘  └─────┘  └─────┘
         │        │        │        │        │
      Step 1  Step 3  Step 5  Step 6   Healthy
         │        │        │        │
      1분대기  1분대기  30초대기  자동완료
      (Step2)  (Step4)  (Step6)
```

각 단계마다 대기하면서 로그를 보고, 메트릭을 확인하고, 문제가 없으면 다음 단계로 넘어가는 거예요.

---

## 주요 명령어

### 상태 확인

```bash
# Rollout 상태 확인 (기본)
kubectl argo rollouts get rollout was -n petclinic

# 실시간 모니터링 (-w 옵션)
kubectl argo rollouts get rollout was -n petclinic -w

# 간단한 상태 확인
kubectl argo rollouts status was -n petclinic

# 모든 Rollout 목록
kubectl get rollouts -n petclinic
```

### 배포 제어

```bash
# 이미지 업데이트 (배포 시작)
kubectl argo rollouts set image was was=<ECR_URL>:<NEW_TAG> -n petclinic

# 다음 단계로 진행 (pause 상태에서)
kubectl argo rollouts promote was -n petclinic

# 모든 단계 건너뛰고 즉시 완료
kubectl argo rollouts promote was --full -n petclinic

# 배포 중단 및 롤백
kubectl argo rollouts abort was -n petclinic

# 이전 버전으로 롤백
kubectl argo rollouts undo was -n petclinic

# 특정 리비전으로 롤백
kubectl argo rollouts undo was --to-revision=2 -n petclinic
```

### 배포 재시작

```bash
# Rollout 재시작 (Rolling Restart)
kubectl argo rollouts restart was -n petclinic
```

---

## 실제 배포 시나리오

### 시나리오 1: 정상 Canary 배포

새 버전을 조심스럽게 배포하는 과정이에요:

```bash
# 1. 새 이미지로 배포 시작
kubectl argo rollouts set image was \
  was=010068699561.dkr.ecr.ap-northeast-2.amazonaws.com/eks-3tier-dev-was:v2 \
  -n petclinic

# 2. 상태 모니터링
kubectl argo rollouts get rollout was -n petclinic -w
```

**예상 흐름:**
```
[시작]
Status: Progressing
Step: 1/6, SetWeight: 20%
Images: v2 (canary), v1 (stable)

[1분 후]
Status: Progressing
Step: 3/6, SetWeight: 50%

[2분 후]
Status: Progressing
Step: 5/6, SetWeight: 80%

[2분 30초 후]
Status: Healthy
Step: 6/6, SetWeight: 100%
Images: v2 (stable)  ← v2가 stable로 승격!
```

각 단계마다 로그를 확인하고, 에러율을 체크하고, 응답 시간을 모니터링했어요. 문제가 없으면 자동으로 다음 단계로 넘어가고요.

### 시나리오 2: 문제 발견 시 롤백

배포 중간에 문제를 발견했을 때:

```bash
# 1. 배포 진행 중 문제 발견
kubectl argo rollouts get rollout was -n petclinic
# → Status: Paused, Step: 3/6, SetWeight: 50%

# 2. 배포 중단 및 롤백
kubectl argo rollouts abort was -n petclinic

# 3. 상태 확인
kubectl argo rollouts get rollout was -n petclinic
# → Status: Degraded, canary Pod 종료, stable 유지
```

abort 명령 하나로 신버전 Pod가 모두 종료되고, 기존 버전으로 돌아가요. 정말 빠르고 안전해요.

### 시나리오 3: 수동 승인 배포

더 신중하게 배포하고 싶을 때는 수동 승인을 넣었어요:

```yaml
# pause: {} 설정 시 수동 승인 필요
strategy:
  canary:
    steps:
      - setWeight: 20
      - pause: {}        # ← 수동 승인 대기
      - setWeight: 100
```

```bash
# 1. 배포 시작 후 20%에서 대기
kubectl argo rollouts get rollout was -n petclinic
# → Status: Paused, Message: "CanaryPauseStep"

# 2. 테스트 수행... (로그 확인, 메트릭 확인, 실제 사용자 테스트)

# 3. 수동으로 다음 단계 승인
kubectl argo rollouts promote was -n petclinic
```

---

## 트러블슈팅

### 문제 1: Deployment와 Rollout 충돌

처음에 Rollout을 추가했는데 기존 Deployment가 남아있어서 충돌이 났어요.

**증상:** 같은 이름의 Deployment와 Rollout이 동시에 존재

**원인:** ArgoCD가 기존 deployment.yaml을 계속 적용

**해결:**
```bash
# 1. Deployment 삭제
kubectl delete deployment was -n petclinic

# 2. manifestrepo에서 deployment.yaml 삭제/이름변경
mv deployment.yaml deployment.yaml.bak

# 3. rollout.yaml로 대체
```

### 문제 2: HPA가 Deployment 참조

HPA가 Rollout이 아니라 Deployment를 참조하고 있어서 스케일링이 안 됐어요.

**해결:**
```yaml
# hpa.yaml 수정
spec:
  scaleTargetRef:
    apiVersion: argoproj.io/v1alpha1  # ← 변경
    kind: Rollout                      # ← 변경
    name: was
```

### 문제 3: Canary Pod가 Ready 안됨

신버전 Pod가 `ready:0/1` 상태로 계속 멈춰있었어요.

**원인:** readinessProbe 실패

**확인:**
```bash
kubectl describe pod <CANARY_POD_NAME> -n petclinic
kubectl logs <CANARY_POD_NAME> -n petclinic
```

**해결:** readinessProbe의 `initialDelaySeconds` 값 조정

WAS 부팅에 시간이 걸리는데 Probe가 너무 빨리 체크해서 실패했던 거예요. 30초로 늘려주니까 해결됐어요.

---

## 배운 점

### Canary 배포가 정말 필요한가?

처음엔 "우리 서비스는 작은데 Canary까지 필요할까?"라고 생각했어요. 하지만 실제로 사용해보니 **심리적 안정감**이 엄청나더라고요.

배포할 때 "혹시 문제가 생기면 어떡하지?"라는 걱정 없이, "20%만 보내고 지켜보면 되지"라는 마음으로 편하게 배포할 수 있었어요.

### 빠른 롤백이 핵심

Canary 배포의 진짜 장점은 "조금씩 배포"가 아니라 **"빠른 롤백"**이에요. 문제를 빨리 발견하고, 빠르게 대응할 수 있다는 게 가장 큰 가치였습니다.

### 모니터링이 같이 가야 한다

Canary 배포만 하고 모니터링이 없으면 의미가 없어요. 각 단계에서 에러율, 응답 시간, CPU/Memory 사용량을 확인해야 문제를 발견할 수 있거든요.

---

## 마무리

Argo Rollouts로 Canary 배포를 구현하면서 **안전한 배포**가 무엇인지 배웠습니다. 기존 Deployment보다 설정은 조금 복잡하지만, 배포할 때의 안정감과 빠른 롤백 능력은 그 이상의 가치가 있었어요.

특히 프로덕션 환경에서는 정말 필수라고 생각해요. "배포가 무섭다"는 느낌이 사라지니까요.

다음 글에서는 Multi-AZ로 99.99% 가용성을 달성한 경험을 공유할게요!
