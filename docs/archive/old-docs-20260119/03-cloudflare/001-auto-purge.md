# Cloudflare Cache 자동 Purge 설정 가이드

> GitHub Actions에서 배포 후 Cloudflare CDN 캐시를 자동으로 삭제하는 방법

**문제:** 배포 완료했는데 변경사항이 안 보임 → Cloudflare가 이전 파일 캐시
**해결:** GitHub Actions에서 배포 후 자동으로 캐시 삭제

---

## 📋 목차

1. [왜 필요한가?](#1-왜-필요한가)
2. [Cloudflare API Token 발급](#2-cloudflare-api-token-발급)
3. [Zone ID 확인](#3-zone-id-확인)
4. [GitHub Secrets 등록](#4-github-secrets-등록)
5. [워크플로우 적용](#5-워크플로우-적용)
6. [테스트](#6-테스트)
7. [트러블슈팅](#7-트러블슈팅)

---

## 1. 왜 필요한가?

### 1.1 문제 상황

```
배포 프로세스:
1. git push → GitHub Actions 실행
2. Hugo 빌드 → /var/www/blog/ 복사
3. Nginx 재시작
4. ✅ 서버에는 새 파일 있음

하지만...
사용자 접속:
1. 사용자 → Cloudflare CDN 접속
2. Cloudflare가 오래된 파일 캐시에서 서빙
3. ❌ 사용자는 이전 버전 봄
```

### 1.2 해결책

```
배포 프로세스 (개선):
1. git push → GitHub Actions 실행
2. Hugo 빌드 → /var/www/blog/ 복사
3. Nginx 재시작
4. ✅ Cloudflare Cache Purge API 호출  ← 추가!
5. ✅ 사용자가 새 버전 즉시 확인 가능
```

### 1.3 수동 vs 자동

| 방식 | 작업 | 시간 | 문제점 |
|------|------|------|--------|
| **수동** | 1. git push<br>2. Cloudflare 대시보드 접속<br>3. Purge 버튼 클릭 | 5분 | 깜빡하면 반영 안 됨 |
| **자동** | 1. git push | 10초 | 없음 ✅ |

---

## 2. Cloudflare API Token 발급

### 2.1 Cloudflare 대시보드 접속

1. https://dash.cloudflare.com/ 접속
2. 로그인

### 2.2 API Token 생성

1. 오른쪽 상단 **프로필 아이콘** 클릭
2. **My Profile** 클릭
3. 왼쪽 메뉴에서 **API Tokens** 클릭
4. **Create Token** 버튼 클릭

### 2.3 권한 설정

**Option 1: 템플릿 사용 (추천)**

1. **"Edit zone DNS"** 템플릿 선택
2. "Use template" 클릭
3. 다음 설정 확인:

```
Token name: GitHub Actions Cache Purge

Permissions:
  Zone - Cache Purge - Purge

Zone Resources:
  Include - Specific zone - blog.jiminhome.shop
```

4. "Continue to summary" 클릭
5. "Create Token" 클릭

**Option 2: 커스텀 설정**

```
Permissions:
  Zone - Cache Purge - Purge  ← 이것만 선택

Zone Resources:
  Include - Specific zone - blog.jiminhome.shop

Client IP Address Filtering:
  (비워둠)

TTL:
  Start: (지금)
  End: (무제한)
```

### 2.4 Token 저장

⚠️ **중요:** Token은 **단 한 번만** 표시됩니다!

```
예시:
Token: qL3e8xC9yH2pK4mN6vJ1wR5oG7tA9dF2sX8bZ4nM

이 토큰을 복사해서 안전한 곳에 저장하세요!
```

**보안 주의사항:**
- ❌ GitHub 코드에 직접 저장 금지
- ❌ 공개 장소에 공유 금지
- ✅ GitHub Secrets에만 저장
- ✅ 필요 시 재발급 가능

---

## 3. Zone ID 확인

### 3.1 Cloudflare 대시보드에서 확인

1. https://dash.cloudflare.com/ 접속
2. **blog.jiminhome.shop** 사이트 클릭
3. 오른쪽 사이드바 하단에 **"API"** 섹션 확인

```
Zone ID: 1a2b3c4d5e6f7g8h9i0j  (예시)
```

### 3.2 API로 확인 (선택)

```bash
# Terminal에서 실행
curl -X GET "https://api.cloudflare.com/client/v4/zones" \
  -H "Authorization: Bearer YOUR_API_TOKEN" \
  -H "Content-Type: application/json" | jq -r '.result[] | select(.name=="blog.jiminhome.shop") | .id'
```

---

## 4. GitHub Secrets 등록

### 4.1 GitHub 저장소 설정 접근

1. https://github.com/wlals2/my-hugo-blog 접속
2. 상단 **Settings** 탭 클릭
3. 왼쪽 메뉴에서 **Secrets and variables** → **Actions** 클릭

### 4.2 Secret 추가

**Secret 1: CLOUDFLARE_ZONE_ID**

1. "New repository secret" 클릭
2. Name: `CLOUDFLARE_ZONE_ID`
3. Value: `1a2b3c4d5e6f7g8h9i0j` (위에서 복사한 Zone ID)
4. "Add secret" 클릭

**Secret 2: CLOUDFLARE_API_TOKEN**

1. "New repository secret" 클릭
2. Name: `CLOUDFLARE_API_TOKEN`
3. Value: `qL3e8xC9yH2pK4mN6vJ1wR5oG7tA9dF2sX8bZ4nM` (위에서 복사한 Token)
4. "Add secret" 클릭

### 4.3 확인

Settings → Secrets and variables → Actions에서 다음 2개가 보여야 함:

```
Repository secrets:
  CLOUDFLARE_API_TOKEN     Last updated X minutes ago
  CLOUDFLARE_ZONE_ID       Last updated X minutes ago
```

---

## 5. 워크플로우 적용

### 5.1 기존 워크플로우 백업

```bash
cd ~/blogsite
cp .github/workflows/deploy.yml .github/workflows/deploy.yml.backup
```

### 5.2 개선된 워크플로우로 교체

```bash
# 개선된 버전으로 교체
mv .github/workflows/deploy-improved.yml .github/workflows/deploy.yml
```

### 5.3 변경사항 확인

```bash
git diff .github/workflows/deploy.yml
```

**핵심 추가 부분:**
```yaml
- name: Purge Cloudflare Cache
  if: success()
  env:
    CF_ZONE_ID: ${{ secrets.CLOUDFLARE_ZONE_ID }}
    CF_TOKEN: ${{ secrets.CLOUDFLARE_API_TOKEN }}
  run: |
    curl -X POST \
      "https://api.cloudflare.com/client/v4/zones/$CF_ZONE_ID/purge_cache" \
      -H "Authorization: Bearer $CF_TOKEN" \
      -H "Content-Type: application/json" \
      --data '{"purge_everything":true}'
```

### 5.4 커밋 및 푸시

```bash
cd ~/blogsite
git add .github/workflows/deploy.yml
git commit -m "Add: Cloudflare cache auto-purge after deployment"
git push
```

---

## 6. 테스트

### 6.1 테스트 파일 수정

```bash
cd ~/blogsite

# 간단한 테스트 포스트 생성
cat > content/posts/test-cache-purge.md << 'EOF'
---
title: "Cache Purge 테스트"
date: 2026-01-12
---

이 파일은 Cloudflare 캐시 자동 삭제 테스트용입니다.

현재 시각: $(date)
EOF

git add content/posts/test-cache-purge.md
git commit -m "Test: Cloudflare cache purge"
git push
```

### 6.2 GitHub Actions 확인

1. https://github.com/wlals2/my-hugo-blog/actions 접속
2. 방금 생성된 워크플로우 실행 클릭
3. "Purge Cloudflare Cache" 단계 확인

**성공 로그 예시:**
```
🔥 Cloudflare 캐시 삭제 중...
✅ Cloudflare 캐시 삭제 완료!
```

**실패 로그 예시:**
```
❌ Failed to purge cache (HTTP 403)
{
  "success": false,
  "errors": [
    {
      "code": 9109,
      "message": "Invalid access token"
    }
  ]
}
```

### 6.3 실제 사이트 확인

1. 브라우저에서 https://blog.jiminhome.shop/posts/test-cache-purge/ 접속
2. **Ctrl + Shift + R** (강제 새로고침)
3. 테스트 포스트가 보이면 성공! ✅

### 6.4 Response Header 확인

```bash
curl -I https://blog.jiminhome.shop/posts/test-cache-purge/

# 확인할 헤더:
# cf-cache-status: MISS  ← 캐시 삭제 성공 (Origin에서 새로 가져옴)
# cf-cache-status: HIT   ← 캐시에서 서빙 (삭제 안 됨)
```

---

## 7. 트러블슈팅

### 7.1 API Token 권한 에러

**증상:**
```
❌ Failed to purge cache (HTTP 403)
{
  "success": false,
  "errors": [{"code": 9109, "message": "Invalid access token"}]
}
```

**원인:**
- Token 권한 부족
- Token 만료
- Token 오타

**해결:**
1. Cloudflare → API Tokens에서 Token 확인
2. "Zone - Cache Purge - Purge" 권한 있는지 확인
3. 필요 시 Token 재발급
4. GitHub Secrets에 다시 등록

---

### 7.2 Zone ID 오류

**증상:**
```
❌ Failed to purge cache (HTTP 404)
{
  "success": false,
  "errors": [{"code": 1003, "message": "Zone not found"}]
}
```

**원인:**
- Zone ID 오타
- 다른 사이트의 Zone ID 사용

**해결:**
1. Cloudflare 대시보드에서 Zone ID 재확인
2. GitHub Secrets의 `CLOUDFLARE_ZONE_ID` 업데이트

---

### 7.3 Secrets 설정 안 됨

**증상:**
```
⚠️  Cloudflare secrets not set. Skipping cache purge.
```

**원인:**
- GitHub Secrets에 `CLOUDFLARE_ZONE_ID` 또는 `CLOUDFLARE_API_TOKEN` 없음

**해결:**
1. GitHub → Settings → Secrets and variables → Actions
2. 2개 Secret 등록 확인
3. 오타 확인: `CLOUDFLARE_ZONE_ID` (복수형 X, 언더스코어 O)

---

### 7.4 캐시가 여전히 남아있음

**증상:**
- Purge 성공했는데도 이전 버전 보임

**원인 1: 브라우저 캐시**

**해결:**
```
Ctrl + Shift + R (강제 새로고침)
또는
Ctrl + Shift + Delete → 캐시 삭제
```

**원인 2: DNS 캐시**

**해결:**
```bash
# Windows
ipconfig /flushdns

# Linux
sudo systemd-resolve --flush-caches

# macOS
sudo dscacheutil -flushcache
```

**원인 3: Cloudflare Purge 지연**

**해결:**
- Cloudflare Purge는 **전세계 엣지 서버 전파**에 1-3분 소요
- 3분 대기 후 재확인

---

### 7.5 Partial Purge (선택적 삭제)

**현재 설정:**
```json
{"purge_everything":true}  ← 모든 캐시 삭제
```

**개선안 (특정 파일만 삭제):**
```yaml
- name: Purge Cloudflare Cache (Selective)
  run: |
    # 변경된 파일만 Purge
    CHANGED_FILES=$(git diff --name-only HEAD^ HEAD | grep '^public/')

    FILES_JSON=$(echo "$CHANGED_FILES" | jq -R -s -c 'split("\n")[:-1] | map("https://blog.jiminhome.shop/" + .)')

    curl -X POST \
      "https://api.cloudflare.com/client/v4/zones/$CF_ZONE_ID/purge_cache" \
      -H "Authorization: Bearer $CF_TOKEN" \
      -H "Content-Type: application/json" \
      --data "{\"files\":$FILES_JSON}"
```

**장점:**
- 캐시 히트율 유지 (변경 안 된 파일은 캐시 유지)

**단점:**
- 복잡함
- Hugo 빌드 시 파일 경로 변경 가능 (index.html → 2/index.html)

**결론:** `purge_everything`가 간단하고 확실함 (추천)

---

## 8. 고급 설정 (선택)

### 8.1 Cloudflare Cache 정책 최적화

**Cloudflare 대시보드 설정:**

1. **Caching** → **Configuration**
2. **Browser Cache TTL**: 4 hours (기본값)
3. **Crawler Hints**: On (크롤러 캐시 최적화)

### 8.2 Page Rules 추가

1. **Rules** → **Page Rules**
2. "Create Page Rule" 클릭

**Rule 1: HTML 캐시 짧게**
```
URL: blog.jiminhome.shop/*.html
Settings:
  - Browser Cache TTL: 2 hours
  - Cache Level: Standard
```

**Rule 2: 정적 파일 캐시 길게**
```
URL: blog.jiminhome.shop/css/*
Settings:
  - Browser Cache TTL: 1 day
  - Cache Level: Cache Everything
```

### 8.3 Cache Reserve (Enterprise만)

Cloudflare Enterprise 플랜에서만 사용 가능:
- Origin 다운되어도 캐시에서 서빙
- 추가 비용 발생

---

## 9. 참고 자료

### 9.1 Cloudflare API 문서

- [Cache Purge API](https://developers.cloudflare.com/api/operations/zone-purge)
- [API Tokens](https://developers.cloudflare.com/fundamentals/api/get-started/create-token/)

### 9.2 관련 파일

```
~/blogsite/
├── .github/workflows/
│   ├── deploy.yml           # 개선된 워크플로우 (Purge 포함)
│   └── deploy.yml.backup    # 백업
├── docs/
│   ├── GITHUB-ACTIONS-GUIDE.md          # 이 가이드
│   └── CLOUDFLARE-AUTO-PURGE-SETUP.md   # Purge 설정 가이드
└── scripts/
    └── manual-purge.sh      # 수동 Purge 스크립트 (아래 참조)
```

### 9.3 수동 Purge 스크립트

```bash
#!/bin/bash
# ~/blogsite/scripts/manual-purge.sh
# 수동으로 Cloudflare 캐시 삭제

set -e

# GitHub Secrets 대신 환경 변수 사용
# export CLOUDFLARE_ZONE_ID="..."
# export CLOUDFLARE_API_TOKEN="..."

if [ -z "$CLOUDFLARE_ZONE_ID" ] || [ -z "$CLOUDFLARE_API_TOKEN" ]; then
    echo "❌ 환경 변수 설정 필요:"
    echo "   export CLOUDFLARE_ZONE_ID=<your-zone-id>"
    echo "   export CLOUDFLARE_API_TOKEN=<your-api-token>"
    exit 1
fi

echo "🔥 Cloudflare 캐시 삭제 중..."

curl -X POST \
  "https://api.cloudflare.com/client/v4/zones/$CLOUDFLARE_ZONE_ID/purge_cache" \
  -H "Authorization: Bearer $CLOUDFLARE_API_TOKEN" \
  -H "Content-Type: application/json" \
  --data '{"purge_everything":true}' \
  | jq

echo "✅ 완료"
```

**사용법:**
```bash
export CLOUDFLARE_ZONE_ID="..."
export CLOUDFLARE_API_TOKEN="..."
./scripts/manual-purge.sh
```

---

## 10. 요약

### 10.1 설정 완료 체크리스트

- [ ] Cloudflare API Token 발급
- [ ] Zone ID 확인
- [ ] GitHub Secrets 등록 (`CLOUDFLARE_ZONE_ID`, `CLOUDFLARE_API_TOKEN`)
- [ ] 워크플로우 업데이트 (deploy.yml)
- [ ] 테스트 파일로 배포 테스트
- [ ] GitHub Actions 로그 확인 ("✅ Cloudflare 캐시 삭제 완료!")
- [ ] 실제 사이트 접속 확인

### 10.2 최종 배포 프로세스

```
git push
    ↓
GitHub Actions 트리거
    ↓
Hugo 빌드
    ↓
Nginx 배포
    ↓
✅ Cloudflare Cache Purge  ← 자동 추가!
    ↓
사용자가 즉시 새 버전 확인 가능
```

### 10.3 예상 효과

| 항목 | 이전 | 이후 |
|------|------|------|
| 배포 후 반영 시간 | **수동 Purge 필요** (5분) | **즉시** (10초) |
| 작업 단계 | git push + Purge 클릭 | **git push만** |
| 실수 가능성 | Purge 깜빡함 | **없음** ✅ |

---

**작성자:** Jimin
**마지막 업데이트:** 2026-01-12
**프로젝트:** blog.jiminhome.shop
