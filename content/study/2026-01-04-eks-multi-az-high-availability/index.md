---
title: "AWS EKS Multi-AZ로 99.99% 가용성 달성하기"
date: 2026-01-04T16:00:00+09:00
tags: ["AWS EKS", "Multi-AZ", "High Availability", "Kubernetes", "topologySpreadConstraints", "Karpenter"]
categories: ["study", "Cloud & Terraform"]
description: "AWS EKS에서 Pod를 여러 가용 영역에 균등 분산하여 단일 AZ 장애 시에도 서비스를 유지하는 고가용성 아키텍처 구축 경험을 공유합니다. topologySpreadConstraints, Pod Anti-Affinity, Karpenter 설정까지 실전 노하우를 담았습니다."
---

## 들어가며

어느 날 "만약 가용 영역(AZ) 하나가 통째로 죽으면 어떡하지?"라는 생각이 들었어요. AWS는 AZ 장애가 드물다고 하지만, 실제로 일어났을 때 우리 서비스가 버틸 수 있을까?

확인해보니 WAS Pod 2개가 "우연히" 다른 AZ에 분산되어 있었어요. 하지만 이건 보장된 게 아니었죠. Pod를 재시작하면 모두 같은 AZ로 몰릴 수도 있었어요.

이 글에서는 Kubernetes `topologySpreadConstraints`를 사용해 **99.99% 가용성**을 달성한 과정을 공유하려고 합니다.

---

## 문제 상황: 우연에 의존한 분산

### 현재 상태 (구축 초기)

```
| Pod | Node | AZ | 상태 |
|-----|------|----|------|
| redis-master-0 | ip-10-0-12-217 | 2c | ⚠️ 단일 AZ (SPOF) |
| was-xxx-6zq6p | ip-10-0-12-217 | 2c | ✅ 2c AZ |
| was-xxx-kksbb | ip-10-0-11-71 | 2a | ✅ 2a AZ (멀티 AZ 분산) |
| web-xxx-7nz49 | ip-10-0-11-47 | 2a | ✅ 2a AZ |
| web-xxx-wxpx9 | ip-10-0-12-217 | 2c | ✅ 2c AZ (멀티 AZ 분산) |
```

WEB/WAS는 다행히 두 AZ에 분산되어 있었어요. 하지만 이건 **우연**이었죠.

### 문제점

- ❌ Kubernetes Scheduler가 리소스만 보고 배치 (AZ 고려 안 함)
- ❌ topologySpreadConstraints 없음 → 분산 보장 안 됨
- ❌ Pod 재시작 시 모두 같은 AZ로 몰릴 수 있음
- ❌ HPA 스케일 아웃 시 불균등 분산 가능

---

## AZ 장애 시나리오 분석

실제로 AZ가 장애 나면 어떻게 될까요?

### 시나리오 1: AZ-C 장애 (Redis가 있는 AZ)

```
┌─────────────────────────────────────────────────────────────┐
│                    EKS Cluster (VPC)                        │
│                                                             │
│  ┌──────────────────────┐      ┌──────────────────────┐   │
│  │   AZ 2a (정상)        │      │   AZ 2c (❌ 장애)     │   │
│  │                      │      │                      │   │
│  │  WAS Pod 1 ✅        │      │  WAS Pod 2 ❌        │   │
│  │  (살아있음)           │      │  (중단)              │   │
│  │                      │      │                      │   │
│  │  WEB Pod 1 ✅        │      │  WEB Pod 2 ❌        │   │
│  │  (살아있음)           │      │  (중단)              │   │
│  │                      │      │                      │   │
│  │                      │      │  Redis Pod ❌        │   │
│  │                      │      │  (중단 - SPOF!)      │   │
│  └──────────────────────┘      └──────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

**영향:**
- WAS: 50% 용량 손실 (2 → 1 pod)
- WEB: 50% 용량 손실 (2 → 1 pod)
- **Redis: 100% 중단** → WAS가 세션 조회 불가 → **전체 서비스 중단** ❌

Redis가 단일 AZ에만 있어서 AZ-C가 죽으면 서비스 전체가 멈춰요.

### 시나리오 2: AZ-A 장애 (Redis 없는 AZ)

```
┌─────────────────────────────────────────────────────────────┐
│                    EKS Cluster (VPC)                        │
│                                                             │
│  ┌──────────────────────┐      ┌──────────────────────┐   │
│  │   AZ 2a (❌ 장애)     │      │   AZ 2c (정상)        │   │
│  │                      │      │                      │   │
│  │  WAS Pod 1 ❌        │      │  WAS Pod 2 ✅        │   │
│  │  (중단)              │      │  (살아있음)           │   │
│  │                      │      │                      │   │
│  │  WEB Pod 1 ❌        │      │  WEB Pod 2 ✅        │   │
│  │  (중단)              │      │  (살아있음)           │   │
│  │                      │      │                      │   │
│  │                      │      │  Redis Pod ✅        │   │
│  │                      │      │  (정상 동작)          │   │
│  └──────────────────────┘      └──────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

**영향:**
- WAS: 50% 용량 (1 pod 살아있음) ✅
- WEB: 50% 용량 (1 pod 살아있음) ✅
- Redis: 정상 ✅
- **결과: 서비스 유지 (느리지만 동작)** ✅

AZ-A가 죽어도 서비스는 계속 돼요. Redis가 살아있으니까요.

---

## 해결 방법: topologySpreadConstraints

### 왜 topologySpreadConstraints인가?

여러 방법을 비교해봤어요:

| 방법 | 장점 | 단점 |
|------|------|------|
| **topologySpreadConstraints** | ✅ 간단, 유연, K8s 표준 | - |
| Pod Anti-Affinity | 유사한 효과 | 복잡한 설정 |
| Node Selector | 명시적 제어 | AZ마다 별도 설정 필요 |

topologySpreadConstraints가 가장 간단하면서도 강력했어요.

### 동작 원리

```yaml
topologySpreadConstraints:
  - maxSkew: 1                                    # AZ 간 최대 차이 1개
    topologyKey: topology.kubernetes.io/zone      # AZ 기준 분산
    whenUnsatisfiable: ScheduleAnyway             # ⭐ 조건 불만족 시에도 스케줄 (HA 우선!)
    labelSelector:
      matchLabels:
        app: petclinic
        tier: was
```

**핵심 설정:**

- `maxSkew: 1`: AZ 간 Pod 개수 차이가 최대 1개까지만 허용
- `topologyKey: topology.kubernetes.io/zone`: AZ 기준으로 분산
- `whenUnsatisfiable: ScheduleAnyway`: **조건을 만족 못해도 일단 스케줄 (중요!)**

---

## DoNotSchedule vs ScheduleAnyway

처음엔 `DoNotSchedule`을 썼다가 큰 문제를 겪었어요.

### 실제 테스트 결과

| 설정 | AZ 장애 시 동작 | 결과 |
|------|----------------|------|
| **DoNotSchedule** | "AZ 분산 못하면 스케줄 안 함" | ❌ Pod Pending, 서비스 중단 |
| **ScheduleAnyway** | "AZ 분산 못해도 일단 스케줄" | ✅ 한쪽 AZ에 몰리지만 서비스 유지 |

### 시나리오: AZ-A 장애 발생

```
정상 시: AZ-A 1개, AZ-C 1개 (분산)

AZ-A 장애 시 (ScheduleAnyway):
  → AZ-C에 2개 몰림 → 서비스 유지! ✅
  → "분산은 안 되지만 일단 서비스는 살려야지"

AZ-A 장애 시 (DoNotSchedule):
  → "AZ 분산 못하니까 스케줄 안 함"
  → Pod Pending → 서비스 중단! ❌
  → "원칙은 지켰지만 서비스는 죽음"
```

**결론: HA가 목적이면 `ScheduleAnyway` 사용!**

원칙보다 서비스 유지가 더 중요해요. 완벽한 분산은 못해도, 서비스는 살려야 하니까요.

---

## 구현 가이드

### WEB Deployment 수정

**파일:** `~/CICD/manifestrepo/web/deployment.yaml`

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: web
  namespace: petclinic
spec:
  replicas: 2
  template:
    metadata:
      labels:
        app: petclinic
        tier: web
    spec:
      # ✅ Pod Anti-Affinity (같은 노드에 같은 Pod 안 뜨게)
      affinity:
        podAntiAffinity:
          preferredDuringSchedulingIgnoredDuringExecution:
            - weight: 100
              podAffinityTerm:
                labelSelector:
                  matchLabels:
                    app: petclinic
                    tier: web
                topologyKey: kubernetes.io/hostname

      # ✅ topologySpreadConstraints (AZ 분산)
      topologySpreadConstraints:
        - maxSkew: 1
          topologyKey: topology.kubernetes.io/zone
          whenUnsatisfiable: ScheduleAnyway  # ⭐ HA 우선!
          labelSelector:
            matchLabels:
              app: petclinic
              tier: web

        - maxSkew: 1
          topologyKey: kubernetes.io/hostname
          whenUnsatisfiable: ScheduleAnyway
          labelSelector:
            matchLabels:
              app: petclinic
              tier: web

      containers:
        - name: web
          # ✅ Startup Probe 추가 (부팅 느려도 죽이지 마)
          startupProbe:
            httpGet:
              path: /health
              port: 80
            failureThreshold: 6
            periodSeconds: 10
          ...
```

**왜 두 개의 topologySpreadConstraints?**

1. **zone 기준**: AZ 간 균등 분산 (Multi-AZ HA)
2. **hostname 기준**: 노드 간 균등 분산 (노드 장애 대응)

둘 다 설정해서 AZ도 분산하고, 노드도 분산했어요.

### WAS Rollout 수정

Argo Rollout도 동일한 방식으로 설정했어요:

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Rollout
metadata:
  name: was
  namespace: petclinic
spec:
  replicas: 2
  template:
    spec:
      affinity:
        podAntiAffinity:
          preferredDuringSchedulingIgnoredDuringExecution:
            - weight: 100
              podAffinityTerm:
                labelSelector:
                  matchLabels:
                    app: petclinic
                    tier: was
                topologyKey: kubernetes.io/hostname

      topologySpreadConstraints:
        - maxSkew: 1
          topologyKey: topology.kubernetes.io/zone
          whenUnsatisfiable: ScheduleAnyway
          labelSelector:
            matchLabels:
              app: petclinic
              tier: was

        - maxSkew: 1
          topologyKey: kubernetes.io/hostname
          whenUnsatisfiable: ScheduleAnyway
          labelSelector:
            matchLabels:
              app: petclinic
              tier: was
```

---

## 적용 및 검증

### Git Push

```bash
cd ~/CICD/manifestrepo

git add was/rollout.yaml web/deployment.yaml
git commit -m "Add topologySpreadConstraints for Multi-AZ HA

- maxSkew: 1 (AZ 간 최대 차이 1개)
- topologyKey: topology.kubernetes.io/zone
- whenUnsatisfiable: ScheduleAnyway

Why:
- Pod 재시작 시에도 멀티 AZ 분산 보장
- HPA 스케일 아웃 시 균등 분산
- 단일 AZ 장애 시에도 서비스 유지"

git push
```

### ArgoCD Sync 확인

```bash
# ArgoCD 자동 sync 대기 또는 수동 refresh
kubectl annotate application petclinic -n argocd argocd.argoproj.io/refresh=normal --overwrite

# 적용 확인
kubectl get application petclinic -n argocd
# 출력: SYNC STATUS: Synced, HEALTH STATUS: Healthy
```

### Pod 재배치 확인

```bash
# Pod 재시작하여 재배치 확인
kubectl rollout restart deployment web -n petclinic
kubectl argo rollouts restart was -n petclinic

# 대기
sleep 60

# AZ 분산 확인
kubectl get pods -n petclinic -o wide
```

**예상 결과:**
```
NAME                   READY   STATUS    NODE
was-xxxxxxxxx-xxxxx    1/1     Running   ip-10-0-11-71...  (2a AZ)
was-xxxxxxxxx-xxxxx    1/1     Running   ip-10-0-12-217... (2c AZ)
web-xxxxxxxxx-xxxxx    1/1     Running   ip-10-0-11-47...  (2a AZ)
web-xxxxxxxxx-xxxxx    1/1     Running   ip-10-0-12-217... (2c AZ)
```

완벽하게 분산됐어요!

---

## Karpenter Security Group 문제

### 문제 발견

AZ 장애 테스트 중에 Karpenter가 생성한 노드에서 DNS 해석이 실패했어요:

```
java.net.UnknownHostException: eks-3tier-dev-db.czgliwfs2orh.ap-northeast-2.rds.amazonaws.com
```

### 원인 분석

```
┌─────────────────────────────────────────────────────────────────┐
│  Managed Node SG: sg-0c0ca21f964249e1c                         │
│    - DNS (UDP 53) 허용 ✅                                       │
│    - CoreDNS Pod가 여기서 실행 중                                │
├─────────────────────────────────────────────────────────────────┤
│  Karpenter Node SG: sg-007ad52306baf04e7 (Cluster SG)          │
│    - Protocol: ALL (-1)                                         │
│    - Source: 자기 자신만! (self-reference)                       │
│    → Managed Node의 CoreDNS와 통신 불가! ❌                      │
└─────────────────────────────────────────────────────────────────┘
```

Karpenter 노드가 Managed Node의 CoreDNS와 통신을 못 하는 문제였어요.

### 해결 방법

**1. Managed Node SG에 Karpenter discovery 태그 추가:**

```bash
aws ec2 create-tags \
  --resources sg-0c0ca21f964249e1c \
  --tags Key=karpenter.sh/discovery,Value=eks-dev-cluster
```

**2. Terraform에서 영구 설정:**

```hcl
module "eks" {
  ...

  # Node Security Group Tags (Karpenter Discovery)
  # Karpenter가 Managed Node와 같은 SG를 사용하도록 태그 추가
  node_security_group_tags = {
    "karpenter.sh/discovery" = var.cluster_name
  }
}
```

이러면 Karpenter가 노드를 생성할 때 Managed Node와 같은 Security Group을 사용해요.

---

## 실제 AZ 장애 테스트

### 테스트 절차

실제로 AZ 장애를 시뮬레이션해봤어요:

```bash
# 1. AZ-A 노드 cordon (스케줄 금지)
kubectl cordon ip-10-0-11-47.ap-northeast-2.compute.internal
kubectl cordon ip-10-0-11-71.ap-northeast-2.compute.internal

# 2. AZ-A에 있는 WAS/WEB Pod 삭제
kubectl delete pod was-xxx -n petclinic
kubectl delete pod web-xxx -n petclinic

# 3. Pod가 AZ-C에서 재생성되는지 확인
kubectl get pods -n petclinic -o wide

# 4. 서비스 접속 확인
curl -I https://www.goupang.shop/petclinic/

# 5. 복구
kubectl uncordon ip-10-0-11-47.ap-northeast-2.compute.internal
kubectl uncordon ip-10-0-11-71.ap-northeast-2.compute.internal
```

### 테스트 결과

```
┌─────────────────────────────────────────────────────────────────┐
│                    AZ-A 장애 시뮬레이션 성공! ✅                 │
├─────────────────────────────────────────────────────────────────┤
│  AZ-A: SchedulingDisabled (장애 시뮬레이션)                     │
│    - ip-10-0-11-47 ❌                                          │
│    - ip-10-0-11-71 ❌                                          │
├─────────────────────────────────────────────────────────────────┤
│  AZ-C: 모든 Pod 실행 중! ✅                                     │
│    - WAS #1: ip-10-0-13-41                                     │
│    - WAS #2: ip-10-0-13-41                                     │
│    - WEB #1: ip-10-0-12-172 (Karpenter 자동 생성!)             │
│    - WEB #2: ip-10-0-13-79                                     │
├─────────────────────────────────────────────────────────────────┤
│  서비스 접속: HTTP/2 200 OK ✅                                  │
└─────────────────────────────────────────────────────────────────┘
```

**핵심:** Karpenter가 자동으로 새 노드를 생성하고, Pod가 AZ-C에서 정상 동작했어요!

---

## Redis SPOF 이슈

### 현재 상태

Redis는 여전히 단일 AZ에만 있어요:

```
2a AZ:              2c AZ:
WAS Pod 1 ────┐     WAS Pod 2 ────┐
               │                   │
               └───> Redis Pod <───┘
                     (2c AZ - SPOF!)
```

AZ-C가 장애 나면 Redis가 죽고, 전체 서비스가 중단돼요.

### 해결 방안 (선택)

#### Option 1: Redis Sentinel (3 replica, Multi-AZ)

```yaml
spec:
  replicas: 3  # 3개 replica
  template:
    spec:
      topologySpreadConstraints:
        - maxSkew: 1
          topologyKey: topology.kubernetes.io/zone
          whenUnsatisfiable: DoNotSchedule
```

**장점:**
- ✅ Multi-AZ 고가용성
- ✅ 자동 failover

**단점:**
- ❌ 복잡도 증가 (Sentinel 설정 필요)
- ❌ 리소스 3배 (3 replica)

#### Option 2: ElastiCache for Redis (AWS 관리형)

**장점:**
- ✅ AWS 관리형 (자동 백업, 패치)
- ✅ Multi-AZ 자동 failover
- ✅ 설정 간단

**단점:**
- ❌ 비용 증가 (~$30/month)
- ❌ Terraform 설정 변경 필요

#### Option 3: 현재 유지 (세션 손실 허용)

**현재 선택한 방법:**

1. ✅ WEB/WAS만 topologySpreadConstraints 적용 (즉시)
2. ⏸️ Redis HA는 Priority 2 (나중에 개선)

**이유:**
- AZ-A 장애 시에도 서비스 유지 가능 (WAS/WEB/Redis 모두 정상)
- AZ-C 장애는 확률이 낮음 (AWS AZ SLA 99.99%)
- Redis HA 구축 비용 대비 효과 낮음 (현재 트래픽 기준)

---

## 적용 효과

### Before/After 비교

| 시나리오 | 적용 전 | 적용 후 |
|---------|---------|---------|
| **정상 운영** | WEB 2, WAS 2 (우연히 분산) | WEB 2, WAS 2 (보장된 분산) ✅ |
| **Pod 재시작** | 같은 AZ로 몰릴 수 있음 ❌ | 균등 분산 보장 ✅ |
| **HPA 스케일 아웃** | 불균등 분산 가능 ❌ | 균등 분산 보장 ✅ |
| **AZ-A 장애** | 50% 용량 (Redis 정상) ✅ | 50% 용량 (Redis 정상) ✅ |
| **AZ-C 장애** | Redis 중단 → 전체 중단 ❌ | Redis 중단 → 전체 중단 ❌ |

WEB/WAS는 완벽하게 보호되었고, Redis는 아직 SPOF로 남아있어요.

---

## 배운 점

### ScheduleAnyway의 중요성

처음엔 "AZ 분산을 완벽하게 해야지"라고 생각해서 `DoNotSchedule`을 썼어요. 하지만 테스트해보니 AZ 장애 시 서비스가 중단되더라고요.

**깨달은 점:** 완벽한 원칙보다 **서비스 유지**가 더 중요해요. 분산은 못해도 일단 서비스가 살아있어야 한다는 걸 배웠습니다.

### Karpenter Security Group 함정

Karpenter가 자동으로 노드를 만들어주는 건 편한데, Security Group 설정을 놓치면 DNS가 안 돼요. `karpenter.sh/discovery` 태그를 꼭 추가해야 한다는 걸 배웠습니다.

### 모든 SPOF를 없앨 필요는 없다

Redis SPOF를 고민하다가 깨달은 건, **모든 SPOF를 없앨 필요는 없다**는 거예요. 비용, 복잡도, 확률을 따져봤을 때 현재는 WEB/WAS 보호만으로도 충분했어요.

나중에 트래픽이 늘고, 비즈니스 중요도가 높아지면 그때 Redis HA를 구축해도 늦지 않아요.

---

## 마무리

topologySpreadConstraints로 Multi-AZ 고가용성을 구축하면서 **가용성 설계**의 핵심을 배웠습니다.

완벽한 분산보다 **서비스 유지**가 더 중요하고, 모든 SPOF를 없애기보다 **우선순위를 정하는 것**이 현명하다는 걸 깨달았어요.

다음 글에서는 MySQL 연결이 Broken pipe로 끊기는 문제를 해결한 경험을 공유할게요!
