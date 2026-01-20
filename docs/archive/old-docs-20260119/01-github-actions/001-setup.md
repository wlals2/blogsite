# GitHub Actions CI/CD 구성 가이드

> 작성일: 2026-01-18
> Jenkins와 병행하여 GitHub Actions 사용

---

## 🎯 목표

Jenkins 대신 (또는 병행하여) GitHub Actions로 자동 배포

**장점:**
- ✅ Git Push 즉시 빌드 (0초 지연)
- ✅ GitHub에서 빌드 로그 확인
- ✅ 클라우드 CI/CD 경험
- ✅ 무료 (Public 저장소)
- ✅ 설정 파일이 Git에 포함 (버전 관리)

---

## 📁 파일 구조

```
blogsite/
├─ .github/
│  └─ workflows/
│     └─ deploy-web.yml  ✅ 생성 완료
├─ docs/
│  └─ GITHUB-ACTIONS-SETUP.md  (이 파일)
└─ ...
```

---

## 1단계: GitHub Secrets 설정

### 1.1 GHCR Token 생성

**GitHub 접속:**
1. https://github.com/settings/tokens
2. **Generate new token** (classic)
3. Token name: `ghcr-actions`
4. Scopes:
   - ✅ `write:packages`
   - ✅ `read:packages`
5. **Generate token** 클릭
6. **Token 복사** (한 번만 표시!)

```
예: ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

### 1.2 kubeconfig 준비

**로컬에서 kubeconfig Base64 인코딩:**

```bash
# kubeconfig Base64 인코딩
cat ~/.kube/config | base64 -w 0 > /tmp/kubeconfig-base64.txt

# 결과 확인
cat /tmp/kubeconfig-base64.txt
```

**⚠️ 보안 주의:**
- kubeconfig에는 클러스터 Admin 권한 포함
- Private 저장소 권장
- 또는 Service Account Token 사용 (더 안전)

### 1.3 GitHub Repository Secrets 설정

**WEB용 (blogsite):**

1. https://github.com/wlals2/blogsite/settings/secrets/actions
2. **New repository secret** 클릭
3. 다음 Secrets 추가:

| Name | Value | 설명 |
|------|-------|------|
| `GHCR_TOKEN` | ghp_xxxxx | 1.1에서 생성한 Token |
| `KUBECONFIG_BASE64` | [kubeconfig-base64.txt 내용] | 1.2에서 생성한 Base64 |

---

## 2단계: Workflow 파일 확인

**파일 위치:**
```
/home/jimin/blogsite/.github/workflows/deploy-web.yml
```

**주요 단계:**
1. Checkout 코드 (submodules 포함)
2. Hugo 설치 및 빌드 (v0.146.0)
3. Docker Buildx 설정 (캐시 활용)
4. GHCR 로그인
5. Docker 이미지 빌드/푸시
6. kubeconfig 설정
7. Kubernetes 배포 (kubectl set image)
8. Health Check
9. 빌드 정보 출력

**이미지 태그:**
- `ghcr.io/wlals2/blog-web:${{ github.run_number }}` (고유)
- `ghcr.io/wlals2/blog-web:latest`

**예:** GitHub Actions Run #15 → 이미지 `v15`

---

## 3단계: Git Commit 및 Push

```bash
cd /home/jimin/blogsite

# Workflow 파일 확인
ls -la .github/workflows/

# Git 추가 및 커밋
git add .github/workflows/deploy-web.yml
git add docs/GITHUB-ACTIONS-SETUP.md
git commit -m "feat: Add GitHub Actions CI/CD for WEB"

# Push (즉시 빌드 시작!)
git push origin main
```

---

## 4단계: GitHub Actions 모니터링

### 4.1 빌드 상태 확인

**GitHub 저장소 접속:**
- https://github.com/wlals2/blogsite/actions

**확인 사항:**
1. ✅ Workflow 자동 실행 확인
2. 📊 각 Step 로그 확인
3. ⏱️ 빌드 시간 확인 (예상: 3-5분)
4. ✅ Deploy to Kubernetes 성공 확인

### 4.2 실패 시 트러블슈팅

**가능한 에러:**

#### 에러 1: kubeconfig 에러
```
Error: Unable to connect to the server
```

**원인:**
- Secrets에 KUBECONFIG_BASE64 누락
- Base64 인코딩 오류

**해결:**
```bash
# Base64 재생성 (-w 0 옵션 필수!)
cat ~/.kube/config | base64 -w 0 > /tmp/kubeconfig-base64.txt

# GitHub Secrets에 재설정
```

#### 에러 2: GHCR 로그인 실패
```
Error: failed to authorize: authentication required
```

**원인:**
- Secrets에 GHCR_TOKEN 누락
- Token 권한 부족

**해결:**
- GitHub Settings → Tokens → 권한 확인 (`write:packages`)

#### 에러 3: kubectl 명령 실패
```
Error: deployment "web" not found
```

**원인:**
- Namespace 또는 Deployment 이름 오류

**해결:**
```bash
# 로컬에서 확인
kubectl get deployment -n blog-system
kubectl get pods -n blog-system
```

---

## 5단계: Jenkins vs GitHub Actions 선택

### 현재 설정 (병렬 운영)

```
Git Push (main)
    │
    ├─ GitHub Actions (자동) ✅ 신규
    │   └─ Run #15, #16, #17...
    │
    └─ Jenkins (수동/Poll) ✅ 유지
        └─ Build #12, #13, #14...
```

**선택 방법:**

#### 옵션 1: GitHub Actions만 사용 (자동)
```bash
# Jenkins Job 비활성화
Jenkins → blog-web-pipeline → 구성 → 비활성화 체크
```

#### 옵션 2: Jenkins만 사용 (수동 제어)
```yaml
# .github/workflows/deploy-web.yml 수정
on:
  workflow_dispatch:  # 수동만
  # push:  # 자동 비활성화
```

#### 옵션 3: 둘 다 사용 (상황별 선택)
- GitHub Actions: 일반 배포 (자동)
- Jenkins: 긴급 배포 (수동 제어)

---

## 📊 GitHub Actions vs Jenkins 비교

| 항목 | GitHub Actions | Jenkins |
|------|----------------|---------|
| **트리거** | Git Push 즉시 | 수동 또는 Poll SCM |
| **빌드 번호** | Run Number (#15) | Build Number (#12) |
| **로그 위치** | GitHub Actions 탭 | Jenkins UI |
| **설정 위치** | Git (.github/workflows) | Jenkins 서버 |
| **비용** | 무료 (Public) | 로컬 서버 (무료) |
| **캐시** | GitHub Cache (Actions) | Docker Layer Cache |
| **빌드 시간** | ~3-5분 (WEB) | ~2분 (WEB) |
| **외부 접근** | 불필요 | 불필요 (Poll SCM) |

**빌드 시간 차이 이유:**
- GitHub Actions: 클라우드 Runner 초기화 시간 포함
- Jenkins: 로컬 환경, Docker Layer Cache 활용

---

## 🔒 보안 고려사항

### 1. kubeconfig 보호 (현재)

**현재 방식:**
- ⚠️ kubeconfig 전체를 Secret에 저장
- ⚠️ 클러스터 Admin 권한 포함

**개선 방식 (권장):**
- ✅ Service Account Token 사용
- ✅ 최소 권한 (blog-system namespace만)

**Service Account 생성 (나중에):**
```bash
# Service Account 생성
kubectl create sa github-actions -n blog-system

# Role 생성 (최소 권한)
kubectl create role deployer \
  --verb=get,list,patch,update \
  --resource=deployments,pods \
  -n blog-system

# RoleBinding 생성
kubectl create rolebinding github-actions-deployer \
  --role=deployer \
  --serviceaccount=blog-system:github-actions \
  -n blog-system

# Token 추출
kubectl create token github-actions -n blog-system --duration=87600h
```

### 2. GHCR Token 최소 권한

**현재 권장 Scope:**
- ✅ `write:packages` (푸시)
- ✅ `read:packages` (풀)
- ❌ `delete:packages` (불필요)

---

## ✅ 테스트 및 검증

### 1. Git Push 테스트

```bash
cd /home/jimin/blogsite

# 테스트 커밋
echo "# GitHub Actions Test" >> README.md
git add .
git commit -m "test: GitHub Actions deployment"
git push origin main
```

### 2. GitHub Actions 확인

1. https://github.com/wlals2/blogsite/actions
2. 최신 Workflow 실행 확인
3. 모든 Step 성공 확인 (✅)
4. Build Summary 확인

### 3. Kubernetes 배포 확인

```bash
# 이미지 버전 확인
kubectl get deployment web -n blog-system -o jsonpath='{.spec.template.spec.containers[0].image}'
# 결과: ghcr.io/wlals2/blog-web:[Run Number]

# Pod 상태 확인
kubectl get pods -n blog-system -l app=web
```

### 4. 사이트 접속

```
https://blog.jiminhome.shop/
https://blog.jiminhome.shop/board
```

---

## 📈 GitHub Actions Badge 추가 (선택)

**README.md에 Badge 추가:**

```markdown
# Jimin's Blog

![Deploy WEB](https://github.com/wlals2/blogsite/actions/workflows/deploy-web.yml/badge.svg)

...
```

**결과:**
- ✅ 초록색: 최근 빌드 성공
- ❌ 빨간색: 최근 빌드 실패

---

## 🔄 고급 기능 (나중에)

### 1. 조건부 배포 (특정 경로 변경 시만)

```yaml
on:
  push:
    branches: [ main ]
    paths:
      - 'content/**'  # content 변경 시만 배포
      - 'themes/**'
      - 'static/**'
      - '!**.md'  # Markdown 제외
```

### 2. 멀티 환경 배포 (dev/prod 분리)

```yaml
jobs:
  deploy-dev:
    if: github.ref == 'refs/heads/develop'
    env:
      NAMESPACE: blog-dev

  deploy-prod:
    if: github.ref == 'refs/heads/main'
    env:
      NAMESPACE: blog-system
```

### 3. Slack/Discord 알림

```yaml
- name: Notify Slack
  if: always()
  uses: 8398a7/action-slack@v3
  with:
    status: ${{ job.status }}
    webhook_url: ${{ secrets.SLACK_WEBHOOK }}
```

---

## 🎉 완료 체크리스트

### 구성 완료
- [x] `.github/workflows/deploy-web.yml` 생성
- [ ] GitHub Secrets 설정 (GHCR_TOKEN, KUBECONFIG_BASE64)
- [ ] Git Push 및 테스트
- [ ] GitHub Actions 빌드 성공 확인
- [ ] Kubernetes 배포 확인
- [ ] 사이트 접속 확인

### 선택 사항
- [ ] Jenkins 비활성화 (GitHub Actions만 사용)
- [ ] Service Account Token 사용 (보안 강화)
- [ ] Badge 추가 (README.md)
- [ ] Slack/Discord 알림 설정

---

## 🔗 관련 문서

- [GitHub Actions 공식 문서](https://docs.github.com/actions)
- [Hugo Action](https://github.com/peaceiris/actions-hugo)
- [Docker Buildx Action](https://github.com/docker/build-push-action)
- [GHCR 가이드](https://docs.github.com/packages/working-with-a-github-packages-registry/working-with-the-container-registry)

---

## 📝 다음 단계

1. **WAS (Spring Boot) GitHub Actions 추가**
   - board-was 저장소에 동일하게 설정

2. **GitHub Actions 모니터링**
   - 빌드 성공률 확인
   - 평균 빌드 시간 측정

3. **Jenkins vs GitHub Actions 선택**
   - 상황별로 어느 쪽이 더 효율적인지 판단
   - 하나로 통일 또는 병행 운영

---

## ❓ FAQ

### Q1: GitHub Actions와 Jenkins 중 어떤 것을 사용해야 하나요?

**A:** 둘 다 장단점이 있습니다.

- **GitHub Actions 추천:**
  - Git Push 즉시 자동 빌드
  - GitHub에서 로그 확인 편리
  - 클라우드 CI/CD 경험

- **Jenkins 추천:**
  - 수동 제어 필요 (긴급 배포)
  - 빌드 시간 단축 (로컬 캐시)
  - 복잡한 파이프라인

**결론:** 둘 다 사용하며 상황별로 선택하는 것을 추천

### Q2: GitHub Actions 빌드 시간이 더 오래 걸리는 이유는?

**A:** GitHub Actions는 클라우드 Runner를 사용하므로:
- Runner 초기화 시간 (30초~1분)
- 네트워크 속도 (이미지 푸시/풀)
- Docker Layer Cache 최적화 필요

Jenkins는 로컬 환경으로 더 빠릅니다.

### Q3: kubeconfig를 Secret에 저장하는 것이 안전한가요?

**A:**
- **현재 방식:** 편리하지만 보안 낮음 (Admin 권한)
- **권장 방식:** Service Account Token (최소 권한)
- **대안:** Self-hosted Runner 사용 (kubeconfig 불필요)

개인 프로젝트에서는 현재 방식도 충분하지만, 팀 프로젝트에서는 Service Account 권장

---

> 작성: 2026-01-18
> 최종 수정: 2026-01-18
