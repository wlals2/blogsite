---
title: "세션 쿠키 수명 불일치 완벽 해결하기"
date: 2025-12-28
tags: ["Spring Boot", "Redis", "Session Management", "Cookie", "Troubleshooting"]
categories: ["Infrastructure & IaC"]
series: ["Spring Session 완벽 가이드"]
description: "시크릿 모드에서는 로그인이 되는데 일반 브라우저에서는 무한 리다이렉트가 발생하는 문제를 겪었어요. Redis 세션과 브라우저 쿠키의 생명주기 불일치 문제를 완벽하게 해결한 과정을 공유합니다."
showToc: true
draft: false
---

## 이상한 로그인 문제를 발견했어요

어느 날 PetClinic 프로젝트를 테스트하던 중, 정말 이상한 현상을 발견했어요.

```
1. 어제 로그인했었음 ✅
2. 오늘 다시 접속 🌐
3. 로그인 페이지로 계속 튐 ❌
4. 시크릿 모드로 열면 잘 됨 ✅
5. "또 왜 이래...?" 🤔
```

처음엔 "브라우저 캐시 문제겠지" 하고 넘어가려 했는데, 거의 매번 반복되는 거예요. 특히 점심 먹고 돌아왔을 때 항상 로그인이 풀려있었죠.

## 시크릿 모드의 힌트

"시크릿 모드에서는 되는데 일반 브라우저에서는 안 된다"는 게 핵심 힌트였어요.

시크릿 모드의 특징을 생각해보니:
- 쿠키를 세션 단위로만 저장 (브라우저 닫으면 삭제)
- 기존 쿠키와 격리됨
- 깨끗한 상태에서 시작

**"아, 기존 브라우저에 뭔가 잘못된 쿠키가 남아있구나!"**

## 근본 원인을 찾아서

개발자 도구(F12)로 쿠키를 확인해봤어요.

```
Application → Cookies → www.goupang.shop
JSESSIONID: abc123def456
Expires: 2026-01-27 (30일 후!)
```

그리고 Redis를 확인했더니...

```bash
kubectl exec -it redis-master-0 -n petclinic -- redis-cli KEYS "spring:session:sessions:*"
# 결과: (empty array)
```

**세션은 이미 사라졌는데 쿠키는 30일간 유지되고 있었어요!**

### 생명주기 불일치 문제

| 항목 | 생명주기 | 저장 위치 |
|------|---------|----------|
| **브라우저 쿠키 (JSESSIONID)** | ~30일 (브라우저 기본값) | 브라우저 |
| **Redis 세션** | 30분 (Spring Session 기본값) | Redis |

문제의 타임라인은 이랬어요:

```
Time: 0분
- 로그인 성공 ✅
- 브라우저: JSESSIONID=abc123 저장 (30일 만료)
- Redis: abc123 세션 저장 (30분 TTL)

Time: 30분 후
- 브라우저: JSESSIONID=abc123 (여전히 유효)
- Redis: abc123 세션 만료됨 (자동 삭제)

Time: 31분 (다음 접속)
- 브라우저 → "JSESSIONID=abc123" 쿠키 전송
- Spring → Redis에서 abc123 세션 찾기 시도
- Redis → 세션 없음 ❌
- Spring → 로그인 안 됨 → 로그인 페이지로 리다이렉트
- 브라우저 → 여전히 같은 쿠키 보냄
- 무한 반복 🔄
```

## Redis Eviction Policy도 문제였어요

Redis 설정을 확인해봤더니 또 다른 문제를 발견했어요.

```bash
kubectl exec -it redis-master-0 -n petclinic -- redis-cli INFO stats

# expired_keys: 0
# evicted_keys: 0
```

**만료된 세션 키가 0개라는 건 이상했어요.** 분명 세션은 30분 TTL로 설정했는데 말이죠.

Redis의 `noeviction` 정책과 `maxmemory: 0B` 설정 때문에 만료된 키가 즉시 삭제되지 않고 메모리에 남아있었던 거예요.

## 해결 방법을 찾아서

### 1. 쿠키 만료 시간 = 세션 TTL (최우선 해결책)

가장 근본적인 해결 방법은 쿠키와 세션의 생명주기를 일치시키는 거였어요.

**파일**: `src/main/java/org/springframework/samples/petclinic/system/SessionConfig.java`

```java
@Configuration
@EnableRedisHttpSession
public class SessionConfig {

    @Bean
    public CookieSerializer cookieSerializer() {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();

        // 쿠키 만료 시간 = 세션 TTL (30분)
        serializer.setCookieMaxAge(1800); // 30분 (초 단위)

        // HTTPS 환경 설정
        serializer.setUseSecureCookie(true);
        serializer.setUseHttpOnlyCookie(true);
        serializer.setSameSite("Lax");

        return serializer;
    }
}
```

**이렇게 하니까**:
- 30분 후 브라우저 쿠키도 자동 만료 ✅
- 세션과 쿠키 생명주기 완벽 일치 ✅
- 무한 리다이렉트 문제 해결 ✅

### 2. Redis Eviction Policy 개선

Redis가 만료된 키를 즉시 삭제하도록 정책을 변경했어요.

**파일**: `k8s-manifests/redis/redis-config.yaml`

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: redis-config
  namespace: petclinic
data:
  redis.conf: |
    maxmemory 256mb
    maxmemory-policy allkeys-lru  # LRU로 변경

    # 만료 키 적극적으로 삭제
    activeexpiredelay 100
```

**적용**:
```bash
kubectl apply -f k8s-manifests/redis/redis-config.yaml
kubectl delete pod redis-master-0 -n petclinic  # 재시작
```

**효과**:
- 만료된 세션 키 즉시 삭제 ✅
- 메모리 효율 개선 ✅
- expired_keys 메트릭 정상 증가 확인 ✅

### 3. 세션 유효성 검사 강화

Spring Security 설정도 개선했어요.

**파일**: `src/main/java/org/springframework/samples/petclinic/system/SecurityConfig.java`

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig extends WebSecurityConfigurerAdapter {

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http
            .sessionManagement()
                .sessionFixation().newSession()  // 로그인 시 새 세션 생성
                .invalidSessionUrl("/login?invalid")  // 무효 세션 시 리다이렉트
                .maximumSessions(1)  // 사용자당 1개 세션만
                .expiredUrl("/login?expired");  // 만료 세션 시 리다이렉트
    }
}
```

**개선 효과**:
- 무효 세션 감지 시 쿠키 삭제 + 로그인 페이지 이동 ✅
- 무한 루프 방지 ✅
- 사용자에게 친절한 에러 메시지 제공 ✅

### 4. 로그아웃 시 쿠키 강제 삭제

마지막으로 로그아웃 처리도 확실하게 했어요.

```java
@Override
protected void configure(HttpSecurity http) throws Exception {
    http
        .logout()
            .logoutUrl("/logout")
            .deleteCookies("JSESSIONID")  // 로그아웃 시 쿠키 강제 삭제
            .invalidateHttpSession(true)  // 세션 무효화
            .clearAuthentication(true)
            .logoutSuccessUrl("/login?logout");
}
```

## 검증 과정

### 1. 쿠키 TTL 확인

브라우저 개발자 도구로 확인해봤어요.

```
1. 로그인
2. F12 → Application → Cookies
3. JSESSIONID 클릭
4. Expires 필드 확인
```

**Before**: `2026-01-27` (30일 후)
**After**: `2025-12-28 14:30` (30분 후) ✅

### 2. 세션 만료 테스트

```bash
# 1. 로그인 후 세션 ID 확인
kubectl exec -it redis-master-0 -n petclinic -- redis-cli KEYS "spring:session:sessions:*"

# 2. TTL 확인 (1800초 = 30분)
kubectl exec -it redis-master-0 -n petclinic -- redis-cli TTL "spring:session:sessions:<session-id>"
# 결과: 1800

# 3. 31분 대기

# 4. 세션 자동 삭제 확인
kubectl exec -it redis-master-0 -n petclinic -- redis-cli EXISTS "spring:session:sessions:<session-id>"
# 결과: 0 (삭제됨) ✅
```

### 3. 무한 루프 해결 확인

**Before (문제 재현)**:
```
1. 로그인 ✅
2. 30분 대기 ⏰
3. 새로고침 → 무한 302 루프 ❌
```

**After (Solution 1 적용 후)**:
```
1. 로그인 ✅
2. 30분 대기 ⏰
3. 새로고침 → 자동으로 로그인 페이지 + 쿠키 삭제됨 ✅
4. 다시 로그인 → 정상 동작 ✅
```

## 왜 이 문제가 자주 발생했을까?

일반적인 사용 패턴을 생각해보니 당연한 거였어요.

```
1. 오전 9시: 로그인 (세션 30분 TTL)
2. 오전 9시-10시: 사용 중 (세션 계속 갱신)
3. 오전 10시: 점심 먹으러 감 (브라우저 그냥 둠)
4. 오후 2시: 다시 접속 (4시간 경과)
   → 세션은 만료됨 (30분 타임아웃)
   → 브라우저 쿠키는 살아있음
   → 무한 루프 발생 ❌
```

**특히 점심시간 후에 항상 로그인이 풀려있던 이유가 이것 때문이었어요!**

## 우선순위별 적용 계획

| 우선순위 | 해결책 | 난이도 | 효과 | 구현 시간 |
|---------|--------|--------|------|----------|
| **P0** | 쿠키 TTL 일치 | 낮음 | 높음 | 10분 |
| **P1** | 로그아웃 강화 | 낮음 | 중간 | 5분 |
| **P2** | Redis Policy | 중간 | 중간 | 15분 |
| **P3** | 세션 검증 강화 | 높음 | 높음 | 30분 |

P0부터 순서대로 적용했고, 모든 문제가 깔끔하게 해결됐어요.

## 배운 점

### 1. 쿠키와 세션은 별개

브라우저 쿠키와 서버 세션은 완전히 독립적이에요. 한쪽만 설정하면 생명주기 불일치 문제가 생길 수 있어요.

### 2. 시크릿 모드의 힘

"시크릿 모드에서만 된다"는 건 **기존 상태(쿠키, 캐시)에 문제가 있다**는 강력한 힌트예요.

### 3. Redis 모니터링 중요성

`expired_keys: 0`처럼 이상한 메트릭을 발견하면 무시하지 말고 원인을 파악해야 해요.

### 4. 사용자 패턴 고려

"30분 세션 타임아웃"은 괜찮아 보이지만, 사용자가 점심 먹고 오면 항상 로그인이 풀린다는 게 문제였어요. 사용자 경험을 생각한 설정이 중요해요.

## 마무리

처음엔 "브라우저 문제겠지" 하고 넘어가려 했던 간단한 문제가, 알고 보니 쿠키와 세션의 생명주기 불일치라는 근본적인 설계 문제였어요.

**핵심 교훈**:
- ✅ 쿠키 만료 시간 = 세션 TTL (필수!)
- ✅ Redis eviction policy 제대로 설정
- ✅ 세션 유효성 검사 강화
- ✅ 로그아웃 시 쿠키 강제 삭제

이제 점심 먹고 돌아와도 로그인이 유지되고, 30분 후에는 자연스럽게 로그인 페이지로 이동해요. 사용자도 만족하고 저도 편안해졌습니다! 😊

혹시 비슷한 문제를 겪고 계신다면, 브라우저 개발자 도구로 쿠키 만료 시간을 먼저 확인해보세요. 의외로 간단한 설정 하나로 해결될 수 있어요!
