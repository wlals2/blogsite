---
title: "[AI Agent 시리즈 #3] 자율 장애 대응 — Lambda 헬스체크에서 Agent 자동 기동까지"
date: 2026-03-14T10:00:00+09:00
summary: "EventBridge 5분 크론으로 K8s 이상을 감지하고, Bedrock AI가 분석하고, ECS Agent가 자동 기동되어 Orchestrator 패턴으로 장애를 진단하는 전체 파이프라인 구축기"
tags: ["ai-agent", "lambda", "eventbridge", "orchestrator", "blackboard-pattern", "autonomous", "homelab"]
categories: ["study", "Cloud & Terraform"]
series: ["AI Agent 시리즈"]
draft: false
showtoc: true
tocopen: true
---

> **시리즈**: [AI Agent 시리즈]
> - 이전 글: [ECS Fargate에 Agent 서버 배포하기 #2](/study/2026-03-13-ai-agent-ecs-cloudflare-tunnel/)
> - 다음 글: [LLM 관측성 — Langfuse로 Agent 비용/성능 추적 #4](/study/2026-03-14-ai-agent-langfuse-observability/)

## 배경

[이전 글](/study/2026-03-13-ai-agent-ecs-cloudflare-tunnel/)에서 Agent 서버를 AWS ECS로 분리하고, Cloudflare Tunnel로 홈랩 K8s API에 Zero Trust 연결했다.

하지만 여전히 사람이 `!infra pod 상태 확인`을 직접 타이핑해야 Agent가 동작했다.

```
현재 상태:
  사람이 새벽 3시에 잠들어 있음
  → K8s Pod CrashLoopBackOff 발생
  → 아무도 모름
  → 아침에 일어나서 발견
  → "어젯밤부터 죽어있었네..."
```

**사람이 없어도 Agent가 스스로 장애를 감지하고, 진단하고, 수정 PR까지 만들어야 한다.** 이것이 Phase 5의 목표다.

---

## 이 글에서 다루는 것

```
1. Lambda 헬스체크 — 5분마다 K8s Pod 상태 자동 감지
2. 쿨다운 메커니즘 — SSM Parameter Store로 알림 폭풍 방지
3. ECS 자동 기동 — 이상 감지 시 Agent 서버를 깨움
4. AUTO_DIAGNOSE — Agent가 스스로 진단을 시작하는 모드
5. Orchestrator 패턴 — Blackboard + Reviewer 피드백 루프
6. 트러블슈팅 — Lambda → CF Tunnel 403, Bedrock IAM
```

---

## 1. 전체 아키텍처: 누가 누구를 깨우는가

설계의 핵심 질문은 "**누가 첫 번째로 이상을 감지하는가**"였다.

```
[EventBridge] ── 5분마다 Lambda 트리거
     │
     ▼
[Lambda] ── K8s API 호출 → Pod 상태 확인
     │
     ├── 정상 → 로그만 남기고 종료
     │
     └── 이상 감지 → 3가지 동시 수행:
           │
           ├── 1. Bedrock AI 분석 (원인 요약)
           ├── 2. Discord webhook 알림 (사람에게 통보)
           └── 3. ECS Agent 자동 기동 (desired_count: 0→1)
                    │
                    ▼
               [ECS Agent Bot]
                    │  AUTO_DIAGNOSE=true 감지
                    │  → 자동으로 Orchestrator 실행
                    │
                    ├── Phase 1: Infra Agent → kubectl로 상세 진단
                    ├── Phase 2: Backend/Frontend Agent → 수정 코드 PR
                    └── Phase 3: Reviewer Agent → 리뷰 (피드백 루프)
                    │
                    ▼
               [Discord 채널에 결과 보고]
               [사람은 PR 머지만 결정]
```

Lambda와 ECS Agent를 분리한 이유: **Lambda는 경량 센서, Agent는 중량 두뇌**다.

| | Lambda | ECS Agent |
|--|--------|-----------|
| 실행 시간 | 5~10초 | 수 분 |
| 비용 | 거의 무료 (128MB × 5분 주기) | 실행 시간만큼 과금 |
| 역할 | "이상 있다/없다" 판단 | 상세 진단 + 코드 수정 + PR 생성 |
| 상시 가동 | Yes (EventBridge 크론) | No (필요 시만 기동) |

Lambda가 매번 Orchestrator를 실행하면 60초 타임아웃에 걸리고, Bedrock 호출 비용도 낭비된다. 이상이 있을 때만 ECS를 깨우는 것이 비용 효율적이다.

---

## 2. Lambda 헬스체크: 이상 감지

### Pod 상태 체크 로직

Lambda는 K8s API를 호출해서 Pod 목록을 가져오고, 5가지 이상 패턴을 감지한다.

```python
def check_pods(namespace):
    """Pod 상태 이상 감지"""
    result = k8s_get(f"/api/v1/namespaces/{namespace}/pods")
    issues = []

    for pod in result.get("items", []):
        name = pod["metadata"]["name"]
        phase = pod.get("status", {}).get("phase", "Unknown")

        # Completed(Job/CronJob)는 정상
        if phase == "Succeeded":
            continue

        # Pod phase 이상 (Pending, Failed 등)
        if phase not in ("Running", "Succeeded"):
            issues.append({"type": "POD_PHASE", "pod": name, "phase": phase})
            continue

        # Container 상태 확인
        for cs in pod.get("status", {}).get("containerStatuses", []):
            # CrashLoopBackOff, OOMKilled 등
            waiting = cs.get("state", {}).get("waiting", {})
            if waiting.get("reason") in (
                "CrashLoopBackOff", "Error", "OOMKilled",
                "ImagePullBackOff", "ErrImagePull"
            ):
                issues.append({
                    "type": waiting["reason"],
                    "pod": name,
                    "container": cs["name"],
                    "message": waiting.get("message", "")[:200],
                })

            # 재시작 횟수 5회 이상
            if cs.get("restartCount", 0) >= 5:
                issues.append({
                    "type": "HIGH_RESTARTS",
                    "pod": name,
                    "restarts": cs["restartCount"],
                })
    return issues
```

감지하는 이상 패턴:

| 패턴 | 의미 | 긴급도 |
|------|------|--------|
| `CrashLoopBackOff` | 컨테이너 반복 크래시 | Critical |
| `OOMKilled` | 메모리 초과로 강제 종료 | Critical |
| `ImagePullBackOff` | 이미지 다운로드 실패 | Critical |
| `HIGH_RESTARTS` | 5회 이상 재시작 | Warning |
| `NOT_READY` | 헬스체크 실패 | Warning |

### K8s API 호출: CF Tunnel 경유

Lambda도 ECS Agent와 마찬가지로 Cloudflare Tunnel을 통해 K8s API에 접근한다.

```python
def k8s_get(path):
    """K8s API GET 요청 (CF Tunnel 경유)"""
    url = f"{K8S_API_URL}{path}"
    headers = {
        "Authorization": f"Bearer {K8S_TOKEN}",
        "CF-Access-Client-Id": CF_ACCESS_CLIENT_ID,
        "CF-Access-Client-Secret": CF_ACCESS_CLIENT_SECRET,
        # CF Bot Fight Mode가 Python-urllib를 차단하므로 커스텀 UA
        "User-Agent": "AgentTeam-Healthcheck/1.0",
    }
    req = urllib.request.Request(url, headers=headers)
    ctx = ssl.create_default_context()
    with urllib.request.urlopen(req, timeout=10, context=ctx) as resp:
        return json.loads(resp.read().decode())
```

> Lambda는 `requests` 라이브러리를 사용하지 않았다. Lambda 런타임에 기본 포함된 `urllib`만 사용하면 레이어(Layer) 추가 없이 배포할 수 있기 때문이다.

---

## 3. 쿨다운: 알림 폭풍 방지

5분마다 Lambda가 실행되면, Pod가 계속 죽어있는 동안 매번 Bedrock AI 분석 + Discord 알림이 발생한다. 30분 동안 12번의 알림이 Discord를 도배하고, Bedrock 비용도 누적된다.

```
문제:
  03:00 CrashLoopBackOff 발생
  03:05 Lambda → AI 분석 → Discord 알림 ← 필요
  03:10 Lambda → AI 분석 → Discord 알림 ← 중복
  03:15 Lambda → AI 분석 → Discord 알림 ← 중복
  ...
  03:55 총 12번 알림, Bedrock 12번 호출
```

### SSM Parameter Store로 쿨다운 구현

```python
# SSM Parameter Store에 마지막 알림 시간을 저장
SSM_PARAM_NAME = "/agent-team/healthcheck/last-alert"
COOLDOWN_MINUTES = 30

def check_cooldown():
    """마지막 알림 후 30분 이내면 True (스킵)"""
    ssm = boto3.client("ssm", region_name="ap-northeast-2")
    try:
        resp = ssm.get_parameter(Name=SSM_PARAM_NAME)
        last_alert = datetime.fromisoformat(resp["Parameter"]["Value"])
        elapsed = (datetime.now(timezone.utc) - last_alert).total_seconds() / 60
        if elapsed < COOLDOWN_MINUTES:
            print(f"[cooldown] 마지막 알림 {elapsed:.0f}분 전 — 스킵")
            return True
        return False
    except ssm.exceptions.ParameterNotFound:
        return False  # 첫 실행
```

SSM Parameter Store를 선택한 이유: DynamoDB는 테이블 생성 + IAM 설정이 필요하고, S3는 단일 값 저장에 과하다. SSM은 키-값 하나를 저장하는 데 추가 리소스가 필요 없고, IAM 정책에 `ssm:GetParameter`/`ssm:PutParameter`만 추가하면 된다.

```
쿨다운 적용 후:
  03:00 CrashLoopBackOff 발생
  03:05 Lambda → AI 분석 → Discord 알림 → 쿨다운 시작
  03:10 Lambda → 이상 감지 → 쿨다운 중 → 스킵
  03:15 Lambda → 이상 감지 → 쿨다운 중 → 스킵
  ...
  03:35 Lambda → 이상 감지 → 쿨다운 해제 → AI 분석 → Discord 알림
```

---

## 4. ECS 자동 기동: Agent를 깨우다

Lambda가 이상을 감지하면, ECS Agent 서비스의 `desired_count`를 0에서 1로 변경한다.

```python
def start_ecs_agent():
    """ECS Agent 서비스를 자동 기동 (desired_count: 0 → 1)"""
    ecs = boto3.client("ecs", region_name="ap-northeast-2")

    # 이미 실행 중이면 중복 기동 방지
    resp = ecs.describe_services(cluster=ECS_CLUSTER, services=[ECS_SERVICE])
    running = resp["services"][0].get("runningCount", 0)
    if running > 0:
        print(f"[ECS] 이미 실행 중 (running={running}) — 기동 생략")
        return

    # desired_count를 1로 올려서 Fargate 태스크 시작
    ecs.update_service(
        cluster=ECS_CLUSTER,
        service=ECS_SERVICE,
        desiredCount=1,
    )
    print(f"[ECS] Agent 기동 요청 완료: desired_count=1")
```

평소에 `desired_count=0`으로 유지하는 이유: Fargate는 실행 시간에 비례해서 과금된다. Agent가 24시간 대기하면 월 ~$30이지만, 장애 시에만 기동하면 월 ~$1 미만이다.

### Lambda 전체 흐름

```python
def lambda_handler(event, context):
    # 1. Pod 상태 확인
    issues = check_pods(NAMESPACE)
    if not issues:
        return {"statusCode": 200, "body": "OK - no issues"}

    # 2. 쿨다운 확인
    if check_cooldown():
        return {"statusCode": 200, "body": "skipped - cooldown"}

    # 3. 이상 Pod 로그 수집
    logs_map = {}
    for pod_name in set(i.get("pod") for i in issues if i.get("pod")):
        logs_map[pod_name] = get_pod_logs(NAMESPACE, pod_name)

    # 4. Bedrock AI 분석
    analysis = analyze_with_bedrock(issues, logs_map)

    # 5. Discord webhook 알림
    send_discord(f"🚨 K8s 이상 감지\n{analysis}")

    # 6. ECS Agent 자동 기동
    start_ecs_agent()

    # 7. 쿨다운 타임스탬프 갱신
    update_cooldown()
```

---

## 5. AUTO_DIAGNOSE: Agent가 스스로 시작하는 모드

ECS Agent가 기동되면 Discord Bot이 연결된다. 이때 `AUTO_DIAGNOSE=true` 환경변수가 설정되어 있으면, 사람의 명령 없이 자동으로 Orchestrator를 실행한다.

```python
# 환경변수
AUTO_DIAGNOSE = os.getenv("AUTO_DIAGNOSE", "false").lower() == "true"
DIAGNOSE_CHANNEL_ID = os.getenv("DIAGNOSE_CHANNEL_ID", "")

@client.event
async def on_ready():
    print(f"Bot 연결 성공: {client.user}", flush=True)

    # 자율 진단 모드
    if AUTO_DIAGNOSE and DIAGNOSE_CHANNEL_ID:
        channel = await client.fetch_channel(int(DIAGNOSE_CHANNEL_ID))
        await channel.send(
            "🤖 **자율 진단 모드 활성화**\n"
            "Lambda 헬스체크가 K8s 이상을 감지하여 Agent를 자동 기동했습니다.\n"
            "자동으로 장애 진단을 시작합니다..."
        )
        await run_orchestrator(
            channel,
            "Lambda 헬스체크에서 K8s blog-system 네임스페이스 이상 감지. "
            "Pod 상태, 로그, 리소스 사용량을 확인하고 원인 분석 및 "
            "1차 대응 방안을 수립하라."
        )
```

`on_ready()`는 Discord Bot이 WebSocket 연결에 성공했을 때 한 번 호출된다. Lambda가 "이상이 있다"고 판단해서 ECS를 깨운 것이므로, Bot이 연결되는 즉시 진단을 시작하는 것이 자연스럽다.

---

## 6. Orchestrator 패턴: Agent 간 협업

### Blackboard 패턴

4개 Agent(Infra/Frontend/Backend/Reviewer)가 순차적으로 작업할 때, 이전 Agent의 결과를 다음 Agent에게 어떻게 전달할 것인가?

**변수 릴레이 방식** (사용하지 않은 방법):
```python
# 문제: 변수가 늘어날수록 함수 시그니처가 복잡해지고, 상태 추적이 어려움
diagnosis = ask_infra(problem)
fix = ask_backend(problem, diagnosis)
review = ask_reviewer(problem, diagnosis, fix)
```

**Blackboard 패턴** (채택한 방법):
```python
# 공유 상태 객체에 모든 Agent가 읽고 쓸 수 있음
state = {
    "problem": problem_description,
    "diagnosis": None,        # Infra Agent가 작성
    "fix_agent": None,        # Orchestrator가 결정
    "fix_result": None,       # Backend/Frontend Agent가 작성
    "pr_number": None,        # fix Agent가 PR 생성 후 기록
    "review_verdict": None,   # Reviewer가 판정
    "review_feedback": None,  # REQUEST_CHANGES 시 피드백
    "review_round": 0,        # 현재 리뷰 라운드
}
```

Blackboard 패턴은 Agent가 추가되거나 흐름이 변경될 때 `state`에 필드만 추가하면 된다. 함수 파라미터를 수정할 필요가 없다.

### 수정 Agent 결정: 키워드 기반

Infra Agent의 진단 결과에서 어떤 Agent에게 수정을 맡길지 결정해야 한다.

```python
# LLM 호출 없이 키워드로 판단 — 비용 $0
diag_lower = state["diagnosis"].lower()
if any(kw in diag_lower for kw in ["frontend", "hugo", "css", "template"]):
    state["fix_agent"] = "frontend"
elif any(kw in diag_lower for kw in ["backend", "spring", "java", "api"]):
    state["fix_agent"] = "backend"
else:
    state["fix_agent"] = None  # 인프라 문제 → 코드 수정 불필요
```

LLM으로 판단하면 더 정확하겠지만, 이 시점에서 추가 Bedrock 호출은 비용 낭비다. Infra Agent가 이미 "Spring Boot API 500 에러 발생" 같은 구체적인 진단을 내놓기 때문에 키워드 매칭으로 충분하다.

### Reviewer 피드백 루프 (최대 2회)

단방향 릴레이(`진단→수정→리뷰→끝`)가 아니라, Reviewer가 문제를 발견하면 수정 Agent에게 돌려보내는 양방향 루프다.

```
Round 1:
  Infra Agent → "WAS Pod CrashLoopBackOff, NPE 발생"
  Backend Agent → PR #42 생성 (null check 추가)
  Reviewer Agent → "REQUEST_CHANGES: catch 블록이 비어있음"

Round 2:
  Backend Agent → 피드백 반영하여 PR #43 생성
  Reviewer Agent → "APPROVE"

최종: 사람에게 "PR #43 머지해주세요" 알림
```

```python
MAX_REVIEW_LOOPS = 2

while state["review_round"] < MAX_REVIEW_LOOPS:
    state["review_round"] += 1

    # 수정 Agent에게 이전 피드백을 포함한 프롬프트 전달
    if state["review_feedback"]:
        fix_prompt = (
            f"Infra Agent 분석:\n{state['diagnosis']}\n\n"
            f"이전 코드:\n{state['fix_result']}\n\n"
            f"Reviewer 피드백:\n{state['review_feedback']}\n\n"
            f"피드백을 반영하여 수정하고 새 PR을 생성해주세요."
        )
    else:
        fix_prompt = (
            f"Infra Agent 분석:\n{state['diagnosis']}\n\n"
            f"수정 코드를 작성하고 PR을 생성해주세요."
        )

    state["fix_result"] = ask_bedrock(fix_agent, fix_prompt)

    # Reviewer 판정 파싱
    review_result = ask_bedrock("reviewer", review_prompt)
    if "APPROVE" in review_result.upper():
        state["review_verdict"] = "APPROVE"
        break  # 승인 → 루프 종료
    else:
        state["review_feedback"] = review_result
        # → 다음 라운드로 피드백 전달
```

최대 2회로 제한한 이유: Agent끼리 무한 루프에 빠지면 Bedrock 비용이 폭발한다. 2회 안에 해결 못 하면 사람이 직접 봐야 할 만큼 복잡한 문제다.

---

## 7. 트러블슈팅

### Lambda → CF Tunnel 403

**증상**: Lambda에서 K8s API 호출 시 403 반환, 응답 본문이 Cloudflare HTML 페이지

**원인**: [#2 글](/study/2026-03-13-ai-agent-ecs-cloudflare-tunnel/)에서 ECS Agent의 Bot Fight Mode 문제를 해결했지만, Lambda는 별도의 Python 런타임(urllib)을 사용한다. `User-Agent: Python-urllib/3.x`가 Bot Fight Mode에 다시 걸렸다.

**해결**: 커스텀 User-Agent 추가

```python
headers = {
    "Authorization": f"Bearer {K8S_TOKEN}",
    "CF-Access-Client-Id": CF_ACCESS_CLIENT_ID,
    "CF-Access-Client-Secret": CF_ACCESS_CLIENT_SECRET,
    "User-Agent": "AgentTeam-Healthcheck/1.0",  # ← 추가
}
```

### Bedrock IAM: Cross-Region Inference Profile

**증상**: `AccessDeniedException` — Haiku 4.5 모델 호출 실패

**원인**: Bedrock의 Cross-Region Inference Profile을 사용하면 모델 ID 앞에 `us.` 접두사가 붙는다(`us.anthropic.claude-haiku-4-5-20251001-v1:0`). IAM 정책에서 `foundation-model/*`만 허용하면 `inference-profile/*` ARN이 거부된다.

**해결**: IAM 정책에 두 가지 Resource ARN 모두 허용

```hcl
Resource = [
  "arn:aws:bedrock:*::foundation-model/*",
  "arn:aws:bedrock:*:*:inference-profile/*"
]
```

### Discord Webhook 403

**증상**: Lambda에서 Discord webhook 호출 시 403

**원인**: Bot Fight Mode와 동일한 문제. Discord API도 `Python-urllib` User-Agent를 차단한다.

**해결**: webhook 호출에도 커스텀 User-Agent 추가

```python
req = urllib.request.Request(
    DISCORD_WEBHOOK_URL,
    data=data,
    headers={
        "Content-Type": "application/json",
        "User-Agent": "AgentTeam-Healthcheck/1.0",
    },
)
```

---

## 8. Terraform 인프라 구성

### Lambda + EventBridge

```hcl
# Lambda 함수
resource "aws_lambda_function" "healthcheck" {
  function_name = "agent-team-healthcheck"
  runtime       = "python3.12"
  handler       = "healthcheck.lambda_handler"
  timeout       = 60
  memory_size   = 128

  environment {
    variables = {
      K8S_API_URL             = "https://k8s-api.jiminhome.shop"
      K8S_TOKEN               = var.k8s_token
      CF_ACCESS_CLIENT_ID     = var.cf_access_client_id
      CF_ACCESS_CLIENT_SECRET = var.cf_access_client_secret
      DISCORD_WEBHOOK_URL     = var.discord_webhook_url
      ECS_CLUSTER             = aws_ecs_cluster.main.name
      ECS_SERVICE             = aws_ecs_service.agent.name
      COOLDOWN_MINUTES        = "30"
    }
  }
}

# EventBridge: 5분마다 Lambda 실행
resource "aws_cloudwatch_event_rule" "healthcheck_cron" {
  name                = "agent-team-healthcheck-5min"
  schedule_expression = "rate(5 minutes)"
  state               = "DISABLED"  # 포트폴리오 데모용, 필요 시 ENABLED
}
```

`state = "DISABLED"`인 이유: 포트폴리오 목적이므로 평상시에는 비용을 발생시키지 않는다. 데모 시 `state = "ENABLED"`로 변경하면 5분마다 자동 헬스체크가 시작된다.

---

## 성과

| 항목 | Before (수동 감시) | After (자율 대응) |
|------|-------------------|------------------|
| 장애 감지 | 사람이 직접 확인 | Lambda 5분 주기 자동 감지 |
| 알림 | 없음 | Discord webhook 자동 알림 + AI 분석 |
| 진단 | 사람이 kubectl 실행 | Agent가 자동으로 상세 진단 |
| 수정 | 사람이 코드 작성 | Agent가 PR 생성 + Reviewer 피드백 반영 |
| 사람의 역할 | 감지→진단→수정→배포 전 과정 | PR 머지 승인만 |
| 비용 | $0 (대신 사람 시간 소모) | Lambda 거의 무료 + ECS 필요 시만 기동 |
| 알림 폭풍 | N/A | SSM 쿨다운으로 30분 간격 제한 |

---

## 전체 흐름 요약

```
[EventBridge] ── rate(5 minutes)
     │
     ▼
[Lambda healthcheck]
     │  K8s API → CF Tunnel → Pod 상태 확인
     │
     ├── 정상 → 로그 남기고 종료
     │
     └── 이상 감지 →
           │
           ├── [쿨다운 확인] ── 30분 이내 → 스킵
           │
           └── 쿨다운 해제 →
                 │
                 ├── Bedrock Haiku → AI 원인 분석
                 ├── Discord webhook → 🚨 알림
                 ├── ECS desired_count: 0→1 → Agent 기동
                 └── SSM 타임스탬프 갱신
                       │
                       ▼
                  [ECS Agent Bot]
                       │  on_ready() + AUTO_DIAGNOSE=true
                       │
                       ├── Phase 1: Infra Agent ── kubectl 상세 진단
                       ├── Phase 2: Backend Agent ── 수정 코드 + PR 생성
                       ├── Phase 3: Reviewer Agent ── 리뷰
                       │     └── REQUEST_CHANGES → Phase 2로 (최대 2회)
                       │
                       └── Discord 최종 보고
                            "PR #43 머지해주세요 @Tech Lead"
```

---

## 다음 단계

Agent가 자율적으로 동작하기 시작하면 새로운 문제가 생긴다: **Agent가 Bedrock을 얼마나 호출하는지, 비용이 얼마나 나오는지 보이지 않는다.**

Phase 6에서는 Langfuse로 LLM 관측성을 구축하고, 월 비용이 한도를 초과하면 ECS가 스스로 종료하는 자동 차단 메커니즘을 구현한다.

> 다음 글: [LLM 관측성 — Langfuse로 Agent 비용/성능 추적 #4](/study/2026-03-14-ai-agent-langfuse-observability/)
