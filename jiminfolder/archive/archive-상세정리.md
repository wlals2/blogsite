# Archive 상세 정리 — 프로젝트 자료 인벤토리

> 작성일: 2026-02-26
> 목적: 보유 자료 전수 정리 + 포트폴리오/면접 활용 매핑

---

## 보유 자료 목록

### 1. 포트폴리오 PDF
- **파일**: `엔지니어 오지민의 포트폴리오.pdf` (7페이지)
- **위치**: `/home/jimin/blogsite/jiminfolder/archive/`
- **용도**: 사람인/원티드 포트폴리오 첨부
- **버전**: 현재 사용 중 (2개 프로젝트 포함)

#### 페이지별 내용
| 페이지 | 내용 | 핵심 |
|--------|------|------|
| 1 | 표지 + 연락처 | "서비스가 멈추지 않는 인프라를 설계하는 엔지니어 오지민" |
| 2 | 기술 스택 개요 (3개 영역) | Infrastructure & Cloud, DevSecOps & CI/CD, Network & Observability |
| 3 | 2개 프로젝트 요약 | Multi-Cloud DR (Bespin) + Bare Metal K8s (홈랩) |
| 4 | K8s 홈랩 아키텍처 + Zero Trust | Cilium L3/L4 + Istio L7 계층 분리 |
| 5 | 트러블슈팅 상세 | Cilium L7 → L3/L4+Istio L7 리팩터링 (3 Phase) |
| 6 | Bespin AWS 아키텍처 | **VPC 직접 설계 (Public/Private Subnet, Multi-AZ)** |
| 7 | DR 트러블슈팅 | CloudFront POST 제한, RTO 28분, 비용 98% 절감 |

#### 페이지 2 — 기술 스택 상세
```
Infrastructure & Cloud:
  AWS (EKS, RDS, Route53, CloudFront, S3, ECR, KMS, DynamoDB, Lambda@Edge, Karpenter)
  Azure (VM, Static Web Apps, Blob Storage)
  GCP & NCP 3tier ← 추가 경험
  Kubernetes (kubeadm, Cilium, MetalLB, Kyverno)
  Vagrant ← 추가 경험
  Terraform, Ansible
  Docker, VMware, Proxmox

DevSecOps & CI/CD:
  GitHub Actions, ArgoCD, Argo Rollouts
  Jenkins
  Falco + Falco-Talon, Wazuh
  Kyverno, GitLeaks, Trivy, kube-bench

Network & Observability:
  Cilium (eBPF, Hubble)
  Istio (mTLS, Kiali)
  Cloudflare (Tunnel, WARP) ← 추가 경험
  Prometheus, Grafana, Loki, Tempo, Alloy
```

#### 페이지 6 — AWS 아키텍처 (면접 핵심 자료)
```
AWS Cloud 아키텍처:
  ┌─ VPC ──────────────────────────────────────────┐
  │                                                  │
  │  ┌─ Availability Zone C ─────────────────────┐  │
  │  │  Public Subnet    Private Subnet A  DB-C   │  │
  │  │  [WAF]            [EKS]             [MySQL] │  │
  │  │  Certificate      Karpenter                 │  │
  │  │  Manager          WAS (Rollout)             │  │
  │  │                   WEB                       │  │
  │  └───────────────────────────────────────────┘  │
  │                                                  │
  │  ┌─ Availability Zone A ─────────────────────┐  │
  │  │  Public Subnet    Private Subnet A  DB-A   │  │
  │  │  [NGW]            [WAS]             [MySQL] │  │
  │  │  [Jenkins]        [Redis]                   │  │
  │  │                   [ArgoCD]                  │  │
  │  └───────────────────────────────────────────┘  │
  │                                                  │
  │  Route53 → ALB → IGW                            │
  │  S3, ECR, DynamoDB, KMS, Secret Manager          │
  │  CloudFront → Lambda@Edge                        │
  │  SNS → Gmail, Slack                              │
  └──────────────────────────────────────────────────┘

  Azure DR:
  → CloudFront → 점검 페이지
  → Azure VM (dr.goupang.shop) + MySQL 복원
  → Observability: Prometheus, Grafana, Blackbox, Loki
```

---

### 2. 멀티클라우드 보고서 PDF
- **파일**: `멀티클라우드 기반 고가용성 아키텍처 설계 보고서.pdf`
- **위치**: `/home/jimin/blogsite/jiminfolder/archive/`
- **용도**: Bespin 최종 프로젝트 발표 자료 (내부용)
- **내용**: 프로젝트 I2ST 최종 발표 (멀티클라우드 기반 고가용성 아키텍처 구축)

#### 슬라이드 구성
| 슬라이드 | 내용 | 면접 활용 |
|----------|------|----------|
| 표지 | PROJECT I2ST, 팀원 4명 | 프로젝트명, 역할 확인 |
| 목차 | 5개 섹션 | - |
| 팀 구성 | 오지민=Leader/Cloud Architect | **역할 증명** |
| 수행 절차 | 5단계 × 28일 타임라인 | **체계적 프로세스 증명** |
| 수행경과 | STEP 01~04 + FINAL 성과 | **Before/After 수치** |

#### 핵심 성과 수치 (FINAL 슬라이드)
```
장애 복구:    Before 5시간+    → After 2분         (Route53 자동)
스케일링:    Before 30분(수동) → After 3분(자동)    (Karpenter)
배포 영향:   Before 100%      → After 최대 10%     (Canary)
장애 감지:   Before 30분+     → After 30초          (Prometheus)
비용:        월 $15 추가 (+9%) 투자로 자동화/DR 확보
```

#### 프로젝트 타임라인
```
사전기획:     12/8 ~ 12/11 (4일) — 요구사항 분석, 아키텍처 설계, 기술 스택 선정
아키텍처 구성: 12/12 ~ 12/18 (7일) — EKS, Terraform 모듈화, VPC/SG, Redis
CI/CD 구축:   12/19 ~ 12/23 (5일) — Jenkins, ArgoCD, Argo Rollouts Canary
DR 시스템:    12/24 ~ 12/28 (5일) — Route53 Failover, CloudFront, Azure DR VM
모니터링:     12/29 ~ 1/4 (7일)  — Prometheus, Grafana, Loki, 알람
```

---

### 3. 인스턴스 환경 아키텍처 PDF (중간 프로젝트)
- **파일**: `오지민_인스턴스 환경 아키텍처.pdf`
- **위치**: `/home/jimin/blogsite/jiminfolder/archive/`
- **용도**: Bespin 중간 프로젝트 발표 자료
- **내용**: AWS 3-Tier 인스턴스 기반 동물병원 웹사이트

#### 프로젝트 정보
```
프로젝트명: 아마존 동물병원 웹사이트 인프라 구축프로젝트
팀: AWS 2팀 (오지민, 김지수, 김창주, 조현준 + 멘토 김남룡)
팀명: I2ST (Intelligent Infrastructure Solution Team)
주제: AWS 기반 3티어 아키텍처로 병원 홈페이지 운영 인프라 설계 및 구현
기술: AWS, Java17, TOMCAT9, Apache 2.4.65, Amazon Linux
```

#### 역할
```
팀장 오지민: 아키텍처 설계, 총괄 및 모니터링
조현준: 시나리오 총괄
김지수: 백업 및 복구, PPT 제작
김창주: 아키텍처 설계 보조 및 오토스케일링
```

#### 프로젝트 결과 구성
```
01. 시나리오
02. 아키텍처
03. 오토스케일링과 탄력성
04. 백업 및 복구
05. 자동 모니터링 및 알람
```

#### 면접 활용
```
- "중간 프로젝트에서 인스턴스 기반 3-Tier를 먼저 구축한 뒤,
   최종 프로젝트에서 EKS + 컨테이너 기반으로 전환했습니다."
- 인스턴스 → 컨테이너 전환 경험으로 "왜 K8s인가?" 설명 가능
```

---

### 4. 사람인 제출용 이력서 PDF
- **파일**: `서비스가 멈추지 않는 인프라를 설계하는 클라우드 엔지니어 오지민_fw4568.pdf`
- **위치**: `/home/jimin/blogsite/jiminfolder/` (루트)
- **복사본**: `archive/CRScube/` (CRScube 지원용)
- **용도**: 사람인 Version A (클라우드 엔지니어) 이력서

---

### 5. 클라우드 보고서 PDF
- **파일**: `오지민의 클라우드 보고서.pdf`
- **위치**: `/home/jimin/blogsite/jiminfolder/`
- **내용**: Bespin 최종 프로젝트 상세 보고서

#### 슬라이드 구성
```
표지: "서비스가 멈추지 않는 인프라를 설계하는 엔지니어 오지민 입니다."
프로젝트 개요:
  - 아마존 동물병원: 24/7 프랜차이즈 동물병원 예약 플랫폼
  - 가맹 2,500+개 | 월 100만 예약 | 일 3만 트래픽
  - 역할: 4인팀 팀장 / 전체 아키텍처 설계 + Terraform 코드 구축
  - 고객 요구사항 5가지 → 기술 매핑
  - 아키텍처 다이어그램 (VPC 전체 구조)

고가용성 EKS 아키텍처:
  - Multi-AZ (ap-northeast-2a + 2c)
  - Pod 분산: TopologySpreadConstraints + ScheduleAnyway (채택)
    vs Pod Anti-Affinity (탈락: 노드 부족 시 스케줄 불가)
  - HPA: CPU 80%, min2, max10
  - Karpenter: 워크로드 기반 노드 자동 프로비저닝
  - Spot → On-demand 전환 (운영 오버헤드)
  - 시연: AZ 장애 + 대형 트래픽 동시 발생
    → AZ-C로 Pod 자동 분산 + Karpenter 노드 추가
    → uncordon 후 Pod 재분배

DR 아키텍처:
  - Warm vs Cold 비교 → Cold Standby 선택
  - 이유: 금융 아닌 동물 병원, 리전 장애 드묾, Terraform 프로비저닝 13분
  - RTO 30분 달성: CloudFront 점검(3분) + Terraform Azure VM(13분) + DB 복원(2분) + DNS(10분)

CI/CD & Canary:
  - Jenkins → ECR → ArgoCD → EKS
  - 빌드와 배포 분리 원칙
  - Canary에서 세션 깨짐 문제 → Redis 채택 (Sticky Session 탈락, JWT 오버헤드)
  - ALB TrafficRouting으로 가중치 분배 (stable 10% / canary 90%)
```

---

## 자료별 면접 활용 매핑

### "아키텍처를 직접 설계했나요?" → 증거
```
1. 포트폴리오 PDF 6페이지 — AWS VPC 전체 아키텍처 다이어그램
2. 클라우드 보고서 — 고가용성 EKS 설계 (Multi-AZ, TopologySpreadConstraints)
3. 발표 자료 — 팀 구성에서 "Cloud Architect" 명시
```

### "EKS 경험 상세히 설명해주세요" → 증거
```
1. Multi-AZ 배치 (ap-northeast-2a + 2c)
2. TopologySpreadConstraints + ScheduleAnyway 선택 (Anti-Affinity 탈락 이유 설명 가능)
3. HPA (CPU 80%, min2, max10) + Karpenter (노드 자동 프로비저닝)
4. Spot → On-demand 전환 판단 (운영 오버헤드)
5. 시연: AZ cordon + 트래픽 증가 → Pod 재분배 + 노드 추가 → uncordon 복구
```

### "VPC를 직접 설계했나요?" → 증거
```
1. Public Subnet (WAF, Certificate Manager, IGW, NGW)
2. Private Subnet (EKS Worker Nodes, RDS)
3. AZ-A + AZ-C 분리
4. Security Group 설정
5. NAT Gateway 배치
→ 포트폴리오 PDF 6페이지 다이어그램이 직접 증거
```

### "DR은 어떻게 구현했나요?" → 증거
```
1. Warm vs Cold 비교 분석 (팀 내 논의)
2. Cold Standby 선택 이유 (비용 vs RTO 트레이드오프)
3. RTO 30분 상세 흐름 (4단계: 3분+13분+2분+10분)
4. 시연 영상에서 17분 복구 확인
5. 비용: 월 $10 미만 (Cold Standby)
```

### "Canary 배포를 설명해주세요" → 증거
```
1. Sticky Session → Redis 전환 이유 (Target Group 간 세션 깨짐)
2. 3가지 비교: Sticky Session / JWT / Redis → Redis 채택
3. ALB TrafficRouting으로 정확한 비율 제어
4. 스크린샷: stable 10% / canary 90%
5. Bespin = ALB 기반, 홈랩 = Argo Rollouts 기반 (두 가지 방식 경험)
```

---

## 포트폴리오 업데이트 포인트

### 현재 포트폴리오에 없는 것 (추가 검토)
```
1. Bespin 중간 프로젝트 (인스턴스 3-Tier)
   → "인스턴스 → 컨테이너 전환" 스토리로 활용 가능
   → 다만 포트폴리오가 너무 길어질 수 있음 → 면접에서만 언급

2. 발표 자료의 성과 수치 (FINAL 슬라이드)
   → 장애 복구 5시간+→2분, 스케일링 30분→3분 등
   → 포트폴리오 1페이지에 추가하면 임팩트 높음

3. TopologySpreadConstraints vs Anti-Affinity 비교
   → 의사결정 과정으로 포트폴리오에 추가 가치 있음

4. Karpenter Spot→On-demand 전환
   → 비용 최적화 시도 → 운영 현실성 판단 경험
```

### 현재 포트폴리오 수정 필요 사항
```
1. 아키텍처 다이어그램 오타:
   - "Secondery" → "Secondary"
   - "Administartor" → "Administrator"
   - "Lamdba@Edge" → "Lambda@Edge" (확인 필요)
   - "dr.goupnag.shop" → "dr.goupang.shop" (확인 필요)

2. 팀원 수 표기:
   - 중간 프로젝트: 4명 (멘토 제외)
   - 최종 프로젝트: 3명 작업 + 멘토 = 발표 자료는 "3명 (Leader 1, Member 2)"
   - 포트폴리오에는 "4인팀 팀장"으로 되어 있음 → 확인 필요
     (조현준이 최종 프로젝트에도 참여했는지?)

3. 성과 수치 통일:
   - 발표 자료: "장애 복구 2분" (Route53 자동)
   - 포트폴리오: "RTO 30분" (전체 복구)
   - 둘 다 맞지만 맥락이 다름 → 면접에서 혼동하지 않도록 정리 필요
     · 2분 = Route53 Failover 자동 전환
     · 30분 = 전체 서비스 복구 (VM 프로비저닝 + DB 복원 + DNS 전환)
```

---

## 변경 이력

- 2026-02-26: 초안 작성 (발표 자료 이미지 + PDF 전수 분석)
