---
title: "Local K8s Blog - Homeserver Kubernetes 운영 실전"
date: 2026-01-23
summary: "베어메탈 Kubernetes에서 Hugo 블로그 58일 운영: Istio + Cilium + Falco + GitOps 완전 자동화"
tags: ["kubernetes", "bare-metal", "hugo", "istio", "cilium", "falco", "argocd", "gitops", "devsecops", "homelab"]
categories: ["projects"]
series: ["Infrastructure Learning Journey"]
weight: 4
showtoc: true
tocopen: true
draft: false
---

## 📌 프로젝트 개요

> **상태**: ✅ **Production 운영 중** (58일, 2025.11.27 시작)
> **환경**: 베어메탈 Kubernetes 클러스터 (홈서버)
> **목표**: 이 블로그를 Kubernetes Pod로 배포하고 GitOps 자동화 구현
>
> **주요 성과**:
> - ✅ GitHub Actions CI/CD (35초 배포)
> - ✅ ArgoCD GitOps (Auto-Sync, Prune, SelfHeal)
> - ✅ Argo Rollouts Canary 배포
> - ✅ **Istio Service Mesh** (mTLS, Traffic Routing)
> - ✅ **Cilium eBPF** (CNI, kube-proxy 대체)
> - ✅ **Falco Runtime Security** (eBPF IDS)
> - ✅ PLG 모니터링 (4 대시보드, 8 Alert 규칙)
> - ✅ HPA 자동 스케일링 (WAS 2-10, WEB 2-5)

---

## 🎯 왜 이 프로젝트?

### "블로그도 Kubernetes에서 운영해야 진짜 아닌가?"

**현재 상황**:
- 블로그에서 "Kubernetes 전문가"라고 소개
- 하지만 정작 내 블로그는 Netlify/Cloudflare에서 실행 🤔
- **실전 경험**: PetClinic 샘플 앱이 아닌, 매일 사용하는 내 블로그로 운영

**Phase 3 (EKS)와의 차이점**:

| 항목 | Phase 3 (EKS) | Phase 4 (Homeserver K8s) |
|------|--------------|--------------------------|
| **환경** | AWS EKS (클라우드) | 베어메탈 Kubernetes (홈서버) |
| **목적** | 프로덕션급 HA + DR | 블로그 자가 호스팅 + 학습 |
| **WEB** | nginx (정적 파일) | **Hugo 블로그 (이 블로그!)** |
| **WAS** | PetClinic (샘플) | Spring Boot Board (게시판) |
| **DB** | AWS RDS (Multi-AZ) | MySQL Pod (Longhorn PVC 5Gi) |
| **CI/CD** | Jenkins + ArgoCD | **GitHub Actions + ArgoCD** |
| **배포 전략** | Blue-Green | **Argo Rollouts Canary** |
| **Service Mesh** | 없음 | **Istio (mTLS + Traffic)** |
| **CNI** | AWS VPC CNI | **Cilium eBPF** |
| **Runtime Security** | 없음 | **Falco (eBPF IDS)** |
| **모니터링** | CloudWatch | **PLG Stack (58일 운영)** |
| **비용** | $258/월 | **무료** ✅ |

---

## 🏗️ 전체 아키텍처

```
┌─────────────────────────────────────────────────────────────────┐
│                        External Traffic                          │
│                  https://blog.jiminhome.shop                     │
└───────────────────────────┬─────────────────────────────────────┘
                            │
                            ▼
┌───────────────────────────────────────────────────────────────────┐
│  Cloudflare (CDN + DDoS + SSL)                                   │
└───────────────────────────┬───────────────────────────────────────┘
                            │
                            ▼
┌───────────────────────────────────────────────────────────────────┐
│  Kubernetes Cluster (3 nodes: 1 CP + 2 Workers)                  │
│                                                                   │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │  Istio Ingress Gateway (External IP: MetalLB)               │ │
│  │    └─ TLS Termination (Cloudflare Origin Cert)              │ │
│  └──────────────────────────┬──────────────────────────────────┘ │
│                             │                                     │
│  ┌──────────────────────────┴──────────────────────────────────┐ │
│  │  Istio VirtualService (L7 Routing)                          │ │
│  │    ├─ /        → web-service (Hugo)                         │ │
│  │    └─ /api/**  → was-service (Spring Boot)                  │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                                                                   │
│  ┌────────────────┐    ┌────────────────┐    ┌────────────────┐ │
│  │  WEB (Rollout) │    │  WAS (Rollout) │    │     MySQL      │ │
│  │  nginx:alpine  │    │  Spring Boot   │ ──▶│    8.0         │ │
│  │  Replicas: 2   │    │  Replicas: 2   │    │  Longhorn PVC  │ │
│  │  Canary 배포   │    │  HPA 2-10      │    │    5Gi         │ │
│  └────────────────┘    └────────────────┘    └────────────────┘ │
│                                                                   │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │  Security & Observability                                    │ │
│  │    ├─ Cilium (eBPF CNI + NetworkPolicy)                     │ │
│  │    ├─ Istio (mTLS + AuthorizationPolicy)                    │ │
│  │    ├─ Falco (Runtime Security IDS)                          │ │
│  │    └─ PLG Stack (Prometheus + Loki + Grafana)               │ │
│  └─────────────────────────────────────────────────────────────┘ │
└───────────────────────────────────────────────────────────────────┘
```

---

## 🛡️ 기술 스택 상세 (신규 구축)

### Service Mesh & Networking

| 기술 | 역할 | 상세 글 |
|------|------|---------|
| **Istio** | Service Mesh (mTLS, Traffic Routing) | [Istio 아키텍처 구축기](/study/2026-01-22-istio-service-mesh-architecture/) |
| **Istio Traffic** | VirtualService, DestinationRule | [Traffic Management 가이드](/study/2026-01-22-istio-traffic-management/) |
| **Istio mTLS** | Zero Trust 보안 | [mTLS + AuthorizationPolicy](/study/2026-01-22-istio-mtls-security/) |
| **Cilium** | eBPF CNI, kube-proxy 대체 | [Cilium eBPF 가이드](/study/2026-01-22-cilium-ebpf-kube-proxy/) |
| **Hubble** | 네트워크 Observability | [Hubble 트래픽 관찰](/study/2026-01-22-cilium-hubble-observability/) |

### Security (DevSecOps)

| 기술 | 역할 | 상세 글 |
|------|------|---------|
| **Falco** | eBPF 런타임 보안 (IDS) | [Falco 트러블슈팅](/study/2026-01-23-falco-runtime-security-troubleshooting/) |
| **CiliumNetworkPolicy** | L3/L4 Zero Trust | [MySQL 백업 트러블슈팅](/study/2026-01-23-mysql-backup-cronjob-troubleshooting/) |
| **SecurityContext** | Non-root, Capabilities Drop | 아키텍처 문서 참조 |
| **Trivy** | 이미지 취약점 스캔 | GitHub Actions 통합 |

### CI/CD & GitOps

| 기술 | 역할 | 상세 글 |
|------|------|---------|
| **GitHub Actions** | Self-hosted Runner CI | [Runner 트러블슈팅](/study/2026-01-23-runner-not-picking-job/) |
| **ArgoCD** | GitOps CD (Auto-Sync) | [ArgoCD 트러블슈팅](/study/2026-01-23-argocd-troubleshooting/) |
| **Argo Rollouts** | Canary 배포 | [Canary + TopologySpread](/study/2026-01-23-canary-topology-spread/) |

### Storage & Database

| 기술 | 역할 | 상세 글 |
|------|------|---------|
| **Longhorn** | 분산 블록 스토리지 | [Longhorn CSI 트러블슈팅](/study/2026-01-23-longhorn-csi-crashloopbackoff/) |
| **MySQL Backup** | S3 자동 백업 (7일 Lifecycle) | [MySQL 백업 트러블슈팅](/study/2026-01-23-mysql-backup-cronjob-troubleshooting/) |

---

## 🏗️ 상세 아키텍처 (기존 구성)

### Bare-metal Kubernetes Cluster

**Cluster Setup:**
- **Control Plane**: kubeadm으로 구축 (v1.32.0)
- **Container Runtime**: containerd
- **CNI**: Cilium (고성능 네트워킹, eBPF 기반)
- **Storage**: Longhorn (15Gi) + Local-path (75Gi)
- **운영 기간**: **58일** (안정적 운영 중)

### 노드 구성

| 노드 | 역할 | IP | 스펙 |
|------|------|-----|------|
| k8s-cp | Control Plane | 192.168.0.101 | Master, etcd |
| k8s-worker1 | Worker | 192.168.0.61 | 대부분의 워크로드 |
| k8s-worker2 | Worker | 192.168.0.62 | 분산 배치 |

### Application Layer (Namespace: blog-system)

**WEB Rollout (Hugo Blog):**
- **Image**: ghcr.io/wlals2/blog-web (nginx:alpine + Hugo)
- **Multi-stage Build**: Hugo 빌드 → nginx로 정적 파일 서빙
- **Deployment**: Argo Rollouts (Canary 전략)
- **HPA**: 2-5 replicas (CPU 70% 기준)
- **Service**: ClusterIP (Ingress를 통한 접근)
- **Health Check**: `/` 엔드포인트

**WAS Rollout (Spring Boot Board):**
- **Image**: ghcr.io/wlals2/board-was:v16 (Spring Boot 3.2)
- **Deployment**: Argo Rollouts (Canary 전략 + Istio Traffic Routing)
- **HPA**: 2-10 replicas (CPU 70% 기준)
- **ConfigMap**: 환경 변수 주입 (DB 연결 정보)
- **Service**: ClusterIP
- **DB 연결**: MySQL Service → MySQL Pod
- **JVM 튜닝**: -Xms256m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=100
- **HA 설정**: topologySpreadConstraints (DoNotSchedule) + dynamicStableScale

**MySQL StatefulSet:**
- **Image**: mysql:8.0
- **Persistent Volume**: Longhorn PVC 5Gi (데이터 영구 보관)
- **Secret**: DB 자격증명 관리 (board-was-secret)
- **Service**: ClusterIP (WAS에서만 접근)
- **Istio Sidecar**: Disabled (JDBC 호환성)

### CI/CD Pipeline (GitHub Actions + ArgoCD GitOps)

**GitHub Actions (Self-hosted Runner):**
- **Workflow**: deploy-web.yml (WEB 자동 배포)
  1. **Checkout**: PaperMod 테마 포함
  2. **Docker Build**: Multi-stage (Hugo → nginx)
  3. **GHCR Push**: ghcr.io/wlals2/blog-web:vX
  4. **GitOps Update**: k8s-manifests repo의 web-rollout.yaml 이미지 태그 업데이트
  5. **Git Push**: ArgoCD가 자동 감지 (3초 이내)
  6. **ArgoCD Sync**: Auto-Sync로 자동 배포
  7. **Cloudflare Cache**: 전체 캐시 삭제 (purge_everything)
  8. **배포 시간**: **약 35초** ✅

**ArgoCD GitOps:**
- **Application**: blog-system
- **Auto-Sync**: ✅ 활성화 (Git 변경 시 3초 내 배포)
- **Prune**: ✅ 활성화 (Git 삭제 시 K8s 리소스도 삭제)
- **SelfHeal**: ✅ 활성화 (K8s 변경 시 Git으로 되돌림)
- **Sync Status**: Git과 K8s 상태 비교 (OutOfSync 감지)

**Argo Rollouts (Canary Deployment):**
- **WEB/WAS**: Canary 전략 (단계별 트래픽 증가)
- **Automatic Promotion**: Health Check 통과 시 자동 승격
- **Rollback**: 실패 시 이전 버전으로 즉시 롤백

### Monitoring & Observability (PLG Stack)

**Prometheus (Namespace: monitoring):**
- **메트릭 수집**: K8s 클러스터, Pod, Node, Storage
- **Alert Rules**: 8개 (PodCrashLooping, HighMemoryUsage 등)
- **Storage**: Local-path PVC 50Gi
- **Retention**: 15일

**Loki (Namespace: monitoring):**
- **로그 수집**: 모든 Pod 로그 중앙화
- **Storage**: Longhorn PVC 10Gi (복제 3개)
- **Retention**: 7일

**Grafana (Namespace: monitoring):**
- **Dashboard**: 4개 (Cluster, Node, Storage, Application)
- **Alert 연동**: Prometheus Alert 시각화
- **Storage**: Local-path PVC 10Gi
- **운영 기간**: **58일**

---

## 🔧 주요 트러블슈팅

실제 운영 중 발생한 문제들과 해결 과정:

| 문제 | 원인 | 상세 글 |
|------|------|---------|
| kubectl Connection Refused | Private 클러스터 접근 | [해결 가이드](/study/2026-01-23-kubectl-connection-refused/) |
| kubectl HTML 반환 | kubeconfig 오류 | [해결 가이드](/study/2026-01-23-kubectl-returns-html/) |
| Cloudflare 캐시 미삭제 | ZONE_ID Secret 누락 | [해결 가이드](/study/2026-01-23-cloudflare-cache-purge-fail/) |
| Docker 빌드 실패 | .gitignore 문제 | [해결 가이드](/study/2026-01-23-was-docker-build-path-error/) |
| Canary Pod Pending | TopologySpread 충돌 | [해결 가이드](/study/2026-01-23-canary-topology-spread/) |

---

## 💡 주요 학습 포인트

### 1. 베어메탈 Kubernetes 운영 경험

**EKS와의 차이**:
- ❌ EKS: AWS가 Control Plane 관리 → 쉬움
- ✅ Homeserver: kubeadm으로 직접 구축 → **진짜 이해 필요**

**배운 것**:
- kubeadm으로 클러스터 초기화
- CNI 선택 및 설치 (Cilium)
- 분산 스토리지 구축 (Longhorn)
- Ingress Controller 직접 설치 및 관리

### 2. GitOps 완전 자동화 (ArgoCD)

**ArgoCD GitOps 3대 원칙**:
- **Auto-Sync**: Git 변경 감지 → 3초 내 자동 배포
- **Prune**: Git 삭제 → K8s 리소스도 자동 삭제
- **SelfHeal**: K8s 직접 변경 → Git으로 자동 복구

**배운 것**:
- GitOps의 진짜 의미: **Git = Single Source of Truth**
- kubectl 직접 수정 불가 (SelfHeal로 되돌려짐)
- GitHub Actions → manifest 업데이트 → ArgoCD 자동 배포
- 배포 파이프라인 완전 자동화 (35초)

### 3. Multi-stage Docker Build

**Hugo 블로그 이미지 최적화**:
```dockerfile
# Stage 1: Hugo 빌드
FROM klakegg/hugo:0.101.0-alpine AS builder
COPY . /src
RUN hugo --minify

# Stage 2: nginx 서빙
FROM nginx:alpine
COPY --from=builder /src/public /usr/share/nginx/html
```

**결과**: 이미지 크기 대폭 감소 (Hugo 런타임 제외)

### 4. Argo Rollouts Canary 배포

**Canary 배포 전략**:
- **단계적 트래픽 증가**: 10% → 50% → 100%
- **Health Check**: 각 단계마다 Pod 상태 확인
- **Automatic Promotion**: 성공 시 자동 승격
- **Rollback**: 실패 시 이전 버전으로 즉시 복구

**Deployment vs Rollout**:
- Deployment: 단순 롤링 업데이트
- **Rollout**: Canary, Blue-Green 등 고급 전략

### 5. HPA 자동 스케일링

**WAS HPA (2-10 replicas)**:
- CPU 사용률 70% 기준
- 트래픽 증가 시 자동 확장
- 트래픽 감소 시 자동 축소
- 리소스 효율성 극대화

**WEB HPA (2-5 replicas)**:
- 정적 파일 서빙 (CPU 낮음)
- 최소 2개로 가용성 보장

### 6. JVM 튜닝 (컨테이너 최적화)

**WAS Dockerfile JVM 설정**:
```dockerfile
ENTRYPOINT ["java", \
  "-Xms256m", \
  "-Xmx512m", \
  "-XX:+UseG1GC", \
  "-XX:MaxGCPauseMillis=100", \
  "-XX:+UseContainerSupport", \
  "-jar", "app.jar"]
```

**설정 설명**:
| 옵션 | 값 | 목적 |
|------|-----|------|
| **-Xms** | 256m | 최소 힙 (시작 시 할당) |
| **-Xmx** | 512m | 최대 힙 (K8s limit 1Gi의 50%) |
| **-XX:+UseG1GC** | - | G1 GC (짧은 pause time) |
| **-XX:MaxGCPauseMillis** | 100ms | GC pause 목표 |
| **-XX:+UseContainerSupport** | - | 컨테이너 메모리 인식 |

**배운 것**:
- Xmx는 K8s memory limit의 50-75%가 적정
- G1GC는 API 서버에 적합 (짧은 GC pause)
- UseContainerSupport로 cgroup 메모리 제한 인식

### 7. PLG Stack 모니터링 (58일 운영)

**Prometheus + Loki + Grafana**:
- **4개 Dashboard**: Cluster, Node, Storage, Application
- **8개 Alert Rules**: PodCrashLooping, HighMemoryUsage 등
- **중앙화 로그**: 모든 Pod 로그 Loki 수집
- **메트릭 보존**: Prometheus 15일, Loki 7일

**배운 것**:
- 58일간 실제 메트릭 데이터 분석
- Alert 규칙 작성 및 튜닝
- Longhorn vs Local-path 스토리지 성능 비교
- 리소스 사용 패턴 파악

### 8. Service Mesh (Istio)

**Istio 도입으로 얻은 것**:
- **mTLS**: Pod 간 통신 자동 암호화
- **Traffic Management**: VirtualService, DestinationRule로 세밀한 라우팅
- **AuthorizationPolicy**: Zero Trust 보안

### 9. eBPF 기반 보안 (Cilium + Falco)

**Cilium**:
- kube-proxy 대체 (eBPF 기반)
- CiliumNetworkPolicy로 L3/L4 보안

**Falco**:
- 런타임 보안 모니터링
- 컨테이너 이상 행동 탐지

---

## 📊 운영 현황 (58일 안정 운영 중)

### ✅ 완료된 작업

1. ✅ **Bare-metal Kubernetes 클러스터 구축** (kubeadm + Cilium + Longhorn)
2. ✅ **Hugo 블로그 Pod 배포** (nginx:alpine, Multi-stage Build)
3. ✅ **Spring Boot WAS 배포** (board-was:v16, MySQL 연동, JVM 튜닝)
4. ✅ **MySQL StatefulSet 배포** (Longhorn PVC 5Gi, 3 replica)
5. ✅ **Istio Service Mesh** (mTLS, VirtualService, AuthorizationPolicy)
6. ✅ **GitHub Actions CI/CD** (Self-hosted Runner, 35초 배포)
7. ✅ **ArgoCD GitOps 완성** (Auto-Sync, Prune, SelfHeal)
8. ✅ **Argo Rollouts 배포** (Canary 전략)
9. ✅ **HPA 자동 스케일링** (WAS 2-10, WEB 2-5)
10. ✅ **PLG Stack 모니터링** (Prometheus + Loki + Grafana, 4 Dashboard, 8 Alert)
11. ✅ **Falco Runtime Security** (eBPF IDS, Loki 연동)
12. ✅ **MySQL S3 백업** (CronJob, 7일 Lifecycle)

### 📈 운영 성과 (2025.11.27 ~ 현재)

| 지표 | 수치 | 상세 |
|------|------|------|
| **운영 기간** | **58일** | 2025.11.27 시작 (중단 없음) |
| **배포 속도** | **35초** | GitHub Actions GitOps 자동화 |
| **Pod 수** | **~100개** | blog-system + monitoring + argocd + istio + falco |
| **PVC 용량** | **90Gi** | Longhorn 15Gi + Local-path 75Gi |
| **Uptime** | **99%+** | 단 1회 재부팅 (커널 업데이트) |

### Namespace별 서비스

| Namespace | 주요 서비스 |
|-----------|------------|
| blog-system | web, was, mysql |
| istio-system | istiod, ingress, egress, kiali, jaeger |
| monitoring | prometheus, grafana, loki, alertmanager |
| argocd | argocd-server, repo-server |
| falco | falco, falcosidekick |
| longhorn-system | longhorn-manager, csi-plugin |

---

## 🎯 실제 성과 (58일 운영 결과)

### 정량적 성과

| 지표 | 목표 | **실제 결과** | 달성률 |
|------|------|-------------|--------|
| **배포 시간** | 1-2분 | **35초** ✅ | **200%** (목표 대비 3배 빠름) |
| **자동화** | GitOps | **GitHub Actions + ArgoCD** ✅ | **100%** (완전 자동화) |
| **환경** | Kubernetes | **Bare-metal K8s (58일 운영)** ✅ | **100%** |
| **Service Mesh** | 없음 | **Istio mTLS + Traffic** ✅ | **추가 달성** |
| **Runtime Security** | 없음 | **Falco eBPF IDS** ✅ | **추가 달성** |
| **비용** | 무료 | **$0/월** ✅ | **100%** |
| **가용성** | 95%+ | **99%+ (1회 재부팅)** ✅ | **100%** |

### 정성적 성과

1. ✅ **실전 경험**: 매일 사용하는 블로그로 58일 운영 (샘플 앱 아님)
2. ✅ **장애 대응**: 실제 장애 대응 경험 (Istio mTLS + MySQL JDBC 충돌 해결)
3. ✅ **GitOps 완성**: ArgoCD Auto-Sync + SelfHeal + Prune 3대 원칙 체득
4. ✅ **베어메탈 운영**: kubeadm 클러스터 직접 구축 및 58일 관리
5. ✅ **모니터링 구축**: PLG Stack으로 58일간 메트릭/로그 수집 및 분석
6. ✅ **Service Mesh 도입**: Istio로 mTLS, Traffic Management 구현
7. ✅ **DevSecOps 구축**: Falco + Cilium으로 런타임/네트워크 보안

---

## 🔮 다음 단계

- [ ] Prometheus Alert → Slack 연동
- [ ] MySQL HA (Primary-Replica)
- [ ] SealedSecrets (GitOps Secret 관리)
- [ ] SLO/SLI 대시보드

---

## 💭 왜 베어메탈 Kubernetes인가?

### Phase 3 (EKS)의 한계

**EKS에서 배운 것**:
- ✅ Managed Kubernetes의 편리함
- ✅ AWS 생태계 통합 (ALB, RDS, Route53)
- ✅ 프로덕션급 HA 구성

**하지만...**
- ❌ Control Plane은 블랙박스 (AWS가 관리)
- ❌ 비용 부담 ($258/월)
- ❌ PetClinic 샘플 앱 → 실제 사용 안 함

### Homeserver K8s의 장점

**배울 수 있는 것**:
- ✅ kubeadm으로 클러스터 직접 구축
- ✅ CNI, Storage 직접 선택 및 관리
- ✅ Ingress Controller 직접 설치
- ✅ 진짜 트러블슈팅 (AWS 지원 없음)

**실전 경험**:
- ✅ 매일 사용하는 블로그 → 장애 시 즉시 인지
- ✅ 무료 운영 → 무한 실험 가능
- ✅ 로컬 환경 → 네트워크 디버깅 편리

---

## 🔗 관련 문서

- [전체 아키텍처 문서](/docs/05-ARCHITECTURE.md)
- [DevSecOps 아키텍처](/k8s-manifests/docs/DEVSECOPS-ARCHITECTURE.md)
- [트러블슈팅 모음](/docs/03-TROUBLESHOOTING.md)

---

## 📝 업데이트 로그

- **2025-11-27**: 프로젝트 시작, Bare-metal K8s 클러스터 구축
- **2025-12-XX**: Hugo 블로그 + Spring Boot WAS 배포 완료
- **2025-12-XX**: GitHub Actions CI/CD + ArgoCD GitOps 완성
- **2026-01-XX**: Argo Rollouts Canary 배포 + HPA 적용
- **2026-01-15**: PLG Stack 모니터링 완성 (4 Dashboard, 8 Alert)
- **2026-01-20**: Nextcloud 삭제 (30Gi 절약), 스토리지 최적화 완료
- **2026-01-22**: WAS v1.4.0 기능 추가 (viewCount 조회수, 원자적 UPDATE)
- **2026-01-22**: JVM 튜닝 적용 (G1GC, Heap 256-512MB)
- **2026-01-22**: Istio Service Mesh 구축 (mTLS, VirtualService)
- **2026-01-22**: Cilium eBPF CNI 구축 (kube-proxy 대체)
- **2026-01-23**: Falco Runtime Security 구축 (eBPF IDS)
- **2026-01-23**: 트러블슈팅 글 작성 (10개)

---

**최종 업데이트**: 2026-01-23
**상태**: ✅ Production 운영 중
