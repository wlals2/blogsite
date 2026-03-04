---
title: "Terraform State Lock 완벽 이해하기"
date: 2025-12-30
tags: ["Terraform", "IaC", "AWS", "DynamoDB", "State Management"]
categories: ["Cloud & Terraform"]
description: "Terraform State Lock이 어떻게 동작하는지, DynamoDB를 활용한 동시성 제어 메커니즘을 단계별로 깊이 있게 알아봅니다."
---

## 들어가며

Terraform으로 인프라를 관리하면서 가장 헷갈렸던 부분이 "Lock"이었어요. kubectl이 Lock을 거는 건지, Terraform이 거는 건지도 몰랐고, 심지어 Lock이 정확히 무엇인지도 이해하지 못했죠.

처음에는 단순히 "여러 사람이 동시에 terraform apply를 실행하면 안 되니까 Lock을 걸어야 한다"는 정도로만 알고 있었어요. 하지만 실제로 팀원과 동시에 배포하다가 충돌이 발생했고, 그때야 State Lock의 중요성을 깨달았습니다.

이 글에서는 Terraform State Lock의 동작 원리를 처음부터 끝까지, 화장실 자물쇠 비유부터 DynamoDB API 호출까지 모든 과정을 상세하게 정리해봤어요.

---

## 핵심 오해 바로잡기

### 틀린 이해

```
kubectl이 DynamoDB Lock을 건다?
```

저도 처음에 이렇게 생각했어요. Kubernetes를 관리하는 도구니까 Lock도 kubectl이 관리할 거라고요.

### 올바른 이해

```
terraform이 DynamoDB Lock을 건다!

kubectl = Kubernetes 관리 도구 (Pod, Service 등)
terraform = 인프라 관리 도구 (VPC, EKS, RDS 등)

DynamoDB Lock은 terraform apply 실행 시에만 사용됨
```

Terraform은 **인프라 코드 관리 도구**이고, kubectl은 **Kubernetes 리소스 관리 도구**입니다. 각자의 영역이 명확하게 구분되죠.

---

## Lock이란 무엇인가?

### 실생활 비유: 화장실 자물쇠

프로그래밍 개념을 설명할 때 가장 좋은 건 실생활 비유라고 생각해요. Lock도 마찬가지입니다.

```
화장실 (State 파일)
├─ 🚪 문 (S3 Bucket)
└─ 🔒 자물쇠 (DynamoDB Lock)

사용자 A: 화장실 들어감
├─ 🔒 자물쇠 잠금 (Lock 획득)
├─ 용무 처리 (인프라 변경)
└─ 🔓 자물쇠 해제 (Lock 해제)

사용자 B: 화장실 문 열려고 시도
├─ 🔒 잠겨있음! (Lock이 걸려있음)
├─ ⏳ 대기...
└─ 🔓 사용자 A가 나가면 들어감

핵심: 한 번에 한 사람만 사용 가능!
```

이 비유가 완벽하게 들어맞더라고요. 두 사람이 동시에 화장실을 쓸 수 없듯이, 두 엔지니어가 동시에 인프라를 변경할 수 없어야 하니까요.

### 프로그래밍 용어

**Lock (잠금장치):**
- 동시 접근을 막는 메커니즘
- "배타적 잠금" (Exclusive Lock) 또는 "뮤텍스" (Mutex)
- 한 번에 하나의 프로세스만 리소스에 접근 가능

**State 파일 Lock의 목적:**
- 2명이 동시에 `terraform apply`를 실행하지 못하게 함
- State 파일 충돌 방지
- 인프라 일관성 유지

---

## 전체 워크플로우 (완벽 가이드)

실제로 `terraform apply`를 실행하면 어떤 일이 일어날까요? Step by Step으로 따라가보겠습니다.

### Step 0: 초기 상태

```
개발자 PC
└─ terraform/ 디렉토리
   ├─ main.tf (EKS 설정)
   ├─ backend.tf (S3 + DynamoDB 설정)
   └─ ...

AWS 클라우드 (ap-northeast-2)
├─ S3 Bucket: terraform-state-eks-3tier-jimin
│  └─ dev/terraform.tfstate
│     {
│       "was": {"replica": 2},
│       "web": {"replica": 2}
│     }
│
└─ DynamoDB Table: terraform-state-lock
   └─ (비어있음 - Lock 없음)
```

---

### Step 1: terraform apply 명령어 실행

```bash
cd /home/jimin/bespin-project/terraform/eks
terraform apply
```

Terraform이 가장 먼저 하는 일은 backend.tf 파일을 읽는 거예요.

```
1. backend.tf 파일 읽기
   terraform {
     backend "s3" {
       bucket = "terraform-state-eks-3tier-jimin"
       key    = "dev/terraform.tfstate"
       dynamodb_table = "terraform-state-lock"
     }
   }

2. "아, S3 Backend를 사용하는구나"
3. "DynamoDB Lock을 사용해야겠네"
```

---

### Step 2: DynamoDB Lock 획득 시도

이 단계가 제가 가장 흥미롭게 느꼈던 부분이에요. Terraform이 내부적으로 어떤 AWS API를 호출하는지 알게 됐거든요.

```python
# 의사 코드 (실제는 Go 언어)
dynamodb_client = boto3.client('dynamodb', region='ap-northeast-2')

try:
    response = dynamodb_client.put_item(
        TableName='terraform-state-lock',
        Item={
            'LockID': {
                'S': 'terraform-state-eks-3tier-jimin/dev/terraform.tfstate'
            },
            'Info': {
                'S': json.dumps({
                    'ID': 'abc123-def456-ghi789',  # 랜덤 UUID
                    'Operation': 'OperationTypeApply',
                    'Who': 'jimin@ip-10-0-1-100',
                    'Version': '1.6.0',
                    'Created': '2026-01-01T10:00:00Z',
                    'Path': 'terraform-state-eks-3tier-jimin/dev/terraform.tfstate'
                })
            }
        },
        # 핵심: Conditional Expression
        ConditionExpression='attribute_not_exists(LockID)'
        # "LockID가 존재하지 않을 때만 생성하라"
    )

    print("✅ Lock 획득 성공!")

except ClientError as e:
    if e.response['Error']['Code'] == 'ConditionalCheckFailedException':
        print("❌ Lock이 이미 걸려있음! 다른 사람이 작업 중...")
        print("10초 후 다시 시도...")
        time.sleep(10)
        # 재시도...
```

여기서 가장 중요한 건 `ConditionExpression='attribute_not_exists(LockID)'` 부분이에요. 이게 바로 Race Condition을 방지하는 핵심입니다.

**DynamoDB 상태 변화:**

```
Before (Lock 없음):
┌─────────────────────────────────────┐
│ DynamoDB Table: terraform-state-lock│
│ (비어있음)                          │
└─────────────────────────────────────┘

After (Lock 획득):
┌─────────────────────────────────────────────────────────────┐
│ DynamoDB Table: terraform-state-lock                        │
├─────────────────────────────────────────────────────────────┤
│ LockID: terraform-state-eks-3tier-jimin/dev/terraform.tfstate│
│ Info: {                                                     │
│   "ID": "abc123-def456-ghi789",                             │
│   "Who": "jimin@ip-10-0-1-100",                             │
│   "Created": "2026-01-01T10:00:00Z"                         │
│ }                                                           │
└─────────────────────────────────────────────────────────────┘
```

사용자에게 보이는 화면에서는 단순히 이렇게 나와요:

```
Acquiring state lock. This may take a few moments...
```

---

### Step 3: S3에서 State 파일 다운로드

Lock을 성공적으로 획득하면, Terraform은 S3에서 현재 State 파일을 다운로드합니다.

```python
s3_client = boto3.client('s3', region='ap-northeast-2')

# S3에서 State 파일 다운로드
response = s3_client.get_object(
    Bucket='terraform-state-eks-3tier-jimin',
    Key='dev/terraform.tfstate'
)

state_content = response['Body'].read()

# 로컬에 저장
with open('.terraform/terraform.tfstate', 'wb') as f:
    f.write(state_content)

print("✅ State 파일 다운로드 완료")
```

State 파일 내용은 JSON 형식으로 되어 있어요:

```json
{
  "version": 4,
  "terraform_version": "1.6.0",
  "resources": [
    {
      "type": "aws_eks_cluster",
      "name": "main",
      "instances": [
        {
          "attributes": {
            "name": "eks-dev-cluster",
            "version": "1.28"
          }
        }
      ]
    },
    {
      "type": "kubernetes_deployment",
      "name": "was",
      "instances": [
        {
          "attributes": {
            "replicas": 2  # ← 현재 상태
          }
        }
      ]
    }
  ]
}
```

---

### Step 4: Terraform Plan 생성

Terraform은 현재 State와 원하는 State(코드)를 비교해서 변경 계획을 만들어요.

```
1. 현재 State (다운로드한 파일):
   WAS replica: 2

2. 원하는 State (main.tf 파일):
   WAS replica: 3

3. Diff 계산:
   - WAS replica: 2 → 3 (변경)

4. Plan 출력:
   Terraform will perform the following actions:

   # kubernetes_deployment.was will be updated in-place
   ~ resource "kubernetes_deployment" "was" {
       ~ spec {
           ~ replicas = 2 -> 3
       }
   }

   Plan: 0 to add, 1 to change, 0 to destroy.
```

사용자에게 보이는 화면:

```
Plan: 0 to add, 1 to change, 0 to destroy.

Do you want to perform these actions?
  Terraform will perform the actions described above.
  Only 'yes' will be accepted to approve.

  Enter a value:
```

**이 시점에서:**
- DynamoDB Lock: 🔒 여전히 잠겨있음!
- S3 State: 📥 다운로드만 했음 (아직 변경 안 함)
- 실제 인프라: 아직 변경 안 함

---

### Step 5: 사용자 승인 및 인프라 변경

```
  Enter a value: yes
```

yes를 입력하면, Terraform이 실제로 Kubernetes API를 호출해서 인프라를 변경해요.

```python
k8s_client = kubernetes.client.AppsV1Api()

# WAS Deployment replica 변경
deployment = k8s_client.read_namespaced_deployment(
    name='was',
    namespace='petclinic'
)

deployment.spec.replicas = 3  # 2 → 3

k8s_client.patch_namespaced_deployment(
    name='was',
    namespace='petclinic',
    body=deployment
)

print("✅ WAS replica 3으로 변경 완료")
```

**실제 Kubernetes 클러스터 변화:**

```
Before:
┌──────────────────────┐
│ WAS Pod 1 (Running)  │
├──────────────────────┤
│ WAS Pod 2 (Running)  │
└──────────────────────┘

After:
┌──────────────────────┐
│ WAS Pod 1 (Running)  │
├──────────────────────┤
│ WAS Pod 2 (Running)  │
├──────────────────────┤
│ WAS Pod 3 (Creating) │ ← 새로 생성!
└──────────────────────┘
```

---

### Step 6-8: State 업데이트 및 Lock 해제

나머지 과정은 빠르게 진행돼요:

1. **로컬 State 파일 업데이트**: replica: 2 → 3
2. **S3에 업로드**: 변경된 State를 S3에 저장
3. **DynamoDB Lock 해제**: 다른 사람이 작업할 수 있게 Lock 삭제

```python
# Lock 삭제
dynamodb_client.delete_item(
    TableName='terraform-state-lock',
    Key={
        'LockID': {
            'S': 'terraform-state-eks-3tier-jimin/dev/terraform.tfstate'
        }
    }
)

print("✅ Lock 해제 완료")
```

최종 상태:

```
✅ Kubernetes 클러스터:
   WAS Pod: 3개 (2개 → 3개 증가)

✅ S3 State 파일:
   replica: 3 (업데이트됨)

✅ DynamoDB Lock:
   (비어있음 - 해제됨)

✅ 로컬 State 파일:
   .terraform/terraform.tfstate 삭제됨
   (S3에만 보관, 로컬은 캐시만)
```

---

## 동시 실행 시나리오 (Lock의 진가)

Lock이 없으면 어떤 일이 벌어질까요? 실제 시나리오로 살펴봤어요.

### 팀원 A와 팀원 B가 동시에 terraform apply

```
시간      팀원 A (PC 1)                팀원 B (PC 2)
─────────────────────────────────────────────────────────────
10:00   terraform apply 실행
10:00   ├─ DynamoDB Lock 요청
10:00   │  PUT Item (LockID=...)
10:00   │  Condition: not_exists
10:00   ├─ ✅ 성공! (Lock 없었음)
10:00   │  Lock 획득 🔒
10:00   │                               terraform apply 실행
10:00   │                               ├─ DynamoDB Lock 요청
10:00   │                               │  PUT Item (LockID=...)
10:00   │                               │  Condition: not_exists
10:00   │                               ├─ ❌ 실패!
10:00   │                               │  (LockID 이미 존재)
10:00   │                               └─ Error:
10:00   │                                  Lock already exists!
10:00   │                                  Who: jimin@PC-1
10:00   │                                  Created: 10:00:00
10:00   ├─ S3에서 State 다운로드
10:00   │  (replica: 2)
10:01   ├─ WAS replica 3으로 변경
10:01   ├─ S3에 State 업로드
10:01   │  (replica: 3)
10:01   └─ DynamoDB Lock 해제 🔓
10:01                                    ├─ Lock 해제 감지!
10:01                                    ├─ 다시 Lock 요청
10:01                                    ├─ ✅ 성공! Lock 획득 🔒
10:01                                    ├─ S3에서 State 다운로드
10:01                                    │  (replica: 3) ← A의 변경 반영!
10:02                                    ├─ WAS replica 5로 변경
10:02                                    ├─ S3에 State 업로드
10:02                                    │  (replica: 5)
10:02                                    └─ Lock 해제 🔓

결과:
✅ 순차 실행됨 (2 → 3 → 5)
✅ 충돌 없음
✅ 모든 변경 사항 반영됨
```

Lock이 없었다면 팀원 A의 변경사항이 팀원 B의 변경사항에 의해 덮어씌워졌을 거예요. 정말 무서운 일이죠.

---

## Lock의 핵심 메커니즘

### Conditional Expression (조건부 표현식)

DynamoDB의 가장 강력한 기능 중 하나가 바로 Conditional Expression이에요.

```python
# PutItem 요청 시
ConditionExpression='attribute_not_exists(LockID)'
```

**의미:**

```
attribute_not_exists(LockID):
  "LockID 속성이 존재하지 않을 때만 생성하라"

존재 여부 체크 + 생성을 원자적으로 실행 (Atomic Operation)
→ Race Condition 방지!
```

**왜 안전한가?**

잘못된 방법 (Race Condition 발생):
```
1. DynamoDB에서 LockID 조회 (GET)
2. 없으면 생성 (PUT)

문제:
  팀원 A: GET (없음) → PUT 준비
  팀원 B: GET (없음) → PUT 준비  ← A와 동시!
  팀원 A: PUT 실행 ✅
  팀원 B: PUT 실행 ✅  ← 둘 다 생성됨! 💥
```

올바른 방법 (Conditional Expression):
```
1. DynamoDB에 조건부 생성 요청 (PUT with Condition)

결과:
  팀원 A: PUT with Condition ✅ (첫 번째)
  팀원 B: PUT with Condition ❌ (이미 존재)

  DynamoDB가 내부적으로 원자적 처리!
```

---

## Terraform과 Kubectl의 차이

많은 사람들이 헷갈려하는 부분이에요. 저도 그랬고요.

| 항목 | Terraform | kubectl |
|------|-----------|---------|
| **관리 대상** | 인프라 (VPC, EKS, RDS, ALB) | Kubernetes 리소스 (Pod, Service, Deployment) |
| **State 관리** | S3 + DynamoDB Lock | etcd (Kubernetes 내부) |
| **명령어** | `terraform apply` | `kubectl apply` |
| **Lock 메커니즘** | DynamoDB (외부) | Kubernetes etcd Lease |

**예시:**

```bash
# Terraform으로 EKS 클러스터 생성 (인프라)
terraform apply
├─ DynamoDB Lock 사용 ✅
├─ S3 State 사용 ✅
└─ 생성: VPC, Subnet, EKS, RDS

# kubectl로 Pod 배포 (애플리케이션)
kubectl apply -f deployment.yaml
├─ Kubernetes etcd Lock 사용 (내부)
├─ State는 etcd에 저장 (Kubernetes 내부)
└─ 생성: Deployment, Pod, Service
```

---

## 실습: Lock 직접 확인하기

이론만 보는 것보다 직접 확인해보는 게 이해에 도움이 돼요.

### 1단계: terraform apply 실행 (yes 입력 안 함)

```bash
cd /home/jimin/bespin-project/terraform/eks
terraform apply
# "Enter a value:" 나오면 입력하지 말고 대기
```

### 2단계: DynamoDB Lock 확인 (다른 터미널)

```bash
aws dynamodb scan \
    --table-name terraform-state-lock \
    --region ap-northeast-2
```

**결과:**

```json
{
  "Items": [
    {
      "LockID": {
        "S": "terraform-state-eks-3tier-jimin/dev/terraform.tfstate"
      },
      "Info": {
        "S": "{\"ID\":\"abc123\",\"Who\":\"jimin@...\",\"Created\":\"...\"}"
      }
    }
  ]
}
```

**Lock이 걸려있음!** 🔒

### 3단계: 터미널 1에서 yes 입력 → 완료

```bash
  Enter a value: yes
```

### 4단계: 다시 DynamoDB 확인

```bash
aws dynamodb scan \
    --table-name terraform-state-lock \
    --region ap-northeast-2
```

**결과:**

```json
{
  "Items": [],
  "Count": 0
}
```

**Lock 해제됨!** 🔓

---

## 배운 점

### 1. Lock은 단순한 파일이 아니다

처음에는 Lock을 단순히 "잠금 파일"로만 생각했어요. 하지만 실제로는 **분산 시스템의 동시성 제어 메커니즘**이더라고요.

### 2. Conditional Expression의 힘

DynamoDB의 Conditional Expression이 얼마나 강력한지 깨달았어요. 단순한 if문이 아니라, **원자적 연산(Atomic Operation)**을 제공하는 거죠.

### 3. 도구의 역할 구분

Terraform은 인프라를, kubectl은 애플리케이션을 관리한다는 명확한 구분을 이해하게 됐어요. 각자의 영역에서 각자의 State 관리 방식을 가지고 있죠.

### 4. State 관리의 중요성

State 파일이 왜 중요한지, Lock이 왜 필수적인지 이해하게 됐어요. State가 깨지면 인프라 전체가 망가질 수 있거든요.

---

## 마치며

Terraform State Lock은 단순해 보이지만, 그 내부에는 **분산 시스템**, **동시성 제어**, **원자적 연산** 같은 복잡한 개념들이 숨어 있어요.

화장실 자물쇠에서 시작해서 DynamoDB Conditional Expression까지, 이 모든 과정을 이해하니 Terraform이 훨씬 더 안전하고 강력한 도구라는 걸 느꼈습니다.

여러분도 terraform apply를 실행할 때, 백그라운드에서 이 모든 일이 일어나고 있다는 걸 기억해주세요. Lock 획득, State 다운로드, 인프라 변경, State 업로드, Lock 해제까지 - 모든 단계가 여러분의 인프라를 안전하게 지키고 있답니다.
