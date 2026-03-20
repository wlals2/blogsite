---
title: "Longhorn PVC 온라인 용량 확장 — Prometheus 20Gi→40Gi, Loki 10Gi→20Gi 무중단으로"
date: 2026-03-20T18:00:00+09:00
categories:
  - study
  - Storage
tags: ["kubernetes", "longhorn", "pvc", "resize", "prometheus", "loki", "storage"]
summary: "스토리지 개편으로 가용 용량이 늘어난 김에 Prometheus/Loki PVC도 확장. allowVolumeExpansion 조건, kubectl edit pvc 한 줄로 무중단 확장하는 과정을 기록한다."
showtoc: true
tocopen: true
draft: false
---

> 이론 배경: [[Longhorn/Storage 시리즈 #1] Kubernetes에서 Volume이란 무엇인가](/study/2026-03-20-kubernetes-storage-volume-pv-pvc-csi/)

---

## 왜 확장이 필요했나

노드 스토리지 개편([멀티 디스크 → 단일 LVM 통합](/study/2026-03-20-longhorn-multi-disk-to-single-lvm/))으로 각 워커 노드의 Longhorn 가용 공간이 늘어났다. 그 전까지는 스토리지가 부족해 Prometheus와 Loki의 데이터 보존 기간을 짧게 유지했다.

스토리지 개편 완료 후 PVC 현황:

```bash
kubectl get pvc -n monitoring
# NAME                           STATUS   VOLUME         CAPACITY   ACCESS MODES
# prometheus-prometheus-db-0     Bound    pvc-aaa...     20Gi       RWO
# storage-loki-stack-0           Bound    pvc-bbb...     10Gi       RWO
```

| PVC | 기존 | 목표 | 이유 |
|-----|------|------|------|
| Prometheus | 20Gi | 40Gi | 메트릭 보존 기간 15일 → 30일 |
| Loki | 10Gi | 20Gi | 로그 보존 기간 7일 → 14일 |

---

## 사전 조건: allowVolumeExpansion

PVC를 확장하려면 StorageClass에 `allowVolumeExpansion: true`가 설정되어 있어야 한다. 이 설정이 없으면 `kubectl edit pvc`로 용량을 늘려도 K8s가 거부한다.

```bash
kubectl get storageclass longhorn -o yaml | grep allowVolumeExpansion
# allowVolumeExpansion: true ✅
```

Longhorn StorageClass는 기본값으로 `allowVolumeExpansion: true`가 설정되어 있다. 이유는 Longhorn이 온라인(Pod가 살아있는 상태)에서도 볼륨 크기를 확장할 수 있는 CSI Resizer를 포함하기 때문이다.

> 📌 **[사진 위치 1]** PVC 확장 흐름 다이어그램
> - kubectl edit pvc (storage 수정) → K8s가 StorageClass allowVolumeExpansion 확인
> - CSI Resizer 감지 → Longhorn 볼륨 크기 변경 → 파일시스템 리사이즈
> - Pod 재시작 없이 적용

---

## Prometheus PVC 확장

Prometheus는 StatefulSet으로 실행 중이다. Pod를 재시작하지 않고 PVC만 확장할 수 있다.

```bash
kubectl edit pvc prometheus-prometheus-db-0 -n monitoring
```

`spec.resources.requests.storage` 값만 변경한다:

```yaml
spec:
  resources:
    requests:
      storage: 40Gi  # 20Gi → 40Gi 변경
```

저장 후 확인:

```bash
kubectl get pvc prometheus-prometheus-db-0 -n monitoring
# NAME                         STATUS   VOLUME       CAPACITY   ACCESS MODES
# prometheus-prometheus-db-0   Bound    pvc-aaa...   40Gi       RWO   ✅

kubectl describe pvc prometheus-prometheus-db-0 -n monitoring | grep -A3 "Conditions"
# Conditions:
#   Type                      Status
#   FileSystemResizePending   False  ← False면 파일시스템 리사이즈도 완료
```

`FileSystemResizePending: False`가 되면 Pod 재시작 없이 확장이 완료된 것이다.

Pod 내부에서 실제 마운트 크기 확인:

```bash
kubectl exec -n monitoring prometheus-prometheus-0 -- df -h /prometheus
# Filesystem      Size  Used Avail Use%
# /dev/longhorn/  40G   18G   21G  46%  ✅
```

---

## Loki PVC 확장

Loki도 같은 방법으로 진행한다.

```bash
kubectl edit pvc storage-loki-stack-0 -n monitoring
```

```yaml
spec:
  resources:
    requests:
      storage: 20Gi  # 10Gi → 20Gi 변경
```

확인:

```bash
kubectl get pvc storage-loki-stack-0 -n monitoring
# NAME                    STATUS   VOLUME       CAPACITY   ACCESS MODES
# storage-loki-stack-0   Bound    pvc-bbb...   20Gi       RWO   ✅

kubectl exec -n monitoring loki-stack-0 -- df -h /data
# Filesystem      Size  Used Avail Use%
# /dev/longhorn/  20G   4.2G  15G  22%  ✅
```

---

## 결과

| 항목 | Before | After |
|------|--------|-------|
| Prometheus PVC | 20Gi (사용률 ~90%) | 40Gi (사용률 ~46%) |
| Loki PVC | 10Gi (사용률 ~78%) | 20Gi (사용률 ~22%) |
| Prometheus 보존 기간 | 15일 | 30일 |
| Loki 보존 기간 | 7일 | 14일 |
| Pod 재시작 | - | 0회 (무중단) |
| 소요 시간 | - | 각 1분 이내 |

---

## 핵심 정리

**Longhorn PVC 온라인 확장이 가능한 조건**:

1. StorageClass에 `allowVolumeExpansion: true` 설정
2. `kubectl edit pvc` → `spec.resources.requests.storage` 값만 수정
3. CSI Resizer가 자동으로 볼륨 + 파일시스템 확장
4. `FileSystemResizePending: False` 확인 → 완료

**주의**: 확장은 가능하지만 **축소는 불가능**하다. 한번 늘린 PVC는 줄일 수 없다.

---

> **관련 이론**: [[Longhorn/Storage 시리즈 #1] Kubernetes에서 Volume이란 무엇인가](/study/2026-03-20-kubernetes-storage-volume-pv-pvc-csi/)
