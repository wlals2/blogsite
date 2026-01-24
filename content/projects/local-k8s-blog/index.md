---
title: "Local K8s Blog - Homeserver Kubernetes 운영 실전 🏠"
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
> - ✅ **Istio Service Mesh** (mTLS, Traffic Routing) ← 신규
> - ✅ **Cilium eBPF** (CNI, kube-proxy 대체) ← 신규
> - ✅ **Falco Runtime Security** (eBPF IDS) ← 신규
> - ✅ PLG 모니터링 (4 대시보드, 8 Alert 규칙)
> - ✅ HPA 자동 스케일링 (WAS 2-10, WEB 2-5)
> - ✅ 스토리지 최적화 (30Gi 절약, 90Gi 운영)

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
| **모니터링** | CloudWatch | **PLG Stack (58일 운영)** |
| **HPA** | 미적용 | **WAS 2-10, WEB 2-5** |
| **비용** | $258/월 | **무료** ✅ |
| **실사용** | 샘플 앱 | **매일 사용 (58일)** ✅ |

---

## 🏗️ 상세 아키텍처

![Homeserver Kubernetes Architecture](/images/architecture/phase4-home-server.webp)

**아키텍처 구성 요소:**

### Bare-metal Kubernetes Cluster

| 구성 요소 | 상세 |
|----------|------|
| **Control Plane** | kubeadm 기반 (v1.31.13) |
| **Container Runtime** | containerd |
| **CNI** | Cilium (eBPF 기반 고성능 네트워킹) |
| **Storage** | Longhorn (15Gi) + Local-path (75Gi) |
| **운영 기간** | **58일** (안정적 운영 중) |

### Networking & Service Mesh

| 구성 요소 | 상세 |
|----------|------|
| **Istio Gateway** | blog-gateway (단일 L7 진입점) |
| **LoadBalancer** | MetalLB (192.168.1.200) |
| **Cloudflare** | CDN + SSL/TLS 종료 + DDoS 방어 |
| **VirtualService** | Path-based 라우팅 (`/` → web, `/api` → was) |
| **mTLS** | PERMISSIVE (평문 + mTLS 모두 허용) |
| **개선 효과** | Nginx Ingress 제거로 레이턴시 21% 감소 |

**트래픽 플로우:**
```
Cloudflare (HTTPS) → MetalLB (192.168.1.200) → Istio Gateway → VirtualService → Services
```

### Application Layer (Namespace: blog-system)

#### WEB (Hugo Blog)

| 항목 | 상세 |
|------|------|
| **Image** | ghcr.io/wlals2/blog-web (nginx:alpine + Hugo) |
| **빌드** | Multi-stage (Hugo 빌드 → nginx 서빙) |
| **배포** | Argo Rollouts (Canary 전략) |
| **Auto Scaling** | HPA 2-5 replicas (CPU 70%) |
| **Service** | ClusterIP (Istio Gateway 경유) |
| **Health Check** | `/` 엔드포인트 |

#### WAS (Spring Boot Board)

| 항목 | 상세 |
|------|------|
| **Image** | ghcr.io/wlals2/board-was:v16 (Spring Boot 3.2) |
| **배포** | Argo Rollouts (Canary + Istio Traffic Routing) |
| **Auto Scaling** | HPA 2-10 replicas (CPU 70%) |
| **JVM 튜닝** | -Xms256m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=100 |
| **HA 설정** | topologySpreadConstraints + dynamicStableScale |
| **DB 연결** | MySQL Service → MySQL Pod |

#### MySQL Database

| 항목 | 상세 |
|------|------|
| **Image** | mysql:8.0 |
| **Storage** | Longhorn PVC 5Gi (영구 보관) |
| **Secret** | board-was-secret (자격증명 관리) |
| **Service** | ClusterIP (WAS에서만 접근) |
| **Istio Sidecar** | Disabled (JDBC 호환성) |

### CI/CD Pipeline (GitOps)

**배포 흐름**: Git Push → GitHub Actions (CI) → GitOps Manifest 업데이트 → ArgoCD (CD) → Canary 배포

- **총 배포 시간**: 약 35초 (Hugo 빌드 → Docker 이미지 → GHCR Push → GitOps Update → ArgoCD Sync → Cloudflare 캐시 퍼지)
- **GitOps 원칙**: Git = Single Source of Truth (SSOT), kubectl 직접 배포 금지
- **Canary 배포**: WEB (10%→50%→90%, 30초 간격), WAS (20%→50%→80%, 1분 간격)
- **ArgoCD 기능**: Auto-Sync, Prune, SelfHeal (Git ↔ K8s 상태 동기화)

> 📖 **상세 가이드**: [GitOps CI/CD 파이프라인 구축](/study/2026-01-20-gitops-cicd-pipeline/), [Canary 배포 전략 비교](/study/2026-01-21-canary-deployment-web-was-comparison/)

### Monitoring & Observability (PLG Stack)

| 구성 요소 | 메트릭/로그 수집 | Storage | Retention | 비고 |
|----------|-----------------|---------|-----------|------|
| **Prometheus** | K8s 클러스터, Pod, Node, Storage | Local-path 50Gi | 15일 | Alert Rules 8개 |
| **Loki** | 모든 Pod 로그 중앙화 | Longhorn 10Gi | 7일 | 복제 3개 |
| **Grafana** | 시각화 (4개 Dashboard) | Local-path 10Gi | - | 운영 58일 |
| **Pushgateway** | Batch Job 메트릭 | Local-path 5Gi | - | 단기 작업용 |

---

## 🛠️ 기술 스택

### 기존 인프라 (활용)

| 컴포넌트 | 버전/상태 | 역할 |
|---------|----------|------|
| **Kubernetes** | v1.31.13 | 베어메탈 멀티 노드 (51일+ 운영) |
| **CNI** | Cilium | eBPF 기반 고성능 네트워킹 |
| **Storage** | Longhorn | 분산 스토리지 (3 replica) |
| **Monitoring** | Prometheus + Grafana | 기존 모니터링 스택 활용 |

### 신규 구축 (Local K8s Blog)

| 레이어 | 기술 | 선택 이유 |
|--------|------|----------|
| **Ingress** | nginx-ingress | Path-based Routing (`/`, `/board`) |
| **WEB** | Hugo + nginx:alpine | 이 블로그 자체를 Pod로 배포 |
| **WAS** | Spring Boot 3.2 | 게시판 CRUD 기능 |
| **DB** | MySQL 8.0 | Longhorn PVC 5Gi (복제 3개) |
| **CI/CD** | GitHub Actions | Self-hosted Runner (35초 배포) |
| **GitOps** | ArgoCD | Auto-Sync, Prune, SelfHeal |
| **Deployment** | Argo Rollouts | Canary 배포 전략 |
| **HPA** | K8s HPA | WAS 2-10, WEB 2-5 자동 스케일링 |
| **Monitoring** | PLG Stack | Prometheus + Loki + Grafana (58일) |

---

## 🛡️ 신규 구축 기술 스택 (2026.01)

### Service Mesh & Networking

| 기술 | 역할 | 상세 글 |
|------|------|---------|
| **Istio Service Mesh** | mTLS, Circuit Breaker, Retry, Timeout | [Istio 아키텍처 완전 가이드](/study/2026-01-22-istio-service-mesh-architecture/) |
| **Istio Gateway** | Nginx Ingress → Istio Gateway 마이그레이션 | [Gateway 일원화 (레이턴시 21% 감소)](/study/2026-01-24-nginx-ingress-to-istio-gateway/) |
| **PassthroughCluster** | Host 헤더 문제 해결 | [Istio mesh 통합 트러블슈팅](/study/2026-01-20-nginx-proxy-istio-mesh-passthrough/) |
| **Cilium** | eBPF CNI, NetworkPolicy | [Cilium eBPF 네트워킹 & Hubble 관측성](/study/2026-01-14-cilium-ebpf-networking/) |
| **Hubble** | 네트워크 Observability | [Cilium eBPF 네트워킹 & Hubble 관측성](/study/2026-01-14-cilium-ebpf-networking/) |

### Security (DevSecOps)

| 기술 | 역할 | 상세 글 |
|------|------|---------|
| **Falco** | eBPF 런타임 보안 (IDS) | [Falco 트러블슈팅](/study/2026-01-23-falco-runtime-security-troubleshooting/) |
| **CiliumNetworkPolicy** | L3/L4 Zero Trust | [MySQL 백업 트러블슈팅](/study/2026-01-23-mysql-backup-cronjob-troubleshooting/) |
| **SecurityContext** | Non-root, Capabilities Drop | 아키텍처 문서 참조 |
| **Trivy** | 이미지 취약점 스캔 | GitHub Actions 통합 |
| **Private GHCR** | 컨테이너 이미지 비공개 | imagePullSecrets 설정 |

### CI/CD & GitOps

| 기술 | 역할 | 상세 글 |
|------|------|---------|
| **GitOps Pipeline** | GitHub Actions + ArgoCD (SSOT) | [GitOps CI/CD 파이프라인 구축](/study/2026-01-20-gitops-cicd-pipeline/) |
| **Canary Deployment** | WEB vs WAS 전략 비교 | [Canary 배포 전략 비교](/study/2026-01-21-canary-deployment-web-was-comparison/) |
| **GitHub Actions** | Self-hosted Runner CI | [Runner 트러블슈팅](/study/2026-01-23-runner-not-picking-job/) |
| **ArgoCD** | GitOps CD (Auto-Sync) | [ArgoCD 트러블슈팅](/study/2026-01-23-argocd-troubleshooting/) |
| **Argo Rollouts** | Canary + TopologySpread | [Canary + TopologySpread](/study/2026-01-23-canary-topology-spread/) |
| **Private GHCR** | 이미지 비공개 + imagePullSecrets | 아래 상세 설명 |

#### Private Container Registry 보안

**문제 발견**: WEB 이미지(`ghcr.io/wlals2/blog-web`)가 Public으로 노출
- 누구나 `docker pull`로 블로그 콘텐츠 복제 가능
- Hugo 빌드 결과물(정적 파일)이 이미지에 포함

**해결: Private GHCR + imagePullSecrets**

```yaml
# web-rollout.yaml
spec:
  template:
    spec:
      imagePullSecrets:
        - name: ghcr-secret  # Private GHCR 인증
      containers:
        - name: nginx
          image: ghcr.io/wlals2/blog-web:v60
```

**ghcr-secret 생성**:
```bash
kubectl create secret docker-registry ghcr-secret \
  --namespace blog-system \
  --docker-server=ghcr.io \
  --docker-username=wlals2 \
  --docker-password=ghp_xxxxx  # GitHub PAT (read:packages)
```

**보안 효과**:
- ✅ 인증 없이 이미지 pull 불가
- ✅ 블로그 콘텐츠 무단 복제 방지
- ✅ K8s Pod만 ghcr-secret으로 인증하여 pull

### Storage & Database

| 기술 | 역할 | 상세 글 |
|------|------|---------|
| **Longhorn** | 분산 블록 스토리지 | [Longhorn CSI 트러블슈팅](/study/2026-01-23-longhorn-csi-crashloopbackoff/) |
| **MySQL Backup** | S3 자동 백업 (7일 Lifecycle) | [MySQL 백업 트러블슈팅](/study/2026-01-23-mysql-backup-cronjob-troubleshooting/) |

---

## 📚 핵심 기술 상세

### 전체 DevSecOps 아키텍처

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         External Traffic (HTTPS)                            │
│                               blog.jiminhome.shop                           │
└───────────────────────────────────┬─────────────────────────────────────────┘
                                    ↓
┌───────────────────────────────────────────────────────────────────────────────┐
│  ☁️ Cloudflare (CDN + WAF + Tunnel)                                          │
│  ├─ DDoS Protection (Layer 3/4/7)                                            │
│  ├─ WAF Rules (SQL Injection, XSS 차단)                                      │
│  └─ Tunnel → NodePort 30080                                                  │
└───────────────────────────────────┬───────────────────────────────────────────┘
                                    ↓
┌───────────────────────────────────────────────────────────────────────────────┐
│  🌐 Kubernetes Cluster (Bare-metal, kubeadm v1.31.13)                        │
│  ├─ Control Plane (1) + Workers (2)                                          │
│  └─ CNI: Cilium eBPF (kube-proxy 미대체, Hubble Observability)               │
├───────────────────────────────────────────────────────────────────────────────┤
│                                                                               │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │ 📦 Namespace: ingress-nginx                                             │ │
│  │ ├─ nginx-ingress-controller                                             │ │
│  │ ├─ Path Routing: / → web, /api → was, /board → was                      │ │
│  │ └─ NodePort 30080                                                       │ │
│  └───────────────────────────────────┬─────────────────────────────────────┘ │
│                                      ↓                                       │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │ 📦 Namespace: blog-system                              [Istio Mesh]     │ │
│  │ ┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐      │ │
│  │ │   web-rollout   │    │   was-rollout   │    │  mysql-stateful │      │ │
│  │ │ ┌─────────────┐ │    │ ┌─────────────┐ │    │ ┌─────────────┐ │      │ │
│  │ │ │ nginx       │ │    │ │ Spring Boot │ │    │ │ MySQL 8.0   │ │      │ │
│  │ │ │ Hugo Static │ │    │ │ board-was   │ │    │ │ PVC 5Gi     │ │      │ │
│  │ │ ├─────────────┤ │    │ ├─────────────┤ │    │ │ (no sidecar)│ │      │ │
│  │ │ │istio-proxy  │◀─mTLS─▶│istio-proxy  │ │    │ └─────────────┘ │      │ │
│  │ │ │ (sidecar)   │ │    │ │ (sidecar)   │◀JDBC▶│     plain      │      │ │
│  │ │ └─────────────┘ │    │ └─────────────┘ │    └─────────────────┘      │ │
│  │ │ HPA: 2-5       │    │ HPA: 2-10       │                             │ │
│  │ │ Canary Deploy  │    │ Canary + Istio  │                             │ │
│  │ └─────────────────┘    └─────────────────┘                             │ │
│  │                                                                        │ │
│  │ 🛡️ Security Layer:                                                     │ │
│  │ ├─ PeerAuthentication (PERMISSIVE mTLS)                                │ │
│  │ ├─ DestinationRule (mTLS ISTIO_MUTUAL)                                 │ │
│  │ ├─ AuthorizationPolicy (Zero Trust)                                    │ │
│  │ └─ CiliumNetworkPolicy (L3/L4 filtering)                               │ │
│  └─────────────────────────────────────────────────────────────────────────┘ │
│                                                                               │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │ 📦 Namespace: monitoring (PLG Stack)                                    │ │
│  │ ┌────────────┐  ┌────────────┐  ┌────────────┐  ┌────────────┐        │ │
│  │ │ Prometheus │  │   Loki     │  │  Grafana   │  │Pushgateway │        │ │
│  │ │ 50Gi       │  │ 10Gi       │  │ 10Gi       │  │ 5Gi        │        │ │
│  │ │ 15d retain │  │ 7d retain  │  │ 4 Dashboard│  │ Batch Job  │        │ │
│  │ │ 8 Alerts   │  │ Promtail   │  │ Alert View │  │ Metrics    │        │ │
│  │ └────────────┘  └────────────┘  └────────────┘  └────────────┘        │ │
│  └─────────────────────────────────────────────────────────────────────────┘ │
│                                                                               │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │ 📦 Namespace: falco (Runtime Security)                                  │ │
│  │ ┌────────────┐  ┌────────────┐                                         │ │
│  │ │   Falco    │  │Falcosidekick│──────────────────▶ Loki               │ │
│  │ │  DaemonSet │  │ (forwarder) │                                        │ │
│  │ │ eBPF probes│  │             │                                        │ │
│  │ └────────────┘  └────────────┘                                         │ │
│  └─────────────────────────────────────────────────────────────────────────┘ │
│                                                                               │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │ 📦 Namespace: argocd (GitOps CD)                                        │ │
│  │ ├─ ArgoCD Server (Auto-Sync, Prune, SelfHeal)                          │ │
│  │ └─ Git Source: github.com/wlals2/k8s-manifests                         │ │
│  └─────────────────────────────────────────────────────────────────────────┘ │
│                                                                               │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │ 📦 Namespace: argo-rollouts                                             │ │
│  │ ├─ Rollouts Controller                                                  │ │
│  │ └─ Canary + Istio Traffic Routing                                       │ │
│  └─────────────────────────────────────────────────────────────────────────┘ │
│                                                                               │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │ 💾 Storage Layer                                                        │ │
│  │ ├─ Longhorn (15Gi): MySQL PVC (3 replica, 복제)                         │ │
│  │ └─ Local-path (75Gi): Prometheus, Grafana, Pushgateway                  │ │
│  └─────────────────────────────────────────────────────────────────────────┘ │
│                                                                               │
└───────────────────────────────────────────────────────────────────────────────┘
                                    ↑
┌───────────────────────────────────────────────────────────────────────────────┐
│  🔄 CI/CD Pipeline (GitHub Actions Self-hosted Runner)                       │
│  ├─ blogsite repo (Hugo) → Docker Build → GHCR Push → k8s-manifests 업데이트 │
│  └─ ArgoCD Auto-Sync (3초) → Argo Rollouts Canary → 35초 배포 완료           │
└───────────────────────────────────────────────────────────────────────────────┘
```

**아키텍처 계층별 역할:**

| 계층 | 기술 | 역할 |
|------|------|------|
| **Edge** | Cloudflare | DDoS 방어, WAF, CDN, Tunnel |
| **Ingress** | nginx-ingress | L7 라우팅, SSL Termination |
| **Service Mesh** | Istio | mTLS, Traffic Routing, AuthZ |
| **CNI** | Cilium | eBPF 네트워킹, Hubble Observability |
| **Runtime Security** | Falco | eBPF IDS, 이상 행위 탐지 |
| **Monitoring** | PLG Stack | 메트릭, 로그, 대시보드, 알람 |
| **GitOps** | ArgoCD | 자동 동기화, Rollback, SelfHeal |
| **Deployment** | Argo Rollouts | Canary 배포, 트래픽 라우팅 |
| **Storage** | Longhorn + Local-path | 분산 스토리지 (90Gi) |

---

### Istio Service Mesh 핵심

**mTLS 트래픽 플로우:**
```
[External Traffic]
       ↓ HTTPS
[Nginx Ingress Controller]
       ↓ HTTP (plain text)
[web pod]
 ├─ nginx (reverse proxy)
 ├─ istio-proxy (sidecar)
       ↓ mTLS (encrypted) ← 자동 암호화
[was pod]
 ├─ Spring Boot WAS
 ├─ istio-proxy (sidecar)
       ↓ plain text (JDBC)
[mysql] ← mesh 제외
```

**주요 구성:**

| 리소스 | 개수 | 역할 |
|--------|------|------|
| PeerAuthentication | 2개 | mTLS 모드 설정 (PERMISSIVE) |
| DestinationRule | 3개 | 서비스별 mTLS 강제 |
| VirtualService | 1개 | L7 트래픽 라우팅 |
| AuthorizationPolicy | 2개 | Zero Trust 접근 제어 |

**왜 PERMISSIVE인가?**
- Nginx Ingress는 mesh 외부에서 동작
- STRICT 설정 시 Ingress → web 통신에서 502 에러 발생
- PERMISSIVE로 plain text + mTLS 둘 다 허용

**nginx Host 헤더 문제 해결:**
```nginx
# Before (문제)
proxy_set_header Host $host;  # → blog.jiminhome.shop

# After (해결)
proxy_set_header Host was-service;  # 서비스명으로 변경
```

---

### Cilium eBPF 핵심

**kube-proxy vs Cilium eBPF 비교:**

| 항목 | kube-proxy | Cilium eBPF |
|------|------------|-------------|
| **구현** | iptables 규칙 | eBPF 프로그램 |
| **성능** | 보통 | **30-40% 빠름** |
| **Latency** | 보통 | **30% 감소** |
| **CPU 사용량** | 보통 | **낮음** |
| **Service 타입** | ClusterIP, NodePort, LB | 모두 + DSR 지원 |

**현재 선택: kube-proxy 유지**
- 로컬 클러스터 환경 (3노드)
- 실험 및 학습 목적
- 안정성 우선 (불필요한 리스크 회피)
- Hubble UI/Relay로 충분한 Observability 확보

**Hubble 네트워크 관찰:**
```bash
# 실시간 트래픽 관찰
hubble observe --namespace blog-system

# mTLS 상태 확인
hubble observe --verdict FORWARDED | grep ENCRYPTED
```

---

### Falco Runtime Security 핵심

**eBPF 기반 IDS 아키텍처:**
```
[Kernel Space]
     ↑ eBPF probes
[Falco Engine]
     ↓ Alerts
[Falcosidekick]
     ↓ Forward
[Loki] → [Grafana Dashboard]
```

**주요 탐지 규칙:**

| 규칙 | 심각도 | 탐지 대상 |
|------|--------|----------|
| Terminal shell in container | Warning | 컨테이너 내 쉘 접근 |
| Drop and execute new binary | Critical | 새 바이너리 실행 |
| Sensitive file access | Warning | /etc/passwd 등 접근 |
| Network tool in container | Notice | curl, wget 실행 |

**False Positive 처리 (BuildKit):**
```yaml
# BuildKit 예외 규칙
customRules:
  blog-rules.yaml: |-
    - rule: Drop and execute new binary in container
      append: true
      exceptions:
        - name: buildkit_binaries
          fields: [container.image.repository]
          values: [[moby/buildkit]]
```

**Quick Reference:**

| 증상 | 원인 | 해결 |
|------|------|------|
| CrashLoopBackOff + inotify | inotify 제한 | sysctl 설정 증가 |
| Loki no such host | DNS/서비스 | Loki 서비스 확인 |
| BPF probe 실패 | 커널 버전 | ebpf 드라이버 변경 |

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

### 트러블슈팅 상세 기록

#### 1. kubectl Connection Refused (Self-hosted Runner)

**문제**: GitHub Actions에서 kubectl 명령 실행 시 Connection Refused
```
Error: The connection to the server xxx:6443 was refused
```

**원인 분석**:
- GitHub Actions Default Runner는 Azure 데이터센터에서 실행
- Private K8s 클러스터의 API Server는 외부 접근 불가
- kubeconfig의 server 주소가 내부 IP (192.168.x.x)

**해결 방법**: Self-hosted Runner 구축
```bash
# Runner 설치 (k8s 노드에서)
mkdir actions-runner && cd actions-runner
curl -o actions-runner-linux-x64-2.311.0.tar.gz -L \
  https://github.com/actions/runner/releases/download/v2.311.0/actions-runner-linux-x64-2.311.0.tar.gz
./config.sh --url https://github.com/wlals2/k8s-manifests --token XXX
./run.sh
```

**결과**:
- Before: Connection Refused (외부 Runner)
- After: 정상 연결 (Self-hosted Runner, kubectl 직접 실행)

---

#### 2. kubectl이 HTML을 반환하는 문제

**문제**: kubectl 명령 결과로 HTML 페이지 반환
```bash
$ kubectl get pods
<!DOCTYPE html>
<html>
<head><title>403 Forbidden</title>...
```

**원인**: kubeconfig가 프록시 서버를 가리킴 (Cloudflare Tunnel)

**진단**:
```bash
# kubeconfig 확인
cat ~/.kube/config | grep server
# server: https://blog.jiminhome.shop:443  ← 잘못됨!

# 정상 설정
# server: https://192.168.122.10:6443
```

**해결**: 올바른 kubeconfig 설정
```bash
# Control Plane에서 kubeconfig 복사
scp control-plane:/etc/kubernetes/admin.conf ~/.kube/config

# server 주소 확인
server: https://192.168.122.10:6443  # 내부 IP
```

---

#### 3. ArgoCD 트러블슈팅 모음

**문제 1: OutOfSync 무한 반복**
```
App Status: OutOfSync → Synced → OutOfSync (반복)
```

**원인**: kubectl로 직접 수정 → SelfHeal이 Git으로 되돌림

**해결**: Git을 통해서만 수정 (kubectl edit 사용 금지)

---

**문제 2: Sync 실패 (Health Check 실패)**
```
SyncFailed: one or more objects failed to apply
```

**원인**: Pod가 Ready 상태 도달 전에 Health Check 실패

**해결**: Health Check Timeout 증가
```yaml
spec:
  syncPolicy:
    syncOptions:
      - CreateNamespace=true
      - PrunePropagationPolicy=foreground
      - PruneLast=true
  # Health check 재시도 설정
  ignoreDifferences:
    - group: apps
      kind: Deployment
      jsonPointers:
        - /spec/replicas
```

---

#### 4. Canary Pod Pending (TopologySpread 충돌)

**문제**: Canary 배포 시 새 Pod가 Pending 상태 유지
```
Events:
  Warning  FailedScheduling  0/3 nodes are available:
  1 node(s) didn't match pod topology spread constraints
```

**원인**: TopologySpread + 동적 Replica 수 충돌
- `whenUnsatisfiable: DoNotSchedule` 설정
- Canary 배포 시 Replica가 늘어나면 spread 제약 위반

**해결 1: ScheduleAnyway 사용**
```yaml
topologySpreadConstraints:
  - maxSkew: 1
    topologyKey: kubernetes.io/hostname
    whenUnsatisfiable: ScheduleAnyway  # DoNotSchedule → ScheduleAnyway
```

**해결 2: dynamicStableScale 활성화** (Argo Rollouts)
```yaml
spec:
  strategy:
    canary:
      dynamicStableScale: true  # Stable replica 동적 조정
```

---

#### 5. GitHub Actions Runner Job 미실행

**문제**: Runner 상태는 Active인데 Job이 실행되지 않음
```
Waiting for a self-hosted runner to pick up this job...
```

**원인**: Runner Label 불일치

**진단**:
```bash
# Workflow의 runs-on
runs-on: self-hosted

# Runner 실제 label
./config.sh --labels self-hosted,linux,x64
```

**해결**: Label 정확히 매칭
```yaml
# workflow.yml
runs-on: [self-hosted, linux, x64]
```

---

#### 6. Longhorn CSI CrashLoopBackOff

**문제**: longhorn-csi-plugin Pod가 CrashLoopBackOff
```
Error: rpc error: code = Internal
desc = fail to create longhorn client
```

**원인**: Longhorn Manager가 아직 Ready가 아닌 상태에서 CSI 시작

**해결**: Longhorn 재설치 순서
```bash
# 1. Longhorn 삭제
kubectl delete ns longhorn-system --grace-period=0 --force

# 2. 남은 리소스 정리
kubectl get crd | grep longhorn | xargs kubectl delete crd

# 3. Helm으로 재설치
helm install longhorn longhorn/longhorn --namespace longhorn-system --create-namespace

# 4. Manager Ready 확인 후 CSI 확인
kubectl wait --for=condition=Ready pod -l app=longhorn-manager -n longhorn-system --timeout=300s
```

---

#### 7. MySQL 백업 CronJob (Cilium + Istio 환경)

**문제**: CronJob Pod가 S3 업로드 실패
```
Error: unable to connect to s3.amazonaws.com
```

**원인**: CiliumNetworkPolicy가 egress 트래픽 차단

**해결**: egress 규칙 추가
```yaml
apiVersion: cilium.io/v2
kind: CiliumNetworkPolicy
metadata:
  name: mysql-backup-egress
spec:
  endpointSelector:
    matchLabels:
      app: mysql-backup
  egress:
    - toFQDNs:
        - matchPattern: "*.amazonaws.com"
      toPorts:
        - ports:
            - port: "443"
              protocol: TCP
```

**Istio Sidecar 문제**:
- CronJob에 sidecar가 inject되면 Job이 종료되지 않음
- `sidecar.istio.io/inject: "false"` annotation 필수

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

### 8. Ingress Path Routing

**하나의 IP로 여러 서비스 접근**:
- `/` → Hugo 블로그
- `/board` → Spring Boot 게시판
- `/api/*` → REST API

**배운 것**: L7 라우팅, rewrite 규칙, CORS 설정

---

## 📊 운영 현황 (58일 안정 운영 중)

### ✅ 완료된 작업

1. ✅ **Bare-metal Kubernetes 클러스터 구축** (kubeadm + Cilium + Longhorn)
2. ✅ **Hugo 블로그 Pod 배포** (nginx:alpine, Multi-stage Build)
3. ✅ **Spring Boot WAS 배포** (board-was:v16, MySQL 연동, JVM 튜닝)
4. ✅ **MySQL StatefulSet 배포** (Longhorn PVC 5Gi, 3 replica)
5. ✅ **nginx Ingress 설정** (Path-based Routing: `/`, `/board`, `/api`)
6. ✅ **GitHub Actions CI/CD** (Self-hosted Runner, 35초 배포)
7. ✅ **ArgoCD GitOps 완성** (Auto-Sync, Prune, SelfHeal)
8. ✅ **Argo Rollouts 배포** (Canary 전략)
9. ✅ **HPA 자동 스케일링** (WAS 2-10, WEB 2-5)
10. ✅ **PLG Stack 모니터링** (Prometheus + Loki + Grafana, 4 Dashboard, 8 Alert)
11. ✅ **스토리지 최적화** (Nextcloud 삭제, 30Gi 절약)
12. ✅ **WAS v1.4.0 기능** (viewCount 조회수 + 원자적 UPDATE 최적화)
13. ✅ **JVM 튜닝** (G1GC, Heap 256-512MB, 컨테이너 최적화)
14. ✅ **HA 설정** (topologySpreadConstraints DoNotSchedule + dynamicStableScale)

### 📈 운영 성과 (2025.11.27 ~ 현재)

| 지표 | 수치 | 상세 |
|------|------|------|
| **운영 기간** | **58일** | 2025.11.27 시작 (중단 없음) |
| **배포 속도** | **35초** | GitHub Actions GitOps 자동화 |
| **Pod 수** | **98개** | blog-system (8) + monitoring (15) + argocd (7) + 시스템 |
| **PVC 수** | **5개** | MySQL (5Gi) + PLG Stack (75Gi) |
| **스토리지** | **90Gi** | Longhorn 15Gi + Local-path 75Gi |
| **HPA 동작** | **정상** | WAS 2-10, WEB 2-5 자동 스케일링 |
| **Alert 규칙** | **8개** | PodCrashLooping, HighMemoryUsage 등 |
| **Dashboard** | **4개** | Cluster, Node, Storage, Application |
| **Uptime** | **99%+** | 단 1회 재부팅 (커널 업데이트) |
| **WAS 버전** | **v16** | JVM 튜닝, viewCount, HA 설정 적용 |

---

## 🎯 실제 성과 (58일 운영 결과)

### 정량적 성과

| 지표 | 목표 | **실제 결과** | 달성률 |
|------|------|-------------|--------|
| **배포 시간** | 1-2분 | **35초** ✅ | **200%** (목표 대비 3배 빠름) |
| **자동화** | GitOps | **GitHub Actions + ArgoCD** ✅ | **100%** (완전 자동화) |
| **환경** | Kubernetes | **Bare-metal K8s (58일 운영)** ✅ | **100%** |
| **제어** | 완전 제어 | **GitOps SelfHeal + Rollback** ✅ | **100%** |
| **비용** | 무료 | **$0/월** ✅ | **100%** |
| **가용성** | 95%+ | **99%+ (1회 재부팅)** ✅ | **100%** |

### 정성적 성과

1. ✅ **실전 경험**: 매일 사용하는 블로그로 58일 운영 (샘플 앱 아님)
2. ✅ **장애 대응**: 실제 장애 대응 경험 (Istio mTLS + MySQL JDBC 충돌 해결)
3. ✅ **GitOps 완성**: ArgoCD Auto-Sync + SelfHeal + Prune 3대 원칙 체득
4. ✅ **베어메탈 운영**: kubeadm 클러스터 직접 구축 및 58일 관리
5. ✅ **모니터링 구축**: PLG Stack으로 58일간 메트릭/로그 수집 및 분석
6. ✅ **스토리지 최적화**: Longhorn/Local-path 비교 분석, 30Gi 절약
7. ✅ **Canary 배포**: Argo Rollouts으로 무중단 배포 경험
8. ✅ **자동 스케일링**: HPA로 트래픽 대응 자동화

---

## 🔮 다음 단계: Phase 4 운영 고도화

Local K8s Blog 완성 후 운영 개선 예정 (2026.02~):

### 1. Monitoring 강화 🔍
- **Prometheus Alert 실전 활용**: Slack 연동 (현재: 8개 규칙, 미연동)
- **Distributed Tracing**: Jaeger로 요청 추적 (WEB → WAS → MySQL)
- **SLO/SLI 설정**: 가용성 99.9%, 응답 시간 <200ms 목표

### 2. Security 강화 🔐
- **Network Policy**: WEB ↔ WAS만 허용 (현재: 전체 허용)
- **Pod Security Standards**: Restricted 모드 적용
- **External Secrets Operator**: Git에 Secret 저장 금지

### 3. Cost 최적화 💰
- **리소스 Request 튜닝**: 58일 메트릭 기반 최적화
- **Longhorn 복제 수 조정**: 3 → 2 (30% 스토리지 절약)
- **이미지 최적화**: Alpine 기반 경량화

### 4. Observability 개선 📊
- **Custom Metrics**: 게시판 조회수, 댓글 수 등 비즈니스 메트릭
- **Log 분석 자동화**: Loki Query로 에러 패턴 분석

### 5. (장기) MSA 전환 준비 🚧
**조건**: 트래픽 증가 + 기능 복잡도 증가 시 (2026.06~)
- Istio Service Mesh 도입
- Kafka Event-driven Architecture
- Auth Service 분리

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

- **[GitOps 구현 문서](../../docs/CICD/GITOPS-IMPLEMENTATION.md)** - GitHub Actions + ArgoCD 구성
- **[스토리지 분석](../../docs/storage/STORAGE-ANALYSIS.md)** - Longhorn + Nextcloud 최적화
- **[스토리지 현황](../../docs/storage/README.md)** - PVC 5개, 90Gi 운영
- **[k8s-manifests repo](https://github.com/wlals2/k8s-manifests)** - ArgoCD GitOps 저장소

---

## 📝 업데이트 로그

- **2025-11-27**: 프로젝트 시작, Bare-metal K8s 클러스터 구축
- **2025-12-XX**: Hugo 블로그 + Spring Boot WAS 배포 완료
- **2025-12-XX**: GitHub Actions CI/CD + ArgoCD GitOps 완성
- **2026-01-XX**: Argo Rollouts Canary 배포 + HPA 적용
- **2026-01-15**: PLG Stack 모니터링 완성 (4 Dashboard, 8 Alert)
- **2026-01-20**: Nextcloud 삭제 (30Gi 절약), 스토리지 최적화 완료
- **2026-01-20**: 프로젝트 페이지 업데이트 (58일 운영 성과 반영)
- **2026-01-22**: WAS v1.4.0 기능 추가 (viewCount 조회수, 원자적 UPDATE)
- **2026-01-22**: JVM 튜닝 적용 (G1GC, Heap 256-512MB)
- **2026-01-22**: HA 설정 완료 (topologySpreadConstraints, dynamicStableScale)
- **2026-01-22**: Istio Service Mesh 구축 (mTLS, VirtualService, AuthorizationPolicy)
- **2026-01-22**: Cilium eBPF CNI 구축 (kube-proxy 대체, Hubble Observability)
- **2026-01-23**: Falco Runtime Security 구축 (eBPF IDS, Loki 연동)
- **2026-01-23**: MySQL S3 백업 CronJob 구축 (7일 Lifecycle)
- **2026-01-23**: 트러블슈팅 글 10개 작성 및 링크 추가

---

**작성일**: 2026-01-23 (최종 업데이트)
**프로젝트 상태**: ✅ **Production 운영 중** (58일, 2025.11.27 시작)
**난이도**: ⭐⭐⭐⭐⭐ (Expert - Service Mesh + eBPF Security + GitOps)
**실제 소요 시간**: 58일 (지속적 개선)
**다음 단계**: Prometheus Alert Slack 연동, MySQL HA
