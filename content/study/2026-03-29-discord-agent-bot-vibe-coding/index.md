---
title: "Discord + claude -p로 모바일 바이브코딩 환경 구축 — 어디서든 K8s 인프라를 관리하는 AI 에이전트 봇"
date: 2026-03-29T10:00:00+09:00
author: "늦찌민"
slug: "discord-agent-bot-vibe-coding"
summary: "Discord Bot + claude -p를 베어메탈에서 조합해 어디서든 K8s 진단→코드 수정→PR 생성까지 수행하는 AI 에이전트 봇 구축기"
categories: ["study", "Development"]
tags: ["claude-code", "discord-bot", "vibe-coding", "ai-agent", "kubernetes", "gitops", "automation", "homelab"]
draft: false
---

> PC를 켜지 않고 모바일 Discord에서 "HAProxy 재시작이 잦아, 수정해줘"라고 한 줄 치면,
> AI가 클러스터를 진단하고, manifest를 수정하고, PR을 만든다.
> 이걸 구현한 과정을 정리합니다.

<!--more-->

## 1. 배경: 어디서든 인프라를 관리할 수 있다면

### 출발점

이전에 AWS ECS + Bedrock 기반으로 Discord Agent 봇을 만들어서 K8s 클러스터를 모니터링한 적이 있다. 클라우드에서 잘 동작했지만, 한 가지 고민이 남았다 — **이걸 로컬에서도 돌릴 수 있지 않을까?**

Claude Code의 `claude -p`(비대화형 모드)를 쓰면서 답을 찾았다:

```
claude -p가 베어메탈 호스트에서 실행되면:
  → kubectl이 바로 된다 (K8s API 터널 불필요)
  → 파일 시스템에 직접 접근 (S3 불필요)
  → Bash/Read/Write 도구를 Claude가 알아서 쓴다 (tool wrapper 불필요)
  → API 키 불필요 (Claude Code 구독으로 동작)
```

**Discord Bot + claude -p + systemd.** 이 세 개만 조합하면 모바일에서도, 어디서든 K8s 인프라를 관리하는 AI 에이전트를 만들 수 있다.

### 클라우드 vs 로컬

기존 ECS 봇이 "못하는 것"이 있었던 게 아니다. 오히려 로컬에서 돌릴 수 있다는 것을 발견한 것에 가깝다.

| | 클라우드 (ECS) | 로컬 (베어메탈) |
|--|--------------|---------------|
| K8s 접근 | Cloudflare 터널 경유 API | **직접 kubectl** |
| 파일 접근 | S3 업로드/다운로드 | **/home/jimin/ 직접** |
| 도구 구현 | kubectl_tool.py, rag_tool.py 직접 구현 | **Claude가 알아서** |
| 비용 | Bedrock 마크업 포함 | **0원** (구독) |
| 코드량 | 717줄 + tools/ 3파일 | **~300줄 단일 파일** |

로컬이니까 가능한 것들이 있다. 특히 **호스트의 모든 것에 Claude가 접근 가능**하다는 점이 결정적이었다.

---

## 2. 아키텍처

```
[모바일/PC Discord]
       │
       ▼
[discord-agent-bot] ─── systemd service on k8s-cp (베어메탈)
       │
       ├── 메시지 수신 → 에이전트 라우팅
       │
       ├── claude -p (subprocess)
       │   ├── --system-prompt "너는 K8s 인프라 에이전트다..."
       │   ├── --allowed-tools "Bash,Read,Write,Edit"
       │   ├── --permission-mode "bypassPermissions"
       │   └── Claude가 알아서: kubectl, 파일 읽기/쓰기, git, gh
       │
       └── 결과 → Discord 채널로 전송
```

핵심 차이: Claude Code가 도구를 직접 실행하므로, 봇은 **메시지 중계자**일 뿐이다.

| | ECS 버전 (기존) | 베어메탈 버전 (현재) |
|--|---------------|-------------------|
| LLM | Bedrock (AWS) | claude -p (로컬) |
| 도구 실행 | 봇이 직접 (kubectl_tool.py) | Claude가 직접 (Bash 도구) |
| RAG | S3 + Bedrock Titan + FAISS | Claude가 Read로 docs/ 직접 읽음 |
| 코드량 | 717줄 + tools/ 3파일 | **~300줄, 단일 파일** |
| 의존성 | boto3, faiss, numpy, langfuse... | **discord.py, dotenv** 2개 |
| 비용 | Bedrock 마크업 포함 | 0원 (구독) |

---

## 3. 에이전트 설계

### 역할 분리

```
!infra      → 진단만 (읽기 전용)
!dev        → 코드 수정 + PR 생성 (쓰기 가능)
!security   → 보안 점검 (읽기 전용)
!emergency  → 즉시 복구 (safe-kubectl.sh만)
!fix        → infra(진단) → dev(수정+PR) 자동 파이프라인
```

각 에이전트는 `--allowed-tools`로 권한이 물리적으로 제한된다. dev만 Write/Edit을 가지고, infra/security는 `Bash(readonly:true),Read`만 가진다.

### emergency 에이전트: 안전한 긴급 복구

긴급 상황에서 kubectl 변경이 필요할 수 있다. 하지만 `claude -p`에 Bash를 그냥 주면 아무 명령이나 실행할 수 있다. 그래서 **래퍼 스크립트**로 물리적 차단을 구현했다.

```bash
# safe-kubectl.sh — 허용된 명령만 실행
case "$ACTION" in
  restart)    kubectl rollout restart "$TARGET" -n blog-system ;;
  delete-pod) kubectl delete pod "$TARGET" -n blog-system ;;
  cordon)     kubectl cordon "$TARGET" ;;
  *)          echo "허용되지 않은 명령: $ACTION" ; exit 1 ;;
esac
```

차단 테스트 결과:

```bash
$ ./safe-kubectl.sh diagnose pods kube-system
# 출력: [차단] 네임스페이스 'kube-system'는 허용되지 않습니다.

$ ./safe-kubectl.sh delete-pod "--all"
# 출력: [차단] 위험한 타겟 패턴: '--all'

$ ./safe-kubectl.sh delete-pod was
# 출력: [차단] Pod 이름이 아닌 것 같습니다: 'was'
```

### ArgoCD Auto-Sync와의 충돌 회피

이 설계에서 가장 고민한 부분이다. 우리 환경은 ArgoCD auto-sync가 켜져 있다.

```
kubectl scale replicas=5 실행
  → 3초 후 ArgoCD: "Git에는 replicas=2인데?" → 되돌림
  → 긴급 조치가 무의미
```

그래서 emergency 에이전트는 **선언적 상태를 바꾸는 명령을 전부 금지**했다.

| 허용 (ArgoCD 충돌 없음) | 금지 (ArgoCD가 되돌림) |
|------------------------|----------------------|
| rollout restart | scale |
| delete pod (자동 재생성) | edit / patch |
| cordon node | apply |

설정 변경이 필요하면? `!fix`가 Git manifest를 수정 → PR 생성 → ArgoCD가 자동 반영. GitOps 원칙을 깨지 않는다.

---

## 4. !fix 오케스트레이터: 진단부터 PR까지

단일 에이전트에 모든 작업을 맡기면 max_turns를 초과한다. 그래서 에이전트를 순차 호출하는 파이프라인을 만들었다.

```
!fix "HAProxy readiness probe timeout이 1초라 재시작이 잦음"

  Phase 1: infra 에이전트 (진단)
    → kubectl get/logs/describe로 원인 분석
    → "probe timeout 1초 → 재시작 반복"

  Phase 2: dev 에이전트 (수정 + PR)
    → infra 진단 결과를 받아서
    → manifest 파일 찾기 → 수정 → git branch → PR 생성

  결과: PR #1 생성 → merge 완료
```

실제 Discord 대화:

```
사용자: !fix HAProxy readiness probe timeout이 1초라 재시작이 잦음. 수정해줘

봇: 🔧 Fix Pipeline 시작
    📋 문제: HAProxy readiness probe timeout이 1초라 재시작이 잦음
    🔍 Phase 1 — 진단 (infra 에이전트)
    ⏳ 클러스터 상태 조회 중...

봇: 🔍 진단 결과 (87초 소요):
    원인: Readiness Probe timeout=1s ...

봇: 🔧 Phase 2 — 수정 + PR (dev 에이전트)
    ⏳ 파일 탐색 및 수정 중...

봇: 🔧 수정 결과 (142초 소요):
    PR #1 생성 완료: fix/haproxy-readiness-probe-timeout

봇: ✅ Fix Pipeline 완료 (총 229초)
```

---

## 5. 안전장치: 4중 방어

자율 에이전트는 "잘 되면 편하고, 잘못되면 재앙"이다. 안전장치를 겹겹이 걸어야 한다.

| 계층 | 안전장치 | 뭘 막는가 |
|------|---------|---------|
| 물리적 | max_turns (20~25) | 무한 도구 호출 |
| 물리적 | timeout (600초) | 10분 넘는 작업 |
| 물리적 | max-budget-usd ($5) | 토큰 과소비 |
| 물리적 | safe-kubectl.sh | 위험한 kubectl 명령 |
| 논리적 | 조기 종료 규칙 | 삽질 방지 ("3~4번 해도 못 찾으면 멈추고 보고해라") |
| 논리적 | 작업 범위 제한 | blog-system, monitoring NS만 접근 |

특히 `--max-budget-usd`는 Claude Code 구독이라 실제 과금은 없지만, 무한 루프 감지용으로 동작한다.

---

## 6. 성과

| 항목 | 클라우드 버전 (ECS) | 로컬 버전 (베어메탈) |
|------|-------------------|-------------------|
| 코드량 | 717줄 + tools/ 3파일 | **~300줄 단일 파일** |
| Python 의존성 | 8개 (boto3, faiss, numpy...) | **2개** (discord.py, dotenv) |
| 추가 비용 | Bedrock 마크업 포함 | **0원** (구독) |
| K8s 접근 | Cloudflare 터널 경유 API | **직접 kubectl** |
| 도구 구현 | kubectl_tool.py, rag_tool.py 직접 래핑 | **Claude가 내장 도구로 처리** |
| PR 자동 생성 | 미구현 | **!fix로 진단→수정→PR 완료** |
| 접근성 | 어디서든 (클라우드) | **어디서든 (Discord)** |

---

## 7. 트러블슈팅

### OOM Kill (systemd MemoryMax)

처음에 `MemoryMax=512M`으로 설정했다가 봇이 OOM으로 죽었다.

```
[수신] pod 상태 확인해줘
discord-agent-bot.service: A process has been killed by the OOM killer.
```

원인: `claude` 바이너리가 Node.js 기반이라 RSS ~600MB를 사용한다. 봇(~30MB) + claude(~600MB) = 최소 1GB 필요.

```bash
# 해결: MemoryMax를 2G로 변경
MemoryMax=2G
```

### max_turns 부족

infra 에이전트가 `max_turns=5`일 때 복잡한 진단에서 턴이 부족했다.

```
kubectl get pods  → 턴 1
kubectl logs      → 턴 2
kubectl describe  → 턴 3
kubectl get svc   → 턴 4
분석 시작...      → 턴 5 (끊김)
# 출력: Error: Reached max turns (5)
```

해결: 턴을 넉넉하게 올리고(20~25), 대신 timeout + budget + 조기 종료 규칙으로 안전장치를 강화했다. 구독이라 턴을 늘려도 비용 차이 없다.

---

## 8. 다음 단계

- **cron 자동 실행**: 매일 새벽 보안 점검 → 결과 Discord 전송
- **판단 대기 루프**: 에이전트가 판단이 필요하면 Discord DM으로 질문 → 사용자 응답 대기 → 재개
- **ai-security-copilot 연동**: `!security scan` → copilot 실행 → 취약점 발견 → `!fix`로 자동 수정
- **Claude Code SDK 전환**: 현재 subprocess(`claude -p`)에서 Python 네이티브 호출로 업그레이드 → 스트리밍 중간 피드백

---

## 핵심 교훈

**"바이브코딩"의 본질은 대단한 기술이 아니다.** Discord Bot 하나, `claude -p` 한 줄, systemd service 하나. 이 세 개를 연결한 것뿐이다.

진짜 어려운 건 기술 구현이 아니라 **안전장치 설계**였다:
- 에이전트가 뭘 할 수 있고 뭘 못 하게 할 것인가
- ArgoCD와 충돌하지 않는 긴급 조치는 무엇인가
- 언제 멈추고 사람에게 물어볼 것인가

이 질문들에 답을 내리는 과정이 진짜 설계였고, 코드는 그 결과물일 뿐이다.
