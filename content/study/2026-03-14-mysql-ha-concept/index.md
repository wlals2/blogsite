---
title: "[홈랩 K8s 아키텍처 시리즈 #1] MySQL HA: Galera Replication부터 Percona XtraDB Cluster까지"
date: 2026-03-14T09:00:00+09:00
description: "단일 MySQL의 한계와 HA 솔루션 비교, Galera Cluster 동기 복제 원리, Percona XtraDB Cluster 아키텍처를 설명하고 홈랩 Kubernetes에 실제 적용한 구성을 소개한다"
tags: ["mysql", "pxc", "galera", "kubernetes", "ha", "percona", "haproxy", "homelab"]
categories: ["study", "Kubernetes"]
series: ["홈랩 K8s 아키텍처 시리즈"]
showtoc: true
tocopen: true
draft: false
---

> **시리즈**: [홈랩 K8s 아키텍처 시리즈]
> - 다음 글: [단일 MySQL의 10분 다운타임을 수초로 — Percona XtraDB Cluster 전환기 #2](/study/2026-03-14-mysql-ha-pxc/)

---

## 1. 단일 MySQL의 한계 — 왜 HA가 필요한가

Kubernetes에서 MySQL을 단일 Pod Deployment로 운영하면 다음 문제가 발생한다.

**Pod 장애 시 복구 흐름**:

```
노드 장애 발생
    ↓ 5분 (node-monitor-grace-period)
K8s가 NotReady 감지
    ↓ 1~2분
Pod Eviction + 다른 노드에 재스케줄
    ↓ 1~2분
Longhorn 볼륨 분리 → 새 노드에 재마운트
    ↓ 1~3분
InnoDB crash recovery (비정상 종료 시)
    ↓
서비스 복구 완료 (최소 10분+)
```

10분 동안 DB와 연결된 모든 API가 503이다. WAS는 HikariCP connection pool을 가지고 있지만, 연결이 끊기면 재연결 시도 중 오류가 계속 발생한다.

**본질적인 문제**: K8s의 Pod 재시작은 "같은 데이터로 다른 곳에서 다시 시작"이지, "항상 살아있는 다른 서버로 즉시 전환"이 아니다. DB HA를 위해서는 **여러 서버가 동시에 동일한 데이터를 갖고 있어야** 한다.

---

## 2. MySQL HA 방식들

### 2.1 Primary-Replica (비동기 복제)

```
Primary ──write──▶ Replica 1
         ──write──▶ Replica 2
```

- Primary에서 쓰고, Binary Log를 Replica에 비동기 전송
- **장점**: 설정 간단, 읽기 부하 분산 가능
- **단점**: Failover 시 수동 개입 또는 Orchestrator 도구 필요, 비동기이므로 Primary가 죽으면 일부 데이터 손실 가능

### 2.2 InnoDB Cluster (MySQL 8.0 내장)

```
MySQL Router ──▶ Primary
            ──▶ Secondary 1 (동기)
            ──▶ Secondary 2 (동기)
```

- MySQL Group Replication + MySQL Router + MySQL Shell 조합
- **장점**: 공식 솔루션, 동기 복제로 데이터 손실 없음
- **단점**: MySQL Router 별도 배포 필요, MySQL Shell로 초기 설정 복잡

### 2.3 Galera Cluster (동기 복제)

```
노드 1 ←── wsrep ───▶ 노드 2
  ↕                      ↕
  └──────── wsrep ────────┘
                노드 3
```

- Write Set Replication(wsrep) 프로토콜로 모든 노드가 동시에 커밋
- **장점**: 어느 노드에도 쓰기 가능 (Active-Active), 데이터 손실 없음, Failover 자동
- **단점**: 쓰기 성능이 가장 느린 노드에 맞춰짐, 최소 3노드 필요 (Quorum)

**Percona XtraDB Cluster(PXC)는 Galera Cluster를 기반으로 Percona가 만든 MySQL 배포판**이다.

---

## 3. Galera Cluster 동작 원리

### 3.1 wsrep — Write Set Replication

Galera의 핵심은 **wsrep API**다. 트랜잭션이 커밋되는 순간 다음이 일어난다:

```
클라이언트가 COMMIT 실행
       ↓
노드 1이 변경 내용을 "Write Set"으로 묶음
       ↓
Write Set을 모든 노드에 브로드캐스트 (멀티캐스트/유니캐스트)
       ↓
모든 노드가 Write Set 수신 확인 응답
       ↓
모든 노드가 동시에 커밋 실행
       ↓
클라이언트에게 성공 응답
```

중요한 점: **모든 노드가 커밋하기 전까지 클라이언트는 응답을 받지 못한다**. 이것이 동기 복제다. 가장 느린 노드가 응답할 때까지 기다리므로, 노드 간 네트워크 지연이 쓰기 성능에 직접 영향을 준다.

### 3.2 SST — State Snapshot Transfer

새 노드가 클러스터에 합류하거나 오랫동안 떨어져 있던 노드가 재합류할 때 사용된다.

```
기존 노드 (Donor)          새 노드 (Joiner)
       │                         │
       │──── 전체 DB 스냅샷 ────▶│
       │     (xtrabackup 사용)    │
       │                         │ DB 복원 완료
       │◀─── 합류 요청 ──────────│
       │                         │
       └──── 클러스터 합류 ───────┘
```

SST는 전체 데이터를 복사하므로 데이터가 많으면 시간이 걸린다. PXC는 기본적으로 xtrabackup을 SST 방법으로 사용한다.

### 3.3 IST — Incremental State Transfer

노드가 잠깐 오프라인이었다가 재합류하는 경우, 빠진 트랜잭션만 전송한다.

```
노드 3이 10분간 오프라인
       ↓
재합류 시도
       ↓
Donor가 gcache(ring buffer)에 빠진 트랜잭션 보유 중?
  → YES: IST로 빠진 트랜잭션만 전송 (빠름)
  → NO:  SST로 전체 복사 (느림)
```

gcache 크기(`gcache.size`)를 크게 설정할수록 IST를 더 오래 활용할 수 있다.

### 3.4 Quorum — 과반수 원칙

Galera는 과반수 노드가 살아있어야 쓰기 작업을 허용한다.

| 총 노드 | 허용 장애 노드 | 필요 생존 노드 |
|--------|-------------|-------------|
| 3      | 1           | 2           |
| 5      | 2           | 3           |

3노드에서 2개가 동시에 죽으면 남은 1개 노드는 자신이 소수파임을 감지하고 **쓰기를 거부**한다. 데이터 불일치를 방지하기 위해서다. 이를 **Split-Brain 방지**라고 한다.

```bash
# 현재 클러스터 상태 확인
mysql -e "SHOW STATUS LIKE 'wsrep_%';"

# 출력 예시:
wsrep_cluster_size     3        ← 클러스터 노드 수
wsrep_cluster_status   Primary  ← Primary(정상) / Non-Primary(소수파)
wsrep_ready            ON       ← 쓰기 가능 여부
wsrep_connected        ON       ← 클러스터 연결 여부
```

---

## 4. Percona XtraDB Cluster (PXC) 아키텍처

PXC는 Galera + Percona Server for MySQL의 조합이다. 순수 Galera에 비해 xtrabackup SST 통합이 더 안정적이고, Percona의 성능 패치가 포함된다.

### 4.1 PXC + HAProxy 구성

직접 PXC 노드에 접속하면 어떤 노드가 Primary인지 매번 확인해야 한다. 실제로는 **HAProxy가 앞에서 라우팅**을 담당한다.

```
애플리케이션
     │
     ▼
HAProxy (L4 TCP Proxy)
  ├── health check → PXC 노드들 상태 확인
  ├── port 3306 → Primary 노드로만 라우팅 (쓰기)
  └── port 3309 → 모든 노드로 라우팅 (읽기 분산)
     │
     ▼
┌────────────────────────────────────┐
│  PXC 클러스터 (Galera)              │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐  │
│  │ pxc-0   │  │ pxc-1   │  │ pxc-2   │  │
│  │ Primary │◄─►│ Replica │◄─►│ Replica │  │
│  │ (쓰기)  │  │ (동기)  │  │ (동기)  │  │
│  └─────────┘  └─────────┘  └─────────┘  │
└────────────────────────────────────┘
```

**Failover 흐름**:
1. `pxc-0`(Primary)가 다운
2. HAProxy가 health check 실패 감지 (수초 이내)
3. HAProxy가 `pxc-1` 또는 `pxc-2`로 트래픽 전환
4. 나머지 2노드로 Galera Quorum 유지 → 쓰기 계속 가능
5. 애플리케이션에서 connection retry 한 번으로 복구

### 4.2 Percona Operator for PXC

Kubernetes에서 PXC를 수동으로 관리하면:
- StatefulSet 3개 (PXC 노드들)
- HAProxy StatefulSet
- Headless Service, ClusterIP Service
- ConfigMap, Secret, PodDisruptionBudget
- 백업 CronJob

이 모든 것을 **Percona Operator**가 단 하나의 CR(Custom Resource)로 관리한다.

```yaml
# 이 CR 하나로 위의 모든 리소스가 자동 생성됨
apiVersion: pxc.percona.com/v1
kind: PerconaXtraDBCluster
metadata:
  name: mysql-pxc
spec:
  pxc:
    size: 3        # 3노드 Galera 클러스터
  haproxy:
    enabled: true  # HAProxy 자동 배포
    size: 2        # HAProxy 2노드 (HA)
```

Operator는 **컨트롤 루프**를 통해 지속적으로 실제 상태와 원하는 상태를 비교하고, 차이가 있으면 수정한다. PXC 노드 하나가 죽으면 자동으로 재생성하고 클러스터에 재합류시킨다.

---

## 5. 홈랩 환경 적용 — 설계 결정 사항

### 5.1 전체 구성

```
blog-system namespace
├── mysql-pxc-haproxy-0  (HAProxy, Worker1)
├── mysql-pxc-haproxy-1  (HAProxy, Worker2)
├── mysql-pxc-pxc-0      (Galera, Worker1, Longhorn 5Gi)
├── mysql-pxc-pxc-1      (Galera, Worker2, Longhorn 5Gi)
└── mysql-pxc-pxc-2      (Galera, Worker3, Longhorn 5Gi)
```

**antiAffinity로 노드 분산**: 같은 Worker에 두 개의 PXC 노드가 올라가지 않도록 강제한다. Worker 1개가 죽어도 Quorum(2노드)이 유지된다.

### 5.2 Istio Sidecar 제외 (ADR-003)

MySQL은 **Server-First 프로토콜**이다. 클라이언트가 먼저 패킷을 보내는 HTTP와 달리, MySQL 서버가 먼저 Greeting 패킷을 보낸다.

```
일반 HTTP (Client-First):
클라이언트 ──GET /──▶ Istio Envoy ──▶ 서버
            ← 200 OK ←

MySQL (Server-First):
클라이언트 ◀── Greeting 패킷 ── MySQL 서버
                        ↑
              Istio Envoy가 첫 바이트를 기다리며
              프로토콜을 감지하려고 차단 상태
→ 데드락 발생 (Greeting이 클라이언트에 도달하지 못함)
```

해결: `sidecar.istio.io/inject: "false"` annotation으로 PXC/HAProxy Pod에서 Istio sidecar를 완전히 제외한다.

### 5.3 TLS 비활성화

PXC는 기본적으로 노드 간 통신에 TLS를 요구한다. 홈랩 내부 망 전용이므로 TLS를 비활성화하고 `unsafeFlags.tls: true`를 설정했다.

> 프로덕션 환경이라면 cert-manager와 연동하여 TLS를 활성화하는 것이 필요하다.

### 5.4 Operator의 RBAC 최소 권한 (ADR-016)

PXC Operator 기본 설정은 `watchAllNamespaces: true`로 클러스터 전체를 감시한다.

```yaml
# values.yaml
pxc-operator:
  watchNamespace: "blog-system"  # blog-system만 감시
```

이유: Operator가 전체 namespace를 감시하면 kube-apiserver에 불필요한 List/Watch 요청이 지속적으로 발생한다. 최소 권한 원칙에 따라 필요한 namespace만 감시하도록 제한했다.

---

## 6. 현재 클러스터 상태 확인 방법

```bash
# 1. Galera 클러스터 상태
kubectl exec mysql-pxc-pxc-0 -n blog-system -- \
  mysql -uroot -p<pw> \
  -e "SHOW STATUS LIKE 'wsrep_%';" 2>/dev/null | grep -E "cluster_size|status|ready"

# 출력:
# wsrep_cluster_size    3
# wsrep_cluster_status  Primary
# wsrep_ready           ON

# 2. HAProxy stats (port 8404)
kubectl port-forward svc/mysql-pxc-haproxy -n blog-system 8404:8404
# → http://localhost:8404/stats 에서 각 PXC 노드 상태 확인

# 3. PXC 및 HAProxy Pod 상태
kubectl get pods -n blog-system -l app.kubernetes.io/instance=mysql-pxc

# 출력:
# mysql-pxc-haproxy-0   2/2   Running   ...
# mysql-pxc-haproxy-1   2/2   Running   ...
# mysql-pxc-pxc-0       1/1   Running   ...
# mysql-pxc-pxc-1       1/1   Running   ...
# mysql-pxc-pxc-2       1/1   Running   ...
```

---

## 다음 단계

- mysql-exporter 추가: `wsrep_cluster_size`, `wsrep_ready` 메트릭을 Prometheus에서 수집해 클러스터 이상 감지

---

> **다음 글 →** [[홈랩 K8s 아키텍처 시리즈 #2] 단일 MySQL의 10분 다운타임을 수초로 — Percona XtraDB Cluster 전환기](/study/2026-03-14-mysql-ha-pxc/)
