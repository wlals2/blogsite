# Blogsite 프로젝트 규칙

> Hugo 정적 블로그 (PaperMod 테마)

---

## 1. 작업 완료 후 요약 (필수!)

### 작업 완료 시점 판단

다음 상황에서 "작업 완료"로 간주:
- ✅ Git 커밋 완료 (`git commit`, `git push`)
- ✅ 파일 생성/수정 완료 (Write, Edit 도구 사용 후)
- ✅ 배포 트리거 완료 (GitHub Actions 시작됨)
- ✅ 조사/분석 완료 (결과 정리 후)

**애매한 경우**: 사용자에게 확인
```
작업이 완료된 것 같습니다. 지금까지 정리가 필요하신가요?
```

### 요약 형식 (간결하게!)

```
## ✅ 완료

### 요청 작업
- [사용자가 요청한 작업] ✅

### 발견 & 해결 (있다면)
- **[문제 제목]**
  - Before: [문제 상황]
  - After: [해결 결과]
  - 파일: [수정된 파일]

### 확인
```bash
# 확인 명령어
# 예상 결과: ...
```
```

### 예시

```
## ✅ 완료

### 요청 작업
- 홈페이지 CSS 박스 추가 ✅

### 발견 & 해결
- **Cloudflare 캐시 문제 발견**
  - Before: 두 워크플로우 실행 → 캐시 안 지워짐
  - After: deploy.yml 삭제 → 캐시 삭제 보장
  - 파일: `.github/workflows/deploy.yml` 삭제

### 확인
```bash
curl -I https://blog.jiminhome.shop/ | grep "cf-cache-status"
# 예상: DYNAMIC (캐시 없음)
```
```

---

## 2. 배포 프로세스

### GitHub Actions
- **워크플로우**: `.github/workflows/deploy-improved.yml`
- **트리거**: main 브랜치 push
- **자동 실행**:
  1. Hugo 빌드 (`hugo --minify`)
  2. `/var/www/blog` 배포
  3. **Cloudflare 캐시 삭제** (purge_everything)
  4. 배포 검증

### 배포 확인 방법
```bash
# 1. 워크플로우 로그 확인
tail -f ~/actions-runner/_diag/Worker_*.log | grep -i "cloudflare"

# 2. 캐시 상태 확인
curl -I https://blog.jiminhome.shop/ | grep "cf-cache-status"

# 3. 실제 콘텐츠 확인
curl -s https://blog.jiminhome.shop/ | grep -o 'class="profile-section"'
```

---

## 3. 주요 파일

| 파일 | 역할 | 수정 시 주의사항 |
|------|------|-----------------|
| `config.toml` | 사이트 설정, 프로필 | HTML 사용 가능 |
| `static/css/custom.css` | 커스텀 스타일 | 다크모드 고려 |
| `layouts/partials/extend_head.html` | CSS/JS 로드 | Mermaid 지원 포함 |
| `content/projects/**/*.md` | 프로젝트 글 | front matter 필수 |

---

## 4. CSS 스타일 클래스

| 클래스 | 용도 |
|--------|------|
| `.profile-section` | 섹션 박스 (호버 효과) |
| `.highlight-box` | 메트릭 하이라이트 |
| `.tech-grid` | 3열 기술 스택 그리드 |
| `.project-card` | 프로젝트 카드 |
| `.interest-box` | 관심사 박스 |
| `.goal-box` | 목표 박스 (점선) |
| `.metric-badge` | 숫자 배지 (67%, 99.9%) |

---

## 5. 금지 사항

- ❌ `deploy.yml` 재생성 금지 (deploy-improved.yml만 사용)
- ❌ config.toml에 Markdown만 사용 (HTML + CSS 클래스 사용)
- ❌ custom.css 없이 인라인 스타일 사용
- ❌ 테스트 없이 바로 main 브랜치 push

---

## 6. 문제 해결

### Cloudflare 캐시 안 지워질 때
```bash
# GitHub Secrets 확인 필요
CLOUDFLARE_ZONE_ID
CLOUDFLARE_API_TOKEN
```

### CSS 스타일 안 보일 때
```bash
# 1. custom.css 로드 확인
curl -I https://blog.jiminhome.shop/css/custom.css

# 2. extend_head.html 확인
cat layouts/partials/extend_head.html | grep custom.css
```

### 배포 안 될 때
```bash
# 워크플로우 상태 확인
ls -lt ~/actions-runner/_diag/*.log | head -1
```
