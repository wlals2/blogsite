---
title: "Local K8s Blog - Homeserver Kubernetes 운영 실전 🏠"
date: 2026-01-20
summary: "베어메탈 Kubernetes에서 Hugo 블로그 55일 운영: GitHub Actions GitOps + ArgoCD + Argo Rollouts + PLG Stack 모니터링 (완료)"
tags: ["kubernetes", "bare-metal", "hugo", "spring-boot", "github-actions", "argocd", "argo-rollouts", "gitops", "monitoring", "plg-stack", "homelab"]
categories: ["projects"]
series: ["Infrastructure Learning Journey"]
weight: 4
showtoc: true
tocopen: true
draft: false
---

## 📌 프로젝트 개요

> **상태**: ✅ **완료** (55일 운영 중, 2025.11.27 시작)
> **환경**: 베어메탈 Kubernetes 클러스터 (홈서버)
> **목표**: 이 블로그를 Kubernetes Pod로 배포하고 GitOps 자동화 구현
>
> **주요 성과**:
> - ✅ GitHub Actions CI/CD (35초 배포)
> - ✅ ArgoCD GitOps (Auto-Sync, Prune, SelfHeal)
> - ✅ Argo Rollouts Canary 배포
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
| **모니터링** | CloudWatch | **PLG Stack (55일 운영)** |
| **HPA** | 미적용 | **WAS 2-10, WEB 2-5** |
| **비용** | $258/월 | **무료** ✅ |
| **실사용** | 샘플 앱 | **매일 사용 (55일)** ✅ |

---

## 🏗️ 상세 아키텍처

![Homeserver Kubernetes Architecture](/images/architecture/phase4-home-server.png)

**아키텍처 구성 요소:**

### Bare-metal Kubernetes Cluster

**Cluster Setup:**
- **Control Plane**: kubeadm으로 구축 (v1.31.13)
- **Container Runtime**: containerd
- **CNI**: Cilium (고성능 네트워킹, eBPF 기반)
- **Storage**: Longhorn (15Gi) + Local-path (75Gi)
- **운영 기간**: **55일** (안정적 운영 중)

### Networking & Ingress Layer

**Ingress Controller:**
- **nginx Ingress Controller**: Path-based L7 라우팅
- **NodePort**: 30080 (외부 접속)
- **Cloudflare Tunnel**: `http://blog.jiminhome.shop/` → NodePort

**Ingress Rules:**
- `/` → web-service (Hugo 블로그)
- `/board` → was-service (Spring Boot 게시판)
- `/api/*` → was-service (REST API)

### Application Layer (Namespace: blog-system)

**WEB Rollout (Hugo Blog):**
- **Image**: ghcr.io/wlals2/blog-web (nginx:alpine + Hugo)
- **Multi-stage Build**: Hugo 빌드 → nginx로 정적 파일 서빙
- **Deployment**: Argo Rollouts (Canary 전략)
- **HPA**: 2-5 replicas (CPU 70% 기준)
- **Service**: ClusterIP (Ingress를 통한 접근)
- **Health Check**: `/` 엔드포인트

**WAS Rollout (Spring Boot Board):**
- **Image**: ghcr.io/wlals2/board-was:v1 (Spring Boot 3.2)
- **Deployment**: Argo Rollouts (Canary 전략)
- **HPA**: 2-10 replicas (CPU 70% 기준)
- **ConfigMap**: 환경 변수 주입 (DB 연결 정보)
- **Service**: ClusterIP
- **DB 연결**: MySQL Service → MySQL Pod

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
- **운영 기간**: **55일**

**Pushgateway (Namespace: monitoring):**
- **Batch Job**: 단기 작업 메트릭 수집
- **Storage**: Local-path PVC 5Gi

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
| **Monitoring** | PLG Stack | Prometheus + Loki + Grafana (55일) |

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

### 6. PLG Stack 모니터링 (55일 운영)

**Prometheus + Loki + Grafana**:
- **4개 Dashboard**: Cluster, Node, Storage, Application
- **8개 Alert Rules**: PodCrashLooping, HighMemoryUsage 등
- **중앙화 로그**: 모든 Pod 로그 Loki 수집
- **메트릭 보존**: Prometheus 15일, Loki 7일

**배운 것**:
- 55일간 실제 메트릭 데이터 분석
- Alert 규칙 작성 및 튜닝
- Longhorn vs Local-path 스토리지 성능 비교
- 리소스 사용 패턴 파악

### 7. Ingress Path Routing

**하나의 IP로 여러 서비스 접근**:
- `/` → Hugo 블로그
- `/board` → Spring Boot 게시판
- `/api/*` → REST API

**배운 것**: L7 라우팅, rewrite 규칙, CORS 설정

---

## 📊 운영 현황 (55일 안정 운영 중)

### ✅ 완료된 작업

1. ✅ **Bare-metal Kubernetes 클러스터 구축** (kubeadm + Cilium + Longhorn)
2. ✅ **Hugo 블로그 Pod 배포** (nginx:alpine, Multi-stage Build)
3. ✅ **Spring Boot WAS 배포** (board-was:v1, MySQL 연동)
4. ✅ **MySQL StatefulSet 배포** (Longhorn PVC 5Gi, 3 replica)
5. ✅ **nginx Ingress 설정** (Path-based Routing: `/`, `/board`, `/api`)
6. ✅ **GitHub Actions CI/CD** (Self-hosted Runner, 35초 배포)
7. ✅ **ArgoCD GitOps 완성** (Auto-Sync, Prune, SelfHeal)
8. ✅ **Argo Rollouts 배포** (Canary 전략)
9. ✅ **HPA 자동 스케일링** (WAS 2-10, WEB 2-5)
10. ✅ **PLG Stack 모니터링** (Prometheus + Loki + Grafana, 4 Dashboard, 8 Alert)
11. ✅ **스토리지 최적화** (Nextcloud 삭제, 30Gi 절약)

### 📈 운영 성과 (2025.11.27 ~ 현재)

| 지표 | 수치 | 상세 |
|------|------|------|
| **운영 기간** | **55일** | 2025.11.27 시작 (중단 없음) |
| **배포 속도** | **35초** | GitHub Actions GitOps 자동화 |
| **Pod 수** | **98개** | blog-system (8) + monitoring (15) + argocd (7) + 시스템 |
| **PVC 수** | **5개** | MySQL (5Gi) + PLG Stack (75Gi) |
| **스토리지** | **90Gi** | Longhorn 15Gi + Local-path 75Gi |
| **HPA 동작** | **정상** | WAS 2-10, WEB 2-5 자동 스케일링 |
| **Alert 규칙** | **8개** | PodCrashLooping, HighMemoryUsage 등 |
| **Dashboard** | **4개** | Cluster, Node, Storage, Application |
| **Uptime** | **99%+** | 단 1회 재부팅 (커널 업데이트) |

---

## 🎯 실제 성과 (55일 운영 결과)

### 정량적 성과

| 지표 | 목표 | **실제 결과** | 달성률 |
|------|------|-------------|--------|
| **배포 시간** | 1-2분 | **35초** ✅ | **200%** (목표 대비 3배 빠름) |
| **자동화** | GitOps | **GitHub Actions + ArgoCD** ✅ | **100%** (완전 자동화) |
| **환경** | Kubernetes | **Bare-metal K8s (55일 운영)** ✅ | **100%** |
| **제어** | 완전 제어 | **GitOps SelfHeal + Rollback** ✅ | **100%** |
| **비용** | 무료 | **$0/월** ✅ | **100%** |
| **가용성** | 95%+ | **99%+ (1회 재부팅)** ✅ | **100%** |

### 정성적 성과

1. ✅ **실전 경험**: 매일 사용하는 블로그로 55일 운영 (샘플 앱 아님)
2. ✅ **장애 대응**: 실제 장애 대응 경험 (Istio mTLS + MySQL JDBC 충돌 해결)
3. ✅ **GitOps 완성**: ArgoCD Auto-Sync + SelfHeal + Prune 3대 원칙 체득
4. ✅ **베어메탈 운영**: kubeadm 클러스터 직접 구축 및 55일 관리
5. ✅ **모니터링 구축**: PLG Stack으로 55일간 메트릭/로그 수집 및 분석
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
- **리소스 Request 튜닝**: 55일 메트릭 기반 최적화
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
- **2026-01-20**: 프로젝트 페이지 업데이트 (55일 운영 성과 반영)

---

**작성일**: 2026-01-20 (최종 업데이트)
**프로젝트 상태**: ✅ **완료** (55일 안정 운영 중, 2025.11.27 시작)
**난이도**: ⭐⭐⭐⭐ (Advanced - GitOps + Monitoring + Storage 운영 경험)
**실제 소요 시간**: 55일 (지속적 개선)
**다음 단계**: Phase 4 운영 고도화 (2026.02~)
