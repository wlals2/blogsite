---
title: "Hugo 블로그 완벽 가이드: 구조부터 배포까지"
date: 2025-11-16T22:00:00+09:00
draft: false
categories: ["Hugo", "DevOps", "Blog"]
tags: ["Hugo", "GitHub Actions", "Nginx", "자동화", "CI/CD", "정적 사이트"]
series: ["Hugo 블로그 구축"]
description: "Hugo 블로그의 전체 구조와 작동 원리를 처음부터 끝까지 상세히 알아봅니다. 설정 파일 분석, Git 워크플로우, GitHub Actions 자동 배포까지!"
author: "늦찌민"
---

## 🎯 이 글을 읽으면

처음 Hugo를 시작했을 때 정말 막막했어요. "이 설정이 뭘 의미하는 거지?", "왜 안 되는 거지?" 같은 질문들로 가득했죠. 이 글은 그때의 저처럼 고민하는 분들을 위해 작성했습니다.

- Hugo 블로그의 전체 구조를 이해할 수 있습니다
- config.toml의 모든 설정이 무엇을 의미하는지 알 수 있습니다
- 글 작성부터 배포까지의 전체 흐름을 파악할 수 있습니다
- 문제가 생겼을 때 어디를 확인해야 하는지 알 수 있습니다

---

## 📂 1. 프로젝트 디렉토리 구조

Hugo 블로그의 기본 구조는 다음과 같습니다. 처음엔 파일이 너무 많아서 어디서부터 봐야 할지 몰랐어요.

```bash
my-hugo-blog/                   # 프로젝트 루트
├── config.toml                 # Hugo 핵심 설정 파일
├── content/                    # 마크다운 글 저장소
│   ├── posts/                  # 블로그 포스트
│   ├── study/                  # 스터디 노트 (선택)
│   └── archives/               # 아카이브 (선택)
├── themes/                     # 테마 디렉토리
│   └── PaperMod/              # 사용 중인 테마 (Git submodule)
├── layouts/                    # 커스텀 레이아웃 오버라이드
│   ├── partials/              # 부분 템플릿
│   └── shortcodes/            # 커스텀 숏코드
├── static/                     # 정적 파일 (이미지, CSS, JS 등)
│   ├── images/
│   └── favicon.ico
├── public/                     # 빌드 결과물 (Git 무시 필요)
├── resources/                  # Hugo가 자동 생성하는 캐시
├── .github/                    # GitHub 관련 설정
│   └── workflows/
│       └── deploy.yml         # GitHub Actions 배포 설정
├── archetypes/                 # 새 글 템플릿
│   └── default.md
├── data/                       # 데이터 파일 (JSON, YAML 등)
├── .gitignore                  # Git 무시 파일 목록
├── .gitmodules                 # Git submodule 설정
└── README.md                   # 프로젝트 설명
```

### 주요 디렉토리 역할

| 디렉토리 | 역할 | Git 추적 |
|----------|------|----------|
| `content/` | 마크다운 글 저장 | ✅ |
| `themes/` | 테마 (보통 submodule) | ✅ |
| `static/` | 이미지, 파비콘 등 정적 파일 | ✅ |
| `layouts/` | 테마 커스터마이징 | ✅ |
| `public/` | 빌드 결과물 (HTML, CSS) | ❌ |
| `resources/` | Hugo 캐시 | ❌ |

처음엔 `public/`을 Git에 올려야 하나 고민했는데, 절대 올리면 안 돼요! 빌드할 때마다 자동으로 생성되니까요.

---

## ⚙️ 2. config.toml 완벽 분석

`config.toml`은 Hugo 블로그의 심장입니다. 모든 설정이 여기서 시작됩니다.

처음엔 "이게 다 뭐지?"라고 당황했는데, 하나씩 알아가니까 재미있더라구요.

### 2.1 기본 설정

```toml
baseURL = "https://yourdomain.com/"
title = "나의 기술 블로그"
theme = "PaperMod"
languageCode = "ko-kr"
defaultContentLanguage = "ko"
timeZone = "Asia/Seoul"
enableRobotsTXT = true
googleAnalytics = ""  # GA 측정 ID (예: G-XXXXXXXXXX)
enableGitInfo = true
buildFuture = true
```

**설명:**
- `baseURL`: 블로그의 실제 도메인 (절대 경로 생성에 사용)
- `theme`: 사용할 테마 이름 (`themes/` 디렉토리 내 폴더명)
- `timeZone`: 날짜/시간 표시 기준 (한국: `Asia/Seoul`)
- `enableRobotsTXT`: `/robots.txt` 자동 생성 (SEO)
- `enableGitInfo`: Git 커밋 정보를 날짜로 사용 가능
- `buildFuture`: `true`면 미래 날짜 글도 빌드 (예약 발행 시 유용)

처음엔 `buildFuture`를 몰라서 미래 날짜로 글을 쓰면 안 보여서 당황했어요.

### 2.2 날짜 우선순위 설정 (중요!)

```toml
[frontmatter]
  date = ["date", "publishDate", ":git", ":fileModTime", "lastmod"]
```

**Hugo는 날짜를 결정할 때 이 순서대로 확인합니다:**

1. `date` - frontmatter의 `date` 필드 (최우선!)
2. `publishDate` - frontmatter의 `publishDate` 필드
3. `:git` - Git 마지막 커밋 날짜 (`enableGitInfo: true` 필요)
4. `:fileModTime` - 파일 시스템 수정 시간
5. `lastmod` - frontmatter의 `lastmod` 필드

> **💡 팁:** `date` 필드를 최우선으로 두면, 작성자가 명시한 날짜가 정확히 반영됩니다. Git 리베이스나 파일 복사 시에도 날짜가 바뀌지 않습니다.

한 번 Git 리베이스를 했는데 모든 글의 날짜가 바뀌어서 놀란 적이 있어요. 그때 이 설정의 중요성을 깨달았죠.

### 2.3 페이지네이션

```toml
[pagination]
  pagerSize = 10  # 한 페이지당 표시할 글 개수
```

### 2.4 최소화 및 출력 형식

```toml
[minify]
  minifyOutput = true  # HTML/CSS/JS 압축 (성능 향상)

[outputs]
  home = ["HTML", "RSS", "JSON"]  # 홈페이지 출력 형식
```

**출력 형식:**
- `HTML`: 웹 페이지
- `RSS`: RSS 피드 (`/index.xml`)
- `JSON`: JSON 피드 (검색 기능에 활용 가능)

### 2.5 마크다운 렌더링 설정

```toml
[markup]
  [markup.goldmark]
    [markup.goldmark.renderer]
      unsafe = true  # HTML 태그 허용 (기본값: false)
```

> **⚠️ 주의:** `unsafe = true`로 설정하면 마크다운 내 HTML 태그를 사용할 수 있지만, XSS 공격에 취약할 수 있습니다. 신뢰할 수 있는 콘텐츠만 작성하세요.

처음엔 이 설정을 몰라서 HTML이 안 먹혀서 한참 고생했어요.

### 2.6 사이트 파라미터

```toml
[params]
  env = "production"
  defaultTheme = "auto"          # auto, light, dark
  showReadingTime = true
  showBreadCrumbs = true
  showCodeCopyButtons = true
  showPostNavLinks = true
  showWordCount = true
  mainSections = ["posts", "study"]  # 메인 페이지에 표시할 섹션
```

### 2.7 프로필 모드 (PaperMod 테마)

```toml
[params.profileMode]
  enabled = true
  title = "Hi there 👋"
  subtitle = "Welcome to my blog"
  imageUrl = "/images/profile.jpg"
  imageTitle = "Profile Image"
  imageWidth = 120
  imageHeight = 120

  [[params.profileMode.buttons]]
    name = "Posts"
    url = "/posts/"
  [[params.profileMode.buttons]]
    name = "Tags"
    url = "/tags/"
  [[params.profileMode.buttons]]
    name = "Archives"
    url = "/archives/"
```

프로필 모드는 블로그를 개인 홈페이지처럼 만들어줘서 정말 마음에 들어요.

### 2.8 소셜 아이콘

```toml
[[params.socialIcons]]
  name = "github"
  url = "https://github.com/yourusername"

[[params.socialIcons]]
  name = "twitter"
  url = "https://twitter.com/yourusername"

[[params.socialIcons]]
  name = "linkedin"
  url = "https://linkedin.com/in/yourusername"
```

### 2.9 Taxonomies (분류 체계)

```toml
[taxonomies]
  tag = "tags"
  category = "categories"
  series = "series"
```

**사용 예시:**
```yaml
---
tags: ["Hugo", "DevOps"]
categories: ["Tutorial"]
series: ["Hugo 블로그 구축"]
---
```

### 2.10 메뉴 구성

```toml
[[menu.main]]
  identifier = "posts"
  name = "Posts"
  url = "/posts/"
  weight = 1

[[menu.main]]
  identifier = "tags"
  name = "Tags"
  url = "/tags/"
  weight = 2

[[menu.main]]
  identifier = "archives"
  name = "Archives"
  url = "/archives/"
  weight = 3

[[menu.main]]
  identifier = "search"
  name = "Search"
  url = "/search/"
  weight = 4
```

**weight**: 작을수록 먼저 표시됩니다.

---

## 📝 3. 글 작성 완벽 가이드

### 3.1 새 글 생성

**방법 1: Hugo 명령어 (권장)**
```bash
hugo new posts/my-first-post.md
# content/posts/my-first-post.md 생성됨
```

처음엔 직접 파일을 만들었는데, `hugo new`를 쓰니까 front matter가 자동으로 생성돼서 편하더라구요.

**방법 2: 수동 생성**
```bash
touch content/posts/my-first-post.md
```

### 3.2 Frontmatter 작성

```yaml
---
title: "Hugo 블로그 시작하기"
date: 2025-11-16T14:00:00+09:00
lastmod: 2025-11-16T15:30:00+09:00
draft: false
author: "작성자명"
description: "Hugo로 블로그를 시작하는 완벽 가이드"
tags: ["Hugo", "블로그", "정적사이트"]
categories: ["Tutorial"]
series: ["Hugo 시리즈"]
weight: 1  # 시리즈 내 순서
cover:
  image: "/images/hugo-cover.jpg"
  alt: "Hugo 로고"
  caption: "Hugo - The world's fastest framework"
ShowToc: true
TocOpen: true
---
```

**주요 필드 설명:**

| 필드 | 필수 | 설명 |
|------|------|------|
| `title` | ✅ | 글 제목 |
| `date` | ✅ | 발행 날짜 |
| `draft` | ❌ | `true`면 비공개 (기본값: `false`) |
| `description` | ❌ | SEO용 설명 (메타 태그) |
| `tags` | ❌ | 태그 목록 |
| `categories` | ❌ | 카테고리 |
| `series` | ❌ | 시리즈 (연재물) |
| `cover.image` | ❌ | 커버 이미지 경로 |
| `ShowToc` | ❌ | 목차 표시 여부 |

### 3.3 Draft(초안) 관리

```bash
# draft 포함 미리보기
hugo server -D

# draft 포함 빌드
hugo -D

# production 빌드 (draft 제외)
hugo --minify
```

처음엔 `draft: true`를 빼먹고 왜 안 보이지?라고 한참 고민했어요.

### 3.4 번들 페이지 (Page Bundle)

**Leaf Bundle (권장):**
```

content/posts/my-post/
├── index.md          # 메인 글
├── image1.jpg        # 이미지 1
└── image2.png        # 이미지 2

```

**사용 예시:**
```markdown
![이미지](image1.jpg)  # 상대 경로 사용 가능!
```

이미지를 글과 같은 폴더에 두니까 관리가 정말 편해졌어요.

---

## 🔄 4. Git 워크플로우

### 4.1 초기 설정

```bash
# 프로젝트 초기화
git init

# .gitignore 설정
cat > .gitignore << 'EOF'
# Hugo
/public/
/resources/
/.hugo_build.lock

# OS
.DS_Store
Thumbs.db

# Editor
.vscode/
.idea/
*.swp
EOF

# 테마 추가 (submodule)
git submodule add https://github.com/adityatelange/hugo-PaperMod.git themes/PaperMod
git submodule update --init --recursive
```

### 4.2 일상적인 워크플로우

```bash
# 1. 새 글 작성
hugo new posts/my-new-post.md
vim content/posts/my-new-post.md

# 2. 로컬 미리보기
hugo server -D
# http://localhost:1313 접속

# 3. Git 커밋
git add content/posts/my-new-post.md
git commit -m "Add: 새 글 작성 - Hugo 블로그 시작하기"

# 4. GitHub에 푸시
git push origin main
# → GitHub Actions 자동 배포 시작!
```

이제는 이 워크플로우가 자연스럽게 손에 익었어요.

### 4.3 테마 업데이트

```bash
# submodule 업데이트
git submodule update --remote --merge

# 커밋
git add themes/PaperMod
git commit -m "Update: PaperMod 테마 최신 버전으로 업데이트"
git push origin main
```

---

## 🤖 5. GitHub Actions 자동 배포

### 5.1 워크플로우 파일 생성

`.github/workflows/deploy.yml`:

```yaml
name: Deploy Hugo Blog

on:
  push:
    branches: [ "main" ]
    paths:
      - "content/**"      # content 변경 시
      - "static/**"       # static 변경 시
      - "themes/**"       # theme 변경 시
      - "layouts/**"      # layout 변경 시
      - "config.*"        # config 변경 시
  workflow_dispatch:      # 수동 실행 가능

concurrency:
  group: hugo-deploy-production
  cancel-in-progress: true

jobs:
  deploy:
    runs-on: ubuntu-latest  # 또는 self-hosted
    timeout-minutes: 15

    steps:
      - name: Checkout
        uses: actions/checkout@v4
        with:
          submodules: true    # 테마 submodule 포함
          fetch-depth: 0      # Git 히스토리 전체 (enableGitInfo 사용 시)

      - name: Setup Hugo
        uses: peaceiris/actions-hugo@v3
        with:
          hugo-version: 'latest'
          extended: true      # Hugo Extended (SCSS 지원)

      - name: Build
        env:
          HUGO_ENV: production
        run: |
          hugo --minify
          echo "빌드 완료: $(find public -name '*.html' | wc -l)개 페이지"

      - name: Deploy to GitHub Pages
        uses: peaceiris/actions-gh-pages@v3
        with:
          github_token: ${{ secrets.GITHUB_TOKEN }}
          publish_dir: ./public
          cname: yourdomain.com  # 커스텀 도메인
```

GitHub Actions를 처음 설정했을 때 정말 신기했어요. Git push만 하면 자동으로 배포되다니!

### 5.2 Self-Hosted Runner 설정 (자체 서버)

**Runner 설치:**
```bash
# 1. GitHub에서 Runner 토큰 발급
# Settings → Actions → Runners → New self-hosted runner

# 2. Runner 설치
mkdir actions-runner && cd actions-runner
curl -o actions-runner-linux-x64-2.311.0.tar.gz -L \
  https://github.com/actions/runner/releases/download/v2.311.0/actions-runner-linux-x64-2.311.0.tar.gz
tar xzf ./actions-runner-linux-x64-2.311.0.tar.gz

# 3. Runner 설정
./config.sh --url https://github.com/yourusername/your-repo --token YOUR_TOKEN

# 4. 서비스로 등록
sudo ./svc.sh install
sudo ./svc.sh start
```

**Self-Hosted용 deploy.yml:**

```yaml
jobs:
  deploy:
    runs-on: [self-hosted, linux, x64]

    steps:
      # ... (이전 단계 동일)

      - name: Deploy to Nginx
        run: |
          sudo rsync -avh --delete public/ /var/www/blog/
          sudo chown -R www-data:www-data /var/www/blog
          sudo chmod -R 755 /var/www/blog
          sudo systemctl reload nginx
```

---

## 🌐 6. 서버 배포 및 Nginx 설정

### 6.1 Nginx 설정

`/etc/nginx/sites-available/blog.conf`:

```nginx
server {
    listen 80;
    listen [::]:80;
    server_name yourdomain.com www.yourdomain.com;

    # HTTPS로 리다이렉트
    return 301 https://$server_name$request_uri;
}

server {
    listen 443 ssl http2;
    listen [::]:443 ssl http2;
    server_name yourdomain.com www.yourdomain.com;

    # SSL 인증서 (Let's Encrypt)
    ssl_certificate /etc/letsencrypt/live/yourdomain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/yourdomain.com/privkey.pem;

    # SSL 설정
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers HIGH:!aNULL:!MD5;
    ssl_prefer_server_ciphers on;

    # 웹 루트
    root /var/www/blog;
    index index.html;

    # Gzip 압축
    gzip on;
    gzip_vary on;
    gzip_min_length 1000;
    gzip_types text/plain text/css text/xml text/javascript
               application/x-javascript application/xml+rss
               application/json image/svg+xml;

    # 캐싱 설정
    location ~* \.(jpg|jpeg|png|gif|ico|css|js|woff|woff2)$ {
        expires 1y;
        add_header Cache-Control "public, immutable";
    }

    # HTML 파일
    location / {
        try_files $uri $uri/ =404;
        add_header Cache-Control "no-cache, must-revalidate";
    }

    # 404 페이지
    error_page 404 /404.html;
    location = /404.html {
        internal;
    }
}
```

**설정 적용:**
```bash
sudo ln -s /etc/nginx/sites-available/blog.conf /etc/nginx/sites-enabled/
sudo nginx -t
sudo systemctl reload nginx
```

### 6.2 Let's Encrypt SSL 인증서

```bash
# Certbot 설치
sudo apt install certbot python3-certbot-nginx

# 인증서 발급
sudo certbot --nginx -d yourdomain.com -d www.yourdomain.com

# 자동 갱신 확인
sudo certbot renew --dry-run
```

Certbot 덕분에 HTTPS 설정이 정말 쉬워졌어요.

### 6.3 배포 스크립트 (deploy.sh)

```bash
#!/bin/bash
set -euo pipefail

echo "=== Hugo 블로그 배포 시작 ==="

# 1. 빌드
echo "[1/4] Hugo 빌드 중..."
hugo --minify

# 2. 백업
echo "[2/4] 기존 사이트 백업 중..."
sudo mkdir -p /var/backups/blog
sudo rsync -a /var/www/blog/ /var/backups/blog/backup-$(date +%Y%m%d-%H%M%S)/

# 3. 배포
echo "[3/4] 새 사이트 배포 중..."
sudo rsync -avh --delete public/ /var/www/blog/
sudo chown -R www-data:www-data /var/www/blog
sudo chmod -R 755 /var/www/blog
sudo find /var/www/blog -type f -exec chmod 644 {} \;

# 4. Nginx 재시작
echo "[4/4] Nginx 재시작 중..."
sudo nginx -t && sudo systemctl reload nginx

echo "✅ 배포 완료!"
echo "🔗 https://yourdomain.com"
```

---

## 🐛 7. 트러블슈팅

### 7.1 "글을 작성했는데 안 보여요"

이거 정말 많이 겪었어요. 처음엔 뭐가 문제인지 몰라서 한참 헤맸죠.

**체크리스트:**

1. **Draft 확인**

```yaml
draft: false  # true면 비공개
```

2. **날짜 확인**

```yaml
date: 2025-11-16T14:00:00+09:00  # 미래 날짜면 buildFuture: true 필요
```

3. **Git 커밋/푸시 확인**

```bash
git status
git log origin/main..HEAD --oneline
```

4. **GitHub Actions 확인**
- GitHub → Actions 탭에서 워크플로우 성공 여부 확인

5. **브라우저 캐시 삭제**
- `Ctrl + Shift + R` (강제 새로고침)

### 7.2 "파일을 삭제했는데 웹사이트에 남아있어요"

**원인:** 브라우저 캐시 또는 rsync `--delete` 옵션 누락

**해결:**
```bash
# 1. Git에서 제대로 삭제되었는지 확인
git log --diff-filter=D --summary

# 2. rsync에 --delete 옵션 있는지 확인
sudo rsync -avh --delete public/ /var/www/blog/

# 3. 브라우저 캐시 삭제
Ctrl + Shift + Delete → 캐시 삭제
```

### 7.3 "날짜가 이상하게 표시돼요"

**원인:** `[frontmatter]` 설정에서 `:git` 우선순위가 높음

**해결:**
```toml
[frontmatter]
  date = ["date", "publishDate", ":git", ":fileModTime"]
  # frontmatter의 date 필드가 최우선!
```

### 7.4 "테마가 적용 안 돼요"

**원인:** Submodule이 제대로 클론 안 됨

**해결:**
```bash
# Submodule 초기화
git submodule update --init --recursive

# Submodule 상태 확인
git submodule status
```

### 7.5 "빌드 시 에러 발생"

```bash
# 에러 확인
hugo --verbose

# 캐시 삭제 후 재빌드
rm -rf resources/ public/
hugo --cleanDestinationDir
```

---

## 🚀 8. 성능 최적화

### 8.1 이미지 최적화

```bash
# WebP 변환 (더 작은 용량)
sudo apt install webp
cwebp -q 80 input.jpg -o output.webp

# 이미지 압축
sudo apt install optipng jpegoptim
jpegoptim --max=85 *.jpg
optipng -o7 *.png
```

**Frontmatter에서 여러 형식 지원:**
```yaml
cover:
  image: "/images/cover.webp"
  fallback: "/images/cover.jpg"
```

### 8.2 Hugo 빌드 최적화

```toml
[minify]
  minifyOutput = true

  [minify.tdewolff.html]
    keepWhitespace = false

  [minify.tdewolff.css]
    precision = 2
```

### 8.3 CDN 사용 (Cloudflare)

**장점:**
- 전 세계 엣지 서버에서 캐싱
- DDoS 방어
- 무료 SSL
- 대역폭 절약

Cloudflare를 쓰니까 블로그가 정말 빨라졌어요!

**설정:**
1. Cloudflare에 도메인 등록
2. DNS 설정을 Cloudflare 네임서버로 변경
3. SSL/TLS 모드: Full (strict)
4. Auto Minify 활성화 (HTML, CSS, JS)
5. Brotli 압축 활성화

---

## 📊 9. SEO 최적화

### 9.1 robots.txt

```toml
enableRobotsTXT = true
```

생성된 `/robots.txt`:

```

User-agent: *
Disallow: /admin/
Sitemap: https://yourdomain.com/sitemap.xml

```

### 9.2 Sitemap

Hugo는 자동으로 `sitemap.xml`을 생성합니다.

**커스터마이징:**
```toml
[sitemap]
  changefreq = "weekly"
  filename = "sitemap.xml"
  priority = 0.5
```

### 9.3 Open Graph & Twitter Cards

**Frontmatter:**
```yaml
---
title: "제목"
description: "설명"
cover:
  image: "/images/cover.jpg"
  alt: "이미지 설명"
---
```

**layouts/partials/head.html (커스텀):**
```html
<meta property="og:title" content="{{ .Title }}" />
<meta property="og:description" content="{{ .Description }}" />
<meta property="og:image" content="{{ .Params.cover.image | absURL }}" />
<meta property="og:url" content="{{ .Permalink }}" />
<meta name="twitter:card" content="summary_large_image" />
```

---

## 🎯 10. 체크리스트

### 새 글 발행 체크리스트

- [ ] `draft: false` 설정
- [ ] `date` 필드 확인 (미래 날짜 아닌지)
- [ ] `title`, `description` 작성
- [ ] `tags`, `categories` 설정
- [ ] 이미지 경로 확인 (깨진 링크 없는지)
- [ ] 로컬 미리보기 (`hugo server -D`)
- [ ] Git 커밋 & 푸시
- [ ] GitHub Actions 성공 확인
- [ ] 실제 사이트 확인
- [ ] 브라우저 캐시 삭제 후 재확인

이 체크리스트 덕분에 실수가 많이 줄었어요.

### 배포 후 확인 사항

```bash
# 1. 빌드된 페이지 수 확인
find public -name '*.html' | wc -l

# 2. 배포 성공 확인
curl -I https://yourdomain.com

# 3. RSS 피드 확인
curl https://yourdomain.com/index.xml

# 4. Sitemap 확인
curl https://yourdomain.com/sitemap.xml

# 5. Lighthouse 점수 확인 (Chrome DevTools)
```

---

## 📚 11. 추가 학습 자료

- **Hugo 공식 문서:** https://gohugo.io/documentation/
- **PaperMod 테마:** https://github.com/adityatelange/hugo-PaperMod
- **Markdown 가이드:** https://www.markdownguide.org/
- **GitHub Actions:** https://docs.github.com/en/actions
- **Nginx 최적화:** https://nginx.org/en/docs/

---

## 🎓 마치며

이제 Hugo 블로그의 전체 구조와 작동 원리를 이해하셨을 것입니다.

처음 시작할 때는 막막했지만, 하나씩 알아가면서 정말 재미있었어요. 여러분도 직접 만들어보면서 배우는 게 가장 빠른 방법이라고 생각해요.

**핵심 요약:**
1. `config.toml`이 모든 설정의 중심
2. `content/`에 마크다운으로 글 작성
3. Git 푸시 → GitHub Actions → 자동 배포
4. Nginx가 `/var/www/blog` 경로 서빙
5. 문제 발생 시: draft, 날짜, Git 상태, 캐시 확인

질문이나 피드백은 댓글로 남겨주세요!
