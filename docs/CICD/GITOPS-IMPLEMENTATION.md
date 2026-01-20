# GitOps 구현 완료

> GitHub Actions → Git Manifest 업데이트 → ArgoCD 자동 배포

**구현일**: 2026-01-20
**상태**: ✅ GitOps 완성 (SSOT 달성)

---

## 변경 사항

### Before (AS-IS): kubectl 직접 배포

```yaml
# .github/workflows/deploy-web.yml
- name: Deploy to Kubernetes
  run: |
    kubectl argo rollouts set image web \
      nginx=ghcr.io/wlals2/blog-web:v12
```

**문제:**
- Git Manifest: v11
- Cluster: v12
- **Git ≠ Cluster** ❌
- ArgoCD OutOfSync

---

### After (TO-BE): Git Manifest 업데이트

```yaml
# .github/workflows/deploy-web.yml
- name: Update Kubernetes Manifest (GitOps)
  run: |
    # 1. k8s-manifests repo clone
    git clone https://github.com/wlals2/k8s-manifests.git

    # 2. yq로 이미지 태그 업데이트
    yq eval ".spec.template.spec.containers[0].image = \"...v12\"" \
      -i web-rollout.yaml

    # 3. Git Commit & Push
    git commit -m "chore: Update WEB to v12"
    git push
```

**결과:**
- Git Manifest: v12 ✅
- ArgoCD: Git 감지 → 자동 배포
- Cluster: v12 ✅
- **Git = Cluster** ✅
- ArgoCD Synced

---

## 데이터 흐름

### 전체 CI/CD 파이프라인

```
Developer
    ↓ git push (blogsite/main)

GitHub Actions (CI)
    ├─ 1. Hugo Build
    ├─ 2. Docker Build
    ├─ 3. Push to ghcr.io/wlals2/blog-web:v12 ✅
    └─ 4. Update Git Manifest ✅
        │
        ├─ git clone k8s-manifests
        ├─ yq eval ".containers[0].image = v12"
        ├─ git commit -m "Update WEB to v12"
        └─ git push
            ↓

Git Manifest (SSOT) ⭐
    k8s-manifests/blog-system/web-rollout.yaml: v12 ✅
        ↓ (ArgoCD Poll: 3초 간격)

ArgoCD (CD)
    ├─ Git Poll
    ├─ Diff: Git(v12) vs Cluster(v11)
    └─ kubectl apply (자동) ✅
        ↓

Kubernetes Cluster
    └─ WEB Pod: v12 ✅

Cloudflare CDN
    └─ Cache Purged ✅

최종 상태:
- Git: v12
- Cluster: v12
- Git = Single Source of Truth ✅
```

---

## SSOT (Single Source of Truth) 달성

### 정의

```
Git Manifest = 유일한 진실의 원천
```

### 검증

```bash
# 1. Git Manifest 확인
cat k8s-manifests/blog-system/web-rollout.yaml | grep image
# image: ghcr.io/wlals2/blog-web:v12

# 2. Cluster 확인
kubectl get rollout web -n blog-system -o jsonpath='{.spec.template.spec.containers[0].image}'
# ghcr.io/wlals2/blog-web:v12

# 3. ArgoCD 상태 확인
kubectl get application blog-system -n argocd
# NAME          SYNC STATUS   HEALTH STATUS
# blog-system   Synced        Healthy  ✅
```

**결론: Git = Cluster ✅**

---

## 배포 이력 추적

### Git Log로 배포 이력 확인

```bash
cd k8s-manifests
git log --oneline blog-system/web-rollout.yaml

# 예상 출력:
# abc1234 chore: Update WEB image to v12  (2026-01-20 20:45)
# def5678 chore: Update WEB image to v11  (2026-01-20 19:30)
# ghi9012 chore: Update WEB image to v10  (2026-01-20 18:15)
```

**효과:**
- ✅ 누가, 언제, 어떤 버전을 배포했는지 추적
- ✅ Git Blame으로 변경자 확인
- ✅ Git Diff로 변경 내역 비교

---

## 롤백 (1-Click)

### Git Revert로 자동 롤백

```bash
# 1. 최근 배포 확인
git log --oneline blog-system/web-rollout.yaml | head -3
# abc1234 chore: Update WEB image to v12  ← 현재 (문제 발생)
# def5678 chore: Update WEB image to v11  ← 이전 정상 버전
# ghi9012 chore: Update WEB image to v10

# 2. Git Revert (v11로 롤백)
git revert abc1234
git push

# 3. ArgoCD 자동 배포 (30초 이내)
# Git: v11
# ArgoCD: Detect change → kubectl apply
# Cluster: v11로 롤백 완료 ✅

# 4. 검증
kubectl get rollout web -n blog-system -o jsonpath='{.spec.template.spec.containers[0].image}'
# ghcr.io/wlals2/blog-web:v11 ✅
```

**소요 시간:** 1분 (Git Revert 10초 + ArgoCD Sync 30초 + Pod Rollout 20초)

---

## 감사 추적 (Audit Trail)

### 배포 이력 분석

```bash
# 1. 특정 기간 배포 이력
git log --since="2026-01-01" --until="2026-01-31" \
  --pretty=format:"%h %ad %s" --date=short \
  blog-system/web-rollout.yaml

# 출력:
# abc1234 2026-01-20 chore: Update WEB image to v12
# def5678 2026-01-20 chore: Update WEB image to v11
# ghi9012 2026-01-19 chore: Update WEB image to v10
# jkl3456 2026-01-18 chore: Update WEB image to v9

# 2. 누가 배포했는지 확인
git log --pretty=format:"%h %an %ad %s" --date=short \
  blog-system/web-rollout.yaml

# 출력:
# abc1234 github-actions[bot] 2026-01-20 chore: Update WEB image to v12
# def5678 github-actions[bot] 2026-01-20 chore: Update WEB image to v11

# 3. 특정 버전 변경 내역
git show abc1234

# 출력:
# -        image: ghcr.io/wlals2/blog-web:v11
# +        image: ghcr.io/wlals2/blog-web:v12
```

**효과:**
- ✅ 보안 감사 (Audit) 가능
- ✅ 규정 준수 (Compliance)
- ✅ 사고 조사 (Incident Investigation)

---

## 재현 가능성

### 특정 시점 상태 재현

```bash
# 1. 2026-01-19 상태로 돌아가기
git log --before="2026-01-20" blog-system/ | head -1
# commit ghi9012 (2026-01-19 18:00)

git checkout ghi9012

# 2. 해당 시점의 manifest 확인
cat blog-system/web-rollout.yaml | grep image
# image: ghcr.io/wlals2/blog-web:v10

# 3. 새 클러스터에 재현
kubectl apply -f blog-system/
# v10 배포됨 ✅

# 4. 최신으로 복귀
git checkout main
```

**사용 사례:**
- 🔍 과거 버전 디버깅
- 🧪 특정 시점 테스트
- 🔄 재배포 (Disaster Recovery)

---

## ArgoCD 동작 방식

### Polling 주기

```yaml
# argocd-application.yaml
spec:
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
```

**동작:**
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

**확인:**
```bash
# ArgoCD Application 상태
kubectl get application blog-system -n argocd
# NAME          SYNC STATUS   HEALTH STATUS
# blog-system   Synced        Healthy

# ArgoCD UI 접근 (선택)
kubectl port-forward -n argocd svc/argocd-server 8080:443
# https://localhost:8080
```

---

### ignoreDifferences (Argo Rollouts 호환성)

**문제:**
- Argo Rollouts가 DestinationRule의 subset labels에 `rollouts-pod-template-hash` 동적 추가
- Git manifest에는 이 레이블이 없음
- ArgoCD가 OutOfSync로 인식

**해결:**
```yaml
# ArgoCD Application 설정
spec:
  ignoreDifferences:
  - group: networking.istio.io
    kind: DestinationRule
    name: web-dest-rule
    jsonPointers:
    - /spec/subsets/0/labels  # stable subset labels 무시
    - /spec/subsets/1/labels  # canary subset labels 무시
```

**효과:**
- ArgoCD: Synced ✅
- Argo Rollouts: 계속 동적으로 레이블 관리
- GitOps 원칙: 유지 (Rollouts-managed 필드만 예외)

**설정 적용:**
```bash
kubectl apply -f argocd-application.yaml

# 상태 확인
kubectl get application blog-system -n argocd
# NAME          SYNC STATUS   HEALTH STATUS
# blog-system   Synced        Healthy  ✅
```

---

## 문제 해결

### 1. Git Push 실패

**증상:**
```
error: failed to push some refs to 'https://github.com/wlals2/k8s-manifests.git'
```

**원인:** GitHub Token 권한 부족

**해결:**
```bash
# 1. GitHub Personal Access Token (PAT) 확인
# Settings → Developer settings → Personal access tokens
# Scope: repo (전체) 필요

# 2. GitHub Secrets 확인
# blogsite → Settings → Secrets → Actions
# GHCR_TOKEN에 PAT 저장되어 있어야 함
```

---

### 2. ArgoCD OutOfSync

**증상:**
```bash
kubectl get application blog-system -n argocd
# NAME          SYNC STATUS   HEALTH STATUS
# blog-system   OutOfSync     Healthy
```

**원인 1: 수동 kubectl 실행**

```bash
# 1. Git Manifest와 Cluster 차이 확인
argocd app diff blog-system

# 2. Git 상태로 강제 Sync
argocd app sync blog-system --force

# 또는
kubectl patch application blog-system -n argocd \
  --type merge -p '{"operation":{"sync":{}}}'
```

**원인 2: Argo Rollouts 동적 레이블 (일반적)**

**증상:**
- DestinationRule에만 OutOfSync 표시
- 다른 리소스는 모두 Synced

**확인:**
```bash
# Diff 확인
kubectl get application blog-system -n argocd -o yaml | grep -A 5 "OutOfSync"

# DestinationRule 비교
kubectl get destinationrule web-dest-rule -n blog-system -o yaml
```

**해결: ignoreDifferences 설정**

위의 "[ignoreDifferences (Argo Rollouts 호환성)](#ignoredifferences-argo-rollouts-호환성)" 섹션 참조

---

### 3. yq 명령어 오류

**증상:**
```
yq: command not found
```

**해결:**
```bash
# Self-hosted runner에 yq 설치
sudo wget https://github.com/mikefarah/yq/releases/latest/download/yq_linux_amd64 \
  -O /usr/bin/yq
sudo chmod +x /usr/bin/yq
yq --version
```

---

## 모니터링

### GitHub Actions 로그

```bash
# Actions 로그 확인
# GitHub UI → Actions → 최근 워크플로우 선택

# 예상 출력:
# ✅ Docker 이미지 빌드 완료
# ✅ ghcr.io/wlals2/blog-web:v12 푸시 완료
# ✅ Manifest updated: v12
# ✅ ArgoCD will deploy automatically (within 3min)
```

---

### ArgoCD 상태

```bash
# Application 상태
kubectl get application -n argocd

# 상세 정보
kubectl describe application blog-system -n argocd

# Sync 이력
kubectl get application blog-system -n argocd -o yaml | grep lastSync
```

---

### Pod 상태

```bash
# WEB Pod 이미지 확인
kubectl get rollout web -n blog-system -o jsonpath='{.spec.template.spec.containers[0].image}'

# WAS Pod 이미지 확인
kubectl get deployment was -n blog-system -o jsonpath='{.spec.template.spec.containers[0].image}'

# Pod 상태
kubectl get pods -n blog-system
```

---

## 성과 측정

### Before vs After

| 항목 | Before (kubectl 직접) | After (GitOps) | 개선 |
|------|---------------------|----------------|------|
| **SSOT** | ❌ Git ≠ Cluster | ✅ Git = Cluster | +100% |
| **배포 이력** | ❌ 없음 | ✅ Git Log | +100% |
| **롤백 시간** | 5분 (수동) | 1분 (git revert) | -80% |
| **감사 추적** | ❌ 불가능 | ✅ Git Log | +100% |
| **재현성** | ⚠️ 어려움 | ✅ 완벽 | +100% |
| **ArgoCD 상태** | OutOfSync | Synced | +100% |
| **GitOps 준수** | 0% | 100% | +100% |
| **배포 시간** | 1분 30초 | 2분 | +30초 |

**결론:** 30초 느려지는 대신 모든 영역에서 개선 ✅

---

## 다음 단계

### 추가 개선 사항 (선택)

1. **ArgoCD Image Updater** (자동화 고도화)
   - GitHub Actions가 manifest 업데이트할 필요 없음
   - ArgoCD가 GHCR 감시 → 자동 업데이트
   - 완전 자동화

2. **Slack 알림**
   - 배포 완료 시 Slack 알림
   - 롤백 시 Slack 알림

3. **배포 승인 프로세스**
   - Production 환경에 ArgoCD Sync 수동 승인
   - Slack 버튼으로 승인

---

## 관련 문서

- [CI/CD 파이프라인](./CICD-PIPELINE.md)
- [CI/CD 검증 결과](./CICD-VERIFICATION.md)
- [트러블슈팅](../03-TROUBLESHOOTING.md)
- [모니터링](../monitoring/README.md)

---

## 요약

✅ **GitOps 구현 완료**
- GitHub Actions: CI만 담당 (빌드 + Manifest 업데이트)
- ArgoCD: CD 전담 (Git → Kubernetes 자동 동기화)
- Git = Single Source of Truth

✅ **SSOT 달성**
- Git Manifest: v12
- Kubernetes Cluster: v12
- ArgoCD: Synced

✅ **배포 이력 추적**
- Git Log에 모든 배포 기록
- 누가, 언제, 어떤 버전 배포했는지 추적

✅ **1-Click 롤백**
- `git revert` → 자동 롤백 (1분)

✅ **감사 추적**
- Git Log로 보안 감사 가능

---

**구현일**: 2026-01-20
**상태**: ✅ Production 운영 중
**배포 이력**: https://github.com/wlals2/k8s-manifests/commits/main/blog-system/
