---
title: "Cloudflare WARP Split Tunnel로 원격 네트워크 안전하게 접속하기"
date: 2025-11-03
draft: false
tags: ["Cloudflare", "WARP", "VPN", "Network", "RDP", "Split Tunnel"]
categories: ["Infrastructure", "Tutorial"]
description: "Cloudflare WARP Split Tunnel을 활용하여 특정 네트워크만 터널을 통과시키고 나머지 트래픽은 직접 인터넷을 사용하는 방법"
---

## 개요

이 가이드는 Cloudflare WARP와 Cloudflare Tunnel을 사용하여 원격지에서 프라이빗 네트워크에 안전하게 접속하면서도, 일반 인터넷 트래픽은 로컬 연결을 유지하는 Split Tunnel 설정 방법을 다룹니다.

### 해결하려는 문제

일반적인 VPN 사용 시:
- ❌ 모든 트래픽이 VPN을 통과하여 속도 저하
- ❌ 일반 웹사이트 접속도 VPN 서버를 경유
- ❌ Netflix, YouTube 등 스트리밍 서비스 제한 가능

Split Tunnel 사용 시:
- ✅ 특정 네트워크(예: 회사 내부망)만 터널 통과
- ✅ 일반 인터넷은 로컬 연결 직접 사용
- ✅ 속도 저하 없이 원격 접속 가능

## 아키텍처

```

[클라이언트 PC (외부)]
    │
    ├─→ 프라이빗 네트워크 (10.0.0.0/24)
    │   └─→ WARP → Cloudflare → cloudflared → 서버
    │
    └─→ 일반 인터넷 (Google, YouTube 등)
        └─→ 로컬 ISP 직접 연결

```

## 사전 요구사항

### 서버 측
- Cloudflare 계정
- Cloudflare Zero Trust 설정 완료
- cloudflared 설치 및 터널 생성 완료
- 프라이빗 네트워크 라우트 설정 완료

### 클라이언트 측
- Windows 10/11 (또는 macOS, Linux)
- 인터넷 연결

## 서버 측 설정 확인

먼저 서버에서 Cloudflare Tunnel이 제대로 설정되어 있는지 확인합니다.

### 1. cloudflared 상태 확인

```powershell
# cloudflared 버전 확인
cloudflared --version

# 터널 목록 확인
cloudflared tunnel list

# 특정 터널 상세 정보
cloudflared tunnel info <TUNNEL_NAME>

```

예상 출력:

```

ID                                   NAME            CREATED              CONNECTIONS
xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx my-tunnel       2025-10-24T23:56:36Z 2xicn01, 2xicn06

```

### 2. 네트워크 라우트 확인

```powershell
cloudflared tunnel route ip show

```

예상 출력:

```

NETWORK         TUNNEL ID                            TUNNEL NAME
10.0.0.0/24     xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx my-tunnel

```

### 3. 서비스 상태 확인

```powershell
# Windows Service 확인
Get-Service cloudflared

# 프로세스 확인
Get-Process | Where-Object {$_.ProcessName -like "*cloudflared*"}

```

서비스가 **Running** 상태여야 합니다.

### 4. 설정 파일 확인

```powershell
# config.yml 위치 확인
Get-Content "C:\Users\$env:USERNAME\.cloudflared\config.yml"

```

예시 설정:

```yaml
tunnel: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
credentials-file: C:\Users\username\.cloudflared\xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx.json

warp-routing:
  enabled: true

ingress:
  - service: http_status:404

```

## 클라이언트 측 설정

이제 원격지(클라이언트)에서 WARP를 설정합니다.

### 1. WARP 설치

[Cloudflare WARP 다운로드](https://1.1.1.1/)에서 설치 파일을 다운로드하고 설치합니다.

### 2. 초기 등록 및 연결

PowerShell을 **관리자 권한**으로 실행합니다.

```powershell
# warp-cli 경로 설정
$warpCli = "C:\Program Files\Cloudflare\Cloudflare WARP\warp-cli.exe"

# 새 등록 생성
& $warpCli registration new

# WARP 연결
& $warpCli connect

# 상태 확인
& $warpCli status

```

출력 예시:

```

Status update: Connecting
Reason: Establishing connection to 162.159.198.2:443

```

### 3. Zero Trust 조직 로그인

**GUI 방식 (권장):**

1. 시스템 트레이에서 Cloudflare WARP 아이콘 클릭
2. **Settings** (⚙️) 클릭
3. **Preferences** → **Account** 선택
4. **Login with Cloudflare Zero Trust** 클릭
5. 조직 이름 입력 (예: `mycompany`)
6. 브라우저에서 인증 완료

### 4. 로그인 확인

```powershell
# 상태 확인
& $warpCli status

# 설정 확인
& $warpCli settings

```

출력에서 다음을 확인:

```

(user set)      Organization: mycompany
Status update: Connected

```

### 5. Split Tunnel 설정 확인

**Zero Trust 대시보드에서 설정:**

1. [Cloudflare Zero Trust 대시보드](https://one.dash.cloudflare.com/) 접속
2. **Settings** → **WARP Client** → **Device settings**
3. 프로필 선택 → **Split Tunnels** 클릭
4. **Manage** → Mode를 **Include IPs and domains** 선택
5. **Add destination** 클릭
6. 값 입력:
   - **Selector**: address
   - **Value**: `10.0.0.0/24` (프라이빗 네트워크 대역)
   - **Description**: private network
7. **Save destination** 클릭

**클라이언트에서 확인:**

```powershell
& $warpCli settings

```

출력에서 확인:

```

(network policy)        Include mode, with hosts/ips:
  10.0.0.0/24 (private network)

```

### 6. WARP 재연결

설정이 적용되도록 재연결합니다.

```powershell
& $warpCli disconnect
Start-Sleep -Seconds 2
& $warpCli connect

```

## 동작 확인

### 1. 네트워크 인터페이스 확인

```powershell
ipconfig

```

출력 예시:

```

알 수 없는 어댑터 CloudflareWARP:
   IPv4 주소 . . . . . . . . . : 100.96.0.1
   서브넷 마스크 . . . . . . . : 255.255.255.255

무선 LAN 어댑터 Wi-Fi:
   IPv4 주소 . . . . . . . . . : 192.168.50.100
   기본 게이트웨이 . . . . . . : 192.168.50.1

```

### 2. Split Tunnel 동작 확인

**테스트 1: Wi-Fi를 끄고 원격 접속 시도**

```powershell
# Wi-Fi 비활성화
netsh interface set interface "Wi-Fi" disabled

# 프라이빗 네트워크로 RDP 연결
mstsc /v:10.0.0.100

# Wi-Fi 다시 활성화
netsh interface set interface "Wi-Fi" enabled

```

Wi-Fi가 꺼진 상태에서도 RDP 연결이 성공하면 ✅ **터널을 통해 연결**되고 있는 것입니다.

**테스트 2: 인터넷 연결 확인**

```powershell
# 공인 IP 확인
curl ifconfig.me

```

자신의 실제 공인 IP가 출력되면 ✅ **일반 인터넷은 로컬 연결** 사용 중입니다.

**테스트 3: Traceroute 확인**

```powershell
# 프라이빗 네트워크로 traceroute
tracert -d -h 5 10.0.0.100

```

`100.96.0.1` (WARP 가상 IP)로 시작하면 터널을 통과하는 것입니다.

### 3. RDP 연결 테스트

```powershell
# 기본 RDP 포트로 연결
mstsc /v:10.0.0.100

# 사용자 지정 포트로 연결
mstsc /v:10.0.0.100:3389

```

## 트러블슈팅

### 문제 1: "Registration Missing" 오류

```powershell
# 기존 등록 삭제 후 재등록
& $warpCli registration delete
& $warpCli registration new
& $warpCli connect

```

### 문제 2: 터널 연결 실패

```powershell
# 상태 확인
& $warpCli status

# 로그 확인
& $warpCli debug

```

서버 측에서 확인:

```powershell
# cloudflared 로그 확인
cloudflared tunnel info <TUNNEL_NAME>

# 서비스 재시작
Restart-Service cloudflared

```

### 문제 3: Split Tunnel이 적용 안됨

**Zero Trust 대시보드 확인:**
1. 설정이 올바른 Device Profile에 적용되었는지 확인
2. 프로필이 사용자/디바이스에 할당되었는지 확인

**클라이언트에서 강제 동기화:**
```powershell
& $warpCli disconnect
& $warpCli registration delete
& $warpCli registration new

```

그 후 GUI에서 Zero Trust 재로그인

### 문제 4: Ping이 안됨

WARP는 ICMP(ping)를 차단할 수 있습니다. 이는 **정상 동작**입니다.

대신 실제 서비스(RDP, SSH, HTTP 등)로 연결을 테스트하세요:

```powershell
# RDP 포트 연결 테스트
Test-NetConnection -ComputerName 10.0.0.100 -Port 3389

```

### 문제 5: 속도가 느림

Split Tunnel 설정을 확인하세요:

```powershell
& $warpCli settings | Select-String -Pattern "mode"

```

**Include 모드**로 설정되어 있어야 합니다. Exclude 모드는 모든 트래픽을 터널로 보냅니다.

## 고급 설정

### 여러 네트워크 대역 추가

Zero Trust 대시보드에서:
- `10.0.0.0/24` (본사 네트워크)
- `172.16.0.0/16` (지사 네트워크)
- `internal.company.com` (도메인 기반)

모두 추가 가능합니다.

### 특정 도메인만 터널 통과

```

Selector: hostname
Value: internal.mycompany.com

```

### 자동 연결 설정

```powershell
# 자동 연결 활성화
& $warpCli set-mode warp

# Always On 설정 (GUI에서)
WARP Settings → Preferences → Connection → Always On

```

## CLI 명령어 참고

### 주요 명령어

```powershell
# 상태 확인
& $warpCli status

# 연결/해제
& $warpCli connect
& $warpCli disconnect

# 설정 확인
& $warpCli settings

# 등록 관리
& $warpCli registration new
& $warpCli registration delete

# 모드 변경
& $warpCli set-mode warp
& $warpCli set-mode doh

# 디버그 정보
& $warpCli debug

# 도움말
& $warpCli --help

```

## 보안 고려사항

### 장점
- ✅ Zero Trust Network Access (ZTNA) 구현
- ✅ 인증된 사용자만 접근 가능
- ✅ 퍼블릭 IP 노출 불필요
- ✅ 암호화된 터널 통신
- ✅ Cloudflare의 DDoS 보호

### 주의사항
- 🔒 Zero Trust 정책을 통해 접근 제어 설정 필수
- 🔒 디바이스 인증서 또는 WARP Connector 사용 권장
- 🔒 정기적인 액세스 로그 모니터링
- 🔒 최소 권한 원칙 적용

## 결론

Cloudflare WARP Split Tunnel을 사용하면:
- 원격지에서 프라이빗 네트워크에 안전하게 접속
- 일반 인터넷 트래픽은 속도 저하 없이 사용
- VPN 대비 간편한 설정과 관리
- Cloudflare의 글로벌 네트워크를 통한 빠른 연결

특히 재택근무나 출장 시 회사 내부 시스템에 접속해야 하는 경우 매우 유용합니다.

## 참고 자료

- [Cloudflare WARP 공식 문서](https://developers.cloudflare.com/cloudflare-one/connections/connect-devices/warp/)
- [Cloudflare Tunnel 가이드](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/)
- [Split Tunnels 설정](https://developers.cloudflare.com/cloudflare-one/connections/connect-devices/warp/configure-warp/route-traffic/split-tunnels/)
- [Zero Trust 시작하기](https://developers.cloudflare.com/cloudflare-one/setup/)

---

**작성일**: 2025년 11월 3일  
**최종 수정**: 2025년 11월 3일  
**테스트 환경**: Windows 11, Cloudflare WARP 2025.8.0, cloudflared 2025.8.0



