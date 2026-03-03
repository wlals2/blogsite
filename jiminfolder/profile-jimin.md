# 오지민 프로필 — 직무 분석 & 지원 전략

> 작성일: 2026-02-25
> 목적: 내 경험/수준 정리 + 직무별 매칭 분석 + 지원 가능 공고 추적
> 영어: 업무 수준 아님 (한국 시장 우선)

---

## 1. 나의 경험 요약

### Bespin Global 부트캠프 프로젝트 (2025.10 ~ 2026.01, 팀 4명, 팀장)
```
AWS EKS 3-Tier + Multi-Cloud DR 아키텍처
├── AWS EKS 운영 (Multi-AZ, HPA, Karpenter)
├── Terraform IaC (State 삭제 사고 → DynamoDB Lock 재발 방지)
├── ArgoCD GitOps + Argo Rollouts Canary 배포
├── CI/CD: Jenkins + ArgoCD 2-Repo GitOps
├── CloudWatch + PLG Stack (Prometheus/Loki/Grafana) 모니터링
│   ├── 9개 대시보드, 31개 알람 (3-Tier: Critical/High/Medium)
│   └── 실제 발견: 백업 CronJob 실패, Redis CPU throttling
├── DR: AWS → Azure Cold Standby (RTO 30분, 비용 $0 추가)
└── 트러블슈팅: HTTPS Redirect Loop, ArgoCD+HPA 충돌, CloudFront POST 차단
```

### K8s 홈랩 프로젝트 (2025.07 ~ 현재, 220일+ 운영 중, 1인)
```
Bare-Metal Kubernetes 블로그 플랫폼 (blog.jiminhome.shop)
├── 인프라
│   ├── kubeadm으로 베어메탈 K8s 클러스터 직접 구축 (CP 1 + Worker 3)
│   ├── Cilium CNI (eBPF 기반 L3/L4 NetworkPolicy)
│   ├── Istio Service Mesh (mTLS, L7 AuthorizationPolicy)
│   ├── MetalLB LoadBalancer
│   ├── ArgoCD GitOps + App of Apps 패턴
│   └── Argo Rollouts Canary 배포 (501회 배포, 실패 0)
├── 보안 (Defense in Depth)
│   ├── Cilium (L3/L4) + Istio (L7 mTLS) 계층별 보안
│   ├── Falco 런타임 보안 (컨테이너 IDS, syscall 기반)
│   ├── Falco-Talon IPS (Phase 전략: CRITICAL 즉시 격리, WARNING 관찰)
│   ├── Wazuh SIEM (로그 수집/분석/알림, MITRE ATT&CK 매핑)
│   ├── 실제 공격 탐지: SSH Brute Force 238,903건, 웹 스캐너 17건
│   ├── 5개 공격 시나리오 396건 탐지
│   └── OS 하드닝 (SSH 키 인증, fail2ban)
├── 관측성 (Metrics + Logs + Traces 3축 완비)
│   ├── 메트릭 수집 (6개 ServiceMonitor)
│   │   ├── Alloy DaemonSet → 노드 CPU/Memory/Disk (USE Method)
│   │   ├── Istio Metrics → WEB/WAS RPS/Error/Latency (RED Method)
│   │   ├── MySQL Exporter → 연결/쿼리/InnoDB 상태
│   │   ├── Blackbox Exporter → WEB·WAS·MySQL 가용성 Probe (30초 간격)
│   │   ├── Tempo → 분산 추적 기반 Span 메트릭
│   │   └── Pushgateway → CronJob(백업) 성공/실패 메트릭
│   ├── SLI/SLO 체계
│   │   ├── SLI Recording Rules: Availability, Latency p95/p99, Error Rate, Throughput
│   │   ├── SLO 목표: Availability 99%, Latency p95 < 500ms, Error Rate < 1%
│   │   ├── Alert: SLOAvailabilityViolation1h/30d, SLOLatencyViolation, BlogServiceDown
│   │   └── 한계 인지: 내부 Probe 기반 → 측정 주체 장애 시 공백 처리(avg_over_time 제외)
│   ├── 로그: Loki + Alloy (WEB/WAS/MySQL/Istio Proxy 전 계층 수집)
│   ├── 분산 추적: Tempo + OTEL Java Agent (서비스 간 호출 경로 추적)
│   ├── Discord 알람 (Grafana Alerting → Discord Webhook)
│   └── 대시보드 7개 (3-Level 구조)
│       ├── [L1] SLO Overview — 전체 가용성/에러율/레이턴시 한눈에
│       ├── [L2] Service Detail (RED) / Node Detail (USE) / MySQL Detail
│       └── [L3] CronJob / Logs / MITRE ATT&CK (Falco+Wazuh)
├── 운영 성과
│   ├── 220일 무중단 운영
│   ├── CPU Requests 22.4 Core → 6.25 Core (-72% 최적화)
│   └── MySQL CronJob 백업 + S3 업로드
└── 트러블슈팅
    ├── Istio + MySQL Protocol Detection Deadlock (Server-First 교착)
    ├── Falco File Descriptor Limit (eBPF FD 초과)
    ├── kubelet 중단 → Pod Terminating 무한 대기
    └── SSH Brute Force 대응 (fail2ban + SSH 키 전용)
```

### 넷코아텍 실무 경험 (보안 관련)
```
UTM 기반 IDS/IPS 운영
├── 오탐률 문제: 자동 차단 기능은 있었으나 정상 트래픽까지 차단
├── 수동 정책 관리 경험
└── → Falco-Talon Phase 전략의 설계 근거
```

---

## 2. 나의 기술 수준 (정직한 자기 평가)

### 강함 (실전 경험 + 깊이 있음)
| 기술 | 수준 | 근거 |
|------|------|------|
| Kubernetes | 상 | 베어메탈 kubeadm 구축 + EKS 운영 + 220일 무중단 |
| ArgoCD GitOps | 상 | App of Apps, selfHeal, ignoreDifferences 활용 |
| Terraform | 중-상 | State 관리, DynamoDB Lock, 멀티클라우드 프로비저닝 |
| Prometheus/Grafana | 상 | SLI/SLO 체계, 대시보드 직접 설계, PromQL |
| Cilium | 중-상 | L3/L4 NetworkPolicy 운영 |
| Istio | 중-상 | mTLS, AuthorizationPolicy, VirtualService |
| Falco | 중 | 커스텀 룰, Talon 연동, Phase 전략 설계 |
| Wazuh | 중 | 커스텀 룰 XML 작성, Falco 연동, MITRE ATT&CK |
| Linux | 중 | OS 하드닝, systemd, 트러블슈팅 |
| CI/CD | 중-상 | GitHub Actions, Jenkins, ArgoCD 2-Repo |

### 보통 (사용 경험은 있지만 깊지 않음)
| 기술 | 수준 | 비고 |
|------|------|------|
| AWS | 중 | EKS, RDS, ALB, Route53, CloudFront, S3 사용 |
| Docker | 중 | 멀티스테이지 빌드, 이미지 관리 |
| Python/Bash | 중-하 | 자동화 스크립트 수준 |
| Helm | 중 | Wrapper Chart, values.yaml 커스텀 |
| Loki | 중 | 로그 수집/검색 |

### 부족 (경험 없거나 매우 얕음)
| 기술 | 상태 | 보강 계획 |
|------|------|----------|
| AWS 자격증 | 없음 | SAA 취득 검토 |
| CKS 인증 | 없음 | K8s 보안 경험 기반 취득 가능 |
| Trivy/Snyk (이미지 스캐닝) | 없음 | GitHub Actions에 추가 예정 |
| Python 자동화 | 약함 | 보안 스크립트 작성으로 보강 |
| Datadog/Splunk (상용 모니터링) | 없음 | - |
| ISMS/ISO 27001 | 없음 | 한국 보안 직무 시 학습 필요 |
| 전통 보안장비 (Firewall/WAF) | UTM만 | 네이버클라우드 등 지원 시 보강 필요 |
| 영어 | 업무 수준 아님 | 글로벌 Remote 포지션은 현실적으로 어려움 |

---

## 3. 직무별 매칭 분석

### 한국 시장 (신입/주니어 기준)

| 직무 | 매칭도 | 신입 공고 | 현실적 판단 |
|------|--------|----------|------------|
| **DevOps 엔지니어** | **상** | 많음 | ✅ **최우선 지원 대상** — 기술 스택 90% 일치 |
| **클라우드 엔지니어** | **중** | 많음 | △ 가능하지만 경험이 오버스펙, MSP SM은 비추 |
| **DevSecOps** | **중-상** | 적음 | △ CI/CD 보안 도구 보강 후 지원 가능 |
| **보안 엔지니어 (클라우드)** | **중** | 거의 없음 | △ 대부분 경력 3년+, 전통 보안장비 요구 |
| **SRE** | **상** | 거의 없음 | X 신입 SRE 포지션 자체가 거의 없음 |
| **보안 엔지니어 (전통)** | **하** | 있음 | X Firewall/IPS/WAF 중심, 미스매치 |

**결론: 한국 시장에서 현실적 진입점 = DevOps 엔지니어**

---

## 4. 지원 가능 공고 목록 (2026-02-25 기준)

### 적합도 높음 (기술 스택 직접 일치 + 신입 가능)

| 순위 | 회사 | 직무 | 일치 기술 | 플랫폼 |
|------|------|------|----------|--------|
| **1** | **데브시스터즈** | DevOps (신입) | K8s, AWS, Terraform, ArgoCD, Prometheus, Grafana, **Istio** | 원티드 |
| **2** | **쿼리파이 (QueryPie)** | DevOps (신입) | Terraform, GitHub Actions, K8s, Prometheus, Grafana | 원티드 |
| **3** | **빗썸** | DevOps (신입) | CI/CD, 컨테이너, 하이브리드 인프라 | 원티드 |
| **4** | **아데나소프트웨어** | DevOps (신입~5년) | AWS, ECS/EKS, Terraform, CI/CD | 원티드 |
| **5** | **씨드앤** | DevOps (신입) | AWS, K8s, Prometheus, Grafana, Helm | 원티드 |
| **6** | **커브** | DevSecOps (신입/경력) | DevSecOps | 잡코리아 |
| **7** | **포에버아이티** | K8s 플랫폼 DevOps (경력무관) | Kubernetes | 잡코리아 |

### 도전적 지원 (경력 2-3년 요구하지만 기술 매칭 높음)

| 회사 | 직무 | 요구 경력 | 기술 매칭 |
|------|------|----------|----------|
| 서울로보틱스 | DevOps Release Engineer | 2년 | GitHub Actions, CI/CD |
| eMoldino | DevOps Engineer | 3년 | K8s, AWS, RBAC, NetworkPolicy |
| 뤼튼테크놀로지스 | DevOps Engineer | 미상 | K8s, Terraform, MSA |
| 토스증권 | DevOps Engineer | 미상 | K8s, **Istio** |

### 클라우드 엔지니어 (신입)

| 회사 | 직무 | 비고 |
|------|------|------|
| 솔트룩스이노베이션 | 클라우드 엔지니어 (신입) | AWS, EKS |
| 스윗코리아 (Swit) | 클라우드 엔지니어 (경력무관) | GCP, K8s |
| 메가존클라우드 | 클라우드 보안 솔루션 (신입/경력) | 클라우드+보안 |

---

## 5. 나의 차별화 포인트 (대부분의 신입이 없는 것)

```
1. 베어메탈 K8s + 관리형 EKS 양쪽 경험
   → 대부분의 신입은 EKS 콘솔 클릭만 경험

2. Cilium + Istio 양쪽 사용
   → 보통은 둘 중 하나만

3. 실제 공격 탐지 수치
   → SSH Brute Force 238,903건은 "실제 운영"의 증거

4. 220일 무중단 운영
   → 테스트 환경이 아닌 실제 서비스

5. 501회 배포, 실패 0
   → GitOps 파이프라인이 실제로 동작하는 증거

6. CPU -72% 최적화 (22.4 → 6.25 Core)
   → 정책 기반 리소스 관리 경험

7. 넷코아텍 UTM 실무 → Falco Phase 전략 설계
   → "현장 경험에서 나온 설계"

8. SLO 측정 한계까지 이해한 관측성 설계
   → 내부 Blackbox Exporter 기반 SLO의 한계 파악
     (측정 주체 장애 시 공백 처리 → avg_over_time에서 제외)
   → 외부 관측 기반 SLO와의 차이 이해
   → 홈랩 환경 특수성(PC 전원 off)을 고려한 측정 정책 설계
   → "SLO 99%를 만들었다"가 아닌 "왜 88%가 나왔는지 수식부터 분석하고 한계를 정의"
```

---

## 6. 부족한 점 & 보강 로드맵

### 즉시 가능 (1-2주)
- [ ] Trivy 이미지 스캐닝을 GitHub Actions에 추가 → "Supply Chain Security" 경험
- [ ] kube-bench 실행 → CIS Kubernetes Benchmark 컴플라이언스 경험
- [ ] AWS GuardDuty/Security Hub 활성화 → 퍼블릭 클라우드 보안 경험

### 단기 (1-3개월)
- [ ] CKS 인증 취득 (현재 K8s 보안 경험 충분, 취득 현실적)
- [ ] AWS SAA 인증 (서류 필터링 통과용)
- [ ] Python 보안 자동화 스크립트 작성

### 중기 (3-6개월)
- [ ] ISMS/ISMS-P 기본 학습 (한국 보안 직무 필수)
- [ ] 영어 학습 (글로벌 포지션 도전 시)

---

## 7. 포트폴리오 전략

| 포트폴리오 | 타겟 직무 | 핵심 메시지 |
|-----------|----------|------------|
| **Bespin (6장, 완료)** | DevOps, 클라우드 엔지니어 | HA + DR + 모니터링 + 트러블슈팅 |
| **Homelab (6장, 작성 예정)** | DevOps, DevSecOps | 플랫폼 구축 + 운영 + 보안 + 최적화 |

### 직무별 포트폴리오 조합
```
DevOps 지원 시:
  → Bespin (팀 경험, 클라우드) + Homelab (운영 깊이, GitOps)
  → 보안은 부가 역량으로 한 줄

DevSecOps 지원 시:
  → Homelab (보안 중심) + Bespin (클라우드 경험)
  → Cilium/Istio/Falco/Wazuh + 공격 탐지 수치 강조
```

---

## 8. 정기 확인 채용 플랫폼

| 플랫폼 | URL | 검색 키워드 |
|--------|-----|-----------|
| 원티드 | wanted.co.kr | DevOps, Kubernetes, 클라우드 엔지니어 |
| 잡코리아 | jobkorea.co.kr | DevOps 신입, kubernetes |
| 사람인 | saramin.co.kr | 클라우드 엔지니어, DevOps |
| LinkedIn Korea | kr.linkedin.com/jobs | DevOps, Cloud Engineer |
| 주니어 채용 스케줄러 | github.com/jojoldu/junior-recruit-scheduler | 주니어 전체 |

---

## 변경 이력
- 2026-03-03: 관측성 섹션 상세화 (6개 ServiceMonitor, SLI/SLO 한계 명시, 7개 대시보드 3-Level 구조) + Section 5에 차별화 포인트 8번 추가
- 2026-02-25: 초안 작성 (직무 분석 + 지원 가능 공고 + 보강 로드맵)
