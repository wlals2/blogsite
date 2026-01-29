# 블로그 소스 코드 완전 가이드

> **목적**: 모든 파일이 어디에 있고, 무엇을 하는지 명확히 파악
> **최종 업데이트**: 2026-01-29

---

## 📋 목차

1. [전체 디렉토리 구조](#전체-디렉토리-구조)
2. [핵심 파일 설명](#핵심-파일-설명)
3. [콘텐츠 위치](#콘텐츠-위치)
4. [수정 가이드](#수정-가이드)
5. [자주 묻는 질문](#자주-묻는-질문)

---

## 전체 디렉토리 구조

```
/home/jimin/blogsite/
├── config.toml                    # ⚙️ 사이트 전체 설정 (가장 중요!)
├── content/                       # 📝 블로그 콘텐츠 (Markdown)
│   ├── posts/                     # 일반 블로그 포스트
│   ├── projects/                  # 프로젝트 소개
│   └── study/                     # 기술 학습 포스트 (96개)
├── static/                        # 🖼️ 정적 파일 (이미지, CSS, JS)
│   ├── css/
│   │   └── custom.css             # 커스텀 스타일
│   ├── js/
│   │   └── animations.js          # 애니메이션 스크립트
│   └── images/                    # 이미지 파일
│       ├── local-k8s-architecture.png
│       └── istio-config.png
├── layouts/                       # 🎨 Hugo 템플릿 (HTML 구조)
│   ├── partials/
│   │   └── extend_head.html       # HTML <head> 커스터마이징
│   └── study/
│       └── list.html              # Study 페이지 템플릿 (카테고리 필터)
├── themes/                        # 🎭 테마 (PaperMod)
│   └── PaperMod/                  # Hugo 테마 디렉토리
├── docs/                          # 📚 프로젝트 문서
│   ├── README.md
│   ├── 02-INFRASTRUCTURE.md
│   ├── 03-TROUBLESHOOTING.md
│   ├── istio/
│   ├── cilium/
│   ├── CICD/
│   ├── monitoring/
│   └── blog-design/
├── scripts/                       # 🛠️ 유틸리티 스크립트
│   ├── suggest-category.py        # 카테고리 자동 제안
│   ├── update-categories.py       # 카테고리 일괄 업데이트
│   └── new-post.sh                # 새 포스트 생성
├── .blog-categories.yaml          # 📂 10개 고정 카테고리 정의
├── .github/workflows/             # 🚀 CI/CD 파이프라인
│   ├── deploy-web.yml             # WEB 배포 워크플로우
│   └── deploy-was.yml             # WAS 배포 워크플로우
├── blog-k8s-project/              # 💼 WAS 소스코드 (Spring Boot)
│   └── was/
└── CLAUDE.md                      # 🤖 Claude 작업 규칙
```

---

## 핵심 파일 설명

### 1. config.toml (사이트 설정)

**위치**: `/home/jimin/blogsite/config.toml`

**역할**: Hugo 사이트의 모든 설정을 관리

**주요 섹션**:
```toml
[params]
  # 사이트 기본 정보

[params.profileMode]
  # 홈페이지 프로필 섹션
  # ⚠️ 문제: 여기에 하드코딩된 데이터가 많음
  #    예: "62일 운영", "115개 Pod"

[[menu.main]]
  # 상단 메뉴 (About, Projects, Study, Docs, Tags)
```

**수정 시 영향**:
- 홈페이지 전체
- 메뉴 구조
- 사이트 메타데이터

---

### 2. content/ (콘텐츠 디렉토리)

**위치**: `/home/jimin/blogsite/content/`

**구조**:
```
content/
├── posts/           # 일반 블로그 포스트 (사용 안 함)
├── projects/        # 프로젝트 소개 페이지
│   ├── aws-eks/
│   ├── homelab-k8s/
│   └── ...
└── study/           # 기술 학습 포스트 (주력)
    ├── 2026-01-25-local-k8s-architecture/
    │   └── index.md
    ├── 2026-01-26-istio-service-mesh/
    │   └── index.md
    └── ... (96개 포스트)
```

**파일 형식** (Front Matter):
```yaml
---
title: "포스트 제목"
date: 2026-01-29
categories: ["study", "Kubernetes"]
tags: ["kubernetes", "k8s", "helm"]
---

# 본문 시작
```

**카테고리 규칙**:
- 10개 고정 카테고리만 사용 (`.blog-categories.yaml`)
- 신규 포스트 작성 시 `scripts/suggest-category.py` 사용 필수

---

### 3. static/ (정적 파일)

**위치**: `/home/jimin/blogsite/static/`

**중요 파일**:

| 파일 | 역할 | 수정 시 영향 |
|------|------|-------------|
| `css/custom.css` | 커스텀 스타일 | 전체 사이트 디자인 |
| `js/animations.js` | 애니메이션 스크립트 | 숫자 카운터, 스킬바 |
| `images/` | 이미지 저장소 | 아키텍처 다이어그램 등 |

**이미지 추가 방법**:
```bash
# 1. 이미지 복사
cp image.png /home/jimin/blogsite/static/images/

# 2. Markdown에서 사용
![설명](/images/image.png)

# 3. HTML에서 사용
<img src="/images/image.png" alt="설명">
```

---

### 4. layouts/ (템플릿)

**위치**: `/home/jimin/blogsite/layouts/`

**주요 파일**:

| 파일 | 역할 | 언제 수정하나? |
|------|------|---------------|
| `partials/extend_head.html` | HTML <head> 커스터마이징 | CSS/JS 추가 시 |
| `study/list.html` | Study 페이지 템플릿 | 카테고리 필터 수정 시 |

**템플릿 우선순위**:
```
1. layouts/study/list.html       (커스텀, 우선)
2. themes/PaperMod/layouts/...   (테마 기본, 후순위)
```

---

### 5. scripts/ (유틸리티)

**위치**: `/home/jimin/blogsite/scripts/`

**스크립트 목록**:

| 스크립트 | 용도 | 사용 시점 |
|---------|------|----------|
| `suggest-category.py` | 카테고리 자동 제안 | 새 포스트 작성 전 |
| `update-categories.py` | 기존 포스트 일괄 분류 | 카테고리 재정리 |
| `new-post.sh` | 새 포스트 템플릿 생성 | 포스트 작성 시작 |

**예시**:
```bash
# 새 포스트 카테고리 제안
python3 scripts/suggest-category.py \
  "Kubernetes HPA 완벽 가이드" \
  "kubernetes,hpa,autoscaling"

# 출력: Kubernetes (점수: 8)
```

---

### 6. .github/workflows/ (CI/CD)

**위치**: `/home/jimin/blogsite/.github/workflows/`

**워크플로우**:

| 파일 | 트리거 | 동작 |
|------|--------|------|
| `deploy-web.yml` | main 브랜치 push | Hugo 빌드 → Docker 이미지 → ArgoCD |
| `deploy-was.yml` | main 브랜치 push | Maven 빌드 → Docker 이미지 → ArgoCD |

**배포 플로우**:
```
git push
    ↓
GitHub Actions 시작
    ↓
Hugo 빌드 (hugo --minify)
    ↓
Docker 이미지 빌드 & Push (GHCR)
    ↓
k8s-manifests 저장소 업데이트 (GitOps)
    ↓
ArgoCD Auto-Sync (3초 이내)
    ↓
Cloudflare 캐시 삭제
    ↓
배포 완료! (총 35초)
```

---

## 콘텐츠 위치

### 블로그 글은 어디에?

**Study 포스트**: `/home/jimin/blogsite/content/study/`
```bash
# 전체 포스트 목록
ls -lt content/study/

# 특정 포스트 찾기
find content/study/ -name "*kubernetes*"

# 카테고리별 포스트 개수
grep -r "categories.*Kubernetes" content/study/ | wc -l
```

**Projects 포스트**: `/home/jimin/blogsite/content/projects/`

---

### 이미지는 어디에?

**아키텍처 다이어그램**: `/home/jimin/blogsite/static/images/`
```bash
ls -lh static/images/

# 주요 이미지
local-k8s-architecture.png    # Homelab 아키텍처
istio-config.png               # Istio 설정
cilium-architecture.png        # Cilium 아키텍처
```

**포스트 내 이미지**: `/home/jimin/blogsite/content/study/포스트명/`
```
content/study/2026-01-25-local-k8s-architecture/
├── index.md
├── diagram1.png
└── diagram2.png
```

---

### 설정은 어디에?

| 설정 항목 | 파일 위치 |
|----------|---------|
| **사이트 전체 설정** | `config.toml` |
| **카테고리 정의** | `.blog-categories.yaml` |
| **커스텀 CSS** | `static/css/custom.css` |
| **HTML <head>** | `layouts/partials/extend_head.html` |
| **CI/CD 파이프라인** | `.github/workflows/*.yml` |

---

## 수정 가이드

### 홈페이지 프로필 수정

**파일**: `config.toml`

**위치**: Line 51-200 (`[params.profileMode]`)

**수정 예시**:
```toml
# Before
무중단 운영 <span data-count='62' data-suffix='일'>0일</span>

# After
무중단 운영 <span data-count='63' data-suffix='일'>0일</span>
```

**주의**: 하드코딩이라 매번 수동 수정 필요 ⚠️

---

### Study 페이지 카테고리 필터 수정

**파일**: `layouts/study/list.html`

**수정 가능한 부분**:
1. 카테고리 버튼 스타일
2. 필터링 JavaScript 로직
3. 페이지네이션 숨김/표시

**예시**:
```html
<!-- Line 142: JavaScript 필터링 로직 -->
<script>
document.addEventListener('DOMContentLoaded', function() {
  // 여기서 필터 로직 수정
});
</script>
```

---

### 커스텀 CSS 수정

**파일**: `static/css/custom.css`

**주요 섹션**:
```css
/* Line 1-200: 프로필 스타일 */
.profile-section { ... }
.highlight-box { ... }

/* Line 845-942: 카테고리 필터 */
.category-filter-box { ... }
.category-btn { ... }
```

**수정 후 반영**:
```bash
git add static/css/custom.css
git commit -m "style: CSS 수정"
git push
# → 35초 후 자동 배포
```

---

### 새 포스트 작성

**Step 1: 카테고리 제안**
```bash
python3 scripts/suggest-category.py \
  "포스트 제목" \
  "tag1,tag2,tag3"
```

**Step 2: Markdown 파일 생성**
```bash
mkdir -p content/study/2026-01-29-new-post
cat > content/study/2026-01-29-new-post/index.md <<'EOF'
---
title: "새 포스트 제목"
date: 2026-01-29
categories: ["study", "Kubernetes"]
tags: ["kubernetes", "k8s"]
---

# 내용 시작
EOF
```

**Step 3: 커밋 & 배포**
```bash
git add content/study/2026-01-29-new-post/
git commit -m "post: 새 포스트 추가"
git push
```

---

## 자주 묻는 질문

### Q1. 홈페이지 숫자가 왜 안 바뀌나요?

**A**: `config.toml`에 하드코딩되어 있습니다.

**해결 방법 (2가지)**:

**방법 1: 수동 업데이트**
```bash
vi config.toml
# Line 62: data-count='62' → data-count='63'
git commit -am "update: 운영 일수 63일로 수정"
git push
```

**방법 2: 자동 업데이트 (권장)**
→ 다음 섹션 참조 (동적 데이터)

---

### Q2. 카테고리를 새로 만들 수 있나요?

**A**: ❌ 불가능합니다.

10개 고정 카테고리만 사용 (`.blog-categories.yaml`)

**이유**: 카테고리가 매번 달라지면 블로그 탐색이 어려워짐

**카테고리 목록**:
1. Kubernetes
2. Service Mesh
3. Networking
4. Security
5. Storage
6. Observability
7. Cloud & Terraform
8. Elasticsearch
9. Troubleshooting
10. Development

---

### Q3. 이미지를 어떻게 추가하나요?

**A**: `static/images/` 디렉토리에 복사

```bash
# 1. 이미지 복사
cp ~/Downloads/diagram.png static/images/

# 2. Markdown에서 사용
![아키텍처](/images/diagram.png)

# 3. 커밋
git add static/images/diagram.png
git commit -m "asset: 아키텍처 다이어그램 추가"
git push
```

---

### Q4. CSS가 반영 안 되는데요?

**A**: Cloudflare 캐시 문제

**해결**:
```bash
# 브라우저 하드 새로고침
Windows/Linux: Ctrl + Shift + R
Mac: Cmd + Shift + R

# 또는 빈 커밋으로 캐시 삭제 트리거
git commit --allow-empty -m "chore: Purge cache"
git push
```

---

### Q5. 배포가 안 되는데요?

**A**: GitHub Actions 로그 확인

```bash
# 최근 워크플로우 로그
tail -100 /home/jimin/actions-runner/_diag/Worker_*.log

# 또는 GitHub에서 확인
# https://github.com/wlals2/blogsite/actions
```

---

## 다음 단계: 동적 데이터 구현

**문제**: 모든 지표가 하드코딩되어 자동 업데이트 안 됨

**해결 방안 3가지**:

### 1. Hugo Data Files (추천)

`/data/homelab.yaml`:
```yaml
cluster:
  uptime_days: 62
  total_pods: 115
  nodes: 4
```

`config.toml`:
```toml
무중단 운영 {{ site.Data.homelab.cluster.uptime_days }}일
```

**장점**: Hugo 네이티브, 빌드 시 반영
**단점**: 여전히 수동 업데이트 필요

---

### 2. JavaScript 동적 로딩 (권장)

`/static/data/metrics.json`:
```json
{
  "uptime_days": 62,
  "total_pods": 115,
  "deployment_time": 35
}
```

`/static/js/metrics.js`:
```javascript
fetch('/data/metrics.json')
  .then(res => res.json())
  .then(data => {
    document.querySelector('[data-metric="uptime"]')
      .textContent = data.uptime_days + '일';
  });
```

**장점**: 실시간 업데이트 가능
**단점**: SEO 불리 (초기 HTML에 없음)

---

### 3. GitHub Actions 자동 업데이트 (최고)

`.github/workflows/update-metrics.yml`:
```yaml
name: Update Metrics
on:
  schedule:
    - cron: '0 0 * * *'  # 매일 자동 실행

jobs:
  update:
    runs-on: ubuntu-latest
    steps:
      - name: Calculate uptime
        run: |
          START_DATE="2024-11-27"
          DAYS=$(((`date +%s` - `date -d "$START_DATE" +%s`) / 86400))

          # config.toml 자동 업데이트
          sed -i "s/data-count='[0-9]*' data-suffix='일'/data-count='$DAYS' data-suffix='일'/" config.toml

      - name: Commit
        run: |
          git config user.name "github-actions[bot]"
          git commit -am "chore: Update metrics to $DAYS days"
          git push
```

**장점**: 완전 자동화
**단점**: 구현 복잡도 높음

---

**어떤 방법을 선택하시겠습니까?**

**작성일**: 2026-01-29
**작성자**: Jimin & Claude Sonnet 4.5
**다음 업데이트**: 동적 데이터 구현 후
