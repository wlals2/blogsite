---
title: "Cloudflare WARP vs Tunnel: NAT 개념으로 이해하는 홈랩 보안 설계"
date: 2026-02-23T14:00:00+09:00
draft: false
categories: ["study", "Kubernetes"]
  - "study"
  - "Security"
tags: ["cloudflare", "warp", "tunnel", "nat", "dnat", "snat", "cilium", "firewall", "wireguard", "proxy", "homelab"]
## 배경

홈랩 Kubernetes 클러스터를 운영하면서 두 가지 요구사항이 충돌했다.

1. 외부에서 SSH로 노드에 접근해야 한다
2. SSH 브루트포스 공격은 차단해야 한다

단순히 22번 포트를 열면 자동화된 공격이 즉시 시작된다. 반대로 포트를 막으면 외부 접근이 불가능하다. Cloudflare WARP와 Cilium Host Firewall 조합으로 이 문제를 해결하면서, WARP와 Tunnel의 차이를 NAT 개념으로 이해하게 됐다.

---

## Cloudflare를 두 가지로 사용한다

홈랩에서 Cloudflare 서비스를 두 가지 목적으로 사용하고 있다.

```
역할 1 → Cloudflare Tunnel: 내부 서비스를 외부에 노출
역할 2 → Cloudflare WARP:  외부에서 내부 네트워크에 접근
```

이 두 가지는 트래픽 방향이 완전히 반대다.

---

## 개념 1: Tunnel은 왜 Outbound인가

홈랩은 공유기(NAT) 뒤에 있다.

```
인터넷 ←→ 공유기(NAT) ←→ 192.168.1.0/24
```

외부에서 블로그에 접근하려면 보통 공유기에서 포트포워딩을 설정해야 한다. 하지만 이는 공인 IP 고정, DDNS 설정, 방화벽 개방 등 복잡한 작업이 필요하다.

Cloudflare Tunnel은 이 문제를 다르게 해결한다.

```
일반 방식:  외부 → (공유기 NAT 차단) → 홈랩   ❌
Tunnel:    홈랩 → Cloudflare ← 외부           ✅
```

`cloudflared` 프로세스가 먼저 Cloudflare 서버로 **아웃바운드** 연결을 맺는다. NAT는 아웃바운드 연결을 기본적으로 허용하기 때문에 포트포워딩이 필요 없다. 이 연결 위로 외부 트래픽이 역방향으로 흘러온다. 이 패턴을 **Reverse Tunnel**이라 부른다.

실제 cloudflared 설정:

```yaml
# /etc/cloudflared/config.yml
tunnel: 65759494-dae6-4287-b92d-02a918b34722
credentials-file: /etc/cloudflared/65759494-...json

ingress:
  - hostname: blog.jiminhome.shop
    service: http://192.168.1.200:80  # MetalLB VIP
  - service: http_status:404
```

`localhost`가 아닌 MetalLB VIP를 가리키는 것이 핵심이다. 이렇게 하면 Istio Gateway의 VirtualService 라우팅을 그대로 활용할 수 있다.

---

## 개념 2: WARP는 왜 Inbound인가

WARP의 목적은 다르다. 내부 서비스를 노출하는 것이 아니라, 외부 기기가 내부 네트워크에 접근할 수 있게 하는 것이다. VPN의 역할이다.

```
내 노트북 (WARP 앱 활성화)
  ↓ WireGuard 암호화 터널
Cloudflare WARP 네트워크
  ↓
WARP Connector (홈랩 내 설치, 가상 NIC: 100.64.x.x)
  ↓ 내부 네트워크 전체 접근 가능
노드 SSH (192.168.1.187:22)
MetalLB (192.168.1.200) → 모니터링 도구
```

| | Tunnel | WARP |
|--|--------|------|
| **방향** | Outbound (내부 → 외부) | Inbound (외부 → 내부) |
| **목적** | 서비스 노출 | 네트워크 접근 |
| **라우팅** | L7 (hostname 기반) | L3 (IP 기반) |
| **범위** | 특정 URL만 | 내부 IP 전체 |

---

## 개념 3: Tunnel은 DNAT가 아니라 Proxy다

처음에 Tunnel이 DNAT(Destination NAT, 포트포워딩)와 같다고 생각했다.

```
DNAT (포트포워딩):
  공인 IP:80 → 192.168.1.200:80
  L3/L4 레벨, IP 헤더만 수정
  동일한 TCP 연결 유지
  클라이언트 원본 IP 보존

Cloudflare Tunnel:
  클라이언트 → [연결 1] → Cloudflare → [연결 2] → cloudflared → MetalLB
  L7 레벨, HTTP 요청 파싱
  TCP 연결을 끊고 새 연결 생성
  원본 IP가 Cloudflare IP로 대체됨
```

결정적 차이는 **TCP 연결의 수**다. DNAT는 연결이 하나로 이어지지만, Tunnel은 두 개의 독립된 연결로 분리된다. 이 때문에 서버에서 클라이언트 원본 IP를 확인하려면 별도 설정이 필요하다.

```nginx
# nginx에서 Cloudflare 원본 IP 복원
set_real_ip_from 103.21.244.0/22;   # Cloudflare IP 대역
real_ip_header CF-Connecting-IP;
```

DNAT였다면 이 설정이 필요 없다. TCP 레벨에서 원본 IP가 그대로 유지되기 때문이다.

---

## 개념 4: WARP는 WireGuard 터널이다

Linux iptables 기준 NAT:

```
SNAT (Source NAT, Masquerade):
  내부 → 외부 (outbound)
  소스 IP 변환: 192.168.1.100 → 공인 IP

DNAT (Destination NAT, 포트포워딩):
  외부 → 내부 (inbound)
  목적지 IP 변환: 공인 IP:22 → 192.168.1.187:22
```

WARP는 이 SNAT/DNAT 프레임으로 정확히 분류되지 않는다. WireGuard 기반 **터널링 프로토콜**이기 때문이다.

```
WARP 클라이언트에서: ssh user@192.168.1.187

패킷: src=클라이언트_공인_IP, dst=192.168.1.187:22
  ↓ WireGuard 암호화 캡슐화
  ↓ Cloudflare 네트워크 경유
  ↓ WARP Connector에서 언래핑
  ↓ 내부 네트워크로 라우팅
```

노드 sshd가 받은 패킷 소스 IP: `100.64.x.x` (WARP Connector 가상 NIC)

NAT 테이블에서 IP 헤더를 수정하는 것이 아니라, 패킷을 암호화하여 터널로 전달한 뒤 목적지 근처에서 언래핑하는 방식이다. 전통적 NAT보다는 VPN 게이트웨이에 가깝다.

---

## Cilium Host Firewall 설계

이 이해를 바탕으로 SSH 브루트포스 방어 정책을 설계했다.

**핵심 원칙**: WARP를 통해 SSH가 들어오면 소스 IP가 `100.64.0.0/10`으로 보인다. 이 대역만 허용하고 나머지를 차단하면 된다.

Cilium에는 `ingress`(허용)와 `ingressDeny`(차단) 두 가지 규칙이 있다.

```
ingress    → 허용 (높은 우선순위)
ingressDeny → 차단 (낮은 우선순위)
```

레거시 방화벽의 `permit → deny` 순서와 동일하다. 허용 규칙에 먼저 매칭되면 차단 규칙은 평가하지 않는다.

주의: `ingress` 규칙만 사용하면 명시된 포트 외 모든 포트가 차단된다(allowlist). HTTP/HTTPS까지 막힌다. SSH만 선택적으로 차단하려면 `ingressDeny`를 써야 한다.

```yaml
# k8s-manifests/configs/security/cilium-host-firewall.yaml
apiVersion: cilium.io/v2
kind: CiliumClusterwideNetworkPolicy
metadata:
  name: host-firewall-ssh
spec:
  nodeSelector:
    matchLabels: {}

  ingress:
    # Why: 홈랩 내부 네트워크 직접 SSH 허용
    - fromCIDR:
        - 192.168.1.0/24
      toPorts:
        - ports:
            - port: "22"
              protocol: TCP
    # Why: WARP Connector 가상 NIC 대역 허용 (WireGuard 터널 언래핑 후 소스 IP)
    - fromCIDR:
        - 100.64.0.0/10
      toPorts:
        - ports:
            - port: "22"
              protocol: TCP

  # Why: 위 허용 대역 외 전체 SSH 차단 (브루트포스 방어)
  # ingressDeny는 ingress보다 낮은 우선순위이므로 허용 규칙이 먼저 적용됨
  ingressDeny:
    - fromCIDR:
        - 0.0.0.0/0
      toPorts:
        - ports:
            - port: "22"
              protocol: TCP
```

---

## 트래픽 경로별 정책 검증

```
접근 경로                    소스 IP           결과
─────────────────────────────────────────────────────
홈랩 내부 SSH               192.168.1.x       ingress 허용 ✅
WARP 외부 SSH               100.64.x.x        ingress 허용 ✅
외부 브루트포스              공인 IP           ingressDeny 차단 ✅
웹 트래픽 (80/443)          모든 IP           규칙 없음 → 통과 ✅
Cloudflare Tunnel (웹)      outbound 연결      inbound 아님, 무관 ✅
```

HTTP/HTTPS는 Tunnel의 outbound 연결로 처리되므로 Host Firewall의 ingress 규칙과 무관하다.

---

## 성과

| 항목 | Before | After |
|------|--------|-------|
| 외부 SSH 시도 차단 | 0% (포트 개방) | 100% (WARP 제외 전체 차단) |
| 외부 접근 방법 | 없음 | WARP 클라이언트 활성화 후 접속 |
| HTTP/HTTPS 영향 | - | 없음 (정책 분리) |

---

## 전체 개념 흐름 정리

### 1. Cloudflare 두 서비스의 방향

| | Tunnel | WARP |
|--|--------|------|
| **방향** | Outbound (내부 → Cloudflare로 먼저 연결) | Inbound (외부 기기 → 내부 네트워크) |
| **이유** | NAT 뒤에서 포트 개방 없이 서비스 노출 | VPN 대체, 내부 네트워크 전체 접근 |
| **레이어** | L7 (hostname → service) | L3 (IP 기반) |
| **범위** | 특정 URL만 | 내부 IP 전체 |

### 2. Linux NAT vs Cloudflare 비교

| | Linux SNAT | Linux DNAT | Cloudflare Tunnel | Cloudflare WARP |
|--|-----------|-----------|-------------------|-----------------|
| **방향** | outbound | inbound | outbound (proxy) | inbound (tunnel) |
| **동작** | 소스 IP 변환 | 목적지 IP 변환 | 연결 종료 후 재생성 | WireGuard 캡슐화 |
| **레이어** | L3/L4 | L3/L4 | L7 | L3 |
| **TCP 연결** | 유지 | 유지 | 두 개로 분리 | 터널 경유 |
| **원본 IP** | 소스 변경 | 목적지 변경 | 손실 (X-Forwarded-For) | WARP Connector IP로 대체 |

### 3. DNAT vs Proxy의 결정적 차이

```
DNAT (포트포워딩):
  클라이언트 ──────────────────────────── 서버
              단일 TCP 연결
              IP 헤더만 수정
              원본 IP 그대로 → 서버에서 확인 가능

L7 Proxy (Cloudflare Tunnel):
  클라이언트 ──── Cloudflare ──── cloudflared ──── 서버
             연결 1          연결 2
             두 개의 독립된 TCP 연결
             원본 IP 손실 → X-Forwarded-For 헤더로 전달
```

DNAT였다면 `nginx`에서 `real_ip_header` 설정이 필요 없다. Proxy이기 때문에 별도 설정이 필요하다.

### 4. Cilium ingress vs ingressDeny 우선순위

레거시 방화벽의 `permit → deny` 순서와 동일한 whitelist 방식:

```
ingress (허용, 높은 우선순위)
  → 매칭되면 → 허용 (ingressDeny 평가하지 않음)
  → 매칭 안 되면 → ingressDeny 평가

ingressDeny (차단, 낮은 우선순위)
  → ingress에서 허용되지 않은 트래픽만 여기서 평가
```

`ingressDeny`에 `0.0.0.0/0`을 넣어도 `192.168.1.0/24`가 포함되지만, `ingress` 허용 규칙이 먼저 평가되므로 내부 SSH는 차단되지 않는다.

### 5. WARP Connector → 100.64.0.0/10 허용 이유

```
외부 기기 (공인 IP: 1.2.3.4)
  ↓ WireGuard 암호화
WARP Connector (가상 NIC: 100.64.5.10)
  ↓ 내부 네트워크로 라우팅
노드 sshd → 소스 IP로 100.64.5.10 수신
```

WireGuard는 NAT가 아닌 터널링이다. 실제 클라이언트 공인 IP(1.2.3.4)가 아닌 WARP Connector 가상 NIC IP(100.64.x.x)가 소스로 보인다. 따라서 Cilium 정책에서 `100.64.0.0/10`을 허용해야 WARP SSH가 동작한다.

---

## 전체 학습 체크리스트

| 개념 | 내용 |
|------|------|
| WARP = Inbound, Tunnel = Outbound | 방향 기준, Tunnel은 outbound 연결로 NAT 우회 |
| SNAT = outbound, DNAT = inbound | Linux iptables 기준 (SNAT=masquerade, DNAT=포트포워딩) |
| WARP = WireGuard 터널 (NAT 아님) | L3 캡슐화, SNAT/DNAT 프레임으로 분류 불가 |
| Tunnel = L7 Proxy (새 연결 생성) | TCP 연결 두 개로 분리, DNAT와 다름 |
| DNAT = 원본 IP 유지 | L3/L4 헤더 수정, TCP 연결 이어짐 |
| Proxy = 원본 IP 손실 → X-Forwarded-For | nginx `real_ip_header` 설정 필요 |
| Cilium ingress > ingressDeny | whitelist 우선, 레거시 방화벽 permit→deny 동일 |
| WARP Connector → 100.64.0.0/10 허용 | WireGuard 터널 언래핑 후 가상 NIC IP가 소스 |
