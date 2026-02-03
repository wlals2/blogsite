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

> **상태**: ✅ **Production 운영 중** (58일, 2024.11.28 시작)
> **환경**: 베어메탈 Kubernetes 클러스터 (홈서버 4대)
> **목표**: 이 블로그를 Kubernetes Pod로 배포하고 GitOps 자동화 구현

---

## 왜 이 프로젝트?

"Kubernetes 전문가"라고 블로그에 소개하는데, 정작 내 블로그는 Netlify/Cloudflare에서 실행?

**Phase 3 (EKS)와의 차이점:**

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

## 🏗️ 전체 아키텍처

![Local K8s Architecture](../../../image/localk8s%20아키텍처.png)

### 핵심 아키텍처

**8개 계층으로 구성:**

| 계층 | 기술 | 역할 | 상세 가이드 |
|------|------|------|-------------|
| **1. Ingress** | Cloudflare + Nginx | CDN 캐시, SSL/TLS 종료 | [아키텍처 상세](/study/2026-01-25-local-k8s-architecture/) |
| **2. Service Mesh** | Istio (mTLS PERMISSIVE) | Pod 간 암호화 통신 | [Istio Service Mesh](/study/2026-01-22-istio-service-mesh-architecture/) |
| **3. Application** | WEB (Hugo) + WAS (Spring Boot) + MySQL | 블로그 + 게시판 + DB | [Canary 배포 비교](/study/2026-01-21-canary-deployment-web-was-comparison/) |
| **4. Storage** | Longhorn (15Gi) + Local-path (75Gi) | 복제 스토리지 + 로컬 스토리지 | [MySQL HA 전략](/study/2026-01-25-longhorn-mysql-ha-strategy/) |
| **5. CNI** | Cilium eBPF + Hubble | 네트워크 정책 + 플로우 시각화 | [Cilium eBPF 네트워킹](/study/2026-01-14-cilium-ebpf-networking/) |
| **6. GitOps** | ArgoCD (Auto-Sync) | Git Push 후 3초 내 자동 배포 | [GitOps CI/CD](/study/2026-01-20-gitops-cicd-pipeline/) |
| **7. Monitoring** | PLG Stack (Prometheus + Loki + Grafana) | 15일 메트릭, 7일 로그 | [PLG Stack 구축](#) |
| **8. Security** | Falco IDS + IPS | eBPF syscall 탐지, 자동 격리 | [Falco 런타임 보안](/study/2026-01-25-falco-ebpf-runtime-security-architecture/) |

> 📖 **[전체 아키텍처 상세 가이드](/study/2026-01-25-local-k8s-architecture/)** - 각 계층별 설계 결정, 트레이드오프, 성능 지표

---

## 주요 성과

### 운영 성과 (58일)

| 지표 | 수치 | 비고 |
|------|------|------|
| **운영 기간** | 58일 | 2024-11-28 ~ 현재 |
| **다운타임** | 0분 | 100% 가동률 |
| **배포 횟수** | 47회 | GitOps 자동화 |
| **배포 시간** | 35초 | Hugo 빌드 → 배포 완료 |
| **Canary 배포** | WEB 1.5분, WAS 3분 | 단계적 트래픽 전환 |
| **Rollback** | 10초 | Argo Rollouts abort |

### 리소스 최적화

| 항목 | Before | After | 절약 |
|------|--------|-------|------|
| **스토리지** | 120Gi (Nextcloud 포함) | 90Gi | 30Gi (25%) |
| **PVC 수** | 8개 | 5개 | 3개 정리 |
| **Pod 수** | 100개 | 98개 | 불필요 제거 |

### 클러스터 리소스 사용률

| 노드 | CPU | Memory | Storage |
|------|-----|--------|---------|
| k8s-cp (Control Plane) | 7% | 30% | 20Gi |
| k8s-worker1 | 16% | 72% | 45Gi |
| k8s-worker2 | 15% | 39% | 25Gi |
| k8s-worker3 | 12% | 35% | 20Gi |

---

## 기술 스택

### Kubernetes 기본

| 컴포넌트 | 버전/상태 | 역할 |
|---------|----------|------|
| **Kubernetes** | v1.31.13 | 베어메탈 4노드 클러스터 |
| **Container Runtime** | containerd | Pod 실행 환경 |
| **CNI** | Cilium eBPF | 네트워크 플러그인 |
| **Storage** | Longhorn (15Gi) + Local-path (75Gi) | 영구 볼륨 |

### Service Mesh & Networking

| 컴포넌트 | 역할 | 상세 가이드 |
|---------|------|-------------|
| **Istio** | Service Mesh (mTLS PERMISSIVE) | [Istio 아키텍처](/study/2026-01-22-istio-service-mesh-architecture/) |
| **Cilium** | CNI (eBPF 기반) | [Cilium eBPF](/study/2026-01-14-cilium-ebpf-networking/) |
| **Hubble** | 네트워크 플로우 시각화 | [Hubble 관측성](/study/2026-01-22-cilium-hubble-observability/) |
| **Cloudflare** | CDN + DDoS 방어 | - |

### CI/CD & GitOps

| 컴포넌트 | 역할 | 상세 가이드 |
|---------|------|-------------|
| **GitHub Actions** | CI (빌드 + 이미지 푸시) | [GitOps CI/CD](/study/2026-01-20-gitops-cicd-pipeline/) |
| **ArgoCD** | CD (GitOps 자동 배포) | [ArgoCD 트러블슈팅](/study/2026-01-23-argocd-troubleshooting/) |
| **Argo Rollouts** | Canary 배포 전략 | [Canary 배포 비교](/study/2026-01-21-canary-deployment-web-was-comparison/) |
| **GHCR** | Private Container Registry | - |

### Monitoring & Observability

| 컴포넌트 | 역할 | Retention |
|---------|------|-----------|
| **Prometheus** | 메트릭 수집 | 15일 |
| **Loki** | 로그 중앙화 | 7일 |
| **Grafana** | 시각화 (4 대시보드) | - |
| **Pushgateway** | Batch Job 메트릭 | - |

### Security (DevSecOps)

| 컴포넌트 | 역할 | 상세 가이드 |
|---------|------|-------------|
| **Trivy** | 이미지 취약점 스캔 (CI) | - |
| **Falco** | 런타임 보안 (IDS + IPS) | [Falco 런타임 보안](/study/2026-01-25-falco-ebpf-runtime-security-architecture/) |
| **Falcosidekick** | 알림 라우팅 (Loki, Slack) | [Falco 트러블슈팅](/study/2026-01-23-falco-runtime-security-troubleshooting/) |
| **Falco Talon** | 자동 대응 (NetworkPolicy 생성) | [Falco 아키텍처](/study/2026-01-25-falco-ebpf-runtime-security-architecture/) |
| **CiliumNetworkPolicy** | Pod 간 트래픽 제어 | - |

### Storage & Database

| 컴포넌트 | 용량 | 역할 | 상세 가이드 |
|---------|------|------|-------------|
| **Longhorn** | 15Gi (3 replicas) | 분산 스토리지 (MySQL, Loki) | [Longhorn & MySQL HA](/study/2026-01-25-longhorn-mysql-ha-strategy/) |
| **Local-path** | 75Gi | 로컬 스토리지 (Prometheus, Grafana) | - |
| **MySQL** | 5Gi (Longhorn PVC) | 게시판 DB | [MySQL HA 전략](/study/2026-01-25-longhorn-mysql-ha-strategy/) |
| **MySQL Backup** | 일일 CronJob (NFS 백업) | 자동 백업 | [MySQL 백업 트러블슈팅](/study/2026-01-23-mysql-backup-cronjob-troubleshooting/) |

---

## 🔧 주요 트러블슈팅

58일간 운영하면서 만난 문제들과 해결 과정을 정리했습니다.

### Kubernetes & GitOps

| 문제 | 해결 방법 | 상세 가이드 |
|------|----------|-------------|
| kubectl Connection Refused (Self-hosted Runner) | kubeconfig 권한 + 소유자 변경 | [Connection Refused](/study/2026-01-23-kubectl-connection-refused/) |
| kubectl이 HTML을 반환 | API Server 인증서 재발급 | [HTML 반환 문제](/study/2026-01-23-kubectl-returns-html/) |
| ArgoCD 동기화 실패 | 다양한 시나리오별 해결법 | [ArgoCD 트러블슈팅](/study/2026-01-23-argocd-troubleshooting/) |
| Canary Pod Pending | TopologySpread와 Canary 충돌 | [Topology Spread 충돌](/study/2026-01-23-canary-topology-spread/) |

### CI/CD & Runner

| 문제 | 해결 방법 | 상세 가이드 |
|------|----------|-------------|
| Runner가 Job을 안 가져감 | curl로 연결 테스트 + labels 확인 | [Runner Job 미실행](/study/2026-01-23-runner-not-picking-job/) |
| WAS Docker 빌드 경로 오류 | GitHub Actions context 경로 수정 | [Docker 빌드 오류](/study/2026-01-23-was-docker-build-path-error/) |
| Cloudflare 캐시 퍼지 실패 | API 토큰 권한 확인 | [캐시 퍼지 실패](/study/2026-01-23-cloudflare-cache-purge-fail/) |

### Storage & Database

| 문제 | 해결 방법 | 상세 가이드 |
|------|----------|-------------|
| Longhorn CSI CrashLoopBackOff | iscsi-initiator-utils 설치 | [Longhorn CSI 오류](/study/2026-01-23-longhorn-csi-crashloopbackoff/) |
| MySQL 백업 CronJob 실패 | Cilium + Istio 환경 DNS 설정 | [MySQL 백업 오류](/study/2026-01-23-mysql-backup-cronjob-troubleshooting/) |

### Service Mesh

| 문제 | 해결 방법 | 상세 가이드 |
|------|----------|-------------|
| Nginx Ingress → Istio Gateway 전환 | mTLS PERMISSIVE + nginx proxy | [Ingress → Gateway 전환](/study/2026-01-24-nginx-ingress-to-istio-gateway/) |

---

## 핵심 학습 포인트

### 1. 베어메탈 Kubernetes 운영 경험

- **kubeadm**으로 클러스터 직접 구축 (AWS EKS 추상화 벗어남)
- Worker 노드 추가/제거 실습
- 노드 장애 시 Pod 재스케줄링 경험

### 2. GitOps 완전 자동화

- Git Push → ArgoCD Auto-Sync (3초) → Canary 배포 → Cloudflare 캐시 퍼지
- kubectl 사용 금지 (Git = Single Source of Truth)
- SelfHeal로 수동 변경 자동 되돌림

### 3. Canary 배포 전략

- WEB (10%→50%→90%, 30초 간격): 빠른 배포
- WAS (20%→50%→80%, 1분 간격): 신중한 배포
- Istio Traffic Management로 트래픽 제어

### 4. Service Mesh 실전 적용

- Istio mTLS PERMISSIVE (평문 + mTLS 공존)
- VirtualService로 Path-based 라우팅 (`/` → WEB, `/api` → WAS)
- MySQL은 Istio Sidecar 제외 (JDBC 호환성)

### 5. eBPF 기반 네트워킹 & 보안

- Cilium CNI로 kube-proxy 대체 가능 (성능 30% 향상)
- Hubble UI로 네트워크 플로우 시각화
- Falco로 런타임 syscall 탐지 + NetworkPolicy 자동 격리

### 6. HPA 자동 스케일링

- WAS: 2-10 replicas (CPU 70%)
- WEB: 2-5 replicas (CPU 70%)
- Canary + HPA 동시 동작 (dynamicStableScale)

### 7. PLG Stack 모니터링 (58일 운영)

- Prometheus: 15일 메트릭, 8개 Alert Rules
- Loki: 7일 로그 (모든 Pod 중앙화)
- Grafana: 4개 대시보드

### 8. 리소스 최적화

- Nextcloud 제거 (30Gi 절약)
- Longhorn vs Local-path 혼용 (리소스 효율)
- JVM 튜닝 (`-Xms256m -Xmx512m -XX:+UseG1GC`)

---

## 🔮 다음 단계

### ⏳ 빨리 할 것들 (30분 내)

1. **Loki Retention 설정** (5분) - 로그 7일로 제한
2. **Longhorn 스냅샷 정책** (15분) - 매일 3AM 자동 스냅샷
3. **Prometheus Alert → Slack** (10분) - 알림 자동화

### 🔜 나중에 해볼 것들 (1시간+)

4. **Cilium kube-proxy 대체** (1시간) - 성능 30% 향상 예상
5. **Istio Gateway 직접 노출** (1시간) - Let's Encrypt 인증서
6. **Falco IPS Phase 2** (30분) - WARNING 레벨 자동 격리

---

## 왜 베어메탈 Kubernetes인가?

### Phase 3 (EKS)의 한계

- 샘플 앱 (PetClinic)만 배포 → 실사용 경험 부족
- AWS 추상화 → 내부 동작 이해 어려움
- 비용 $258/월 → 지속 운영 불가

### Homeserver K8s의 장점

- 매일 사용 (58일) → 진짜 운영 경험
- 무료 운영 → 장기 실험 가능
- 베어메탈 경험 → kubeadm, CNI, CSI 직접 구축
- 트러블슈팅 → 실전 문제 해결 능력
- 지속 개선 → GitOps로 안전하게 실험

---

## 🔗 관련 문서

### 핵심 아키텍처
- [전체 아키텍처 가이드](/study/2026-01-25-local-k8s-architecture/)
- [Istio Service Mesh](/study/2026-01-22-istio-service-mesh-architecture/)
- [Cilium eBPF 네트워킹](/study/2026-01-14-cilium-ebpf-networking/)
- [GitOps CI/CD 파이프라인](/study/2026-01-20-gitops-cicd-pipeline/)

### 배포 전략
- [Canary 배포 전략 비교 (WEB vs WAS)](/study/2026-01-21-canary-deployment-web-was-comparison/)

### Storage & Database
- [Longhorn & MySQL HA 전략](/study/2026-01-25-longhorn-mysql-ha-strategy/)

### Monitoring & Security
- [Falco eBPF 런타임 보안](/study/2026-01-25-falco-ebpf-runtime-security-architecture/)

---

## 업데이트 로그

| 날짜 | 업데이트 내용 |
|------|-------------|
| 2026-01-25 | 전체 아키텍처 가이드 작성, 프로젝트 페이지 간소화 |
| 2026-01-23 | Falco Runtime Security 구축 완료 |
| 2026-01-22 | Istio Service Mesh 전환 완료 (Nginx Ingress 제거) |
| 2026-01-20 | Nextcloud 제거 (30Gi 절약), GitOps 파이프라인 완성 |
| 2026-01-14 | Cilium + Hubble UI 구축 완료 |
| 2025-12-02 | WAS (Spring Boot Board) 추가 |
| 2025-11-28 | 프로젝트 시작 (Hugo 블로그 배포) |
