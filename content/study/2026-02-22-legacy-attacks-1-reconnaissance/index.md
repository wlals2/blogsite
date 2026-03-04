---
title: "공격자의 시선으로 보는 보안: 1편 - 정찰과 스캐닝"
date: 2026-02-22T10:00:00+09:00
categories:
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

### ICMP 패킷 구조

ICMP는 IP 패킷 안에 캡슐화된다. Ping에 사용되는 ICMP Echo의 구조:

```
[Ethernet Header (14 bytes)]
[IP Header (20 bytes)]
  └─ Protocol: 1 (ICMP)
[ICMP Header (8 bytes)]
  ├─ Type (1 byte)     ← 8: Echo Request, 0: Echo Reply
  ├─ Code (1 byte)     ← 0
  ├─ Checksum (2 bytes)
  ├─ Identifier (2 bytes)  ← 요청/응답 매칭용
  └─ Sequence (2 bytes)    ← 순서 번호
[ICMP Data (가변)]
  └─ 패딩 데이터 (기본 56 bytes)
```

Ping Sweep에서는 Type 8 패킷을 대량으로 보낸다.
정상적인 호스트는 Type 0으로 응답하고, 방화벽이 차단하면 응답이 없다.

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

### TCP 헤더 구조 (Port Scan 이해의 핵심)

Port Scan을 이해하려면 TCP 헤더의 **Flags 필드**를 알아야 한다.
스캔 기법마다 이 Flags를 다르게 조합해서 보낸다.

```
TCP Header (20 bytes 기본):
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
├─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┤
│         Source Port (16 bits)        │       Dest Port (16 bits)        │
├─────────────────────────────────────┼──────────────────────────────────┤
│                    Sequence Number (32 bits)                            │
├────────────────────────────────────────────────────────────────────────┤
│                 Acknowledgment Number (32 bits)                        │
├────────┼────────┼──┼──┼──┼──┼──┼──┼────────────────────────────────────┤
│Offset  │Reserved│U │A │P │R │S │F │         Window Size               │
│(4 bits)│        │R │C │S │S │Y │I │                                    │
│        │        │G │K │H │T │N │N │                                    │
├────────┴────────┴──┴──┴──┴──┴──┴──┼────────────────────────────────────┤
│          Checksum                  │        Urgent Pointer              │
└────────────────────────────────────┴────────────────────────────────────┘
```

**6개의 TCP Flags** (각 1 bit):

| Flag | 이름 | 역할 | 스캔 사용 |
|------|------|------|----------|
| **SYN** | Synchronize | 연결 시작 요청 | SYN Scan, Connect Scan |
| **ACK** | Acknowledge | 수신 확인 | Connect Scan (3-way) |
| **FIN** | Finish | 연결 종료 | FIN Scan |
| **RST** | Reset | 연결 강제 종료 | 닫힌 포트의 응답 |
| **PSH** | Push | 즉시 전달 | XMAS Scan |
| **URG** | Urgent | 긴급 데이터 | XMAS Scan |

스캔 기법은 결국 이 Flags의 조합이다.

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

**각 스캔의 TCP Flags 바이트**:
```
TCP Flags 바이트 (오프셋 13):

NULL Scan:  0x00  [. . . . . .]   ← 모든 Flag가 0
FIN Scan:   0x01  [. . . . . F]   ← FIN만 1
XMAS Scan:  0x29  [. . P . . F]   ← FIN + PSH + URG 동시 설정
                       U       I       "크리스마스 트리처럼 다 켜놨다"
                       R       N
                       G

정상 SYN:   0x02  [. . . . S .]   ← 비교용
```

각 스캔에 대한 서버 응답:
```
열린 포트:  응답 없음 (무시)     → open|filtered
닫힌 포트:  RST (0x14) 반환     → closed
필터링됨:   응답 없음            → filtered
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

### IP 헤더에서 TTL 위치

```
IP Header (20 bytes):
 0       4       8      12      16      20      24      28      32
├───────┼───────┼───────┼───────┼───────┼───────┼───────┼───────┤
│Version│  IHL  │   Type of Service     │      Total Length        │
├───────┴───────┼───────────────────────┼─────────────────────────┤
│  Identification                       │Flags│  Fragment Offset   │
├───────────────┼───────────────────────┼─────────────────────────┤
│  TTL (8bit)   │  Protocol (8bit)      │   Header Checksum       │
│  ↑ 여기!       │  (6=TCP, 17=UDP,      │                         │
│  64=Linux      │   1=ICMP)             │                         │
│  128=Windows   │                       │                         │
├───────────────┴───────────────────────┼─────────────────────────┤
│              Source IP Address (32 bits)                         │
├─────────────────────────────────────────────────────────────────┤
│           Destination IP Address (32 bits)                       │
└─────────────────────────────────────────────────────────────────┘
```

TTL은 IP 헤더의 오프셋 8 위치에 있다.
패킷이 라우터를 하나 지날 때마다 TTL이 1씩 감소한다.
수신한 패킷의 TTL이 62이면 → 초기값 64에서 라우터 2개를 거침 → Linux 추정.
TTL이 125이면 → 초기값 128에서 라우터 3개를 거침 → Windows 추정.

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

## 8. Wireshark로 스캔 확인하기

스캔 기법은 결국 패킷이다. Wireshark로 어떤 스캔인지 식별할 수 있다.

### 캡처 시작

```bash
# 특정 인터페이스에서 캡처 (CLI)
tshark -i eth0 -w scan_capture.pcap

# Wireshark GUI에서 캡처
# 1. Capture > Interfaces > eth0 선택
# 2. Start 클릭
# 3. 스캔 실행 후 Stop
```

### 스캔 유형별 Wireshark 필터

**Ping Sweep 탐지**:
```
icmp.type == 8
```
짧은 시간에 다수의 목적지 IP로 ICMP Echo Request가 나가면 Ping Sweep이다.
Statistics > Conversations > IPv4 탭에서 대상 IP 수를 확인한다.

**SYN Scan 탐지**:
```
tcp.flags.syn == 1 && tcp.flags.ack == 0
```
동일 Source IP에서 다수의 Destination Port로 SYN 패킷이 나간다.
SYN+ACK 수신 후 ACK 대신 RST를 보내면 SYN Scan이다.
```
# SYN 후 RST 패턴 확인 (SYN Scan의 특징)
tcp.flags == 0x002 || tcp.flags == 0x004
```

**TCP Connect Scan 탐지**:
```
tcp.flags.syn == 1
```
SYN Scan과 달리 완전한 3-way handshake(SYN→SYN+ACK→ACK) 후 즉시 RST가 나간다.
Statistics > Flow Graph에서 SYN→SYN+ACK→ACK→RST 패턴이 반복되면 Connect Scan이다.

**FIN/NULL/XMAS Scan 탐지**:
```
# NULL Scan: 모든 Flag가 0
tcp.flags == 0x000

# FIN Scan: FIN만 설정
tcp.flags == 0x001

# XMAS Scan: FIN + PSH + URG
tcp.flags == 0x029
```
정상 트래픽에서 이 Flag 조합은 거의 나타나지 않는다. 하나라도 발견되면 스캔을 의심한다.

**UDP Scan 탐지**:
```
udp && icmp.type == 3 && icmp.code == 3
```
닫힌 UDP 포트는 ICMP Port Unreachable(Type 3, Code 3)을 반환한다.
대량의 ICMP Port Unreachable이 발생하면 UDP Scan이다.

**Banner Grabbing / 서비스 탐지**:
```
tcp.port == 22 || tcp.port == 80 || tcp.port == 3306
```
연결 후 서버가 보내는 첫 데이터에서 배너를 확인한다.
Wireshark에서 해당 TCP stream을 Follow > TCP Stream으로 보면 배너 전문을 볼 수 있다.

### 실전 분석 예시: SYN Scan 패킷

```
Wireshark 캡처 결과 (SYN Scan):

No.  Time     Source          Dest            Protocol  Info
1    0.000    192.168.1.100   192.168.1.50    TCP       49152→22  [SYN]
2    0.001    192.168.1.50    192.168.1.100   TCP       22→49152  [SYN,ACK]  ← 포트 열림
3    0.001    192.168.1.100   192.168.1.50    TCP       49152→22  [RST]      ← 연결 안 맺음
4    0.002    192.168.1.100   192.168.1.50    TCP       49153→23  [SYN]
5    0.003    192.168.1.50    192.168.1.100   TCP       23→49153  [RST,ACK]  ← 포트 닫힘
6    0.004    192.168.1.100   192.168.1.50    TCP       49154→80  [SYN]
7    0.005    192.168.1.50    192.168.1.100   TCP       80→49154  [SYN,ACK]  ← 포트 열림
8    0.005    192.168.1.100   192.168.1.50    TCP       49154→80  [RST]

특징:
- 순차적 포트 증가 (22→23→80...)
- SYN+ACK 후 ACK가 아닌 RST 전송 (Half-open)
- 짧은 시간 간격 (밀리초 단위)
```

### OS Fingerprinting Wireshark 분석

```
# TTL 확인
ip.ttl

# 응답 패킷의 TTL 값 확인
# Packet Details > Internet Protocol > Time to Live

# TCP Window Size 확인
# Packet Details > TCP > Window Size

예시:
SYN+ACK 패킷에서:
  TTL: 64           → Linux 추정
  Window Size: 29200 → Linux 4.x 이상 추정
  TCP Options: MSS, SACK, Timestamp → Linux 확인
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
