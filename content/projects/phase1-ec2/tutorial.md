---
title: "Terraform으로 AWS 3-Tier 인프라 구축하기: 단계별 실습 가이드"
date: 2025-10-01
summary: "Terraform IaC를 사용한 AWS 인프라 자동화 실습 (VPC부터 RDS까지)"
tags: ["terraform", "aws", "iac", "tutorial", "hands-on"]
categories: ["projects"]
series: ["Infrastructure Learning Journey"]
weight: 1
draft: false
---

# Terraform으로 AWS 3-Tier 인프라 구축하기

> **AWS DevOps Blog 스타일**: 따라하면서 배우는 실습 가이드

---

## 학습 목표

이 튜토리얼을 완료하면:

- Terraform으로 AWS VPC 3-Tier 아키텍처 구축
- Infrastructure as Code 원칙 이해
- Terraform State 관리 방법 학습
- 보안 그룹 및 네트워크 설계 실습

**예상 시간**: 2-3시간
**난이도**: ⭐⭐⭐ (Intermediate)

---

## 사전 준비사항

### 필수 도구

```bash
# 1. Terraform 설치 확인
$ terraform version
Terraform v1.9.8

# 2. AWS CLI 설치 및 설정
$ aws --version
aws-cli/2.13.0

$ aws configure
AWS Access Key ID: YOUR_ACCESS_KEY
AWS Secret Access Key: YOUR_SECRET_KEY
Default region name: ap-northeast-2
Default output format: json

# 3. Git 설치
$ git --version
git version 2.40.0
```

### AWS 계정 요구사항

- IAM 사용자 (관리자 권한 또는 EC2, VPC, RDS FullAccess)
- 예상 비용: ~$50/월 (t3.micro 기준, 실습 후 삭제 권장)

---

## 🏗️ 구축할 아키텍처

```
Internet
    │
    ▼
┌──────────────────────────────────────────────────────┐
│  VPC (10.0.0.0/16)                                   │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  │
│  │ Public      │  │ Private     │  │ Private     │  │
│  │ Subnet      │  │ Subnet      │  │ Subnet      │  │
│  │ (WEB)       │  │ (WAS)       │  │ (DB)        │  │
│  │             │  │             │  │             │  │
│  │  ALB        │  │  EC2        │  │  RDS        │  │
│  │  NAT GW     │  │  Tomcat     │  │  MySQL      │  │
│  └─────────────┘  └─────────────┘  └─────────────┘  │
└──────────────────────────────────────────────────────┘
```

---

## Step 1: 프로젝트 초기화

### 1.1 디렉토리 구조 생성

```bash
# 프로젝트 디렉토리 생성
mkdir -p ~/terraform-3tier-aws
cd ~/terraform-3tier-aws

# Terraform 파일 구조 생성
mkdir -p {modules/vpc,modules/ec2,modules/rds,environments/dev}
```

**왜 이렇게 구조화하나?**
- `modules/`: 재사용 가능한 컴포넌트 (VPC, EC2, RDS)
- `environments/`: 환경별 설정 (dev, prod 분리)
- **장점**: 여러 환경에서 같은 모듈 재사용 가능

### 1.2 Backend 설정 (S3 + DynamoDB)

**왜 Backend가 필요한가?**
- Terraform State를 로컬이 아닌 S3에 저장 → 팀 협업 가능
- DynamoDB Lock → 동시 실행 방지

```bash
# S3 Bucket 생성 (State 저장용)
aws s3 mb s3://my-terraform-state-bucket-20250101 --region ap-northeast-2

# Versioning 활성화 (실수로 삭제 방지)
aws s3api put-bucket-versioning \
    --bucket my-terraform-state-bucket-20250101 \
    --versioning-configuration Status=Enabled

# DynamoDB Table 생성 (Lock용)
aws dynamodb create-table \
    --table-name terraform-lock \
    --attribute-definitions AttributeName=LockID,AttributeType=S \
    --key-schema AttributeName=LockID,KeyType=HASH \
    --billing-mode PAY_PER_REQUEST \
    --region ap-northeast-2
```

**확인:**
```bash
# S3 Bucket 확인
aws s3 ls | grep terraform-state

# DynamoDB Table 확인
aws dynamodb list-tables | grep terraform-lock
```

---

## Step 2: VPC 모듈 작성

### 2.1 VPC 기본 구조 (`modules/vpc/main.tf`)

```hcl
# VPC 생성
resource "aws_vpc" "main" {
  cidr_block           = var.vpc_cidr
  enable_dns_hostnames = true
  enable_dns_support   = true

  tags = {
    Name = "${var.project_name}-vpc"
  }
}

# Internet Gateway
resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = {
    Name = "${var.project_name}-igw"
  }
}
```

**왜 `enable_dns_hostnames = true`인가?**
- EC2 인스턴스에 Public DNS 이름 자동 할당
- RDS 엔드포인트에도 DNS 이름 필요

### 2.2 서브넷 생성 (Multi-AZ)

```hcl
# Public Subnet (ALB, NAT Gateway)
resource "aws_subnet" "public" {
  count                   = length(var.availability_zones)
  vpc_id                  = aws_vpc.main.id
  cidr_block              = cidrsubnet(var.vpc_cidr, 8, count.index)
  availability_zone       = var.availability_zones[count.index]
  map_public_ip_on_launch = true

  tags = {
    Name = "${var.project_name}-public-${var.availability_zones[count.index]}"
    Tier = "Public"
  }
}

# Private Subnet - WAS (EC2)
resource "aws_subnet" "private_was" {
  count             = length(var.availability_zones)
  vpc_id            = aws_vpc.main.id
  cidr_block        = cidrsubnet(var.vpc_cidr, 8, count.index + 10)
  availability_zone = var.availability_zones[count.index]

  tags = {
    Name = "${var.project_name}-private-was-${var.availability_zones[count.index]}"
    Tier = "Private-WAS"
  }
}

# Private Subnet - DB (RDS)
resource "aws_subnet" "private_db" {
  count             = length(var.availability_zones)
  vpc_id            = aws_vpc.main.id
  cidr_block        = cidrsubnet(var.vpc_cidr, 8, count.index + 20)
  availability_zone = var.availability_zones[count.index]

  tags = {
    Name = "${var.project_name}-private-db-${var.availability_zones[count.index]}"
    Tier = "Private-DB"
  }
}
```

**왜 `cidrsubnet()`을 사용하나?**
- `cidrsubnet(var.vpc_cidr, 8, 0)` → 10.0.0.0/24 (Public Subnet 1)
- `cidrsubnet(var.vpc_cidr, 8, 10)` → 10.0.10.0/24 (Private WAS 1)
- `cidrsubnet(var.vpc_cidr, 8, 20)` → 10.0.20.0/24 (Private DB 1)
- **장점**: CIDR 수동 계산 불필요, 충돌 방지

**왜 Multi-AZ인가?**
- 한 AZ 장애 시 다른 AZ에서 서비스 계속 → 고가용성
- AWS 권장사항: 최소 2개 AZ

### 2.3 NAT Gateway 생성

```hcl
# Elastic IP for NAT Gateway
resource "aws_eip" "nat" {
  count  = length(var.availability_zones)
  domain = "vpc"

  tags = {
    Name = "${var.project_name}-nat-eip-${var.availability_zones[count.index]}"
  }
}

# NAT Gateway (Private Subnet에서 인터넷 접근용)
resource "aws_nat_gateway" "main" {
  count         = length(var.availability_zones)
  allocation_id = aws_eip.nat[count.index].id
  subnet_id     = aws_subnet.public[count.index].id

  tags = {
    Name = "${var.project_name}-nat-${var.availability_zones[count.index]}"
  }
}
```

**왜 NAT Gateway가 필요한가?**
- Private Subnet의 EC2는 Public IP 없음
- 하지만 `yum update`, Docker Hub 등 인터넷 접근 필요
- NAT Gateway를 통해 Outbound만 허용 (Inbound는 차단 → 보안)

**왜 각 AZ마다 NAT Gateway인가?**
- AZ 장애 시 다른 AZ의 NAT Gateway 사용 → 고가용성
- **비용**: ~$32/월 per NAT Gateway (실습 후 삭제 권장)

---

## Step 3: EC2 모듈 작성 (WAS)

### 3.1 Security Group (`modules/ec2/security_group.tf`)

```hcl
# WAS Security Group
resource "aws_security_group" "was" {
  name        = "${var.project_name}-was-sg"
  description = "Security group for WAS EC2 instances"
  vpc_id      = var.vpc_id

  # ALB에서 오는 트래픽만 허용
  ingress {
    description     = "Allow HTTP from ALB"
    from_port       = 8080
    to_port         = 8080
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }

  # SSH (관리용, 특정 IP만)
  ingress {
    description = "Allow SSH from specific IP"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.admin_ip]  # 예: ["203.0.113.0/32"]
  }

  # Outbound 모두 허용 (인터넷 접근)
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${var.project_name}-was-sg"
  }
}
```

**왜 `security_groups = [aws_security_group.alb.id]`인가?**
- IP 대신 Security Group ID 참조 → ALB IP가 바뀌어도 자동 업데이트
- **원칙**: Private Subnet EC2는 ALB를 통해서만 접근 (직접 접근 차단)

### 3.2 EC2 인스턴스 (`modules/ec2/main.tf`)

```hcl
# WAS EC2 Instance
resource "aws_instance" "was" {
  count                  = var.instance_count
  ami                    = data.aws_ami.amazon_linux_2.id
  instance_type          = var.instance_type
  subnet_id              = var.private_subnets[count.index % length(var.private_subnets)]
  vpc_security_group_ids = [aws_security_group.was.id]
  key_name               = var.key_name

  # User Data로 Tomcat 설치
  user_data = templatefile("${path.module}/user_data.sh", {
    db_endpoint = var.db_endpoint
    db_name     = var.db_name
    db_user     = var.db_user
    db_password = var.db_password
  })

  tags = {
    Name = "${var.project_name}-was-${count.index + 1}"
  }
}

# Amazon Linux 2 AMI 조회
data "aws_ami" "amazon_linux_2" {
  most_recent = true
  owners      = ["amazon"]

  filter {
    name   = "name"
    values = ["amzn2-ami-hvm-*-x86_64-gp2"]
  }
}
```

**왜 `data.aws_ami`를 사용하나?**
- AMI ID는 리전마다 다름 (예: ap-northeast-2와 us-east-1 다름)
- `data` 소스로 최신 AMI 자동 조회 → 리전 변경 시에도 동작

### 3.3 User Data 스크립트 (`modules/ec2/user_data.sh`)

```bash
#!/bin/bash
set -e

# 로그 파일
exec > >(tee /var/log/user-data.log)
exec 2>&1

echo "===== Starting User Data Script ====="

# 1. 시스템 업데이트
yum update -y

# 2. Java 11 설치 (Tomcat 9 요구사항)
amazon-linux-extras install java-openjdk11 -y

# 3. Tomcat 9 다운로드 및 설치
cd /opt
wget https://archive.apache.org/dist/tomcat/tomcat-9/v9.0.80/bin/apache-tomcat-9.0.80.tar.gz
tar xzf apache-tomcat-9.0.80.tar.gz
mv apache-tomcat-9.0.80 tomcat9

# 4. 애플리케이션 배포
# (실제로는 S3에서 WAR 파일 다운로드)
# aws s3 cp s3://my-app-bucket/petclinic.war /opt/tomcat9/webapps/

# 5. DB 연결 설정 (환경 변수)
cat >> /etc/environment <<EOF
DB_ENDPOINT=${db_endpoint}
DB_NAME=${db_name}
DB_USER=${db_user}
DB_PASSWORD=${db_password}
EOF

# 6. Tomcat 시작
/opt/tomcat9/bin/startup.sh

echo "===== User Data Script Completed ====="
```

**확인 방법:**
```bash
# EC2 SSH 접속 후
$ tail -f /var/log/user-data.log  # User Data 실행 로그
$ ps aux | grep tomcat             # Tomcat 프로세스 확인
$ curl localhost:8080              # Tomcat 응답 확인
```

---

## Step 4: RDS 모듈 작성

### 4.1 DB Subnet Group

```hcl
# DB Subnet Group (Multi-AZ RDS 요구사항)
resource "aws_db_subnet_group" "main" {
  name       = "${var.project_name}-db-subnet-group"
  subnet_ids = var.private_db_subnets

  tags = {
    Name = "${var.project_name}-db-subnet-group"
  }
}
```

**왜 Subnet Group이 필요한가?**
- RDS Multi-AZ는 최소 2개 AZ의 서브넷 필요
- Failover 시 다른 AZ로 자동 전환

### 4.2 RDS Instance

```hcl
# RDS MySQL
resource "aws_db_instance" "main" {
  identifier           = "${var.project_name}-db"
  engine               = "mysql"
  engine_version       = "8.0.35"
  instance_class       = var.db_instance_class
  allocated_storage    = 20
  storage_type         = "gp3"

  db_name  = var.db_name
  username = var.db_user
  password = var.db_password

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.rds.id]

  # 고가용성 설정
  multi_az               = true
  backup_retention_period = 7
  backup_window          = "03:00-04:00"
  maintenance_window     = "mon:04:00-mon:05:00"

  # 삭제 방지 (프로덕션에서는 true 권장)
  deletion_protection = false
  skip_final_snapshot = true  # 실습용, 프로덕션에서는 false

  tags = {
    Name = "${var.project_name}-rds"
  }
}
```

**왜 `multi_az = true`인가?**
- Primary DB 장애 시 Standby DB로 자동 Failover (1-2분)
- **비용**: Single-AZ 대비 2배 (고가용성 대가)

**왜 `backup_retention_period = 7`인가?**
- 7일간 자동 백업 유지 → 실수로 삭제 시 복구 가능
- Point-in-Time Recovery 가능 (5분 단위)

---

## Step 5: 환경별 설정 (`environments/dev/main.tf`)

```hcl
terraform {
  required_version = ">= 1.9.0"

  backend "s3" {
    bucket         = "my-terraform-state-bucket-20250101"
    key            = "dev/terraform.tfstate"
    region         = "ap-northeast-2"
    dynamodb_table = "terraform-lock"
    encrypt        = true
  }

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = var.aws_region
}

# VPC 모듈 호출
module "vpc" {
  source = "../../modules/vpc"

  project_name       = var.project_name
  vpc_cidr           = "10.0.0.0/16"
  availability_zones = ["ap-northeast-2a", "ap-northeast-2c"]
}

# EC2 모듈 호출
module "ec2" {
  source = "../../modules/ec2"

  project_name    = var.project_name
  vpc_id          = module.vpc.vpc_id
  private_subnets = module.vpc.private_was_subnets
  instance_count  = 2
  instance_type   = "t3.micro"
  key_name        = var.key_name
  admin_ip        = var.admin_ip

  # RDS 정보 전달
  db_endpoint = module.rds.db_endpoint
  db_name     = var.db_name
  db_user     = var.db_user
  db_password = var.db_password
}

# RDS 모듈 호출
module "rds" {
  source = "../../modules/rds"

  project_name        = var.project_name
  vpc_id              = module.vpc.vpc_id
  private_db_subnets  = module.vpc.private_db_subnets
  db_instance_class   = "db.t3.micro"
  db_name             = var.db_name
  db_user             = var.db_user
  db_password         = var.db_password
  allowed_cidr_blocks = [module.vpc.vpc_cidr]
}
```

---

## Step 6: 실행 및 검증

### 6.1 Terraform 초기화

```bash
cd ~/terraform-3tier-aws/environments/dev

# 초기화 (모듈 다운로드, Backend 설정)
terraform init
```

**예상 출력:**
```
Initializing modules...
- vpc in ../../modules/vpc
- ec2 in ../../modules/ec2
- rds in ../../modules/rds

Initializing the backend...
Successfully configured the backend "s3"!

Terraform has been successfully initialized!
```

### 6.2 Plan 실행 (드라이런)

```bash
# 실행 계획 확인
terraform plan -out=tfplan
```

**확인할 것:**
- `Plan: XX to add, 0 to change, 0 to destroy` → 몇 개 리소스가 생성되는지
- VPC, Subnet, EC2, RDS 등이 포함되어 있는지

### 6.3 Apply 실행

```bash
# 실제 인프라 생성
terraform apply tfplan
```

**예상 시간**: 10-15분 (RDS 생성이 가장 오래 걸림)

**확인:**
```bash
# VPC 생성 확인
aws ec2 describe-vpcs --filters "Name=tag:Name,Values=my-project-vpc"

# EC2 인스턴스 확인
aws ec2 describe-instances --filters "Name=tag:Name,Values=my-project-was-*"

# RDS 확인
aws rds describe-db-instances --db-instance-identifier my-project-db
```

### 6.4 접속 테스트

```bash
# ALB DNS 이름 확인
terraform output alb_dns_name

# 브라우저에서 접속
# http://<ALB_DNS_NAME>/petclinic

# 또는 curl
curl http://<ALB_DNS_NAME>/petclinic
```

**예상 결과:**
- HTTP 200 OK
- PetClinic 홈페이지 표시

---

## Step 7: 트러블슈팅

### 문제 1: EC2가 RDS에 연결 못함

**증상:**
```
Error: Cannot connect to database
```

**확인:**
```bash
# 1. Security Group 확인
aws ec2 describe-security-groups --group-ids <RDS_SG_ID>

# 2. EC2에서 RDS 연결 테스트
ssh ec2-user@<EC2_IP>
mysql -h <RDS_ENDPOINT> -u admin -p
```

**원인:**
- RDS Security Group이 EC2 Security Group으로부터 3306 포트 허용 안 함

**해결:**
```hcl
# modules/rds/security_group.tf
ingress {
  from_port       = 3306
  to_port         = 3306
  protocol        = "tcp"
  security_groups = [var.was_security_group_id]  # ← 추가
}
```

### 문제 2: Terraform State Lock 에러

**증상:**
```
Error: Error acquiring the state lock
```

**원인:**
- 이전 `terraform apply`가 비정상 종료됨 (Ctrl+C 등)
- DynamoDB에 Lock이 남아있음

**해결:**
```bash
# Lock ID 확인
aws dynamodb scan --table-name terraform-lock

# 수동 Unlock
terraform force-unlock <LOCK_ID>
```

---

## 성과 측정

### Before (수동 구축)

| 작업 | 소요 시간 | 재현 가능성 |
|------|----------|------------|
| VPC 생성 | 30분 | ❌ 불가능 |
| EC2 설정 | 1시간 | ❌ 불가능 |
| RDS 생성 | 30분 | ❌ 불가능 |
| 보안 그룹 설정 | 1시간 (실수 많음) | ❌ 불가능 |
| **총 시간** | **3시간** | **0%** |

### After (Terraform)

| 작업 | 소요 시간 | 재현 가능성 |
|------|----------|------------|
| 코드 작성 | 2시간 (최초 1회) | ✅ 100% |
| `terraform apply` | 15분 | ✅ 100% |
| **총 시간** | **15분** | **100%** |
| **절감** | **-83%** | **+100%** |

---

## 🎓 배운 점

### 1. Infrastructure as Code의 가치

**Before:**
```
수동 작업 → 문서화 (README) → 동료가 재현 시도 → 실패 → 질문 → 다시 설명
```

**After:**
```
코드 작성 → Git Push → 동료가 terraform apply → 동일 환경 생성 ✅
```

### 2. Terraform 모듈화의 이점

**모듈 재사용:**
```
environments/
├── dev/     ← 모듈 참조 (instance_type: t3.micro)
├── staging/ ← 동일 모듈 참조 (instance_type: t3.small)
└── prod/    ← 동일 모듈 참조 (instance_type: t3.medium)
```

### 3. State 관리의 중요성

**로컬 State 문제:**
- 팀원 A가 EC2 추가 → State는 A PC에만
- 팀원 B가 apply → State 불일치 → 충돌

**S3 Backend 해결:**
- State 중앙 저장 → 누가 apply해도 동일
- DynamoDB Lock → 동시 실행 방지

---

## 🧹 클린업

```bash
# 모든 리소스 삭제 (비용 절감)
cd ~/terraform-3tier-aws/environments/dev
terraform destroy -auto-approve

# S3 Bucket 삭제 (State 파일 먼저 삭제)
aws s3 rm s3://my-terraform-state-bucket-20250101 --recursive
aws s3 rb s3://my-terraform-state-bucket-20250101

# DynamoDB Table 삭제
aws dynamodb delete-table --table-name terraform-lock
```

**주의:** `terraform destroy` 전 중요 데이터는 백업!

---

## 🔗 다음 단계

이제 Terraform 기본을 익혔으니:

1. **[Phase 2: Kubernetes 환경 구축](../phase2-k8s/)** → 컨테이너 오케스트레이션
2. **[Phase 3: EKS + Multi-Cloud DR](../phase3-eks-dr/)** → 고급 아키텍처

---

## 참고 자료

- [Terraform AWS Provider 공식 문서](https://registry.terraform.io/providers/hashicorp/aws/latest/docs)
- [AWS VPC 설계 모범 사례](https://docs.aws.amazon.com/vpc/latest/userguide/vpc-design.html)
- [Terraform 모듈 작성 가이드](https://developer.hashicorp.com/terraform/language/modules)

---

**작성일**: 2025-10-01
**난이도**: ⭐⭐⭐ (Intermediate)
**실습 시간**: 2-3시간
