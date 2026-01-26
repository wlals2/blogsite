---
title: "GitOps CI/CD 파이프라인 구축 완전 가이드"
date: 2026-01-20
description: "GitHub Actions + ArgoCD로 Git을 Single Source of Truth로 만든 GitOps 파이프라인 구축기"
tags: ["gitops", "argocd", "github-actions", "kubernetes", "cicd", "canary-deployment"]
categories: ["study", "Kubernetes"]
---

## 개요

kubectl 직접 배포에서 완전한 GitOps 파이프라인으로 전환하여 SSOT(Single Source of Truth) 달성:

| 단계 | 목표 | 주요 개선 |
|------|------|----------|
| **AS-IS** | kubectl 직접 배포 | ArgoCD OutOfSync, 배포 이력 없음 |
| **TO-BE** | GitOps 완성 | Git = Cluster, 1-Click 롤백, 감사 추적 |
| **검증** | 실제 동작 테스트 | 2026-01-20 배포 성공 |

**최종 달성**:
- ✅ Git = Single Source of Truth (SSOT)
- ✅ 배포 이력 Git에 자동 기록
- ✅ 1-Click 롤백 (git revert)
- ✅ ArgoCD Synced 상태 유지
- ✅ 배포 시간: ~2분 (자동화)

---

## 1. 문제 인식 (AS-IS)

### 기존 아키텍처의 문제점

**트래픽 플로우 (구현 전)**:
```
Developer
  ↓ git push
GitHub Actions (CI + CD)
  ├─ Docker Build ✅
  ├─ GHCR Push ✅
  └─ kubectl 직접 배포 ❌
      ↓
Kubernetes Cluster
  ├─ Image: v12 (실제)
  └─ Git Manifest: v11 (오래됨) ❌
      ↓
ArgoCD: OutOfSync ❌
```

### 핵심 문제점

| 문제 | 영향 | 심각도 |
|------|------|--------|
| **GitOps 원칙 위반** | Git ≠ Cluster → SSOT 미달성 | ⚠️⚠️⚠️ |
| **ArgoCD OutOfSync** | selfHeal 의미 없음 | ⚠️⚠️ |
| **배포 이력 없음** | 감사 추적 불가능 | ⚠️⚠️ |
| **수동 롤백** | 5분 소요, 휴먼 에러 | ⚠️ |

**실제 상황**:
```bash
# Git Manifest
cat k8s-manifests/blog-system/web-rollout.yaml | grep image
# image: ghcr.io/wlals2/blog-web:v11

# Kubernetes Cluster
kubectl get rollout web -n blog-system -o jsonpath='{.spec.template.spec.containers[0].image}'
# ghcr.io/wlals2/blog-web:v12

# ❌ Git ≠ Cluster (OutOfSync)
```

---

## 2. GitOps 구현 (TO-BE)

### 개선된 아키텍처

**트래픽 플로우 (구현 후)**:
```
Developer
  ↓ git push
GitHub Actions (CI only)
  ├─ Docker Build ✅
  ├─ GHCR Push ✅
  └─ Git Manifest Update ✅
      │ yq eval ".image = v12"
      │ git commit -m "Update to v12"
      └─ git push k8s-manifests
          ↓
Git Manifest (SSOT) ⭐
  ├─ k8s-manifests/blog-system/web-rollout.yaml
  └─ image: v12 ✅
      ↓ (ArgoCD Poll: 3초)
ArgoCD (CD only)
  ├─ Git Diff 확인
  └─ kubectl apply (자동) ✅
      ↓
Kubernetes Cluster
  └─ Image: v12 ✅
      ↓
✅ Git = Cluster (Synced)
```

### 역할 분리

| 컴포넌트 | Before | After | 역할 |
|----------|--------|-------|------|
| **GitHub Actions** | CI + CD | CI only | 빌드 + Manifest 업데이트 |
| **ArgoCD** | selfHeal만 | CD 전담 | Git → Cluster 동기화 |
| **Git Manifest** | 참고용 | SSOT | 유일한 진실의 원천 |

---

## 3. 구현 과정

### Step 1: GitHub Actions 워크플로우 수정

**변경 전** (kubectl 직접 배포):
```yaml
# .github/workflows/deploy-web.yml
- name: Deploy to Kubernetes
  run: |
    kubectl argo rollouts set image web \
      nginx=ghcr.io/wlals2/blog-web:v${{ github.run_number }}
```

**변경 후** (Manifest 업데이트):
```yaml
# .github/workflows/deploy-web.yml
- name: Update Kubernetes Manifest (GitOps)
  env:
    GITOPS_TOKEN: ${{ secrets.GITOPS_TOKEN }}
  run: |
    # 1. k8s-manifests repo clone
    git clone https://x-access-token:$GITOPS_TOKEN@github.com/wlals2/k8s-manifests.git
    cd k8s-manifests/blog-system
    
    # 2. yq로 이미지 업데이트
    yq eval ".spec.template.spec.containers[0].image = \"ghcr.io/wlals2/blog-web:v${{ github.run_number }}\"" \
      -i web-rollout.yaml
    
    # 3. Git Commit & Push
    git config user.name "github-actions[bot]"
    git config user.email "github-actions[bot]@users.noreply.github.com"
    git add web-rollout.yaml
    git commit -m "chore: Update WEB image to v${{ github.run_number }}"
    git push
```

### Step 2: GitHub Token 설정

**2가지 Token 필요**:

| Secret | 용도 | Scope |
|--------|------|-------|
| `GHCR_TOKEN` | Container Registry | `write:packages`, `read:packages` |
| `GITOPS_TOKEN` | k8s-manifests Push | `repo` (Full control) |

**GITOPS_TOKEN 생성**:
```bash
# GitHub → Settings → Developer settings → Personal Access Tokens
# - Note: "GITOPS_TOKEN (k8s-manifests push)"
# - Expiration: 90 days
# - Scope: ✅ repo (Full control)

# blogsite Repository Secrets 추가
# Settings → Secrets → Actions → New repository secret
# Name: GITOPS_TOKEN
# Value: ghp_xxxxxxxxxxxxx
```

### Step 3: ArgoCD Application 설정

**ignoreDifferences 추가** (Argo Rollouts 호환):
```yaml
# argocd/blog-system-application.yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: blog-system
  namespace: argocd
spec:
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
  
  # Rollouts 동적 레이블 무시
  ignoreDifferences:
  - group: networking.istio.io
    kind: DestinationRule
    name: web-dest-rule
    jsonPointers:
    - /spec/subsets/0/labels  # stable
    - /spec/subsets/1/labels  # canary
```

**왜 필요한가?**
- Argo Rollouts가 `rollouts-pod-template-hash` 레이블 동적 추가
- Git Manifest에는 이 레이블 없음
- ArgoCD가 OutOfSync로 인식하는 것 방지

---

## 4. 실제 검증 결과 (2026-01-20)

### 테스트 시나리오

```bash
# 1. 파일 수정
cd ~/blogsite
echo "# GitOps Test" >> README.md

# 2. Git Push
git add README.md
git commit -m "test: GitOps verification"
git push origin main

# 3. 자동 실행 대기 (~2분 30초)
```

### 자동화 흐름

```
20:20:22 - Git Push
  ↓
20:21:05 - GitHub Actions: Docker Build ✅
20:21:32 - GHCR Push: v14 ✅
20:21:55 - Manifest Update: v14 ✅
  ↓
20:22:10 - ArgoCD: Git Diff 감지
20:22:15 - kubectl apply (자동)
  ↓
20:22:45 - Pod Rolling Update 완료 ✅
  ↓
총 소요 시간: ~2분 30초
```

### SSOT 달성 검증

```bash
# Git Manifest 확인
cat ~/k8s-manifests/blog-system/web-rollout.yaml | grep image
# image: ghcr.io/wlals2/blog-web:v14 ✅

# Cluster 확인
kubectl get rollout web -n blog-system -o jsonpath='{.spec.template.spec.containers[0].image}'
# ghcr.io/wlals2/blog-web:v14 ✅

# ArgoCD 상태
kubectl get application blog-system -n argocd
# NAME          SYNC STATUS   HEALTH STATUS
# blog-system   Synced        Healthy  ✅

# ✅ Git = Cluster (SSOT 달성)
```

### 배포 이력 추적

```bash
cd ~/k8s-manifests
git log --oneline blog-system/web-rollout.yaml | head -3

# f87d821 chore: Update WEB image to v14  ← 자동 커밋 ✅
# f668c89 feat: Enable Istio mesh routing
# 90f0b1b test: Canary deployment v10 → v11

# Author 확인
git log --pretty=format:"%h %an %ad %s" --date=short blog-system/web-rollout.yaml | head -1
# f87d821 github-actions[bot] 2026-01-20 chore: Update WEB image to v14
#         ↑ 자동화된 Bot ✅
```

---

## 5. 1-Click 롤백

### Git Revert로 자동 롤백

```bash
# 1. 최근 배포 확인
cd ~/k8s-manifests
git log --oneline blog-system/web-rollout.yaml | head -3
# f87d821 chore: Update WEB image to v14  ← 현재 (문제 발생)
# f668c89 feat: Enable Istio mesh routing  ← v13 (정상)

# 2. Git Revert (v13으로 롤백)
git revert f87d821 --no-edit
git push

# 3. ArgoCD 자동 배포 (30초 이내)
# Git: v13
# ArgoCD: Detect change → kubectl apply
# Cluster: v13로 롤백 ✅

# 4. 검증
kubectl get rollout web -n blog-system -o jsonpath='{.spec.template.spec.containers[0].image}'
# ghcr.io/wlals2/blog-web:v13 ✅
```

**소요 시간**: ~1분
- Git Revert: 10초
- ArgoCD Sync: 30초
- Pod Rollout: 20초

---

## 6. 트레이드오프 분석

### 배포 속도 vs GitOps 원칙

| 방식 | 배포 속도 | GitOps | 롤백 | 감사 | 추천 |
|------|-----------|--------|------|------|------|
| **kubectl 직접** | 1분 30초 | ❌ | 수동 | ❌ | ❌ |
| **GitOps** | 2분 | ✅ | 자동 | ✅ | ✅ |

**트레이드오프 결론**:
- 배포 속도 차이: +30초
- 얻는 것: GitOps 원칙, 자동 롤백, 감사 추적, 재현성
- **판단**: 30초 느려지는 것은 큰 문제 아님 → GitOps 선택

### ArgoCD 동작 방식

**Polling 주기**: 3초
```
ArgoCD (3초마다)
  ↓
Git Poll: k8s-manifests/blog-system/
  ↓
Diff 계산: Git vs Cluster
  ↓ (변경 감지 시)
kubectl apply
  ↓
Cluster 업데이트 ✅
```

---

## 7. 성과 측정

### Before vs After

| 항목 | Before | After | 개선율 |
|------|--------|-------|--------|
| **SSOT 달성** | 0% | 100% | +100% |
| **배포 이력** | 없음 | Git Log | +100% |
| **롤백 시간** | 5분 (수동) | 1분 (자동) | -80% |
| **감사 추적** | 불가 | Git Log | +100% |
| **GitOps 준수** | 0% | 100% | +100% |
| **ArgoCD 상태** | OutOfSync | Synced | +100% |

### 정성적 개선

**Before**:
- ❌ Git Manifest는 참고용 (의미 없음)
- ❌ kubectl로 수동 배포
- ❌ 배포 이력 추적 불가
- ❌ 롤백 시 수동 작업

**After**:
- ✅ Git = Single Source of Truth
- ✅ ArgoCD 자동 배포
- ✅ 모든 배포 이력 Git에 기록
- ✅ git revert로 1-Click 롤백

---

## 8. 트러블슈팅

### 문제 1: Git Push 실패

**증상**:
```
error: failed to push some refs to 'https://github.com/wlals2/k8s-manifests.git'
fatal: Authentication failed
```

**원인**: GITOPS_TOKEN 권한 부족

**해결**:
```bash
# GitHub PAT 재생성
# Scope: ✅ repo (Full control)

# Repository Secrets 업데이트
# Settings → Secrets → GITOPS_TOKEN 수정
```

### 문제 2: ArgoCD OutOfSync (Rollouts 레이블)

**증상**:
```bash
kubectl get application blog-system -n argocd
# NAME          SYNC STATUS   HEALTH STATUS
# blog-system   OutOfSync     Healthy
```

**원인**: Argo Rollouts 동적 레이블

**해결**: ignoreDifferences 설정 (위 참조)

---

## 9. 감사 추적 (Audit Trail)

### 배포 이력 분석

```bash
# 특정 기간 배포 이력
cd ~/k8s-manifests
git log --since="2026-01-01" --until="2026-01-31" \
  --pretty=format:"%h %ad %s" --date=short \
  blog-system/web-rollout.yaml

# 출력:
# f87d821 2026-01-20 chore: Update WEB image to v14
# f668c89 2026-01-20 feat: Enable Istio mesh routing
# 90f0b1b 2026-01-19 test: Canary deployment

# 누가 배포했는지
git log --pretty=format:"%h %an %s" blog-system/web-rollout.yaml | grep "github-actions"
# f87d821 github-actions[bot] chore: Update WEB image to v14

# 변경 내역 상세
git show f87d821
# -        image: ghcr.io/wlals2/blog-web:v13
# +        image: ghcr.io/wlals2/blog-web:v14
```

**효과**:
- ✅ 보안 감사 (Security Audit)
- ✅ 규정 준수 (Compliance)
- ✅ 사고 조사 (Incident Investigation)

---

**작성일**: 2026-01-20
**태그**: gitops, argocd, github-actions, kubernetes
**관련 문서**:
- [Canary 배포 전략 비교](/study/2026-01-21-canary-deployment-web-was-comparison/)
- [Istio Service Mesh 아키텍처](/study/2026-01-22-istio-service-mesh-architecture/)
