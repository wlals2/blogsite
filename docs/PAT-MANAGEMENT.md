# PAT (Personal Access Token) 관리 가이드

> blogsite 프로젝트의 모든 Token 및 Secrets 관리

---

## 📋 사용 중인 GitHub Secrets (3개)

### 1. GHCR_TOKEN ⭐ (PAT)
**유형:** Personal Access Token (재발급 가능)
**용도:** GitHub Container Registry (ghcr.io) 접근
**사용 위치:**
- `.github/workflows/deploy-web.yml` (Line 40)
- `.github/workflows/deploy-was.yml` (Line 40)

**필요 권한:**
- ✅ `write:packages` - Docker 이미지 푸시
- ✅ `read:packages` - Docker 이미지 풀

**만료:** 90일 (설정에 따라 다름)

---

### 2. CLOUDFLARE_ZONE_ID
**유형:** Cloudflare Zone ID (고정값)
**용도:** Cloudflare 캐시 퍼지 대상 Zone 식별
**사용 위치:**
- `.github/workflows/deploy-web.yml` (Line 86)
- `.github/workflows/deploy-was.yml` (Line 86)

**확인 방법:**
1. https://dash.cloudflare.com 로그인
2. 도메인 선택 (blog.jiminhome.shop)
3. Overview → Zone ID 복사

**만료:** 없음 (영구)

---

### 3. CLOUDFLARE_API_TOKEN
**유형:** Cloudflare API Token (재발급 가능)
**용도:** Cloudflare API 인증 (캐시 삭제)
**사용 위치:**
- `.github/workflows/deploy-web.yml` (Line 87)
- `.github/workflows/deploy-was.yml` (Line 87)

**필요 권한:**
- Zone → Cache Purge → Edit

**확인 방법:**
1. https://dash.cloudflare.com/profile/api-tokens
2. "Blog Cache Purge" Token 확인

**만료:** 설정에 따라 다름

---

## 🔧 PAT 재발급 시나리오

### 시나리오 1: GHCR_TOKEN 만료 (90일)

**증상:**
- GitHub Actions에서 "unauthorized" 에러
- Docker push 실패

**해결:**
1. 새 PAT 생성
2. GitHub Secrets 업데이트 (1곳만)
3. 워크플로우 자동 반영

**상세 절차:**
```bash
# 1. 새 Token 생성
# https://github.com/settings/tokens
# Scopes: write:packages, read:packages

# 2. GitHub Secrets 업데이트
# https://github.com/wlals2/blogsite/settings/secrets/actions
# GHCR_TOKEN → Update secret → 새 Token 붙여넣기

# 3. 로컬 테스트
echo "새_토큰" | docker login ghcr.io -u wlals2 --password-stdin

# 4. 워크플로우 테스트 (간단한 커밋)
git commit --allow-empty -m "test: Verify GHCR_TOKEN"
git push origin main
```

---

### 시나리오 2: CLOUDFLARE_API_TOKEN 재발급

**증상:**
- Cloudflare 캐시 퍼지 실패
- 워크플로우는 성공하지만 캐시 안 지워짐

**해결:**
1. Cloudflare에서 새 Token 생성
2. GitHub Secrets 업데이트

**상세 절차:**
```bash
# 1. 새 Token 생성
# https://dash.cloudflare.com/profile/api-tokens
# Template: Edit zone DNS
# Zone Resources: Include → Specific zone → blog.jiminhome.shop
# Permissions: Zone → Cache Purge → Edit

# 2. GitHub Secrets 업데이트
# https://github.com/wlals2/blogsite/settings/secrets/actions
# CLOUDFLARE_API_TOKEN → Update secret → 새 Token 붙여넣기

# 3. 테스트
curl -X POST "https://api.cloudflare.com/client/v4/zones/$ZONE_ID/purge_cache" \
  -H "Authorization: Bearer $NEW_TOKEN" \
  -H "Content-Type: application/json" \
  --data '{"purge_everything":true}'
```

---

## 📝 변경 필요한 곳 체크리스트

### GHCR_TOKEN 재발급 시
- [x] GitHub Secrets: `GHCR_TOKEN` 업데이트
- [ ] 워크플로우 파일 변경 (불필요 - 자동 반영)
- [ ] 로컬 `.env` 파일 (없음 - 사용 안 함)

### CLOUDFLARE_API_TOKEN 재발급 시
- [x] GitHub Secrets: `CLOUDFLARE_API_TOKEN` 업데이트
- [ ] 워크플로우 파일 변경 (불필요 - 자동 반영)

### CLOUDFLARE_ZONE_ID 변경 시 (거의 없음)
- [x] GitHub Secrets: `CLOUDFLARE_ZONE_ID` 업데이트

---

## ⚠️ 보안 주의사항

### Token 보관
- ❌ 코드에 직접 작성 금지
- ❌ `.env` 파일에 커밋 금지
- ✅ GitHub Secrets에만 저장
- ✅ 1Password, Bitwarden 등 보안 저장소 사용

### 권한 최소화
| Token | 최소 권한 | 과도한 권한 (금지) |
|-------|-----------|-------------------|
| GHCR_TOKEN | `write:packages`, `read:packages` | `repo`, `admin:org` |
| CLOUDFLARE_API_TOKEN | `Zone.Cache Purge.Edit` | `Zone.*.Edit` (전체 권한) |

### 만료 관리
- ✅ 90일 만료 설정 권장 (No expiration 금지)
- ✅ 만료 7일 전 GitHub 이메일 알림 확인
- ✅ 만료 전 미리 재발급 (만료일 2주 전)

---

## 🔍 Token 상태 확인

### GitHub PAT 확인
```bash
# 현재 활성 Token 목록
# https://github.com/settings/tokens

# 만료일 확인
# Token 목록에서 "Expires on" 컬럼 확인
```

### Cloudflare Token 확인
```bash
# Token 목록
# https://dash.cloudflare.com/profile/api-tokens

# 테스트
curl -X GET "https://api.cloudflare.com/client/v4/user/tokens/verify" \
  -H "Authorization: Bearer $CLOUDFLARE_API_TOKEN"
```

### GitHub Secrets 확인
```bash
# Repository Secrets 목록
# https://github.com/wlals2/blogsite/settings/secrets/actions

# 확인 항목:
# - GHCR_TOKEN (Updated X days ago)
# - CLOUDFLARE_ZONE_ID (Updated X days ago)
# - CLOUDFLARE_API_TOKEN (Updated X days ago)
```

---

## 📚 관련 문서

- **Secrets 설정 가이드**: `docs/archive/old-docs-20260119/01-github-actions/002-secrets-setup.md`
- **CI/CD 파이프라인**: `docs/CICD-PIPELINE.md`
- **워크플로우 파일**:
  - `.github/workflows/deploy-web.yml`
  - `.github/workflows/deploy-was.yml`

---

## 🎯 Quick Reference

### GHCR_TOKEN 재발급 (5분)
1. https://github.com/settings/tokens → Generate new token
2. Scopes: `write:packages`, `read:packages`
3. https://github.com/wlals2/blogsite/settings/secrets/actions
4. GHCR_TOKEN → Update secret
5. Test: `git commit --allow-empty -m "test" && git push`

### Cloudflare Token 재발급 (3분)
1. https://dash.cloudflare.com/profile/api-tokens → Create Token
2. Template: Edit zone DNS → Zone: blog.jiminhome.shop
3. https://github.com/wlals2/blogsite/settings/secrets/actions
4. CLOUDFLARE_API_TOKEN → Update secret

---

**작성일**: 2026-01-20
**최종 업데이트**: 2026-01-20
**상태**: ✅ 모든 Secrets 활성
