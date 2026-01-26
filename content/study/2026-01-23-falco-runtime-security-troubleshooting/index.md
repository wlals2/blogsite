---
title: "Falco 런타임 보안 트러블슈팅 (eBPF IDS)"
date: 2026-01-23
description: "inotify 초기화, Falcosidekick 연결, eBPF 드라이버, BuildKit False Positive 해결"
tags: ["kubernetes", "falco", "ebpf", "security", "ids", "runtime-security", "troubleshooting"]
categories: ["study", "Troubleshooting", "Security"]
---

## 개요

Falco (eBPF 기반 IDS) 운영 중 발생한 트러블슈팅:

| 번호 | 문제 | 원인 |
|------|------|------|
| 1 | CrashLoopBackOff | inotify 리소스 부족 |
| 2 | Loki 연결 실패 | DNS/서비스 문제 |
| 3 | BPF probe 실패 | 커널 버전 낮음 |
| 4 | Alert 저장 안 됨 | priority 필터 |
| 5 | UI 접속 불가 | UI 비활성화 |
| 6 | BuildKit Alert | False Positive |

---

## 1. inotify 초기화 실패

### 상황

```bash
kubectl logs -n falco -l app.kubernetes.io/name=falco
```

```
Error: could not initialize inotify handler
```

```bash
kubectl get pods -n falco
```

```
NAME           READY   STATUS             RESTARTS
falco-xxxxx    0/2     CrashLoopBackOff   5
```

### 원인

Linux 커널의 `inotify` 시스템이 파일 시스템 이벤트를 모니터링합니다. Falco는 룰 파일 변경 감지에 inotify를 사용합니다.

**기본 제한값 문제**:

| 설정 | 기본값 | 용도 |
|------|--------|------|
| max_user_watches | 8192 | 감시할 파일/디렉터리 수 |
| max_user_instances | 128 | inotify 인스턴스 수 |

Kubernetes 환경에서는 기본값이 부족할 수 있음:
- 다수의 컨테이너 실행
- Falco, Prometheus 등 모니터링 도구

### 해결

**1. 현재 값 확인**:

```bash
cat /proc/sys/fs/inotify/max_user_watches
cat /proc/sys/fs/inotify/max_user_instances
```

**2. 임시 적용** (재부팅 시 초기화):

```bash
sudo sysctl -w fs.inotify.max_user_watches=524288
sudo sysctl -w fs.inotify.max_user_instances=512
```

**3. 영구 적용**:

```bash
echo "fs.inotify.max_user_watches=524288" | sudo tee -a /etc/sysctl.conf
echo "fs.inotify.max_user_instances=512" | sudo tee -a /etc/sysctl.conf
sudo sysctl -p
```

**4. Falco Pod 재시작**:

```bash
kubectl delete pod -n falco -l app.kubernetes.io/name=falco \
  --field-selector spec.nodeName=<노드명>
```

### 확인

```bash
kubectl get pods -n falco -o wide
```

```
NAME           READY   STATUS    NODE
falco-xxxxx    2/2     Running   k8s-worker1
```

---

## 2. Falcosidekick Loki 연결 실패

### 상황

```bash
kubectl logs -n falco deploy/falco-falcosidekick
```

```
[ERROR] Loki - Post "http://loki-stack.monitoring.svc.cluster.local:3100/loki/api/v1/push":
dial tcp: lookup loki-stack.monitoring.svc.cluster.local: no such host
```

### 원인

| 원인 | 설명 |
|------|------|
| Loki 서비스 미실행 | Loki Pod가 Running이 아님 |
| 서비스 이름 오류 | 설정 파일의 서비스명 틀림 |
| Namespace 다름 | Loki가 다른 namespace에 있음 |
| NetworkPolicy | 트래픽 차단 |

### 해결

**1. Loki 서비스 확인**:

```bash
kubectl get svc -n monitoring | grep loki
```

```
NAME         TYPE        CLUSTER-IP     PORT(S)
loki-stack   ClusterIP   10.96.xxx.xx   3100/TCP
```

**2. values.yaml 설정 확인**:

```yaml
# values.yaml
falcosidekick:
  config:
    loki:
      hostport: "http://loki-stack.monitoring.svc.cluster.local:3100"
```

**3. DNS 해상도 테스트**:

```bash
kubectl run -it --rm debug --image=busybox --restart=Never \
  -- nslookup loki-stack.monitoring.svc.cluster.local
```

**4. Helm upgrade**:

```bash
helm upgrade falco falcosecurity/falco \
  -n falco \
  -f values.yaml
```

---

## 3. modern_ebpf 드라이버 실패

### 상황

```bash
kubectl logs -n falco -l app.kubernetes.io/name=falco -c falco
```

```
Error: failed to load modern BPF probe
```

### 원인

커널 버전이 modern_ebpf를 지원하지 않음:

| 드라이버 | 최소 커널 | 비고 |
|----------|-----------|------|
| modern_ebpf | 5.8+ | 권장 (CO-RE 기반) |
| ebpf (classic) | 4.14+ | 폭넓은 호환성 |
| kmod | 2.6+ | 커널 모듈 필요 |

### 해결

**1. 커널 버전 확인**:

```bash
uname -r
# 5.8 이상이어야 modern_ebpf 사용 가능
```

**2. 드라이버 변경** (5.8 미만):

```yaml
# values.yaml
driver:
  kind: ebpf  # modern_ebpf → ebpf
```

**3. Helm upgrade**:

```bash
helm upgrade falco falcosecurity/falco \
  -n falco \
  -f values.yaml
```

---

## 4. Alert가 Loki에 저장되지 않음

### 상황

Grafana에서 Falco alert 조회 시 결과 없음:

```
{job="falco"} | json
# 결과 없음
```

### 확인 순서

**1. Falco 이벤트 탐지 확인**:

```bash
# 테스트 이벤트 발생
kubectl exec -it $(kubectl get pod -l app=web -o name | head -1) \
  -n blog-system -- /bin/sh -c "exit"

# Falco 로그 확인
kubectl logs -n falco -l app.kubernetes.io/name=falco -c falco | grep -i "terminal"
```

**2. Falcosidekick 로그 확인**:

```bash
kubectl logs -n falco deploy/falco-falcosidekick | tail -20
# [INFO] Loki - Post OK (204)  ← 정상
# [ERROR] Loki - ...           ← 문제
```

**3. Loki 직접 확인**:

```bash
kubectl port-forward -n monitoring svc/loki-stack 3100:3100 &
curl -G "http://localhost:3100/loki/api/v1/query" \
  --data-urlencode 'query={job="falco"}'
```

**4. minimumpriority 확인**:

```yaml
# values.yaml
config:
  loki:
    minimumpriority: "warning"  # notice 이하는 전송 안 됨
```

---

## 5. Falco UI 접속 불가

### 상황

```bash
kubectl port-forward -n falco svc/falco-falcosidekick-ui 2802:2802
# 브라우저에서 http://localhost:2802 접속 실패
```

### 해결

**1. UI Pod 상태 확인**:

```bash
kubectl get pods -n falco | grep ui
# falco-falcosidekick-ui-xxx   1/1   Running
```

**2. 서비스 확인**:

```bash
kubectl get svc -n falco | grep ui
# falco-falcosidekick-ui   ClusterIP   10.96.xxx.xx   2802/TCP
```

**3. UI 비활성화 확인**:

```yaml
# values.yaml
falcosidekick:
  webui:
    enabled: true  # false면 UI 없음
```

---

## 6. BuildKit Alert (False Positive)

### 상황

GitHub Actions에서 Docker 이미지 빌드 시 CRITICAL Alert 발생:

```bash
# Grafana Loki 쿼리
{priority="Critical"}
```

```
rule="Drop and execute new binary in container"
priority=Critical
container_image=moby/buildkit:buildx-stable-1
proc_name=/check
```

### 원인

**BuildKit 동작 방식**:

```
1. buildx가 BuildKit 컨테이너 생성
   ↓
2. BuildKit이 Dockerfile 실행
   ↓
3. 헬스체크용 /check 바이너리 생성 및 실행 ← Falco 탐지!
   ↓
4. 이미지 완성 및 레지스트리 푸시
```

**Falco 룰이 탐지한 이유**:

```yaml
# Falco 기본 룰
- rule: Drop and execute new binary in container
  condition: >
    spawned_process and
    proc.is_exe_from_memfd=true and
    container
  priority: CRITICAL
```

**"Drop"의 의미**:
- ❌ Falco가 차단했다는 뜻 아님
- ✅ 새 바이너리가 생성/설치("Drop")되었다는 뜻
- 보안 용어: "Dropper" = 악성코드 설치 프로그램

### 판단: False Positive (정상 동작)

| 판단 근거 | 설명 |
|-----------|------|
| 정상 프로세스 | GitHub Actions BuildKit은 공식 Docker 빌드 도구 |
| 신뢰 이미지 | `moby/buildkit` (Docker 공식 이미지) |
| 헬스체크 용도 | `/check` 바이너리는 BuildKit 헬스체크 전용 |
| 격리된 환경 | GitHub Actions Runner는 격리된 빌드 환경 |

**실제 공격과 차이점**:

| 항목 | BuildKit (정상) | 실제 공격 |
|------|----------------|----------|
| **이미지** | moby/buildkit (공식) | 악의적 이미지 |
| **프로세스** | /check (헬스체크) | /tmp/backdoor |
| **목적** | 빌드 도구 동작 | 백도어, rootkit |
| **지속성** | 빌드 완료 후 삭제 | 영구 설치 시도 |

### 해결

**옵션 1: 무시 (권장)**:

```bash
# Grafana에서 BuildKit Alert 필터링
{priority="Critical"} | json | container_image !~ "buildkit"
```

**옵션 2: 예외 규칙 추가**:

```yaml
# values.yaml
customRules:
  blog-rules.yaml: |-
    - rule: Drop and execute new binary in container
      append: true
      exceptions:
        - name: buildkit_binaries
          fields:
            - container.image.repository
          comps:
            - startswith
          values:
            - moby/buildkit
```

```bash
helm upgrade falco falcosecurity/falco \
  -n falco \
  -f values.yaml
```

### Alert 판단 프로세스

```
1. Alert 발생
   ↓
2. 컨텍스트 확인
   - container_image: 신뢰할 수 있는가?
   - k8s_ns_name: 예상된 namespace인가?
   - proc_name: 정상 프로세스인가?
   ↓
3. 판단
   - False Positive → 무시 또는 예외 추가
   - 실제 공격 → 즉시 대응
```

---

## 정리

### Quick Reference

| 증상 | 원인 | 해결 |
|------|------|------|
| CrashLoopBackOff + inotify 에러 | inotify 제한 | sysctl 설정 증가 |
| Loki no such host | DNS/서비스 문제 | Loki 서비스 확인 |
| BPF probe 실패 | 커널 버전 낮음 | ebpf 드라이버로 변경 |
| Alert 없음 | priority 필터 | minimumpriority 확인 |
| UI 접속 불가 | UI 비활성화 | webui.enabled: true |
| BuildKit CRITICAL | False Positive | 무시 또는 예외 추가 |

### False Positive 학습

- IDS/IPS 운영 초기에는 False Positive 많이 발생
- 패턴 학습을 통해 예외 규칙 추가
- 1-2주 운영 후 노이즈 감소

---

## 관련 명령어

```bash
# Falco 상태 확인
kubectl get pods -n falco -o wide

# Falco 로그 확인
kubectl logs -n falco -l app.kubernetes.io/name=falco -c falco | tail -50

# Falcosidekick 로그 확인
kubectl logs -n falco deploy/falco-falcosidekick | tail -20

# 테스트 이벤트 발생
kubectl exec -it <pod-name> -n <namespace> -- /bin/sh -c "exit"

# inotify 설정 확인
cat /proc/sys/fs/inotify/max_user_watches
cat /proc/sys/fs/inotify/max_user_instances

# Falco UI 접속
kubectl port-forward -n falco svc/falco-falcosidekick-ui 2802:2802
```
