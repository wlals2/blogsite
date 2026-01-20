# GitHub Secrets 설정 가이드

> GitHub Actions에서 Kubernetes 배포를 위한 Secrets 설정

---

## 🔐 필요한 Secrets (4개)

### 1. GHCR_TOKEN (GitHub Container Registry 접근)
### 2. KUBECONFIG_BASE64 (Kubernetes 배포 권한)
### 3. CLOUDFLARE_ZONE_ID (Cloudflare 캐시 퍼지)
### 4. CLOUDFLARE_API_TOKEN (Cloudflare 캐시 퍼지)

---

## 1단계: GHCR_TOKEN 생성

### 1.1 GitHub Personal Access Token 생성

**접속:**
https://github.com/settings/tokens

**절차:**
1. **Generate new token** 클릭
2. **Generate new token (classic)** 선택
3. 설정:
   - **Note**: `ghcr-actions-blog`
   - **Expiration**: 90 days (또는 No expiration)
   - **Scopes** 선택:
     - ✅ `write:packages` (이미지 푸시)
     - ✅ `read:packages` (이미지 풀)

4. **Generate token** 클릭
5. **Token 복사** (한 번만 표시됨!)

```
예: ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

---

## 2단계: KUBECONFIG_BASE64 생성 (이미 완료!)

**생성된 파일:**
```
/tmp/kubeconfig-base64.txt
```

**확인:**
```bash
cat /tmp/kubeconfig-base64.txt
```

**⚠️ 보안 주의:**
- 이 값은 Kubernetes 클러스터 Admin 권한을 포함합니다
- Private 저장소 권장
- 또는 나중에 Service Account Token으로 변경 권장

---

## 3단계: GitHub Repository Secrets 추가

### 3.1 GitHub 저장소 접속

**URL:**
https://github.com/wlals2/blogsite/settings/secrets/actions

### 3.2 Secrets 추가

#### Secret 1: GHCR_TOKEN

1. **New repository secret** 클릭
2. 입력:
   - **Name**: `GHCR_TOKEN`
   - **Secret**: [1단계에서 생성한 Token 붙여넣기]
3. **Add secret** 클릭

#### Secret 2: KUBECONFIG_BASE64

1. **New repository secret** 클릭
2. 입력:
   - **Name**: `KUBECONFIG_BASE64`
   - **Secret**: [/tmp/kubeconfig-base64.txt 내용 복사/붙여넣기]

   ```bash
   # 복사 방법
   cat /tmp/kubeconfig-base64.txt
   # 출력 전체 복사
   ```

3. **Add secret** 클릭

---

## 4단계: Cloudflare Secrets 추가

### 4.1 CLOUDFLARE_ZONE_ID 확인

**Cloudflare 대시보드 접속:**
1. https://dash.cloudflare.com/ 로그인
2. 도메인 선택: `jiminhome.shop` 클릭
3. 우측 사이드바 하단 **API** 섹션에서 **Zone ID** 복사
   ```
   예: 1a2b3c4d5e6f7g8h9i0j1k2l3m4n5o6p
   ```

**GitHub Secret 추가:**
1. https://github.com/wlals2/blogsite/settings/secrets/actions
2. **New repository secret** 클릭
3. 입력:
   - **Name**: `CLOUDFLARE_ZONE_ID`
   - **Secret**: [복사한 Zone ID 붙여넣기]
4. **Add secret** 클릭

### 4.2 CLOUDFLARE_API_TOKEN 생성

**Cloudflare API Token 생성:**
1. https://dash.cloudflare.com/profile/api-tokens 접속
2. **Create Token** 클릭
3. **Custom token** 선택 또는 "Edit zone DNS" 템플릿 사용
4. 설정:
   ```
   Token name: github-actions-cache-purge

   Permissions:
   - Zone / Cache Purge / Purge

   Zone Resources:
   - Include / Specific zone / jiminhome.shop
   ```
5. **Continue to summary** → **Create Token**
6. **토큰 복사** (한 번만 표시!)
   ```
   예: abc123def456ghi789jkl012mno345pqr678stu901
   ```

**GitHub Secret 추가:**
1. https://github.com/wlals2/blogsite/settings/secrets/actions
2. **New repository secret** 클릭
3. 입력:
   - **Name**: `CLOUDFLARE_API_TOKEN`
   - **Secret**: [복사한 API Token 붙여넣기]
4. **Add secret** 클릭

---

## 5단계: Secrets 확인

**확인 위치:**
https://github.com/wlals2/blogsite/settings/secrets/actions

**표시되어야 할 Secrets:**
- ✅ `GHCR_TOKEN` (Updated X seconds ago)
- ✅ `KUBECONFIG_BASE64` (Updated X seconds ago)
- ✅ `CLOUDFLARE_ZONE_ID` (Updated X seconds ago)
- ✅ `CLOUDFLARE_API_TOKEN` (Updated X seconds ago)

**⚠️ 주의:**
- Secret 값은 추가 후 다시 볼 수 없음 (보안)
- 수정 필요 시 다시 생성해서 Update

---

## 5단계: 테스트

### 5.1 Git Push

```bash
cd /home/jimin/blogsite

# Workflow 파일 확인
cat .github/workflows/deploy-web.yml

# Git Push (즉시 빌드 시작!)
git add .
git commit -m "feat: Enable GitHub Actions CI/CD"
git push origin main
```

### 5.2 GitHub Actions 확인

**접속:**
https://github.com/wlals2/blogsite/actions

**확인 사항:**
1. ✅ Workflow 자동 실행 시작
2. ✅ 모든 Step 성공 (초록색 체크)
3. ✅ Deploy to Kubernetes 성공
4. ✅ Health Check 통과

**예상 시간:**
- ~3-5분 (Hugo 빌드 + Docker 빌드 + 배포)

---

## 트러블슈팅

### ❌ 에러 1: GHCR 로그인 실패

```
Error: failed to authorize: authentication required
```

**원인:**
- GHCR_TOKEN Secret 누락
- Token 권한 부족

**해결:**
1. GitHub Secrets에 GHCR_TOKEN 확인
2. Token 권한 확인 (`write:packages`)

---

### ❌ 에러 2: kubeconfig 에러

```
Error: Unable to connect to the server
```

**원인:**
- KUBECONFIG_BASE64 Secret 누락
- Base64 인코딩 오류

**해결:**
```bash
# Base64 재생성 (-w 0 옵션 필수!)
cat ~/.kube/config | base64 -w 0 > /tmp/kubeconfig-base64.txt

# GitHub Secrets에서 KUBECONFIG_BASE64 업데이트
```

---

### ❌ 에러 3: kubectl 명령 실패

```
Error: deployment "web" not found
```

**원인:**
- Namespace 또는 Deployment 이름 오류
- Kubernetes 클러스터 접근 권한 없음

**해결:**
```bash
# 로컬에서 확인
kubectl get deployment -n blog-system
kubectl get pods -n blog-system

# deploy-web.yml의 NAMESPACE, DEPLOYMENT_NAME 확인
```

---

## 보안 강화 (선택사항, 나중에)

### Service Account Token 사용

**현재 방식:**
- ⚠️ kubeconfig 전체 (Admin 권한)

**개선 방식:**
- ✅ Service Account Token (최소 권한)

**생성:**
```bash
# Service Account 생성
kubectl create sa github-actions -n blog-system

# Role 생성 (blog-system namespace만)
kubectl create role deployer \
  --verb=get,list,patch,update \
  --resource=deployments,pods \
  -n blog-system

# RoleBinding 생성
kubectl create rolebinding github-actions-deployer \
  --role=deployer \
  --serviceaccount=blog-system:github-actions \
  -n blog-system

# Token 생성 (10년)
kubectl create token github-actions -n blog-system --duration=87600h
```

**장점:**
- 최소 권한 (blog-system namespace만)
- Admin 권한 노출 방지

**단점:**
- 설정 복잡 (약 10분)

**권장:**
- 개인 프로젝트: kubeconfig (현재 방식)
- 팀 프로젝트: Service Account Token

---

## ✅ 완료 체크리스트

- [ ] GHCR_TOKEN 생성 및 추가
- [ ] KUBECONFIG_BASE64 추가 (/tmp/kubeconfig-base64.txt 사용)
- [ ] CLOUDFLARE_ZONE_ID 추가
- [ ] CLOUDFLARE_API_TOKEN 생성 및 추가
- [ ] GitHub Secrets 확인 (4개 모두)
- [ ] Git Push 테스트
- [ ] GitHub Actions 빌드 성공 확인
- [ ] Kubernetes 배포 확인
- [ ] Cloudflare 캐시 퍼지 확인
- [ ] 사이트 접속 확인 (https://blog.jiminhome.shop/)

---

> 작성: 2026-01-18
> GitHub-hosted runner 사용 (ubuntu-latest)
