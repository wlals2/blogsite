---
title: "[AI Agent 시리즈 #4] LLM 관측성 — Langfuse로 Agent 비용/성능 추적하기"
date: 2026-03-14T14:00:00+09:00
summary: "Langfuse로 Agent별 Bedrock 호출을 Trace/Generation 단위로 추적하고, SSM Parameter Store로 월별 비용을 누적하고, 한도 초과 시 ECS가 스스로 종료하는 자동 차단 구현기"
tags: ["ai-agent", "langfuse", "observability", "cost-control", "bedrock", "ecs-fargate", "homelab"]
categories: ["study", "Observability"]
series: ["AI Agent 시리즈"]
draft: false
showtoc: true
tocopen: true
---

> **시리즈**: [AI Agent 시리즈]
> - 이전 글: [자율 장애 대응 — Lambda 헬스체크에서 Agent 자동 기동까지 #3](/study/2026-03-14-ai-agent-autonomous-fault-response/)

## 배경

[이전 글](/study/2026-03-14-ai-agent-autonomous-fault-response/)에서 Lambda 헬스체크 → ECS 자동 기동 → Orchestrator 자율 진단까지 구축했다.

Agent가 자율적으로 동작하기 시작하자, 보이지 않는 문제가 생겼다.

```
Q. 지난주 Agent가 Bedrock을 몇 번 호출했는가?
A. 모른다.

Q. 어떤 Agent가 가장 많은 토큰을 소비하는가?
A. 모른다.

Q. 이번 달 LLM 비용이 얼마인가?
A. AWS 청구서가 나와야 안다 (다음 달 초).

Q. Agent가 무한 루프에 빠져서 비용이 폭발하면?
A. 알 방법이 없다.
```

**측정할 수 없으면 관리할 수 없다.** LLM 호출마다 토큰 수, 비용, 지연시간을 추적해야 하고, 비용이 한도를 넘으면 자동으로 차단해야 한다.

---

## 이 글에서 다루는 것

```
1. Langfuse란 — LLM 전용 관측성 플랫폼
2. Trace/Generation 연동 — Agent별 Bedrock 호출 추적
3. SSM 월별 비용 누적 — 호출마다 비용 계산 + 저장
4. 자동 차단 — 한도 초과 시 ECS 스스로 종료
5. 트러블슈팅 — Langfuse SDK v4 호환성, Discord Bot 403
6. E2E 검증 — Langfuse 대시보드에서 실제 비용 확인
```

---

## 1. Langfuse란

Langfuse는 LLM 애플리케이션을 위한 관측성 플랫폼이다. Datadog이 서버 메트릭을 수집하듯, Langfuse는 LLM 호출의 입력/출력/토큰/비용/지연시간을 수집한다.

```
서버 관측성:            LLM 관측성:
  Prometheus → 메트릭     Langfuse → Trace/Generation
  Grafana → 시각화        Langfuse Dashboard → 시각화
  AlertManager → 알림     비용 자동 차단 (직접 구현)
```

### 핵심 개념

| 개념 | 설명 | 비유 |
|------|------|------|
| **Trace** | 하나의 사용자 요청 전체 | HTTP Request 하나 |
| **Generation** | Trace 안의 개별 LLM 호출 | DB Query 하나 |
| **Span** | LLM 외 작업 (도구 실행 등) | 함수 호출 하나 |

하나의 `!infra pod 상태 확인` 명령이 하나의 Trace가 되고, 그 안에서 Bedrock을 3번 호출하면(1차 호출 → kubectl Tool Use → 2차 호출 → ...) 3개의 Generation이 기록된다.

```
Trace: "agent-infra"
  ├── Generation: invoke-1 (input=1200 tokens, output=400 tokens)
  ├── [Tool Use: kubectl get pods]
  ├── Generation: invoke-2 (input=2100 tokens, output=600 tokens)
  ├── [Tool Use: kubectl logs]
  └── Generation: invoke-3 (input=3500 tokens, output=800 tokens) ← 최종 응답
```

### 왜 Langfuse인가

| 옵션 | 장점 | 단점 |
|------|------|------|
| CloudWatch만 사용 | 추가 도구 불필요 | LLM 전용 분석(토큰/비용 추적) 없음 |
| Langfuse Cloud 무료 | Trace/비용/지연시간 대시보드 | 50K observations/월 제한 |
| LangSmith | LangChain 생태계 통합 | LangChain 미사용 시 이점 없음 |

이 프로젝트는 LangChain을 사용하지 않고 Bedrock `invoke_model`을 직접 호출하므로, Langfuse Python SDK로 수동 계측(manual instrumentation)하는 것이 가장 간단하다.

---

## 2. Trace/Generation 연동

### Langfuse 클라이언트 초기화

```python
from langfuse import Langfuse

langfuse = Langfuse(
    public_key=os.getenv("LANGFUSE_PUBLIC_KEY"),
    secret_key=os.getenv("LANGFUSE_SECRET_KEY"),
    host=os.getenv("LANGFUSE_HOST", "https://cloud.langfuse.com"),
)
```

키는 Langfuse Cloud 콘솔에서 발급하고, ECS Task Definition 환경변수로 주입한다. 코드에 키를 하드코딩하지 않는다.

### ask_bedrock() 함수: 핵심 연동 코드

```python
def ask_bedrock(agent_name: str, user_message: str) -> str:
    """Bedrock Claude 호출 (Langfuse Trace/Generation 포함)"""
    agent = AGENTS[agent_name]
    messages = [{"role": "user", "content": user_message}]
    total_input_tokens = 0
    total_output_tokens = 0

    # 1 Trace = 1 사용자 요청
    trace = langfuse.trace(
        name=f"agent-{agent_name}",
        input=user_message,
        metadata={"agent": agent_name, "model": agent["model_id"]},
    )

    # 1차 Bedrock 호출 → 1 Generation
    gen = trace.generation(
        name="invoke-1",
        model=agent["model_id"],
        input=messages,
    )
    response = bedrock.invoke_model(
        modelId=agent["model_id"],
        body=json.dumps({
            "anthropic_version": "bedrock-2023-05-31",
            "max_tokens": agent["max_tokens"],
            "system": agent["system_prompt"],
            "tools": agent["tools"],
            "messages": messages,
        }),
    )
    result = json.loads(response["body"].read())
    usage = result["usage"]
    total_input_tokens += usage["input_tokens"]
    total_output_tokens += usage["output_tokens"]

    # Generation 종료 — 토큰 수 기록
    gen.end(
        output=result["content"],
        usage={"input": usage["input_tokens"], "output": usage["output_tokens"]},
    )
```

### Tool Use 루프에서의 Generation 추적

Agent가 Tool Use를 요청하면 추가 Bedrock 호출이 발생한다. 각 호출마다 새로운 Generation을 생성한다.

```python
    loop_count = 0
    while result.get("stop_reason") == "tool_use" and loop_count < agent["max_loops"]:
        loop_count += 1

        # Tool 실행 (kubectl, GitHub API 등)
        # ... (tool_results 수집)

        # 다음 Bedrock 호출 → 새 Generation
        gen = trace.generation(
            name=f"invoke-{loop_count + 1}",
            model=agent["model_id"],
            input=messages,
        )
        response = bedrock.invoke_model(...)
        result = json.loads(response["body"].read())
        usage = result["usage"]
        total_input_tokens += usage["input_tokens"]
        total_output_tokens += usage["output_tokens"]
        gen.end(
            output=result["content"],
            usage={"input": usage["input_tokens"], "output": usage["output_tokens"]},
        )

    # Trace 최종 업데이트
    trace.update(output=final_response)
```

이렇게 하면 Langfuse 대시보드에서 "Infra Agent가 3번 호출에 총 7,500 토큰을 사용했다"를 한 눈에 볼 수 있다.

---

## 3. SSM 월별 비용 누적

### 비용 계산 로직

Bedrock은 입력/출력 토큰 수에 따라 과금된다. `invoke_model` 응답의 `usage` 필드에서 토큰 수를 가져와 단가를 곱한다.

```python
# Bedrock 단가 (Cross-Region Inference 마크업 포함)
MODEL_COST = {
    "anthropic.claude-3-haiku-20240307-v1:0": {
        "input":  0.25 / 1_000_000,   # $0.25/1M tokens
        "output": 1.25 / 1_000_000,   # $1.25/1M tokens
    },
    "us.anthropic.claude-haiku-4-5-20251001-v1:0": {
        "input":  1.00 / 1_000_000,   # $1.00/1M tokens
        "output": 5.00 / 1_000_000,   # $5.00/1M tokens
    },
}
```

### SSM에 월별 누적 저장

```python
def _monthly_cost_param_name() -> str:
    """월별 SSM 파라미터 이름 (자동 월 구분)"""
    month = datetime.now(timezone.utc).strftime("%Y-%m")
    return f"/agent-team/costs/monthly-{month}"

def _add_cost(delta: float) -> float:
    """호출 비용을 월별 SSM에 누적"""
    ssm = boto3.client("ssm", region_name="ap-northeast-2")
    param = _monthly_cost_param_name()

    # 현재 누적값 조회
    try:
        resp = ssm.get_parameter(Name=param)
        current = float(resp["Parameter"]["Value"])
    except ssm.exceptions.ParameterNotFound:
        current = 0.0  # 월초 첫 호출

    # 새 합계 저장
    new_total = current + delta
    ssm.put_parameter(
        Name=param, Value=str(new_total),
        Type="String", Overwrite=True,
    )
    return new_total
```

SSM 파라미터 이름에 `2026-03` 같은 월 정보가 포함되므로, 매월 자동으로 새 파라미터가 생성된다. 이전 달의 비용은 별도 파라미터에 남아있어서 월별 비교도 가능하다.

### finally 블록에서 비용 추적

```python
def ask_bedrock(agent_name, user_message):
    try:
        # ... Bedrock 호출 + Tool Use 루프
        return final_response
    except Exception as e:
        return f"[에러] {e}"
    finally:
        # 예외 발생 시에도 토큰은 소비되었으므로 finally에서 처리
        cost_rate = MODEL_COST.get(agent["model_id"], {"input": 0, "output": 0})
        call_cost = (
            total_input_tokens * cost_rate["input"] +
            total_output_tokens * cost_rate["output"]
        )
        if call_cost > 0:
            new_total = _add_cost(call_cost)
            print(
                f"[cost] {agent_name}: ${call_cost:.6f} | "
                f"월 누계: ${new_total:.4f} / ${MONTHLY_COST_LIMIT_USD:.0f}",
                flush=True,
            )
            if new_total >= MONTHLY_COST_LIMIT_USD:
                threading.Thread(
                    target=_shutdown_due_to_cost,
                    args=(new_total,),
                    daemon=True,
                ).start()
        langfuse.flush()  # Langfuse 이벤트 즉시 전송
```

`finally`를 사용하는 이유: Bedrock 호출 도중 예외가 발생해도 이미 토큰이 소비되었다. 비용 추적을 `try` 블록 안에 넣으면 예외 시 비용이 기록되지 않는다.

---

## 4. 자동 차단: ECS 스스로 종료

### 왜 자동 차단이 필요한가

Agent가 자율적으로 동작하면 사람이 비용을 실시간으로 감시할 수 없다. Orchestrator의 피드백 루프가 예상보다 많은 토큰을 소비하거나, Tool Use 루프가 `max_loops` 근처까지 반복되면 비용이 급증할 수 있다.

```
시나리오:
  새벽 3시, Lambda가 ECS Agent를 자동 기동
  Agent가 Orchestrator 실행 → Bedrock 반복 호출
  Reviewer가 계속 REQUEST_CHANGES → 피드백 루프 2회
  각 Agent 호출에 4000+ 토큰 × 4개 Agent × 2라운드
  = 하룻밤에 수십 달러 가능
```

### 차단 메커니즘

```python
MONTHLY_COST_LIMIT_USD = float(os.getenv("MONTHLY_COST_LIMIT", "20.0"))

def _shutdown_due_to_cost(total_cost: float):
    """비용 한도 초과 시 자동 종료"""
    # 1. Discord webhook으로 알림
    msg = (
        f"⚠️ **Agent 월 비용 한도 초과 — 자동 종료**\n"
        f"이번 달 누적 비용: **${total_cost:.4f}** "
        f"(한도: ${MONTHLY_COST_LIMIT_USD:.0f})\n"
        f"ECS Agent 서비스를 종료합니다."
    )
    # ... webhook 전송

    # 2. ECS desiredCount=0 (자기 자신을 종료)
    ecs = boto3.client("ecs", region_name="ap-northeast-2")
    ecs.update_service(
        cluster=os.getenv("ECS_CLUSTER_NAME"),
        service=os.getenv("ECS_SERVICE_NAME"),
        desiredCount=0,
    )
```

**별도 스레드에서 실행하는 이유**: 종료 로직이 Discord webhook + ECS API 호출을 포함하므로 수 초가 걸린다. 현재 요청에 대한 응답을 Discord에 먼저 보내고, 백그라운드에서 종료하면 사용자는 마지막 응답을 받을 수 있다.

```python
# daemon=True: 메인 프로세스 종료 시 함께 종료
threading.Thread(
    target=_shutdown_due_to_cost,
    args=(new_total,),
    daemon=True,
).start()
```

### IAM 최소 권한 추가

자동 차단을 위해 ECS Task Role에 두 가지 권한을 추가했다.

```hcl
# SSM 비용 추적 (읽기/쓰기)
{
  Effect   = "Allow"
  Action   = ["ssm:GetParameter", "ssm:PutParameter"]
  Resource = "arn:aws:ssm:ap-northeast-2:<ACCOUNT_ID>:parameter/agent-team/*"
}

# ECS 자기 종료 (자기 서비스만)
{
  Effect   = "Allow"
  Action   = ["ecs:UpdateService"]
  Resource = "arn:aws:ecs:ap-northeast-2:<ACCOUNT_ID>:service/agent-team-cluster/*"
}
```

`ecs:UpdateService`를 모든 서비스가 아닌 `agent-team-cluster/*`로 한정했다. Agent가 자기 서비스만 종료할 수 있고, 다른 ECS 서비스에는 접근할 수 없다.

---

## 5. 트러블슈팅

### Langfuse SDK v4 호환성 문제

**증상**: ECS Agent가 시작 직후 크래시

```
AttributeError: 'Langfuse' object has no attribute 'trace'
```

**원인**: `requirements.txt`에 `langfuse>=2.0.0`으로 지정해서 Docker 빌드 시 v4.0.0이 설치되었다. Langfuse v4는 API를 전면 변경하여 `trace()`, `generation()` 메서드가 제거되었다.

```
v2.x API:                          v4.x API:
  langfuse.trace()                   langfuse.create_trace_id()
  trace.generation()                 langfuse.start_observation()
```

**해결**: 상한 버전을 명시하여 v2 계열 고정

```
# requirements.txt
langfuse>=2.0.0,<3.0.0
```

Docker 이미지를 재빌드하고 ECR에 Push한 후 ECS를 force-new-deployment로 재시작했다.

```bash
# Docker 빌드 → ECR Push → ECS 재배포
docker build -t agent-team-server .
docker tag agent-team-server:latest <ECR_URI>:latest
docker push <ECR_URI>:latest
aws ecs update-service --cluster agent-team-cluster \
  --service agent-team-service --force-new-deployment
```

> **교훈**: Python 의존성에 상한 버전 없이 `>=`만 쓰면 Docker 빌드 시점에 따라 다른 버전이 설치된다. 특히 LLM SDK는 빠르게 변하므로 메이저 버전 상한(`<3.0.0`)은 필수다.

### Discord Bot 403 Missing Access

**증상**: AUTO_DIAGNOSE 모드에서 채널에 메시지를 보내려고 할 때 403

```
[AUTO_DIAGNOSE] 채널 fetch 실패: 403 Forbidden (error code: 50001): Missing Access
```

**원인**: Bot이 해당 Discord 서버에 초대되지 않았다. Discord Bot은 서버별로 초대가 필요하다 — 하나의 서버에서 작동한다고 다른 서버에서도 작동하는 것이 아니다.

**해결 순서**:
1. Discord Developer Portal → OAuth2 → URL Generator
2. Scopes: `bot` 선택
3. Bot Permissions: 최소한 `Send Messages`, `View Channels` 선택
4. 생성된 URL로 접속 → 대상 서버 선택 → 승인

### Webhook과 Bot의 차이

이 프로젝트에서는 두 가지 메시지 전송 방식을 모두 사용한다.

| | Discord Webhook | Discord Bot |
|--|----------------|-------------|
| 사용처 | Lambda 알림 | ECS Agent 양방향 대화 |
| 인증 | URL에 토큰 포함 | Bot Token + OAuth2 초대 |
| 기능 | 메시지 전송만 (단방향) | 메시지 수신/전송, 채널 관리 |
| 서버 참여 | 불필요 | 필요 (서버별 초대) |
| 이름 표시 | Webhook 설정 이름 | Bot 계정 이름 |

Lambda는 "이상 감지됨" 알림만 보내면 되므로 Webhook으로 충분하다. ECS Agent는 사용자의 `!infra` 명령을 수신하고 응답해야 하므로 Bot이 필요하다.

---

## 6. E2E 검증

### 테스트 시나리오

Discord에서 `ping`과 `get pods -n blog-system`을 보내고, Langfuse 대시보드에서 Trace를 확인한다.

### Discord 결과

```
사용자: ping
Bot: pong!

사용자: get pods -n blog-system
Bot: 🤖 Infra Agent
  blog-system 네임스페이스의 Pod 상태:
  - mysql-0: Running (Ready 2/2)
  - was-primary-xxx: Running (Ready 2/2)
  - web-xxx: Running (Ready 2/2)
  ...
```

### Langfuse 대시보드

Langfuse Cloud 대시보드에서 4개의 Trace와 총 비용 $0.12를 확인했다.

```
Traces:
  agent-infra  │ invoke-1 → invoke-2 → invoke-3  │ $0.03
  agent-infra  │ invoke-1 → invoke-2              │ $0.02
  agent-infra  │ invoke-1                          │ $0.04
  agent-infra  │ invoke-1 → invoke-2              │ $0.03
                                          총 비용:   $0.12
```

### SSM Parameter Store 확인

```bash
aws ssm get-parameter --name "/agent-team/costs/monthly-2026-03"
# Value: "0.120171"
```

Langfuse 대시보드의 총 비용($0.12)과 SSM에 누적된 비용($0.120171)이 일치한다. 비용 추적 로직이 정상 동작하는 것을 확인했다.

---

## 성과

| 항목 | Before (블랙박스) | After (관측 가능) |
|------|-------------------|------------------|
| LLM 호출 추적 | CloudWatch 로그만 | Langfuse Trace/Generation 단위 추적 |
| 토큰 사용량 | 모름 | 호출마다 input/output 토큰 기록 |
| 비용 추적 | 월말 AWS 청구서 | 실시간 SSM 누적 + Langfuse 대시보드 |
| 비용 제어 | 없음 | $20 초과 시 ECS 자동 종료 + Discord 알림 |
| Agent별 분석 | 불가 | Langfuse에서 agent-infra / agent-backend 별도 추적 |
| 의존성 안정성 | `>=` 오픈 | `>=2.0.0,<3.0.0` 상한 고정 |

---

## AI Agent 시리즈 전체 요약

4편에 걸쳐 구축한 전체 시스템을 정리한다.

```
[#1] Agent 설계       : 4개 Agent + Tool Use + 안전장치
[#2] 클라우드 배포    : ECS Fargate + CF Tunnel Zero Trust
[#3] 자율 장애 대응   : Lambda 헬스체크 → ECS 자동 기동 → Orchestrator
[#4] LLM 관측성      : Langfuse 추적 + SSM 비용 누적 + 자동 차단
```

```
최종 아키텍처:

[EventBridge 5분 크론]
     │
     ▼
[Lambda] → K8s 이상 감지 → Discord 알림 + ECS 기동
     │
     ▼
[ECS Agent Bot]
     │  AUTO_DIAGNOSE → Orchestrator
     │
     ├── Infra Agent ── kubectl 진단
     ├── Backend Agent ── PR 생성
     ├── Reviewer Agent ── 리뷰 (피드백 루프)
     │
     ├── Langfuse ── Trace/Generation 추적
     ├── SSM ── 월별 비용 누적
     └── 비용 초과 → ECS 자동 종료

[사람의 역할: PR 머지 승인만]
```

사람이 하는 일은 **PR을 머지할지 말지 결정하는 것** 하나다. 장애 감지, 진단, 수정 코드 작성, 리뷰까지 Agent가 수행하고, GitOps 파이프라인(ArgoCD)이 배포한다.

> 시리즈 처음부터 읽기: [AI Agent로 스타트업급 팀 구성하기 #1](/study/2026-03-12-ai-agent-startup-team/)
