---
title: "수동 인프라에서 IaC로: 실패와 학습의 4주 여정"
date: 2025-10-10
summary: "Terraform 도입 실패 → 재도전 → 성공까지의 실제 케이스 스터디"
tags: ["terraform", "case-study", "aws", "iac", "lessons-learned"]
categories: ["projects"]
series: ["Infrastructure Learning Journey"]
weight: 1
draft: false
---

# 수동 인프라에서 IaC로: 실패와 학습의 4주 여정

> **Etsy Engineering 스타일**: 실패를 포함한 진짜 이야기

---

## 🎬 프롤로그: "이대로는 안 되겠다"

### 문제의 시작 (2025년 9월 5일)

팀 리더: "지민씨, 내일 스테이징 환경 하나 더 만들어주세요. 클라이언트 데모용이요."

나: "네, 알겠습니다!" *(속으로: 4시간이면 되겠지...)*

**실제로 걸린 시간: 7시간 30분**

| 시간 | 작업 | 문제 |
|------|------|------|
| 09:00 - 10:00 | VPC, Subnet 생성 | Subnet CIDR 겹침 → 30분 재작업 |
| 10:00 - 12:00 | Security Group 설정 | 포트 8080을 80으로 잘못 열음 → 1시간 디버깅 |
| 13:00 - 14:30 | EC2 인스턴스 생성 | User Data 스크립트 오타 → Tomcat 미실행 |
| 14:30 - 16:00 | RDS 생성 | Multi-AZ 잘못 설정 → 다시 생성 (15분 소요) |
| 16:00 - 16:30 | ALB 설정 | Target Group Health Check 실패 → 경로 수정 |

**결과:** 밤 10시에 완료 😭

---

## Week 1: 첫 번째 Terraform 시도 (실패)

### 시도 1: ChatGPT 코드 복붙 (2025-09-12)

**생각:**
> "Terraform? 간단하잖아. ChatGPT한테 물어보면 되지."

**질문:**
```
AWS 3-tier architecture Terraform code please
```

**결과:**
```hcl
resource "aws_vpc" "main" {
  cidr_block = "10.0.0.0/16"
}

resource "aws_subnet" "public" {
  vpc_id     = aws_vpc.main.id
  cidr_block = "10.0.1.0/24"
}
# ... (50줄 생략)
```

**실행:**
```bash
$ terraform apply
Error: Invalid CIDR block "10.0.1.0/24": overlaps with existing subnet
```

**문제:**
- ChatGPT 코드가 우리 VPC CIDR과 맞지 않음 (우리는 172.31.0.0/16 사용)
- Subnet 개수도 다름 (우리는 6개, 예제는 3개)
- Security Group 규칙이 우리 요구사항과 다름

**소요 시간:** 2시간 (실패)

**배운 점:**
> 복붙 코드는 99% 우리 환경에 안 맞는다. 이해하고 수정해야 한다.

---

### 시도 2: 공식 문서 따라하기 (2025-09-15)

**생각:**
> "좋아, 제대로 배우자. 공식 문서부터."

**읽은 문서:**
- [Terraform AWS Provider 문서](https://registry.terraform.io/providers/hashicorp/aws/latest/docs)
- [VPC 리소스 문서](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/vpc)

**작성 코드:**
```hcl
resource "aws_vpc" "main" {
  cidr_block           = "10.0.0.0/16"
  enable_dns_hostnames = true
  enable_dns_support   = true
}

resource "aws_subnet" "public_2a" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = "10.0.1.0/24"
  availability_zone = "ap-northeast-2a"
}

resource "aws_subnet" "public_2c" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = "10.0.2.0/24"
  availability_zone = "ap-northeast-2c"
}

# ... (서브넷 6개를 일일이 작성)
```

**문제 1: 코드 중복**
- 서브넷 6개를 복붙으로 작성 → 200줄
- CIDR 하나 바꾸려면 6곳 수정

**문제 2: State 파일 충돌 (팀원과)**

팀원: "지민씨, 제가 terraform apply 했는데 에러나요."

```bash
$ terraform apply
Error: state file is locked by another operation
```

나: "아... 로컬에서 State 관리하면 안 되는구나."

**소요 시간:** 1주일 (부분 성공, 하지만 유지보수 어려움)

**배운 점:**
1. 코드 중복 → `count`, `for_each` 사용해야 함
2. 로컬 State → S3 Backend 필요
3. 팀 협업 → State Lock (DynamoDB) 필요

---

## Week 2: 모듈화 도전 (반쯤 성공)

### 시도 3: 모듈로 분리 (2025-09-20)

**생각:**
> "모듈화하면 재사용 가능하다고 했으니까 해보자."

**디렉토리 구조:**
```
terraform/
├── modules/
│   ├── vpc/
│   │   ├── main.tf
│   │   ├── variables.tf
│   │   └── outputs.tf
│   ├── ec2/
│   └── rds/
└── main.tf
```

**VPC 모듈 (modules/vpc/main.tf):**
```hcl
variable "vpc_cidr" {}
variable "availability_zones" {
  type = list(string)
}

resource "aws_vpc" "main" {
  cidr_block = var.vpc_cidr
}

# count로 서브넷 생성
resource "aws_subnet" "public" {
  count             = length(var.availability_zones)
  vpc_id            = aws_vpc.main.id
  cidr_block        = cidrsubnet(var.vpc_cidr, 8, count.index)
  availability_zone = var.availability_zones[count.index]
}
```

**main.tf에서 모듈 호출:**
```hcl
module "vpc" {
  source = "./modules/vpc"

  vpc_cidr           = "10.0.0.0/16"
  availability_zones = ["ap-northeast-2a", "ap-northeast-2c"]
}

module "ec2" {
  source = "./modules/ec2"

  vpc_id     = module.vpc.vpc_id
  subnet_ids = module.vpc.public_subnet_ids
}
```

**결과:**
```bash
$ terraform apply
Error: Missing required argument

The argument "outputs" is required, but was not set.
```

**문제:**
- VPC 모듈의 `outputs.tf`에서 `vpc_id` export 안 함
- EC2 모듈이 `module.vpc.vpc_id` 참조 못함

**수정 (modules/vpc/outputs.tf):**
```hcl
output "vpc_id" {
  value = aws_vpc.main.id
}

output "public_subnet_ids" {
  value = aws_subnet.public[*].id
}
```

**재실행:**
```bash
$ terraform apply
Apply complete! Resources: 15 added, 0 changed, 0 destroyed.
```

**성공! 🎉**

**소요 시간:** 3일

**배운 점:**
- 모듈 간 데이터 전달 → `outputs.tf` 필수
- `[*]` 문법으로 리스트의 모든 속성 추출 가능

---

## Week 3: S3 Backend와 팀 협업 (진짜 성공)

### 문제: 팀원이 내 State 못 봄 (2025-09-25)

**상황:**

팀원: "지민씨가 만든 인프라에 EC2 하나 추가하고 싶은데요."

나: "그럼 terraform apply 하시면 돼요."

팀원: "근데 제 PC에는 State 파일이 없어서 기존 리소스를 못 보네요?"

**근본 원인:**
- State 파일: `terraform.tfstate` (로컬 PC에만 존재)
- 팀원 PC에는 State 없음 → Terraform이 "기존 인프라 없음"으로 판단
- `terraform apply` 시도 → 중복 생성 시도 → 충돌 에러

**해결: S3 Backend**

**1. S3 Bucket 생성 (State 저장용):**
```bash
aws s3 mb s3://my-terraform-state-20250925 --region ap-northeast-2

# Versioning 활성화 (실수로 삭제 방지)
aws s3api put-bucket-versioning \
    --bucket my-terraform-state-20250925 \
    --versioning-configuration Status=Enabled
```

**2. DynamoDB Table 생성 (Lock용):**
```bash
aws dynamodb create-table \
    --table-name terraform-lock \
    --attribute-definitions AttributeName=LockID,AttributeType=S \
    --key-schema AttributeName=LockID,KeyType=HASH \
    --billing-mode PAY_PER_REQUEST
```

**3. Backend 설정 (backend.tf):**
```hcl
terraform {
  backend "s3" {
    bucket         = "my-terraform-state-20250925"
    key            = "prod/terraform.tfstate"
    region         = "ap-northeast-2"
    dynamodb_table = "terraform-lock"
    encrypt        = true
  }
}
```

**4. State 마이그레이션:**
```bash
# 로컬 State → S3로 이동
$ terraform init -migrate-state
Do you want to copy existing state to the new backend? yes

Successfully configured the backend "s3"!
```

**검증:**
```bash
# 팀원 PC에서
$ git pull
$ terraform init
$ terraform plan

No changes. Infrastructure is up-to-date!  ✅
```

**성공! 이제 팀원과 State 공유 🎉**

**소요 시간:** 2일

**배운 점:**
1. S3 Backend → State 중앙 관리
2. DynamoDB Lock → 동시 apply 방지
3. `encrypt = true` → State 파일 암호화 (DB 비밀번호 등 민감 정보 보호)

---

## Week 4: 프로덕션 적용과 최종 문제 해결

### 문제 1: Terraform State Lock 영구 잠김 (2025-10-01)

**증상:**
```bash
$ terraform apply
Error: Error acquiring the state lock

Error message: ConditionalCheckFailedException
Lock Info:
  ID:        abc-123-def-456
  Who:       jimin@laptop
  Created:   2025-10-01 10:00:00 UTC
```

**원인:**
- 어제 `terraform apply` 중 네트워크 끊김 (Ctrl+C로 종료)
- DynamoDB에 Lock 남아있음 → 영구 잠김

**해결 시도 1: force-unlock**
```bash
$ terraform force-unlock abc-123-def-456
Unlock complete!

$ terraform apply
[5분 후 또 같은 에러...]
```

**왜 계속 Lock이 걸리나?** → [Deep Dive 문서](./deep-dive.md) 참조

**최종 해결: lock_timeout 설정**
```hcl
terraform {
  backend "s3" {
    bucket         = "my-terraform-state-20250925"
    key            = "prod/terraform.tfstate"
    region         = "ap-northeast-2"
    dynamodb_table = "terraform-lock"
    encrypt        = true
    lock_timeout   = "5m"  # ← 추가
  }
}
```

**결과:**
- Lock 획득 실패 시 5분 대기
- 5분 내 Lock 해제되면 자동 재시도
- **수동 unlock 거의 불필요** ✅

---

### 문제 2: RDS 비밀번호 하드코딩 (2025-10-03)

**초기 코드:**
```hcl
resource "aws_db_instance" "main" {
  username = "admin"
  password = "MyPassword123!"  # ← 하드코딩!
}
```

**문제:**
- Git에 비밀번호 노출
- 팀원 모두가 DB 비밀번호 알게 됨
- 보안 팀 지적: "비밀번호 Git에 올리면 안 됩니다!"

**해결: AWS Secrets Manager**

**1. Secrets Manager에 비밀번호 저장:**
```bash
aws secretsmanager create-secret \
    --name prod/db/password \
    --secret-string "MySecurePassword123!"
```

**2. Terraform에서 참조:**
```hcl
# Secrets Manager에서 비밀번호 조회
data "aws_secretsmanager_secret_version" "db_password" {
  secret_id = "prod/db/password"
}

resource "aws_db_instance" "main" {
  username = "admin"
  password = jsondecode(data.aws_secretsmanager_secret_version.db_password.secret_string)
}
```

**장점:**
- Git에 비밀번호 노출 안 됨
- IAM으로 접근 제어 (개발자별 권한 분리)
- 비밀번호 변경 시 Secrets Manager만 수정 (코드 변경 불필요)

---

## 📊 최종 성과

### 정량적 지표

| 지표 | Before (수동) | After (Terraform) | 개선 |
|------|--------------|------------------|------|
| **인프라 구축 시간** | 4시간 | **15분** | 94% 단축 |
| **재현 가능성** | 0% (수동 작업) | **100%** (코드 기반) | +100% |
| **실수율** | 30% (Security Group 등) | **0%** (코드 리뷰) | -30% |
| **환경 복제** | 4시간 (수동 재작업) | **15분** (terraform apply) | 94% 단축 |
| **팀 협업** | 불가능 (로컬만) | **가능** (S3 State) | +∞ |

### 비용 절감

**Before:**
- 개발자 시간: 4시간/환경 × $50/시간 = **$200/환경**
- 실수로 중복 리소스 생성 (주 1회) = **$100/월**

**After:**
- 개발자 시간: 15분/환경 × $50/시간 = **$12.5/환경**
- 실수율 0% = **$0/월**

**절감액:** $287.5/월 per 환경

---

## 🎓 핵심 교훈

### 1. 실패는 학습의 과정

**실패 1 (ChatGPT 복붙):**
→ "이해 없이 사용하면 안 된다"

**실패 2 (코드 중복):**
→ "DRY 원칙: Don't Repeat Yourself"

**실패 3 (로컬 State):**
→ "팀 협업은 중앙 저장소 필요"

### 2. 점진적 개선의 힘

```
Week 1: 복붙 (실패)
    ↓
Week 2: 공식 문서 (부분 성공)
    ↓
Week 3: 모듈화 (성공)
    ↓
Week 4: S3 Backend (완전 성공)
```

**교훈:** 한 번에 완벽하려 하지 말고 단계별로 개선

### 3. 문서화의 중요성

**문제 발생 시:**
1. 에러 메시지 캡처
2. 시도한 해결 방법 기록
3. 최종 해결책 문서화

**결과:**
- 같은 문제 재발 시 5분 내 해결
- 팀원 온보딩 시간: 1주 → 2일

### 4. 보안은 처음부터

**잘못된 접근:**
> "일단 되게 만들고, 나중에 보안 챙기자"

**올바른 접근:**
> "처음부터 Secrets Manager, 암호화, 최소 권한"

**이유:**
- 나중에 수정하려면 State 재구축 (리소스 삭제 후 재생성)
- 보안 사고는 단 한 번으로도 치명적

---

## 🔮 다음 도전

이제 Terraform을 익혔으니:

1. **Kubernetes로 컨테이너 오케스트레이션** (Phase 2)
   - 문제: EC2 수동 배포 여전히 번거로움
   - 목표: 선언적 배포, 자동 복구

2. **AWS EKS + Multi-Cloud DR** (Phase 3)
   - 문제: 단일 클라우드 의존 (AWS 장애 시 서비스 중단)
   - 목표: 99.9% 가용성, DR RTO 2분

---

## 💬 후기

4주간의 여정을 돌아보며:

**가장 힘들었던 점:**
> "실패할 때마다 '내가 이걸 할 수 있을까?' 의심했다."

**가장 보람찬 순간:**
> "팀원이 '15분 만에 스테이징 환경 생성했어요!'라고 했을 때."

**동료 엔지니어에게:**
> "실패를 두려워하지 마세요. 제 경험이 여러분의 시행착오를 줄여줄 수 있기를 바랍니다."

---

## 🔗 관련 문서

- [Story: 수동 작업의 고통](./index.md) - Phase 1 전체 이야기
- [Deep Dive: Terraform State Lock](./deep-dive.md) - Lock 문제 기술 분석
- [Tutorial: Terraform 3-Tier 구축](./tutorial.md) - 단계별 실습 가이드

---

**작성일**: 2025-10-10
**작성자**: Jimin
**실제 소요 기간**: 4주 (2025-09-05 ~ 10-09)
**난이도**: ⭐⭐⭐⭐ (실패 포함한 진짜 여정)
