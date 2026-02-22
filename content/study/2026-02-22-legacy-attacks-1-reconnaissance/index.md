---
title: "공격자의 시선으로 보는 보안: 1편 - 정찰과 스캐닝"
date: 2026-02-22T10:00:00+09:00
categories:
  - study
  - Security
tags: ["security", "reconnaissance", "port-scan", "nmap", "network", "legacy-attacks"]
summary: "모든 공격은 상대방을 아는 것에서 시작한다. 공격자가 첫 번째로 하는 정찰과 스캐닝 기법을 분석하고, 방어자 관점에서 어떻게 탐지하고 차단할 수 있는지 정리한다."
showtoc: true
tocopen: true
draft: false
series: ["공격자의 시선으로 보는 보안"]
series_order: 1
---

## 배경

방화벽을 세우고, IDS를 붙이고, WAF를 적용했다.
그런데 공격자는 이미 우리 네트워크가 무엇으로 이루어져 있는지 알고 들어온다.

보안 장비를 구축하기 전에 **공격자가 무엇을 먼저 보는지** 알아야 제대로 막을 수 있다.
모든 공격은 정찰(Reconnaissance)에서 시작한다.

이 시리즈는 2000년대 초부터 지금까지 유효한 고전 공격 기법을 공격자의 시선으로 분석한다.
OWASP, CVE 번호보다 더 근본적인 원리를 다룬다.

---

## 1. 정찰 단계 개요

공격자는 행동 전에 반드시 정보를 수집한다. 정찰 방식은 두 가지로 나뉜다.

| 구분 | 방식 | 대상에 흔적 | 예시 |
|------|------|------------|------|
| **Passive Reconnaissance** | 직접 접촉 없이 수집 | 없음 | WHOIS, DNS 조회, 구글 해킹 |
| **Active Reconnaissance** | 직접 패킷 전송 | 로그에 남음 | Ping Sweep, Port Scan |

---

## 2. Passive Reconnaissance

### WHOIS / DNS 조회

```bash
# 도메인 등록 정보 조회
whois target.com
# 출력: 등록자, 네임서버, 등록일, 관리자 이메일

# DNS 레코드 전체 조회
dig target.com ANY
# 출력: A, MX, NS, TXT 레코드

# 서브도메인 열거 (Zone Transfer 시도)
dig axfr @ns1.target.com target.com
# 성공 시: 모든 서브도메인 목록 노출 (현대 서버는 대부분 차단)
```

DNS Zone Transfer는 Primary → Secondary DNS 서버 동기화용이지만,
설정이 잘못되면 외부에서 도메인 전체 목록을 가져갈 수 있다.

### 구글 해킹 (Google Dork)

검색 엔진을 활용해 민감한 정보를 찾는 기법.

```
site:target.com filetype:pdf          # PDF 파일 목록
site:target.com intitle:"index of"    # 디렉터리 리스팅 활성화된 페이지
inurl:admin site:target.com           # 관리자 페이지
"DB_PASSWORD" site:github.com         # GitHub에 노출된 비밀번호
```

---

## 3. Ping Sweep (호스트 발견)

네트워크에서 살아있는 호스트를 찾는 첫 번째 Active 스캔.

### 동작 원리

```
공격자 → ICMP Echo Request (Ping) → 대상 호스트
공격자 ← ICMP Echo Reply          ← 대상 호스트 (살아있음)
공격자   (응답 없음)                  대상 호스트 (꺼져있거나 차단)
```

```bash
# ICMP Ping Sweep (nmap)
nmap -sn 192.168.1.0/24
# 출력: 살아있는 호스트 목록

# ICMP 차단된 경우 - TCP Ping으로 우회
nmap -sn -PS80,443 192.168.1.0/24
# 포트 80, 443으로 SYN을 보내 응답 여부로 호스트 확인
```

방화벽이 ICMP를 차단해도 HTTP/HTTPS 포트로 응답하면 탐지된다.

---

## 4. Port Scanning

살아있는 호스트에서 어떤 서비스가 열려있는지 확인한다.

### 4.1 TCP Connect Scan

```
공격자 → SYN →      서버
공격자 ← SYN+ACK ← 서버 (포트 열림)
공격자 → ACK →      서버
공격자 → RST →      서버 (연결 즉시 종료)
```

완전한 3-way handshake를 맺기 때문에 **서버 로그에 반드시 기록**된다.
```bash
nmap -sT 192.168.1.100
```

### 4.2 SYN Scan (Half-open Scan)

```
공격자 → SYN →      서버
공격자 ← SYN+ACK ← 서버 (포트 열림 확인)
공격자 → RST →      서버 (연결 완성 없이 종료)
```

ACK를 보내지 않아 연결이 완성되지 않는다.
일부 오래된 시스템에서는 로그에 기록되지 않아 **스텔스 스캔**이라고도 불렸다.
현대 IDS는 대부분 탐지한다.

```bash
# root 권한 필요 (raw socket)
nmap -sS 192.168.1.100
```

### 4.3 FIN / NULL / XMAS Scan

RFC 793에 따르면, 열린 포트는 FIN/NULL/XMAS 패킷에 응답하지 않고
닫힌 포트는 RST를 반환해야 한다.

```bash
nmap -sF 192.168.1.100   # FIN Scan
nmap -sN 192.168.1.100   # NULL Scan (플래그 없음)
nmap -sX 192.168.1.100   # XMAS Scan (FIN+PSH+URG)
```

Windows는 RFC를 엄격히 따르지 않아 이 스캔이 효과 없다.
Linux/Unix 계열에서 방화벽 없이 동작하는 경우에 유효하다.

### 4.4 UDP Scan

```bash
nmap -sU 192.168.1.100
```

UDP는 연결 상태가 없어 응답이 없으면 열린 것인지 차단된 것인지 구분이 어렵다.
TCP보다 훨씬 느리고 오탐이 많다. DNS(53), SNMP(161), DHCP(67/68)가 주요 대상.

### 포트 상태 해석

| 상태 | 의미 |
|------|------|
| open | 서비스가 수신 중 |
| closed | 포트는 접근 가능하지만 서비스 없음 |
| filtered | 방화벽이 패킷 차단 (응답 없음) |
| open\|filtered | 열린 것인지 필터된 것인지 불명확 (UDP 주로) |

---

## 5. 서비스 버전 탐지 (Banner Grabbing)

포트가 열린 것을 확인하면, 다음은 서비스의 버전을 파악한다.

### 배너 그래빙

서버는 연결 시 자신의 정보를 응답 헤더에 포함하는 경우가 많다.

```bash
# HTTP 배너
curl -I http://target.com
# 출력:
# Server: Apache/2.4.49 (Ubuntu)
# X-Powered-By: PHP/7.4.3

# SSH 배너
nc target.com 22
# 출력:
# SSH-2.0-OpenSSH_7.4
```

Apache 2.4.49는 실제로 Path Traversal 취약점(CVE-2021-41773)이 존재했다.
버전이 노출되면 공격자는 해당 버전의 CVE를 바로 검색한다.

```bash
# nmap 서비스 버전 탐지
nmap -sV 192.168.1.100
# 출력:
# 22/tcp open  ssh     OpenSSH 7.4 (protocol 2.0)
# 80/tcp open  http    Apache httpd 2.4.49
# 3306/tcp open mysql  MySQL 5.7.38
```

---

## 6. OS Fingerprinting

TCP/IP 스택의 구현 방식이 OS마다 다르다는 점을 이용해 OS를 판별한다.

### 판별 요소

| 요소 | Linux | Windows |
|------|-------|---------|
| TTL 초기값 | 64 | 128 |
| TCP Window Size | 29200 | 65535 |
| TCP Timestamp | 있음 | 없음 (기본) |
| ICMP Error 메시지 | RFC 준수 | 독자적 |

```bash
nmap -O 192.168.1.100
# 출력:
# OS details: Linux 4.15 - 5.6
# Network Distance: 1 hop
```

---

## 7. 탐지와 방어

### 공격자가 남기는 흔적

```bash
# 짧은 시간에 대량 포트 접근 → IDS 탐지 패턴
# /var/log/auth.log (SSH 연결 시도)
# /var/log/nginx/access.log (HTTP 요청)
# iptables 로그 (차단된 패킷)

# 비정상 TCP 플래그 패킷 (FIN/NULL/XMAS)
# ICMP 대량 발생 (Ping Sweep)
```

### 방어 방법

**배너 제거/변경** (서버 버전 숨기기):
```nginx
# Nginx
server_tokens off;

# Apache
ServerTokens Prod
ServerSignature Off
```

**포트 스캔 탐지** (iptables 예시):
```bash
# 초당 20개 이상 새 연결 → 차단
iptables -A INPUT -p tcp --syn -m limit --limit 1/s --limit-burst 20 -j ACCEPT
iptables -A INPUT -p tcp --syn -j DROP
```

**불필요한 서비스 종료**:
```bash
ss -tlnp          # 열린 포트 확인
systemctl disable <서비스>  # 불필요한 서비스 종료
```

**ICMP 제한**:
```bash
# Ping Sweep 방어 (ICMP Rate Limiting)
iptables -A INPUT -p icmp -m limit --limit 1/second -j ACCEPT
iptables -A INPUT -p icmp -j DROP
```

**Zone Transfer 차단** (BIND DNS):
```
allow-transfer { none; };
```

---

## 정리

| 기법 | 목적 | 탐지 방법 | 방어 방법 |
|------|------|----------|----------|
| WHOIS/DNS 조회 | 도메인/IP 정보 수집 | 탐지 불가 (Passive) | 최소 정보만 등록 |
| Ping Sweep | 살아있는 호스트 파악 | IDS (ICMP 대량) | ICMP Rate Limiting |
| Port Scan | 열린 포트 확인 | IDS (다수 포트 접근) | 방화벽, 불필요 포트 차단 |
| Banner Grabbing | 서비스 버전 파악 | 접근 로그 | 배너 숨기기, 버전 업데이트 |
| OS Fingerprinting | 운영체제 판별 | 비정상 패킷 모니터링 | TTL 변조, 방화벽 |

공격자는 이 정보를 바탕으로 다음 단계로 넘어간다.

다음 편에서는 수집한 정보를 이용한 **네트워크 레이어 공격 (ARP Poisoning, IP Spoofing, DNS Poisoning, SYN Flood)**을 다룬다.

---

*시리즈: 공격자의 시선으로 보는 보안*
- **1편: 정찰과 스캐닝** ← 현재 글
- 2편: 네트워크 레이어 공격 (ARP, IP Spoofing, DNS, SYN Flood)
- 3편: 웹 공격 Part 1 - Injection (SQL, XSS, Command)
- 4편: 웹 공격 Part 2 - 인증/세션/접근 제어
- 5편: WAF 우회 기법
