---
title: "Terraform State S3 마이그레이션 실전 가이드"
date: 2025-12-31
tags: ["Terraform", "Infrastructure as Code", "S3", "DynamoDB", "State Management"]
categories: ["Cloud & Terraform"]
description: "로컬에 저장된 Terraform State 파일을 S3 Backend로 마이그레이션했어요. 파일 손실과 팀원 간 충돌 문제를 완벽하게 해결한 과정을 공유합니다."
showToc: true
draft: false
---
## 로컬 State 파일의 불안함

Terraform으로 Azure DR 환경을 구축하고 나니, `terraform.tfstate` 파일이 로컬에 저장돼 있는 게 불안했어요.

```bash
ls -lh terraform/azure-dr/
# -rw-r--r--  1 jimin  staff   56K Dec 29 15:30 terraform.tfstate
```

**"이거 실수로 삭제하면 어떡하지?"**
**"팀원이랑 동시에 apply 하면 충돌 나는 거 아닌가?"**

밤마다 이런 생각에 잠이 안 왔어요. 그래서 S3 Backend로 마이그레이션을 결심했습니다.

## 현재 상황 파악

### S3에 저장 중 (안전) ✅

**EKS (메인 인프라)**:
- S3 Bucket: `terraform-state-eks-3tier-jimin`
- State 파일: `dev/terraform.tfstate` (1.29MB)
- DynamoDB Lock: `terraform-state-lock` ✅
- 암호화: 활성화 ✅

이건 이미 처음부터 S3 Backend로 구축했어요. 문제없이 잘 작동하고 있었죠.

### 로컬에 저장 중 (위험) ⚠️

| 디렉토리 | State 파일 크기 | 관리 리소스 | 위험도 |
|---------|---------------|------------|--------|
| `azure-dr/` | 56KB | Azure VM, MySQL, AppGW | ⚠️ 높음 |
| `waf/` | 14KB | AWS WAF | ⚠️ 중간 |
| `route53-test/` | 30KB | Route53 레코드 | ⚠️ 낮음 |

**특히 `azure-dr/`은 DR 환경 전체를 관리하는데 로컬 파일이라니!** 이건 당장 해결해야 했어요.

## 왜 S3 Backend가 필요한가?

### 로컬 State의 문제점

실제로 겪었던 문제들이에요.

#### 1. 파일 손실 위험

```bash
# 실수로 삭제하는 경우
rm -rf terraform/  # 와! 잘못 지웠다!
# State 파일 없이는 인프라 관리 불가능 😱
```

한 번은 디렉토리 정리하다가 `terraform.tfstate.backup`을 삭제할 뻔했어요. 식은땀이 났죠.

#### 2. 동시 작업 충돌

```
팀원 A (14:00):
$ terraform apply
# 로컬 state 수정

팀원 B (14:00, 같은 시간):
$ terraform apply
# 로컬 state 수정 → 충돌! 💥
```

각자 로컬에 State 파일이 있으니, 누가 먼저 apply 했는지 모르고 덮어쓰게 돼요.

#### 3. 버전 관리 부족

```bash
# 잘못된 apply 후 복구 불가
terraform apply  # 실수로 리소스 삭제
# 이전 상태로 롤백? 불가능! ❌
```

Git에 State 파일을 커밋하는 건 보안상 절대 안 돼요. 비밀번호가 평문으로 들어있거든요.

### S3 Backend의 장점

| 장점 | 설명 | 효과 |
|------|------|------|
| **백업 자동화** | S3 Versioning으로 이전 버전 복구 | 파일 손실 위험 제거 |
| **동시 작업 방지** | DynamoDB Lock으로 한 명만 apply | State 충돌 방지 |
| **암호화** | S3 암호화 (at-rest) | 보안 정보 보호 |
| **팀 협업** | 모든 팀원이 같은 State 공유 | 일관성 보장 |

## S3 Backend 아키텍처

### 동작 원리

```
개발자 로컬 환경
    │
    ├─ 1. DynamoDB Lock 획득 (terraform-state-lock)
    ├─ 2. S3에서 State 다운로드
    ├─ 3. 변경 사항 적용
    ├─ 4. S3에 State 업로드 (암호화)
    └─ 5. DynamoDB Lock 해제
    │
    ▼
AWS 클라우드
┌──────────────────────────────────────────┐
│  S3 Bucket: terraform-state-eks-3tier    │
│  ├── dev/terraform.tfstate               │
│  ├── azure-dr/terraform.tfstate          │
│  └── waf/terraform.tfstate               │
│                                           │
│  DynamoDB: terraform-state-lock          │
│  ├── LockID                              │
│  ├── Info                                │
│  └── Digest                              │
└──────────────────────────────────────────┘
```

**핵심**:
- 한 번에 한 명만 작업 가능 (DynamoDB Lock)
- 모든 변경 이력 저장 (S3 Versioning)
- 자동 암호화 (AES-256)

## 마이그레이션 방법

### 자동 스크립트 (권장)

처음엔 수동으로 하나씩 마이그레이션하려 했는데, 실수할 가능성이 높더라고요. 그래서 스크립트를 만들었어요.

#### 스크립트 실행

```bash
cd /home/jimin/bespin-project

# 스크립트 실행
./scripts/migrate-state-to-s3.sh
```

#### 스크립트가 하는 일

1. S3 Bucket 및 DynamoDB 테이블 존재 확인
2. 각 디렉토리에서:
   - 로컬 state 백업 생성 (타임스탬프 포함)
   - `terraform init -migrate-state` 실행
   - S3 업로드 확인
   - 로컬 state 삭제 (백업은 유지)
3. 최종 결과 확인

#### 실행 로그

```
======================================
Terraform State S3 마이그레이션
======================================

📋 사전 확인 중...
✅ S3 Bucket: terraform-state-eks-3tier-jimin
✅ DynamoDB Table: terraform-state-lock

======================================
📦 azure-dr 마이그레이션 중...
======================================
💾 로컬 state 백업 중...
✅ 백업 완료: terraform.tfstate.backup.20251231-143000
🔄 Backend 마이그레이션 중...

Initializing the backend...
Do you want to copy existing state to the new backend?
  Enter a value: yes

Successfully configured the backend "s3"!
✅ azure-dr 마이그레이션 성공!
✅ S3 업로드 확인: s3://terraform-state-eks-3tier-jimin/azure-dr/terraform.tfstate
🗑️  로컬 state 파일 삭제 중...
✅ 로컬 state 파일 삭제 완료 (백업은 유지)

======================================
✅ 모든 마이그레이션 완료!
======================================
```

**감동의 순간**: 스크립트가 에러 없이 모든 디렉토리를 마이그레이션하는 걸 보고 정말 뿌듯했어요.

### 수동 마이그레이션 (학습용)

스크립트가 뭘 하는지 이해하려면 수동으로 한 번 해보는 게 좋아요.

#### Azure DR 마이그레이션 예시

```bash
cd /home/jimin/bespin-project/terraform/azure-dr

# 1. 백업 생성
cp terraform.tfstate terraform.tfstate.backup

# 2. Backend 설정 확인
cat backend.tf
# backend "s3" {
#   bucket = "terraform-state-eks-3tier-jimin"
#   key    = "azure-dr/terraform.tfstate"
#   region = "ap-northeast-2"
#   encrypt = true
#   dynamodb_table = "terraform-state-lock"
# }

# 3. Init (마이그레이션)
terraform init -migrate-state

# 질문: Do you want to copy existing state to the new backend?
# 답변: yes

# 4. S3 업로드 확인
aws s3 ls s3://terraform-state-eks-3tier-jimin/azure-dr/
# 2025-12-31 14:30:00      56632 terraform.tfstate

# 5. State 확인
terraform state list
# azurerm_resource_group.dr
# azurerm_mysql_flexible_server.dr
# azurerm_virtual_network.dr
# ...

# 6. 로컬 state 삭제 (백업은 유지)
rm terraform.tfstate terraform.tfstate.backup
```

## 마이그레이션 검증

### S3 State 파일 확인

```bash
aws s3 ls s3://terraform-state-eks-3tier-jimin/ --recursive | grep terraform.tfstate
```

**결과**:
```
2025-12-29 16:55:57    1293430 dev/terraform.tfstate
2025-12-31 14:30:00      56632 azure-dr/terraform.tfstate
2025-12-31 14:31:00      13611 waf/terraform.tfstate
2025-12-31 14:32:00      29798 route53-test/terraform.tfstate
```

**체크리스트**:
- [ ] 모든 디렉토리 State 파일 S3에 존재
- [ ] 파일 크기가 로컬과 일치
- [ ] 타임스탬프 최신

### State 리소스 확인

```bash
cd /home/jimin/bespin-project/terraform/azure-dr
terraform state list
```

**결과**:
```
azurerm_resource_group.dr
azurerm_mysql_flexible_server.dr
azurerm_virtual_network.dr
azurerm_subnet.dr
azurerm_application_gateway.dr
azurerm_linux_virtual_machine.dr
...
```

**"S3에서 State를 제대로 읽어오네!"** 감격스러운 순간이었어요.

### DynamoDB Lock 테이블 확인

```bash
aws dynamodb scan \
    --table-name terraform-state-lock \
    --region ap-northeast-2 \
    --query 'Items[*].[LockID.S, Info.S]' \
    --output table
```

**정상 상태**: 아무도 작업 중이 아니면 빈 테이블
**작업 중**: Lock이 걸려 있으면 LockID가 표시됨

## 작업 흐름 변화

### Before (로컬 State)

```bash
cd terraform/azure-dr

# 문제: 팀원 A와 팀원 B가 동시 작업
# 팀원 A
terraform apply  # 로컬 state 수정

# 팀원 B (같은 시간)
terraform apply  # 로컬 state 수정 → 충돌! 💥
```

**결과**: State 파일 손상 → 인프라 관리 불가능

### After (S3 Backend)

```bash
cd terraform/azure-dr

# 해결: DynamoDB Lock으로 한 명만 작업
# 팀원 A
terraform apply  # S3 state lock 획득 → 작업 진행

# 팀원 B (같은 시간)
terraform apply  # Lock 대기 중...
# Error: Error acquiring the state lock
# 팀원 A가 작업 끝날 때까지 대기
```

**결과**: State 충돌 방지 → 안전한 협업 ✅

## 트러블슈팅 경험

### State Lock 걸림

**증상**:
```
Error: Error acquiring the state lock

Lock Info:
  ID:        1234567890abcdef
  Path:      terraform-state-eks-3tier-jimin/azure-dr/terraform.tfstate
  Operation: OperationTypeApply
  Who:       jimin@ip-10-0-1-100
  Version:   1.6.0
  Created:   2025-12-31 14:30:00 +0000 UTC
```

**원인**:
- 다른 팀원이 작업 중
- 이전 작업이 강제 종료되어 Lock이 남아있음

**해결**:

1. 작업 중인지 확인:
```bash
# DynamoDB 테이블 확인
aws dynamodb scan \
    --table-name terraform-state-lock \
    --region ap-northeast-2 \
    --query 'Items[*].[LockID.S, Info.S]' \
    --output table

# 팀원에게 확인
echo "지금 terraform apply 하고 있나요?"
```

2. 작업 중이 아니면 Lock 강제 해제:
```bash
terraform force-unlock 1234567890abcdef
```

**주의**: 다른 사람이 실제로 작업 중이면 절대 force-unlock 하지 말 것!

### "No state file found"

**증상**:
```
Error: No state file was found!
```

**원인**: S3 업로드 실패 또는 잘못된 backend 설정

**해결**:

1. S3에 파일이 있는지 확인:
```bash
aws s3 ls s3://terraform-state-eks-3tier-jimin/azure-dr/
```

2. Backend 설정 확인:
```bash
cat backend.tf
# bucket, key, region이 올바른지 확인
```

3. 백업에서 복구:
```bash
# 로컬 백업 파일 확인
ls -la terraform.tfstate.backup.*

# 가장 최근 백업 복사
cp terraform.tfstate.backup.20251231-143000 terraform.tfstate

# 다시 마이그레이션
terraform init -migrate-state
```

## S3 Backend 모범 사례

### Backend 설정 구조

```
terraform/
├── eks/
│   ├── backend.tf          # key = "dev/terraform.tfstate"
│   └── main.tf
├── azure-dr/
│   ├── backend.tf          # key = "azure-dr/terraform.tfstate"
│   └── main.tf
└── waf/
    ├── backend.tf          # key = "waf/terraform.tfstate"
    └── main.tf
```

**원칙**:
- 각 디렉토리마다 별도의 `key` 사용
- Bucket과 DynamoDB 테이블은 공유
- `backend.tf` 파일은 Git에 커밋 ✅
- State 파일은 Git에 커밋 ❌

### S3 Versioning 활성화

```bash
aws s3api put-bucket-versioning \
    --bucket terraform-state-eks-3tier-jimin \
    --versioning-configuration Status=Enabled
```

**효과**: 실수로 덮어쓰거나 삭제해도 이전 버전 복구 가능

**복구 방법**:
```bash
# 버전 목록 확인
aws s3api list-object-versions \
    --bucket terraform-state-eks-3tier-jimin \
    --prefix azure-dr/terraform.tfstate

# 특정 버전 복구
aws s3api get-object \
    --bucket terraform-state-eks-3tier-jimin \
    --key azure-dr/terraform.tfstate \
    --version-id VERSION_ID \
    terraform.tfstate
```

**실제 경험**: 한 번 실수로 `terraform destroy`를 실행했는데, Versioning 덕분에 이전 State로 복구할 수 있었어요. 진심으로 감사했죠.

### S3 Lifecycle Policy (비용 절감)

90일 이상 된 State 버전을 자동 삭제해서 비용을 절감해요.

```bash
cat > lifecycle.json <<EOF
{
  "Rules": [
    {
      "Id": "DeleteOldVersions",
      "Status": "Enabled",
      "NoncurrentVersionExpiration": {
        "NoncurrentDays": 90
      }
    }
  ]
}
EOF

aws s3api put-bucket-lifecycle-configuration \
    --bucket terraform-state-eks-3tier-jimin \
    --lifecycle-configuration file://lifecycle.json
```

## 비용 분석

### 예상 비용

| 항목 | 비용 |
|------|------|
| S3 Storage (4 state files × 200KB) | ~$0.01/월 |
| S3 Requests (apply 시 GET/PUT) | ~$0.01/월 |
| DynamoDB Lock (On-Demand) | ~$0.01/월 |
| **총 비용** | **~$0.03/월** |

**한 달에 3센트!** 커피 한 잔 값의 1/100도 안 돼요. 보안과 안정성 대비 완전히 무시할 수 있는 수준이에요.

### 실제 비용

3개월간 사용한 실제 비용을 확인해봤어요.

```bash
aws ce get-cost-and-usage \
    --time-period Start=2025-10-01,End=2025-12-31 \
    --granularity MONTHLY \
    --metrics BlendedCost \
    --group-by Type=SERVICE
```

**결과**: $0.08 (3개월 합계)
**월평균**: $0.027

**"예상과 거의 일치하네!"**

## 배운 점

### 1. State 관리는 인프라의 기초

Terraform State는 인프라의 "설계도"예요. 이게 없으면 모든 게 무용지물이 돼요.

### 2. 백업은 필수

로컬 State 시절엔 밤마다 불안했는데, S3 Versioning 덕분에 이제 푹 잘 수 있어요.

### 3. 팀 협업을 위한 투자

DynamoDB Lock으로 동시 작업 충돌을 방지하니, 팀원과의 협업이 훨씬 편해졌어요.

### 4. 자동화 스크립트의 힘

수동으로 하나씩 마이그레이션하는 건 시간 낭비예요. 스크립트 하나로 모든 걸 해결했어요.

## 체크리스트

### 마이그레이션 전

- [ ] S3 Bucket 존재 확인
- [ ] DynamoDB 테이블 존재 확인
- [ ] 로컬 state 백업 생성
- [ ] 팀원에게 마이그레이션 공지

### 마이그레이션 중

- [ ] `backend.tf` 파일 생성
- [ ] `terraform init -migrate-state` 실행
- [ ] S3 업로드 확인
- [ ] `terraform state list` 실행 확인

### 마이그레이션 후

- [ ] 로컬 state 파일 삭제
- [ ] S3 Versioning 활성화
- [ ] 백업 파일 Git에서 제외 (`.gitignore`)
- [ ] 팀원에게 완료 공지

## 마무리

로컬 State에서 S3 Backend로 마이그레이션하면서 정말 많은 걸 배웠어요.

**Before**:
- 파일 손실 불안
- 동시 작업 충돌
- 버전 관리 부족
- 밤마다 걱정

**After**:
- S3 Versioning으로 안전
- DynamoDB Lock으로 충돌 방지
- 완벽한 버전 관리
- 편안한 숙면

**핵심 교훈**:
- Terraform State는 S3에 저장하세요 (필수!)
- Versioning과 Encryption은 기본
- DynamoDB Lock으로 팀 협업 문제 해결
- 자동화 스크립트로 실수 방지

이제 안심하고 인프라를 관리할 수 있어요. 혹시 아직 로컬 State를 사용하고 계신다면, 당장 마이그레이션하세요! 🚀
