---
title: "K8s 1.31 → 1.33 버전 업그레이드: Longhorn PDB와 etcd 암호화 복구까지"
date: 2026-03-20T20:00:00+09:00
categories: ["study", "Kubernetes"]
summary: "홈랩 K8s 클러스터를 1.31에서 1.33으로 업그레이드하면서 Longhorn PDB 차단, etcd 암호화 설정 소실, CRI socket annotation 누락 3가지 트러블슈팅을 해결한 과정"
---

## 배경

홈랩 K8s 클러스터(Control Plane 1대 + Worker 4대)가 v1.31로 운영 중이었다. 마이너 버전은 한 번에 1씩만 올릴 수 있다는 K8s skew 정책에 따라 1.31 → 1.32 → 1.33 순으로 두 번에 걸쳐 업그레이드를 진행했다.

단순히 버전 숫자를 올리는 작업처럼 보였지만, Longhorn 스토리지 PDB 차단, etcd 암호화 설정 소실, CRI socket annotation 누락 세 가지 문제가 연달아 터졌다.

## 핵심 개념

### K8s 업그레이드 순서가 CP → Worker인 이유

kube-apiserver가 최신 버전이어야 이전 버전 kubelet과의 호환성이 보장된다. 반대로 Worker가 CP보다 높은 버전이 되면 API 호환성이 깨질 수 있다. kubeadm은 이 순서를 강제한다.

### drain vs cordon

drain은 노드의 모든 Pod를 다른 노드로 이동시키고 스케줄링도 막는다. 업그레이드 중 해당 노드에 새 Pod가 뜨지 않도록 하기 위해 사용한다. uncordon으로 복원한다.

### PodDisruptionBudget(PDB)

"이 Pod는 최소 N개 이상 살아있어야 한다"는 정책이다. replicas=1인 앱에 minAvailable=1 PDB가 걸려있으면, 유일한 Pod를 evict할 수 없어 drain이 멈춘다.

## 업그레이드 절차

### CP 업그레이드

```bash
# 1. 새 버전 repo 추가
sudo mkdir -p /etc/apt/keyrings
curl -fsSL https://pkgs.k8s.io/core:/stable:/v1.32/deb/Release.key | \
  sudo gpg --dearmor -o /etc/apt/keyrings/kubernetes-1.32.gpg
echo "deb [signed-by=/etc/apt/keyrings/kubernetes-1.32.gpg] https://pkgs.k8s.io/core:/stable:/v1.32/deb/ /" | \
  sudo tee /etc/apt/sources.list.d/kubernetes-1.32.list

# 2. kubeadm 업그레이드 후 적용
sudo apt-mark unhold kubeadm
sudo apt-get install -y kubeadm=1.32.13-1.1
sudo apt-mark hold kubeadm
sudo kubeadm upgrade apply v1.32.13

# 3. kubelet/kubectl 업그레이드
sudo apt-mark unhold kubelet kubectl
sudo apt-get install -y kubelet=1.32.13-1.1 kubectl=1.32.13-1.1
sudo apt-mark hold kubelet kubectl
sudo systemctl daemon-reload && sudo systemctl restart kubelet
```

### Worker 업그레이드 (노드당 반복)

CP에서 drain → 워커에서 업그레이드 → CP에서 uncordon 순서로 진행한다.

```bash
# CP에서: drain
kubectl drain k8s-worker1 --ignore-daemonsets --delete-emptydir-data

# Worker에서: repo 추가 + 업그레이드
sudo kubeadm upgrade node
sudo apt-get install -y kubelet=1.32.13-1.1 kubectl=1.32.13-1.1
sudo systemctl daemon-reload && sudo systemctl restart kubelet

# CP에서: uncordon
kubectl uncordon k8s-worker1
```

## 트러블슈팅

### 1. Longhorn PDB가 drain을 차단

**증상**: `Cannot evict pod as it would violate the pod's disruption budget`

**원인**: Longhorn은 볼륨 replica를 관리하는 instance-manager Pod마다 PDB를 자동 생성한다. 해당 볼륨이 `degraded` 상태(설정된 replica 수 < 실제 running 수)이면 PDB의 ALLOWED DISRUPTIONS이 0이 되어 evict를 막는다.

```bash
# 볼륨 상태 확인
kubectl get volumes -n longhorn-system | grep -v healthy

# stopped replica 확인
kubectl get replicas -n longhorn-system | grep stopped
```

**해결**: stopped 상태 replica를 삭제하면 Longhorn이 다른 노드에 자동으로 새 replica를 생성한다. 볼륨이 healthy로 돌아오면 PDB가 자동 해제된다.

```bash
# stopped replica 전체 삭제
kubectl get replicas -n longhorn-system | grep stopped | awk '{print $1}' | \
  xargs kubectl delete replica -n longhorn-system
```

| Before | After |
|--------|-------|
| pvc-0bd6ea3d: degraded, ALLOWED DISRUPTIONS=0 | pvc-0bd6ea3d: healthy, ALLOWED DISRUPTIONS=1 |
| drain 차단 | drain 정상 진행 |

### 2. kubeadm upgrade가 etcd 암호화 설정을 제거

**증상**: `kubeadm upgrade apply` 완료 후 `kubectl get secrets` 시도 시:

```
Internal error occurred: unable to transform key "/registry/secrets/...":
identity transformer tried to read encrypted data
```

istiod, mysql-pxc 등 Secret을 마운트하는 Pod들이 모두 ContainerCreating 상태로 멈췄다.

**원인**: `kubeadm upgrade apply`가 `/etc/kubernetes/manifests/kube-apiserver.yaml`을 재생성하면서 직접 추가했던 `--encryption-provider-config` 플래그를 제거했다. kube-apiserver가 etcd의 암호화된 데이터를 평문으로 읽으려 해서 에러가 발생했다.

**해결**: kube-apiserver.yaml에 플래그와 volume mount를 다시 추가한다.

```yaml
# command에 추가
- --encryption-provider-config=/etc/kubernetes/encryption-config.yaml

# volumeMounts에 추가
- mountPath: /etc/kubernetes/encryption-config.yaml
  name: encryption-config
  readOnly: true

# volumes에 추가
- hostPath:
    path: /etc/kubernetes/encryption-config.yaml
    type: File
  name: encryption-config
```

kubelet이 파일 변경을 감지하고 kube-apiserver를 자동 재시작한다.

**다음 업그레이드 전 필수 확인**:

```bash
# 업그레이드 완료 직후 반드시 실행
sudo grep encryption /etc/kubernetes/manifests/kube-apiserver.yaml
# 출력이 없으면 즉시 위 설정 재추가
```

### 3. 워커 노드 CRI socket annotation 누락

**증상**: 워커 노드에서 `sudo kubeadm upgrade node` 실행 시:

```
node k8s-worker1 doesn't have kubeadm.alpha.kubernetes.io/cri-socket annotation
```

**원인**: kubeadm이 노드의 Container Runtime 소켓을 찾기 위해 annotation을 조회하는데, 클러스터 초기 구성 방식에 따라 이 annotation이 없을 수 있다.

**해결**: CP에서 drain 전에 annotation을 미리 추가한다.

```bash
for node in k8s-worker1 k8s-worker2 k8s-worker3 k8s-worker4; do
  kubectl annotate node $node \
    kubeadm.alpha.kubernetes.io/cri-socket=unix:///var/run/containerd/containerd.sock \
    --overwrite
done
```

## 결과

| 항목 | Before | After |
|------|--------|-------|
| K8s 버전 | v1.31.13 | v1.33.10 |
| 전체 노드 상태 | Ready | Ready |
| Longhorn 볼륨 | 정상 | 정상 |
| blog-system | 정상 | 정상 |

## 다음 단계

- Longhorn replica rebalance (worker4에 replica 0개 상태)
- 업그레이드 절차를 `scripts/` 스크립트로 자동화 검토
- `kubeadm upgrade apply` 후 encryption 설정 검증 단계 체크리스트 추가
