# Bespin 프로젝트 포트폴리오 — 클라우드 엔지니어용

> 작성일: 2026-02-23
> 상태: 구조 확정, 작성 전
> 형식: PDF (사람인 첨부용), 6장
> 원칙: Case Study 형식 (문제 → 비교 → 해결 → 증거)

---

## 포트폴리오 작성 원칙

### 면접관이 보는 3가지: 지식, 경험, 증명

| 원칙 | 설명 |
|------|------|
| **첫 페이지에서 승부** | 1장만 봐도 "괜찮다" 인상 |
| **기술 나열 X, 의사결정 O** | "Redis 사용" ❌ → "3가지 비교 후 Redis 선택" ✅ |
| **스크린샷 > 코드** | Grafana 캡처 1장이 YAML 10줄보다 강력 |
| **이력서와 겹치면 삭제** | 기술 스택 표, 역량 소개, 자기소개 전부 삭제 |

---

## 프로젝트 기본 정보

| 항목 | 내용 |
|------|------|
| 프로젝트명 | AWS EKS 3-Tier + Multi-Cloud DR |
| 기간 | 2025.10 ~ 2026.01 (약 3개월) |
| 팀 구성 | 4명 |
| 역할 | **팀장** — 아키텍처 설계 + 프로젝트 조율 + Terraform IaC |
| 애플리케이션 | Spring PetClinic |
| Primary | AWS (EKS, RDS, ALB, Route53, CloudFront) |
| DR | Azure (VM, MySQL, Static Web Apps, Blob Storage) |

---

## [1장] 프로젝트 개요

### 내용
- 팀 4명 / 팀장 역할 설명
- 시나리오: AWS 리전 장애 → 고객 요구 RTO 6시간
- 전체 아키텍처 다이어그램
- 시연 영상 링크/QR

### 핵심 성과 요약
```
· 고객 요구 RTO 6시간 → 실제 30분 이내 달성 (시연 영상 검증)
· Terraform State 삭제 사고 → DynamoDB Lock 재발 방지 설계
· Pushgateway로 백업 CronJob 실패 감지 → DR 신뢰성 확보
```

### 사용할 이미지
- 전체 아키텍처 다이어그램 (오타 수정 필요: Secondery→Secondary, Administartor→Administrator)

---

## [2장] Case Study ① — DR 아키텍처

### 도입 배경
- 고객 요구: AWS 장애 시 서비스 연속성 보장

### RPO / RTO (용어 정확히!)
| 용어 | 의미 | 값 | 근거 |
|------|------|-----|------|
| **RPO** | 데이터 손실 허용 범위 | 24시간 | CronJob mysqldump 24시간 주기 |
| **RTO** | 서비스 복구 시간 | 30분 이내 | 장애 페이지 ~3분 + 전체 복구 ~18분 + Route53 수동 전환 ~10분 |

> ⚠️ RPO = Point (어느 시점까지 복구), RTO = Time (얼마나 걸리는가)
> 면접에서 100% 나오는 질문. 절대 혼동하지 말 것

### 4가지 DR 옵션 비용 비교 (핵심 의사결정)
| 옵션 | 비용 | RTO | 선택 |
|------|------|-----|------|
| Cloudflare Load Balancing | $360/년 | 90초 자동 | 탈락 |
| Azure Front Door | $420/년 | 30초 자동 | 탈락 |
| Route53 + CloudFront (AWS 의존) | $24/년 | 2분 자동 | 부분 채택 |
| **Cold Standby VM + 수동 DNS** | **$0 추가** | **30분 수동** | **채택** |

선택 근거: AWS 완전 장애 = 5-10년에 1회 예상 → 자동화 비용 대비 수동 대응이 합리적

### CloudFront POST 차단 → Dual Domain 전략
- 문제: CloudFront Origin Group은 POST/PUT/DELETE 차단
- PetClinic은 예약 생성에 POST 필수
- 해결: www.goupang.shop (점검 페이지) / dr.goupang.shop (전체 서비스) 분리

### 백업 검증
- Pushgateway로 CronJob 성공/실패 확인
- **실제 사례**: 백업 시간 미기록 → CronJob 문제 발견 → 수정
  → Pushgateway 없었으면 DR 발동 시 "백업이 없다" 상황

### 사용할 이미지
- 서비스 점검 중 페이지 (AMAZON VET 이미지)
- Azure DR 복구 사이트 (Azure PetClinic + 타이머 17:42)
- Azure VM에서 DB 복원 터미널

---

## [3장] Case Study ② — Canary 배포 + Redis 세션

### 도입 배경 (순서 중요!)
```
1. Sticky Session으로도 WAS 여러 개 운영 가능했음
2. 하지만 Canary 배포를 하려면 2개 Target Group 필요 (was-stable / was-canary)
3. Sticky Session은 하나의 TG 안에서만 유지
4. Canary에서 stable TG → canary TG로 넘어가면 세션 소실
5. 세션을 TG와 무관하게 유지하려면 외부 저장소 필요
6. → Redis 세션 클러스터링 도입
7. → 이후 ALB Traffic Routing으로 정확한 Canary 배포 완성
```

### Redis 대안 비교
| 방법 | 장점 | 단점 | 선택 |
|------|------|------|------|
| Sticky Session | 구현 간단, 무료 | TG 간 세션 깨짐, Canary 불가 | 탈락 |
| **Redis Session** | TG 무관, Canary 가능 | 비용 ~$15/월, SPOF 위험 | **채택** |
| JWT (Stateless) | Pod 독립적, 무료 | PetClinic 코드 대대적 수정 | 탈락 |

### Canary 방식 비교
| 방식 | 문제 | 결과 |
|------|------|------|
| Replica Shifting | 10% 하려면 9+1=10개 Pod 필요, 비율 부정확 | 탈락 |
| **ALB Traffic Routing** | ALB 가중치로 정확한 % 제어 (10→30→50→90%) | **채택** |

### 사용할 이미지
- kubectl argo rollouts 상태 (Canary Paused, Step 5/6, SetWeight 90)
- AWS ALB 콘솔 리스너 규칙 (wascanar: 90% / wastabl: 10%)

---

## [4장] Case Study ③ — IaC + CI/CD

### Terraform IaC — 실제 사고 스토리
```
사고: 팀원이 Terraform State 파일을 삭제
  → 인프라 실제 상태와 코드 불일치
  → Terraform이 이미 있는 리소스를 다시 생성하려 함

해결: 팀원 간 Terraform 코드 수정 통합

재발 방지:
  → DynamoDB Lock (동시 수정 차단)
  → S3에 State 파일 백업 저장
```

### CI/CD — Jenkins + ArgoCD 2-Repo GitOps
```
개발자 → Source Repo (git push)
  → Webhook → Jenkins (Maven Build + Docker Build + ECR Push)
  → Manifest Repo (Image Tag 업데이트)
  → ArgoCD Watch → EKS Sync/Apply
```

분리 이유: Jenkins는 빌드, ArgoCD는 배포 — 관심사 분리 + kubectl 직접 접근 방지

### Karpenter 노드 스케일링
- Node Group 고정 대신 Karpenter로 워크로드에 맞게 노드 자동 프로비저닝

### 사용할 이미지
- CI/CD 파이프라인 다이어그램
- Jenkins 빌드 알람 (Discord: WAS Build SUCCESS)
- Karpenter 노드 스케일링 (kubectl get nodeclaims + kubectl get nodes)

---

## [5장] Case Study ④ — 관측성

### 도입 배경
```
CloudWatch만으로 가능한 것:
  → 기본 메트릭 (CPU, Memory, Network)
  → Agent 설치 시 상세 메트릭도 가능

CloudWatch만으로 불가능한 것:
  → CronJob 성공/실패 모니터링 (Pushgateway 필요)
  → 외부 엔드포인트 상태 확인 (Blackbox Exporter 필요)
  → 커스텀 대시보드 자유도 (Grafana 필요)
  → 로그 수집/검색 (Loki 필요)

→ PLG Stack (Prometheus + Loki + Grafana) 도입
→ 9개 대시보드 구축
```

### 9개 대시보드 목적 (각각 "왜")
| ID | 대시보드 | 왜 필요한가 |
|----|---------|-----------|
| 001 | System Overview | Pod/Node 크래시를 사용자보다 먼저 감지 |
| 002 | AWS Infrastructure | ALB 응답시간 + RDS 커넥션 (CloudWatch Exporter) |
| 003 | JVM Monitoring | Heap 압박 → OOM 전에 감지 |
| 004 | Karpenter | 노드 풀 비용/상태 |
| 005 | OpenCost | 네임스페이스별 비용 (모니터링이 앱보다 비싸면 안됨) |
| 006 | DR Status | Route53 Health Check + Failover 상태 |
| 007 | Session Monitoring | Redis 키 수 + 메모리 (세션 손실 전에 감지) |
| 009 | RDS Backup | CronJob 백업 성공/실패 (Pushgateway) |
| 010 | Pushgateway | 모든 Batch Job 메트릭 |

### 성과: 대시보드로 실제 문제 발견
| 발견 | 내용 | 영향 |
|------|------|------|
| **백업 CronJob 실패** | Pushgateway에서 백업 시간 미기록 → CronJob 미동작 확인 | DR 신뢰성 확보 (안 발견했으면 RPO 무한대) |
| **Redis CPU throttling** | Discord ALERT: 38.78% throttling → RESOLVED 28.33% | 장애 전 인지 + 대응 |

### 사용할 이미지
- Grafana 백업 대시보드 (Pushgateway: 백업 상태 성공 + 백업 정보 테이블)
- Discord Redis CPU throttling 알람 (ALERT → RESOLVED)

---

## [6장] 트러블슈팅

### 형식: 증상 → 원인 → 해결

### ① HTTPS Redirect Loop
```
증상: https://www.goupang.shop/petclinic/ → "Too many redirects"
원인: ALB(HTTPS) → nginx(HTTP), nginx가 X-Forwarded-Proto 미확인 → HTTPS redirect 무한 루프
해결: nginx.conf에서 $http_x_forwarded_proto = "https" 헤더 확인 로직 추가
```

### ② ArgoCD + HPA 충돌
```
증상: HPA가 Pod를 5개로 늘림 → ArgoCD가 Git(2개)으로 되돌림 → 무한 반복
원인: ArgoCD가 /spec/replicas 필드의 drift 감지 → 강제 동기화
해결: ignoreDifferences 설정으로 /spec/replicas를 ArgoCD 감시에서 제외
```

### ③ Lambda@Edge 400 → Azure Static Web Apps 교체
```
증상: CloudFront → Azure Blob 접속 시 400 Bad Request
원인: Azure Blob이 Host 헤더 검증 — CloudFront가 원래 Host 헤더 전달
시도: Lambda@Edge로 Host 헤더 변경 → 동작하나 복잡도+비용 증가
최종: Azure Static Web Apps로 교체 (무료 + Host 헤더 검증 없음 + HTTPS 자동)
```

---

## 확보된 스크린샷/증거 목록

| 장 | 증거 | 상태 |
|----|------|------|
| 1장 | 전체 아키텍처 다이어그램 | ✅ 있음 (오타 수정 필요) |
| 2장 | 장애대응 점검 페이지 | ✅ 있음 (시연 영상 캡처) |
| 2장 | Azure DR 복구 사이트 + 타이머 | ✅ 있음 (17:42) |
| 2장 | Azure VM DB 복원 터미널 | ✅ 있음 |
| 3장 | ALB 콘솔 (90/10 비율) | ✅ 있음 |
| 3장 | kubectl argo rollouts 상태 | ✅ 있음 |
| 4장 | CI/CD 파이프라인 다이어그램 | ✅ 있음 |
| 4장 | Jenkins 빌드 알람 (Discord) | ✅ 있음 |
| 4장 | Karpenter 노드 스케일링 | ✅ 있음 |
| 5장 | Grafana 백업 대시보드 (Pushgateway) | ✅ 있음 |
| 5장 | Redis CPU throttling 알람 | ✅ 있음 |
| 추가 | 유튜브 시연 영상 | ✅ 있음 (QR/링크로 제공) |

### 아키텍처 다이어그램 오타 수정 목록
| 위치 | 현재 | 수정 |
|------|------|------|
| 우측 하단 | Secondery | **Secondary** |
| 하단 | Administartor | **Administrator** |
| 우측 상단 | Lamdba@Edge | **Lambda@Edge** (확인) |
| 우측 | dr.goupnag.shop | **dr.goupang.shop** (확인) |

---

## 성과 정리 (면접관 관점 강도 평가)

| Case Study | 핵심 성과 | 강도 |
|------------|----------|------|
| ① DR | 고객 요구 RTO 6시간 → 실제 30분 이내, 추가 비용 $0 | ★★★ |
| ② Canary+Redis | Sticky Session 한계 → Redis로 세션 외부화 → Canary 무중단 배포 가능 | ★★ |
| ③ IaC | State 삭제 사고 → DynamoDB Lock + S3 백업 재발 방지 | ★★★ |
| ④ 관측성 | Pushgateway로 백업 CronJob 실패 감지 → DR 신뢰성 확보 | ★★★ |
| ④ 관측성 | Redis CPU throttling 알람으로 장애 전 감지 | ★★ |
| 트러블슈팅 | CloudFront POST 차단 → Dual Domain / ArgoCD+HPA 충돌 해결 | ★★ |

> 이 프로젝트는 "구축 프로젝트"이므로 운영 성과가 나오기 어려운 구조.
> 의사결정 과정 자체가 강점 — 기술 나열이 아닌 "왜 이걸 선택했는가"로 승부

---

## 다음 작업

- [ ] K8s 온프레미스 프로젝트도 같은 방식으로 분석
- [ ] 포트폴리오 실제 작성 (PPT 또는 PDF)
- [ ] 아키텍처 다이어그램 오타 수정
- [ ] Grafana 대시보드 추가 캡처 (필요 시)
