---
title: "WOL (Wake-on-LAN)이 뭔가?"
date: 2025-10-07T12:00:00+09:00
draft: false
categories: ["Network", "System"]
tags: ["WOL", "ethtool", "systemd", "Linux"]
series: ["서버관리 기초"]
author: "지민 오"
description: "꺼져 있는 PC를 네트워크 매직 패킷으로 원격으로 켜는 기술, WOL 설정과 원리 정리"
---

### 🧠 WOL(Wake-on-LAN) 이 뭔가?

꺼져 있는 PC를 네트워크 매직 패킷(Magic Packet)으로 원격으로 켜는 기술

> ⚠️ **USB NIC 주의:**  
> USB LAN 어댑터는 WOL을 지원하지 않는 경우가 많음.  
> `Supports Wake-on:` 항목에 `g`가 포함되어 있는지 확인하세요.

```bash
sudo ethtool enxb0386cf28a7e | grep Supports

```

### 🧩 전체 구조

```

┌──────────────┐
│ BIOS/UEFI    │ ← Wake on LAN 옵션
│  └─> NIC(유선랜) 전원 유지
└──────────────┘
         ↓
┌──────────────┐
│ NIC (LAN 칩) │ ← Magic Packet을 감시 (전원 대기 상태에서도)
└──────────────┘
         ↓
💡 매직패킷 수신 → NIC이 메인보드로 “Power ON 신호” 전달 → 부팅 시작

```

### ⚙️ 1. 하드웨어 & BIOS 설정

**WOL**은 BIOS/UEFI 와 NIC(네트워크 카드)가 이를 지원해야 합니다.
1. BIOS/UEFI 진입 후 아래 항목을 Enabled 로 설정
- Wake on LAN
- Power On By PCI-E

---

### ⚙️ 2. 네트워크 확인 및 Tool 설치

```bash
# 사용할 NIC 확인
ip link show

# ethtool 설치
sudo apt update
sudo apt install -y ethtool

# WOL 상태 확인
sudo ethtool 인터페이스명 | grep Wake-on

```

### 출력예시

```bash
Supports Wake-on: pumbg
Wake-on: d

```
- d → 비활성화
- g → Magic Packet 기반 WOL 활성화

### ⚙️ 3. Systemd 서비스로 설정
**sudo vi /etc/systemd/system/wol.service**
```bash
[Unit]
Description=Enable Wake-on-LAN for enxb0386cf28a7e
After=network.target

[Service]
Type=oneshot
ExecStart=/sbin/ethtool -s enxb0386cf28a7e wol g

[Install]
WantedBy=multi-user.target

```

활성화 명령어:

```bash
sudo systemctl daemon-reload
sudo systemctl enable wol.service
sudo systemctl start wol.service

```



