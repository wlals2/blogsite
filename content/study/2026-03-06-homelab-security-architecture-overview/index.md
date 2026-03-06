---
title: "홈랩 Kubernetes 보안 아키텍처 — 6계층 Defense in Depth 설계와 구현"
date: 2026-03-06T19:00:00+09:00
categories:
  - Security
tags: ["kubernetes", "devsecops", "defense-in-depth", "security-architecture", "homelab"]
summary: "코드 커밋부터 런타임까지 6계층으로 나눠 설계한 홈랩 보안 아키텍처. 각 계층이 왜 필요하고, 하나가 뚫리면 다음 계층이 어떻게 받는지를 실제 공격 시나리오로 설명한다."
showtoc: true
tocopen: true
draft: false
---

## 이 글의 목적

블로그 서비스를 Kubernetes에서 운영하면서 보안 도구를 하나씩 추가해왔다. GitLeaks, Trivy, Cilium, Istio mTLS, Falco, Wazuh... 도구는 많은데, **"왜 이 도구가 이 위치에 있는가"**를 설명하는 글이 없었다.

각 도구의 구축기는 이미 작성했다. 하지만 개별 글만 읽으면 **하나의 정책 아래 움직이는 체계**라는 느낌이 안 든다. 이 글은 그 빠진 고리를 채운다.

**핵심 질문: "공격자가 한 계층을 뚫으면, 다음 계층은 어떻게 막는가?"**

---

## 1. 왜 6계층인가 — Defense in Depth

보안에서 가장 위험한 생각은 **"이 하나만 잘하면 안전하다"**다.

```
"방화벽이 있으니까 안전하다"
  → 방화벽 규칙에 빈틈이 있으면?

"이미지 스캔을 하니까 안전하다"
  → 스캔 시점에 없던 취약점이 다음 날 공개되면?

"mTLS로 암호화하니까 안전하다"
  → 이미 Pod 안에 침투한 공격자는?
```

어떤 단일 도구도 모든 공격을 막지 못한다. 그래서 **계층별로 역할을 나누고, 한 계층이 실패하면 다음 계층이 받는 구조**를 설계했다. 이것을 Defense in Depth(심층 방어)라고 부른다.

### 전체 파이프라인

```
Git Push → GitLeaks → Docker Build → Trivy → ArgoCD → Kyverno → Falco+Talon → Wazuh
           (Secret)                   (CVE)   (GitOps)  (Admit)   (IDS+IPS)     (SIEM)
```

이 파이프라인을 6계층으로 분류하면:

| 계층 | 시점 | 역할 | 도구 | 핵심 질문 |
|------|------|------|------|----------|
| **1. CI/CD 보안** | 빌드 전 | 코드와 이미지의 알려진 위험 차단 | GitLeaks, Trivy | "배포 전에 잡을 수 있는가?" |
| **2. Admission Control** | 배포 시 | 정책 위반 워크로드 거부 | Kyverno | "정책에 맞는 Pod만 들어오는가?" |
| **3. 네트워크 격리** | 런타임 | Pod 간 통신 최소화 | Cilium L3/L4, Istio L7 | "필요한 통신만 허용했는가?" |
| **4. 컨테이너 보안** | 런타임 | 컨테이너 내부 권한 제한 | SecurityContext, mTLS | "침투해도 할 수 있는 게 적은가?" |
| **5. 런타임 탐지/대응** | 런타임 | 이상 행위 탐지 + 자동 격리 | Falco, Talon | "침투를 실시간으로 알 수 있는가?" |
| **6. SIEM 통합** | 상시 | 이벤트 상관 분석 + 에스컬레이션 | Wazuh | "전체 그림을 볼 수 있는가?" |

---

## 2. 공격 시나리오로 보는 계층 간 연결

이론만으로는 "왜 6개나 필요한가"가 와닿지 않는다. 실제 공격 시나리오를 따라가보자.

### 시나리오: 개발자가 실수로 DB 비밀번호를 커밋

```
공격 경로:
  개발자 → git commit (DB_PASSWORD=secret) → git push

방어 순서:
  ① GitLeaks가 Git 히스토리에서 탐지 → CI 실패 → 배포 차단
     ✅ 여기서 끝 (1계층에서 차단)

만약 GitLeaks를 우회했다면?
  ② Trivy는 이미지 내부 CVE만 스캔 → 코드 내 비밀은 못 잡음 ❌
  ③ Kyverno는 Pod 스펙 정책만 검증 → 코드 내 비밀은 못 잡음 ❌
  → 비밀이 포함된 이미지가 배포됨

  ④ 하지만 Sealed Secrets를 쓰고 있으므로,
     코드에 비밀이 있어도 실제 접속은 SealedSecret으로만 가능
  ⑤ 공격자가 Git 히스토리에서 비밀을 발견해 접근 시도하면
     → Cilium NetworkPolicy가 외부에서 MySQL 직접 접근 차단
```

### 시나리오: Spring Boot RCE (Log4Shell 같은 취약점)

```
공격 경로:
  공격자 → 악성 요청 → WAS(Spring Boot) → Shell 실행 → C&C 서버 연결

방어 순서:
  ① Trivy 야간 스캔 → CVE 발견 시 Discord 알림 (하지만 0-day는 못 잡음)
  ② Kyverno → 배포 시점 검증이라 런타임 공격과 무관
  ③ Cilium → WAS Pod는 MySQL(3306)과 DNS(53)만 Egress 허용
     → C&C 서버(4444번 포트) 연결 시도 → 네트워크 레벨에서 차단
     ✅ 3계층에서 차단

만약 공격자가 443(HTTPS)으로 C&C 통신을 시도한다면?
  → Cilium은 443을 허용하고 있으므로 통과 ❌
  ④ SecurityContext: runAsNonRoot + DROP ALL capabilities
     → root 권한 획득 불가, 시스템 명령 제한
  ⑤ Falco Rule 1 (Java Process Spawning Shell)
     → java가 sh를 실행하는 순간 CRITICAL 탐지
     → Talon이 해당 Pod에 NetworkPolicy 적용 → 격리
     ✅ 5계층에서 탐지 + 자동 대응

  ⑥ Wazuh가 Falco 이벤트를 수집
     → 5분 내 3회 이상 반복 시 Level 12로 에스컬레이션
     → Discord 알림 → 관리자 확인
```

### 시나리오: 내부자가 kubectl로 직접 악성 이미지 배포

```
공격 경로:
  내부자 → kubectl set image deployment/was was=악성이미지:latest

방어 순서:
  ① CI/CD 파이프라인 우회 → GitLeaks, Trivy 무력화 ❌
  ② Kyverno ClusterPolicy:
     → disallow-latest-tag: latest 태그 거부 → 배포 차단
     → require-run-as-non-root: root 컨테이너 거부
     → require-resource-limits: limits 없으면 거부
     ✅ 2계층에서 차단

만약 태그를 바꿔서 우회한다면? (was=악성이미지:v1.0)
  ③ ArgoCD selfHeal이 즉시 Git 상태로 되돌림
     → kubectl 직접 수정은 3초 내 롤백
     ✅ GitOps에서 차단
```

이렇게 **어느 한 계층이 실패해도 다음 계층이 받는다.** 이것이 6계층이 필요한 이유다.

---

## 3. 각 계층 상세

### 3.1 CI/CD 보안 — Shift Left

> **원칙**: 문제를 최대한 왼쪽(개발 단계)에서 잡는다. 런타임에서 발견하면 이미 늦다.

```
Git Push → GitLeaks(Secret 탐지) → Docker Build → Trivy(CVE 스캔) → 배포
```

| 도구 | 탐지 대상 | 실패 시 | 커버 범위 |
|------|----------|---------|----------|
| **GitLeaks** | Git 히스토리 전체의 비밀(API Key, Password) | CI 실패 → 배포 차단 | WAS + WEB |
| **Trivy** | Docker 이미지 내 OS/라이브러리 CVE | 경고만 (Discord 알림) | WAS(야간) + WEB(Push 시) |

**설계 결정**: GitLeaks는 차단, Trivy는 경고만 하는 이유
- 비밀 노출은 즉시 해킹으로 이어질 수 있다 → 무조건 차단
- CVE는 Base Image 문제일 수 있어 직접 패치 불가 → 경고 후 판단

> 상세 구현: [CI/CD 보안 파이프라인 구축기](/study/2026-02-25-devsecops-ci-security-gitleaks-trivy/)

### 3.2 Admission Control — 정책 게이트

> **원칙**: CI/CD를 우회하더라도, 클러스터에 들어오는 모든 워크로드는 정책을 통과해야 한다.

Kyverno가 kube-apiserver의 Admission Webhook으로 동작한다. Pod 생성/수정 요청이 들어올 때 정책을 검증하고, 위반 시 거부한다.

| 정책 | 검증 내용 | 위반 시 |
|------|----------|---------|
| `disallow-latest-tag` | 이미지 태그에 `:latest` 사용 금지 | Pod 생성 거부 |
| `require-run-as-non-root` | `runAsNonRoot: true` 필수 | Pod 생성 거부 |
| `require-resource-limits` | CPU/Memory limits 필수 | Pod 생성 거부 |
| `disallow-privileged-containers` | `privileged: true` 금지 | Pod 생성 거부 |

**CI/CD와의 관계**: CI/CD 보안이 "빌드 파이프라인을 지나는 코드"를 검사한다면, Kyverno는 "클러스터에 들어오는 모든 요청"을 검사한다. kubectl로 직접 배포해도 Kyverno는 동작한다.

### 3.3 네트워크 격리 — 최소 권한 통신

> **원칙**: "모든 Pod이 모든 Pod과 통신 가능"은 위험하다. 필요한 경로만 열어야 한다.

두 도구가 역할을 분담한다:

| 계층 | 도구 | 역할 | 예시 |
|------|------|------|------|
| **L3/L4** | Cilium NetworkPolicy | IP/포트 기반 격리 | WAS → MySQL:3306만 허용 |
| **L7** | Istio AuthorizationPolicy | HTTP 경로/메서드 기반 제어 | `/api/*`만 허용, 나머지 거부 |

**왜 두 개를 쓰는가?** Cilium만으로는 "WAS의 /admin 경로만 차단"이 불가능하다. Istio만으로는 "MySQL 포트 접근 자체를 차단"하는 L3/L4 격리가 부족하다. 각각이 잘하는 영역이 다르다 (ADR-001).

```
현재 Cilium 정책 (5개):

WEB → :80 전체 허용 (Ingress), WAS:8080 + DNS만 (Egress)
WAS → WEB:8080 + Istio sidecar만 (Ingress), MySQL:3306 + DNS만 (Egress)
MySQL → WAS:3306 + backup:3306만 (Ingress), DNS만 (Egress)
```

이 구조의 효과: WAS가 침투당해도 **MySQL과 DNS 외에는 아무 곳에도 연결할 수 없다.**

**Istio mTLS**: Pod 간 모든 통신이 자동으로 TLS 암호화된다. 네트워크를 스니핑해도 평문이 보이지 않는다. 현재 PERMISSIVE 모드로 운영 중이다 (ADR-002).

> 상세 구현: [Cilium + WARP 네트워크 보안](/study/2026-02-21-cloudflare-warp-metallb-cilium/) | [Istio mTLS + AuthZ](/study/2026-01-22-istio-mtls-security/)

### 3.4 컨테이너 보안 — 침투 후 피해 최소화

> **원칙**: 공격자가 컨테이너 안에 들어와도, 할 수 있는 일을 최소화한다.

| 설정 | 효과 | 적용 범위 |
|------|------|----------|
| `runAsNonRoot: true` | root 권한 없음 → 시스템 파일 수정 불가 | WAS, MySQL |
| `allowPrivilegeEscalation: false` | 권한 상승 차단 | 전체 |
| `capabilities.drop: ALL` | Linux capability 전부 제거 | 전체 |
| Sealed Secrets | 비밀은 암호화된 상태로 Git에 저장 | 전체 |

**WEB(NGINX)의 예외**: 80번 포트 바인딩에 `NET_BIND_SERVICE` capability가 필요하다. 이것만 ADD하고 나머지는 전부 DROP한다.

> 상세 구현: [securityContext 완벽가이드](/study/2025-11-04T20:21:31+09:00-kubernetes-securitycontext-완벽가이드:nextcloud-80번-포트-문제해결/)

### 3.5 런타임 탐지/대응 — IDS + IPS

> **원칙**: 1~4계층을 모두 뚫고 들어온 공격을 실시간으로 탐지하고 자동 대응한다.

```
Falco (IDS) → Falcosidekick (Router) → Talon (IPS)
  syscall 감시     이벤트 분배           자동 대응
```

Falco는 eBPF로 커널의 syscall을 감시한다. 컨테이너 안에서 shell이 실행되거나, 비정상적인 외부 연결이 발생하면 즉시 탐지한다.

| 규칙 | 탐지 대상 | 심각도 |
|------|----------|--------|
| Java Spawning Shell | Java 프로세스가 sh 실행 (RCE 의심) | CRITICAL |
| Shell in App Container | 앱 컨테이너에서 shell 실행 | WARNING |
| Package Manager in Container | 런타임에 패키지 설치 (불변성 위반) | WARNING |
| Write to Binary Dir | /bin, /usr/bin 등에 파일 쓰기 | ERROR |
| Unexpected Outbound | 비표준 포트로 외부 연결 | NOTICE |

**IDS → IPS 전환**: 현재 Talon은 dryRun 모드로, 탐지만 하고 실제 격리는 하지 않는다. 오탐을 충분히 튜닝한 뒤 Phase 3에서 실제 격리를 활성화할 계획이다.

**오탐 관리의 중요성**: 오늘도 Docker BuildKit 빌드 컨테이너가 shell을 실행하고 외부 레지스트리에 연결하는 것이 탐지되었다. 분석 결과 정상적인 이미지 빌드 과정이었다. `moby/buildkit` 이미지를 예외로 추가하고, 필터링 기준을 출발 포트(lport)에서 목적지 포트(rport)로 변경했다.

이런 **탐지 → 분석 → 튜닝** 사이클이 반복되면서 규칙의 정밀도가 올라간다. 오탐이 줄어야 진짜 경보에 집중할 수 있다.

> 상세 구현: [런타임 보안 개념](/study/2026-03-03-falco-runtime-security-concept/) | [Falco eBPF 아키텍처](/study/2026-01-25-falco-ebpf-runtime-security-architecture/) | [Falco+Talon IDS/IPS 구축](/study/2026-02-19-kubernetes-runtime-security/)

### 3.6 SIEM 통합 — 전체 그림

> **원칙**: 개별 도구의 이벤트를 한 곳에서 보고, 상관 분석하고, 에스컬레이션한다.

```
Falco 이벤트 → Wazuh Agent(호스트 로그 수집) → Wazuh Manager → 규칙 매칭 → Discord
```

Wazuh는 단순히 로그를 모으는 것이 아니다. **에스컬레이션 규칙**으로 이벤트의 심각도를 판단한다:

| Wazuh Rule | 조건 | Level | 의미 |
|------------|------|-------|------|
| 100080 | Falco WARNING 1회 | 7 | 관찰 |
| 100090 | Falco CRITICAL 1회 | 13 | 즉시 확인 |
| 100113 | 외부 연결 5분 내 3회 이상 | 12 | C2 통신 의심 |
| 100140 | Reverse Shell 패턴 | 15 | 최고 심각도 |

Level 10 이상만 Discord로 알림을 보낸다. 이유: 모든 이벤트를 보내면 알람 피로(Alert Fatigue)로 정작 중요한 알림을 무시하게 된다.

**MITRE ATT&CK 매핑**: 각 Wazuh Rule에 MITRE 전술(Tactic)과 기법(Technique)을 태깅한다. "이 알람이 공격 킬체인의 어느 단계인가"를 즉시 파악할 수 있다.

---

## 4. 계층 간 관계 — 빈틈과 보완

어떤 보안 아키텍처든 빈틈이 있다. 중요한 것은 **빈틈을 인식하고, 다른 계층이 보완하는 구조**를 만드는 것이다.

| 빈틈 | 해당 계층 | 보완 계층 |
|------|----------|----------|
| 0-day 취약점 (Trivy가 모름) | 1. CI/CD | 5. Falco가 런타임에서 이상 행위 탐지 |
| CI/CD 우회 (kubectl 직접 배포) | 1. CI/CD | 2. Kyverno가 정책 검증 + ArgoCD가 롤백 |
| HTTPS(443)로 C2 통신 | 3. 네트워크 | 5. Falco Rule 4가 비정상 패턴 탐지 |
| Falco 오탐으로 알람 무시 | 5. 런타임 | 6. Wazuh 에스컬레이션으로 반복 패턴 포착 |
| 컨테이너 내부에서 파일 변조 | 4. 컨테이너 | 5. Falco Rule 3 (Write to Binary Dir) 탐지 |

### 아직 없는 것 (TODO)

| 항목 | 현재 상태 | 계획 |
|------|----------|------|
| etcd Secret 암호화 | 미구현 | CIS 1.2.27/28 대응 |
| Talon 실제 격리 | dryRun | 오탐 튜닝 후 Phase 3 전환 |
| Wazuh → Talon 자동 연동 | 미구현 | Phase 4에서 에스컬레이션 기반 격리 |
| Pre-commit Hook (GitLeaks) | 미구현 | 로컬에서 비밀 커밋 자체를 차단 |

---

## 5. 이 아키텍처의 포트폴리오 가치

이 보안 아키텍처가 보여주는 역량:

| 역량 | 증거 |
|------|------|
| **설계 능력** | 6계층 Defense in Depth를 직접 설계 + 계층 간 보완 관계 정의 |
| **구현 능력** | GitLeaks부터 Wazuh까지 전 계층 직접 구축 (Helm Chart, YAML, eBPF) |
| **운영 능력** | 오탐 분석 → 규칙 튜닝 → Git 커밋 → ArgoCD 배포 사이클 |
| **의사결정** | ADR로 기술 선택 근거 기록 (Cilium L7 금지, mTLS PERMISSIVE 등) |
| **문서화** | 각 계층별 상세 구축기 + 이 Overview로 전체 연결 |

---

## 시리즈 네비게이션

이 글은 전체 보안 아키텍처의 Overview다. 각 계층의 상세 구현은 아래 글에서 다룬다:

| 계층 | 글 |
|------|---|
| 1. CI/CD 보안 | [CI/CD 보안 파이프라인 구축기 — GitLeaks와 Trivy](/study/2026-02-25-devsecops-ci-security-gitleaks-trivy/) |
| 1. Secret 관리 | [Sealed Secrets로 GitOps 구현하기](/study/2026-02-04-sealed-secrets-gitops/) |
| 3. 네트워크 격리 | [Cloudflare WARP + Cilium Host Firewall](/study/2026-02-21-cloudflare-warp-metallb-cilium/) |
| 3. 패킷 분석 | [tcpdump로 패킷 분석하기](/study/2026-01-29-network-security-tcpdump-packet-analysis/) |
| 3+4. mTLS + AuthZ | [Istio mTLS와 AuthorizationPolicy로 Zero Trust 구현](/study/2026-01-22-istio-mtls-security/) |
| 4. 컨테이너 보안 | [securityContext 완벽가이드](/study/2025-11-04T20:21:31+09:00-kubernetes-securitycontext-완벽가이드:nextcloud-80번-포트-문제해결/) |
| 5. 런타임 개념 | [런타임 보안이란 무엇인가](/study/2026-03-03-falco-runtime-security-concept/) |
| 5. 런타임 구현 | [Falco eBPF 런타임 보안 아키텍처](/study/2026-01-25-falco-ebpf-runtime-security-architecture/) |
| 5. 런타임 운영 | [Falco+Talon으로 IDS/IPS 구축](/study/2026-02-19-kubernetes-runtime-security/) |
| 5. 트러블슈팅 | [Falco 런타임 보안 트러블슈팅](/study/2026-01-23-falco-runtime-security-troubleshooting/) |
