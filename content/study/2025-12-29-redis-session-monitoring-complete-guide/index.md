---
title: "Redis Session 모니터링 완벽 가이드"
date: 2025-12-29
categories: ["Infrastructure & IaC"]
tags: ["Redis", "Spring Session", "Monitoring", "Grafana", "Prometheus", "Kubernetes", "Session Clustering"]
description: "Spring Session과 Redis를 활용한 세션 클러스터링 구현부터 실시간 모니터링까지, WAS Pod 간 세션 공유의 모든 것을 다룹니다."
slug: redis-session-monitoring-complete-guide
---

> **작성 배경**: WAS Pod를 2개로 늘렸더니 로그인 무한 루프에 빠졌던 경험에서 시작된, 세션 클러스터링 구현과 모니터링 여정입니다.

---

## 시작: 로그인 무한 루프의 악몽

2025년 12월 26일, 저는 큰 문제에 부딪혔어요.

WAS Pod를 1개에서 2개로 늘렸을 뿐인데... 로그인이 안 되는 거예요. 정확히는 **로그인 무한 루프**에 빠졌죠.

```
사용자 요청 → WAS Pod 1 → 로그인 성공
다음 요청 → WAS Pod 2 → 세션 없음 → 로그인 페이지로 리다이렉트
다음 요청 → WAS Pod 1 → 로그인 상태 유지
다음 요청 → WAS Pod 2 → 세션 없음 → 로그인 페이지로 리다이렉트
...
```

"뭐지? 뭐가 문제지?" 하면서 로그를 확인했어요.

```bash
$ kubectl logs -n petclinic was-pod-1
INFO: User logged in successfully (session: abc-123)

$ kubectl logs -n petclinic was-pod-2
WARN: No session found for request
```

**원인을 알았어요!**

각 WAS Pod가 **독립적인 메모리**에 세션을 저장하고 있었던 거죠. Pod 1에서 로그인한 세션은 Pod 1의 메모리에만 있고, Pod 2는 그 세션을 알 수 없었어요.

---

## 해결책: Spring Session + Redis

### 왜 Redis를 선택했나요?

세션 공유 방법은 여러 가지예요.

| 방법 | 장점 | 단점 | 선택 이유 |
|------|------|------|----------|
| **Redis** | 빠름<br>Spring 지원 우수 | SPOF 가능성 | ✅ **선택** |
| Sticky Session | 구현 간단 | Pod 재시작 시 세션 손실 | ❌ 안정성 부족 |
| Database | 영구 저장 | 느림 (I/O) | ❌ 성능 이슈 |
| Hazelcast | 분산 캐시 | 복잡함 | ❌ 오버엔지니어링 |

Redis를 선택한 가장 큰 이유는 **Spring Session과의 완벽한 통합**이었어요. 설정 몇 줄만 추가하면 바로 동작하거든요.

### Spring Session 구현

**application.yml**에 이 설정을 추가했어요.

```yaml
spring:
  session:
    store-type: redis
    redis:
      flush-mode: on_save
      namespace: spring:session
  redis:
    host: redis-master.petclinic.svc.cluster.local
    port: 6379
    timeout: 60000

server:
  servlet:
    session:
      timeout: 30m
```

그리고 Redis를 Helm으로 배포했어요.

```bash
helm install redis bitnami/redis \
  --namespace petclinic \
  --set architecture=standalone \
  --set auth.enabled=false \
  --set master.persistence.enabled=true \
  --set master.persistence.size=1Gi
```

**핵심 포인트**:
- `architecture=standalone`: 일단 단일 Redis로 시작 (나중에 HA 고려)
- `auth.enabled=false`: 간단한 테스트용 (프로덕션에서는 비활성화 금지!)
- `persistence.enabled=true`: Redis 재시작 시에도 세션 유지

### 세션 공유 동작 확인

설정을 적용하고 다시 테스트했어요.

```
1. 사용자 A → WAS Pod 1로 로그인
   - Spring Session이 Redis에 세션 저장
   - Redis Key: spring:session:sessions:d5f2b1a8-...
   - TTL: 30분

2. 사용자 A의 다음 요청이 WAS Pod 2로 라우팅
   - WAS Pod 2가 Redis에서 세션 조회
   - 세션 데이터 존재 → 로그인 상태 유지 ✅
```

**드디어 성공!** 로그인 무한 루프가 사라졌어요!

---

## 다음 문제: 세션이 보이지 않는다

세션 공유는 해결했지만, 새로운 고민이 생겼어요.

"지금 Redis에 세션이 몇 개 저장되어 있지?"
"메모리는 충분한가?"
"세션이 갑자기 삭제되면 어떻게 알지?"

이런 질문들에 답할 수가 없었어요. Redis CLI로 직접 확인하는 건 너무 불편하거든요.

```bash
$ kubectl exec -n petclinic redis-master-0 -- redis-cli DBSIZE
(integer) 1
```

이걸 매번 확인할 순 없잖아요? 그래서 **모니터링**이 필요했어요.

---

## Redis Exporter: 메트릭의 변환자

### Redis는 Prometheus를 모른다

여기서 알게 된 사실이 하나 있어요.

**Redis는 Prometheus 메트릭을 직접 노출하지 않아요.**

Redis의 모니터링 방법은 `INFO` 명령어예요.

```bash
$ kubectl exec -n petclinic redis-master-0 -- redis-cli INFO
# Server
redis_version:7.0.5
uptime_in_seconds:86400

# Memory
used_memory:1289472
used_memory_human:1.23M

# Stats
total_connections_received:150
```

이건 사람이 읽기엔 좋지만, Prometheus가 수집하기엔 어려운 형식이에요.

그래서 **Redis Exporter**가 필요한 거죠!

```
Redis (INFO 명령)
  ↓
Redis Exporter (변환)
  ↓
Prometheus (수집)
  ↓
Grafana (시각화)
```

### Redis Exporter 배포

Redis Exporter는 간단한 Deployment로 배포했어요.

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: redis-exporter
  namespace: petclinic
spec:
  replicas: 1
  template:
    spec:
      containers:
      - name: redis-exporter
        image: oliver006/redis_exporter:v1.55.0-alpine
        args:
          - --redis.addr=redis-master.petclinic.svc.cluster.local:6379
          - --web.listen-address=:9121
        ports:
        - containerPort: 9121
          name: metrics
```

배포하고 메트릭을 확인해봤어요.

```bash
$ kubectl exec -n petclinic redis-exporter-xxx -- \
  wget -q -O- http://localhost:9121/metrics | grep "redis_up"
redis_up 1
```

완벽! Redis 연결이 정상이에요.

### Prometheus 수집 설정

하지만 여기서 끝이 아니었어요.

Grafana 대시보드를 만들어서 확인했더니... **데이터가 없는 거예요!**

"왜지? Redis Exporter는 정상인데?"

알고 보니 **Prometheus가 수집하지 않고 있었어요**.

Prometheus Operator를 사용하는 환경에서는 **ServiceMonitor**라는 리소스가 필요했거든요.

```yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: redis-exporter
  namespace: petclinic
  labels:
    release: kube-prometheus  # 이 레이블이 핵심!
spec:
  selector:
    matchLabels:
      app: redis-exporter
  endpoints:
  - port: metrics
    interval: 30s
    path: /metrics
```

**왜 `release: kube-prometheus` 레이블이 필요한가요?**

Prometheus Operator는 이 레이블을 기준으로 ServiceMonitor를 찾아요. 레이블이 없으면 무시하죠.

이걸 추가하고 나니 Prometheus가 정상적으로 수집하기 시작했어요!

```bash
# Prometheus 타겟 확인
$ kubectl port-forward -n monitoring svc/kube-prometheus-prometheus 9090:9090
# 브라우저: http://localhost:9090/targets
# redis-exporter (1/1 up) ✅
```

---

## Grafana Dashboard 007: 세션의 시각화

### 어떤 메트릭을 보여줄까?

메트릭 수집은 성공했으니, 이제 **대시보드**를 만들어야 했어요.

처음에는 "모든 메트릭을 다 보여주면 되지 않을까?" 생각했는데, 그러면 너무 복잡해지더라고요.

**핵심 질문을 정리했어요:**

1. Redis가 살아있나?
2. 현재 세션 수는?
3. 메모리는 충분한가?
4. WAS Pod들이 정상적으로 연결되어 있나?
5. 세션이 강제 삭제되고 있진 않나?

### Dashboard 구성

이 질문들에 답할 수 있도록 대시보드를 4개 Row로 나눴어요.

#### Row 1: Redis Status (상태 요약)

```
┌─────────────┬─────────────┬─────────────┬─────────────┬─────────────┐
│ Redis       │ Active      │ Connected   │ Memory      │ Memory      │
│ Status      │ Sessions    │ Clients     │ Used        │ Fragmentation│
│             │             │             │             │             │
│    🟢       │     12      │      9      │   1.5MB     │    1.2      │
│    UP       │             │             │             │             │
└─────────────┴─────────────┴─────────────┴─────────────┴─────────────┘
```

각 패널의 메트릭:

**Redis Status**:
```promql
redis_up{namespace="petclinic"}
```
- 1 = UP (녹색)
- 0 = DOWN (빨간색)

**Active Sessions**:
```promql
redis_db_keys{db="db0",namespace="petclinic"}
```
- Spring Session이 DB0에 세션 저장
- 세션 1개당 2-3개 키 생성

**Connected Clients**:
```promql
redis_connected_clients{namespace="petclinic"}
```
- WAS Pod 2개 = 약 4-10 클라이언트

**Memory Used**:
```promql
redis_memory_used_bytes{namespace="petclinic"}
```
- 세션 100개 ≈ 100-500KB

#### Row 2: Session Trends (세션 추이)

시간대별로 세션 수와 메모리 사용량을 그래프로 표시했어요.

```promql
# 세션 수 추이
redis_db_keys{db="db0",namespace="petclinic"}

# 메모리 사용률
redis_memory_used_bytes{namespace="petclinic"} /
redis_memory_max_bytes{namespace="petclinic"} * 100
```

이 그래프를 보면 피크 타임을 한눈에 알 수 있어요!

#### Row 3: Redis Performance

```promql
# 초당 처리 명령 수
rate(redis_commands_processed_total{namespace="petclinic"}[1m])

# Eviction (강제 삭제) 속도
rate(redis_evicted_keys_total{namespace="petclinic"}[5m])
```

**Eviction이 뭔가요?**

Redis 메모리가 부족하면 오래된 키를 강제로 삭제해요. 이게 **Eviction**이에요.

세션이 Eviction되면 = 사용자가 갑자기 로그아웃됨!

그래서 이 메트릭이 0이 아니면 **즉시 조치**해야 해요.

#### Row 4: WAS Pod Status

Redis뿐만 아니라 WAS Pod 상태도 함께 모니터링했어요.

```promql
# Node별 WAS Pod 분산
count(kube_pod_info{pod=~"was-.*"}) by (node)

# WAS Pod 메모리 사용량
container_memory_usage_bytes{pod=~"was-.*"}

# WAS JVM Heap
jvm_memory_heap_used_bytes / jvm_memory_heap_max_bytes * 100
```

이렇게 하면 세션 문제가 Redis 때문인지, WAS Pod 때문인지 구분할 수 있어요.

---

## 실전 트러블슈팅 경험담

### 사건 1: redis_up = 0

어느 날 아침, Slack 알림이 왔어요.

```
⚠️ Redis is DOWN!
redis_up{namespace="petclinic"} = 0
```

"뭐야!" 하면서 급하게 확인했죠.

```bash
$ kubectl get pods -n petclinic | grep redis
redis-master-0   0/1     CrashLoopBackOff   5          10m
```

Redis Pod가 계속 재시작하고 있었어요. 로그를 확인했죠.

```bash
$ kubectl logs -n petclinic redis-master-0
Fatal error: Can't open and lock config file: Permission denied
```

**원인**: PersistentVolume의 권한 문제였어요.

**해결**:
```bash
# PVC 삭제 후 재생성
kubectl delete pvc redis-data-redis-master-0 -n petclinic
kubectl delete pod redis-master-0 -n petclinic
```

새로운 PVC가 생성되면서 권한 문제가 해결됐어요.

### 사건 2: Active Sessions = 0 (세션이 생성 안 됨)

로그인했는데 대시보드에서 "Active Sessions: 0"이 나오는 거예요.

"세션이 Redis에 저장이 안 되는 건가?" 생각하며 Redis를 직접 확인했어요.

```bash
$ kubectl exec -n petclinic redis-master-0 -- redis-cli DBSIZE
(integer) 0
```

정말로 세션이 없었어요!

WAS Pod 로그를 확인했죠.

```bash
$ kubectl logs -n petclinic was-xxx | grep -i redis
ERROR: Could not connect to Redis at redis-master.petclinic.svc.cluster.local:6379
```

**원인**: Redis Service 이름이 잘못되어 있었어요.

실제 Service 이름은 `redis-master`인데, application.yml에 `redis`로 설정되어 있었던 거죠.

**해결**:
```yaml
# application.yml
spring:
  redis:
    host: redis-master.petclinic.svc.cluster.local  # ✅ 수정
```

재배포하니 세션이 정상적으로 저장되기 시작했어요!

### 사건 3: redis_evicted_keys_total > 0

"Key Evictions" 패널에서 값이 계속 증가하는 걸 발견했어요.

```
redis_evicted_keys_total{namespace="petclinic"} = 150
rate(...) = 0.5/s
```

**원인**: Redis 메모리가 부족했어요.

```bash
$ kubectl exec -n petclinic redis-master-0 -- redis-cli INFO memory
used_memory_human:128.00M
maxmemory_human:128.00M  ← 메모리 한계 도달!
```

**해결**: Redis 메모리를 증가시켰어요.

```bash
kubectl edit statefulset redis-master -n petclinic

# spec.template.spec.containers[0].resources.limits.memory
memory: "256Mi"  # 128Mi → 256Mi
```

Eviction이 멈추고 세션이 안정적으로 유지되기 시작했어요!

---

## 운영 가이드: 매일 확인하는 것들

### 일일 점검 체크리스트

매일 아침 Grafana Dashboard 007을 열고 이것들을 확인해요.

| 항목 | 메트릭 | 정상 범위 | 조치 |
|------|--------|----------|------|
| **Redis 연결** | `redis_up` | 1 | 0이면 즉시 확인 |
| **세션 수** | `redis_db_keys{db="db0"}` | 0-100 | 100+ 시 메모리 확인 |
| **메모리 사용** | `redis_memory_used_bytes` | 0-64MB | 64MB+ 시 Eviction 확인 |
| **Eviction** | `redis_evicted_keys_total` | 0 | 0 아니면 메모리 증가 |
| **단편화** | `redis_mem_fragmentation_ratio` | 1.0-1.5 | 2.0+ 시 Redis 재시작 |

### Alert 설정 (나중에 추가함)

매일 확인하는 게 귀찮아서 **PrometheusRule**을 추가했어요.

```yaml
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: redis-session-alerts
  namespace: monitoring
spec:
  groups:
    - name: redis-session
      rules:
        # Redis Down
        - alert: RedisDown
          expr: redis_up{namespace="petclinic"} == 0
          for: 1m
          labels:
            severity: critical
          annotations:
            summary: "Redis is down!"

        # 세션 수 급증
        - alert: HighSessionCount
          expr: redis_db_keys{db="db0",namespace="petclinic"} > 100
          for: 5m
          labels:
            severity: warning

        # Eviction 발생
        - alert: RedisEvictionDetected
          expr: rate(redis_evicted_keys_total{namespace="petclinic"}[5m]) > 0
          for: 1m
          labels:
            severity: warning
```

이제 문제가 생기면 Slack으로 알림이 와요!

---

## 배운 것들

### 1. 세션 공유는 필수

처음에는 "WAS Pod 1개면 되지 않나?" 생각했는데, HPA를 활용하려면 **세션 공유가 필수**예요.

세션 공유 없이 HPA를 켜면 = 로그인 무한 루프

### 2. 모니터링도 필수

"Redis 동작하면 되는 거 아냐?" 생각했는데, **모니터링 없이는 문제를 사전에 감지할 수 없어요**.

Eviction이 발생하기 전에 메모리를 증가시킬 수 있었던 건 모니터링 덕분이에요.

### 3. 작은 것부터 시작

처음부터 Redis Sentinel이나 ElastiCache를 쓸 필요는 없어요.

1. 단일 Redis로 시작
2. 모니터링 설정
3. 문제 발생 시 HA 구축

이 순서가 제일 효율적이에요.

### 4. 문서화의 중요성

이 가이드를 작성하면서 "아, 이래서 이렇게 했구나" 하고 다시 깨달았어요.

나중에 같은 문제가 생기면 이 문서를 보면 되니까 정말 편해요.

---

## 다음 단계

현재 구현은 완료됐지만, 개선할 점들이 있어요.

### ⏳ 30분 내 완료 가능

1. **Alert Slack 연동** (20분)
   - AlertManager 설정
   - Slack Webhook 연결

2. **세션 TTL 최적화** (10분)
   - 현재 30분 → 사용 패턴에 맞게 조정
   - 불필요한 세션 빨리 삭제

### 🔜 선택 사항 (Priority 2)

3. **Redis HA (Sentinel)** (2시간)
   - SPOF 해결
   - 자동 Failover

4. **Redis Cluster** (4시간)
   - 대규모 트래픽 대비
   - 샤딩으로 성능 향상

---

## 마무리

"로그인 무한 루프"라는 작은 문제에서 시작해서, 세션 클러스터링과 모니터링까지 구현하게 됐어요.

처음에는 "Redis 설치하면 끝이지 뭐" 생각했는데, 막상 운영하다 보니 모니터링이 정말 중요하더라고요.

**핵심 교훈**:
1. 세션 공유 = HPA의 필수 조건
2. 모니터링 = 사전 문제 감지
3. Alert = 자동 알림
4. 문서화 = 미래의 나를 위한 것

이 가이드가 세션 클러스터링을 구현하시는 분들께 도움이 되었으면 좋겠어요.

혹시 다른 방법으로 구현하셨거나, 개선 아이디어가 있으면 언제든지 공유해 주세요. 함께 배우는 게 최고니까요! 😊

---

**관련 문서**:
- [Kubernetes 운영 도구 완벽 설치 가이드](../2025-12-25-kubernetes-addons-operational-guide/)
- [Spring Session Redis Documentation](https://docs.spring.io/spring-session/reference/guides/boot-redis.html)
- [Redis Exporter GitHub](https://github.com/oliver006/redis_exporter)
