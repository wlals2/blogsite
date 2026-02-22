---
title: "공격자의 시선으로 보는 보안: 2편 - 네트워크 레이어 공격"
date: 2026-02-22T14:00:00+09:00
categories:
  - study
  - Security
tags: ["security", "arp-poisoning", "ip-spoofing", "dns-poisoning", "syn-flood", "network", "legacy-attacks"]
summary: "정찰이 끝나면 실제 공격이 시작된다. L2/L3/L4 레이어에서 작동하는 고전적인 네트워크 공격 - ARP Poisoning, IP Spoofing, DNS Poisoning, SYN Flood의 원리와 방어 방법을 분석한다."
showtoc: true
tocopen: true
draft: false
series: ["공격자의 시선으로 보는 보안"]
series_order: 2
---

## 배경

1편에서 공격자가 어떻게 대상 환경을 파악하는지 살펴봤다.
이번 편은 그 정보를 바탕으로 실행하는 **네트워크 레이어 공격**을 다룬다.

이 공격들의 공통점은 **프로토콜 설계 단계의 취약점**을 이용한다는 것이다.
TCP/IP가 설계된 1970~80년대에는 "신뢰할 수 있는 환경"을 가정했다.
ARP, IP, DNS, TCP 모두 인증 없이 동작하도록 설계되었고, 이 가정이 공격의 출발점이 된다.

---

## 1. ARP Poisoning (ARP 스푸핑)

### ARP가 뭔가

ARP(Address Resolution Protocol)는 IP 주소 → MAC 주소를 변환하는 프로토콜이다.
같은 네트워크(L2 브로드캐스트 도메인)에서 패킷을 전달하려면 MAC 주소가 필요하다.

```
호스트 A (192.168.1.10)가 호스트 B (192.168.1.20)에게 데이터를 보내려면:

1. A: "192.168.1.20의 MAC 주소가 뭐야?" → 브로드캐스트 (ARP Request)
2. B: "나야! MAC: aa:bb:cc:dd:ee:ff" → A에게 응답 (ARP Reply)
3. A: ARP 캐시에 저장 (192.168.1.20 → aa:bb:cc:dd:ee:ff)
4. 이후 패킷은 MAC 주소로 직접 전달
```

ARP 캐시는 일정 시간 후 만료되며, 새 ARP Reply를 받으면 **무조건 덮어쓴다**.
이 "무조건 덮어쓰기"가 취약점이다.

### Gratuitous ARP (무조건 덮어쓰기 악용)

Gratuitous ARP는 Request 없이도 보내는 ARP Reply다.
원래 IP 충돌 감지, NIC 교체 후 캐시 갱신 등 정상 목적으로 사용된다.

**공격에 악용**:
```
공격자 (192.168.1.100, MAC: ee:ee:ee:ee:ee:ee):

→ "192.168.1.1(게이트웨이)의 MAC은 ee:ee:ee:ee:ee:ee 입니다"
   (브로드캐스트, ARP Reply)

→ 네트워크의 모든 호스트가 이 Reply를 받아 캐시를 업데이트
→ 이제 게이트웨이로 보내는 모든 패킷이 공격자에게 전달됨
```

### 공격 시나리오 (MITM)

```
[정상 통신]
피해자 (192.168.1.10) ← → 게이트웨이 (192.168.1.1)

[ARP Poisoning 후]
피해자 ARP 캐시:
  192.168.1.1 → ee:ee:ee:ee:ee:ee  ← 공격자 MAC으로 오염

게이트웨이 ARP 캐시:
  192.168.1.10 → ee:ee:ee:ee:ee:ee ← 공격자 MAC으로 오염

결과:
피해자 → 공격자 → 게이트웨이  (MITM: Man-in-the-Middle)
공격자는 패킷을 가로채거나 변조 후 포워딩
```

```bash
# 실제 ARP 캐시 확인
arp -n
# 출력:
# Address        HWtype  HWaddress           Flags
# 192.168.1.1    ether   aa:bb:cc:dd:ee:ff   C
# 192.168.1.100  ether   ee:ee:ee:ee:ee:ee   C  ← 공격자

# 공격 도구 (학습/방어 이해 목적)
# arpspoof -i eth0 -t 192.168.1.10 192.168.1.1
```

### 탐지 방법

```bash
# ARP 캐시에서 동일 MAC이 여러 IP에 매핑되면 의심
arp -n | awk '{print $3}' | sort | uniq -d
# 중복 MAC이 나오면 ARP Poisoning 의심

# Wireshark 필터
# arp.duplicate-address-detected
# arp.opcode == 2 && arp.src.hw_mac == <의심 MAC>
```

### 방어 방법

**1. Dynamic ARP Inspection (DAI)** - 스위치 레벨:
- DHCP Snooping 테이블과 ARP 패킷을 비교
- 신뢰할 수 없는 포트에서 오는 Gratuitous ARP 차단

**2. 정적 ARP 항목 (Static ARP)**:
```bash
# 게이트웨이 MAC을 고정 (변경 불가)
arp -s 192.168.1.1 aa:bb:cc:dd:ee:ff
```

**3. XDR/네트워크 모니터링**:
- ARP 캐시 변화 주기적 감지
- 동일 IP에 대해 MAC이 바뀌면 알람

---

## 2. IP Spoofing

### 원리

IP 헤더의 Source IP를 위조하는 기법이다.
TCP/IP에는 Source IP를 검증하는 메커니즘이 없다.

```
정상 패킷:
[IP Header]
  Source IP: 192.168.1.10 (실제 출발지)
  Dest IP: 10.0.0.1

스푸핑된 패킷:
[IP Header]
  Source IP: 1.2.3.4 (위조된 출발지)
  Dest IP: 10.0.0.1
```

### Blind Spoofing vs Non-blind Spoofing

| 구분 | 특징 | 가능한 공격 |
|------|------|------------|
| **Blind Spoofing** | 응답을 받을 수 없음 (응답이 위조된 IP로 감) | DoS, DDoS 증폭 |
| **Non-blind Spoofing** | 같은 서브넷 내에서 응답을 가로챌 수 있음 | MITM, 세션 가로채기 |

### DDoS 증폭 공격 (가장 많이 사용되는 시나리오)

반사(Reflection) + 증폭(Amplification)을 결합한다.

```
1. 공격자: 피해자 IP를 Source IP로 위조
2. 공격자: 다수의 공개 서버에 요청 전송
3. 공개 서버들: 피해자에게 응답 전송 (요청보다 큰 응답)
4. 결과: 피해자는 대량의 트래픽을 받음
```

증폭 계수 예시:

| 프로토콜 | 요청 크기 | 응답 크기 | 증폭 배수 |
|---------|---------|---------|----------|
| DNS | 40 bytes | ~3,000 bytes | 75x |
| NTP monlist | 8 bytes | ~48,000 bytes | 6,000x |
| SSDP | 30 bytes | ~3,000 bytes | 100x |
| Memcached | 15 bytes | ~750,000 bytes | 50,000x |

```bash
# NTP 증폭 공격 원리 (monlist 명령)
# monlist: 최근 600개 클라이언트 목록 반환 → 대용량 응답
ntpdc -n -c monlist <NTP 서버>
# 출력: 최근 연결한 클라이언트 목록 (600개까지)
```

### 탐지/방어

**BCP38 (Ingress Filtering)** - ISP 레벨:
```
인터넷 → ISP 라우터 → 내부 네트워크

ISP 라우터에서 자신의 IP 범위가 아닌 Source IP를 가진 패킷 차단
예: 192.168.0.0/16 구간에서 출발지 IP가 1.2.3.4이면 → DROP
```

**Reverse Path Forwarding (RPF)**:
```bash
# Linux에서 RPF 활성화
sysctl -w net.ipv4.conf.all.rp_filter=1
# 패킷이 들어온 인터페이스로 응답할 수 있는지 라우팅 테이블 검증
# 불가능한 경우 DROP
```

**Rate Limiting** (NTP, DNS 등 증폭에 사용되는 서비스):
```
# NTP: monlist 비활성화
# DNS: Response Rate Limiting (RRL) 활성화
```

---

## 3. DNS Poisoning (DNS Cache Poisoning)

### DNS 동작 원리 (재귀 질의)

```
브라우저 → 로컬 DNS 캐시 확인
          → 없으면 재귀 질의 시작

사용자 → [Recursive Resolver] → [Root NS] → [TLD NS] → [Authoritative NS]
                 ↑                                              ↓
          캐시에 저장 ← ← ← ← ← ← ← ← ← ← ← ← ← ← ← 최종 응답
```

Recursive Resolver는 응답을 캐시한다. TTL(Time to Live)이 만료될 때까지 캐시된 값을 사용한다.

### Cache Poisoning 원리

Recursive Resolver가 외부 NS에 질의할 때, **가짜 응답을 먼저 보내면** 캐시에 저장된다.

```
조건:
- 올바른 Transaction ID와 Source Port를 맞춰야 함
- 실제 응답이 오기 전에 가짜 응답이 도착해야 함

Transaction ID: 16bit (0~65535, 총 65,536가지)
→ 초기에는 순차 증가 → 쉽게 예측 가능
```

### Kaminsky Attack (2008년, 가장 심각한 DNS 취약점)

Dan Kaminsky가 2008년 발표한 공격으로, 모든 주요 DNS 구현에 영향을 미쳤다.

**기존 방식의 한계**:
- 가짜 응답이 틀리면 → TTL이 만료될 때까지 기다려야 함 (시간 낭비)

**Kaminsky의 혁신**:
```
공격자:
1. "aaaaa.target.com 알려줘" → Resolver에게 질의
   (존재하지 않는 서브도메인 → 매번 새로운 질의 발생)
2. 동시에 가짜 응답 대량 전송 (Transaction ID 브루트포스)
   "aaaaa.target.com 없음. 대신 target.com NS는 attacker.com"
3. 맞으면 → target.com 전체 캐시 오염
4. 틀리면 → "bbbbb.target.com 알려줘"로 재시도

결과: TTL 기다릴 필요 없이 반복 시도 가능
      1초 안에 캐시 오염 가능
```

```bash
# 현재 DNS 캐시 확인 (Linux)
systemd-resolve --statistics
# 또는
dig @127.0.0.1 target.com
```

### 탐지/방어

**DNSSEC (DNS Security Extensions)**:
```
Authoritative NS가 응답에 디지털 서명 추가
Resolver가 서명 검증 → 위조 응답은 서명 불일치 → 거부

단점: 설정 복잡, 모든 도메인이 DNSSEC 지원하지 않음
```

**Source Port 랜덤화** (Kaminsky 공격 대응):
```
Transaction ID: 16bit (65,536가지)
Source Port: 16bit (65,536가지)
→ 조합: 65,536 × 65,536 = 4,294,967,296가지

브루트포스가 현실적으로 불가능해짐
현대 DNS는 기본적으로 Source Port 랜덤화 적용
```

**Response Rate Limiting (RRL)**:
```
동일 IP에서 단시간에 대량 질의 → 응답 제한
DNS 증폭 공격 + 브루트포스 완화
```

---

## 4. SYN Flood

### TCP 3-way Handshake 원리

```
클라이언트             서버
    |                   |
    |--- SYN ---------->|  1. "연결할게"
    |<-- SYN+ACK -------|  2. "OK, 기다릴게" ← 서버가 Half-open 상태 유지
    |--- ACK ---------->|  3. "연결됨"
    |                   |
  (데이터 전송)
```

서버는 SYN을 받으면 **Half-open 상태**를 `backlog queue`에 저장한다.
ACK가 오거나 타임아웃이 될 때까지 대기한다.

### 공격 원리

```
공격자 (IP Spoofing 사용):
→ 위조된 Source IP로 SYN 대량 전송

서버:
→ 각 SYN에 대해 SYN+ACK 응답
→ backlog queue에 Half-open 상태 저장
→ ACK를 기다리지만 위조된 IP는 응답하지 않음

결과:
→ backlog queue가 가득 참 (기본값: 128~1024)
→ 새로운 정상 연결 요청 거부
→ 서비스 불가 (DoS)
```

```bash
# 서버의 backlog queue 크기 확인
sysctl net.ipv4.tcp_max_syn_backlog
# 출력: net.ipv4.tcp_max_syn_backlog = 1024

# SYN Flood 발생 시 확인
ss -s
# 출력:
# Total: 150
# TCP: 2,847 (orphaned 0), synrecv 2,845, ...
#               ↑ synrecv가 급격히 증가하면 SYN Flood 의심

netstat -an | grep SYN_RECV | wc -l
# SYN_RECV 상태 연결 수
```

### SYN Cookie (핵심 방어 기법)

SYN Cookie는 backlog queue를 사용하지 않고 상태를 인코딩하는 방식이다.

```
일반 방식:
SYN 수신 → backlog queue에 저장 → ACK 기다림 (메모리 사용)

SYN Cookie 방식:
SYN 수신 → SYN+ACK의 ISN(Initial Sequence Number)에 상태 인코딩
           → backlog queue에 저장 안 함
ACK 수신 → ISN에서 상태 복원 → 정상 연결 수립

인코딩 정보:
ISN = f(Source IP, Source Port, Dest IP, Dest Port, Secret Key, 타임스탬프)

결과: backlog queue 없이도 정상 연결 수립 가능
     SYN Flood로 queue를 고갈시킬 수 없음
```

```bash
# SYN Cookie 활성화 (Linux)
sysctl -w net.ipv4.tcp_syncookies=1

# 영구 적용 (/etc/sysctl.conf)
net.ipv4.tcp_syncookies = 1
net.ipv4.tcp_syn_retries = 2       # SYN 재전송 횟수 감소
net.ipv4.tcp_synack_retries = 2    # SYN+ACK 재전송 횟수 감소
net.ipv4.tcp_max_syn_backlog = 4096  # backlog 크기 증가
```

### iptables Rate Limiting (추가 방어)

```bash
# 초당 SYN 패킷 제한
iptables -A INPUT -p tcp --syn -m limit --limit 10/s --limit-burst 20 -j ACCEPT
iptables -A INPUT -p tcp --syn -j DROP
# 초당 10개 이상 SYN → DROP

# 특정 IP에서 SYN 과다 시 차단 (최근 60초 내 100개 이상)
iptables -A INPUT -p tcp --syn -m recent --name SYN_FLOOD --update --seconds 60 --hitcount 100 -j DROP
iptables -A INPUT -p tcp --syn -m recent --name SYN_FLOOD --set -j ACCEPT
```

---

## 5. 공격 비교 및 탐지 정리

| 공격 | OSI 레이어 | 프로토콜 취약점 | 주요 방어 |
|------|-----------|----------------|----------|
| ARP Poisoning | L2 | 인증 없는 ARP Reply | DAI, Static ARP |
| IP Spoofing | L3 | Source IP 미검증 | BCP38, RPF |
| DNS Poisoning | L7 (App) | Transaction ID 예측 | DNSSEC, Port 랜덤화 |
| SYN Flood | L4 | Half-open 상태 고갈 | SYN Cookie, Rate Limit |

### 공통 탐지 포인트

```bash
# 네트워크 비정상 탐지 (tcpdump)
# ARP 이상: 동일 IP에서 MAC 변경
tcpdump -n arp | grep -v "Request\|Reply"

# SYN Flood: SYN 패킷 급증
tcpdump -n 'tcp[tcpflags] & tcp-syn != 0' | wc -l

# DNS 이상: 비정상적으로 많은 실패 응답
tcpdump -n port 53 and udp

# IDS/IPS 도구
# Snort, Suricata: 네트워크 패킷 실시간 분석
# Zeek: 네트워크 트래픽 로그 분석
```

---

## 마무리

이 공격들은 모두 **2000년대 이전부터 알려진** 공격이지만 지금도 유효하다.
특히 내부망 환경에서는 ARP Poisoning이 여전히 위협적이다.

핵심은 "프로토콜 설계 단계의 신뢰 가정"이 공격의 출발점이라는 것이다.
ARP, IP, DNS, TCP 모두 상대방을 신뢰하도록 설계되었고,
현대 보안은 이 신뢰 가정을 하나씩 제거하는 방향으로 발전했다.
(DNSSEC, RPF, SYN Cookie, DAI 모두 이 흐름)

다음 편에서는 네트워크 레이어를 통과하고 나서 **웹 애플리케이션 레이어에서 발생하는 공격**을 다룬다.

---

*시리즈: 공격자의 시선으로 보는 보안*
- 1편: 정찰과 스캐닝
- **2편: 네트워크 레이어 공격** ← 현재 글
- 3편: 웹 공격 Part 1 - Injection (SQL, XSS, Command)
- 4편: 웹 공격 Part 2 - 인증/세션/접근 제어
- 5편: WAF 우회 기법
