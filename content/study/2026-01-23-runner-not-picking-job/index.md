---
title: "GitHub Actions Runner가 Job을 가져가지 않는 문제"
date: 2026-01-23
description: "Self-hosted Runner가 잘못된 저장소에 등록되어 Job을 인식 못하는 문제 해결"
tags: ["github-actions", "self-hosted-runner", "troubleshooting"]
categories: ["study"]
---

## 상황

워크플로우 실행 시 무한정 대기 상태.

```
Waiting for a runner to pick up this job...
```

---

## 원인

### Runner 설정 확인

```bash
cat ~/actions-runner/.runner
```

```json
{
  "gitHubUrl": "https://github.com/wlals2/my-hugo-blog",
  "agentName": "k8s-cp"
}
```

### 문제 분석

| 항목 | 값 | 문제 |
|------|-----|------|
| 워크플로우 저장소 | wlals2/blogsite | 여기서 실행 |
| Runner 등록 저장소 | wlals2/my-hugo-blog | 잘못 등록됨 |

Runner가 다른 저장소에 등록되어 있어서 Job을 인식하지 못함.

---

## 해결

### 1. Runner 서비스 중지

```bash
cd ~/actions-runner

# 서비스 중지 및 제거
sudo ./svc.sh stop
sudo ./svc.sh uninstall
```

### 2. 설정 파일 삭제

```bash
rm -f .runner .credentials .credentials_rsaparams
```

### 3. 올바른 저장소로 재등록

```bash
# GitHub > Settings > Actions > Runners > New self-hosted runner
# 토큰 복사

./config.sh --url https://github.com/wlals2/blogsite --token <NEW_TOKEN>
```

### 4. 서비스 재시작

```bash
sudo ./svc.sh install
sudo ./svc.sh start
```

### 5. 등록 확인

```bash
cat .runner | jq '.gitHubUrl'
# "https://github.com/wlals2/blogsite"
```

---

## 결과

| 항목 | Before | After |
|------|--------|-------|
| Runner 등록 | my-hugo-blog | blogsite |
| Job 인식 | ❌ 무한 대기 | ✅ 정상 실행 |

---

## 정리

### Runner 등록 체크리스트

| 확인 항목 | 명령어 |
|----------|--------|
| 등록된 저장소 | `cat .runner \| jq '.gitHubUrl'` |
| Runner 상태 | `sudo ./svc.sh status` |
| GitHub UI | Settings > Actions > Runners |

### 주의사항

- Organization runner는 여러 저장소에서 공유 가능
- Repository runner는 해당 저장소만 사용 가능
- 저장소 이름 변경 시 Runner 재등록 필요

---

## 관련 명령어

```bash
# Runner 상태 확인
sudo ~/actions-runner/svc.sh status

# Runner 로그 확인 (Job 수신 확인)
tail -f ~/actions-runner/_diag/Worker_*.log | grep "Running job"

# Runner 강제 재등록
./config.sh remove --token <TOKEN>
./config.sh --url https://github.com/wlals2/blogsite --token <NEW_TOKEN>
```
