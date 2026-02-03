---
title: "네트워크 보안 기초: tcpdump로 패킷 분석하기"
date: 2026-01-29T00:00:00+09:00
draft: false
description: "UTM 경험을 클라우드/K8s 보안으로 확장하기 - tcpdump 실습"
tags: ["tcpdump", "packet-analysis", "network-security", "wireshark", "security", "troubleshooting"]
categories: ["study", "Security", "Troubleshooting"]
---

## 학습 목표

> **UTM 경험을 현대 클라우드/K8s 보안으로 확장하기**

- tcpdump로 실시간 패킷 캡처 및 분석
- OSI 7 Layer별 공격 패턴 이해
- Kubernetes 환경에서의 패킷 분석
- 실제 보안 이슈 트러블슈팅 경험

---

## 배경지식

### UTM vs Cloud Security

**UTM (Unified Threat Management)**:
- 경계 보안 (Perimeter Security)
- 네트워크 레벨 방어
- L3-L7 트래픽 제어

**Cloud/K8s Security**:
- 다층 방어 (Defense in Depth)
- Security Group (L3-L4) + WAF (L7)
- Network Policy (Pod 간 통신 제어)

### 왜 tcpdump를 배워야 하는가?

**현실**:
- CloudWatch나 Prometheus는 애플리케이션 레벨 메트릭만 보여줌
- 네트워크 레벨 문제는 패킷을 직접 봐야 함
- 공격 탐지 시 패킷 분석이 필수

**예시**:
```
증상: Pod 간 통신 실패
CloudWatch: "Connection Timeout" (도움 안 됨)
tcpdump: "SYN → RST" (방화벽이 막고 있음을 확인!)
```

---

## 실습 1: 기본 tcpdump 사용법

### 1-1. 네트워크 인터페이스 확인

**명령어**:
```bash
ip addr show
```

**결과 예시**:
```
1: lo: <LOOPBACK,UP,LOWER_UP> mtu 65536
2: ens33: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500
3: docker0: <NO-CARRIER,BROADCAST,MULTICAST,UP> mtu 1500
```

**해석**:
- `lo`: 로컬 루프백 (127.0.0.1)
- `ens33`: 실제 네트워크 카드
- `docker0`: Docker 브리지 네트워크

### 1-2. 첫 번째 패킷 캡처 (HTTP 요청)

**명령어**:
```bash
sudo tcpdump -i ens33 -c 10 port 80
```

**설명**:
- `-i ens33`: ens33 인터페이스 감시
- `-c 10`: 10개 패킷만 캡처
- `port 80`: HTTP 트래픽만 필터링

**실행 후 다른 터미널에서**:
```bash
curl http://example.com
```

**결과 예시**:
```
12:34:56.123456 IP 192.168.1.100.54321 > 93.184.216.34.80: Flags [S], seq 123456, win 64240
12:34:56.234567 IP 93.184.216.34.80 > 192.168.1.100.54321: Flags [S.], seq 789012, ack 123457
```

**해석**:
1. **Flags [S]**: SYN 패킷 (연결 요청)
2. **Flags [S.]**: SYN-ACK (서버가 승인)
3. **3-Way Handshake 성공!**

---

## 실습 2: Kubernetes Pod 패킷 캡처

### 2-1. Pod 내부에서 tcpdump 실행

**시나리오**: WAS → MySQL 연결 문제 트러블슈팅

**1단계: 실행 중인 Pod 확인**:
```bash
kubectl get pods -n blog-system
```

**2단계: Pod에 진입**:
```bash
kubectl exec -it was-xxxxx -n blog-system -- /bin/sh
```

**3단계: tcpdump 설치 (Alpine 기준)**:
```bash
apk add tcpdump
```

**4단계: MySQL 통신 캡처**:
```bash
tcpdump -i eth0 -n port 3306 -A
```

**설명**:
- `-n`: DNS 조회 안 함 (빠름)
- `port 3306`: MySQL 포트
- `-A`: ASCII로 출력 (SQL 쿼리 볼 수 있음)

### 2-2. 실제 공격 패턴 탐지

**DDoS 공격 (SYN Flood) 탐지**:
```bash
sudo tcpdump -i ens33 'tcp[tcpflags] & (tcp-syn) != 0' -c 100
```

**의심스러운 패턴**:
```
12:00:01 IP 1.2.3.4 > my-server: Flags [S]
12:00:01 IP 1.2.3.4 > my-server: Flags [S]
12:00:01 IP 1.2.3.4 > my-server: Flags [S]
... (100회 반복)
```

**판단**:
- 같은 IP에서 SYN만 계속 보냄 (ACK 안 옴)
- **SYN Flood 공격 가능성 높음**

---

## 실습 3: 공격 시나리오 재현

### 3-1. SQL Injection 로그 확인

**취약한 SQL 쿼리 (의도적)**:
```sql
SELECT * FROM users WHERE id = '1' OR '1'='1'
```

**tcpdump로 캡처**:
```bash
sudo tcpdump -i lo -A port 3306 | grep "SELECT"
```

**공격 탐지**:
```
SELECT * FROM users WHERE id = '1' OR '1'='1'
                                    ^^^^^^^ 의심!
```

### 3-2. XSS 공격 탐지 (HTTP 레벨)

**tcpdump로 HTTP Body 확인**:
```bash
sudo tcpdump -i ens33 -A port 80 | grep "<script>"
```

**공격 패턴**:
```
GET /search?q=<script>alert('XSS')</script>
                     ^^^^^^^^^^^^^^^^^^^^^^ 공격!
```

---

## 실습 4: Wireshark로 패킷 분석

### 4-1. tcpdump로 pcap 파일 생성

**명령어**:
```bash
sudo tcpdump -i ens33 -w capture.pcap -c 1000
```

**설명**:
- `-w capture.pcap`: 파일로 저장
- `-c 1000`: 1000개 패킷

### 4-2. Wireshark로 열기

**로컬로 파일 복사**:
```bash
scp user@server:/tmp/capture.pcap ./
```

**Wireshark 필터 예시**:
```
tcp.flags.syn == 1 and tcp.flags.ack == 0  # SYN 패킷만
http.request.method == "POST"              # POST 요청만
ip.src == 192.168.1.100                    # 특정 IP만
```

---

## 실전 트러블슈팅 사례

### 사례 1: Pod 간 통신 실패

**증상**:
```
WAS → MySQL: Connection Timeout
```

**tcpdump로 확인**:
```bash
kubectl exec -it was-xxxxx -- tcpdump -i eth0 host mysql-service -n
```

**결과**:
```
12:00:01 IP was.12345 > mysql.3306: Flags [S], seq 123456
12:00:01 IP mysql.3306 > was.12345: Flags [R], seq 0
                                           ^^^ RST!
```

**분석**:
- **RST (Reset)**: MySQL이 연결 거부
- **원인**: Cilium NetworkPolicy가 막고 있음
- **해결**: NetworkPolicy에 WAS → MySQL 허용 규칙 추가

### 사례 2: Istio mTLS 통신 확인

**명령어**:
```bash
kubectl exec -it was-xxxxx -c istio-proxy -- \
  tcpdump -i eth0 -A port 15001
```

**결과 (암호화)**:
```
...E..H.@.@.............9.........
  ^^^^^^^^^^ 읽을 수 없음 (TLS)
```

**판단**: mTLS가 정상 작동 중

---

## 학습 정리

### 배운 것

1. **tcpdump 기본 사용법**
   - 인터페이스 선택 (`-i`)
   - 필터링 (port, host)
   - 출력 형식 (`-A`, `-X`)

2. **TCP 3-Way Handshake 분석**
   - SYN → SYN-ACK → ACK
   - RST 패킷의 의미 (연결 거부)

3. **공격 패턴 탐지**
   - SYN Flood: SYN만 계속 보내기
   - SQL Injection: 쿼리에 `OR '1'='1'`
   - XSS: HTML에 `<script>` 삽입

### 다음 학습

- **Cilium Hubble**: Kubernetes 네트워크 관찰성
- **Falco**: 런타임 보안 (이상 행위 탐지)
- **WAF 로그 분석**: ALB + AWS WAF

---

## 🔗 참고 자료

- tcpdump 공식 문서: https://www.tcpdump.org/
- Wireshark 필터 가이드: https://wiki.wireshark.org/DisplayFilters
- OWASP Top 10: https://owasp.org/www-project-top-ten/

---

**작성일**: 2026-01-29
**학습 시간**: 2시간 (실습 포함)
**다음 학습**: Cilium Hubble UI 실습
