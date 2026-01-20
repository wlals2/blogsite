# Hugo 블로그 Self-hosted Runner 워크플로우 완전 이해

## 📋 목차
1. [SSH 키의 역할](#1-ssh-키의-역할)
2. [Self-hosted Runner 동작 원리](#2-self-hosted-runner-동작-원리)
3. [전체 워크플로우](#3-전체-워크플로우)
4. [실전 예제](#4-실전-예제)
5. [FAQ](#5-faq)

---

## 1. SSH 키의 역할

### 🔑 현재 사용 중인 SSH 키들

```
~/.ssh/
├── id_ed25519       ← GitHub에 push할 때 사용 (Git 인증)
├── id_ed25519.pub   ← GitHub Settings에 등록된 public key
├── github_deploy    ← (사용 안 함 - 이전 설정 잔여)
├── github_deploy.pub
└── authorized_keys  ← (사용 안 함 - Self-hosted에서는 불필요)
```

### Q1: Git push할 때 SSH 키가 필요한가?

**답: YES, 하지만 Self-hosted Runner와는 무관합니다.**

#### Git SSH 인증 흐름

```
[내 컴퓨터]                      [GitHub]
    |                               |
    | git push                      |
    |------------------------------>|
    |                               |
    | ~/.ssh/id_ed25519 사용       |
    | (Private Key)                |
    |                               |
    |        인증 확인              |
    |<------------------------------|
    | GitHub에 등록된               |
    | id_ed25519.pub와 매칭        |
    |                               |
    | Push 성공!                   |
    |------------------------------>|
```

**용도**:
- `git push`, `git pull` 등 **Git 작업 인증**
- GitHub이 "이 사람이 이 저장소에 접근 권한이 있는가?"를 확인

**설정 위치**:
- Local: `~/.ssh/id_ed25519` (Private Key)
- GitHub: Settings → SSH and GPG keys → `id_ed25519.pub` 등록

**확인**:
```bash
# SSH 키로 GitHub 연결 확인
ssh -T git@github.com

# 출력: Hi wlals2! You've successfully authenticated...
```

---

### Q2: Self-hosted Runner에 SSH 키가 필요한가?

**답: NO! Self-hosted Runner는 SSH를 사용하지 않습니다.**

#### Self-hosted Runner 인증 흐름

```
[내 서버 - Runner]              [GitHub Actions]
    |                               |
    | 1. Runner 등록 시             |
    |    ./config.sh 실행          |
    |------------------------------>|
    |                               |
    | 2. Token 생성                |
    |<------------------------------|
    | ~/.runner에 저장              |
    |                               |
    | 3. HTTPS로 연결 유지         |
    |<----------------------------->|
    | (지속적으로 Job 대기)         |
```

**인증 방식**:
- **HTTPS + OAuth Token** 사용
- SSH 키 **사용 안 함**
- Token은 `~/.runner`, `~/.credentials` 파일에 저장

**확인**:
```bash
# Runner 설정 파일
cat ~/actions-runner/.runner

# 출력 예시:
{
  "agentId": 2,
  "agentName": "jimin-AB350M-Gaming-3",
  "serverUrl": "https://pipelinesghubeus8.actions.githubusercontent.com/...",
  "gitHubUrl": "https://github.com/wlals2/my-hugo-blog"
}
```

→ **HTTPS URL 사용, SSH 아님!**

---

## 2. Self-hosted Runner 동작 원리

### 🏃 Runner는 어떻게 Job을 받는가?

```
┌─────────────────────────────────────────────────────────────┐
│                      GitHub Actions                          │
│  (https://github.com/wlals2/my-hugo-blog)                   │
└────────────────────┬────────────────────────────────────────┘
                     │
                     │ ① git push (main 브랜치)
                     │    content/** 파일 변경 감지
                     ↓
            ┌────────────────────┐
            │  Workflow 트리거   │
            │  (deploy.yml)      │
            └────────┬───────────┘
                     │
                     │ ② Job 생성
                     │    runs-on: [self-hosted, linux, x64]
                     ↓
            ┌────────────────────┐
            │   Job Queue        │
            │   (대기열에 등록)  │
            └────────┬───────────┘
                     │
                     │ ③ HTTPS Long Polling
                     │    (Runner가 계속 확인)
                     ↓
    ┌────────────────────────────────────┐
    │  Self-hosted Runner (내 서버)      │
    │                                     │
    │  Runner.Listener (프로세스)        │
    │  - 계속 GitHub에 Job 있는지 확인  │
    │  - Job 발견 시 즉시 실행          │
    └────────────────────────────────────┘
```

### 핵심 포인트

1. **Runner는 능동적으로 확인**
   - GitHub이 Runner에게 연결하는 게 아님
   - **Runner가 GitHub에 계속 물어봄** ("Job 있어?")
   - 이를 **"Long Polling"** 방식이라고 함

2. **SSH 불필요**
   - GitHub → Runner 연결이 아니므로 SSH 필요 없음
   - Runner → GitHub HTTPS 연결만 필요

3. **방화벽 뚫을 필요 없음**
   - Outbound(나가는) 연결만 필요
   - Inbound(들어오는) 포트 열 필요 없음

---

## 3. 전체 워크플로우

### 📊 시나리오: 블로그 글 작성 후 자동 배포

```
┌──────────────────────────────────────────────────────────────────┐
│  Step 0: 사전 준비 (한 번만)                                     │
└──────────────────────────────────────────────────────────────────┘

[개발자 로컬]
  1. SSH 키 생성 & GitHub 등록
     $ ssh-keygen -t ed25519 -C "your@email.com"
     $ cat ~/.ssh/id_ed25519.pub
     → GitHub Settings → SSH Keys 등록

  2. Git remote를 SSH로 설정
     $ git remote set-url origin git@github.com:wlals2/my-hugo-blog.git

[서버]
  3. Self-hosted Runner 설치 & 등록
     $ cd ~/actions-runner
     $ ./config.sh --url https://github.com/wlals2/my-hugo-blog --token <TOKEN>

  4. Runner를 systemd 서비스로 등록
     $ sudo ./svc.sh install
     $ sudo ./svc.sh start

  5. sudo 권한 설정
     $ sudo visudo -f /etc/sudoers.d/github-runner
     → 필요한 명령어 NOPASSWD 추가

  6. Nginx 설정
     $ sudo vim /etc/nginx/sites-enabled/blog
     → root /var/www/blog;


┌──────────────────────────────────────────────────────────────────┐
│  Step 1: 글 작성 & Push (개발자)                                │
└──────────────────────────────────────────────────────────────────┘

[개발자 로컬 PC - ~/blogsite]
  $ hugo new posts/my-post.md
  $ vim content/posts/my-post.md

  $ git add content/posts/my-post.md
  $ git commit -m "post: 새 글 작성"
  $ git push origin main
     ↓
     │ SSH 키 인증 (id_ed25519 사용)
     │ GitHub이 push 허용
     ↓


┌──────────────────────────────────────────────────────────────────┐
│  Step 2: GitHub Actions 트리거                                   │
└──────────────────────────────────────────────────────────────────┘

[GitHub - github.com/wlals2/my-hugo-blog]

  ① Push 이벤트 감지
     - 브랜치: main ✓
     - 변경 파일: content/posts/my-post.md ✓
     - paths 필터 매칭: content/** ✓

  ② Workflow 파일 읽기
     - .github/workflows/deploy.yml

  ③ Job 생성
     - Job name: deploy
     - runs-on: [self-hosted, linux, x64]
     - Status: Queued (대기 중)


┌──────────────────────────────────────────────────────────────────┐
│  Step 3: Runner가 Job 감지 & 실행                               │
└──────────────────────────────────────────────────────────────────┘

[서버 - Runner.Listener 프로세스]

  ① Long Polling으로 Job 확인
     Runner → GitHub: "Job 있어요?"
     GitHub → Runner: "네! deploy Job 있어요"

  ② Job 다운로드
     - Job 정보 (steps, environment 등)
     - GitHub으로부터 HTTPS로 다운로드

  ③ Worker 프로세스 시작
     $ Runner.Worker spawnclient

  ④ Step 실행 시작
     [서버 - ~/actions-runner/_work/blogsite/blogsite]


┌──────────────────────────────────────────────────────────────────┐
│  Step 4: Workflow Steps 실행                                     │
└──────────────────────────────────────────────────────────────────┘

[Step 1] Checkout
  ├─ actions/checkout@v4 실행
  ├─ Git clone (HTTPS 사용, Runner의 credential)
  ├─ ~/actions-runner/_work/blogsite/blogsite 에 코드 다운로드
  └─ Submodule 초기화 (themes/PaperMod)

[Step 2] Setup Hugo
  ├─ peaceiris/actions-hugo@v3 실행
  ├─ Hugo 바이너리 다운로드
  └─ PATH에 hugo 추가

[Step 3] Build (production)
  ├─ 환경변수 설정: HUGO_ENV=production
  ├─ $ hugo --minify
  ├─ public/ 디렉토리 생성
  │   ├─ index.html
  │   ├─ posts/my-post/index.html
  │   ├─ assets/
  │   └─ ...
  └─ 빌드된 파일 수 출력

[Step 4] Stamp deploy info
  ├─ deploy.txt 파일 생성
  └─ public/deploy.txt
      source=ci
      time=2025-10-08T10:47:06Z
      commit=a563cda
      run_id=18342309914

[Step 5] Deploy to nginx root
  ├─ $ sudo mkdir -p /var/www/blog
  ├─ $ sudo chown -R jimin:www-data /var/www/blog
  ├─ $ rsync -ah --delete public/ /var/www/blog/
  │    (public/ 내용을 /var/www/blog/로 복사)
  ├─ $ sudo nginx -t  (설정 테스트)
  └─ $ sudo systemctl reload nginx  (Nginx 재로드)


┌──────────────────────────────────────────────────────────────────┐
│  Step 5: 배포 완료                                               │
└──────────────────────────────────────────────────────────────────┘

[서버 - Nginx]

  /var/www/blog/
  ├─ index.html
  ├─ posts/
  │   ├─ my-post/
  │   │   └─ index.html  ← 새 글!
  │   └─ ...
  └─ deploy.txt

  Nginx가 /var/www/blog/를 서빙
  → https://blog.jiminhome.shop/posts/my-post/ 접근 가능!


┌──────────────────────────────────────────────────────────────────┐
│  Step 6: 결과 확인                                               │
└──────────────────────────────────────────────────────────────────┘

[사용자 브라우저]
  https://blog.jiminhome.shop/ 접속
  → 새 글이 목록에 나타남!

[개발자]
  GitHub Actions 탭에서 성공 확인
  ✓ deploy Job - Succeeded
```

---

## 4. 실전 예제

### 시나리오: 처음부터 Hugo 블로그 구축하기

#### Phase 1: 로컬 환경 설정

```bash
# 1. SSH 키 생성 (Git push 인증용)
ssh-keygen -t ed25519 -C "your@email.com"
cat ~/.ssh/id_ed25519.pub
# → GitHub Settings → SSH and GPG keys → New SSH key에 등록

# 2. Hugo 블로그 생성
hugo new site my-blog
cd my-blog
git init
git remote add origin git@github.com:username/my-blog.git

# 3. 테마 추가
git submodule add https://github.com/adityatelange/hugo-PaperMod.git themes/PaperMod

# 4. 설정 파일 작성
cat > config.toml <<EOF
baseURL = "https://blog.example.com/"
title = "My Blog"
theme = "PaperMod"
EOF

# 5. 첫 글 작성
hugo new posts/hello.md
vim content/posts/hello.md

# 6. Push
git add .
git commit -m "Initial commit"
git push -u origin main
```

#### Phase 2: 서버 설정 (Self-hosted Runner)

```bash
# 서버에 SSH 접속
ssh user@your-server.com

# 1. Runner 다운로드 & 설치
mkdir ~/actions-runner && cd ~/actions-runner
curl -o actions-runner-linux-x64-2.328.0.tar.gz -L \
  https://github.com/actions/runner/releases/download/v2.328.0/actions-runner-linux-x64-2.328.0.tar.gz
tar xzf actions-runner-linux-x64-2.328.0.tar.gz

# 2. Runner 등록
# GitHub Repo → Settings → Actions → Runners → New self-hosted runner
# Token을 복사해서 사용
./config.sh --url https://github.com/username/my-blog --token <YOUR_TOKEN>

# 입력 예시:
# Enter name of runner: [press Enter] (서버 호스트명 사용)
# Enter any additional labels: [press Enter]
# Enter name of work folder: [press Enter] (_work 사용)

# 3. systemd 서비스로 등록
sudo ./svc.sh install
sudo ./svc.sh start
sudo ./svc.sh status

# 4. Hugo 설치
sudo snap install hugo --channel=extended

# 5. Nginx 설치 & 설정
sudo apt install nginx
sudo mkdir -p /var/www/blog

sudo vim /etc/nginx/sites-enabled/blog
```

Nginx 설정 파일:
```nginx
server {
    listen 80;
    server_name blog.example.com;

    root /var/www/blog;
    index index.html;

    location / {
        try_files $uri $uri/ =404;
    }
}
```

```bash
# Nginx 테스트 & 재시작
sudo nginx -t
sudo systemctl restart nginx

# 6. sudo 권한 설정
sudo visudo -f /etc/sudoers.d/github-runner
```

sudoers 내용:
```
# GitHub Actions Runner
jimin ALL=(ALL) NOPASSWD: /bin/mkdir -p /var/www/blog
jimin ALL=(ALL) NOPASSWD: /usr/bin/chown -R jimin\:www-data /var/www/blog
jimin ALL=(ALL) NOPASSWD: /usr/sbin/nginx -t
jimin ALL=(ALL) NOPASSWD: /bin/systemctl reload nginx
```

#### Phase 3: Workflow 파일 작성

```bash
# 로컬로 돌아와서
cd ~/my-blog
mkdir -p .github/workflows
vim .github/workflows/deploy.yml
```

Workflow 파일 (`.github/workflows/deploy.yml`):
```yaml
name: Deploy Hugo Blog

on:
  push:
    branches: [ "main" ]
    paths:
      - "content/**"
      - "static/**"
      - "themes/**"
      - "config.*"

jobs:
  deploy:
    runs-on: [self-hosted, linux, x64]
    timeout-minutes: 15

    steps:
      - name: Checkout
        uses: actions/checkout@v4
        with:
          submodules: true

      - name: Setup Hugo
        uses: peaceiris/actions-hugo@v3
        with:
          hugo-version: 'latest'
          extended: true

      - name: Build
        env:
          HUGO_ENV: production
        run: |
          hugo --minify
          echo "Build complete!"

      - name: Deploy
        run: |
          sudo mkdir -p /var/www/blog
          sudo chown -R $USER:www-data /var/www/blog
          rsync -ah --delete public/ /var/www/blog/
          sudo nginx -t
          sudo systemctl reload nginx
          echo "Deploy complete!"
```

```bash
# Push
git add .github/workflows/deploy.yml
git commit -m "Add workflow"
git push
```

#### Phase 4: 테스트

```bash
# 글 작성
echo "---
title: Test Post
date: $(date -Iseconds)
---

Hello World!" > content/posts/test.md

# Push
git add content/posts/test.md
git commit -m "post: test"
git push

# 10-20초 후 확인
curl https://blog.example.com/posts/test/
```

---

## 5. FAQ

### Q1: SSH를 사용하는 곳과 사용하지 않는 곳?

| 위치 | SSH 사용? | 인증 방식 | 용도 |
|------|-----------|----------|------|
| **개발자 → GitHub** | ✅ YES | SSH Key (`id_ed25519`) | `git push`, `git pull` |
| **Runner → GitHub** | ❌ NO | HTTPS + OAuth Token | Job 가져오기, 결과 업로드 |
| **Workflow의 Checkout** | ❌ NO | HTTPS + GitHub Token | 코드 다운로드 |

### Q2: Runner가 코드를 어떻게 받아오나?

**답**: `actions/checkout@v4`가 HTTPS로 clone합니다.

```yaml
- name: Checkout
  uses: actions/checkout@v4
```

내부 동작:
```bash
# Runner가 실행하는 명령 (대략적)
git clone https://github.com/wlals2/my-hugo-blog.git \
  --branch main \
  --depth 1 \
  ~/actions-runner/_work/blogsite/blogsite
```

→ **GitHub Token 자동 사용** (Runner 등록 시 설정된 credential)

### Q3: 여러 서버에 배포하려면?

**현재 구조**:
```
Runner가 실행되는 서버 = Nginx가 실행되는 서버
→ 로컬 복사 (rsync로 같은 서버 내 복사)
```

**다른 서버에 배포하려면**:

#### 방법 1: SSH로 다른 서버에 배포

```yaml
- name: Deploy to remote server
  env:
    SSH_KEY: ${{ secrets.DEPLOY_SSH_KEY }}
  run: |
    # SSH 키 설정
    echo "$SSH_KEY" > /tmp/deploy_key
    chmod 600 /tmp/deploy_key

    # 원격 서버로 rsync
    rsync -avz --delete \
      -e "ssh -i /tmp/deploy_key -o StrictHostKeyChecking=no" \
      public/ user@remote-server:/var/www/blog/

    # 원격 서버에서 nginx reload
    ssh -i /tmp/deploy_key user@remote-server \
      "sudo systemctl reload nginx"
```

이 경우 **SSH 키 필요** (배포 서버 접근용):
```bash
# 서버에서
ssh-keygen -t ed25519 -f ~/.ssh/deploy_key
cat ~/.ssh/deploy_key.pub
# → 원격 서버의 ~/.ssh/authorized_keys에 추가

# GitHub Secrets에 등록
# Settings → Secrets → New secret
# Name: DEPLOY_SSH_KEY
# Value: ~/.ssh/deploy_key 내용 복사
```

#### 방법 2: 여러 Runner 사용

```yaml
jobs:
  deploy-server-1:
    runs-on: [self-hosted, server-1]
    steps:
      - name: Deploy
        run: rsync public/ /var/www/blog/

  deploy-server-2:
    runs-on: [self-hosted, server-2]
    steps:
      - name: Deploy
        run: rsync public/ /var/www/blog/
```

각 서버에 Runner 설치:
```bash
# server-1에서
./config.sh --labels server-1

# server-2에서
./config.sh --labels server-2
```

### Q4: GitHub-hosted runner vs Self-hosted runner?

| 항목 | GitHub-hosted | Self-hosted |
|------|---------------|-------------|
| **비용** | 무료 (2,000분/월) | 서버 비용 |
| **속도** | 느림 (네트워크) | 빠름 (로컬) |
| **관리** | GitHub가 관리 | 직접 관리 |
| **보안** | GitHub 책임 | 직접 책임 |
| **서버 접근** | 불가 | 가능 (`rsync`, `sudo` 등) |
| **SSH 배포** | 필요 (원격 배포) | 불필요 (로컬 배포) |

**언제 Self-hosted를 쓰나?**
- ✅ 서버에 직접 접근해야 할 때
- ✅ 빌드가 자주 실행될 때 (무료 시간 절약)
- ✅ 로컬 캐시/리소스 사용
- ✅ 특수한 환경 필요 (특정 GPU, DB 등)

### Q5: Nginx가 여러 개면?

**시나리오**: Load Balancer 뒤에 Nginx 3대

```
                    ┌─> Nginx Server 1
Load Balancer ──────┼─> Nginx Server 2
                    └─> Nginx Server 3
```

**해결책 1**: 공유 스토리지
```yaml
- name: Deploy to NFS
  run: |
    rsync -ah --delete public/ /mnt/nfs/blog/
```

모든 Nginx가 NFS 마운트:
```bash
# 각 Nginx 서버에서
sudo mount -t nfs nfs-server:/blog /var/www/blog
```

**해결책 2**: 순차 배포
```yaml
- name: Deploy
  run: |
    for server in server1 server2 server3; do
      rsync -avz public/ user@$server:/var/www/blog/
      ssh user@$server "sudo systemctl reload nginx"
    done
```

---

## 6. 핵심 정리

### ✅ Self-hosted Runner에 필요한 것

1. **Runner 등록** (한 번만)
   ```bash
   ./config.sh --url <REPO_URL> --token <TOKEN>
   ```

2. **systemd 서비스 등록** (한 번만)
   ```bash
   sudo ./svc.sh install && sudo ./svc.sh start
   ```

3. **sudo 권한 설정** (한 번만)
   ```bash
   sudo visudo -f /etc/sudoers.d/github-runner
   ```

4. **Workflow 파일** (한 번만)
   ```yaml
   runs-on: [self-hosted, linux, x64]
   ```

### ❌ Self-hosted Runner에 필요 없는 것

- ❌ SSH 키 (GitHub Actions 연결용)
- ❌ SSH 서버 설정 (sshd)
- ❌ 방화벽 Inbound 포트 열기
- ❌ GitHub Secrets (같은 서버 배포 시)

### 🔑 SSH 키가 필요한 경우

1. **Git push/pull** (개발자 → GitHub)
   ```bash
   git push origin main
   ```

2. **다른 서버로 배포** (Runner → 원격 서버)
   ```yaml
   rsync -e "ssh -i key" public/ user@remote:/path/
   ```

---

## 7. 다이어그램 정리

### 전체 구조

```
┌─────────────────────────────────────────────────────────────────┐
│                          개발자 PC                               │
│                                                                  │
│  1. 글 작성: content/posts/my-post.md                           │
│  2. Git Push (SSH 인증)                                         │
│     $ git push origin main                                      │
│     → SSH Key: ~/.ssh/id_ed25519                               │
└────────────────────┬────────────────────────────────────────────┘
                     │ SSH (22)
                     │
                     ↓
        ┌────────────────────────────┐
        │       GitHub.com           │
        │                            │
        │  - 코드 저장               │
        │  - Workflow 트리거         │
        │  - Job Queue 생성         │
        └────────┬───────────────────┘
                 │ HTTPS (443)
                 │ Long Polling
                 ↓
┌────────────────────────────────────────────────────────────────┐
│                         내 서버                                 │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │  Self-hosted Runner (systemd service)                    │ │
│  │                                                           │ │
│  │  1. Job 감지 (HTTPS Polling)                            │ │
│  │  2. Workflow 실행:                                       │ │
│  │     - Checkout (HTTPS로 코드 clone)                     │ │
│  │     - Hugo Build                                         │ │
│  │     - rsync → /var/www/blog/                           │ │
│  │     - nginx reload                                       │ │
│  └──────────────────────────────────────────────────────────┘ │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │  Nginx (Web Server)                                      │ │
│  │                                                           │ │
│  │  root /var/www/blog;                                     │ │
│  │  → HTML 파일 서빙                                       │ │
│  └──────────────────────────────────────────────────────────┘ │
└────────────────────┬────────────────────────────────────────────┘
                     │ HTTP/HTTPS (80/443)
                     │
                     ↓
            ┌────────────────┐
            │  인터넷 사용자  │
            │  (브라우저)     │
            └────────────────┘
```

---

**📌 요약**:
- **SSH 키**: Git push 인증용 (개발자 → GitHub)
- **Runner**: HTTPS로 GitHub과 통신 (SSH 불필요)
- **배포**: 같은 서버에서 로컬 복사 (SSH 불필요)
- **Nginx**: 정적 파일 서빙만 담당

이 구조에서는 **SSH 키는 Git 작업에만 사용**되고, **Self-hosted Runner와는 무관**합니다!
