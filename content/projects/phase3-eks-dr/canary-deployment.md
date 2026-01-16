---
title: "Canary 배포: 버그가 있어도 10% 고객만 영향받게"
date: 2026-01-12
summary: "Argo Rollouts로 안전한 배포와 즉시 롤백 구현"
tags: ["canary", "argo-rollouts", "deployment", "kubernetes", "progressive-delivery"]
categories: ["projects"]
series: ["Infrastructure Learning Journey"]
weight: 3
showtoc: true
tocopen: true
---

# Canary 배포: 버그가 있어도 10% 고객만 영향받게

> Rolling Update에서 Canary 배포로 전환해 배포 안정성 10배 향상

---

## 🚨 문제의 발견: Rolling Update의 한계

Phase 2에서 Rolling Update로 무중단 배포를 구현했지만, **치명적인 버그를 배포한 사건**이 있었습니다.

### 사건: 로그인 버그 배포 (2025-12-15)

**상황:**
```java
// 잘못된 코드 (버그)
if (user.getPassword() == password) {  // == 연산자 사용 ❌
    return "login success";
}
// 올바른 코드
if (user.getPassword().equals(password)) {  // equals() 사용 ✅
    return "login success";
}
```

**배포 과정:**
```
09:00 - Git Push (버그 있는 코드)
09:10 - Jenkins 빌드 완료
09:12 - ArgoCD Rolling Update 시작
09:12 - Pod 1 배포 완료 (전체의 50%)
09:13 - Pod 2 배포 시작
09:14 - Pod 2 배포 완료 (전체의 100%) ✅
```

**문제 발생:**
```
09:15 - 고객 A: "로그인이 안 되요!"
09:16 - 고객 B: "비밀번호가 틀렸다고 나와요!"
09:17 - 고객 C: "저도 로그인 안 됩니다!"
09:18 - 긴급 상황 인지 → 롤백 결정
09:20 - Git revert + ArgoCD Sync
09:24 - 롤백 완료 (4분 소요)

영향받은 고객: 100% (전체)
다운타임: 9분
```

**왜 모든 고객이 영향받았는가?**
- Rolling Update는 **점진적으로 배포**하지만
- **트래픽은 즉시 100% 전환**됨
- 버그를 발견하기 전에 이미 전체 배포 완료

---

## 🎯 해결 방안: Canary 배포

### Canary 배포란?

```
Rolling Update (기존):
┌─────────────────────────────────────────┐
│  Old     New     New     New     New    │
│ 100% → 50/50 → 0/100 → 100% → 100%     │
└─────────────────────────────────────────┘
트래픽: 즉시 100% 전환 → 버그 발견 시 전체 영향

Canary 배포 (개선):
┌─────────────────────────────────────────┐
│  Old      Old+New    Old+New   All New  │
│ 100% → 90% + 10% → 50% + 50% → 100%    │
└─────────────────────────────────────────┘
트래픽: 점진적 전환 (10% → 50% → 100%)
→ 버그 발견 시 10%만 영향 ✅
```

**Canary 배포의 핵심:**
1. **Stable (안정 버전)**: 기존 버전 90%
2. **Canary (새 버전)**: 신규 버전 10%
3. **점진적 증가**: 10% → 30% → 50% → 100%
4. **자동 롤백**: 에러율 증가 시 즉시 롤백

---

## 🏗️ 아키텍처: Argo Rollouts

### 왜 Argo Rollouts인가?

| 도구 | Rolling Update | Canary 지원 | 자동 롤백 | 선택 |
|------|---------------|-------------|----------|------|
| **Kubernetes Deployment** | ✅ | ❌ | ❌ | ❌ |
| **Flagger** | ✅ | ✅ | ✅ | ⚠️ 복잡 |
| **Argo Rollouts** | ✅ | ✅ | ✅ | ✅ **선택** |
| **Spinnaker** | ✅ | ✅ | ✅ | ❌ 과도한 복잡도 |

**Argo Rollouts 선택 이유:**
- ArgoCD와 완벽 통합
- 선언적 YAML 설정
- kubectl plugin으로 쉬운 관리
- 자동 롤백 지원

---

### Argo Rollouts 설치

```bash
# Argo Rollouts 설치
kubectl create namespace argo-rollouts
kubectl apply -n argo-rollouts -f \
  https://github.com/argoproj/argo-rollouts/releases/latest/download/install.yaml

# kubectl plugin 설치
curl -LO https://github.com/argoproj/argo-rollouts/releases/latest/download/kubectl-argo-rollouts-linux-amd64
chmod +x kubectl-argo-rollouts-linux-amd64
sudo mv kubectl-argo-rollouts-linux-amd64 /usr/local/bin/kubectl-argo-rollouts

# 설치 확인
kubectl argo rollouts version
# kubectl-argo-rollouts: v1.6.0
```

---

### WAS Rollout 설정

**Before (Deployment):**
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: was
spec:
  replicas: 2
  strategy:
    type: RollingUpdate  # Rolling Update만 가능
```

**After (Rollout):**
```yaml
apiVersion: argoproj.io/v1alpha1
kind: Rollout
metadata:
  name: was
  namespace: petclinic
spec:
  replicas: 2
  revisionHistoryLimit: 5

  strategy:
    canary:
      # Canary 단계 정의
      steps:
        - setWeight: 10    # 1단계: Canary 10%
        - pause:
            duration: 2m   # 2분 대기 (모니터링)

        - setWeight: 30    # 2단계: Canary 30%
        - pause:
            duration: 2m

        - setWeight: 50    # 3단계: Canary 50%
        - pause:
            duration: 2m

        - setWeight: 100   # 4단계: Canary 100%

      # 자동 Rollback 조건
      autoPromotionEnabled: false  # 수동 승인 필요
      maxSurge: 1                  # 추가 생성 Pod 수
      maxUnavailable: 0            # 최소 유지 Pod 수

  selector:
    matchLabels:
      app: petclinic
      tier: was

  template:
    metadata:
      labels:
        app: petclinic
        tier: was
    spec:
      containers:
        - name: was
          image: 123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/petclinic:v100
          ports:
            - containerPort: 8080
          resources:
            requests:
              cpu: 250m
              memory: 512Mi
            limits:
              cpu: 500m
              memory: 1Gi
          livenessProbe:
            httpGet:
              path: /petclinic/actuator/health
              port: 8080
            initialDelaySeconds: 60
            periodSeconds: 10
          readinessProbe:
            httpGet:
              path: /petclinic/actuator/health
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 5
```

**설정 설명:**

| 설정 | 값 | 설명 |
|------|-----|------|
| `setWeight` | 10, 30, 50, 100 | Canary 트래픽 비율 (%) |
| `pause.duration` | 2m | 각 단계마다 2분 대기 (모니터링 시간) |
| `autoPromotionEnabled` | false | 수동 승인 필요 (자동 진행 안 함) |
| `maxSurge` | 1 | 추가로 생성할 Pod 수 |
| `maxUnavailable` | 0 | 최소 유지 Pod 수 (다운타임 방지) |

---

### ALB Ingress 설정 (트래픽 라우팅)

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: petclinic-ingress
  namespace: petclinic
  annotations:
    kubernetes.io/ingress.class: alb
    alb.ingress.kubernetes.io/scheme: internet-facing
    alb.ingress.kubernetes.io/target-type: ip
    alb.ingress.kubernetes.io/listen-ports: '[{"HTTP": 80}, {"HTTPS": 443}]'
    alb.ingress.kubernetes.io/ssl-redirect: '443'

    # Argo Rollouts Canary 지원
    alb.ingress.kubernetes.io/actions.canary-action: |
      {
        "type": "forward",
        "forwardConfig": {
          "targetGroups": [
            {
              "serviceName": "was-stable",
              "servicePort": 8080,
              "weight": 90
            },
            {
              "serviceName": "was-canary",
              "servicePort": 8080,
              "weight": 10
            }
          ]
        }
      }
spec:
  rules:
    - host: www.goupang.shop
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: canary-action  # ALB가 트래픽 분산
                port:
                  name: use-annotation
```

**동작 원리:**
```
사용자 요청
    ↓
ALB (Ingress)
    ↓
┌────────────┴────────────┐
│ was-stable (90%)        │ was-canary (10%)
│ Old Version             │ New Version
└─────────────────────────┴──────────────────
```

---

## ✅ Canary 배포 실행

### 시나리오: 새 버전 배포

```bash
# 1. 코드 수정 후 Git Push
cd ~/CICD/sourece-repo
git add .
git commit -m "Add new feature"
git push origin main

# 2. Jenkins 빌드 완료 (8분)
# → ECR에 petclinic:v101 이미지 생성

# 3. manifestrepo 업데이트
cd ~/CICD/manifestrepo
# was/rollout.yaml:
#   image: petclinic:v101  (v100 → v101 변경)

git add .
git commit -m "Update WAS image to v101"
git push origin main

# 4. ArgoCD 자동 Sync → Canary 배포 시작
kubectl argo rollouts get rollout was -n petclinic --watch

# 출력:
Name:            was
Namespace:       petclinic
Status:          ॥ Paused
Strategy:        Canary
  Step:          1/7
  SetWeight:     10
  ActualWeight:  10
Images:          petclinic:v100 (stable)
                 petclinic:v101 (canary)
Replicas:
  Desired:       2
  Current:       3  (2 stable + 1 canary)
  Updated:       1
  Ready:         3
  Available:     3

# Pods:
NAME                   READY   STATUS    AGE     VERSION
was-v100-abc12         1/1     Running   10m     v100 (stable)
was-v100-def34         1/1     Running   10m     v100 (stable)
was-v101-ghi56         1/1     Running   30s     v101 (canary) ← 새 버전!

# 5. 트래픽 분산 확인
for i in {1..10}; do
  curl -s https://www.goupang.shop/petclinic/actuator/info | jq -r .version
done
# 출력:
# v100  ← 90%
# v100
# v100
# v100
# v100
# v100
# v100
# v100
# v100
# v101  ← 10% (Canary)

# 6. 모니터링 (2분 대기)
# Grafana에서 에러율, 응답 시간 확인
# - 에러율: 0% → 정상 ✅
# - 응답 시간: 500ms → 정상 ✅

# 7. 수동 승인 (다음 단계로 진행)
kubectl argo rollouts promote was -n petclinic

# Canary 30%로 증가
# Pods:
# was-v100-abc12  (stable)
# was-v101-ghi56  (canary)
# was-v101-jkl78  (canary) ← 추가 생성

# 8. 2분 대기 후 다시 승인
kubectl argo rollouts promote was -n petclinic

# Canary 50%로 증가
# Pods:
# was-v101-ghi56  (canary)
# was-v101-jkl78  (canary)

# 9. 최종 승인
kubectl argo rollouts promote was -n petclinic

# Canary 100% → Stable로 전환
# Pods:
# was-v101-ghi56  (stable)
# was-v101-jkl78  (stable)
# was-v100-abc12  (Terminating) ← 이전 버전 종료
# was-v100-def34  (Terminating)

# 10. 배포 완료
kubectl argo rollouts get rollout was -n petclinic

# 출력:
Name:            was
Status:          ✔ Healthy
Strategy:        Canary
Images:          petclinic:v101 (stable)  ← 새 버전이 Stable
Replicas:
  Desired:       2
  Current:       2
  Updated:       2
  Ready:         2
  Available:     2
```

**총 소요 시간:**
- Jenkins 빌드: 8분
- Canary 10%: 2분
- Canary 30%: 2분
- Canary 50%: 2분
- **총 14분** (수동 승인 대기 시간 포함)

---

### 시나리오: 버그 발견 시 즉시 롤백

```bash
# 1. Canary 10% 배포 중
kubectl argo rollouts get rollout was -n petclinic
# Status: ॥ Paused
# Step: 1/7 (Canary 10%)

# 2. 에러 발견 (Grafana 모니터링)
# - 에러율: 0% → 15% ⚠️ (임계값 5% 초과)
# - 응답 시간: 500ms → 3000ms ⚠️

# 3. 즉시 롤백 결정
kubectl argo rollouts abort was -n petclinic

# 출력:
rollout 'was' aborted

# 4. 롤백 완료 (30초 이내)
kubectl argo rollouts get rollout was -n petclinic

# 출력:
Name:            was
Status:          ✖ Degraded
Strategy:        Canary
Images:          petclinic:v100 (stable)  ← 이전 버전 유지
Replicas:
  Desired:       2
  Current:       2  (2 stable + 0 canary)
  Updated:       0
  Ready:         2
  Available:     2

# Pods:
NAME                   READY   STATUS        AGE
was-v100-abc12         1/1     Running       15m     (stable)
was-v100-def34         1/1     Running       15m     (stable)
was-v101-ghi56         1/1     Terminating   3m      (canary 삭제)

# 5. 트래픽 확인 (100% 이전 버전)
for i in {1..10}; do
  curl -s https://www.goupang.shop/petclinic/actuator/info | jq -r .version
done
# 출력:
# v100  ← 100% 이전 버전
# v100
# v100
# ...
```

**롤백 영향:**
- 영향받은 고객: **10%만** (Canary 트래픽)
- 롤백 시간: **30초** (Canary Pod 삭제만)
- 다운타임: **0분** (Stable Pod 유지)

**Before (Rolling Update)와 비교:**
| 지표 | Rolling Update | Canary 배포 |
|------|---------------|------------|
| **영향 고객** | 100% | 10% |
| **롤백 시간** | 4분 (재배포) | 30초 (Pod 삭제) |
| **다운타임** | 0분 | 0분 |

---

## 📊 성과 요약

### 정량적 성과 (3개월)

```
총 Canary 배포: 52회
성공 (100% 전환): 47회 (90.4%)
롤백 (10%에서 중단): 5회 (9.6%)

평균 배포 시간: 14분 (Jenkins 8분 + Canary 6분)
평균 롤백 시간: 28초

버그 발견:
- Canary 10%: 3건 (영향 고객: 평균 45명)
- Canary 30%: 2건 (영향 고객: 평균 120명)
- Canary 50%: 0건
- Production (100%): 0건 ✅

Before (Rolling Update):
- 버그 발견: Production 100% (영향 고객: 평균 1,500명)
```

**개선 효과:**
- 버그 영향 고객: 1,500명 → **45명** (97% 감소)
- 롤백 시간: 4분 → **28초** (93% 단축)

---

## 💡 핵심 교훈

### 1. Progressive Delivery의 힘

**All-or-Nothing (기존):**
```
배포 → 100% 트래픽 → 버그 발견 → 전체 영향 ❌
```

**Progressive Delivery (Canary):**
```
배포 → 10% 트래픽 → 버그 발견 → 10%만 영향 → 롤백 ✅
```

**교훈:**
- **점진적 노출**로 리스크 최소화
- 문제를 **조기에 발견**
- 영향 범위 **제한**

---

### 2. 자동화 vs 수동 승인

현재는 `autoPromotionEnabled: false`로 수동 승인입니다.

**수동 승인 (현재):**
- 장점: 각 단계마다 사람이 확인
- 단점: 배포 시간 증가 (14분)

**자동 승인 (개선안):**
```yaml
strategy:
  canary:
    steps:
      - setWeight: 10
      - pause:
          duration: 2m
      - analysis:  # 자동 분석
          templates:
            - templateName: error-rate-analysis
      # 에러율 5% 이하면 자동 진행
      # 에러율 5% 초과면 자동 롤백
```

**교훈:**
- 초기에는 **수동 승인**으로 학습
- 신뢰도 확보 후 **자동 승인** 전환
- **Analysis Template**로 자동 판단

---

### 3. 모니터링이 핵심

Canary 배포는 **모니터링이 없으면 의미 없음**.

**모니터링 지표:**
| 지표 | 정상 | 경고 | 즉시 롤백 |
|------|------|------|----------|
| **에러율** | < 1% | 1-5% | > 5% |
| **응답 시간** | < 500ms | 500-1000ms | > 1000ms |
| **CPU** | < 70% | 70-90% | > 90% |
| **Memory** | < 80% | 80-95% | > 95% |

**Grafana 대시보드:**
- Canary vs Stable 비교 그래프
- 실시간 에러율 모니터링
- 응답 시간 P50, P95, P99

**교훈:**
- **데이터 기반 의사결정**
- 사람의 감이 아닌 **메트릭 기반 판단**

---

## 🚧 개선 계획

### 1. 자동 Analysis 도입

```yaml
# AnalysisTemplate 정의
apiVersion: argoproj.io/v1alpha1
kind: AnalysisTemplate
metadata:
  name: error-rate-analysis
spec:
  metrics:
    - name: error-rate
      interval: 30s
      successCondition: result < 0.05  # 5% 이하
      failureLimit: 3  # 3회 실패 시 롤백
      provider:
        prometheus:
          address: http://prometheus:9090
          query: |
            sum(rate(http_requests_total{status=~"5..",job="was"}[1m]))
            /
            sum(rate(http_requests_total{job="was"}[1m]))
```

---

### 2. Blue-Green 배포 추가

특정 상황에서는 Blue-Green이 더 유용할 수 있습니다.

| 배포 방식 | 사용 시기 |
|----------|----------|
| **Canary** | 일반 배포 (점진적 검증) |
| **Blue-Green** | 대규모 변경 (DB 스키마 변경 등) |
| **Rolling Update** | 긴급 패치 (빠른 배포) |

---

## 📚 관련 문서

- [Argo Rollouts Documentation](https://argo-rollouts.readthedocs.io/)
- [Progressive Delivery](https://www.weave.works/blog/what-is-progressive-delivery)
- [Canary vs Blue-Green](https://martinfowler.com/bliki/BlueGreenDeployment.html)
- [Argo Rollouts Canary 아키텍처 설명](https://github.com/wlals2/bespin-project/blob/main/docs/operations/argo-rollouts-canary-architecture-explained.md)
- [Canary Production Readiness Checklist](https://github.com/wlals2/bespin-project/blob/main/docs/operations/canary-production-readiness-checklist.md)

---

**다음 읽기:**
- [Redis Session Clustering](./redis-session.md)
- [Multi-Cloud DR 아키텍처](./dr-architecture.md)
