---
title: "Kubernetes Probe 완벽 가이드: livenessProbe와 readinessProbe"
date: 2025-11-05T10:03:08+09:00
draft: false
categories: ["k8s","ubuntu","nextcloud"]
tags: ["k8s","probe","troubleshooting","study","health-check","nextcloud","error"]
description: "Kubernetes Probe 완벽 가이드: livenessProbe와 readinessProbe"
author: "늦찌민"
---

## Git Secret 노출 트러블슈팅 정리

### 📋 문제 발생 과정
---

### 1. 초기 상황

- Kubernetes Dashboard 접근용 admin token을 dashboard/dashboard-token.txt 파일로 저장
- 해당 파일을 Public Git Repository에 커밋 및 푸시
- GitLab/GitHub deployment 화면에서 실패 발생 (사진 참조)

### 2. 문제 발견
```bash
# GitGuardian(ggshield)로 스캔 실행
ggshield secret scan repo .

# 결과: Kubernetes JWT 토큰이 commit 히스토리에서 감지됨
# Commit: 935a85afe976d495bc0ac282ada86864a6cbf3a9
# 파일: dashboard/dashboard-token.txt
```

---

### 🔍 왜 이런 일이 발생했나?
### 근본 원인

1. 민감 정보를 파일로 저장: 토큰을 평문 파일로 저장
2. .gitignore 미설정: 토큰 파일이 git 추적 대상에 포함됨
3. Pre-commit 검증 부재: 커밋 전 민감정보 검사 없음
4. Public Repository: 노출된 정보가 전 세계에 공개됨

### 위험성

- Public repo에 노출된 토큰은 누구나 접근 가능
- 해당 토큰으로 Kubernetes 클러스터의 모든 리소스 제어 가능 (cluster-admin 권한)
- Git 히스토리에 남아있어 삭제해도 계속 접근 가능

## 🛠️ 해결 과정
####  1단계: 문제 인식
```
# ggshield가 탐지만 함 - 자동 수정은 안 됨
# 수동으로 처리 필요
```

#### 2단계: 토큰 무효화 시도 (실패)
```
# Secret만 삭제하고 재생성
kubectl delete secret admin-user-token -n kubernetes-dashboard
kubectl apply -f secret.yaml

# 결과: 토큰이 똑같이 생성됨! ❌kub
```

#### 실패 원인:

- ServiceAccount의 UID가 변하지 않음 \
- JWT 토큰은 다음 정보로 생성됨:

```
{
    "kubernetes.io/serviceaccount/service-account.uid": "32831f10-aa7b-46d1-be40-ff3ca1df454b",
    "kubernetes.io/serviceaccount/service-account.name": "admin-user",
    // ...
  }
```
- 동일한 UID = 동일한 토큰
#### 3단계: 올바른 토큰 무효화 (성공)
```bash
# ServiceAccount 자체를 삭제해야 UID가 변경됨
kubectl delete serviceaccount admin-user -n kubernetes-dashboard

# 재생성하면 새로운 UID 부여 → 완전히 다른 토큰 생성
kubectl create serviceaccount admin-user -n kubernetes-dashboard
kubectl create clusterrolebinding admin-user \
  --clusterrole=cluster-admin \
  --serviceaccount=kubernetes-dashboard:admin-user
```
#### 4단계: Git 히스토리에서 완전 제거
```bash
# BFG Repo-Cleaner로 히스토리에서 파일 제거
bfg --delete-files dashboard-token.txt

# Git 정리
git reflog expire --expire=now --all
git gc --prune=now --aggressive

# Force push (주의!)
git push origin main --force
```

### 5단계: 재발 방지
```bash
# .gitignore 설정
cat >> .gitignore << EOF
dashboard/dashboard-token.txt
dashboard/*.token
*-token.txt
*.token
.env
EOF

# Pre-commit hook 설치
ggshield install -m local

# 또는 수동 hook 생성
cat > .git/hooks/pre-commit << 'EOF'
#!/bin/bash
ggshield secret scan pre-commit
if [ $? -ne 0 ]; then
    echo "❌ Secret detected! Commit blocked."
    exit 1
fi
EOF
chmod +x .git/hooks/pre-commit
```

## 📊 핵심 개념 정리

### Kubernetes ServiceAccount Token의 구조
```
JWT Token = Header + Payload + Signature

Payload에 포함되는 정보:
- ServiceAccount UID (고유 식별자)
- ServiceAccount 이름
- Namespace
- Secret 이름

→ UID가 같으면 토큰도 같음!
```

#### Secret vs ServiceAccount 삭제의 차이

### ✅ 최종 해결책
```bash
#!/bin/bash
# dashboard/rotate-token.sh

# 1. ServiceAccount 재생성 (UID 변경)
kubectl delete serviceaccount admin-user -n kubernetes-dashboard
kubectl create serviceaccount admin-user -n kubernetes-dashboard

# 2. 권한 재설정
kubectl create clusterrolebinding admin-user \
  --clusterrole=cluster-admin \
  --serviceaccount=kubernetes-dashboard:admin-user

# 3. 새 토큰 생성
cat <<EOF | kubectl apply -f -
apiVersion: v1
kind: Secret
metadata:
  name: admin-user-token
  namespace: kubernetes-dashboard
  annotations:
    kubernetes.io/service-account.name: admin-user
type: kubernetes.io/service-account-token
EOF

# 4. 새 토큰 출력
sleep 2
kubectl get secret admin-user-token -n kubernetes-dashboard -o jsonpath='{.data.token}' | base64 -d
```

### 🎓 교훈

1. 민감정보는 Git에 절대 올리지 말 것
    - 환경변수, Secret Manager, Vault 등 사용


2. Pre-commit hook으로 사전 검증

    - ggshield, gitleaks, git-secrets 등


3. ServiceAccount 토큰의 생성 원리 이해

    - Secret만 재생성해서는 토큰이 바뀌지 않음
    - ServiceAccount UID가 핵심


4. Public Repo에서는 더욱 주의

    - 한번 노출되면 전 세계가 볼 수 있음
    - Git 히스토리 완전 제거 필요


5. .gitignore 선제적 설정

    -   프로젝트 시작 시점부터 설정
    - *.token, .env, *secret* 등