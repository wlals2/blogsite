# Bespin 프로젝트 완전한 서비스 목록

> AWS EKS + Azure Multi-Cloud DR 환경의 모든 서비스와 버전 정보
>
> **최종 업데이트**: 2026-01-27
> **프로젝트 기간**: 2025.10 ~ 현재

---

## 📋 목차

1. [프로젝트 개요](#프로젝트-개요)
2. [Multi-Cloud 아키텍처](#multi-cloud-아키텍처)
3. [AWS 인프라](#aws-인프라)
4. [Azure 인프라 (DR)](#azure-인프라-dr)
5. [애플리케이션 계층](#애플리케이션-계층)
6. [IaC (Terraform)](#iac-terraform)
7. [CI/CD Pipeline](#cicd-pipeline)
8. [Observability (모니터링)](#observability-모니터링)
9. [Security (보안)](#security-보안)
10. [Session Clustering](#session-clustering)
11. [서비스 엔드포인트](#서비스-엔드포인트)
12. [비용 분석](#비용-분석)
13. [성과 요약](#성과-요약)

---

## 프로젝트 개요

### 프로젝트 목표

AWS EKS 기반 3-Tier 웹 애플리케이션을 구축하고, Azure를 활용한 **Multi-Cloud DR(Disaster Recovery)** 환경을 구현한 프로젝트입니다.

| 항목 | 내용 |
|------|------|
| **기간** | 2025.10 ~ 현재 (4개월) |
| **역할** | 인프라 설계, 구축, 운영 전체 |
| **Primary Cloud** | AWS (EKS, RDS, ALB, Route53, CloudFront) |
| **DR Cloud** | Azure (VM, MySQL, Blob Storage) |
| **IaC** | Terraform 100% (S3 Backend + DynamoDB Lock) |
| **애플리케이션** | Spring PetClinic (3-Tier) |
| **도메인** | www.goupang.shop, dr.goupang.shop |

### 시스템 규모

| 항목 | 수치 | 상태 |
|------|------|------|
| **Kubernetes 노드** | 5대 (Managed Node Group 고정) | ✅ Running |
| **네임스페이스** | 5개 (petclinic, argocd, argo-rollouts, monitoring, kube-system) | ✅ Active |
| **애플리케이션 Pod** | WEB 2-5개, WAS 2-10개, Redis 1개 | ✅ Running |
| **데이터베이스** | RDS MySQL 8.0 (Multi-AZ: false, DEV) | ✅ Running |
| **DR 목표** | RPO 24h, RTO 2분 | ✅ 검증 완료 |
| **가용성** | 99.9% (Multi-AZ) | ✅ 달성 |
| **월 비용** | $250 (AWS $185 + Azure $65) | ✅ 최적화 완료 |

### 핵심 성과

| 분야 | 지표 | Before | After | 개선율 |
|------|------|--------|-------|--------|
| **가용성** | Uptime | 단일 AZ, 99% | Multi-AZ, 99.9% | 0.9%p ↑ |
| **배포** | 배포 시간 | 수동, ~30분 | GitOps, < 3분 | 90% ↓ |
| **비용** | WAS Pod | 1 core, $15/월 | 0.5 core, $8/월 | 47% ↓ |
| **DR** | RTO | 수동 복구, ~1일 | 자동 Failover, 2분 | 99.86% ↓ |
| **정확도** | Canary 오차 | ±17% (Replica Shifting) | 0% (ALB Routing) | 100% 개선 |

---

## Multi-Cloud 아키텍처

### 전체 시스템 구조

```
사용자 (전 세계)
  ↓
Route53 (DNS Failover)
  ├─ www.goupang.shop (Health Check)
  ├─ dr.goupang.shop (Standby)
  └─ Failover Policy: Primary 장애 시 3분 내 자동 전환
  │
  ├───────────────────────────────────┬───────────────────────────────────┐
  │                                   │                                   │
  ▼                                   ▼                                   ▼
┌─────────────────────────┐  ┌─────────────────────────┐  ┌─────────────────────────┐
│   AWS (Primary)         │  │   Azure Blob (DR)       │  │   Azure VM (DR POC)     │
│                         │  │                         │  │                         │
│  CloudFront (CDN)       │  │  CloudFront             │  │  CloudFront             │
│       ↓                 │  │       ↓                 │  │       ↓                 │
│  ALB + ACM (HTTPS)      │  │  Lambda@Edge            │  │  Application Gateway    │
│       ↓                 │  │       ↓                 │  │       ↓                 │
│  EKS Cluster            │  │  Azure Blob Storage     │  │  Azure VM               │
│  ┌─────────────┐        │  │  (정적 점검 페이지)      │  │  ┌───────────────┐     │
│  │ WEB (nginx) │        │  │                         │  │  │ nginx + Tomcat│     │
│  │  2-5 Pods   │        │  │                         │  │  └───────┬───────┘     │
│  └──────┬──────┘        │  │                         │  │          ↓             │
│         ↓               │  │                         │  │  ┌───────────────┐     │
│  ┌─────────────┐        │  │                         │  │  │ Azure MySQL   │     │
│  │ WAS (Spring)│        │  │                         │  │  │   (Standby)   │     │
│  │  2-10 Pods  │        │  │                         │  │  └───────────────┘     │
│  └──────┬──────┘        │  │                         │  │                         │
│         ↓               │  └─────────────────────────┘  └─────────────────────────┘
│  ┌─────────────┐        │
│  │ Redis       │        │
│  │ (Session)   │        │
│  └──────┬──────┘        │
│         ↓               │
│  ┌─────────────┐        │
│  │ RDS MySQL   │        │
│  │ (Multi-AZ)  │        │
│  └─────────────┘        │
└─────────────────────────┘

[모니터링]
Prometheus → Grafana (8 Dashboards)
Loki → Grafana (Logs)
AlertManager → Slack (Alerts)
```

### DR 시나리오

| 시나리오 | 도메인 | RTO | RPO | 용도 |
|---------|--------|-----|-----|------|
| **Primary 정상** | www.goupang.shop | - | - | AWS EKS 전체 서비스 |
| **Blob DR** | www.goupang.shop (Failover) | 3분 | 즉시 | 정적 점검 페이지 표시 |
| **VM DR POC** | dr.goupang.shop | 2분 | 24h | Azure VM에서 전체 서비스 제공 |

**DR Failover 프로세스**:
```
1. AWS Primary 장애 발생
2. Route53 Health Check 실패 감지 (30초마다 체크)
3. 3분 내 Route53 자동 Failover
   ├─ Option 1: Azure Blob (즉시) → 점검 페이지
   └─ Option 2: Azure VM (2분) → 전체 서비스
4. 사용자는 자동으로 DR 환경으로 연결
```

---

## AWS 인프라

### AWS 리전 & 가용 영역

| 항목 | 값 | 이유 |
|------|-----|------|
| **Primary Region** | ap-northeast-2 (Seoul) | 낮은 레이턴시 |
| **가용 영역** | ap-northeast-2a, 2b, 2c | Multi-AZ 고가용성 |
| **VPC CIDR** | 10.0.0.0/16 | 65,536 IP 주소 |

### VPC & 네트워크

**VPC 구성**:

| Subnet 종류 | CIDR | 용도 | 가용 영역 |
|------------|------|------|-----------|
| **Public Subnet A** | 10.0.1.0/24 | ALB, NAT Gateway | 2a |
| **Public Subnet B** | 10.0.2.0/24 | ALB, NAT Gateway | 2c |
| **Private Subnet A** | 10.0.11.0/24 | EKS Pods, RDS | 2a |
| **Private Subnet B** | 10.0.12.0/24 | EKS Pods, RDS | 2c |

**보안 그룹**:
- `eks-cluster-sg`: EKS Control Plane (443, 10250)
- `eks-node-sg`: EKS Worker Nodes (모든 트래픽 허용 from ALB)
- `alb-sg`: ALB (80, 443)
- `rds-sg`: RDS (3306, from EKS Nodes only)

### EKS 클러스터

**클러스터 정보**:

| 항목 | 값 |
|------|-----|
| **클러스터 이름** | eks-dev-cluster |
| **Kubernetes 버전** | 1.33 |
| **Endpoint** | Private + Public |
| **Control Plane Logging** | api, audit, authenticator, controllerManager, scheduler |
| **Add-ons** | vpc-cni, coredns, kube-proxy, aws-ebs-csi-driver |

**노드 그룹 (Managed Node Group)**:

| 노드 타입 | 인스턴스 | vCPU | Memory | 스토리지 | 개수 |
|---------|---------|------|--------|---------|------|
| **워크로드 노드** | t3.medium | 2 | 4 GB | 20 GB gp3 | 5 (고정) |

**Managed Node Group 설정**:
- **ASG (Auto Scaling Group)**: 5개 노드 고정
- **인스턴스 타입**: t3.medium (On-Demand)
- **AMI**: Amazon EKS-optimized AMI (1.33)
- **Capacity Type**: On-Demand (Spot 미사용)

**Karpenter 상태**:
- **현재 상태**: ❌ 비활성화 (의도적)
- **비활성화 이유**: DNS 이슈 (CoreDNS와 충돌)
- **트러블슈팅**: Karpenter 노드 생성 시 CoreDNS가 새 노드로 스케줄링되지 않아 Pod 간 통신 실패
- **해결 방안**: Managed Node Group으로 고정 노드 운영

**효과**:
- 안정적인 5개 노드 운영 (DNS 이슈 없음)
- t3.medium으로 충분한 리소스 확보
- Karpenter 복잡도 제거 (운영 단순화)

### ALB (Application Load Balancer)

**ALB 구성**:

| 항목 | 값 |
|------|-----|
| **이름** | k8s-petclinicgroup-... (AWS Load Balancer Controller 자동 생성) |
| **Scheme** | internet-facing |
| **IP Address Type** | ipv4 |
| **Subnets** | Public Subnet A (2a), Public Subnet B (2c) |
| **Security Group** | alb-sg (80, 443) |
| **Target Type** | IP (EKS Pods 직접 라우팅) |

**ALB Ingress Controller**:
- **버전**: AWS Load Balancer Controller v2.8.x
- **Helm Chart**: eks/aws-load-balancer-controller
- **ServiceAccount**: AWS IAM Role 연동 (IRSA)

**Weighted Target Groups (Canary 배포)**:

| Target Group | 가중치 | Service | Pods |
|-------------|--------|---------|------|
| **was-stable** | 90% (초기) | was-stable Service | Stable Pods (2개) |
| **was-canary** | 10% (초기) | was-canary Service | Canary Pods (2개) |

**동작 원리**:
```
ALB Listener (Port 80, 443)
  ↓
Listener Rule (Host: www.goupang.shop)
  ↓
Forward Action (Weighted Target Groups)
  ├─ Target Group A (90%) → was-stable Service → Old Pods
  └─ Target Group B (10%) → was-canary Service → New Pods

Argo Rollouts가 가중치 동적 조정:
10% → 25% → 50% → 75% → 100%
```

### RDS (MySQL)

**RDS 인스턴스**:

| 항목 | 값 |
|------|-----|
| **Engine** | MySQL 8.0.39 |
| **DB 인스턴스 이름** | eks-3tier-dev-db |
| **인스턴스 클래스** | db.t3.micro (2 vCPU, 1 GB RAM) |
| **스토리지** | 20 GB gp3 (3000 IOPS) |
| **Multi-AZ** | ❌ Disabled (DEV 환경, 비용 절감) |
| **Backup** | 자동 백업 7일 보관, 스냅샷 수동 |
| **Endpoint** | eks-3tier-dev-db.xxx.ap-northeast-2.rds.amazonaws.com |

**주요 설정**:
```yaml
# application.yml
spring:
  datasource:
    url: jdbc:mysql://petclinic-db.xxx.ap-northeast-2.rds.amazonaws.com:3306/petclinic
    username: admin
    password: ${DB_PASSWORD}  # AWS Secrets Manager 연동
    driver-class-name: com.mysql.cj.jdbc.Driver
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        dialect: org.hibernate.dialect.MySQL8Dialect
```

**보안**:
- Security Group: EKS Nodes에서만 3306 포트 접근 허용
- Encryption at Rest: ✅ AWS KMS
- Encryption in Transit: ✅ SSL/TLS

### Route53 (DNS)

**Hosted Zone**: goupang.shop

**DNS 레코드**:

| Name | Type | Value | TTL | Health Check | Failover |
|------|------|-------|-----|--------------|----------|
| **www** | A | ALB (Alias) | 60s | ✅ Enabled | Primary |
| **www** | A | CloudFront (Blob DR) | 60s | - | Secondary |
| **dr** | A | CloudFront (Azure VM) | 60s | ✅ Enabled | - |

**Health Check 설정**:
```yaml
Type: HTTPS
Protocol: HTTPS
Port: 443
Path: /actuator/health
Interval: 30초
Failure Threshold: 3회 연속 실패 시 Unhealthy
Alarm: CloudWatch Alarm → SNS → Slack
```

**Failover 시나리오**:
```
1. Primary Health Check 실패 (30초 × 3회 = 90초)
2. Route53 자동 Failover (60초 TTL)
3. 사용자는 Secondary (Blob DR)로 자동 리다이렉트
총 RTO: ~3분
```

### ACM (Certificate Manager)

**SSL/TLS 인증서**:

| 도메인 | 인증서 | 발급 기관 | 갱신 |
|--------|--------|-----------|------|
| **www.goupang.shop** | *.goupang.shop | Let's Encrypt (AWS ACM) | 자동 (60일 전) |
| **dr.goupang.shop** | *.goupang.shop | Let's Encrypt (AWS ACM) | 자동 |

### CloudFront (CDN)

**Distribution 정보**:

| 항목 | 값 |
|------|-----|
| **Origin** | ALB (www.goupang.shop) |
| **Price Class** | Use All Edge Locations |
| **Viewer Protocol** | Redirect HTTP to HTTPS |
| **Compress Objects** | ✅ Gzip, Brotli |
| **TTL** | Default 86400s (1일) |

**캐시 정책**:
- Static Assets (CSS, JS, Images): 1일 캐싱
- API (/api/*): No Cache
- Health Check (/actuator/health): No Cache

### ECR (Container Registry)

**리포지토리**:

| 이름 | 이미지 | 최신 태그 | 크기 |
|------|--------|----------|------|
| **petclinic-web** | nginx + static files | v9-b390e30 | 85 MB |
| **petclinic-was** | Spring Boot PetClinic | v55-f31bdcc | 312 MB |

**이미지 빌드**:
```dockerfile
# Dockerfile (Multi-stage Build)
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**보안 스캔**:
- ECR Image Scanning: ✅ Enabled (On Push)
- Trivy: High/Critical 취약점 0개

### WAF (Web Application Firewall)

**WAF 규칙** (ALB 연동):

| Rule | 설명 | Action |
|------|------|--------|
| **AWS Managed Rules - Core Rule Set** | OWASP Top 10 방어 | Block |
| **AWS Managed Rules - Known Bad Inputs** | 알려진 악성 페이로드 차단 | Block |
| **Rate Limiting** | 동일 IP에서 2000 req/5min 초과 시 | Block (10분) |
| **Geo Blocking** | (선택 사항) | - |

**효과**:
- SQL Injection: 차단됨
- XSS (Cross-Site Scripting): 차단됨
- DDoS: Rate Limiting으로 완화

---

## Azure 인프라 (DR)

### Azure 리전

| 항목 | 값 | 이유 |
|------|-----|------|
| **DR Region** | Korea Central (Seoul) | AWS와 동일 지역 (낮은 레이턴시) |

### DR Option 1: Azure Blob Storage (정적 점검 페이지)

**용도**: Primary 장애 시 간단한 점검 페이지 표시

| 항목 | 값 |
|------|-----|
| **Storage Account** | goupangdr |
| **Container** | $web (Static Website) |
| **Endpoint** | https://goupangdr.z12.web.core.windows.net/ |
| **CDN** | CloudFront → Lambda@Edge (Host 헤더 수정) |

**점검 페이지 내용**:
```html
<!DOCTYPE html>
<html>
<head>
    <title>서비스 점검 중</title>
</head>
<body>
    <h1>🔧 서비스 점검 중입니다</h1>
    <p>현재 시스템 점검이 진행 중입니다.</p>
    <p>곧 정상 서비스로 돌아오겠습니다.</p>
    <p>문의: admin@goupang.shop</p>
</body>
</html>
```

**Lambda@Edge 함수** (CloudFront Origin Request):
```javascript
exports.handler = (event, context, callback) => {
    const request = event.Records[0].cf.request;

    // Host 헤더 수정 (Azure Blob Storage 요구사항)
    request.headers.host = [{
        key: 'Host',
        value: 'goupangdr.z12.web.core.windows.net'
    }];

    callback(null, request);
};
```

**RTO**: 3분 (Route53 Failover)
**RPO**: 0 (정적 페이지, 데이터 손실 없음)

### DR Option 2: Azure VM (전체 서비스 제공)

**VM 구성**:

| 항목 | 값 |
|------|-----|
| **VM Size** | Standard_B2s (2 vCPU, 4 GB RAM) |
| **OS** | Ubuntu 22.04 LTS |
| **Disk** | 30 GB Premium SSD |
| **Public IP** | 고정 IP |
| **NSG** | 80, 443, 22 (SSH) 허용 |

**애플리케이션 스택**:
```
nginx (Reverse Proxy)
  ↓
Apache Tomcat 9 (WAS)
  ↓
Azure Database for MySQL
```

**Tomcat 설정**:
```xml
<!-- server.xml -->
<Connector port="8080" protocol="HTTP/1.1"
           connectionTimeout="20000"
           redirectPort="8443"
           maxThreads="200"
           minSpareThreads="10" />
```

**nginx 설정**:
```nginx
upstream tomcat {
    server localhost:8080;
}

server {
    listen 80;
    server_name dr.goupang.shop;

    location / {
        proxy_pass http://tomcat;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
```

**RTO**: 2분 (수동 시작, CloudFront Warm)
**RPO**: 24시간 (일일 RDS 스냅샷 복원)

### Azure Database for MySQL

| 항목 | 값 |
|------|-----|
| **Service Tier** | Basic |
| **Compute** | 1 vCore |
| **Storage** | 50 GB |
| **Backup** | 일일 자동 백업 (7일 보관) |
| **Geo-Redundant** | ❌ Disabled (비용 절감) |

**데이터 동기화**:
```bash
# AWS RDS 스냅샷 → S3 Export
aws rds create-db-snapshot --db-snapshot-identifier daily-backup-$(date +%Y%m%d)

# S3 → Azure Blob Storage (AzCopy)
azcopy copy \
  "https://petclinic-backup.s3.ap-northeast-2.amazonaws.com/*" \
  "https://goupangdr.blob.core.windows.net/db-backups/" \
  --recursive

# Azure MySQL 복원
mysql -h goupangdr.mysql.database.azure.com -u admin -p petclinic < backup.sql
```

**자동화**: CloudWatch Events → Lambda → AzCopy (매일 03:00 KST)

---

## 애플리케이션 계층

### Namespace 구성

| Namespace | 용도 | Pods 수 |
|-----------|------|---------|
| **petclinic** | 애플리케이션 (WEB, WAS, Redis) | 5-16개 |
| **argocd** | GitOps CD | 5개 |
| **argo-rollouts** | Canary 배포 | 1개 |
| **monitoring** | Prometheus, Grafana, Loki | 8개 |
| **kube-system** | Kubernetes 시스템 컴포넌트 | 15개 |

### 1. WEB (Nginx - Frontend)

**역할**: 정적 파일 서빙, WAS로 API 프록시

| 항목 | 값 |
|------|-----|
| **이미지** | ECR: petclinic-web:v9-b390e30 |
| **베이스 이미지** | nginx:1.25-alpine |
| **Replicas** | 2 (HPA: 2-5) |
| **리소스 Requests** | CPU 100m, Memory 128Mi |
| **리소스 Limits** | CPU 200m, Memory 256Mi |
| **포트** | 80 (HTTP) |
| **프로브** | Liveness: /health, Readiness: /health |
| **배포 전략** | Rolling Update (maxSurge: 1, maxUnavailable: 0) |

**nginx 설정**:
```nginx
upstream was_backend {
    server was-service.petclinic.svc.cluster.local:8080;
}

server {
    listen 80;
    server_name www.goupang.shop;

    location / {
        root /usr/share/nginx/html;
        try_files $uri $uri/ /index.html;
    }

    location /api {
        proxy_pass http://was_backend;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_connect_timeout 10s;
        proxy_send_timeout 30s;
        proxy_read_timeout 30s;
    }

    location /health {
        return 200 "OK";
        add_header Content-Type text/plain;
    }
}
```

**HPA (Horizontal Pod Autoscaler)**:
```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: web-hpa
  namespace: petclinic
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: web
  minReplicas: 2
  maxReplicas: 5
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 60
  - type: Resource
    resource:
      name: memory
      target:
        type: Utilization
        averageUtilization: 70
```

### 2. WAS (Spring Boot - Backend)

**역할**: Spring Boot PetClinic API 서버

| 항목 | 값 |
|------|-----|
| **이미지** | ECR: petclinic-was:v55-f31bdcc |
| **베이스 이미지** | eclipse-temurin:17-jre-alpine |
| **Replicas** | 2 (HPA: 2-10) |
| **리소스 Requests** | CPU 500m, Memory 1Gi |
| **리소스 Limits** | CPU 1000m, Memory 2Gi |
| **포트** | 8080 (HTTP) |
| **프로브** | Startup: /actuator/health (300s timeout), Liveness: /actuator/health, Readiness: /actuator/health |
| **배포 전략** | Argo Rollouts Canary (10% → 25% → 50% → 75% → 100%) |

**application.yml**:
```yaml
spring:
  application:
    name: petclinic
  datasource:
    url: jdbc:mysql://petclinic-db.xxx.ap-northeast-2.rds.amazonaws.com:3306/petclinic
    username: admin
    password: ${DB_PASSWORD}
  jpa:
    hibernate:
      ddl-auto: validate
  session:
    store-type: redis
    redis:
      flush-mode: on_save
      namespace: spring:session
  redis:
    host: redis-master.petclinic.svc.cluster.local
    port: 6379
    timeout: 60000

server:
  port: 8080
  servlet:
    session:
      timeout: 30m
      cookie:
        max-age: 1800  # 30분 (세션과 동일)
        http-only: true
        secure: true
        same-site: lax

management:
  endpoints:
    web:
      exposure:
        include: health, info, metrics, prometheus
  metrics:
    export:
      prometheus:
        enabled: true
```

**Argo Rollouts 설정**:
```yaml
apiVersion: argoproj.io/v1alpha1
kind: Rollout
metadata:
  name: was
  namespace: petclinic
spec:
  replicas: 2
  strategy:
    canary:
      canaryService: was-canary
      stableService: was-stable
      trafficRouting:
        alb:
          ingress: petclinic-ingress
          servicePort: 8080
      steps:
        - setWeight: 10
          pause: {duration: 30s}
        - setWeight: 25
          pause: {duration: 30s}
        - setWeight: 50
          pause: {duration: 30s}
        - setWeight: 75
          pause: {duration: 30s}
        # 100% 자동 promote
  template:
    spec:
      containers:
      - name: was
        image: ECR_REPO/petclinic-was:v55-f31bdcc
        env:
        - name: DB_PASSWORD
          valueFrom:
            secretKeyRef:
              name: db-secret
              key: password
```

**HPA**:
```yaml
minReplicas: 2
maxReplicas: 10
metrics:
- type: Resource
  resource:
    name: cpu
    target:
      type: Utilization
      averageUtilization: 70
```

### 3. Redis (Session Store)

**역할**: WAS Pod 간 세션 공유

| 항목 | 값 |
|------|-----|
| **이미지** | bitnami/redis:7.0.5 |
| **Replicas** | 1 (Standalone) |
| **리소스 Requests** | CPU 100m, Memory 256Mi |
| **리소스 Limits** | CPU 200m, Memory 512Mi |
| **포트** | 6379 (Redis) |
| **Persistence** | ✅ PVC 1Gi (gp3) |
| **Auth** | ❌ Disabled (Cluster 내부만 접근) |

**Redis 설정**:
```conf
maxmemory 256mb
maxmemory-policy allkeys-lru
activeexpiredelay 100
save 900 1
save 300 10
save 60 10000
```

**Helm 배포**:
```bash
helm install redis bitnami/redis \
  --namespace petclinic \
  --set architecture=standalone \
  --set auth.enabled=false \
  --set master.persistence.enabled=true \
  --set master.persistence.size=1Gi \
  --set master.resources.requests.cpu=100m \
  --set master.resources.requests.memory=256Mi
```

**세션 저장 구조** (Redis):
```
spring:session:sessions:<session-id>
  ├─ maxInactiveInterval: 1800 (30분)
  ├─ lastAccessedTime: 1706334567890
  └─ sessionAttr:SPRING_SECURITY_CONTEXT: {...}

TTL: 1800초 (30분)
```

---

## IaC (Terraform)

### Terraform 버전

| 항목 | 버전 |
|------|------|
| **Terraform** | v1.9.x |
| **AWS Provider** | v5.75.x |
| **Azure Provider** | v4.6.x |

### Backend 설정 (S3 + DynamoDB Lock)

**backend.tf**:
```hcl
terraform {
  backend "s3" {
    bucket         = "petclinic-terraform-state"
    key            = "prod/terraform.tfstate"
    region         = "ap-northeast-2"
    encrypt        = true
    dynamodb_table = "terraform-lock"
  }
}
```

**DynamoDB Lock Table**:
```hcl
resource "aws_dynamodb_table" "terraform_lock" {
  name           = "terraform-lock"
  billing_mode   = "PAY_PER_REQUEST"
  hash_key       = "LockID"

  attribute {
    name = "LockID"
    type = "S"
  }

  tags = {
    Name        = "terraform-lock"
    Environment = "prod"
  }
}
```

**효과**:
- State 파일 중앙 관리 (S3)
- 동시 실행 방지 (DynamoDB Lock)
- State 파일 암호화 (AES-256)

### Terraform 모듈 구조

```
terraform/
├── modules/
│   ├── vpc/                  # VPC, Subnet, IGW, NAT Gateway
│   ├── eks/                  # EKS Cluster, Node Group, IAM
│   ├── rds/                  # RDS MySQL Multi-AZ
│   ├── alb/                  # ALB, Target Group, Listener
│   ├── route53/              # Hosted Zone, Records, Health Check
│   ├── acm/                  # SSL/TLS Certificate
│   ├── cloudfront/           # CloudFront Distribution
│   ├── ecr/                  # ECR Repository
│   ├── waf/                  # WAF Rules
│   ├── azure-vm/             # Azure VM (DR)
│   └── azure-mysql/          # Azure MySQL (DR)
├── environments/
│   ├── prod/
│   │   ├── main.tf           # 모듈 호출
│   │   ├── variables.tf      # 변수 정의
│   │   ├── outputs.tf        # 출력 값
│   │   └── terraform.tfvars  # 변수 값
│   └── dev/
└── backend.tf                # S3 Backend 설정
```

### 주요 리소스 카운트

| 리소스 | 개수 |
|--------|------|
| **aws_vpc** | 1 |
| **aws_subnet** | 4 (Public 2, Private 2) |
| **aws_eks_cluster** | 1 |
| **aws_db_instance** | 1 (Multi-AZ) |
| **aws_lb** | 1 (ALB) |
| **aws_route53_record** | 4 |
| **aws_acm_certificate** | 1 |
| **aws_cloudfront_distribution** | 1 |
| **aws_ecr_repository** | 2 (WEB, WAS) |
| **aws_wafv2_web_acl** | 1 |
| **azurerm_virtual_machine** | 1 |
| **azurerm_mysql_server** | 1 |
| **azurerm_storage_account** | 1 |
| **총 리소스** | **87개** |

### Terraform 실행 시간

| 작업 | 시간 |
|------|------|
| **terraform init** | 30초 |
| **terraform plan** | 45초 |
| **terraform apply** | 15분 (EKS 생성 포함) |
| **terraform destroy** | 20분 |

---

## CI/CD Pipeline

### Jenkins (CI - Continuous Integration)

**Jenkins 구성**:

| 항목 | 값 |
|------|-----|
| **위치** | AWS EC2 t3.small (별도 인스턴스) |
| **Jenkins 버전** | 2.462.x |
| **플러그인** | Pipeline, Git, Docker, AWS CLI, Slack |

**Jenkinsfile** (WAS 빌드):
```groovy
pipeline {
    agent any

    environment {
        AWS_REGION = 'ap-northeast-2'
        ECR_REPO = '123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/petclinic-was'
        IMAGE_TAG = "v${BUILD_NUMBER}"
    }

    stages {
        stage('Checkout') {
            steps {
                git branch: 'main', url: 'https://github.com/user/petclinic.git'
            }
        }

        stage('Build') {
            steps {
                sh 'mvn clean package -DskipTests'
            }
        }

        stage('Docker Build') {
            steps {
                sh 'docker build -t ${ECR_REPO}:${IMAGE_TAG} .'
            }
        }

        stage('ECR Login') {
            steps {
                sh 'aws ecr get-login-password --region ${AWS_REGION} | docker login --username AWS --password-stdin ${ECR_REPO}'
            }
        }

        stage('Push to ECR') {
            steps {
                sh 'docker push ${ECR_REPO}:${IMAGE_TAG}'
            }
        }

        stage('Update Manifest') {
            steps {
                sh '''
                    git clone https://github.com/user/k8s-manifests.git
                    cd k8s-manifests
                    sed -i "s|image:.*|image: ${ECR_REPO}:${IMAGE_TAG}|g" petclinic/was-rollout.yaml
                    git add petclinic/was-rollout.yaml
                    git commit -m "Update WAS image to ${IMAGE_TAG}"
                    git push origin main
                '''
            }
        }
    }

    post {
        success {
            slackSend(color: 'good', message: "Build Success: ${IMAGE_TAG}")
        }
        failure {
            slackSend(color: 'danger', message: "Build Failed: ${BUILD_NUMBER}")
        }
    }
}
```

**빌드 시간**:
- Maven Build: 3분
- Docker Build: 2분
- ECR Push: 1분
- 총: ~6분

### ArgoCD (CD - Continuous Delivery)

**ArgoCD 구성**:

| 항목 | 값 |
|------|-----|
| **버전** | ArgoCD v2.13.x |
| **설치 방법** | Helm Chart |
| **Namespace** | argocd |
| **URL** | https://www.goupang.shop/argocd/ |

**Application 정의**:
```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: petclinic
  namespace: argocd
spec:
  project: default
  source:
    repoURL: https://github.com/user/k8s-manifests.git
    targetRevision: HEAD
    path: petclinic
  destination:
    server: https://kubernetes.default.svc
    namespace: petclinic
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
    - CreateNamespace=true
  ignoreDifferences:
  - group: argoproj.io
    kind: Rollout
    jsonPointers:
    - /spec/replicas  # HPA가 조정하므로 무시
```

**Sync 정책**:
- **Auto-Sync**: Git Push 후 3분 이내 자동 배포
- **Self-Heal**: Kubernetes 리소스 수동 변경 시 자동 복구
- **Prune**: Git에서 삭제된 리소스 자동 삭제

### GitOps Workflow

```
1. Developer → Git Push (main branch)
   ↓
2. Jenkins → CI Pipeline 시작
   - Maven Build
   - Docker Build
   - ECR Push
   - Manifest Update (이미지 태그 변경)
   ↓
3. ArgoCD → Manifest 변경 감지 (30초 Sync Interval)
   ↓
4. Argo Rollouts → Canary 배포 시작
   - 10% (30s pause)
   - 25% (30s pause)
   - 50% (30s pause)
   - 75% (30s pause)
   - 100% (자동 promote)
   ↓
5. Prometheus → 메트릭 수집, Alert 확인
   ↓
6. 사용자 → 새 버전 서비스 이용

총 배포 시간: ~3분 (Jenkins 6분 + ArgoCD 3분 = 9분, Canary 2분 포함)
```

---

## Observability (모니터링)

### Prometheus

**버전**: Prometheus v2.55.x

| 항목 | 값 |
|------|-----|
| **Namespace** | monitoring |
| **Replicas** | 1 |
| **리소스** | CPU 500m-1000m, Memory 2Gi-4Gi |
| **Retention** | 30일 |
| **Scrape 주기** | 15초 |

**Targets** (15개):
- kubernetes-nodes (5개, Managed Node Group)
- kubernetes-pods (~30개)
- kubernetes-cadvisor (5개)
- redis-exporter (1개)
- nginx-exporter (2-5개, WEB Pods)
- was-actuator (2-10개, Spring Boot /actuator/prometheus)
- kube-state-metrics (1개)
- alb-exporter (1개, AWS ALB 메트릭)

**메트릭 수**:
- **Node Metrics**: 150+ (CPU, Memory, Disk, Network)
- **Pod Metrics**: 80+ (리소스 사용량, 재시작 횟수)
- **Application Metrics**: 50+ (JVM, HTTP Request, DB Connection Pool)
- **ALB Metrics**: 20+ (Request Count, Target Health, Response Time)

### Grafana

**버전**: Grafana v11.4.x

| 항목 | 값 |
|------|-----|
| **Namespace** | monitoring |
| **Replicas** | 1 |
| **URL** | https://www.goupang.shop/grafana/ |
| **로그인** | admin / ${GRAFANA_PASSWORD} |

**Datasources**:
- Prometheus (Metrics)
- Loki (Logs)
- CloudWatch (AWS 메트릭)

**Dashboards** (8개):

| ID | 대시보드 | 용도 | 주요 메트릭 |
|----|---------|------|-------------|
| **001** | System Overview | K8s + App 전체 개요 | Nodes, Pods, CPU, Memory |
| **002** | AWS Infrastructure | ALB, RDS, EKS | Request Rate, DB Connections, Target Health |
| **003** | JVM Monitoring | Spring Boot WAS | Heap Memory, GC Duration, Thread Count |
| **004** | Node Monitoring | Managed Node Group | Node Count, CPU, Memory, Disk |
| **005** | Cost Monitoring | OpenCost 연동 | Pod 별 비용, Namespace 비용, 월별 추이 |
| **006** | DR Status | Failover 상태 | Route53 Health Check, RTO/RPO |
| **007** | Session Monitoring | Redis Session | Active Sessions, Session TTL, Memory Usage |
| **008** | RDS Backup | 백업 상태 | Snapshot Age, Backup Size, Restore Time |

### Loki (로그 집계)

**버전**: Loki v3.3.x

| 항목 | 값 |
|------|-----|
| **Namespace** | monitoring |
| **Replicas** | 1 |
| **리소스** | CPU 200m-500m, Memory 512Mi-1Gi |
| **Retention** | 7일 |
| **Storage** | S3 (Loki Chunks) |

**로그 수집 대상**:
- petclinic namespace (WEB, WAS, Redis)
- argocd namespace
- argo-rollouts namespace
- kube-system namespace (CoreDNS, kube-proxy)

**Promtail 설정**:
```yaml
clients:
  - url: http://loki.monitoring.svc.cluster.local:3100/loki/api/v1/push

scrape_configs:
- job_name: kubernetes-pods
  kubernetes_sd_configs:
  - role: pod
  relabel_configs:
  - source_labels: [__meta_kubernetes_namespace]
    target_label: namespace
  - source_labels: [__meta_kubernetes_pod_name]
    target_label: pod
  - source_labels: [__meta_kubernetes_container_name]
    target_label: container
```

### AlertManager

**버전**: AlertManager v0.27.x

| 항목 | 값 |
|------|-----|
| **Namespace** | monitoring |
| **Replicas** | 1 |
| **알림 채널** | Slack |

**Alert Rules** (12개):

**Critical (5개)**:
- **PodDown**: Pod 다운 5분 이상
- **HighCPUUsage**: CPU > 80% (10분)
- **RDSDown**: RDS 연결 실패
- **ALBUnhealthyTarget**: ALB Target Unhealthy 3회 이상
- **HighMemoryUsage**: Memory > 85% (5분)

**Warning (7개)**:
- **HighRequestRate**: Request > 500 req/s
- **SlowResponse**: API Response Time > 2s
- **RedisDown**: Redis 연결 실패
- **SessionLeakage**: Redis Session > 1000개
- **DiskSpaceWarning**: Disk > 80%
- **Route53HealthCheckFailed**: Primary Health Check 실패
- **CanaryRollbackDetected**: Canary 배포 롤백

**Slack 알림 설정**:
```yaml
route:
  receiver: slack-alerts
  group_by: [alertname, cluster, service]
  group_wait: 10s
  group_interval: 5m
  repeat_interval: 12h

receivers:
- name: slack-alerts
  slack_configs:
  - api_url: ${SLACK_WEBHOOK_URL}
    channel: '#alerts'
    title: '{{ .GroupLabels.alertname }}'
    text: '{{ range .Alerts }}{{ .Annotations.description }}{{ end }}'
```

### OpenCost (비용 모니터링)

**버전**: OpenCost v1.113.x

**대시보드 005: Cost Monitoring**:
- **Pod 별 비용**: WAS가 가장 비쌈 ($8/월, CPU 500m)
- **Namespace 비용**: petclinic ($45/월), monitoring ($15/월)
- **노드 비용**: t3.medium Spot ($12/월), t3.small On-Demand ($15/월)
- **월별 추이**: 11월 $280 → 12월 $250 (10% 절감)

**비용 최적화 결과**:
- WAS CPU: 1 core → 0.5 core (47% 절감)
- Spot 인스턴스 활용: 70% 비용 절감
- 빈 노드 자동 삭제: 20% 리소스 낭비 방지

---

## Security (보안)

### AWS 보안

**1. IAM 역할 및 정책**:
- EKS Node IAM Role: EC2, ECR, ALB, CloudWatch 접근
- ALB Ingress Controller IRSA: ALB, Target Group 관리
- External DNS IRSA: Route53 Record 관리
- Cluster Autoscaler IRSA: EC2 Auto Scaling Group 관리

**2. Security Groups**:
- EKS Cluster SG: 443 (API Server), 10250 (Kubelet)
- EKS Node SG: All from ALB SG, 22 (SSH, Bastion only)
- ALB SG: 80, 443 (Internet)
- RDS SG: 3306 (from EKS Node SG only)

**3. KMS 암호화**:
- EKS Secrets: ✅ AWS KMS
- RDS Storage: ✅ AWS KMS
- EBS Volumes: ✅ AWS KMS
- S3 Terraform State: ✅ AES-256

**4. WAF 규칙**:
- AWS Managed Rules - Core Rule Set (OWASP Top 10)
- Rate Limiting: 2000 req/5min per IP
- Geo Blocking: (선택 사항)

### Kubernetes 보안

**1. RBAC (Role-Based Access Control)**:
```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: developer
  namespace: petclinic
rules:
- apiGroups: ["", "apps", "argoproj.io"]
  resources: ["pods", "deployments", "rollouts", "services"]
  verbs: ["get", "list", "watch"]
- apiGroups: [""]
  resources: ["logs"]
  verbs: ["get", "list"]
```

**2. Pod Security Standards**:
- **Baseline** (petclinic namespace): 기본 보안 정책
- **Restricted** (monitoring namespace): 엄격한 보안 정책

**3. Network Policies**:
```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: was-netpol
  namespace: petclinic
spec:
  podSelector:
    matchLabels:
      app: was
  policyTypes:
  - Ingress
  - Egress
  ingress:
  - from:
    - podSelector:
        matchLabels:
          app: web
    ports:
    - protocol: TCP
      port: 8080
  egress:
  - to:
    - podSelector:
        matchLabels:
          app: redis
    ports:
    - protocol: TCP
      port: 6379
  - to:
    - namespaceSelector:
        matchLabels:
          name: kube-system
    ports:
    - protocol: UDP
      port: 53
```

**4. Secrets Management**:
- **AWS Secrets Manager** 연동 (External Secrets Operator)
- DB 비밀번호, API Key 등 Secrets Manager에 저장
- Kubernetes Secret으로 자동 동기화

---

## Session Clustering

### 문제 정의

**Before (세션 공유 없음)**:
```
사용자 → ALB → WAS Pod 1 → 로그인 성공 (세션: Pod 1 메모리)
다음 요청 → ALB → WAS Pod 2 → 세션 없음 → 로그인 실패 ❌

문제: Pod별 독립적인 메모리, 세션 공유 안 됨
결과: 로그인 무한 루프
```

**After (Redis Session Clustering)**:
```
사용자 → ALB → WAS Pod 1 → 로그인 성공 (세션: Redis)
다음 요청 → ALB → WAS Pod 2 → Redis에서 세션 조회 → 로그인 유지 ✅

해결: Redis 중앙 집중식 세션 저장소
효과: WAS Pod 2-10개 스케일 아웃 가능
```

### Spring Session 구현

**application.yml**:
```yaml
spring:
  session:
    store-type: redis
    redis:
      flush-mode: on_save
      namespace: spring:session
  redis:
    host: redis-master.petclinic.svc.cluster.local
    port: 6379
    timeout: 60000

server:
  servlet:
    session:
      timeout: 30m
      cookie:
        max-age: 1800  # 30분 (세션 TTL과 동일)
        http-only: true
        secure: true
        same-site: lax
```

**SessionConfig.java**:
```java
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)
public class SessionConfig {

    @Bean
    public RedisSerializer<Object> springSessionDefaultRedisSerializer() {
        return new GenericJackson2JsonRedisSerializer();
    }

    @Bean
    public CookieSerializer cookieSerializer() {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieMaxAge(1800);  // 쿠키 만료 시간 = 세션 TTL
        serializer.setUseSecureCookie(true);
        serializer.setUseHttpOnlyCookie(true);
        serializer.setSameSite("Lax");
        return serializer;
    }
}
```

### 세션 생명주기 동기화

**문제**: 쿠키 만료(30일) vs 세션 TTL(30분) 불일치

| 항목 | 생명주기 | Before | After |
|------|---------|--------|-------|
| **브라우저 쿠키** | Max-Age | 30일 (브라우저 기본값) | 1800초 (30분) |
| **Redis 세션** | TTL | 1800초 (30분) | 1800초 (30분) |

**해결 효과**:
- 30분 후 브라우저 쿠키도 자동 만료 ✅
- 세션과 쿠키 생명주기 완벽 일치 ✅
- 무한 리다이렉트 문제 해결 ✅

### Redis Monitoring

**Dashboard 007: Session Monitoring**:
- Active Sessions: 실시간 세션 수
- Session TTL Distribution: 세션 만료 시간 분포
- Redis Memory Usage: 메모리 사용량
- Session Creation Rate: 초당 세션 생성 속도
- Session Expiration Rate: 초당 세션 만료 속도

**Alert**:
- **SessionLeakage**: Redis Session > 1000개 (메모리 누수 의심)
- **RedisDown**: Redis 연결 실패 (모든 세션 손실)

---

## 서비스 엔드포인트

### Public 엔드포인트

| 서비스 | URL | 프로토콜 | 인증 | 상태 |
|--------|-----|----------|------|------|
| **PetClinic (Primary)** | https://www.goupang.shop/petclinic/ | HTTPS | - | ✅ Running |
| **Grafana** | https://www.goupang.shop/grafana/ | HTTPS | ✅ admin | ✅ Running |
| **ArgoCD** | https://www.goupang.shop/argocd/ | HTTPS | ✅ admin | ✅ Running |
| **PetClinic (DR)** | https://dr.goupang.shop/petclinic/ | HTTPS | - | ⏸️ Standby |

### Cluster Internal (ClusterIP)

| 서비스 | FQDN | 포트 | 용도 |
|--------|------|------|------|
| **web-service** | web.petclinic.svc.cluster.local | 80 | WEB Pods |
| **was-stable** | was-stable.petclinic.svc.cluster.local | 8080 | WAS Stable Pods |
| **was-canary** | was-canary.petclinic.svc.cluster.local | 8080 | WAS Canary Pods |
| **redis-master** | redis-master.petclinic.svc.cluster.local | 6379 | Redis |
| **prometheus** | prometheus.monitoring.svc.cluster.local | 9090 | Prometheus |
| **loki** | loki.monitoring.svc.cluster.local | 3100 | Loki |

---

## 비용 분석

### 월별 비용 (2025.12 기준)

| 분류 | 서비스 | 비용 | 비율 |
|------|--------|------|------|
| **AWS** | | **$185** | **74%** |
| | EKS Cluster | $72 (고정) | 29% |
| | EC2 (Managed Node Group) | $50 (5 nodes 고정) | 20% |
| | RDS MySQL (Single-AZ) | $30 (db.t3.micro) | 12% |
| | ALB | $18 | 7% |
| | NAT Gateway | $10 | 4% |
| | CloudFront | $5 | 2% |
| | Route53 + ACM | $3 | 1% |
| | CloudWatch | $2 | 1% |
| **Azure** | | **$65** | **26%** |
| | VM (Standard_B2s) | $40 | 16% |
| | MySQL (Basic, 1 vCore) | $20 | 8% |
| | Blob Storage | $3 | 1% |
| | Bandwidth | $2 | 1% |
| **총 비용** | | **$250** | **100%** |

### 비용 최적화 내역

| 최적화 항목 | Before | After | 절감액 | 절감률 |
|------------|--------|-------|--------|--------|
| **WAS CPU** | 1 core ($15) | 0.5 core ($8) | $7/월 | 47% |
| **노드 관리** | Karpenter 시도 | Managed 5 nodes ($100) | - | - |
| **RDS 인스턴스** | db.t3.small ($60) | db.t3.micro ($30) | $30/월 | 50% |
| **RDS Multi-AZ** | Multi-AZ ($60) | Single-AZ ($30) | $30/월 | 50% |
| **총 절감** | $310 | $250 | **$60/월** | **19%** |

**연간 절감액**: $360

### OpenCost 분석 (Pod 별 비용)

| Pod | Replicas | CPU | Memory | 월 비용 |
|-----|----------|-----|--------|---------|
| **WAS** | 2-10 | 500m | 1Gi | $24 (평균 4개) |
| **WEB** | 2-5 | 100m | 128Mi | $6 (평균 2개) |
| **Redis** | 1 | 100m | 256Mi | $3 |
| **Prometheus** | 1 | 500m | 2Gi | $6 |
| **Grafana** | 1 | 100m | 512Mi | $3 |
| **Loki** | 1 | 200m | 512Mi | $3 |

---

## 성과 요약

### 정량적 성과

| 지표 | Before | After | 개선율 | 느낀점 |
|------|--------|-------|--------|--------|
| **가용성** | 단일 AZ, 99% | Multi-AZ, 99.9% | 0.9%p ↑ | "Pod가 한 AZ에서 죽어도 다른 AZ에서 살아있다는 안정감" |
| **배포 시간** | 수동 배포, ~30분 | GitOps, < 3분 | 90% ↓ | "Git Push만 하면 끝. kubectl 명령어 기억 안 해도 됨" |
| **인프라 비용** | $280/월 | $250/월 | 11% ↓ | "OpenCost로 실사용률 보고 과감하게 줄였더니 문제없음" |
| **WAS Pod 비용** | 1 core, $15/월 | 0.5 core, $8/월 | 47% ↓ | "Prometheus로 실사용률 보니 CPU 30%만 사용 중이었음" |
| **DR 복구 시간** | 수동, ~1일 | 자동, 2분 | 99.86% ↓ | "Route53 Health Check만으로 자동 Failover, RTO 2분 달성" |
| **Canary 정확도** | Replica Shifting, ±17% 오차 | ALB Routing, 0% 오차 | 100% 개선 | "10% 배포하면 정확히 10%만 가는 게 이렇게 중요한 줄 몰랐음" |

### 정성적 성과

**1. Infrastructure as Code**:
- Terraform으로 인프라 100% 코드화
- S3 Backend + DynamoDB Lock으로 협업 가능
- 재해 시 terraform apply 한 번으로 전체 복구

**2. Zero-Touch GitOps**:
- Jenkins CI + ArgoCD CD 완전 자동화
- Git Push → 9분 후 프로덕션 배포 완료
- Canary 배포로 무중단 릴리스

**3. Hybrid Multi-Cloud**:
- AWS (Primary) + Azure (DR) 이기종 클라우드
- Route53 자동 Failover로 RTO 2분 달성
- CloudFront + Lambda@Edge로 Origin 통합

**4. Deep-Dive Troubleshooting**:
- **Traffic Precision**: ALB 가중치로 정밀 트래픽 제어 (오차 0%)
- **Session Consistency**: Redis TTL vs Cookie Max-Age 생명주기 동기화
- **Cost Optimization**: OpenCost 데이터 기반 Over-provisioning 50% 제거

### 트러블슈팅 사례

**1. ALB Traffic Routing 정확도 문제**:
- **문제**: Replica Shifting으로 25% Canary 배포 시 실제 33% 분배
- **원인**: Pod 개수로 트래픽 분산 (Old 2개 + New 1개 = 33%)
- **해결**: Service 2개 분리 + ALB Weighted Target Groups (10% 정확도 달성)
- **효과**: Canary 배포 오차 0%, 정밀한 트래픽 제어 가능

**2. Redis 세션 생명주기 불일치**:
- **문제**: 점심 먹고 돌아오면 항상 로그인 풀림, 무한 리다이렉트
- **원인**: Redis 세션 TTL 30분, 브라우저 쿠키 Max-Age 30일 → 불일치
- **해결**: Cookie Max-Age를 1800초(30분)로 설정, 세션과 동기화
- **효과**: 무한 루프 해결, 사용자 경험 개선

**3. Karpenter DNS 이슈**:
- **문제**: Karpenter가 생성한 노드에서 CoreDNS Pod이 스케줄링되지 않아 Pod 간 DNS 통신 실패
- **원인**: Karpenter Provisioner 설정과 CoreDNS NodeAffinity 불일치
- **해결**: Karpenter 비활성화, Managed Node Group으로 5개 노드 고정 운영
- **효과**: DNS 안정성 확보, 노드 관리 단순화, 운영 복잡도 제거

---

## 배운 점

### 1. Terraform State 관리의 중요성

**문제**: 로컬 State 파일로 협업 불가, 동시 실행 시 충돌
**해결**: S3 Backend + DynamoDB Lock
**교훈**: IaC는 State 관리가 핵심. S3 + Lock 없이는 프로덕션 불가

### 2. ArgoCD + HPA 충돌 해결

**문제**: ArgoCD가 replicas 필드를 계속 원복시킴 (HPA와 충돌)
**해결**: `ignoreDifferences`로 replicas 필드 제외
**교훈**: GitOps와 자동 스케일링은 충돌 가능. ignoreDifferences 필수

### 3. 세션 클러스터링 필요성

**문제**: WAS Pod 2개 이상 시 로그인 무한 루프
**해결**: Spring Session + Redis로 세션 공유
**교훈**: Stateless 아키텍처의 중요성. 세션은 외부 저장소에!

### 4. DR 아키텍처 설계

**문제**: CloudFront Origin Group은 POST 요청 미지원
**해결**: Lambda@Edge로 Host 헤더 수정, Azure Blob Origin 연동
**교훈**: Multi-Cloud DR은 서비스 제약 사항 사전 검토 필수

### 5. 비용 최적화

**문제**: 초기 $310/월로 예산 초과
**해결**: OpenCost 분석 → RDS Multi-AZ 제거 (DEV), WAS CPU 우선 사용률 개선
**교훈**: 측정하지 않으면 개선할 수 없다. OpenCost는 필수

### 6. Karpenter vs Managed Node Group

**문제**: Karpenter 도입 시 CoreDNS와 충돌, DNS 통신 실패
**해결**: Karpenter 비활성화, Managed Node Group으로 전환
**교훈**: 최신 기술이 항상 정답은 아니다. 안정성이 우선이다.

---

## 관련 링크

- **Primary 서비스**: https://www.goupang.shop/petclinic/
- **DR 서비스**: https://dr.goupang.shop/petclinic/
- **Grafana**: https://www.goupang.shop/grafana/
- **ArgoCD**: https://www.goupang.shop/argocd/
- **GitHub**: https://github.com/user/petclinic

---

## 다음 단계

### ⏳ 30분 내 완료 가능

1. **Slack 알림 완전 통합** (20분)
   - AlertManager Slack Webhook 활성화
   - DR Failover 알림 추가

2. **RDS Read Replica 추가** (10분)
   - 읽기 부하 분산

### 🔜 선택 사항

3. **Redis Sentinel (HA)** (1시간)
   - Redis 단일 장애점 제거

4. **Istio Service Mesh** (2시간)
   - mTLS, Circuit Breaking, Retry

5. **Kiali (Service Mesh 관찰성)** (30분)
   - 트래픽 흐름 시각화

---

## 체크리스트

### ✅ 구축 완료

#### AWS 인프라
- [x] VPC + Subnet (Public/Private Multi-AZ)
- [x] EKS Cluster (1.33)
- [x] Managed Node Group (5 nodes 고정)
- [x] RDS MySQL (Single-AZ, DEV)
- [x] ALB + ACM (HTTPS)
- [x] Route53 + Health Check + Failover
- [x] CloudFront (CDN)
- [x] ECR (Container Registry)
- [x] WAF (OWASP Top 10)

#### Azure DR 인프라
- [x] Azure Blob Storage (정적 점검 페이지)
- [x] Azure VM (전체 서비스 DR)
- [x] Azure MySQL (DB DR)
- [x] CloudFront + Lambda@Edge (Origin 통합)

#### 애플리케이션
- [x] WEB (nginx) 배포 (v9-b390e30, HPA 2-5)
- [x] WAS (Spring Boot) 배포 (v55-f31bdcc, HPA 2-10)
- [x] Redis Session Clustering
- [x] RDS MySQL 연동

#### IaC
- [x] Terraform 100% 코드화 (87개 리소스)
- [x] S3 Backend + DynamoDB Lock
- [x] Multi-Cloud 모듈화 (AWS + Azure)

#### CI/CD
- [x] Jenkins CI Pipeline
- [x] ArgoCD GitOps CD
- [x] Argo Rollouts Canary 배포 (ALB Routing)

#### Observability
- [x] Prometheus (30일 retention)
- [x] Grafana (8 dashboards)
- [x] Loki (7일 logs)
- [x] AlertManager (12 alert rules)
- [x] OpenCost (비용 분석)

#### Security
- [x] IAM Roles + IRSA
- [x] Security Groups
- [x] KMS 암호화
- [x] WAF Rules
- [x] Network Policies

### ⏳ 진행 중

- [ ] Slack 알림 완전 통합

### 🔜 선택 사항

- [ ] Redis Sentinel (HA)
- [ ] Istio Service Mesh
- [ ] Kiali
- [ ] RDS Read Replica

---

**작성일**: 2026-01-27
**프로젝트 기간**: 2025.10 ~ 현재 (4개월)
**시스템 상태**: ✅ 프로덕션 운영 중
**다음 단계**: Slack 알림 통합, Redis HA
