# Hugo 블로그 자동배포 트러블슈팅 학습 정리

## 📋 목차
1. [문제 상황](#1-문제-상황)
2. [진단 과정](#2-진단-과정)
3. [해결 방법](#3-해결-방법)
4. [핵심 개념](#4-핵심-개념)
5. [학습 포인트](#5-학습-포인트)
6. [체크리스트](#6-체크리스트)

---

## 1. 문제 상황

### 증상
```bash
git add .
git commit -m "post: 새 글"
git push origin main
```
- Push는 성공
- **블로그에 자동 배포가 안 됨**
- 수동 배포(`./deploy.sh`)는 정상 작동

### 환경
- OS: Ubuntu 22.04
- 블로그: Hugo + PaperMod 테마
- CI/CD: GitHub Actions (Self-hosted Runner)
- 웹서버: Nginx
- 배포 위치: `/var/www/blog`

---

## 2. 진단 과정

### Step 1: Runner 상태 확인

```bash
# Runner 프로세스 확인
ps aux | grep "Runner.Listener" | grep -v grep
```

**결과**: 아무것도 출력되지 않음 → **Runner가 중지됨**

```bash
# Runner 로그 확인
tail ~/actions-runner/_diag/Runner_*.log
```

```
[2025-10-08 10:32:47Z INFO Runner] Received Ctrl-C signal, stop Runner.Listener
[2025-10-08 10:32:47Z INFO HostContext] Runner will be shutdown for UserCancelled
```

**원인 1**: Runner가 수동으로 중지되어 있고, 재시작하지 않음

---

### Step 2: Runner 재시작 후 테스트

```bash
cd ~/actions-runner
nohup ./run.sh &
```

테스트 파일 수정 & Push:
```bash
echo "테스트" >> content/_test.md
git add content/_test.md
git commit -m "test: auto-deploy"
git push
```

**결과**: 워크플로우 실행됨, 하지만 **실패**

---

### Step 3: 워크플로우 실패 원인 분석

```bash
# Worker 로그에서 실패 원인 찾기
grep "result.*Failed" ~/actions-runner/_diag/Worker_*.log -B 5
```

```
[2025-10-08 10:35:18Z INFO ProcessInvokerWrapper] Finished process 24128 with exit code 1
[2025-10-08 10:35:18Z INFO StepsRunner] Step result: Failed
```

**"Deploy to nginx root" 단계에서 실패**

---

### Step 4: sudo 권한 확인

워크플로우에서 실행하는 명령어:
```yaml
- name: Deploy to nginx root (local copy)
  run: |
    sudo mkdir -p /var/www/blog
    sudo chown -R jimin:www-data /var/www/blog
    rsync -ah --delete public/ /var/www/blog/
    sudo nginx -t
    sudo systemctl reload nginx
```

현재 sudo 설정 확인:
```bash
sudo -l | grep NOPASSWD
```

```
(ALL) NOPASSWD: /bin/systemctl reload nginx
```

**원인 2**:
- nginx reload만 NOPASSWD로 설정됨
- 나머지 명령어(`mkdir`, `chown`, `nginx -t`)는 비밀번호 필요
- GitHub Actions에서는 비밀번호 입력 불가 → 실패

---

## 3. 해결 방법

### 해결 1: Runner를 systemd 서비스로 등록

**문제**: 수동 실행 시 터미널 종료하면 Runner도 종료됨

**해결**:
```bash
cd ~/actions-runner
sudo ./svc.sh install
sudo ./svc.sh start
```

**효과**:
- ✅ 부팅 시 자동 시작
- ✅ 백그라운드에서 항상 실행
- ✅ 크래시 시 자동 재시작
- ✅ systemctl로 관리 가능

**확인**:
```bash
systemctl status actions.runner.wlals2-my-hugo-blog.jimin-AB350M-Gaming-3.service
```

---

### 해결 2: sudo 권한 설정

**문제**: 워크플로우에서 실행하는 명령어에 비밀번호 필요

**해결**:
```bash
sudo visudo -f /etc/sudoers.d/github-runner
```

다음 내용 추가:
```
# GitHub Actions Runner - Hugo blog deployment
jimin ALL=(ALL) NOPASSWD: /bin/mkdir -p /var/www/blog
jimin ALL=(ALL) NOPASSWD: /usr/bin/chown -R jimin\:www-data /var/www/blog
jimin ALL=(ALL) NOPASSWD: /usr/sbin/nginx -t
jimin ALL=(ALL) NOPASSWD: /bin/systemctl reload nginx
```

**보안 포인트**:
- ❌ `jimin ALL=(ALL) NOPASSWD: ALL` (모든 권한 - 위험!)
- ✅ 필요한 명령어만 정확히 명시 (최소 권한 원칙)

**확인**:
```bash
sudo -l | grep NOPASSWD
```

---

### 해결 3: 최종 테스트

```bash
# 1. 테스트 파일 수정
echo "최종 테스트" >> content/_test.md

# 2. Push
git add content/_test.md
git commit -m "test: final auto-deploy test"
git push

# 3. 워크플로우 확인 (10초 후)
grep "Job result" ~/actions-runner/_diag/Worker_*.log | tail -1
```

**결과**:
```
[2025-10-08 10:47:07Z INFO JobRunner] Job result after all job steps finish: Succeeded
```

✅ **성공!**

**배포 확인**:
```bash
cat /var/www/blog/deploy.txt
```
```
source=ci
time=2025-10-08T10:47:06Z
commit=203977b
run_id=18342156050
```

---

## 4. 핵심 개념

### 4.1 Self-hosted Runner란?

**정의**: GitHub Actions 워크플로우를 **자신의 서버에서** 실행하는 실행 환경

**장점**:
- 무료 (GitHub-hosted runner는 시간 제한)
- 빠른 속도 (로컬 네트워크)
- 직접 서버 접근 가능 (rsync, systemctl 등)

**단점**:
- 서버 관리 필요
- 보안 책임 (sudo 권한, 프로세스 관리)
- 네트워크 안정성 필요

---

### 4.2 GitHub Actions 워크플로우 구조

```yaml
name: Deploy Hugo Blog (self-hosted)

on:                           # 트리거 조건
  push:
    branches: [ "main" ]
    paths:                    # 특정 파일 변경 시만 실행
      - "content/**"
      - "static/**"

jobs:
  deploy:
    runs-on: [self-hosted, linux, x64]  # Runner 지정

    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Build
        run: hugo --minify

      - name: Deploy
        run: |
          sudo rsync -ah --delete public/ /var/www/blog/
          sudo systemctl reload nginx
```

**중요 포인트**:
- `paths` 필터: 지정된 파일 변경 시에만 트리거
- `runs-on: [self-hosted]`: Self-hosted Runner 사용
- `sudo` 명령어: NOPASSWD 설정 필요

---

### 4.3 systemd 서비스 관리

**서비스 등록**:
```bash
sudo ./svc.sh install    # 서비스 생성
sudo ./svc.sh start      # 서비스 시작
sudo ./svc.sh status     # 상태 확인
sudo ./svc.sh stop       # 서비스 중지
sudo ./svc.sh uninstall  # 서비스 제거
```

**systemctl 명령어**:
```bash
systemctl status actions.runner.*     # 상태 확인
systemctl restart actions.runner.*    # 재시작
systemctl enable actions.runner.*     # 부팅 시 자동 시작 활성화
systemctl disable actions.runner.*    # 부팅 시 자동 시작 비활성화
journalctl -u actions.runner.* -f     # 실시간 로그
```

---

### 4.4 Linux sudo 권한 관리

**sudoers 파일 구조**:
```
사용자  호스트=(실행사용자) NOPASSWD: 명령어
jimin   ALL  =(ALL)        NOPASSWD: /bin/systemctl reload nginx
```

**안전한 편집 방법**:
```bash
# visudo 사용 (문법 검사 자동)
sudo visudo -f /etc/sudoers.d/github-runner

# 직접 편집 (위험! 문법 오류 시 sudo 전체 불가)
sudo vim /etc/sudoers.d/github-runner  # ❌
```

**디렉토리 구조**:
- `/etc/sudoers` - 메인 설정 파일 (수정 비추천)
- `/etc/sudoers.d/*` - 추가 설정 파일 (권장)

---

## 5. 학습 포인트

### 5.1 문제 해결 프로세스

```
1. 증상 확인
   ↓
2. 로그 확인 (어디서 멈췄나?)
   ↓
3. 프로세스 상태 확인 (실행 중인가?)
   ↓
4. 권한 확인 (실행 가능한가?)
   ↓
5. 테스트 & 검증
```

### 5.2 디버깅 기술

#### (1) 프로세스 확인
```bash
# 특정 프로세스 찾기
ps aux | grep Runner

# 포트 사용 중인 프로세스
lsof -i :8080

# 프로세스 트리
pstree -p
```

#### (2) 로그 분석
```bash
# 실시간 로그
tail -f ~/actions-runner/_diag/Runner_*.log

# 특정 패턴 검색
grep -i "error\|fail" ~/actions-runner/_diag/Worker_*.log

# 시간대별 로그
journalctl --since "1 hour ago"
```

#### (3) 권한 디버깅
```bash
# 현재 사용자 권한
id

# sudo 권한 확인
sudo -l

# 파일 권한 확인
ls -la /var/www/blog

# 실행 테스트
sudo -u runner-user whoami
```

---

### 5.3 CI/CD 베스트 프랙티스

#### ✅ DO
1. **Runner를 서비스로 등록**
   - systemd 사용
   - 자동 재시작 설정
   - 로그 관리

2. **최소 권한 원칙**
   - 필요한 명령어만 NOPASSWD
   - 특정 경로만 허용

3. **배포 검증**
   - deploy.txt로 배포 시간 기록
   - 배포 후 nginx 테스트

4. **트리거 조건 명확히**
   - `paths` 필터로 불필요한 실행 방지
   - `workflow_dispatch`로 수동 실행 가능

#### ❌ DON'T
1. **Runner를 nohup으로만 실행**
   - 터미널 종료 시 문제
   - 재시작 관리 어려움

2. **sudo ALL 권한**
   ```bash
   # ❌ 위험!
   runner ALL=(ALL) NOPASSWD: ALL
   ```

3. **로그 무시**
   - 실패 원인을 로그에서 찾기
   - 추측보다 로그 확인

4. **테스트 없이 배포**
   - 로컬 테스트 먼저
   - 단계별 검증

---

## 6. 체크리스트

### 🔍 자동배포 안 될 때 점검 항목

#### Level 1: Runner 상태
- [ ] Runner 프로세스 실행 중인가?
  ```bash
  ps aux | grep Runner.Listener
  ```
- [ ] systemd 서비스 활성화되었나?
  ```bash
  systemctl status actions.runner.*
  ```
- [ ] GitHub에서 Runner 연결 상태는?
  - Repository → Settings → Actions → Runners

#### Level 2: 워크플로우
- [ ] 워크플로우가 트리거되었나?
  ```bash
  grep "Job request.*received" ~/actions-runner/_diag/Runner_*.log
  ```
- [ ] paths 필터 조건에 맞는 파일을 수정했나?
  ```yaml
  paths:
    - "content/**"  # content 폴더 내 파일만 트리거
  ```

#### Level 3: 실행 결과
- [ ] Job이 성공했나?
  ```bash
  grep "Job result" ~/actions-runner/_diag/Worker_*.log
  ```
- [ ] 실패 시 어떤 step에서?
  ```bash
  grep -i "fail\|error" ~/actions-runner/_diag/Worker_*.log
  ```

#### Level 4: 권한
- [ ] sudo 권한이 충분한가?
  ```bash
  sudo -l | grep NOPASSWD
  ```
- [ ] 배포 디렉토리 권한은?
  ```bash
  ls -la /var/www/blog
  ```

#### Level 5: 배포 검증
- [ ] deploy.txt가 업데이트되었나?
  ```bash
  cat /var/www/blog/deploy.txt
  ```
- [ ] 실제 사이트에 반영되었나?
  ```bash
  curl -s https://blog.jiminhome.shop/ | grep "제목"
  ```

---

## 7. 자주 사용하는 명령어 모음

### Runner 관리
```bash
# 서비스 상태
systemctl status actions.runner.*

# 서비스 재시작
sudo systemctl restart actions.runner.*

# 실시간 로그
journalctl -u actions.runner.* -f

# 최근 로그 (50줄)
journalctl -u actions.runner.* -n 50
```

### 워크플로우 디버깅
```bash
# 최근 Job 확인
grep "Job request.*received" ~/actions-runner/_diag/Runner_*.log | tail -5

# Job 결과 확인
grep "Job result" ~/actions-runner/_diag/Worker_*.log | tail -3

# 에러 찾기
grep -i "error\|fail" ~/actions-runner/_diag/Worker_*.log | tail -20
```

### 배포 확인
```bash
# 배포 시간 확인
cat /var/www/blog/deploy.txt

# 최근 배포된 파일
ls -lrt /var/www/blog/posts/ | tail -5

# nginx 설정 테스트
sudo nginx -t

# nginx 리로드
sudo systemctl reload nginx
```

### Git 작업
```bash
# 상태 확인
git status

# 최근 커밋
git log --oneline -5

# 변경된 파일 확인
git diff --name-only

# Push & 자동배포
git add .
git commit -m "post: 제목"
git push origin main
```

---

## 8. 문제 해결 플로우차트

```
자동배포 안 됨
    ↓
[Runner 실행 중?]
    ├─ NO → systemctl start actions.runner.*
    ↓
    YES
    ↓
[워크플로우 트리거됨?]
    ├─ NO → paths 필터 확인 (content/** 수정했나?)
    ↓
    YES
    ↓
[Job 성공?]
    ├─ NO → Worker 로그 확인
    │        ↓
    │     [sudo 권한 에러?]
    │        ├─ YES → sudoers 수정
    │        └─ NO → 다른 에러 분석
    ↓
    YES
    ↓
[deploy.txt 업데이트됨?]
    ├─ NO → rsync 명령 확인
    ↓
    YES
    ↓
[사이트에 반영됨?]
    ├─ NO → nginx reload 확인
    └─ YES → 성공! 🎉
```

---

## 9. 추가 학습 자료

### 공식 문서
- [GitHub Actions - Self-hosted runners](https://docs.github.com/en/actions/hosting-your-own-runners)
- [Hugo Documentation](https://gohugo.io/documentation/)
- [Nginx 공식 문서](https://nginx.org/en/docs/)
- [systemd 가이드](https://www.freedesktop.org/software/systemd/man/)

### 관련 기술
- **CI/CD**: Jenkins, GitLab CI, CircleCI
- **정적 사이트 생성기**: Jekyll, Gatsby, Next.js
- **웹서버**: Apache, Caddy
- **컨테이너**: Docker, Kubernetes

### 실습 과제
1. [ ] workflow_dispatch 트리거 추가
2. [ ] Slack/Discord 알림 추가
3. [ ] 배포 전 테스트 단계 추가
4. [ ] Rollback 기능 구현
5. [ ] 모니터링 대시보드 구축

---

## 10. 마무리

### 이번에 배운 것
1. **Self-hosted Runner 관리**
   - systemd 서비스 등록
   - 로그 분석 방법

2. **Linux 권한 관리**
   - sudo NOPASSWD 설정
   - 최소 권한 원칙

3. **디버깅 스킬**
   - 로그 기반 문제 해결
   - 체계적인 점검 프로세스

4. **CI/CD 실전 경험**
   - 워크플로우 설계
   - 트리거 조건 설정
   - 배포 자동화

### 다음 단계
1. **모니터링 추가**: 배포 실패 시 알림
2. **성능 최적화**: 빌드 캐시, 병렬 처리
3. **보안 강화**: Secret 관리, 네트워크 격리
4. **문서화**: 팀원과 공유할 수 있는 가이드 작성

---

**💡 핵심 교훈**:
> "자동화는 설정으로 끝나지 않는다. 지속적인 모니터링과 개선이 필요하다."

**🎯 실전 팁**:
> "문제가 생기면 추측하지 말고, 로그를 먼저 확인하라."
