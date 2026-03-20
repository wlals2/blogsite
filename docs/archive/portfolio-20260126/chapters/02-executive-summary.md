# Slide 2: Executive Summary

> **3분 발표용 핵심 요약**

---

## 프로젝트 개요

**Production-Grade Kubernetes 홈랩 인프라 구축**

- **목적**: 엔터프라이즈급 클라우드 네이티브 환경을 온프레미스에서 구현
- **기간**: 2024-11 ~ 2026-01 (58일 운영)
- **결과**: 99.9% 가동률, 60회 무중단 배포

---

## 핵심 성과 지표

### 가용성 & 안정성

| 지표 | 수치 |
|------|------|
| **Uptime** | **99.9%** (58일 중 5분 장애) |
| **배포 횟수** | **60회** (v1 → v60) |
| **배포 시간** | **2분** (GitHub → Production) |
| **장애 복구** | **2분** (RTO) |
| **데이터 손실** | **0초** (RPO) |

### 자동화 수준

| 영역 | 자동화율 |
|------|---------|
| **배포** | 100% (GitHub Actions + ArgoCD) |
| **복구** | 100% (Self-Healing + Auto-Failover) |
| **모니터링** | 100% (Prometheus + Grafana) |
| **보안 대응** | 80% (Falco + Talon) |

### 비용 효율

| 항목 | 홈랩 | AWS EKS | 절감액 |
|------|------|---------|--------|
| **월 비용** | $20 | $300 | **$280/월** |
| **연 비용** | $240 | $3,600 | **$3,360/년** |

---

## 기술 스택

```
┌─────────────────────────────────────┐
│     Kubernetes v1.31.13 (4-node)    │
├─────────────────────────────────────┤
│ Service Mesh │ Istio (mTLS)         │
│ CNI          │ Cilium (eBPF)        │
│ Storage      │ Longhorn (Replica 3) │
│ GitOps       │ ArgoCD + Rollouts    │
│ Monitoring   │ PLG Stack            │
│ Security     │ Falco + Talon        │
│ CDN          │ Cloudflare (Free)    │
└─────────────────────────────────────┘
```

---

## 아키텍처 개선 (2026-01-24)

### Before
```
Cloudflare → MetalLB → Nginx Ingress → Istio Gateway → Pod
                         ↓ 중복        ↓
```

### After
```
Cloudflare → MetalLB → Istio Gateway → Pod
                         ↓ 일원화
```

**개선 효과**:
- ✅ 레이턴시 **30% 감소**
- ✅ 아키텍처 단순화
- ✅ Istio 기능 완전 활용

---

## 핵심 학습

### 1. 기술 선택의 이유

| 기술 | 선택 이유 | 대안 |
|------|----------|------|
| **Istio** | 엔터프라이즈급 기능 | Linkerd (기능 제한) |
| **Cilium** | eBPF 3배 빠름 | Calico (iptables) |
| **Longhorn** | 간단한 Auto-Failover | Ceph (복잡) |

### 2. 실전 경험

**성공 사례**:
- Canary 배포로 v46 오류 사전 차단 (90% 사용자 무영향)
- Longhorn Auto-Failover로 MySQL 1분 30초 복구

**실패 사례**:
- Istio STRICT mTLS → PERMISSIVE 전환 필요
- Cilium NetworkPolicy 과도 적용 → 최소 권한 원칙 학습

---

## 다음 목표

### ⏳ 단기 (1-2개월)
1. Service Mesh mTLS 활성화 (DISABLE → ISTIO_MUTUAL)
2. Prometheus AlertManager 설정
3. Jaeger Distributed Tracing

### 🔜 장기 (6개월+)
1. Multi-Cluster Federation
2. Kubernetes Operator 개발
3. FinOps 최적화

---

**핵심 메시지**: 단순 구축이 아닌, **58일간 운영하며 99.9% 가동률을 지킨** Production 경험
