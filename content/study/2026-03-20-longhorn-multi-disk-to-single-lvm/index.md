---
title: "홈랩 Longhorn 멀티 디스크를 단일 LVM으로 통합한 이유와 3가지 트러블슈팅"
date: 2026-03-20T14:00:00+09:00
categories:
  - study
  - Storage
tags: ["kubernetes", "longhorn", "lvm", "storage", "troubleshooting", "pvc"]
summary: "여러 디스크를 VG로 묶어 쓰던 Longhorn 환경을 단일 sdb LVM으로 재구성한 과정 — drain 플래그 오류, lvremove 'in use', disk UUID mismatch 3가지 트러블슈팅 기록"
showtoc: true
tocopen: true
draft: false
---

> 이론 배경: [[Longhorn/Storage 시리즈 #1] Kubernetes에서 Volume이란 무엇인가](/study/2026-03-20-kubernetes-storage-volume-pv-pvc-csi/)

---

## 왜 재구성이 필요했나

홈랩 K8s 클러스터를 구축하면서 워커 노드마다 디스크 구성이 달랐다.

| 노드 | 기존 구성 | 총 용량 |
|------|----------|--------|
| worker1 | sdb(150G) + sdc(50G) → vg_data(200G) | 200G |
| worker2 | sdb(97G) + ssc(50G) → vg_data(147G) | 147G |
| worker3 | sdb(100G) + sdc(50G) + sdd(40G) → vg_longhorn(190G) | 190G |

멀티 디스크 LVM 구성에는 두 가지 문제가 있었다.

**문제 1: 특정 디스크 장애 시 전체 LV 손상**

여러 디스크를 하나의 VG로 묶으면 그 중 하나만 죽어도 전체 VG가 손상된다. RAID가 아니라 단순 LVM stripes 구성이기 때문이다.

**문제 2: VMware에서 디스크 단위 확장이 불가능**

VMware에서는 가상 디스크 단위로 크기를 늘릴 수 있다. VG에 디스크가 여러 개 섞여 있으면 어느 디스크를 확장해야 하는지 복잡해지고, LVM 재구성이 필요해진다.

**목표**: 전 워커 노드를 `sdb 100G 단일 LVM`으로 통일 → VMware에서 `sdb` 하나만 확장하면 `lvextend + resize2fs`로 용량 증설 가능.

> - Before: 각 노드마다 다른 구성 (멀티 디스크 VG)
> - After: 전 노드 sdb 100G 단일 LVM 통일

---

## 작업 전 안전성 검증

worker3부터 시작하기 전에 먼저 모든 볼륨이 다른 노드에도 Replica가 있는지 확인했다. worker3을 작업하는 동안 데이터가 보호되는지 확인하기 위해서다.

```bash
kubectl get replicas.longhorn.io -n longhorn-system \
  -o custom-columns=VOLUME:.spec.volumeName,NODE:.spec.nodeID,STATE:.status.currentState \
  | sort

# 출력:
# VOLUME         NODE           STATE
# pvc-aaa...    k8s-worker1    running
# pvc-aaa...    k8s-worker2    running
# pvc-bbb...    k8s-worker1    running
# pvc-bbb...    k8s-worker2    running
# ...
# → 모든 볼륨이 worker1 + worker2에 각 2개씩 running → worker3 작업 중 데이터 손실 없음 ✅
```

---

## 트러블슈팅 1: kubectl drain 플래그 오류 (K8s v1.31)

작업 노드를 drain하려고 기존에 쓰던 명령어를 실행했다.

```bash
kubectl drain k8s-worker3 --ignore-daemonsets --delete-local-data

# 출력:
# error: unknown flag: --delete-local-data
```

**원인**: K8s v1.31에서 `--delete-local-data` 플래그가 제거됐다. 이전 버전 문서나 블로그를 참고하면 자주 만나는 오류다.

**해결**: 새 플래그로 교체

```bash
kubectl drain k8s-worker3 --ignore-daemonsets --delete-emptydir-data

# 출력:
# node/k8s-worker3 cordoned
# evicting pod longhorn-system/longhorn-xxx
# ...
# node/k8s-worker3 drained ✅
```

`--delete-emptydir-data`가 필요한 이유: Prometheus, Loki 같은 Pod가 PVC 외에 `emptyDir`(임시 캐시)도 사용한다. 이 플래그 없이는 "emptyDir가 있는 Pod가 있다"며 drain을 거부한다. PVC 데이터는 영향 없다.

---

## 트러블슈팅 2: lvremove "filesystem in use" — umount 성공 후에도

drain이 완료됐으니 LVM을 제거하려 했다.

```bash
sudo umount /var/lib/longhorn
# umount: 성공 (오류 없음)

sudo lvremove /dev/vg_longhorn/lv_data
# 출력:
# Do you really want to remove active logical volume vg_longhorn/lv_data? [y/n]: y
# Logical volume vg_longhorn/lv_data contains a filesystem in use.
```

`umount`는 성공했는데 `lvremove`는 실패했다. 이 상황이 처음엔 이해가 안 됐다.

**원인 파악**

```bash
sudo dmsetup info vg_longhorn-lv_data
# Open count: 1  ← 누군가 열고 있음

sudo lsof /dev/mapper/vg_longhorn-lv_data
# (아무것도 없음)
```

프로세스 목록엔 없는데 `Open count: 1`이다. 이것은 **컨테이너 mount namespace** 문제다.

drain 전에 실행 중이던 컨테이너들은 각자 독립된 mount namespace를 가진다. drain 후 Pod가 종료됐어도, 이미 열어둔 파일 디스크립터가 **커널 레벨의 namespace**에 남아있어 device가 여전히 "사용 중"으로 표시된다. `lsof`는 현재 프로세스만 보기 때문에 이것을 잡지 못한다.

**해결**: fstab에서 자동 마운트를 끄고 재부팅 → 재부팅 시 모든 namespace가 정리된다.

```bash
# 재부팅 후 자동 마운트 방지
sudo sed -i 's|^/dev/vg_longhorn/lv_data|#/dev/vg_longhorn/lv_data|' /etc/fstab

sudo reboot
```

재부팅 후:

```bash
sudo lvremove /dev/vg_longhorn/lv_data
# Logical volume "lv_data" successfully removed ✅
```

> - Host OS에서 umount 완료
> - 커널에 남은 컨테이너 namespace가 device를 참조 중
> - 재부팅으로 모든 namespace 정리

---

## LVM 재구성

LVM 제거가 완료됐으니 `sdb` 단일 LVM으로 새로 구성했다.

```bash
# 기존 LVM 완전 제거
sudo vgremove vg_longhorn
sudo pvremove /dev/sdb /dev/sdc /dev/sdd

# sdb 단일로 새 LVM 생성
sudo pvcreate /dev/sdb
sudo vgcreate vg_data /dev/sdb
sudo lvcreate -l 100%FREE -n lv_data vg_data
sudo mkfs.ext4 /dev/vg_data/lv_data

# Longhorn 마운트
sudo mkdir -p /var/lib/longhorn
sudo mount /dev/vg_data/lv_data /var/lib/longhorn

# fstab 업데이트 (재부팅 시 자동 마운트)
echo '/dev/vg_data/lv_data /var/lib/longhorn ext4 defaults 0 2' | sudo tee -a /etc/fstab

# 재부팅으로 fstab 자동 마운트 검증
sudo reboot
```

재부팅 후 확인:

```bash
df -h | grep longhorn
# /dev/mapper/vg_data-lv_data   98G  1.1G  92G  2% /var/lib/longhorn ✅
```

---

## 트러블슈팅 3: Longhorn disk UUID mismatch → Unschedulable

LVM 재구성이 완료됐는데 Longhorn UI에서 worker3이 `Unschedulable` 상태였다.

```bash
kubectl get nodes.longhorn.io -n longhorn-system

# 출력:
# NAME           READY   ALLOWSCHEDULING   SCHEDULABLE   AGE
# k8s-worker1    True    True              True          45d
# k8s-worker2    True    True              True          45d
# k8s-worker3    True    True              False         45d  ← ❌
```

**원인 파악**

```bash
kubectl logs -n longhorn-system -l app=longhorn-manager | grep worker3 | tail -5

# 출력:
# "Disk data {filesystem /var/lib/longhorn/ true false 10737418240 []}
#  is mismatched with collected data → UUID: 825824d9-..."
```

LVM을 완전히 날리면 `/var/lib/longhorn/longhorn-disk.cfg` 파일도 함께 사라진다. 이 파일에 디스크 UUID가 저장되어 있다. 새 LVM을 만들면 새 UUID가 생기고, Longhorn CRD의 `spec.disks`에는 이전 UUID(`lvm-disk`)가 남아있어 mismatch가 발생한다.

> - LVM 재구성 전: longhorn-disk.cfg에 UUID-A 저장됨
> - LVM 재구성 후: 새 디스크 → UUID-B 생성
> - Longhorn CRD에는 UUID-A가 남아있음 → mismatch → Unschedulable

**해결**: 기존 disk 항목 비활성화 → 삭제 → 새 disk 등록

```bash
# Step 1: 기존 lvm-disk 스케줄링 비활성화
kubectl patch nodes.longhorn.io k8s-worker3 -n longhorn-system \
  --type=merge \
  -p='{"spec":{"disks":{"lvm-disk":{"allowScheduling":false,"evictionRequested":true}}}}'

# Step 2: 기존 lvm-disk 항목 삭제
kubectl patch nodes.longhorn.io k8s-worker3 -n longhorn-system \
  --type=json \
  -p='[{"op": "remove", "path": "/spec/disks/lvm-disk"}]'

# Step 3: 새 default-disk 등록
kubectl patch nodes.longhorn.io k8s-worker3 -n longhorn-system \
  --type=merge \
  -p='{"spec":{"disks":{"default-disk":{"allowScheduling":true,"diskType":"filesystem","evictionRequested":false,"path":"/var/lib/longhorn/","storageReserved":10737418240,"tags":[]}}}}'
```

확인:

```bash
kubectl get nodes.longhorn.io k8s-worker3 -n longhorn-system \
  -o jsonpath='{.status.diskStatus}' | python3 -m json.tool | grep -E "ready|schedulable"

# 출력:
# "ready": true,
# "schedulable": true  ✅
```

---

## 결과

| 항목 | Before | After |
|------|--------|-------|
| worker1 디스크 구성 | sdb(150G) + sdc(50G), 멀티 LVM | sdb 100G 단일 LVM |
| worker2 디스크 구성 | sdb(97G) + ssc(50G), 멀티 LVM | sdb 100G 단일 LVM |
| worker3 디스크 구성 | sdb + sdc + sdd, 멀티 LVM | sdb 100G 단일 LVM |
| 디스크 장애 위험 | 복수 디스크 중 1개 장애 → 전체 LV 손상 | 단일 디스크 → 손상 범위 한정 |
| VMware 용량 확장 | 복잡 (여러 디스크 중 어떤 것?) | sdb 단순 확장 → lvextend 1줄 |
| 노드 간 구성 일관성 | 3가지 다른 패턴 | 완전 동일 |

---

## 핵심 교훈 3가지

| 상황 | 원인 | 해결 |
|------|------|------|
| `--delete-local-data` 플래그 오류 | K8s v1.31에서 제거됨 | `--delete-emptydir-data` 사용 |
| `lvremove` "in use" (umount 성공 후에도) | 컨테이너 mount namespace가 커널에 잔존 | fstab 주석 처리 후 재부팅 |
| Longhorn `Unschedulable` (LVM 재구성 후) | longhorn-disk.cfg 삭제로 UUID 변경 → CRD mismatch | 기존 disk 삭제 + 새 disk 등록 |

---

> **관련 이론**: [[Longhorn/Storage 시리즈 #1] Kubernetes에서 Volume이란 무엇인가](/study/2026-03-20-kubernetes-storage-volume-pv-pvc-csi/)
