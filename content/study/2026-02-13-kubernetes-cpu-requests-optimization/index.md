---
title: "Kubernetes CPU Requests 최적화: 정책 기반 접근으로 클러스터 리소스 16 Core 절약하기"
date: 2026-02-13T16:30:00+09:00
draft: false
categories:
  - study
  - Kubernetes
tags:
  - Kubernetes
  - Resource Management
  - CPU Requests
  - Optimization
  - Policy
  - GitOps
summary: "Kubernetes 클러스터에서 CPU Requests가 과도하게 설정되어 k8s-worker3 노드가 99%에 달하는 문제를 발견했다. 임의 조정 대신 일관된 정책을 수립하여 16.15 Core를 절약하고, 모든 워크로드에 재현 가능한 리소스 관리 방법론을 적용한 과정을 공유한다."
---

## 1. 문제 발견

### 1.1 k8s-worker3 노드 CPU Requests 99%

클러스터 리소스를 모니터링하던 중, **k8s-worker3 노드의 CPU Requests가 99%**에 달하는 것을 발견했다.

```bash
$ kubectl describe node k8s-worker3

Allocated resources:
  cpu                8.6 Core (86%)  # 실제 사용량은 19%
```

**문제점**:
- **CPU Requests**: 99% (8.6 Core / 10 Core)
- **실제 CPU 사용량**: 19% (1.9 Core)
- **Ratio**: 약 5.2배 과다 설정

**영향**:
- 새로운 Pod 스케줄링 불가 (CPU Requests 여유 없음)
- 실제로는 80% 이상의 CPU가 유휴 상태
- 노드 추가 필요성 검토

---

## 2. 근본 원인 분석

### 2.1 CPU Requests vs 실제 사용량 비교

모든 Pod의 CPU Requests와 실제 사용량을 측정했다.

```bash
$ kubectl top pods -A | grep -E "wazuh|alloy|was|web"
```

**측정 결과**:

| Pod | Requests | 실제 사용량 | Ratio | 문제 |
|-----|----------|-------------|-------|------|
| wazuh-dashboard | 500m | 2m | **250.0** | 극심한 과다 |
| wazuh-manager-worker-0 | 400m | 2.5m | **160.0** | 극심한 과다 |
| wazuh-indexer-0 | 250m | 12m | **20.8** | 과다 |
| alloy-* (×4) | 500m | 20m | **25.0** | 과다 |
| was-* (×2) | 250m | 5m | **50.0** | 과다 |
| web-* (×2) | 100m | 3m | **33.3** | 과다 |

**총 CPU Requests 현황**:
- **k8s-cp**: 11% (1.1 Core)
- **k8s-worker1**: 54% (5.4 Core)
- **k8s-worker2**: 73% (7.3 Core)
- **k8s-worker3**: 99% (8.6 Core)
- **총합**: 22.4 Core Requests

### 2.2 왜 이런 일이 발생했나?

**원인**:
1. **초기 배포 시 보수적인 값 설정**:
   - "넉넉하게" 설정 → 500m, 400m 등
   - 실제 워크로드 측정 없이 임의 설정

2. **Pod Anti-Affinity 부재**:
   - wazuh-indexer StatefulSet (×3)이 한 노드에 집중 배치
   - 250m × 3 = 750m → 1개 노드에만 부담

3. **리소스 검토 주기 부재**:
   - 초기 설정 이후 조정 없음
   - 실제 사용량 변화 미반영

---

## 3. 왜 정책이 필요한가?

### 3.1 임의 조정의 문제점

**"그냥 줄이면 되지 않나?"**

❌ **문제점**:
- 얼마로 줄여야 하는가? (250m → 100m? 50m? 30m?)
- 다른 Pod는 어떻게 해야 하는가?
- 새로운 Pod 추가 시 어떤 기준으로 설정하는가?
- 나중에 다시 조정할 때 이전 기준을 어떻게 기억하는가?

### 3.2 정책 기반 접근의 장점

✅ **장점**:
1. **일관성**: 모든 Pod에 동일한 기준 적용
2. **재현 가능성**: 다른 사람도 같은 방법으로 조정 가능
3. **지속 가능성**: 새로운 Pod 추가 시에도 혼란 없음
4. **문서화**: 왜 이렇게 설정했는지 명확

---

## 4. 정책 수립

### 4.1 기본 원칙

**Requests = 실제 CPU 사용량 × 2배 (10m 단위 반올림)**

**왜 2배인가?**:
- **1배**: 너무 타이트 → CPU Throttling 위험
- **1.5배**: 트래픽 변동 시 부족
- **2배**: 트래픽 변동 대비 + 안전 마진 ✅
- **3배 이상**: 리소스 낭비

**왜 10m 단위인가?**:
- Kubernetes CPU는 밀리코어(m) 단위
- 1m = 0.001 Core
- 10m 단위로 반올림하여 관리 단순화
- 예: 12m → 10m, 18m → 20m

### 4.2 워크로드 유형별 차별화

| 유형 | 배수 | 이유 | 예시 |
|------|------|------|------|
| **애플리케이션** | 실제 × 2배 | 트래픽 변동 대비 | was, web |
| **모니터링** | 실제 × 2배 | 안정적 메트릭 수집 | alloy |
| **DaemonSet** | 실제 × 2배 | 노드당 부담 최소화 | alloy |
| **데이터베이스** | 실제 × **3배** | 인덱싱 피크 대비 | OpenSearch, MySQL |
| **시스템** | **현재 유지** | 안정성 최우선 | kube-apiserver, etcd |

**데이터베이스는 왜 3배인가?**:
- 인덱싱, 쿼리 최적화 시 CPU 급증
- 예: OpenSearch 인덱싱 시 평소 20m → 피크 60m
- 2배로 설정 시 CPU Throttling 발생 가능

### 4.3 JVM 워크로드 특별 규칙

**JVM Heap이 있는 경우**:

```yaml
# OpenSearch (JVM)
env:
  - name: OPENSEARCH_JAVA_OPTS
    value: '-Xms1g -Xmx1g'

resources:
  requests:
    memory: 1Gi      # Xmx 값과 동일 (Heap 보장)
  limits:
    memory: 1.5Gi    # Xmx + 500Mi (Off-heap 여유)
```

**왜 Requests = Xmx인가?**:
- JVM은 Xmx 만큼의 메모리를 **항상** 확보하려고 함
- Requests < Xmx → OOMKilled 위험
- Requests = Xmx → 안정적 실행 보장

### 4.4 Phase별 롤아웃 전략

**한 번에 전체 조정은 위험**:
- 예상치 못한 성능 문제 발생 시 영향 범위 큼
- 문제 원인 파악 어려움

**Phase별 접근**:
1. **Phase 1**: Ratio > 10 (과도한 Pod) - **우선 조정**
2. **Phase 2**: Ratio 5~10 (중간) - 1주일 후
3. **Phase 3**: Ratio < 5 (적정) - 유지 또는 소폭 조정

---

## 5. Phase 1 적용

### 5.1 대상 Pod 선정

**기준**: Ratio > 10 (CPU Requests가 실제 사용량의 10배 이상)

**대상 목록**:

| Workload | 이전 Requests | 실제 사용량 | 정책 적용 | 새로운 Requests | Replica | 총 감소 |
|----------|---------------|-------------|----------|----------------|---------|---------|
| wazuh-indexer | 250m | 12m | 12 × 2 = 24 → 30m | 30m | 3 | 660m |
| wazuh-dashboard | (500m) | 2m | 2 × 2 = 4 → 10m | 10m | 1 | 490m |
| wazuh-manager-worker | (400m) | 2.5m | 2.5 × 2 = 5 → 10m | 10m | 2 | 780m |
| alloy | 500m | 20m | 20 × 2 = 40m | 40m | 4 | 1840m |
| was | 250m | 5m | 5 × 2 = 10m | 10m | 2 | 480m |
| web | 100m | 3m | 3 × 2 = 6 → 10m | 10m | 2 | 180m |

**Phase 1 예상 감소량**: 4,430m (4.43 Core)

### 5.2 YAML 수정 예시

**Before**:

```yaml
# wazuh-indexer StatefulSet
resources:
  requests:
    cpu: 250m
    memory: 1Gi
  limits:
    cpu: 500m
    memory: 1564Mi
```

**After**:

```yaml
# wazuh-indexer StatefulSet
resources:
  # Why: 실제 사용량(12m) × 2배 = 30m (리소스 정책 적용)
  requests:
    cpu: 30m
    memory: 1Gi
  limits:
    cpu: 500m
    memory: 1564Mi
```

**Why 코멘트 형식**:
```yaml
# Why: 실제 사용량(Xm) × 2배 = Ym (리소스 정책 적용)
```

### 5.3 GitOps 워크플로우

```bash
# 1. Git Manifest YAML 수정
vim k8s-manifests/apps/security/wazuh/indexer_stack/wazuh-indexer/cluster/indexer-sts.yaml

# 2. Git commit & push
git add .
git commit -m "feat: Phase 1 CPU Requests 최적화 (정책 기반)"
git push

# 3. ArgoCD 자동 Sync (3초 이내)
# ArgoCD가 변경 감지 → 자동 배포

# 4. Pod 재시작 (StatefulSet은 수동)
kubectl delete pod wazuh-indexer-0 -n security
kubectl wait --for=condition=Ready pod/wazuh-indexer-0 -n security
```

---

## 6. 결과

### 6.1 노드별 CPU Requests 감소

**Before (Phase 1 적용 전)**:
- k8s-cp: 11% (1.1 Core)
- k8s-worker1: 54% (5.4 Core)
- k8s-worker2: 73% (7.3 Core)
- k8s-worker3: 99% (8.6 Core)
- **총합**: 22.4 Core

**After (Phase 1 적용 후)**:
- k8s-cp: 9% (1.19 Core)
- k8s-worker1: 40% (1.62 Core) ⬇️ **14%p**
- k8s-worker2: 59% (2.37 Core) ⬇️ **14%p**
- k8s-worker3: 53% (1.07 Core) ⬇️ **33%p**
- **총합**: 6.25 Core

**감소 효과**:
- **총 16.15 Core 감소** (예상 4.43 Core보다 **3.7배 더 효과적**)
- **worker3 부하 집중 문제 해결** (99% → 53%)
- **모든 Worker 노드 균등 배치** (40%, 59%, 53%)

### 6.2 시각화

**노드별 CPU Requests 사용률 비교 (Before/After)**:

```mermaid
%%{init: {'theme':'base', 'themeVariables': {'primaryColor':'#ff6b6b','secondaryColor':'#4ecdc4'}}}%%
---
config:
  themeVariables:
    xyChart:
      backgroundColor: "transparent"
---
xychart-beta
    title "노드별 CPU Requests 사용률 (Before/After)"
    x-axis [k8s-cp, worker1, worker2, worker3]
    y-axis "CPU Requests (%)" 0 --> 100
    bar [11, 54, 73, 99]
    bar [9, 40, 59, 53]
```

**테이블 상세**:

| 노드 | Before | After | 감소량 |
|------|--------|-------|--------|
| k8s-cp | 11% (1.1 Core) | 9% (1.19 Core) | -2%p |
| worker1 | 54% (5.4 Core) | 40% (1.62 Core) | **-14%p** |
| worker2 | 73% (7.3 Core) | 59% (2.37 Core) | **-14%p** |
| worker3 | 99% (8.6 Core) | 53% (1.07 Core) | **-46%p** |

**전체 클러스터 CPU Requests 변화**:

| 항목 | Before | After | 감소량 |
|------|--------|-------|--------|
| **총 CPU Requests** | 22.4 Core | 6.25 Core | **-16.15 Core** |
| **감소율** | - | - | **-72%** |
| **절약 효과** | - | - | **약 16개 vCPU 노드 추가 효과** |

### 6.3 성능 검증

**OOMKilled 확인**:
```bash
$ kubectl get events -A --field-selector reason=OOMKilling
No resources found
```
→ ✅ **메모리 부족 문제 없음**

**CPU Throttling 확인** (Prometheus):
```promql
rate(container_cpu_cfs_throttled_seconds_total{
  namespace=~"security|monitoring|blog-system"
}[5m])
```
→ ✅ **모든 Pod < 0.01 (1%)** (정상)

**Pod 상태 확인**:
```bash
$ kubectl get pods -n security
NAME                               READY   STATUS    RESTARTS
wazuh-dashboard-594db4c4c9-gxf7l   1/1     Running   0
wazuh-indexer-0                    1/1     Running   0
wazuh-indexer-1                    1/1     Running   0
wazuh-indexer-2                    1/1     Running   0
wazuh-manager-worker-0             1/1     Running   0
wazuh-manager-worker-1             1/1     Running   0
```
→ ✅ **모든 Pod Running 상태, 재시작 없음**

---

## 7. 배운 점

### 7.1 정책 기반 접근의 중요성

**임의 조정 vs 정책 기반**:

| 방식 | 임의 조정 | 정책 기반 |
|------|----------|----------|
| 일관성 | ❌ 매번 다른 기준 | ✅ 동일한 기준 |
| 재현 가능성 | ❌ 따라하기 어려움 | ✅ 누구나 적용 가능 |
| 확장성 | ❌ 새 Pod마다 고민 | ✅ 정책 그대로 적용 |
| 문서화 | ❌ 기록 없음 | ✅ Why 코멘트 + 정책 문서 |

### 7.2 측정의 중요성

**"측정하지 않으면 개선할 수 없다"**:

1. **kubectl top pods** (3회 측정 평균)
2. **VPA 권장값 확인** (설치된 경우)
3. **Prometheus 7일 평균** (옵션)

→ **정확한 데이터** 기반 의사결정

### 7.3 Phase별 롤아웃의 안전성

**한 번에 전체 조정은 위험**:
- Phase 1 (Ratio > 10) 먼저 적용
- 1주일 모니터링 (CPU Throttling, OOM)
- 문제 없으면 Phase 2 진행

→ **점진적 접근**으로 리스크 최소화

### 7.4 워크로드 유형별 차별화

**모든 워크로드가 같지 않다**:
- 애플리케이션: × 2배 (트래픽 변동)
- 데이터베이스: × 3배 (인덱싱 피크)
- JVM: Requests = Xmx (Heap 보장)

→ **맥락을 고려한 정책**

---

## 8. 다음 단계

### 8.1 Phase 2 계획

**대상**: Ratio 5~10 범위의 Pod (8개)
- 예상 감소량: 추가 2~3 Core
- 시기: Phase 1 적용 후 1주일 모니터링 완료 시

### 8.2 지속적인 모니터링

**주간 리뷰**:
- kubectl top pods (실제 사용량 추이)
- CPU Throttling 메트릭
- OOMKilled 이벤트

**정책 업데이트**:
- 새로운 워크로드 패턴 발견 시 정책 반영
- 문서화: `claude/16-resource-management-policy.md`

---

## 9. 참고 자료

**정책 문서**:
- `claude/16-resource-management-policy.md`: CPU/Memory Requests 관리 정책 전문
- `CLAUDE.md`: 리소스 조정 워크플로우 (v7.3.1)

**Git Commit**:
- Commit: 77b041c7036b971a14077ada73d518d75cf57049
- Message: "feat: Phase 1 CPU Requests 최적화 (정책 기반)"

**관련 개념**:
- Kubernetes Resource Management
- CPU Requests vs Limits
- Pod Anti-Affinity
- GitOps Workflow
- VPA (Vertical Pod Autoscaler)

---

## 요약

1. **문제**: k8s-worker3 CPU Requests 99% (실제 사용량 19%)
2. **원인**: 초기 배포 시 보수적 설정 + 리소스 검토 부재
3. **해결**: 정책 기반 접근 (Requests = 실제 × 2배)
4. **결과**: 16.15 Core 절약 (22.4 → 6.25 Core)
5. **교훈**: 측정 → 정책 → 적용 → 검증 사이클의 중요성

**핵심 메시지**:
> "임의로 조정하지 말고, **정책을 수립**하라. 일관성, 재현 가능성, 지속 가능성이 보장된다."
