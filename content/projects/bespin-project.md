---
title: "AWS EKS 3-Tier + Multi-Cloud DR"
date: 2026-01-08
summary: "AWS EKS 기반 3-Tier 아키텍처 + Azure DR 구현"
tags: ["AWS", "EKS", "Kubernetes", "DR", "Azure", "Terraform", "ArgoCD"]
showtoc: true
tocopen: true
---

## 프로젝트 개요

AWS EKS 기반 3-Tier 웹 애플리케이션을 구축하고, Azure를 활용한 Multi-Cloud DR(Disaster Recovery) 환경을 구현한 프로젝트입니다.

| 항목 | 내용 |
|------|------|
| **기간** | 2025.10 ~ 현재 |
| **역할** | 인프라 설계, 구축, 운영 전체 |
| **환경** | AWS (Primary) + Azure (DR) |
| **IaC** | Terraform |
| **애플리케이션** | Spring PetClinic |

---

## 아키텍처

```

┌──────────────────────────────────────────────────────────────────────┐
│                         Route53 (DNS Failover)                        │
│                   www.goupang.shop / dr.goupang.shop                 │
└───────────────────────────────┬──────────────────────────────────────┘
                                │
        ┌───────────────────────┼───────────────────────┐
        │                       │                       │
        ▼                       ▼                       ▼
┌───────────────┐      ┌───────────────┐      ┌───────────────┐
│  AWS (Primary) │      │  DR (Blob)    │      │  DR (VM POC)  │
│               │      │               │      │               │
│  ALB + ACM    │      │  CloudFront   │      │  CloudFront   │
│      ↓        │      │  + Lambda@Edge│      │      ↓        │
│  EKS Cluster  │      │      ↓        │      │  Azure VM     │
│  ┌─────────┐  │      │  Azure Blob   │      │  nginx+Tomcat │
│  │ WEB Pod │  │      │  (점검페이지)  │      │      ↓        │
│  └────┬────┘  │      │               │      │  Azure MySQL  │
│       ↓       │      │               │      │               │
│  ┌─────────┐  │      └───────────────┘      └───────────────┘
│  │ WAS Pod │  │
│  └────┬────┘  │
│       ↓       │
│  ┌─────────┐  │
│  │   RDS   │  │
│  └─────────┘  │
└───────────────┘

```

---

## 핵심 구현 내용

### 1. EKS 3-Tier Architecture

| 계층 | 구성 | 기술 |
|------|------|------|
| **WEB** | nginx Reverse Proxy | Deployment, HPA (2-5) |
| **WAS** | Spring Boot PetClinic | Rollout, HPA (2-10) |
| **DB** | MySQL 8.0 | RDS Multi-AZ |

**주요 특징**:
- Terraform으로 전체 인프라 IaC 관리
- ALB Ingress Controller로 트래픽 라우팅
- Private Subnet에 Pod 배치 (보안)

### 2. Canary 배포 (Argo Rollouts)

```yaml
strategy:
  canary:
    canaryService: was-canary
    stableService: was-stable
    trafficRouting:
      alb:
        ingress: petclinic-ingress
    steps:
      - setWeight: 10
      - pause: {duration: 30s}
      - setWeight: 50
      - pause: {duration: 2m}
      - setWeight: 90
      - pause: {duration: 30s}

```

**구현 결과**:
- ALB Traffic Routing으로 정확한 트래픽 비율 제어
- 자동 롤백 (Analysis Template 연동 예정)

### 3. Session Clustering (Redis)

**문제**: WAS Pod 2개 이상 시 세션 공유 안 됨 → 로그인 무한 루프

**해결**: Spring Session + Redis

```java
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)
public class RedisSessionConfig {
    @Bean
    public RedisSerializer<Object> springSessionDefaultRedisSerializer() {
        return new GenericJackson2JsonRedisSerializer();
    }
}
```

**결과**: WAS Pod 2-10개 스케일 아웃 가능, HPA 활성화

### 4. Multi-Cloud DR

| 방식 | 도메인 | 용도 |
|------|--------|------|
| **Blob** | www.goupang.shop (Failover) | 점검 페이지 표시 |
| **VM POC** | dr.goupang.shop | 전체 서비스 제공 |

**DR 목표**:
- RPO: 24시간 (일일 백업)
- RTO: 3시간 (Route53 자동 Failover)

### 5. CI/CD Pipeline

```

┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│   GitHub     │────▶│   Jenkins    │────▶│     ECR      │
│  (Source)    │     │   (Build)    │     │   (Image)    │
└──────────────┘     └──────────────┘     └──────┬───────┘
                                                  │
┌──────────────┐     ┌──────────────┐             │
│   EKS Pod    │◀────│   ArgoCD     │◀────────────┘
│  (Deploy)    │     │  (GitOps)    │
└──────────────┘     └──────────────┘

```

**빌드 시간**: ~10분 (Layer Cache 최적화)

### 6. Monitoring (9개 대시보드)

| ID | 대시보드 | 용도 |
|----|---------|------|
| 001 | System Overview | K8s + App 개요 |
| 002 | AWS Infrastructure | ALB, RDS |
| 003 | JVM Monitoring | Heap, Thread, GC |
| 004 | Karpenter | Node 관리 |
| 005 | Cost Monitoring | OpenCost |
| 006 | DR Status | Failover 상태 |
| 007 | Session Monitoring | Redis Session |
| 009 | RDS Backup | 백업 상태 |
| 010 | Pushgateway | Batch Job |

---

## 기술 스택

| 분류 | 기술 |
|------|------|
| **Cloud** | AWS (EKS, RDS, ALB, Route53, CloudFront, ACM, WAF) |
| **DR** | Azure (VM, MySQL, Blob Storage) |
| **IaC** | Terraform (S3 Backend + DynamoDB Lock) |
| **Container** | Docker, ECR |
| **Orchestration** | EKS, Helm |
| **CI/CD** | Jenkins, ArgoCD, Argo Rollouts |
| **Monitoring** | Prometheus, Grafana, Loki, AlertManager |
| **Security** | WAF, ECR Scan, EBS Encryption |

---

## 성과 및 배운 점

### 성과

| 항목 | 결과 |
|------|------|
| **가용성** | Multi-AZ Pod 분산, DR Failover 구현 |
| **배포** | Canary 배포로 안전한 릴리스 |
| **확장성** | HPA로 트래픽에 따른 자동 스케일링 |
| **관측성** | 9개 Grafana 대시보드로 전체 시스템 모니터링 |

### 배운 점

1. **Terraform State 관리의 중요성**
   - S3 + DynamoDB Lock으로 협업 환경 구축
   - State Drift 방지를 위한 CI/CD 연동

2. **ArgoCD + HPA 충돌 해결**
   - `ignoreDifferences`로 replicas 필드 제외
   - GitOps와 오토스케일링 공존 방법

3. **세션 클러스터링 필요성**
   - Stateless 아키텍처의 중요성
   - Redis Session으로 Pod 독립성 확보

4. **DR 아키텍처 설계**
   - CloudFront Origin Group 제약 (POST 미지원)
   - Lambda@Edge로 Host 헤더 수정 필요성

---

## 관련 링크

- **서비스**: https://www.goupang.shop/petclinic/
- **DR**: https://dr.goupang.shop/petclinic/
- **Grafana**: https://www.goupang.shop/grafana/
- **ArgoCD**: https://www.goupang.shop/argocd/

---

## 문서

프로젝트 진행 중 작성한 문서는 200개 이상입니다.

| 카테고리 | 문서 수 | 주요 내용 |
|---------|--------|----------|
| Architecture | 9개 | 아키텍처 설계, Multi-AZ |
| DR | 20개+ | Failover, CloudFront, Lambda@Edge |
| Monitoring | 30개+ | Grafana, Prometheus, CloudWatch |
| Operations | 8개 | Canary, Probe, HPA |
| CI/CD | 6개 | Jenkins, ArgoCD |
| Troubleshooting | 10개 | 문제 해결 기록 |
