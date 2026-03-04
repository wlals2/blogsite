---
title: "MySQL 연결이 Broken pipe로 끊기는 문제 해결기"
date: 2025-12-21T09:00:00+09:00
tags: ["MySQL", "Connection Pool", "Broken Pipe", "Tomcat JDBC", "DR"]
categories: ["Troubleshooting"]
description: "Azure DR 환경에서 장시간 유휴 후 MySQL 연결이 Broken pipe로 끊기는 문제를 Connection Pool 검증 설정으로 해결한 과정을 공유합니다. wait_timeout의 원리와 testOnBorrow, testWhileIdle 설정 방법을 다룹니다."
---

## 들어가며

Azure DR 환경에 Failover 테스트를 하고 8시간쯤 지나서 접속해봤어요. 로그인을 하려는데 이런 에러가 나더라고요:

```
com.mysql.cj.exceptions.CJCommunicationsException:
The last packet successfully received from the server was 61,119,581 milliseconds ago.
...
Caused by: java.net.SocketException: Broken pipe
```

61,119,581 milliseconds는 약 17시간이에요. "17시간 동안 연결을 안 썼더니 끊어졌다"는 뜻이죠.

이 글에서는 이 문제의 원인과 해결 과정을 공유하려고 합니다.

---

## 문제 현상

### 증상

```
Azure Failover 후 로그인 시도 → 에러 발생
```

### 에러 로그

```
com.mysql.cj.exceptions.CJCommunicationsException:
The last packet successfully received from the server was 61,119,581 milliseconds ago.
...
Caused by: java.net.SocketException: Broken pipe
```

### 발생 조건

- DR 환경이 오랜 시간 (8시간 이상) 유휴 상태
- Failover 후 첫 로그인 시도 시

처음엔 "왜 운영 환경에서는 안 나는데 DR에서만 나지?"라고 생각했어요.

---

## 원인 분석

### MySQL wait_timeout

MySQL에는 `wait_timeout`이라는 설정이 있어요:

```
┌─────────────────────────────────────────────────────────────────┐
│  MySQL 서버 설정                                                 │
├─────────────────────────────────────────────────────────────────┤
│  wait_timeout = 28800초 (8시간)                                  │
│                                                                  │
│  의미: 유휴 연결이 8시간 동안 쿼리가 없으면 자동 종료            │
│  이유: 리소스 보호, 좀비 연결 방지, 보안                         │
└─────────────────────────────────────────────────────────────────┘
```

MySQL은 연결을 계속 열어두면 메모리를 먹으니까, 사용하지 않는 연결은 자동으로 끊어버려요.

### 문제 발생 과정

시간 순서로 보면 이래요:

```
[시간 흐름]

t=0     WAS 시작 → MySQL 연결 생성
        │
        │  (DR 대기 상태 - 트래픽 없음)
        │
t=8시간  MySQL: "wait_timeout 초과!" → 연결 강제 종료
        │
        │  (WAS는 모름 - TCP 알림 없음)
        │
t=17시간 사용자 로그인 시도
        │
        ▼
        WAS: 끊어진 연결로 쿼리 시도 → "Broken pipe" 에러!
```

핵심은 **WAS가 연결이 끊어진 걸 모른다**는 거예요.

### 왜 WAS는 모르는가?

| 상황 | MySQL | WAS (Tomcat) |
|------|-------|--------------|
| 8시간 경과 | 연결 종료함 | 모름 |
| 연결 상태 | 없음 | "살아있다고 착각" |
| 쿼리 시도 | - | 에러 발생! |

**TCP 특성:**
- MySQL이 연결을 끊어도 WAS에 알림이 가지 않아요
- WAS는 실제로 쿼리를 보낼 때서야 끊어진 것을 알게 돼요

그래서 첫 번째 로그인 시도에서 에러가 나는 거죠.

---

## 해결 방법: Connection Pool 검증 설정

### 기존 설정 (문제 있음)

처음엔 이렇게만 되어 있었어요:

```xml
<!-- datasource-config.xml -->
<bean id="dataSource" class="org.apache.tomcat.jdbc.pool.DataSource"
      p:driverClassName="com.mysql.cj.jdbc.Driver"
      p:url="jdbc:mysql://..."
      p:username="dbadmin"
      p:password="PetclinicDR2024"/>
```

Connection Pool이 연결을 주기만 하고, 그 연결이 살아있는지는 확인 안 했어요.

### 개선된 설정 (문제 해결)

검증 설정을 추가했어요:

```xml
<bean id="dataSource" class="org.apache.tomcat.jdbc.pool.DataSource"
      p:driverClassName="com.mysql.cj.jdbc.Driver"
      p:url="jdbc:mysql://..."
      p:username="dbadmin"
      p:password="PetclinicDR2024"
      p:testOnBorrow="true"
      p:validationQuery="SELECT 1"
      p:testWhileIdle="true"
      p:timeBetweenEvictionRunsMillis="30000"
      p:minEvictableIdleTimeMillis="60000"/>
```

### 설정 설명

| 설정 | 값 | 의미 | 왜 필요한가? |
|------|-----|------|------------|
| `testOnBorrow` | true | 연결 빌릴 때 검증 | 끊어진 연결 사용 방지 |
| `validationQuery` | SELECT 1 | 검증용 쿼리 | 가장 빠른 쿼리 |
| `testWhileIdle` | true | 유휴 시에도 검증 | 사전에 끊어진 연결 제거 |
| `timeBetweenEvictionRunsMillis` | 30000 | 30초마다 검증 실행 | 8시간 기다리지 않고 미리 확인 |
| `minEvictableIdleTimeMillis` | 60000 | 1분 이상 유휴 시 제거 대상 | 오래된 연결 정리 |

### 동작 방식

이제 Connection Pool이 이렇게 동작해요:

```
[검증 설정 적용 후]

앱: "연결 하나 주세요"
     │
     ▼
Pool: "잠깐, 먼저 검증할게"
     │
     ▼
Pool: "SELECT 1" 실행
     │
     ├── 성공 → 이 연결 반환 ✅
     │
     └── 실패 → 연결 버리고 새로 생성 → 반환 ✅
```

**핵심:** 끊어진 연결은 절대 애플리케이션에 주지 않아요.

---

## 대안 해결 방법

다른 방법들도 있었는데, 왜 안 썼는지 설명할게요.

### 1. JDBC autoReconnect (권장하지 않음)

```properties
jdbc.url=jdbc:mysql://...?autoReconnect=true
```

**왜 안 썼나?**
- MySQL 8.0 이후 deprecated
- 트랜잭션 중 재연결 시 데이터 손실 위험
- "쿼리 실행 중에 연결이 끊어지면 자동으로 재연결"인데, 트랜잭션 상태는 복구 안 됨

### 2. MySQL wait_timeout 증가

```sql
-- Azure MySQL에서 실행
SET GLOBAL wait_timeout = 86400;  -- 24시간
```

**왜 안 썼나?**
- 좀비 연결 누적
- 리소스 낭비 (메모리, 커넥션 수)
- MySQL이 wait_timeout을 설정한 이유를 무시하는 거라 근본 해결 아님

### 3. Keep-alive 쿼리 (cron)

```bash
# 매 시간 MySQL에 쿼리 전송하여 연결 유지
0 * * * * mysql -h ... -e "SELECT 1"
```

**왜 안 썼나?**
- 복잡함 (cron 설정, 스크립트 관리)
- Connection Pool 단위가 아니라 MySQL 단위 (의미 없음)
- 근본 해결 아님

**결론:** Connection Pool 검증이 가장 간단하고 안전해요.

---

## 운영 vs DR 환경 차이

왜 운영 환경에서는 이 문제가 안 나왔을까요?

```
[AWS 운영 환경]
  │
  │  지속적인 사용자 요청
  │  ↓
  │  연결이 계속 사용됨
  │  ↓
  │  wait_timeout 계속 리셋
  │  ↓
  │  연결 끊어지지 않음 ✅


[Azure DR 환경]
  │
  │  사용자 없음 (대기 상태)
  │  ↓
  │  연결 유휴 상태
  │  ↓
  │  wait_timeout 초과
  │  ↓
  │  MySQL이 연결 종료
  │  ↓
  │  (검증 설정 있으면) 자동 재연결 ✅
  │  (검증 설정 없으면) 에러 ❌
```

**운영 환경:** 트래픽이 계속 있어서 연결이 끊어질 일이 없었어요.

**DR 환경:** 대기 상태라 8시간 동안 쿼리가 한 번도 안 날아가니까 연결이 끊어졌던 거죠.

---

## 확인 방법

### 1. MySQL 설정 확인

현재 wait_timeout이 얼마인지 확인:

```bash
ssh azureuser@20.249.72.191 "
mysql -h dr-petclinic-mysql.mysql.database.azure.com \
  -u dbadmin -p'PetclinicDR2024' \
  -e 'SHOW VARIABLES LIKE \"wait_timeout\";'
"
```

**예상 결과:**
```
+--------------+-------+
| Variable_name| Value |
+--------------+-------+
| wait_timeout | 28800 |
+--------------+-------+
```

28800초 = 8시간이에요.

### 2. Connection Pool 설정 확인

datasource-config.xml에 검증 설정이 있는지 확인:

```bash
ssh azureuser@20.249.72.191 "
ssh 172.160.1.4 'sudo grep testOnBorrow /opt/tomcat/webapps/petclinic/WEB-INF/classes/spring/datasource-config.xml'
"
```

**예상 결과:**
```xml
p:testOnBorrow="true"
```

### 3. Tomcat 재시작 후 테스트

설정을 변경했으면 Tomcat을 재시작해야 해요:

```bash
# Tomcat 재시작
ssh azureuser@20.249.72.191 "ssh 172.160.1.4 'sudo systemctl restart tomcat'"

# 10초 후 접속 테스트
curl -sL https://app.goupang.shop/petclinic/ -o /dev/null -w "%{http_code}"
```

**예상 결과:** `200`

---

## 트러블슈팅: 겪었던 실수들

### 실수 1: validationQuery 없이 testOnBorrow만 설정

처음엔 `testOnBorrow="true"`만 넣고 `validationQuery`를 안 넣었어요.

**결과:** 에러 발생

```
java.sql.SQLException: validationQuery is set to null
```

**해결:** `validationQuery="SELECT 1"` 추가

### 실수 2: timeBetweenEvictionRunsMillis를 너무 크게 설정

처음엔 1시간(3600000ms)으로 설정했어요.

**문제:** 1시간마다만 체크하니까 wait_timeout(8시간)보다는 짧지만, 첫 로그인 시 여전히 끊어진 연결을 받을 수 있었어요.

**해결:** 30초(30000ms)로 줄임. 8시간 안에 최소 960번 체크하니까 충분해요.

### 실수 3: testWhileIdle 없이 testOnBorrow만 설정

`testOnBorrow`만 있으면 "연결을 빌릴 때만" 검증해요.

**문제:** 8시간 동안 아무도 로그인 안 하면 끊어진 연결이 Pool에 계속 남아있어요.

**해결:** `testWhileIdle="true"` 추가. 30초마다 유휴 연결도 검증해서 끊어진 건 미리 제거해요.

---

## 배운 점

### wait_timeout은 적이 아니다

처음엔 "wait_timeout을 늘리면 되지 않나?"라고 생각했어요. 하지만 이건 MySQL이 리소스를 보호하기 위한 정상적인 설정이에요.

문제는 MySQL이 아니라 **WAS가 끊어진 연결을 모른다는 것**이었어요.

### Connection Pool 검증은 필수

운영 환경에서는 트래픽이 계속 있어서 문제가 안 보였지만, DR 환경처럼 **유휴 상태가 긴 환경**에서는 Connection Pool 검증이 필수예요.

프로덕션에서도 새벽 시간대에 트래픽이 없으면 같은 문제가 생길 수 있어요.

### testOnBorrow vs testWhileIdle

- `testOnBorrow`: 연결을 빌릴 때 검증 → 첫 요청 시 약간 느려짐
- `testWhileIdle`: 백그라운드에서 미리 검증 → 요청 시 빠름

**결론:** 둘 다 켜는 게 베스트예요. 성능 영향은 미미하고, 안정성은 훨씬 좋아져요.

---

## 마무리

MySQL 연결이 Broken pipe로 끊기는 문제를 Connection Pool 검증 설정으로 해결했습니다.

핵심은 **끊어진 연결을 사전에 감지하고 제거**하는 거예요. `testOnBorrow`, `testWhileIdle`, `validationQuery` 이 세 가지만 제대로 설정하면 됩니다.

운영 환경에서는 트래픽이 많아서 문제가 안 보일 수 있지만, DR 환경이나 새벽 시간대처럼 유휴 시간이 긴 경우를 대비해서 꼭 설정하는 걸 추천해요.

| 문제 | 원인 | 해결 |
|------|------|------|
| 로그인 실패 | MySQL wait_timeout 초과 | Connection Pool 검증 설정 |
| Broken pipe | 끊어진 연결 사용 | testOnBorrow=true |
| DR 환경 특유 | 장시간 유휴 상태 | 30초마다 자동 검증 |

다음 글에서는 Canary 배포 전략을 비교하고 ALB Traffic Routing을 선택한 이유를 공유할게요!
