---
title: "Argo Rollouts로 Canary 배포 마스터하기"
date: 2025-12-29
tags: ["Argo Rollouts", "Kubernetes", "Canary Deployment", "CI/CD", "GitOps"]
categories: ["CI/CD & GitOps"]
description: "Argo Rollouts를 사용한 Canary 배포의 모든 것 - 개념부터 실전까지, 트러블슈팅 과정을 포함한 완벽 가이드"
---

## 들어가며

Canary 배포라는 용어를 처음 들었을 때, "왜 카나리(새)라는 이름을 붙였을까?"라고 궁금했어요. 알고 보니 옛날 광부들이 탄광에서 유독가스를 감지하기 위해 카나리를 사용했던 것에서 유래했더라고요.

카나리가 죽으면 가스가 있다는 신호, 그래서 광부들이 대피하는 거죠. Canary 배포도 마찬가지예요. 소수의 사용자에게 먼저 새 버전을 배포하고, 문제가 생기면 전체 배포를 중단하는 겁니다.

이 글에서는 Argo Rollouts를 사용해서 Kubernetes에서 Canary 배포를 구현한 경험을 공유하려고 해요. 개념 설명부터 실제 트러블슈팅까지, 제가 겪었던 모든 과정을 담았습니다.

---

## 전체 아키텍처 개요

### 전체 흐름 (사용자 → Pod)

```
사용자
  ↓
Route53 (DNS: www.goupang.shop)
  ↓
AWS Application Load Balancer (ALB)
  ↓
Kubernetes Ingress (petclinic-ingress)
  ↓
┌─────────────────────────────────────┐
│   Argo Rollouts Traffic Routing     │
│                                      │
│   10% → was-canary Service           │
│   90% → was-stable Service           │
└─────────────────────────────────────┘
  ↓                      ↓
was-canary Pods      was-stable Pods
(새 버전)             (현재 버전)
  ↓                      ↓
Spring Boot Container (Tomcat)
  ↓
RDS MySQL
```

처음에는 이 구조가 복잡해 보였는데, 각 레이어의 역할을 이해하니 완벽한 설계라는 걸 깨달았어요.

### 레이어별 역할

| 레이어 | 컴포넌트 | 역할 | 관리 주체 |
|--------|----------|------|----------|
| **L1 (DNS)** | Route53 | 도메인 → ALB IP 변환 | Terraform |
| **L2 (Load Balancer)** | ALB | HTTPS 종료, 트래픽 분산 | Terraform (Ingress로 자동 생성) |
| **L3 (Ingress)** | petclinic-ingress | ALB 규칙 정의 (/petclinic → was-stable) | Terraform |
| **L4 (Traffic Routing)** | Argo Rollouts | Canary 트래픽 분산 (10%/50%/90%) | manifestrepo (Git) |
| **L5 (Service)** | was-stable, was-canary | Pod 그룹 라벨링 | Argo Rollouts 자동 생성 |
| **L6 (Pod)** | WAS Pods | Spring Boot 앱 실행 | Argo Rollouts |

**핵심 포인트**:
- **Ingress**는 기본 라우팅 규칙만 정의
- **Argo Rollouts**가 Canary 배포 시 ALB 규칙을 동적으로 수정
- **Traffic Routing**: 10% → 50% → 90% → 100% 점진적 전환

---

## 핵심 개념 설명

### Rollout

**무엇인가?**
- Kubernetes의 **Deployment를 대체**하는 Argo Rollouts의 커스텀 리소스 (CRD)
- 일반 Deployment는 단순 롤링 업데이트만 지원
- Rollout은 **Canary, Blue/Green** 등 고급 배포 전략 지원

**왜 필요한가?**

처음에는 "Deployment도 롤링 업데이트 되는데 왜 Rollout이 필요해?"라고 생각했어요. 하지만 실제로 사용해보니 차이가 명확하더라고요.

```yaml
# 일반 Deployment
apiVersion: apps/v1
kind: Deployment
spec:
  replicas: 10
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 1
      maxSurge: 1

# 결과: 한 번에 1개씩 교체 (10% 단위)
# 문제: 트래픽 비율 제어 불가 (무조건 Pod 개수 비율)
```

```yaml
# Argo Rollout
apiVersion: argoproj.io/v1alpha1
kind: Rollout
spec:
  replicas: 10
  strategy:
    canary:
      steps:
        - setWeight: 10  # 정확히 10% 트래픽
        - pause: {duration: 30s}

# 결과: ALB 가중치로 정확히 10% 제어
# 장점: Pod 개수와 무관하게 트래픽 비율 조절
```

---

### Stable vs Canary

#### Stable (안정 버전)

**무엇인가?**
- **현재 운영 중인 버전** (프로덕션)
- 대부분의 트래픽이 이 버전으로 라우팅됨

**예시:**
- Image: `eks-3tier-dev-was:v42-8f47ce2`
- ReplicaSet: `was-7448899d88` (revision 2)
- Service: `was-stable` (port 8080)

#### Canary (카나리 버전)

**무엇인가?**
- **새로 배포되는 버전** (테스트 중)
- 일부 트래픽만 받아서 안정성 검증
- 문제 발견 시 즉시 롤백 가능

**이름 유래가 인상적이었어요:**

> 옛날 광부들이 탄광에서 유독가스 감지용으로 카나리(새)를 사용했어요. 카나리가 죽으면 가스 위험, 광부들이 대피하는 거죠. Canary 배포도 마찬가지로, 소수 사용자가 먼저 새 버전을 체험하고 문제를 발견하는 역할을 해요.

---

### Revision (리비전)

**무엇인가?**
- Pod Template의 **스냅샷 버전 번호**
- Pod Template이 변경되면 Revision이 증가
- 각 Revision마다 별도의 ReplicaSet 생성

**예시:**
```
revision:1 (was-5c75465fcf) → 최초 배포
revision:2 (was-7448899d88) → Pod template 변경 (현재 Stable)
revision:3 (was-84d9997bdb) → Pod template 변경 (이전 배포 실패)
revision:4 (was-c744458bf)  → Pod template 변경 (abort됨)
revision:5 (was-79c49d7cbb) → Pod template 변경 (현재 Canary)
```

**Pod Template 변경 예시:**
- 이미지 태그 변경: `v42 → v43`
- 환경변수 추가
- 리소스 제한 변경 (CPU/Memory)
- **annotation 변경** (제가 사용한 방법!)

**왜 Annotation 변경으로 새 배포가 시작되나?**

처음에는 이해가 안 됐어요. "이미지도 안 바꿨는데 왜 배포가 시작되지?"

```yaml
template:
  metadata:
    annotations:
      rollout.argoproj.io/revision: "test-auto-progress-v2"  # 변경!
```

알고 보니 Annotation도 Pod Template의 일부라서, Revision이 증가하더라고요. Argo Rollouts가 "새 버전이 배포되어야 한다"고 인식하는 거죠.

---

## Canary 배포 동작 원리

### Canary 배포 단계별 흐름

#### Phase 1: 초기 상태 (배포 전)

```
┌────────────────────────────────────────┐
│          Stable (revision 2)           │
│                                         │
│  ReplicaSet: was-7448899d88            │
│  Replicas: 2                           │
│  Image: v42-8f47ce2                    │
│                                         │
│  Pods:                                 │
│    - was-7448899d88-dg9mz (Running)   │
│    - was-7448899d88-dz6ld (Running)   │
└────────────────────────────────────────┘

Traffic: 100% → was-stable Service → Stable Pods
```

---

#### Phase 2: Canary 시작 (Step 0/6)

**무슨 일이 일어나나?**
1. Pod Template 변경 감지 (annotation 변경)
2. 새 Revision 생성 (revision 5)
3. 새 ReplicaSet 생성 (`was-79c49d7cbb`)
4. Canary Pod 1개 생성 시작

```
┌────────────────────────────────────────┐
│          Stable (revision 2)           │
│  ReplicaSet: was-7448899d88            │
│  Replicas: 2                           │
│                                         │
│  Pods:                                 │
│    - was-7448899d88-dg9mz (Running)   │
│    - was-7448899d88-dz6ld (Running)   │
└────────────────────────────────────────┘
              ↓
┌────────────────────────────────────────┐
│          Canary (revision 5)           │
│  ReplicaSet: was-79c49d7cbb            │
│  Replicas: 1 (스케일 업 중)             │
│  Image: v42-8f47ce2                    │
│                                         │
│  Pods:                                 │
│    - was-79c49d7cbb-mhgtj (Starting)  │
└────────────────────────────────────────┘

Traffic: 100% → was-stable (Canary Pod 아직 미준비)
```

---

#### Phase 3: Canary Ready → Step 1 (10% 트래픽)

이 순간이 진짜 Canary 배포의 시작이에요!

**무슨 일이 일어나나?**
1. Canary Pod가 Ready 상태 도달 (StartupProbe + ReadinessProbe 통과)
2. Argo Rollouts가 ALB 규칙 수정:
   - `was-stable`: 90% 가중치
   - `was-canary`: 10% 가중치
3. 30초 대기 (`pause: {duration: 30s}`)

```
┌────────────────────────────────────────┐
│          Stable (revision 2)           │
│  Pods: 2개 (Running, Ready)            │
│  Traffic: 90%                          │
└────────────────────────────────────────┘
              ↓ 90% traffic
┌────────────────────────────────────────┐
│               ALB                       │
│  /petclinic → Forward:                 │
│    - was-stable: 90%                   │
│    - was-canary: 10%                   │
└────────────────────────────────────────┘
              ↓ 10% traffic
┌────────────────────────────────────────┐
│          Canary (revision 5)           │
│  Pods: 1개 (Running, Ready)            │
│  Traffic: 10%                          │
└────────────────────────────────────────┘

30초 모니터링:
- Canary Pod에서 에러 발생 여부 체크
- 에러율, 응답 시간 등 메트릭 확인
- 정상이면 다음 Step으로 자동 진행
```

처음 이 부분을 구현했을 때, ALB 가중치가 실시간으로 변경되는 걸 보고 감탄했어요. Kubernetes의 유연함을 제대로 느꼈죠.

---

#### Phase 4-5: Step 2 (50% 트래픽) → Step 3 (90% 트래픽)

트래픽 비율을 점진적으로 늘려가요. 각 단계마다 충분한 모니터링 시간을 두고요.

```
Step 2 (50%):
  - was-stable: 50%
  - was-canary: 50%
  - 2분 대기 (더 긴 모니터링 시간)

Step 3 (90%):
  - was-stable: 10%
  - was-canary: 90%
  - 30초 최종 확인
```

---

#### Phase 6: 완료 (100% Canary → 새로운 Stable)

**무슨 일이 일어나나?**
1. 30초 대기 완료 → 자동 완료
2. Canary를 새로운 Stable로 승격:
   - `was-79c49d7cbb` (revision 5) → Stable
3. 이전 Stable Pod 삭제:
   - `was-7448899d88` (revision 2) → Terminated
4. ALB 규칙 정리:
   - `was-stable`: 100% (revision 5 Pod들로 변경)

```
┌────────────────────────────────────────┐
│      새로운 Stable (revision 5)         │
│  ReplicaSet: was-79c49d7cbb            │
│  Replicas: 2                           │
│  Image: v42-8f47ce2                    │
│                                         │
│  Pods:                                 │
│    - was-79c49d7cbb-mhgtj (Running)   │
│    - was-79c49d7cbb-xxxxx (Running)   │
└────────────────────────────────────────┘

Traffic: 100% → was-stable Service → 새로운 Stable Pods

이전 Stable (revision 2):
  ReplicaSet: was-7448899d88 (ScaledDown, replicas: 0)
  보관 이유: 롤백 가능 (revisionHistoryLimit: 3)
```

이전 ReplicaSet을 완전히 삭제하지 않고 보관하는 게 인상적이었어요. 문제가 생기면 즉시 롤백할 수 있거든요.

---

## 트러블슈팅 과정 복기

실제로 구현하면서 겪었던 문제들을 공유해볼게요.

### 문제 1: Rollout Suspended (Paused)

#### 증상

```bash
kubectl argo rollouts get rollout was -n petclinic
# Status: ॥ Paused
# Message: CanaryPauseStep
# Step: 1/6
```

ArgoCD에서는 "Out of Sync"라고 표시되고, Rollout은 "Suspended"라고 나왔어요. 뭔가 잘못됐다는 건 알겠는데, 무엇이 문제인지 몰랐죠.

#### 원인 분석

Git 이력을 확인해보니 문제가 보였어요.

```yaml
steps:
  - setWeight: 10
  - pause: {}  # 무한 대기!
```

`pause: {}`는 **무한 대기 (manual approval)**를 의미했어요. 사람이 수동으로 승인해야 다음 단계로 넘어가는 거죠.

**왜 이런 문제가?**
- ArgoCD가 Git과 sync 완료했지만 Rollout이 사람 승인 대기 중
- ArgoCD는 "내 할 일은 다 했는데, Rollout이 왜 안 끝나지?" → Out of Sync 표시

#### 해결 방법

```yaml
# rollout.yaml 수정
steps:
  - setWeight: 10
  - pause: {duration: 30s}  # 자동 진행으로 변경
```

Git push 후 새 배포를 시작하니 자동으로 진행됐어요.

---

### 문제 2: 배포 전략 변경이 적용 안 됨

#### 증상

```bash
# Git push 후
git push
# 성공

# 하지만 Rollout은 여전히 Paused
kubectl argo rollouts get rollout was -n petclinic
# Status: Paused (변화 없음)
```

"Git에 올렸는데 왜 적용이 안 되지?"

#### 원인 분석

**Argo Rollouts 동작 원리:**
- 배포가 **시작될 때** rollout.yaml의 strategy를 읽음
- **실행 중인** 배포는 이미 읽은 전략으로 계속 진행
- Git 변경사항은 **다음 배포**부터 적용

**비유:**
```
배포 = 기차 여행

시작 전: 여행 계획서 확인 (rollout.yaml)
여행 중: 계획서대로 진행 (pause: {} → 역에서 대기)
여행 중간에 계획서 수정: 이번 여행은 영향 없음
다음 여행: 새 계획서 적용
```

이 비유가 딱 맞더라고요.

#### 해결 방법

```bash
# 1. 현재 배포 abort
kubectl argo rollouts abort was -n petclinic

# 2. Stable 상태로 복귀 대기

# 3. 새 배포 트리거 (annotation 변경)
# rollout.yaml 수정:
#   annotations:
#     rollout.argoproj.io/revision: "test-auto-progress"

git add was/rollout.yaml
git commit -m "Trigger new deployment with auto-progress"
git push

# ArgoCD 자동 sync → 새 배포 시작
```

---

### 문제 3: Pod Pending - Insufficient Resources

#### 증상

```bash
kubectl get pods -n petclinic -l tier=was
# was-xxx-xxx            0/1     Pending   0          2m
# was-xxx-xxx            0/1     Pending   0          2m
```

Pod가 생성되지 않고 Pending 상태로 멈췄어요.

```bash
kubectl describe pod was-xxx-xxx -n petclinic
# Events:
#   Warning  FailedScheduling  0/5 nodes are available:
#            3 Insufficient cpu, 5 Insufficient memory.
```

#### 원인 분석

**현재 설정:**
```yaml
spec:
  replicas: 4  # (kubectl patch로 4로 변경됨)

  containers:
    - resources:
        requests:
          cpu: 500m
          memory: 1Gi
```

**Canary 배포 시 필요 리소스 계산:**

| 단계 | Stable | Canary | Total Pods | CPU 필요 | Memory 필요 |
|------|--------|--------|------------|----------|-------------|
| 초기 | 4 | 0 | 4 | 2 vCPU | 4Gi |
| Step 1 | 4 | 2 | 6 | 3 vCPU | 6Gi |
| Step 2 | 4 | 4 | **8** | **4 vCPU** | **8Gi** ← 문제! |

**Node 리소스:**
```bash
kubectl get nodes
# 5 nodes × t3.medium (2 vCPU, 4GB each)

# Total: ~3.3 vCPU, ~6.2Gi available
```

필요한 리소스(4 vCPU, 8Gi)보다 가용 리소스(3.3 vCPU, 6.2Gi)가 적었어요!

#### 해결 방법

```yaml
# rollout.yaml 수정
spec:
  replicas: 4 → 2
```

```
replicas: 2 → Canary 배포 시 최대 4 pods
4 pods × (500m + 1Gi) = 2 vCPU + 4Gi
가용 리소스: 3.3 vCPU + 6.2Gi
✅ 충분!
```

테스트 환경에서는 replicas를 줄이는 게 합리적이었어요. 운영 환경에서는 Node를 증설해야겠지만요.

---

## 배운 점

### 1. Canary 배포는 "안전장치"다

처음에는 "왜 이렇게 복잡하게 배포해?"라고 생각했어요. 하지만 실제로 사용해보니, **위험을 최소화하는 완벽한 전략**이더라고요.

10% → 50% → 90%로 점진적으로 늘리면서, 각 단계마다 메트릭을 확인하고, 문제가 생기면 즉시 롤백할 수 있으니까요.

### 2. pause 설정의 중요성

```yaml
# 수동 승인
steps:
  - setWeight: 10
  - pause: {}  # 무한 대기 → 사람이 promote 필요

# 자동 진행
steps:
  - setWeight: 10
  - pause: {duration: 30s}  # 30초 후 자동 진행
```

운영 환경에서는 자동 + Prometheus 메트릭 기반 자동 롤백이 최선이에요. 사람이 24시간 모니터링할 수는 없으니까요.

### 3. 리소스 계획의 중요성

Canary 배포 시 **최대 2배의 Pod**가 필요하다는 걸 깨달았어요.

```
replicas: 4
→ Canary 배포 시 최대 8 pods (Stable 4 + Canary 4)
→ 2배의 리소스 필요!
```

Node 증설이나 리소스 제한 조정을 미리 계획해야 해요.

### 4. Argo Rollouts의 강력함

단순히 "Deployment의 대체"가 아니라, **프로덕션급 배포 전략의 완성판**이더라고요.

- ALB 가중치 자동 제어
- 자동 롤백 지원
- 메트릭 기반 판단 (Analysis)
- Git으로 모든 것 관리 (GitOps)

---

## 마치며

Canary 배포를 처음 구현할 때는 "정말 필요한가?"라는 의문이 들었어요. 하지만 실제로 운영하면서, 이게 **서비스 안정성의 핵심**이라는 걸 깨달았습니다.

특히 Argo Rollouts는 Kubernetes 네이티브하게 Canary 배포를 구현할 수 있어서 정말 좋았어요. ALB 가중치 제어부터 자동 롤백까지, 모든 게 선언적으로 관리되니까요.

여러분도 프로덕션 환경을 운영한다면, Canary 배포를 꼭 도입해보시길 추천드립니다. 처음엔 복잡해 보이지만, 한 번 구축해두면 서비스 안정성이 크게 향상될 거예요.

**핵심 정리:**
- Canary 배포는 **점진적 위험 분산** 전략
- Argo Rollouts는 **Deployment의 상위 호환**
- pause 설정은 **자동 진행**이 운영에 유리
- 리소스는 **2배**를 준비하자

마지막으로, 카나리(새)가 광부들의 생명을 구했듯이, Canary 배포가 여러분의 서비스를 지켜줄 거예요!
