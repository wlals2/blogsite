# Technology Decision Matrix

> **기술 선택의 이유 (Why 중심)**

---

## 핵심 기술 선택 (5가지)

### 1. Service Mesh: Istio

| 항목 | Istio ✅ | Linkerd | Consul |
|------|---------|---------|--------|
| **기능** | 엔터프라이즈급 완전 | 제한적 | VM 중심 |
| **Retry/Timeout** | ✅ | ❌ | ⚠️ |
| **Circuit Breaker** | ✅ | ❌ | ✅ |
| **Canary 배포** | ✅ (Argo Rollouts) | ⚠️ | ❌ |
| **커뮤니티** | CNCF Graduated | CNCF Incubating | HashiCorp |

**선택 이유**: 실무 표준, Argo Rollouts 연동, 완전한 Observability

---

### 2. CNI: Cilium

| 항목 | Cilium ✅ | Calico | Flannel |
|------|-----------|--------|---------|
| **성능** | eBPF (3배 빠름) | iptables | 기본 |
| **NetworkPolicy** | L7 인식 | L3/L4만 | ❌ |
| **Observability** | Hubble UI | ❌ | ❌ |
| **Falco 연동** | ✅ | ⚠️ | ❌ |

**선택 이유**: eBPF 성능, Hubble 가시성, Falco IPS 통합

---

### 3. Storage: Longhorn

| 항목 | Longhorn ✅ | Ceph | NFS |
|------|-------------|------|-----|
| **구성 복잡도** | 간단 (Helm 5분) | 매우 복잡 | 매우 간단 |
| **Auto-Failover** | ✅ (2분 RTO) | ✅ | ❌ |
| **Replica** | 3개 | 3개 | 1개 (SPOF) |
| **데이터 손실** | RPO 0초 | RPO 0초 | RPO ? |

**선택 이유**: 간단한 구성, Auto-Failover, 웹 UI

---

### 4. GitOps: ArgoCD

| 항목 | ArgoCD ✅ | Flux | Jenkins |
|------|-----------|------|---------|
| **웹 UI** | ✅ | ❌ | ✅ |
| **Self-Healing** | ✅ | ✅ | ❌ |
| **Argo Rollouts** | ✅ (통합) | ⚠️ | ❌ |
| **Rollback** | 1-Click | CLI | 파이프라인 |

**선택 이유**: 웹 UI 시각화, Self-Healing, Argo Rollouts 완벽 통합

---

### 5. CDN: Cloudflare

| 항목 | Cloudflare ✅ | AWS CloudFront | Fastly |
|------|---------------|----------------|--------|
| **비용** | **Free** | $0.085/GB | 유료 |
| **DDoS 방어** | ✅ | ⚠️ | ✅ |
| **SSL** | Universal SSL | ACM | 유료 |
| **API 캐시 삭제** | ✅ | ✅ | ✅ |

**선택 이유**: 무료, DDoS 방어, API 연동

---

## 의사결정 원칙

### 1. 비용 최우선
- ✅ 오픈소스 우선 (Istio, Cilium, Longhorn)
- ✅ SaaS 무료 플랜 (Cloudflare)
- ❌ 유료 솔루션 제외 (Datadog, Sysdig)

### 2. 학습 가치
- ✅ 실무 표준 기술 (Istio, ArgoCD)
- ✅ 최신 트렌드 (eBPF, GitOps)
- ✅ CNCF 프로젝트 우선

### 3. 운영 복잡도
- ✅ 간단한 구성 (Longhorn > Ceph)
- ✅ 웹 UI 제공 (ArgoCD, Longhorn, Grafana)
- ✅ Self-Healing 기능

### 4. 트레이드오프 수용
| 선택 | 단점 수용 | 이유 |
|------|----------|------|
| **Istio** | 높은 리소스 사용 | 기능 완전성 우선 |
| **Cilium** | 디버깅 어려움 | 성능 3배 향상 |
| **Longhorn** | 네트워크 오버헤드 | Auto-Failover 보장 |

---

**핵심 메시지**: **"왜?"에 대한 명확한 답변** - 대안 분석 + 트레이드오프 이해
