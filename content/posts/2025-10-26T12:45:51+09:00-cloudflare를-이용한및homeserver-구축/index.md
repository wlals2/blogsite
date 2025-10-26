---
title: "Cloudflare를 이용한 및 homeserver 구축"
date: 2025-10-26T12:45:51+09:00
draft: false
categories: ["homeserver","cloudflare","blog","VPN"]
tags: ["homeserver","cloudflare","blog","WOL","VPN","WARP","turnel"]
description: "Cloudflare를 이용한 및 homeserver 구축"
author: "늦찌민"
---
---
### 🎯 목표
오늘은 기존 홈서서버를 보안 취약점을 보완하기 위해 구축을 해보았습니다.
**DDNS**를 사용하던 홈서버는 **DDNS 없이도 접속 방법** (`VPN`, `Cloud turneling`) 하도록 설정하였습니다.

현재는 `Cloud flare`를 사용하였지만 다른 방법으로 `wireguard`를 생각 \
물론 DDNS 를 CNAME을 사용할 수 있지만 TP link 외부에서 들어온 알지 못하는 proxy server를 다 차단하기 때문 \
공유기 사이트로 접속이 되지 않음
이러한 문제를 해결 하기 위해 VPN 이나 Turneling을 사용함

---

## 초기 상황
### ⚠️ 문제 상황
```
❌ TP-Link DDNS 사용 (외부 노출)
❌ 포트포워딩 (보안 취약)
❌ 공인 IP 노출
❌ WOL을 위해 공유기 접근 필요
```

### 📝 **원하는 목표**
```
✅ 외부에서 안전하게 Windows/Ubuntu 접근
✅ DDNS 주소 숨기기
✅ VPN을 통한 안전한 접속
✅ WOL 기능 유지
```

### **Cloudflare WARP + Cloudflare Tunnel**

**아키텍처:**
```
[외부 디바이스] 
    ↓ (WARP VPN - L3/L4)
[Cloudflare 글로벌 네트워크]
    ↓ (보안 터널)
[Ubuntu - cloudflared]
    ↓ (로컬 네트워크)
[홈 네트워크 192.168.1.0/24]
    ├─ Windows
    └─ Ubuntu 
```

**주요 특징:**
- L3/L4 레벨 VPN (모든 프로토콜 지원)
- 포트포워딩 불필요
- IP 주소 노출 없음
- Zero Trust 인증

## 3. 구축 단계별 정리

### Phase 1: Cloudflare 설정
```
✅ Cloudflare 계정 생성
✅ Zero Trust 활성화 (무료 플랜)
✅ Team name 설정: jiminhome
✅ 도메인 등록: jiminhome.shop
```

### Phase 2: Ubuntu 서버 설정
```
✅ cloudflared 설치
✅ Cloudflare 인증 (tunnel login)
✅ 터널 생성: home-network
✅ config.yml 작성 (warp-routing 활성화)
✅ 시스템 서비스로 등록 (자동 시작)
✅ Private Network 등록: 192.168.1.0/24

tunnel: [터널ID]
credentials-file: /etc/cloudflared/[터널ID].json

warp-routing:
  enabled: true

ingress:
  - service: http_status:404
```

### Phase 3: Authentication 설정
```
✅ One-time PIN 활성화
✅ WARP authentication identity 활성화
✅ Device enrollment policy 설정
```

### phase 4: 클라이언트 설정
```
✅ 노트북에 WARP 클라이언트 설치
✅ Team name으로 로그인 (jiminhome)
✅ Zero Trust 인증 완료
```

---

## 트러블슈팅 과정

### 문제 1
``` 
Cannot determine default configuration path
permission denied
```
> 원인: sudo로 실행하면 ~가 root 디렉토리를 

#### 💡 해결
```
# 설정 값들이 정확한 경로를 갖게 설정 해줌

sudo mkdir -p /etc/cloudflared
sudo cp ~/.cloudflared/* /etc/cloudflared/
sudo sed -i 's|/home/jimin/.cloudflared/|/etc/cloudflared/|g' /etc/cloudflared/config.yml
sudo cloudflared service install
```

---

### 문제 2
```
✅ Ubuntu에서 Windows로: ping 성공
✅ WARP → Ubuntu: ping 성공
❌ WARP → Windows: ping 실패

#Windows 방화벽이 WARP를 통해 들어오는 트래픽을 "외부"로 인식하여 차단
```
#### 💡 해결
```
# 방화벽 규칙을 "모든 원격 주소"에서 허용하도록 변경
New-NetFirewallRule -DisplayName "ICMP Allow All" -Direction Inbound -Protocol ICMPv4 -Action Allow -RemoteAddress Any -Profile Domain,Private,Public -Enabled True

New-NetFirewallRule -DisplayName "RDP 52515 All" -Direction Inbound -Protocol TCP -LocalPort 52515 -Action Allow -RemoteAddress Any -Profile Domain,Private,Public -Enabled True
```

#### 확인
✅ Ubuntu 접속 \
✅ Windows Ping  \
✅ Windows RDP \
✅ WOL 기능: wakeonlan [MAC] \
✅ 외부 네트워크 테스트: 핸드폰 테더링으로 확인

---



## 보안 고려사항 및 베스트 프랙티스

### 1. ⚠️ Zero Trust 인증

- 이메일 기반 인증 (One-time PIN)
- 본인만 접근 가능


### 2. ⚠️ IP 및 DDNS 노출 제거

- 공인 IP 숨김
- DDNS 제거 가능
- 포트스캔 공격 방어


암호화된 터널 

Cloudflare → Ubuntu: TLS 암호화 \
WARP: WireGuard 기반 암호화


포트포워딩 제거 

공유기에 포트 열기 불필요 \
외부에서 직접 접근 불가


### 3. ⚠️ Windows 방화벽 완전 개방
```
현재 설정:
powershell-RemoteAddress Any -Profile Public

문제:
모든 원격 주소에서 접근 허용
Public 프로필에서도 허용
만약 Cloudflare Tunnel이 뚫리면 Windows도 노출
```

### 4.   위험  ⚠️ WOL의 보안 취약점
문제:
WOL 매직 패킷은 암호화되지 않음 \
MAC 주소를 알면 누구나 PC를 깨울 수 있음

-> ubuntu 에서만 WOL 실행

---

### 느낀점 

> 더 많은 것을 구축하려면 얼마 든지 할 수있다. \
외부에 공개된 주소는 최소화하며, 포트포워딩 등 \
핵심적인 원칙에 맞춰  지속된 업데이트 및 버저닝을 한다는 것이 \
운영엔지니어의 일인 것 같다.

----


### ✅ 핵심 보안 

> 1. 최소 권한 원칙 (Least Privilege) -> 필요한 것만 허용
> 2. 심층 방어 (Defense in Depth) -> 여러 계층의 보안 (인증, 방화벽, 암호화)
> 3. Zero Trust -> 아모것도 신뢰하지 않고 항상 검증
> 4. 지속 모니터링 -> 정기적인 점검과 로그 확인



