---
title: "Longhorn 다중 가상 디스크를 단일 LVM으로 재구성하며 만난 3가지 트러블슈팅"
date: 2026-03-20T10:00:00+09:00
description: "Kubernetes 노드에서 Longhorn이 사용하는 가상 디스크 3개(sdb+sdc+sdd)를 단일 LVM(sdb 100G)으로 재구성하는 과정에서 kubectl drain 플래그 변경, lvremove 'filesystem in use', Longhorn disk UUID mismatch 3가지 문제를 만나고 해결한 기록"
tags: ["longhorn", "lvm", "kubernetes", "storage", "troubleshooting", "homelab", "vmware", "disk"]
categories: ["study", "Storage"]
showtoc: true
tocopen: true
draft: false
---

> **이론 배경**: [LVM(Logical Volume Manager) 개념](https://man7.org/linux/man-pages/man8/lvm.8.html) — PV → VG → LV 계층 구조

---

## 배경 — 왜 단일 디스크로 통일해야 하는가

홈랩 Kubernetes의 worker3 노드에는 Longhorn 스토리지용 가상 디스크가 3개 붙어 있었다.

```
/dev/sdb  100G  ─┐
/dev/sdc   50G  ─┼─ vg_longhorn ─ lv_data (140G)
/dev/sdd   40G  ─┘
```

VMware에서 가상 디스크를 붙이다 보니 여러 개가 쌓인 것이다. 이 구성에는 두 가지 문제가 있다.

**문제 1: 장애 전파**
LVM은 여러 Physical Volume을 하나의 Volume Group으로 묶어서 마치 하나의 큰 디스크처럼 사용한다. 편리하지만 위험이 따른다. 3개 중 어느 디스크 하나가 죽으면 Volume Group 전체가 손상되어 Longhorn 볼륨이 모두 사라진다. "데이터를 3개 디스크에 분산해서 더 안전하다"는 착각과 정반대다.

**문제 2: 용량 증설의 어려움**
단일 디스크라면 VMware에서 디스크 크기를 늘린 뒤 `lvextend + resize2fs`로 간단히 확장할 수 있다. 다중 디스크 LVM은 어느 디스크를 늘려야 하는지 복잡하고, 특정 디스크에만 데이터가 몰려있으면 `pvmove`로 이동시켜야 하는 번거로움이 생긴다.

**목표**: 전 워커 노드를 `sdb 100G 단일 LVM` 표준으로 통일.

---

## Longhorn replica로 안전하게 작업하기

디스크를 날리는 작업이다 보니 데이터 손실이 가장 걱정됐다. Longhorn이 제공하는 replica 메커니즘이 이 문제를 해결해준다.

**Longhorn replica 동작 원리**:
- 각 볼륨은 설정된 `numberOfReplicas` 수만큼 다른 노드에 복사본을 유지한다
- 특정 노드의 디스크가 사라지더라도 다른 노드의 replica로 서비스가 유지된다
- 노드가 복구되면 자동으로 재동기화한다

즉, worker3의 디스크를 완전히 날리더라도 worker1·worker2에 replica가 살아있으면 데이터는 안전하다.

**작업 전 안전성 검증**:
```bash
kubectl get replicas.longhorn.io -n longhorn-system \
  -o custom-columns=VOLUME:.spec.volumeName,NODE:.spec.nodeID,STATE:.status.currentState \
  | sort

# 출력:
# pvc-0a1b2c3d   k8s-worker1   running
# pvc-0a1b2c3d   k8s-worker2   running
# pvc-1e2f3a4b   k8s-worker1   running
# pvc-1e2f3a4b   k8s-worker2   running
# ... (7개 볼륨 모두 worker1+worker2에 각 2개씩 running)
```

모든 볼륨의 replica가 worker3 외 다른 노드에 있음을 확인 후 작업을 시작했다.

---

## 전체 작업 흐름

```
① 안전성 검증 (replica 분포 확인)
      ↓
② kubectl drain (worker3 비우기)
      ↓
③ fstab 주석 → 재부팅 (LVM 안전 해제)
      ↓
④ LVM 전체 삭제 후 sdb 단일 재구성
      ↓
⑤ kubectl uncordon (노드 복귀)
      ↓
⑥ Longhorn disk 재등록 (UUID 갱신)
```

---

## 트러블슈팅 1: kubectl drain — v1.31에서 바뀐 플래그

워커 노드를 비우는 첫 단계부터 막혔다.

```bash
kubectl drain k8s-worker3 --ignore-daemonsets --delete-local-data
# error: unknown flag: --delete-local-data
```

검색해보니 `--delete-local-data`는 Kubernetes v1.20에서 deprecated되고 v1.25에서 완전히 제거됐다. 홈랩을 구축할 때 쓴 글들이 대부분 이전 버전 기준이라 이 함정을 자주 만난다.

**v1.31 올바른 플래그**:
```bash
kubectl drain k8s-worker3 \
  --ignore-daemonsets \
  --delete-emptydir-data

# 출력:
# node/k8s-worker3 cordoned
# Warning: ignoring DaemonSet-managed Pods: ...
# evicting pod blog-system/was-...
# evicting pod longhorn-system/instance-manager-...
# node/k8s-worker3 drained
```

`--delete-emptydir-data`로 바뀐 이유: 기존 이름 `emptyDir`이 실제 저장 타입을 더 명확하게 표현한다. `emptyDir`은 Pod가 사용하는 임시 디렉터리로, PVC와 달리 Pod가 사라지면 함께 사라진다. Prometheus, Loki 등이 캐시 용도로 이걸 사용한다. Drain 시 이 임시 데이터는 지워도 무방하다는 것을 명시적으로 허용하는 것이다.

> 팁: 에러 메시지를 그대로 읽으면 정답이 나온다. Kubernetes는 deprecated 명령 사용 시 대안을 에러 메시지에 직접 알려준다.

---

## 트러블슈팅 2: lvremove "filesystem in use" — 컨테이너 mount namespace의 함정

drain이 끝난 뒤 마운트 해제를 시도했다.

```bash
sudo umount /var/lib/longhorn
# (성공, 아무 출력 없음)

sudo lvremove /dev/vg_longhorn/lv_data
# Do you really want to remove active logical volume vg_longhorn/lv_data? [y/n]: y
# Logical volume vg_longhorn/lv_data contains a filesystem in use.
```

`umount`는 성공했는데 `lvremove`는 실패한다. 무언가가 아직 이 디바이스를 사용하고 있다는 뜻이다.

```bash
sudo dmsetup info vg_longhorn-lv_data
# ...
# Open count: 1  ← 누군가 열고 있음

sudo fuser /dev/mapper/vg_longhorn-lv_data
# (아무 출력 없음 — 프로세스 없음)

sudo lsof /dev/mapper/vg_longhorn-lv_data
# (아무 출력 없음)
```

`open count: 1`인데 `fuser`, `lsof`에는 아무것도 없다. 일반적인 프로세스 레벨에서는 안 보이는 상태다.

**원인: 컨테이너 mount namespace**

Linux는 각 컨테이너에 독립적인 **mount namespace**를 부여한다. 컨테이너가 시작될 때 `/var/lib/longhorn`을 자신의 namespace에 마운트하면, `umount`로 호스트의 마운트를 해제해도 컨테이너의 namespace에는 그 마운트가 남아있다. drain으로 Pod를 내보냈지만 커널 레벨에서 해당 namespace가 완전히 정리되지 않은 것이다.

```
호스트 namespace:    /var/lib/longhorn → 마운트 해제됨 ✅
컨테이너 namespace:  /var/lib/longhorn → 아직 열려있음 ❌ (kernel에 남음)
```

kubelet, containerd를 멈춰봤지만 해결되지 않았다. namespace는 커널이 들고 있으므로 서비스 재시작만으로는 정리되지 않는다.

**해결: fstab 주석 처리 후 재부팅**

```bash
# 재부팅 후 다시 마운트되지 않도록 fstab 수정
sudo sed -i 's|^/dev/vg_longhorn/lv_data|#/dev/vg_longhorn/lv_data|' /etc/fstab

sudo reboot
```

재부팅하면 모든 커널 namespace가 새로 시작되므로 이전 컨테이너가 남긴 흔적이 완전히 사라진다. 재부팅 후 `lvremove`가 즉시 성공했다.

> 교훈: `umount`가 성공해도 디바이스가 닫힌 게 아닐 수 있다. 컨테이너 환경에서는 mount namespace로 인해 커널 레벨에서 디바이스가 살아있을 수 있다. 확인은 `dmsetup info`의 `Open count`로.

---

## LVM 재구성 절차

재부팅 후 기존 LVM을 완전히 해체하고 sdb 단일로 재구성했다.

```bash
# 1. 기존 LV, VG, PV 제거
sudo lvremove /dev/vg_longhorn/lv_data   # y 입력
# Logical volume "lv_data" successfully removed.

sudo vgremove vg_longhorn
# Volume group "vg_longhorn" successfully removed.

sudo pvremove /dev/sdb /dev/sdc /dev/sdd
# Labels on physical volume "/dev/sdb" successfully wiped.
# Labels on physical volume "/dev/sdc" successfully wiped.
# Labels on physical volume "/dev/sdd" successfully wiped.

# 2. sdb 단일로 새 LVM 생성
sudo pvcreate /dev/sdb
# Physical volume "/dev/sdb" successfully created.

sudo vgcreate vg_data /dev/sdb
# Volume group "vg_data" successfully created.

sudo lvcreate -l 100%FREE -n lv_data vg_data
# Logical volume "lv_data" created.

sudo mkfs.ext4 /dev/vg_data/lv_data
# Creating filesystem with 26214400 4k blocks...

sudo mount /dev/vg_data/lv_data /var/lib/longhorn

# 3. fstab 업데이트 (재부팅 시 자동 마운트)
echo '/dev/vg_data/lv_data /var/lib/longhorn ext4 defaults 0 2' | sudo tee -a /etc/fstab

sudo reboot
```

재부팅 후 확인:
```bash
df -h | grep longhorn
# /dev/mapper/vg_data-lv_data   98G   61M   93G   1% /var/lib/longhorn
```

sdb 100G 단일 LVM이 정상 마운트됐다.

---

## 트러블슈팅 3: Longhorn Unschedulable — disk UUID mismatch

LVM 재구성 완료 후 `kubectl uncordon k8s-worker3`으로 노드를 복귀시켰다. 그런데 Longhorn UI에서 worker3이 `Unschedulable` 상태로 표시됐다. 새 볼륨 replica를 이 노드에 배치할 수 없는 상태다.

**원인 분석**:

Longhorn은 각 디스크를 고유 UUID로 식별한다. 이 UUID는 `/var/lib/longhorn/longhorn-disk.cfg`에 저장된다.

```bash
cat /var/lib/longhorn/longhorn-disk.cfg
# {"diskUUID":"825824d9-a1b2-c3d4-e5f6-789012345678","diskName":"default-disk-xxx"}
```

LVM을 날리고 다시 만들면 `longhorn-disk.cfg`도 함께 사라진다. 새 파일시스템에는 새 UUID가 생성된다. 그런데 Longhorn CRD(`nodes.longhorn.io`)의 `spec.disks`에는 이전 디스크 이름 `lvm-disk`가 그대로 남아있어서 UUID가 맞지 않는다는 에러가 발생한다.

```
longhorn-manager 로그:
"Disk data {filesystem /var/lib/longhorn/ true false 10737418240 []}
 is mismatched with collected data → UUID: 825824d9-..."
```

Longhorn UI에서 디스크를 삭제하는 버튼을 찾아봤지만, "Kubernetes node must be deleted first"라는 메시지만 나오고 디스크 삭제 옵션이 없었다. `kubectl patch`로 직접 처리해야 한다.

**해결 절차**:

```bash
# 1. 기존 lvm-disk 비활성화 (eviction 요청)
kubectl patch nodes.longhorn.io k8s-worker3 -n longhorn-system \
  --type=merge \
  -p='{"spec":{"disks":{"lvm-disk":{"allowScheduling":false,"evictionRequested":true}}}}'

# 2. lvm-disk 항목 완전 삭제
kubectl patch nodes.longhorn.io k8s-worker3 -n longhorn-system \
  --type=json \
  -p='[{"op": "remove", "path": "/spec/disks/lvm-disk"}]'

# 3. 새 UUID로 default-disk 등록
kubectl patch nodes.longhorn.io k8s-worker3 -n longhorn-system \
  --type=merge \
  -p='{"spec":{"disks":{"default-disk":{
    "allowScheduling":true,
    "diskType":"filesystem",
    "evictionRequested":false,
    "path":"/var/lib/longhorn/",
    "storageReserved":10737418240,
    "tags":[]
  }}}}'
```

**확인**:
```bash
kubectl get nodes.longhorn.io k8s-worker3 -n longhorn-system \
  -o jsonpath='{.status.diskStatus}' | python3 -m json.tool

# 출력 (핵심 부분):
# "default-disk-xxx": {
#   "diskUUID": "825824d9-...",  ← 새 UUID
#   "conditions": [
#     {"reason": "","status": "True","type": "Ready"},
#     {"reason": "","status": "True","type": "Schedulable"}
#   ],
#   "storageAvailable": 105195864064,  ← ~98G 정상
# }
```

`Ready: True`, `Schedulable: True` 확인. 정상화됐다.

> 교훈: Longhorn disk UUID는 `/var/lib/longhorn/longhorn-disk.cfg`에 저장된다. LVM이나 파일시스템을 재구성하면 이 파일이 사라지고 새 UUID가 생성된다. 이때 CRD의 spec.disks는 자동으로 업데이트되지 않으므로 수동으로 disk 삭제 → 재등록이 필요하다.

---

## 결과 — Before / After

| 항목 | Before | After |
|------|--------|-------|
| 디스크 구성 | sdb(100G) + sdc(50G) + sdd(40G) = 3개 | sdb(100G) 단일 |
| LVM 용량 | vg_longhorn 140G (비표준) | vg_data 100G (표준) |
| Longhorn storageAvailable | ~98G (측정값 오염) | 98G (정상) |
| Longhorn disk 상태 | Unschedulable (UUID mismatch) | Ready / Schedulable ✅ |
| 장애 전파 위험 | 3개 중 1개 실패 → VG 전체 손상 | 단일 디스크 (VMware 레벨에서 보호) |
| 용량 증설 방법 | pvmove + 복잡한 LVM 조작 | VMware 디스크 크기 확장 → lvextend 1단계 |

---

## 핵심 교훈 요약

| 상황 | 원인 | 핵심 해결책 |
|------|------|------------|
| `kubectl drain` 플래그 에러 | v1.31에서 `--delete-local-data` 제거 | `--delete-emptydir-data` 사용 |
| `lvremove` filesystem in use (umount 후에도) | 컨테이너 mount namespace가 커널에 남음 | fstab 주석 → 재부팅 |
| Longhorn Unschedulable (LVM 재구성 후) | `longhorn-disk.cfg` 삭제 → UUID 변경 → CRD mismatch | lvm-disk 삭제 + default-disk 재등록 |

---

## 다음 단계

worker3 재구성으로 절차가 검증됐다. 다음 세션에서 worker1(sdb 150G + sdc 50G → sdb 단일), worker2(sdb 97G + sdc 50G → sdb 단일) 순서로 동일 절차를 적용한다.

worker1, worker2는 sdb가 이미 150G, 97G이므로 100G보다 크다. 용량은 넉넉하니 그대로 sdb 단일 LVM으로 재구성하면 된다.
