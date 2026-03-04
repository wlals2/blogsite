---
title: "GitHub Actions 자동 배포 트러블 슈팅정리"
date: 2025-11-05T21:05:59+09:00
draft: false
categories: []
tags: ["github","ci/cd","blog","cloudflare","self-hosted-runner","git-actions","sudoers"]
description: "GitHub Actions 자동 배포 트러블 슈팅정리"
author: "늦찌민"
---

# GitHub Actions 자동 배포 트러블슈팅 정리

### 문제 상황
- GitHub Actions로 Hugo 블로그를 self-hosted runner에서 빌드하고 배포
- Actions 로그상으로는 성공하지만 실제 블로그에 새 글이 반영되지 않음
- 수동으로 rsync 실행 시에는 정상 작동


## 🔎 발견된 문제들
### 1. Cloudflare 캐시 문제 (의심했으나 주요 원인 아님)
**증상 :**
```bash
curl -I https://blog.jiminhome.shop/
# server: cloudflare
# cf-cache-status: DYNAMIC

```

**확인:**
- Cloudflare를 통해 접속하고 있어 캐시가 의심됨
- 하지만 캐시 퍼지 후에도 문제 지속

### 2. **실제 원인: GitHub Actions에서 sudo 비밀번호 요구**

**에러 메시지:**
```

sudo rsync -avh --delete public/ /var/www/blog/
sudo: a terminal is required to read the password; either use the -S option to read from standard input or configure an askpass helper
sudo: a password is required
Error: Process completed with exit code 1.

```
---
### 원인분석: 
- Self-hosted runner가 jimin 사용자로 실행됨
- 워크플로우에서 sudo 명령어 사용 시 비밀번호 요구
- GitHub Actions는 interactive terminal이 없어서 비밀번호 입력 불가능
- 따라서 배포 단계에서 실패

---
### 해결 과정
### Step 1: sudoers 설정 확인

```bash
sudo cat /etc/sudoers | grep jimin

```

### 문제점 발견:
- sudoers 파일에 여러 줄로 중복 설정되어 있었음 
- 개별 명령어마다 NOPASSWD 설정
---

### Step 2: sudoers.d에 통합 설정 추가

```bash
# /etc/sudoers.d/github-actions 파일 생성
sudo bash -c 'cat > /etc/sudoers.d/github-actions << EOF
# GitHub Actions runner를 위한 sudo 권한
jimin ALL=(ALL) NOPASSWD: ALL
EOF'

# 권한 설정
sudo chmod 0440 /etc/sudoers.d/github-actions

# 설정 검증
sudo visudo -c

```

### Step 3: 설정 테스트

```bash
# 비밀번호 없이 sudo 실행 테스트
sudo -n whoami
# 출력: root (성공)

```

### Step 4: GitHub Actions Runner 재시작

```bash
# Runner 서비스 재시작하여 새 권한 적용
sudo systemctl restart actions.runner.*

# 상태 확인
sudo systemctl status actions.runner.* --no-pager

```

### Step 5: 워크플로우 재실행

```bash
# 방법 1: GitHub UI에서 Re-run
# 저장소 → Actions 탭 → 해당 워크플로우 → Re-run all jobs

# 방법 2: 빈 커밋으로 트리거
git commit --allow-empty -m "test: sudo 권한 테스트"
git push origin main

```

### 최종 워크플로우 구조

```yaml
name: Deploy Hugo Blog (self-hosted)

jobs:
  deploy:
    runs-on: [self-hosted, linux, x64]
    
    steps:
      - name: Checkout
      - name: Setup Hugo
      - name: Build (production)
      - name: Stamp deploy info
      
      - name: Deploy to nginx root
        run: |
          sudo mkdir -p /var/www/blog
          sudo rsync -avh --delete public/ /var/www/blog/
          sudo chown -R www-data:www-data /var/www/blog
          sudo chmod -R 755 /var/www/blog
          sudo systemctl reload nginx

```

## 추가 권장 사항

### 1. Cloudflare 캐시 자동 퍼지 추가 (선택사항)

```yaml
- name: Purge Cloudflare Cache
  env:
    CLOUDFLARE_ZONE_ID: ${{ secrets.CLOUDFLARE_ZONE_ID }}
    CLOUDFLARE_API_TOKEN: ${{ secrets.CLOUDFLARE_API_TOKEN }}
  run: |
    curl -X POST "https://api.cloudflare.com/client/v4/zones/${CLOUDFLARE_ZONE_ID}/purge_cache" \
      -H "Authorization: Bearer ${CLOUDFLARE_API_TOKEN}" \
      -H "Content-Type: application/json" \
      --data '{"purge_everything":true}'

```

### 2. Hugo 미래 날짜 글 허용

```toml
# config.toml 또는 hugo.toml
buildFuture = true

```

### 3.배포 검증 단계 추가

```yaml
- name: Verify Deployment
  run: |
    echo "배포된 파일 확인:"
    cat /var/www/blog/deploy.txt
    
    curl -sI http://localhost/ -H "Host: blog.jiminhome.shop"

```
---
## 🎓 핵심 

### 1. Self-hosted runner는 sudo 권한 설정 필수
 - GitHub-hosted runner와 달리 로컬 권한 관리 필요
### 2. Interactive terminal 불가
- 비밀번호 입력이 필요한 명령어는 NOPASSWD 설정 필수
### 3. 보안 고려사항
- 프로덕션: 특정 명령어만 NOPASSWD 허용
- 로컬 개발: NOPASSWD: ALL 사용 가능
### 4. 설정 변경 후 재시작 필수
- sudoers 변경 후 runner 재시작해야 적용됨