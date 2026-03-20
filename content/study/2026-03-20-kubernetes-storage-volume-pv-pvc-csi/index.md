---
title: "[Longhorn/Storage 시리즈 #1] Kubernetes에서 Volume이란 무엇인가 — emptyDir부터 CSI까지"
date: 2026-03-20T10:00:00+09:00
categories:
  - study
  - Storage
tags: ["kubernetes", "volume", "pv", "pvc", "storageclass", "csi", "longhorn"]
series: ["Longhorn/Storage 시리즈"]
summary: "K8s Volume은 데이터 그릇이 아니다. emptyDir의 한계에서 시작해 PV/PVC 추상화, StorageClass 동적 프로비저닝, CSI 표준까지 — 왜 이 계층이 필요한지 순서대로 설명한다."
showtoc: true
tocopen: true
draft: false
---

> **시리즈**: Longhorn/Storage 시리즈
>
> - 다음 글: [[Longhorn/Storage 시리즈 #2] Longhorn은 어떻게 동작하는가](#) (예정)

---

## 왜 이 글을 쓰는가

스토리지 개편 작업을 하면서 처음에 한 가지 오해가 있었다.

"Volume이면 데이터가 담겨 있는 무언가 아닌가?"

하지만 Kubernetes에서 Volume은 그런 의미가 아니었다. Volume은 Pod에 **마운트되는 디렉터리**다. 데이터를 어디에 저장할지, 얼마나 유지할지는 전혀 다른 이야기다.

이 오해를 먼저 풀고 나면 왜 PV, PVC, StorageClass, CSI가 필요한지가 자연스럽게 이해된다.

---

## 1. Volume — Pod에 붙는 디렉터리

Kubernetes에서 Volume은 **Pod 스펙에 선언된 마운트 지점**이다. Pod 안의 컨테이너들이 파일을 읽고 쓸 수 있는 경로를 제공한다.

```yaml
spec:
  volumes:
    - name: my-volume
      emptyDir: {}          # 어디에 저장할지 지정
  containers:
    - name: app
      volumeMounts:
        - name: my-volume
          mountPath: /data  # 컨테이너 내부 경로
```

여기서 핵심은 **Volume의 유형**이 "어디에 데이터를 저장할지"를 결정한다는 것이다.

### 주요 Volume 유형

| 유형 | 저장 위치 | 생명주기 |
|------|----------|---------|
| `emptyDir` | Pod가 실행 중인 노드의 임시 공간 | Pod가 삭제되면 사라짐 |
| `hostPath` | 노드의 실제 파일시스템 경로 | 노드에 남지만 다른 노드로 이동 불가 |
| `configMap` | etcd (K8s 내부) | ConfigMap 생명주기에 종속 |
| `secret` | etcd (메모리, 암호화 옵션) | Secret 생명주기에 종속 |

### 문제: Pod 생명주기에 종속

`emptyDir`로 데이터를 저장하다가 Pod가 재시작되면 어떻게 될까?

```bash
# Pod가 OOM Kill로 재시작됨
kubectl get pod mysql-xxx
# STATUS: OOMKilled → Restarting
```

**Pod가 죽으면 emptyDir도 함께 사라진다.** MySQL 데이터, 업로드된 파일, 로그 — 전부.

`hostPath`는 노드에 데이터가 남지만 다른 문제가 있다. Pod가 다른 노드로 스케줄되면 이전 노드의 데이터에 접근할 수 없다.

> 📌 **[사진 위치 1]** emptyDir vs hostPath 데이터 유실 시나리오 다이어그램
> - emptyDir: Pod 재시작 → 데이터 소실
> - hostPath: 노드 A → 노드 B 스케줄 변경 → 데이터 미접근

이것이 "영구 스토리지(Persistent Storage)"가 필요한 이유다.

---

## 2. PV와 PVC — 영속성을 위한 추상화

영구 스토리지 문제를 해결하려면 두 가지가 필요하다:

1. Pod 생명주기와 **독립적인** 스토리지
2. 어느 노드에서 스케줄되어도 **동일하게 접근** 가능한 스토리지

Kubernetes는 이를 **두 개의 리소스**로 분리해서 해결했다.

### PersistentVolume (PV) — 실제 스토리지

PV는 클러스터에 이미 존재하는 스토리지를 K8s 리소스로 표현한 것이다. NFS 서버, 클라우드 디스크, 로컬 디스크 등 실제 스토리지가 PV로 등록된다.

```yaml
apiVersion: v1
kind: PersistentVolume
metadata:
  name: pv-mysql
spec:
  capacity:
    storage: 10Gi
  accessModes:
    - ReadWriteOnce       # 한 노드에서만 읽기/쓰기
  nfs:
    server: 192.168.1.10
    path: /exports/mysql
```

PV는 클러스터 관리자가 만들거나, StorageClass가 자동으로 만든다 (뒤에서 설명).

### PersistentVolumeClaim (PVC) — 스토리지 요청

PVC는 개발자(또는 애플리케이션)가 "이런 스토리지가 필요하다"고 요청하는 리소스다.

```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: mysql-pvc
  namespace: blog-system
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: 10Gi       # 10Gi 이상인 PV를 찾아 바인딩
```

PVC가 생성되면 K8s는 조건에 맞는 PV를 찾아 **바인딩(Bound)** 한다.

```bash
kubectl get pvc -n blog-system
# NAME        STATUS   VOLUME        CAPACITY   ACCESS MODES
# mysql-pvc   Bound    pv-mysql      10Gi       RWO
```

Pod는 PV가 어디 있는지 몰라도 된다. PVC 이름만 알면 된다.

```yaml
spec:
  volumes:
    - name: mysql-data
      persistentVolumeClaim:
        claimName: mysql-pvc    # PV가 어디있는지 신경 쓰지 않아도 됨
```

### accessModes — 누가 어떻게 접근할 수 있는가

PV와 PVC 모두에 `accessModes`를 지정한다. 바인딩이 성공하려면 **둘의 accessModes가 일치**해야 한다.

| 모드 | 약어 | 의미 | 주요 사용처 |
|------|------|------|-----------|
| `ReadWriteOnce` | RWO | 한 노드에서만 읽기/쓰기 | MySQL, 단일 인스턴스 앱 |
| `ReadOnlyMany` | ROX | 여러 노드에서 읽기 전용 | 설정 파일 배포 |
| `ReadWriteMany` | RWX | 여러 노드에서 읽기/쓰기 | 공유 파일 서버, NFS |
| `ReadWriteOncePod` | RWOP | 한 Pod에서만 읽기/쓰기 (K8s 1.22+) | 엄격한 단일 접근 보장 |

Longhorn은 기본적으로 **RWO**만 지원한다. "여러 Pod가 같은 볼륨을 공유하고 싶다"고 RWX를 요청하면 Longhorn에서는 바인딩 자체가 안 된다. 이 경우 NFS나 CephFS 같은 공유 파일시스템이 필요하다.

### PV 생명주기 — 상태가 왜 이렇게 되어있는가

`kubectl get pv`를 치면 STATUS 컬럼에 여러 상태가 보인다. 이 흐름을 모르면 "왜 PVC가 Pending이지?"에서 막힌다.

```
Available → Bound → Released → (Failed)
```

| 상태 | 의미 | 원인 |
|------|------|------|
| `Available` | PVC 바인딩 대기 중 | PV가 생성되었지만 아직 아무 PVC도 사용 안 함 |
| `Bound` | PVC와 연결됨 | 정상 사용 중 |
| `Released` | PVC는 삭제됐지만 PV는 남아 있음 | ReclaimPolicy: Retain일 때 |
| `Failed` | 자동 회수 실패 | Reclaim 과정 중 오류 |

**`Released` 상태가 중요하다.** PVC를 삭제했는데 PV가 `Released`로 남아 있으면 새 PVC가 이 PV에 바인딩되지 않는다. K8s는 `Released` PV를 "아직 이전 데이터가 있을 수 있다"고 보기 때문에 자동 재사용하지 않는다. 수동으로 PV를 삭제하거나 `claimRef`를 지워야 재사용할 수 있다.

### 왜 PV와 PVC를 분리했는가?

이 구조가 처음엔 불필요하게 복잡해 보일 수 있다. 왜 스토리지를 직접 지정하지 않고 PV/PVC 두 단계를 거치는가?

**관심사 분리** 때문이다.

| 역할 | 책임 | 리소스 |
|------|------|--------|
| 클러스터 관리자 | 스토리지 인프라 운영 (NFS, 클라우드 디스크 등) | PV |
| 개발자/앱 운영자 | 애플리케이션 배포, 스토리지 요구사항 정의 | PVC |

개발자는 "10Gi, ReadWriteOnce"만 요청하면 된다. 실제로 NFS인지, SSD인지, 어느 서버인지는 모른다. 관리자는 애플리케이션 코드를 몰라도 스토리지를 독립적으로 관리할 수 있다.

> 📌 **[사진 위치 2]** PV/PVC 바인딩 아키텍처 다이어그램
> - 관리자 영역: PV (NFS, Cloud Disk, Local Disk) → 클러스터에 등록
> - 개발자 영역: PVC (요청) → 조건 매칭 → PV 바인딩
> - Pod: PVC 참조 → 실제 스토리지 접근

---

## 3. StorageClass — 동적 프로비저닝

PV를 수동으로 만드는 방식은 현실에서 한계가 있다. 클러스터에 수십 개의 애플리케이션이 PVC를 요청할 때마다 관리자가 PV를 직접 생성해야 한다면 운영 부담이 크다.

**StorageClass**는 "이 클래스에 해당하는 PVC가 오면, 이렇게 PV를 자동으로 만들어라"는 정책이다.

```yaml
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: longhorn
provisioner: driver.longhorn.io    # 어떤 CSI Driver가 PV를 만들 것인가
parameters:
  numberOfReplicas: "2"            # Longhorn 복제본 수
reclaimPolicy: Delete              # PVC 삭제 시 PV도 삭제
volumeBindingMode: Immediate
```

이제 PVC에 `storageClassName`을 지정하면:

```yaml
spec:
  storageClassName: longhorn        # 이 클래스로 자동 프로비저닝
  resources:
    requests:
      storage: 10Gi
```

관리자가 PV를 미리 만들지 않아도, PVC가 생성되는 순간 StorageClass의 provisioner가 PV를 자동으로 생성하고 바인딩한다. 이것이 **동적 프로비저닝(Dynamic Provisioning)** 이다.

### ReclaimPolicy — PVC를 삭제하면 데이터는 어떻게 되는가

StorageClass의 `reclaimPolicy`는 **PVC가 삭제됐을 때 PV(실제 데이터)를 어떻게 처리할지** 결정한다.

| 정책 | 동작 | 주의 |
|------|------|------|
| `Delete` | PVC 삭제 → PV + 실제 데이터 자동 삭제 | ⚠️ Longhorn 기본값. 실수로 PVC 삭제 시 데이터 복구 불가 |
| `Retain` | PVC 삭제 → PV는 `Released` 상태로 보존 | 데이터 안전하지만 수동 정리 필요 |

Longhorn StorageClass의 기본값은 `Delete`다. Helm rollout 실수나 namespace 삭제로 PVC가 함께 날아가면 **Longhorn 볼륨 데이터도 같이 삭제**된다. 프로덕션 데이터는 `reclaimPolicy: Retain`으로 설정하거나, Longhorn의 백업 기능을 반드시 활성화해야 한다.

> 📌 **[사진 위치 3]** 정적 vs 동적 프로비저닝 비교 다이어그램
> - 정적: 관리자가 PV 수동 생성 → PVC 요청 → 매칭 바인딩
> - 동적: PVC 요청 → StorageClass provisioner가 PV 자동 생성 → 즉시 바인딩

---

## 4. CSI — 스토리지 드라이버 표준

StorageClass에서 `provisioner: driver.longhorn.io`를 썼는데, 이 provisioner는 어디서 오는가?

초기 Kubernetes는 NFS, AWS EBS, GCE Persistent Disk 등의 스토리지 드라이버가 K8s 코어 코드에 직접 포함되어 있었다. 이를 **in-tree 플러그인**이라고 한다.

문제는 명확했다:
- 새 스토리지 벤더가 지원을 추가하려면 K8s 릴리즈 사이클을 기다려야 함
- 버그 수정도 K8s 릴리즈와 함께
- K8s 코어가 스토리지 코드로 점점 비대해짐

**CSI(Container Storage Interface)** 는 이 문제를 해결한 표준 인터페이스다.

K8s와 스토리지 시스템 사이에 **표준 API 계약**을 정의해, 스토리지 벤더가 K8s 코드와 독립적으로 드라이버를 개발할 수 있게 했다.

### CSI Driver의 구조

CSI Driver는 보통 K8s 클러스터 안에 DaemonSet + Deployment로 배포된다.

```bash
kubectl get pods -n longhorn-system | grep -E "csi|driver"
# longhorn-csi-attacher-xxx        Running
# longhorn-csi-provisioner-xxx     Running
# longhorn-csi-resizer-xxx         Running
# longhorn-csi-snapshotter-xxx     Running
```

| 컴포넌트 | 역할 |
|----------|------|
| CSI Provisioner | PVC 요청을 감지해 스토리지 볼륨 생성 |
| CSI Attacher | PV를 노드에 연결(attach) |
| CSI Node Plugin | 노드에서 볼륨을 마운트(mount) |
| CSI Resizer | 볼륨 크기 변경 (온라인 확장) |

### Attach와 Mount — 왜 두 단계로 나뉘는가

CSI에서 볼륨이 Pod에 도달하기까지 두 단계가 있다. 이게 왜 분리되어 있는지가 중요하다.

**Attach (연결)** — CSI Attacher가 담당
- 스토리지 시스템의 볼륨을 **노드에 연결**하는 단계
- 예: AWS EBS 디스크를 EC2 인스턴스에 attach, Longhorn 볼륨을 워커 노드의 블록 디바이스로 노출
- 이 시점에서 노드에는 `/dev/sdb` 같은 블록 디바이스가 생긴다

**Mount (마운트)** — CSI Node Plugin이 담당
- Attach된 블록 디바이스를 **파일시스템으로 포맷하고 컨테이너 경로에 마운트**
- 예: `/dev/sdb`를 ext4로 포맷 → `/var/lib/kubelet/pods/<uid>/volumes/...`에 마운트

두 단계가 분리된 이유는 **실패 처리 방식이 다르기 때문**이다. Attach는 노드 장애 시 강제로 detach해야 하고(Force Detach), Mount는 Pod 스케줄링 이후에만 의미 있다. 이 두 생명주기를 분리해야 노드 장애나 Pod 이동 시 볼륨을 안전하게 다른 노드에 재연결할 수 있다.

> 📌 **[사진 위치 4]** CSI 아키텍처 다이어그램
> - K8s Control Plane (PVC 감지) → CSI Provisioner → 스토리지 시스템에 볼륨 생성
> - Scheduler → Pod를 노드에 배치 → CSI Attacher → 볼륨을 노드에 연결
> - kubelet → CSI Node Plugin → 볼륨을 컨테이너에 마운트

---

## 5. Longhorn은 이 구조 어디에 있는가

Longhorn은 **CSI Driver를 구현한 분산 스토리지 시스템**이다.

```
emptyDir/hostPath
    ↓ (한계: Pod 종속, 노드 종속)
PV/PVC 추상화
    ↓ (한계: 수동 프로비저닝 부담)
StorageClass + 동적 프로비저닝
    ↓ (필요: 표준 인터페이스)
CSI Driver
    ↓ (구현체 중 하나)
Longhorn
```

Longhorn이 특별한 이유는 단순히 디스크를 제공하는 것을 넘어, 워커 노드들의 디스크를 모아 **분산 블록 스토리지**를 구성하기 때문이다.

- 복제본(Replica)을 여러 노드에 분산 → 노드 1개 장애에도 데이터 유지
- 스냅샷과 백업 내장
- 웹 UI에서 볼륨/복제본 상태 시각화

이 내부 동작 원리는 다음 글에서 다룬다.

---

## 정리

| 개념 | 한 줄 정의 | 왜 필요한가 |
|------|-----------|------------|
| Volume | Pod에 마운트되는 디렉터리 | 컨테이너 간 파일 공유 |
| PV | 클러스터에 등록된 실제 스토리지 | Pod와 독립적인 영속성 |
| PVC | 스토리지 요청 리소스 | 개발자/관리자 관심사 분리 |
| accessModes | 노드/Pod 접근 권한 (RWO/ROX/RWX) | 스토리지 공유 방식 제어 |
| PV 생명주기 | Available → Bound → Released | PVC 삭제 후 PV 재사용 여부 판단 |
| ReclaimPolicy | PVC 삭제 시 PV/데이터 처리 방식 | 실수로 인한 데이터 삭제 방지 |
| StorageClass | 동적 프로비저닝 정책 | PV 수동 생성 부담 제거 |
| CSI | 스토리지 드라이버 표준 인터페이스 | 벤더 독립적인 플러그인 생태계 |
| Attach/Mount | 볼륨을 노드 연결 → 컨테이너 마운트 2단계 | 노드 장애 시 안전한 볼륨 재연결 |
| Longhorn | CSI Driver 구현체 (분산 스토리지) | 홈랩 멀티 노드 고가용성 스토리지 |

---

> **다음 글**: [[Longhorn/Storage 시리즈 #2] Longhorn은 어떻게 동작하는가](#) — Replica 분산 방식, 스냅샷, 백업, 홈랩 스토리지 개편 실전 기록
