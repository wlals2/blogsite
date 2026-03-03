---
title: "Kubernetes Probe 완벽 이해하기"
date: 2025-12-26
tags: ["Kubernetes", "Probe", "Health Check", "Spring Boot", "Actuator"]
categories: ["study", "Kubernetes"]
series: ["Kubernetes 기초 시리즈"]
description: "StartupProbe, LivenessProbe, ReadinessProbe의 차이가 헷갈렸어요. 'StartupProbe가 기다리기만 하면 진짜 에러는 언제 감지하나?' 같은 의문을 실전 경험으로 해결한 과정을 공유합니다."
showToc: true
draft: false
---

## Probe 설정의 혼란

처음 Kubernetes에서 Spring Boot 앱을 배포할 때, Probe 설정이 정말 헷갈렸어요.

```yaml
# 방법 1: initialDelaySeconds (간단하지만 비효율)
livenessProbe:
  httpGet:
    path: /actuator/health
  initialDelaySeconds: 120  # 무조건 120초 대기
  periodSeconds: 10

# 방법 2: StartupProbe (복잡하지만 효율적)
startupProbe:
  httpGet:
    path: /actuator/health
  periodSeconds: 5
  failureThreshold: 30  # 최대 150초 (5초 × 30회)
```

**"둘 다 비슷해 보이는데, 뭐가 다른 거지?"**
**"StartupProbe는 언제까지 기다리는 거야?"**

밤새 문서를 읽어도 명확한 답을 찾지 못했어요. 결국 직접 테스트하면서 이해하게 됐죠.

## 가장 혼란스러웠던 질문

### "StartupProbe가 진짜 에러를 어떻게 감지하나?"

**시나리오 1: 느린 시작 (정상)**
```
Spring Boot 앱이 120초 걸려서 시작
- StartupProbe가 120초 동안 503 반환
- 121초에 200 OK 반환 → 성공! ✅
```

**시나리오 2: 진짜 에러 (비정상)**
```
Spring Boot 앱이 설정 오류로 시작 실패
- StartupProbe가 영원히 503 반환
- 언제까지 기다리나? 죽이지 않나? 🤔
```

이 질문이 머릿속을 떠나지 않았어요. "만약 앱이 진짜 망가져서 절대 시작 못 하면, Kubernetes는 어떻게 판단하는 거지?"

## Probe의 종류를 이해하다

### StartupProbe (시작 확인)

**역할**: "앱이 처음 시작할 준비가 되었나?"

**언제 사용하나?**
- Spring Boot처럼 시작 시간이 오래 걸리는 앱 (30초 ~ 2분)
- 데이터 로딩, DB 연결 등 초기화 작업이 많은 앱

**동작 흐름**:
```
Pod 생성
  ↓
StartupProbe 시작 (5초마다 체크)
  ↓
[Try 1] GET /actuator/health → 503 (아직 시작 중)
  ↓ 5초 대기
[Try 2] GET /actuator/health → 503
  ↓ 5초 대기
...
  ↓ 5초 대기
[Try 20] GET /actuator/health → 200 OK (시작 완료!)
  ↓
StartupProbe 성공 → LivenessProbe, ReadinessProbe 시작
```

**핵심**: 1회만 성공하면 됨, 이후엔 안 체크함

### LivenessProbe (생존 확인)

**역할**: "앱이 살아있나? (Deadlock, 무한루프 감지)"

**실제 경험**: 한 번 JVM이 Deadlock에 빠져서 요청을 전혀 처리하지 못한 적이 있었어요. 하지만 Pod는 Running 상태였죠. LivenessProbe가 없었다면 계속 방치됐을 거예요.

**동작 흐름**:
```
StartupProbe 성공 후
  ↓
LivenessProbe 시작 (20초마다 체크)
  ↓
[정상] GET /actuator/health/liveness → 200 OK
  ↓ 20초 대기
[Deadlock 발생!] GET /actuator/health/liveness → 타임아웃
  ↓ 20초 대기
[Deadlock 지속] → 타임아웃
  ↓ 20초 대기
[Deadlock 지속] → 타임아웃
  ↓
failureThreshold 3회 도달 → **Pod 재시작** 🔄
```

**왜 재시작하나?**
Deadlock은 복구 불가능해요. 유일한 해결책은 재시작이에요.

### ReadinessProbe (준비 확인)

**역할**: "앱이 트래픽을 받을 준비가 되었나?"

**LivenessProbe와의 차이**:
- LivenessProbe: 실패 시 **Pod 재시작**
- ReadinessProbe: 실패 시 **Endpoint 제외** (재시작 ❌)

**왜 재시작하지 않나요?**

DB 연결이 일시적으로 끊긴 경우를 생각해보세요.
- 5초 후 DB가 복구되면 자동으로 다시 연결됨
- Pod를 재시작할 필요가 없어요
- 대신 트래픽만 잠시 다른 Pod로 보내면 돼요

**동작 흐름**:
```
StartupProbe 성공 후
  ↓
ReadinessProbe 시작 (10초마다 체크)
  ↓
[DB 연결 중] GET /actuator/health/readiness → 503
  ↓ Service Endpoint에서 **제외** (트래픽 받지 않음)
  ↓ 10초 대기
[DB 연결 완료] GET /actuator/health/readiness → 200 OK
  ↓ Service Endpoint에 **등록** (트래픽 받기 시작) ✅
```

### Probe 비교표

| Probe | 시작 시점 | 체크 대상 | 실패 시 동작 | 필수 여부 |
|-------|----------|----------|-------------|----------|
| **StartupProbe** | Pod 생성 직후 | 앱 초기화 완료 | **Pod 재시작** | 선택 (권장) |
| **LivenessProbe** | StartupProbe 성공 후 | Deadlock, 무한루프 | **Pod 재시작** | 선택 (권장) |
| **ReadinessProbe** | StartupProbe 성공 후 | 트래픽 처리 준비 | Endpoint 제외 | **필수** |

## 핵심 질문의 답: failureThreshold

### 질문: "StartupProbe가 진짜 에러를 어떻게 감지하나?"

**답**: `failureThreshold`가 한계를 정해요.

**설정 예시**:
```yaml
startupProbe:
  httpGet:
    path: /actuator/health
    port: 8080
  initialDelaySeconds: 10  # 첫 체크까지 10초 대기
  periodSeconds: 5          # 5초마다 체크
  failureThreshold: 30      # 30회 실패하면 포기
  timeoutSeconds: 3         # 각 요청 타임아웃 3초
```

**최대 대기 시간 계산**:
```
initialDelaySeconds + (periodSeconds × failureThreshold)
= 10초 + (5초 × 30회)
= 10초 + 150초
= 160초 (약 2분 40초)
```

### 실제 동작 흐름

**느린 시작 (정상) 케이스**:
```
Pod 생성
  ↓
10초 대기 (initialDelaySeconds)
  ↓
[Try 1] GET /actuator/health → 503 (앱 시작 중)
  ↓ 5초 대기
[Try 2-23] → 503 (계속 시작 중)
  ↓ 5초 대기
[Try 24] GET /actuator/health → 200 OK (120초 후 성공!)
  ↓
StartupProbe 성공! ✅
```

**진짜 에러 (비정상) 케이스**:
```
Pod 생성
  ↓
10초 대기
  ↓
[Try 1] GET /actuator/health → 타임아웃 (설정 오류)
  ↓ 5초 대기
[Try 2-30] → 타임아웃 (계속 실패)
  ↓
failureThreshold 30회 도달
  ↓
**Pod 재시작 (RESTART +1)** 🔄
```

**"아하! StartupProbe는 무한정 기다리지 않는구나!"**

### CrashLoopBackOff의 비밀

StartupProbe가 30회 실패하면 Kubernetes가 Pod를 재시작해요. 하지만 진짜 오류(설정 파일 없음, DB 주소 틀림)라면 재시작해도 똑같이 실패하겠죠?

**재시작 간격 (Exponential Backoff)**:

| 재시작 횟수 | 대기 시간 | 누적 시간 |
|------------|----------|----------|
| 1회 | 0초 | 0초 |
| 2회 | 10초 | 10초 |
| 3회 | 20초 | 30초 |
| 4회 | 40초 | 1분 10초 |
| 5회 | 80초 | 2분 30초 |
| 6회 | 160초 | 5분 10초 |
| 7회 이후 | 300초 (5분) | 10분+ |

**CrashLoopBackOff 상태**:
```bash
kubectl get pods -n petclinic

# NAME                   READY   STATUS              RESTARTS
# was-abc123-xxx         0/1     CrashLoopBackOff    5 (3m ago)
```

**의미**:
- `CrashLoopBackOff`: Kubernetes가 재시작을 시도했지만 계속 실패
- `RESTARTS: 5`: 5번 재시작했음
- `(3m ago)`: 마지막 재시작이 3분 전 (다음 재시작까지 대기 중)

**"이제 이해됐어!"** CrashLoopBackOff는 "계속 재시작 시도했는데 안 되니까 좀 쉬었다가 다시 할게"라는 뜻이었어요.

## 실전 Probe 설정

### WAS (Spring Boot) 권장 설정

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Rollout
metadata:
  name: was
  namespace: petclinic
spec:
  template:
    spec:
      containers:
        - name: was
          image: 339713018679.dkr.ecr.ap-northeast-2.amazonaws.com/petclinic-was:latest

          # 1. StartupProbe (앱 초기 시작 확인)
          startupProbe:
            httpGet:
              path: /actuator/health
              port: 8080
            initialDelaySeconds: 10   # JVM 시작에 최소 10초 필요
            periodSeconds: 5          # 5초마다 체크 (너무 짧으면 부하)
            failureThreshold: 30      # 최대 160초 대기 (Spring Boot 2분)
            successThreshold: 1       # 1회 성공하면 OK
            timeoutSeconds: 3         # 각 요청 타임아웃 3초

          # 2. LivenessProbe (Deadlock, 무한루프 감지)
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8080
            periodSeconds: 20         # 20초마다 체크 (자주 하면 부하)
            failureThreshold: 3       # 60초 연속 실패 시 재시작
            timeoutSeconds: 5

          # 3. ReadinessProbe (트래픽 수신 준비 확인)
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8080
            periodSeconds: 10         # 10초마다 체크
            failureThreshold: 3       # 30초 연속 실패 시 Endpoint 제외
            timeoutSeconds: 3
```

### 왜 이 값들인가?

#### StartupProbe

| 설정 | 값 | 이유 |
|------|-----|------|
| `initialDelaySeconds` | 10초 | JVM 시작에 최소 10초 필요 |
| `periodSeconds` | 5초 | 너무 짧으면 부하, 너무 길면 지연 |
| `failureThreshold` | 30회 | Spring Boot가 최대 2분 걸릴 수 있음 |
| `timeoutSeconds` | 3초 | `/actuator/health`는 빠름 (< 1초) |

**최대 대기 시간**: 10 + (5 × 30) = 160초

**실제 경험**: 처음엔 `failureThreshold: 20`으로 설정했다가, 점심시간 후 재배포 시 Spring Boot가 2분 10초 걸려서 CrashLoopBackOff에 빠졌어요. 30으로 올리니 해결됐죠.

#### LivenessProbe

| 설정 | 값 | 이유 |
|------|-----|------|
| `periodSeconds` | 20초 | 자주 체크하면 부하, 드물게 체크하면 감지 지연 |
| `failureThreshold` | 3회 | Deadlock이면 3회 연속 실패 (60초 내 감지) |
| `timeoutSeconds` | 5초 | Deadlock 시 응답 못할 수 있으므로 여유 있게 |

**재시작 조건**: 20초 × 3회 = 60초 연속 실패

#### ReadinessProbe

| 설정 | 값 | 이유 |
|------|-----|------|
| `periodSeconds` | 10초 | 트래픽 준비 상태를 자주 체크 |
| `failureThreshold` | 3회 | 일시적 DB 연결 끊김 허용 (30초) |
| `timeoutSeconds` | 3초 | DB 연결 체크도 빠름 |

**Endpoint 제외 조건**: 10초 × 3회 = 30초 연속 실패

## Spring Boot Actuator 설정

### 왜 Actuator가 필요한가?

**기본 Probe의 문제점**:
```yaml
# 단순 루트 경로 체크
livenessProbe:
  httpGet:
    path: /
    port: 8080
```

**문제**:
- `/`는 Spring MVC Controller가 응답
- Deadlock 시에도 nginx가 대신 응답할 수 있음 (잘못된 판단)
- DB 연결 상태, JVM 메모리 등 내부 상태 모름

**Actuator 사용 시**:
```yaml
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
```

**장점**:
- Spring Boot가 내부 상태를 진단하여 200/503 반환
- Liveness와 Readiness를 분리하여 정확한 상태 파악

### application.properties 설정

```properties
# Actuator 활성화
management.endpoints.web.exposure.include=health,info,metrics,prometheus
management.endpoint.health.probes.enabled=true
management.endpoint.health.show-details=always

# Liveness Probe
management.health.livenessState.enabled=true

# Readiness Probe
management.health.readinessState.enabled=true

# DB Health Check (Readiness에 포함)
management.health.db.enabled=true

# Disk Space Health Check (Liveness에 포함)
management.health.diskspace.enabled=true
```

### Actuator Endpoint 동작

#### /actuator/health/liveness

**체크 항목**:
- JVM 상태 (OOM 등)
- Disk Space (90% 이상 사용 시 DOWN)

**응답 예시**:
```json
// 정상
{
  "status": "UP"
}

// Deadlock 또는 OOM
{
  "status": "DOWN"
}
```

**HTTP 상태 코드**:
- `status: UP` → 200 OK
- `status: DOWN` → 503 Service Unavailable

#### /actuator/health/readiness

**체크 항목**:
- DB 연결 (MySQL Ping)
- Redis 연결 (Session Store)

**응답 예시**:
```json
// 정상
{
  "status": "UP",
  "components": {
    "db": {
      "status": "UP",
      "details": {
        "database": "MySQL",
        "validationQuery": "isValid()"
      }
    },
    "redis": {
      "status": "UP"
    }
  }
}

// DB 연결 끊김
{
  "status": "DOWN",
  "components": {
    "db": {
      "status": "DOWN",
      "details": {
        "error": "Connection refused"
      }
    }
  }
}
```

**실제 경험**: DB Security Group 설정을 잘못해서 Pod가 DB에 연결하지 못한 적이 있었어요. ReadinessProbe가 DOWN을 반환해서 ALB가 트래픽을 보내지 않았죠. 덕분에 사용자는 에러를 보지 못했어요.

## 트러블슈팅 실전 경험

### CrashLoopBackOff 해결

**증상**:
```bash
kubectl get pods -n petclinic

# NAME                   READY   STATUS              RESTARTS
# was-abc123-xxx         0/1     CrashLoopBackOff    10
```

**Step 1: Pod 로그 확인**
```bash
kubectl logs was-abc123-xxx -n petclinic --previous
```

**에러 패턴별 원인**:

| 에러 메시지 | 원인 | 해결 |
|------------|------|------|
| `Cannot load database driver` | DB Driver 누락 | pom.xml에 mysql-connector 추가 |
| `Connection refused: eks-3tier-dev-db...` | DB 주소 틀림 | ConfigMap/Secret 확인 |
| `OutOfMemoryError: Java heap space` | Memory 부족 | resources.limits.memory 증가 |

**Step 2: failureThreshold 증가**

**문제**: Spring Boot가 2분 30초 걸리는데, StartupProbe는 160초만 기다림

**해결**:
```yaml
startupProbe:
  periodSeconds: 5
  failureThreshold: 40  # 30 → 40으로 증가 (최대 210초)
```

### READY 0/1 문제

**증상**:
```bash
kubectl get pods -n petclinic

# NAME                   READY   STATUS    RESTARTS
# was-abc123-xxx         0/1     Running   0
```

**원인**: ReadinessProbe 실패

**확인**:
```bash
kubectl exec -it was-abc123-xxx -n petclinic -- \
  curl http://localhost:8080/actuator/health/readiness

# 응답:
# {
#   "status": "DOWN",
#   "components": {
#     "db": {
#       "status": "DOWN",
#       "details": {
#         "error": "Connection refused"
#       }
#     }
#   }
# }
```

**해결**: DB Security Group에 EKS Pod CIDR 추가

## 배운 점

### 1. StartupProbe는 무한정 기다리지 않아요

`failureThreshold`로 최대 시도 횟수를 정해요. 진짜 에러는 재시작해도 똑같이 실패 → CrashLoopBackOff

### 2. LivenessProbe는 보수적으로

잘못된 판단으로 재시작하면 서비스 중단돼요. `failureThreshold: 3` 이상 (60초 이상 Deadlock 확인)

### 3. ReadinessProbe는 엄격하게

DB 연결 끊기면 즉시 Endpoint 제외 (다른 Pod로 트래픽). `failureThreshold: 2~3` (20~30초)

### 4. Actuator를 꼭 사용하세요

단순 `/` 경로 체크는 정확하지 않아요. Actuator의 `/health/liveness`와 `/health/readiness`를 사용하세요.

### 5. Probe 경로를 통일하세요

ALB Health Check = ReadinessProbe = 트래픽 수신 준비 (같은 조건)

## 마무리

처음엔 "Probe는 그냥 Health Check 아닌가?" 하고 단순하게 생각했어요. 하지만 실전에서 부딪히며 배운 건:

**Probe는 Kubernetes가 앱의 생명주기를 관리하는 핵심 메커니즘이에요.**

**핵심 요약**:
- StartupProbe: 시작 확인 (failureThreshold로 최대 대기 시간 제한)
- LivenessProbe: 생존 확인 (Deadlock 감지, 재시작)
- ReadinessProbe: 준비 확인 (트래픽 제어, 재시작 ❌)

이 가이드가 여러분의 Probe 설정 고민을 해결하는 데 도움이 되길 바랍니다! 🚀
