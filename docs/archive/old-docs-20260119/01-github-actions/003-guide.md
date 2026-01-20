# GitHub Actions 완벽 가이드

> Hugo 블로그 자동 배포 시스템 (Self-Hosted Runner)

**작성일:** 2026-01-12
**프로젝트:** blog.jiminhome.shop

---

## 📚 목차

1. [GitHub Actions란 무엇인가?](#1-github-actions란-무엇인가)
2. [현재 배포 워크플로우 분석](#2-현재-배포-워크플로우-분석)
3. [Self-Hosted Runner 이해하기](#3-self-hosted-runner-이해하기)
4. [각 단계 상세 설명](#4-각-단계-상세-설명)
5. [트리거 조건 (on)](#5-트리거-조건-on)
6. [개선 가능한 옵션](#6-개선-가능한-옵션)
7. [트러블슈팅](#7-트러블슈팅)
8. [보안 고려사항](#8-보안-고려사항)

---

## 1. GitHub Actions란 무엇인가?

### 1.1 개념

**GitHub Actions**는 GitHub 저장소에서 발생하는 이벤트(push, PR 등)에 반응해서 자동으로 작업(빌드, 테스트, 배포)을 실행하는 CI/CD 도구입니다.

```
코드 Push → GitHub → Actions 트리거 → 빌드 → 배포 → 완료
```

### 1.2 왜 사용하는가?

**수동 배포의 문제:**
```bash
# 매번 이 과정을 반복해야 함
cd ~/blogsite
git pull
hugo --minify
sudo cp -r public/* /var/www/blog/
sudo systemctl reload nginx
```

**GitHub Actions 도입 후:**
```bash
git push  # 끝! 나머지는 자동으로 진행됨
```

### 1.3 핵심 개념

| 용어 | 설명 | 예시 |
|------|------|------|
| **Workflow** | 자동화 작업의 전체 흐름 | `.github/workflows/deploy.yml` |
| **Job** | 워크플로우 안의 작업 단위 | `build`, `test`, `deploy` |
| **Step** | Job 안의 개별 명령어 | `checkout`, `hugo build` |
| **Runner** | 실제로 작업을 실행하는 서버 | GitHub 제공 or Self-Hosted |
| **Trigger** | 워크플로우를 시작하는 이벤트 | `push`, `pull_request` |

---

## 2. 현재 배포 워크플로우 분석

### 2.1 전체 흐름도

```
┌─────────────────────────────────────────────────────────────┐
│                    GitHub Repository                         │
│                                                              │
│   git push main → content/** 파일 변경 감지                  │
└───────────────────────────┬─────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│              GitHub Actions (Trigger)                        │
│                                                              │
│   - on: push (main branch)                                  │
│   - paths: content/**, static/**, config.*                  │
└───────────────────────────┬─────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│          Self-Hosted Runner (홈 서버)                        │
│                                                              │
│   Step 1: Checkout (코드 받기)                               │
│   Step 2: Setup Hugo (Hugo 설치)                            │
│   Step 3: Build (hugo --minify)                             │
│   Step 4: Encrypt (private 컨텐츠 암호화)                    │
│   Step 5: Stamp (배포 정보 기록)                             │
│   Step 6: Deploy (rsync → /var/www/blog/)                   │
│   Step 7: Nginx Reload                                      │
│   Step 8: Verification (배포 검증)                           │
└───────────────────────────┬─────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                   Nginx Web Server                           │
│                                                              │
│   /var/www/blog/ → blog.jiminhome.shop                      │
│                                                              │
│   ✅ 사용자가 새 컨텐츠 확인 가능                             │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 파일 위치

```
~/blogsite/
├── .github/
│   └── workflows/
│       └── deploy.yml  ← 이 파일이 모든 자동화를 제어
├── content/
│   ├── about/
│   ├── posts/
│   └── ...
└── config.toml
```

### 2.3 현재 설정 요약

```yaml
name: Deploy Hugo Blog (self-hosted)

on:
  push:
    branches: [ "main" ]          # main 브랜치 push 시
    paths:                        # 특정 파일만 변경되면
      - "content/**"              # 컨텐츠 변경
      - "static/**"               # 정적 파일 변경
      - "themes/**"               # 테마 변경
      - "config.*"                # 설정 변경
  workflow_dispatch:              # 수동 실행 가능

jobs:
  deploy:
    runs-on: [self-hosted]        # 홈 서버에서 실행
    timeout-minutes: 15           # 15분 초과 시 실패
```

---

## 3. Self-Hosted Runner 이해하기

### 3.1 GitHub-Hosted vs Self-Hosted

| 항목 | GitHub-Hosted | Self-Hosted (현재 사용) |
|------|---------------|------------------------|
| **서버** | GitHub 제공 (클라우드) | 내 홈 서버 |
| **비용** | 무료 한도 초과 시 유료 | 무료 (서버 전기세만) |
| **성능** | 제한적 (2 CPU, 7GB RAM) | 내 서버 스펙대로 |
| **접근** | Public Internet만 | Private 네트워크 가능 |
| **배포** | 별도 서버로 전송 필요 | 로컬 `/var/www/blog/` 직접 복사 |
| **보안** | GitHub 관리 | 직접 관리 필요 |

### 3.2 Self-Hosted Runner 작동 원리

```
┌──────────────┐         ┌──────────────┐         ┌──────────────┐
│   GitHub     │         │  홈 서버     │         │    Nginx     │
│  Repository  │         │   Runner     │         │  Web Server  │
└──────┬───────┘         └──────┬───────┘         └──────┬───────┘
       │                        │                        │
       │ 1. Push Event         │                        │
       ├──────────────────────▶│                        │
       │                        │                        │
       │ 2. Job 할당            │                        │
       ├──────────────────────▶│                        │
       │                        │                        │
       │                        │ 3. 코드 Clone          │
       │◀──────────────────────┤                        │
       │                        │                        │
       │                        │ 4. Hugo Build          │
       │                        ├────────┐               │
       │                        │        │               │
       │                        │◀───────┘               │
       │                        │                        │
       │                        │ 5. 파일 복사           │
       │                        ├───────────────────────▶│
       │                        │                        │
       │ 6. 완료 보고           │                        │
       │◀──────────────────────┤                        │
       │                        │                        │
```

### 3.3 Runner 설치 위치 (참고)

```bash
# Runner는 홈 서버에 이미 설치되어 있음
~/actions-runner/
├── _work/              # 작업 디렉토리 (여기서 git clone됨)
│   └── my-hugo-blog/
│       └── my-hugo-blog/  # 실제 코드
├── run.sh              # Runner 실행 스크립트
└── config.sh           # Runner 설정
```

**확인 방법:**
```bash
# Runner 상태 확인
sudo systemctl status actions-runner

# Runner 로그 확인
journalctl -u actions-runner -f
```

---

## 4. 각 단계 상세 설명

### 4.1 Step 1: Checkout (코드 받기)

```yaml
- name: Checkout
  uses: actions/checkout@v4
  with:
    submodules: true
```

**무엇을 하는가?**
- GitHub 저장소의 코드를 Runner 작업 디렉토리로 clone

**왜 `submodules: true`?**
- Hugo 테마가 Git Submodule로 관리되기 때문
- 테마 없이 빌드하면 빈 페이지 생성됨

**실제 동작:**
```bash
# Actions가 자동으로 실행하는 명령어 (개념)
cd ~/actions-runner/_work/my-hugo-blog/my-hugo-blog
git clone --recurse-submodules https://github.com/wlals2/my-hugo-blog.git .
```

**트러블슈팅:**
- 테마가 안 보이면: `git submodule update --init --recursive`

---

### 4.2 Step 2: Setup Hugo

```yaml
- name: Setup Hugo (extended)
  uses: peaceiris/actions-hugo@v3
  with:
    hugo-version: 'latest'
    extended: true
```

**무엇을 하는가?**
- Hugo Extended 버전 설치 (SCSS 컴파일 가능)

**왜 Extended?**
| Feature | Hugo Standard | Hugo Extended |
|---------|--------------|---------------|
| SCSS/SASS | ❌ | ✅ |
| PostCSS | ❌ | ✅ |
| 크기 | 작음 | 큼 |
| 속도 | 동일 | 동일 |

**PaperMod 테마는 SCSS 사용 → Extended 필수!**

**확인 방법:**
```bash
# 로컬에서 Hugo 버전 확인
hugo version
# 예: hugo v0.121.0+extended linux/amd64
```

---

### 4.3 Step 3: Build

```yaml
- name: Build (production)
  env:
    HUGO_ENV: production
    PRIVATE_TOTP_SECRET: ${{ secrets.PRIVATE_TOTP_SECRET }}
    PRIVATE_AES_KEY: ${{ secrets.PRIVATE_AES_KEY }}
  run: |
    hugo --minify
    echo "html_count=$(find public -name '*.html' | wc -l)"
```

**무엇을 하는가?**
- Markdown → HTML 변환
- CSS/JS 압축 (--minify)

**`HUGO_ENV=production`의 효과:**
```toml
# config.toml에서 환경별 설정 가능
[params]
  env = "production"  # 구글 애널리틱스 활성화
  showShareButtons = true  # 프로덕션에서만 공유 버튼
```

**`--minify` 효과:**
- HTML: `<div class="container">` → `<div class=container>`
- CSS: 공백 제거, 줄바꿈 제거
- 파일 크기 20-30% 감소 → 로딩 속도 향상

**생성되는 파일:**
```
public/
├── index.html           # 홈페이지
├── about/
│   └── index.html       # About 페이지
├── posts/
│   └── my-post/
│       └── index.html   # 각 포스트
├── css/
│   └── main.min.css     # 압축된 CSS
└── js/
    └── main.min.js      # 압축된 JS
```

---

### 4.4 Step 4: Encrypt Private Content

```yaml
- name: Encrypt private content
  env:
    PRIVATE_AES_KEY: ${{ secrets.PRIVATE_AES_KEY }}
  run: |
    if [ -d "public/private" ]; then
      ./scripts/encrypt-private-content.sh
    fi
```

**무엇을 하는가?**
- `public/private/` 디렉토리의 HTML 파일을 AES-256으로 암호화
- 브라우저에서 TOTP(OTP) 인증 후에만 복호화

**왜 필요한가?**
- Private 카테고리 컨텐츠 보호 (개인 메모, 민감 정보)
- Public 저장소여도 컨텐츠는 암호화됨

**암호화 과정:**
```
public/private/note.html (평문)
    ↓ AES-256 암호화
public/private/note.html.enc (암호문)
    ↓ 브라우저에서 TOTP 입증
복호화 후 표시
```

---

### 4.5 Step 5: Stamp Deploy Info

```yaml
- name: Stamp deploy info
  run: |
    printf "source=ci\ntime=%s\ncommit=%s\nrun_id=%s\n" \
      "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" "${GITHUB_SHA::7}" "$GITHUB_RUN_ID" \
      > public/deploy.txt
```

**무엇을 하는가?**
- 배포 메타데이터를 `deploy.txt`에 기록

**생성되는 파일 예시:**
```
source=ci
time=2026-01-12T01:30:45Z
commit=795915c
run_id=1234567890
```

**왜 필요한가?**
- 배포 시각 확인 (`curl https://blog.jiminhome.shop/deploy.txt`)
- 어떤 커밋이 배포되었는지 추적
- GitHub Actions 로그 연결 (run_id)

**활용 예:**
```bash
# 배포 시각 확인
curl -s https://blog.jiminhome.shop/deploy.txt

# 배포된 커밋 확인
git log --oneline | grep 795915c
```

---

### 4.6 Step 6: Deploy to Nginx

```yaml
- name: Deploy to nginx root (local copy)
  run: |
    sudo mkdir -p /var/www/blog
    sudo rsync -avh --delete public/ /var/www/blog/
    sudo chown -R www-data:www-data /var/www/blog
    sudo chmod -R 755 /var/www/blog
    sudo nginx -t
    sudo systemctl reload nginx
```

**무엇을 하는가?**
1. `public/` → `/var/www/blog/` 복사
2. 권한 설정 (www-data:www-data)
3. Nginx 설정 검증 (`nginx -t`)
4. Nginx 재시작 (무중단)

**rsync 옵션 설명:**
| 옵션 | 설명 |
|------|------|
| `-a` | Archive mode (권한, 타임스탬프 유지) |
| `-v` | Verbose (진행 상황 출력) |
| `-h` | Human-readable (1024MB → 1GB) |
| `--delete` | 목적지에만 있는 파일 삭제 (동기화) |

**왜 `--delete`가 중요한가?**
```
시나리오: posts/old-post.md 삭제
--delete 없으면: /var/www/blog/posts/old-post/ 남아있음 → 삭제한 포스트 계속 접근 가능
--delete 있으면: /var/www/blog/posts/old-post/ 삭제됨 ✅
```

**`systemctl reload` vs `restart`:**
| 명령어 | 동작 | 다운타임 |
|--------|------|---------|
| `reload` | 설정만 다시 읽기 | ✅ 무중단 |
| `restart` | 프로세스 종료 후 재시작 | ❌ 1-2초 다운타임 |

---

### 4.7 Step 7: Post-deployment Verification

```yaml
- name: Post-deployment Verification
  run: |
    # 파일 존재 확인
    for file in index.html 404.html; do
      if [ -f "/var/www/blog/$file" ]; then
        echo "✅ $file exists"
      else
        echo "❌ $file NOT found"
        exit 1
      fi
    done

    # 실제 접속 테스트
    curl -sI https://blog.jiminhome.shop/
```

**무엇을 하는가?**
- 배포된 파일 검증
- Nginx 응답 확인
- 실제 도메인 접속 테스트

**왜 필요한가?**
- 파일은 복사되었지만 nginx 에러 → 검증으로 조기 발견
- GitHub Actions에서 즉시 실패 알림

---

## 5. 트리거 조건 (on)

### 5.1 현재 설정

```yaml
on:
  push:
    branches: [ "main" ]
    paths:
      - "content/**"
      - "static/**"
      - "themes/**"
      - "layouts/**"
      - "config.*"
      - "hugo.*"
  workflow_dispatch:
```

### 5.2 Push Trigger 상세

**`branches: [ "main" ]`**
- main 브랜치에 push할 때만 실행
- dev, feature 브랜치는 실행 안 됨

**`paths:`**
- 특정 파일만 변경되면 실행
- `.github/workflows/deploy.yml` 수정 시 실행 안 됨 (paths에 없음)

**예시:**
```bash
# ✅ 실행됨
git add content/posts/new-post.md
git commit -m "Add post"
git push origin main

# ❌ 실행 안 됨
git add README.md
git commit -m "Update README"
git push origin main

# ❌ 실행 안 됨
git add content/posts/new-post.md
git push origin dev  # dev 브랜치
```

### 5.3 workflow_dispatch

**무엇인가?**
- GitHub 웹 UI에서 수동으로 워크플로우 실행

**사용 방법:**
1. GitHub 저장소 → Actions 탭
2. "Deploy Hugo Blog" 선택
3. "Run workflow" 버튼 클릭
4. 브랜치 선택 → "Run workflow"

**언제 사용하는가?**
- 코드 변경 없이 강제 재배포
- 이전 커밋으로 롤백
- Cloudflare 캐시 삭제 후 재배포

---

## 6. 개선 가능한 옵션

### 6.1 Pull Request 빌드 테스트

**현재 문제:**
- PR 생성해도 빌드 테스트 안 됨
- merge 후에 에러 발견 → main 브랜치 망가짐

**개선안:**
```yaml
on:
  push:
    branches: [ "main" ]
  pull_request:  # 추가
    branches: [ "main" ]

jobs:
  test:
    if: github.event_name == 'pull_request'
    runs-on: [self-hosted]
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Build Test
        run: hugo --minify

      - name: Link Check
        run: |
          # 깨진 링크 검사
          npm install -g broken-link-checker
          blc http://localhost:1313 --recursive

  deploy:
    if: github.event_name == 'push' && github.ref == 'refs/heads/main'
    runs-on: [self-hosted]
    # ... 기존 배포 로직
```

**효과:**
- PR에서 빌드 에러 사전 발견
- main 브랜치 안정성 보장
- PR 리뷰 시 "All checks passed" 표시

---

### 6.2 Cloudflare Cache 자동 Purge (★ 가장 중요!)

**현재 문제:**
- 배포 완료해도 Cloudflare CDN에 이전 파일 캐시됨
- 수동으로 Cloudflare 대시보드 접속 → Purge 클릭 필요
- **이게 테이블 안 보이는 진짜 원인!**

**해결책:**
```yaml
- name: Purge Cloudflare Cache
  if: success()
  env:
    CF_ZONE_ID: ${{ secrets.CLOUDFLARE_ZONE_ID }}
    CF_TOKEN: ${{ secrets.CLOUDFLARE_API_TOKEN }}
  run: |
    echo "🔥 Purging Cloudflare cache..."

    RESPONSE=$(curl -X POST \
      "https://api.cloudflare.com/client/v4/zones/$CF_ZONE_ID/purge_cache" \
      -H "Authorization: Bearer $CF_TOKEN" \
      -H "Content-Type: application/json" \
      --data '{"purge_everything":true}' \
      -s)

    SUCCESS=$(echo $RESPONSE | jq -r '.success')

    if [ "$SUCCESS" = "true" ]; then
      echo "✅ Cloudflare cache purged successfully"
    else
      echo "❌ Failed to purge cache"
      echo $RESPONSE | jq
      exit 1
    fi
```

**필요한 Secret 설정:**

1. **Cloudflare API Token 발급:**
   - Cloudflare 대시보드 → My Profile → API Tokens
   - "Create Token" → "Edit zone DNS" 템플릿
   - Permissions: `Zone - Cache Purge - Purge`
   - Zone Resources: `Include - Specific zone - blog.jiminhome.shop`

2. **Zone ID 확인:**
   - Cloudflare 대시보드 → 사이트 선택
   - 오른쪽 사이드바에 "Zone ID" 표시

3. **GitHub Secrets 등록:**
   - GitHub 저장소 → Settings → Secrets and variables → Actions
   - "New repository secret" 클릭
   - `CLOUDFLARE_ZONE_ID`: (복사한 Zone ID)
   - `CLOUDFLARE_API_TOKEN`: (생성한 API Token)

**효과:**
- 배포 후 즉시 캐시 삭제 → 변경사항 즉시 반영 ✅
- 수동 작업 불필요

---

### 6.3 Hugo 캐시 추가 (빌드 속도 개선)

**현재 상황:**
- 매번 Hugo 처음부터 빌드 → 느림

**개선안:**
```yaml
- name: Cache Hugo resources
  uses: actions/cache@v4
  with:
    path: |
      resources/_gen
      .hugo_build.lock
    key: ${{ runner.os }}-hugo-${{ hashFiles('config.toml') }}
    restore-keys: |
      ${{ runner.os }}-hugo-
```

**어떻게 작동하는가?**
1. 첫 빌드: `resources/_gen/` 생성 (이미지 최적화, CSS 컴파일)
2. 캐시 저장: GitHub Actions 캐시에 저장
3. 다음 빌드: 캐시 복원 → 이미 처리된 리소스 재사용

**효과:**
- 빌드 시간 30-50% 단축 (4분 → 2분)
- 비용 절감 (Runner 사용 시간 감소)

---

### 6.4 Slack/Discord 알림

**현재 문제:**
- 배포 성공/실패를 GitHub에 들어가야 확인 가능

**개선안 (Slack):**
```yaml
- name: Slack Notification
  if: always()  # 성공/실패 모두
  uses: 8398a7/action-slack@v3
  with:
    status: ${{ job.status }}
    text: |
      배포 ${{ job.status }}
      커밋: ${{ github.event.head_commit.message }}
      작성자: ${{ github.actor }}
    webhook_url: ${{ secrets.SLACK_WEBHOOK_URL }}
    username: "GitHub Actions Bot"
    icon_emoji: ":rocket:"
```

**개선안 (Discord):**
```yaml
- name: Discord Notification
  if: always()
  run: |
    STATUS_COLOR=${{ job.status == 'success' && '3066993' || '15158332' }}
    STATUS_TEXT=${{ job.status == 'success' && '✅ 배포 성공' || '❌ 배포 실패' }}

    curl -X POST ${{ secrets.DISCORD_WEBHOOK_URL }} \
      -H "Content-Type: application/json" \
      -d '{
        "embeds": [{
          "title": "'"$STATUS_TEXT"'",
          "description": "'"${{ github.event.head_commit.message }}"'",
          "color": '"$STATUS_COLOR"',
          "fields": [
            {"name": "커밋", "value": "'"${GITHUB_SHA::7}"'", "inline": true},
            {"name": "작성자", "value": "'"${{ github.actor }}"'", "inline": true}
          ],
          "timestamp": "'"$(date -u +%Y-%m-%dT%H:%M:%SZ)"'"
        }]
      }'
```

**효과:**
- 모바일로 실시간 배포 상태 확인
- 배포 실패 시 즉시 대응

---

### 6.5 롤백 기능

**현재 문제:**
- 배포 실패 시 사이트 다운 → 수동으로 복구해야 함

**개선안:**
```yaml
- name: Backup current version
  id: backup
  run: |
    if [ -d /var/www/blog ]; then
      BACKUP_DIR="/var/www/blog.backup.$(date +%s)"
      sudo cp -r /var/www/blog "$BACKUP_DIR"
      echo "backup_dir=$BACKUP_DIR" >> $GITHUB_OUTPUT
      echo "✅ Backup created: $BACKUP_DIR"
    fi

- name: Deploy
  id: deploy
  run: |
    sudo rsync -avh --delete public/ /var/www/blog/
    sudo systemctl reload nginx

- name: Rollback on failure
  if: failure()
  run: |
    echo "❌ 배포 실패! 이전 버전으로 롤백..."

    BACKUP_DIR="${{ steps.backup.outputs.backup_dir }}"

    if [ -n "$BACKUP_DIR" ] && [ -d "$BACKUP_DIR" ]; then
      sudo rm -rf /var/www/blog
      sudo cp -r "$BACKUP_DIR" /var/www/blog
      sudo systemctl reload nginx
      echo "✅ 롤백 완료"
    else
      echo "❌ 백업 없음 - 수동 복구 필요"
      exit 1
    fi
```

**효과:**
- 배포 실패 시 자동 롤백 → 사이트 다운타임 최소화
- 이전 버전으로 즉시 복구

---

## 7. 트러블슈팅

### 7.1 워크플로우가 실행되지 않음

**증상:**
- git push했는데 Actions 탭에 아무것도 안 보임

**원인 1: paths 필터**
```bash
# 이런 파일들은 워크플로우 트리거 안 됨
README.md
LICENSE
.gitignore
scripts/something.sh
```

**해결:**
```yaml
# deploy.yml에 paths 추가
on:
  push:
    paths:
      - "scripts/**"  # 추가
```

**원인 2: 브랜치 불일치**
```bash
# dev 브랜치에 push
git push origin dev  # ❌ main이 아니라서 실행 안 됨
```

**해결:**
```bash
# main 브랜치로 merge 후 push
git checkout main
git merge dev
git push origin main
```

---

### 7.2 Runner가 오프라인

**증상:**
- Actions 탭에서 "Waiting for a runner..."

**확인:**
```bash
# Runner 상태 확인
sudo systemctl status actions-runner

# 로그 확인
journalctl -u actions-runner -n 50
```

**해결:**
```bash
# Runner 재시작
sudo systemctl restart actions-runner
```

---

### 7.3 Hugo 빌드 실패

**증상:**
- "Error: Unable to locate config file"

**원인:**
- Submodule (테마) 체크아웃 안 됨

**해결:**
```yaml
- name: Checkout
  uses: actions/checkout@v4
  with:
    submodules: true  # 이 줄 확인
```

---

### 7.4 Nginx 403 Forbidden

**증상:**
- 배포 완료했는데 403 에러

**원인:**
- 파일 권한 문제

**확인:**
```bash
ls -la /var/www/blog/
# drwxr-xr-x www-data www-data ...  ← 정상
# drwx------ runner   runner   ...  ← 문제
```

**해결:**
```bash
sudo chown -R www-data:www-data /var/www/blog
sudo chmod -R 755 /var/www/blog
```

---

### 7.5 배포는 성공했는데 변경사항이 안 보임

**원인: Cloudflare CDN 캐시**

**확인:**
```bash
# Response Header 확인
curl -I https://blog.jiminhome.shop/

# cf-cache-status: HIT  ← 캐시에서 서빙됨
# cf-cache-status: MISS ← Origin에서 가져옴
```

**해결:**
1. Cloudflare 대시보드 → Caching → Purge Everything
2. 또는 개선안 6.2 적용 (자동 Purge)

---

## 8. 보안 고려사항

### 8.1 Secrets 관리

**절대 하지 말 것:**
```yaml
# ❌ 나쁜 예
env:
  API_KEY: "abc123secret"  # 코드에 직접 노출
```

**올바른 방법:**
```yaml
# ✅ 좋은 예
env:
  API_KEY: ${{ secrets.API_KEY }}
```

**Secret 등록 방법:**
1. GitHub 저장소 → Settings → Secrets and variables → Actions
2. "New repository secret" 클릭
3. Name: `API_KEY`, Value: `abc123secret`

---

### 8.2 Self-Hosted Runner 보안

**위험:**
- Runner가 sudo 권한 가짐 → 악의적 PR로 서버 장악 가능

**대책:**

1. **Public 저장소는 주의:**
```yaml
# .github/workflows/deploy.yml
jobs:
  deploy:
    # Only run on pushes to main from trusted users
    if: |
      github.event_name == 'push' &&
      github.actor == 'wlals2'  # 본인만
```

2. **Runner 격리:**
```bash
# Docker 컨테이너 안에서 Runner 실행 (권장)
# 또는 별도 VM에서 실행
```

3. **최소 권한 원칙:**
```bash
# /etc/sudoers.d/actions-runner
runner ALL=(ALL) NOPASSWD: /usr/bin/rsync, /usr/bin/systemctl reload nginx
# rsync와 nginx reload만 sudo 허용
```

---

### 8.3 Private Content 암호화

**현재 구현:**
- AES-256 암호화
- TOTP (OTP) 인증

**보안 체크리스트:**
- [ ] `PRIVATE_AES_KEY`를 GitHub Secrets에 저장
- [ ] `PRIVATE_TOTP_SECRET`을 GitHub Secrets에 저장
- [ ] 암호화 스크립트 권한 확인 (`chmod 600`)
- [ ] `.env` 파일을 `.gitignore`에 추가

---

## 9. 참고 자료

### 9.1 공식 문서

- [GitHub Actions 공식 문서](https://docs.github.com/en/actions)
- [Hugo 공식 문서](https://gohugo.io/documentation/)
- [Cloudflare API 문서](https://developers.cloudflare.com/api/)

### 9.2 유용한 Actions

| Action | 용도 | URL |
|--------|------|-----|
| `actions/checkout` | 코드 체크아웃 | https://github.com/actions/checkout |
| `peaceiris/actions-hugo` | Hugo 설치 | https://github.com/peaceiris/actions-hugo |
| `actions/cache` | 빌드 캐시 | https://github.com/actions/cache |
| `8398a7/action-slack` | Slack 알림 | https://github.com/8398a7/action-slack |

### 9.3 관련 파일

```
~/blogsite/
├── .github/workflows/deploy.yml  # 이 가이드의 주제
├── scripts/
│   └── encrypt-private-content.sh
├── public/
│   └── deploy.txt                # 배포 정보
└── /var/www/blog/                # 최종 배포 경로
```

---

## 10. 요약

### 10.1 핵심 개념 정리

```
┌────────────────────────────────────────────────────┐
│  GitHub Actions = Git Push → 자동 빌드 → 자동 배포   │
└────────────────────────────────────────────────────┘

핵심 구성 요소:
1. Trigger (on): 언제 실행되는가?
2. Runner: 어디서 실행되는가?
3. Jobs/Steps: 무엇을 실행하는가?
4. Secrets: 민감 정보 어떻게 관리하는가?
```

### 10.2 배포 프로세스

```
git push main
    ↓
GitHub Actions 트리거
    ↓
Self-Hosted Runner 시작
    ↓
1. 코드 받기 (Checkout)
2. Hugo 설치 (Setup)
3. 빌드 (hugo --minify)
4. 암호화 (Private Content)
5. 배포 정보 기록 (deploy.txt)
6. 파일 복사 (rsync)
7. Nginx 재시작
8. 검증
    ↓
배포 완료! ✅
```

### 10.3 다음 단계

**우선순위 높음:**
1. ✅ **Cloudflare Cache 자동 Purge** (가장 중요!)
2. ✅ Pull Request 빌드 테스트
3. ✅ Slack/Discord 알림

**우선순위 중간:**
4. Hugo 캐시 추가
5. 롤백 기능

**우선순위 낮음:**
6. 배포 메트릭 수집
7. Preview 환경

---

**작성자:** Jimin
**마지막 업데이트:** 2026-01-12
**프로젝트:** blog.jiminhome.shop
**GitHub:** https://github.com/wlals2/my-hugo-blog
