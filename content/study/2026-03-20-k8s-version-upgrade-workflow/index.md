---
title: "K8s 버전 업그레이드를 하면 왜 서비스가 다운되는가 — encryption-config 소실과 CRI socket 누락 실전 기록"
date: 2026-03-20T22:00:00+09:00
categories:
  - study
  - Kubernetes
tags: ["kubernetes", "upgrade", "kubeadm", "encryption", "cri", "troubleshooting"]
summary: "kubeadm upgrade apply가 kube-apiserver.yaml을 재생성한다는 사실을 몰라 Secret 복호화 불가로 서비스가 다운됐다. 왜 이런 일이 일어나는지, 올바른 업그레이드 워크플로우는 무엇인지 기록한다."
showtoc: true
tocopen: true
draft: false
---

> 이론 배경: [[Longhorn/Storage 시리즈 #1] Kubernetes에서 Volume이란 무엇인가](/study/2026-03-20-kubernetes-storage-volume-pv-pvc-csi/)

---

## 이날 실제로 겪은 일

v1.32 → v1.33 업그레이드를 진행했다. `kubeadm upgrade apply`가 성공 메시지를 출력했고 노드 상태도 Ready였다. 그런데 잠시 후 블로그 서비스가 다운됐다.

```bash
kubectl get pods -n blog-system
# NAME          READY   STATUS                       RESTARTS
# was-xxx       0/1     CreateContainerConfigError   0
# was-yyy       0/1     CreateContainerConfigError   0
```

WAS Pod 2개가 모두 `CreateContainerConfigError`. Secret을 마운트하지 못하는 상태였다.

```bash
kubectl describe pod was-xxx -n blog-system | grep -A5 "Events"
# Error from server (InternalError): unable to transform key
# "/registry/secrets/blog-system/aws-s3-credentials":
# identity transformer tried to read encrypted data
```

etcd에는 암호화된 Secret이 있는데, kube-apiserver는 평문(identity) 모드로 기동 중이었다. **복호화 불가.**

---

## 왜 이런 일이 일어나는가

### kubeadm upgrade apply가 하는 일

`kubeadm upgrade apply`는 단순히 컨테이너 이미지를 교체하는 것이 아니다. **Control Plane의 Static Pod manifest 파일을 완전히 재생성**한다.

```
/etc/kubernetes/manifests/
├── kube-apiserver.yaml      ← kubeadm이 재생성
├── kube-controller-manager.yaml  ← 재생성
├── kube-scheduler.yaml      ← 재생성
└── etcd.yaml                ← 재생성
```

재생성이란 **기존 파일을 덮어쓴다**는 의미다. 수동으로 추가한 설정이 전부 사라진다.

> 📌 **[사진 위치 1]** kubeadm upgrade apply 흐름 다이어그램
> - kubeadm → kube-apiserver.yaml 재생성 (기존 설정 소실)
> - kubelet이 변경 감지 → kube-apiserver 자동 재시작
> - 수동 추가 설정 (encryption-config, audit-log 등) → 모두 날아감

### encryption-config가 왜 수동 설정인가

etcd Secret 암호화(`--encryption-provider-config`)는 kubeadm의 기본 설정에 포함되어 있지 않다. 별도로 설정한 기능이다.

```yaml
# kube-apiserver.yaml에 수동으로 추가한 내용
spec:
  containers:
  - command:
    - kube-apiserver
    - --encryption-provider-config=/etc/kubernetes/encryption-config.yaml  # ← 수동
    volumeMounts:
    - mountPath: /etc/kubernetes/encryption-config.yaml  # ← 수동
      name: encryption-config
      readOnly: true
  volumes:
  - hostPath:
      path: /etc/kubernetes/encryption-config.yaml       # ← 수동
    name: encryption-config
```

업그레이드 후 kube-apiserver.yaml이 재생성되면 이 3가지가 전부 사라진다. etcd에는 여전히 암호화된 데이터가 있는데 kube-apiserver는 복호화 키를 모르는 상태가 된다.

---

## 트러블슈팅 1: encryption-config 소실 복구

kube-apiserver.yaml에 소실된 3가지를 다시 추가했다.

```bash
sudo vi /etc/kubernetes/manifests/kube-apiserver.yaml
```

추가할 내용:

```yaml
# 1. spec.containers[0].command에 추가
- --encryption-provider-config=/etc/kubernetes/encryption-config.yaml

# 2. spec.containers[0].volumeMounts에 추가
- mountPath: /etc/kubernetes/encryption-config.yaml
  name: encryption-config
  readOnly: true

# 3. spec.volumes에 추가
- hostPath:
    path: /etc/kubernetes/encryption-config.yaml
    type: File
  name: encryption-config
```

저장하면 kubelet이 변경을 감지해 kube-apiserver를 자동으로 재시작한다.

```bash
# 재시작 확인
kubectl get pods -n kube-system | grep apiserver
# kube-apiserver-k8s-cp   1/1   Running   1   2m

# Secret 접근 복구 확인
kubectl get secrets -n blog-system
# NAME                    TYPE     DATA   AGE
# aws-s3-credentials      Opaque   3      30d  ✅
```

---

## 트러블슈팅 2: 워커 노드 CRI socket annotation 누락

CP 업그레이드 후 워커 노드에서 `kubeadm upgrade node`를 실행했다.

```bash
sudo kubeadm upgrade node

# 출력:
# unable to fetch the kubeadm-config ConfigMap: failed to get node registration:
# node k8s-worker1 doesn't have kubeadm.alpha.kubernetes.io/cri-socket annotation
```

### 왜 이 annotation이 필요한가

kubeadm은 업그레이드 시 노드가 어떤 Container Runtime(containerd, CRI-O 등)을 쓰는지 알아야 한다. 이 정보를 노드 annotation에서 읽는다.

```bash
kubectl get node k8s-worker1 -o jsonpath='{.metadata.annotations}' | python3 -m json.tool | grep cri
# (아무것도 없음 → annotation이 없음)
```

클러스터 초기 구성 방식에 따라 이 annotation이 자동으로 설정되지 않을 수 있다. 업그레이드 전에 미리 확인해야 한다.

### 해결: CP에서 annotation을 먼저 추가

```bash
# 전체 워커 노드 일괄 추가 (업그레이드 전 필수)
for node in k8s-worker1 k8s-worker2 k8s-worker3 k8s-worker4; do
  kubectl annotate node $node \
    kubeadm.alpha.kubernetes.io/cri-socket=unix:///var/run/containerd/containerd.sock \
    --overwrite
done

# 확인
kubectl get nodes -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.metadata.annotations.kubeadm\.alpha\.kubernetes\.io/cri-socket}{"\n"}{end}'
# k8s-worker1  unix:///var/run/containerd/containerd.sock ✅
# k8s-worker2  unix:///var/run/containerd/containerd.sock ✅
# ...
```

annotation 추가 후 `kubeadm upgrade node` 정상 동작.

---

## 올바른 K8s 버전 업그레이드 워크플로우

이번 경험으로 만든 체크리스트다.

### 업그레이드 전 (CP에서)

```bash
# 1. 수동 추가 설정 목록 확인 및 백업
sudo grep -n "encryption\|audit\|feature" /etc/kubernetes/manifests/kube-apiserver.yaml
sudo cp /etc/kubernetes/manifests/kube-apiserver.yaml ~/backup/kube-apiserver-before-upgrade.yaml

# 2. 워커 노드 CRI socket annotation 확인
kubectl get nodes -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.metadata.annotations.kubeadm\.alpha\.kubernetes\.io/cri-socket}{"\n"}{end}'
# annotation이 없는 노드가 있으면 미리 추가

# 3. etcd 백업
sudo ETCDCTL_API=3 etcdctl snapshot save ~/backup/etcd-before-upgrade.db \
  --endpoints=https://127.0.0.1:2379 \
  --cacert=/etc/kubernetes/pki/etcd/ca.crt \
  --cert=/etc/kubernetes/pki/etcd/server.crt \
  --key=/etc/kubernetes/pki/etcd/server.key
```

### 업그레이드 실행

```bash
sudo kubeadm upgrade apply v1.33.0
```

### 업그레이드 직후 (CP에서 반드시)

```bash
# kube-apiserver.yaml 재생성 여부 확인
sudo grep encryption /etc/kubernetes/manifests/kube-apiserver.yaml
# 없으면 → 즉시 재적용 (위 복구 방법 참조)

# 있으면 정상
kubectl get secrets -n blog-system  # Secret 접근 확인
```

### 워커 노드 순차 업그레이드

```bash
# 1대씩 drain → upgrade → uncordon
kubectl drain k8s-worker1 --ignore-daemonsets --delete-emptydir-data
ssh k8s-worker1 "sudo kubeadm upgrade node && sudo systemctl restart kubelet"
kubectl uncordon k8s-worker1
```

> 📌 **[사진 위치 2]** K8s 업그레이드 워크플로우 다이어그램
> - 백업(etcd + manifest) → CP upgrade → manifest 재적용 확인 → 워커 순차 업그레이드

---

## 결과

| 항목 | Before | After |
|------|--------|-------|
| K8s 버전 | v1.32 | v1.33 |
| 서비스 다운 시간 | ~15분 (encryption-config 소실로) | 0 (워크플로우 확립 후) |
| 업그레이드 실패 원인 | encryption-config 소실 + CRI socket annotation 누락 | 사전 확인으로 방지 |

---

## 핵심 교훈

`kubeadm upgrade apply`는 **Static Pod manifest를 재생성**한다. 수동으로 추가한 설정은 업그레이드 전에 반드시 백업하고, 업그레이드 직후 재적용 여부를 확인해야 한다.

kubeadm이 기본 제공하지 않는 설정(etcd 암호화, Audit Log, Feature Gates 등)은 모두 이 위험에 노출되어 있다.
