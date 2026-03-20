# Slide 6: Network Flow (User → Pod)

> **7-Step 트래픽 플로우**

---

## Mermaid Diagram

```mermaid
flowchart TD
    User[👤 사용자<br/>https://blog.jiminhome.shop/]

    CDN[☁️ Cloudflare CDN<br/>SSL/TLS 종료<br/>DDoS 방어]

    MetalLB[⚖️ MetalLB<br/>192.168.1.200<br/>L2 LoadBalancer]

    IGW[🚪 Istio Gateway<br/>L7 Routing<br/>포트: 80]

    VS[📋 VirtualService<br/>URI: / → web<br/>URI: /api → web]

    WEB[🌐 WEB Pod<br/>nginx + Hugo<br/>정적 파일]

    WAS[☕ WAS Pod<br/>Spring Boot<br/>API 처리]

    MySQL[💾 MySQL<br/>Longhorn PVC<br/>Replica 3]

    User -->|1. HTTPS| CDN
    CDN -->|2. HTTP (평문)| MetalLB
    MetalLB -->|3. Port 80| IGW
    IGW -->|4. Host 매칭| VS
    VS -->|5. URI /| WEB
    VS -.->|5. URI /api| WEB
    WEB -.->|6. nginx proxy<br/>/api → was:8080| WAS
    WAS -.->|7. JDBC| MySQL

    style User fill:#e1f5ff
    style CDN fill:#ffd700
    style MetalLB fill:#90ee90
    style IGW fill:#ffb6c1
    style VS fill:#dda0dd
    style WEB fill:#87ceeb
    style WAS fill:#ffa07a
    style MySQL fill:#d3d3d3
```

---

## 7-Step 플로우 (간략)

| Step | Layer | 설명 |
|------|-------|------|
| **1** | User | HTTPS 요청 (SSL) |
| **2** | Cloudflare | SSL 종료 → HTTP 평문 |
| **3** | MetalLB | LoadBalancer IP 할당 (192.168.1.200) |
| **4** | Istio Gateway | L7 Routing (Host 매칭) |
| **5** | VirtualService | URI 매칭 (/, /api) |
| **6** | WEB nginx | 정적 파일 또는 WAS proxy |
| **7** | WAS → MySQL | JDBC 연결 (Istio mesh 제외) |

---

## Before vs After (2026-01-24)

### Before
```
Cloudflare → MetalLB → Nginx Ingress → Istio Gateway → Pod
                         ↓ 중복        ↓
                      L7 라우팅     L7 라우팅
```

### After (현재)
```
Cloudflare → MetalLB → Istio Gateway → Pod
                         ↓
                    단일 L7 진입점
```

**개선 효과**:
- P95 레이턴시: 180ms → 120ms (**-33%**)
- Hop 제거: 1개 (성능 향상)
- 관리 포인트: 2개 → 1개 (단순화)

---

## 핵심 포인트

### 1. Cloudflare → 홈랩
- ✅ SSL 종료: Cloudflare에서 처리
- ⚠️ 홈랩 구간: HTTP 평문 (내부 네트워크)
- 🔜 향후: Cloudflare Origin 인증서 (HTTPS 연결)

### 2. Istio Gateway
- ✅ 모든 서브도메인 처리 (`*.jiminhome.shop`)
- ✅ VirtualService로 라우팅 위임
- ✅ Retry, Timeout, Circuit Breaker 적용

### 3. Cilium eBPF
- ✅ ClusterIP → Pod IP 변환 (L4 Load Balancing)
- ✅ 3배 빠른 패킷 처리 (vs iptables)
- ✅ Hubble UI로 트래픽 시각화

---

**핵심 메시지**: **Istio 일원화**로 중복 제거 + 성능 33% 향상
