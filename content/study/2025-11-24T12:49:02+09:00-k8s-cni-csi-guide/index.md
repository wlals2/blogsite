---
title: "k8s-cni-csi-guide"
date: 2025-11-24T12:49:02+09:00
draft: false
categories: ["cni","csi"]
tags: ["cni","csi","k9s","guide","longhorn","cilium"]
description: "k8s-cni-csi-guide"
author: "늦찌민"
---

# Kubernetes CSI와 CNI 완벽 가이드

> Kubernetes의 두 가지 핵심 플러그인 인터페이스인 CSI(Container Storage Interface)와 CNI(Container Network Interface)를 정리한 글입니다.

---

## 한눈에 보는 CSI vs CNI

| 구분 | CSI | CNI |
|------|-----|-----|
| **풀네임** | Container Storage Interface | Container Network Interface |
| **역할** | 스토리지 관리 (볼륨) | 네트워크 관리 (Pod 통신) |
| **담당** | PV/PVC, 동적 프로비저닝 | Pod IP 할당, 네트워크 정책 |
| **예시** | Longhorn, Ceph, EBS CSI | Cilium, Calico, Flannel |
| **없으면?** | PVC 생성 불가 | Pod가 NotReady, 통신 불가 |

---

## 전체 아키텍처

```
                    ┌─────────────────────────────────────┐
                    │           Kubernetes Cluster         │
                    │                                       │
  ┌─────────────────┼───────────────────────────────────────┼─────────────────┐
  │                 │                                       │                 │
  │    Storage      │         ┌───────────┐                 │    Network      │
  │                 │         │           │                 │                 │
  │  ┌──────────┐   │         │    Pod    │                 │   ┌──────────┐  │
  │  │   CSI    │◄──┼─────────┤  Container├─────────────────┼──►│   CNI    │  │
  │  │ Driver   │   │         │           │                 │   │  Plugin  │  │
  │  └────┬─────┘   │         └───────────┘                 │   └────┬─────┘  │
  │       │         │                                       │        │        │
  └───────┼─────────┼───────────────────────────────────────┼────────┼────────┘
          │         │                                       │        │
          ▼         │                                       │        ▼
   ┌─────────────┐  │                                       │  ┌─────────────┐
   │   Storage   │  │                                       │  │   Network   │
   │   Backend   │  │                                       │  │   Backend   │
   │ (Disk, NFS) │  │                                       │  │(VXLAN, BGP) │
   └─────────────┘  │                                       │  └─────────────┘
                    └───────────────────────────────────────┘
```

---

# Part 1: CNI (Container Network Interface)

## CNI란?

CNI는 컨테이너의 **네트워크 연결을 담당**하는 표준 인터페이스입니다.

### CNI가 하는 일

1. **Pod에 IP 주소 할당**
2. **Pod 간 통신 경로 설정**
3. **네트워크 정책(NetworkPolicy) 적용**
4. **서비스 로드밸런싱 지원**

---

## CNI 아키텍처

```
┌────────────────────────────────────────────────────────────────┐
│                         Control Plane                          │
│  ┌─────────────────┐                                           │
│  │  CNI Controller │  ← 네트워크 정책, 라우팅 테이블 관리        │
│  │  (Deployment)   │                                           │
│  └────────┬────────┘                                           │
│           │                                                    │
├───────────┼────────────────────────────────────────────────────┤
│           │              Worker Nodes                          │
│           ▼                                                    │
│  ┌─────────────────┐    ┌─────────────────┐                    │
│  │   CNI Agent     │    │   CNI Agent     │  ← 각 노드에서     │
│  │  (DaemonSet)    │    │  (DaemonSet)    │    네트워크 설정    │
│  └────────┬────────┘    └────────┬────────┘                    │
│           │                      │                             │
│     ┌─────┴─────┐          ┌─────┴─────┐                       │
│     │  Pod      │          │  Pod      │                       │
│     │ 10.0.1.10 │◄────────►│ 10.0.2.20 │                       │
│     └───────────┘          └───────────┘                       │
│                                                                │
│         Node 1                    Node 2                       │
│      (10.0.1.0/24)             (10.0.2.0/24)                   │
└────────────────────────────────────────────────────────────────┘
```

---

## CNI 핵심 컴포넌트

### 1. CNI Controller (Deployment)

클러스터 전체의 네트워크를 관리합니다.

```yaml
kind: Deployment
metadata:
  name: cilium-operator  # 예: Cilium의 경우
spec:
  replicas: 1
  # 역할:
  # - 네트워크 정책 관리
  # - IP 풀(IPAM) 관리
  # - 클러스터 전체 라우팅
```

### 2. CNI Agent (DaemonSet)

각 노드에서 실제 네트워크를 설정합니다.

```yaml
kind: DaemonSet
metadata:
  name: cilium  # 모든 노드에 배포
spec:
  # 역할:
  # - Pod에 veth 페어 생성
  # - IP 주소 할당
  # - iptables/eBPF 규칙 설정
  # - 노드 간 터널링 (VXLAN/Geneve)
```

### 3. CNI 설정 파일

```bash
# 위치: /etc/cni/net.d/
$ ls /etc/cni/net.d/
05-cilium.conflist

$ cat /etc/cni/net.d/05-cilium.conflist
{
  "cniVersion": "0.3.1",
  "name": "cilium",
  "plugins": [
    {
      "type": "cilium-cni"
    }
  ]
}
```

---

## CNI 동작 흐름

### Pod 생성 시

```
1. kubelet: Pod 생성 요청
       ↓
2. Container Runtime: 컨테이너 생성 (네트워크 없음)
       ↓
3. CNI Plugin 호출: /etc/cni/net.d/ 설정 읽음
       ↓
4. CNI Agent:
   - veth 페어 생성 (Pod ↔ 호스트)
   - IP 주소 할당 (IPAM)
   - 라우팅 테이블 업데이트
       ↓
5. Pod: 네트워크 사용 가능
```

### Pod 간 통신 (다른 노드)

```
┌─────────────┐                           ┌─────────────┐
│   Pod A     │                           │   Pod B     │
│ 10.0.1.10   │                           │ 10.0.2.20   │
└──────┬──────┘                           └──────┬──────┘
       │                                         │
       ▼                                         ▼
┌──────────────┐                         ┌──────────────┐
│ veth pair    │                         │ veth pair    │
└──────┬───────┘                         └──────┬───────┘
       │                                         │
       ▼                                         ▼
┌──────────────┐    VXLAN/Geneve 터널    ┌──────────────┐
│   Node 1     │◄───────────────────────►│   Node 2     │
│ 192.168.1.61 │                         │ 192.168.1.62 │
└──────────────┘                         └──────────────┘
```

---

## 주요 CNI 플러그인 비교

| CNI | 특징 | 사용 사례 |
|-----|------|----------|
| **Cilium** | eBPF 기반, 고성능, L7 정책 | 프로덕션, 보안 중시 |
| **Calico** | BGP 지원, NetworkPolicy | 대규모 클러스터 |
| **Flannel** | 간단함, VXLAN 오버레이 | 학습용, 소규모 |
| **Weave** | 설치 쉬움, 암호화 지원 | 멀티 클라우드 |

---

## CNI 없으면 생기는 문제

```bash
$ kubectl get nodes
NAME          STATUS     ROLES           AGE   VERSION
k8s-worker1   NotReady   <none>          5m    v1.31.13
              ^^^^^^^^
              CNI 없으면 NotReady!

$ kubectl describe node k8s-worker1 | grep -A 5 "Conditions"
Ready   False   KubeletNotReady   container runtime network not ready:
                                  NetworkReady=false reason:NetworkPluginNotReady
```

---

# Part 2: CSI (Container Storage Interface)

## CSI란?

CSI는 컨테이너의 **스토리지 연결을 담당**하는 표준 인터페이스입니다.

### CSI가 하는 일

1. **볼륨 동적 생성/삭제** (PVC → PV 자동 생성)
2. **볼륨을 노드에 Attach/Detach**
3. **볼륨을 Pod에 Mount/Unmount**
4. **스냅샷 생성/복원**
5. **볼륨 크기 확장**

---

## CSI 아키텍처

```
┌─────────────────────────────────────────────────────────────────────┐
│                          Control Plane                              │
│                                                                     │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │                    CSI Controller                            │    │
│  │                    (Deployment)                              │    │
│  │  ┌──────────────┬──────────────┬──────────────┬───────────┐ │    │
│  │  │ provisioner  │   attacher   │   resizer    │ snapshotter│ │    │
│  │  │              │              │              │           │ │    │
│  │  │ PVC 감지 →   │ 볼륨을 노드에│ 볼륨 크기    │ 스냅샷    │ │    │
│  │  │ 볼륨 생성    │ Attach       │ 확장         │ 관리      │ │    │
│  │  └──────────────┴──────────────┴──────────────┴───────────┘ │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                                                                     │
├─────────────────────────────────────────────────────────────────────┤
│                          Worker Nodes                               │
│                                                                     │
│  ┌─────────────────────────────┐  ┌─────────────────────────────┐   │
│  │      CSI Node Plugin        │  │      CSI Node Plugin        │   │
│  │       (DaemonSet)           │  │       (DaemonSet)           │   │
│  │  ┌───────────────────────┐  │  │  ┌───────────────────────┐  │   │
│  │  │  node-driver-registrar│  │  │  │  node-driver-registrar│  │   │
│  │  │  (kubelet에 드라이버   │  │  │  │                       │  │   │
│  │  │   등록)                │  │  │  │                       │  │   │
│  │  ├───────────────────────┤  │  │  ├───────────────────────┤  │   │
│  │  │  CSI Driver           │  │  │  │  CSI Driver           │  │   │
│  │  │  (실제 마운트 수행)    │  │  │  │                       │  │   │
│  │  └───────────────────────┘  │  │  └───────────────────────┘  │   │
│  └─────────────────────────────┘  └─────────────────────────────┘   │
│           Node 1                            Node 2                  │
└─────────────────────────────────────────────────────────────────────┘
                    │                              │
                    ▼                              ▼
            ┌─────────────────────────────────────────────┐
            │              Storage Backend                │
            │   (Longhorn, Ceph, NFS, AWS EBS, etc.)      │
            └─────────────────────────────────────────────┘
```

---

## CSI 핵심 컴포넌트

### 1. CSI Controller (Deployment)

클러스터 전체의 볼륨을 관리합니다. 보통 1개만 실행됩니다.

```yaml
kind: Deployment
metadata:
  name: longhorn-csi-controller
spec:
  replicas: 1
  template:
    spec:
      containers:
      # Sidecar 컨테이너들
      - name: csi-provisioner      # PVC → PV 동적 생성
      - name: csi-attacher         # 볼륨을 노드에 연결
      - name: csi-resizer          # 볼륨 크기 확장
      - name: csi-snapshotter      # 스냅샷 관리
      # 실제 드라이버
      - name: longhorn-manager     # Longhorn 볼륨 관리
```

### 2. CSI Node Plugin (DaemonSet)

각 노드에서 실제 마운트를 수행합니다.

```yaml
kind: DaemonSet
metadata:
  name: longhorn-csi-plugin
spec:
  template:
    spec:
      containers:
      - name: node-driver-registrar  # kubelet에 CSI 드라이버 등록
      - name: longhorn-csi-plugin    # 실제 Mount/Unmount 수행
```

### 3. Sidecar 컨테이너 역할

| Sidecar | 역할 | 감시 대상 |
|---------|------|----------|
| **csi-provisioner** | PVC 생성 시 볼륨 생성 | PersistentVolumeClaim |
| **csi-attacher** | 볼륨을 노드에 연결 | VolumeAttachment |
| **csi-resizer** | 볼륨 크기 확장 | PVC 크기 변경 |
| **csi-snapshotter** | 스냅샷 생성/복원 | VolumeSnapshot |
| **node-driver-registrar** | kubelet에 드라이버 등록 | - |

---

## CSI 파일 구조 (Longhorn 예시)

```
longhorn/
│
├── 01-namespace.yaml              # longhorn-system 네임스페이스
│
├── 02-rbac/
│   ├── serviceaccount.yaml        # 서비스 계정
│   ├── clusterrole.yaml           # 클러스터 권한 정의
│   └── clusterrolebinding.yaml    # 권한 바인딩
│
├── 03-crds/                       # Custom Resource Definitions
│   ├── volumes.yaml               # Longhorn 볼륨 CRD
│   ├── replicas.yaml              # 복제본 CRD
│   ├── engines.yaml               # 스토리지 엔진 CRD
│   ├── backups.yaml               # 백업 CRD
│   └── snapshots.yaml             # 스냅샷 CRD
│
├── 04-configmap/
│   └── longhorn-config.yaml       # 설정 (복제본 수, 백업 경로 등)
│
├── 05-controller/
│   └── longhorn-manager.yaml      # Deployment (CSI Controller)
│       ├── csi-provisioner
│       ├── csi-attacher
│       ├── csi-resizer
│       └── csi-snapshotter
│
├── 06-node-plugin/
│   └── longhorn-driver.yaml       # DaemonSet (CSI Node Plugin)
│       ├── node-driver-registrar
│       └── longhorn-csi-plugin
│
├── 07-services/
│   ├── longhorn-backend.yaml      # 내부 통신용 Service
│   └── longhorn-frontend.yaml     # UI용 Service
│
├── 08-ui/
│   └── longhorn-ui.yaml           # 웹 UI (Deployment)
│
└── 09-storageclass/
    └── storageclass.yaml          # 기본 StorageClass
```

---

## CSI 동작 흐름

### PVC 생성 → Pod 마운트까지

```
┌─────────────────────────────────────────────────────────────────────┐
│ 1. 사용자: PVC 생성                                                 │
│    kubectl apply -f pvc.yaml                                        │
└─────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────┐
│ 2. csi-provisioner: PVC 감지                                        │
│    - StorageClass 확인                                              │
│    - CSI Controller에 CreateVolume 요청                             │
└─────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────┐
│ 3. CSI Controller: 볼륨 생성                                        │
│    - 스토리지 백엔드에 실제 볼륨 생성                                │
│    - PV 오브젝트 생성                                               │
│    - PVC와 PV 바인딩                                                │
└─────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────┐
│ 4. Pod 스케줄링                                                     │
│    - Pod가 특정 노드에 배치됨                                        │
│    - VolumeAttachment 오브젝트 생성                                  │
└─────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────┐
│ 5. csi-attacher: 볼륨 Attach                                        │
│    - VolumeAttachment 감지                                          │
│    - CSI Controller에 ControllerPublishVolume 요청                  │
│    - 볼륨을 노드에 연결 (예: /dev/longhorn/vol-xxx)                  │
└─────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────┐
│ 6. kubelet: 마운트 요청                                             │
│    - CSI Node Plugin에 NodeStageVolume 요청                         │
│    - CSI Node Plugin에 NodePublishVolume 요청                       │
└─────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────┐
│ 7. CSI Node Plugin: 마운트 수행                                     │
│    - 블록 디바이스를 파일시스템으로 마운트                           │
│    - Pod 컨테이너 경로에 바인드 마운트                               │
│    - 예: /dev/longhorn/vol-xxx → /var/lib/kubelet/pods/.../mount    │
└─────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────┐
│ 8. Pod: 볼륨 사용 가능!                                             │
│    컨테이너 내부에서 /data 경로로 접근 가능                          │
└─────────────────────────────────────────────────────────────────────┘
```

---

## StorageClass 예시

```yaml
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: longhorn
  annotations:
    storageclass.kubernetes.io/is-default-class: "true"  # 기본 SC로 설정
provisioner: driver.longhorn.io          # CSI 드라이버 이름
allowVolumeExpansion: true                # 볼륨 확장 허용
reclaimPolicy: Delete                     # PVC 삭제 시 PV도 삭제
volumeBindingMode: Immediate              # 즉시 바인딩
parameters:
  numberOfReplicas: "2"                   # 데이터 복제본 수
  staleReplicaTimeout: "2880"             # 복제본 타임아웃 (분)
  fromBackup: ""                          # 백업에서 복원 시 사용
  diskSelector: ""                        # 특정 디스크 선택
  nodeSelector: ""                        # 특정 노드 선택
```

---

## 주요 CSI 드라이버 비교

| CSI Driver | 특징 | 사용 사례 |
|------------|------|----------|
| **Longhorn** | 분산 블록 스토리지, UI 제공 | 온프레미스, 홈랩 |
| **Rook-Ceph** | 고가용성, 대용량 | 대규모 프로덕션 |
| **OpenEBS** | 다양한 엔진 선택 가능 | 유연한 구성 필요 시 |
| **NFS CSI** | 간단함, 공유 스토리지 | ReadWriteMany 필요 시 |
| **AWS EBS CSI** | AWS 네이티브 | AWS EKS |
| **GCE PD CSI** | GCP 네이티브 | GKE |

---

# Part 3: CSI와 CNI 비교 정리

## 배포 구조 비교

```
CSI 구조                              CNI 구조
───────────────────────               ───────────────────────
┌─────────────────────┐               ┌─────────────────────┐
│   CSI Controller    │               │   CNI Controller    │
│   (Deployment)      │               │   (Deployment)      │
│   - provisioner     │               │   - 네트워크 정책   │
│   - attacher        │               │   - IPAM 관리       │
│   - resizer         │               │                     │
│   - snapshotter     │               │                     │
└─────────────────────┘               └─────────────────────┘
         │                                     │
         ▼                                     ▼
┌─────────────────────┐               ┌─────────────────────┐
│  CSI Node Plugin    │               │    CNI Agent        │
│  (DaemonSet)        │               │   (DaemonSet)       │
│  - 볼륨 마운트      │               │  - Pod 네트워크     │
│  - 드라이버 등록    │               │  - veth 생성        │
└─────────────────────┘               └─────────────────────┘
```

## 기능 비교

| 항목 | CSI | CNI |
|------|-----|-----|
| **대상** | 스토리지 (PV/PVC) | 네트워크 (Pod IP) |
| **Controller** | 볼륨 생성/삭제/스냅샷 | 네트워크 정책/라우팅 |
| **Node Plugin** | 마운트/언마운트 | IP 할당/veth 생성 |
| **설정 위치** | StorageClass | /etc/cni/net.d/ |
| **없으면?** | PVC Pending | Node NotReady |

## 트러블슈팅

### CNI 문제

```bash
# 증상
$ kubectl get nodes
NAME     STATUS     ROLES    AGE   VERSION
node1    NotReady   <none>   5m    v1.31.13

# 확인
$ kubectl describe node node1 | grep -i network
NetworkReady=false reason:NetworkPluginNotReady

# 해결
$ ls /etc/cni/net.d/           # CNI 설정 파일 확인
$ kubectl get pods -n kube-system | grep cilium  # CNI Pod 상태 확인
```

### CSI 문제

```bash
# 증상
$ kubectl get pvc
NAME      STATUS    VOLUME   CAPACITY   ACCESS MODES   STORAGECLASS
my-pvc    Pending                                      longhorn

# 확인
$ kubectl describe pvc my-pvc
Events:
  waiting for a volume to be created

# 해결
$ kubectl get pods -n longhorn-system  # CSI Pod 상태 확인
$ kubectl get storageclass             # StorageClass 확인
```

---

## 실무에서의 관리

### 클라우드 환경

```
┌─────────────────────────────────────────────┐
│              Managed Kubernetes             │
│                (EKS, GKE, AKS)              │
│                                             │
│   CNI: 기본 제공 (VPC CNI, GKE CNI 등)      │
│   CSI: 기본 제공 (EBS CSI, GCE PD CSI 등)   │
│                                             │
│   → 관리할 것 거의 없음                      │
└─────────────────────────────────────────────┘
```

### 온프레미스 환경

```
┌─────────────────────────────────────────────┐
│            Self-Managed Kubernetes          │
│                                             │
│   CNI: 직접 선택/설치 (Cilium, Calico 등)   │
│   CSI: 직접 선택/설치 (Longhorn, Ceph 등)   │
│                                             │
│   → Helm 또는 Operator로 관리 권장          │
└─────────────────────────────────────────────┘
```

---

## 참고 자료

- [Kubernetes CSI Developer Documentation](https://kubernetes-csi.github.io/docs/)
- [Kubernetes CNI Specification](https://github.com/containernetworking/cni/blob/main/SPEC.md)
- [Cilium Documentation](https://docs.cilium.io/)
- [Longhorn Documentation](https://longhorn.io/docs/)


