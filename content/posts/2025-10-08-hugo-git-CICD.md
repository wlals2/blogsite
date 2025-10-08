---
title: "GitHub Actions를 이용한 Hugo 블로그 자동 배포 구축기"
date: 2025-10-08
tags: ["hugo", "github-actions", "nginx", "cicd", "devops"]
series: ["infra-note"]
draft: false
---

## 1. 목표

로컬에서 직접 Hugo를 빌드하고 서버에 수동 복사하지 않아도,
`git push` 한 번으로 자동 배포가 이루어지는 **CI/CD 파이프라인**을 구성합니다.

---

## 2. 구성 개요

| 구성요소 | 설명 |
|-----------|------|
| **GitHub Repository** | `my-hugo-blog` — Hugo 소스 저장소 |
| **Self-hosted Runner** | Ubuntu 22.04 서버 (로컬 또는 클라우드) |
| **웹 루트 경로** | `/home/ubuntu/hugo-blog/public` |
| **웹서버** | Nginx (HTTPS, Let's Encrypt 인증서 적용) |
| **도메인** | `https://example-blog.site` |

> Hugo는 정적 사이트 생성기이므로, 빌드 결과물(`public/`)을 웹서버에 복사하면 곧 배포 완료입니다.

---

## 3. Self-hosted Runner 등록

### (1) GitHub에서 등록

GitHub → Repository → **Settings → Actions → Runners**
→ "New self-hosted runner" → Linux → x64 선택 후 표시된 명령어를 복사합니다.

### (2) Ubuntu에서 설치

```bash
mkdir -p ~/actions-runner && cd ~/actions-runner
curl -o actions-runner-linux-x64.tar.gz -L https://github.com/actions/runner/releases/latest/download/actions-runner-linux-x64.tar.gz
tar xzf ./actions-runner-linux-x64.tar.gz
```

### (3) GitHub에서 받은 토큰을 이용해 등록

```bash
./config.sh --url https://github.com/<GitHubID>/my-hugo-blog --token <등록_토큰>
```

### (4) 서비스 등록 및 실행

```bash
sudo ./svc.sh install
sudo ./svc.sh start
```

### (5) 정상 동작 확인

```bash
sudo systemctl status actions.runner.<GitHubID>.my-hugo-blog.service
```

---

## 4. GitHub Actions 워크플로우

`.github/workflows/deploy.yml` 파일을 생성합니다.

```yaml
name: Deploy Hugo Blog

on:
  push:
    branches: [ "main" ]

jobs:
  build-deploy:
    runs-on: self-hosted

    steps:
      - name: Checkout repository
        uses: actions/checkout@v4

      - name: Install Hugo
        run: |
          sudo snap install hugo --channel=extended
          hugo version

      - name: Build Hugo site
        run: |
          hugo --minify

      - name: Deploy to Nginx web root
        run: |
          sudo rsync -av --delete public/ /home/ubuntu/hugo-blog/public/
          sudo chown -R www-data:www-data /home/ubuntu/hugo-blog/public
          sudo systemctl reload nginx
```

> 이 파일이 핵심입니다.
> `main` 브랜치로 커밋이 push되면 runner가 자동으로 Hugo를 빌드하고 Nginx에 반영합니다.

---

## 5. Nginx 설정 예시

```nginx
server {
    listen 80;
    server_name example-blog.site;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl;
    server_name example-blog.site;

    ssl_certificate /etc/letsencrypt/live/example-blog.site/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/example-blog.site/privkey.pem;

    root /home/ubuntu/hugo-blog/public;
    index index.html;
}
```

---

## 6. Hugo 디렉터리 구조 요약

| 폴더 | 설명 |
|------|------|
| `content/` | Markdown 글 |
| `themes/` | PaperMod 테마 |
| `public/` | Hugo 빌드 결과물 |
| `config.toml` | Hugo 설정파일 |
| `.github/workflows/deploy.yml` | 자동배포 워크플로우 |

---

## 7. 배포 테스트

로컬에서 새 글을 추가하고 push합니다:

```bash
git add .
git commit -m "Add new post: CI/CD 구축기"
git push origin main
```

GitHub → Actions 탭에서 "Deploy Hugo Blog" 실행 로그를 확인하면
빌드 및 배포가 자동으로 이루어집니다.

---

## 8. 장점 요약

✅ 수동 배포가 필요 없음
✅ Hugo 빌드 → Nginx 반영까지 자동화
✅ Self-hosted Runner로 SSH 접근 없이 서버 제어 가능
✅ DevOps 실습 및 개인 블로그 운영에 최적
