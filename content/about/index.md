---
title: "About Me"
date: 2026-01-08
layout: "single"
url: "/about/"
summary: "DevOps Engineer를 향한 여정"
showtoc: true
tocopen: true
---

## 안녕하세요, 지민입니다

클라우드 인프라와 DevOps에 열정을 가진 엔지니어입니다.

**"왜 이렇게 동작하는가?"** 를 끊임없이 질문하며,
단순히 작동하는 것을 넘어 **최적의 아키텍처**를 고민합니다.

---

## 현재 집중하고 있는 것

```
┌─────────────────────────────────────────────────────────┐
│                    현재 진행 중인 프로젝트                 │
├─────────────────────────────────────────────────────────┤
│                                                          │
│   AWS EKS 3-Tier Architecture                           │
│   ├── WEB (nginx) + WAS (Spring Boot) + RDS (MySQL)    │
│   ├── Argo Rollouts Canary 배포                         │
│   ├── HPA + Karpenter 오토스케일링                       │
│   └── Prometheus + Grafana 모니터링                     │
│                                                          │
│   Multi-Cloud DR (AWS + Azure)                          │
│   ├── Route53 Failover                                  │
│   ├── CloudFront + Lambda@Edge                          │
│   └── Azure VM + MySQL Flexible Server                  │
│                                                          │
│   CI/CD GitOps Pipeline                                 │
│   ├── Jenkins (Build + ECR Push)                        │
│   ├── ArgoCD (GitOps Sync)                              │
│   └── Argo Rollouts (Canary/Blue-Green)                │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

---

## 단기 목표

> **3개월 내 DevOps Engineer 취업**

### 왜 DevOps인가?

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
| 🔄 | DR Failover 테스트 | AWS → Azure 자동 전환 |
| 🔄 | 보안 강화 | WAF, ECR Scan, EBS 암호화 |
| ⏳ | CKA 자격증 | 2월 예정 |
| ⏳ | 면접 준비 | 기술 질문 정리 |

---

## 기술 스택

### Cloud & Infrastructure

| 분야 | 기술 |
|------|------|
| **AWS** | EKS, EC2, RDS, ALB, Route53, CloudFront, ACM, WAF, S3, ECR |
| **Azure** | Virtual Machine, MySQL Flexible Server, Blob Storage, Application Gateway |
| **IaC** | Terraform (State: S3 + DynamoDB Lock) |

### Kubernetes & DevOps

| 분야 | 기술 |
|------|------|
| **Orchestration** | EKS, kubectl, Helm |
| **Deployment** | Argo Rollouts (Canary), ArgoCD (GitOps) |
| **Scaling** | HPA, Karpenter |
| **CI/CD** | Jenkins, GitHub Actions |

### Monitoring & Observability

| 분야 | 기술 |
|------|------|
| **Metrics** | Prometheus, CloudWatch Exporter |
| **Visualization** | Grafana (9개 대시보드) |
| **Logging** | Loki, CloudWatch Logs |
| **Alerting** | AlertManager, Slack |

### Application

| 분야 | 기술 |
|------|------|
| **Backend** | Spring Boot, Redis (Session) |
| **Web Server** | nginx (Reverse Proxy) |
| **Database** | MySQL (RDS, Azure Flexible) |

---

## 연락처

- **GitHub**: [github.com/wlals2](https://github.com/wlals2)
- **Email**: (이메일 주소)

---

## 이 블로그에서 다루는 것

| 섹션 | 내용 |
|------|------|
| [Projects](/projects/) | 진행한 프로젝트 상세 설명 |
| [TIL](/til/) | Today I Learned - 매일 배운 것 |
| [DevOps](/devops/) | 기술 깊이 있는 글 |
| [Blog](/blog/) | 트러블슈팅, 일상 기록 |
