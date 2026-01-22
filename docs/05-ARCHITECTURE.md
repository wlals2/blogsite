# 블로그 시스템 아키텍처

> WEB + WAS 3-TIER 구조 (Hugo 정적 블로그 + Spring Boot API)

**작성일**: 2026-01-21
**최종 수정**: 2026-01-22
**상태**: ✅ Production 운영 중 (56일)

---

## 목차

1. [클러스터 구성](#클러스터-구성)
2. [서비스별 HA/Failover 설정](#서비스별-hafailover-설정)
3. [전체 아키텍처](#전체-아키텍처)
4. [WEB (Hugo 블로그)](#web-hugo-블로그)
5. [WAS (Spring Boot API)](#was-spring-boot-api)
6. [데이터베이스 (MySQL)](#데이터베이스-mysql)
7. [네트워킹 (Istio)](#네트워킹-istio)
8. [CI/CD 파이프라인](#cicd-파이프라인)
9. [WAS 개선 가능 항목](#was-개선-가능-항목)

---

## 클러스터 구성

### 노드 구성

| 노드 | 역할 | IP | 비고 |
|------|------|-----|------|
| **k8s-cp** | Control Plane | 192.168.0.101 | Master, etcd, API Server |
| **k8s-worker1** | Worker | 192.168.0.61 | 대부분의 워크로드 |
| **k8s-worker2** | Worker | 192.168.0.62 | 분산 배치된 워크로드 |

### Kubernetes 버전

```
Kubernetes: v1.32.0
Container Runtime: containerd://1.7.24
CNI: Cilium (eBPF 기반)
CSI: Longhorn v1.7.2
```

### 스토리지 구성

| 스토리지 | 용도 | 특징 |
|----------|------|------|
| **Longhorn** | Stateful 서비스 (MySQL, Loki) | Replica 2, 자동 Failover |
| **NFS** | Monitoring (Grafana, Prometheus) | 단일 노드 (NAS) |
| **Local Path** | 임시 데이터 | 노드 로컬 디스크 |

---

## 서비스별 HA/Failover 설정

### Failover 메커니즘 이해

```
┌─────────────────────────────────────────────────────────────────┐
│                      Failover 종류                               │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  1. Kubernetes 기본 Failover (Stateless)                        │
│     ┌─────────┐     5분 후     ┌─────────┐                      │
│     │ Node    │ ───────────→  │ Taint   │ → Pod Eviction       │
│     │ 장애    │  (기본값)     │ 적용    │ → 다른 노드에 재생성  │
│     └─────────┘               └─────────┘                       │
│                                                                  │
│  2. Longhorn Failover (Stateful)                                │
│     ┌─────────┐     30초~2분   ┌─────────┐                      │
│     │ Node    │ ───────────→  │ Pod     │ → 다른 노드에서      │
│     │ 장애    │  (빠른 탐지)  │ 삭제    │ → Replica로 복구     │
│     └─────────┘               └─────────┘                       │
│                                                                  │
│  3. topologySpreadConstraints (사전 분산)                        │
│     ┌─────────────────────────────────────┐                     │
│     │  Worker1      │    Worker2          │                     │
│     │   Pod A       │     Pod B           │  ← 처음부터 분산    │
│     │               │                     │  → 다운타임 최소화  │
│     └─────────────────────────────────────┘                     │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 서비스 분류

#### 1️⃣ Stateful + Longhorn (자동 Failover 30초~2분)

| 서비스 | Namespace | PVC | Replicas | 복구 시간 |
|--------|-----------|-----|----------|----------|
| **MySQL** | blog-system | mysql-pvc (5Gi) | 1 | 30초~2분 |
| **Loki** | monitoring | storage-loki-stack-0 (10Gi) | 1 | 30초~2분 |

**Longhorn 설정:**
```yaml
# /k8s-manifests/docs/helm/longhorn/values.yaml
defaultSettings:
  defaultReplicaCount: 2
  nodeDownPodDeletionPolicy: delete-both-statefulset-and-deployment-pod
```

**왜 Longhorn Failover가 빠른가?**
- Longhorn Manager가 노드 상태를 지속적으로 모니터링
- `nodeDownPodDeletionPolicy` 설정으로 Pod 자동 삭제
- 다른 노드에 이미 Replica가 있으므로 즉시 복구 가능

#### 2️⃣ Stateful + NFS (수동 복구 필요)

| 서비스 | Namespace | PVC | 복구 방법 |
|--------|-----------|-----|----------|
| **Grafana** | monitoring | grafana-data-pvc (10Gi) | NAS 의존, K8s 기본 Failover |
| **Prometheus** | monitoring | prometheus-data-pvc (50Gi) | NAS 의존, K8s 기본 Failover |
| **Pushgateway** | monitoring | pushgateway-data-pvc (5Gi) | NAS 의존, K8s 기본 Failover |

**주의:** NFS는 단일 NAS에 의존하므로 NAS 장애 시 전체 서비스 중단

#### 3️⃣ Stateless (K8s 기본 Failover 5분)

| 서비스 | Namespace | Replicas | topologySpread | 다운타임 |
|--------|-----------|----------|----------------|----------|
| **WEB** | blog-system | 2 | ✅ DoNotSchedule | 0초 |
| **WAS** | blog-system | 2~10 (HPA) | ✅ DoNotSchedule | 0초 |
| **Istio Ingress** | istio-system | 2 | ✅ DoNotSchedule | 0초 |
| **Alertmanager** | monitoring | 1 | N/A | 5분 |
| **kube-state-metrics** | monitoring | 1 | N/A | 5분 |
| **ArgoCD** | argocd | 각 1 | N/A | 5분 |

### 현재 Pod 분포

```
┌─────────────────────────────────────────────────────────────────┐
│                        k8s-worker1                               │
├─────────────────────────────────────────────────────────────────┤
│ blog-system:  mysql, was(1), web(1)          ← 분산 배치        │
│ monitoring:   alertmanager, kube-state-metrics, loki-stack,     │
│               cadvisor, node-exporter, promtail, nginx-exporter,│
│               prometheus-adapter                                 │
│ argocd:       controller, dex, notifications, redis, server     │
│ istio:        istiod, ingress(1), egress, jaeger, kiali        │
│ longhorn:     manager, csi-plugin, ui                           │
│ cert-manager: 전체                                               │
│ metallb:      controller, speaker                                │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                        k8s-worker2                               │
├─────────────────────────────────────────────────────────────────┤
│ blog-system:  was(1), web(1)                 ← 분산 배치        │
│ monitoring:   grafana, prometheus, pushgateway, cadvisor        │
│ argocd:       repo-server                                       │
│ istio:        ingress(1)                     ← 분산 배치        │
│ longhorn:     manager, csi-plugin, instance-manager             │
│ metallb:      speaker                                           │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                        k8s-cp (Control Plane)                    │
├─────────────────────────────────────────────────────────────────┤
│ monitoring:   promtail (로그 수집만)                            │
│ metallb:      speaker                                           │
└─────────────────────────────────────────────────────────────────┘
```

### 복구 시간 요약표

| 서비스 유형 | 저장소 | 복구 방식 | 예상 다운타임 |
|-------------|--------|-----------|---------------|
| Stateful + Longhorn | Longhorn | 자동 (Longhorn Failover) | **30초~2분** |
| Stateful + NFS | NFS | 자동 (K8s 기본) | **5분** |
| Stateless (replicas=1) | 없음 | 자동 (K8s 기본) | **5분** |
| Stateless (replicas≥2, spread) | 없음 | 즉시 (미리 분산) | **0초** |
| Stateless (replicas≥2, no spread) | 없음 | 부분 (일부만 분산) | **0~5분** |

### ✅ 완료된 개선사항

1. **WEB/WAS에 topologySpreadConstraints + dynamicStableScale 적용** ✅
   - 적용: DoNotSchedule (hard constraint)로 노드별 분산 강제
   - dynamicStableScale: true로 Canary 배포 중에도 Pod 수 유지
   - 효과: 다운타임 0초, HA 보장
   - 참고: [CANARY-HA-STRATEGY.md](/home/jimin/k8s-manifests/docs/CANARY-HA-STRATEGY.md)

2. **Istio Ingress Gateway HA 적용** ✅
   - 적용: replicas 2 + topologySpreadConstraints (DoNotSchedule)
   - 효과: Gateway 장애 시에도 다운타임 0초

### 선택적 개선사항

1. **tolerationSeconds 조정 (선택)**
   - 현재: 300초 (5분) 기본값
   - 개선: 60초로 단축 가능
   - 주의: 너무 짧으면 일시적 네트워크 문제에도 eviction 발생

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
| **Deployment** | Argo Rollout (Canary) |
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

### ✅ 완료된 개선 (P0)

#### 1. Argo Rollouts로 전환 (Canary 배포) ✅

**완료일:** 2026-01-22

**적용 내용:**
```yaml
# was-rollout.yaml
strategy:
  canary:
    dynamicStableScale: true  # Pod 수 유지
    steps:
    - setWeight: 20
    - pause: {duration: 1m}
    - setWeight: 50
    - pause: {duration: 1m}
    - setWeight: 80
    - pause: {duration: 1m}
    trafficRouting:
      istio:
        virtualService:
          name: was-retry-timeout
```

**효과:**
- ✅ 점진적 배포로 위험 최소화
- ✅ API 장애 시 즉시 롤백
- ✅ WEB과 동일한 배포 전략
- ✅ dynamicStableScale + DoNotSchedule로 HA 보장

---

### P1 (선택적 개선)

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

---

## 전체 서비스 목록

### blog-system Namespace (핵심 서비스)

| 서비스 | 타입 | 역할 | 저장소 | Replicas |
|--------|------|------|--------|----------|
| **web** | Argo Rollout | Hugo 정적 블로그 | 없음 | 2 (고정) |
| **was** | Argo Rollout | Spring Boot API | 없음 | 2-10 (HPA) |
| **mysql** | Deployment | 데이터베이스 | Longhorn 5Gi | 1 |
| **mysql-exporter** | Deployment | MySQL 메트릭 수집 | 없음 | 1 |

### monitoring Namespace (모니터링 스택)

| 서비스 | 타입 | 역할 | 저장소 | Replicas |
|--------|------|------|--------|----------|
| **prometheus** | Deployment | 메트릭 수집/저장 | NFS 50Gi | 1 |
| **grafana** | Deployment | 시각화 대시보드 | NFS 10Gi | 1 |
| **loki-stack** | StatefulSet | 로그 수집/저장 | Longhorn 10Gi | 1 |
| **promtail** | DaemonSet | 로그 수집 (각 노드) | 없음 | 3 (노드당 1) |
| **alertmanager** | Deployment | 알림 관리 | 없음 | 1 |
| **pushgateway** | Deployment | 배치 작업 메트릭 | NFS 5Gi | 1 |
| **kube-state-metrics** | Deployment | K8s 리소스 메트릭 | 없음 | 1 |
| **prometheus-adapter** | Deployment | Custom Metrics API | 없음 | 1 |
| **cadvisor** | DaemonSet | 컨테이너 메트릭 | 없음 | 2 (Worker당 1) |
| **node-exporter** | DaemonSet | 노드 메트릭 | 없음 | 3 (노드당 1) |
| **nginx-exporter** | DaemonSet | Nginx 메트릭 | 없음 | 2 |

### istio-system Namespace (Service Mesh)

| 서비스 | 타입 | 역할 |
|--------|------|------|
| **istiod** | Deployment | Control Plane |
| **istio-ingressgateway** | Deployment | 외부 트래픽 인입 |
| **istio-egressgateway** | Deployment | 외부 트래픽 송출 |
| **kiali** | Deployment | Service Mesh 시각화 |
| **jaeger** | Deployment | 분산 추적 |
| **prometheus** | Deployment | Istio 메트릭 수집 |

### argocd Namespace (GitOps)

| 서비스 | 역할 |
|--------|------|
| **argocd-server** | API Server / Web UI |
| **argocd-repo-server** | Git Repository 관리 |
| **argocd-application-controller** | Application 동기화 |
| **argocd-dex-server** | SSO/OIDC 인증 |
| **argocd-redis** | 캐시 |
| **argocd-notifications-controller** | 알림 |

### longhorn-system Namespace (스토리지)

| 서비스 | 타입 | 역할 |
|--------|------|------|
| **longhorn-manager** | DaemonSet | 볼륨 관리 |
| **longhorn-driver-deployer** | Deployment | CSI 드라이버 배포 |
| **longhorn-ui** | Deployment | Web UI |
| **longhorn-csi-plugin** | DaemonSet | CSI 플러그인 |
| **csi-attacher/provisioner/resizer/snapshotter** | Deployment | CSI 컴포넌트 |
| **instance-manager** | Pod | 볼륨 인스턴스 관리 |
| **engine-image** | DaemonSet | 스토리지 엔진 |

### 기타 Namespace

| Namespace | 서비스 | 역할 |
|-----------|--------|------|
| **argo-rollouts** | argo-rollouts | Canary/Blue-Green 배포 |
| **cert-manager** | cert-manager | TLS 인증서 관리 |
| **metallb-system** | controller, speaker | LoadBalancer IP 할당 |
| **ingress-nginx** | ingress-nginx-controller | Nginx Ingress |
| **kubernetes-dashboard** | dashboard | K8s Web UI |
| **local-path-storage** | local-path-provisioner | 로컬 스토리지 |

---

## Helm Chart 관리

### 현재 Helm으로 관리하는 서비스

| Chart | Version | Namespace | Values 위치 |
|-------|---------|-----------|-------------|
| **argo-cd** | 9.3.4 | argocd | [argocd/values.yaml](../k8s-manifests/docs/helm/argocd/values.yaml) |
| **longhorn** | 1.7.2 | longhorn-system | [longhorn/values.yaml](../k8s-manifests/docs/helm/longhorn/values.yaml) |
| **loki-stack** | 2.10.2 | monitoring | [loki-stack/values.yaml](../k8s-manifests/docs/helm/loki-stack/values.yaml) |
| **kube-state-metrics** | 5.27.0 | monitoring | [kube-state-metrics/values.yaml](../k8s-manifests/docs/helm/kube-state-metrics/values.yaml) |

### Helm Values 요약

**Longhorn:**
```yaml
defaultSettings:
  defaultReplicaCount: 2  # 노드 2개이므로
  nodeDownPodDeletionPolicy: delete-both-statefulset-and-deployment-pod  # 자동 Failover
```

**Loki-Stack:**
```yaml
loki:
  persistence:
    enabled: true
    size: 10Gi
  config:
    retention_period: 0s  # 무제한 보관
promtail:
  enabled: true
```

**ArgoCD:**
```yaml
# 기본값으로 설치 (오버라이드 없음)
```

---

## 관련 문서

| 문서 | 설명 |
|------|------|
| [CICD-PIPELINE.md](CICD/CICD-PIPELINE.md) | CI/CD 파이프라인 상세 |
| [GITOPS-IMPLEMENTATION.md](CICD/GITOPS-IMPLEMENTATION.md) | GitOps 구현 가이드 |
| [02-INFRASTRUCTURE.md](02-INFRASTRUCTURE.md) | 인프라 구성 |
| [monitoring/README.md](monitoring/README.md) | 모니터링 설정 |
| [istio/COMPLETE-ISTIO-ARCHITECTURE.md](istio/COMPLETE-ISTIO-ARCHITECTURE.md) | Istio 아키텍처 |

---

**작성:** Claude Code
**최종 수정:** 2026-01-22 (HA 구현 반영)
**상태:** ✅ Production 운영 중 (WEB/WAS/Istio HA 적용 완료)
