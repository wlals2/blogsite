---
title: "mTLS 실전 적용기 — 하루 만에 커밋 12개를 찍은 이유"
date: 2026-02-25T10:00:00+09:00
summary: "Istio 보안을 '이론대로' 적용하면 서비스가 죽는다. STRICT mTLS로 502, MySQL JDBC CrashLoopBackOff, AuthorizationPolicy로 서비스 접근 차단, Protocol Detection 데드락까지. 실제 커밋 이력과 함께 5번의 삽질을 복기한다."
tags: ["istio", "mtls", "security", "kubernetes", "authorizationpolicy", "troubleshooting", "service-mesh"]
categories: ["study", "Service Mesh"]
showtoc: true
tocopen: true
draft: false
---
## 배경 — "보안 적용하면 끝이겠지"

[#6 mTLS 이론편](/study/2026-01-22-istio-mtls-security/)에서 Istio의 mTLS와 AuthorizationPolicy를 다뤘다. PeerAuthentication으로 STRICT 모드를 켜면 mesh 내부 트래픽이 자동 암호화되고, AuthorizationPolicy로 서비스 간 접근을 제어한다.

이론은 깔끔하다. **현실은 달랐다.**

2026년 1월 20일 하루 동안 보안 관련 커밋만 12개를 찍었다. 문제를 고치면 다른 곳이 터지고, 그걸 고치면 또 다른 곳이 터졌다. 이 글은 그 하루의 기록과, 이후 한 달간 추가로 겪은 문제들을 정리한다.

---

## 삽질 타임라인 — 커밋으로 보는 하루

```
1월 20일

15:59  feat: Add Istio Service Mesh policies          ← mTLS STRICT 적용
16:36  fix: Allow plain TCP for MySQL                  ← 삽질 #1: MySQL 죽음
17:11  Fix: Change mTLS STRICT to PERMISSIVE           ← 삽질 #2: 502 Bad Gateway
17:12  feat: Enable Istio mesh routing                 ← nginx 라우팅 재설계
17:40  fix: Correct nginx proxy path                   ← 경로 수정
17:42  fix: Force HTTP/1.1 for nginx → WAS             ← 프로토콜 강제
20:30  fix: Use FQDN and correct Host header           ← PassthroughCluster 해결
21:59  feat: Implement production-grade security       ← AuthorizationPolicy 적용
22:02  fix: Adjust AuthorizationPolicy for Ingress     ← 삽질 #3: 서비스 접근 차단

1월 21일

12:18  fix: Update WAS AuthorizationPolicy             ← 삽질 #4: WAS 403
12:22  fix: Remove source namespace condition          ← namespace 매칭 실패

2월 21일

23:49  fix: MySQL JDBC + Istio Protocol Detection      ← 삽질 #5: 데드락
```

하나씩 복기한다.

---

## 삽질 #1: STRICT mTLS → MySQL CrashLoopBackOff

### 무엇을 했나

교과서대로 mTLS STRICT 모드를 적용했다:

```yaml
apiVersion: security.istio.io/v1beta1
kind: PeerAuthentication
metadata:
  name: default
  namespace: blog-system
spec:
  mtls:
    mode: STRICT  # 모든 트래픽 mTLS 강제
```

### 무엇이 터졌나

WAS(Spring Boot)가 **CrashLoopBackOff**에 빠졌다. 로그를 보니:

```
Communications link failure
The last packet sent successfully to the server was 0 milliseconds ago.
```

**MySQL JDBC 드라이버는 mTLS를 모른다.** JDBC는 평문 TCP로 MySQL에 연결하는데, STRICT 모드가 이걸 차단한 것이다.

### 어떻게 해결했나

MySQL에 mTLS 예외를 추가했다:

```yaml
apiVersion: security.istio.io/v1beta1
kind: PeerAuthentication
metadata:
  name: mysql-mtls-exception
  namespace: blog-system
spec:
  selector:
    matchLabels:
      app: mysql
  mtls:
    mode: PERMISSIVE  # MySQL만 평문 TCP 허용
```

**커밋**: `f33587a` (16:36) — STRICT 적용 37분 만에 첫 번째 핫픽스.

### 배운 것

> **데이터베이스 클라이언트(JDBC, MySQL CLI 등)는 Istio mTLS를 지원하지 않는다.**
> DB 서비스에는 반드시 PERMISSIVE 예외가 필요하다.

---

## 삽질 #2: STRICT mTLS → 502 Bad Gateway

### 무엇이 터졌나

MySQL 문제를 고치고 나니 이번에는 **브라우저에서 502 Bad Gateway**가 떴다. 블로그 자체에 접근이 안 됐다.

### 원인

당시 아키텍처에서 **Nginx Ingress Controller가 Istio mesh 밖에** 있었다:

```
브라우저 → Nginx Ingress (mesh 외부) → web Pod (mesh 내부, STRICT)
                                        ↑
                                   평문 HTTP로 접근 시도
                                   → mTLS 인증서 없어서 거부
                                   → 502 Bad Gateway
```

Nginx Ingress는 Istio sidecar가 없으므로 mTLS 인증서를 제시하지 못한다. STRICT 모드는 인증서 없는 연결을 전부 거부한다.

### 어떻게 해결했나

namespace 전체를 **PERMISSIVE**로 변경했다:

```yaml
spec:
  mtls:
    mode: PERMISSIVE  # mTLS와 평문 둘 다 허용
```

**커밋**: `0b8d573` (17:11) — "이 변경으로 502 Bad Gateway 문제 근본 해결"

### 그러면 보안은?

PERMISSIVE라도 mesh 내부 Pod끼리는 **자동으로 mTLS가 적용**된다. 양쪽 모두 sidecar가 있으면 Istio가 알아서 인증서를 교환한다. PERMISSIVE는 "mTLS를 쓸 수 있으면 쓰고, 못 쓰면 평문도 허용"이라는 의미다.

추가로, DestinationRule에서 `ISTIO_MUTUAL`을 명시하면 특정 경로의 mTLS를 강제할 수도 있다:

```yaml
trafficPolicy:
  tls:
    mode: ISTIO_MUTUAL  # 이 목적지로의 트래픽은 반드시 mTLS
```

### 배운 것

> **mesh 외부에서 오는 트래픽이 하나라도 있으면 STRICT를 쓸 수 없다.**
> PERMISSIVE + DestinationRule `ISTIO_MUTUAL` 조합이 현실적인 선택이다.

---

## 삽질 #3: AuthorizationPolicy → 모니터링 도구 접근 차단

### 무엇을 했나

mTLS 문제를 해결하고, 같은 날 밤 9시에 AuthorizationPolicy를 적용했다. Zero Trust 원칙으로 "허용한 것만 통과" 정책을 만들었다:

```yaml
# authz-web.yaml (초기 버전)
apiVersion: security.istio.io/v1beta1
kind: AuthorizationPolicy
metadata:
  name: web-authz
spec:
  selector:
    matchLabels:
      app: web
  action: ALLOW
  rules:
  - from:
    - source:
        namespaces: ["istio-system"]  # Ingress에서만 접근 허용
    to:
    - operation:
        ports: ["80"]
```

### 무엇이 터졌나

`source.namespaces: ["istio-system"]`이 **매칭되지 않았다.** Nginx Ingress는 mesh 외부이므로 Istio가 부여한 **service identity가 없다**. namespace 기반 매칭은 identity가 있어야 동작한다.

결과: **블로그 접근 불가.**

### 어떻게 해결했나 (같은 날 22:02)

source 조건을 제거하고, 포트만 허용하는 방식으로 변경했다:

```yaml
rules:
- to:
  - operation:
      ports: ["80"]      # HTTP 포트 허용
- to:
  - operation:
      ports: ["15090"]   # Prometheus 메트릭 포트 허용
```

**커밋**: `cb7e6aa` (22:02) — "Nginx Ingress는 mesh 외부이므로 source identity 없음"

### 이후 진화 과정

AuthorizationPolicy는 이후 한 달간 **10번 이상 수정**되었다:

| 날짜 | 변경 내용 | 이유 |
|------|----------|------|
| 2/6 | Grafana 도메인 추가 | 모니터링 도구 접근 필요 |
| 2/7 | Prometheus, AlertManager 추가 | 같은 이유 |
| 2/8 | 15090 포트 from 조건 제거 | Prometheus 메트릭 수집 차단됨 |
| 2/10 | Blackbox Exporter health check 허용 | 업타임 모니터링 불가 |
| 2/13 | Wazuh 도메인 추가 | SIEM 대시보드 접근 필요 |
| 2/18 | Board API 내부 전용 | 게시글 CRUD 외부 차단 |
| 2/19 | 뉴스 API GET 공개 | RSS 뉴스 조회는 공개 |
| 2/19 | OPTIONS 메서드 추가 | CORS preflight 허용 |

처음에 "한 번 설정하면 끝"이라고 생각했지만, **새 서비스를 추가할 때마다 AuthorizationPolicy를 업데이트**해야 했다.

### 배운 것

> **AuthorizationPolicy는 "한 번 설정하고 끊"이 아니다.**
> 서비스를 추가할 때마다 접근 규칙도 함께 추가해야 한다.
> 기본 DENY 정책에서는 허용하지 않으면 **무조건 차단**된다.

---

## 삽질 #4: WAS AuthorizationPolicy → 403 RBAC Denied

### 무엇을 했나

WAS에도 AuthorizationPolicy를 적용해서, **web(Nginx)에서만 WAS에 접근**할 수 있게 했다:

```yaml
# authz-was.yaml (초기 버전)
rules:
- from:
  - source:
      namespaces: ["blog-system"]
  to:
  - operation:
      ports: ["8080"]
      paths: ["/api/*"]
```

### 무엇이 터졌나

Nginx에서 WAS로 프록시할 때 **403 RBAC access denied**가 발생했다. Nginx는 같은 namespace에 있는데도 `source.namespaces` 매칭이 안 됐다.

원인: Nginx의 DestinationRule에서 `tls.mode: DISABLE`을 설정했기 때문이다. mTLS가 꺼져 있으면 Istio identity가 전달되지 않고, namespace 매칭도 실패한다.

### 어떻게 해결했나

source namespace 조건을 제거하고, 포트와 경로만으로 제어했다:

```yaml
rules:
# blog-system 내부에서 WAS 접근
- to:
  - operation:
      ports: ["8080"]
      paths: ["/api/*", "/actuator/*", "/auth/*"]
# Blackbox Exporter (monitoring namespace → health check)
- from:
  - source:
      namespaces: ["monitoring"]
  to:
  - operation:
      ports: ["8080"]
      paths: ["/actuator/health"]
# Prometheus 메트릭 수집
- to:
  - operation:
      ports: ["15090"]
```

**커밋**: `78a251a` (1/21 12:22) — "Remove source namespace condition from WAS AuthorizationPolicy"

### 배운 것

> **mTLS가 DISABLE이면 source identity 기반 정책이 동작하지 않는다.**
> `source.namespaces`, `source.principals` 조건은 mTLS가 활성화된 경우에만 사용 가능하다.
> mTLS를 끈 구간에서는 **포트 + 경로** 기반으로 제어해야 한다.

---

## 삽질 #5: MySQL Protocol Detection 데드락

한 달 뒤인 2월 21일에 발견된 문제다.

### 증상

MySQL 연결이 간헐적으로 **타임아웃**되었다. WAS 로그에 connection timeout이 찍혔지만, MySQL 자체는 정상이었다.

### 원인

MySQL Service의 port name이 `mysql`이었다:

```yaml
# 문제의 설정
ports:
- port: 3306
  name: mysql  # ← Istio가 "MySQL 프로토콜"로 인식
```

Istio Envoy는 port name을 보고 **프로토콜을 자동 감지(Protocol Detection)**한다. `mysql`이라는 이름을 보고 Envoy가 "이건 MySQL 프로토콜이니까 클라이언트가 먼저 뭔가 보내겠지"라고 기다렸다.

하지만 **MySQL은 Server-First 프로토콜**이다. 서버가 먼저 Greeting 패킷을 보낸다. Envoy는 클라이언트 데이터를 기다리고, MySQL은 아무도 연결 완료를 안 하니까 기다리고 — **양쪽이 서로 기다리는 데드락**이 발생한 것이다.

### 어떻게 해결했나

port name에 `tcp-` 접두사를 붙여서 Istio가 **순수 TCP로 처리**하게 했다:

```yaml
ports:
- port: 3306
  name: tcp-mysql  # "tcp-" 접두사 → Protocol Detection 안 함
```

동시에 DestinationRule에서도 HTTP 섹션을 제거했다:

```diff
 connectionPool:
   tcp:
     maxConnections: 20
-  http:
-    http1MaxPendingRequests: 10
-    maxRequestsPerConnection: 5
```

**커밋**: `517b2d5` (2/21) — "MySQL은 TCP 프로토콜이므로 http 섹션 제거"

### 배운 것

> **Istio Service의 port name은 프로토콜 힌트다.**
> `http-`, `grpc-`, `tcp-` 접두사로 Istio에게 프로토콜을 명시해야 한다.
> Server-First 프로토콜(MySQL, PostgreSQL, MongoDB)은 반드시 `tcp-` 접두사를 사용한다.

---

## 최종 구성 — 5번의 삽질 끝에 도달한 설정

### PeerAuthentication

```yaml
# namespace 전체: PERMISSIVE (mesh 외부 트래픽 허용)
spec:
  mtls:
    mode: PERMISSIVE

# MySQL: 별도 PERMISSIVE (JDBC 호환)
spec:
  selector:
    matchLabels:
      app: mysql
  mtls:
    mode: PERMISSIVE
```

### AuthorizationPolicy (Gateway 레벨)

```yaml
# 블로그 정적 페이지 → 전세계 공개
# 블로그 API/Board → 192.168.1.0/24만
# 모니터링 도구 → 192.168.1.0/24만
```

### Service Port Naming

```yaml
# web: HTTP 프로토콜 (기본)
name: http-web

# MySQL: TCP 강제 (Protocol Detection 방지)
name: tcp-mysql
```

### 보안 커밋 횟수

| 기간 | 커밋 수 | 주요 내용 |
|------|:---:|----------|
| 1월 20일 (하루) | 12 | mTLS + AuthorizationPolicy 초기 구현 + 3번 핫픽스 |
| 1월 21일 | 4 | WAS AuthorizationPolicy 수정 |
| 2월 6~19일 | 8 | 모니터링 도구/뉴스 API 접근 규칙 추가 |
| 2월 21일 | 1 | MySQL Protocol Detection 데드락 해결 |
| **합계** | **25+** | |

---

## 정리 — Istio 보안 적용 시 체크리스트

5번의 삽질에서 뽑아낸 체크리스트다:

**mTLS 적용 전:**
- [ ] mesh 외부에서 오는 트래픽이 있는가? → 있으면 PERMISSIVE
- [ ] DB 서비스가 있는가? → JDBC/드라이버는 mTLS 미지원, PERMISSIVE 예외 필요
- [ ] Server-First 프로토콜(MySQL, PostgreSQL)이 있는가? → port name에 `tcp-` 접두사

**AuthorizationPolicy 적용 전:**
- [ ] mTLS가 DISABLE인 구간이 있는가? → source identity 매칭 불가, 포트/경로 기반으로
- [ ] Prometheus 메트릭 수집 포트(15090)를 허용했는가?
- [ ] health check 경로를 허용했는가? (Blackbox Exporter, kubelet)

**적용 후:**
- [ ] 새 서비스 추가 시 AuthorizationPolicy도 업데이트했는가?
- [ ] CORS가 필요한 API는 OPTIONS 메서드를 허용했는가?

---

*이 글은 [Istio 실전 시리즈](/series/istio-실전-시리즈/)의 일부입니다.*
