---
title: "K8s 보안은 완벽했다, 그런데 리눅스가 뚫릴 뻔했다"
date: 2026-02-23T22:00:00+09:00
categories:
  - study
  - Security
tags: ["security", "ssh", "brute-force", "fail2ban", "ufw", "wazuh", "linux-hardening", "kubernetes"]
summary: "Wazuh SIEM이 238,903건의 SSH Brute Force 공격을 탐지했다. Istio mTLS, Cilium NetworkPolicy는 정상이었다. 문제는 예상치 못한 곳에 있었다 — 사용하지 않는 USB 랜 어댑터에 공인 IP가 붙어있었다."
showtoc: true
tocopen: true
draft: false
series: ["홈랩 보안 시리즈"]
---

## 배경: K8s 보안은 충분하다고 생각했다

홈랩 K8s 클러스터에는 여러 보안 레이어가 구성되어 있다.

- **Istio mTLS**: 서비스 간 암호화 통신
- **Cilium NetworkPolicy**: L3/L4 네트워크 정책
- **Istio AuthorizationPolicy**: L7 접근 제어
- **Wazuh SIEM**: 보안 이벤트 모니터링
- **Falco**: 런타임 이상 행동 탐지

블로그로 들어오는 트래픽 경로는 이렇다.

```
외부 사용자
  → Cloudflare (DDoS 방어, TLS 종료)
  → MetalLB (192.168.1.200, LoadBalancer)
  → Istio Gateway
  → Istio AuthorizationPolicy (192.168.1.0/24 허용)
  → Pod (blog-system namespace)
```

이 경로는 완벽하게 보호됐다. 문제는 이 경로와 전혀 무관한 곳에 있었다.
그런데 Wazuh Discord 알람이 울렸다.

---

## 탐지: Wazuh가 먼저 알았다

```
⚠️ HIGH | Level 10
sshd: brute force trying to get access to the system.
Rule ID: 5712
Log: Feb 23 19:14:16 k8s-cp sshd[2640343]: Invalid user admin from 134.199.197.95 port 47802
```

```
⚠️ HIGH | Level 10
PAM: Multiple failed logins in a small period of time.
Rule ID: 5551 | MITRE T1110
Log: Feb 23 19:14:27 k8s-cp sshd[2640423]: pam_unix(sshd:auth): authentication failure;
     rhost=134.199.197.95 user=root
```

알람은 계속 쏟아졌다. `admin → root → operator → jenkins → spark → mysql → zabbix` 순서로 시도했다. 전형적인 딕셔너리 공격이었다.

---

## 분석: 어떻게 들어온 건가

k8s-cp는 192.168.1.187 사설 IP다. 외부에서 직접 접근이 불가능해야 한다.
그런데 어떻게?

```bash
$ ip addr show | grep "inet "
    inet 127.0.0.1/8 scope host lo
    inet 192.168.1.187/24 ...  eno1          # 내부망
    inet 122.46.102.252/24 ... enxb0386cf28a7e  # ← 공인 IP!
```

**USB 랜 어댑터(`enxb0386cf28a7e`)에 공인 IP `122.46.102.252`가 직접 할당되어 있었다.**

과거 Cloudflare 도입 전에 블로그 외부 접근용으로 붙여놓은 USB 랜 어댑터였다.
Cloudflare로 전환 후 제거하지 않았고, 계속 공인 IP를 할당받고 있었다.

```
[실제 공격 경로]
인터넷 → 122.46.102.252:22 (k8s-cp 공인 IP 직접 노출)
        → sshd (방화벽도 꺼져 있었음)
        → Brute Force 공격
```

---

## 실태: 더 심각했다

실패 로그인 기록을 확인했다.

```bash
$ sudo lastb | wc -l
238903
```

**238,903건.** 그리고 기간을 확인했다.

```bash
$ sudo lastb | tail -1   # 가장 오래된 기록
btmp begins Sun Feb  1 00:51:08 2026

$ sudo lastb | head -1   # 가장 최근 기록
git   ssh:notty  134.199.197.95  Mon Feb 23 19:32
```

**23일간** 공격이 진행 중이었다. 그동안 전혀 인지하지 못했다.

IP별로 집계해보니 134.199.197.95는 전체 중 하나에 불과했다.

```bash
$ sudo lastb | awk '{print $3}' | grep -E "^[0-9]" | sort | uniq -c | sort -rn | head -10
   7670 61.50.119.110
   7318 165.245.139.37
   7091 130.12.181.140
   5765 165.245.137.158
   5353 165.245.142.236
   5068 134.199.197.95     ← Wazuh가 탐지한 IP (5위)
   3853 170.64.201.228
   2525 130.12.181.253
   2348 130.12.181.254
   1476 36.108.170.78

$ sudo lastb | awk '{print $3}' | grep -E "^[0-9]" | sort -u | wc -l
1434
```

**1,434개의 고유 IP**가 공격에 참여했다. Wazuh가 탐지한 134.199.197.95는 5위였다. 공격의 일부만 알람이 울린 것이었다.

시도된 계정도 확인했다.

```bash
$ sudo lastb | awk '{print $1}' | sort | uniq -c | sort -rn | head -5
  37642 root
  23066 admin
  16946 user
  12556 ubuntu     ← Ubuntu 서버임을 파악하고 시도
  10937 oracle
```

`ubuntu` 계정을 12,556회 시도했다. 봇이 이 서버가 Ubuntu임을 파악하고 있었다는 뜻이다.

**침투 성공 여부 확인:**

```bash
$ sudo last | grep -v "192.168.1"
# 출력 없음 — 내부 IP에서만 로그인 성공 기록
```

다행히 침투는 없었다. 키 인증만으로 실질적 방어가 됐다.

---

## 근본 원인: 4가지가 동시에

| 취약점 | 상태 | 이유 |
|--------|------|------|
| 사용하지 않는 공인 IP 인터페이스 | 활성화 | Cloudflare 전환 후 제거 누락 |
| ufw | inactive | 방화벽 자체가 꺼짐 |
| PasswordAuthentication | yes (기본값) | 명시적 설정 안 함 |
| SSH 포트 | Anywhere ALLOW | 전 세계 허용 |

이 중 하나만 막혔어도 공격이 훨씬 어려웠다. 하지만 4개가 모두 열려있었다.

---

## 조치: 공격 경로 완전 차단

### Step 1 — 공인 IP 제거 (근본 원인 해결)

```bash
$ sudo nmcli connection delete "Wired connection 2"
$ ip route show | grep default
default via 192.168.1.1 dev eno1 proto static metric 101
# 출력: 기본 게이트웨이가 공유기로 자동 전환됨
```

공인 IP가 사라지자 기본 라우팅이 자동으로 공유기 NAT으로 전환됐다.
외부에서 22번 포트에 직접 접근하는 경로가 사라졌다.

### Step 2 — ufw 활성화 + SSH 규칙 정리

```bash
# 내부망만 SSH 허용 (활성화 전에 먼저!)
$ sudo ufw allow from 192.168.1.0/24 to any port 22
$ sudo ufw --force enable

# 기존 SSH Anywhere 규칙 제거
$ yes | sudo ufw delete 9   # 22/tcp ALLOW Anywhere 삭제

# 최종 SSH 규칙
$ sudo ufw status | grep 22
22/tcp  ALLOW IN  192.168.1.195   (Windows 워크스테이션)
22/tcp  ALLOW IN  192.168.1.0/24  (내부망 전체)
```

### Step 3 — PasswordAuthentication 비활성화

```bash
# /etc/ssh/sshd_config
$ sudo sed -i 's/^#PasswordAuthentication yes/PasswordAuthentication no/' /etc/ssh/sshd_config
$ sudo systemctl restart sshd

$ sudo sshd -T | grep passwordauthentication
passwordauthentication no
# 출력: 키 인증만 허용됨
```

### Step 4 — fail2ban 설치 + 내부망 오탐 방지

```bash
$ sudo apt install fail2ban -y

$ sudo cat /etc/fail2ban/jail.local
[DEFAULT]
# Why: 내부망은 절대 차단하지 않음 (합법적 SSH 접근의 유일한 경로)
ignoreip = 127.0.0.1/8 192.168.1.0/24
bantime  = 86400
findtime = 300
maxretry = 3
backend  = systemd

[sshd]
enabled  = true
port     = ssh
logpath  = /var/log/auth.log
maxretry = 3
bantime  = 86400

$ sudo fail2ban-client get sshd ignoreip
192.168.1.0/24
# 출력: 내부망 IP는 차단 제외

$ sudo fail2ban-client status sshd
Status for the jail: sshd
|- Currently failed:  0
|- Total failed:      0
`- Currently banned:  0
```

`ignoreip` 설정이 중요한 이유가 있다. fail2ban이 내부망 IP를 차단하면 SSH 접근 방법이 완전히 사라진다. 콘솔이나 IPMI 없이는 물리적으로 서버 앞에 가야 한다. `192.168.1.0/24`를 ignoreip에 추가해 이 위험을 원천 차단했다.

---

## 방어 레이어: 3중 보호 구조

단일 방어선으로는 부족하다. 한 레이어가 우회되면 그 다음이 없다. 이번 조치에서 3개 레이어를 독립적으로 설계했다.

```
외부 공격 (1,434개 IP, 238,903회 시도)
  ↓
[Layer 1] ufw — 패킷 레벨 차단
  ALLOW: 192.168.1.0/24 only
  → 외부 IP는 22번 포트 패킷 자체를 DROP
  ↓ (내부망만 통과)
[Layer 2] fail2ban — 반복 실패 자동 차단
  3회 실패 → 24시간 차단
  ignoreip: 192.168.1.0/24 (내부망은 절대 차단 안 함)
  ↓
[Layer 3] sshd AllowUsers — 계정 레벨 제한
  Match Address 192.168.1.0/24
  AllowUsers ubuntu jimin
```

| 레이어 | 도구 | 역할 | 내부망 오탐 방지 |
|--------|------|------|-----------------|
| L1 | ufw | 외부 IP 패킷 DROP | 내부망만 허용 (Anywhere 규칙 삭제) |
| L2 | fail2ban | 반복 실패 자동 차단 | `ignoreip = 192.168.1.0/24` |
| L3 | sshd AllowUsers | 허용된 계정만 인증 | `Match Address` 내부망 범위 내만 |

각 레이어는 독립적으로 작동한다. ufw가 비활성화되더라도 fail2ban이 차단하고, fail2ban이 실패해도 sshd가 계정을 제한한다. 이번 공격이 23일간 진행될 수 있었던 건 **3개 레이어가 모두 없었기** 때문이다.

---

## K8s 보안 vs OS 보안: 서로 다른 레이어

이번 사건에서 중요한 교훈을 얻었다.

```
K8s 보안 레이어              OS 보안 레이어
─────────────────────        ─────────────────────
Istio mTLS       ✅          ufw                ❌ inactive
Cilium Policy    ✅          PasswordAuth       ❌ yes
AuthorizationPolicy ✅       fail2ban           ❌ 미설치
Wazuh 탐지       ✅          네트워크 인터페이스  ❌ 공인IP 노출
```

**K8s 보안 도구들은 Pod 트래픽만 보호한다.** OS 레벨 SSH, 네트워크 인터페이스, 방화벽은 K8s가 전혀 관여하지 않는 레이어다.

---

## OS 보안: K8s 시대에도 생략할 수 없는 것들

K8s, Istio, Cilium 같은 도구에 익숙해지다 보면 "예전 방식"처럼 느껴지는 것들이 있다.

- ufw (방화벽)
- fail2ban (자동 차단)
- sshd 설정 (PasswordAuthentication, AllowUsers)
- 네트워크 인터페이스 관리

이것들은 컨테이너 오케스트레이션이 나오기 훨씬 전부터 있던 Linux 기본 보안이다.
"레거시"처럼 보이지만, K8s가 이것들을 대체하지 않는다. 보호하는 대상이 다르기 때문이다.

| 도구 | 보호 대상 | K8s로 대체 가능? |
|------|----------|----------------|
| Istio mTLS | Pod 간 통신 | - |
| Cilium NetworkPolicy | Pod 네트워크 트래픽 | - |
| **ufw** | **OS 네트워크 인터페이스** | **불가** |
| **fail2ban** | **OS SSH 서비스** | **불가** |
| **sshd 설정** | **OS 인증** | **불가** |

K8s 클러스터를 운영한다는 것은 결국 Linux 서버를 운영한다는 뜻이다.
클러스터 보안을 아무리 잘 구성해도, 서버 자체의 기본 보안이 없으면 의미가 없다.

---

## 성과

| 항목 | 조치 전 | 조치 후 |
|------|--------|--------|
| 외부 SSH 노출 | 공인 IP 직접 노출 | 공유기 NAT 뒤 (포트 포워딩 없음) |
| 방화벽 | inactive | active, 내부망만 허용 |
| 비밀번호 인증 | 허용 (기본값) | 비활성화 (키 인증만) |
| 자동 차단 | 없음 | fail2ban (3회 → 24시간 차단) |
| 공격 가능 여부 | 238,903건 시도 가능 | SSH 접근 자체 불가 |

---

## 교훈

**1. 사용하지 않는 리소스는 즉시 제거해야 한다.**
"나중에 지우지"라는 생각이 보안 취약점이 된다. 특히 네트워크 인터페이스, 포트, 계정은 즉시 제거가 원칙이다.

**2. Wazuh가 없었다면 몰랐다.**
238,903건의 공격이 진행 중이었지만 시스템이 정상 동작하고 있어 눈치채지 못했다. SIEM 없이는 탐지 자체가 불가능했다.

**3. 보안은 레이어별로 점검해야 한다.**
K8s 보안이 완벽하더라도 OS 레벨, 네트워크 레벨, 애플리케이션 레벨은 각각 따로 점검해야 한다.

**4. fail2ban은 K8s 환경에서도 필수다.**
Wazuh/Falco는 탐지 도구다. 자동 차단은 fail2ban이 담당한다. 역할이 다르다.

---

## 다음 단계

- [ ] 워커 노드(192.168.1.61/62/60) fail2ban 동일 적용
- [ ] ufw K8s 포트 규칙 최소화 (6443, 2379:2380 등)
- [ ] 정기 보안 점검 체크리스트 수립
- [ ] Wazuh Active Response 설정 (탐지 → 자동 차단 연동)
