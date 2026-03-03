---
title: "SSH 공격 대응 중 발견한 것: 웹 스캐너도 왔다 갔다"
date: 2026-02-23T23:00:00+09:00
categories:
  - study
  - Security
tags: ["security", "web-security", "wazuh", "istio", "cloudflare", "nginx", "attack-analysis", "env-scan", "linux-hardening"]
summary: "SSH Brute Force 대응 완료 직후, Istio 액세스 로그를 확인했다. 4개 IP에서 17건의 웹 공격이 시도되어 있었다. .env 탈취, .git/config 노출, PHP RCE까지. 그리고 Wazuh는 아무것도 탐지하지 못했다."
showtoc: true
tocopen: true
draft: false
series: ["홈랩 보안 시리즈"]
---

## 배경

SSH Brute Force 대응(238,903건)을 마무리하고, 같은 날 Istio 액세스 로그를 확인했다.

SSH 공격은 Wazuh가 탐지했다. 웹 공격은?

```bash
$ kubectl logs -n istio-system istio-ingressgateway-... --since=168h \
  | grep -E "(\.env|\.git|\.php|eval-stdin)"
# 출력: 17건의 의심 요청 확인
```

Wazuh는 아무 알람도 보내지 않았다. Wazuh가 Nginx access log를 읽고 있지 않았기 때문이다.

---

## 탐지: Istio 로그에서 발견

Istio Ingress Gateway는 모든 외부 트래픽의 진입점이다. SSH 공격과 달리, 웹 공격은 Cloudflare를 통과해 들어왔다.

```
외부 공격자
  → Cloudflare (DDoS 방어)
  → MetalLB 192.168.1.200
  → Istio Ingress Gateway  ← 로그가 여기 남음
  → blog-system namespace (Nginx + Hugo)
```

7일치 로그에서 공격 패턴을 추출했다.

```bash
$ kubectl logs -n istio-system istio-ingressgateway-... --since=168h \
  | grep -E "(\.env|\.git|\.php|phpunit|phpinfo)" \
  | awk '{match($0, /"[A-Z]+ [^ ]+ HTTP[^"]*"/); print substr($0, RSTART+1, RLENGTH-2)}' \
  | sort | uniq -c | sort -rn
# 출력:
#  4 GET /.env HTTP/1.1
#  2 GET /.git/config HTTP/1.1
#  2 GET /.env.bak HTTP/1.1
#  ... (총 17건)
```

---

## 분석: 어떤 공격이었나

4개 IP에서 시도됐다. 모두 자동화 스캐너 패턴이다.

### 공격 유형 1 — 환경 파일 탈취 시도

`.env` 파일에는 DB 비밀번호, API 키, Secret 같은 민감 정보가 담긴다. 스캐너는 여러 경로를 시도했다.

```
GET /.env
GET /.env.bak
GET /.env.backup
GET /.env.save
GET /.env.example
GET /admin/.env
GET /backend/.env
```

### 공격 유형 2 — Git 저장소 노출 시도

`.git/config`가 노출되면 저장소 URL, 브랜치, 원격 서버 정보를 얻을 수 있다. 더 나아가 `.git/FETCH_HEAD`, `.git/logs/HEAD` 등을 통해 소스코드 전체를 재구성할 수 있다.

```
GET /.git/config
```

### 공격 유형 3 — PHP 취약점 탐지

서버가 PHP를 실행하는지, 어떤 버전인지 파악하려는 시도다.

```
GET /info.php          ← PHP 버전 탐지
GET /phpinfo.php       ← PHP 설정 전체 노출
GET /test.php          ← 테스트 파일 잔재 탐지
```

### 공격 유형 4 — PHP RCE (원격 코드 실행)

PHPUnit은 테스트 도구인데, 특정 버전에 취약점이 있다. 이 취약점을 이용해 서버에서 임의 코드를 실행하려는 시도다.

```
POST /vendor/phpunit/phpunit/src/Util/PHP/eval-stdin.php
```

`POST`이므로 실제 페이로드를 함께 전송했다. `405 Method Not Allowed` 응답으로 차단됐다.

---

## 왜 피해가 없었나

모든 요청이 `200 OK`를 반환했다. 그런데 응답 크기를 보면 이유가 명확하다.

```
GET /.env           200  54,756 bytes
GET /.git/config    200  54,756 bytes
GET /phpinfo.php    200  54,756 bytes
```

54,756 bytes는 Hugo 블로그 홈페이지 크기다.

Hugo는 정적 사이트 생성기다. `public/` 디렉터리에 빌드 결과물(HTML, CSS, JS, 이미지)만 남는다. `.env`도, `.git`도, PHP 파일도 없다. Nginx는 없는 경로를 요청받으면 `404` 대신 Hugo 홈페이지를 반환했다.

공격자 입장에서는 `200 OK`를 받았지만, 받은 것은 블로그 메인 페이지였다.

```
공격자가 기대한 것:
  /.env → "DB_PASSWORD=xxx\nSECRET_KEY=xxx"

실제로 받은 것:
  /.env → <html>...(블로그 홈페이지, 54756 bytes)...</html>
```

PHP RCE 시도는 `405` 응답으로 차단됐다. Nginx가 POST 메서드를 처리하지 않는 경로였다.

---

## 현재 방어 레이어

이번 공격이 피해 없이 지나간 이유는 공격을 막은 게 아니라 **공격할 것이 없었기** 때문이다.

```
[Cloudflare]      DDoS 방어, 기본 WAF 동작 중
  ↓
[Istio Gateway]   트래픽 라우팅 (L7)
  ↓
[Nginx]           정적 파일 서빙 (Hugo 빌드 결과물만)
  ↓
[Hugo 정적 사이트] PHP 없음, .env 없음, .git 없음
```

공격자가 찾던 파일들이 존재하지 않는다. 공격 표면(Attack Surface) 자체가 작다.

---

## 발견된 문제: Wazuh가 탐지하지 못했다

SSH 공격은 Wazuh가 탐지했다. 웹 공격은 탐지하지 못했다.

Wazuh Agent가 읽는 로그:

| 로그 파일 | 탐지 대상 | 현재 상태 |
|-----------|----------|-----------|
| `/var/log/auth.log` | SSH 인증 실패 | ✅ 모니터링 중 |
| `/var/log/containers/falco-*.log` | 런타임 이상 행동 | ✅ 모니터링 중 |
| `/var/log/syslog` | 시스템 이벤트 | ✅ 모니터링 중 |
| `/var/log/containers/web-*.log` | **Nginx 액세스 로그** | ❌ **미설정** |

Nginx access log가 Wazuh에 연결되지 않았다. `.env` 스캔 10건이 들어와도 알람이 없다.

---

## 보안 강화 조치

### 조치 1 — Wazuh Agent에 Nginx 로그 추가 (예정)

Wazuh Agent ConfigMap에 경로를 추가한다.

```xml
<localfile>
  <!-- Why: Nginx access log → 웹 공격 패턴 탐지 -->
  <log_format>syslog</log_format>
  <location>/var/log/containers/web-*.log</location>
</localfile>
```

Wazuh에는 웹 공격 탐지 규칙이 내장되어 있다. Rule Group `web`, `attack` 이 활성화되면 `.env` 스캔, SQLi, XSS 패턴을 탐지하고 Discord 알람을 전송한다.

### 조치 2 — Nginx에서 공격 경로 차단 (예정)

Hugo 정적 사이트에 `.env`, `.git`, `.php` 경로는 존재할 이유가 없다. Nginx에서 직접 차단한다.

```nginx
# Why: 스캐너가 찾는 경로, 정적 사이트에 존재하지 않음
location ~* \.(env|git|php|asp|aspx|jsp)$ {
    return 444;  # Connection 자체를 끊음 (응답 없음)
}

location ~ /\. {
    return 444;  # .으로 시작하는 숨김 파일 전체 차단
}
```

현재 `200 OK`(Hugo 홈페이지)를 반환하는 것을 `444`(응답 없음)로 변경한다. 스캐너 입장에서는 서버가 없는 것처럼 보인다.

### 조치 3 — ufw K8s 포트 정리 (예정)

ufw를 확인하니 SSH 포트 외에도 K8s 관련 포트가 `Anywhere`로 열려있었다.

```
[9]  6443/tcp   ALLOW IN  Anywhere   ← K8s API 서버
[10] 2379:2380  ALLOW IN  Anywhere   ← etcd
[11] 10250/tcp  ALLOW IN  Anywhere   ← kubelet API
```

공인 IP가 제거되어 지금은 직접 접근이 불가능하지만, 규칙 자체는 정리가 필요하다.

---

## 성과

| 항목 | 현재 | 목표 |
|------|------|------|
| 웹 공격 탐지 | ❌ Istio 로그 수동 확인만 가능 | ✅ Wazuh 실시간 알람 |
| `.env` 스캔 응답 | `200 OK` (Hugo 홈페이지) | `444` (응답 없음) |
| K8s API 포트 노출 | Anywhere (ufw 규칙상) | 내부망만 허용 |
| etcd 포트 노출 | Anywhere (ufw 규칙상) | 내부망만 허용 |

---

## 교훈

**1. 탐지 범위를 명확히 알아야 한다.**
Wazuh가 모든 것을 탐지한다고 생각했다. 실제로는 설정한 로그만 탐지한다. "Wazuh가 탐지하지 않았으니 공격이 없었다"는 잘못된 가정이다.

**2. 공격이 실패한 이유를 분석해야 한다.**
이번에 피해가 없었던 건 방어를 잘해서가 아니라 Hugo 정적 사이트라 공격 대상이 없었기 때문이다. Spring Boot WAS 같은 동적 서버였다면 결과가 달랐을 수 있다.

**3. 스캐너는 항상 온다.**
공인 IP가 붙은 서버는 24시간 이내에 스캐너에 발견된다. SSH 포트도, 웹 포트도 마찬가지다. "아무도 모를 것"이라는 가정 자체가 틀렸다.

---

## 다음 단계

- [ ] Wazuh Agent ConfigMap에 `/var/log/containers/web-*.log` 추가
- [ ] Nginx ConfigMap에 `.env`, `.git`, `.php` 경로 차단 규칙 추가
- [ ] ufw K8s 포트 규칙 정리 (6443, 2379:2380, 10250 → 내부망만)
- [ ] Cloudflare WAF 커스텀 룰 검토 (PHP 경로, `.env` 요청 차단)
