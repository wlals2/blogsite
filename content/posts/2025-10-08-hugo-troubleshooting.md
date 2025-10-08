---
title: "Hugo 블로그 자동배포 트러블슈팅 기록"
date: 2025-10-08T19:00:00+09:00
draft: false
tags: ["Hugo", "CI/CD", "GitHub Actions", "Nginx", "Let's Encrypt"]
categories: ["Infra", "DevOps","Blog"]
series: ["내 기술 블로그 구축기"]
---

# Hugo + Nginx + GitHub Actions 자동배포 트러블슈팅

이번 글에서는 **Hugo PaperMod 테마 기반 기술 블로그**를 구축하면서 겪은 문제와 해결 과정을 정리했습니다.
목표는 **`https://blog.example.com`** 에서 Hugo 정적 블로그를 자동으로 배포하는 것입니다.

---

## 1️⃣ 초기 환경

- OS: Ubuntu 22.04
- 웹서버: **nginx**
- 인증서: **Let's Encrypt (Certbot)**
- Hugo 테마: **PaperMod**
- 소스 저장소: GitHub (`my-hugo-blog`)
- 배포 대상 디렉토리: `/home/jimin/blogsite/public`

---

## 2️⃣ HTTPS 인증서 설정

```bash
sudo apt install -y certbot python3-certbot-nginx
sudo certbot --nginx -d blog.example.com -m admin@example.com --agree-tos -n
```

정상적으로 발급되면 다음 경로에 인증서가 저장됩니다:

```
/etc/letsencrypt/live/blog.example.com/
├── fullchain.pem
└── privkey.pem
```

### 주의사항

nginx 설정을 root 권한 없이 테스트하면 다음 오류가 발생합니다.

```
cannot load certificate ... Permission denied
```

이는 정상입니다. `sudo nginx -t` 명령으로만 인증서 접근이 가능합니다.

---

## 3️⃣ PaperMod 테마 적용

```bash
cd /var/www/hugo
hugo new site blogsite
cd blogsite
git init
git submodule add https://github.com/adityatelange/hugo-PaperMod.git themes/PaperMod
```

`config.toml` 수정:

```toml
baseURL = "https://blog.example.com/"
title = "지민 기술 블로그"
theme = "PaperMod"
languageCode = "ko-kr"
defaultContentLanguage = "ko"

[pagination]
  pagerSize = 10

[minify]
  minifyOutput = true

[params]
  env = "production"
  defaultTheme = "auto"
  showReadingTime = true

  [params.homeInfoParams]
    Title = "지민 기술 블로그"
    Content = "인프라/클라우드/리눅스 관련 메모를 정리합니다. 🚀"
```

---

## 4️⃣ Hugo 빌드 및 수동 배포 스크립트

```bash
cat <<'SH' > deploy.sh
#!/usr/bin/env bash
set -e
hugo --minify
sudo rsync -av --delete public/ /var/www/blog/
sudo systemctl reload nginx
SH
chmod +x deploy.sh
```

403 오류 발생 시 `/var/www/blog` 권한을 확인하세요:

```bash
sudo chown -R www-data:www-data /var/www/blog
sudo chmod -R 755 /var/www/blog
```

---

## 5️⃣ GitHub Actions 자동배포 설정

처음엔 self-hosted runner로 시도했지만, 러너가 등록되지 않아 실행이 중단되었습니다.

> ❗️ "You don't have any self-hosted runners for this repository"
> → GitHub-hosted runner + SSH 배포 방식으로 전환

### (1) 서버 쪽 SSH 설정

```bash
ssh-keygen -t ed25519 -f ~/.ssh/github_deploy -N ""
cat ~/.ssh/github_deploy.pub >> ~/.ssh/authorized_keys
chmod 700 ~/.ssh && chmod 600 ~/.ssh/authorized_keys
echo 'jimin ALL=(ALL) NOPASSWD: /bin/systemctl reload nginx' | sudo tee /etc/sudoers.d/gh-actions
```

### (2) GitHub Secrets 등록

| Key | Example |
|-----|---------|
| `SSH_HOST` | `blog.example.com` |
| `SSH_PORT` | `22` |
| `SSH_USER` | `jimin` |
| `SSH_KEY` | 개인키 내용 전체 (`~/.ssh/github_deploy`) |

### (3) `.github/workflows/deploy.yml`

```yaml
name: Deploy Hugo Blog (SSH)

on:
  push:
    branches: [ "main" ]
  workflow_dispatch: {}

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          submodules: recursive

      - uses: peaceiris/actions-hugo@v3
        with:
          hugo-version: 'latest'
          extended: true

      - name: Build
        run: hugo --minify

      - name: Prepare SSH
        run: |
          install -m 600 /dev/null ~/.ssh/id_deploy
          echo "${{ secrets.SSH_KEY }}" > ~/.ssh/id_deploy
          ssh-keyscan -p "${{ secrets.SSH_PORT }}" "${{ secrets.SSH_HOST }}" >> ~/.ssh/known_hosts

      - name: Deploy via rsync
        run: |
          rsync -avz --delete -e "ssh -i ~/.ssh/id_deploy -p ${{ secrets.SSH_PORT }}" \
            public/ ${{ secrets.SSH_USER }}@${{ secrets.SSH_HOST }}:/home/jimin/blogsite/public/
          ssh -i ~/.ssh/id_deploy -p "${{ secrets.SSH_PORT }}" ${{ secrets.SSH_USER }}@${{ secrets.SSH_HOST }} \
            "sudo nginx -t && sudo systemctl reload nginx"
```

### (4) 푸시 후 동작

```bash
git add .
git commit -m "post: 자동배포 테스트"
git push origin main
```

→ GitHub Actions에서 자동 빌드 & 배포 완료!
`https://blog.example.com` 에서 바로 반영됩니다.

---

## 6️⃣ Git Push 인증 오류 해결

### ⚠️ 문제

```
remote: Invalid username or token. Password authentication is not supported for Git operations.
```

### ✅ 해결 — SSH 연결로 변경

```bash
ssh-keygen -t ed25519 -C "your-email@example.com"
cat ~/.ssh/id_ed25519.pub
```

**GitHub → Settings → SSH and GPG keys → New SSH key** 등록 후 원격 URL 변경:

```bash
git remote set-url origin git@github.com:wlals2/my-hugo-blog.git
git push origin main
```

이제 매번 토큰 입력 없이도 푸시됩니다.

---

## 🚀 정리

| 항목 | 도구 | 비고 |
|------|------|------|
| 정적 사이트 생성 | Hugo + PaperMod | `hugo new posts/` 명령으로 글 작성 |
| 웹서버 | nginx | `/home/jimin/blogsite/public` 서빙 |
| 인증서 | Let's Encrypt | Certbot 자동갱신 |
| 자동배포 | GitHub Actions + SSH | GitHub-hosted runner 사용 |
| 인증 | SSH 키 기반 | 비밀번호/PAT 불필요 |

---

## 📚 느낀 점

- Hugo는 "정적 사이트지만 코드로 완벽히 관리되는 블로그"다.
- PaperMod 테마는 깔끔하고, `config.toml`만 수정하면 기본 구성이 가능하다.
- CI/CD를 직접 구축하면서 nginx 설정, 권한, SSH 보안 구조까지 한 번에 익힐 수 있었다.

---

## Reference

- [Hugo 공식 문서](https://gohugo.io/documentation/)
- [PaperMod 테마](https://github.com/adityatelange/hugo-PaperMod)
- [Certbot with nginx](https://certbot.eff.org/instructions?ws=nginx&os=ubuntufocal)
- [GitHub Actions: Deploy via SSH](https://github.com/marketplace/actions/ssh-remote-commands)

---

💡 **"인프라를 잘 아는 엔지니어가 직접 운영하는 개인 블로그는, 그 자체로 최고의 포트폴리오다."**
