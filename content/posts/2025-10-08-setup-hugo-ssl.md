---
title: "내 기술 블로그 구축기 ① Let's Encrypt와 Nginx로 HTTPS 완성하기"
date: 2025-10-08
draft: false
categories: ["DevOps", "Blog"]
tags: ["Hugo", "Nginx", "Let's Encrypt", "Cloudflare", "Ubuntu"]
series: ["내 기술 블로그 구축기"]
summary: "Ubuntu에서 Hugo 블로그를 구축하고 Let's Encrypt 인증서를 적용해 HTTPS를 완성하는 과정을 정리했습니다."
---

## 💡 개요

이번 글에서는 **내 기술 블로그(blog.jiminhome.shop)** 를 직접 서버에 구축하면서  
**Let's Encrypt 인증서로 HTTPS를 적용**하고, **Nginx를 설정**한 전체 과정을 기록했습니다.

---

## 🧩 환경 구성

| 항목 | 내용 |
|------|------|
| OS | Ubuntu 22.04 LTS |
| 호스트명 | `jimin-AB350M-Gaming-3` (보드명)| 
| 도메인 | `blog.jiminhome.shop` |
| DNS | Cloudflare |
| 웹서버 | Nginx |
| 정적 사이트 | Hugo (PaperMod 테마) |
| SSL 발급 도구 | Certbot (Let's Encrypt) |

---

##  Step 1. Nginx 설치

```bash
sudo apt update
sudo apt install -y nginx
sudo systemctl enable nginx
sudo systemctl start nginx

```

설치 후 브라우저에서 서버 IP(http://122.46.102.248)로 접속해
Welcome to nginx! 페이지가 보이면 정상입니다.

## ☁️ Step 2. Cloudflare DNS 설정
1. Cloudflare에 blog.jiminhome.shop 도메인을 추가
2. A 레코드 생성

```bash
blog  →  122.46.102.248

```

3. Proxy(🌩️) 기능은 ON으로 유지해도 HTTPS 동작에는 문제 없습니다.
단, 인증서 발급 시에는 일시적으로 OFF 하는 것이 안전합니다.

## 🔐 Step 3. Let's Encrypt SSL 인증서 발급

Certbot과 nginx 플러그인을 설치합니다.

``` bash
sudo apt install -y certbot python3-certbot-nginx
# 인증서 발급
sudo certbot --nginx -d blog.jiminhome.shop

```
- 인증 이메일: fw4568@gmail.com
- 자동으로 /etc/letsencrypt/live/blog.jiminhome.shop/ 경로에 PEM 파일 생성
1. fullchain.pem
2. privkey.pem

## 🧠 Step 4. 인증서 자동 갱신 확인
**certbot.timer** 가 활성화되어 있으면 자동 갱신이 설정된 상태입니다.
```bash
sudo systemctl status certbot.timer
sudo certbot renew --dry-run

```

## 🧱 Step 5. Nginx HTTPS 설정 확인
인증서 적용이 완료되면 깁노 설정 파일(/etc/nginx/sites-enabled/default)에 자동으로 아래와 같은 블록 추가

```bash
server {
    listen 443 ssl;
    server_name blog.jiminhome.shop;

    ssl_certificate /etc/letsencrypt/live/blog.jiminhome.shop/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/blog.jiminhome.shop/privkey.pem;

    location / {
        root /var/www/html;
        index index.html;
    }
}
```

## ⚙️ Step 6. 방화벽 설정(UFW)

```bash
sudo ufw allow 'Nginx Full'
sudo ufw enable
sudo ufw status
# 80, 443/tcp 허용되어 있으면 된다.

```

## 🧾 Step 7. Hugo 블로그 연동 준비

이제 SSL이 완벽하게 적용된 상태이므로,
다음 글에서는 Hugo 블로그 콘텐츠 배포 **(PaperMod 테마 설정, posts 구조 등)**  를 다룰 예정입니다.
