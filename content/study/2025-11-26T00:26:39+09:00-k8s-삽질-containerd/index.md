---
title: "k8s-삽질-containerd"
date: 2025-11-26T00:26:39+09:00
draft: false
categories: ["삽질","k8s","containerd"]
tags: ["삽질","k8s","containerd","마이그레이션"]
description: "k8s-삽질-containerd"
author: "늦찌민"
---

# Kubernetes VM 롤백 후 클러스터 복구 트러블슈팅

> Worker 노드 VM 롤백 후 발생한 다양한 장애를 해결하는 과정을 정리한 글입니다.

## 📋 사건 개요

- **환경**: Kubernetes v1.31.13 (1 Control Plane + 2 Worker)
- **CNI**: Cilium
- **발생 원인**: Worker1 VM을 이전 스냅샷으로 롤백
- **핵심 문제**: **containerd 버전 불일치**

---

## 🎯 전체 타임라인

```

[사건 발생]
Worker1 VM 롤백 (스냅샷 복원)
        ↓
[문제 1] hostname 불일치
        ↓
[문제 2] API 서버 주소 불일치
        ↓
[문제 3] br_netfilter 모듈 누락
        ↓
[문제 4] Kubernetes 버전 불일치
        ↓
[문제 5] Control Plane 실수로 리셋
        ↓
[클러스터 재구성]
        ↓
[문제 6] kubelet 자동 시작 안됨
        ↓
[문제 7] CNI Pod CrashLoopBackOff ← containerd 버전!
        ↓
[해결] containerd 2.1.5로 통일

```

---

## 🔥 문제 1: hostname 불일치

### 증상

```bash
$ kubectl get nodes
NAME          STATUS     ROLES           AGE   VERSION
k8s-worker1   NotReady   <none>          23d   v1.31.13  # 기존
w1            NotReady   <none>          51s   v1.29.15  # 스냅샷에서 복원 (중복!)

```

### 원인
스냅샷 시점의 hostname이 `w1`으로 설정되어 있었음

```bash
# Worker1에서 확인
$ hostname
w1  # 예상: k8s-worker1

```

### 해결

```bash
# Worker1에서 실행
sudo hostnamectl set-hostname k8s-worker1

# Control Plane에서 중복 노드 삭제
kubectl delete node w1
kubectl delete node k8s-worker1

```

---

## 🔥 문제 2: API 서버 주소 불일치

### 증상

```bash
# Worker1 kubelet 로그
$ journalctl -u kubelet | tail -10
dial tcp 10.0.0.50:6443: connect: no route to host

```

### 원인
스냅샷 시점의 `/etc/kubernetes/kubelet.conf`에 기록된 API 서버 주소가 현재와 다름

```yaml
# /etc/kubernetes/kubelet.conf
clusters:
- cluster:
    server: https://10.0.0.50:6443  # 구 Control Plane IP

```

현재 Control Plane: `https://10.0.0.100:6443`

### 해결
Worker 노드 재조인 필요:

```bash
# Control Plane에서 토큰 생성
kubeadm token create --print-join-command

# Worker1에서 재조인
sudo kubeadm reset -f
sudo rm -rf /etc/cni/net.d/*
sudo kubeadm join 10.0.0.100:6443 --token <token> --discovery-token-ca-cert-hash <hash>

```

---

## 🔥 문제 3: br_netfilter 모듈 누락

### 증상

```bash
$ sudo kubeadm join ...
[ERROR FileContent--proc-sys-net-bridge-bridge-nf-call-iptables]:
/proc/sys/net/bridge/bridge-nf-call-iptables does not exist

```

### 원인
스냅샷 복원 시 커널 모듈이 자동 로드되지 않음

### 해결

```bash
# 모듈 로드
sudo modprobe br_netfilter

# 커널 파라미터 설정
sudo sysctl -w net.bridge.bridge-nf-call-iptables=1

# 영구 설정
cat <<EOF | sudo tee /etc/modules-load.d/k8s.conf
br_netfilter
EOF

cat <<EOF | sudo tee /etc/sysctl.d/k8s.conf
net.bridge.bridge-nf-call-iptables=1
net.bridge.bridge-nf-call-ip6tables=1
net.ipv4.ip_forward=1
EOF

sudo sysctl --system

```

---

## 🔥 문제 4: Kubernetes 버전 불일치

### 증상

```bash
$ kubectl get nodes
NAME          STATUS   VERSION
k8s-cp        Ready    v1.31.13
k8s-worker1   Ready    v1.29.15  # 버전 다름!
k8s-worker2   Ready    v1.31.13

```

### 해결
Worker1 업그레이드:

```bash
# Worker1에서 실행
# 1. 패키지 저장소 업데이트
curl -fsSL https://pkgs.k8s.io/core:/stable:/v1.31/deb/Release.key | \
  sudo gpg --dearmor -o /etc/apt/keyrings/kubernetes-apt-keyring.gpg --yes

echo 'deb [signed-by=/etc/apt/keyrings/kubernetes-apt-keyring.gpg] https://pkgs.k8s.io/core:/stable:/v1.31/deb/ /' | \
  sudo tee /etc/apt/sources.list.d/kubernetes.list

# 2. 업그레이드
sudo apt-get update
sudo apt-get install -y kubeadm=1.31.13-1.1 kubelet=1.31.13-1.1 kubectl=1.31.13-1.1

# 3. 노드 업그레이드
sudo kubeadm upgrade node

# 4. kubelet 재시작
sudo systemctl daemon-reload
sudo systemctl restart kubelet

```

---

## 🔥 문제 5: Control Plane 실수로 리셋

### 사건
Worker용 명령어를 Control Plane에서 실행:

```bash
# CP에서 실수로 실행...
sudo kubeadm reset -f

```

### 결과

```bash
$ kubectl get nodes
The connection to the server 10.0.0.100:6443 was refused

```

etcd 데이터 손실, 클러스터 완전 손상

### 복구
Control Plane 재초기화:

```bash
# 1. 완전 정리
sudo pkill -9 kubelet
sudo rm -rf /etc/kubernetes/manifests/*
sudo rm -rf /var/lib/etcd/*
sudo rm -rf /etc/cni/net.d/*

# 2. 재초기화
sudo kubeadm init --pod-network-cidr=10.244.0.0/16

# 3. kubeconfig 설정
mkdir -p $HOME/.kube
sudo cp -f /etc/kubernetes/admin.conf $HOME/.kube/config
sudo chown $(id -u):$(id -g) $HOME/.kube/config

# 4. CNI 재설치
cilium install

# 5. Worker 노드 재조인

```

---

## 🔥 문제 6: kubelet 자동 시작 안됨

### 증상
재부팅 후:

```bash
$ kubectl get nodes
The connection to the server 10.0.0.100:6443 was refused

$ sudo systemctl status kubelet
● kubelet.service
     Active: inactive (dead)

```

### 원인
`kubeadm reset` 이후 kubelet이 disabled 상태로 변경됨

### 해결

```bash
sudo systemctl start kubelet
sudo systemctl enable kubelet  # 재부팅 시 자동 시작

```

---

## 🔥 문제 7: CNI Pod CrashLoopBackOff (핵심 문제!)

### 증상

```bash
$ kubectl get pods -n kube-system
NAME                    READY   STATUS             RESTARTS
cilium-abc123          0/1     CrashLoopBackOff   5
cilium-envoy-xyz789    0/1     CrashLoopBackOff   5

$ kubectl get nodes
NAME          STATUS     ROLES           AGE   VERSION
k8s-cp        Ready      control-plane   1h    v1.31.13
k8s-worker1   NotReady   <none>          30m   v1.31.13  # CNI 없어서 NotReady
k8s-worker2   Ready      <none>          1h    v1.31.13

```

### 원인 분석

#### 1단계: Pod 로그 확인

```bash
$ kubectl logs -n kube-system cilium-abc123
Error: failed to create containerd client: rpc error: code = Unavailable desc = connection error

```

#### 2단계: containerd 버전 확인

```bash
# Control Plane
$ containerd --version
containerd containerd.io v2.1.5

# Worker1 (문제의 노드)
$ containerd --version
containerd containerd.io v1.6.28  # 버전 다름!

# Worker2
$ containerd --version
containerd containerd.io v2.1.5

```

**발견**: Worker1의 containerd 버전이 낮음 (스냅샷 복원 때문)

### 해결: containerd 업그레이드

```bash
# Worker1에서 실행

# 1. 기존 containerd 제거
sudo systemctl stop containerd
sudo apt-get remove -y containerd.io

# 2. 최신 버전 설치
sudo apt-get update
sudo apt-get install -y containerd.io=1.7.24-1

# 또는 특정 버전 설치 (2.1.5)
curl -LO https://github.com/containerd/containerd/releases/download/v2.1.5/containerd-2.1.5-linux-amd64.tar.gz
sudo tar Cxzvf /usr/local containerd-2.1.5-linux-amd64.tar.gz

# 3. 설정 적용
sudo mkdir -p /etc/containerd
containerd config default | sudo tee /etc/containerd/config.toml

# SystemdCgroup 활성화 (필수!)
sudo sed -i 's/SystemdCgroup = false/SystemdCgroup = true/' /etc/containerd/config.toml

# 4. 재시작
sudo systemctl restart containerd
sudo systemctl restart kubelet

# 5. 확인
containerd --version

```

### 결과

```bash
$ kubectl get nodes
NAME          STATUS   ROLES           AGE   VERSION
k8s-cp        Ready    control-plane   5h    v1.31.13
k8s-worker1   Ready    <none>          4h    v1.31.13  # Ready!
k8s-worker2   Ready    <none>          5h    v1.31.13

$ kubectl get pods -n kube-system | grep cilium
cilium-abc123          1/1     Running   0       2m
cilium-envoy-xyz789    1/1     Running   0       2m
cilium-operator-...    1/1     Running   0       2m

```

---

## 📊 CNI/CSI 트러블슈팅 로그 확인 가이드

### CNI 문제 진단

#### 1. 노드 상태 확인

```bash
$ kubectl get nodes
NAME          STATUS     ROLES    AGE   VERSION
worker1       NotReady   <none>   10m   v1.31.13

$ kubectl describe node worker1 | grep -A 10 "Conditions:"
Ready   False   KubeletNotReady
        container runtime network not ready: NetworkReady=false
        reason:NetworkPluginNotReady
        message:Network plugin returns error: cni plugin not initialized

```

#### 2. CNI Pod 상태 확인

```bash
# CNI Pod 찾기
$ kubectl get pods -n kube-system -o wide | grep -E "cilium|calico|flannel"
cilium-abc123   0/1   CrashLoopBackOff   5   10m   worker1

# Pod 로그 확인
$ kubectl logs -n kube-system cilium-abc123

# 이전 로그 확인 (재시작 반복 시)
$ kubectl logs -n kube-system cilium-abc123 --previous

```

#### 3. CNI 설정 파일 확인

```bash
# Worker 노드에서
$ ls -la /etc/cni/net.d/
05-cilium.conflist

$ cat /etc/cni/net.d/05-cilium.conflist
# 설정 내용 확인

```

#### 4. kubelet 로그 확인

```bash
# Worker 노드에서
$ sudo journalctl -u kubelet --no-pager | tail -50
$ sudo journalctl -u kubelet -f  # 실시간 로그

```

### CSI 문제 진단

#### 1. PVC 상태 확인

```bash
$ kubectl get pvc
NAME      STATUS    VOLUME   CAPACITY   STORAGECLASS
my-pvc    Pending                        longhorn

$ kubectl describe pvc my-pvc
Events:
  Type     Reason                Age   From                         Message
  ----     ------                ----  ----                         -------
  Warning  ProvisioningFailed    5s    persistentvolume-controller
           Failed to provision volume: waiting for a volume to be created

```

#### 2. CSI Controller 확인

```bash
# CSI Controller Pod 찾기
$ kubectl get pods -n longhorn-system | grep controller
longhorn-csi-controller-...   3/3   Running

# 로그 확인 (각 컨테이너별로)
$ kubectl logs -n longhorn-system longhorn-csi-controller-... -c csi-provisioner
$ kubectl logs -n longhorn-system longhorn-csi-controller-... -c csi-attacher
$ kubectl logs -n longhorn-system longhorn-csi-controller-... -c longhorn-manager

```

#### 3. CSI Node Plugin 확인

```bash
# 특정 노드의 CSI Node Plugin 찾기
$ kubectl get pods -n longhorn-system -o wide | grep node | grep worker1
longhorn-csi-plugin-abc   3/3   Running   0   10m   worker1

# 로그 확인
$ kubectl logs -n longhorn-system longhorn-csi-plugin-abc -c node-driver-registrar
$ kubectl logs -n longhorn-system longhorn-csi-plugin-abc -c longhorn-csi-plugin

```

#### 4. StorageClass 확인

```bash
$ kubectl get storageclass
NAME                 PROVISIONER          RECLAIMPOLICY
longhorn (default)   driver.longhorn.io   Delete

$ kubectl describe storageclass longhorn

```

#### 5. VolumeAttachment 확인

```bash
# Pod가 어느 노드에 있는지 확인
$ kubectl get pods -o wide
my-pod   Running   worker1

# VolumeAttachment 확인
$ kubectl get volumeattachment
NAME                                                                   ATTACHED   AGE
csi-xyz123...   true       5m

$ kubectl describe volumeattachment csi-xyz123...

```

### Container Runtime 문제 진단

#### 1. containerd 상태 확인

```bash
# Worker 노드에서
$ sudo systemctl status containerd
$ sudo journalctl -u containerd --no-pager | tail -50

```

#### 2. containerd 버전 확인

```bash
$ containerd --version
containerd containerd.io v2.1.5

# CRI 버전 확인
$ sudo crictl version

```

#### 3. 컨테이너 목록 확인

```bash
# 실행 중인 컨테이너
$ sudo crictl ps

# 모든 컨테이너 (중지된 것 포함)
$ sudo crictl ps -a

# Pod 목록
$ sudo crictl pods

```

#### 4. 이미지 확인

```bash
# 이미지 목록
$ sudo crictl images

# 특정 이미지 pull 테스트
$ sudo crictl pull quay.io/cilium/cilium:v1.18.2

```

---

## 🎯 트러블슈팅 체크리스트

VM 롤백 후 Worker 노드 재가입 시:

```

□ 1. hostname 확인 및 수정
   - hostname
   - /etc/hostname

□ 2. 커널 모듈 확인
   - lsmod | grep br_netfilter
   - /etc/modules-load.d/k8s.conf

□ 3. 커널 파라미터 확인
   - sysctl net.bridge.bridge-nf-call-iptables
   - /etc/sysctl.d/k8s.conf

□ 4. containerd 버전 확인 ← 중요!
   - containerd --version
   - 다른 노드와 버전 일치 확인

□ 5. Kubernetes 버전 확인
   - kubelet --version
   - Control Plane과 버전 호환성 확인

□ 6. kubelet 설정
   - systemctl status kubelet
   - systemctl enable kubelet

□ 7. 네트워크 연결 확인
   - ping Control Plane IP
   - telnet Control Plane IP 6443

□ 8. 기존 Kubernetes 설정 제거
   - kubeadm reset -f
   - rm -rf /etc/cni/net.d/*
   - rm -rf /var/lib/kubelet/*

□ 9. 클러스터 재조인
   - kubeadm join ...

□ 10. CNI Pod 상태 확인
   - kubectl get pods -n kube-system

```

---

## 📝 교훈 및 예방책

### 1. VM 스냅샷 관리
- **스냅샷 이름에 날짜/버전 명시**: `worker1-k8s-v1.31-containerd-2.1.5-20250115`
- **복원 전 버전 정보 확인**: 현재 클러스터와 호환되는지 체크
- **복원 후 즉시 확인**:
  ```bash
  hostname
  containerd --version
  kubelet --version
  ```

### 2. 버전 통일 관리
모든 노드에서 동일한 버전 사용:

```bash
# 버전 확인 스크립트
#!/bin/bash
for node in cp worker1 worker2; do
  echo "=== $node ==="
  ssh $node "hostname && containerd --version && kubelet --version"
done

```

### 3. 자동화된 노드 준비 스크립트

```bash
#!/bin/bash
# k8s-node-prepare.sh

# 1. Hostname 설정
if [ -z "$1" ]; then
  echo "Usage: $0 <hostname>"
  exit 1
fi

sudo hostnamectl set-hostname $1

# 2. 커널 모듈
cat <<EOF | sudo tee /etc/modules-load.d/k8s.conf
br_netfilter
overlay
EOF

sudo modprobe br_netfilter
sudo modprobe overlay

# 3. 커널 파라미터
cat <<EOF | sudo tee /etc/sysctl.d/k8s.conf
net.bridge.bridge-nf-call-iptables=1
net.bridge.bridge-nf-call-ip6tables=1
net.ipv4.ip_forward=1
EOF

sudo sysctl --system

# 4. containerd 설정
sudo mkdir -p /etc/containerd
containerd config default | sudo tee /etc/containerd/config.toml
sudo sed -i 's/SystemdCgroup = false/SystemdCgroup = true/' /etc/containerd/config.toml

# 5. kubelet enable
sudo systemctl enable kubelet
sudo systemctl enable containerd

echo "✅ Node preparation complete for: $1"

```

### 4. 정기 백업
- **etcd 백업** (Control Plane):
  ```bash
  ETCDCTL_API=3 etcdctl snapshot save /backup/etcd-$(date +%Y%m%d).db \
    --endpoints=https://127.0.0.1:2379 \
    --cacert=/etc/kubernetes/pki/etcd/ca.crt \
    --cert=/etc/kubernetes/pki/etcd/server.crt \
    --key=/etc/kubernetes/pki/etcd/server.key
  ```

- **Kubernetes 리소스 백업**:
  ```bash
  kubectl get all -A -o yaml > k8s-resources-$(date +%Y%m%d).yaml
  ```

### 5. 모니터링 설정
노드 상태 자동 알림:

```bash
# Slack/Discord webhook으로 알림
while true; do
  NOT_READY=$(kubectl get nodes | grep NotReady | wc -l)
  if [ $NOT_READY -gt 0 ]; then
    curl -X POST $WEBHOOK_URL -d '{"text":"⚠️ K8s Node NotReady detected!"}'
  fi
  sleep 60
done

```

---

## 🔍 containerd 버전 호환성

| Kubernetes | containerd 권장 버전 |
|------------|---------------------|
| v1.31.x    | v1.7.x, v2.1.x      |
| v1.30.x    | v1.7.x, v2.0.x      |
| v1.29.x    | v1.6.x, v1.7.x      |

**중요**: 클러스터 내 모든 노드는 **동일한 containerd 버전** 사용 권장!

---

## 최종 상태

```bash
$ kubectl get nodes -o wide
NAME          STATUS   ROLES           VERSION    CONTAINER-RUNTIME
k8s-cp        Ready    control-plane   v1.31.13   containerd://2.1.5
k8s-worker1   Ready    <none>          v1.31.13   containerd://2.1.5
k8s-worker2   Ready    <none>          v1.31.13   containerd://2.1.5

$ kubectl get pods -n kube-system | grep cilium
cilium-...          1/1     Running   0       30m
cilium-envoy-...    1/1     Running   0       30m
cilium-operator-... 1/1     Running   0       30m

```

모든 노드 정상 동작! 🎉

---

## 참고 자료

- [containerd Release Notes](https://github.com/containerd/containerd/releases)
- [Kubernetes Component Version Skew](https://kubernetes.io/releases/version-skew-policy/)
- [Cilium Troubleshooting Guide](https://docs.cilium.io/en/stable/operations/troubleshooting/)
- [kubeadm Troubleshooting](https://kubernetes.io/docs/setup/production-environment/tools/kubeadm/troubleshooting-kubeadm/)


