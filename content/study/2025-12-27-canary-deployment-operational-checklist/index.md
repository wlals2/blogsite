---
title: "Canary 배포 운영 체크리스트 완벽 가이드"
date: 2025-12-27
tags: ["Canary Deployment", "Argo Rollouts", "Kubernetes", "DevOps", "Production"]
categories: ["CI/CD & GitOps"]
series: ["Canary 배포 완벽 가이드"]
description: "Production 환경에서 Canary 배포를 안전하게 수행하기 위한 완벽한 체크리스트를 만들었어요. 배포 전 준비부터 롤백 절차까지, 실전에서 검증된 모든 과정을 공유합니다."
showToc: true
draft: false
---

## 첫 Production 배포의 떨림

처음 Production 환경에 Canary 배포를 시도했을 때, 정말 떨렸어요. "혹시 실수하면 서비스가 멈추는 거 아닐까?" 하는 걱정에 밤잠을 설쳤죠.

그래서 만든 게 바로 이 체크리스트예요. **"모든 항목이 ✅이어야 다음 단계로 진행"**이라는 원칙으로, 15분짜리 배포를 35번 이상 성공시킨 검증된 가이드입니다.

## 체크리스트를 만든 이유

### 배포는 팀 작업이에요

혼자 배포하는 게 아니에요. DevOps 엔지니어, SRE, 개발자, 그리고 승인자까지 여러 역할이 협력해야 해요.

**문제는**:
- 각자 확인해야 할 게 다름
- 커뮤니케이션이 명확하지 않으면 놓치는 게 생김
- 긴급 상황에서 판단 기준이 애매함

**체크리스트의 힘**:
- 모든 팀원이 같은 기준으로 확인
- 놓치는 단계 없이 진행
- 롤백 조건이 명확함

## 배포 전 준비 (Pre-Deployment, 15분)

### 1. 인프라 상태 확인 (가장 중요!)

배포 전에 인프라가 건강해야 해요. 이미 문제가 있는 상태에서 배포하면 원인 파악이 어려워져요.

#### EKS Cluster 체크

```bash
# 노드 상태 확인
kubectl get nodes

# 예상 결과:
# NAME                                            STATUS   ROLES    AGE   VERSION
# ip-10-0-1-100.ap-northeast-2.compute.internal   Ready    <none>   5d    v1.28.x
# ip-10-0-2-200.ap-northeast-2.compute.internal   Ready    <none>   5d    v1.28.x
```

**체크리스트**:
- [ ] 모든 노드 STATUS: `Ready`
- [ ] 노드 개수: 최소 2개 이상 (Multi-AZ)
- [ ] Karpenter가 Running인가?

**경험담**: 한 번은 노드 하나가 `NotReady` 상태인 걸 모르고 배포했다가, Canary Pod가 계속 `Pending`에 걸려서 배포가 멈췄어요. 10분을 허비한 후에야 노드 문제를 발견했죠.

#### Pod 상태 확인

```bash
kubectl get pods -n petclinic

# 예상:
# NAME                   READY   STATUS    RESTARTS   AGE
# was-abc123-xxx         1/1     Running   0          2d
# was-abc123-yyy         1/1     Running   0          2d
# redis-master-0         1/1     Running   0          5d
```

**체크리스트**:
- [ ] 모든 Pod READY: `1/1`
- [ ] 모든 Pod STATUS: `Running`
- [ ] RESTARTS: 0 또는 낮은 숫자 (< 5)
- [ ] Redis Session Store Running인가?

**왜 RESTARTS를 확인하나요?**
RESTARTS가 5 이상이면 Pod가 불안정하다는 신호예요. 이런 상태에서 배포하면 Canary Pod도 불안정해질 가능성이 높아요.

#### Database 연결 테스트

```bash
# RDS 상태
aws rds describe-db-instances \
  --db-instance-identifier eks-3tier-dev-db \
  --query 'DBInstances[0].DBInstanceStatus' \
  --output text

# 예상: available

# Pod에서 DB 연결 테스트
kubectl run mysql-test --rm -i --restart=Never \
  --image=mysql:8.0 -n petclinic -- \
  mysql -h eks-3tier-dev-db.czgliwfs2orh.ap-northeast-2.rds.amazonaws.com \
  -u root -p'PASSWORD' -e "SELECT 1"
```

**체크리스트**:
- [ ] RDS Status: `available`
- [ ] DB 연결 테스트 성공
- [ ] DB Storage < 80% (용량 확인)

**실수 사례**: DB 용량이 90%인 상태에서 배포했다가, Canary Pod가 로그를 쓰지 못해 CrashLoopBackOff에 빠진 적이 있어요.

### 2. 모니터링 시스템 확인 (10분)

배포 중 문제를 발견하려면 모니터링이 필수예요.

#### Grafana 대시보드 확인

```bash
# Grafana Pod 상태
kubectl get pods -n monitoring -l app.kubernetes.io/name=grafana

# Grafana 접속
echo "https://www.goupang.shop/grafana"
```

**체크리스트**:
- [ ] Grafana Pod Running
- [ ] Grafana UI 접속 성공
- [ ] 주요 대시보드 데이터 표시 중:
  - [ ] Dashboard 001 - System Overview (K8s + Application)
  - [ ] Dashboard 002 - AWS Infrastructure (ALB, RDS)
  - [ ] Dashboard 003 - JVM Monitoring
  - [ ] Dashboard 007 - Session Monitoring (Redis Session)

**왜 Session Monitoring이 중요한가요?**
Redis Session이 죽으면 모든 사용자가 로그아웃돼요. 배포 중 Session Store 상태를 실시간으로 확인해야 해요.

#### Prometheus 메트릭 수집 확인

```bash
# Prometheus Pod 상태
kubectl get pods -n monitoring -l app.kubernetes.io/name=prometheus

# 메트릭 수집 확인
kubectl port-forward -n monitoring svc/kube-prometheus-prometheus 9090:9090 &

curl -s http://localhost:9090/api/v1/query?query=up | jq '.data.result | length'
# 예상: 20+ (수집 중인 타겟 개수)
```

**주요 메트릭**:
```promql
# WAS Pod CPU 사용률
rate(container_cpu_usage_seconds_total{namespace="petclinic",pod=~"was-.*"}[5m])

# WAS Pod Memory 사용률
container_memory_usage_bytes{namespace="petclinic",pod=~"was-.*"}

# HTTP 요청률
rate(http_server_requests_seconds_count{namespace="petclinic"}[5m])
```

### 3. 팀 준비 (5분)

**체크리스트**:
- [ ] 배포 담당자 준비 (Slack/Discord 온라인)
- [ ] 모니터링 담당자 준비 (Grafana 접속 중)
- [ ] 승인자 준비 (Promote/Abort 결정권자)
- [ ] 배포 예정 시간 팀 공지 (Slack)
- [ ] 롤백 계획 공유 (문제 시 즉시 Abort)

**Slack 메시지 예시**:
```
[배포 예정] WAS v1.0.1 → Production

⏰ 시간: 2025-12-27 14:30 (5분 후)
📦 이미지: petclinic-was:abc123
🚀 방식: Canary 배포 (10% → 25% → 50% → 75% → 100%)
⏱️ 예상 소요: 15분

📊 모니터링: https://www.goupang.shop/grafana
🔄 롤백: 에러율 5% 초과 시 즉시 Abort

@devops-team @monitoring-team 준비 부탁드립니다!
```

## 배포 중 모니터링 (Deployment, 10분)

### 1. Rollout 시작 (5분)

#### ArgoCD Sync

```bash
# ArgoCD App Sync
argocd app sync petclinic-was

# 또는 Auto Sync 대기 (3분)
argocd app get petclinic-was
```

**체크리스트**:
- [ ] ArgoCD Sync 시작
- [ ] Rollout Revision 증가 확인

#### Canary Pod 생성 확인

```bash
# 실시간 모니터링
kubectl argo rollouts get rollout was -n petclinic --watch
```

**예상 출력**:
```
Name:            was
Namespace:       petclinic
Status:          ॥ Paused
Strategy:        Canary
  Step:          1/8
  SetWeight:     10
  ActualWeight:  10

Replicas:
  Desired:       2
  Current:       4  (2 stable + 2 canary)
  Updated:       2
  Ready:         4
  Available:     4
```

**체크리스트**:
- [ ] Canary Pod 2개 생성됨
- [ ] Canary Pod STATUS: `Running`
- [ ] Canary Pod READY: `1/1`
- [ ] ActualWeight: 10% (첫 단계)

### 2. 각 단계별 체크 (Step 1~4, 각 1분)

#### Step 1: 10% Canary

**대기 시간**: 30초

**체크리스트**:
- [ ] ALB 가중치 확인 (stable 90%, canary 10%)
- [ ] Canary Pod 로그 확인 (에러 없는지)
- [ ] Grafana 에러율 < 1%

**Canary Pod 로그 확인**:
```bash
kubectl logs -f $(kubectl get pods -n petclinic -l rollouts-pod-template-hash -o name | head -1) -n petclinic
```

**Grafana에서 확인할 메트릭**:
```promql
# 5분간 5xx 에러율
rate(http_server_requests_seconds_count{status=~"5..",namespace="petclinic"}[5m])
# 예상: < 0.01 (1% 미만)
```

#### Step 2: 25% Canary

**대기 시간**: 30초

**체크리스트**:
- [ ] ActualWeight: 25%
- [ ] ALB 가중치 변경 확인 (stable 75%, canary 25%)
- [ ] Canary Pod CPU/Memory 정상 범위

```bash
kubectl top pods -n petclinic -l rollouts-pod-template-hash
# CPU < 400m, Memory < 800Mi
```

**Grafana 응답 시간 확인**:
```promql
# P95 응답 시간
histogram_quantile(0.95, rate(http_server_requests_seconds_bucket{namespace="petclinic"}[5m]))
# 예상: < 1초
```

#### Step 3: 50% Canary

**대기 시간**: 30초

**체크리스트**:
- [ ] ActualWeight: 50%
- [ ] Canary와 Stable Pod 에러율 비교

**Canary vs Stable 에러율 비교**:
```promql
# Canary 에러율
rate(http_server_requests_seconds_count{status=~"5..",rollouts_pod_template_hash=~".*canary.*"}[5m])

# Stable 에러율
rate(http_server_requests_seconds_count{status=~"5..",rollouts_pod_template_hash=~".*stable.*"}[5m])
```

**핵심**: Canary ≤ Stable (에러율 동일 또는 낮아야 함)

#### Step 4: 75% Canary

**대기 시간**: 30초

**체크리스트**:
- [ ] ActualWeight: 75%
- [ ] 전체 에러율 < 1%
- [ ] Canary Pod 안정적 (RESTARTS: 0)

### 3. 최종 확인 (Promote 전)

**체크리스트**:
- [ ] 모든 Step에서 에러 없음
- [ ] Grafana 모든 메트릭 정상
- [ ] Canary Pod 로그에 에러 없음
- [ ] 사용자 신고 없음 (Slack 확인)
- [ ] **승인자 Promote 승인** ✅

**Slack에서 승인 요청**:
```
[Promote 승인 요청] WAS Canary 배포

✅ Step 1~4 모두 정상
✅ 에러율: 0.01% (정상)
✅ 응답 시간 P95: 450ms (정상)
✅ Canary Pod 안정적 (RESTARTS: 0)

@devops-lead Promote 승인 부탁드립니다!
```

## Promote 및 완료 (Post-Deployment, 10분)

### 1. Promote 실행

```bash
# 수동 Promote (즉시 100% Canary)
kubectl argo rollouts promote was -n petclinic
```

**확인**:
```bash
kubectl argo rollouts get rollout was -n petclinic

# Status: ✔ Healthy
# Images: xxx:abc123 (stable) ← Canary가 Stable로 승격
# Replicas: 2 (Canary Pod 삭제됨)
```

**체크리스트**:
- [ ] Rollout Status: `Healthy`
- [ ] Canary Pod 삭제됨 (replicas: 2)
- [ ] 새 이미지가 Stable로 승격
- [ ] ALB 가중치: stable 100%

### 2. 최종 검증 (10분)

#### 서비스 동작 확인

```bash
# HTTP 응답 확인
for i in {1..10}; do
  curl -s https://www.goupang.shop/actuator/info | jq -r '.build.version'
done

# 모두 새 버전 (1.0.1)인지 확인
```

**체크리스트**:
- [ ] 모든 요청이 새 버전 응답
- [ ] HTTPS 접속 정상
- [ ] 로그인 테스트 성공 (Session 유지)
- [ ] 주요 기능 테스트 (Pet 등록, 수정, 삭제)

#### Grafana 대시보드 15분 모니터링

**체크리스트**:
- [ ] HTTP 요청률 정상 (배포 전과 유사)
- [ ] 에러율 < 1%
- [ ] 응답 시간 P95 < 1초
- [ ] CPU 사용률 < 80%
- [ ] Memory 사용률 < 80%
- [ ] Pod RESTARTS: 0

### 3. 배포 완료 공지

**Slack 메시지 템플릿**:
```
[배포 완료] WAS v1.0.1 → Production

✅ 배포 시간: 2025-12-27 14:30 ~ 14:45 (15분)
✅ Canary 단계: 10% → 25% → 50% → 75% → 100%
✅ 에러율: 0.01% (정상)
✅ 응답 시간 P95: 450ms (정상)
✅ 주요 변경 사항:
  - 세션 관리 개선 (Redis Clustering)
  - API 응답 속도 최적화

📊 모니터링: https://www.goupang.shop/grafana
📦 이미지: 339713018679.dkr.ecr.ap-northeast-2.amazonaws.com/petclinic-was:abc123
```

## 롤백 절차 (Rollback)

### 언제 롤백하나?

**즉시 롤백 조건** (승인 불필요):
- [ ] Canary 에러율 > 5% (5분간)
- [ ] Canary Pod CrashLoopBackOff
- [ ] 응답 시간 P95 > 5초 (평소의 5배 이상)
- [ ] 사용자 신고 급증 (Slack에서 3건 이상)
- [ ] 치명적 버그 발견 (데이터 손실, 보안 이슈)

### 즉시 롤백 (Abort)

```bash
# 모든 Canary 트래픽 중단
kubectl argo rollouts abort was -n petclinic
```

**확인**:
```bash
kubectl argo rollouts get rollout was -n petclinic

# Status: ॥ Degraded (Aborted)
# Images: xxx:old-version (stable) ← 이전 버전 유지
# Replicas: 2 (Canary Pod 즉시 삭제)
```

**동작**:
1. ALB 가중치 즉시 변경: stable 100%, canary 0% (1-2초)
2. Canary Pod 삭제 (5초)
3. Stable Pod 유지 (재배포 없음)

**체크리스트**:
- [ ] Abort 실행 완료
- [ ] Canary Pod 삭제 확인
- [ ] ALB 가중치 stable 100%
- [ ] 서비스 정상 응답 (이전 버전)

### 롤백 후 조치

**Slack 롤백 공지**:
```
[긴급 롤백] WAS v1.0.1 → v1.0.0

⚠️ 롤백 이유: Canary Pod 에러율 7% 초과
✅ 현재 버전: v1.0.0 (안정)
✅ 서비스 정상 운영 중

📋 후속 조치:
- 에러 로그 분석 진행 중
- 버그 수정 후 재배포 예정 (내일 14시)
```

**체크리스트**:
- [ ] 에러율 정상으로 복귀 확인
- [ ] Stable Pod만 2개 Running
- [ ] Slack 롤백 공지
- [ ] 에러 원인 분석 시작
- [ ] 버그 수정 계획 수립

## 일일/주간/월간 운영 가이드

### 아침 체크 (매일 09:00, 5분)

**체크리스트**:
- [ ] Pod 상태 확인 (모두 Running, RESTARTS < 5)
- [ ] Grafana 야간 메트릭 확인 (에러율 < 1%)
- [ ] ALB Target Health 모두 healthy
- [ ] RDS 상태 `available`

### 주간 체크 (매주 월요일, 30분)

**체크리스트**:
- [ ] Karpenter 노드 교체 로그 확인
- [ ] PDB (PodDisruptionBudget) 위반 없음
- [ ] Grafana 주간 리포트 확인
- [ ] ECR 이미지 정리 (30일 이상 미사용 이미지 삭제)

### 월간 체크 (매월 첫째 주, 2시간)

**체크리스트**:
- [ ] RDS Backup 정상 수행 확인
- [ ] DR 환경 Failover 테스트
- [ ] 모니터링 알림 규칙 업데이트
- [ ] 비용 분석 (OpenCost)

## 배운 점

### 1. 체크리스트의 힘

처음엔 "이렇게까지 해야 하나?" 싶었는데, 막상 사용해보니 **놓치는 게 하나도 없더라고요**. 특히 긴급 상황에서 체크리스트가 있으면 침착하게 대응할 수 있어요.

### 2. 팀 커뮤니케이션이 핵심

혼자 배포하는 게 아니에요. Slack으로 각 단계를 공유하고, 승인자의 OK를 받는 과정이 안전한 배포의 핵심이에요.

### 3. 모니터링 없이는 배포 불가

Grafana가 없었다면 Canary 에러율을 어떻게 확인했을까요? 모니터링이 준비되지 않은 상태에서는 절대 배포하지 마세요.

### 4. 롤백은 부끄러운 게 아니에요

35번 배포 중 3번 롤백했어요. 하지만 덕분에 서비스는 항상 안정적이었어요. **빠른 롤백이 최고의 대응**이에요.

## 마무리

이 체크리스트는 35번의 실전 배포를 거치며 계속 개선됐어요. 처음엔 10개 항목이었는데, 지금은 80개 이상이 됐죠.

**핵심 원칙**:
- ✅ 모든 항목이 체크되어야 다음 단계로
- ✅ 의심스러우면 롤백
- ✅ 팀과 실시간 소통
- ✅ 모니터링이 준비되지 않았다면 배포 금지

여러분만의 체크리스트를 만들어보세요. 한 번 만들어두면 평생 쓸 수 있는 자산이 됩니다! 🚀
