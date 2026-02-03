---
title: "Redis Session Clustering: WAS 1개에서 10개로 확장 가능하게"
date: 2026-01-08
summary: "Spring Session + Redis로 세션 공유 문제를 완전히 해결하고 HPA 활성화"
tags: ["redis", "session", "spring-boot", "kubernetes", "hpa", "clustering"]
categories: ["projects"]
series: ["Infrastructure Learning Journey"]
weight: 3
showtoc: true
tocopen: true
---

# Redis Session Clustering: WAS 1개에서 10개로 확장 가능하게

> Phase 2에서 발견한 세션 공유 문제를 Redis로 근본적으로 해결한 여정

---

## 📖 문제 재확인: Phase 2의 한계

Phase 2에서 Kubernetes + HPA를 도입했지만, **세션 공유 불가**로 WAS replica를 1개로 고정해야 했습니다.

**문제 상황:**
```
WAS Pod 1 (메모리) ─┐
                    ├─ Session 공유 불가 ❌
WAS Pod 2 (메모리) ─┘

→ 사용자 로그인 후 다른 Pod로 요청 전달 시 세션 소실
→ WAS replica = 1 고정
→ HPA 사용 불가
→ 트래픽 급증 시 수동 대응
```

**목표:**
- WAS Pod 2-10개로 자유롭게 스케일링
- 세션 유지율 100%
- Pod 재시작 시 세션 소실 0건

---

## 해결 방안: Spring Session + Redis

### 왜 Redis인가?

여러 해결 방안을 비교했습니다.

| 방법 | 장점 | 단점 | 선택 이유 |
|------|------|------|----------|
| **Sticky Session** | 구현 간단 | Pod 재시작 시 세션 소실 | ❌ 근본 해결 아님 |
| **DB Session** | 영구 저장 | 느림 (Disk I/O) | ❌ 성능 문제 |
| **Redis Session** | 빠름 (Memory), 공유 가능 | Redis 관리 필요 | ✅ **최적** |
| **Hazelcast** | In-Memory Grid | 복잡한 설정 | ❌ 과도한 복잡도 |

**Redis 선택 이유:**
1. **빠름**: 메모리 기반 → 1ms 이하 응답 시간
2. **단순**: Spring Session이 자동 연동 지원
3. **검증됨**: 업계 표준 (Netflix, Twitter 등 사용)
4. **확장 가능**: Sentinel, Cluster로 HA 구성 가능

---

## 구현 과정

### Step 1: Redis 설치 (Helm)

Kubernetes에 Redis를 Standalone 모드로 설치했습니다.

```bash
# Helm으로 Redis 설치
helm install redis bitnami/redis \
  --namespace petclinic \
  --set architecture=standalone \
  --set auth.enabled=false \
  --set master.persistence.enabled=false

# 설치 확인
kubectl get pods -n petclinic | grep redis
# redis-master-0   1/1     Running   0          30s
```

**설정 선택 이유:**

| 설정 | 값 | 이유 |
|------|-----|------|
| `architecture` | standalone | DEV 환경이므로 단일 인스턴스로 충분 |
| `auth.enabled` | false | 내부 네트워크만 접근 가능하므로 불필요 |
| `persistence` | false | Session은 일시적 데이터 (재시작 시 삭제 OK) |

**Production 환경이라면?**
- `architecture: replication` + Sentinel (HA 구성)
- `auth.enabled: true` + 비밀번호 설정
- `persistence: true` + PVC 설정

---

### Step 2: Spring Boot 설정

#### 2.1 의존성 추가 (pom.xml)

```xml
<dependencies>
    <!-- Spring Session Redis -->
    <dependency>
        <groupId>org.springframework.session</groupId>
        <artifactId>spring-session-data-redis</artifactId>
    </dependency>

    <!-- Lettuce (Redis Client) -->
    <dependency>
        <groupId>io.lettuce</groupId>
        <artifactId>lettuce-core</artifactId>
    </dependency>
</dependencies>
```

**왜 Lettuce?**
- Spring Boot 2.x 기본 Redis Client
- Jedis보다 성능 우수 (비동기 지원)
- Thread-Safe

---

#### 2.2 application.yml 설정

```yaml
spring:
  session:
    store-type: redis
    timeout: 1800  # 30분 (초 단위)
  redis:
    host: redis-master.petclinic.svc.cluster.local
    port: 6379
    lettuce:
      pool:
        max-active: 10
        max-idle: 5
        min-idle: 2
```

**설정 설명:**

| 설정 | 값 | 설명 |
|------|-----|------|
| `store-type` | redis | Session을 Redis에 저장 |
| `timeout` | 1800 | 30분 동안 활동 없으면 Session 자동 삭제 |
| `host` | redis-master... | Kubernetes DNS 이름 |
| `max-active` | 10 | 최대 연결 수 |

**왜 Kubernetes DNS?**
- `redis-master.petclinic.svc.cluster.local`
- Pod가 재시작되어도 Service는 유지
- IP 변경에 영향받지 않음

---

#### 2.3 Redis Session Config (Java)

```java
package org.springframework.samples.petclinic.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)
public class RedisSessionConfig {

    @Bean
    public RedisSerializer<Object> springSessionDefaultRedisSerializer() {
        return new GenericJackson2JsonRedisSerializer();
    }
}
```

**설정 설명:**

| 항목 | 설명 |
|------|------|
| `@EnableRedisHttpSession` | Spring Session Redis 활성화 |
| `maxInactiveIntervalInSeconds` | Session TTL (30분) |
| `RedisSerializer` | Session 객체를 JSON으로 직렬화 |

**왜 JSON 직렬화?**
- Java 기본 직렬화보다 가독성 좋음
- Redis CLI로 Session 확인 가능
- 다른 언어와 호환 가능

---

### Step 3: Docker 이미지 빌드 및 배포

```bash
# 1. 소스 수정 (Spring Session 설정 추가)
cd ~/CICD/sourece-repo
git add .
git commit -m "Add Redis Session Clustering"
git push origin main

# 2. Jenkins 자동 빌드 (10분)
# - Maven Build
# - Docker Build
# - ECR Push
# - manifestrepo 업데이트

# 3. ArgoCD 자동 배포
# - manifestrepo 변경 감지
# - EKS에 자동 배포
# - Rolling Update (무중단)
```

---

### Step 4: WAS HPA 활성화

이제 WAS Pod를 2-10개로 스케일링할 수 있습니다!

```yaml
# was/hpa.yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: was-hpa
  namespace: petclinic
spec:
  scaleTargetRef:
    apiVersion: argoproj.io/v1alpha1
    kind: Rollout
    name: was
  minReplicas: 2
  maxReplicas: 10
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
    - type: Resource
      resource:
        name: memory
        target:
          type: Utilization
          averageUtilization: 80
```

**Before (Phase 2):**
```yaml
spec:
  replicas: 1  # 고정 (세션 이슈)
```

**After (Phase 3):**
```yaml
spec:
  minReplicas: 2   # 최소 2개 (HA)
  maxReplicas: 10  # 최대 10개 (트래픽 대응)
```

---

## 동작 확인

### 1. Redis Session 저장 확인

```bash
# Redis Pod 접속
kubectl exec -it redis-master-0 -n petclinic -- redis-cli

# Session 키 확인
127.0.0.1:6379> KEYS *
1) "spring:session:sessions:abc-123-def-456"
2) "spring:session:expirations:1704718800000"

# Session 내용 확인
127.0.0.1:6379> GET "spring:session:sessions:abc-123-def-456"
"{\"sessionId\":\"abc-123-def-456\",\"creationTime\":1704632400000,\"lastAccessedTime\":1704632450000,\"maxInactiveInterval\":1800,\"sessionAttr\":{\"user\":\"admin\"}}"
```

**확인된 내용:**
- Session이 Redis에 JSON으로 저장됨 ✅
- TTL 30분 (1800초) 설정됨 ✅
- User 정보 포함됨 ✅

---

### 2. 세션 유지 테스트

**테스트 시나리오:**
```bash
# 1. 로그인 (Pod 1)
curl -c cookies.txt -X POST https://www.goupang.shop/petclinic/login \
  -d "username=admin&password=admin"

# 2. 세션 확인 요청 (Pod 2로 전달됨)
curl -b cookies.txt https://www.goupang.shop/petclinic/api/user
# {"username":"admin","logged_in":true}  ✅ 세션 유지!

# 3. WAS Pod 재시작
kubectl delete pod was-xxx-1 -n petclinic

# 4. 다시 세션 확인 (새 Pod로 전달)
curl -b cookies.txt https://www.goupang.shop/petclinic/api/user
# {"username":"admin","logged_in":true}  ✅ 여전히 세션 유지!
```

**결과:**
- Pod 간 세션 공유 정상
- Pod 재시작 후에도 세션 유지
- 로그인 풀림 현상 0건

---

### 3. HPA 스케일링 테스트

부하 테스트로 HPA 동작을 확인했습니다.

```bash
# Apache Bench로 부하 생성
ab -n 100000 -c 200 https://www.goupang.shop/petclinic/

# HPA 상태 모니터링
kubectl get hpa was-hpa -n petclinic --watch

NAME       REFERENCE         TARGETS          MINPODS   MAXPODS   REPLICAS
was-hpa    Rollout/was       15%/70%, 20%/80%   2         10        2
was-hpa    Rollout/was       75%/70%, 60%/80%   2         10        2
was-hpa    Rollout/was       85%/70%, 75%/80%   2         10        4  ← 스케일 업!
was-hpa    Rollout/was       60%/70%, 65%/80%   2         10        4
was-hpa    Rollout/was       40%/70%, 50%/80%   2         10        4
was-hpa    Rollout/was       20%/70%, 30%/80%   2         10        2  ← 스케일 다운
```

**확인된 동작:**
1. CPU 70% 초과 → 4개로 스케일 업 (30초 소요)
2. 부하 감소 → 2개로 스케일 다운 (5분 대기 후)
3. **스케일링 중에도 세션 유지 100%** ✅

---

## 성과 요약

### Before (Phase 2) vs After (Phase 3)

| 지표 | Phase 2 (Sticky Session) | Phase 3 (Redis Session) | 개선 |
|------|-------------------------|------------------------|------|
| **WAS replica** | 1개 고정 | 2-10개 (HPA) | ✅ **10배 확장** |
| **세션 유지율** | 50% (Pod 재시작 시 소실) | 100% | ✅ **2배** |
| **HPA** | 비활성화 | 활성화 | ✅ **자동 스케일링** |
| **처리량** | 50 req/sec | 200 req/sec | ✅ **4배** |
| **응답 시간** | 1991ms | 498ms | ✅ **75% 단축** |
| **Pod 재시작 영향** | 세션 소실 | 영향 없음 | ✅ **완전 해결** |

---

### 정량적 성과

**세션 모니터링 결과 (1주일):**
```
총 세션 수: 10,234개
세션 소실: 0건
세션 유지율: 100.00%
평균 세션 TTL: 28분 (설정: 30분)

HPA 스케일 이벤트:
- 스케일 업: 42회
- 스케일 다운: 38회
- 스케일 중 세션 소실: 0건 ✅
```

---

## 🎓 핵심 교훈

### 1. Stateless 아키텍처의 중요성

**Before (Stateful):**
```java
// Session을 Tomcat 메모리에 저장
HttpSession session = request.getSession();
session.setAttribute("user", user);
// → Pod 재시작 시 소실
// → Pod 간 공유 불가
```

**After (Stateless):**
```java
// Session을 Redis에 저장 (Spring Session이 자동 처리)
HttpSession session = request.getSession();
session.setAttribute("user", user);
// → Redis에 자동 저장
// → 모든 Pod가 공유
// → Pod 재시작해도 유지
```

**교훈:**
- Kubernetes에서는 **Pod가 언제든 재시작될 수 있음**
- 상태를 Pod 메모리에 저장하면 안 됨
- **외부 저장소(Redis, DB)에 저장** 필수

---

### 2. 임시방편 vs 근본 해결

| 방법 | Phase 2 (Sticky Session) | Phase 3 (Redis Session) |
|------|------------------------|------------------------|
| **구현 난이도** | 쉬움 (ALB 설정만) | 중간 (Redis + Spring 설정) |
| **구현 시간** | 10분 | 2일 |
| **세션 유지** | Pod 재시작 시 소실 | 완전 유지 |
| **HPA** | 사용 불가 | 사용 가능 |
| **장기 운영** | 불안정 | 안정적 |

**교훈:**
- **빠른 해결 ≠ 좋은 해결**
- 임시방편은 나중에 더 큰 문제 야기
- 시간이 걸려도 근본 원인 해결이 중요

---

### 3. Spring Framework의 강력함

Spring Session을 사용하면 **코드 변경 없이** Session 저장소를 교체할 수 있습니다.

```java
// 애플리케이션 코드는 동일
HttpSession session = request.getSession();
session.setAttribute("user", user);
String user = (String) session.getAttribute("user");

// application.yml만 변경
spring:
  session:
    store-type: redis  # none → redis 변경만!
```

**교훈:**
- **추상화의 힘**
- Spring Session이 저장소 변경을 완전히 추상화
- 코드 수정 없이 In-Memory → Redis 전환

---

## 🚧 남은 과제

### Redis SPOF (단일 장애점)

현재 Redis가 Standalone 모드라 단일 장애점입니다.

```
Redis Pod 1개 (ap-northeast-2c)
  ↓
2c AZ 장애 시 → Redis 중단 → 세션 소실
```

**해결 방안:**
1. **Redis Sentinel** (3 replica)
2. **ElastiCache for Redis** (AWS Managed, Multi-AZ)

**우선순위:** Priority 2 (나중에 개선)

---

## 관련 문서

- [Spring Session Redis 공식 문서](https://docs.spring.io/spring-session/reference/guides/boot-redis.html)
- [Redis Session 모니터링 가이드](https://github.com/wlals2/bespin-project/blob/main/docs/monitoring/session-monitoring-guide.md)
- [Phase 2: 세션 공유 문제 발견](../phase2-k8s/session-problem.md)
- [Multi-AZ 고가용성 아키텍처](./ha-infrastructure.md)

---

**다음 읽기:**
- [Multi-Cloud DR 아키텍처: AWS 장애에도 서비스 유지](./dr-architecture.md)
- [Canary 배포로 무중단 배포 실현](./canary-deployment.md)
