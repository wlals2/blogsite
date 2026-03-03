---
title: "Wazuh SIEM 실전 보안 모니터링 - SSH Brute Force 탐지와 트러블슈팅"
date: 2026-02-16T02:30:00+09:00
draft: false
categories:
  - study
  - Security
tags:
  - Wazuh
  - SIEM
  - Security
  - Kubernetes
  - SSH
  - Brute Force
  - Troubleshooting
---

## 개요

Kubernetes 클러스터에 Wazuh SIEM을 구축하고, 실제 보안 위협을 탐지하는 과정을 기록했다. Falco 로그만 수집하던 Agent를 시스템 전체 보안 모니터링으로 확장하면서, 예상치 못한 실제 SSH Brute Force 공격을 발견했다.

**환경**:
- Kubernetes 1.31.4
- Wazuh 4.14.2 (Manager, Indexer, Dashboard, Agent)
- Falco 4.14.0
- Ubuntu 22.04 LTS

---

## 1. 초기 상태: Falco 로그만 수집

### 현재 Wazuh Agent 설정

Wazuh Agent는 DaemonSet으로 모든 노드에 배포되어 있었고, Falco 로그만 수집하도록 설정되어 있었다.

```xml
<!-- wazuh-agent-conf/ossec.conf -->
<ossec_config>
  <localfile>
    <log_format>syslog</log_format>
    <location>/var/log/containers/falco-*.log</location>
  </localfile>
</ossec_config>
```

**문제점**:
- ❌ SSH 로그 수집 안 함 (`/var/log/auth.log`)
- ❌ 시스템 로그 수집 안 함 (`/var/log/syslog`)
- ❌ 파일 무결성 모니터링 (FIM) 비활성화
- ❌ Rootcheck 비활성화

**결론**: Wazuh는 Falco가 탐지한 이벤트만 받고, 직접적인 보안 위협(SSH Brute Force, 파일 변조 등)은 탐지하지 못한다.

---

## 2. Wazuh Agent 설정 확장

### 목표

Falco 로그 + 시스템 로그 수집으로 **통합 보안 모니터링** 구현.

### 추가한 설정

```xml
<!-- SSH 로그 수집 (Brute Force 공격 탐지) -->
<localfile>
  <log_format>syslog</log_format>
  <location>/var/log/auth.log</location>
</localfile>

<!-- 시스템 로그 수집 (전체 시스템 이벤트) -->
<localfile>
  <log_format>syslog</log_format>
  <location>/var/log/syslog</location>
</localfile>

<!-- 파일 무결성 모니터링 (중요 파일 변경 탐지) -->
<syscheck>
  <disabled>no</disabled>
  <frequency>300</frequency>
  <directories check_all="yes" realtime="no">/etc</directories>
  <directories check_all="yes" realtime="no">/usr/bin,/usr/sbin</directories>
  <directories check_all="yes" realtime="no">/bin,/sbin</directories>
</syscheck>

<!-- Rootkit 탐지 -->
<rootcheck>
  <disabled>no</disabled>
  <frequency>43200</frequency>
</rootcheck>

<!-- 시스템 정보 수집 (프로세스, 네트워크, 패키지 등) -->
<wodle name="syscollector">
  <disabled>no</disabled>
  <interval>1h</interval>
  <scan_on_start>yes</scan_on_start>
</wodle>
```

### 배포

```bash
# Git commit & push
git add apps/security/wazuh/wazuh_agents/wazuh-agent-conf/ossec.conf
git commit -m "feat(wazuh): Wazuh Agent 설정 확장 - 통합 보안 모니터링"
git push

# Wazuh Agent Pod 재시작 (ConfigMap 변경 적용)
kubectl rollout restart daemonset wazuh-agent -n security
```

---

## 3. 실전 공격 시나리오 테스트

### 테스트 1: SSH Brute Force 공격 시뮬레이션

존재하지 않는 사용자로 로그인 시도 (5회):

```bash
for i in {1..5}; do
  echo "Attempt $i"
  echo "wrong_password" | timeout 2 ssh -o StrictHostKeyChecking=no \
    attacker@localhost 2>&1 | head -1
  sleep 1
done
```

**결과**:
```
Permission denied, please try again. (5회)
```

### 테스트 2: 파일 무결성 모니터링 (FIM)

```bash
# /etc/hosts 파일 수정
echo "# Test comment added at $(date)" | sudo tee -a /etc/hosts

# 의심스러운 스크립트 생성
echo "malicious content" | sudo tee /tmp/suspicious_script.sh
sudo chmod +x /tmp/suspicious_script.sh
```

### 테스트 3: 비정상 프로세스 실행

```bash
# 암호화폐 채굴 시뮬레이션
timeout 5 bash -c 'while true; do echo "Mining..."; sleep 1; done' &

# 네트워크 스캔 시뮬레이션
timeout 3 nc -zv 192.168.1.1 22 80 443
```

---

## 4. 실제 외부 공격 발견 🚨

테스트 후 `/var/log/auth.log`를 확인하던 중, **예상치 못한 실제 SSH Brute Force 공격**을 발견했다.

### 공격 로그

```bash
sudo tail -30 /var/log/auth.log | grep "Failed password"
```

**결과**:
```
Feb 16 02:16:23 k8s-cp sshd[410078]: Failed password for root from 178.62.212.81 port 54112 ssh2
Feb 16 02:16:46 k8s-cp sshd[410105]: Failed password for root from 188.166.61.234 port 45746 ssh2
Feb 16 02:16:51 k8s-cp sshd[410107]: Failed password for root from 64.225.64.75 port 34152 ssh2
Feb 16 02:16:54 k8s-cp sshd[410109]: Failed password for root from 157.245.72.104 port 42788 ssh2
Feb 16 02:17:14 k8s-cp sshd[410130]: Failed password for root from 188.166.26.245 port 43044 ssh2
Feb 16 02:17:36 k8s-cp sshd[410168]: Failed password for root from 165.22.204.165 port 60400 ssh2
Feb 16 02:17:37 k8s-cp sshd[410166]: Failed password for root from 188.166.11.206 port 53906 ssh2
```

### 공격 분석

| 항목 | 내용 |
|------|------|
| **공격 유형** | SSH Brute Force |
| **대상** | root 계정 |
| **공격 IP 수** | 7개 (DigitalOcean, Linode 등 클라우드 IP) |
| **공격 시간** | 02:16 ~ 02:17 (약 1분간) |
| **탐지 방법** | Wazuh Agent → `/var/log/auth.log` 수집 |

**공격 IP 목록**:
- 178.62.212.81
- 188.166.61.234
- 64.225.64.75
- 157.245.72.104
- 188.166.26.245
- 165.22.204.165
- 188.166.11.206

### 의미

Wazuh Agent 설정을 확장하지 않았다면, 이 공격은 **탐지되지 않았을 것이다**. Falco는 컨테이너 이상 행위만 탐지하므로, 호스트 노드의 SSH 로그인 시도는 모니터링 범위 밖이었다.

---

## 5. 트러블슈팅: Wazuh Manager 연결 실패

### 문제 발견

Wazuh Agent 로그를 확인하던 중, Manager에 연결하지 못하는 문제를 발견했다.

```bash
kubectl logs -n security -l app=wazuh-agent --tail=20
```

**에러**:
```
2026/02/15 17:16:59 wazuh-agentd: INFO: Could not resolve hostname
'wazuh-manager-master-0.wazuh-cluster.security.svc.cluster.local'
```

### 원인 분석

```bash
kubectl get pods -n security | grep wazuh-manager
```

**결과**:
```
wazuh-manager-master-0   0/1  ContainerCreating  0  131m
wazuh-manager-worker-0   1/1  Running            0   59m
wazuh-manager-worker-1   1/1  Running            0   58m
```

Wazuh Manager Master가 131분째 ContainerCreating 상태였다.

```bash
kubectl describe pod wazuh-manager-master-0 -n security
```

**근본 원인**:
```
Warning  FailedAttachVolume  attachdetach-controller
AttachVolume.Attach failed for volume "pvc-8f3bd21b-5ecc-4811-92bc-591bc0a3709c"
: rpc error: code = Aborted desc = volume is not ready for workloads
```

**PVC Attach 실패**로 인해 Master Pod가 시작하지 못했다.

### 해결 방법

Worker가 정상 작동 중이므로, **Agent 연결 대상을 Worker로 변경**했다.

```xml
<!-- Before -->
<address>wazuh-manager-master-0.wazuh-cluster.security.svc.cluster.local</address>

<!-- After -->
<address>wazuh-manager-worker-0.wazuh-cluster.security.svc.cluster.local</address>
```

```bash
git add apps/security/wazuh/wazuh_agents/wazuh-agent-conf/ossec.conf
git commit -m "fix(wazuh): Agent 연결 대상을 Worker로 변경 (Master PVC 이슈)"
git push
kubectl rollout restart daemonset wazuh-agent -n security
```

---

## 6. 교훈

### Wazuh + Falco 통합 아키텍처

```
┌─────────────────────────────────────────┐
│ Wazuh Agent (DaemonSet)                 │
├─────────────────────────────────────────┤
│ 1. Falco 로그 (/var/log/containers)     │
│    → 컨테이너 이상 행위 탐지            │
├─────────────────────────────────────────┤
│ 2. SSH 로그 (/var/log/auth.log)         │
│    → Brute Force 공격 탐지              │
├─────────────────────────────────────────┤
│ 3. 시스템 로그 (/var/log/syslog)        │
│    → 전체 시스템 이벤트                 │
├─────────────────────────────────────────┤
│ 4. 파일 무결성 모니터링 (FIM)           │
│    → /etc, /usr/bin 등 중요 파일 변조   │
├─────────────────────────────────────────┤
│ 5. Rootcheck + Syscollector             │
│    → Rootkit 탐지, 시스템 정보 수집     │
└─────────────────────────────────────────┘
           ↓
    Wazuh Manager
           ↓
   Wazuh Indexer (OpenSearch)
           ↓
   Wazuh Dashboard
```

### 단일 도구의 한계

- **Falco만**: 컨테이너 이상 행위만 탐지 (호스트 SSH 공격 미탐지)
- **Wazuh만**: 컨테이너 내부 이벤트 수집 어려움
- **통합**: Kubernetes 환경 + 호스트 시스템 전체 보안 모니터링 가능

### 실전 경험의 가치

이론적으로는 "SSH Brute Force를 탐지해야 한다"고 알고 있었지만, **실제로 공격을 발견**하면서 보안 모니터링의 중요성을 체감했다.

---

## 7. 다음 단계

### 즉시 해결 필요

1. **Wazuh Manager Master PVC 문제 해결**
   - PVC가 "not ready for workloads" 상태인 원인 파악
   - StorageClass, PV 상태 확인
   - 필요 시 PVC 재생성

2. **Wazuh Agent Pod Evicted 문제**
   - 리소스 부족으로 일부 Worker 노드에서 Pod Evicted
   - CPU/Memory Requests 조정 필요

### 보안 강화

1. **SSH 접근 제어**
   - Fail2ban 또는 Wazuh Active Response 설정
   - 5회 실패 시 IP 자동 차단
   - SSH 포트 변경 (22 → Custom)

2. **Wazuh Alert 설정**
   - SSH Brute Force 탐지 룰 강화
   - Discord/Slack 알림 연동
   - Critical Alert 자동 대응

3. **Wazuh Dashboard 활용**
   - Security Events 대시보드 생성
   - GeoIP로 공격 출처 시각화
   - 공격 패턴 분석

---

## 스크린샷 가이드

**추천 캡처 화면**:

1. **Wazuh Dashboard**:
   - Security Events 탭 (SSH Failed login 이벤트)
   - GeoIP Map (공격 IP 위치 시각화)

2. **auth.log**:
   ```bash
   sudo tail -50 /var/log/auth.log | grep "Failed password"
   ```

3. **Wazuh Agent 상태**:
   ```bash
   kubectl get pods -n security -l app=wazuh-agent -o wide
   ```

4. **Wazuh Manager 상태**:
   ```bash
   kubectl get pods -n security | grep wazuh-manager
   kubectl describe pod wazuh-manager-master-0 -n security
   ```

---

## 참고 자료

- [Wazuh 공식 문서](https://documentation.wazuh.com/)
- [Falco 공식 문서](https://falco.org/docs/)
- [Kubernetes Security Best Practices](https://kubernetes.io/docs/concepts/security/)
- [SSH Brute Force 방어 전략](https://www.ssh.com/academy/attack/brute-force)

---

## 결론

Wazuh SIEM을 Kubernetes 환경에 통합하고, 실제 보안 위협을 탐지하는 과정을 경험했다. Falco와 Wazuh의 **역할 분담**이 명확해졌다:

- **Falco**: 컨테이너 이상 행위 탐지
- **Wazuh**: 호스트 + 컨테이너 통합 보안 모니터링

단순히 도구를 설치하는 것을 넘어, **실전 공격 탐지**를 통해 보안 모니터링의 중요성을 체감했다. 앞으로는 Alert 자동 대응, 공격 패턴 분석 등으로 보안 수준을 더욱 강화할 계획이다.
