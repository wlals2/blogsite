---
title: "Hugo 블로그 자동배포 문제 해결: GitHub Actions Runner 편"
date: 2025-10-08T15:00:00+09:00
draft: false
tags: ["Hugo", "CI/CD", "GitHub Actions", "Self-hosted Runner", "Troubleshooting"]
categories: ["Infra", "DevOps", "Blog"]
series: ["내 기술 블로그 구축기"]
weight: 98
---

# Hugo 블로그 자동배포가 안 될 때: Self-hosted Runner 트러블슈팅

이전 글에서 Hugo 블로그를 구축하고 GitHub Actions로 자동배포를 설정했지만, 실제로는 **자동배포가 제대로 작동하지 않았습니다**. 이번 글에서는 그 원인을 찾고 해결한 과정을 기록합니다.

---

## 🔍 증상: Push해도 배포가 안 됨

```bash
git add .
git commit -m "post: 새 글 작성"
git push
```

분명히 push는 성공하는데, 블로그에는 반영되지 않았습니다. 수동으로 `./deploy.sh`를 실행하면 정상 작동했습니다.

---

## 1️⃣ 문제 진단: Runner 상태 확인

### (1) Runner가 실행 중인지 확인

```bash
ps aux | grep "Runner.Listener" | grep -v grep
```

**결과**: 아무것도 출력되지 않음 → **Runner가 중지된 상태**였습니다.

### (2) 로그 확인

```bash
tail ~/actions-runner/_diag/Runner_*.log
```

```
[2025-10-08 10:32:47Z INFO Runner] Received Ctrl-C signal, stop Runner.Listener
[2025-10-08 10:32:47Z INFO HostContext] Runner will be shutdown for UserCancelled
```

Runner가 **수동으로 중지**되어 있었고, 재시작하지 않은 상태였습니다.

---

## 2️⃣ 해결 1: Runner를 systemd 서비스로 등록

매번 수동으로 실행하는 대신, **부팅 시 자동 시작되도록** systemd 서비스로 등록했습니다.

```bash
cd ~/actions-runner
sudo ./svc.sh install
sudo ./svc.sh start
```

### 서비스 상태 확인

```bash
systemctl status actions.runner.wlals2-my-hugo-blog.jimin-AB350M-Gaming-3.service
```

```
● actions.runner.wlals2-my-hugo-blog.jimin-AB350M-Gaming-3.service
     Loaded: loaded (...; enabled; vendor preset: enabled)
     Active: active (running) since Wed 2025-10-08 06:43:40 EDT

Oct 08 06:43:41 jimin-AB350M-Gaming-3 runsvc.sh[24449]: ✓ Connected to GitHub
```

✅ **이제 Runner가 항상 실행됩니다!**

---

## 3️⃣ 해결 2: sudo 권한 설정 문제

Runner를 재시작한 뒤 테스트로 push했지만, 워크플로우가 **실패**했습니다.

### 로그 확인

```bash
grep "result.*Failed" ~/actions-runner/_diag/Worker_*.log -B 5
```

```
[2025-10-08 10:35:18Z INFO ProcessInvokerWrapper] Finished process 24128 with exit code 1
[2025-10-08 10:35:18Z INFO StepsRunner] Step result: Failed
```

**"Deploy to nginx root" 단계에서 실패**했습니다.

### 원인: sudo 권한 부족

워크플로우에서 실행하는 명령어들:

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

→ **nginx reload만 NOPASSWD**, 나머지는 비밀번호 필요 → **GitHub Actions에서 비밀번호를 입력할 수 없어 실패**

---

## 4️⃣ 해결: sudoers 파일에 필요한 명령어 추가

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

### 확인

```bash
sudo -l | grep NOPASSWD
```

```
(ALL) NOPASSWD: /bin/mkdir -p /var/www/blog
(ALL) NOPASSWD: /usr/bin/chown -R jimin:www-data /var/www/blog
(ALL) NOPASSWD: /usr/sbin/nginx -t
(ALL) NOPASSWD: /bin/systemctl reload nginx
```

✅ **이제 모든 명령어를 비밀번호 없이 실행 가능!**

---

## 5️⃣ 최종 테스트: 자동배포 확인

### (1) 테스트 파일 수정 & Push

```bash
echo "자동 배포 최종 테스트 - $(date)" >> content/_test.md
git add content/_test.md
git commit -m "test: final auto-deploy test with sudo fix"
git push
```

### (2) 워크플로우 실행 확인 (약 10-20초 후)

```bash
tail ~/actions-runner/_diag/Worker_*.log | grep "Job result"
```

```
[2025-10-08 10:47:07Z INFO JobRunner] Job result after all job steps finish: Succeeded
```

✅ **성공!**

### (3) 배포 확인

```bash
cat /var/www/blog/deploy.txt
```

```
source=ci
time=2025-10-08T10:47:06Z
commit=203977b
run_id=18342156050
```

✅ **자동배포 완료!**

### (4) 실제 사이트 확인

```bash
curl -s https://blog.jiminhome.shop/_test/ | grep "최종 테스트"
```

```html
최종 테스트 - 2025-10-08 10:45 (sudo 권한 수정 후)
```

✅ **블로그에 정상 반영!**

---

## 📊 문제 요약

| 문제 | 원인 | 해결 방법 |
|------|------|----------|
| Push해도 배포 안 됨 | Runner 중지됨 | systemd 서비스로 등록 (`svc.sh install`) |
| 워크플로우 실패 | sudo 권한 부족 | `/etc/sudoers.d/github-runner` 설정 추가 |

---

## 🎯 Self-hosted Runner 체크리스트

자동배포가 안 될 때 다음을 확인하세요:

### ✅ Runner 상태
```bash
# Runner 프로세스 확인
ps aux | grep Runner.Listener

# 서비스 상태 확인
systemctl status actions.runner.*
```

### ✅ 워크플로우 로그
```bash
# Runner 로그
tail -50 ~/actions-runner/_diag/Runner_*.log

# Worker 로그 (실패 원인 확인)
grep -i "error\|fail" ~/actions-runner/_diag/Worker_*.log
```

### ✅ sudo 권한
```bash
# 현재 NOPASSWD 설정 확인
sudo -l | grep NOPASSWD
```

### ✅ 워크플로우 트리거 조건

`.github/workflows/deploy.yml`:

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
```

→ **워크플로우 파일(`.github/workflows/deploy.yml`)만 수정하면 배포되지 않습니다!**

---

## 💡 핵심 포인트

### 1. Runner는 서비스로 등록하자
```bash
cd ~/actions-runner
sudo ./svc.sh install
sudo ./svc.sh start
```

- ✅ 부팅 시 자동 시작
- ✅ 백그라운드 실행
- ✅ 자동 재시작 (크래시 시)

### 2. sudo 권한은 최소한으로, 명확하게
```bash
# ❌ 나쁜 예: 모든 권한 허용
jimin ALL=(ALL) NOPASSWD: ALL

# ✅ 좋은 예: 필요한 명령어만 명시
jimin ALL=(ALL) NOPASSWD: /bin/systemctl reload nginx
jimin ALL=(ALL) NOPASSWD: /usr/sbin/nginx -t
```

### 3. 워크플로우 트리거 조건 확인
- `paths` 필터가 있으면 해당 파일 변경 시만 실행
- 워크플로우 파일만 수정해도 배포 안 됨
- `content/**` 등 실제 콘텐츠 변경 시 트리거

---

## 🚀 결과

이제 다음과 같이 사용할 수 있습니다:

```bash
# 1. 블로그 글 작성
hugo new posts/my-new-post.md
vim content/posts/my-new-post.md

# 2. commit & push
git add .
git commit -m "post: 새 글 작성"
git push

# 3. 자동으로 배포됨! (10-20초 소요)
# https://blog.jiminhome.shop 에서 즉시 확인 가능
```

---

## 📚 배운 점

1. **Self-hosted Runner는 단순히 실행만으로는 부족하다**
   - systemd 서비스로 등록해야 안정적
   - 로그 위치와 확인 방법을 알아야 함

2. **CI/CD 실패의 80%는 권한 문제**
   - sudo 권한을 정확히 설정하지 않으면 실패
   - 에러 로그를 꼼꼼히 확인해야 원인 파악 가능

3. **워크플로우 트리거 조건도 중요하다**
   - `paths` 필터가 있으면 특정 파일만 트리거
   - 테스트할 때는 `workflow_dispatch`도 추가하면 편리

---

## Reference

- [GitHub Actions: Self-hosted runners](https://docs.github.com/en/actions/hosting-your-own-runners)
- [GitHub Actions: Runner service](https://docs.github.com/en/actions/hosting-your-own-runners/managing-self-hosted-runners/configuring-the-self-hosted-runner-application-as-a-service)
- [Linux sudoers 설정](https://www.sudo.ws/docs/man/sudoers.man/)

---

💡 **"자동화는 한 번 설정하면 끝이 아니다. 지속적으로 모니터링하고 개선해야 한다."**
