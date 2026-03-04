---
title: "ALB Traffic Routing으로 정확한 Canary 배포 구현하기"
date: 2025-12-29
tags: ["AWS", "ALB", "Argo Rollouts", "Canary Deployment", "Traffic Routing", "Kubernetes"]
categories: ["Cloud & Terraform", "Kubernetes"]
description: "Replica Shifting의 한계를 극복하고, ALB Weighted Target Groups로 정확한 트래픽 비율 제어를 구현한 경험을 공유합니다."
---

## 들어가며

Canary 배포를 처음 구현할 때, 가장 큰 좌절감을 느꼈던 순간이 있어요. "10% 트래픽을 Canary로 보내고 싶은데, 실제로는 33%가 가네?"

왜 그럴까요? **Replica Shifting 방식의 한계** 때문이었어요. Pod 개수로만 트래픽을 제어하니, 수학적으로 정확한 비율을 맞출 수가 없었죠.

```yaml
replicas: 2
strategy:
  canary:
    steps:
      - setWeight: 25  # 25%를 원했는데...
```

```
실제 결과:
Old Pods: 2개
New Pods: 1개
→ 1/(2+1) = 33% 🤦
```

이 글에서는 이 문제를 **ALB Traffic Routing**으로 어떻게 해결했는지, 그리고 그 과정에서 겪었던 모든 시행착오를 공유하려고 해요.

---

## 기존 문제: Replica Shifting

### 무엇이 문제였나?

```yaml
replicas: 2
strategy:
  canary:
    steps:
      - setWeight: 25  # 실제로는 33%가 됨!
```

**동작 원리:**
```
Before:
Old Pods: 2개 (100% 트래픽)

Canary 시작:
Old Pods: 2개 (유지)
New Pods: 1개 (추가, maxSurge: 1)
→ 총 3개 Pod

트래픽 분산:
New Pod: 1/3 = 33.3%
Old Pods: 2/3 = 66.7%

원하는 25%가 아닌 33%! ❌
```

### 왜 이런 일이 벌어지나?

Kubernetes Service는 **Pod 개수로 트래픽을 분산**해요. 3개 Pod가 있으면 각각 1/3씩 받는 거죠.

수학적으로 정확히 25%를 만들려면?
```
Old Pods: 3개
New Pods: 1개
→ 1/4 = 25% ✅

하지만 replicas: 2일 때는 불가능!
```

replicas를 4, 8, 16처럼 늘리면 더 정확한 비율을 만들 수 있지만, 리소스 낭비가 심하죠.

---

## 해결 방법: ALB Traffic Routing

### 핵심 아이디어

**ALB의 Weighted Target Groups 기능 활용**

```
Before (Replica Shifting):
Service (단일) → Pod 개수로 분산
  → 제한적인 비율 (33%, 50%, 66%)

After (ALB Traffic Routing):
ALB → Target Group A (90% 가중치) → Old Pods
    → Target Group B (10% 가중치) → New Pods
  → 정확한 비율! ✅
```

ALB가 Target Group 레벨에서 가중치를 설정하니, **Pod 개수와 무관하게** 정확한 비율을 만들 수 있어요.

### 아키텍처

```
사용자 요청
    ↓
Route53 (www.goupang.shop)
    ↓
ALB (ACM HTTPS)
    ↓
[Listener Rule]
    ↓
[Forward Action - Weighted Target Groups]
    ├─ Target Group A (90%) ← was-stable Service ← Old Pods (2개)
    └─ Target Group B (10%) ← was-canary Service ← New Pods (2개)
```

**핵심 원리:**
- ALB의 Weighted Target Groups 기능 활용
- Service 2개로 Target Group 분리
- Argo Rollouts가 가중치 동적 조정

---

## 구현 과정

### Phase 1: Service 분리

#### 왜 Service를 분리해야 하나?

ALB는 **Target Group 단위로만** 가중치를 설정할 수 있어요.

```
✅ 가능 (Service 2개):
ALB → [Weighted TG Action]
       ├─ TG A (90%) ← Service A → Old Pods
       └─ TG B (10%) ← Service B → New Pods

❌ 불가능 (Service 1개):
ALB → Single TG ← Single Service → Mixed Pods
(ALB는 Pod를 직접 구분 못함)
```

처음에 이걸 이해하는 데 시간이 걸렸어요. "왜 Service를 2개나 만들어야 하지?"

알고 보니 **Service = Target Group의 1:1 매핑**이더라고요. Service Selector로 어떤 Pod를 Target Group에 등록할지 결정하는 거죠.

#### Service Stable 생성

```yaml
apiVersion: v1
kind: Service
metadata:
  name: was-stable
  namespace: petclinic
spec:
  selector:
    app: petclinic
    tier: was
    # Argo Rollouts가 자동으로 rollouts-pod-template-hash 추가
  ports:
    - name: http
      port: 8080
      targetPort: 8080
```

**핵심 포인트:**
- `selector`: Rollout의 Pod Label과 일치해야 함
- `rollouts-pod-template-hash`: Argo Rollouts가 자동으로 추가하여 Stable/Canary Pod 구분

#### Service Canary 생성

```yaml
apiVersion: v1
kind: Service
metadata:
  name: was-canary
  namespace: petclinic
spec:
  selector:
    app: petclinic
    tier: was
    # Argo Rollouts가 자동으로 rollouts-pod-template-hash 추가
  ports:
    - name: http
      port: 8080
      targetPort: 8080
```

---

### Phase 2: Rollout 수정

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Rollout
metadata:
  name: was
  namespace: petclinic
spec:
  replicas: 2

  strategy:
    canary:
      # Service 이름 지정
      canaryService: was-canary
      stableService: was-stable

      # ALB Traffic Routing 설정
      trafficRouting:
        alb:
          ingress: petclinic-ingress  # Ingress 리소스 이름
          servicePort: 8080           # Service Port

      # Canary 배포 단계
      steps:
        # 10% Canary
        - setWeight: 10
        - pause: {duration: 30s}

        # 25% Canary
        - setWeight: 25
        - pause: {duration: 30s}

        # 50% Canary
        - setWeight: 50
        - pause: {duration: 30s}

        # 75% Canary
        - setWeight: 75
        - pause: {duration: 30s}

        # 100% Canary (자동 promote)
```

**변경 사항 설명:**

| 항목 | 변경 전 | 변경 후 | 이유 |
|------|---------|---------|------|
| `canaryService` | 없음 | `was-canary` | Canary Pod를 별도 Target Group에 등록 |
| `stableService` | 없음 | `was-stable` | Stable Pod를 별도 Target Group에 등록 |
| `trafficRouting.alb` | 없음 | 추가 | ALB 가중치 제어 활성화 |
| `steps.setWeight` | 25, 50 | 10, 25, 50, 75 | 점진적 배포 (10%부터 시작) |

#### 왜 maxSurge를 제거할 수 있나?

**Replica Shifting 시:**
```yaml
replicas: 2
maxSurge: 1  # 필수!
# → Old 2개 유지 + New 1개 추가 = 3개로 33% 구현
```

**ALB Traffic Routing 시:**
```yaml
replicas: 2
# maxSurge 불필요
# → Stable 2개, Canary 2개 (총 4개)
# → ALB가 가중치로 10%, 25% 구현
```

ALB Traffic Routing에서는 maxSurge를 명시하지 않아도 돼요. Argo Rollouts가 자동으로 Canary replicas를 생성하거든요.

---

### Phase 3: Ingress 확인 및 수정

#### 현재 Ingress 상태 확인

```bash
kubectl get ingress -n petclinic
```

**실제 결과:**
```
NAME                 CLASS   HOSTS              ADDRESS
petclinic-ingress    alb     www.goupang.shop   k8s-petclinicgroup-...
```

**Ingress YAML 확인:**
```yaml
metadata:
  annotations:
    alb.ingress.kubernetes.io/scheme: internet-facing
    alb.ingress.kubernetes.io/target-type: ip
    alb.ingress.kubernetes.io/group.name: petclinic-group
```

#### Argo Rollouts가 자동으로 annotation 추가

**Rollout 배포 시 자동 추가되는 annotation:**

```yaml
metadata:
  annotations:
    # 기존 annotation은 유지
    alb.ingress.kubernetes.io/scheme: internet-facing
    ...

    # Argo Rollouts가 자동 추가 ⭐
    alb.ingress.kubernetes.io/actions.was-stable: |
      {
        "Type": "forward",
        "ForwardConfig": {
          "TargetGroups": [
            {
              "ServiceName": "was-stable",
              "ServicePort": "8080",
              "Weight": 90
            },
            {
              "ServiceName": "was-canary",
              "ServicePort": "8080",
              "Weight": 10
            }
          ]
        }
      }
```

이 부분이 정말 놀라웠어요. **Argo Rollouts가 Ingress annotation을 동적으로 수정**해서 ALB 가중치를 제어하는 거예요!

#### Terraform 관리 Ingress 수정

제 경우 Ingress가 Terraform으로 관리되고 있어서, WAS backend 경로를 추가해야 했어요.

**문제 발견:**
```bash
kubectl describe rollout was -n petclinic
# Error: ingress `petclinic-ingress` has no rules using service was-stable backend
```

Ingress에 WEB만 있고 WAS backend 경로가 없었던 거예요.

**해결:**
```hcl
spec {
  rule {
    http {
      # WAS 직접 접근 경로 (ALB Traffic Routing용)
      path {
        path      = "/petclinic"
        path_type = "Prefix"
        backend {
          service {
            name = "was-stable"
            port {
              number = 8080
            }
          }
        }
      }

      # 기본 경로 - nginx로 라우팅
      path {
        path      = "/"
        path_type = "Prefix"
        backend {
          service {
            name = "web-service"
            port {
              number = 80
            }
          }
        }
      }
    }
  }
}
```

kubectl로 즉시 패치 적용:
```bash
kubectl patch ingress petclinic-ingress -n petclinic --type='json' -p='[...]'
```

---

### Phase 4: Behavior 분리 (GET vs POST)

여기서 큰 문제를 발견했어요.

**문제:** CloudFront Origin Group은 **POST/PUT/DELETE 메서드를 허용하지 않음**

```
POST /owners/new → 403 Forbidden ❌
```

처음엔 "왜 POST가 막히지?"라고 당황했는데, Origin Group의 제약사항이었어요.

**해결:** Behavior를 경로별로 분리

```json
{
  "Behaviors": {
    "Items": [
      {
        "PathPattern": "/*",
        "TargetOriginId": "Azure-Failover-Group",
        "AllowedMethods": ["GET", "HEAD", "OPTIONS"]
      },
      {
        "PathPattern": "/owners/*",
        "TargetOriginId": "Azure-VM",
        "AllowedMethods": ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
      }
    ]
  }
}
```

**왜 이렇게?**
- **조회(GET)**: Origin Group → VM 실패 시 SWA Failover ✅
- **쓰기(POST)**: VM Direct → VM 실패 시 503 에러 (합리적)

쓰기 작업은 어차피 VM이 다운되면 할 수 없으니, 점검 페이지로 Failover하는 것보다 명확한 에러를 보여주는 게 낫다고 판단했어요.

---

## 배포 테스트

### Rollout 상태 모니터링

```bash
# 실시간 모니터링
kubectl argo rollouts get rollout was -n petclinic --watch
```

**예상 출력:**

```
Name:            was
Namespace:       petclinic
Status:          ॥ Paused
Strategy:        Canary
  Step:          1/8
  SetWeight:     10
  ActualWeight:  10  ← ALB가 정확히 10% 트래픽 전달!
Images:          ...was:abc123 (stable)
                 ...was:def456 (canary)

Replicas:
  Desired:       2
  Current:       4  (2 stable + 2 canary)
  Updated:       2
  Ready:         4
  Available:     4
```

**핵심 확인 포인트:**
- `ActualWeight: 10` → ALB가 10% 트래픽을 Canary로 전달
- `Replicas: 4 (2 stable + 2 canary)` → Pod 개수 고정
- `Service` 2개 모두 Healthy

---

### ALB Target Group 확인

```bash
# ALB ARN 조회
ALB_ARN=$(aws elbv2 describe-load-balancers \
  --query "LoadBalancers[?contains(DNSName, 'k8s-petclinicgroup')].LoadBalancerArn" \
  --output text)

# Listener 조회 (HTTPS:443)
LISTENER_ARN=$(aws elbv2 describe-listeners \
  --load-balancer-arn $ALB_ARN \
  --query "Listeners[?Port==`443`].ListenerArn" \
  --output text)

# Rules 조회
aws elbv2 describe-rules --listener-arn $LISTENER_ARN \
  --query 'Rules[?!IsDefault].[Priority,Actions[0].ForwardConfig.TargetGroups]' \
  --output json | jq .
```

**예상 출력:**
```json
[
  [
    "1",
    [
      {
        "TargetGroupArn": "arn:aws:...:targetgroup/k8s-petclinic-wasstable-xxx",
        "Weight": 90
      },
      {
        "TargetGroupArn": "arn:aws:...:targetgroup/k8s-petclinic-wascanary-yyy",
        "Weight": 10
      }
    ]
  ]
]
```

**확인 사항:**
- Target Group 2개 생성됨 (stable, canary)
- Weight가 90:10 (또는 현재 step의 비율)

진짜 ALB 가중치가 10:90으로 설정된 걸 확인했을 때, 정말 감격스러웠어요!

---

### 트래픽 테스트

```bash
# 10번 요청하여 어느 Pod가 응답하는지 확인
for i in {1..10}; do
  curl -s https://www.goupang.shop/actuator/info | jq -r '.build.version'
  sleep 1
done

# 예상 결과 (setWeight: 10일 때):
# 1.0.0 (stable)
# 1.0.0 (stable)
# 1.0.1 (canary) ← 10%
# 1.0.0 (stable)
# ...
```

**통계 확인:**
```bash
for i in {1..100}; do
  curl -s https://www.goupang.shop/actuator/info | jq -r '.build.version'
done | sort | uniq -c

# 예상:
#  90 1.0.0  (stable - 90%)
#  10 1.0.1  (canary - 10%)
```

실제로 테스트해보니 정확히 10:90 비율로 트래픽이 분산됐어요. Replica Shifting에서는 불가능했던 일이죠!

---

## 트러블슈팅

### 문제 1: Ingress 이름 불일치

**증상:**
```bash
kubectl describe rollout was -n petclinic
# Error: ingress.networking.k8s.io "petclinic-was-ingress" not found
```

**원인:**
rollout.yaml에 잘못된 Ingress 이름 설정

```yaml
trafficRouting:
  alb:
    ingress: petclinic-was-ingress  # ❌ 실제 Ingress 이름과 다름
```

**해결:**
```yaml
trafficRouting:
  alb:
    ingress: petclinic-ingress  # ✅ 올바른 이름
```

---

### 문제 2: Ingress에 WAS backend 없음

**증상:**
```bash
kubectl describe rollout was -n petclinic
# Error: ingress `petclinic-ingress` has no rules using service was-stable backend
```

**원인:**
Ingress가 WEB(nginx)만 가지고 있고 WAS backend가 없음

**해결:**
Terraform에 WAS backend 경로 추가 후 kubectl patch 적용

---

## 배운 점

### 1. ALB의 강력함

단순한 로드밸런서가 아니라, **정교한 트래픽 제어 도구**라는 걸 깨달았어요. Weighted Target Groups 기능이 Canary 배포를 완벽하게 만들어줬죠.

### 2. Service의 역할

처음엔 "왜 Service를 2개나 만들어야 해?"라고 생각했는데, **Service = Target Group**이라는 걸 이해하니 명확해졌어요.

### 3. Argo Rollouts의 유연함

Ingress annotation을 동적으로 수정하는 방식이 정말 영리하다고 느꼈어요. 별도의 컨트롤러 없이, 선언적으로 모든 걸 관리할 수 있으니까요.

### 4. 쓰기 작업은 신중하게

POST/PUT 같은 쓰기 작업은 Failover하면 안 된다는 교훈을 얻었어요. 데이터 일관성이 깨질 수 있거든요.

---

## 마치며

Replica Shifting에서 ALB Traffic Routing으로 전환하면서, **"정확성"**의 중요성을 깨달았어요.

10%를 원하는데 33%가 되는 것과, 정확히 10%를 제어하는 것은 완전히 다른 경험이에요. 특히 프로덕션 환경에서는 이런 정확성이 서비스 안정성에 직결되니까요.

**핵심 정리:**
- Replica Shifting: Pod 개수로 제어 → 제한적 비율
- ALB Traffic Routing: ALB 가중치로 제어 → 정확한 비율
- Service 분리: Target Group 1:1 매핑
- Argo Rollouts: Ingress annotation 동적 수정

ALB Traffic Routing은 단순한 기능이 아니라, **프로덕션급 Canary 배포의 핵심**이에요. 여러분도 꼭 구현해보시길 추천드립니다!
