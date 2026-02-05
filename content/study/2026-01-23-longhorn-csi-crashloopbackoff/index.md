---
title: "Longhorn CSI Plugin CrashLoopBackOff 문제"
date: 2026-01-23T10:00:00+09:00
description: "Replica 수와 Worker 노드 수 불일치로 인한 Longhorn 장애 해결"
tags: ["kubernetes", "longhorn", "storage", "csi", "troubleshooting"]
categories: ["study", "Storage", "Troubleshooting"]
---

## 상황

Longhorn CSI Plugin이 CrashLoopBackOff 상태.

```bash
kubectl get pods -A | grep -v Running | grep -v Completed
```

```
longhorn-system   longhorn-csi-plugin-bb6t8   2/3   CrashLoopBackOff   37   57d
```

```bash
kubectl logs -n longhorn-system longhorn-csi-plugin-bb6t8 --all-containers --tail=10
```

```
E0122 03:14:23.017453  1 main.go:68] "Failed to establish connection to CSI driver" err="context deadline exceeded"
```

---

## 원인

### Volume 상태 확인

```bash
kubectl get volumes.longhorn.io -n longhorn-system
```

```
NAME          STATE      ROBUSTNESS   SIZE          NODE
pvc-xxx       attached   degraded     5368709120    k8s-worker1
pvc-yyy       attached   degraded     10737418240   k8s-worker1
```

**핵심 문제**: Volume이 `degraded` 상태

### Replica 수 vs 노드 수

```bash
kubectl get volumes.longhorn.io -n longhorn-system -o yaml | grep numberOfReplicas
```

```
numberOfReplicas: 3
```

| 항목 | 값 |
|------|-----|
| 필요한 Replica | 3개 |
| Worker 노드 | 2개 |
| Anti-Affinity | 같은 노드에 동일 볼륨 replica 불가 |

### 문제 시각화

```
┌─────────────────────────────────────────────────────────┐
│                Longhorn Replica 분산                     │
├─────────────────────────────────────────────────────────┤
│  numberOfReplicas: 3                                    │
│                                                         │
│  ┌─────────────┐    ┌─────────────┐    ┌──────────┐   │
│  │ k8s-worker1 │    │ k8s-worker2 │    │    ???   │   │
│  │  Replica 1  │    │  Replica 2  │    │ Replica 3│   │
│  │     ✅      │    │     ✅      │    │    ❌    │   │
│  └─────────────┘    └─────────────┘    └──────────┘   │
│                                                         │
│  → 3번째 replica 만들 노드 없음 → degraded              │
└─────────────────────────────────────────────────────────┘
```

---

## 해결

### 1. 기존 볼륨 Replica 수 변경

```bash
# 볼륨별로 patch
kubectl patch volume pvc-xxx \
  -n longhorn-system \
  --type merge \
  -p '{"spec":{"numberOfReplicas":2}}'
```

### 2. Helm Values 업데이트 (새 PVC용)

```yaml
# values.yaml
defaultSettings:
  defaultReplicaCount: 2
```

```bash
helm upgrade longhorn longhorn/longhorn \
  -n longhorn-system \
  -f values.yaml
```

### 3. Git 커밋

```bash
git add docs/helm/longhorn/values.yaml
git commit -m "fix: Set Longhorn defaultReplicaCount to 2"
git push
```

---

## 결과

```bash
kubectl get volumes.longhorn.io -n longhorn-system
```

```
NAME          STATE      ROBUSTNESS   SIZE          NODE
pvc-xxx       attached   healthy      5368709120    k8s-worker1   ✅
pvc-yyy       attached   healthy      10737418240   k8s-worker1   ✅
```

```bash
kubectl get pods -n longhorn-system | grep csi
```

```
longhorn-csi-plugin-xxx   3/3   Running   0   5m   ✅
```

| 항목 | Before | After |
|------|--------|-------|
| numberOfReplicas | 3 | 2 |
| Volume Robustness | degraded | healthy |
| CSI Plugin | CrashLoopBackOff | Running |

---

## 정리

### Longhorn Replica 규칙

| 규칙 | 설명 |
|------|------|
| numberOfReplicas ≤ Worker 노드 수 | 필수 |
| Anti-Affinity | 같은 노드에 동일 볼륨 replica 불가 |
| 권장 | 노드 수 - 1 이하 |

### 노드 추가 시

Worker 노드 3개 이상이면 replica 3으로 변경 가능:

```bash
kubectl patch volume <volume-name> \
  -n longhorn-system \
  --type merge \
  -p '{"spec":{"numberOfReplicas":3}}'
```

---

## 관련 명령어

```bash
# Volume 상태 확인
kubectl get volumes.longhorn.io -n longhorn-system

# Replica 상태 확인
kubectl get replicas.longhorn.io -n longhorn-system

# Longhorn Manager 로그
kubectl logs -n longhorn-system -l app=longhorn-manager --tail=50

# Longhorn UI 접속
kubectl port-forward -n longhorn-system svc/longhorn-frontend 8080:80
```
