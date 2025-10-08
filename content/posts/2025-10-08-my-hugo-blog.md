---
title: "내 기술 블로그 구축기 ② Hugo 블로그 설치와 포스트 작성 기본기"
date: 2025-10-08
draft: false
categories: ["DevOps", "Blog"]
tags: ["Hugo", "Static Site", "PaperMod", "Markdown"]
series: ["내 기술 블로그 구축기"]
summary: "Hugo를 설치하고 PaperMod 테마를 적용한 뒤, 새 포스트를 작성하는 기본 명령어와 폴더 구조를 정리했습니다."
---

## 💡 개요

1편에서는 **Let's Encrypt + Nginx**로 HTTPS 환경을 구축했습니다.
이번 글에서는 정적 사이트 생성기 **Hugo**를 설치하고,
**PaperMod 테마 적용 + 새 글 작성 및 배포** 방법을 정리합니다.

---

## 🧩 Step 1. Hugo 설치

```bash
sudo apt install -y hugo
```

설치 후 버전 확인:

```bash
hugo version
```

✅ Hugo Extended 버전이면 SCSS 테마(PaperMod 등)도 문제없이 사용할 수 있습니다.

---

## 📁 Step 2. 새 블로그 사이트 생성

블로그 프로젝트를 보관할 디렉터리를 정합니다.

```bash
mkdir ~/blogsite
cd ~/blogsite
hugo new site .
```

생성되면 기본 구조는 다음과 같습니다:

```
blogsite/
├── archetypes/
├── content/
├── layouts/
├── static/
├── themes/
└── config.toml
```

---

## 🎨 Step 3. PaperMod 테마 적용

Git으로 테마를 추가합니다.

```bash
git init
git submodule add https://github.com/adityatelange/hugo-PaperMod themes/PaperMod
```

`config.toml` 파일에 테마 설정을 추가합니다.

```toml
theme = "PaperMod"
title = "내 기술 블로그"
baseURL = "https://blog.jiminhome.shop"
languageCode = "ko-kr"
paginate = 5
```

⚙️ 이후 `config.toml`은 필요에 따라 `config.yml` 또는 `config/_default/` 디렉토리로 세분화해도 됩니다.

---

## ✍️ Step 4. 첫 포스트 작성하기

Hugo는 `content/` 폴더 아래에 Markdown 파일로 글을 관리합니다.
새 글을 만들려면 다음 명령을 사용합니다:

```bash
hugo new posts/2025-10-08-my-first-post.md
```

이 명령을 실행하면 아래와 같은 기본 틀이 자동 생성됩니다:

```yaml
---
title: "My First Post"
date: 2025-10-08
draft: true
---
```

`draft: true`는 초안 상태를 의미합니다.
실제 배포 시에는 `false`로 변경해야 사이트에 표시됩니다.

---

## 🧠 Step 5. 로컬 서버 실행

Hugo 내장 서버로 결과를 즉시 미리보기할 수 있습니다.

```bash
hugo server -D
```

- `-D`: draft 상태의 글도 포함하여 미리보기
- 기본 포트: `http://localhost:1313`

---

## 🚀 Step 6. 정적 사이트 빌드

최종 배포용 HTML을 생성합니다.

```bash
hugo
```

출력 결과는 `public/` 디렉터리에 저장됩니다.

```
blogsite/public/
├── index.html
├── posts/
└── categories/
```

이 폴더를 Nginx의 DocumentRoot로 연결하면 웹에서 접근할 수 있습니다.

---

## 🌐 Step 7. Nginx와 연동

`/etc/nginx/sites-enabled/default` 설정을 아래처럼 수정합니다.

```nginx
server {
    listen 80;
    server_name blog.jiminhome.shop;
    root /home/jimin/blogsite/public;

    location / {
        index index.html;
        try_files $uri $uri/ =404;
    }
}
```

Hugo에서 새 글을 추가하고 `hugo`로 빌드할 때마다
`public/` 폴더가 갱신되며 자동으로 반영됩니다.

---

## 🪶 Step 8. 포스트 관리 기본 명령어 요약

| 기능 | 명령어 예시 | 설명 |
|------|-------------|------|
| 새 사이트 생성 | `hugo new site myblog` | 새 Hugo 프로젝트 생성 |
| 새 글 작성 | `hugo new posts/hello-world.md` | Markdown 포스트 생성 |
| 초안 포함 미리보기 | `hugo server -D` | 로컬 미리보기 실행 |
| 정적 파일 빌드 | `hugo` | `public/`에 HTML 생성 |
| 배포 준비 | `rsync -avz public/ /var/www/html/` | 빌드 결과를 서버로 복사 |

---

## 🧾 Step 9. Hugo 디렉터리 구조 요약

| 폴더 | 역할 |
|------|------|
| `content/` | 실제 포스트 콘텐츠 (Markdown 파일) |
| `themes/` | 적용된 테마 |
| `layouts/` | 커스텀 템플릿 |
| `static/` | 이미지, CSS, JS 등 정적 파일 |
| `public/` | 빌드 결과 (웹 배포용) |
| `archetypes/` | 새 글 생성 시 기본 Front Matter 템플릿 |
