---
title: "Phase 4: Homeserver Kubernetes - 블로그 자가 호스팅 🏠"
date: 2026-01-16
summary: "베어메탈 Kubernetes에서 Hugo 블로그 + Spring Boot 게시판 운영, Jenkins GitOps 파이프라인 구축 (진행 중)"
tags: ["kubernetes", "bare-metal", "hugo", "spring-boot", "jenkins", "gitops", "homelab"]
categories: ["projects"]
series: ["Infrastructure Learning Journey"]
weight: 4
showtoc: true
tocopen: true
draft: false
---

## 📌 프로젝트 개요

> **상태**: 🚧 **진행 중** (2026.01.16 시작)
> **환경**: 베어메탈 Kubernetes 클러스터 (홈서버)
> **목표**: 이 블로그를 Kubernetes Pod로 배포하고 GitOps 자동화 구현

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
| **DB** | AWS RDS (Multi-AZ) | MySQL Pod (Longhorn PVC) |
| **CI/CD** | Jenkins + ArgoCD | Jenkins (GitOps) |
| **비용** | $258/월 | **무료** ✅ |
| **실사용** | 샘플 앱 | **매일 사용** ✅ |

---

## 🏗️ 상세 아키텍처

![Homeserver Kubernetes Architecture](/images/architecture/phase4-home-server.png)

**아키텍처 구성 요소:**

### Bare-metal Kubernetes Cluster

**Cluster Setup:**
- **Control Plane**: kubeadm으로 구축 (v1.31.13)
- **Container Runtime**: containerd
- **CNI**: Cilium (고성능 네트워킹, eBPF 기반)
- **Storage**: Longhorn (분산 스토리지, 3 replica)
- **운영 기간**: 51일+ (안정적 운영 중)

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

**WEB Pod (Hugo Blog):**
- **Image**: nginx:alpine + Hugo 빌드 결과물
- **Multi-stage Build**: Hugo 빌드 → nginx로 정적 파일 서빙
- **Service**: ClusterIP (Ingress를 통한 접근)
- **Health Check**: `/health` 엔드포인트

**WAS Pod (Spring Boot Board):**
- **Image**: Spring Boot 3.2 (게시판 CRUD)
- **ConfigMap**: 환경 변수 주입 (DB 연결 정보)
- **Service**: ClusterIP
- **DB 연결**: MySQL Service → MySQL Pod

**MySQL Pod:**
- **Image**: mysql:8.0
- **Persistent Volume**: Longhorn PVC (데이터 영구 보관)
- **Secret**: DB 자격증명 관리
- **Service**: ClusterIP (WAS에서만 접근)

### CI/CD Pipeline (Jenkins Docker)

**Jenkins Container:**
- **실행 방식**: Docker 컨테이너 (Kubernetes 외부)
- **Pipeline 1 (Jenkinsfile-web)**: Hugo 블로그 자동 배포
  1. Git Push 감지
  2. Hugo 빌드 (`hugo --minify`)
  3. Docker 이미지 빌드
  4. Worker 노드로 이미지 전송
  5. Kubernetes 배포 (`kubectl apply`)

- **Pipeline 2 (Jenkinsfile-was)**: Spring Boot WAS 자동 배포
  1. Git Push 감지
  2. Maven 빌드 (`mvn clean package`)
  3. Docker 이미지 빌드
  4. Worker 노드로 이미지 전송
  5. Kubernetes 배포 (`kubectl rollout restart`)

### Monitoring & Observability

- **Prometheus**: K8s 메트릭 수집 (기존 운영 중)
- **Grafana**: 대시보드 시각화 (기존 운영 중)
- **Longhorn UI**: 스토리지 모니터링

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
| **DB** | MySQL 8.0 | Longhorn PVC로 데이터 영구 저장 |
| **CI/CD** | Jenkins | Docker 컨테이너로 간단 배포 |

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

### 2. GitOps 파이프라인 직접 구현

**Phase 3 (EKS)와의 차이**:
- Phase 3: ArgoCD (자동화 프레임워크)
- **Phase 4**: Jenkins + kubectl (직접 구현)

**장점**:
- GitOps 원리 이해 (Git → 빌드 → 배포)
- Jenkinsfile as Code 경험
- 로컬 이미지 관리 (ECR 없이)

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

### 4. Ingress Path Routing

**하나의 IP로 여러 서비스 접근**:
- `/` → Hugo 블로그
- `/board` → Spring Boot 게시판
- `/api/*` → REST API

**배운 것**: L7 라우팅, rewrite 규칙, CORS 설정

---

## 📊 현재 진행 상황

### ✅ 완료된 작업

1. ✅ 구현 계획 수립
2. ✅ 기술 스택 결정 및 아키텍처 설계
3. ✅ "Why?" 문서화 (모든 기술 선택 이유 명시)

### 🚧 진행 중 (Phase별 구현)

**Phase 0**: Ingress Controller 설치 (진행 예정)
**Phase 1**: Hugo 블로그 Pod 배포 (진행 예정)
**Phase 2**: Spring Boot WAS 개발 및 배포 (진행 예정)
**Phase 3**: MySQL 배포 (Longhorn PVC) (진행 예정)
**Phase 4**: Ingress 설정 (Path Routing) (진행 예정)
**Phase 5**: Jenkins CI/CD 구축 (진행 예정)

---

## 🎯 예상 성과

### 정량적 목표

| 지표 | 현재 (Cloudflare) | 목표 (K8s) |
|------|------------------|-----------|
| **배포 시간** | 1-2분 | **1-2분** (동일) |
| **자동화** | Git Push → Cloudflare | **Git Push → Jenkins** |
| **환경** | Cloudflare 서버 | **내 Kubernetes** |
| **제어** | 제한적 | **완전한 제어** ✅ |
| **비용** | 무료 (단, 종속적) | **무료 + 독립적** ✅ |

### 정성적 목표

1. **실전 경험**: 샘플 앱이 아닌 실제 블로그 운영
2. **장애 대응**: 실제 장애 발생 시 트러블슈팅 경험
3. **GitOps 이해**: ArgoCD 없이 직접 구현하며 원리 체득
4. **베어메탈 운영**: EKS의 편리함 없이 진짜 Kubernetes 운영

---

## 🔮 향후 확장 계획: Homelab Services

Local K8s Blog 성공 후 확장 예정:
- **Nextcloud**: 파일 저장소 (클라우드 대체)
- **Vaultwarden**: 비밀번호 관리 (1Password 대체)
- **Gitea**: Self-hosted Git (GitHub 보조)
- **Grafana 통합**: 모든 홈 서비스 모니터링

**최종 목표**: 집 전체를 Kubernetes 클러스터로! 🏠

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

- **[구현 계획서 (IMPLEMENTATION-PLAN.md)](.claude/IMPLEMENTATION-PLAN.md)** - 상세 구현 가이드 (2000+ 줄)
- **[현재 상태 (context.md)](.claude/context.md)** - 프로젝트 현황
- **[Skills (blog-k8s.md)](.claude/skills/blog-k8s.md)** - 운영 명령어 모음

---

## 📝 업데이트 로그

- **2026-01-16**: 프로젝트 시작, 구현 계획 수립 완료
- **2026-01-XX**: Phase 0-1 완료 예정
- **2026-01-XX**: Phase 2-5 순차 진행 예정

---

**작성일**: 2026-01-16
**프로젝트 상태**: 🚧 **진행 중** (구현 계획 완료, Phase 0 대기)
**난이도**: ⭐⭐⭐ (Intermediate - Bare-metal K8s 운영 경험 필요)
**예상 소요 시간**: 8-10시간
