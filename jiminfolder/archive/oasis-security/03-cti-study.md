# 03. CTI 기술 학습 가이드

> 작성일: 2026-03-03
> 목적: 면접에서 "CTI를 모르지만 공부해왔다"를 증명할 수준까지 학습
> 수준 목표: 전문가가 아닌 "인프라 엔지니어가 CTI를 이해하고 있다" 수준
> 원칙: DevOps 엔지니어 관점에서 CTI 인프라에 기여할 수 있는 것에 집중

---

## 1. CTI (Cyber Threat Intelligence) 란?

### 정의
```
사이버 위협에 대한 정보를 수집 → 분석 → 공유하여
조직이 위협에 사전 대응할 수 있게 하는 분야
```

### CTI의 3가지 수준

| 수준 | 대상 | 예시 | 소비자 |
|------|------|------|--------|
| **전략적 (Strategic)** | 위협 동향, 공격자 동기 | "북한 해킹그룹이 금융권 공격 증가" | 경영진, CISO |
| **전술적 (Tactical)** | 공격 기법, TTP | "킴수키는 스피어피싱으로 초기 진입" | 보안팀 리더 |
| **운영적 (Operational)** | IOC, C2 주소, 악성 해시 | "IP 1.2.3.4는 C2 서버" | SOC 분석가, SIEM |

### 오아시스의 위치

```
기존 CTI 회사: 주로 운영적/전술적 수준
  → 공격 발생 후 IOC 수집 → "이 IP가 악성", "이 해시가 악성코드"

오아시스 (AGATHA): 전략적 + 전술적 수준
  → 공격 발생 전에 인프라 포착 → "이 서버가 공격 준비 중"
  → PRE-ATT&CK 단계에서 선제적 정보 제공
```

---

## 2. MITRE ATT&CK 프레임워크

### 전체 구조

```
공격 생명주기 (Cyber Kill Chain과 유사하지만 더 상세):

[공격 준비 — PRE-ATT&CK]
  ① Reconnaissance (정찰)
     - 대상 조직의 IP 범위, 직원 정보, 사용 기술 조사
     - 예: LinkedIn에서 보안 담당자 이름, 사용 중인 보안 도구 파악

  ② Resource Development (자원 개발) ← AGATHA가 탐지하는 단계
     - C2 서버 구매/설정
     - 악성 도메인 등록
     - 공격 도구 테스트
     - 피싱 인프라 구축

[공격 실행]
  ③ Initial Access (초기 진입) — 피싱, 취약점 익스플로잇
  ④ Execution (실행) — 악성코드 실행
  ⑤ Persistence (지속성) — 백도어 설치
  ⑥ Privilege Escalation (권한 상승)
  ⑦ Defense Evasion (방어 회피)
  ⑧ Credential Access (자격증명 탈취)
  ⑨ Discovery (내부 탐색)
  ⑩ Lateral Movement (횡적 이동)
  ⑪ Collection (데이터 수집)
  ⑫ Command and Control (C2 통신) ← Falco/Wazuh가 탐지하는 단계
  ⑬ Exfiltration (데이터 유출)
  ⑭ Impact (피해)
```

### 내 경험과의 연결

```
내가 이미 경험한 것:
  - ③ Initial Access: SSH Brute Force 238K건 탐지 (Wazuh)
  - ④ Execution: Falco — Java 프로세스에서 /bin/bash 실행 탐지
  - ⑫ C2 통신: Falco — 비정상 외부 통신 탐지 가능
  - Grafana MITRE ATT&CK Heatmap 대시보드 운영 중

오아시스에서 배울 수 있는 것:
  - ①② PRE-ATT&CK 단계의 인텔리전스
  - C2 인프라 프로파일링 기술
  - 대규모 스캐닝 + 데이터 분석 파이프라인
```

---

## 3. C2 (Command & Control) 서버 이해

### C2란?
```
공격자가 감염된 시스템을 원격 제어하기 위한 서버

감염된 PC/서버 → C2 서버로 주기적 통신 (Beacon)
  - 명령 수신 (파일 다운로드, 정보 수집, 횡적 이동)
  - 탈취 데이터 전송
  - 상태 보고

비유: 군대의 "지휘통제소" = C2 서버
     전투원(agent) = 감염된 시스템
```

### 대표적 C2 프레임워크

| 도구 | 특징 | 사용자 |
|------|------|--------|
| **Cobalt Strike** | 상용, 가장 많이 사용/탐지됨 | APT 그룹, 레드팀 |
| **Metasploit** | 오픈소스, 입문용 | 교육, 기초 침투 테스트 |
| **Sliver** | 오픈소스, CS 대체 | 레드팀, APT |
| **Mythic** | 모듈형, 확장성 | 고급 레드팀 |
| **Brute Ratel** | 탐지 회피에 특화 | 고급 공격자 |

### AGATHA의 C2 탐지 원리 (추론)

```
C2 서버는 "설정 단계"에서 특유의 fingerprint를 남김:

1. SSL/TLS 인증서 패턴
   - Cobalt Strike: 기본 인증서에 특유의 Subject/Issuer 패턴
   - 자체 서명 인증서의 시리얼 넘버, 유효기간 패턴
   - JA3/JA3S TLS fingerprint (Client Hello/Server Hello 패턴)

2. HTTP(S) 응답 특성
   - 기본 설정의 응답 헤더 패턴
   - 404 페이지의 content-length, body 패턴
   - Server 헤더 유무/값

3. JARM Hash
   - 서버의 TLS 응답을 10가지 다른 요청으로 probe
   - 응답 패턴을 해시화 → C2 도구별 고유 JARM
   - 예: Cobalt Strike의 JARM = 특정 값

4. 도메인 패턴
   - DGA (Domain Generation Algorithm): 자동 생성된 도메인 패턴
   - 최근 등록 도메인 (newly registered domains)
   - 유사 도메인 (typosquatting): apple.com → appie.com

5. 네트워크 행동 패턴
   - Beacon 주기 (주기적 HTTP/HTTPS 통신)
   - Sleep 패턴 (jitter 포함)
   - DNS 쿼리 패턴

AGATHA는 이런 fingerprint들을 대규모로 스캔하여
"아직 공격에 사용되지 않았지만 C2로 설정된 서버"를 사전에 포착
```

---

## 4. 다크웹 인텔리전스 (ARTHUR)

### 다크웹 기본 구조

```
Surface Web (표면 웹):
  - Google에서 검색 가능
  - 일반 웹사이트
  - 전체 웹의 ~4%

Deep Web (딥 웹):
  - 검색 엔진에 인덱싱되지 않음
  - 로그인 필요한 페이지, DB 쿼리 결과 등
  - 전체 웹의 ~90%

Dark Web (다크웹):
  - Tor, I2P 등 익명 네트워크에서만 접근 가능
  - .onion 주소 사용
  - 합법적 용도(익명 통신, 검열 우회)도 있지만
  - 불법 마켓플레이스, 랜섬웨어 유출 사이트, 해킹 포럼 등 악용
```

### Tor 히든 서비스 원리

```
일반 웹: Client → DNS → IP → Server (IP 노출됨)

Tor 히든 서비스:
  Client → Tor 네트워크 → Rendezvous Point ← Tor 네트워크 ← Server

  - 서버의 실제 IP가 노출되지 않음 (3중 중계)
  - .onion 주소는 공개키에서 파생 (DNS 불필요)
  - 클라이언트와 서버 모두 익명성 보장

왜 IP가 중요한가:
  - .onion 주소만으로는 서버의 물리적 위치를 알 수 없음
  - IP를 알면 → 호스팅 업체 확인 → 법적 조치 가능
  - 다른 범죄 인프라와의 연관성 분석 가능
```

### ARTHUR의 IP 식별 방법 (추론)

```
다크웹 서버가 IP를 노출하는 경우:

1. 설정 실수 (Misconfiguration)
   - Tor 히든 서비스이면서 동시에 일반 인터넷에서도 접근 가능
   - 방화벽 미설정으로 직접 접근 허용
   - SSL 인증서에 실제 도메인/IP 포함

2. 상관관계 분석 (Correlation)
   - 히든 서비스와 일반 서비스가 같은 서버에서 동작
   - 동일한 SSL 인증서, SSH 키, HTTP 응답 패턴
   - 업타임 상관관계 (동시에 다운/업)

3. 네트워크 서비스 스캔
   - 전 인터넷 스캔 (Shodan, Censys 유사)
   - SSH fingerprint, SMTP banner, HTTP 응답으로 .onion 서버 매칭

4. 트래픽 분석 (고급)
   - Tor 트래픽 패턴 분석으로 출구 노드 추적
   - 타이밍 분석 (트래픽 볼륨 상관관계)
```

---

## 5. IOC (Indicator of Compromise) 이해

### 정의
```
"이 시스템이 침해되었음을 나타내는 증거"

IOC 종류:
  - IP 주소: "1.2.3.4는 C2 서버"
  - 도메인: "evil-example.com은 피싱 사이트"
  - 파일 해시: "SHA256:abc...는 악성코드"
  - URL: "https://evil.com/payload.exe"
  - 이메일 주소: "attacker@evil.com이 피싱 발송"
  - YARA 규칙: 파일 내 특정 패턴 매칭
```

### IOC와 CTI의 관계

```
전통적 CTI 워크플로우:
  1. 공격 발생
  2. 침해 분석 (Forensics)
  3. IOC 추출 (IP, 해시, 도메인)
  4. IOC 공유 (STIX/TAXII 형식)
  5. 다른 조직에서 IOC로 방어 (방화벽, IDS 규칙 업데이트)

AGATHA의 차별점:
  1. 공격 준비 단계에서 인프라 fingerprint 수집
  2. C2 서버 IP, 피싱 도메인을 "공격 전에" IOC로 제공
  3. 기존 IOC가 "사후 방어"라면, AGATHA IOC는 "사전 방어"
```

### 표준 포맷

| 포맷 | 용도 | 설명 |
|------|------|------|
| **STIX** | IOC 표현 | 위협 정보를 구조화된 JSON으로 표현 |
| **TAXII** | IOC 전송 | STIX 데이터를 공유하기 위한 프로토콜 |
| **MISP** | IOC 플랫폼 | 오픈소스 위협 인텔리전스 공유 플랫폼 |

---

## 6. DevOps 엔지니어가 CTI 인프라에 기여하는 것

### 6-1. 대량 스캔 인프라

```
AGATHA/ARTHUR는 전 인터넷을 스캔해야 함:
  - IPv4: 약 40억 개 IP
  - 포트: 주요 포트 수십~수백 개
  - 주기: 매일 또는 실시간

DevOps가 해야 할 것:
  - K8s 기반 스캔 워커 오케스트레이션
  - 스캔 작업의 병렬 처리 + 리소스 관리
  - 스캔 결과 데이터 파이프라인 (수집 → 정제 → 저장 → 인덱싱)
  - 장애 시 자동 복구 (Pod restart, 재시도 로직)
```

### 6-2. 데이터 저장/검색

```
CTI 데이터 특성:
  - 대량 (수십~수백 TB)
  - 비정형 (스캔 결과, 인증서 정보, HTTP 응답)
  - 빠른 검색 필요 (고객이 특정 IP/도메인 조회)

DevOps가 해야 할 것:
  - Elasticsearch/OpenSearch 클러스터 운영
  - 인덱스 전략 (시계열, 파티셔닝)
  - 백업/복구 전략
  - 성능 튜닝 (쿼리 최적화, 캐싱)
```

### 6-3. SaaS 서비스 안정성

```
AGATHA/ARTHUR는 SaaS로 고객에게 제공:
  - 웹 대시보드
  - API 엔드포인트
  - 실시간 알림

DevOps가 해야 할 것:
  - 서비스 가용성 (SLO 설정, 모니터링)
  - 인프라 보안 (CTI 데이터 자체가 민감 정보)
  - CI/CD 파이프라인 (빠른 배포 + 보안 게이트)
  - 인프라 비용 최적화
```

### 6-4. 내 경험과의 매핑

```
홈랩 경험 → 오아시스에서의 적용:

K8s 클러스터 운영
  → 온프레미스 CTI 서비스 클러스터 구축/운영

ArgoCD GitOps
  → GitLab CI/CD + ArgoCD로 배포 자동화

PLG Stack 모니터링
  → CTI 서비스 모니터링 + SLO 체계

DevSecOps 파이프라인
  → CTI 서비스 빌드/배포 보안

Falco + Wazuh
  → CTI 인프라 자체의 런타임 보안

Elasticsearch (ELKF + Wazuh)
  → CTI 데이터 저장/검색 엔진 운영
```

---

## 7. 면접에서 CTI 관련 말할 수 있는 것들

### "내가 이미 하고 있는 CTI 관련 활동"

```
1. MITRE ATT&CK 매핑
   - Grafana에 MITRE ATT&CK Heatmap 대시보드 운영 중
   - Falco 이벤트를 MITRE 기법에 매핑

2. 실제 위협 탐지
   - SSH Brute Force 238,903건 (7개 외부 IP)
   - 웹 스캐너 17건
   - Falco 5개 시나리오 396건

3. 보안 이벤트 상관 분석
   - Wazuh SIEM에서 다중 소스 이벤트 통합
   - Falco + Audit Log + Wazuh Agent 로그 상관
```

### "오아시스에서 배우고 싶은 것"

```
1. PRE-ATT&CK 단계의 인텔리전스 수집/분석 방법론
2. C2 서버 fingerprinting 기술의 실제 구현
3. 대규모 인터넷 스캐닝 아키텍처
4. CTI 데이터 파이프라인 설계
5. 다크웹 히든 서비스 IP 식별 기술
```

---

## 8. 추가 학습 리소스 (면접 전)

### 필수 (30분 투자)
- [ ] MITRE ATT&CK 웹사이트에서 Reconnaissance + Resource Development 택틱 훑어보기
  - https://attack.mitre.org/tactics/TA0043/ (Reconnaissance)
  - https://attack.mitre.org/tactics/TA0042/ (Resource Development)
- [ ] AGATHA 공식 페이지 한 번 더 읽기 (https://oasis-security.io/)

### 권장 (1시간 투자)
- [ ] Cobalt Strike 기본 개념 + JARM hash란 무엇인가 검색
- [ ] 데일리시큐 오아시스 관련 기사 3건 읽기 (안다리엘, 킴수키, ARTHUR 출시)
- [ ] Shodan/Censys가 뭔지 간단히 파악 (인터넷 스캐닝 원리)

### 심화 (여유 있을 때)
- [ ] STIX/TAXII 포맷 기본 구조 파악
- [ ] Tor 히든 서비스 동작 원리 상세
- [ ] 북한 해킹그룹 (킴수키, 라자루스, 안다리엘) 최근 동향

---

## 변경 이력
- 2026-03-03 v1.0.0: 초안 작성 (CTI 기본 → MITRE ATT&CK → C2 → 다크웹 → DevOps 연결)
