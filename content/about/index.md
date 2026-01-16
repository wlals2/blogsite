---
title: "About Me"
date: 2026-01-13
layout: "single"
url: "/about/"
summary: "클라우드 엔지니어를 준비하는 지민입니다"
showtoc: true
tocopen: true
---

## 왜 클라우드/DevOps인가?

처음엔 **4시간 걸리던 인프라 구축**이 너무 고통스러웠습니다.

AWS Console에서 클릭, 클릭, 클릭... 매번 실수하고, 같은 작업을 반복하고, 어제 뭘 어떻게 설정했는지 기억이 안 나서 또 삽질하고.

**"이걸 코드로 관리하면 안 될까?"**

그 질문 하나에서 시작했습니다.

---

## 성장 타임라인

```
2025.09                                                     현재
   │                                                          │
   ▼                                                          ▼
┌──────────────────────────────────────────────────────────────┐
│                                                              │
│  [문제] 수동 작업 4시간, 매번 다른 결과                        │
│     │                                                        │
│     ▼                                                        │
│  [Phase 1] Terraform IaC                                     │
│     "코드로 관리하면 되잖아?"                                  │
│     → 인프라 구축 시간 93% 단축 (4시간 → 15분)                 │
│     │                                                        │
│     ▼                                                        │
│  [Phase 2] Kubernetes                                        │
│     "배포도 선언적으로 할 수 있잖아?"                          │
│     → 배포 시간 83% 단축 (30분 → 5분)                         │
│     │                                                        │
│     ▼                                                        │
│  [Phase 3] AWS EKS + Multi-Cloud DR                          │
│     "클라우드도 장애나면 어떡해?"                              │
│     → 99.9% 가용성, RTO 2분 DR 달성                           │
│     │                                                        │
│     ▼                                                        │
│  [현재]                                                      │
│     "왜?"를 묻고, 코드로 답하는 엔지니어                       │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

---

## 핵심 역량

### 1. 문제 해결 능력

> "기술은 도구일 뿐. 문제를 먼저 이해하라."

- 문제 발견 → 원인 분석 → 해결책 비교 → 구현 → 검증
- 51개 트러블슈팅 사례 문서화
- 모든 기술 선택에 "왜?"를 질문

### 2. 의사결정 과정

모든 기술 선택에는 **Trade-off**가 있습니다:

| 상황 | 선택 | 이유 | 포기한 것 |
|------|------|------|----------|
| IaC 도구 | Terraform | Multi-Cloud 지원 | CloudFormation (AWS 전용) |
| 배포 전략 | Canary | 리스크 최소화 | 빠른 배포 (Rolling) |
| 세션 관리 | Redis | HPA 가능 | Sticky Session |
| DR | Multi-Cloud | 클라우드 장애 대응 | 비용 절감 |

### 3. 문서화 습관

- 214개 이상의 기술 문서 작성
- 모든 문제 해결 과정을 기록
- "3개월 후의 나도 이해할 수 있게"

---

## 기술 스택

### Cloud & Infrastructure

| 분야 | 기술 | 수준 |
|------|------|------|
| **AWS** | EKS, EC2, RDS, ALB, Route53, CloudFront, ACM, S3, ECR | ⭐⭐⭐⭐ |
| **Azure** | VM, MySQL Flexible Server, Blob Storage, Application Gateway | ⭐⭐⭐ |
| **IaC** | Terraform (State: S3 + DynamoDB Lock) | ⭐⭐⭐⭐ |

### Kubernetes & DevOps

| 분야 | 기술 | 수준 |
|------|------|------|
| **Orchestration** | EKS, kubectl, Helm | ⭐⭐⭐⭐ |
| **Deployment** | Argo Rollouts (Canary), ArgoCD (GitOps) | ⭐⭐⭐⭐ |
| **Scaling** | HPA, Karpenter | ⭐⭐⭐ |
| **CI/CD** | Jenkins, GitHub Actions | ⭐⭐⭐⭐ |

### Monitoring & Observability

| 분야 | 기술 | 수준 |
|------|------|------|
| **Metrics** | Prometheus, CloudWatch Exporter | ⭐⭐⭐⭐ |
| **Visualization** | Grafana (9개 대시보드) | ⭐⭐⭐⭐ |
| **Logging** | Loki, CloudWatch Logs | ⭐⭐⭐ |

---

## 정량적 성과

| 항목 | Before | After | 개선율 |
|------|--------|-------|--------|
| **인프라 구축** | 4시간 | 15분 | **94% 단축** |
| **배포 시간** | 30분 | 10분 | **67% 단축** |
| **롤백 시간** | 30분 | 10초 | **99% 단축** |
| **가용성** | 95% | 99.9% | **+4.9%** |
| **DR RTO** | 없음 | 2분 | **신규 구축** |

---

## 현재 진행 중

```
┌─────────────────────────────────────────────────────────────┐
│                    AWS EKS 3-Tier Architecture              │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│   WEB (nginx) + WAS (Spring Boot) + RDS (MySQL)            │
│   ├── Argo Rollouts Canary 배포                            │
│   ├── HPA + Karpenter 오토스케일링                          │
│   └── Prometheus + Grafana 모니터링                        │
│                                                             │
│   Multi-Cloud DR (AWS + Azure)                             │
│   ├── Route53 Failover (RTO 2분)                           │
│   ├── CloudFront + Lambda@Edge                             │
│   └── Azure VM + MySQL Flexible Server                     │
│                                                             │
│   CI/CD GitOps Pipeline                                    │
│   ├── Jenkins (Build + ECR Push)                           │
│   ├── ArgoCD (GitOps Sync)                                 │
│   └── Argo Rollouts (Canary 배포)                          │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

---

## 목표

> **3개월 내 클라우드/DevOps 엔지니어 취업**

### 왜 이 분야인가?

개발과 운영의 경계에서 **시스템 전체를 바라보는 시야**가 매력적입니다.

- 코드 한 줄이 어떻게 사용자에게 전달되는지
- 장애가 발생했을 때 어떻게 빠르게 복구하는지
- 비용을 최적화하면서 안정성을 유지하는 방법

이런 고민들이 재미있습니다.

### 로드맵

| 상태 | 항목 | 목표 |
|:---:|------|------|
| ✅ | EKS 클러스터 구축 | Terraform IaC |
| ✅ | CI/CD 파이프라인 | Jenkins + ArgoCD |
| ✅ | Canary 배포 | Argo Rollouts + ALB Traffic Routing |
| ✅ | 세션 클러스터링 | Spring Session + Redis |
| ✅ | 모니터링 | Prometheus + Grafana (9개 대시보드) |
| ✅ | DR Failover | AWS → Azure 자동 전환 |
| 🔄 | 보안 강화 | WAF, ECR Scan, EBS 암호화 |
| ⏳ | CKA 자격증 | 2월 예정 |

---

## 연락처

- **GitHub**: [github.com/wlals2](https://github.com/wlals2)
- **Blog**: [blog.jiminhome.shop](https://blog.jiminhome.shop)
- **Email**: (이메일 주소)

---

## 이 블로그에서 다루는 것

| 섹션 | 내용 |
|------|------|
| [Projects](/projects/) | Phase 1-3 프로젝트 상세 (문제 → 해결 → 성과) |
| [Study](/study/) | 공부한 내용 정리 (K8s, EKS, Monitoring, Storage 등) |
<- 커스텀 CSS 추가 ( 그라데이션, 카드 UI, 애니메이션 ) Test: Fri Jan 16 03:37:02 PM KST 2026 -->
<- 커스텀 CSS 추가 ( 그라데이션, 카드 UI, 애니메이션 ) Test: Fri Jan 16 03:52:10 PM KST 2026 -->
