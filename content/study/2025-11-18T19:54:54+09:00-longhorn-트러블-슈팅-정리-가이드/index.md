---
title: "longhorn 트러블 슈팅 정리 가이드"
date: 2025-11-18T19:54:54+09:00
draft: false
categories: ["study", "Storage"]
tags: ["k8s","longhorn","troubleshooting","PVC","creating"]
description: "longhorn 트러블 슈팅 정리 가이드 - 추가 정리"
author: "늦찌민"
series: ["Longhorn/Storage 시리즈"]
---

# Kubernetes + Longhorn + VMware Worker 환경에서 PVC가 계속 망가지는 문제 해결

> 실제 트러블슈팅 과정을 기록한 문서입니다. VMware VM 기반 Kubernetes Worker 환경에서 Longhorn 사용 시 VM 재시작마다 PVC가 faulted 상태가 되는 문제를 해결한 과정을 다룹니다.

## 문제 상황

### 환경
- **Control Plane**: 물리 PC (Ubuntu 22.04, /mnt/data에 대용량 스토리지)
- **Worker1, Worker2**: VMware VM
- **스토리지**: Longhorn (분산 블록 스토리지)
- **문제**: VM을 껐다 켜면 모든 PVC가 `faulted` 상태가 되어 Pod가 시작 안 됨

### 증상

```bash
$ kubectl get pods -A
NAMESPACE     NAME                          READY   STATUS
monitoring    grafana-xxx                   0/1     ContainerCreating
monitoring    prometheus-xxx                0/1     ContainerCreating
nextcloud     nextcloud-xxx                 0/1     ContainerCreating

$ kubectl -n longhorn-system get volume
NAME           STATE      ROBUSTNESS
pvc-xxx        detached   faulted
pvc-yyy        detached   faulted
pvc-zzz        detached   faulted

$ kubectl describe pod grafana-xxx -n monitoring
Events:
  Warning  FailedAttachVolume  volume pvc-xxx is not ready for workloads

```

---

## 원인 분석

### 1. Longhorn의 Replica 분산 구조

Longhorn은 고가용성을 위해 **기본 2개의 replica**를 다른 노드에 분산 저장합니다.

```

Grafana PVC 생성 시:
├─ Worker1에 replica 1 생성 ✅
└─ Worker2에 replica 2 생성 ✅

```

### 2. VMware Worker를 껐다 켰을 때 발생하는 일

```

1. VM 종료
   └─ Worker1의 kubelet 프로세스 종료

2. 40초 후
   └─ Kubernetes가 Worker1을 "NotReady" 상태로 마킹
   └─ (kubelet heartbeat가 40초 동안 없으면 NotReady)

3. Longhorn replica 상태 변화
   ├─ Worker1의 replica: 접근 불가 ❌
   ├─ Worker2의 replica: 정상 ✅
   └─ Longhorn: "replica 절반만 살아있어서 위험" → faulted

4. VM 재시작
   ├─ Worker1: Ready 상태로 복귀 ✅
   ├─ Worker1의 replica: outdated (오래된 데이터)
   ├─ Worker2의 replica: 최신 데이터
   └─ Longhorn: "두 replica sync 안 맞음" → 계속 faulted 💥

5. Pod 시작 실패
   └─ "volume is not ready for workloads" 에러

```

### 3. Kubernetes Health Check 메커니즘

**Q: Kubernetes는 어떻게 Worker가 죽었다는 걸 아는가?**

**A: Kubelet Heartbeat**
- Worker 노드의 `kubelet`이 10초마다 Control Plane에 신호 전송
- 40초 동안 응답 없으면 → `NotReady` 상태로 변경
- 5분 후 → Pod를 다른 노드로 Eviction (이동)

```bash
$ kubectl describe node k8s-worker1
Conditions:
  Type     Status  LastHeartbeatTime
  Ready    True    2025-11-18 19:18:50  # ← 마지막 heartbeat 시간

```

VM이 꺼지면 kubelet 프로세스도 종료 → heartbeat 중단 → NotReady

---

## 근본적인 문제

### **VMware + Longhorn의 구조적 모순**

| 요소 | 특성 | 문제 |
|------|------|------|
| Longhorn | 분산 스토리지 (고가용성 추구) | VM 재시작마다 replica 재동기화 필요 |
| VMware Worker | 일시적으로 종료 가능 | Longhorn이 "노드 장애"로 인식 |
| Control Plane | 항상 켜져있음 | Longhorn 스토리지로 사용 안 함 (기본 taint) |

**결론:**
항상 켜져있는 Control Plane은 스토리지로 안 쓰고,
꺼질 수 있는 VMware Worker만 스토리지로 쓰니 문제 발생

---

## 해결 방법

### 전략: Control Plane을 주 스토리지 노드로 변경

```

변경 전:
├─ Control Plane: 스토리지 X
├─ Worker1 (VMware): 주 스토리지 → 꺼지면 문제 💥
└─ Worker2 (VMware): 주 스토리지 → 꺼지면 문제 💥

변경 후:
├─ Control Plane (/mnt/data): 주 스토리지 ✅ (항상 ON)
├─ Worker1 (VMware): 보조 스토리지 (optional)
└─ Worker2 (VMware): 보조 스토리지 (optional)

```

### 1. Control Plane의 /mnt/data를 Longhorn 디스크로 추가

**주의:** Control Plane에는 기본적으로 `NoSchedule` taint가 있어서 Longhorn manager Pod가 스케줄링 안 됩니다.

```bash
# 1. Control Plane taint 제거
kubectl taint nodes jimin-ab350m-gaming-3 \
  node-role.kubernetes.io/control-plane:NoSchedule-

# 2. /mnt/data/longhorn 디렉토리 생성
sudo mkdir -p /mnt/data/longhorn
sudo chmod 700 /mnt/data/longhorn

# 3. Longhorn manager가 자동으로 노드 등록될 때까지 대기 (1-2분)
kubectl -n longhorn-system get nodes.longhorn.io --watch

# 4. 노드가 등록되면 디스크 경로 변경
kubectl -n longhorn-system patch node.longhorn.io jimin-ab350m-gaming-3 \
  --type='json' \
  -p='[{"op": "replace", "path": "/spec/disks/default-disk-xxxxxxxxx/path", "value": "/mnt/data/longhorn"}]'

# 주의: default-disk-xxxxxxxxx는 실제 디스크 ID로 변경
# 확인 방법: kubectl -n longhorn-system get node.longhorn.io jimin-ab350m-gaming-3 -o yaml | grep "default-disk-"

```

### 2. Worker 노드의 스케줄링 우선순위 낮춤

```bash
# Worker 노드들의 스케줄링 비활성화
kubectl -n longhorn-system patch node.longhorn.io k8s-worker1 \
  --type='json' \
  -p='[{"op": "replace", "path": "/spec/allowScheduling", "value": false}]'

kubectl -n longhorn-system patch node.longhorn.io k8s-worker2 \
  --type='json' \
  -p='[{"op": "replace", "path": "/spec/allowScheduling", "value": false}]'

# 확인
kubectl -n longhorn-system get nodes.longhorn.io
# 출력 예시:
# NAME                    READY   ALLOWSCHEDULING   SCHEDULABLE   AGE
# jimin-ab350m-gaming-3   True    true              True          2m
# k8s-worker1             True    false             True          15d
# k8s-worker2             True    false             True          15d

```

### 3. 기존 faulted PVC 복구

**중요:** 기존 Worker 노드의 faulted 볼륨은 자동 복구가 안 됩니다. 수동으로 복구해야 합니다.

**방법 A: PVC 백업 후 재생성** (가장 안전, 추천)

```bash
# 1. 각 네임스페이스의 PVC YAML 백업
kubectl get pvc -n monitoring -o yaml > monitoring-pvc-backup.yaml
kubectl get pvc -n nextcloud -o yaml > nextcloud-pvc-backup.yaml

# 2. Pod 삭제
kubectl delete deployment -n monitoring grafana prometheus pushgateway
kubectl delete deployment -n nextcloud nextcloud nextcloud-db

# 3. PVC 삭제 (주의: 데이터 손실!)
kubectl delete pvc -n monitoring --all
kubectl delete pvc -n nextcloud --all

# 4. Longhorn 볼륨 삭제
kubectl -n longhorn-system delete volume --all

# 5. PVC 재생성 (백업에서 복원)
kubectl apply -f monitoring-pvc-backup.yaml
kubectl apply -f nextcloud-pvc-backup.yaml

# 6. Deployment 재생성 (자동으로 PVC에 새 볼륨 생성)
# 이미 있는 Deployment spec을 다시 apply하면 됨

```

**방법 B: Longhorn UI로 수동 복구** (복잡함)

```bash
# 1. Longhorn UI 접속
kubectl port-forward -n longhorn-system svc/longhorn-frontend 8080:80

# 2. 브라우저에서 localhost:8080 접속

# 3. 각 볼륨마다:
#    - Volume 클릭
#    - "Salvage" 클릭 (Worker 노드의 오래된 replica 삭제)
#    - "Attach to Node" → jimin-ab350m-gaming-3 선택
#    - 정상 상태가 되면 "Detach"
#    - Pod 재시작

```

**방법 C: 간단한 복구 (데이터 손실 감수)**

만약 monitoring, nextcloud 데이터가 중요하지 않다면:

```bash
# 모든 것을 삭제하고 재설치
kubectl delete namespace monitoring
kubectl delete namespace nextcloud

# Longhorn 볼륨도 자동 삭제됨

# 다시 설치
# (원래 사용한 Helm chart나 manifest로 재설치)

```

### 4. 기본 replica 개수 조정 (선택)

개인 학습 환경이라면 replica 1개로도 충분:

```bash
kubectl -n longhorn-system edit settings.longhorn.io default-replica-count

```

```yaml
value: "1"  # 기본 2 → 1로 변경

```

---

## 결과 확인

```bash
# 노드 상태
$ kubectl get nodes
NAME                    STATUS   ROLES           AGE
jimin-ab350m-gaming-3   Ready    control-plane   17d
k8s-worker1             Ready    <none>          17d
k8s-worker2             Ready    <none>          17d

# PVC 상태
$ kubectl get pvc -A
NAMESPACE     NAME                 STATUS   VOLUME
monitoring    grafana-data-pvc     Bound    pvc-xxx
monitoring    prometheus-data-pvc  Bound    pvc-yyy

# Longhorn 볼륨 상태
$ kubectl -n longhorn-system get volume
NAME       STATE      ROBUSTNESS   NODE
pvc-xxx    attached   healthy      jimin-ab350m-gaming-3  # ← Control Plane!
pvc-yyy    attached   healthy      jimin-ab350m-gaming-3

# Pod 상태
$ kubectl get pods -A
NAMESPACE     NAME                READY   STATUS    NODE
monitoring    grafana-xxx         1/1     Running   k8s-worker1
monitoring    prometheus-xxx      1/1     Running   k8s-worker2

```

---

## 추가 최적화

### 1. /mnt/data 디렉토리 권한 설정

```bash
sudo mkdir -p /mnt/data/longhorn
sudo chown -R root:root /mnt/data/longhorn
sudo chmod 700 /mnt/data/longhorn

```

### 2. Longhorn 백업 설정 (NFS/S3)

```yaml
# S3 백업 타겟 설정 예시
apiVersion: longhorn.io/v1beta2
kind: BackupTarget
metadata:
  name: default
  namespace: longhorn-system
spec:
  backupTargetURL: s3://my-bucket@us-east-1/
  credentialSecret: aws-secret

```

### 3. 모니터링 설정

```bash
# Longhorn metrics를 Prometheus에 추가
kubectl apply -f - <<EOF
apiVersion: v1
kind: ServiceMonitor
metadata:
  name: longhorn-prometheus-servicemonitor
  namespace: longhorn-system
spec:
  selector:
    matchLabels:
      app: longhorn-manager
  endpoints:
  - port: manager
EOF

```

---

## 학습 포인트

### 1. Kubernetes Node Health Check
- Kubelet heartbeat (10초 간격)
- Node Conditions: Ready, MemoryPressure, DiskPressure, PIDPressure
- Grace Period: 40초 (node-monitor-grace-period)
- Pod Eviction Timeout: 5분 (pod-eviction-timeout)

### 2. Longhorn 아키텍처
- **Replica**: 데이터 복사본 (기본 2개)
- **Engine**: 볼륨 I/O 처리
- **Instance Manager**: 여러 engine/replica 관리
- **CSI Driver**: Kubernetes와 통합

### 3. 분산 스토리지 vs VMware 환경
- 분산 스토리지는 "노드가 항상 켜져있음"을 가정
- VM 환경에서는 주 스토리지를 물리 서버에 두는 게 안전
- 또는 Local Path Provisioner 같은 단순한 방식 고려

---

## 대안 솔루션 (참고)

### A. Local Path Provisioner (간단)

```bash
kubectl apply -f https://raw.githubusercontent.com/rancher/local-path-provisioner/master/deploy/local-path-storage.yaml

```
- 각 노드의 로컬 디스크 사용
- 복제 없음 (데이터 손실 위험)
- VM 재시작해도 해당 노드의 데이터는 유지

### B. NFS Provisioner (안정적)

```bash
# Control Plane에 NFS 서버 구성
sudo apt install nfs-kernel-server
sudo mkdir -p /mnt/data/nfs
sudo chown nobody:nogroup /mnt/data/nfs
echo "/mnt/data/nfs *(rw,sync,no_subtree_check,no_root_squash)" | sudo tee -a /etc/exports
sudo systemctl restart nfs-kernel-server

```
- 모든 노드가 Control Plane의 NFS 공유 사용
- 구조 단순, 성능 적당

### C. Rook-Ceph (엔터프라이즈급)
- 더 강력한 분산 스토리지
- 학습 비용 높음
- 프로덕션 환경에 적합

---

## 결론

**문제:**
VMware Worker 기반 K8s에서 Longhorn 사용 시 VM 재시작마다 PVC가 faulted 상태

**원인:**
Longhorn이 항상 켜져있는 Control Plane은 스토리지로 안 쓰고,
꺼질 수 있는 Worker만 쓰니 replica 동기화 문제 발생

**해결:**
Control Plane의 /mnt/data를 Longhorn 주 스토리지로 설정
→ VM 재시작해도 데이터 안전

**학습 내용:**
- Kubernetes의 Node Health Check (kubelet heartbeat)
- Longhorn의 replica 분산 메커니즘
- 분산 스토리지와 VM 환경의 구조적 모순
- 스토리지 선택 전략 (학습용 vs 프로덕션)

---

## 실제 트러블슈팅 과정

### 1단계: 문제 발견

```bash
# VM 재시작 후 Pod 상태 확인
$ kubectl get pods -A
NAMESPACE     NAME                          READY   STATUS
monitoring    grafana-xxx                   0/1     ContainerCreating
monitoring    prometheus-xxx                0/1     ContainerCreating

# 상세 원인 확인
$ kubectl describe pod grafana-xxx -n monitoring
Events:
  Warning  FailedAttachVolume  volume pvc-xxx is not ready for workloads

```

**첫 번째 가설:** Pod 재시작 문제? → 아니었음

### 2단계: PVC 상태 조사

```bash
# PVC는 Bound 상태
$ kubectl get pvc -A
NAMESPACE     NAME                 STATUS   VOLUME
monitoring    grafana-data-pvc     Bound    pvc-xxx

# 하지만 Longhorn 볼륨은 faulted!
$ kubectl -n longhorn-system get volume
NAME       STATE      ROBUSTNESS
pvc-xxx    detached   faulted

```

**두 번째 가설:** Longhorn 볼륨이 문제의 근원

### 3단계: Longhorn Replica 상태 확인

```bash
$ kubectl -n longhorn-system get replica | grep pvc-xxx
pvc-xxx-r-worker1   stopped   k8s-worker1
pvc-xxx-r-worker2   stopped   k8s-worker2

# 노드 상태 확인
$ kubectl get nodes
NAME          STATUS     ROLES
worker1       NotReady   <none>   # ← 문제 발견!
worker2       Ready      <none>

```

**핵심 발견:** Worker1이 NotReady → 해당 replica가 outdated → 전체 볼륨 faulted

### 4단계: Kubernetes Health Check 메커니즘 이해

**질문:** "Kubernetes는 어떻게 Worker가 죽었다는 걸 아는가?"

```bash
$ kubectl describe node k8s-worker1
Conditions:
  Type     Status  LastHeartbeatTime
  Ready    False   2025-11-18 09:30:00  # ← 40초 전 마지막 heartbeat

```

**학습:**
- Kubelet이 10초마다 heartbeat 전송
- 40초 응답 없으면 NotReady
- VM 꺼지면 kubelet 종료 → heartbeat 중단

### 5단계: 근본 원인 분석

```

문제의 구조:
├─ Longhorn은 고가용성을 위해 Worker1, Worker2에 replica 분산
├─ VM 재시작 → Worker1 NotReady → Worker1의 replica outdated
├─ Longhorn: "두 replica sync 안 맞아서 위험" → faulted
└─ Control Plane은 항상 켜져있는데 스토리지로 안 씀 (taint 때문)

```

**깨달음:** 항상 켜져있는 Control Plane을 주 스토리지로 써야 함!

### 6단계: Control Plane을 Longhorn 노드로 등록 시도

**첫 번째 시도:**
```bash
$ kubectl label node jimin-ab350m-gaming-3 \
    node.longhorn.io/create-default-disk=config

# Longhorn 노드 목록 확인
$ kubectl -n longhorn-system get nodes.longhorn.io
NAME          READY
k8s-worker1   True
k8s-worker2   True
# Control Plane이 없음! 😱

```

**실패 원인 조사:**
```bash
$ kubectl get node jimin-ab350m-gaming-3 -o jsonpath='{.spec.taints}'
[{"effect":"NoSchedule","key":"node-role.kubernetes.io/control-plane"}]

```

**발견:** Control Plane에 `NoSchedule` taint → Longhorn manager Pod가 스케줄링 안 됨

### 7단계: Taint 제거 후 재시도

```bash
# Taint 제거
$ kubectl taint nodes jimin-ab350m-gaming-3 \
    node-role.kubernetes.io/control-plane:NoSchedule-
node/jimin-ab350m-gaming-3 untainted

# Longhorn manager Pod가 생성되는지 확인
$ kubectl -n longhorn-system get pods -o wide | grep jimin
longhorn-manager-cdmqp   0/1   ContainerCreating   jimin-ab350m-gaming-3
engine-image-ei-xxx      0/1   ContainerCreating   jimin-ab350m-gaming-3
longhorn-csi-plugin-xxx  0/3   ContainerCreating   jimin-ab350m-gaming-3

```

**진행 중:** 이미지 다운로드 중 (1-2분 소요)

### 8단계: 노드 등록 대기 및 확인

```bash
# 2분 후 재확인
$ kubectl -n longhorn-system get nodes.longhorn.io
NAME                    READY   ALLOWSCHEDULING
jimin-ab350m-gaming-3   True    true              # ✅ 성공!
k8s-worker1             True    true
k8s-worker2             True    true

```

**성공!** Control Plane이 Longhorn 노드로 등록됨

### 9단계: 스토리지 경로 변경

```bash
# /mnt/data/longhorn 디렉토리 생성
$ sudo mkdir -p /mnt/data/longhorn
$ sudo chmod 700 /mnt/data/longhorn

# 현재 디스크 설정 확인
$ kubectl -n longhorn-system get node.longhorn.io jimin-ab350m-gaming-3 -o yaml | grep path
path: /var/lib/longhorn/  # ← 기본 경로

# /mnt/data/longhorn으로 변경
$ kubectl -n longhorn-system patch node.longhorn.io jimin-ab350m-gaming-3 \
    --type='json' \
    -p='[{"op": "replace", "path": "/spec/disks/default-disk-4e154299498f4305/path", "value": "/mnt/data/longhorn"}]'
node.longhorn.io/jimin-ab350m-gaming-3 patched

```

### 10단계: Worker 노드 우선순위 낮춤

```bash
# Worker 노드들의 스케줄링 비활성화
$ kubectl -n longhorn-system patch node.longhorn.io k8s-worker1 \
    --type='json' \
    -p='[{"op": "replace", "path": "/spec/allowScheduling", "value": false}]'

$ kubectl -n longhorn-system patch node.longhorn.io k8s-worker2 \
    --type='json' \
    -p='[{"op": "replace", "path": "/spec/allowScheduling", "value": false}]'

# 최종 확인
$ kubectl -n longhorn-system get nodes.longhorn.io
NAME                    READY   ALLOWSCHEDULING   SCHEDULABLE
jimin-ab350m-gaming-3   True    true              True       ✅
k8s-worker1             True    false             True       🔽
k8s-worker2             True    false             True       🔽

```

**완벽!** 이제 새 PVC는 Control Plane에만 생성됨

### 11단계: 기존 Faulted PVC 복구 시도

**시도 1: Pod 재시작**
```bash
$ kubectl delete pod -n monitoring --all
$ kubectl delete pod -n nextcloud --all

# 10초 후 확인
$ kubectl -n longhorn-system get volume
NAME       STATE      ROBUSTNESS
pvc-xxx    detached   faulted    # ← 여전히 faulted 😢

```

**실패:** 기존 Worker 노드의 faulted replica는 자동 복구 안 됨

**시도 2: 강제 복구 시도**
```bash
# Replica 개수를 1로 줄여보기
$ kubectl -n longhorn-system patch volume pvc-xxx \
    --type='json' \
    -p='[{"op": "replace", "path": "/spec/numberOfReplicas", "value": 1}]'

# 여전히 faulted

```

**실패:** Longhorn이 안전을 위해 faulted 볼륨 attach 거부

### 12단계: 최종 해결 - PVC 전체 재생성

```bash
# 데이터가 중요하지 않다면 (학습 환경이므로)
$ kubectl delete pvc -n monitoring --all
$ kubectl delete pvc -n nextcloud --all

# Longhorn 볼륨 자동 삭제 확인
$ kubectl -n longhorn-system get volume
No resources found

# Deployment가 자동으로 새 PVC 생성
$ kubectl get pvc -A
NAMESPACE     NAME                 STATUS   VOLUME
monitoring    grafana-data-pvc     Bound    pvc-new-xxx  # ✅ 새 볼륨!

# Longhorn 볼륨이 Control Plane에 생성되었는지 확인
$ kubectl -n longhorn-system get volume -o wide
NAME        STATE     NODE
pvc-new-xxx attached  jimin-ab350m-gaming-3  # ✅ Control Plane에!

# Pod 정상 실행 확인
$ kubectl get pods -A | grep monitoring
grafana-xxx      1/1   Running   jimin-ab350m-gaming-3
prometheus-xxx   1/1   Running   jimin-ab350m-gaming-3

```

**성공!** 🎉

---

## 트러블슈팅에서 배운 교훈

### 1. 문제 진단 순서
1. Pod 상태 (`kubectl get pods`)
2. PVC 상태 (`kubectl get pvc`)
3. PV/볼륨 상태 (`kubectl get pv`, Longhorn volume)
4. 노드 상태 (`kubectl get nodes`)
5. 스토리지 백엔드 로그

### 2. Kubernetes 동작 원리
- **Kubelet Heartbeat**: 10초마다, 40초 없으면 NotReady
- **Taint & Toleration**: Control Plane의 NoSchedule taint가 문제였음
- **Health Check**: Readiness/Liveness Probe가 아니라 kubelet heartbeat가 핵심

### 3. Longhorn 동작 원리
- **Replica 분산**: 고가용성을 위해 다른 노드에 복사본 생성
- **Faulted 상태**: Replica sync 안 맞으면 안전을 위해 attach 거부
- **자동 복구 한계**: Faulted 볼륨은 수동 복구 필요

### 4. VMware + 분산 스토리지의 모순
- 분산 스토리지는 "노드가 항상 켜져있음"을 가정
- VM 환경에서는 항상 켜져있는 물리 서버를 주 스토리지로 사용해야 함

### 5. 문제 해결 전략
- **빠른 임시 방편**: Replica 1개로 줄이기 (데이터 위험)
- **근본 해결**: Control Plane을 주 스토리지로 변경
- **최종 수단**: 데이터 삭제 후 재생성

---

## 참고 자료

- [Longhorn 공식 문서](https://longhorn.io/docs/)
- [Kubernetes Node 상태 관리](https://kubernetes.io/docs/concepts/architecture/nodes/)
- [Kubelet Heartbeat 메커니즘](https://kubernetes.io/docs/reference/command-line-tools-reference/kubelet/)

