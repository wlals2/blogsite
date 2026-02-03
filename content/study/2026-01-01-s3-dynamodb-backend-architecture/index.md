---
title: "Terraform Backend: S3와 DynamoDB의 완벽한 조합"
date: 2026-01-01
tags: ["Terraform", "AWS", "S3", "DynamoDB", "Backend", "State Management"]
categories: ["study", "Cloud & Terraform"]
description: "Terraform State를 S3에 저장하고 DynamoDB로 Lock을 관리하는 Backend 아키텍처를 깊이 있게 탐구합니다."
---

## 들어가며

Terraform을 처음 배울 때 가장 헷갈렸던 부분이 "DynamoDB는 글로벌 서비스인가, 리전 서비스인가?"였어요. S3는 글로벌 네임스페이스를 사용하는데, DynamoDB도 그런 줄 알았거든요.

하지만 실제로 여러 리전에서 Terraform을 사용해보니, **DynamoDB는 완전한 리전 서비스**라는 걸 깨달았어요. 각 리전마다 독립적인 테이블이 존재하고, 서로 데이터를 공유하지 않죠.

이 글에서는 S3와 DynamoDB를 Terraform Backend로 사용할 때의 아키텍처, 동작 원리, 리전 선택 이유까지 모든 것을 정리해봤어요.

---

## 핵심 요약

### DynamoDB는 리전 서비스입니다!

많은 사람들이 헷갈리는 부분이에요. 저도 그랬고요.

| 서비스 | 타입 | 설명 |
|--------|------|------|
| **S3** | 글로벌 (Bucket 이름) | Bucket 이름은 전 세계에서 유일해야 함, 하지만 데이터는 특정 리전에 저장 |
| **DynamoDB** | **리전** | 각 리전마다 독립적인 테이블 존재, 다른 리전과 공유 안 됨 |

S3는 "글로벌 네임스페이스"를 사용하지만, **실제 데이터는 특정 리전에 저장**돼요. 반면 DynamoDB는 네임스페이스부터 데이터까지 모두 리전별로 독립적이죠.

---

## 현재 구성

### 위치

제 Terraform Backend는 서울 리전(ap-northeast-2)에 있어요.

```
AWS 리전: ap-northeast-2 (서울)
├─ S3 Bucket
│  └─ terraform-state-eks-3tier-jimin
│     ├─ dev/terraform.tfstate (1.29MB)
│     ├─ azure-dr/terraform.tfstate (56KB) ← 예정
│     ├─ waf/terraform.tfstate (14KB) ← 예정
│     └─ route53-test/terraform.tfstate (30KB) ← 예정
│
└─ DynamoDB Table
   └─ terraform-state-lock
      ├─ LockID: dev/terraform.tfstate
      └─ Digest: 809d517a... (MD5 해시)
```

### ARN (Amazon Resource Name)

ARN을 보면 리전 정보가 어떻게 포함되는지 알 수 있어요.

```
S3 Bucket:
arn:aws:s3:::terraform-state-eks-3tier-jimin
      ↑ S3 ARN에는 리전 정보 없음!

DynamoDB Table:
arn:aws:dynamodb:ap-northeast-2:010068699561:table/terraform-state-lock
                 ^^^^^^^^^^^^^^^^^ ← 리전 정보 포함!
```

**주목:** DynamoDB ARN에는 리전 정보가 포함되어 있지만, S3 ARN에는 없습니다!

왜 그럴까요? S3 Bucket 이름은 글로벌하게 유일하지만, DynamoDB 테이블은 리전마다 별도로 존재하기 때문이에요.

---

## 리전 서비스 vs 글로벌 서비스

### S3 (Bucket 이름은 글로벌, 데이터는 리전)

```
전 세계 S3 Bucket 이름 공간 (글로벌)
├─ us-east-1 (버지니아)
│  └─ my-bucket-123 ✅
│
├─ ap-northeast-2 (서울)
│  ├─ terraform-state-eks-3tier-jimin ✅
│  └─ my-bucket-123 ❌ (이미 버지니아에 있음!)
│
└─ eu-west-1 (아일랜드)
   └─ another-bucket-456 ✅

특징:
- Bucket 이름은 전 세계에서 유일해야 함
- 데이터는 생성 시 지정한 리전에만 저장
- 다른 리전에서 접근 가능하지만 느림
```

처음 S3를 배울 때 "왜 내가 만들려는 Bucket 이름이 이미 존재한다고 하지?"라고 궁금했어요. 다른 사람이 다른 리전에서 먼저 만들었기 때문이더라고요.

---

### DynamoDB (완전한 리전 서비스)

```
us-east-1 (버지니아)
└─ terraform-state-lock ✅

ap-northeast-2 (서울)
├─ terraform-state-lock ✅ ← 현재 사용 중!
└─ 3tier-terraform-lock ✅

eu-west-1 (아일랜드)
└─ terraform-state-lock ✅

특징:
- 같은 이름의 테이블을 각 리전에 생성 가능
- 각 리전의 테이블은 완전히 독립적 (데이터 공유 안 됨)
- 다른 리전의 테이블은 별도의 리소스
```

이게 정말 중요한 차이예요. 서울 리전의 `terraform-state-lock` 테이블과 버지니아 리전의 `terraform-state-lock` 테이블은 **완전히 별개의 테이블**이에요.

---

## Terraform Backend 동작 흐름

실제로 `terraform apply`를 실행하면 어떤 과정을 거칠까요?

### terraform apply 실행 (서울에서)

```
개발자 PC (서울)
    │
    ├─ terraform apply
    │
    ▼
┌─────────────────────────────────────────────────────┐
│   AWS ap-northeast-2 (서울 리전)                    │
│                                                     │
│   1단계: DynamoDB Lock 획득                         │
│   ┌──────────────────────────┐                      │
│   │ DynamoDB                 │                      │
│   │ terraform-state-lock     │                      │
│   │                          │                      │
│   │ PUT Item                 │                      │
│   │ LockID = "dev/..."       │                      │
│   │ Condition: not_exists    │                      │
│   └──────────────────────────┘                      │
│            │                                         │
│            ├─ Lock 없음 → 생성 ✅                    │
│            └─ Lock 있음 → 대기 ⏳                    │
│                                                     │
│   2단계: S3에서 State 다운로드                       │
│   ┌──────────────────────────┐                      │
│   │ S3 Bucket                │                      │
│   │ terraform-state-eks-...  │                      │
│   │                          │                      │
│   │ GET dev/terraform.tfstate│                      │
│   └──────────────────────────┘                      │
│            │                                         │
│            └─ 로컬로 다운로드                        │
│                                                     │
│   3단계: 인프라 변경                                 │
│   (AWS API 호출)                                    │
│                                                     │
│   4단계: S3에 State 업로드                           │
│   ┌──────────────────────────┐                      │
│   │ S3 Bucket                │                      │
│   │ terraform-state-eks-...  │                      │
│   │                          │                      │
│   │ PUT dev/terraform.tfstate│                      │
│   └──────────────────────────┘                      │
│                                                     │
│   5단계: DynamoDB Lock 해제                         │
│   ┌──────────────────────────┐                      │
│   │ DynamoDB                 │                      │
│   │ terraform-state-lock     │                      │
│   │                          │                      │
│   │ DELETE Item              │                      │
│   │ LockID = "dev/..."       │                      │
│   └──────────────────────────┘                      │
└─────────────────────────────────────────────────────┘
```

**전체 과정이 ap-northeast-2 (서울) 리전 내에서 발생**
- 빠른 속도 (리전 내 통신 < 1ms)
- 낮은 비용 (크로스 리전 트래픽 없음)

---

## 왜 같은 리전을 사용해야 하는가?

### Case 1: 같은 리전 (현재 구성) ✅

```
ap-northeast-2 (서울)
├─ S3: terraform-state-eks-3tier-jimin
└─ DynamoDB: terraform-state-lock

terraform apply 소요 시간:
├─ Lock 획득: ~10ms
├─ State 다운로드: ~50ms
├─ 인프라 변경: ~5초 (실제 작업)
├─ State 업로드: ~50ms
└─ Lock 해제: ~10ms

총 오버헤드: ~120ms (무시할 수 있는 수준)
```

---

### Case 2: 다른 리전 (비권장) ❌

```
S3: ap-northeast-2 (서울)
DynamoDB: us-east-1 (버지니아)

terraform apply 소요 시간:
├─ Lock 획득: ~150ms (서울 → 버지니아)
├─ State 다운로드: ~50ms (서울 내)
├─ 인프라 변경: ~5초 (실제 작업)
├─ State 업로드: ~50ms (서울 내)
└─ Lock 해제: ~150ms (서울 → 버지니아)

총 오버헤드: ~400ms (3배 느림)

추가 문제:
- 크로스 리전 트래픽 비용 ($0.02/GB)
- 네트워크 지연 시간 증가
- 리전 장애 시 영향 범위 확대
```

실제로 테스트해봤을 때, 같은 리전을 사용하는 게 훨씬 빠르고 안정적이었어요.

---

## DynamoDB 테이블이 어떻게 생성되었나?

### 확인된 사실

| 항목 | 값 |
|------|-----|
| 테이블 이름 | `terraform-state-lock` |
| 생성일 | 2025-12-08 14:55:45 (약 3주 전) |
| 리전 | ap-northeast-2 |
| Primary Key | LockID (String, HASH) |
| Billing Mode | On-Demand |

### 생성 방법 (추정)

DynamoDB 테이블은 **수동으로 생성**되었을 가능성이 높아요.

**이유:**
- Terraform 코드에서 `aws_dynamodb_table` 리소스를 찾을 수 없음
- 프로젝트들에 backend 설정만 있고 테이블 생성 코드 없음

**생성 방법 (3가지 중 하나):**

#### 방법 1: AWS CLI

```bash
aws dynamodb create-table \
    --table-name terraform-state-lock \
    --attribute-definitions AttributeName=LockID,AttributeType=S \
    --key-schema AttributeName=LockID,KeyType=HASH \
    --billing-mode PAY_PER_REQUEST \
    --region ap-northeast-2
```

#### 방법 2: AWS Console

1. DynamoDB → Create table
2. Table name: `terraform-state-lock`
3. Partition key: `LockID` (String)
4. Billing mode: On-Demand
5. Create table

#### 방법 3: Terraform (별도 프로젝트)

```hcl
resource "aws_dynamodb_table" "terraform_lock" {
  name         = "terraform-state-lock"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "LockID"

  attribute {
    name = "LockID"
    type = "S"
  }

  tags = {
    Name = "Terraform State Lock"
  }
}
```

저는 아마 AWS CLI로 만들었을 것 같아요. 가장 빠르고 간단하니까요.

---

## 현재 테이블 확인

### AWS Console에서 확인

**URL:**
```
https://ap-northeast-2.console.aws.amazon.com/dynamodbv2/home?region=ap-northeast-2#table?name=terraform-state-lock
```

**확인 단계:**

1. AWS Console 로그인
2. 우측 상단 리전 선택: **서울 (ap-northeast-2)** 확인
3. 서비스 → DynamoDB
4. Tables → `terraform-state-lock` 클릭

**확인할 수 있는 정보:**

| 탭 | 정보 |
|----|------|
| **Overview** | 테이블 상태, ARN, 생성일, 항목 수, 크기 |
| **Items** | 현재 저장된 Lock 항목 (LockID, Info, Digest) |
| **Metrics** | Read/Write 요청 수, 스토리지 사용량 |
| **Backups** | 백업 설정 (현재: 없음) |
| **Monitor** | CloudWatch 메트릭 (요청 수, 에러율) |

---

### AWS CLI로 확인

```bash
# 테이블 정보
aws dynamodb describe-table \
    --table-name terraform-state-lock \
    --region ap-northeast-2

# 현재 Lock 상태 확인
aws dynamodb scan \
    --table-name terraform-state-lock \
    --region ap-northeast-2

# 테이블 메트릭
aws cloudwatch get-metric-statistics \
    --namespace AWS/DynamoDB \
    --metric-name ConsumedReadCapacityUnits \
    --dimensions Name=TableName,Value=terraform-state-lock \
    --start-time $(date -u -d '1 hour ago' +%Y-%m-%dT%H:%M:%S) \
    --end-time $(date -u +%Y-%m-%dT%H:%M:%S) \
    --period 3600 \
    --statistics Sum \
    --region ap-northeast-2
```

---

## 다른 리전에도 테이블 생성 가능한가?

### 가능합니다!

같은 이름의 DynamoDB 테이블을 다른 리전에 생성할 수 있어요.

**예시:**

```bash
# 서울 (이미 있음)
aws dynamodb create-table --table-name terraform-state-lock --region ap-northeast-2 ...

# 도쿄 (새로 생성 가능)
aws dynamodb create-table --table-name terraform-state-lock --region ap-northeast-1 ...

# 버지니아 (새로 생성 가능)
aws dynamodb create-table --table-name terraform-state-lock --region us-east-1 ...
```

**각 리전의 테이블은 완전히 독립적:**
- 서로 다른 ARN
- 서로 다른 데이터
- 서로 다른 항목 (Lock)

---

## 글로벌 운영 시나리오

### 시나리오: 한국 팀과 미국 팀이 각자의 인프라 관리

```
한국 팀
├─ S3: terraform-state-korea (ap-northeast-2)
├─ DynamoDB: terraform-state-lock (ap-northeast-2)
└─ 관리: 한국 인프라 (EKS, RDS in 서울)

미국 팀
├─ S3: terraform-state-usa (us-east-1)
├─ DynamoDB: terraform-state-lock (us-east-1)  # 같은 이름 가능!
└─ 관리: 미국 인프라 (EKS, RDS in 버지니아)

각 팀은 자신의 리전 내에서만 작업
→ 빠른 속도, 낮은 비용, 리전 격리
```

이렇게 하면 각 팀이 독립적으로 작업하면서도 충돌 없이 관리할 수 있어요.

---

## 비용 영향

### 같은 리전 (현재)

| 항목 | 비용 |
|------|------|
| S3 Storage (2MB) | $0.0001/월 |
| S3 Requests (100회) | $0.0001/월 |
| DynamoDB Requests (100회) | $0.001/월 |
| **총계** | **$0.001/월** |

거의 무료 수준이에요.

---

### 다른 리전 (비권장)

| 항목 | 비용 |
|------|------|
| S3 Storage (2MB) | $0.0001/월 |
| S3 Requests (100회) | $0.0001/월 |
| DynamoDB Requests (100회) | $0.001/월 |
| **크로스 리전 트래픽 (2MB × 100회)** | **$0.004/월** |
| **총계** | **$0.005/월 (5배 증가)** |

**결론:** 같은 리전 사용이 훨씬 경제적

---

## FAQ

### Q1: S3 Bucket은 왜 글로벌 이름 공간을 사용하나?

**A:** 역사적 이유 + DNS 친화적

- S3는 `bucketname.s3.amazonaws.com` 형식의 URL 사용
- DNS는 전 세계적으로 유일해야 하므로 Bucket 이름도 글로벌하게 유일
- 하지만 실제 데이터는 특정 리전에만 저장

---

### Q2: DynamoDB Global Tables는 뭔가?

**A:** 다중 리전 복제 기능 (선택 사항)

```
DynamoDB Global Table (활성화 시)
├─ ap-northeast-2 (서울) ← Primary
│  └─ terraform-state-lock
│
├─ us-east-1 (버지니아) ← Replica
│  └─ terraform-state-lock (자동 복제)
│
└─ 양방향 자동 동기화 ✅

비용: 추가 복제 비용 발생 (~$0.10/GB)
용도: 멀티 리전 DR, 글로벌 서비스
```

**Terraform State Lock에는 불필요:**
- Lock은 짧은 시간 (초 단위)만 유지
- 복제 지연 (수백 ms)이 Lock 충돌 유발 가능
- 단일 리전으로 충분

---

### Q3: DynamoDB 테이블을 삭제하면?

**A:** Terraform이 Lock을 생성할 수 없어서 에러

```
Error: Error acquiring the state lock

Error: ResourceNotFoundException: Requested resource not found
```

**해결:** 테이블 재생성

```bash
aws dynamodb create-table \
    --table-name terraform-state-lock \
    --attribute-definitions AttributeName=LockID,AttributeType=S \
    --key-schema AttributeName=LockID,KeyType=HASH \
    --billing-mode PAY_PER_REQUEST \
    --region ap-northeast-2
```

---

### Q4: 리전을 변경하려면?

**A:** Backend 설정 변경 + 마이그레이션

```bash
# 1. 새 리전에 DynamoDB 테이블 생성
aws dynamodb create-table ... --region us-east-1

# 2. S3 Bucket을 새 리전으로 복사
aws s3 sync s3://old-bucket s3://new-bucket --source-region ap-northeast-2 --region us-east-1

# 3. backend.tf 수정
terraform {
  backend "s3" {
    bucket = "new-bucket"
    region = "us-east-1"  # 변경
    dynamodb_table = "terraform-state-lock"
  }
}

# 4. terraform init -migrate-state
terraform init -migrate-state
```

---

## 배운 점

### 1. DynamoDB는 리전 서비스다

가장 큰 깨달음이에요. DynamoDB를 글로벌 서비스로 착각하고 있었는데, 완전한 리전 서비스라는 걸 알게 됐죠.

### 2. 같은 리전 사용의 중요성

S3와 DynamoDB를 같은 리전에 배치하면 속도도 빠르고, 비용도 저렴하고, 관리도 쉬워요. 굳이 다른 리전을 쓸 이유가 없더라고요.

### 3. On-Demand는 Terraform에 최적

Terraform State Lock은 짧은 시간만 유지되고, 빈도도 낮아요. Provisioned Capacity보다 On-Demand가 훨씬 경제적이에요.

### 4. 테이블 생성은 수동이 편하다

IaC로 모든 걸 관리하고 싶지만, DynamoDB Lock 테이블만큼은 수동으로 만드는 게 나은 것 같아요. "닭이 먼저냐 달걀이 먼저냐" 문제가 있거든요.

---

## 마치며

Terraform Backend 아키텍처는 단순해 보이지만, S3와 DynamoDB의 특성을 제대로 이해해야 효율적으로 운영할 수 있어요.

**핵심 정리:**
- DynamoDB는 **리전 서비스** (글로벌 아님)
- S3와 DynamoDB는 **같은 리전**에 배치
- Lock 테이블은 **On-Demand** 모드 사용
- 비용은 거의 **무료** 수준

여러분도 Terraform Backend를 설정할 때, 이 글이 도움이 되길 바랍니다. 특히 "DynamoDB는 리전 서비스다"라는 사실, 꼭 기억해주세요!
