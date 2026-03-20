---
title: "Longhorn Volume이 Degraded가 되는 이유와 복구 워크플로우 — Replica, CSI 충돌 실전 기록"
date: 2026-03-21T10:00:00+09:00
categories:
  - study
  - Storage
tags: ["kubernetes", "longhorn", "replica", "csi", "degraded", "troubleshooting", "volume"]
summary: "Stopped Replica 28개, Degraded Volume 8개, CSI Plugin CrashLoopBackOff — 홈랩에서 겪은 Longhorn 볼륨 장애의 원인을 replica 동작 원리부터 설명하고 체계적인 복구 순서를 정리한다."
showtoc: true
tocopen: true
draft: false
---

> 이론 배경: [[Longhorn/Storage 시리즈 #1] Kubernetes에서 Volume이란 무엇인가](/study/2026-03-20-kubernetes-storage-volume-pv-pvc-csi/)

---

## 이날 실제로 겪은 일

Longhorn UI를 열었더니 볼륨 8개가 `degraded` 상태였다. CSI Plugin은 `CrashLoopBackOff`였고, 새 PVC를 만들어도 `Pending`에서 벗어나지 못했다.

```bash
kubectl get pods -n longhorn-system | grep -v Running
# longhorn-csi-plugin-bb6t8    2/3   CrashLoopBackOff   37   57d

kubectl get volumes.longhorn.io -n longhorn-system
# NAME          STATE      ROBUSTNESS   SIZE
# pvc-aaa...    attached   degraded     10Gi
# pvc-bbb...    attached   degraded     20Gi
# pvc-ccc...    attached   degraded     5Gi
# ... (8개)

kubectl get replicas.longhorn.io -n longhorn-system | grep stopped | wc -l
# 28
```

Stopped Replica 28개. Degraded Volume 8개. CSI Plugin 비정상.

---

## 왜 이런 일이 일어나는가

### Longhorn Volume의 상태 모델

Longhorn Volume은 `robustness`라는 상태값으로 건강도를 표시한다.

| Robustness | 의미 | 데이터 안전성 |
|------------|------|------------|
| `healthy` | 모든 replica가 정상 동기화됨 | ✅ 완전 보호 |
| `degraded` | replica가 부족하거나 일부 비정상 | ⚠️ 일부만 보호 |
| `faulted` | 모든 replica가 실패 | ❌ 데이터 손실 위험 |

`degraded`는 "지금 당장 데이터가 손실된 건 아니지만, 노드 1개가 더 죽으면 위험한 상태"다.

### Replica가 'stopped' 되는 3가지 원인

**원인 1: numberOfReplicas > 실제 노드 수**

Longhorn의 Anti-Affinity 정책은 같은 볼륨의 replica를 동일 노드에 2개 이상 두지 않는다. 노드가 2개인데 `numberOfReplicas: 3`이면 3번째 replica를 만들 수가 없다.

```
Worker1: Replica A ✅
Worker2: Replica B ✅
???:     Replica C ❌ (만들 노드 없음)
→ Volume degraded
```

**원인 2: 노드 디스크 재구성 (UUID 변경)**

디스크를 교체하거나 LVM을 재구성하면 `/var/lib/longhorn/longhorn-disk.cfg`의 UUID가 변경된다. Longhorn CRD에 저장된 이전 UUID와 맞지 않아 해당 노드의 replica가 전부 stopped된다.

**원인 3: 노드 장기 NotReady (볼륨 detach)**

노드가 NotReady 상태가 지속되면 Longhorn이 해당 노드의 volume을 강제 detach한다. detach된 볼륨의 replica가 재연결되지 못하면 stopped 상태로 남는다.

> - healthy → degraded: replica 부족/UUID mismatch/노드 NotReady
> - degraded → healthy: replica 재생성 + 동기화 완료
> - degraded → faulted: 남은 replica마저 실패

### CSI Plugin이 CrashLoopBackOff가 되는 이유

CSI Plugin은 Longhorn Manager에게 볼륨 상태를 주기적으로 쿼리한다. 볼륨이 `degraded` 상태에서 새 replica를 계속 만들려다 실패하면, Longhorn Manager가 CSI Plugin의 연결 요청에 `context deadline exceeded`를 반환하기 시작한다.

```bash
kubectl logs -n longhorn-system longhorn-csi-plugin-xxx --all-containers --tail=10
# E Failed to establish connection to CSI driver: context deadline exceeded
# E Failed to establish connection to CSI driver: context deadline exceeded
# (반복)
```

즉 **CSI Plugin 자체의 문제가 아니라 Longhorn Manager가 과부하 상태**인 것이다. Volume degraded → replica 생성 반복 실패 → Manager 과부하 → CSI timeout → CrashLoopBackOff 순서로 이어진다.

---

## 복구 워크플로우

복구 순서가 중요하다. CSI를 먼저 고치려 하면 근본 원인인 replica 문제가 남아있어 반복된다.

```
① 원인 파악 → ② replica 수 조정 → ③ stopped replica 정리
→ ④ degraded volume 복구 확인 → ⑤ CSI 자동 복구 확인
```

### ① 원인 파악

```bash
# 현재 replica 설정 확인
kubectl get storageclass longhorn -o jsonpath='{.parameters.numberOfReplicas}'
# 3

# 실제 스케줄 가능한 노드 수 확인
kubectl get nodes.longhorn.io -n longhorn-system
# NAME           READY   ALLOWSCHEDULING   SCHEDULABLE
# k8s-worker1    True    True              True
# k8s-worker2    True    True              True
# k8s-worker3    True    True              False  ← AllowScheduling이 false!
```

Worker3가 AllowScheduling=false였다. 실제로 replica를 받을 수 있는 노드가 2개인데 numberOfReplicas가 3 → 근본 원인 확인.

### ② replica 수를 노드 수에 맞게 조정

기존 볼륨들의 replica 수를 2로 낮춘다.

```bash
# 모든 degraded volume의 replica 수 일괄 변경
kubectl get volumes.longhorn.io -n longhorn-system \
  -o jsonpath='{range .items[?(@.status.robustness=="degraded")]}{.metadata.name}{"\n"}{end}' \
  | while read vol; do
    kubectl patch volume $vol -n longhorn-system \
      --type merge -p '{"spec":{"numberOfReplicas":2}}'
    echo "Patched: $vol"
  done

# StorageClass도 변경 (새 PVC에 적용)
kubectl patch storageclass longhorn \
  -p '{"parameters":{"numberOfReplicas":"2"}}'
```

### ③ stopped replica 정리

Stopped replica가 디스크를 차지하고 Longhorn Manager에 부하를 주고 있다.

```bash
# stopped replica 목록 확인
kubectl get replicas.longhorn.io -n longhorn-system \
  -o custom-columns=NAME:.metadata.name,VOLUME:.spec.volumeName,STATE:.status.currentState \
  | grep stopped

# 일괄 삭제
kubectl get replicas.longhorn.io -n longhorn-system \
  -o jsonpath='{range .items[?(@.status.currentState=="stopped")]}{.metadata.name}{"\n"}{end}' \
  | xargs -I{} kubectl delete replica {} -n longhorn-system
```

### ④ Volume 복구 확인

replica 수 조정 후 Longhorn이 자동으로 새 replica를 생성하고 동기화한다. 시간이 걸린다 (볼륨 크기와 네트워크 속도에 따라 수 분~수십 분).

```bash
# 상태 모니터링
watch kubectl get volumes.longhorn.io -n longhorn-system

# 목표:
# NAME          STATE      ROBUSTNESS
# pvc-aaa...    attached   healthy    ✅
# pvc-bbb...    attached   healthy    ✅
```

### ⑤ CSI 자동 복구 확인

Volume이 healthy 상태로 복구되면 CSI Plugin은 자동으로 정상화된다. 별도 작업 불필요.

```bash
kubectl get pods -n longhorn-system | grep csi-plugin
# longhorn-csi-plugin-xxx   3/3   Running   0   5m  ✅
```

---

## 추가: bind mount는 왜 안 되는가

디스크 공간을 늘리려고 다른 마운트 포인트를 Longhorn 디렉터리에 bind mount하는 방법을 시도했다가 replica stopped를 겪었다.

```bash
# ❌ 이 방법은 안 됨
sudo mount --bind /mnt/data /var/lib/longhorn
```

Longhorn은 `/var/lib/longhorn/longhorn-disk.cfg`에 디스크 UUID를 저장하고 이 UUID로 디스크를 식별한다. bind mount는 블록 디바이스 수준의 마운트가 아니라 디렉터리를 다른 경로에 재노출하는 것이다. Longhorn의 UUID 검증이 실제 파일시스템 UUID를 읽는 방식과 충돌한다.

**Longhorn 디스크 확장 올바른 방법**: LVM(`lvextend + resize2fs`) 또는 신규 디스크를 LVM VG에 추가(`vgextend`)해야 한다. 절대로 bind mount, symlink는 사용하지 않는다.

---

## 올바른 Longhorn 운영 워크플로우

### 신규 노드/디스크 추가 시

```bash
# 1. 새 디스크 LVM으로 추가
sudo pvcreate /dev/sdc
sudo vgextend vg_data /dev/sdc
sudo lvextend -l +100%FREE /dev/vg_data/lv_data
sudo resize2fs /dev/vg_data/lv_data

# 2. Longhorn이 자동으로 디스크 크기 확장 감지
kubectl get nodes.longhorn.io k8s-worker1 -n longhorn-system \
  -o jsonpath='{.status.diskStatus}' | python3 -m json.tool | grep storage
```

### numberOfReplicas 설정 원칙

| 스케줄 가능한 노드 수 | 권장 numberOfReplicas |
|-------------------|----------------------|
| 1 | 1 |
| 2 | 2 |
| 3 이상 | 2~3 (권장 3) |

**노드를 추가하면 numberOfReplicas도 함께 올려야 한다.** 노드 수가 replica 수보다 적어지는 상황이 되지 않도록 관리한다.

### 정기 상태 확인

```bash
# degraded/stopped 여부 한 번에 확인
kubectl get volumes.longhorn.io -n longhorn-system | grep -v healthy
kubectl get replicas.longhorn.io -n longhorn-system | grep stopped
kubectl get nodes.longhorn.io -n longhorn-system  # SCHEDULABLE 컬럼 확인
```

---

## 결과

| 항목 | Before | After |
|------|--------|-------|
| Degraded Volume | 8개 | 0개 |
| Stopped Replica | 28개 | 0개 |
| CSI Plugin 상태 | CrashLoopBackOff | 3/3 Running |
| numberOfReplicas | 3 (노드 수 초과) | 2 (노드 수에 맞춤) |

---

## 핵심 교훈 3가지

1. **numberOfReplicas ≤ 스케줄 가능한 노드 수** — 초과하면 replica 생성 실패 → degraded
2. **CSI Plugin CrashLoopBackOff의 근본 원인은 Longhorn Manager 과부하** — CSI를 재시작해도 해결 안 됨, Volume 상태를 먼저 고쳐야 함
3. **Longhorn 디스크 경로는 실제 마운트 포인트** — bind mount/symlink 불가, LVM으로만 확장
