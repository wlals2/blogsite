---
title: "kubernetes PVC 삭제뿌시기"
date: 2025-11-23T16:04:52+09:00
draft: false
categories: ["k8s","pvc","finlayzer"]
tags: ["k8s","pvc","migrade","storageclass","pvc binding"]
description: "kubernetes PVC 삭제뿌시기"
author: "늦찌민"
series: ["K8s 개념 뿌시기"]
---

# Kubernetes PVC 마이그레이션 가이드

## 문제 상황 요약

PVC(PersistentVolumeClaim) 마이그레이션 중 발생한 문제들과 해결 방법을 정리합니다.

---

## 1. PVC가 삭제되지 않는 문제 (Terminating 상태)

### 증상
```bash
$ kubectl get pvc -n monitoring
NAME                  STATUS        AGE
prometheus-data-pvc   Terminating   42h
```

### 원인: `pvc-protection` Finalizer

Kubernetes는 PVC가 Pod에 의해 사용 중일 때 삭제를 방지하기 위해 **finalizer**를 사용합니다.

```yaml
metadata:
  finalizers:
    - kubernetes.io/pvc-protection
```

**동작 원리:**
```
kubectl delete pvc
       ↓
Kubernetes: "Pod이 이 PVC 사용 중인가?"
       ↓
    YES → Terminating 상태로 대기
       ↓
Pod 종료 → finalizer 제거 → PVC 삭제 완료
```

### 해결 방법

#### 방법 1: Pod 먼저 종료 (권장)
```bash
# Deployment 스케일 다운
kubectl scale deployment/myapp --replicas=0 -n mynamespace

# Pod 종료 대기
kubectl wait --for=delete pod -l app=myapp -n mynamespace --timeout=60s

# 이제 PVC 삭제 가능
kubectl delete pvc mypvc -n mynamespace
```

#### 방법 2: Finalizer 강제 제거 (주의!)
```bash
# 데이터 손실 가능! 정말 필요한 경우에만 사용
kubectl patch pvc mypvc -n mynamespace \
  -p '{"metadata":{"finalizers":null}}' --type=merge
```

---

## 2. Deployment 스케일 다운 실패

### 증상
스크립트에서 `kubectl scale` 명령이 실패하거나 무시됨.

### 원인: 리소스 타입 누락

```bash
# 잘못된 방법
kubectl scale --replicas=0 myapp  # 타입이 없음!

# 올바른 방법
kubectl scale deployment/myapp --replicas=0
kubectl scale statefulset/myapp --replicas=0
```

### 해결 방법

Deployment와 StatefulSet을 분리해서 검색:

```bash
# Deployment 검색
DEPLOY=$(kubectl get deploy -n $NS -o json | \
  jq -r ".items[] | select(.spec.template.spec.volumes[]?.persistentVolumeClaim.claimName==\"$PVC\") | .metadata.name")

if [ -n "$DEPLOY" ]; then
    kubectl scale deployment/$DEPLOY --replicas=0 -n $NS
fi

# StatefulSet 검색
STS=$(kubectl get sts -n $NS -o json | \
  jq -r ".items[] | select(.spec.template.spec.volumes[]?.persistentVolumeClaim.claimName==\"$PVC\") | .metadata.name")

if [ -n "$STS" ]; then
    kubectl scale statefulset/$STS --replicas=0 -n $NS
fi
```

---

## 3. Pod 종료 대기 없음

### 증상
스케일 다운 직후 다음 단계로 넘어가서 PVC가 아직 사용 중.

### 원인
단순히 `sleep 5`로는 Pod 종료를 보장할 수 없음.

### 해결 방법

```bash
# Pod 완전 종료 대기
for i in {1..60}; do
    RUNNING=$(kubectl get pods -n $NS -l app=$APP --no-headers 2>/dev/null | wc -l)
    if [ "$RUNNING" -eq 0 ]; then
        echo "모든 Pod 종료됨"
        break
    fi
    echo "대기 중... ($i/60)"
    sleep 2
done
```

---

## 4. Migrator Pod이 Pending 상태

### 증상
```bash
$ kubectl get pod pvc-migrator-xxx
NAME                    STATUS
pvc-migrator-xxx        Pending
```

### 원인들

#### 4.1 PVC가 아직 다른 Pod에 마운트됨 (ReadWriteOnce)
```
Warning  FailedScheduling  Pod requires PVC which is already bound
```

**해결:** 기존 Pod 완전 종료 후 migrator 실행

#### 4.2 StorageClass 문제
```
Warning  ProvisioningFailed  config doesn't contain path on node
```

**해결:** StorageClass 설정 확인
```bash
kubectl get sc local-path-hdd -o yaml
kubectl get configmap local-path-config -n kube-system -o yaml
```

#### 4.3 노드 스케줄링 문제
**해결:** Pod이 어느 노드에 스케줄링되는지 확인
```bash
kubectl describe pod pvc-migrator-xxx | grep -A10 Events
```

---

## 5. PVC 이름 변경 시 PV 바인딩 충돌

### 증상
```
Warning  FailedBinding  volume already bound to a different claim
```

### 원인
기존 PVC를 삭제해도 PV는 `Released` 상태로 남아있음. 새 PVC가 같은 PV를 바인딩하려고 할 때 충돌 발생.

### 해결 방법

#### 방법 1: Deployment에서 새 PVC 이름 사용 (권장)
```bash
# 새 PVC 이름 그대로 사용
# deployment.yaml에서 volumes.persistentVolumeClaim.claimName 수정
kubectl patch deployment myapp -n mynamespace \
  -p '{"spec":{"template":{"spec":{"volumes":[{"name":"data","persistentVolumeClaim":{"claimName":"myapp-data-pvc-new"}}]}}}}'
```

#### 방법 2: PV 정리 후 재생성
```bash
# Released 상태 PV 삭제
kubectl delete pv pvc-xxx-xxx

# 새 PVC 생성 (새 PV 자동 생성됨)
```

---

## 6. StorageClass Provisioner 문제

### local-path-provisioner 설정

`local-path-hdd` 같은 커스텀 StorageClass를 사용할 때:

```yaml
# ConfigMap 설정 필요
apiVersion: v1
kind: ConfigMap
metadata:
  name: local-path-config
  namespace: local-path-storage
data:
  config.json: |
    {
      "nodePathMap": [
        {
          "node": "DEFAULT_PATH_FOR_NON_LISTED_NODES",
          "paths": ["/opt/local-path-provisioner"]
        },
        {
          "node": "worker-node-1",
          "paths": ["/mnt/data/local-path-provisioner"]
        }
      ]
    }
```

**주의:** `local-path-provisioner`는 StorageClass 파라미터를 통한 경로 설정을 지원하지 않음. ConfigMap을 통해 설정해야 함.

---

## 올바른 PVC 마이그레이션 절차

### 방법 A: 스크립트 사용 (개선된 버전)

```bash
./migrate-pvc.sh <namespace> <pvc-name> <new-storageclass> [size]
```

### 방법 B: 수동 마이그레이션 (권장)

```bash
# 1. 현재 상태 확인
kubectl get pvc,pod -n $NAMESPACE

# 2. Deployment 스케일 다운
kubectl scale deployment/$DEPLOY --replicas=0 -n $NAMESPACE

# 3. Pod 종료 대기
while kubectl get pods -n $NAMESPACE -l app=$APP --no-headers 2>/dev/null | grep -q .; do
    sleep 2
done

# 4. 새 PVC 생성
kubectl apply -f - <<EOF
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: ${PVC}-new
  namespace: $NAMESPACE
spec:
  accessModes: ["ReadWriteOnce"]
  storageClassName: $NEW_SC
  resources:
    requests:
      storage: $SIZE
EOF

# 5. 데이터 복사 Pod 실행
kubectl apply -f - <<EOF
apiVersion: v1
kind: Pod
metadata:
  name: pvc-migrator
  namespace: $NAMESPACE
spec:
  restartPolicy: Never
  containers:
  - name: migrator
    image: busybox:latest
    command: ["sh", "-c", "cp -av /old/. /new/ && sync"]
    volumeMounts:
    - name: old
      mountPath: /old
    - name: new
      mountPath: /new
  volumes:
  - name: old
    persistentVolumeClaim:
      claimName: $PVC
  - name: new
    persistentVolumeClaim:
      claimName: ${PVC}-new
EOF

# 6. 복사 완료 대기
kubectl wait --for=jsonpath='{.status.phase}'=Succeeded pod/pvc-migrator -n $NAMESPACE --timeout=600s
kubectl logs pvc-migrator -n $NAMESPACE

# 7. Migrator 삭제
kubectl delete pod pvc-migrator -n $NAMESPACE

# 8. Deployment에서 새 PVC 사용하도록 변경
kubectl patch deployment/$DEPLOY -n $NAMESPACE \
  -p '{"spec":{"template":{"spec":{"volumes":[{"name":"data","persistentVolumeClaim":{"claimName":"'${PVC}-new'"}}]}}}}'

# 9. 스케일 업
kubectl scale deployment/$DEPLOY --replicas=1 -n $NAMESPACE

# 10. 기존 PVC 삭제 (선택)
kubectl delete pvc $PVC -n $NAMESPACE
```

---

## 트러블슈팅 체크리스트

| 문제 | 확인 사항 |
|------|-----------|
| PVC Terminating | `kubectl get pods`로 PVC 사용 중인 Pod 확인 |
| Migrator Pending | `kubectl describe pod`로 스케줄링 이벤트 확인 |
| PVC Pending | `kubectl describe pvc`로 provisioning 이벤트 확인 |
| 데이터 손실 | 마이그레이션 전 백업 필수! |

---

## 예방 조치

1. **마이그레이션 전 반드시 백업**
   ```bash
   kubectl get pvc $PVC -n $NS -o yaml > backup-pvc.yaml
   ```

2. **테스트 환경에서 먼저 검증**

3. **스케일 다운 → Pod 종료 확인 → 작업 진행** 순서 준수

4. **WaitForFirstConsumer 모드 이해**
   - PVC가 Pending 상태여도 Pod이 스케줄링되면 Bound됨

