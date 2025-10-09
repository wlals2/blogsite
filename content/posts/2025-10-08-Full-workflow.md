---
title: "Hugo 블로그 자동배포 완전 정복: SSH 키와 Self-hosted Runner의 모든 것"
date: 2025-10-08T16:00:00+09:00
draft: false
tags: ["Hugo", "CI/CD", "GitHub Actions", "Self-hosted Runner", "SSH", "Nginx", "DevOps"]
categories: ["Infra", "DevOps", "Blog"]
series: ["내 기술 블로그 구축기"]
---

# 🚀 개요

Hugo 블로그를 Self-hosted Runner로 자동배포하면서 가장 혼란스러웠던 부분:
- **"SSH 키는 어디에 필요한가?"**
- **"Self-hosted Runner는 어떻게 동작하는가?"**
- **"전체 워크플로우는 어떻게 연결되는가?"**

이 글에서는 이 모든 의문을 **그림과 예제**로 명확하게 정리합니다.

---

## 🔑 1. SSH 키의 역할 이해하기

### 현재 사용 중인 SSH 키들

```bash
~/.ssh/
├── id_ed25519          # Git push/pull 인증용 (Private Key)
├── id_ed25519.pub      # GitHub에 등록 (Public Key)
└── authorized_keys     # (Self-hosted에서는 불필요)
```

### Q: Git push할 때 SSH 키가 필요한가?

**답: YES! 하지만 Self-hosted Runner와는 무관합니다.**

#### Git SSH 인증 흐름

```
┌─────────────────┐      git push      ┌─────────────────┐
│   개발자 PC     │─────────────────>│   GitHub.com    │
│                 │                    │                 │
│ ~/.ssh/         │                    │ Settings →      │
│ id_ed25519      │<──── 인증 ────────│ SSH Keys에      │
│ (Private Key)   │                    │ Public Key 등록 │
└─────────────────┘                    └─────────────────┘

용도: git push, git pull, git clone (SSH URL)
```

**설정 방법**:
```bash
# 1. SSH 키 생성
ssh-keygen -t ed25519 -C "your@email.com"

# 2. Public Key 확인
cat ~/.ssh/id_ed25519.pub

# 3. GitHub에 등록
# GitHub → Settings → SSH and GPG keys → New SSH key
# 위에서 복사한 Public Key 붙여넣기

# 4. 연결 테스트
ssh -T git@github.com
# 출력: Hi username! You've successfully authenticated...
```

### Q: Self-hosted Runner에 SSH 키가 필요한가?

**답: NO! Runner는 HTTPS를 사용합니다.**

#### Self-hosted Runner 인증 흐름

```
┌─────────────────────┐              ┌──────────────────┐
│  Self-hosted Runner │              │  GitHub Actions  │
│  (내 서버)          │              │                  │
│                     │              │                  │
│  ~/actions-runner/  │<──HTTPS───>│  Job Queue       │
│  .credentials       │   Polling   │                  │
│  (OAuth Token)      │              │                  │
└─────────────────────┘              └──────────────────┘

인증: HTTPS + OAuth Token (SSH 키 사용 안 함)
```

**확인**:
```bash
cat ~/actions-runner/.runner | grep serverUrl

# 출력:
# "serverUrl": "https://pipelines-ghubeus**.actions.githubusercontent.com/[TOKEN]..."
# → HTTPS 사용! SSH 아님!
```

---

## 🏃 2. Self-hosted Runner 동작 원리

### Runner가 Job을 받는 과정 (Long Polling)

```
┌────────────────────────────────────────────────────────┐
│                    GitHub Actions                       │
│                                                          │
│  1. Push 이벤트 발생                                    │
│  2. Workflow 트리거 (.github/workflows/deploy.yml)     │
│  3. Job 생성 & Queue에 등록                            │
│     runs-on: [self-hosted, linux, x64]                 │
└─────────────────────┬──────────────────────────────────┘
                      │
                      │ HTTPS (443)
                      │ Long Polling
                      ↓
        ┌─────────────────────────────┐
        │  Self-hosted Runner         │
        │  (systemd service)          │
        │                             │
        │  while true; do             │
        │    check_for_jobs()         │
        │    if job_available; then   │
        │      execute_job()          │
        │    fi                       │
        │    sleep 1                  │
        │  done                       │
        └─────────────────────────────┘
```

**핵심 포인트**:
1. **Runner가 능동적으로 확인** - GitHub이 Runner에게 연결하는 게 아님
2. **Outbound 연결만 필요** - 방화벽 Inbound 포트 열 필요 없음
3. **SSH 불필요** - HTTPS로 통신

---

## 📊 3. 전체 워크플로우 (상세)

### 시나리오: 블로그 글 작성 → 자동 배포

```
┌──────────────────────────────────────────────────────────────┐
│  Phase 1: 개발자가 글 작성 & Push                            │
└──────────────────────────────────────────────────────────────┘

[개발자 PC - ~/blogsite]
  $ hugo new posts/my-post.md
  $ vim content/posts/my-post.md

  $ git add content/posts/my-post.md
  $ git commit -m "post: 새 글 작성"
  $ git push origin main
     ↓
     └─ SSH 인증 (id_ed25519 사용)


┌──────────────────────────────────────────────────────────────┐
│  Phase 2: GitHub Actions 트리거                              │
└──────────────────────────────────────────────────────────────┘

[GitHub.com]
  ① Push 이벤트 감지
     - Branch: main ✓
     - Changed files: content/posts/my-post.md
     - Workflow file: .github/workflows/deploy.yml

  ② Workflow 조건 확인
     paths:
       - "content/**"  ← 매칭! ✓

  ③ Job 생성
     runs-on: [self-hosted, linux, x64]
     Status: Queued


┌──────────────────────────────────────────────────────────────┐
│  Phase 3: Runner가 Job 감지 & 실행                          │
└──────────────────────────────────────────────────────────────┘

[서버 - Runner.Listener]
  ① Long Polling으로 Job 확인 (매 1초)
     GET https://github.com/.../jobs?status=queued
     → 응답: deploy Job 발견!

  ② Job 다운로드 (HTTPS)
     - Workflow 정의
     - Environment variables
     - Secrets

  ③ Worker 프로세스 시작
     ~/actions-runner/bin/Runner.Worker


┌──────────────────────────────────────────────────────────────┐
│  Phase 4: Workflow Steps 실행                                │
└──────────────────────────────────────────────────────────────┘

[Step 1] Checkout
  actions/checkout@v4
  ├─ Git clone (HTTPS, GitHub Token 자동 사용)
  ├─ ~/actions-runner/_work/blogsite/blogsite/
  └─ Submodule 초기화 (themes/PaperMod)

[Step 2] Setup Hugo
  peaceiris/actions-hugo@v3
  ├─ Hugo 바이너리 다운로드
  └─ PATH에 추가

[Step 3] Build
  $ hugo --minify
  ├─ content/ → HTML 변환
  ├─ public/ 디렉토리 생성
  └─ 정적 파일 생성 완료

[Step 4] Stamp deploy info
  $ echo "..." > public/deploy.txt
  └─ 배포 시간, commit 정보 기록

[Step 5] Deploy
  $ sudo rsync -ah --delete public/ /var/www/blog/
  $ sudo nginx -t
  $ sudo systemctl reload nginx


┌──────────────────────────────────────────────────────────────┐
│  Phase 5: Nginx가 서빙                                       │
└──────────────────────────────────────────────────────────────┘

[Nginx]
  /var/www/blog/
  ├─ index.html
  ├─ posts/
  │   └─ my-post/
  │       └─ index.html  ← 새 글!
  └─ deploy.txt

  https://blog.example.com/posts/my-post/
  → 새 글 접속 가능! 🎉
```

---

## 🔧 4. 실전 셋업 가이드

### Step 1: SSH 키 설정 (Git 인증용)

```bash
# 개발자 PC에서

# 1. SSH 키 생성
ssh-keygen -t ed25519 -C "your@email.com"
# Enter file: [기본값 사용]
# Passphrase: [선택사항]

# 2. Public Key 복사
cat ~/.ssh/id_ed25519.pub

# 3. GitHub에 등록
# https://github.com/settings/keys → New SSH key

# 4. Git remote를 SSH로 설정
git remote set-url origin git@github.com:username/my-hugo-blog.git

# 5. 테스트
ssh -T git@github.com
git push origin main
```

### Step 2: Self-hosted Runner 설정

```bash
# 서버에서

# 1. Runner 다운로드
mkdir ~/actions-runner && cd ~/actions-runner
curl -o actions-runner-linux-x64-2.328.0.tar.gz -L \
  https://github.com/actions/runner/releases/download/v2.328.0/actions-runner-linux-x64-2.328.0.tar.gz
tar xzf actions-runner-linux-x64-2.328.0.tar.gz

# 2. Runner 등록
# GitHub Repo → Settings → Actions → Runners → New self-hosted runner
# Token을 복사해서 사용
./config.sh --url https://github.com/username/my-blog --token <TOKEN>

# 3. systemd 서비스로 등록
sudo ./svc.sh install
sudo ./svc.sh start

# 4. 상태 확인
systemctl status actions.runner.*
```

### Step 3: sudo 권한 설정

```bash
# 서버에서

sudo visudo -f /etc/sudoers.d/github-runner
```

내용:
```
# GitHub Actions Runner
username ALL=(ALL) NOPASSWD: /bin/mkdir -p /var/www/blog
username ALL=(ALL) NOPASSWD: /usr/bin/chown -R username\:www-data /var/www/blog
username ALL=(ALL) NOPASSWD: /usr/sbin/nginx -t
username ALL=(ALL) NOPASSWD: /bin/systemctl reload nginx
```

**보안 주의**:
```bash
# ❌ 위험! 모든 sudo 권한
username ALL=(ALL) NOPASSWD: ALL

# ✅ 안전! 필요한 명령어만
username ALL=(ALL) NOPASSWD: /bin/systemctl reload nginx
```

### Step 4: Workflow 파일 작성

`.github/workflows/deploy.yml`:
```yaml
name: Deploy Hugo Blog (self-hosted)

on:
  push:
    branches: [ "main" ]
    paths:
      - "content/**"
      - "static/**"
      - "themes/**"
      - "config.*"

concurrency:
  group: hugo-deploy-production
  cancel-in-progress: true

jobs:
  deploy:
    runs-on: [self-hosted, linux, x64]
    timeout-minutes: 15

    steps:
      - name: Checkout
        uses: actions/checkout@v4
        with:
          submodules: true

      - name: Setup Hugo (extended)
        uses: peaceiris/actions-hugo@v3
        with:
          hugo-version: 'latest'
          extended: true

      - name: Build (production)
        env:
          HUGO_ENV: production
        run: |
          hugo --minify
          echo "Build complete!"

      - name: Stamp deploy info
        run: |
          printf "source=ci\ntime=%s\ncommit=%s\nrun_id=%s\n" \
            "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" \
            "${GITHUB_SHA::7}" \
            "$GITHUB_RUN_ID" \
            > public/deploy.txt

      - name: Deploy to nginx root
        run: |
          sudo mkdir -p /var/www/blog
          sudo chown -R $USER:www-data /var/www/blog
          rsync -ah --delete public/ /var/www/blog/
          sudo nginx -t
          sudo systemctl reload nginx
```

### Step 5: Nginx 설정

```bash
sudo vim /etc/nginx/sites-enabled/blog
```

내용:
```nginx
server {
    listen 80;
    server_name blog.example.com;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl http2;
    server_name blog.example.com;

    ssl_certificate /etc/letsencrypt/live/blog.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/blog.example.com/privkey.pem;

    root /var/www/blog;
    index index.html;

    location / {
        try_files $uri $uri/ =404;
    }
}
```

```bash
sudo nginx -t
sudo systemctl restart nginx
```

---

## 🎯 5. 핵심 정리

### SSH 키 사용 여부

| 작업 | SSH 키 필요? | 인증 방식 |
|------|-------------|----------|
| **git push** (개발자 → GitHub) | ✅ YES | SSH Key (`id_ed25519`) |
| **Runner → GitHub** (Job 받기) | ❌ NO | HTTPS + OAuth Token |
| **Workflow Checkout** (코드 다운로드) | ❌ NO | HTTPS + GitHub Token |
| **로컬 배포** (rsync) | ❌ NO | 로컬 파일 복사 |

### Self-hosted Runner vs GitHub-hosted

| 항목 | GitHub-hosted | Self-hosted |
|------|---------------|-------------|
| **비용** | 무료 (2000분/월) | 서버 비용 |
| **속도** | 느림 | 빠름 (로컬) |
| **서버 접근** | 불가 | 가능 (rsync, sudo) |
| **관리** | GitHub가 관리 | 직접 관리 |
| **보안** | GitHub 책임 | 직접 책임 |

---

## 🔍 6. 트러블슈팅

### 문제 1: Runner가 Job을 받지 못함

```bash
# Runner 상태 확인
systemctl status actions.runner.*

# Runner 프로세스 확인
ps aux | grep Runner.Listener

# 로그 확인
tail -f ~/actions-runner/_diag/Runner_*.log

# 해결: Runner 재시작
sudo systemctl restart actions.runner.*
```

### 문제 2: Workflow 실패 (sudo 권한)

```bash
# 증상: Permission denied

# 확인
sudo -l | grep NOPASSWD

# 해결
sudo visudo -f /etc/sudoers.d/github-runner
# 필요한 명령어 추가
```

### 문제 3: paths 필터로 트리거 안 됨

```yaml
# 문제: deploy.yml만 수정해도 배포되길 원함
on:
  push:
    branches: [ "main" ]
    paths:
      - "content/**"  # content 폴더만 트리거

# 해결 1: paths 필터 제거
on:
  push:
    branches: [ "main" ]

# 해결 2: workflow_dispatch 추가 (수동 실행)
on:
  push:
    branches: [ "main" ]
  workflow_dispatch:  # Actions 탭에서 수동 실행 버튼 생성
```

---

## 📚 7. 고급 활용

### 여러 서버에 배포

```yaml
- name: Deploy to multiple servers
  run: |
    # 서버 1
    rsync -avz -e "ssh -i ~/.ssh/deploy_key" \
      public/ user@server1:/var/www/blog/

    # 서버 2
    rsync -avz -e "ssh -i ~/.ssh/deploy_key" \
      public/ user@server2:/var/www/blog/
```

이 경우 **SSH 키 필요**:
```bash
# 서버에서 SSH 키 생성
ssh-keygen -t ed25519 -f ~/.ssh/deploy_key

# Public Key를 원격 서버에 등록
ssh-copy-id -i ~/.ssh/deploy_key.pub user@server1
```

### 배포 알림 (Slack/Discord)

```yaml
- name: Notify deployment
  if: success()
  run: |
    curl -X POST ${{ secrets.SLACK_WEBHOOK }} \
      -H 'Content-Type: application/json' \
      -d '{"text":"✅ Blog deployed! Commit: ${{ github.sha }}"}'
```

### Rollback 기능

```yaml
- name: Backup before deploy
  run: |
    sudo cp -r /var/www/blog /var/www/blog.backup

- name: Deploy
  run: |
    rsync -ah --delete public/ /var/www/blog/

- name: Rollback on failure
  if: failure()
  run: |
    sudo rm -rf /var/www/blog
    sudo mv /var/www/blog.backup /var/www/blog
```

---

## 💡 8. 마무리

### 핵심 3줄 요약

1. **SSH 키**: Git push 인증용 (Self-hosted Runner와 무관)
2. **Self-hosted Runner**: HTTPS Long Polling으로 Job 받음
3. **배포**: 같은 서버에서 rsync로 복사 (SSH 불필요)

### 학습 포인트

✅ **이해한 것**:
- Self-hosted Runner의 동작 원리 (Long Polling)
- SSH 키의 실제 용도 (Git vs Runner)
- CI/CD 파이프라인의 전체 흐름

✅ **실습한 것**:
- Runner 등록 & systemd 서비스화
- sudo 권한 최소화 설정
- Workflow 파일 작성 & 테스트

✅ **트러블슈팅**:
- Runner 상태 확인 방법
- 로그 분석 기술
- 권한 문제 해결

### 다음 단계

1. **모니터링 추가**: 배포 실패 시 알림
2. **성능 최적화**: 빌드 캐시, 병렬 처리
3. **보안 강화**: Secret 관리, 네트워크 격리
4. **문서화**: 팀원과 공유

---

## 📖 참고 자료

- [GitHub Actions - Self-hosted runners](https://docs.github.com/en/actions/hosting-your-own-runners)
- [Hugo Documentation](https://gohugo.io/documentation/)
- [systemd 가이드](https://www.freedesktop.org/software/systemd/man/)
- [Nginx 공식 문서](https://nginx.org/en/docs/)

---

**💡 핵심 교훈**:
> "복잡해 보이는 CI/CD도 결국 단계별로 나누면 이해할 수 있다. 로그를 읽고, 과정을 이해하고, 하나씩 검증하자."

**🎯 실전 팁**:
> "SSH 키가 필요한지 헷갈릴 땐, '누가 누구에게 연결하는가?'를 먼저 생각하자. Self-hosted는 내가 GitHub에 연결하는 것이므로 SSH 불필요!"
