# Blog System

> Kubernetes 기반 프로덕션 블로그 시스템 (Hugo + Spring Boot + DevSecOps)

[![Production](https://img.shields.io/badge/Production-Running-success)](https://blog.jiminhome.shop)
[![Kubernetes](https://img.shields.io/badge/Kubernetes-v1.31-blue)](https://kubernetes.io/)
[![ArgoCD](https://img.shields.io/badge/ArgoCD-GitOps-orange)](https://argo-cd.readthedocs.io/)
[![Monitoring](https://img.shields.io/badge/Monitoring-PLG_Stack-green)](http://monitoring.jiminhome.shop)
[![Security](https://img.shields.io/badge/Security-Falco_IDS%2BIPS-red)](http://falco.jiminhome.shop)

---

## 🚀 프로젝트 개요

개인 블로그를 **Kubernetes 프로덕션 환경**에서 운영하며 **DevSecOps 실무 경험**을 습득하는 프로젝트입니다.

### 주요 특징

- ✅ **완전 자동화된 CI/CD** (git push → 35초 후 배포)
- ✅ **GitOps 기반 배포** (ArgoCD, selfHeal)
- ✅ **고가용성** (HPA, 무중단 Canary 배포)
- ✅ **55일 안정 운영** (PLG Stack 모니터링)
- ✅ **런타임 보안** (Falco IDS + Talon IPS)
- ✅ **데이터 보호** (MySQL 자동 백업, S3 저장)

### 시스템 규모

| 항목 | 수치 |
|------|------|
| **Kubernetes 노드** | 3대 (1 control-plane + 2 workers) |
| **Namespace** | 4개 (blog-system, argocd, monitoring, falco) |
| **Pod 복제본** | WEB 2개, WAS 2개, MySQL 1개 |
| **HPA** | WEB (2-5), WAS (2-10) - CPU/Memory 기반 |
| **모니터링** | PLG Stack (55일 운영, 4 Dashboards, 8 Alert Rules) |
| **보안** | Falco IDS + Talon IPS (Dry-Run, 커스텀 룰 4개) |
| **배포 시간** | 35초 (GitHub Actions Self-hosted Runner) |
| **운영 비용** | $0 (자체 서버 + 무료 서비스) |

---

## 🏗️ 시스템 아키텍처

```
사용자 (브라우저)
  ↓ HTTPS
Cloudflare CDN (글로벌 캐싱, DDoS 방어)
  ↓ HTTPS
MetalLB LoadBalancer (192.168.X.200)
  ↓
Ingress Nginx Controller
  ↓
┌─────────────────────────────────────────┐
│        blog-system Namespace            │
│                                          │
│  ┌──────────────────────────────────┐  │
│  │ WEB (Hugo 블로그)                │  │
│  │ - Argo Rollouts (Canary 배포)   │  │
│  │ - Istio mTLS 암호화               │  │
│  │ - HPA: 2-5 replicas              │  │
│  │ - SecurityContext: Non-root       │  │
│  └────────┬─────────────────────────┘  │
│           ↓ /api/*                      │
│  ┌────────────────────────────────────┐ │
│  │ WAS (Spring Boot 3.5.0)           │ │
│  │ - Deployment                       │ │
│  │ - Istio mTLS 암호화                │ │
│  │ - HPA: 2-10 replicas               │ │
│  │ - SecurityContext: UID 65534       │ │
│  └────────┬───────────────────────────┘ │
│           ↓ JDBC                        │
│  ┌────────────────────────────────────┐ │
│  │ MySQL 8.0                          │ │
│  │ - Istio mesh 제외 (JDBC 호환)     │ │
│  │ - Longhorn PVC 10Gi                │ │
│  │ - 자동 백업 (매일 03:00 KST)      │ │
│  └────────────────────────────────────┘ │
│                                          │
└──────────────────────────────────────────┘

┌─────────────────────────────────────────┐
│        monitoring Namespace             │
│  - Prometheus (메트릭 수집)             │
│  - Loki (로그 저장, 7일 Retention)      │
│  - Grafana (대시보드, AlertManager)      │
└──────────────────────────────────────────┘

┌─────────────────────────────────────────┐
│        falco Namespace                  │
│  - Falco IDS (eBPF syscall 모니터링)    │
│  - Falco Talon IPS (Pod Isolation)      │
│  - Falcosidekick (Alert 라우팅)          │
└──────────────────────────────────────────┘
```

**상세 아키텍처:** [docs/README.md](docs/README.md) | [docs/05-ARCHITECTURE.md](docs/05-ARCHITECTURE.md)

---

## 🔧 기술 스택

### Application Layer

| 구분 | 기술 | 버전 | 역할 |
|------|------|------|------|
| **Frontend** | Hugo | latest | 정적 사이트 생성기 (빌드 속도 0.1초) |
| | PaperMod | - | Hugo 테마 |
| **Backend** | Spring Boot | 3.5.0 | RESTful API |
| | Java | 17 | JVM 런타임 |
| | Hibernate | - | JPA ORM |
| **Database** | MySQL | 8.0 | 관계형 데이터베이스 |

### Infrastructure Layer

| 구분 | 기술 | 버전 | 역할 |
|------|------|------|------|
| **Container** | Kubernetes | 1.31.13 | 컨테이너 오케스트레이션 |
| | Docker | latest | 컨테이너 런타임 |
| **Service Mesh** | Istio | 1.24.1 | mTLS, Traffic Management |
| **GitOps** | ArgoCD | latest | 선언적 배포, selfHeal |
| | Argo Rollouts | latest | Canary 배포 (10% → 50% → 90%) |
| **CI/CD** | GitHub Actions | - | Self-hosted Runner (35초 배포) |
| **CDN** | Cloudflare | - | 글로벌 캐싱, DDoS 방어, SSL |
| **Storage** | Longhorn | latest | 분산 블록 스토리지 |
| | AWS S3 | - | MySQL 백업 저장 (7일 보관) |

### Monitoring & Security (DevSecOps)

| 구분 | 기술 | 버전 | 역할 |
|------|------|------|------|
| **Monitoring** | Prometheus | 2.x | 메트릭 수집 (15초 간격) |
| | Grafana | 12.3.1 | 대시보드 (4개), Alert Rules (8개) |
| | Loki | latest | 로그 저장 (7일 Retention) |
| | AlertManager | 0.27.0 | Alert 라우팅, Slack 연동 |
| **Security** | Falco | 0.42.1 | 런타임 보안 (IDS, eBPF 기반) |
| | Falco Talon | latest | 자동 대응 (IPS, Pod Isolation) |
| | Falcosidekick | latest | Alert 전송 (Loki, WebUI) |
| **Exporters** | nginx-exporter | - | HTTP 메트릭 |
| | mysql-exporter | 0.16.0 | MySQL 메트릭 |
| | node-exporter | - | 노드 메트릭 |

**선택 근거:** [docs/README.md#왜-이렇게-구축했는가](docs/README.md#왜-이렇게-구축했는가)

---

## 📊 현재 상태 (2026-01-23)

### ✅ 프로덕션 운영 중

**애플리케이션**:
- ✅ Hugo 블로그 (https://blog.jiminhome.shop)
- ✅ WAS API (6개 엔드포인트, Swagger UI)
- ✅ MySQL 데이터베이스 (Longhorn PVC)
- ✅ 자동 백업 (매일 03:00 KST → S3)

**인프라**:
- ✅ Kubernetes 3-node 클러스터
- ✅ ArgoCD GitOps (selfHeal 활성화)
- ✅ GitHub Actions CI/CD (35초 배포)
- ✅ MetalLB LoadBalancer (192.168.X.200)
- ✅ HPA (WAS 2-10, WEB 2-5)

**모니터링** (55일 운영):
- ✅ Grafana 대시보드 4개
- ✅ Alert Rules 8개
- ✅ Loki 로그 중앙화 (7일 보관)
- ✅ Prometheus 메트릭 수집

**보안** (DevSecOps P0 완료):
- ✅ Falco IDS (커스텀 룰 4개)
- ✅ Falco Talon IPS (Dry-Run, Pod Isolation)
- ✅ SecurityContext (Non-root, UID 65534)
- ✅ MySQL 자동 백업 (S3, 7일 보관)
- ✅ Loki Retention (168h)

### 📈 운영 성과

| 지표 | 수치 |
|------|------|
| **가동 시간** | 55일 (다운타임 0) |
| **평균 배포 시간** | 35초 |
| **배포 성공률** | 100% (50회 이상) |
| **모니터링 커버리지** | 100% (모든 Pod 메트릭 수집) |
| **보안 탐지** | Falco Alert 수집 중 |
| **데이터 백업** | 매일 자동 (S3 저장) |

---

## 🎯 주요 구현 사항

### 1. DevSecOps P0 개선 (2026-01-23 완료) 🆕

**목표**: 프로덕션 보안 및 안정성 강화

**구현 내용**:
1. ✅ **MySQL 백업 자동화**
   - CronJob (매일 03:00 KST)
   - mysqldump → gzip → S3 업로드
   - S3 Lifecycle (7일 보관)
   - RTO: 5분, RPO: 24시간

2. ✅ **SecurityContext 적용**
   - WAS: UID 65534 (nobody), Non-root
   - WEB: Capabilities drop ALL + 필요 권한만
   - 컨테이너 탈출 공격 방어

3. ✅ **Loki Retention 설정**
   - retention_period: 168h (7일)
   - 자동 삭제 (매일 UTC 00:00)
   - 디스크 고갈 방지

4. ✅ **Falco Talon IPS 구축**
   - Pod Isolation 방식 (Pod Termination 대신)
   - Dry-Run 모드 (Phase 1 학습)
   - 3단계 활성화 전략
   - 커스텀 룰 4개 (RCE 방어, 불변성 위반 등)

**효과**:
- 데이터 손실 위험 99% 감소
- 컨테이너 보안 강화
- IDS → IPS 전환 준비 (대응 시간 5분 → 5초)

**상세:** [docs/README.md#devsecops-p0-개선-완료](docs/README.md#devsecops-p0-개선-완료)

### 2. PLG Stack 모니터링 (55일 운영 중)

**대시보드 (4개)**:
- Nginx Dashboard (HTTP 요청, 응답 시간)
- WAS Dashboard (Spring Boot, JVM 메트릭)
- MySQL Dashboard (쿼리 성능, 커넥션 풀)
- Full Stack Overview (통합 뷰)

**Alert Rules (8개)**:
- PodDown, MySQLDown
- HighCPUUsage, HighMemoryUsage
- HighDiskUsage, PodCrashLooping
- HighErrorRate, HighResponseTime

**접속**: http://monitoring.jiminhome.shop (내부망)

### 3. Falco 런타임 보안 (IDS + IPS)

**탐지 규칙 (커스텀 4개)**:
- **CRITICAL**: Java Process Spawning Shell (RCE 공격)
- **WARNING**: Package Manager in Container (불변성 위반)
- **ERROR**: Write to Binary Dir (악성코드 설치)
- **NOTICE**: Unexpected Outbound Connection (C&C 통신)

**IPS 자동 대응 (Dry-Run)**:
- Falco Talon (Pod Isolation)
- NetworkPolicy 자동 생성
- 3단계 활성화 전략

**접속**: http://falco.jiminhome.shop (내부망)

**상세:** [docs/security/security-falco.md](docs/security/security-falco.md)

### 4. GitOps (ArgoCD)

**배포 방식**:
```bash
# 1. manifest 수정
vi /path/to/k8s-manifests/blog-system/web-rollout.yaml

# 2. Git 푸시
git add . && git commit -m "scale: web replicas 2 → 3" && git push

# 3. ArgoCD 자동 동기화 (3초 이내)
# selfHeal 활성화 → kubectl 수정도 자동 복구
```

**주요 기능**:
- ✅ selfHeal (Drift 자동 복구)
- ✅ Prune (삭제된 리소스 자동 제거)
- ✅ Git이 Single Source of Truth

### 5. Canary 배포 (Argo Rollouts + Istio)

**배포 단계**:
```
1. 10% 트래픽 → Canary → 30초 대기
2. 50% 트래픽 → Canary → 30초 대기
3. 90% 트래픽 → Canary → 30초 대기
4. 100% 트래픽 → 배포 완료
```

**Istio 통합**:
- VirtualService (트래픽 가중치)
- DestinationRule (stable/canary subset)
- mTLS 자동 암호화 (web ↔ was)

---

## 📁 프로젝트 구조

```
blogsite/                       # Hugo 블로그 소스코드
├── docs/                       # 📄 프로젝트 문서 (포트폴리오용)
│   ├── README.md               # 프로젝트 완전 가이드 (1600줄)
│   ├── 02-INFRASTRUCTURE.md    # 인프라 상세
│   ├── 03-TROUBLESHOOTING.md   # 트러블슈팅
│   ├── 04-SOURCE-CODE-GUIDE.md # 소스코드 가이드
│   ├── 05-ARCHITECTURE.md      # 아키텍처 상세
│   ├── monitoring/             # 모니터링 문서
│   ├── security/               # 보안 문서 (Falco)
│   ├── istio/                  # Istio Service Mesh
│   └── CICD/                   # CI/CD 파이프라인
│
├── content/                    # Hugo 콘텐츠 (Markdown)
├── static/                     # 정적 파일 (CSS, Images)
├── layouts/                    # Hugo 템플릿
├── blog-k8s-project/was/       # Spring Boot WAS 소스코드
│   ├── src/main/java/com/jimin/board/
│   ├── pom.xml
│   └── Dockerfile
│
├── .github/workflows/          # GitHub Actions CI/CD
│   ├── deploy-web.yml          # WEB 배포 (35초)
│   └── deploy-was.yml          # WAS 배포
│
├── CLAUDE.md                   # Claude 작업 규칙
└── README.md                   # 이 파일

k8s-manifests/                  # Kubernetes manifests (ArgoCD)
├── blog-system/                # blog-system namespace
│   ├── web-rollout.yaml        # Argo Rollouts Canary
│   ├── was-deployment.yaml     # WAS Deployment
│   ├── mysql-deployment.yaml   # MySQL + PVC
│   ├── mysql-backup-cronjob.yaml  # 자동 백업
│   └── ...
└── docs/helm/                  # Helm values
    ├── loki-stack/values.yaml  # Loki Retention 설정
    └── falco/
        ├── values.yaml         # Falco 커스텀 룰
        └── talon-values.yaml   # Talon IPS 설정
```

---

## 🚀 빠른 시작

### 로컬 개발

```bash
# 1. Hugo 서버 실행
cd /home/jimin/blogsite
hugo server -D

# 2. WAS 로컬 실행
cd blog-k8s-project/was
./mvnw spring-boot:run
```

### 배포

```bash
# 1. 변경사항 커밋
git add .
git commit -m "feat: 새 기능 추가"
git push

# 2. GitHub Actions 자동 빌드 (35초)
# 3. ArgoCD 자동 배포 (3초 감지)
```

### 모니터링 확인

```bash
# Grafana 접속 (내부망)
http://monitoring.jiminhome.shop

# Falco UI 접속 (내부망)
http://falco.jiminhome.shop

# Prometheus 포트포워딩
kubectl port-forward -n monitoring svc/prometheus 9090:9090
```

---

## 📖 문서 가이드

### 포트폴리오 작성용

| 문서 | 내용 | 용도 |
|------|------|------|
| **[docs/README.md](docs/README.md)** | 프로젝트 완전 가이드 (1600줄) | 포트폴리오 메인 |
| **[docs/02-INFRASTRUCTURE.md](docs/02-INFRASTRUCTURE.md)** | 인프라 상세 (Cloudflare, K8s, GitOps) | 기술 스택 증빙 |
| **[docs/05-ARCHITECTURE.md](docs/05-ARCHITECTURE.md)** | Istio Service Mesh 아키텍처 | 아키텍처 설계 |
| **[docs/security/security-falco.md](docs/security/security-falco.md)** | Falco IDS/IPS 상세 (1500줄) | 보안 경험 |
| **[docs/monitoring/README.md](docs/monitoring/README.md)** | PLG Stack 모니터링 | 운영 경험 |

### 기술 문서

| 문서 | 언제 보는가 |
|------|-----------|
| **[docs/03-TROUBLESHOOTING.md](docs/03-TROUBLESHOOTING.md)** | 에러 발생 시 |
| **[docs/04-SOURCE-CODE-GUIDE.md](docs/04-SOURCE-CODE-GUIDE.md)** | WAS 코드 이해 |
| **[docs/CICD/](docs/CICD/)** | CI/CD 파이프라인 이해 |
| **[docs/istio/](docs/istio/)** | Istio Service Mesh 구조 |

---

## 🔗 링크

- **Production**: https://blog.jiminhome.shop
- **GitHub (Blog)**: https://github.com/wlals2/blogsite
- **GitHub (Manifests)**: https://github.com/wlals2/k8s-manifests
- **Grafana**: http://monitoring.jiminhome.shop (내부망)
- **Falco UI**: http://falco.jiminhome.shop (내부망)

---

## 📝 최근 업데이트

### 2026-01-23
- ✅ **DevSecOps P0 완료** (MySQL 백업, SecurityContext, Loki Retention, Falco Talon IPS)
- ✅ 문서 업데이트 (README v2.3, Falco IPS 추가)

### 2026-01-22
- ✅ Falco IDS 구축 (커스텀 룰 4개, Loki 연동)
- ✅ Falcosidekick UI Ingress 설정

### 2026-01-20
- ✅ ArgoCD 설치 (GitOps, selfHeal)
- ✅ Cloudflare Tunnel 설정

### 2026-01-19
- ✅ HPA 구축 (WAS 2-10, WEB 2-5)
- ✅ 문서 통합 (28개 → 3개)

### 2026-01-18
- ✅ GitHub Actions 마이그레이션 (Jenkins → Self-hosted Runner)
- ✅ 배포 시간 61% 개선 (90초 → 35초)

**전체 변경사항:** [CHANGELOG.md](CHANGELOG.md)

---

## 🎓 학습 성과

### 기술 역량

- ✅ **Kubernetes 실전 운영** (55일 프로덕션 운영)
- ✅ **GitOps 구축** (ArgoCD, selfHeal, Pull 모델)
- ✅ **CI/CD 파이프라인** (GitHub Actions, 35초 배포)
- ✅ **Service Mesh** (Istio mTLS, Canary 배포)
- ✅ **모니터링** (PLG Stack, 4 Dashboards, 8 Alert Rules)
- ✅ **런타임 보안** (Falco IDS/IPS, eBPF)
- ✅ **컨테이너 보안** (SecurityContext, Non-root)
- ✅ **데이터 보호** (자동 백업, S3 저장)

### 트러블슈팅 경험

- ✅ Istio mTLS vs MySQL JDBC 호환성
- ✅ Cloudflare Tunnel vs Ingress 라우팅
- ✅ GitHub Actions Self-hosted Runner 구축
- ✅ Argo Rollouts vs ArgoCD ignoreDifferences
- ✅ Falco BuildKit False Positive 처리
- ✅ Loki 디스크 고갈 시나리오

**상세:** [docs/03-TROUBLESHOOTING.md](docs/03-TROUBLESHOOTING.md)

---

## 📧 연락처

- **GitHub**: [@wlals2](https://github.com/wlals2)
- **Email**: your-email@example.com
- **Blog**: https://blog.jiminhome.shop/

---

## 📄 라이선스

MIT License - Copyright (c) 2026 Jimin

---

**마지막 업데이트**: 2026-01-23
**문서 버전**: 2.3 (DevSecOps P0 완료 + Falco Talon IPS)
**프로젝트 상태**: ✅ 프로덕션 운영 중 (55일)
