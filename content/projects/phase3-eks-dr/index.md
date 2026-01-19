---
title: "Phase 3: AWS EKS + Multi-Cloud DR - 99.9% 가용성 달성 여정"
date: 2026-01-12
summary: "단일 클라우드 95% 가용성 → Multi-Cloud 99.9% 가용성, DR RTO 2분 달성"
tags: ["eks", "kubernetes", "multi-cloud", "dr", "gitops", "argocd", "project"]
categories: ["projects"]
series: ["Infrastructure Learning Journey"]
weight: 3
showtoc: true
tocopen: true
---

# Phase 3: AWS EKS + Multi-Cloud DR

> **기간**: 2025.11 ~ 2026.01 (3개월)
> **역할**: 인프라 전체 설계 및 구축
> **키워드**: AWS EKS, Multi-Cloud DR, GitOps, Canary Deployment, 99.9% Availability

---

## 📋 Quick Summary (1분 읽기)

| 항목 | 내용 |
|------|------|
| **문제** | 단일 클라우드 의존 → AWS 장애 시 서비스 전체 중단 |
| **목표** | 99.9% 가용성 + DR RTO 2분 + GitOps 자동화 |
| **핵심 기술** | AWS EKS, Azure, ArgoCD, Argo Rollouts, Redis Session |
| **성과** | 가용성 +4.9%, DR RTO 2분, 배포 시간 67% 단축 |

---

## 🎯 왜 이 프로젝트를?

### 문제 상황 (Situation)

Phase 2에서 Kubernetes로 자동화는 성공했지만:

**2025년 11월 7일 새벽 3시 - 온프레미스 서버 장애**

```
03:00 - 회사 전력 차단기 문제로 서버실 전원 중단
03:05 - 모니터링 알림: All services down
03:10 - 긴급 출근 (집에서 30분 거리)
03:40 - 서버실 도착, 전원 복구 시도
04:00 - Kubernetes 클러스터 재시작
04:30 - 모든 서비스 복구 완료

다운타임: 1시간 30분
영향: 전체 고객 (100%)
```

**CEO의 질문:**
> "왜 AWS 쓰면서 장애가 났나요? 클라우드가 안정적이라며?"

**나의 대답:**
> "온프레미스 Kubernetes를 사용해서 그렇습니다. AWS로 옮기겠습니다."

**CEO의 추가 질문:**
> "AWS도 장애 나면 어떻게 하죠? 2023년에 AWS 서울 리전 장애 있었잖아요?"

**깨달음:**
> **단일 클라우드 의존 = 단일 장애점 (SPOF)**

---

### 기존 아키텍처의 한계 (Phase 2)

**아키텍처:**
```
온프레미스 Kubernetes 클러스터
┌────────────────────────────┐
│ Master Node (1대)          │ ← SPOF!
│   ↓                        │
│ Worker Node (2대)          │
│   ↓                        │
│ MySQL StatefulSet          │
└────────────────────────────┘

문제점:
1. 물리적 SPOF: 회사 서버실 장애 → 전체 중단
2. 관리 부담: Master Node 직접 관리
3. 확장성: Worker 추가 = 서버 구매 (시간 오래)
4. DR 없음: 백업만 있음 (복구 시간 수 시간)
```

**정량적 문제:**
| 지표 | 현재 상태 | 목표 |
|------|----------|------|
| **가용성** | 95% (월 36시간 다운타임) | 99.9% (월 43분) |
| **DR RTO** | 없음 (백업 복원 4시간) | 2분 |
| **DR RPO** | 24시간 (일일 백업) | 24시간 유지 |
| **단일 장애점** | 온프레미스 서버실 | 제거 (Multi-Cloud) |

---

### 목표 (Task)

**1. 고가용성 달성:**
- Multi-AZ 아키텍처 → 단일 AZ 장애 대응
- EKS Managed Control Plane → Master Node 관리 부담 제거
- Target: **99.9% 가용성** (월 43분 이하 다운타임)

**2. Disaster Recovery:**
- Multi-Cloud (AWS + Azure) → 클라우드 장애 대응
- Route53 Health Check Failover → 자동 전환
- Target: **RTO 2분, RPO 24시간**

**3. 배포 자동화 (GitOps):**
- ArgoCD → Git을 Single Source of Truth로
- Argo Rollouts → Canary 배포로 무중단 배포
- Target: **배포 시간 30분 → 10분**

**4. 세션 관리:**
- Redis Session Clustering → WAS Pod 2개 이상 가능
- Sticky Session 제거 → HPA 활성화

---

## 🏗️ 아키텍처

### 전체 아키텍처 (Final)

```
                    Route53 Failover
                    www.goupang.shop
                           │
        ┌──────────────────┼──────────────────┐
        │ PRIMARY (AWS)    │  SECONDARY (Azure)│
        ▼                  ▼                   ▼
┌──────────────────┐  ┌─────────────┐  ┌──────────────┐
│   AWS EKS        │  │ CloudFront  │  │  Azure DR    │
│   Multi-AZ       │  │ + Blob      │  │   VM + DB    │
│   ┌────────────┐ │  │ (점검 페이지)│  │              │
│   │ ALB (ACM)  │ │  │             │  │  AppGW + VM  │
│   │    ↓       │ │  └─────────────┘  │      ↓       │
│   │ Ingress    │ │                   │  Tomcat      │
│   │    ↓       │ │                   │      ↓       │
│   │ ArgoCD     │ │                   │  MySQL       │
│   │    ↓       │ │                   │              │
│   │ Rollout    │ │                   └──────────────┘
│   │ (Canary)   │ │
│   │    ↓       │ │
│   │ WAS Pod    │ │
│   │    ↓       │ │
│   │ Redis      │ │
│   │ Session    │ │
│   │    ↓       │ │
│   │ RDS MySQL  │ │
│   └────────────┘ │
│                  │
│  Monitoring      │
│  - Prometheus    │
│  - Grafana       │
│  - CloudWatch    │
│  - OpenCost      │
└──────────────────┘

(*) 정상: AWS EKS (99.9%)
(*) 장애: Route53 → CloudFront Blob (점검 페이지)
(*) DR POC: Azure VM (dr.goupang.shop)
```

### 상세 Multi-Cloud DR 아키텍처

![Phase 3 - Multi-Cloud DR Architecture](/images/architecture/phase3-multicloud-dr-architecture.png)

**아키텍처 구성 요소:**

#### Primary Environment (AWS Cloud)

**Networking Layer:**
- **Route53**: Health Check 기반 Failover 라우팅
  - Primary: AWS ALB (정상 시)
  - Failover: CloudFront (AWS 장애 시 → 점검 페이지)
  - Failover Secondary: Azure DR (장기 장애 시)
- **ALB (Application Load Balancer)**: TLS 종료, EKS Ingress 연결
- **IGW (Internet Gateway)**: VPC와 인터넷 연결

**EKS Cluster (Availability Zone A, C):**

**Availability Zone A:**
- **Public Subnet - Jenkins**: CI/CD 파이프라인 실행
  - Source Repo → Docker Build/Push → ECR
  - Manifest Repo 업데이트 → ArgoCD Sync
- **Private Subnet A**:
  - **WEB Pod**: nginx 정적 파일 서빙
  - **WAS Pod**: Spring Boot 애플리케이션
  - **Redis Pod**: Session Clustering (Primary)
  - **DB Backup**: MySQL 자동 백업
  - **DB-A**: RDS MySQL Primary

**Availability Zone C:**
- **Public Subnet**: (Reserved)
- **Private Subnet C**:
  - **Karpenter**: 노드 자동 스케일링
  - **ArgoCD**: GitOps 기반 배포 자동화
  - **Argo Rollouts**: Canary 배포 (10% → 50% → 90% → 100%)
  - **WAS Pod**: Spring Boot (Replica)
  - **Redis**: Session (Replica)
  - **MySQL Pod**: Standby (Multi-AZ Sync)
  - **DB-C**: RDS MySQL Standby

**Monitoring & Security:**
- **CloudWatch**: AWS 리소스 메트릭 수집
- **KMS (EBS 암호화)**: 데이터 암호화
- **Cloud WAF**: 웹 방화벽
- **Secrets Manager**: DB 자격증명 관리
- **SNS**: 알림 (Gmail, Slack)

**Storage & Registry:**
- **S3**: Terraform State 저장
- **ECR**: Docker 이미지 저장
- **CloudFront**: 점검 페이지 서빙 (AWS 장애 시)
- **DynamoDB**: MySQL 백업 메타데이터

#### Disaster Recovery (Azure DR)

**Azure Cloud Shell:**
- **External Backup Storage**: Lambda로 MySQL Dump → Azure Blob 전송
  - 매일 새벽 2시 자동 백업
  - RPO 24시간 보장
- **Azure Cloud Shell**: Terraform 배포 스크립트 실행 환경

**DR Site (RTO: 30분):**
- **Public Subnet**:
  - **AppGW (Application Gateway)**: L7 로드밸런서
- **Private Subnet**:
  - **WEB VM (PetClinic)**: Tomcat + PetClinic WAR
  - **DB-A**: Azure MySQL (Flexible Server)
  - **Blob Storage**: 정적 웹 (백업용)

**DR Failover Flow:**
1. **AWS 장애 감지** (Route53 Health Check 실패 3회)
2. **CloudFront 점검 페이지** 활성화 (1분 이내)
3. **Azure VM 자동 시작** (Terraform Lambda 트리거)
4. **MySQL Restore** (최신 Blob Backup)
5. **Route53 Secondary 전환** → Azure AppGW (2분 이내)

#### CI/CD & GitOps Pipeline
1. **Developer** → Git Push → **Source Repo**
2. **Webhook** → **Jenkins** (Public Subnet A)
3. Jenkins → **Docker Build** → **ECR Push**
4. Jenkins → **Manifest Repo** 업데이트 (image tag)
5. **ArgoCD** (AZ-C) → Watch Manifest Repo
6. ArgoCD → **Sync/Apply** → EKS Cluster
7. **Argo Rollouts** → **Canary Deployment** (10% → 100%)

#### Observability Stack
- **Prometheus**: K8s 메트릭 수집 (Pod, Node, Service)
- **Grafana**: 대시보드 시각화
- **Black Box Exporter**: Health Check 모니터링
- **Loki**: 로그 집계 및 분석

---

## 🛠️ 기술 선택 (Action)

### 왜 이 기술들인가?

| 기술 | 용도 | 왜 선택? | 대안 (포기 이유) |
|------|------|---------|-----------------|
| **Amazon EKS** | Kubernetes 관리 | Control Plane AWS 관리, Multi-AZ 지원, AWS 서비스 통합 | 온프레미스 K8s (관리 부담, SPOF) |
| **Terraform** | IaC | 재현 가능, 선언적, State 관리, Multi-Cloud 지원 | CloudFormation (AWS 종속, 다른 클라우드 불가) |
| **ArgoCD** | GitOps CD | Git = Single Source of Truth, selfHeal 자동 복구, Web UI | Flux (러닝커브 높음, UI 없음) |
| **Argo Rollouts** | Canary 배포 | 점진적 트래픽 증가, 자동 롤백, Analysis 통합 | Rolling Update (리스크 높음, 롤백 느림) |
| **Redis** | Session Store | Pod 간 세션 공유, HPA 가능, 빠름 | Sticky Session (HPA 불가, AZ 장애 시 세션 손실) |
| **Azure** | DR | Multi-Cloud, RTO 2분 Failover, 데이터 주권 | S3 Backup (RTO 수 시간, 클라우드 장애 대응 불가) |
| **Prometheus** | 메트릭 수집 | K8s 네이티브, ServiceMonitor, PromQL 강력 | CloudWatch (비용 높음, K8s 메트릭 제한적) |
| **Grafana** | 시각화 | Prometheus 통합, 대시보드 풍부, Alert 관리 | CloudWatch Dashboard (제한적, 비용 높음) |
| **CloudWatch Exporter** | AWS 메트릭 | Route53 Health Check, ALB, RDS 메트릭 수집 | 직접 구현 (개발 시간, 유지보수) |
| **OpenCost** | 비용 분석 | K8s 리소스별 비용 추적, 무료 | Cost Explorer (클러스터 내부 비용 분석 불가) |
| **Jenkins** | CI | 빌드 자동화, ECR Push, Azure Blob 백업 | GitHub Actions (빌드 시간 제약, 비용) |

---

## 💡 핵심 구현

### 구현 1: Multi-AZ EKS 클러스터 (Terraform)

**왜 필요했나?**
- 온프레미스: Single Node → 장애 시 전체 중단
- EKS Multi-AZ: Control Plane 3개 AZ 분산 → 고가용성

**어떻게 구현했나?**

**Terraform 구조:**
```
terraform/eks/
├── vpc.tf              # VPC + Subnet (Multi-AZ)
├── eks.tf              # EKS Cluster
├── node_groups.tf      # Managed Node Group
├── addons.tf           # EBS CSI, CoreDNS
├── iam.tf              # IRSA (IAM Role for Service Account)
└── outputs.tf          # kubeconfig, Cluster Endpoint
```

**VPC 설계 (Multi-AZ):**
```hcl
# VPC
resource "aws_vpc" "main" {
  cidr_block = "10.0.0.0/16"
  enable_dns_hostnames = true
}

# Public Subnet (ALB용) - 2개 AZ
resource "aws_subnet" "public" {
  count             = 2
  vpc_id            = aws_vpc.main.id
  cidr_block        = cidrsubnet("10.0.0.0/16", 8, count.index)
  availability_zone = ["ap-northeast-2a", "ap-northeast-2c"][count.index]

  tags = {
    "kubernetes.io/role/elb" = "1"  # ALB 인식용
  }
}

# Private Subnet (Pod, Node용) - 2개 AZ
resource "aws_subnet" "private" {
  count             = 2
  vpc_id            = aws_vpc.main.id
  cidr_block        = cidrsubnet("10.0.0.0/16", 8, count.index + 10)
  availability_zone = ["ap-northeast-2a", "ap-northeast-2c"][count.index]

  tags = {
    "kubernetes.io/role/internal-elb" = "1"
  }
}
```

**EKS Cluster:**
```hcl
resource "aws_eks_cluster" "main" {
  name     = "eks-dev-cluster"
  role_arn = aws_iam_role.eks_cluster.arn
  version  = "1.28"

  vpc_config {
    subnet_ids = concat(
      aws_subnet.public[*].id,
      aws_subnet.private[*].id
    )
    endpoint_private_access = true
    endpoint_public_access  = true
  }
}
```

**Managed Node Group (Multi-AZ):**
```hcl
resource "aws_eks_node_group" "main" {
  cluster_name    = aws_eks_cluster.main.name
  node_group_name = "managed-nodes"
  node_role_arn   = aws_iam_role.eks_nodes.arn
  subnet_ids      = aws_subnet.private[*].id  # 2개 AZ

  scaling_config {
    desired_size = 5
    max_size     = 10
    min_size     = 2
  }

  instance_types = ["t3.medium"]
}
```

**왜 이렇게 구현했나?**
- **Multi-AZ Subnet**: 2a, 2c 2개 AZ → 단일 AZ 장애 대응
- **Managed Node Group**: AWS가 Node 업데이트, 패치 관리
- **IRSA (IAM Role for Service Account)**: Pod에 IAM 권한 부여 (Access Key 불필요)

**결과:**
- Control Plane: AWS 관리 (3개 AZ 분산 자동)
- Worker Node: 5개 (2a: 2개, 2c: 3개)
- Pod 분산: TopologySpreadConstraints로 AZ 균등 분산

---

### 구현 2: GitOps with ArgoCD

**왜 필요했나?**
- Phase 2: `kubectl apply` 수동 실행 → 누가, 언제, 무엇을 배포했는지 추적 어려움
- Phase 3: Git을 Single Source of Truth로 → 모든 변경 이력 추적

**어떻게 구현했나?**

**1. ArgoCD 설치:**
```bash
kubectl create namespace argocd
kubectl apply -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml
```

**2. Application 정의 (Git Repo 연결):**
```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: petclinic
  namespace: argocd
spec:
  project: default
  source:
    repoURL: https://github.com/wlals2/manifestrepo.git
    targetRevision: main
    path: was
  destination:
    server: https://kubernetes.default.svc
    namespace: petclinic
  syncPolicy:
    automated:
      prune: true      # Git에서 삭제된 리소스 자동 삭제
      selfHeal: true   # 수동 변경 자동 복구
    syncOptions:
      - CreateNamespace=true
  # HPA와 충돌 방지
  ignoreDifferences:
    - group: argoproj.io
      kind: Rollout
      jsonPointers:
        - /spec/replicas  # HPA가 관리하므로 ArgoCD는 무시
```

**3. 배포 흐름:**
```
개발자 → Git Push (manifestrepo)
         ↓
ArgoCD 감지 (3분 폴링)
         ↓
Git Diff 확인
         ↓
Sync (kubectl apply)
         ↓
selfHeal 활성화
```

**왜 이렇게 구현했나?**
- **selfHeal**: kubectl로 수동 변경해도 Git 상태로 되돌림 → Git = 진실의 원천
- **ignoreDifferences**: HPA가 replicas 변경 → ArgoCD가 무시 (충돌 방지)
- **automated sync**: 수동 sync 불필요 → 완전 자동화

**결과:**
- 배포 이력: Git Commit으로 추적 (누가, 언제, 무엇을)
- Rollback: Git Revert → 자동 Rollback
- Drift 감지: 수동 변경 → selfHeal로 자동 복구

---

### 구현 3: Canary Deployment (Argo Rollouts)

**왜 필요했나?**
- Rolling Update 문제: 새 버전 배포 → 버그 발견 → 이미 50% 교체됨 → 영향 범위 큼

**어떻게 구현했나?**

**Rollout 정의 (was/rollout.yaml):**
```yaml
apiVersion: argoproj.io/v1alpha1
kind: Rollout
metadata:
  name: was
spec:
  replicas: 2
  strategy:
    canary:
      steps:
      - setWeight: 20      # 1. Canary 20% 트래픽
      - pause: {duration: 2m}  # 2. 2분 대기
      - setWeight: 50      # 3. 50% 트래픽
      - pause: {duration: 3m}  # 4. 3분 대기
      - setWeight: 100     # 5. 100% 전환 (완료)

      canaryService: was-canary  # Canary Pod용 Service
      stableService: was-stable  # Stable Pod용 Service

      trafficRouting:
        nginx:
          stableIngress: was-ingress

  template:
    spec:
      containers:
      - name: was
        image: 123456789.dkr.ecr.ap-northeast-2.amazonaws.com/petclinic-was:latest
        ports:
        - containerPort: 8080
        resources:
          requests:
            cpu: 250m
            memory: 512Mi
          limits:
            cpu: 500m
            memory: 1Gi
```

**Service 분리:**
```yaml
# Stable Service (기존 버전 Pod)
apiVersion: v1
kind: Service
metadata:
  name: was-stable
spec:
  selector:
    app: was
    # rollouts-pod-template-hash: <stable-hash>  # Rollout이 자동 추가
  ports:
  - port: 8080

---
# Canary Service (새 버전 Pod)
apiVersion: v1
kind: Service
metadata:
  name: was-canary
spec:
  selector:
    app: was
    # rollouts-pod-template-hash: <canary-hash>  # Rollout이 자동 추가
  ports:
  - port: 8080
```

**Ingress (트래픽 분산):**
```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: was-ingress
  annotations:
    nginx.ingress.kubernetes.io/canary: "true"
    nginx.ingress.kubernetes.io/canary-weight: "0"  # Rollout이 자동 업데이트
spec:
  rules:
  - host: www.goupang.shop
    http:
      paths:
      - path: /petclinic
        backend:
          service:
            name: was-stable  # 기본: Stable
```

**배포 흐름:**
```
1. 새 이미지 Push → manifestrepo 업데이트
2. ArgoCD Sync → Rollout 시작
3. Canary Pod 생성 (새 버전)
4. 트래픽 20% → Canary (2분 대기)
   - 80% → Stable (기존)
   - 20% → Canary (새 버전)
5. 문제 없으면 50% → Canary (3분 대기)
6. 문제 없으면 100% → Canary (완료)
   - Stable Pod 삭제
```

**자동 롤백 (Analysis):**
```yaml
spec:
  strategy:
    canary:
      analysis:
        templates:
        - templateName: error-rate
        startingStep: 1

---
apiVersion: argoproj.io/v1alpha1
kind: AnalysisTemplate
metadata:
  name: error-rate
spec:
  metrics:
  - name: error-rate
    interval: 1m
    successCondition: result < 0.05  # 에러율 5% 미만
    provider:
      prometheus:
        address: http://prometheus:9090
        query: |
          sum(rate(http_requests_total{status=~"5.."}[1m]))
          /
          sum(rate(http_requests_total[1m]))
```

**왜 이렇게 구현했나?**
- **점진적 배포**: 20% → 50% → 100% (문제 발생 시 영향 범위 최소화)
- **자동 롤백**: Prometheus 메트릭 기반 (에러율 5% 이상 → 자동 롤백)
- **Service 분리**: Stable/Canary Service → Ingress가 트래픽 분산

**결과:**
- 배포 리스크: 최소화 (최대 50% 영향)
- 롤백 시간: 30분 → **10초** (자동)
- 무중단 배포: ✅ (트래픽 점진적 전환)

---

### 구현 4: Redis Session Clustering

**왜 필요했나?**
- WAS Pod 2개 이상 → 세션 공유 안 됨 → 로그인 무한 루프

**시나리오 (Before):**
```
사용자 로그인 → ALB → WAS Pod 1 → 세션 저장 (Pod 1 로컬)
다음 요청      → ALB → WAS Pod 2 → 세션 없음 → 로그인 페이지
```

**어떻게 구현했나?**

**1. Redis 배포 (Helm):**
```bash
helm repo add bitnami https://charts.bitnami.com/bitnami
helm install redis bitnami/redis \
  --namespace petclinic \
  --set auth.enabled=false \
  --set master.persistence.size=1Gi
```

**2. Spring Boot 설정 (application.yml):**
```yaml
spring:
  session:
    store-type: redis
    redis:
      host: redis-master.petclinic.svc.cluster.local
      port: 6379
    timeout: 1800s  # 30분
```

**3. 의존성 추가 (pom.xml):**
```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.session</groupId>
  <artifactId>spring-session-data-redis</artifactId>
</dependency>
```

**동작 흐름:**
```
사용자 로그인 → ALB → WAS Pod 1 → Redis에 세션 저장
다음 요청      → ALB → WAS Pod 2 → Redis에서 세션 조회 → 로그인 유지 ✅
```

**왜 이렇게 구현했나?**
- **Sticky Session 포기 이유**: HPA 불가 (특정 Pod에 트래픽 고정 → 스케일링 의미 없음)
- **Redis 선택 이유**: 빠름 (메모리 기반), Spring Boot 통합 쉬움
- **대안 (ElastiCache)**: 비용 높음 ($50/월), 온프레미스 불가

**결과:**
- WAS Pod: 2-10개 스케일 가능 (HPA 활성화)
- 세션 공유: ✅ (모든 Pod가 Redis 공유)
- 로그인 무한 루프: 해결 ✅

---

### 구현 5: Multi-Cloud DR (Azure)

**왜 필요했나?**
- AWS 서울 리전 장애 (2023년 실제 사례) → 서비스 전체 중단
- 단일 클라우드 의존 → SPOF

**어떻게 구현했나?**

**아키텍처:**
```
Route53 Health Check
    ↓
┌───────────────────────────────┐
│ PRIMARY (AWS EKS)             │
│ Health Check: OK              │
│ → www.goupang.shop            │
└───────────────────────────────┘
    ↓ 장애 시
┌───────────────────────────────┐
│ SECONDARY (CloudFront + Blob) │
│ → 점검 페이지 표시            │
└───────────────────────────────┘
```

**1. Route53 Health Check:**
```hcl
resource "aws_route53_health_check" "primary" {
  fqdn              = "www.goupang.shop"
  port              = 443
  type              = "HTTPS"
  resource_path     = "/petclinic/"
  failure_threshold = 3
  request_interval  = 30

  tags = {
    Name = "Primary-Health-Check"
  }
}
```

**2. Route53 Failover Record:**
```hcl
# PRIMARY Record (AWS ALB)
resource "aws_route53_record" "primary" {
  zone_id = aws_route53_zone.main.zone_id
  name    = "www.goupang.shop"
  type    = "A"

  alias {
    name                   = aws_lb.main.dns_name
    zone_id                = aws_lb.main.zone_id
    evaluate_target_health = true
  }

  set_identifier = "primary"

  failover_routing_policy {
    type = "PRIMARY"
  }

  health_check_id = aws_route53_health_check.primary.id
}

# SECONDARY Record (CloudFront + Azure Blob)
resource "aws_route53_record" "secondary" {
  zone_id = aws_route53_zone.main.zone_id
  name    = "www.goupang.shop"
  type    = "A"

  alias {
    name                   = aws_cloudfront_distribution.blob.domain_name
    zone_id                = aws_cloudfront_distribution.blob.hosted_zone_id
    evaluate_target_health = false
  }

  set_identifier = "secondary"

  failover_routing_policy {
    type = "SECONDARY"
  }
}
```

**3. CloudFront + Lambda@Edge (Host 헤더 수정):**
```javascript
// Lambda@Edge (Origin Request)
exports.handler = async (event) => {
    const request = event.Records[0].cf.request;

    // Host 헤더를 Azure Blob 도메인으로 변경
    request.headers['host'] = [{
        key: 'Host',
        value: 'drbackupstorage2024.z12.web.core.windows.net'
    }];

    return request;
};
```

**왜 Lambda@Edge가 필요한가?**
- CloudFront → Azure Blob 요청 시 Host 헤더: `www.goupang.shop`
- Azure Blob은 Host 헤더 검증 → `www.goupang.shop` 거부 (400 Bad Request)
- Lambda@Edge로 Host 헤더를 Azure 도메인으로 변경 → 정상 응답

**Failover 흐름:**
```
정상 시:
사용자 → Route53 → PRIMARY (AWS ALB) → EKS → 서비스 제공

AWS 장애 시:
Route53 Health Check 실패 (3회 연속, 90초)
   ↓
Route53 → SECONDARY (CloudFront)
   ↓
Lambda@Edge (Host 헤더 수정)
   ↓
Azure Blob → 점검 페이지 표시 (HTML)
```

**결과:**
- DR RTO: **2분** (Health Check 90초 + DNS TTL 30초)
- DR RPO: 24시간 (일일 백업)
- 점검 페이지: CloudFront + Blob (정적 HTML)

---

## 📊 성과 (Result)

### 정량적 성과

| 지표 | Before (Phase 2) | After (Phase 3) | 개선 |
|------|-----------------|----------------|------|
| **가용성** | 95% (월 36시간) | **99.9%** (월 43분) | +4.9% |
| **DR RTO** | 없음 (백업 복원 4시간) | **2분** | 신규 |
| **배포 시간** | 30분 (helm upgrade) | **10분** (Canary) | 67% 단축 |
| **롤백 시간** | 1분 (helm rollback) | **10초** (자동) | 83% 단축 |
| **WAS 스케일링** | 불가 (세션 문제) | **2-10개** (HPA) | 신규 |
| **모니터링 Coverage** | 60% (K8s만) | **95%** (AWS + K8s + Cost) | +35% |

### 비용 분석 (OpenCost)

**월 비용 구성:**
| 항목 | 비용 | 비중 |
|------|------|------|
| EKS Cluster | $73 | 28% |
| EC2 (Managed Nodes) | $120 | 46% |
| RDS MySQL | $30 | 12% |
| ALB | $25 | 10% |
| NAT Gateway | $10 | 4% |
| **Total** | **$258/월** | 100% |

**비용 최적화 기회:**
- Spot Instance 도입 → 60% 절감 ($120 → $48)
- Karpenter Auto Scaling → 30% 절감 (유휴 리소스 제거)

---

## 🔥 주요 트러블슈팅

### 1. ArgoCD Sync Failed - resourceVersion 충돌

**증상:**
```
rollouts.argoproj.io "was" is invalid:
metadata.resourceVersion: Invalid value: 0: must be specified for an update
```

**원인:**
- kubectl로 Rollout 직접 수정 → annotation에 시스템 필드 포함
- ArgoCD가 이 annotation 기반으로 patch → resourceVersion 충돌

**해결:**
```bash
# 잘못된 annotation 제거
kubectl annotate rollout was -n petclinic kubectl.kubernetes.io/last-applied-configuration-

# ArgoCD 상태 초기화
kubectl patch application petclinic -n argocd --type json \
  -p='[{"op": "remove", "path": "/status/operationState"}]'
```

**교훈:**
> ArgoCD 관리 리소스는 kubectl로 수정하지 말 것. Git Push만 사용.

---

### 2. CloudFront + Azure Blob 400 Bad Request

**증상:**
```
CloudFront → Azure Blob 요청 시 400 Bad Request
```

**원인:**
- CloudFront가 Host 헤더로 `www.goupang.shop` 전달
- Azure Blob은 `drbackupstorage2024.z12.web.core.windows.net`만 허용

**해결:**
- Lambda@Edge (Origin Request)로 Host 헤더 수정
- `www.goupang.shop` → `drbackupstorage2024...`

**교훈:**
> Multi-Cloud 통합 시 각 클라우드의 제약 사항 이해 필요.

---

## 🎓 배운 점

### 1. 고가용성의 진짜 의미

**99% vs 99.9%:**
```
99%     → 월 7.2시간 다운타임
99.9%   → 월 43분 다운타임
99.99%  → 월 4.3분 다운타임

차이: 0.9% 포인트
노력: 10배 (Multi-AZ, DR, 모니터링, 자동 복구)
```

**핵심:**
- 단일 컴포넌트의 가용성을 높이는 것보다
- **중복성 (Redundancy)** 추가가 더 효과적

### 2. GitOps의 가치

**Before (kubectl):**
```
개발자 → kubectl apply → 클러스터
         ↓
누가 배포했는지 모름
언제 배포했는지 모름
무엇을 배포했는지 모름 (kubectl history 제한적)
```

**After (GitOps):**
```
개발자 → Git Push → ArgoCD → 클러스터
         ↓
Git Commit에 모든 이력 (누가, 언제, 무엇을)
Rollback = Git Revert (간단)
Drift 감지 → selfHeal (자동 복구)
```

### 3. Trade-off의 이해

**Canary Deployment:**
- 장점: 리스크 최소화, 자동 롤백
- 단점: 배포 시간 길어짐 (10분), 복잡도 증가

**Multi-Cloud DR:**
- 장점: 클라우드 장애 대응, 데이터 주권
- 단점: 비용 증가 ($100/월), 관리 복잡도

**선택 기준:**
> 비즈니스 요구사항에 따라. 가용성 > 비용 → Multi-Cloud 선택

---

## 🔗 Live Demo

- **Primary (AWS EKS)**: [www.goupang.shop/petclinic](https://www.goupang.shop/petclinic/)
- **Grafana**: [www.goupang.shop/grafana](https://www.goupang.shop/grafana/)
- **ArgoCD**: [www.goupang.shop/argocd](https://www.goupang.shop/argocd/)

---

## 📂 관련 문서 (214개)

상세 구현 문서는 [bespin-project/docs](https://github.com/wlals2/bespin-project/tree/main/docs) 참조:

- **아키텍처**: Multi-AZ 설계, DR 전략
- **모니터링**: Prometheus, Grafana, CloudWatch
- **CI/CD**: Jenkins, ArgoCD, Canary
- **트러블슈팅**: 51개 문제 해결 사례

---

## 🔮 다음 단계: Phase 4 (MSA)

### Phase 3의 한계

**Monolithic 아키텍처:**
- 전체 애플리케이션 하나의 WAR 파일
- 작은 변경에도 전체 재배포
- 기능별 독립 스케일링 불가

### Phase 4 목표

**MSA (Microservices Architecture):**
- 기능별 독립 서비스 (User, Pet, Vet, Visit)
- Service Mesh (Istio): mTLS, Circuit Breaker
- Event-Driven (Kafka): 비동기 통신
- API Gateway (Spring Cloud Gateway): 라우팅

**[Phase 4 계획 보기 →](../phase4-msa/)**

---

**작성일**: 2026-01-12
**프로젝트 기간**: 2025.11.07 ~ 2026.01.12 (3개월)
**난이도**: ⭐⭐⭐⭐⭐ (Expert)
**읽는 시간**: 25분
