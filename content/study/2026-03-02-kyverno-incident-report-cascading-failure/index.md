---
title: "Kyverno Incident Report — 정책 변경과 네트워크 장애가 빚어낸 연쇄 배포 실패"
date: 2026-03-02T18:00:00+09:00
categories: ["study", "Security"]
  - "study"
  - "Security"
tags: ["kubernetes", "kyverno", "incident-report", "exponential-backoff", "admission-controller", "troubleshooting", "istio"]
summary: "3일 전 배포된 Kyverno 정책이 잠복해 있다가, 노드 장애 → Istio Webhook 실패 → Exponential Backoff → Kyverno 차단으로 연쇄 장애가 터졌다. Kubernetes의 Admission Controller 체인이 실전에서 어떻게 맞물리는지를 사고 보고서 형식으로 기록한다."
showtoc: true
tocopen: true
draft: false---
## 사고 요약 (Executive Summary)

| 항목 | 내용 |
|------|------|
| **발생일** | 2026-03-02 |
| **영향 범위** | blog-system namespace의 mysql-exporter Pod |
| **근본 원인** | Kyverno `require-non-root` 정책과 mysql-exporter의 securityContext 누락 (잠복 위반) |
| **트리거** | Worker 노드 NotReady → Pod 재생성 시도 → Kyverno Enforce에 의해 차단 |
| **해결** | securityContext 추가 + rollout restart |
| **복구 시간** | 약 30분 (원인 파악 15분 + 수정/배포 15분) |

---

## 타임라인

```
D-3 (3일 전)
  └─ Kyverno require-non-root 정책 Enforce 배포
     └─ 기존 mysql-exporter Pod: 영향 없음 (이미 실행 중 → 소급 적용 안 됨)
     └─ securityContext 누락 → 잠복 위반 시작

D-0 오전 (사고 당일)
  └─ Worker 노드 3대 NotReady (VM 정지)
     └─ 모든 Pod Eviction 시작
     └─ Cilium 네트워크 단절

D-0 오후
  ├─ Worker 노드 복구 (Ready)
  ├─ Cilium 네트워크 복구
  ├─ WAS, MySQL, WEB: 복구 성공
  │   └─ ArgoCD가 새 ReplicaSet 트리거 → 즉시 생성
  │
  └─ mysql-exporter: 복구 실패 ★
      ├─ 1차 차단: Istio Webhook 연결 실패 (네트워크 불안정 잔존)
      │   └─ ReplicaSet Controller → Exponential Backoff (10s → 20s → ... → 5min)
      ├─ 2차 차단: Backoff 해제 후 Kyverno Webhook 도달
      │   └─ require-non-root 위반 → Pod 생성 거부
      └─ 상태: 0/1 Ready (Prometheus 메트릭 수집 중단)
```

---

## 1단계: 잠복기 — 조용한 보안 정책 업데이트 (3일 전)

사건의 발단은 3일 전 클러스터 전역에 배포된 Kyverno의 `require-non-root` 정책이었다. `blog-system` 네임스페이스에는 위반 시 즉시 차단(Enforce)되는 강력한 규칙이 적용되었다.

```yaml
# require-non-root.yaml (발췌)
spec:
  validationFailureAction: Audit
  validationFailureActionOverrides:
    - action: Enforce
      namespaces:
        - blog-system  # ← 이 namespace에서는 위반 시 즉시 차단
```

하지만 이미 실행 중이던 기존 Pod들에는 이 정책이 **소급 적용되지 않았다**. Kyverno의 Validating Webhook은 **새로운 API 요청(CREATE, UPDATE)**에만 작동하기 때문이다.

mysql-exporter의 Deployment는 이런 상태였다:

```yaml
# 당시 mysql-exporter.yaml (문제 상태)
spec:
  template:
    spec:
      containers:
      - name: mysql-exporter
        image: prom/mysqld-exporter:v0.16.0
        # securityContext: 완전히 누락!
        # → runAsNonRoot 설정 없음
        # → Kyverno require-non-root 위반이지만,
        #   이미 Running이므로 당장은 문제 없음
```

이것이 **잠복 위반**이다. 지뢰가 묻혔지만 아직 밟지 않은 상태.

`background: true` 설정으로 Background Scan이 활성화되어 있었기 때문에, PolicyReport에는 위반이 **기록**되어 있었을 것이다. 하지만 당시 PolicyReport 모니터링 체계가 없었기 때문에 이를 사전에 발견하지 못했다.

---

## 2단계: 트리거 — Worker 노드 장애와 네트워크 단절 (당일 오전)

당일 오전, Worker 노드 3대(VMware VM)가 NotReady 상태에 빠졌다.

```bash
kubectl get nodes
# 출력:
# NAME          STATUS     ROLES           AGE    VERSION
# k8s-cp        Ready      control-plane   ...    v1.31.13
# k8s-worker1   NotReady   <none>          ...    v1.31.13
# k8s-worker2   NotReady   <none>          ...    v1.31.13
# k8s-worker3   NotReady   <none>          ...    v1.31.13
```

노드 장애로 인해 blog-system의 Pod들은 모두 사라졌다. Kubernetes는 이들을 다른 곳에서 재생성하려고 시도하지만, 모든 Worker 노드가 NotReady이므로 스케줄링할 곳이 없다.

```bash
kubectl get pods -n blog-system
# 출력:
# No resources found in blog-system namespace.
```

블로그 접속 시 `no healthy upstream` 에러가 발생했다. Istio Ingress Gateway는 Control Plane(k8s-cp)에서 실행 중이어서 살아있었지만, 뒤에 연결할 Pod이 없었다.

---

## 3단계: 1차 차단 — Istio Webhook과 Exponential Backoff의 늪

노드가 복구되고 Pod 재생성이 시작되었다. API Server가 Pod 생성 요청을 처리하는 첫 번째 관문은 Istio의 **Mutating Webhook**이다 (sidecar 자동 주입).

하지만 네트워크 단절 직후 Cilium이 완전히 안정화되기 전이었고, Istiod와 통신이 불안정했다:

```
Internal error occurred: failed calling webhook "rev.namespace.sidecar-injector.istio.io":
  failed to call webhook:
    Post "https://istiod.istio-system.svc:443/inject?timeout=10s":
      dial tcp 10.99.203.23:443: connect: operation not permitted
```

여기서 Kubernetes의 **ReplicaSet Controller**는 포기하지 않고 재시도를 거듭한다. 하지만 시스템 보호를 위해 실패할 때마다 대기 시간을 2배씩 늘리는 **Exponential Backoff** 상태에 빠진다:

```
1차 시도: 실패 → 10초 대기
2차 시도: 실패 → 20초 대기
3차 시도: 실패 → 40초 대기
4차 시도: 실패 → 80초 대기
...
n차 시도: 실패 → 최대 5분 대기  ← 이 상태에서 고착
```

WAS와 MySQL은 ArgoCD의 개입으로 **새로운 ReplicaSet**이 생성되어 Backoff 없이 즉시 복구되었다. 하지만 단순 Deployment인 mysql-exporter는 기존 ReplicaSet의 긴 Backoff 대기를 그대로 감수해야 했다.

---

## 4단계: 2차 차단 — 노드 복구, 그러나 가로막는 Kyverno

오후에 네트워크가 완전히 안정화되었다. Backoff 대기 시간이 끝난 mysql-exporter의 ReplicaSet Controller가 다시 Pod 생성을 시도한다.

이번에는 첫 번째 관문(Istio Mutating Webhook)을 통과했다. 하지만 바로 다음 관문인 Kyverno의 **Validating Webhook**에서 차단당했다:

```
API Server 요청 처리 체인:

Pod 생성 요청
  ↓
✅ Authentication / Authorization (통과)
  ↓
✅ Istio Mutating Webhook (sidecar 주입 — 네트워크 복구되어 성공)
  ↓
❌ Kyverno Validating Webhook (require-non-root 위반!)
  ↓
거부: "root(UID 0) 실행 금지: securityContext.runAsNonRoot: true 또는
       runAsUser를 0이 아닌 값으로 설정하세요"
```

3일 전에 묻어둔 지뢰가 드디어 터진 것이다. mysql-exporter는 securityContext가 없었고, `runAsNonRoot: true`도 설정되지 않았기 때문에 Kyverno가 정당하게 차단했다.

문제는 이 차단이 **또 다시 Exponential Backoff를 트리거**한다는 것이다:

```
Istio 실패로 인한 Backoff: 10s → 20s → ... → 5min (이미 고착)
  ↓ (대기 후 재시도)
Kyverno 차단으로 또 실패 → Backoff 리셋되지 않음 → 5분 주기 계속
```

결과적으로 mysql-exporter는 **5분에 한 번 시도 → 즉시 Kyverno에 차단 → 다시 5분 대기**를 반복하는 무한 루프에 빠졌다.

---

## 5단계: 해결 — 근본 원인 제거와 컨트롤러 우회

### 원인 진단

```bash
kubectl describe replicaset mysql-exporter-xxx -n blog-system
# Events에서 Kyverno 거부 메시지 확인
```

### 조치 1: securityContext 추가 (근본 원인 제거)

mysql-exporter는 9104번 포트를 사용하므로 root 특권이 전혀 필요 없다 (1024 이상 포트). 매니페스트를 수정했다:

```yaml
# mysql-exporter.yaml (수정 후)
spec:
  template:
    spec:
      # Pod 레벨: non-root 실행 선언
      securityContext:
        runAsNonRoot: true
        runAsUser: 65534      # nobody 유저
        fsGroup: 65534

      containers:
      - name: mysql-exporter
        image: prom/mysqld-exporter:v0.16.0
        # Container 레벨: 최소 권한
        securityContext:
          allowPrivilegeEscalation: false
          capabilities:
            drop: ["ALL"]     # 모든 Linux capability 제거
          readOnlyRootFilesystem: true
```

**UID 65534 (nobody)를 선택한 이유**: 관례적으로 Linux에서 "권한 없는 유저"에 사용되는 UID이다. prom/mysqld-exporter 이미지가 이 UID로 정상 동작함을 확인했다.

### 조치 2: rollout restart (Backoff 우회)

매니페스트 수정 후 Git push → ArgoCD Sync까지 완료했다. 하지만 기존 ReplicaSet은 여전히 **5분 주기의 Backoff** 늪에 빠져 있었다. ArgoCD가 Deployment spec을 업데이트하면 새 ReplicaSet이 생성되어 즉시 시도되어야 하지만, 확실하게 강제하기 위해 rollout restart를 실행했다:

```bash
kubectl rollout restart deployment mysql-exporter -n blog-system
# → 기존 ReplicaSet 종료
# → 새 ReplicaSet 생성 (Backoff 카운터 초기화)
# → 즉시 Pod 생성 시도

kubectl get pods -n blog-system -l app=mysql-exporter
# 출력:
# NAME                              READY   STATUS    RESTARTS   AGE
# mysql-exporter-7d8f9b6c4f-xxxxx   1/1     Running   0          15s
```

새 ReplicaSet은 Backoff가 없으므로 즉시 시도되었고, securityContext가 추가되어 Kyverno를 통과했다. 정상 복구.

---

## 연쇄 장애 흐름도 (전체 그림)

```
D-3: Kyverno Enforce 배포
     └─ mysql-exporter: securityContext 없음 (잠복 위반)
         └─ 기존 Pod Running → 검사 대상 아님

D-0 오전: Worker 노드 NotReady
     └─ 모든 Pod 소멸
         └─ Pod 재생성 시도 시작

     └─ [1차 관문] Istio Mutating Webhook
         └─ Cilium 네트워크 미안정 → Istiod 연결 실패
             └─ ReplicaSet Controller: Exponential Backoff
                 └─ 10s → 20s → 40s → ... → 5min (고착)

D-0 오후: Worker 노드 Ready + 네트워크 복구
     └─ WAS/MySQL/WEB: ArgoCD로 즉시 복구 ✅
     └─ mysql-exporter: 5분 Backoff 대기 중...

     └─ Backoff 해제 → 재시도
         └─ [1차 관문] Istio Webhook: 통과 ✅
         └─ [2차 관문] Kyverno Webhook: 차단 ❌
             └─ require-non-root 위반!
                 └─ 다시 Backoff → 5분마다 차단 반복

     └─ 해결:
         ├─ securityContext 추가 (Kyverno 통과)
         └─ rollout restart (Backoff 초기화)
             └─ 즉시 생성 → 모든 Webhook 통과 → Running ✅
```

---

## 교훈

### 1. Admission Controller 정책은 "배포 시"가 아니라 "재생성 시" 적용된다

Kyverno는 이미 Running인 Pod를 건드리지 않는다. 하지만 **노드 장애, 스케일링, 롤링 업데이트** 등으로 Pod가 재생성되는 순간 정책이 적용된다. 이것이 "잠복 위반"이 위험한 이유다.

**대응**: 정책 배포 후 **PolicyReport를 반드시 확인**하여 기존 리소스의 잠복 위반을 사전에 발견해야 한다.

```bash
# PolicyReport에서 위반 사항 확인
kubectl get policyreport -A
kubectl get policyreport -n blog-system -o yaml | grep -A5 "result: fail"
```

### 2. Exponential Backoff는 복구를 지연시킨다

ReplicaSet Controller의 Backoff은 시스템 보호 메커니즘이지만, 근본 원인이 해결된 후에도 **대기 시간이 남아있다**. 최대 5분까지 기다려야 할 수 있다.

**대응**: 근본 원인 해결 후 `kubectl rollout restart`로 새 ReplicaSet을 생성하면 Backoff을 즉시 초기화할 수 있다.

### 3. Webhook 체인은 순서대로 실패한다

API Server의 Admission Webhook은 **순서대로** 실행된다:
1. Mutating Webhook (Istio) → 실패하면 여기서 멈춤
2. Validating Webhook (Kyverno) → 1을 통과해야 도달

이 때문에 1차 장애(Istio)가 해결된 후에야 2차 장애(Kyverno)가 **비로소 드러난다**. 한 번에 모든 문제가 보이지 않는다.

### 4. 시스템 namespace 제외는 필수이다

만약 `istio-system`, `kyverno` namespace를 정책 제외하지 않았다면, Kyverno 자체의 Pod도 non-root 정책에 걸렸을 것이다. Kyverno가 자기 자신을 차단하는 교착 상태(deadlock)가 발생할 수 있다.

---

## 재발 방지 조치

| # | 조치 | 상태 |
|---|------|------|
| 1 | mysql-exporter에 securityContext 추가 | ✅ 완료 |
| 2 | web(nginx)에 require-non-root 예외 추가 | ✅ 완료 |
| 3 | 새 정책 배포 시 기존 리소스 PolicyReport 확인 프로세스 수립 | 진행 예정 |
| 4 | Kyverno PolicyReport 위반 건수 모니터링 (Grafana 대시보드) | 검토 예정 |

---

## 성과

| 항목 | Before | After |
|------|--------|-------|
| mysql-exporter securityContext | 없음 (root 실행) | runAsNonRoot + DROP ALL + readOnlyRootFilesystem |
| require-non-root 예외 | MySQL만 | MySQL + WEB(nginx) |
| 잠복 위반 탐지 | 없음 (사후 발견) | PolicyReport 확인 프로세스 수립 |
| 복구 소요 시간 | 원인 불명 시 수 시간 | 30분 (원인 파악 체계 확보) |

---

*이 글은 [Kyverno 실전 시리즈](/series/kyverno-실전-시리즈/)의 일부입니다.*
- [Part 1: Kyverno 개념 + Kubernetes Admission Controller 원리](/study/2026-03-02-kyverno-admission-controller-concept/)
- [Part 2: 4개 ClusterPolicy 구현기](/study/2026-03-02-kyverno-4-policies-implementation/)
- **Part 3 (현재 글)**: Incident Report — 정책 변경과 네트워크 장애가 빚어낸 연쇄 배포 실패
