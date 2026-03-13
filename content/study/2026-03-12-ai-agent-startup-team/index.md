---
title: "[AI Agent로 스타트업급 팀 구성하기 #1] 혼자서 팀을 만들 수 있을까 — AI Agent 도입 배경과 아키텍처"
date: 2026-03-12T14:00:00+09:00
summary: "1인 홈랩 운영의 반복 비용과 역할 공백을 AI Agent로 해결하기 위한 도입 배경, 멀티 Agent 아키텍처 설계, Tech Lead 워크플로우 정리"
tags: ["ai-agent", "aws-bedrock", "discord", "tool-use", "multi-agent", "homelab"]
categories: ["study", "Development"]
series: ["AI Agent 시리즈"]
draft: false
showtoc: true
tocopen: true
---

## 배경

K8s 홈랩을 혼자 운영하면서 매일 반복하는 것들이 있다.

```
07:30  터미널 열기 → SSH → kubectl get pods -n blog-system → 이상 없음 확인
09:00  "복사 버튼 추가해야지" → Hugo Go template 문법 검색 → 우선순위 밀림
14:00  블로그 CSS 수정 요청 → 프론트엔드 지식 부족 → 또 밀림
18:00  장애 알림 → 다시 SSH → kubectl logs → kubectl describe → 원인 분석
```

각각은 5분이지만, 터미널 열고 → SSH 접속하고 → 명령어 조합하고 → 결과 해석하는 **컨텍스트 스위칭 비용**까지 합치면 상당하다. 그리고 더 큰 문제가 있었다.

**나는 인프라 엔지니어다. 프론트엔드와 백엔드 코드를 직접 작성할 역량이 부족하다.**

Hugo 블로그의 Go template, CSS, JavaScript 수정은 계속 미뤄지고 있었고, Spring Boot WAS의 새로운 API 개발도 마찬가지였다. 1인 운영의 한계가 명확했다.

---

## 해결하려는 문제 3가지

### 1. 반복 조회 작업의 컨텍스트 스위칭 비용

Pod 상태 확인, 로그 조회, 메트릭 확인 — 모두 `kubectl` 명령어를 치면 되지만, 그 과정이 번거롭다.

Discord에서 자연어로 물어보면 AI가 알아서 명령어를 조합하고 결과를 요약해주는 구조를 원했다.

```
# Before: 터미널 3단계
ssh k8s-cp → kubectl get pods -n blog-system → kubectl logs was-xxx -n blog-system --tail=50

# After: Discord 1줄
!infra was pod 로그 마지막 50줄 보여줘
```

### 2. 프론트엔드/백엔드 역할 공백

홈랩 블로그는 Hugo(프론트엔드) + Spring Boot(백엔드) + K8s(인프라) 3계층으로 구성되어 있다. 인프라는 내가 직접 하지만, 프론트엔드와 백엔드 코드 수정은 늘 밀리는 영역이었다.

AI Agent가 코드를 읽고 → 수정하고 → PR까지 만들어주면, 내가 할 일은 **리뷰와 머지 판단**뿐이다.

### 3. 1인이지만 팀처럼 운영하고 싶다

목표는 단순 자동화가 아니다. AI Agent를 **팀원처럼 역할 분담**하고, 나는 **Tech Lead로서 최종 판단**만 하는 구조를 만들고 싶었다.

```
┌─────────────────────────────────────────┐
│           나 (Tech Lead)                 │
│  - 아키텍처 결정                          │
│  - PR 리뷰 & 머지 판단                    │
│  - 장애 대응 최종 판단                     │
└──────┬──────┬──────┬──────┬─────────────┘
       │      │      │      │
   Infra  Frontend Backend Reviewer
   Agent   Agent   Agent   Agent
```

스타트업에서 CTO가 직접 코드를 다 짜지 않고, 팀원에게 역할을 분담하고 리뷰하는 것과 같은 구조다.

---

## AI Agent란 무엇인가

위에서 "AI Agent에게 역할을 분담한다"고 했는데, 정확히 AI Agent가 뭔지 먼저 정리해야 한다.

### 챗봇과 Agent의 차이

ChatGPT나 Claude를 웹에서 쓸 때는 **텍스트를 입력하면 텍스트가 나온다**. 이게 챗봇이다.

```
[챗봇]
사용자: "blog-system Pod 상태 알려줘"
LLM:    "kubectl get pods -n blog-system 명령어를 실행해보세요."
         → 여기서 끝. 실제로 실행하지는 않는다.
```

AI Agent는 다르다. LLM이 **도구(Tool)를 직접 호출**하고, 그 결과를 받아서 해석까지 한다.

```
[Agent]
사용자: "blog-system Pod 상태 알려줘"
LLM:    (판단) "kubectl get pods -n blog-system 을 실행해야겠다"
        → kubectl 도구 호출 → 실행 결과 수신
LLM:    "was Pod 2개 Running, web Pod 2개 Running, 전부 정상입니다."
         → 실행 + 해석까지 완료.
```

정리하면:

| | 챗봇 | AI Agent |
|--|------|----------|
| 입력 | 텍스트 | 텍스트 |
| 처리 | LLM만 | LLM + **도구 호출** |
| 출력 | 텍스트 (안내) | 텍스트 (**실행 결과 포함**) |
| 핵심 | "이렇게 해보세요" | "해봤는데 결과가 이렇습니다" |

### Tool Use (Function Calling) — Agent의 핵심 메커니즘

Agent를 가능하게 하는 기술이 **Tool Use**(또는 Function Calling)다.

LLM에게 "너는 이런 도구들을 쓸 수 있어"라고 도구 목록을 미리 알려준다. 그러면 LLM은 사용자의 요청을 분석해서, 어떤 도구를 어떤 파라미터로 호출해야 하는지 **스스로 판단**한다.

```
[Tool 정의 — LLM에게 미리 알려주는 것]
{
  "name": "kubectl",
  "description": "K8s 클러스터에 읽기 전용 명령을 실행한다",
  "parameters": {
    "command": "실행할 kubectl 명령어"
  }
}

[실제 흐름]
1. 사용자 → "was pod 로그 보여줘"
2. LLM 판단 → tool_use: kubectl(command="logs -n blog-system -l app=was --tail=50")
3. 시스템 → kubectl 실행 → 결과를 LLM에게 반환
4. LLM 해석 → "최근 로그에 ERROR는 없고, 정상 요청만 기록되어 있습니다."
```

"로그 보여줘"라고 하면 `kubectl logs`를, "리소스 사용량"이라고 하면 `kubectl top`을 호출한다. 이 판단을 LLM이 한다는 점이 핵심이다.

### 왜 멀티 Agent인가 — 단일 Agent의 한계

Agent 하나에 모든 도구를 다 주면 안 되는 이유가 있다.

**1. 프롬프트가 길어지면 성능이 떨어진다**

시스템 프롬프트에 "너는 인프라 전문가이면서, 프론트엔드 개발자이면서, 코드 리뷰어야"라고 쓰면 — 어느 역할에도 집중하지 못한다. 역할을 하나로 좁혀야 답변 품질이 올라간다.

**2. 권한 분리가 안 된다**

하나의 Agent가 kubectl도 쓰고 GitHub API도 쓸 수 있으면, "코드 수정해줘"라는 요청에 실수로 인프라 명령을 실행할 가능성이 생긴다. Agent별로 접근 가능한 도구를 물리적으로 분리해야 안전하다.

**3. 비용 최적화가 안 된다**

단순 상태 조회에도 비싼 모델을 써야 하고, 코드 생성에도 같은 모델을 써야 한다. Agent를 나누면 작업 난이도에 따라 모델을 다르게 배정할 수 있다.

```
[단일 Agent]
모든 요청 → Sonnet (비쌈) → 월 $50+

[멀티 Agent]
상태 조회 → Infra Agent (Haiku 3, 저렴)     → ~$0.05/일
코드 생성 → Frontend Agent (Haiku 4.5)       → ~$0.30/일
코드 생성 → Backend Agent (Haiku 4.5)        → ~$0.30/일
코드 리뷰 → Reviewer Agent (Haiku 4.5)       → ~$0.30/일
                                     합계 → ~$20/월
```

---

## 기술 스택 선택

### 왜 AWS인가 — 홈랩만으로 부족한 이유

홈랩은 K8s를 직접 운영하기에 좋지만, AI Agent 서버를 올리기에는 한계가 있다.

| 항목 | 홈랩 | AWS |
|------|------|-----|
| **가용성** | 정전/재부팅 시 Agent 중단 | ECS가 자동 복구 |
| **LLM 접근** | API Key를 코드에 넣어야 함 | IAM Role로 키 없이 인증 |
| **스케일** | VM 리소스 고정 | 필요할 때만 컨테이너 기동 |
| **포트폴리오** | "홈랩에서 돌렸다" | "AWS 프로덕션 환경 경험" |

현재 Phase 1~3은 홈랩에서 개발/테스트하고, Phase 4에서 AWS ECS로 이전할 계획이다. 홈랩에서 검증된 코드를 클라우드로 옮기는 과정 자체가 실무 경험이 된다.

### 왜 AWS Bedrock인가

LLM API를 호출하는 방법은 여러 가지가 있다.

| 옵션 | 장점 | 단점 |
|------|------|------|
| **OpenAI API** | 생태계 넓음, GPT-4 성능 | 별도 계정/결제, AWS IAM 통합 안 됨 |
| **Anthropic API 직접 호출** | Claude 모델 직접 접근 | 별도 API Key 관리, ECS 배포 시 키 주입 필요 |
| **AWS Bedrock** | IAM 통합, 다중 모델, ECS 배포 용이 | AWS 종속 |

Bedrock을 선택한 이유:
- **IAM Role 기반 인증** — API Key를 코드에 넣지 않아도 된다. ECS Task에 IAM Role을 붙이면 자동 인증. 보안 관점에서 키 로테이션 부담이 없다
- **다중 모델 전환** — Claude Haiku, Sonnet을 용도별로 나눠 쓸 수 있다. 코드 한 줄(`model_id` 변경)로 모델을 교체할 수 있어서, Agent별 모델 분리가 쉽다
- **인프라 일원화** — ECS(컨테이너), CloudWatch(로그), IAM(인증)이 같은 AWS 안에서 연결된다. Terraform으로 전체를 IaC로 관리할 수 있다

### 왜 Discord인가

처음에는 Slack을 고려했지만, 무료 플랜의 앱 제한(최대 10개)과 메시지 히스토리 제한이 걸렸다. Discord는 봇 생성이 자유롭고, 마크다운 렌더링이 잘 되고, 무료다.

---

## 아키텍처 설계

### 전체 구조

```
┌──────────────────────────────────────────────────────────┐
│                       Discord 서버                         │
│                    (모니터링 채널)                          │
└──────┬──────────┬──────────┬──────────┬──────────────────┘
       │          │          │          │
   !infra     !frontend  !backend   !review
       │          │          │          │
┌──────▼──────────▼──────────▼──────────▼──────────────────┐
│                 agent_bot.py (라우터)                       │
│           명령어 파싱 → Agent 선택 → 응답 전달               │
└──────┬──────────┬──────────┬──────────┬──────────────────┘
       │          │          │          │
  ┌────▼────┐ ┌───▼───┐ ┌───▼───┐ ┌───▼────┐
  │ Infra   │ │Front  │ │Back   │ │Reviewer│
  │ Agent   │ │end    │ │end    │ │ Agent  │
  │(Haiku 3)│ │Agent  │ │Agent  │ │(Haiku  │
  │         │ │(Haiku │ │(Haiku │ │  4.5)  │
  │         │ │  4.5) │ │  4.5) │ │        │
  └────┬────┘ └───┬───┘ └───┬───┘ └───┬────┘
       │          │          │          │
  ┌────▼────┐ ┌───▼──────────▼──────────▼────┐
  │kubectl  │ │        GitHub API             │
  │(읽기    │ │  - read_file                  │
  │ 전용)   │ │  - commit_files               │
  └────┬────┘ │  - create_pr                  │
       │      │  - get_pr_diff                │
  ┌────▼────┐ │  - create_review              │
  │ 홈랩    │ └───────────┬───────────────────┘
  │ K8s     │             │
  │ Cluster │        ┌────▼────┐
  └─────────┘        │ GitHub  │
                     │blogsite │
                     └─────────┘
```

### Agent별 역할과 권한 경계

핵심 설계 원칙은 **"Agent에게 주는 권한은 최소한으로, 사람의 확인은 최대한으로"**다.

| Agent | 역할 | 할 수 있는 것 | 절대 못 하는 것 |
|-------|------|-------------|---------------|
| **Infra** | K8s 상태 모니터링 | get, describe, logs, top | apply, delete, edit, patch |
| **Frontend** | Hugo 프론트엔드 개발 | Hugo/CSS/JS 읽기·수정, PR 생성 | 백엔드 수정, PR 머지, config.toml 변경 |
| **Backend** | Spring Boot 백엔드 개발 | Java 코드 읽기·수정, PR 생성 | 프론트 수정, DB 스키마 직접 변경, PR 머지 |
| **Reviewer** | 코드 리뷰 | PR diff 읽기, 리뷰 작성 | 코드 직접 수정, PR 머지 |

**안전장치가 5겹이다**:

```
Layer 1: 화이트리스트  — kubectl은 get/describe/logs/top만 허용 (Python 코드 수준)
Layer 2: RBAC         — agent-readonly kubeconfig (K8s 수준에서 차단)
Layer 3: 타임아웃      — 10초 제한 (무한 실행 방지)
Layer 4: 루프 제한     — 최대 5~8회 연속 Tool 호출 (토큰 폭발 방지)
Layer 5: 출력 제한     — 3000자 초과 시 잘라냄
```

왜 5겹인가? Layer 1(Python)이 뚫려도 Layer 2(K8s RBAC)에서 막힌다. 방어는 한 겹으로 충분하지 않다.

### 모델 분리 — 비용과 성능의 균형

모든 작업에 같은 모델을 쓰면 비용이 폭발한다.

| 작업 유형 | 모델 | 이유 |
|----------|------|------|
| K8s 상태 조회/요약 | Haiku 3 | 단순 조회 결과 요약이라 저렴한 모델로 충분 |
| 코드 생성, 리뷰 | Haiku 4.5 | 코드 품질이 중요한 작업은 더 높은 모델 |

원래 코드 생성에는 Sonnet 3.5를 쓸 예정이었지만, Bedrock에서 15일 미사용 시 Legacy 모델이 차단되는 정책 때문에 Haiku 4.5로 임시 대체한 상태다.

### Tech Lead 워크플로우

실제 작업 흐름을 예시로 보면:

```
1. 나: Discord에서 "!frontend 코드 블록에 복사 버튼 추가해줘"

2. Frontend Agent:
   - GitHub에서 현재 Hugo 템플릿 코드를 읽음
   - 수정 사항을 판단
   - agent/frontend/add-copy-button 브랜치 생성
   - 코드 커밋
   - [Agent/Frontend] PR 생성

3. 나: "!review 2" (PR #2 리뷰 요청)

4. Reviewer Agent:
   - PR diff를 읽고 분석
   - 보안/버그/품질 관점에서 리뷰 작성
   - GitHub에 리뷰 코멘트 등록

5. 나: 리뷰 내용 확인 → 머지 판단 (이 단계만 사람이 한다)
```

Agent가 브랜치 이름을 `agent/`로 시작하도록 강제하고, PR 제목에 `[Agent/Frontend]`를 붙이도록 강제한 이유는 — 사람이 만든 PR과 Agent가 만든 PR을 즉시 구분하기 위해서다.

---

## 비용 설계

1인 홈랩 프로젝트에서 비용 통제는 현실적으로 중요하다.

| Agent | 일일 예상 사용량 | 일일 비용 |
|-------|----------------|----------|
| Infra (Haiku 3) | 100K input + 20K output | ~$0.05 |
| Frontend (Haiku 4.5) | 50K input + 10K output | ~$0.30 |
| Backend (Haiku 4.5) | 50K input + 10K output | ~$0.30 |
| Reviewer (Haiku 4.5) | 50K input + 10K output | ~$0.30 |

**월간 예산: $20 상한** — 초과 시 Agent 전체 비활성화 + Discord 알림.

Sonnet 모델을 복구하면 비용이 올라가지만, 코드 생성 품질과의 트레이드오프다. 이 부분은 Phase 3 이후에 다시 판단할 예정이다.

---

## 현재 진행 상황

| Phase | 내용 | 상태 |
|-------|------|------|
| **Phase 1** | Discord + Bedrock + kubectl Tool Use (Infra Agent) | ✅ 완료 |
| **Phase 2** | Frontend Agent (GitHub API + PR 생성 + 멀티 Agent 라우팅) | ✅ 완료 |
| **Phase 3** | Backend/Reviewer Agent + 4 Agent 순차 협업 E2E | ✅ 완료 |
| **Phase 4** | AWS ECS 배포 + Terraform IaC + Agent 자율 토론 | 예정 |

Phase 3까지의 실제 검증 결과: Discord에서 "복사 버튼 추가해줘" 요청 → Frontend Agent가 PR 생성 → Reviewer Agent가 코드 리뷰 → 머지 완료. **4개 Agent 전체가 End-to-End로 동작한다.**

---

## 다음 글 예고

이 글에서는 "왜 AI Agent 팀이 필요한가"와 전체 아키텍처를 다뤘다. 다음 글부터는 각 Agent의 구현 과정을 하나씩 다룬다.

- **#2**: Infra Agent — Bedrock Converse API로 kubectl을 자연어로 제어하기
- **#3**: Frontend Agent — GitHub API로 코드 수정부터 PR 생성까지 자동화
- **#4**: Reviewer Agent — AI가 코드 리뷰를 하면 어떤 피드백을 주는가
- **#5**: AWS 배포 — ECS + Terraform으로 로컬에서 클라우드로 전환

---

## 관련 실습

- [#2] Infra Agent 구축 (예정)
- [#3] Frontend Agent 구축 (예정)
- [#4] Reviewer Agent 구축 (예정)
- [#5] AWS ECS 배포 (예정)
