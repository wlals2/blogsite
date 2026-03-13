---
title: "단일 MySQL의 10분 다운타임을 수초로 — Percona XtraDB Cluster 전환기"
date: 2026-03-14T10:00:00+09:00
draft: false
categories: ["study", "Kubernetes"]
summary: "홈랩 Kubernetes에서 단일 MySQL Pod 장애 시 10분+이 걸리던 복구 시간을, Percona XtraDB Cluster(PXC) 3노드 + HAProxy 구성으로 수초 자동 Failover로 줄인 과정"
---

> **이론 배경**: [MySQL HA: Galera Replication부터 Percona XtraDB Cluster까지](/study/2026-03-14-mysql-ha-concept/)

---

## 배경 — 단일 MySQL의 복구 시간 문제

홈랩 Kubernetes에서 MySQL을 단일 Pod Deployment로 운영하고 있었다. 단일 Pod 구성에서 Worker 노드가 죽으면 복구까지 얼마나 걸릴까?

| 단계 | 소요 시간 |
|------|----------|
| Node NotReady 감지 | 약 5분 (`node-monitor-grace-period`) |
| Pod Eviction 처리 | 약 1~2분 |
| Longhorn 볼륨 재마운트 | 약 1~2분 |
| InnoDB 복구 | 약 1~3분 |
| **합계** | **10분 이상** |

10분 이상의 다운타임 동안 블로그의 모든 API가 DB 연결 실패로 503을 반환한다. "홈랩이니까 괜찮다"고 생각할 수도 있지만, 포트폴리오 목적으로 운영하는 블로그이므로 가용성이 중요했다.

## 무엇을 선택했는가 — PXC vs InnoDB Cluster vs Primary-Replica

| 방안 | Failover 시간 | 복잡도 | 홈랩 적합성 |
|------|-------------|--------|-----------|
| **Primary-Replica + VIP** | 30초~2분 (수동 또는 Orchestrator) | 중간 | MySQL 기본 제공이지만 수동 개입 필요 |
| **InnoDB Cluster** | 수초 (MySQL Router 자동) | 높음 | Router 별도 배포, 설정 복잡 |
| **Percona XtraDB Cluster** | **수초 (HAProxy 자동)** | 중간 | **Operator로 관리 자동화, GitOps 친화** |

PXC를 선택한 핵심 이유:
- **Percona Operator**가 3노드 클러스터 전체 Lifecycle을 관리 — 노드 장애, 재가입, 백업 모두 자동
- **HAProxy 내장** — Operator가 HAProxy StatefulSet도 함께 배포, 별도 설정 불필요
- **Galera 동기 복제** — 모든 쓰기가 3노드에 동시 커밋 → 데이터 손실 없음

## 구성 — 어떻게 동작하는가

```
WAS Pod
  └─▶ mysql-pxc-haproxy:3306 (ClusterIP Service)
        ├─▶ mysql-pxc-haproxy-0 (HAProxy)
        └─▶ mysql-pxc-haproxy-1 (HAProxy)
              └─▶ mysql-pxc-pxc-{0,1,2} (Galera Cluster)
                    ↕ wsrep 동기 복제 (모든 노드 동시 커밋)
```

HAProxy는 각 PXC 노드에 주기적으로 health check를 수행한다. Primary 노드가 죽으면 HAProxy가 즉시 다른 노드로 트래픽을 전환 — WAS 입장에서는 연결 재시도 한 번으로 복구된다.

**Quorum 원칙**: 3노드 중 2노드(과반수)가 살아있어야 쓰기 가능. 1노드 장애는 무중단, 2노드 동시 장애는 클러스터 중단.

## 구축 과정

### Phase 1: PXC Operator 설치 (GitOps)

Helm Wrapper Chart 패턴으로 ArgoCD App of Apps에 통합했다.

```yaml
# apps/pxc-operator/Chart.yaml
dependencies:
  - name: pxc-operator
    version: 1.19.0
    repository: https://percona.github.io/percona-helm-charts/
```

```yaml
# apps/pxc-operator/values.yaml
pxc-operator:
  watchNamespace: "blog-system"  # ADR-016: 최소 권한 — 전체 클러스터 감시 금지
  disableTelemetry: true
```

`watchNamespace`를 지정한 이유: Operator가 기본적으로 모든 namespace를 감시(`watchAllNamespaces: true`)하면 kube-apiserver에 불필요한 API 호출이 증가한다. blog-system만 감시하도록 제한했다.

### Phase 2: PXC Cluster CR 배포

```yaml
# services/blog-system/pxc/pxc-cluster.yaml 핵심 부분
spec:
  unsafeFlags:
    tls: true
  tls:
    enabled: false    # 홈랩 내부 통신, TLS 불필요

  pxc:
    size: 3
    annotations:
      sidecar.istio.io/inject: "false"  # ADR-003: Server-First 프로토콜 데드락 방지
    affinity:
      antiAffinityTopologyKey: "kubernetes.io/hostname"  # 3노드를 서로 다른 Worker에 분산

  haproxy:
    enabled: true
    size: 2
    annotations:
      sidecar.istio.io/inject: "false"  # HAProxy도 PXC와 직접 통신
```

### Phase 3: 데이터 마이그레이션

기존 MySQL에서 `board` DB를 dump하고 PXC에 import했다.

```bash
# PXC strict mode가 LOCK TABLE을 금지 → --skip-add-locks 필수
kubectl exec mysql-<pod> -n blog-system -- mysqldump \
  -uroot -p<pw> \
  --single-transaction \
  --skip-lock-tables \
  --skip-add-locks \          # 이 옵션 없으면 PXC에서 import 실패
  --routines --triggers \
  board > /tmp/board-dump.sql

kubectl cp /tmp/board-dump.sql blog-system/mysql-pxc-pxc-0:/tmp/
kubectl exec mysql-pxc-pxc-0 -n blog-system -- \
  bash -c "mysql -uroot -p<pw> board < /tmp/board-dump.sql"
```

**검증** — 기존 MySQL vs PXC 전체 노드 데이터 비교:

```
# 출력:
=== 기존 MySQL ===     === PXC node 0 ===    === PXC node 2 (복제 확인) ===
posts: 2               posts: 2              posts: 2
news_articles: 0       news_articles: 0      news_articles: 0
news_sources: 0        news_sources: 0       news_sources: 0
```

node 2에서도 동일한 데이터가 확인됨 — Galera 동기 복제 정상 작동.

### Phase 4: WAS 전환 (Canary 배포)

```yaml
# was-configmap.yaml
SPRING_DATASOURCE_URL: jdbc:mysql://mysql-pxc-haproxy:3306/board?useSSL=false&allowPublicKeyRetrieval=true

# was-rollout.yaml
- name: SPRING_DATASOURCE_PASSWORD
  valueFrom:
    secretKeyRef:
      name: mysql-pxc-secrets   # Operator 자동 생성 Secret
      key: root
```

Git push → ArgoCD Sync → Argo Rollouts Canary 자동 시작 (20% → 50% → 80% → 100%). 새 Pod가 PXC HAProxy 접속에 성공하면 전체 트래픽 전환 완료.

## 마주친 문제들

### 1. Kyverno 정책 차단 (HAProxy Pod 생성 실패)

**증상**: HAProxy Pod가 `admission webhook denied: require-non-root` 에러로 생성 안 됨

**원인**: 클러스터에 `runAsNonRoot: true`를 강제하는 Kyverno 정책이 있는데, PXC CR의 haproxy 섹션에 `containerSecurityContext`를 명시하지 않아 Kyverno가 거부함

**해결**:
```yaml
haproxy:
  containerSecurityContext:
    runAsNonRoot: true
```

### 2. ResourceQuota 초과

**증상**: PXC 노드 3개 + HAProxy 2개 추가로 blog-system namespace의 메모리 quota 초과

**Before**: `requests.memory: 4Gi` (기존 MySQL 단일 기준)
**After**: `requests.memory: 6Gi` (PXC 3×512Mi + HAProxy 2×256Mi 추가)

### 3. PXC import 실패 (LOCK TABLE 금지)

**증상**: `mysqldump --skip-lock-tables`로 dump했는데도 PXC import 시 에러

**원인**: `--skip-lock-tables`는 dump 서버에서 테이블 잠금을 안 하는 옵션이지, dump 파일 안의 `LOCK TABLES` 구문을 제거하지 않음. PXC는 `pxc_strict_mode = ENFORCING`으로 `LOCK TABLES` 구문 자체를 금지함

**해결**: `--skip-add-locks` 옵션 추가 → dump 파일에서 `LOCK TABLES`/`UNLOCK TABLES` 구문 제거

## 결과

| 지표 | Before (단일 MySQL) | After (PXC 3노드) |
|------|-------------------|------------------|
| 노드 장애 시 복구 시간 | 10분+ | **수초** |
| 데이터 손실 가능성 | 있음 (비동기 없음) | 없음 (Galera 동기 복제) |
| 노드 1개 장애 시 서비스 | 중단 | **무중단** |
| 백업 | 별도 CronJob (S3) | PXC 내장 (S3 자동) |
| MySQL Pod 수 | 1개 | 5개 (PXC 3 + HAProxy 2) |
| 스토리지 | 5Gi × 1 | 5Gi × 3 |

**현재 Galera 클러스터 상태**:
```bash
$ kubectl exec mysql-pxc-pxc-0 -n blog-system -- \
    mysql -uroot -p<pw> -e "SHOW STATUS LIKE 'wsrep_%';" 2>/dev/null | grep -E "cluster_size|status|ready"
# 출력:
wsrep_cluster_size    3
wsrep_cluster_status  Primary
wsrep_ready           ON
```

## 다음 단계

- **mysql-exporter 추가**: PXC 전용 메트릭 수집 (현재 미설정)
- **pxc_strict_mode 완화 검토**: 현재 `ENFORCING` → `PERMISSIVE`로 낮추면 일부 호환성 향상 가능하나 보안 trade-off 존재
- **읽기/쓰기 분리**: HAProxy 3309 포트(읽기 전용)를 활용한 읽기 부하 분산
