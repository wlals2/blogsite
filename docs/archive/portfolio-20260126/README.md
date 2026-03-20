# 포트폴리오 슬라이드 제작 가이드

> **Production-Grade Kubernetes 홈랩** (Istio Service Mesh & GitOps)
>
> **기간**: 2024-11 ~ 2026-01 (58일 운영)
> **핵심 성과**: 99.9% Uptime, 60회 무중단 배포, $280/월 절감

---

## 🚀 빠른 시작 (이미지 생성)

### 1단계: Mermaid 이미지 생성

**📁 위치**: `mermaid-codes/` 디렉터리에 4개 파일 준비됨

```
mermaid-codes/
├── 01-network-flow.mmd       # Network Flow (7-Step)
├── 02-cicd-pipeline.mmd       # CI/CD Pipeline (9-Step)
├── 03-ha-failover.mmd         # HA Failover
└── 04-security-layers.mmd     # Security Layers (5-Layer)
```

### 2단계: PNG 변환 (추천 방법)

**🌐 Mermaid Live Editor** (가장 간단)

1. https://mermaid.live/ 접속
2. `.mmd` 파일 내용 복사
3. 우측 상단 "Actions" → "PNG" 클릭
4. `images/` 디렉터리에 저장

**예시**:
```bash
# 01-network-flow.mmd → images/network-flow.png
# 02-cicd-pipeline.mmd → images/cicd-pipeline.png
# 03-ha-failover.mmd → images/ha-failover.png
# 04-security-layers.mmd → images/security-layers.png
```

---

## 📁 파일 구조

```
docs/portfolio/
│
├── README.md                  # 이 파일 (가이드)
├── USAGE.md                   # 상세 사용법
│
├── chapters/                  # 슬라이드 내용
│   ├── 02-executive-summary.md    # Slide 2 (핵심 성과)
│   └── 13-troubleshooting.md      # Slide 13 (이슈 해결)
│
├── tables/                    # 비교 표
│   ├── technology-decisions.md    # 기술 선택 이유
│   └── metrics.md                 # 성과 지표
│
├── diagrams/                  # 다이어그램 설명 (참고용)
│   ├── network-flow.md
│   ├── cicd-pipeline.md
│   ├── ha-failover.md
│   └── security-layers.md
│
├── mermaid-codes/             # ⭐ Mermaid 코드 (PNG 변환용)
│   ├── 01-network-flow.mmd
│   ├── 02-cicd-pipeline.mmd
│   ├── 03-ha-failover.mmd
│   └── 04-security-layers.mmd
│
└── images/                    # 생성된 PNG (슬라이드 삽입용)
    ├── network-flow.png
    ├── cicd-pipeline.png
    ├── ha-failover.png
    └── security-layers.png
```

---

## 📊 슬라이드 구성 (12-15장)

### Part 1: Introduction (3장)

**Slide 1: Cover**
- 제목: Production-Grade Kubernetes 홈랩
- 부제: Istio Service Mesh & GitOps
- 기간: 2024-11 ~ 2026-01 (58일 운영)

**Slide 2: Executive Summary**
- 📄 파일: `chapters/02-executive-summary.md`
- 내용: 99.9% Uptime, 60회 배포, $280/월 절감

**Slide 3: Background**
- 문제: AWS 비용, 실전 경험 부족
- 목표: 엔터프라이즈급 환경 구축

---

### Part 2: Architecture (5장)

**Slide 4: Full Architecture**
- 🖼️ 이미지: `content/image/localk8s 아키텍처.png` (기존)
- 전체 시스템 다이어그램

**Slide 5: Infrastructure**
- 4-Node Cluster
- 기술 스택: Istio, Cilium, Longhorn, ArgoCD

**Slide 6: Network Flow**
- 📄 파일: `diagrams/network-flow.md` (설명)
- 🖼️ 이미지: `images/network-flow.png` (생성 필요)
- 내용: User → CDN → MetalLB → Istio Gateway → Pod (7-Step)

**Slide 7: Service Mesh**
- 📄 파일: `tables/technology-decisions.md` (Istio vs Cilium)
- 내용: mTLS, NetworkPolicy, 역할 구분

**Slide 8: Application**
- WEB (Hugo + nginx)
- WAS (Spring Boot)
- MySQL (Longhorn Replica 3)

---

### Part 3: Operations (4장)

**Slide 9: CI/CD Pipeline**
- 📄 파일: `diagrams/cicd-pipeline.md` (설명)
- 🖼️ 이미지: `images/cicd-pipeline.png` (생성 필요)
- 내용: GitOps 9-Step, Canary 배포

**Slide 10: Observability**
- PLG Stack (Prometheus, Loki, Grafana)
- 📄 참고: `tables/metrics.md`

**Slide 11: Security**
- 📄 파일: `diagrams/security-layers.md` (설명)
- 🖼️ 이미지: `images/security-layers.png` (생성 필요)
- 내용: 5-Layer 보안 (Cloudflare → Istio → Cilium → Falco → SecurityContext)

**Slide 12: High Availability**
- 📄 파일: `diagrams/ha-failover.md` (설명)
- 🖼️ 이미지: `images/ha-failover.png` (생성 필요)
- 내용: Node 장애 시 Failover (5분 복구)

---

### Part 4: Insights (3장)

**Slide 13: Troubleshooting**
- 📄 파일: `chapters/13-troubleshooting.md`
- 내용: 4가지 이슈 & 해결 (Istio mTLS, Longhorn Failover 등)

**Slide 14: Roadmap**
- 단기: mTLS STRICT, Jaeger Tracing
- 장기: Multi-Cluster, Kubernetes Operator

**Slide 15: Conclusion**
- 핵심 성과 요약
- 기술적 성장
- 마무리 메시지

---

## 🎯 핵심 성과 (숫자로 증명)

| 지표 | 수치 | 설명 |
|------|------|------|
| **Uptime** | **99.9%** | 58일 중 5분 장애 |
| **배포 횟수** | **60회** | v1 → v60 |
| **배포 시간** | **2분** | GitHub → Production |
| **장애 복구** | **5분** | RTO (Auto-Failover) |
| **데이터 손실** | **0초** | RPO (Replica 3) |
| **레이턴시 개선** | **30% ↓** | Istio Gateway 일원화 |
| **비용 절감** | **$280/월** | vs AWS EKS |

---

## ✅ 체크리스트

### 슬라이드 제작 전

- [ ] **Mermaid 이미지 생성** (4개 PNG 파일)
  - [ ] `images/network-flow.png`
  - [ ] `images/cicd-pipeline.png`
  - [ ] `images/ha-failover.png`
  - [ ] `images/security-layers.png`

- [ ] **기존 이미지 준비**
  - [ ] `content/image/localk8s 아키텍처.png`

- [ ] **내용 확인**
  - [ ] `chapters/02-executive-summary.md`
  - [ ] `chapters/13-troubleshooting.md`
  - [ ] `tables/technology-decisions.md`
  - [ ] `tables/metrics.md`

### 슬라이드 제작 중

- [ ] 각 슬라이드에 제목 + 1문장 요약
- [ ] 숫자로 증명 (99.9%, 2분, 30% 등)
- [ ] Before/After 비교 (개선 효과)
- [ ] 핵심 메시지 강조

### 슬라이드 완료 후

- [ ] 3분 발표 연습 (Executive Summary)
- [ ] 10분 발표 연습 (전체)
- [ ] 질문 예상 (Why Istio? Troubleshooting 사례?)

---

## 📝 추가 자료

**상세 가이드**: [`USAGE.md`](USAGE.md) - 슬라이드 제작 전체 프로세스

**기술 문서** (배경지식):
- Istio: `../istio/COMPLETE-ISTIO-ARCHITECTURE.md`
- Cilium: `../cilium/LOCAL-K8S-CILIUM-ARCHITECTURE.md`
- CI/CD: `../CICD/CICD-PIPELINE.md`
- Monitoring: `../monitoring/README.md`

---

**작성일**: 2026-01-26
**용도**: 포트폴리오 슬라이드 (12-15장)
**다음 단계**: Mermaid 이미지 4개 생성 → 슬라이드 작성
