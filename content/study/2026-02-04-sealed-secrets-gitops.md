---
title: "Kubernetes Secret 관리의 새로운 패러다임: Sealed Secrets로 GitOps 구현하기"
date: 2026-02-04T01:00:00+09:00
author: "늦찌민"
slug: "sealed-secrets-gitops-kubernetes"
categories: ["study", "Security"]
tags: ["kubernetes", "gitops", "sealed-secrets", "secret-management", "argocd", "security", "rsa-encryption"]
draft: false
---

> Kubernetes Secret을 Git에 안전하게 저장하고, GitOps로 자동화하는 방법을 다룹니다.
> Sealed Secrets를 사용하여 Secret을 RSA 암호화하고, 클러스터 재구축 시 자동 복구할 수 있는 시스템을 구축합니다.

<!--more-->

## 개요

**핵심 내용**:
- Secret 관리의 근본적인 문제와 해결 방법
- Sealed Secrets vs SOPS 비교 및 선택 기준
- 단계별 구축 과정 및 검증 방법
- 실제 발생한 트러블슈팅 사례
- 보안 트레이드오프 분석

**예상 소요 시간**: 1-2시간

---

## 1. 배경: Secret 관리의 근본적 문제

### 1.1 문제 상황

Kubernetes에서 Secret을 관리할 때 다음과 같은 딜레마에 직면합니다:

**문제 1: Git 저장 불가능**

```yaml
# 절대 Git에 커밋하면 안 됨
apiVersion: v1
kind: Secret
metadata:
  name: mysql-secret
data:
  password: Ym9hcmRwYXNzd29yZA==  # base64 (복호화 쉬움)
```

- Git 이력에 영구 저장됨
- 공개 저장소면 즉시 노출
- 삭제해도 history에 남음

**문제 2: 수동 관리의 한계**

```bash
# Secret을 kubectl로 직접 배포
kubectl apply -f mysql-secret.yaml
```

- Git에 없으므로 백업 없음
- 클러스터 재구축 시 수동 재생성 필요
- GitOps 원칙 위반

**문제 3: 클러스터 장애 시 복구 불가능**

실제 시나리오:
1. 클러스터 전체 장애 발생
2. ArgoCD로 모든 리소스 자동 복구
3. Secret만 없음 (Git에 없었으므로)
4. 수동으로 Secret 재생성 필요
5. 비밀번호를 어디에 저장했는지 기억 안 남

---

### 1.2 기존 해결 방법의 한계

**방법 1: Secret을 별도 시스템에 저장**
- Vault, AWS Secrets Manager 등
- 문제: 추가 인프라 필요, 복잡도 증가

**방법 2: 암호화된 파일을 Git에 저장 (SOPS)**
- AWS KMS, GCP KMS 사용
- 문제: 클라우드 의존성, 홈랩 부적합

**방법 3: 수동 관리**
- 엑셀, 노션 등에 기록
- 문제: 자동화 불가능, 휴먼 에러

---

## 2. 배경 지식

### 2.1 Sealed Secrets 개념

**정의**: Kubernetes Secret을 Public Key로 암호화하여 Git에 안전하게 저장하고, 클러스터 내부에서만 Private Key로 복호화하는 시스템

**핵심 아이디어**:
```
"암호화된 Secret을 Git에 저장하되,
복호화는 클러스터 내부에서만 가능하게 한다"
```

---

### 2.2 RSA 암호화 원리

**Public Key Cryptography**:
- Public Key: 누구나 가질 수 있음, 암호화만 가능
- Private Key: Controller만 가짐, 복호화 가능

```
평문: "boardpassword"
  ↓ Public Key 암호화
암호문: "AgBL5K2j9XfR..." (2048-bit)
  ↓ Private Key 복호화 (Controller만 가능)
평문: "boardpassword"
```

---

### 2.3 GitOps 전제 조건

**필수 구성 요소**:
1. Git Repository (GitHub, GitLab 등)
2. ArgoCD (GitOps 배포 도구)
3. Kubernetes Cluster

**GitOps 원칙**:
- Git = Source of Truth
- 모든 변경은 Git 커밋으로
- 자동 배포 및 동기화

---

### 2.4 보안 계층 이해

```
Layer 1: Git Repository
  → SealedSecret (RSA 암호화)
  → 외부 유출 방지

Layer 2: Kubernetes Cluster
  → Secret (base64 인코딩)
  → RBAC으로 접근 제어

Layer 3: Pod
  → 환경변수 (평문)
  → Pod 격리로 보호
```

**중요**: 각 계층은 서로 다른 보안 메커니즘을 사용합니다.

---

## 3. Sealed Secrets vs SOPS 비교

### 3.1 기술 비교

| 항목 | Sealed Secrets | SOPS |
|------|----------------|------|
| **암호화 방식** | RSA (Controller 자체) | AWS KMS, GCP KMS, Azure Key Vault, PGP, Age |
| **키 관리** | Controller 자동 생성 | 외부 KMS 또는 PGP 키 수동 관리 |
| **로컬 복호화** | 불가능 (Controller만) | 가능 (`sops -d secret.yaml`) |
| **부분 암호화** | 전체만 | 특정 필드만 가능 |
| **클러스터 이동** | 불가능 (Private Key 다름) | 가능 (KMS 키 공유 시) |
| **설정 복잡도** | 낮음 (helm install) | 높음 (KMS 설정, IAM 권한) |
| **비용** | 무료 | KMS 비용 발생 가능 |

---

### 3.2 사용 시나리오

**Sealed Secrets 적합**:
- 홈랩 / 온프레미스 환경
- 단일 클러스터 환경
- 간단한 설정 선호
- 로컬 복호화 불필요

**SOPS 적합**:
- 클라우드 환경 (AWS, GCP, Azure)
- 멀티 클러스터 (같은 Secret 공유)
- CI/CD에서 복호화 필요
- 부분 암호화 필요 (username은 평문, password만 암호화)

---

### 3.3 트레이드오프 분석

**Sealed Secrets**:

장점:
- 설정 간단 (5분 이내)
- 클라우드 의존성 없음
- 홈랩 최적화

단점:
- 클러스터 종속적 (이동 불가)
- 로컬 복호화 불가능
- Private Key 분실 시 복구 불가능

**SOPS**:

장점:
- 유연한 키 관리 (KMS, PGP, Age)
- 멀티 클러스터 지원
- 로컬 복호화 가능

단점:
- KMS 설정 복잡
- 클라우드 의존성
- 비용 발생 가능

**선택 기준**:
```
홈랩 / 온프레미스 → Sealed Secrets
클라우드 / 멀티 클러스터 → SOPS
```

---

## 4. 아키텍처 설계

### 4.1 전체 흐름

```
1. 로컬: Secret 암호화
   kubectl get secret mysql-secret -o yaml > /tmp/mysql-secret.yaml
   kubeseal -f /tmp/mysql-secret.yaml -w mysql-sealedsecret.yaml
   rm /tmp/mysql-secret.yaml  # 평문 삭제

2. Git: SealedSecret 커밋
   git add mysql-sealedsecret.yaml
   git commit -m "feat: mysql-secret를 SealedSecret으로 변환"
   git push

3. ArgoCD: 자동 배포
   Git 감지 (3초) → Sync → kubectl apply

4. Controller: Secret 자동 생성
   SealedSecret 감지 → Private Key 복호화 → Secret 생성

5. Pod: 평문 사용
   MYSQL_PASSWORD=boardpassword
```

---

### 4.2 컴포넌트 역할

**Controller (kube-system)**:
- Private Key 보유 및 관리
- SealedSecret 감지 및 복호화
- Secret 자동 생성 및 관리
- ownerReferences 설정

**kubeseal CLI**:
- Public Key 다운로드
- Secret → SealedSecret 변환
- 로컬에서 실행

**ArgoCD**:
- Git 변경 감지
- SealedSecret 배포
- 동기화 상태 관리

---

## 5. 구축 과정

### 5.1 Controller 설치

**Helm Wrapper Chart 방식 사용 이유**:
- ArgoCD로 관리 가능
- values.yaml로 커스터마이징
- GitOps 원칙 준수

**Chart.yaml**:
```yaml
apiVersion: v2
name: sealed-secrets
version: 1.0.0
dependencies:
  - name: sealed-secrets
    version: 2.16.2
    repository: https://bitnami-labs.github.io/sealed-secrets
```

**values.yaml 주요 설정**:
```yaml
sealed-secrets:
  fullnameOverride: "sealed-secrets-controller"
  namespaceOverride: "kube-system"

  commandArgs:
    - "--key-renew-period=720h"  # 30일 키 갱신

  metrics:
    serviceMonitor:
      enabled: false  # Prometheus Operator 없으면 비활성화
```

**배포**:
```bash
git push
kubectl apply -f argocd/sealed-secrets-app.yaml
```

**검증**:
```bash
kubectl get pods -n kube-system -l app.kubernetes.io/name=sealed-secrets
# 출력: sealed-secrets-controller-xxx 1/1 Running

kubeseal --fetch-cert > /tmp/pub-cert.pem
# Public Key 다운로드 성공
```

---

### 5.2 Secret 변환

**변환 대상 (5개)**:
- mysql-secret (데이터베이스 인증)
- mysql-exporter-secret (모니터링)
- github-oauth-secret (OAuth)
- ghcr-secret (Container Registry)
- aws-s3-credentials (백업)

**변환 스크립트**:
```bash
#!/bin/bash

SECRETS=(
  "mysql-secret:mysql"
  "mysql-exporter-secret:mysql"
  "github-oauth-secret:was"
  "ghcr-secret:web"
  "aws-s3-credentials:backup"
)

for item in "${SECRETS[@]}"; do
  SECRET=$(echo $item | cut -d: -f1)
  DIR=$(echo $item | cut -d: -f2)

  kubectl get secret $SECRET -n blog-system -o yaml > /tmp/$SECRET.yaml

  kubeseal -f /tmp/$SECRET.yaml \
    -w services/blog-system/$DIR/${SECRET/secret/sealedsecret}.yaml \
    --controller-name=sealed-secrets-controller \
    --controller-namespace=kube-system

  rm /tmp/$SECRET.yaml
done
```

---

### 5.3 Git 관리

**.gitignore 필수 설정**:
```gitignore
# 평문 Secret 차단
*-secret.yaml

# SealedSecret 허용
!*-sealedsecret.yaml

# 임시 파일
/tmp/
*.tmp
```

**커밋**:
```bash
git add services/blog-system/**/*-sealedsecret.yaml
git commit -m "feat: 모든 Secret을 SealedSecret으로 변환"
git push
```

---

### 5.4 검증

**1. SealedSecret 리소스 확인**:
```bash
kubectl get sealedsecrets -n blog-system
```

**2. Secret 자동 생성 확인**:
```bash
kubectl get secrets -n blog-system | grep -E "mysql|github|ghcr|aws"
```

**3. ownerReferences 확인**:
```bash
kubectl get secret mysql-secret -n blog-system -o yaml | grep -A 5 ownerReferences
```

출력 예시:
```yaml
ownerReferences:
- apiVersion: bitnami.com/v1alpha1
  controller: true  # Controller가 관리함
  kind: SealedSecret
  name: mysql-secret
```

**4. 복호화 테스트**:
```bash
kubectl get secret mysql-secret -n blog-system \
  -o jsonpath='{.data.mysql-password}' | base64 -d
# 출력: boardpassword (원본과 일치)
```

---

## 6. 트러블슈팅

### 6.1 ArgoCD가 SealedSecret을 배포하지 않음

**증상**:
- Git에 SealedSecret 파일 존재
- 클러스터에 SealedSecret 리소스 없음

**원인**: ArgoCD auto-sync 비활성화

**해결**:
```bash
# 수동 Sync
kubectl patch application blog-system -n argocd \
  --type merge -p '{"operation":{"sync":{"revision":"HEAD"}}}'

# 또는 직접 apply (임시)
kubectl apply -f services/blog-system/mysql/mysql-sealedsecret.yaml
```

**근본 원인**: ArgoCD Application 설정에서 `syncPolicy.automated` 확인 필요

---

### 6.2 ServiceMonitor CRD 에러

**증상**:
```
Error: The Kubernetes API could not find monitoring.coreos.com/ServiceMonitor
```

**원인**: Prometheus Operator 미설치 상태에서 ServiceMonitor 활성화

**해결**:
```yaml
# values.yaml 수정
metrics:
  serviceMonitor:
    enabled: false  # 비활성화
```

**배운 점**: 의존성 사전 확인 필요

---

### 6.3 Secret이 자동 생성되지 않음

**증상**:
- SealedSecret 리소스는 있음
- Secret 리소스는 없음

**진단**:
```bash
kubectl logs -n kube-system -l app.kubernetes.io/name=sealed-secrets --tail=50
kubectl describe sealedsecret mysql-secret -n blog-system
```

**흔한 원인**:
- namespace 불일치
- YAML 형식 오류
- Controller Pod 재시작 중

**해결**: Controller 재시작
```bash
kubectl rollout restart deployment sealed-secrets-controller -n kube-system
```

---

## 7. 성과 및 결과

### 7.1 Before vs After

**Before (수동 관리)**:
- Secret 관리: 수동 kubectl apply
- 백업: 없음 (로컬 파일만)
- 클러스터 재구축: 수동 재생성 (30분)
- 이력 추적: 불가능
- 팀 공유: 불가능 (보안 위험)

**After (Sealed Secrets)**:
- Secret 관리: Git으로 자동화
- 백업: Git 이력으로 보존
- 클러스터 재구축: 자동 복구 (3분)
- 이력 추적: Git history
- 팀 공유: 안전하게 가능

---

### 7.2 정량적 효과

**구축 시간**:
- Controller 설치: 10분
- Secret 변환 (5개): 15분
- 검증 및 테스트: 10분
- 총 소요: 35분

**운영 개선**:
- 클러스터 재구축 시간: 30분 → 3분 (90% 단축)
- Secret 변경 배포: 5분 (수동) → 자동 (100% 자동화)
- 백업 복구 시간: 불가능 → 즉시 (Git에서 복구)

---

### 7.3 보안 개선

**Git 보안**:
- 평문 노출 위험: 100% → 0%
- RSA 2048-bit 암호화
- Private Key 없으면 복호화 불가능

**운영 보안**:
- Secret 변경 추적: 불가능 → Git history
- 롤백: 불가능 → Git revert
- 감사: 불가능 → Git blame

---

## 8. 보안 고려사항

### 8.1 kubectl 접근 = Secret 복호화 가능 (정상)

**사실**:
```bash
kubectl get secret mysql-secret -n blog-system \
  -o jsonpath='{.data.mysql-password}' | base64 -d
# 출력: boardpassword
```

**이건 정상입니다.**

**이유**:
1. kubectl 접근 = 클러스터 관리자 권한
2. Pod도 Secret을 평문으로 사용해야 함
3. Sealed Secrets의 목적은 Git 보호 (kubectl 방지 아님)

**보안 경계**:
- Git 유출: Sealed Secrets로 방지
- kubectl 접근: RBAC으로 제어
- etcd 저장: etcd Encryption (선택)

---

### 8.2 Private Key 관리

**위치**: `kube-system` namespace Secret

**백업 필수**:
```bash
kubectl get secret sealed-secrets-key6pkll -n kube-system -o yaml \
  > ~/BACKUP/sealed-secrets-private-key.yaml
chmod 600 ~/BACKUP/sealed-secrets-private-key.yaml
```

**위험**: Private Key 분실 시 모든 SealedSecret 복구 불가능

**대책**:
- 안전한 곳에 백업 (USB, 암호화된 클라우드)
- 복수 백업 위치
- 접근 권한 제한

---

### 8.3 etcd 평문 저장

**확인**:
```bash
kubectl exec -n kube-system etcd-MASTER -- sh -c \
  "ETCDCTL_API=3 etcdctl get /registry/secrets/blog-system/mysql-secret"
# 출력: mysql-passwordboardpassword (평문)
```

**위험도**:
- 홈랩: 낮음 (물리적 접근 제한)
- Enterprise: 중간 (etcd encryption 권장)

**해결책 (선택)**:
- etcd Encryption at-rest
- 단, 키 분실 시 모든 Secret 복구 불가능

---

## 9. 결론

### 9.1 핵심 요약

**달성한 것**:
1. Secret을 Git으로 안전하게 관리
2. 클러스터 재구축 시 자동 복구
3. GitOps 완성 (Git = Source of Truth)
4. 팀 협업 기반 마련

**배운 것**:
1. 보안은 계층별로 다른 메커니즘 필요
2. 완벽한 보안보다 실용적 보안
3. 자동화가 보안을 개선함 (휴먼 에러 방지)

---

### 9.2 언제 사용하면 좋을까?

**권장 시나리오**:
- 홈랩 / 온프레미스
- 단일 클러스터
- GitOps 구현
- 클라우드 의존성 회피

**부적합 시나리오**:
- 멀티 클러스터 (같은 Secret 공유)
- 클라우드 환경 (KMS 사용 가능)
- 로컬에서 Secret 복호화 필요

---

### 9.3 다음 단계

**추천 작업**:
1. RBAC 세분화 (Secret 접근 제어)
2. etcd Encryption (선택, 백업 전략에 따라)
3. 다른 앱도 Sealed Secrets 적용
4. Private Key 백업 자동화

**추가 학습**:
- SOPS 비교 실습
- Vault 연동
- External Secrets Operator

---

## 참고 자료

- [Sealed Secrets GitHub](https://github.com/bitnami-labs/sealed-secrets)
- [kubeseal CLI](https://github.com/bitnami-labs/sealed-secrets#kubeseal)
- [GitOps 패턴](https://www.gitops.tech/)
- [Kubernetes Secret 보안](https://kubernetes.io/docs/concepts/security/secrets-good-practices/)

---

**작성자**: Infrastructure Team
**환경**: Kubernetes 1.31, Sealed Secrets 0.27.2
**저장소**: https://github.com/wlals2/k8s-manifests
