---
title: "About Me"
date: 2026-02-02
layout: "about"
url: "/about/"
summary: "Homelab Kubernetes Operator & Cloud Engineer"

# 메트릭 배지 데이터
metrics:
  - value: 67
    suffix: "% 단축"
    label: "배포 시간"
    description: "(30분 → 10분)"
  - value: 99.9
    suffix: "%"
    label: "가용성"
    description: "달성"
  - value: 54
    suffix: "일"
    label: "무중단 운영"
    description: "(Homelab)"

# 타임라인 데이터
timelines:
  - date: "2025.11 ~ 현재"
    title: "AWS EKS + Multi-Cloud DR"
    description: "3-Tier 아키텍처 구축 · 99.9% 가용성 달성 · Canary 배포 자동화"
    tech: "Kubernetes, EKS, Route53, Azure, Terraform"
  - date: "2025.09 ~ 2025.10"
    title: "CI/CD Pipeline 구축"
    description: "Jenkins + ArgoCD 기반 GitOps 파이프라인 구현"
    tech: "Jenkins, ArgoCD, Kubernetes, GitHub"
  - date: "2025.07 ~ 2025.08"
    title: "Observability 구축"
    description: "Prometheus + Loki + Grafana 기반 모니터링 시스템"
    tech: "Prometheus, Grafana, Loki, AlertManager"
  - date: "2025.05 ~ 현재"
    title: "Homelab Kubernetes 클러스터"
    description: "베어메탈 3노드 클러스터 구축 및 운영"
    tech: "Kubernetes, Cilium, Istio, Longhorn, ArgoCD"

# 스킬바 데이터
skills:
  - name: "Kubernetes & Container"
    percentage: 85
  - name: "AWS (EKS, VPC, RDS, Route53)"
    percentage: 80
  - name: "IaC (Terraform, ArgoCD)"
    percentage: 75
  - name: "CI/CD (Jenkins, GitHub Actions)"
    percentage: 70

# 기술 그리드
techGrid:
  - category: "Infrastructure"
    items: "Kubernetes · Cilium · Istio · Terraform · ArgoCD"
  - category: "Observability"
    items: "Prometheus · Grafana · Loki · Jaeger · AlertManager"
  - category: "Application"
    items: "Spring Boot · MySQL · Redis · nginx"
---

# 안녕하세요, 지민입니다

**Homelab Kubernetes Operator & Cloud Infrastructure Engineer**

"왜 홈서버를 운영하나요?"라는 질문을 자주 받습니다.
답은 간단합니다. **진짜 운영 경험을 얻고 싶었기 때문입니다.**

AWS 튜토리얼을 따라하는 것과,
베어메탈 서버에 Kubernetes를 직접 설치하고,
네트워크가 안 되는 문제를 밤새 디버깅하는 것은 완전히 다릅니다.

---

## 🏠 현재 운영 중

### Homelab Infrastructure

이 블로그는 제가 직접 구축한 홈서버 Kubernetes 클러스터에서 운영되고 있습니다.

- **Kubernetes 3-Node Cluster** (베어메탈)
- **54일 무중단 운영** (99.9% Uptime)
- **GitOps 자동 배포** (ArgoCD 3초 동기화)
- **Service Mesh** (Cilium L3/L4 + Istio L7)
- **Full Observability** (Prometheus + Loki + Jaeger)

### Cloud Projects

- **AWS EKS Multi-AZ 구축** (99.9% SLA)
- **Terraform IaC** (40+ 리소스 관리)
- **Jenkins + ArgoCD CI/CD** 파이프라인
- **Canary Deployment** 자동화

---

## 📝 이 블로그에서 다루는 것

### ✅ 실전 트러블슈팅

"왜 안되지?"에서 시작해서 "이래서 안됐구나!"까지의 여정

**대표 사례**:
- blog.jiminhome.shop 사이트 다운 → Cilium vs Istio 계층 분리
- MySQL JDBC 연결 timeout → Istio Envoy Proxy 충돌 해결
- /auth 경로 403 Forbidden → L3/L4와 L7 중복 제어 문제

### ✅ 아키텍처 결정 과정

Cilium vs Istio, Local vs Cluster, mTLS vs Plain TCP
선택의 순간마다 고민한 트레이드오프를 공유합니다.

### ✅ 운영 경험 공유

54일간의 무중단 운영에서 배운 것들
(물론 중단된 적도 많았습니다...)

정전, 네트워크 끊김, 디스크 고장을 겪으며
클라우드의 SLA가 얼마나 대단한지 깨달았습니다.

---

## ❌ 다루지 않는 것

- "5분 만에 따라하기" 튜토리얼
- 에러 없이 한 번에 성공한 척하기
- 이론만 있는 내용

---

## 🛠️ Tech Stack

### 직접 구축하고 운영 중

**Infrastructure**
- Kubernetes (베어메탈 3노드 + AWS EKS)
- Cilium CNI (L3/L4 네트워크 정책)
- Istio Service Mesh (mTLS + L7 트래픽 제어)
- Longhorn (분산 스토리지)

**GitOps & Automation**
- ArgoCD (3초 자동 배포)
- Terraform (AWS 인프라 IaC)
- Jenkins (CI 파이프라인)

**Observability**
- Prometheus + Grafana (메트릭)
- Loki (로그 수집)
- Jaeger (분산 추적)

### 학습 중

- Kafka (Event-Driven Architecture)
- Karpenter (K8s Auto-Scaling)
- OpenTelemetry (Unified Observability)

---

## 💭 이 블로그를 만든 이유

> "Kubernetes 책을 10권 읽는 것보다,
> 직접 클러스터를 구축하고 3일간 안 되는 네트워크를 디버깅하는 것이
> 10배는 더 많은 걸 가르쳐줍니다."

이 블로그는 그런 경험을 기록하고,
같은 문제로 고민하는 누군가에게 도움이 되기를 바라며 운영합니다.

---

## 📫 Contact

- 📧 Email: wlals2@naver.com
- 💻 GitHub: [@wlals2](https://github.com/wlals2)
- 🏠 Blog: [blog.jiminhome.shop](https://blog.jiminhome.shop)
- 📐 Architecture: [전체 아키텍처 문서](/architecture/)

---

**"이론과 실전의 간극을 메우는 엔지니어가 되고 싶습니다."**

*Built with Hugo · Deployed with ArgoCD · Hosted on Homelab Kubernetes*
