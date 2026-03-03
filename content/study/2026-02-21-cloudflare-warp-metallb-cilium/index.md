---
title: "Cloudflare WARP + MetalLB + Cilium Host Firewall: 홈랩 네트워크 보안 설계"
date: 2026-02-21T18:00:00+09:00
draft: false
categories: ["study", "Security"]
tags: ["cloudflare", "warp", "metallb", "cilium", "firewall", "ssh", "homelab", "kubernetes"]
series: ["홈랩 보안"]
cover:
  image: "cover.jpg"
  alt: "Cloudflare WARP + MetalLB + Cilium Host Firewall: 홈랩 네트워크 보안 설계"
  relative: true
---

## 배경

홈랩 Kubernetes 클러스터를 운영하다 보면 두 가지 상충하는 요구사항이 생긴다.

1. **외부에서 안전하게 접근**하고 싶다 (SSH, 모니터링 도구)
2. **외부 공격은 차단**하고 싶다 (SSH 브루트포스)

공인 IP를 직접 노출하면 1번은 해결되지만 2번이 문제가 된다. 반대로 모든 포트를 막으면 안전하지만 원격 접근이 불가능하다.

이 글에서는 **Cloudflare WARP Connector + MetalLB + Cilium Host Firewall** 조합으로 이 문제를 해결한 방법을 설명한다.

---

## 전체 아키텍처

```
외부 사용자 (웹 브라우저)
  ↓ HTTPS
Cloudflare CDN (DDoS 방어, SSL 종료)
  ↓ Cloudflare Tunnel (outbound)
cloudflared 데몬 (k8s-cp: 192.168.1.187)
  ↓ HTTP
MetalLB VIP (192.168.1.200)
  ↓
Istio Gateway → VirtualService → Pods

---

외부 기기 (개발 노트북, 스마트폰)
  ↓ Cloudflare WARP 클라이언트
Cloudflare WARP Network
  ↓
WARP Connector (가상 NIC: 100.64.x.x)
  ↓ 내부 네트워크 직접 접근
노드 SSH (192.168.1.187 등)
MetalLB (192.168.1.200) → 모니터링 도구
```

두 가지 Cloudflare 서비스가 서로 다른 역할을 담당한다.

---

## Cloudflare Tunnel vs WARP: 역할 구분

### Cloudflare Tunnel (웹 서비스 노출)

`cloudflared` 데몬이 Cloudflare 네트워크로 **아웃바운드** 연결을 맺는다. 인바운드 포트 개방이 불필요하다.

```yaml
# /etc/cloudflared/config.yml
tunnel: 65759494-dae6-4287-b92d-02a918b34722
credentials-file: /etc/cloudflared/65759494-...json

ingress:
  - hostname: blog.jiminhome.shop
    service: http://192.168.1.200:80   # MetalLB VIP
  - service: http_status:404
```

핵심: `localhost`가 아닌 MetalLB VIP `192.168.1.200`을 가리킨다. 이렇게 하면 Istio Gateway의 모든 라우팅 기능을 그대로 활용할 수 있다.

**동작 흐름:**
```
blog.jiminhome.shop 접속
  → Cloudflare가 Tunnel로 요청 전달
  → cloudflared가 192.168.1.200:80으로 전달
  → MetalLB → Istio Gateway → web-service Pod
```

**장점:**
- 공인 IP 노출 없음
- 포트포워딩 불필요
- Cloudflare DDoS 방어, SSL 자동 처리

### Cloudflare WARP (내부 네트워크 접근)

WARP Connector는 가상 NIC를 생성하고 CGNAT 대역(`100.64.0.0/10`) IP를 할당받는다. WARP 클라이언트가 연결되면 내부 네트워크(192.168.1.x)에 직접 접근할 수 있게 된다.

**동작 흐름:**
```
외부 기기에서 WARP 앱 활성화
  → Cloudflare WARP 네트워크 연결
  → WARP Connector (100.64.x.x) 경유
  → 192.168.1.187:22 SSH 접속 가능
  → 192.168.1.200 (MetalLB) 접속 가능
    → http://monitoring.jiminhome.shop (Grafana)
    → http://argocd.jiminhome.shop (ArgoCD)
```

**장점:**
- SSH 키 + WARP 이중 인증 효과
- 내부 모니터링 도구에 안전하게 접근
- 공인 IP 노출 없음

---

## Cilium Host Firewall 설계

### 문제: SSH 브루트포스

SSH 포트(22)를 노출하면 자동화된 브루트포스 공격이 끊임없이 들어온다.

```
# 실제 /var/log/auth.log 예시
Failed password for root from 185.x.x.x port 54321
Failed password for admin from 91.x.x.x port 12345
Failed password for ubuntu from 45.x.x.x port 23456
```

Fail2ban으로도 대응할 수 있지만, 패킷 자체를 커널 레벨에서 차단하는 것이 더 효율적이다.

### 잘못된 접근: ingress 규칙만 사용

처음에 이런 정책을 작성했다.

```yaml
# ❌ 잘못된 설계
spec:
  ingress:
    - fromCIDR:
        - 192.168.1.0/24
      toPorts:
        - ports:
            - port: "22"
              protocol: TCP
```

**문제:** Cilium에서 `ingress` 규칙이 하나라도 있으면, **명시적으로 허용되지 않은 모든 트래픽이 차단**된다. SSH(22)만 허용 규칙이 있으므로, HTTP(80)/HTTPS(443)도 모두 차단된다. 결과: 블로그 502 Bad Gateway.

### 올바른 접근: ingressDeny 사용

```yaml
# ✅ 올바른 설계
spec:
  # SSH 허용 (화이트리스트)
  ingress:
    - fromCIDR:
        - 192.168.1.0/24    # 홈랩 내부 직접 접속
      toPorts:
        - ports:
            - port: "22"
              protocol: TCP
    - fromCIDR:
        - 100.64.0.0/10     # WARP Connector CGNAT 대역
      toPorts:
        - ports:
            - port: "22"
              protocol: TCP

  # SSH 차단 (블랙리스트)
  ingressDeny:
    - fromCIDR:
        - 0.0.0.0/0         # 위에서 허용된 것 제외한 모든 외부
      toPorts:
        - ports:
            - port: "22"
              protocol: TCP
```

**핵심 원리:**

| 규칙 | 우선순위 | 영향 범위 |
|------|---------|---------|
| `ingress` | 높음 | 명시된 포트만 허용 (다른 포트는 기본 통과) |
| `ingressDeny` | 낮음 | 명시된 포트 차단 (ingress 허용이 우선) |

`ingressDeny`는 SSH(22)만 대상으로 하므로 HTTP(80)/HTTPS(443)는 영향받지 않는다.

### 소스 IP 매핑

WARP Connector를 통한 SSH 연결 시 노드에서 보이는 소스 IP:

```
외부 기기 (공인 IP)
  → WARP 네트워크
  → WARP Connector (100.64.x.x 가상 NIC)
  → 노드 sshd가 수신하는 소스 IP: 100.64.x.x
```

따라서 `100.64.0.0/10` 대역을 허용하면 WARP를 통한 모든 SSH 접속을 허용할 수 있다.

---

## 최종 정책 검증

| 접근 경로 | 소스 IP | 정책 결과 |
|----------|---------|---------|
| 홈랩 내부 SSH | `192.168.1.x` | ingress allow ✅ |
| WARP 외부 SSH | `100.64.x.x` | ingress allow ✅ |
| 외부 브루트포스 | 기타 공인 IP | ingressDeny 차단 ✅ |
| 웹 트래픽 (80/443) | 모든 IP | 규칙 없음 → 통과 ✅ |
| Cloudflare Tunnel | 127.x/192.168.x | 규칙 없음 → 통과 ✅ |

---

## 최종 구성 파일

```yaml
# k8s-manifests/configs/security/cilium-host-firewall.yaml
apiVersion: cilium.io/v2
kind: CiliumClusterwideNetworkPolicy
metadata:
  name: host-firewall-ssh
spec:
  nodeSelector:
    matchLabels: {}   # 모든 노드 적용

  ingress:
    # Why: 홈랩 내부 네트워크 SSH 허용
    - fromCIDR:
        - 192.168.1.0/24
      toPorts:
        - ports:
            - port: "22"
              protocol: TCP

    # Why: Cloudflare WARP Connector 가상 NIC 대역 허용
    # WARP 클라이언트 → Cloudflare → WARP Connector(100.64.0.0/10) → sshd
    - fromCIDR:
        - 100.64.0.0/10
      toPorts:
        - ports:
            - port: "22"
              protocol: TCP

  # Why: 위 허용 대상 외 모든 외부에서 SSH 차단 (Brute Force 방어)
  # ingressDeny는 ingress보다 낮은 우선순위 → 허용 규칙이 먼저 적용됨
  ingressDeny:
    - fromCIDR:
        - 0.0.0.0/0
      toPorts:
        - ports:
            - port: "22"
              protocol: TCP
```

---

## 핵심 교훈

### 1. Cilium ingress vs ingressDeny 차이

`ingress` 규칙이 있으면 허용 목록 방식(allowlist)으로 동작한다. SSH만 허용 규칙을 추가하면 HTTP/HTTPS까지 막힌다.

포트별 선택적 차단이 필요하면 `ingressDeny`를 사용해야 한다.

### 2. Cloudflare Tunnel의 origin 설정

`localhost:443`이 아닌 MetalLB VIP(`192.168.1.200:80`)를 origin으로 설정하면 Istio Gateway의 VirtualService 라우팅을 그대로 활용할 수 있다. 서비스가 늘어나도 cloudflared 설정은 변경하지 않아도 된다.

### 3. WARP CGNAT 대역

Cloudflare WARP Connector의 가상 NIC는 IANA CGNAT 대역(`100.64.0.0/10`)을 사용한다. 이 대역은 공인 IP도 사설 IP도 아닌 특수 대역으로, 방화벽 규칙에서 정확히 명시해야 한다.

---

## 참고 자료

- [Cloudflare Tunnel 문서](https://developers.cloudflare.com/cloudflare-one/connections/connect-apps/)
- [Cloudflare WARP Connector](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/private-net/warp-connector/)
- [Cilium Host Firewall](https://docs.cilium.io/en/stable/security/host-firewall/)
- [CGNAT RFC 6598 (100.64.0.0/10)](https://datatracker.ietf.org/doc/html/rfc6598)
