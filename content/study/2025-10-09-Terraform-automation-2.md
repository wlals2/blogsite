---
title: "Terraform 클라우드 자동화 여정 #2: 기초편"
date: 2025-10-09T10:55:58-04:00
draft: false
tags: ["Terraform", "IaC", "HCL", "automation", "variables", "loops"]
categories: ["Study"]
series: ["Terraform 클라우드 자동화"]
author: "지민 오"
description: "Terraform 핵심 문법 마스터하기: Resource/Data 블록, 변수, Output, 반복문(count, for_each, for, dynamic) 완벽 가이드"
---

## 개요

Terraform의 기본 문법과 핵심 개념들을 정리합니다. Resource와 Data의 차이, 변수 활용, 반복문 등 Terraform 코드를 작성하는데 필요한 기초 지식을 다룹니다.

---

## ✅ 리소스 유형

```hcl
resource "타입" "이름" {...}
data "타입" "이름" {...}
```

### 타입(Type) 이해하기

- **타입은 고유**합니다.
- Terraform 프로바이더(플러그인)가 미리 정해놓은 것만 사용 가능
- 예: `local_file`, `aws_instance`, `azurerm_resource_group`
- **임의로 만들 수 없습니다**

> 📚 타입 저장소: [Terraform Registry](https://registry.terraform.io/modules/terraform-aws-modules/vpc/aws/latest)

---

## 🎯 resource vs data 블록

### 📌 resource

**실제로 인프라를 생성/수정/삭제하는 블록**

```hcl
resource "aws_instance" "web" {
  ami           = "ami-123456"
  instance_type = "t2.micro"
}
```

→ 실행하면 **인스턴스가 만들어집니다**.

### 📌 data

**읽기 전용 (조회만)**

이미 존재하는 외부의 정보, 상태, 값을 가져오는 역할입니다.

```hcl
data "aws_ami" "ubuntu" {
  most_recent = true
  owners      = ["099720109477"]

  filter {
    name   = "name"
    values = ["ubuntu/images/hvm-ssd/ubuntu-focal-20.04-amd64-server-*"]
  }
}
```

→ 이미 있는 AMI 정보를 **찾아서 가져오기만** 합니다.

### ✅ local_file의 경우

- `resource "local_file"` → 로컬에 파일을 **생성/수정/삭제**
- `data "local_file"` → 로컬에 이미 있는 파일을 **읽어서 조회** (파일을 만들지 않음)

---

## ✅ 입력 변수 (Variable)

입력 변수는 인프라를 구성하는데 필요한 속성 값을 정의해 **코드의 변경 없이 여러 인프라를 생성**하는데 목적이 있습니다.

### 변수 우선순위

변수마다 선언하는 방식에 따라 우선순위가 있으며, 선언할 때 **제일 높은 값이 적용**됩니다.

| 우선순위 | 적용 방법/파일 | 특징 |
|---------|---------------|------|
| 1 | `-var` (CLI) | 최상위, 직접 명령줄에서 지정 |
| 2 | `-var-file` (CLI) | 여러 파일, 뒤에 올수록 우선 |
| 3 | `*.auto.tfvars(.json)` | 자동 로드, 알파벳 순서, tfvars보다 우선 |
| 4 | `terraform.tfvars(.json)` | 루트 모듈에 있으면 자동 |
| 5 | `TF_VAR_변수명` (환경변수) | 쉘/시스템 환경 변수 |
| 6 | variable의 `default` | 최후의 기본값 |

---

## ✅ local (지역 변수)

코드 내에서 사용자가 지정한 값이나 속성 값을 가공해 참조 가능한 **지역 변수**입니다. 코드 내에서만 가공되어 동작하는 값을 말합니다.

단, 빈번하게 여러 곳에 사용하면 실제 값에 대한 추적이 어려워져 **유지 관리 측면에서 부담**이 될 수 있습니다.

```hcl
variable "prefix" {
  default = "hello"
}

locals {
  name = "terraform"
}

resource "local_file" "abc" {
  content  = local.name
  filename = "${path.module}/abc.txt"
}
```

> **주의**: `locals` 블록 안에 지정해야 하며, 정의되지 않은 local 값은 사용할 수 없습니다.

---

## ✅ Output 활용

```hcl
resource "local_file" "abc" {
  content  = "abc123"
  filename = "${path.module}/abc.txt"
}

output "file_id" {
  value = local_file.abc.id
}

output "file_abspath" {
  value = abspath(local_file.abc.filename)
}
```

### local_file 리소스의 주요 속성

| 속성명 | 의미 |
|-------|------|
| `id` | 해당 리소스의 고유 식별자 (일반적으로 파일 경로) |
| `filename` | 생성될 파일의 이름 |
| `content` | 실제 파일에 쓸 내용 |
| `file_permission` / `directory_permission` | 파일/디렉터리 권한 |
| `content_*` | 파일 내용의 다양한 해시값 |

### 핵심 포인트

- `local_file` 리소스에는 `id`, `content`, `filename` 등 여러 속성이 있음
- output에서 `id`를 지정하면 **파일 경로**가 출력됨
- `plan`에서는 실제 값이 안 나오고, **apply 후에 진짜 값이 할당**됨
- `content`는 파일에 실제로 쓰이는 내용, `id`는 파일의 고유 경로
- **Output은 원하는 속성 아무거나 출력 가능!**

---

## ✅ 반복문

list 형태의 값 목록이나 key-value 형태의 문자열 집합인 데이터가 있는 경우, 동일한 내용에 대해 Terraform 구성 정의를 **반복적으로 하지 않고 관리**할 수 있습니다.

### 📌 count

```hcl
variable "names" {
  type    = list(string)
  default = ["a", "b", "c"]
}

resource "local_file" "abc" {
  count    = length(var.names)
  content  = "abc"
  filename = "${path.module}/abc-${var.names[count.index]}.txt"
}

resource "local_file" "def" {
  count    = length(var.names)
  content  = local_file.abc[count.index].content
  filename = "${path.module}/def-${element(var.names, count.index)}.txt"
}
```

**설명**:
- `count`를 숫자로 하지 않고 `length(var.names)`를 사용해 리스트 길이만큼 반복
- `count`는 3이므로 세 번 반복
- `element(리스트, 인덱스)`: 리스트 (a, b, c), 인덱스 (0, 1, 2)만큼 반복
- 만약 인덱스가 더 크다면 처음으로 돌아와 순환

### 📌 for_each

```hcl
variable "names" {
  default = {
    a = "content a"
    c = "content c"
  }
}

resource "local_file" "abc" {
  for_each = var.names
  content  = each.value
  filename = "${path.module}/abc-${each.key}.txt"
}

resource "local_file" "def" {
  for_each = local_file.abc
  content  = each.value.content
  filename = "${path.module}/def-${each.key}.txt"
}
```

**설명**:
- `count`를 받지 않고 `for_each`를 받아 자동으로 반복 횟수와 내용을 받음
- 중요한 것은 key-value 값이 다르고 그것을 통해 파일을 작성
- `each.value` = content
- `each.key` = 파일 키(a, c)

### 📌 for (표현식)

for 문은 **복합 형식 값의 형태를 변환**하는데 사용됩니다. 예를 들어 list 값의 포맷을 변경하거나 특정 접두사를 추가할 수도 있고, output에 원하는 형태로 반복적인 결과를 표현할 수도 있습니다.

#### for vs for_each 비교

| 구분 | for 문 (표현식) | for_each (리소스/데이터 반복) |
|-----|----------------|----------------------------|
| **사용처** | 변수/locals 등 | resource/data 블록 내 |
| **리턴값** | list, map 등 새 자료구조 | 리소스(파일, 서버 등) 여러 개 생성 |
| **반복대상** | list, map | list, map, set |
| **예시** | `[for x in ... : ...]` | `for_each = ...` |

#### for 문 예제

```hcl
variable "members" {
  type = map(object({
    role  = string
    group = string
  }))
  default = {
    "ab" = { role = "member", group = "dev" }
    "cd" = { role = "admin", group = "dev" }
    "ef" = { role = "member", group = "ops" }
  }
}

output "A_to_tuple" {
  value = [for k, v in var.members : "${k} is ${v.role}"]
}

output "B_get_only_role" {
  value = {
    for name, user in var.members : name => user.role
    if user.role == "admin"
  }
}

output "C_group" {
  value = {
    for name, user in var.members : user.role => name...
  }
}
```

**핵심 포인트**:
- 문법의 자연스러움이 HCL을 이해하는데 도움이 많이 됨
- 바로 생각하기 어렵지만 많이 사용해 경험을 얻으면 익숙해짐
- **`key = value`** 쌍으로 생각하는 것이 중요

### 📌 dynamic

리소스 같은 Terraform 구성을 작성하다 보면 `count`나 `for_each` 구문을 사용한 리소스 전체를 여러 개 생성하는 것 외에도, **리소스 내에 선언되는 구성 블록을 다중으로 작성**해야 하는 경우가 있습니다.

`dynamic` 블록을 작성하려면 **기존 블록 속성 이름을 dynamic 블록 이름으로 선언**합니다.

```hcl
variable "rules" {
  default = [
    { from_port = 80, to_port = 80, protocol = "tcp", cidr_blocks = ["0.0.0.0/0"] },
    { from_port = 22, to_port = 22, protocol = "tcp", cidr_blocks = ["0.0.0.0/0"] }
  ]
}

resource "aws_security_group" "example" {
  name = "example-sg"

  dynamic "ingress" {
    for_each = var.rules
    content {
      from_port   = ingress.value.from_port
      to_port     = ingress.value.to_port
      protocol    = ingress.value.protocol
      cidr_blocks = ingress.value.cidr_blocks
    }
  }
}
```

**설명**:
- `dynamic` 블록처럼 `for_each`를 사용해 여러 값을 반복해 사용할 수 있음
- Security Group의 ingress 규칙을 여러 개 동적으로 생성
- 코드 중복을 줄이고 유지보수성을 향상

---

## 📝 정리

Terraform의 기본 문법과 핵심 개념들을 살펴보았습니다:

- **Resource vs Data**: 생성/수정 vs 조회
- **변수**: 입력 변수, 지역 변수, 우선순위
- **Output**: 리소스 속성 출력
- **반복문**: count, for_each, for, dynamic

이러한 기본 개념들을 잘 이해하면 더욱 효율적이고 유지보수하기 쉬운 Terraform 코드를 작성할 수 있습니다.
