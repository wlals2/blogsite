---
title: "k8s-storage-feat.velero"
date: 2025-11-23T18:28:57+09:00
draft: false
categories: ["k8s","pvc","velero"]
tags: ["k8s","storage","velero","pvc","backup","migration"]
description: "k8s-storage-feat.velero"
author: "늦찌민"
---

# Kubernetes 스토리지 완벽 가이드

> PVC 마이그레이션 삽질 경험을 바탕으로 정리한 스토리지 설계 및 관리 가이드

---

## 목차
1. [Kubernetes 스토리지 기본 개념](#1-kubernetes-스토리지-기본-개념)
2. [StorageClass 선택 가이드](#2-storageclass-선택-가이드)
3. [홈랩/소규모 환경 설계](#3-홈랩소규모-환경-설계)
4. [Velero: 백업과 마이그레이션의 정석](#4-velero-백업과-마이그레이션의-정석)
5. [PVC 마이그레이션 실패 사례와 교훈](#5-pvc-마이그레이션-실패-사례와-교훈)
6. [스토리지 설계 체크리스트](#6-스토리지-설계-체크리스트)

---

## 1. Kubernetes 스토리지 기본 개념

### 핵심 컴포넌트

```
┌─────────────────────────────────────────────────────────────┐
│                         Pod                                  │
│    ┌─────────────────────────────────────────────────┐      │
│    │              Volume Mount                        │      │
│    │              /var/lib/data                       │      │
│    └─────────────────────────────────────────────────┘      │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│              PersistentVolumeClaim (PVC)                     │
│              "나에게 10GB 스토리지를 줘"                      │
│              storageClassName: local-path                    │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│              PersistentVolume (PV)                           │
│              실제 스토리지 리소스                             │
│              hostPath: /opt/local-path-provisioner/...       │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│              StorageClass                                    │
│              "어떤 종류의 스토리지를 어떻게 프로비저닝할지"    │
│              provisioner: rancher.io/local-path              │
└─────────────────────────────────────────────────────────────┘
```

### 주요 용어

| 용어 | 설명 |
|------|------|
| **PV** (PersistentVolume) | 실제 스토리지 리소스 |
| **PVC** (PersistentVolumeClaim) | 스토리지 요청서 |
| **StorageClass** | 스토리지 종류 및 프로비저닝 방법 정의 |
| **Provisioner** | PV를 자동으로 생성하는 컨트롤러 |
| **VolumeBindingMode** | PV-PVC 바인딩 시점 결정 |

### VolumeBindingMode 이해하기

```yaml
# Immediate: PVC 생성 즉시 PV 바인딩
volumeBindingMode: Immediate

# WaitForFirstConsumer: Pod 스케줄링 시 PV 바인딩 (권장)
volumeBindingMode: WaitForFirstConsumer
```

**WaitForFirstConsumer 장점:**
- Pod이 스케줄링될 노드에 PV 생성
- 노드 어피니티 문제 방지
- 리소스 효율적 사용

---

## 2. StorageClass 선택 가이드

### 홈랩에서 자주 사용하는 StorageClass

| StorageClass | Provisioner | 특징 | 사용 사례 |
|--------------|-------------|------|----------|
| **local-path** | rancher.io/local-path | 단순, 빠름, HA 없음 | 개발/테스트 |
| **longhorn** | driver.longhorn.io | 복제, 스냅샷, UI | 프로덕션 워크로드 |
| **nfs-client** | nfs-subdir-external-provisioner | 공유 스토리지 | ReadWriteMany 필요시 |
| **openebs-hostpath** | openebs.io/local | 단순, 빠름 | local-path 대안 |
| **rook-ceph** | rook-ceph.rbd.csi.ceph.com | 엔터프라이즈급 | 대규모 클러스터 |

### 워크로드별 권장 StorageClass

```
┌─────────────────────────────────────────────────────────────┐
│                    워크로드 분류                             │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  데이터베이스 (MySQL, PostgreSQL, MongoDB)                  │
│  └─ 권장: local-path (SSD) 또는 longhorn                   │
│  └─ 이유: 빠른 I/O, 단일 Pod 접근                          │
│                                                             │
│  상태 저장 앱 (Nextcloud, GitLab, Wordpress)                │
│  └─ 권장: longhorn                                          │
│  └─ 이유: 스냅샷, 백업, 복제 기능 필요                      │
│                                                             │
│  모니터링 (Prometheus, Grafana)                             │
│  └─ 권장: local-path                                        │
│  └─ 이유: 데이터 손실 허용 가능, 빠른 쓰기                  │
│                                                             │
│  로그/캐시 (Elasticsearch, Redis)                           │
│  └─ 권장: local-path (또는 emptyDir)                        │
│  └─ 이유: 임시 데이터, 재생성 가능                          │
│                                                             │
│  공유 파일 (여러 Pod에서 접근)                               │
│  └─ 권장: nfs-client 또는 longhorn (RWX 모드)               │
│  └─ 이유: ReadWriteMany 지원 필요                           │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### AccessModes 이해하기

```yaml
accessModes:
  - ReadWriteOnce  # RWO: 단일 노드에서 읽기/쓰기
  - ReadOnlyMany   # ROX: 여러 노드에서 읽기 전용
  - ReadWriteMany  # RWX: 여러 노드에서 읽기/쓰기
```

| AccessMode | 지원 StorageClass |
|------------|------------------|
| RWO | 대부분 지원 |
| RWX | NFS, CephFS, Longhorn (설정 필요) |
| ROX | 대부분 지원 |

---

## 3. 홈랩/소규모 환경 설계

### 권장 구성

```yaml
# 1. 기본 StorageClass: local-path
# 설치: https://github.com/rancher/local-path-provisioner
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: local-path
  annotations:
    storageclass.kubernetes.io/is-default-class: "true"
provisioner: rancher.io/local-path
volumeBindingMode: WaitForFirstConsumer
reclaimPolicy: Delete

---
# 2. HA가 필요한 경우: Longhorn
# 설치: helm install longhorn longhorn/longhorn
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: longhorn
provisioner: driver.longhorn.io
parameters:
  numberOfReplicas: "2"
  staleReplicaTimeout: "30"
volumeBindingMode: Immediate
reclaimPolicy: Delete
```

### 노드별 스토리지 구성 예시

```
┌─────────────────────────────────────────────────────────────┐
│  Master Node (jimin-ab350m-gaming-3)                        │
│  ├─ /opt/local-path-provisioner  (SSD, 100GB)              │
│  └─ /mnt/data/longhorn           (HDD, 500GB)              │
├─────────────────────────────────────────────────────────────┤
│  Worker Node 1 (k8s-worker1)                                │
│  ├─ /opt/local-path-provisioner  (SSD, 100GB)              │
│  └─ /mnt/data/longhorn           (HDD, 500GB)              │
├─────────────────────────────────────────────────────────────┤
│  Worker Node 2 (k8s-worker2)                                │
│  ├─ /opt/local-path-provisioner  (SSD, 100GB)              │
│  └─ /mnt/data/longhorn           (HDD, 500GB)              │
└─────────────────────────────────────────────────────────────┘
```

### 실제 배포 예시

```yaml
# Prometheus - 빠른 쓰기 필요, 데이터 손실 허용
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: prometheus-data
  namespace: monitoring
spec:
  accessModes: ["ReadWriteOnce"]
  storageClassName: local-path
  resources:
    requests:
      storage: 10Gi

---
# PostgreSQL - 데이터 중요, 복제 필요
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: postgres-data
  namespace: database
spec:
  accessModes: ["ReadWriteOnce"]
  storageClassName: longhorn
  resources:
    requests:
      storage: 20Gi
```

---

## 4. Velero: 백업과 마이그레이션의 정석

### Velero란?

Velero는 Kubernetes 클러스터의 **백업, 복원, 마이그레이션**을 위한 오픈소스 도구입니다.

```
┌─────────────────────────────────────────────────────────────┐
│                        Velero                                │
├─────────────────────────────────────────────────────────────┤
│  ✓ 클러스터 리소스 백업 (Deployment, Service, PVC 등)       │
│  ✓ PV 데이터 스냅샷                                         │
│  ✓ 스케줄 백업                                              │
│  ✓ 다른 클러스터로 마이그레이션                             │
│  ✓ StorageClass 매핑 (마이그레이션 시)                      │
│  ✓ 네임스페이스 단위 백업/복원                              │
└─────────────────────────────────────────────────────────────┘
```

### Velero 설치

```bash
# 1. Velero CLI 설치
wget https://github.com/vmware-tanzu/velero/releases/download/v1.12.0/velero-v1.12.0-linux-amd64.tar.gz
tar -xvf velero-v1.12.0-linux-amd64.tar.gz
sudo mv velero-v1.12.0-linux-amd64/velero /usr/local/bin/

# 2. MinIO 설치 (S3 호환 스토리지)
kubectl apply -f https://raw.githubusercontent.com/vmware-tanzu/velero/main/examples/minio/00-minio-deployment.yaml

# 3. Velero 서버 설치
velero install \
  --provider aws \
  --plugins velero/velero-plugin-for-aws:v1.8.0 \
  --bucket velero \
  --secret-file ./credentials-velero \
  --backup-location-config region=minio,s3ForcePathStyle="true",s3Url=http://minio.velero.svc:9000 \
  --use-volume-snapshots=false \
  --use-node-agent
```

### Velero 사용법

#### 백업 생성

```bash
# 전체 클러스터 백업
velero backup create full-backup

# 특정 네임스페이스만 백업
velero backup create nextcloud-backup --include-namespaces nextcloud

# 특정 라벨의 리소스만 백업
velero backup create app-backup --selector app=myapp

# PV 데이터 포함 백업 (중요!)
velero backup create data-backup \
  --include-namespaces nextcloud \
  --default-volumes-to-fs-backup
```

#### 백업 확인

```bash
# 백업 목록
velero backup get

# 백업 상세 정보
velero backup describe nextcloud-backup

# 백업 로그
velero backup logs nextcloud-backup
```

#### 복원

```bash
# 전체 복원
velero restore create --from-backup nextcloud-backup

# 특정 리소스만 복원
velero restore create --from-backup nextcloud-backup \
  --include-resources persistentvolumeclaims,persistentvolumes

# StorageClass 변경하면서 복원 (마이그레이션!)
velero restore create --from-backup nextcloud-backup \
  --storage-class-mappings longhorn:local-path
```

#### 스케줄 백업

```bash
# 매일 자정에 백업
velero schedule create daily-backup --schedule="0 0 * * *"

# 매주 일요일에 전체 백업
velero schedule create weekly-full --schedule="0 0 * * 0" \
  --default-volumes-to-fs-backup

# 보관 기간 설정 (7일)
velero schedule create daily-backup --schedule="0 0 * * *" --ttl 168h
```

### Velero를 이용한 StorageClass 마이그레이션

```bash
# 1. 현재 상태 백업
velero backup create pre-migration \
  --include-namespaces nextcloud \
  --default-volumes-to-fs-backup

# 2. 백업 완료 확인
velero backup describe pre-migration

# 3. 기존 리소스 삭제
kubectl delete namespace nextcloud

# 4. 새 StorageClass로 복원
velero restore create post-migration \
  --from-backup pre-migration \
  --storage-class-mappings longhorn:local-path

# 5. 복원 확인
kubectl get pvc -n nextcloud
```

### Velero vs 수동 마이그레이션

| 항목 | Velero | 수동 스크립트 |
|------|--------|--------------|
| 안전성 | ✅ 높음 | ❌ 낮음 |
| 복잡도 | 쉬움 | 복잡함 |
| 데이터 무결성 | 보장됨 | 수동 확인 필요 |
| 롤백 | 쉬움 | 어려움 |
| StorageClass 매핑 | 내장 기능 | 직접 구현 |
| 학습 곡선 | 중간 | 높음 |

---

## 5. PVC 마이그레이션 실패 사례와 교훈

### 실제 발생한 문제들

#### 문제 1: 스케일 다운 실패

```bash
# 잘못된 코드
DEPLOYMENT=$(kubectl get deploy,sts ... | jq ... | head -1)
kubectl scale --replicas=0 ${DEPLOYMENT}  # 타입 누락!

# 올바른 코드
kubectl scale deployment/${DEPLOYMENT} --replicas=0
```

**결과:** Pod이 계속 Running → PVC 삭제 불가 → 무한 대기

#### 문제 2: PVC Protection Finalizer

```yaml
metadata:
  finalizers:
    - kubernetes.io/pvc-protection
```

Pod이 PVC를 사용 중이면 삭제가 차단됨:
```
PVC 상태: Terminating (영원히)
```

**해결:** Pod 먼저 완전히 종료 후 PVC 삭제

#### 문제 3: PV 바인딩 충돌

```bash
# PVC 삭제 후 같은 이름으로 재생성
kubectl delete pvc mydata
kubectl apply -f new-pvc.yaml  # 같은 이름

# 결과
Warning: volume already bound to a different claim
```

**해결:** PV도 함께 삭제하거나, 새 이름 사용

#### 문제 4: StorageClass Provisioner 설정

```
Warning: config doesn't contain path on node
```

`local-path-hdd` 같은 커스텀 StorageClass 사용 시 ConfigMap 설정 필요

### 교훈

```
┌─────────────────────────────────────────────────────────────┐
│                    핵심 교훈                                 │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  1. PVC 마이그레이션은 위험하다                             │
│     → Velero 같은 도구 사용 권장                            │
│                                                             │
│  2. 스케일 다운 → Pod 종료 확인 → 작업 순서 준수            │
│     → sleep 5 대신 실제 종료 확인 루프 사용                 │
│                                                             │
│  3. 테스트 환경에서 먼저 검증                               │
│     → 프로덕션 데이터로 바로 시도하지 않기                  │
│                                                             │
│  4. 백업은 필수                                             │
│     → 작업 전 반드시 kubectl get pvc -o yaml > backup.yaml  │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

---

## 6. 스토리지 설계 체크리스트

### 초기 설계 시

- [ ] 워크로드별 스토리지 요구사항 파악
  - [ ] I/O 성능 요구사항
  - [ ] 데이터 중요도 (손실 허용 여부)
  - [ ] AccessMode 요구사항 (RWO/RWX)
  - [ ] 용량 예측

- [ ] StorageClass 선정
  - [ ] 기본 StorageClass 지정
  - [ ] 워크로드별 StorageClass 매핑
  - [ ] VolumeBindingMode 설정

- [ ] 백업 전략 수립
  - [ ] Velero 설치 및 구성
  - [ ] 스케줄 백업 설정
  - [ ] 복원 테스트

### 배포 전

- [ ] 테스트 환경에서 PVC 생성 테스트
- [ ] Pod-PVC 마운트 테스트
- [ ] 스토리지 성능 테스트 (fio 등)

### 운영 중

- [ ] 스토리지 용량 모니터링
- [ ] 백업 상태 확인
- [ ] PV/PVC 상태 주기적 점검

### 마이그레이션 시

- [ ] Velero로 백업 생성
- [ ] 테스트 환경에서 복원 테스트
- [ ] 다운타임 계획 및 공지
- [ ] 롤백 계획 수립

---

## 참고 자료

- [Kubernetes Storage Documentation](https://kubernetes.io/docs/concepts/storage/)
- [Velero Documentation](https://velero.io/docs/)
- [Longhorn Documentation](https://longhorn.io/docs/)
- [Local Path Provisioner](https://github.com/rancher/local-path-provisioner)

---

> **작성일:** 2025-11-23
> **작성 계기:** PVC 마이그레이션 스크립트 버그로 인한 대규모 삽질 경험


