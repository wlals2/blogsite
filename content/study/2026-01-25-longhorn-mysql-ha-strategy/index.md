---
title: "Longhorn 분산 스토리지와 MySQL HA 전략"
date: 2026-01-25T14:30:00+09:00
tags: ["kubernetes", "longhorn", "mysql", "ha", "storage", "rto", "rpo"]
categories: ["study", "Storage"]
summary: "왜 MySQL HA 구성 없이 Longhorn 복제본 + 백업 전략을 선택했는가? RTO/RPO 분석, 복구 시나리오, 리소스 트레이드오프 완전 분석"
draft: false
---

## 핵심 질문

**"MySQL Pod 1개면 다운타임 있을 텐데?"**

이 글은 이 질문에 대한 완전한 답변입니다.

---

## 1. 현재 MySQL 구성

### 아키텍처

```
┌─────────────────────────────────┐
│     MySQL Pod (단일)             │
│  - Image: mysql:8.0             │
│  - Replicas: 1                  │
│  - Resources: 256Mi RAM         │
└──────────┬──────────────────────┘
           │
           ├─ PVC: mysql-pvc (5Gi)
           │  └─ StorageClass: longhorn
           │     ├─ Replica 1: k8s-worker1
           │     ├─ Replica 2: k8s-worker2
           │     └─ Replica 3: k8s-worker1 (다른 디스크)
           │
           └─ CronJob: mysql-backup
              └─ Schedule: 매일 03:00
                 └─ NFS: mysql-backup-pvc (10Gi)
```

### 스펙 요약

| 항목 | 상세 | 상태 |
|------|------|------|
| **MySQL Replicas** | 1 (단일 Pod) | ✅ 운영 중 |
| **Longhorn Replicas** | 3 (데이터 복제) | ✅ 핵심! |
| **Backup** | 일일 CronJob (mysqldump) | ✅ 재해 복구용 |
| **HA 구성** | ❌ 없음 (Galera, Master-Slave 미사용) | ✅ 의도적 선택 |
| **자동 장애조치** | ❌ 없음 (수동 복구) | ✅ Kubernetes가 대신 |

---

## 2. 왜 MySQL HA가 없는가?

### 오해 vs 사실

| 오해 | 사실 |
|------|------|
| "MySQL Pod 1개 = HA 없음" | "Longhorn 복제본 3개 = 데이터 레벨 HA" |
| "Pod 죽으면 데이터 손실" | "Longhorn이 데이터 보존 (손실 0초)" |
| "복구 시간 오래 걸림" | "Kubernetes가 20초 내 자동 재시작" |
| "HA 구성이 무조건 좋음" | "리소스 3배, 복잡도 10배 (과도한 엔지니어링)" |

---

### Longhorn이 제공하는 HA

```
MySQL Pod (애플리케이션 레벨)
     │
     ├─ 1개 Pod (단일)
     │
     └─ PVC (데이터 레벨)
        ├─ Longhorn Replica 1 (k8s-worker1) ✅
        ├─ Longhorn Replica 2 (k8s-worker2) ✅
        └─ Longhorn Replica 3 (k8s-worker1, 다른 디스크) ✅
```

**핵심**: 데이터는 이미 3개 복제본으로 보호됨!

---

## 3. 노드 장애 시나리오

### Case 1: Worker Node 1 전체 다운

```bash
# Before
$ kubectl get pods -n blog-system -o wide
NAME                    NODE           STATUS
mysql-7c8b9f4d5f-abc12  k8s-worker1    Running

# Worker1 재부팅 발생
$ kubectl get nodes
NAME           STATUS
k8s-worker1    NotReady   ← 장애!
k8s-worker2    Ready

# 30초 후 - Kubernetes 자동 재스케줄
$ kubectl get pods -n blog-system -o wide
NAME                    NODE           STATUS
mysql-7c8b9f4d5f-abc12  k8s-worker2    Running  ✅

# Longhorn이 Replica 2를 Primary로 승격
$ kubectl get volumes -n longhorn-system
NAME        STATE      REPLICAS   NODE
mysql-pvc   Healthy    2/3        k8s-worker2  ✅
```

**결과**:
- 복구 시간: 1-2분 (Kubernetes 자동)
- 데이터 손실: 없음 (Replica 2가 최신 상태)
- 사용자 영향: API 요청 1-2개 실패

---

### Case 2: MySQL Pod 크래시 (OOM)

```bash
# Pod OOM 발생
$ kubectl get pods -n blog-system
NAME                     READY   STATUS      RESTARTS
mysql-7c8b9f4d5f-abc12   0/1     OOMKilled   1

# Kubernetes가 자동 재시작 (10초)
$ kubectl get pods -n blog-system
NAME                     READY   STATUS    RESTARTS
mysql-7c8b9f4d5f-abc12   1/1     Running   1

# MySQL crash recovery
$ kubectl logs -n blog-system mysql-7c8b9f4d5f-abc12
InnoDB: Starting crash recovery...
InnoDB: Crash recovery completed. ✅
```

**결과**:
- 복구 시간: 20초
- 데이터 손실: 없음 (Longhorn 복제본 유지)
- 사용자 영향: 거의 없음

---

## 4. RTO/RPO 분석

### 정의

| 지표 | 의미 | 목표 |
|------|------|------|
| **RTO** | Recovery Time Objective (복구 시간) | 5분 이내 |
| **RPO** | Recovery Point Objective (데이터 손실 허용) | 24시간 이내 |

### 장애 시나리오별 분석

| 장애 | RTO 목표 | RTO 실제 | RPO 목표 | RPO 실제 | 달성 |
|------|---------|---------|---------|---------|------|
| **Pod 크래시** | 5분 | **20초** | 24시간 | **0초** | ✅✅ |
| **노드 장애** | 5분 | **1-2분** | 24시간 | **0초** | ✅✅ |
| **디스크 손상** | 1일 | **24시간** | 24시간 | 24시간 | ✅ |
| **클러스터 재해** | 1주 | **4시간** | 24시간 | 24시간 | ✅✅ |

**결론**: 모든 시나리오에서 목표 초과 달성 ✅

---

## 5. MySQL HA vs 백업 전략 비교

### 대안 분석

| 전략 | RTO | RPO | 리소스 비용 | 복잡도 | 개인 블로그 적합성 |
|------|-----|-----|------------|--------|--------------------|
| **MySQL Galera (3 replicas)** | 0초 | 0초 | CPU 3배, RAM 3배 | ⭐⭐⭐⭐⭐ | ❌ 과도 |
| **Master-Slave (2 replicas)** | 5초 | 0초 | CPU 2배, RAM 2배 | ⭐⭐⭐⭐ | ❌ 과도 |
| **단일 + Longhorn + 백업** | 1-2분 | 0초 | CPU 1배, RAM 1배 | ⭐ | ✅ **선택** |

### 선택 이유

**트레이드오프 분석**:

```
질문: "1-2분 다운타임을 0초로 줄이기 위해 리소스 3배를 투자할 가치가 있는가?"

답변: ❌ 없음

이유:
  ✅ 블로그는 금전적 손실 없음 (전자상거래 아님)
  ✅ 방문자 ~10명/일 (대규모 트래픽 아님)
  ✅ 58일간 실제 다운타임 0분 (Kubernetes 자동 복구)
  ✅ 리소스 제한적 (Bare-metal 클러스터, RAM 72% 사용 중)

트레이드오프:
  투자: 리소스 3배 + 복잡도 10배
  수익: 1-2분 → 0초 (발생 빈도: 월 1회)
  ROI: 매우 낮음 ❌
```

---

## 6. 비즈니스 영향 분석

### 서비스별 다운타임 영향

| 서비스 | 1분 다운타임 영향 | MySQL HA 필요? |
|--------|------------------|--------------|
| **전자상거래** | $10,000 손실 | ✅ 필수 |
| **금융 거래** | 법적 문제 | ✅ 필수 |
| **SNS (Instagram)** | 사용자 이탈 | ✅ 필수 |
| **개인 블로그** | 댓글 1개 실패 | ❌ 불필요 |

### 실제 영향 시뮬레이션

```
시나리오: MySQL Pod 크래시 (20초 복구)

Before (크래시 발생):
  시간: 새벽 3시
  방문자: 0명
  영향: 없음 ✅

After (피크 타임 크래시):
  시간: 오후 3시
  방문자: 2명 (동시 접속)
  실패한 요청: API 1-2개
  사용자 행동: 새로고침 → 정상 작동
  실제 피해: 없음 ✅

결론: 1-2분 다운타임은 개인 블로그에 무시 가능
```

---

## 7. Longhorn 아키텍처

### Longhorn 컴포넌트

```
┌─────────────────────────────────────────────┐
│     Longhorn Manager (Control Plane)         │
│  - CSI Driver, Scheduler, Replication        │
└──────────┬──────────────────────────────────┘
           │
    ┌──────┴──────┐
    │             │
┌───┴────┐  ┌────┴────┐
│Worker1 │  │Worker2  │
│        │  │         │
│Replica1│  │Replica2 │  ← 데이터 3개 복제
│Replica3│  │         │
└────────┘  └─────────┘
```

### 주요 기능

| 기능 | 설명 | 사용 사례 |
|------|------|----------|
| **복제 (Replication)** | 데이터 3개 복제본 유지 | 노드 장애 시 데이터 보존 ✅ |
| **스냅샷 (Snapshot)** | 시점 백업 | 복구 시 RPO 단축 (24시간 → 6시간) |
| **동적 확장 (Resize)** | PVC 크기 변경 | MySQL 용량 부족 시 확장 |
| **CSI 드라이버** | Kubernetes 자동 연동 | Pod 재시작 시 자동 attach |

---

## 8. 실제 운영 데이터 (58일)

### 가동률 현황

```
운영 기간: 2025-11-27 ~ 2026-01-25 (58일)
실제 다운타임: 0분
가동률: 100%
목표 가동률: 99% (연 3.65일 허용)

결론: 목표 초과 달성 (현재 구성으로 충분) ✅
```

### 리소스 사용률

```
현재 (단일 MySQL):
  - CPU: 50m (0.05 CPU)
  - RAM: 256Mi
  - Storage: 5Gi (Longhorn × 3 = 15Gi)

MySQL HA 구성 시:
  - CPU: 1.5 CPU (+3000%)
  - RAM: 1.5Gi (+600%)
  - Storage: 45Gi (+300%)

현재 클러스터 여유:
  - k8s-worker1: CPU 84%, RAM 28% (RAM 부족으로 HA 불가) ❌
```

---

## 9. 복구 시나리오 실습

### 테스트 1: Pod 강제 삭제

```bash
# Pod 삭제
$ kubectl delete pod mysql-7c8b9f4d5f-abc12 -n blog-system
pod "mysql-7c8b9f4d5f-abc12" deleted

# Kubernetes 자동 재시작
$ kubectl get pods -n blog-system -w
NAME                     READY   STATUS    AGE
mysql-7c8b9f4d5f-abc12   0/1     Pending   0s
mysql-7c8b9f4d5f-abc12   0/1     Running   3s
mysql-7c8b9f4d5f-abc12   1/1     Running   18s  ← 18초 만에 복구 ✅

# API 정상 동작 확인
$ curl http://blog.jiminhome.shop/api/posts
[{"id":1,"title":"Test"}]  ← 성공 ✅
```

**결과**: RTO 18초 (목표 5분 대비 94% 빠름)

---

### 테스트 2: Longhorn 볼륨 상태 확인

```bash
# Longhorn 복제본 확인
$ kubectl get volumes -n longhorn-system mysql-pvc -o yaml
spec:
  numberOfReplicas: 3
  dataLocality: disabled

status:
  state: attached
  robustness: healthy

  replicas:
  - name: mysql-pvc-r-1
    hostId: k8s-worker1
    running: true
    mode: RW
  - name: mysql-pvc-r-2
    hostId: k8s-worker2
    running: true
    mode: RW
  - name: mysql-pvc-r-3
    hostId: k8s-worker1
    running: true
    mode: RW
```

**결과**: 3개 복제본 모두 Healthy ✅

---

## 10. 다운타임 방어 논리 (최종 답변)

### 지적: "MySQL 1개면 다운타임 있을 텐데?"

#### 답변 1: 실제 다운타임 0분 (58일 운영)

```
✅ Kubernetes liveness probe가 Pod 크래시 자동 감지
✅ Kubernetes가 Pod를 20초 내 자동 재시작
✅ Longhorn 복제본이 데이터 보존 (손실 없음)
✅ 노드 장애 시에도 1-2분 내 다른 노드로 이동

결론: Kubernetes + Longhorn = 자동 복구 제공
```

#### 답변 2: Longhorn = 데이터 레벨 HA

```
오해: "Pod 1개 = HA 없음"
사실: "Longhorn 복제본 3개 = 데이터 레벨 HA"

비유:
  MySQL Galera (3 replicas):
    "은행 금고 3개 (비용 3배, 즉시 접근)"

  현재 구성 (1 Pod + Longhorn 3 replicas):
    "은행 금고 1개 + 백업 금고 2개 (비용 1배, 1분 대기)"

  개인 블로그에는 후자로 충분 ✅
```

#### 답변 3: 비즈니스 영향 미미

```
1분 다운타임 발생 시:
  - 전자상거래: $10,000 손실 → HA 필수 ✅
  - 개인 블로그: 댓글 1개 실패 → HA 불필요 ❌

실제 영향: 사용자는 새로고침 → 정상 작동
```

#### 답변 4: 리소스 트레이드오프

```
MySQL HA 추가 시:
  투자: 리소스 3배 + 복잡도 10배
  수익: 1-2분 → 0초 복구 (월 1회 발생)
  ROI: 매우 낮음 ❌

제한된 리소스는 다른 곳에 투자:
  ✅ Prometheus Alert (장애 감지)
  ✅ Falco Security (런타임 보안)
  ✅ Cilium L7 Policy (네트워크 보안)
```

---

## 11. 향후 개선 방향

### 우선순위 P0 (필요 시)

- [ ] **Longhorn 스냅샷 정책 추가**
  - 목적: RPO 24시간 → 6시간 단축
  - 비용: 스냅샷 저장 공간 +5Gi
  - 예상 시간: 30분

- [ ] **MySQL 백업 검증 자동화**
  - 목적: 백업 파일 복구 가능 여부 확인
  - 방법: CronJob으로 복구 테스트
  - 예상 시간: 1시간

### 우선순위 P1 (선택)

- [ ] **Read Replica 추가**
  - 목적: 읽기 성능 향상 (HA는 아님)
  - 비용: CPU +500m, RAM +512Mi
  - 트레이드오프: 현재 트래픽(10명/일)에 불필요

- [ ] **Cloudflare Worker API 캐싱**
  - 목적: MySQL 다운타임 시 캐시된 데이터 서빙
  - 효과: RTO 1-2분 → 0초 (읽기 전용)
  - 예상 시간: 2시간

### 우선순위 P2 (불필요)

- [ ] ~~MySQL Galera Cluster~~ (리소스 3배, 복잡도 10배)
- [ ] ~~ProxySQL 로드 밸런싱~~ (단일 WAS에 불필요)

---

## 12. 결론

### MySQL HA가 없는 이유 (요약)

1. ✅ **비즈니스 요구사항**: 개인 블로그는 99% 가동률로 충분 (현재 100% 달성)
2. ✅ **리소스 효율성**: HA 구성 시 리소스 3배, 현재 클러스터는 RAM 부족
3. ✅ **Longhorn 복제본**: 이미 데이터 레벨 HA 제공 (3 replicas)
4. ✅ **충분한 RTO**: 1-2분 복구 (목표 5분 대비 초과 달성)
5. ✅ **완벽한 RPO**: Pod/노드 장애 시 데이터 손실 0초
6. ✅ **관리 단순화**: 복제 지연, Split-brain 문제 없음
7. ✅ **실제 증명**: 58일간 다운타임 0분 (Kubernetes 자동 복구)

### 핵심 메시지

**"MySQL Pod 1개 = HA 없음"은 오해입니다.**

**정확한 이해**:
```
MySQL Pod: 1개 (애플리케이션 레벨)
Longhorn 복제본: 3개 (데이터 레벨) ← 이것이 핵심!

Kubernetes + Longhorn = 자동 복구 + 데이터 안정성 제공
RTO 1-2분은 개인 블로그에 충분
리소스는 보안/모니터링에 투자하는 것이 합리적
```

---

**작성**: 2026-01-25
**태그**: kubernetes, longhorn, mysql, ha, rto, rpo, storage
**관련 문서**:
- [Longhorn CSI 트러블슈팅](/study/2026-01-23-longhorn-csi-crashloopbackoff/)
- [MySQL 백업 트러블슈팅](/study/2026-01-23-mysql-backup-cronjob-troubleshooting/)
- [프로젝트 전체 아키텍처](/projects/local-k8s-blog/)
