# 블로그 시스템 아키텍처

> WEB + WAS 2-Tier 구조 (Hugo 정적 블로그 + Spring Boot API)

**작성일**: 2026-01-21
**상태**: ✅ Production 운영 중 (55일)

---

## 목차

1. [전체 아키텍처](#전체-아키텍처)
2. [WEB (Hugo 블로그)](#web-hugo-블로그)
3. [WAS (Spring Boot API)](#was-spring-boot-api)
4. [데이터베이스 (MySQL)](#데이터베이스-mysql)
5. [네트워킹 (Istio)](#네트워킹-istio)
6. [CI/CD 파이프라인](#cicd-파이프라인)
7. [WAS 개선 가능 항목](#was-개선-가능-항목)

---

## 전체 아키텍처

```
┌─────────────────────────────────────────────────────────────────┐
│ 외부 트래픽                                                      │
│   └─ https://blog.jiminhome.shop/                               │
└──────────┬──────────────────────────────────────────────────────┘
           │
           ↓
┌──────────────────────────────────────────────────────────────────┐
│ Cloudflare CDN                                                   │
│   ├─ DDoS Protection                                             │
│   ├─ SSL/TLS Termination                                         │
│   └─ Cache (정적 파일)                                          │
└──────────┬───────────────────────────────────────────────────────┘
           │
           ↓
┌──────────────────────────────────────────────────────────────────┐
│ Kubernetes Cluster (blog-system namespace)                       │
│                                                                   │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │ Istio Ingress Gateway (istio-system)                       │ │
│  │   ├─ External IP: 223.130.152.214                          │ │
│  │   ├─ Port: 80 (HTTP), 443 (HTTPS)                          │ │
│  │   └─ TLS Certificate (Cloudflare Origin)                   │ │
│  └────────┬───────────────────────────────────────────────────┘ │
│           │                                                       │
│           ↓                                                       │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │ Istio VirtualService (blog-virtualservice)                 │ │
│  │   ├─ Host: blog.jiminhome.shop                             │ │
│  │   ├─ Route 1: / → web-service (WEB)                        │ │
│  │   └─ Route 2: /api/** → was-service (WAS)                  │ │
│  └────────┬────────────────────┬──────────────────────────────┘ │
│           │                    │                                 │
│           ↓                    ↓                                 │
│  ┌──────────────────┐   ┌──────────────────┐                   │
│  │ WEB              │   │ WAS              │                   │
│  │ (Argo Rollout)   │   │ (Deployment)     │                   │
│  │                  │   │                  │                   │
│  │ ┌──────────────┐ │   │ ┌──────────────┐ │                   │
│  │ │ nginx:alpine │ │   │ │ Spring Boot  │ │                   │
│  │ │ (Hugo 정적)  │ │   │ │ (board API)  │ │                   │
│  │ │              │ │   │ │              │ │                   │
│  │ │ CPU: 100m    │ │   │ │ CPU: 250m    │ │                   │
│  │ │ Mem: 128Mi   │ │   │ │ Mem: 512Mi   │ │                   │
│  │ │ Replicas: 2  │ │   │ │ Replicas: 2  │ │                   │
│  │ │ (고정)       │ │   │ │ (HPA 2-10)   │ │                   │
│  │ └──────────────┘ │   │ └───────┬──────┘ │                   │
│  │                  │   │         │        │                   │
│  │ Canary 배포 ✅  │   │         │        │                   │
│  │ (10%→50%→90%)   │   │         │        │                   │
│  │ Istio Traffic   │   │         │        │                   │
│  │ Splitting       │   │         │        │                   │
│  └──────────────────┘   │         ↓        │                   │
│                         │   ┌──────────────┐                   │
│                         │   │ MySQL        │                   │
│                         │   │              │                   │
│                         │   │ CPU: 200m    │                   │
│                         │   │ Mem: 512Mi   │                   │
│                         │   │ PVC: 10Gi    │                   │
│                         │   │ (NFS)        │                   │
│                         │   └──────────────┘                   │
│                         └──────────────────┘                   │
│                                                                 │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │ 모니터링                                                    │ │
│  │   ├─ Prometheus (메트릭 수집)                             │ │
│  │   ├─ Grafana (시각화)                                     │ │
│  │   ├─ MySQL Exporter (DB 메트릭)                           │ │
│  │   └─ Pushgateway (CI/CD 메트릭)                           │ │
│  └────────────────────────────────────────────────────────────┘ │
└───────────────────────────────────────────────────────────────────┘
```

---

## WEB (Hugo 블로그)

### 개요

**목적:** 정적 블로그 호스팅 (Hugo 기반)

| 항목 | 값 |
|------|-----|
| **Source** | ~/blogsite/ |
| **Framework** | Hugo (PaperMod 테마) |
| **Build** | `hugo --minify` |
| **Server** | nginx:alpine |
| **Image** | ghcr.io/wlals2/blog-web:v20 |
| **Image Size** | 74MB |
| **Deployment** | Argo Rollout (Canary) |
| **Replicas** | 2 (고정) |
| **HPA** | 없음 (정적 파일) |
| **Service** | ClusterIP (web-service:80) |

### Canary 배포

```yaml
# Argo Rollouts 설정
strategy:
  canary:
    steps:
    - setWeight: 10  # 10% 트래픽 → Canary
    - pause: {duration: 30s}
    - setWeight: 50  # 50% 트래픽
    - pause: {duration: 30s}
    - setWeight: 90  # 90% 트래픽
    - pause: {duration: 30s}
    # 100% → Stable 전환

    # Istio Traffic Splitting
    trafficRouting:
      istio:
        virtualService:
          name: blog-virtualservice
        destinationRule:
          name: web-dest-rule
          canarySubsetName: canary
          stableSubsetName: stable
```

**효과:**
- 점진적 배포로 위험 최소화
- 문제 발생 시 즉시 롤백 가능
- 트래픽 기반 A/B 테스트 가능

### Dockerfile

```dockerfile
# Multi-stage Build
FROM klakegg/hugo:0.111.3-alpine AS builder
WORKDIR /src
COPY . .
RUN hugo --minify

FROM nginx:alpine
COPY --from=builder /src/public /usr/share/nginx/html
COPY nginx.conf /etc/nginx/nginx.conf
EXPOSE 80
```

**최적화:**
- Multi-stage build로 이미지 크기 최소화 (74MB)
- Alpine Linux 사용
- 빌드 레이어 캐싱

### CI/CD

**트리거:** Git push to main

```yaml
# .github/workflows/deploy-web.yml
Steps:
1. Hugo Build → Docker Image
2. Push to GHCR (ghcr.io/wlals2/blog-web:v{RUN_NUMBER})
3. Build Verification (이미지 크기 10-200MB)
4. GitOps Manifest Update (~/k8s-manifests/)
5. ArgoCD Auto-Sync (3초 내)
6. Canary 배포 (10% → 50% → 90% → 100%)
7. Cloudflare Cache Purge
8. Email Notification ✅
```

**배포 시간:** 약 2분

---

## WAS (Spring Boot API)

### 개요

**목적:** 게시판 API 서버 (Spring Boot)

| 항목 | 값 |
|------|-----|
| **Source** | ~/blogsite/blog-k8s-project/was/ |
| **Framework** | Spring Boot 3.x + JDK 17 |
| **Build** | Maven (mvnw clean package) |
| **Image** | ghcr.io/wlals2/board-was:v1 |
| **Image Size** | ~150MB |
| **Deployment** | Deployment (일반) |
| **Replicas** | 2 (HPA 2-10) |
| **HPA** | CPU 70%, Memory 80% |
| **Service** | ClusterIP (was-service:8080) |

### 현재 상태

```bash
# Pod 상태
kubectl get pods -n blog-system -l app=was
# NAME                   READY   STATUS    RESTARTS   AGE
# was-6d4949cd75-7v92l   2/2     Running   0          15h
# was-6d4949cd75-kmdxg   2/2     Running   0          15h

# HPA 상태
kubectl get hpa -n blog-system
# NAME      REFERENCE        TARGETS                        MINPODS   MAXPODS   REPLICAS
# was-hpa   Deployment/was   cpu: 3%/70%, memory: 48%/80%   2         10        2
```

**현재 사용량:**
- CPU: 3% (여유 충분)
- Memory: 48% (여유 충분)
- Replicas: 2 (최소값 유지 중)

### Dockerfile

```dockerfile
# Multi-stage Build
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml .
RUN ./mvnw dependency:go-offline
COPY src ./src
RUN ./mvnw clean package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s \
  CMD wget --spider http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**최적화:**
- Multi-stage build (JDK → JRE)
- 이미지 크기: ~150MB (JDK 포함 시 ~400MB)
- Dependency 캐싱 레이어
- Actuator health check

### CI/CD

**트리거:** Workflow Dispatch (수동)

```yaml
# .github/workflows/deploy-was.yml
Steps:
1. Copy WAS source from local (~/blog-k8s-project/was/)
2. Maven Build → Docker Image
3. Push to GHCR
4. Build Verification (이미지 크기 50-500MB)
5. GitOps Manifest Update
6. ArgoCD Auto-Sync
7. Email Notification ✅
```

**Note:** WAS 코드는 .gitignore에 포함 (로컬 전용)

### 환경 변수

```yaml
# ConfigMap (was-config)
SPRING_DATASOURCE_URL: jdbc:mysql://mysql-service:3306/board

# Secret (was-secret)
SPRING_DATASOURCE_USERNAME: board_user
SPRING_DATASOURCE_PASSWORD: [encrypted]
```

---

## 데이터베이스 (MySQL)

### 개요

| 항목 | 값 |
|------|-----|
| **Version** | MySQL 8.0 |
| **Deployment** | Deployment (단일 Pod) |
| **Service** | ClusterIP (mysql-service:3306) |
| **Storage** | NFS PVC (10Gi) |
| **Resources** | CPU: 200m, Memory: 512Mi |
| **Monitoring** | MySQL Exporter → Prometheus |

### 제약사항

- **단일 Pod** (고가용성 없음)
- **백업 전략 없음** (현재)
- **Replication 없음**

---

## 네트워킹 (Istio)

### Traffic Routing

```yaml
# VirtualService (blog-virtualservice)
spec:
  hosts:
  - blog.jiminhome.shop

  http:
  # Route 1: API 트래픽 → WAS
  - match:
    - uri:
        prefix: /api/
    route:
    - destination:
        host: was-service
        port:
          number: 8080
    retries:
      attempts: 3
      perTryTimeout: 2s
    timeout: 5s

  # Route 2: 기본 트래픽 → WEB (Canary)
  - route:
    - destination:
        host: web-service
        subset: stable
      weight: 90
    - destination:
        host: web-service
        subset: canary
      weight: 10  # Canary 트래픽 비율 (Argo Rollouts가 자동 조정)
```

### DestinationRule

```yaml
# WEB DestinationRule
spec:
  host: web-service
  trafficPolicy:
    tls:
      mode: ISTIO_MUTUAL  # mTLS
    connectionPool:
      http:
        http1MaxPendingRequests: 100
      tcp:
        maxConnections: 100
    loadBalancer:
      simple: ROUND_ROBIN
    outlierDetection:
      consecutive5xxErrors: 5
      baseEjectionTime: 30s

  subsets:
  - name: stable
  - name: canary
```

---

## CI/CD 파이프라인

### GitOps 워크플로우

```
Developer
  │
  ├─ git push blogsite/main  → WEB 자동 배포
  └─ workflow_dispatch        → WAS 수동 배포
      ↓
GitHub Actions (self-hosted runner)
  ├─ Build (Hugo / Maven)
  ├─ Docker Build & Push (GHCR)
  ├─ Build Verification ✅
  └─ GitOps Manifest Update
      ↓
~/k8s-manifests/ (Git repo)
  ├─ web-rollout.yaml (업데이트)
  └─ was-deployment.yaml (업데이트)
      ↓
ArgoCD (3초 Poll)
  ├─ Git diff 감지
  ├─ kubectl apply
  └─ Auto-Sync ✅
      ↓
Kubernetes Cluster
  ├─ WEB: Canary 배포 (Argo Rollouts)
  └─ WAS: Rolling Update
      ↓
Email Notification ✅
  └─ fw4568@gmail.com
```

**특징:**
- ✅ Git = Single Source of Truth
- ✅ 자동화된 Canary 배포 (WEB)
- ✅ Build Verification
- ✅ Email 알림 (성공/실패)
- ✅ 배포 이력 Git에 기록

---

## WAS 개선 가능 항목

### P0 (우선순위 높음)

#### 1. Argo Rollouts로 전환 (Canary 배포)

**현재:**
- Deployment (Rolling Update)
- 일괄 배포 → 위험 높음

**개선 후:**
```yaml
# WAS를 Argo Rollout으로 전환
apiVersion: argoproj.io/v1alpha1
kind: Rollout
metadata:
  name: was
spec:
  strategy:
    canary:
      steps:
      - setWeight: 20
      - pause: {duration: 1m}
      - setWeight: 50
      - pause: {duration: 1m}
      - setWeight: 80
      - pause: {duration: 1m}
```

**효과:**
- 점진적 배포로 위험 최소화
- API 장애 시 즉시 롤백
- WEB과 동일한 배포 전략

**예상 작업:**
- was-deployment.yaml → was-rollout.yaml
- Istio VirtualService 수정 (/api/ routing)
- GitHub Actions 워크플로우 수정

---

#### 2. 메트릭 기반 Auto-Rollback

**Prometheus 메트릭 활용:**
```yaml
# AnalysisTemplate
spec:
  metrics:
  - name: http-error-rate
    successCondition: result < 0.05  # 에러율 5% 미만
    provider:
      prometheus:
        query: |
          sum(rate(http_requests_total{status=~"5..",app="was"}[5m]))
          /
          sum(rate(http_requests_total{app="was"}[5m]))
```

**효과:**
- 자동 롤백 (에러율 5% 초과 시)
- 수동 개입 불필요
- 안정성 향상

---

### P1 (선택적 개선)

#### 3. WAS 소스 코드 Git 관리

**현재:**
- WAS 코드: 로컬에만 존재 (.gitignore)
- GitHub Actions: 로컬 복사 사용

**개선:**
- Private Git repo 생성 (wlals2/board-was)
- CI/CD에서 git clone 사용

**효과:**
- 버전 관리 가능
- 팀 협업 가능
- 코드 이력 추적

---

#### 4. Database Migration (Flyway/Liquibase)

**현재:**
- 수동 Schema 관리

**개선:**
```yaml
# Spring Boot + Flyway
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration

# Migration 파일
V1__init_schema.sql
V2__add_comment_table.sql
```

**효과:**
- Schema 버전 관리
- 자동 마이그레이션
- 롤백 가능

---

#### 5. MySQL 고가용성

**현재:**
- 단일 Pod (SPOF)
- 백업 없음

**개선:**
- MySQL Replication (Master-Slave)
- 또는 Managed MySQL (Cloud)
- 자동 백업 설정

---

### P2 (장기 계획)

#### 6. API Gateway (Kong/Nginx)

**목적:**
- Rate Limiting
- API Key 관리
- Request Transformation

---

#### 7. Caching Layer (Redis)

**목적:**
- DB 부하 감소
- 응답 속도 향상

---

## 관련 문서

| 문서 | 설명 |
|------|------|
| [CICD-PIPELINE.md](CICD/CICD-PIPELINE.md) | CI/CD 파이프라인 상세 |
| [GITOPS-IMPLEMENTATION.md](CICD/GITOPS-IMPLEMENTATION.md) | GitOps 구현 가이드 |
| [02-INFRASTRUCTURE.md](02-INFRASTRUCTURE.md) | 인프라 구성 |
| [monitoring/README.md](monitoring/README.md) | 모니터링 설정 |

---

**작성:** Claude Code
**최종 수정:** 2026-01-21
**상태:** ✅ Production 운영 중
