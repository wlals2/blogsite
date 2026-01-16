---
title: "세션 공유 문제: WAS Pod가 2개면 로그인이 풀린다"
date: 2025-11-15
summary: "Kubernetes HPA 도입 중 발견한 세션 문제와 임시 해결책의 한계"
tags: ["kubernetes", "session", "hpa", "troubleshooting", "spring-boot"]
categories: ["projects"]
series: ["Infrastructure Learning Journey"]
weight: 2
showtoc: true
tocopen: true
---

# 세션 공유 문제: WAS Pod가 2개면 로그인이 풀린다

> Kubernetes HPA를 도입하려다 만난 예상치 못한 장벽

---

## 🚨 문제 발견

Phase 2에서 Kubernetes를 도입하고 HPA(Horizontal Pod Autoscaler)로 자동 스케일링을 구현했습니다. WEB Tier는 문제없이 2-5개 Pod로 스케일되었지만, **WAS Tier에서 치명적인 문제가 발생**했습니다.

### 증상: 사용자 로그인이 무한 반복

```
사용자 경험:
1. 로그인 페이지 접속
2. 아이디/비밀번호 입력 → 로그인 성공 ✅
3. 다른 페이지 클릭
4. 다시 로그인 페이지로 리다이렉트 ❌
5. 무한 반복...
```

**재현 조건:**
- WAS replica가 2개 이상일 때만 발생
- replica가 1개일 때는 정상 작동

---

## 🔍 원인 분석

### 1. 로그 확인

WAS Pod 로그를 확인했습니다.

```bash
# Pod 1 로그
kubectl logs was-pod-1 -n petclinic

[2025-11-10 10:30:15] INFO  - User 'admin' logged in successfully
[2025-11-10 10:30:15] INFO  - Session created: SESSION_ABC123

# Pod 2 로그
kubectl logs was-pod-2 -n petclinic

[2025-11-10 10:30:20] WARN  - Session not found for request
[2025-11-10 10:30:20] INFO  - Redirecting to login page
```

**발견:**
- Pod 1에서 로그인 성공 → 세션 생성
- Pod 2로 다음 요청이 전달 → 세션 없음 → 로그인 페이지로 리다이렉트

---

### 2. 트래픽 흐름 분석

ALB가 Round-Robin 방식으로 요청을 분산하고 있었습니다.

```
사용자 요청 1 (로그인)
  ↓
ALB (Round-Robin)
  ↓
WAS Pod 1 → 로그인 성공 → 세션 저장 (Pod 1 메모리)

사용자 요청 2 (홈 페이지)
  ↓
ALB (Round-Robin)
  ↓
WAS Pod 2 → 세션 없음 ❌ → 로그인 페이지로 리다이렉트
```

**핵심 문제:**
- 각 Pod가 세션을 **자기 메모리에만 저장**
- Pod 간 세션 공유 안 됨
- 사용자 요청이 다른 Pod로 가면 세션 소실

---

### 3. Spring Boot 세션 기본 동작

Spring Boot는 기본적으로 **In-Memory Session**을 사용합니다.

```java
// Spring Boot 기본 설정 (application.yml)
spring:
  session:
    store-type: none  # 기본값 (메모리 저장)
```

**In-Memory Session 동작:**
1. 사용자 로그인 → HttpSession 생성
2. Session 객체를 **Tomcat 메모리**에 저장
3. Session ID를 Cookie로 클라이언트에 전달
4. 다음 요청 시 Cookie의 Session ID로 Session 조회

**문제:**
- Session이 Pod 메모리에만 존재
- 다른 Pod는 이 Session에 접근 불가

---

## 🛠️ 시도한 해결 방법

### 1. ALB Sticky Session (임시 해결)

ALB의 Sticky Session(Session Affinity) 기능을 활성화했습니다.

```hcl
# Terraform - ALB Target Group
resource "aws_lb_target_group" "was" {
  name     = "was-target-group"
  port     = 8080
  protocol = "HTTP"
  vpc_id   = var.vpc_id

  stickiness {
    enabled         = true
    type            = "lb_cookie"
    cookie_duration = 3600  # 1시간
  }

  health_check {
    path                = "/actuator/health"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 2
  }
}
```

**동작 원리:**
```
사용자 첫 요청
  ↓
ALB → WAS Pod 1 선택
  ↓
ALB가 Cookie 생성 (AWSALB=pod-1-identifier)
  ↓
이후 모든 요청은 Pod 1로만 전달 ✅
```

**결과:**
- ✅ 로그인 유지됨
- ✅ 사용자 경험 정상화

**하지만 문제 발견:**

| 시나리오 | 결과 |
|---------|------|
| Pod 재시작 (배포) | 세션 소실 → 로그인 풀림 ❌ |
| Pod 삭제 (장애) | 세션 소실 → 로그인 풀림 ❌ |
| HPA 스케일 다운 | 일부 사용자 세션 소실 ❌ |

**결론:**
- Sticky Session은 **임시방편**일 뿐
- 근본적인 해결책 아님

---

### 2. WAS replica = 1 고정 (최종 선택)

결국 **WAS replica를 1로 고정**하기로 결정했습니다.

```yaml
# was/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: was
spec:
  replicas: 1  # 고정
  # HPA 비활성화
```

**장점:**
- ✅ 세션 문제 완전 해결
- ✅ 구현 간단

**단점:**
- ❌ HPA 사용 불가
- ❌ 트래픽 급증 시 수동 대응 필요
- ❌ 단일 장애점 (Pod 1개)

---

## 📊 성능 영향 분석

### 부하 테스트 결과

```bash
# Apache Bench로 부하 테스트
ab -n 10000 -c 100 https://www.goupang.shop/petclinic/

# WAS replica = 1
Requests per second:    50.23 [#/sec]
Time per request:       1991.2 [ms] (mean)
Failed requests:        0

# WAS replica = 2 (Sticky Session)
Requests per second:    95.12 [#/sec]
Time per request:       1051.4 [ms] (mean)
Failed requests:        0
```

**분석:**
- replica 2개일 때 처리량 **2배 증가** (50 → 95 req/sec)
- 응답 시간 **50% 단축** (1991ms → 1051ms)
- **하지만 Pod 재시작 시 세션 소실 리스크**

**트레이드오프:**
| 방법 | 처리량 | 안정성 | 세션 유지 |
|------|--------|--------|----------|
| **replica = 1** | 낮음 (50 req/sec) | ✅ 안정 | ✅ 100% |
| **replica = 2 + Sticky** | 높음 (95 req/sec) | ⚠️ 불안정 | ⚠️ Pod 재시작 시 소실 |

**최종 선택:**
- replica = 1 고정
- 이유: **안정성 > 성능**
- Phase 3에서 Redis Session으로 근본 해결 예정

---

## 💡 배운 점

### 1. Stateless vs Stateful 아키텍처

**Stateful (현재):**
```
Pod가 상태(Session)를 메모리에 저장
→ Pod 재시작 시 상태 소실
→ 스케일링 어려움
```

**Stateless (이상적):**
```
Pod는 상태를 저장하지 않음
→ 상태는 외부 저장소(Redis, DB)에 저장
→ 어느 Pod로 요청이 가도 동일하게 처리
→ 자유로운 스케일링 ✅
```

**교훈:**
- Kubernetes에서는 **Stateless 아키텍처가 필수**
- Session을 Pod 메모리에 저장하면 안 됨
- 외부 저장소(Redis) 필요

---

### 2. 근본 원인 vs 증상 치료

**증상 치료 (Sticky Session):**
- 빠르게 적용 가능
- 하지만 새로운 문제 발생 (Pod 재시작 시 세션 소실)

**근본 해결 (Redis Session):**
- 구현 시간 필요
- 하지만 완전한 해결
- Phase 3에서 적용

**교훈:**
- **임시방편은 언젠가 문제가 됨**
- 근본 원인을 해결해야 함
- Phase 3의 Redis Session 도입 동기가 됨

---

### 3. 모니터링의 중요성

세션 문제를 발견한 과정:

```
1. 사용자 신고 ("로그인이 자꾸 풀려요")
   ↓
2. WAS 로그 확인 (Session not found 발견)
   ↓
3. ALB 로그 분석 (Round-Robin 확인)
   ↓
4. Pod 메모리 확인 (Session 메모리 저장 확인)
   ↓
5. 원인 파악 (Pod 간 Session 공유 불가)
```

**교훈:**
- **로그가 없었다면 원인 파악 불가능**
- 모니터링과 로깅은 필수
- Phase 3에서 Prometheus + Grafana 도입

---

## 🚀 Phase 3에서의 해결

Phase 3에서 **Redis Session Clustering**으로 완전히 해결했습니다.

**Before (Phase 2):**
```
WAS Pod 1 (메모리) ─┐
                    ├─ Session 공유 불가 ❌
WAS Pod 2 (메모리) ─┘
```

**After (Phase 3):**
```
WAS Pod 1 ──┐
            ├──► Redis (외부 저장소) ◄── Session 공유 ✅
WAS Pod 2 ──┘
```

**성과:**
- WAS 1 replica → **2-10 replica** (HPA 활성화)
- 세션 유지율 50% → **100%**
- Pod 재시작 시 세션 소실 **0건**

**관련 문서:**
- [Redis Session Clustering 구현 가이드](../phase3-eks-dr/redis-session.md)
- [Multi-AZ 고가용성 아키텍처](../phase3-eks-dr/ha-infrastructure.md)

---

## 📚 참고 자료

- [Spring Session Documentation](https://docs.spring.io/spring-session/reference/)
- [Kubernetes StatefulSet vs Deployment](https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/)
- [AWS ALB Sticky Sessions](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/sticky-sessions.html)

---

**다음 읽기:**
- [CI/CD 파이프라인: Jenkins + ArgoCD](./cicd-pipeline.md)
- [Phase 3: Redis Session Clustering으로 근본 해결](../phase3-eks-dr/redis-session.md)
