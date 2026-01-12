---
title: "Terraform State 관리의 함정과 해결: S3 + DynamoDB Lock Deep Dive"
date: 2025-10-15
summary: "Terraform State Locking 문제를 겪으며 배운 분산 락 메커니즘"
tags: ["terraform", "state", "dynamodb", "aws", "deep-dive"]
categories: ["projects"]
series: ["Infrastructure Learning Journey"]
weight: 1
draft: false
---

# Terraform State 관리의 함정과 해결

> **Netflix 스타일**: 문제의 근본 원인부터 파고들기

---

## 🔍 문제의 발견

### 증상

```bash
$ terraform apply
Error: Error acquiring the state lock

Error message: ConditionalCheckFailedException: The conditional
request failed
Lock Info:
  ID:        abc-123-def-456
  Path:      s3://my-bucket/terraform.tfstate
  Operation: OperationTypeApply
  Who:       jimin@laptop
  Created:   2025-10-09 14:30:00 UTC
  Info:

```

**처음 반응:**
> "아... 이전 apply가 실패했구나. `terraform force-unlock` 하면 되겠지?"

**하지만:**
- force-unlock → 다시 apply → 5분 후 또 같은 에러
- 왜 계속 Lock이 걸리는가?

---

## 🧐 근본 원인 탐구

### Terraform State Locking 메커니즘

Terraform은 **동시 실행 방지**를 위해 State Lock을 사용합니다.

#### 1. Lock 획득 과정

```

terraform apply 시작
    ↓
DynamoDB PutItem (Conditional)
    ↓
조건: LockID가 없거나 TTL 만료
    ↓
성공 → Lock 획득
실패 → 에러 반환

```

#### 2. DynamoDB Lock Table 구조

```hcl
resource "aws_dynamodb_table" "terraform_lock" {
  name           = "terraform-lock"
  billing_mode   = "PAY_PER_REQUEST"
  hash_key       = "LockID"

  attribute {
    name = "LockID"
    type = "S"  # String
  }

  # TTL 설정 (중요!)
  ttl {
    attribute_name = "TimeToLive"
    enabled        = true
  }
}

```

**LockID 형식:**

```

s3://my-bucket/terraform.tfstate-md5

```

#### 3. Lock 항목 예시

```json
{
  "LockID": {
    "S": "s3://my-bucket/terraform.tfstate-abc123"
  },
  "Info": {
    "S": "{\"ID\":\"abc-123\",\"Operation\":\"OperationTypeApply\",\"Who\":\"jimin@laptop\"}"
  },
  "TimeToLive": {
    "N": "1696867200"  # Unix timestamp
  }
}

```

---

## 🔬 실험: 왜 Lock이 안 풀릴까?

### 가설 1: TTL이 작동하지 않는다

**검증:**

```bash
# DynamoDB에서 Lock 항목 확인
$ aws dynamodb get-item \
    --table-name terraform-lock \
    --key '{"LockID":{"S":"s3://my-bucket/terraform.tfstate-abc123"}}'

{
  "Item": {
    "LockID": {"S": "..."},
    "Info": {"S": "..."},
    "TimeToLive": {"N": "0"}  # ← 문제 발견!
  }
}

```

**결과:** TTL이 `0`으로 설정되어 있음 → **영구 Lock!**

**왜 TTL이 0인가?**

Terraform 코드 확인:

```hcl
# backend.tf
terraform {
  backend "s3" {
    bucket         = "my-bucket"
    key            = "terraform.tfstate"
    region         = "ap-northeast-2"
    dynamodb_table = "terraform-lock"
    encrypt        = true
    # lock_timeout이 없음! ← 문제
  }
}

```

### 가설 2: Lock timeout 기본값이 없다

**Terraform 문서 확인:**
> "If lock_timeout is not specified, Terraform will wait indefinitely."

**결론:**
- `lock_timeout` 미설정 → TTL = 0 (영구 Lock)
- `terraform apply` 실패 시 Lock 수동 해제 필요

---

## 💡 해결 방법

### 1. lock_timeout 설정

```hcl
# backend.tf
terraform {
  backend "s3" {
    bucket         = "my-bucket"
    key            = "terraform.tfstate"
    region         = "ap-northeast-2"
    dynamodb_table = "terraform-lock"
    encrypt        = true

    # 🔑 핵심: lock_timeout 설정
    lock_timeout   = "5m"
  }
}

```

**효과:**
- Lock 획득 실패 시 5분 대기
- 5분 후 자동 재시도
- 5분 내 Lock 해제되면 자동 획득

### 2. DynamoDB TTL 활성화 확인

```bash
# TTL 설정 확인
$ aws dynamodb describe-time-to-live \
    --table-name terraform-lock

{
  "TimeToLiveDescription": {
    "TimeToLiveStatus": "ENABLED",
    "AttributeName": "TimeToLive"
  }
}

```

### 3. 수동 Lock 해제 스크립트

```bash
#!/bin/bash
# unlock.sh

LOCK_ID=$(aws dynamodb scan \
    --table-name terraform-lock \
    --query 'Items[0].LockID.S' \
    --output text)

if [ -z "$LOCK_ID" ]; then
    echo "No lock found"
    exit 0
fi

echo "Found lock: $LOCK_ID"
echo "Unlocking..."

terraform force-unlock "$LOCK_ID"

echo "Lock released ✅"

```

---

## 📊 성능 비교

### Before: lock_timeout 없음

| 시나리오 | 결과 |
|---------|------|
| 정상 apply | ✅ OK |
| apply 실패 (네트워크 끊김) | ❌ Lock 영구 유지 |
| 다른 사용자 apply | ❌ Lock 에러 |
| 수동 unlock 필요 | 😭 매번 |

### After: lock_timeout = 5m

| 시나리오 | 결과 |
|---------|------|
| 정상 apply | ✅ OK |
| apply 실패 (네트워크 끊김) | ⏱️ 5분 후 자동 Lock 해제 |
| 다른 사용자 apply (5분 내) | ⏳ 대기 → 자동 재시도 |
| 수동 unlock 필요 | ✅ 거의 없음 |

---

## 🎓 배운 점

### 1. 분산 락의 중요성

**Terraform State Lock은 왜 필요한가?**

**시나리오: Lock 없이 2명이 동시 apply**

```

사용자 A                    사용자 B
  ↓                          ↓
terraform apply            terraform apply
  ↓                          ↓
State 읽기 (v1)             State 읽기 (v1)
  ↓                          ↓
리소스 생성                  리소스 생성
  ↓                          ↓
State 쓰기 (v2)             State 쓰기 (v2') ← A의 변경 덮어씀!

```

**결과:** A가 생성한 리소스가 State에서 사라짐 → 리소스 누수!

**Lock 사용 시:**

```

사용자 A                    사용자 B
  ↓                          ↓
Lock 획득 ✅                Lock 시도 ❌ (대기)
  ↓                          ↓
State 읽기 (v1)             (대기 중...)
  ↓                          ↓
리소스 생성                  (대기 중...)
  ↓                          ↓
State 쓰기 (v2)             (대기 중...)
  ↓                          ↓
Lock 해제                   Lock 획득 ✅
                             ↓
                           State 읽기 (v2) ← A의 변경 반영됨

```

### 2. DynamoDB Conditional Write

**왜 DynamoDB를 Lock Table로 사용하나?**

**이유 1: Conditional Write 지원**

```python
# PutItem with Conditional Expression
dynamodb.put_item(
    TableName='terraform-lock',
    Item={'LockID': 'abc'},
    ConditionExpression='attribute_not_exists(LockID)'
    # ↑ LockID가 없을 때만 성공 (원자적 연산!)
)

```

**이유 2: TTL 자동 삭제**
- TTL 설정 → DynamoDB가 자동으로 만료된 항목 삭제
- 수동 관리 불필요

**이유 3: 저렴함**
- PAY_PER_REQUEST 모드 → 사용량만큼만 과금
- Lock 획득/해제만 → 월 $0.01 미만

### 3. Lock Timeout Trade-off

| lock_timeout | 장점 | 단점 |
|--------------|------|------|
| **없음 (기본)** | Lock 확실히 유지 | 실패 시 수동 해제 필요 |
| **짧음 (1m)** | 빠른 재시도 | 긴 작업 시 Lock 해제됨 |
| **적당함 (5m)** | ⭐ 균형 | - |
| **길음 (30m)** | 긴 작업 지원 | 다른 사용자 오래 대기 |

**추천:** 5-10분 (대부분의 apply는 5분 내 완료)

---

## 🔗 참고 자료

- [Terraform Backend S3 공식 문서](https://developer.hashicorp.com/terraform/language/backend/s3)
- [DynamoDB Conditional Writes](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Expressions.ConditionExpressions.html)
- [DynamoDB TTL](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/TTL.html)

---

## Sources

- [Best DevOps Blogs 2025](https://www.diffblue.com/resources/best-devops-blogs-2025/)
- [Netflix Tech Blog](https://netflixtechblog.com/)
- [AWS DevOps Blog](https://aws.amazon.com/blogs/devops/)

---

**작성일**: 2025-10-15
**난이도**: ⭐⭐⭐⭐ (Advanced)
**읽는 시간**: 10분
