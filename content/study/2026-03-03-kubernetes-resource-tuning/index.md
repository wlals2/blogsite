---
title: "Kubernetes 리소스 낭비 찾기 — VPA 41일 데이터로 WAS 메모리를 두 배 늘린 이야기"
date: 2026-03-03T16:00:00+09:00
categories: ["study", "Kubernetes"]
  - "study"
  - "Kubernetes"
tags: ["kubernetes", "vpa", "resource", "tuning", "prometheus", "etcd", "alertmanager", "argo-rollouts"]
summary: "kubectl top, Prometheus PromQL, VPA 세 가지 방법으로 홈랩 클러스터의 리소스 낭비를 찾고 조정한 과정. WAS CPU 10배 증가, etcd 메모리 5배 증가, 총 세 가지 컴포넌트 튜닝."
showtoc: true
tocopen: true
draft: false
## 왜 리소스를 점검했는가

Longhorn orphan 볼륨 202Gi를 정리한 날, 자연스럽게 다음 질문이 생겼다.

"**디스크만 낭비되는 게 아니라 CPU/메모리도 낭비되고 있지 않을까?**"

`kubectl describe nodes`를 실행하면 두 가지 수치가 보인다:

```bash
### kubectl describe node k8s-worker1 | grep -A 5 "Allocated resources"
# 왜? 노드별 예약량(Request) vs 실제 사용량 비교

# 출력:
Allocated resources:
  Resource           Requests      Limits
  cpu                3910m (97%)   12800m (320%)
  memory             5760Mi (40%)  10332Mi (72%)
```

CPU Requests가 97%인데 실제 사용량은?

```bash
### kubectl top node k8s-worker1
# 왜? 실제 CPU/메모리 사용량 확인 (스냅샷)

NAME          CPU(cores)   CPU%   MEMORY(bytes)   MEMORY%
k8s-worker1   250m         6%     3500Mi          24%
```

**CPU 97% 예약인데 실제는 6%.**

예약(Request)이 실제보다 16배 많다. 이게 문제인 이유는 두 가지다:

1. **스케줄링 불가**: Kubernetes는 Request 기준으로 Pod 배치 — 실제로 CPU가 남아도 Request가 꽉 찼으면 새 Pod 스케줄링 거부
2. **반대 위험도 존재**: Request가 실제보다 낮으면 OOM → Pod 강제 종료

그래서 양방향으로 점검이 필요하다. **Request > 실제** (낭비) 와 **Request < 실제** (OOM 위험) 모두.

---

## 리소스 이상 탐지 방법 3가지

### 방법 1: `kubectl top` (즉시 확인, 스냅샷)

```bash
kubectl top pods -n blog-system
kubectl top pods -n monitoring
kubectl top nodes
```

**특징**:
- cAdvisor(kubelet 내장)가 15초마다 수집한 데이터를 조회
- 실행 시점의 스냅샷 — 1분 전 스파이크는 보이지 않음
- 빠르고 간단하지만 정확도 낮음

**언제 쓰는가**: 지금 당장 대략적인 상태를 보고 싶을 때

### 방법 2: Prometheus PromQL (7일 평균)

```promql
# 7일 평균 CPU 사용량 vs Request 비율
sum(rate(container_cpu_usage_seconds_total{namespace="blog-system"}[7d])) by (pod)
  /
sum(kube_pod_container_resource_requests{namespace="blog-system", resource="cpu"}) by (pod)
```

**특징**:
- 7일, 30일 평균으로 스파이크를 포함한 실제 패턴 파악
- Request/Limit 대비 비율 계산 가능
- 수식을 직접 작성해야 함

**언제 쓰는가**: 트래픽 패턴이 없는 모니터링 스택(Prometheus, Grafana, AlertManager)처럼 정적 서비스 최적화 시

### 방법 3: VPA (Vertical Pod Autoscaler) Off 모드

VPA는 일반적으로 "자동 스케일링" 도구로 알려져 있지만, **Off 모드에서는 권장값만 계산하고 Pod를 건드리지 않는다**.

```bash
### kubectl get vpa -n blog-system
# 왜? VPA가 계산한 권장 Request 값 확인

NAME      MODE   CPU   MEM       PROVIDED   AGE
was-vpa   Off    100m  524288k   True       41d
web-vpa   Off    10m   262144k   True       41d
```

41일간 실제 사용량을 관찰해서 도출한 권장값이다.

**특징**:
- 히스토리가 길수록 신뢰도 높음 (41일 = 매우 높음)
- 단일 스냅샷이나 평균이 아닌 **통계적 권장값** (99th percentile 기반)
- 자동 적용 없이 권장값만 확인 가능 (Off 모드)

**언제 쓰는가**: 트래픽 변동이 있는 애플리케이션 서버 (WAS, WEB) 최적화 시

---

## 실제 발견한 문제들

### 문제 1: WAS — Request가 실제보다 낮아 OOM 위험

```bash
### kubectl top pod -n blog-system -l app=was
# 왜? WAS Pod 실제 메모리 사용량 확인

NAME            CPU(cores)   MEMORY(bytes)
was-xxx-yyy     67m          557Mi
```

```bash
### kubectl get vpa was-vpa -n blog-system -o yaml | grep -A 20 recommendation
# 왜? VPA 41일 데이터 기반 권장값 확인

    containerRecommendations:
    - containerName: spring-boot
      target:
        cpu: 100m
        memory: 524288k  # ≒ 560Mi
```

**현황**:

| 항목 | 기존 Request | VPA 권장 | 실제 사용량 | 위험도 |
|------|------------|----------|-----------|-------|
| CPU | 10m | 100m | ~67m | 과소 예약 (10배 차이) |
| Memory | 256Mi | 560Mi | ~557Mi | 🔴 OOM 위험! (실제 > Request) |

Spring Boot WAS는 JVM 특성상 시작 시 Heap을 확보한다. Memory Request가 실제 사용량(557Mi)보다 낮은 256Mi로 설정되어 있으면 → OOM Killer가 언제든 Pod를 강제 종료할 수 있다.

### 문제 2: etcd — Request가 실제의 9분의 1

```bash
### kubectl top pod -n kube-system -l component=etcd
# 왜? Control Plane 핵심 컴포넌트 etcd 사용량 확인

NAME         CPU(cores)   MEMORY(bytes)
etcd-k8s-cp  12m          945Mi
```

etcd YAML의 설정값:

```yaml
# /etc/kubernetes/manifests/etcd.yaml
resources:
  requests:
    cpu: 100m
    memory: 100Mi  # 실제 945Mi인데 Request가 100Mi!
```

**945Mi를 사용하는데 Request가 100Mi.**

이게 왜 위험한가: etcd는 **Kubernetes 클러스터의 모든 상태를 저장**하는 핵심 컴포넌트다. OOM Killer가 etcd를 종료하면 클러스터 전체가 마비된다.

### 문제 3: AlertManager — Request가 실제의 4배

```bash
### kubectl top pod -n monitoring -l alertmanager=kube-prometheus-stack-alertmanager
# 왜? AlertManager 실제 사용량 확인

NAME              CPU(cores)   MEMORY(bytes)
alertmanager-xxx  3m           33Mi
```

설정값: Memory Request 128Mi, 실제 33Mi → **4배 과다 예약**.

이 경우는 OOM 위험은 없지만, 불필요하게 128Mi를 예약해두어 다른 Pod 스케줄링을 방해한다.

---

## 조정 내용

### 1. WAS — Request 증가 (OOM 방지)

[was-rollout.yaml](../../../k8s-manifests/services/blog-system/was/was-rollout.yaml) 수정:

```yaml
# Istio proxy 메모리 annotation
sidecar.istio.io/proxyMemory: "128Mi"  # 40Mi → 128Mi (VPA 권장 121Mi 반영)

# Spring Boot 컨테이너 리소스
resources:
  requests:
    cpu: 100m     # 10m → 100m (VPA 권장 100m)
    memory: 600Mi # 256Mi → 600Mi (VPA 권장 560Mi + 여유 40Mi)
  limits:
    cpu: 500m     # 유지
    memory: 1Gi   # 유지
```

**배포 방식**: WAS는 Argo Rollouts Canary 전략 사용. Git push → ArgoCD Sync → Canary 20%→50%→80%→100% 자동 진행. **완전 무중단**.

```bash
### kubectl argo rollouts get rollout was -n blog-system
# 왜? Canary 배포 진행 상태 확인

NAME    STRATEGY   STATUS        STEP  SET-WEIGHT  READY  DESIRED  UP-TO-DATE  AVAILABLE
was     Canary     ✔ Healthy     6/6   100         2/2    2        2           2
```

6단계 Canary 완료, 새 Pod에 cpu=100m/memory=600Mi 적용 확인.

### 2. etcd — Request 증가 (Control Plane 안정화)

etcd는 Static Pod로 `/etc/kubernetes/manifests/etcd.yaml`을 kubelet이 직접 관리한다. ArgoCD 관리 밖이므로 직접 수정.

```yaml
resources:
  requests:
    cpu: 100m
    memory: 500Mi  # 100Mi → 500Mi (실제 945Mi의 절반 — 보수적 증가)
```

```bash
### kubectl get pod -n kube-system -l component=etcd
# 왜? etcd 재시작 후 Running 상태 확인

NAME         READY   STATUS    RESTARTS   AGE
etcd-k8s-cp  1/1     Running   0          2m
```

**주의**: etcd YAML 수정 후 kubelet이 약 30초 재시작. 그동안 kubectl 명령어가 응답하지 않는다. 서비스(blog)는 영향 없음 — kube-apiserver와 etcd가 잠시 재시작하는 것이지, 이미 실행 중인 Pod는 계속 동작한다.

### 3. AlertManager — Request 감소 (예약 낭비 제거)

[kube-prometheus-stack/values.yaml](../../../k8s-manifests/apps/kube-prometheus-stack/values.yaml) 수정:

```yaml
alertmanagerSpec:
  resources:
    requests:
      cpu: 10m      # 100m → 10m
      memory: 64Mi  # 128Mi → 64Mi (실제 33Mi의 2배)
    limits:
      cpu: 200m     # 유지
      memory: 128Mi # 256Mi → 128Mi
```

---

## 결과

```
Before (2026-03-03 조정 전):
  WAS spring-boot:   cpu=10m,    memory=256Mi  ← OOM 위험 (실제 557Mi)
  WAS istio-proxy:   proxyMemory=40Mi
  etcd:              memory=100Mi              ← 클러스터 마비 위험 (실제 945Mi)
  AlertManager:      memory=128Mi              ← 4배 낭비 (실제 33Mi)

After (2026-03-03 조정 후):
  WAS spring-boot:   cpu=100m,   memory=600Mi  ✅ OOM 위험 해소
  WAS istio-proxy:   proxyMemory=128Mi         ✅ 실제 사용량(121Mi) 반영
  etcd:              memory=500Mi              ✅ 보수적 증가 (추가 조정 가능)
  AlertManager:      memory=64Mi               ✅ 낭비 제거 (실제의 2배로 조정)
```

| 컴포넌트 | 변경 방향 | 이유 |
|----------|----------|------|
| WAS CPU | 10m → 100m (10배 증가) | 과소 예약 — 실제 67m인데 10m으로 설정 |
| WAS Memory | 256Mi → 600Mi (2.3배 증가) | OOM 위험 — 실제 557Mi > Request 256Mi |
| etcd Memory | 100Mi → 500Mi (5배 증가) | 클러스터 마비 위험 — 실제 945Mi > Request 100Mi |
| AlertManager Memory | 128Mi → 64Mi (50% 감소) | 낭비 — 실제 33Mi인데 128Mi 예약 |

---

## 배운 것

1. **Request와 실제 사용량은 반드시 교차 검증해야 한다**
   - Request가 실제보다 낮으면: OOM → Pod 강제 종료 (WAS, etcd 위험)
   - Request가 실제보다 높으면: 스케줄링 블로킹 → 노드 공간 낭비 (AlertManager)
   - 둘 다 위험하지만 방향이 반대다

2. **VPA Off 모드는 자동 스케일링이 아닌 "정보 수집 도구"다**
   - 41일 데이터 = 높은 신뢰도의 통계적 권장값
   - Pod를 건드리지 않으니 부담 없이 사용 가능
   - 트래픽 변동이 큰 애플리케이션에 특히 유용

3. **etcd는 Static Pod라서 ArgoCD 관리 밖이다**
   - `/etc/kubernetes/manifests/` 파일을 kubelet이 직접 감시
   - GitOps 관리가 안 되는 예외 영역 — 직접 수정 후 별도 기록 필요

4. **"낭비"와 "위험" 모두 잡아야 한다**
   - WAS와 etcd: 조정 방향이 "증가" (낮아서 위험)
   - AlertManager: 조정 방향이 "감소" (높아서 낭비)
   - 같은 "리소스 조정"이지만 방향이 반대
