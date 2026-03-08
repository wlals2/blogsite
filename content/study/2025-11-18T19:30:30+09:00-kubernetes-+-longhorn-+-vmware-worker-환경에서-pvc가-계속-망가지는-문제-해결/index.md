---
title: "Kubernetes + Longhorn + VMware Worker 환경에서 PVC가 계속 망가지는 문제 해결"
date: 2025-11-18T19:30:30+09:00
draft: false
categories: ["study", "Troubleshooting"]
tags: ["kubernetes","PVC","PV","VMware","Lognhorn","faulted"]
description: "Kubernetes + Longhorn + VMware Worker 환경에서 PVC가 계속 망가지는 문제 해결"
author: "늦찌민"
---

# Kubernetes + Longhorn + VMware Worker 환경에서 PVC가 계속 망가지는 문제 해결

## 문제 상황

### 환경
- **Control Plane**: 물리 PC (Ubuntu 22.04, /mnt/data에 대용량 스토리지)
- **Worker1, Worker2**: VMware VM
- **스토리지**: Longhorn (분산 블록 스토리지)
- **문제**: VM을 껐다 켜면 모든 PVC가 `faulted` 상태가 되어 Pod가 시작 안 됨

### 증상:

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

```bash
# Longhorn이 Control Plane을 인식하도록 라벨 추가
kubectl label node jimin-ab350m-gaming-3 \
  node.longhorn.io/create-default-disk=config

# Longhorn 노드 설정 수정
kubectl -n longhorn-system edit node jimin-ab350m-gaming-3

```

**수정 내용:**
```yaml
spec:
  allowScheduling: true  # ← false를 true로 변경
  disks:
    default-disk-xxxxx:
      allowScheduling: true
      path: /mnt/data/longhorn  # ← /var/lib/longhorn에서 변경
      storageReserved: 10737418240  # 10GB 예약
  evictionRequested: false
  tags: []

```

### 2. Worker 노드의 스케줄링 우선순위 낮춤

```bash
kubectl -n longhorn-system edit node k8s-worker1
kubectl -n longhorn-system edit node k8s-worker2

```

```yaml
spec:
  allowScheduling: false  # ← true를 false로 변경

```

### 3. 기존 faulted PVC 복구

**방법 A: Pod 재시작으로 자동 복구**

```bash
# 모든 Pod 삭제 (PVC는 유지)
kubectl delete pod -n monitoring --all
kubectl delete pod -n nextcloud --all
kubectl delete pod -n hugo-system --all

# Longhorn이 Control Plane에 새로운 replica 생성
# Deployment가 자동으로 Pod 재생성

```

**방법 B: 수동 replica 재빌드**

```bash
# Longhorn UI 접속
kubectl port-forward -n longhorn-system svc/longhorn-frontend 8080:80

# 브라우저에서 localhost:8080 접속
# 각 볼륨의 "Salvage" 또는 "Activate" 클릭

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

## 참고 자료

- [Longhorn 공식 문서](https://longhorn.io/docs/)
- [Kubernetes Node 상태 관리](https://kubernetes.io/docs/concepts/architecture/nodes/)
- [Kubelet Heartbeat 메커니즘](https://kubernetes.io/docs/reference/command-line-tools-reference/kubelet/)

