---
title: "트러블슈팅 기록: VSCode Remote SSH & AWS Bastion 접속"
date: 2025-10-09
draft: false
tags: ["troubleshooting", "vscode", "aws", "alb", "bastion", "ssh"]
categories: ["DevOps"]
---

## 💡 개요

오늘 진행한 트러블슈팅 과정을 정리한 기록입니다. VSCode 설정부터 AWS ALB를 통한 Bastion 서버 접속까지의 과정에서 발생한 문제들과 해결 방법을 다룹니다.

> ⚠️ **주의:** 이 문서의 모든 IP 주소, 도메인, 키 파일명은 예시입니다. 실제 환경에 맞게 수정하여 사용하세요.

---

## 1. VSCode Remote SSH 접속 문제

### 문제 상황

AWS 인스턴스에 VSCode Remote SSH로 접속 시도 중 무한 로딩에 빠졌습니다.
- VSCode Server Downloading 상태에서 멈춤
- "Installing VS Code Server" 단계에서 진행되지 않음
- 연결 타임아웃 발생

### 원인 분석

**근본 원인:** 인스턴스가 인터넷 Outbound 연결이 불가능한 환경

- Bastion/Private Subnet 환경으로 외부 인터넷 직접 접근 불가
- VSCode가 자동으로 `https://update.code.visualstudio.com`에서 서버 다운로드 시도
- NAT Gateway 또는 Internet Gateway 미설정
- 보안 그룹에서 Outbound HTTPS 트래픽 차단

### 해결 방법

로컬 또는 Bastion 서버에서 VSCode Server를 수동으로 다운로드한 후 SCP로 전송하는 방식으로 해결했습니다.

#### 1단계: VSCode Commit ID 확인

```bash
# 로컬 VSCode에서 버전 확인
code --version

# 출력 예시:
# 1.85.1
# abc123def456...  <- 이게 COMMIT_ID (실제 값은 40자리 해시값)
# x64
```

#### 2단계: VSCode Server 다운로드

```bash
# Commit ID를 변수로 설정 (본인의 COMMIT_ID로 변경)
# Linux/Mac
export COMMIT_ID="YOUR_COMMIT_ID_HERE"

# Windows (PowerShell)
$COMMIT_ID = "YOUR_COMMIT_ID_HERE"

# VSCode Server 다운로드
wget "https://update.code.visualstudio.com/commit:${COMMIT_ID}/server-linux-x64/stable" -O vscode-server-linux-x64.tar.gz

# 또는 curl 사용
curl -L "https://update.code.visualstudio.com/commit:${COMMIT_ID}/server-linux-x64/stable" -o vscode-server-linux-x64.tar.gz
```

#### 3단계: SCP로 서버에 전송

**SSH Config가 설정되어 있는 경우:**

```bash
# ~/.ssh/config 예시 (실제 환경에 맞게 수정 필요)
Host bastion
    HostName your-alb-xxxxx.ap-northeast-2.elb.amazonaws.com
    User ec2-user
    IdentityFile ~/.ssh/your-bastion-key.pem
    Port 22

Host internal-server
    HostName 10.x.x.x  # 실제 Private IP
    User ubuntu
    IdentityFile ~/.ssh/your-internal-key.pem
    ProxyJump bastion
    Port 22
```

**파일 전송:**

```bash
# Config 사용 시
scp vscode-server-linux-x64.tar.gz internal-server:/tmp/

# 또는 직접 명령어로 (실제 IP로 변경)
scp -o ProxyJump=bastion vscode-server-linux-x64.tar.gz ubuntu@10.x.x.x:/tmp/
```

#### 4단계: 서버에서 압축 해제

```bash
# 서버에 접속
ssh internal-server

# VSCode Server 디렉토리 생성
mkdir -p ~/.vscode-server/bin/${COMMIT_ID}

# 압축 해제 (최상위 디렉토리 제거)
tar xvzf /tmp/vscode-server-linux-x64.tar.gz -C ~/.vscode-server/bin/${COMMIT_ID} --strip-components 1

# 설치 확인
ls -la ~/.vscode-server/bin/${COMMIT_ID}

# 임시 파일 삭제
rm /tmp/vscode-server-linux-x64.tar.gz
```

#### 전체 프로세스 자동화 스크립트

```bash
#!/bin/bash
# 실제 값으로 변경 필요
COMMIT_ID="YOUR_COMMIT_ID_HERE"
SERVER="internal-server"  # SSH config에 설정한 호스트명

# 1. 다운로드
echo "Downloading VSCode Server..."
wget "https://update.code.visualstudio.com/commit:${COMMIT_ID}/server-linux-x64/stable" -O vscode-server-linux-x64.tar.gz

# 2. 전송
echo "Uploading to server..."
scp vscode-server-linux-x64.tar.gz ${SERVER}:/tmp/

# 3. 원격에서 설치
echo "Installing on server..."
ssh ${SERVER} << EOF
mkdir -p ~/.vscode-server/bin/${COMMIT_ID}
tar xvzf /tmp/vscode-server-linux-x64.tar.gz -C ~/.vscode-server/bin/${COMMIT_ID} --strip-components 1
rm /tmp/vscode-server-linux-x64.tar.gz
echo "Installation completed!"
EOF

# 4. 로컬 파일 삭제
rm vscode-server-linux-x64.tar.gz

echo "Done! You can now connect with VSCode."
```

### 결과

VSCode Server를 수동으로 설치한 후 정상적으로 Remote SSH 접속이 가능해졌습니다.
- 자동 다운로드 단계를 건너뛰고 즉시 연결
- 안정적인 원격 개발 환경 구축 완료

---

## 2. SSH Config 최적화

### 연결 안정성 개선

VSCode Remote SSH 연결이 자주 끊기는 문제를 해결하기 위한 설정입니다.

```bash
# ~/.ssh/config (실제 환경에 맞게 수정)
Host bastion
    HostName your-alb-xxxxx.region.elb.amazonaws.com
    User ec2-user
    IdentityFile ~/.ssh/your-bastion-key.pem
    Port 22
    ServerAliveInterval 60
    ServerAliveCountMax 10
    TCPKeepAlive yes

Host internal-server
    HostName 10.x.x.x  # Private IP
    User ubuntu
    IdentityFile ~/.ssh/your-internal-key.pem
    ProxyJump bastion
    Port 22
    ServerAliveInterval 60
    ServerAliveCountMax 10
    TCPKeepAlive yes
```

**주요 설정 설명:**
- `ServerAliveInterval 60`: 60초마다 keep-alive 패킷 전송
- `ServerAliveCountMax 10`: 최대 10번까지 응답 대기
- `TCPKeepAlive yes`: TCP 레벨 keep-alive 활성화
- `ProxyJump`: Bastion을 거쳐 내부 서버 접속

---

## 3. ALB를 통한 Bastion 접속 구조

### 아키텍처

```
[개발자 PC] 
    ↓ SSH (Port 22)
[Application Load Balancer]
    ↓ Target Group
[Bastion Host (Public Subnet)]
    ↓ SSH
[Private Instance (Private Subnet)]
```

### ALB 설정 포인트

#### 리스너 설정
- Protocol: TCP
- Port: 22
- Target Group으로 포워딩

#### 타겟 그룹 설정
- Protocol: TCP
- Port: 22
- Health Check: TCP
- Bastion 인스턴스 등록

#### 보안 그룹
**ALB 보안 그룹:**
- Inbound: 0.0.0.0/0 → TCP 22 (또는 특정 IP 대역)

**Bastion 보안 그룹:**
- Inbound: ALB 보안 그룹 → TCP 22
- Outbound: Private Instance 보안 그룹 → TCP 22

**Private Instance 보안 그룹:**
- Inbound: Bastion 보안 그룹 → TCP 22

### 접속 테스트

```bash
# Bastion 접속 테스트
ssh bastion
# 또는
ssh ec2-user@your-alb-xxxxx.region.elb.amazonaws.com

# 내부 서버 직접 접속 (ProxyJump 사용)
ssh internal-server

# 또는 -J 옵션 사용
ssh -J bastion ubuntu@10.x.x.x
```

---

## 4. 추가 트러블슈팅 팁

### VSCode 연결이 계속 끊길 때

**서버 측 설정 (`/etc/ssh/sshd_config`):**
```bash
ClientAliveInterval 60
ClientAliveCountMax 10
TCPKeepAlive yes
```

설정 후 SSH 재시작:
```bash
sudo systemctl restart sshd
```

### VSCode 설정 (`settings.json`)

```json
{
    "remote.SSH.connectTimeout": 60,
    "remote.SSH.keepAlive": 60,
    "remote.SSH.showLoginTerminal": true
}
```

### 캐시 문제 시

```bash
# VSCode Server 캐시 삭제
rm -rf ~/.vscode-server

# 재연결 시 자동으로 재설치됨
```

---

## 배운 점

1. **Bastion 환경에서는 사전 준비가 필수**: 외부 인터넷 접근이 제한된 환경에서는 필요한 패키지나 바이너리를 미리 준비해야 함

2. **SSH Config의 중요성**: ProxyJump 설정으로 복잡한 네트워크 구조를 단순화할 수 있음

3. **Keep-Alive 설정**: 장시간 유지되는 SSH 세션은 적절한 Keep-Alive 설정이 필수

4. **보안 그룹 체인**: ALB → Bastion → Private Instance 순서로 보안 그룹을 체계적으로 관리

---

## 참고 자료

- [VSCode Remote SSH 공식 문서](https://code.visualstudio.com/docs/remote/ssh)
- [AWS ALB 공식 문서](https://docs.aws.amazon.com/elasticloadbalancing/)
- [SSH Config Man Page](https://man.openbsd.org/ssh_config)
- [AWS Bastion Host 베스트 프랙티스](https://aws.amazon.com/solutions/implementations/linux-bastion/)

---

## 마무리

Bastion 환경에서 VSCode Remote SSH를 사용하기 위해서는 네트워크 구조를 이해하고 적절한 우회 방법을 찾는 것이 중요합니다. 이번 트러블슈팅을 통해 인터넷이 제한된 환경에서도 효율적인 개발 환경을 구축할 수 있었습니다.

---

## 보안 주의사항

🔒 **민감 정보 관리:**
- SSH 키 파일 (.pem)은 절대 Git에 커밋하지 마세요
- SSH 키 권한은 반드시 `chmod 400` 또는 `chmod 600`으로 설정
- AWS 자격증명 정보는 환경변수나 AWS CLI 프로파일로 관리
- 불필요한 포트는 보안 그룹에서 차단
- Bastion 서버 접근은 특정 IP 대역으로 제한 권장

🔑 **SSH 키 권한 설정:**
```bash
# 키 파일 권한 설정
chmod 400 ~/.ssh/your-bastion-key.pem
chmod 400 ~/.ssh/your-internal-key.pem

# SSH 디렉토리 권한
chmod 700 ~/.ssh
```