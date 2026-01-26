---
title: "Terraform 클라우드 자동화 여정 #1"
date: 2025-10-09T10:55:01-04:00
draft: false
tags: ["Terraform", "AWS", "IaC", "automation", "EC2", "Ubuntu"]
categories: ["study", "Cloud & Terraform"]
series: ["Terraform 클라우드 자동화"]
author: "지민 오"
description: "Terraform과 AWS CLI 설치부터 EC2 인스턴스 생성 및 SSH 접속까지: IaC 입문 실습 가이드"
---

## 개요

현재는 Terraform을 느끼고 알아가는 중입니다. 아직은 잘 모르겠지만, 원리 정도는 이해하는 수준입니다. 계속 하다보면 늘지 않을까 싶어 실습을 반복하고 매일 코드 하나씩 작성해볼 생각입니다.

블로그에 글을 올리지 않더라도 기능을 하나씩 추가해보는 등 사용해볼 생각입니다. 하다보면 점점 보일 것이고 궁금할만한 내용도 생기지 않을까 싶습니다.

---

## 🎯 목표

- VirtualBox + NAT (Ubuntu 22.04) + AWS CLI / Terraform 설치
- AWS configure 설정
- 인스턴스 생성 및 키페어를 통한 AWS instance 접속

### 💡 환경 선택

Terraform을 Windows / VirtualBox (Ubuntu 22.04) 둘 중 어디서 사용할지 고민했었습니다.

**실무에 가깝게 구현하기 위해 후자(VirtualBox Ubuntu)를 선택**하였습니다. 이후에 필요한 포트들만 포트 포워딩을 잘 해주면 될 것 같습니다.

---

## ✅ Ubuntu 22.04에 Terraform / AWS CLI 설치

### Terraform 설치

```bash
# 시스템 업데이트 및 필수 패키지 설치
sudo apt-get update && sudo apt-get install -y gnupg software-properties-common curl

# HashiCorp GPG 키 추가
curl -fsSL https://apt.releases.hashicorp.com/gpg | sudo gpg --dearmor -o /usr/share/keyrings/hashicorp-archive-keyring.gpg

# HashiCorp 저장소 추가
echo "deb [signed-by=/usr/share/keyrings/hashicorp-archive-keyring.gpg] https://apt.releases.hashicorp.com $(lsb_release -cs) main" | sudo tee /etc/apt/sources.list.d/hashicorp.list

# Terraform 설치
sudo apt update
sudo apt install terraform

# 설치 확인
terraform -version

```

### AWS CLI 설치

```bash
# AWS CLI v2 다운로드 및 설치
curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
unzip awscliv2.zip
sudo ./aws/install

# 설치 확인
aws --version

```

---

## ✅ AWS CLI 인증 및 연결 확인

### AWS Configure 설정

```bash
aws configure

```

프롬프트에 맞게 다음 정보를 입력합니다:

- **Access Key ID**: IAM 사용자의 액세스 키
- **Secret Access Key**: IAM 사용자의 비밀 액세스 키
- **Default region**: `ap-northeast-2` (서울 리전 권장)
- **Default output format**: `json`

### 💡 Access Key 발급 방법

1. AWS 콘솔 → IAM 유저 생성
2. 보안 자격 증명 → Access Key 발급
3. Access Key와 Secret Access Key 발급 및 저장

> **참고**: 키 입력 후 나머지는 기본값을 사용하면 됩니다.

### 연결 테스트

```bash
aws sts get-caller-identity

```

연결이 정상이면 `UserId`, `Account`, `Arn` 정보가 출력됩니다.

---

## ✅ Terraform 기본 실습

### 💡 실습 개요

AMI를 사용하여 간단한 t2.micro 인스턴스를 생성합니다. 테스트에 가까우니 가볍게 진행하면 됩니다.

### main.tf 파일 생성

```hcl
provider "aws" {
  region = "ap-northeast-2"
}

resource "aws_instance" "test" {
  ami           = "ami-0c9c942bd7bf113a2"   # Ubuntu 22.04 LTS, 서울 리전
  instance_type = "t2.micro"
}
```

### Terraform 실행

```bash
terraform init   # Terraform 초기화
terraform plan   # 생성될 구조 미리 확인
terraform apply  # 클라우드 인프라 생성 시작

```

---

## ✅ EC2를 안전하게 만들고 SSH로 접속

### 💡 키페어(SSH Key Pair)란?

EC2 리눅스 인스턴스에 접속(SSH)할 때 **암호 대신 사용하는 파일 쌍**입니다. 공개키/개인키로 구성되어 있습니다.

### 키페어 생성

1. **EC2 대시보드** → **네트워크 및 보안** → **키페어** → **키 페어 생성**
2. **파일 형식**: PEM (리눅스에서 사용)
3. 키페어 파일을 리눅스로 이동

### 키페어 파일 전송 방법

여러 방법이 있습니다:

- 이전에 구현한 **SFTP** 사용
- **NFS** 사용
- 이메일 또는 클라우드를 통해 다운로드

### 키페어 권한 설정

```bash
chmod 400 ~/tf-key.pem

```

> **중요**: 이 권한이어야 SSH에서 보안 관련 오류가 발생하지 않습니다.

---

## ✅ 키페어를 이용한 SSH 접속 설정

키페어를 통한 접속을 위해 `main.tf` 파일을 다음과 같이 수정합니다:

```hcl
provider "aws" {
  region = "ap-northeast-2"
}

data "aws_vpc" "default" {
  default = true
}

resource "aws_security_group" "allow_ssh" {
  name        = "tf-allow-ssh"
  description = "Allow SSH inbound traffic"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description = "SSH from anywhere"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_instance" "my_first_ec2" {
  ami                         = "ami-0c9c942bd7bf113a2"
  instance_type               = "t2.micro"
  vpc_security_group_ids      = [aws_security_group.allow_ssh.id]
  key_name                    = "tf-key"
  associate_public_ip_address = true
  tags = {
    Name = "tf-first-ec2"
  }
}
```

### Terraform 적용

```bash
terraform init
terraform apply

```

### 🔥 주의사항

해당 파일은 VPC, Security Group, Security Group 룰까지 만드는 명령어입니다. 해당 인스턴스는 퍼블릭 IP를 주어 접근할 예정입니다.

**테스트이기 때문에 가능하지만 실무에서는 절대 해서는 안 될 행동입니다. 프로덕션 환경에서는 퍼블릭 액세스를 최소화해야 합니다.**

---

## ✅ EC2 인스턴스 접속

### 퍼블릭 IP 확인

```bash
aws ec2 describe-instances \
  --filters "Name=tag:Name,Values=tf-first-ec2" \
  --query "Reservations[*].Instances[*].PublicIpAddress" \
  --output text

```

### SSH 접속

```bash
ssh -i ~/tf-key.pem ubuntu@<EC2-퍼블릭-IP>

```

---

## 🔓 트러블슈팅

### 💡 경험

기본적으로 코드 오타 말고는 크게 문제는 없었습니다. 복사해서 사용한다면 아마 정말 빠르게 진행될 실습이라고 생각합니다.

### 오류: 의존성 사이클 에러

이번 오류는 **의존성 사이클** 때문에 발생하는 대표적인 Terraform 에러입니다.

```

Error: Cycle: aws_instance.my_first_ec2, aws_security_group.allow_ssh

```

### 원인

보안 그룹 생성 시, VPC ID를 EC2 인스턴스(`aws_instance.my_first_ec2`)의 속성에서 가져왔기 때문입니다. EC2는 보안 그룹이 필요하니 서로 먼저 만들어야 하는 **무한 순환**이 생긴 것입니다.

```hcl
# 잘못된 예 (순환 참조)
vpc_id = aws_instance.my_first_ec2.vpc_security_group_ids[0]
vpc_security_group_ids = [aws_security_group.allow_ssh.id]

```

위와 같이 서로가 서로를 참조합니다.

### ✅ 해결 방법

**정상적인 흐름**은 Security Group이 EC2보다 항상 먼저 생성 가능해야 합니다.

- Security Group 생성은 **VPC ID만 필요** → EC2 정보가 필요하면 안 됩니다
- **Default VPC를 데이터로 불러온 뒤** 그 ID로 Security Group을 생성하는 것이 올바른 방법입니다

```hcl
# 올바른 예
data "aws_vpc" "default" {
  default = true
}

resource "aws_security_group" "allow_ssh" {
  vpc_id = data.aws_vpc.default.id
  # ...
}
```

---

## 📝 정리

이번 실습을 통해 Terraform의 기본 개념과 AWS 인프라 구성 방법을 익혔습니다. 특히 의존성 관리의 중요성을 배울 수 있었습니다. 앞으로도 꾸준히 실습하며 IaC 역량을 키워나가겠습니다.
