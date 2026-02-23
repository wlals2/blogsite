---
title: "AWS EKS 3-Tier + Multi-Cloud DR"
date: 2026-01-08
summary: "고객 요구 RTO 6시간 → 실제 30분 이내 달성. 4가지 DR 옵션 비용 비교 후 Cold Standby 선택, Pushgateway로 백업 CronJob 실패를 발견하기까지."
tags: ["AWS", "EKS", "Kubernetes", "DR", "Azure", "Terraform", "ArgoCD", "Canary", "Redis"]
showtoc: true
tocopen: true
---

## 프로젝트 개요

| 항목 | 내용 |
|------|------|
| **기간** | 2025.10 ~ 2026.01 (약 3개월) |
| **팀 구성** | 4명 |
| **역할** | **팀장** — 아키텍처 설계 + 프로젝트 조율 + Terraform IaC |
| **애플리케이션** | Spring PetClinic |
| **Primary** | AWS (EKS, RDS, ALB, Route53, CloudFront) |
| **DR** | Azure (VM, MySQL, Static Web Apps, Blob Storage) |

### 핵심 성과

```
· 고객 요구 RTO 6시간 → 실제 30분 이내 달성 (추가 비용 $0)
· Terraform State 삭제 사고 → DynamoDB Lock으로 재발 방지 설계
· Pushgateway 없었으면 몰랐을 백업 CronJob 미동작 → DR 발동 시 "백업이 없다" 상황 예방
```

---

## Case Study ① — DR 아키텍처

### 요구사항

고객 시나리오: AWS 리전 장애 발생 시 서비스 연속성 보장.
제시된 목표 RTO: **6시간**.

### RPO / RTO

| 용어 | 의미 | 값 | 근거 |
|------|------|----|------|
| **RPO** | 데이터 손실 허용 범위 | 24시간 | CronJob mysqldump 24시간 주기 |
| **RTO** | 서비스 복구 시간 | **30분 이내** | 장애 페이지 ~3분 + 전체 복구 ~18분 + Route53 수동 전환 ~10분 |

### 4가지 DR 옵션 비교 (의사결정)

AWS 완전 장애는 5~10년에 1회 예상이다. 자동화 비용이 연간 $360~$420 드는 옵션이 합리적인가를 기준으로 검토했다.

| 옵션 | 비용 | RTO | 선택 |
|------|------|-----|------|
| Cloudflare Load Balancing | $360/년 | 90초 자동 | 탈락 |
| Azure Front Door | $420/년 | 30초 자동 | 탈락 |
| Route53 + CloudFront (AWS 의존) | $24/년 | 2분 자동 | 부분 채택 |
| **Cold Standby VM + 수동 DNS** | **$0 추가** | **30분 수동** | **채택** |

AWS 완전 장애 시 Route53도 같은 리전이면 문제가 생길 수 있어, $0 추가 비용으로 Azure VM Cold Standby + 수동 DNS 전환을 선택했다.

### CloudFront POST 차단 → Dual Domain 전략

DR 구성 중 예상치 못한 제약이 있었다.

```
문제: CloudFront Origin Group은 POST/PUT/DELETE 요청을 원본으로 전달하지 않음
원인: PetClinic은 예약 생성에 POST가 필수
해결: 도메인 분리
  www.goupang.shop → 점검 페이지 (Blob)
  dr.goupang.shop  → 전체 서비스 (Azure VM)
```

장애 발생 시 `www.goupang.shop`은 점검 안내 페이지를, `dr.goupang.shop`으로 실제 서비스를 제공한다.

### 백업 검증 — 발견하지 못했다면

Pushgateway로 CronJob 성공/실패를 모니터링하던 중 백업 시간이 기록되지 않는 것을 발견했다.

```
Pushgateway 대시보드: 백업 시간 = 없음
→ CronJob이 동작하지 않고 있었음 확인
→ 수정 후 정상화
```

이 발견 없이 DR이 발동됐다면 복구할 데이터가 없었다.

---

## Case Study ② — Canary 배포 + Redis 세션

### 도입 순서 (인과관계가 중요)

세션 문제를 먼저 해결하고, Canary를 구현했다. 순서가 반대였다면 Canary가 불가능했다.

```
1. WAS Pod 2개 운영 → Sticky Session으로 세션 공유
2. Canary 배포 도입 시도 → 2개 Target Group 필요 (was-stable / was-canary)
3. Sticky Session은 하나의 TG 안에서만 유지됨
   → TG 전환 시 세션 소실 → 로그인이 풀림
4. 세션을 TG와 무관하게 유지하려면 외부 저장소 필요
5. → Redis 세션 클러스터링 도입
6. → ALB Traffic Routing으로 정확한 Canary 배포 완성
```

### 세션 방식 비교

| 방법 | 장점 | 단점 | 선택 |
|------|------|------|------|
| Sticky Session | 구현 간단, 무료 | TG 간 세션 깨짐, Canary 불가 | 탈락 |
| **Redis Session** | TG 무관, Canary 가능 | 비용 ~$15/월, SPOF 위험 | **채택** |
| JWT (Stateless) | Pod 독립적, 무료 | PetClinic 코드 대대적 수정 필요 | 탈락 |

### Canary 방식 비교

| 방식 | 문제 | 결과 |
|------|------|------|
| Replica Shifting | 10% 비율 위해 9+1=10개 Pod 필요, 비율 부정확 | 탈락 |
| **ALB Traffic Routing** | ALB 가중치로 정확한 % 제어 (10→30→50→90%) | **채택** |

```yaml
# Argo Rollouts — ALB 기반 Canary
strategy:
  canary:
    canaryService: was-canary
    stableService: was-stable
    trafficRouting:
      alb:
        ingress: petclinic-ingress
    steps:
      - setWeight: 10
      - pause: {duration: 30s}
      - setWeight: 50
      - pause: {duration: 2m}
      - setWeight: 90
      - pause: {duration: 30s}
```

---

## Case Study ③ — IaC + CI/CD

### Terraform State 삭제 사고

```
사고: 팀원이 Terraform State 파일을 실수로 삭제
  → 인프라 실제 상태와 코드 불일치
  → Terraform이 이미 존재하는 리소스를 다시 생성하려 함
  → 리소스 중복 생성 위험

해결: 팀원 간 Terraform 코드 수정 통합

재발 방지:
  → DynamoDB Lock — 동시 수정 차단
  → S3 Backend — State 파일 원격 저장 + 버전 관리
```

이 사고를 경험한 뒤, "IaC도 코드다, 코드에는 반드시 백업과 동시성 제어가 있어야 한다"는 원칙을 팀에 공유했다.

### CI/CD 파이프라인 — 관심사 분리

```
개발자 → Source Repo (git push)
  → Webhook → Jenkins (Maven Build + Docker Build + ECR Push)
  → Manifest Repo (Image Tag 자동 업데이트)
  → ArgoCD Watch → EKS Sync/Apply
```

Jenkins는 빌드, ArgoCD는 배포로 역할을 분리했다. 이유는 단순하다: ArgoCD가 kubectl 직접 접근 권한을 갖지 않아도 되고, 배포 이력이 Git에 남는다.

---

## Case Study ④ — 관측성

### CloudWatch만으로 불가능한 것들

CloudWatch로 기본 메트릭(CPU, Memory)은 충분하다. 그런데 이것들은 안 됐다.

| 필요한 것 | 이유 |
|----------|------|
| CronJob 성공/실패 모니터링 | Pushgateway 없이는 불가 |
| 외부 엔드포인트 상태 확인 | Blackbox Exporter 필요 |
| 커스텀 대시보드 자유도 | Grafana 필요 |
| 로그 수집/검색 | Loki 필요 |

PLG Stack(Prometheus + Loki + Grafana)을 도입해 9개 대시보드를 구축했다.

### 9개 대시보드 — "왜" 중심

| 대시보드 | 왜 필요한가 |
|---------|-----------|
| System Overview | Pod/Node 크래시를 사용자보다 먼저 감지 |
| AWS Infrastructure | ALB 응답시간 + RDS 커넥션 (CloudWatch Exporter) |
| JVM Monitoring | Heap 압박 → OOM 전에 감지 |
| Karpenter | 노드 풀 비용/상태 |
| OpenCost | 네임스페이스별 비용 (모니터링이 앱보다 비싸면 안 됨) |
| DR Status | Route53 Health Check + Failover 상태 |
| Session Monitoring | Redis 키 수 + 메모리 (세션 손실 전에 감지) |
| RDS Backup | CronJob 백업 성공/실패 (Pushgateway) |
| Pushgateway | 모든 Batch Job 메트릭 |

### 대시보드로 발견한 실제 문제

| 발견 | 영향 |
|------|------|
| 백업 CronJob 미동작 (Pushgateway 백업 시간 미기록) | DR 발동 시 "백업이 없다" 상황 예방 |
| Redis CPU Throttling 38.78% → RESOLVED 28.33% | 장애 전 인지 + 대응 |

---

## 트러블슈팅

### ① HTTPS Redirect Loop

```
증상: https://www.goupang.shop/petclinic/ → "Too many redirects"

원인:
  ALB(HTTPS) → nginx(HTTP)
  nginx가 X-Forwarded-Proto 헤더를 확인하지 않아
  HTTP 요청이라고 판단 → 다시 HTTPS redirect → 무한 루프

해결:
  nginx.conf에서 $http_x_forwarded_proto = "https" 헤더 확인 후
  이미 HTTPS면 redirect하지 않도록 수정
```

### ② ArgoCD + HPA 충돌

```
증상:
  HPA가 트래픽 증가로 Pod를 5개로 늘림
  → ArgoCD가 Git(replicas: 2)으로 되돌림 → 무한 반복

원인:
  ArgoCD가 /spec/replicas 필드의 drift 감지 → 강제 동기화

해결:
  ignoreDifferences 설정으로 /spec/replicas를 ArgoCD 감시에서 제외
  GitOps와 HPA가 공존할 수 있게 됨
```

### ③ Lambda@Edge → Azure Static Web Apps 교체

```
증상: CloudFront → Azure Blob 접속 시 400 Bad Request

원인:
  Azure Blob이 Host 헤더를 검증
  CloudFront가 원래 Host 헤더를 그대로 전달

시도:
  Lambda@Edge로 Host 헤더를 변경
  → 동작하나 복잡도 증가 + 비용 발생

최종 해결:
  Azure Static Web Apps로 교체
  → 무료 + Host 헤더 검증 없음 + HTTPS 자동 제공
```

---

## 시연 영상

전체 DR Failover 과정을 영상으로 촬영했다. AWS 장애 시뮬레이션 → Azure 복구까지 30분 이내 완료.

- [📹 유튜브 시연 영상 (DR Failover 전체 과정)](https://youtu.be/예정)
