---
title: "[AI Agent 시리즈 #2] ECS Fargate에 Agent 서버 배포하기 — Cloudflare Tunnel로 홈랩 K8s와 Zero Trust 연결"
date: 2026-03-13T10:00:00+09:00
summary: "AI Agent를 AWS ECS Fargate에 배포하고, Cloudflare Tunnel + Access로 홈랩 K8s API에 Zero Trust 연결한 전체 과정과 Bot Fight Mode 트러블슈팅"
tags: ["ai-agent", "ecs-fargate", "cloudflare-tunnel", "terraform", "zero-trust", "homelab"]
categories: ["study", "Cloud & Terraform"]
series: ["AI Agent 시리즈"]
draft: false
showtoc: true
tocopen: true
---

> **시리즈**: [AI Agent 시리즈]
> - 이전 글: [AI Agent로 스타트업급 팀 구성하기 #1](/study/2026-03-12-ai-agent-startup-team/)
> - 다음 글: (예정)

## 배경

[이전 글](/study/2026-03-12-ai-agent-startup-team/)에서 Discord 기반 멀티 Agent 아키텍처를 설계하고, 홈랩에서 4개 Agent(Infra/Frontend/Backend/Reviewer)의 E2E 동작을 검증했다.

하지만 한 가지 구조적 문제가 있었다.

```
[홈랩 서버 장애 발생]
     ├── K8s 클러스터 죽음
     ├── Agent 서버도 같이 죽음
     └── "장애 발생" 알림을 보낼 주체가 없음
```

**모니터링 시스템이 모니터링 대상과 같은 서버에 있으면, 서버가 죽을 때 알림도 같이 죽는다.** PagerDuty, Datadog 같은 SaaS 모니터링 서비스가 고객 인프라 바깥에서 동작하는 이유와 같은 원리다.

Agent 서버를 AWS로 분리하면 홈랩이 죽어도 "응답 없음" 알림을 보낼 수 있다.

---

## 이 글에서 다루는 것

```
1. Dockerfile 작성 — Non-root + 시크릿 주입 설계
2. Terraform으로 AWS 인프라 코드화 — ECR, IAM, ECS
3. Cloudflare Tunnel + Access — 방화벽 오픈 없이 K8s API 연결
4. Bot Fight Mode 트러블슈팅 — AWS IP가 봇으로 차단된 이슈
5. E2E 검증 — Discord → ECS → CF Tunnel → K8s API → 응답
```

---

## 1. Dockerfile: 컨테이너 설계

### 핵심 설계 결정

```dockerfile
FROM python:3.10-slim

# Non-root 사용자
RUN groupadd -r agent && useradd -r -g agent -d /app -s /sbin/nologin agent

WORKDIR /app

# 레이어 캐시: 의존성 먼저 → 코드 변경 시 pip install 스킵
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY agent_bot.py .
COPY tools/ ./tools/

RUN chown -R agent:agent /app
USER agent

CMD ["python", "-u", "agent_bot.py"]
```

**kubectl 바이너리를 포함하지 않은 이유:**

홈랩 K8s API 앞에 Cloudflare Access가 있다. kubectl은 HTTP 헤더에 `CF-Access-Client-Id`/`CF-Access-Client-Secret`을 추가하는 표준 방법이 없다. 대신 Python `requests` 라이브러리로 K8s REST API를 직접 호출하면 CF Access 헤더와 Bearer Token을 동시에 전송할 수 있다.

```python
def _headers():
    h = {"Authorization": f"Bearer {K8S_TOKEN}"}
    if CF_ACCESS_CLIENT_ID:
        h["CF-Access-Client-Id"] = CF_ACCESS_CLIENT_ID
        h["CF-Access-Client-Secret"] = CF_ACCESS_CLIENT_SECRET
    return h
```

**시크릿 관리 원칙:** 이미지에 토큰/키를 절대 포함하지 않는다. 모든 자격증명은 ECS Task Definition의 환경변수로 주입한다.

---

## 2. Terraform: AWS 인프라 코드화

### 디렉터리 구조

```
infra-aws-terraform/
├── bootstrap/          # S3 + DynamoDB (state backend — 먼저 생성)
│   ├── main.tf
│   └── variables.tf
└── main/               # 실제 인프라
    ├── main.tf         # provider + backend 설정
    ├── ecr.tf          # Docker 이미지 저장소
    ├── iam.tf          # ECS Task Role (Bedrock + CloudWatch)
    ├── ecs.tf          # Fargate Task Definition + Service
    ├── variables.tf    # 변수 정의
    └── terraform.tfvars # 값 (.gitignore)
```

### state backend를 분리한 이유

Terraform state를 저장할 S3 + DynamoDB를 Terraform으로 만들려면 "닭이 먼저냐 달걀이 먼저냐" 문제가 생긴다. state backend가 아직 없으니 state를 저장할 곳이 없다.

```
해결: bootstrap/ → 로컬 state로 S3 + DynamoDB 먼저 생성
      main/      → S3 backend 사용
```

### IAM 최소 권한

```hcl
# ECS Task Role — Bedrock 호출 + CloudWatch 로그만 허용
resource "aws_iam_role_policy" "agent_bedrock" {
  policy = jsonencode({
    Statement = [{
      Effect   = "Allow"
      Action   = ["bedrock:InvokeModel", "bedrock:InvokeModelWithResponseStream"]
      Resource = "arn:aws:bedrock:*::foundation-model/*"
    }]
  })
}
```

EC2 인스턴스에 Access Key를 넣는 대신 ECS Task IAM Role을 사용하면:
- 코드에 키가 없으므로 유출 위험 제거
- Role 정책만 변경하면 권한 즉시 반영
- boto3가 자동으로 Task Role 자격증명을 사용

### ECS Task Definition — 환경변수 주입

```hcl
container_definitions = jsonencode([{
  environment = [
    { name = "DISCORD_BOT_TOKEN",      value = var.discord_bot_token },
    { name = "GITHUB_TOKEN",           value = var.github_token },
    { name = "K8S_API_URL",            value = "https://k8s-api.jiminhome.shop" },
    { name = "K8S_TOKEN",              value = var.k8s_token },
    { name = "CF_ACCESS_CLIENT_ID",    value = var.cf_access_client_id },
    { name = "CF_ACCESS_CLIENT_SECRET",value = var.cf_access_client_secret },
  ]
}])
```

> 프로덕션에서는 AWS Secrets Manager + `secrets` 블록을 사용해야 한다. 이 프로젝트는 포트폴리오 목적이므로 환경변수로 간소화했다.

---

## 3. Cloudflare Tunnel + Access: Zero Trust 연결

### 왜 Tunnel인가

일반적으로 AWS에서 홈랩 K8s API에 접근하려면 홈랩 방화벽에 포트를 열어야 한다.

```
일반 방식:
  AWS ECS → (인터넷) → 홈랩 공인IP:6443 → kube-apiserver
  문제: 방화벽 포트 오픈 → 공격 표면 증가

Cloudflare Tunnel 방식:
  AWS ECS → CF Edge → CF Tunnel (Outbound) → kube-apiserver
  장점: 방화벽 포트 오픈 불필요 — Tunnel이 아웃바운드 연결
```

Cloudflare Tunnel은 홈랩에서 Cloudflare Edge로 **아웃바운드** 연결을 유지한다. 외부에서 들어오는 인바운드 연결이 아니므로 방화벽에 포트를 열 필요가 없다.

### 인증 체인 (3계층)

```
[ECS Container]
    │
    │  CF-Access-Client-Id + CF-Access-Client-Secret 헤더
    ▼
[Cloudflare Access] ── Service Token 검증 (Layer 1)
    │
    │  검증 통과 → Tunnel로 전달
    ▼
[Cloudflare Tunnel] ── 홈랩으로 아웃바운드 연결
    │
    │  Authorization: Bearer <K8S_TOKEN>
    ▼
[kube-apiserver] ── RBAC 검증 (Layer 2)
    │
    │  agent-readonly ServiceAccount: get/list/watch만 허용
    ▼
[응답 반환] ── Falco/Wazuh 런타임 감시 (Layer 3)
```

| 계층 | 도구 | 역할 |
|------|------|------|
| Layer 1 | Cloudflare Access | Service Token으로 요청자 신원 확인 |
| Layer 2 | K8s RBAC | agent-readonly: get/list/watch + pods/log만 허용 |
| Layer 3 | Falco + Wazuh | 비정상 API 호출 패턴 런타임 감지 |

---

## 4. Bot Fight Mode 트러블슈팅

### 증상

홈랩에서 Agent 서버를 실행하면 K8s API 호출이 정상 동작하는데, ECS에서 실행하면 403이 반환되었다.

```
# ECS CloudWatch 로그
[k8s-api] 403: /api/v1/namespaces/blog-system/pods
body=<!DOCTYPE html>...Just a moment...
```

`403 Forbidden`이 아니라 Cloudflare의 "Just a moment..." 챌린지 페이지가 HTML로 반환되고 있었다.

### 원인

Cloudflare의 **Bot Fight Mode**가 AWS 데이터센터 IP를 봇으로 분류했다.

```
봇 탐지 조건 (AND):
  1. IP 평판: AWS 데이터센터 → "자동화 트래픽 의심"
  2. User-Agent: python-requests/2.x → "브라우저가 아님"
  3. JS Challenge 실패: Python requests는 JS 실행 불가

홈랩은 통과한 이유:
  가정용 IP → 봇 평판 낮음 → 챌린지 미발동
```

### 시도한 해결책

```
1. WAF Skip Rule로 CF Access 인증 통과 시 봇 검사 면제
   → 실패: Free Plan은 Super Bot Fight Mode(Pro+ 전용)만 WAF Skip 지원
   → Bot Fight Mode는 on/off 토글만 존재

2. User-Agent 변경
   → CF Bot Fight Mode는 UA만으로 판단하지 않음 (IP 평판이 주요 인자)

3. Bot Fight Mode OFF
   → 성공: CF 대시보드 > Security > Bots > Bot Fight Mode 비활성화
```

### 보안 보완 (Defense in Depth)

Bot Fight Mode를 끄면 봇 차단 계층 하나가 사라진다. 하지만 이미 더 강력한 인증이 있다.

```
CF Access Service Token   → 토큰 없으면 접근 자체 불가 (Bot Fight Mode보다 강력)
K8s RBAC                  → get/list/watch만 허용 (쓰기 불가)
Falco + Wazuh             → 비정상 API 호출 실시간 감지 + 자동 대응

결론: Bot Fight Mode는 "브라우저 접근" 보호용
      API 접근은 Service Token + RBAC이 더 적합한 인증 수단
```

---

## 5. E2E 검증

### 테스트 시나리오

```
Discord에서 "!infra blog-system pod 상태 확인" 입력
```

### 결과

```
🤖 Infra Agent:
blog-system 네임스페이스의 Pod 상태를 확인했습니다.

총 9개 Pod 중 8개 Running, 1개 Completed:
- mysql-0: Running (Ready 2/2)
- was-primary-xxx: Running (Ready 2/2)
- web-xxx: Running (Ready 2/2)
- mysql-backup-xxx: Completed (백업 CronJob)
...
```

### CloudWatch 로그 확인

```
[k8s-api] cf_id=SET token=SET
[k8s-api] GET /api/v1/namespaces/blog-system/pods → HTTP 200
```

CF Access 인증과 K8s Bearer Token 인증이 모두 통과한 것을 확인했다.

---

## 성과

| 항목 | Before (홈랩 실행) | After (ECS 배포) |
|------|-------------------|------------------|
| 장애 독립성 | 홈랩 죽으면 Agent도 죽음 | 홈랩 죽어도 Agent 생존, 알림 가능 |
| K8s API 연결 | kubeconfig 파일 + kubectl | CF Tunnel + REST API (Zero Trust) |
| 방화벽 포트 | 없음 (로컬 실행) | 없음 (CF Tunnel 아웃바운드) |
| 인프라 관리 | 수동 | Terraform IaC (코드로 재현 가능) |
| 자격증명 관리 | .env 파일 | ECS Task Definition 환경변수 + IAM Role |
| 운영 비용 | $0 (홈랩 전기) | ~$1/월 (필요시만 실행) |

---

## 전체 인증 체인 요약

```
[Discord 사용자]
    │  "!infra pod 상태 확인"
    ▼
[Discord API] ── WebSocket Gateway
    │
    ▼
[ECS Fargate Container]
    │  boto3 → Bedrock Claude (IAM Role 인증, Access Key 불필요)
    │  Claude → "kubectl_get_pods 도구 호출"
    ▼
[Python requests]
    │  Headers:
    │    CF-Access-Client-Id: <service-token-id>
    │    CF-Access-Client-Secret: <service-token-secret>
    │    Authorization: Bearer <k8s-sa-token>
    ▼
[Cloudflare Edge]
    │  Access Policy: Service Token 검증 ✅
    ▼
[Cloudflare Tunnel]
    │  홈랩으로 아웃바운드 연결 (인바운드 포트 불필요)
    ▼
[kube-apiserver]
    │  RBAC: agent-readonly → get/list/watch 허용 ✅
    ▼
[Pod 목록 JSON 응답]
    │
    ▼ (역순으로 전달)
[Discord 채널에 결과 출력]
```

---

## 다음 단계

현재 Agent는 사람이 `!agent` 명령으로 직접 호출해야 동작한다. Phase 5에서는 **Agent 간 자율 협업**을 구현할 계획이다.

```
현재: 사람 → "!infra 장애 확인" → Agent 응답
목표: Infra Agent가 장애 감지 → Backend Agent에게 수정 요청
      → Reviewer Agent가 리뷰 → 사람에게 최종 승인만 요청
```

Orchestrator 패턴으로 최대 3라운드 토론 → 합의 → PR 생성 → 사람 승인 구조를 설계 중이다.

> 관련 이론: [AI Agent로 스타트업급 팀 구성하기 #1](/study/2026-03-12-ai-agent-startup-team/) — Agent 아키텍처와 안전장치 설계
