---
title: "[Falco/Wazuh 시리즈 #6] Falco 커스텀 규칙의 탐지 사각지대"
date: 2026-03-05T14:00:00+09:00
description: "Falco 커스텀 규칙이 kubectl exec 기반 shell 실행을 탐지하지 못한 원인을 분석하고, 부모 프로세스와 무관하게 모든 shell 실행을 잡는 규칙을 설계하여 탐지→자동 대응 1초 이내 파이프라인을 완성한 과정"
tags: ["falco", "runtime-security", "kubernetes", "ids", "ips", "talon", "shell-detection", "proc-pname", "immutable-container"]
categories:
  - study
  - Security
series: ["Falco/Wazuh 시리즈"]
summary: "Falco의 proc.pname 조건이 kubectl exec 기반 shell 실행을 놓치는 탐지 사각지대를 발견하고, 부모 프로세스 무관 규칙으로 해결한 과정"
showtoc: true
tocopen: true
draft: false
---

## 배경 — "왜 Falco가 shell 실행을 못 잡았는가?"

Falco + Talon IPS 파이프라인을 검증하던 중, WAS Pod에서 shell을 실행했는데 **Falco가 탐지하지 못하는 상황**을 발견했다.

```bash
kubectl exec -n blog-system was-xxx -c spring-boot -- /bin/sh -c "id"
# uid=65534(nobody) gid=65534(nobody) groups=65534(nobody)
```

명령은 성공했지만 Falco 로그에는 아무것도 남지 않았다. 커스텀 규칙으로 "Java Process Spawning Shell"을 만들어 놓았는데, 왜 shell 실행을 탐지하지 못했을까?

---

## 원인 분석 — proc.pname 조건의 한계

### 기존 규칙의 구조

```yaml
- rule: Java Process Spawning Shell
  condition: >
    spawned_process and
    proc.pname in (java, javac) and
    proc.name in (bash, sh, ksh, zsh, dash) and
    container
  priority: CRITICAL
```

핵심 조건은 `proc.pname in (java, javac)` — **부모 프로세스가 java일 때만** 탐지한다.

이 규칙의 설계 의도는 명확하다. Log4Shell, Spring4Shell 같은 **Java RCE 취약점**이 발생하면 Java 프로세스가 직접 shell을 실행하므로, 부모=java 조건으로 정확하게 탐지할 수 있다.

### kubectl exec의 프로세스 트리

문제는 `kubectl exec`으로 실행한 shell의 부모 프로세스가 java가 아니라는 점이다.

```
kubectl exec 경로:
  containerd-shim → sh -c "id"
  (proc.pname = containerd-shim, proc.name = sh)
  → proc.pname이 java가 아니므로 → 규칙 미매칭

Java RCE 경로:
  java (Spring Boot) → /bin/sh
  (proc.pname = java, proc.name = sh)
  → proc.pname이 java이므로 → 규칙 매칭 ✅
```

`kubectl exec`은 kubelet → containerd → containerd-shim → sh 경로로 프로세스를 생성한다. Java 프로세스는 이 체인에 관여하지 않는다.

### 이것이 보안 갭인 이유

실제 공격 시나리오에서 shell이 실행되는 경로는 다양하다:

| 공격 경로 | proc.pname | 기존 규칙 탐지 |
|----------|-----------|-------------|
| Java RCE (Log4Shell) | java | ✅ 탐지 |
| kubectl exec (자격 증명 탈취) | containerd-shim | ❌ 미탐지 |
| Web Shell (파일 업로드 후 실행) | nginx, httpd | ❌ 미탐지 |
| Sidecar 침투 (Envoy 취약점) | envoy, pilot-agent | ❌ 미탐지 |
| Container Escape 후 재진입 | runc | ❌ 미탐지 |

Java RCE만 잡고 나머지 4가지 경로를 모두 놓치고 있었다.

---

## 설계 — 부모 프로세스와 무관한 탐지 규칙

### 핵심 아이디어: Immutable Container 원칙

프로덕션 컨테이너는 **불변(Immutable)**이어야 한다. 빌드 시에 필요한 모든 것을 포함하고, 런타임에 shell을 실행할 이유가 없다.

이 원칙을 규칙으로 표현하면:

> **앱 컨테이너에서 shell이 실행되면, 부모 프로세스가 무엇이든 비정상이다.**

### 오탐 분석 — shell이 정상적으로 실행되는 경우

규칙을 만들기 전에, 클러스터에서 **shell이 정상적으로 실행되는 모든 경우**를 파악해야 한다. 이 분석을 건너뛰면 오탐 폭탄이 된다.

```bash
kubectl get pods -n blog-system -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{range .spec.containers[*]}{.name}{","}{end}{"\t"}{range .spec.initContainers[*]}{.name}{","}{end}{"\n"}{end}'
```

분석 결과, shell을 정상적으로 사용하는 컨테이너:

| 컨테이너 | 이유 | 처리 방법 |
|----------|------|----------|
| `istio-init` (initContainer) | iptables 규칙 설정에 shell 사용 | container name 제외 |
| `otel-agent-download` (initContainer) | curl로 OTEL agent 다운로드 | container name 제외 |
| `mysql-backup` (CronJob) | mysqldump 실행에 shell 필요 | container name 제외 |
| `istio-proxy` (sidecar) | pilot-agent가 shell로 health check | image repository 제외 |

시스템 namespace(kube-system, monitoring, argocd 등)의 Pod들도 제외해야 한다. 이들은 시스템 운영에 shell을 정상적으로 사용한다.

### 최종 규칙

```yaml
- rule: Shell Spawned in Application Container
  desc: >
    Any shell execution in application container indicates
    compromise regardless of parent process
  condition: >
    spawned_process and
    container and
    proc.name in (bash, sh, ksh, zsh, dash) and
    not k8s.ns.name in (kube-system, monitoring, argocd,
      falco, istio-system, security, backup-system,
      longhorn-system, metallb-system, sealed-secrets) and
    not container.image.repository in (
      docker.io/istio/proxyv2,
      docker.io/curlimages/curl) and
    not container.name in (
      istio-init, otel-agent-download, mysql-backup)
  output: >
    WARNING: 앱 컨테이너에서 Shell이 실행되었습니다! (침투 의심)
      (user=%user.name pod=%k8s.pod.name
       namespace=%k8s.ns.name parent=%proc.pname
       cmd=%proc.cmdline container=%container.name
       image=%container.image.repository)
  priority: WARNING
  tags: [container, process, shell, mitre_execution, T1059]
```

**기존 규칙과의 관계**:

| 규칙 | 탐지 대상 | 심각도 | 자동 대응 |
|------|----------|--------|----------|
| Java Process Spawning Shell | RCE (java→shell) | CRITICAL | 네트워크 격리 (NetworkPolicy) |
| Shell Spawned in Application Container | 모든 경로 shell 실행 | WARNING | 의심 라벨 태깅 |

이중 방어가 아니라 **심각도 기반 단계적 대응**이다. Java RCE는 확정적 공격이므로 즉시 격리, 그 외 shell 실행은 의심 단계로 분류하여 분석 대기한다.

---

## Talon 자동 대응 규칙 추가

Falco가 탐지하면 Talon이 자동으로 해당 Pod에 의심 라벨을 붙인다.

```yaml
- rule: Shell in Application Container
  match:
    rules:
      - Shell Spawned in Application Container
    priority: warning
  actions:
    - action: Label Pod as Suspicious
      actionner: kubernetes:label
      parameters:
        labels:
          analysis/status: suspicious
          falco-alert: shell-in-container
```

이 라벨이 붙은 Pod는 보안 분석 대상으로 분류되고, Grafana 대시보드에서 필터링할 수 있다.

---

## 검증 — 공격 시뮬레이션과 결과

### 배포

values.yaml에 규칙을 추가하고 Git push → ArgoCD 자동 배포.

Talon Pod를 재시작하여 새 규칙을 로드했다.

```bash
kubectl rollout restart deployment -n falco falco-talon

kubectl logs -n falco -l app.kubernetes.io/name=falco-talon --tail=5
# 2026-03-05T09:48:33Z INF init result="4 rule(s) has/have been successfully loaded"
# 2026-03-05T09:48:33Z INF http result="Falco Talon is up and listening on 0.0.0.0:2803"
```

### 공격 시뮬레이션

WAS Pod에서 shell을 실행했다.

```bash
kubectl exec -n blog-system was-xxx -c spring-boot -- \
  /bin/sh -c "echo 'verification test'; id; hostname"
# verification test
# uid=65534(nobody) gid=65534(nobody) groups=65534(nobody)
# was-54b99dbfcc-tnn9x
```

### 탐지 결과

Falco가 즉시 탐지했다:

```
09:50:07 INF event="Shell Spawned in Application Container"
  output="WARNING: 앱 컨테이너에서 Shell이 실행되었습니다! (침투 의심)
    (user=nobody pod=was-54b99dbfcc-tnn9x namespace=blog-system
     parent=containerd-shim
     cmd=sh -c echo 'verification test'; id; hostname
     container=spring-boot
     image=ghcr.io/wlals2/board-was)"
```

Talon이 자동으로 규칙을 매칭하고 라벨을 적용했다:

```
09:50:07 INF match  rule="Shell in Application Container"
09:50:07 INF action action="Label Pod as Suspicious" status=in_progress
09:50:08 INF action action="Label Pod as Suspicious" status=success
  output="the pod 'was-54b99dbfcc-tnn9x' in the namespace 'blog-system' has been labeled"
```

Pod 라벨을 확인하면:

```bash
kubectl get pod was-xxx -o jsonpath='{.metadata.labels}' | python3 -m json.tool
# {
#     "analysis/status": "suspicious",
#     "app": "was",
#     "falco-alert": "shell-in-container",
#     ...
# }
```

---

## 트러블슈팅 — Talon이 새 규칙을 인식하지 못한 문제

검증 과정에서 한 가지 문제가 있었다. 규칙을 배포한 직후에는 **Falco는 탐지하지만 Talon이 대응하지 않았다**.

| 단계 | 상태 |
|------|------|
| Falco 탐지 | ✅ 정상 |
| Falcosidekick → Talon 전송 | ✅ 정상 |
| Talon 규칙 매칭 | ❌ 실패 |
| Talon 라벨 적용 | ❌ 실패 |

**원인**: ArgoCD가 ConfigMap을 업데이트했지만, Talon Pod가 변경을 감지하지 못했다.

```
Before (재시작 전): "3 rule(s) has/have been successfully loaded"
After  (재시작 후): "4 rule(s) has/have been successfully loaded"
```

Talon의 `watchRules: true` 설정이 ConfigMap 변경을 실시간으로 반영하지 못하는 한계가 있었다. `kubectl rollout restart`로 Pod를 재시작하여 해결했다.

**교훈**: Talon ConfigMap 변경 후에는 반드시 Pod 재시작으로 규칙 로드를 확인해야 한다.

---

## 성과

| 항목 | Before | After |
|------|--------|-------|
| Shell 탐지 경로 | Java→Shell 1가지만 | 부모 프로세스 무관 전체 경로 |
| kubectl exec 탐지 | ❌ 미탐지 | ✅ 1초 이내 탐지 |
| Web Shell 탐지 | ❌ 미탐지 | ✅ 1초 이내 탐지 |
| 탐지→자동 대응 시간 | - | 약 1초 (Falco→Talon 라벨링) |
| Falco 커스텀 규칙 수 | 4개 | 5개 |
| Talon 자동 대응 규칙 수 | 3개 | 4개 |
| 오탐 방지 예외 | - | namespace 10개 + container 3개 + image 2개 |

### 전체 타임라인

```
09:50:07.xxx  Shell 실행 (kubectl exec)
09:50:07.xxx  Falco eBPF 탐지 (syscall execve 감지)
09:50:07.xxx  Falcosidekick → Loki(로그) + Talon(대응) + WebUI(시각화) 동시 라우팅
09:50:07.xxx  Talon 규칙 매칭 ("Shell in Application Container")
09:50:08.xxx  Talon 라벨 적용 완료 (analysis/status: suspicious)
```

공격 발생부터 자동 대응까지 **약 1초**.

---

## 다음 단계

1. **CRITICAL 규칙 검증**: Java RCE 시뮬레이션으로 NetworkPolicy 자동 격리까지 확인
2. **오탐 모니터링**: 규칙 배포 후 1주일간 정상 운영에서 오탐 발생 여부 확인
3. **Grafana 대시보드 연동**: `analysis/status: suspicious` 라벨 기반 의심 Pod 필터링 패널 추가
