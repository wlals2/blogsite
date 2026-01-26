---
title: "Canary 배포 전략 3가지 비교: 왜 ALB Traffic Routing을 선택했나"
date: 2025-12-29T11:00:00+09:00
tags: ["Canary", "Argo Rollouts", "ALB", "Traffic Routing", "Kubernetes"]
categories: ["study", "Cloud & Terraform"]
description: "Argo Rollouts로 Canary 배포를 구현할 때 Replica Shifting, ALB Traffic Routing, Replica Scaling 3가지 전략을 비교하고, 왜 ALB Traffic Routing을 선택했는지 실전 경험을 공유합니다."
---

## 들어가며

Argo Rollouts로 Canary 배포를 구현하면서 고민이 하나 생겼어요.

"replicas가 2개인데 25% Canary를 어떻게 하지?"

```yaml
spec:
  replicas: 2
  strategy:
    canary:
      steps:
        - setWeight: 25  # ← 이게 의미가 있나?
```

Pod가 2개면 0%, 50%, 100%밖에 안 되는데, 25%나 10% 같은 세밀한 비율은 어떻게 구현할 수 있을까요?

이 글에서는 3가지 전략을 비교하고, 왜 **ALB Traffic Routing**을 선택했는지 공유하려고 합니다.

---

## 문제 상황

### 현재 구성

```yaml
spec:
  replicas: 2
  strategy:
    canary:
      maxSurge: 1
      maxUnavailable: 0
      steps:
        - setWeight: 25
        - pause: {duration: 10s}
        - setWeight: 50
        - pause: {duration: 10s}
```

### 문제점

- replicas가 2개일 때 25%, 50% step이 **의미 없음**
- 실제로는 둘 다 50:50 분배됨 (1 old pod : 1 new pod)
- 점진적인 트래픽 전환 불가능

---

## 전략 1: Replica Shifting (기본 방식)

### 원리

Pod 개수 비율로 트래픽 분배하는 방식이에요.

```
Canary 25% 배포 시도:
┌─────────────────────────────────────┐
│ Old Pods: 2개                       │
│ New Pods: maxSurge로 1개 추가       │
│ = 총 3개 Pod                        │
│                                     │
│ setWeight: 25 설정                  │
│ → New Pod 1개 / 총 3개 = 33.3%     │
│ (의도한 25%가 아님!)                │
└─────────────────────────────────────┘

실제 동작:
Old: ██████████████████ (66.7%)
New: █████████ (33.3%)
```

### 왜 25%가 불가능한가?

| replicas | maxSurge | Total Pods | 가능한 비율 |
|----------|----------|------------|------------|
| 2 | 1 | 3 | 0%, 33%, 66%, 100% |
| 3 | 1 | 4 | 0%, 25%, 50%, 75%, 100% |
| 4 | 2 | 6 | 0%, 16%, 33%, 50%, 66%, 83%, 100% |
| 10 | 5 | 15 | 6.6% 단위로 조절 가능 |

**결론:** replicas가 작으면 세밀한 비율 조정 불가능

처음에 "그럼 replicas를 10개로 늘리면 되지 않나?"라고 생각했는데, 그건 너무 비효율적이었어요.

---

### 장점

✅ **단순성:**
- 추가 설정 불필요 (기본 Argo Rollouts 기능)
- Service 분리 불필요
- Ingress 수정 불필요

✅ **리소스 효율:**
- 필요한 만큼만 Pod 생성 (최소 3개)
- 트래픽 없으면 불필요한 Pod 없음

✅ **자연스러운 롤백:**
- Pod 삭제만으로 즉시 이전 버전으로 복귀

### 단점

❌ **비율 제한:**
- **2 replicas → 33% 단위만 가능** (10%, 25% 불가)
- 10% Canary 테스트 불가능

❌ **리소스 예측 어려움:**
- maxSurge만큼 추가 Pod 생성 → 갑작스런 리소스 사용
- Karpenter가 즉시 노드 추가 못하면 Pending

❌ **롤백 시 순간 중단:**
- Old Pod 삭제 → New Pod 생성 과정에서 순간적으로 가용 Pod 감소

### 적합한 경우

- replicas가 10개 이상 (세밀한 비율 조정 가능)
- 리소스가 충분하여 maxSurge 여유 있음
- 단순한 배포 흐름 선호

**우리 프로젝트:** replicas=2라서 부적합 ❌

---

## 전략 2: ALB Traffic Routing (선택!)

### 원리

ALB Target Group을 분리하고 가중치로 트래픽을 분배하는 방식이에요.

```
Service 분리:
┌─────────────────────────────────────┐
│ was-stable Service                  │
│ → ALB Target Group A (가중치: 90)  │
│ → Old Pods (2개)                    │
└─────────────────────────────────────┘

┌─────────────────────────────────────┐
│ was-canary Service                  │
│ → ALB Target Group B (가중치: 10)  │
│ → New Pods (2개)                    │
└─────────────────────────────────────┘

ALB가 가중치로 트래픽 분배:
90% → Target Group A (Old)
10% → Target Group B (New)
```

### 동작 흐름

```
1. 초기 상태:
   was-stable (2 old pods) ← 100% 트래픽

2. Canary 배포 시작 (setWeight: 10):
   Rollout이 2개 new pod 생성
   ALB Action 수정: stable 90%, canary 10%

3. 점진적 증가 (setWeight: 25):
   Pod는 그대로 (2 old, 2 new)
   ALB Action만 수정: stable 75%, canary 25%

4. 완전 전환 (setWeight: 100):
   ALB Action: canary 100%
   Old pods 삭제
```

**핵심:** Pod 개수는 그대로 두고, **ALB 가중치만** 조정해요.

### 왜 Service를 분리해야 하는가?

처음엔 "Service 하나로 안 되나?"라고 생각했는데, ALB는 **Target Group 단위로만** 가중치 설정이 가능해요:

```
✅ 가능:
Ingress → ALB Action (Weighted Target Groups)
           ├─ TG A (90%) ← Service A → Old Pods
           └─ TG B (10%) ← Service B → New Pods

❌ 불가능:
Ingress → ALB → Single TG ← Single Service → Mixed Pods
(ALB는 TG 내부 Pod 개수로만 분배, 가중치 불가)
```

---

### 구현 방법

#### 1. Service 2개 생성

```yaml
# was-service-stable.yaml
apiVersion: v1
kind: Service
metadata:
  name: was-stable
  namespace: petclinic
spec:
  selector:
    app: petclinic
    tier: was
  ports:
    - port: 8080
      targetPort: 8080
```

```yaml
# was-service-canary.yaml
apiVersion: v1
kind: Service
metadata:
  name: was-canary
  namespace: petclinic
spec:
  selector:
    app: petclinic
    tier: was
  ports:
    - port: 8080
      targetPort: 8080
```

#### 2. Rollout 수정

```yaml
# was-rollout.yaml
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

  strategy:
    canary:
      canaryService: was-canary
      stableService: was-stable

      trafficRouting:
        alb:
          ingress: petclinic-was-ingress  # Ingress 이름
          servicePort: 8080

      steps:
        - setWeight: 10
        - pause: {duration: 30s}

        - setWeight: 25
        - pause: {duration: 30s}

        - setWeight: 50
        - pause: {duration: 30s}

        - setWeight: 75
        - pause: {duration: 30s}
```

#### 3. Ingress (자동 생성)

AWS Load Balancer Controller가 자동으로 annotation을 추가해요:

```yaml
# ingress-was.yaml (Terraform 관리)
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: petclinic-was-ingress
  annotations:
    # AWS Load Balancer Controller가 자동으로 action 추가
    alb.ingress.kubernetes.io/actions.was-action: |
      {
        "Type": "forward",
        "ForwardConfig": {
          "TargetGroups": [
            {"ServiceName": "was-stable", "ServicePort": "8080", "Weight": 90},
            {"ServiceName": "was-canary", "ServicePort": "8080", "Weight": 10}
          ]
        }
      }
spec:
  rules:
    - http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: was-action  # Action 이름
                port:
                  name: use-annotation
```

---

### 장점

✅ **정확한 비율 제어:**
- **10%, 25%, 50%, 75% 모두 가능** (Pod 개수 무관)
- ALB가 요청 레벨에서 분배 (정확도 높음)

✅ **Pod 개수 일정:**
- Stable 2개, Canary 2개로 고정
- Karpenter 노드 계획 예측 가능

✅ **빠른 롤백:**
- ALB 가중치만 변경 → 즉시 적용 (Pod 재배포 불필요)
- 1-2초 내에 이전 버전으로 복귀

✅ **프로덕션 표준:**
- Netflix, Spotify 등 대형 서비스 사용
- AWS 공식 권장 방식

### 단점

❌ **초기 설정 복잡:**
- Service 2개 생성 필요
- Rollout에 trafficRouting 설정 추가
- Ingress 수정 (Terraform 관리 시 주의)

❌ **리소스 사용 증가:**
- Canary 시작 시 즉시 2개 Pod 추가 (10% 트래픽이어도)
- Stable 2 + Canary 2 = 총 4개 Pod 동시 실행

❌ **AWS Load Balancer Controller 의존:**
- 컨트롤러 버전 호환성 확인 필요
- Ingress annotation 문법 정확히 작성

### 적합한 경우

- **프로덕션 환경** (정확한 비율 제어 필수)
- replicas가 작아도 세밀한 Canary 테스트 필요
- 빠른 롤백이 중요한 경우
- **우리 프로젝트** ⭐ (replicas=2, 10% Canary 필요)

---

## 전략 3: Replica Scaling

### 원리

트래픽 비율에 맞춰 replicas를 동적으로 증가시키는 방식이에요.

```
10% Canary 배포:
┌─────────────────────────────────────┐
│ Old Pods: 9개 (90%)                 │
│ New Pods: 1개 (10%)                 │
│ = 총 10개 Pod                       │
└─────────────────────────────────────┘

25% Canary 배포:
┌─────────────────────────────────────┐
│ Old Pods: 3개 (75%)                 │
│ New Pods: 1개 (25%)                 │
│ = 총 4개 Pod                        │
└─────────────────────────────────────┘
```

### 동작 흐름

```
1. 초기: replicas=2 (평소)

2. Canary 10% 시작:
   replicas를 10으로 증가
   Old: 9개, New: 1개 (10%)

3. Canary 25%:
   replicas를 4로 감소
   Old: 3개, New: 1개 (25%)

4. 완전 전환:
   replicas를 다시 2로 감소
   New: 2개
```

---

### 장점

✅ **정확한 비율:**
- 수학적으로 정확한 Pod 비율
- Service 분리 불필요

✅ **단순한 설정:**
- Rollout에 replicas만 조정
- Ingress 수정 불필요

### 단점

❌ **리소스 낭비 심각:**
- 10% Canary를 위해 평소 2개 → 10개로 증가!
- CPU/Memory 비용 5배 증가 (짧은 시간이어도)

❌ **노드 부족 위험:**
- Karpenter가 즉시 노드 추가 못하면 Pending
- Spot 중단 시 더 큰 영향

❌ **HPA 충돌:**
- HPA가 replicas를 조정하면 Canary 비율 깨짐
- HPA 비활성화 필요 (운영 리스크)

❌ **롤백 시간 증가:**
- Pod 10개 → 2개로 감소하는 시간 필요
- 즉시 복귀 불가능

### 적합한 경우

- 리소스가 무한정 (비용 무관)
- 짧은 시간만 Canary (5분 이하)
- HPA 사용 안 함

**현실적으로 거의 사용 안 함** ❌

---

## 전략 비교표

| 항목 | Replica Shifting | ALB Traffic Routing ⭐ | Replica Scaling |
|------|------------------|------------------------|-----------------|
| **10% Canary** | ❌ 불가능 (33%만 가능) | ✅ 정확히 10% | ✅ 정확 (Pod 10개 필요) |
| **Pod 개수** | 3개 (2+1) | 4개 (2+2) | 10개! (9+1) |
| **리소스 사용** | ⭐ 적음 | 보통 | ❌ 매우 많음 |
| **롤백 속도** | 빠름 (Pod 삭제) | ⭐ 매우 빠름 (가중치만) | 느림 (Pod 감소) |
| **설정 복잡도** | ⭐ 단순 | 보통 (Service 분리) | 단순 |
| **HPA 호환** | ✅ | ✅ | ❌ 충돌 |
| **프로덕션 사용** | 중소규모 | ⭐ 대규모 표준 | 거의 없음 |
| **우리 프로젝트 적합도** | ❌ (비율 부족) | ⭐ 최적 | ❌ (리소스 낭비) |

---

## 최종 선택: ALB Traffic Routing

### 선택 이유

1. **정확한 10% Canary 가능:**
   - replicas=2 환경에서도 10%, 25%, 50%, 75% 모두 구현
   - "조금씩 테스트하고 싶다"는 목표 달성

2. **빠른 롤백:**
   - ALB 가중치만 변경 → 1-2초 내 즉시 적용
   - Pod 재배포 불필요
   - 프로덕션에서 문제 발생 시 신속 대응 가능

3. **HPA 호환:**
   - HPA로 트래픽에 따라 replicas 조정 가능
   - Canary 비율은 유지됨
   - 운영 유연성 확보

4. **프로덕션 표준:**
   - Netflix, Spotify 사용
   - AWS 공식 권장
   - 검증된 방식

5. **리소스 예측 가능:**
   - Stable 2 + Canary 2 = 고정 4개
   - Karpenter 노드 계획 용이
   - 비용 예측 가능

---

## 트레이드오프

### 포기하는 것

❌ **Replica Shifting의 단순성:**
- 하지만 초기 설정만 복잡하고, 이후 운영은 더 쉬움

❌ **최소 리소스 사용:**
- Canary 2개 Pod가 추가로 필요 (CPU 500m, Memory 1Gi)
- 하지만 Replica Scaling(10개)보다는 훨씬 적음

### 얻는 것

✅ **정확한 비율 제어 (10% Canary):**
- "신버전을 조금만 테스트하고 싶다"는 요구 충족

✅ **빠른 롤백 (1-2초):**
- 프로덕션에서 문제 발생 시 신속 대응

✅ **프로덕션 안정성:**
- 검증된 방식으로 리스크 최소화

---

## 구현 로드맵

### Phase 1: Service 분리 (30분)

```bash
cd ~/CICD/manifestrepo/was

# 1. Service Stable 생성
cat > service-stable.yaml <<EOF
apiVersion: v1
kind: Service
metadata:
  name: was-stable
  namespace: petclinic
spec:
  selector:
    app: petclinic
    tier: was
  ports:
    - port: 8080
      targetPort: 8080
EOF

# 2. Service Canary 생성
cat > service-canary.yaml <<EOF
apiVersion: v1
kind: Service
metadata:
  name: was-canary
  namespace: petclinic
spec:
  selector:
    app: petclinic
    tier: was
  ports:
    - port: 8080
      targetPort: 8080
EOF

git add service-stable.yaml service-canary.yaml
git commit -m "Add stable/canary services for ALB Traffic Routing"
git push
```

### Phase 2: Rollout 수정 (30분)

```bash
# Rollout에 trafficRouting 추가
vi rollout.yaml
```

**수정 내용:**
```yaml
spec:
  strategy:
    canary:
      canaryService: was-canary
      stableService: was-stable

      trafficRouting:
        alb:
          ingress: petclinic-was-ingress
          servicePort: 8080

      steps:
        - setWeight: 10
        - pause: {duration: 30s}
        - setWeight: 25
        - pause: {duration: 30s}
        - setWeight: 50
        - pause: {duration: 30s}
        - setWeight: 75
        - pause: {duration: 30s}
```

### Phase 3: 배포 테스트 (30분)

```bash
# 1. 새 이미지 배포 (Jenkinsfile-was 실행)
# Jenkins에서 수동 빌드 트리거

# 2. Rollout 상태 모니터링
kubectl argo rollouts get rollout was -n petclinic --watch

# 3. ALB Target Group 확인
TG_ARN=$(kubectl get ingress petclinic-was-ingress -n petclinic -o jsonpath='{.status.loadBalancer.ingress[0].hostname}')
aws elbv2 describe-target-groups --query "TargetGroups[?contains(TargetGroupName, 'was')].TargetGroupArn" --output text

# 4. 가중치 확인
aws elbv2 describe-rules --listener-arn $LISTENER_ARN | jq '.Rules[] | select(.Actions[].ForwardConfig)'
```

---

## 배운 점

### "복잡함"의 기준은 상대적이다

처음엔 "Service 2개 만들고, Rollout 수정하고... 너무 복잡한 거 아냐?"라고 생각했어요.

하지만 막상 구현하고 나니 **초기 설정만 복잡**하고, 이후 운영은 오히려 더 간단했어요. 가중치만 조정하면 되니까요.

### 빠른 롤백이 가장 중요하다

프로덕션에서 가장 무서운 건 "문제가 생겼는데 복구가 느린 것"이에요.

ALB Traffic Routing은 1-2초 안에 롤백할 수 있어요. 이게 가장 큰 가치였습니다.

### 리소스 효율 vs 운영 안정성

Replica Shifting이 리소스는 가장 적게 쓰지만, 세밀한 비율 제어가 안 돼요.

Replica Scaling은 정확한 비율이 나오지만, 리소스를 너무 많이 써요.

ALB Traffic Routing은 중간이에요. 조금 더 쓰지만 **안정성과 유연성**을 얻을 수 있었어요.

---

## 마무리

Canary 배포 전략 3가지를 비교하고, **ALB Traffic Routing**을 선택했습니다.

| 전략 | 핵심 | 선택 이유 |
|------|------|----------|
| **Replica Shifting** | Pod 개수 비율 | ❌ replicas=2라 10% 불가 |
| **ALB Traffic Routing** | ALB 가중치 | ⭐ 정확한 비율 + 빠른 롤백 |
| **Replica Scaling** | replicas 동적 증가 | ❌ 리소스 낭비 심각 |

초기 설정은 조금 복잡하지만, 정확한 비율 제어와 빠른 롤백 능력을 얻을 수 있어서 만족스러워요.

특히 프로덕션 환경에서는 **안정성과 신속한 대응**이 가장 중요하니까, ALB Traffic Routing이 최선의 선택이었습니다.

다음에 Canary 배포를 구현한다면 고민 없이 ALB Traffic Routing을 선택할 것 같아요!
