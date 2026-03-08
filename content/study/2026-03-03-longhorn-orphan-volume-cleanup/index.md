---
title: "Longhorn 디스크 88% → 38%로 줄인 방법 — Orphan 볼륨 정리와 자동화"
date: 2026-03-03T14:00:00+09:00
categories: ["study", "Kubernetes"]
tags: ["longhorn", "kubernetes", "pv", "pvc", "orphan", "storage", "cleanup", "automation"]
summary: "Kubernetes Longhorn 스토리지에서 orphan 볼륨 202Gi를 발견하고 정리한 과정. PV/PVC 생명주기 이해부터 자동 감지 스크립트, 재발 방지 정책까지."
showtoc: true
tocopen: true
draft: false
---

## 왜 디스크가 88%였는가

Grafana 대시보드에서 worker2 노드의 Longhorn 디스크 사용률이 88%를 찍고 있었다.

```
Before (2026-03-03 정리 전):
  worker1: 52% (여유 93Gi / 전체 196Gi)
  worker2: 88% (여유 17Gi / 전체 147Gi)  ← 위험!
  worker3: 2%  (여유 95Gi / 전체 98Gi)
```

worker2가 88%면 새 PVC를 할당할 수 없다. 스토리지 부족으로 Pod 스케줄링이 실패하기 직전이었다.

원인을 추적해보니 **아무도 사용하지 않는 볼륨이 142Gi를 차지**하고 있었다.

---

## Orphan 볼륨이란

Kubernetes에서 데이터 저장 경로는 이렇다:

```
Pod → PVC(PersistentVolumeClaim) → PV(PersistentVolume) → Longhorn Volume(실제 디스크)

Pod:              "나에게 10Gi 스토리지를 달라"
PVC:              "이 요청에 맞는 볼륨을 찾거나 만들어라"
PV:               "실제 볼륨과 연결된 쿠버네티스 리소스"
Longhorn Volume:  "디스크에 실제로 쓰여진 데이터"
```

문제는 **역방향 삭제가 자동으로 일어나지 않을 때** 발생한다:

```
정상 삭제 (reclaimPolicy: Delete):
  Pod 삭제 → PVC 삭제 → PV 자동 삭제 → Longhorn Volume 자동 삭제 ✅

orphan 발생 (reclaimPolicy: Retain):
  Pod 삭제 → PVC 삭제 → PV "Released" 상태 → Longhorn Volume 남아있음 ❌
                          ↑ 여기서 멈춤!
```

`reclaimPolicy: Retain`은 **데이터 보호를 위해 PVC가 삭제되어도 볼륨을 유지**하는 정책이다. 데이터베이스처럼 중요한 데이터를 실수로 삭제하는 것을 방지한다.

하지만 **서비스 자체를 폐기한 후 볼륨을 수동으로 정리하지 않으면** orphan이 된다.

---

## 실제 상태 확인

### 17개 볼륨 중 12개가 orphan

```bash
### kubectl get volumes.longhorn.io -n longhorn-system
# 왜? Longhorn 볼륨의 실제 상태(attached/detached)를 확인
# 알 수 있는 것: 볼륨별 상태, 연결된 노드, 크기
# 주의: detached라고 무조건 orphan은 아님 (Pod이 없을 때 일시적으로 detached)

NAME                                       STATE      ROBUSTNESS   SCHEDULED   SIZE           NODE
pvc-0a6e1d3f-...   attached   healthy      True        2147483648     k8s-worker2   # ✅ 사용 중
pvc-13a79e46-...   attached   healthy      True        21474836480    k8s-worker2   # ✅ 사용 중
pvc-1c53f35c-...   detached   unknown      True        2147483648                   # ❌ orphan
pvc-2e8c9f1a-...   detached   unknown      True        10737418240                  # ❌ orphan
pvc-3ab12ef4-...   detached   unknown      True        53687091200                  # ❌ orphan
...
# 총 12개 detached/unknown
```

### orphan 식별 3단계

orphan 볼륨을 식별하는 방법:

```
Step 1: PV 상태 확인
  kubectl get pv | grep Released
  → "Released" = PVC가 삭제됐지만 PV가 남아있음

Step 2: PVC 존재 여부 확인
  kubectl get pvc -A | grep <volume-name>
  → 결과 없음 = PVC가 삭제된 상태 (orphan 확정)

Step 3: Longhorn Volume 상태 확인
  kubectl get volumes.longhorn.io -n longhorn-system | grep detached
  → detached + unknown = 아무 노드에도 연결 안 됨
```

세 가지 조건이 모두 맞으면 **orphan 확정**이다:
- PV가 Released (PVC와의 연결이 끊김)
- PVC가 존재하지 않음 (이미 삭제됨)
- Longhorn Volume이 detached/unknown (아무도 사용 안 함)

---

## 원인 분석: 왜 orphan이 생겼는가

12개 orphan 전부 Wazuh(SIEM) 관련 볼륨이었다.

```bash
### kubectl get pv --no-headers | grep Released | awk '{print $6}'
# 왜? Released PV가 어떤 namespace/PVC와 연결되어 있었는지 확인
# 알 수 있는 것: 원래 소속 namespace와 PVC 이름

# 출력:
security/wazuh-indexer-wazuh-indexer-0       # Wazuh Indexer 데이터
security/wazuh-indexer-wazuh-indexer-1       # Wazuh Indexer 데이터
security/wazuh-indexer-wazuh-indexer-2       # Wazuh Indexer 데이터
security/wazuh-manager-wazuh-manager-master-0  # Wazuh Manager
...
```

**근본 원인**: Wazuh는 **3번 재배포**되었다 (02/11 → 02/17 → 02/24). 매번 Helm uninstall + install로 재배포할 때마다:

1. `helm uninstall wazuh` → Pod, Service, StatefulSet 삭제
2. PVC는 `Retain` 정책으로 삭제 안 됨
3. `helm install wazuh` → 새 PVC 생성 → 새 Longhorn Volume 할당
4. 구 PVC 수동 삭제 → PV는 "Released"로 남음 → Longhorn Volume도 남음

```
세대별 볼륨 누적:
  1세대 (02/11): PVC 4개 → Longhorn Volume 4개  ← orphan!
  2세대 (02/17): PVC 4개 → Longhorn Volume 4개  ← orphan!
  3세대 (02/24): PVC 4개 → Longhorn Volume 4개  ← orphan!
  현재 (03/03):  Wazuh 완전 제거 상태

  3세대 × 4개 = 12개 orphan (약 142Gi)
```

추가로 **더 이상 사용하지 않는 local-path PVC** 2개도 발견했다:

| PVC | 크기 | 상태 |
|-----|------|------|
| prometheus-data-pvc | 50Gi | Prometheus가 Longhorn PVC로 전환 후 미사용 |
| grafana-data-pvc | 10Gi | Grafana가 emptyDir로 전환 후 미사용 |

---

## 정리 수행

### 안전 확인

삭제 전 반드시 확인해야 할 것:

```bash
### kubectl get pods -n security
# 왜? security namespace에 실행 중인 Pod이 있으면 볼륨을 사용 중일 수 있음

# 출력:
No resources found in security namespace.

### kubectl get pvc -n security
# 왜? PVC가 아직 존재하면 연결된 PV를 삭제하면 안 됨

# 출력:
No resources found in security namespace.
```

security namespace에 Pod도 PVC도 없다. 안전하게 삭제 가능.

### 삭제 실행

```bash
# 1. Released PV 삭제 (12개)
kubectl get pv --no-headers | grep Released | awk '{print $1}' | xargs kubectl delete pv
# 출력: persistentvolume "pvc-1c53f35c-..." deleted (×12)

# 2. Longhorn detached Volume 삭제 (12개)
kubectl get volumes.longhorn.io -n longhorn-system --no-headers | grep detached | \
  awk '{print $1}' | xargs kubectl delete volumes.longhorn.io -n longhorn-system
# 출력: volume.longhorn.io "pvc-1c53f35c-..." deleted (×12)

# 3. 미사용 local-path PVC 삭제 (2개)
kubectl delete pvc prometheus-data-pvc -n monitoring
kubectl delete pvc grafana-data-pvc -n monitoring
```

### 결과

```
After (2026-03-03 정리 후):
  worker1: 14% (여유 168Gi / 전체 196Gi)  ← Before: 52%
  worker2: 38% (여유 91Gi / 전체 147Gi)   ← Before: 88%
  worker3: 2%  (여유 95Gi / 전체 98Gi)    ← 변동 없음

회수한 디스크:
  Wazuh orphan 볼륨:    ~142Gi
  구 local-path PVC:    ~60Gi
  총 회수:              ~202Gi
```

**worker2가 88% → 38%**로 떨어졌다. 위험 구간에서 완전히 벗어났다.

---

## 재발 방지 정책

orphan 볼륨이 다시 쌓이지 않도록 3가지 방지책을 수립했다.

### 1. StorageClass reclaimPolicy 기준

| 워크로드 | reclaimPolicy | 이유 |
|---------|---------------|------|
| 실험/테스트 서비스 | `Delete` | 서비스 삭제 시 볼륨 자동 정리 |
| 프로덕션 DB (MySQL) | `Retain` | 실수 삭제 방지, 단 **폐기 시 수동 정리 필수** |
| 모니터링 (Prometheus) | `Delete` | 재생성 가능한 메트릭 데이터 |
| SIEM (Wazuh 등) | `Delete` 권장 | 재배포가 잦은 경우 orphan 누적 방지 |

**핵심 원칙**: `Retain` 정책을 사용하는 서비스를 **삭제할 때** 반드시 PV/PVC/Longhorn Volume까지 정리한다.

### 2. 서비스 삭제 시 체크리스트

```
서비스 삭제 후 반드시 확인:
  [ ] kubectl get pvc -n <namespace>       → 잔여 PVC 확인
  [ ] kubectl get pv | grep Released       → Released PV 확인
  [ ] kubectl get volumes.longhorn.io -n longhorn-system | grep detached
                                           → detached 볼륨 확인
  [ ] longhorn-cleanup.sh --dry-run        → 전체 진단
```

### 3. 정기 실행: longhorn-cleanup.sh

orphan 볼륨 자동 감지 스크립트를 작성했다. 4가지를 점검한다:

```bash
### ./longhorn-cleanup.sh --help
# Usage: ./longhorn-cleanup.sh [--dry-run] [--clean] [--help]
#
# 감지 항목:
#   [1] Released PV (PVC 삭제됐지만 볼륨 남아있음)
#   [2] Detached Longhorn Volume (아무 노드에도 연결 안 됨)
#   [3] Pod 미참조 PVC (PVC 존재하지만 어떤 Pod도 사용 안 함)
#   [4] 노드별 디스크 사용률
```

**실행 예시** (정리 후 정상 상태):

```bash
### ./longhorn-cleanup.sh
# 왜? 현재 스토리지 상태 전체 진단 (dry-run 기본)
# 알 수 있는 것: orphan PV/Volume/PVC, 노드별 사용률

# 출력:
========================================
 Longhorn Storage Cleanup  2026-03-03 14:30:00
 모드: DRY-RUN (삭제 안 함)
========================================

[1/4] Released PV (PVC 삭제됐지만 볼륨 남아있음)
---
  [정상] Released PV 없음

[2/4] Detached Longhorn Volume
---
  [정상] Detached 볼륨 없음

[3/4] Pod 미참조 PVC (PVC 존재하지만 어떤 Pod도 사용 안 함)
---
  [정상] 모든 PVC가 Pod에 의해 사용 중

[4/4] 노드별 Longhorn 디스크 사용률
---
  k8s-worker1: 14% (여유 168Gi / 전체 196Gi)
  k8s-worker2: 38% (여유 91Gi / 전체 147Gi)
  k8s-worker3: 2% (여유 95Gi / 전체 98Gi)

========================================
결과: 스토리지 정상 — orphan 볼륨 없음
========================================
```

orphan이 발견되면 `--clean` 옵션으로 삭제할 수 있다 (삭제 전 확인 프롬프트 있음).

**권장 운영 주기**: 서비스 삭제/재배포 후 반드시 1회 실행. 정기적으로 월 1회 실행.

---

## 배운 것

1. **`reclaimPolicy: Retain`은 안전장치이자 함정이다**
   - 데이터 보호라는 명확한 목적이 있지만, 서비스 폐기 시 수동 정리를 잊으면 디스크를 잠식한다
   - 특히 재배포가 잦은 서비스(Wazuh 3세대)에서 세대 × 볼륨 수만큼 누적됨

2. **orphan 식별은 3단계 크로스 체크가 필요하다**
   - PV 상태만 봐서는 모른다 (Released PV가 없어도 Longhorn에 detached 볼륨이 있을 수 있음)
   - PVC만 봐서는 모른다 (PVC가 있어도 Pod이 사용 안 하면 의미 없음)
   - PV + PVC + Longhorn Volume 세 가지를 교차 검증해야 정확함

3. **자동화 없이 수동 관리는 반드시 누수가 생긴다**
   - "나중에 정리해야지" → 3개월 뒤 88% → 정리 스크립트 필수
