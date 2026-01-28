# 홈랩 프로젝트 완전한 서비스 목록

> Kubernetes 기반 프로덕션 환경의 모든 서비스와 버전 정보
>
> **최종 업데이트**: 2026-01-27
> **운영 일수**: 60일 (2024-11-27~)

---

## 📋 목차

1. [인프라 개요](#인프라-개요)
2. [Kubernetes 클러스터](#kubernetes-클러스터)
3. [애플리케이션 계층](#애플리케이션-계층)
4. [Service Mesh & Networking](#service-mesh--networking)
5. [GitOps & CI/CD](#gitops--cicd)
6. [Observability (모니터링)](#observability-모니터링)
7. [Security (보안)](#security-보안)
8. [Storage & Backup](#storage--backup)
9. [Autoscaling & HA](#autoscaling--ha)
10. [서비스 엔드포인트](#서비스-엔드포인트)
11. [리소스 사용량](#리소스-사용량)

---

## 인프라 개요

### 시스템 규모

| 항목 | 수치 | 상태 |
|------|------|------|
| **Kubernetes 노드** | 3대 (1 CP + 2 Worker) | ✅ Running |
| **네임스페이스** | 6개 (blog-system, argocd, monitoring, falco, istio-system, kube-system) | ✅ Active |
| **총 Pod 수** | ~35개 | ✅ Running |
| **애플리케이션** | WEB 2개, WAS 2개, MySQL 1개 | ✅ Running |
| **운영 기간** | 60일 (2024-11-27~) | ✅ Stable |
| **배포 횟수** | v84 (WEB), v19 (WAS) | - |
| **가용성** | 99.9% 목표 | ✅ Monitoring |

### 아키텍처 개요

```
사용자 (전 세계)
  ↓ HTTPS
Cloudflare CDN (DDoS, SSL/TLS, 캐싱)
  ↓ HTTP
MetalLB LoadBalancer (192.168.1.200)
  ↓
Istio Ingress Gateway (L7 Routing)
  ├─ blog.jiminhome.shop → WEB (Nginx) → WAS (Spring Boot) → MySQL
  ├─ monitoring.jiminhome.shop → Grafana
  ├─ argocd.jiminhome.shop → ArgoCD
  └─ kiali.jiminhome.shop → Kiali

[모니터링]
Prometheus → Grafana (Metrics)
Loki → Grafana (Logs)
Tempo → Grafana (Traces)
Falco → Falcosidekick → Slack (Security Events)
```

---

## Kubernetes 클러스터

### 클러스터 정보

| 항목 | 값 | 비고 |
|------|-----|------|
| **Kubernetes 버전** | v1.31.13 | 최신 안정 버전 |
| **컨테이너 런타임** | containerd | Docker 대체 |
| **CNI (네트워크)** | Cilium 1.16.x (eBPF) | VXLAN Tunneling |
| **CRI** | CRI-O / containerd | - |
| **설치 도구** | kubeadm | - |

### 노드 구성

| 노드명 | 역할 | IP 주소 | CPU | Memory | Disk | 상태 |
|--------|------|---------|-----|--------|------|------|
| **k8s-cp** | Control Plane | 192.168.1.187 | 4 Core | 8 GB | 100 GB | ✅ Ready |
| **k8s-worker1** | Worker | 192.168.1.188 | 4 Core | 8 GB | 100 GB | ✅ Ready |
| **k8s-worker2** | Worker | 192.168.1.189 | 4 Core | 8 GB | 100 GB | ✅ Ready |

**총 리소스**:
- CPU: 12 Cores
- Memory: 24 GB
- Disk: 300 GB

### Control Plane 컴포넌트

| 컴포넌트 | 버전 | 역할 | 상태 |
|---------|------|------|------|
| **kube-apiserver** | v1.31.13 | Kubernetes API 서버 | ✅ Running |
| **etcd** | v3.5.x | 클러스터 데이터 저장소 | ✅ Running |
| **kube-scheduler** | v1.31.13 | Pod 스케줄링 | ✅ Running |
| **kube-controller-manager** | v1.31.13 | 컨트롤러 관리 | ✅ Running |
| **cloud-controller-manager** | N/A | 온프레미스 (미사용) | - |

### 시스템 컴포넌트 (kube-system)

| 컴포넌트 | 버전 | 역할 | Namespace |
|---------|------|------|-----------|
| **CoreDNS** | v1.11.x | 클러스터 DNS | kube-system |
| **kube-proxy** | v1.31.13 | 네트워크 프록시 | kube-system |
| **Cilium** | v1.16.x | CNI (eBPF) | kube-system |
| **Hubble** | v1.16.x | Cilium 네트워크 관찰성 | kube-system |

---

## 애플리케이션 계층

### blog-system Namespace

#### 1. WEB (Nginx - Frontend)

**역할**: Hugo 정적 사이트 서빙, WAS로 API 프록시

| 항목 | 값 |
|------|-----|
| **이미지** | `ghcr.io/wlals2/blog-web:v84` |
| **베이스 이미지** | nginx:alpine |
| **Replicas** | 2 (HPA: 2-5) |
| **리소스 Requests** | CPU 100m, Memory 128Mi |
| **리소스 Limits** | CPU 200m, Memory 256Mi |
| **포트** | 80 (HTTP) |
| **프로브** | Liveness: /health, Readiness: /health |
| **배포 전략** | Argo Rollouts Canary (10% → 50% → 90% → 100%) |
| **Istio Sidecar** | ✅ Enabled |
| **SecurityContext** | allowPrivilegeEscalation: false, Capabilities: NET_BIND_SERVICE, CHOWN, SETUID, SETGID |

**ConfigMap**:
- `web-nginx-config`: nginx 프록시 설정 (`/etc/nginx/conf.d/default.conf`)

**주요 기능**:
- Hugo 정적 파일 서빙 (`/var/www/blog`)
- API 요청 프록시 (`/api` → WAS:8080)
- Health check 엔드포인트 (`/health`)

#### 2. WAS (Spring Boot - Backend)

**역할**: Spring Boot REST API 서버 (게시판 백엔드)

| 항목 | 값 |
|------|-----|
| **이미지** | `ghcr.io/wlals2/board-was:v19` |
| **베이스 이미지** | eclipse-temurin:17-jre-alpine |
| **Replicas** | 2 (HPA: 2-10) |
| **리소스 Requests** | CPU 250m, Memory 512Mi |
| **리소스 Limits** | CPU 500m, Memory 1Gi |
| **포트** | 8080 (HTTP) |
| **프로브** | Startup: /actuator/health (210s timeout), Liveness: /actuator/health, Readiness: /actuator/health |
| **배포 전략** | Argo Rollouts Canary (20% → 50% → 80% → 100%) |
| **Istio Sidecar** | ✅ Enabled |
| **SecurityContext** | runAsNonRoot: true, runAsUser: 65534, allowPrivilegeEscalation: false, Capabilities: drop ALL |
| **OpenTelemetry** | ✅ Java Agent v1.32.0 (Tempo 연동) |

**ConfigMap**:
- `was-config`: Spring Boot 데이터소스 설정 (JDBC URL, Username)

**Secret**:
- `mysql-secret`: MySQL root password

**환경 변수**:
```bash
SPRING_DATASOURCE_URL=jdbc:mysql://mysql-service:3306/boarddb
SPRING_DATASOURCE_USERNAME=root
SPRING_DATASOURCE_PASSWORD=<mysql-secret>
JAVA_TOOL_OPTIONS=-javaagent:/otel/opentelemetry-javaagent.jar
OTEL_SERVICE_NAME=board-was
OTEL_EXPORTER_OTLP_ENDPOINT=http://tempo.monitoring.svc.cluster.local:4317
OTEL_TRACES_SAMPLER=always_on
```

**Init Container**:
- `otel-agent-download`: OpenTelemetry Java Agent 다운로드 (curlimages/curl:8.5.0)

#### 3. MySQL (Database)

**역할**: 게시판 데이터 저장소

| 항목 | 값 |
|------|-----|
| **이미지** | `mysql:8.0` |
| **Replicas** | 1 (단일 인스턴스) |
| **리소스 Requests** | CPU 250m, Memory 512Mi |
| **리소스 Limits** | CPU 500m, Memory 1Gi |
| **포트** | 3306 (MySQL) |
| **스토리지** | Longhorn PVC 10Gi (RWO) |
| **백업** | CronJob (매일 03:00 KST, S3 업로드) |
| **Istio Sidecar** | ❌ Disabled (JDBC mTLS 불가) |

**Secret**:
- `mysql-secret`: root password, user password

**환경 변수**:
```bash
MYSQL_ROOT_PASSWORD=<secret>
MYSQL_DATABASE=boarddb
MYSQL_USER=board
MYSQL_PASSWORD=<secret>
```

**PersistentVolumeClaim**:
- `mysql-pvc`: 10Gi, RWO, Longhorn StorageClass

#### 4. MySQL Exporter (모니터링)

**역할**: MySQL 메트릭 수집 (Prometheus)

| 항목 | 값 |
|------|-----|
| **이미지** | `prom/mysqld-exporter:v0.16.0` |
| **Replicas** | 1 |
| **리소스 Requests** | CPU 50m, Memory 64Mi |
| **리소스 Limits** | CPU 200m, Memory 256Mi |
| **포트** | 9104 (Metrics) |
| **Istio Sidecar** | ❌ Disabled |

**ConfigMap**:
- `mysql-exporter-config`: MySQL 연결 설정 (`.my.cnf`)

**수집 메트릭**:
- `info_schema.tables`
- `info_schema.query_response_time`
- `info_schema.innodb_metrics`
- `perf_schema.tableiowaits`
- `perf_schema.indexiowaits`

---

## Service Mesh & Networking

### Istio Service Mesh

**버전**: Istio 1.24.x

| 컴포넌트 | 역할 | Namespace | 리소스 |
|---------|------|-----------|--------|
| **istiod** | Control Plane (Pilot, Citadel, Galley) | istio-system | CPU 200m, Mem 512Mi |
| **istio-ingressgateway** | Ingress Gateway (L7 Routing) | istio-system | CPU 100m, Mem 128Mi |

**설정**:
- **mTLS 모드**: PERMISSIVE (평문 + mTLS 혼용)
- **Sidecar 리소스**: CPU 10m-200m, Memory 40Mi-128Mi
- **Mesh 제외**: MySQL (JDBC Wire Protocol 비호환)

#### Istio Gateway

**blog-gateway** (blog-system namespace):
- **Selector**: istio-ingressgateway
- **Hosts**: `*.jiminhome.shop`
- **Ports**: HTTP 80 (HTTPS 443 향후 추가)

#### VirtualService

**1. web-vsvc** (WEB):
- **Route 1**: 헤더 기반 Canary 테스트 (`x-canary-test: true` → canary subset)
- **Route 2**: 일반 트래픽 (Argo Rollouts가 weight 동적 조정)
- **Retry**: 3회, perTryTimeout 2s, retryOn: 5xx,reset,connect-failure,refused-stream
- **Timeout**: 10s
- **Traffic Mirroring**: stable → canary (100% shadow)

**2. was-retry-timeout** (WAS):
- **Route**: primary (Argo Rollouts가 weight 조정)
- **Retry**: 3회 (구현 예정)
- **Timeout**: 30s (구현 예정)

#### DestinationRule

**1. web-dest-rule** (WEB):
- **mTLS**: DISABLE (Gateway → web은 평문)
- **Connection Pool**: http1MaxPendingRequests 100, maxRequestsPerConnection 10
- **Load Balancer**: ROUND_ROBIN
- **Outlier Detection**: consecutive5xxErrors 5, baseEjectionTime 30s, maxEjectionPercent 50%
- **Subsets**: stable, canary (Argo Rollouts 자동 레이블)

**2. was-dest-rule** (WAS):
- **mTLS**: DISABLE (web → was 평문)
- **Connection Pool**: http1MaxPendingRequests 100
- **Load Balancer**: ROUND_ROBIN
- **Subsets**: stable, canary

#### PeerAuthentication

**default** (blog-system namespace):
- **mTLS 모드**: PERMISSIVE (평문 허용, Nginx Ingress 호환)

#### AuthorizationPolicy

**1. web-authz** (WEB):
- **Action**: ALLOW
- **Rules**: 포트 80 접근 허용 (외부 Ingress 역할)

**2. was-authz** (WAS):
- (구현 예정 - web → was만 허용)

### Cilium CNI (eBPF)

**버전**: Cilium v1.16.x

| 컴포넌트 | 역할 | 상태 |
|---------|------|------|
| **cilium-agent** | eBPF 네트워크 데이터플레인 | ✅ DaemonSet (모든 노드) |
| **cilium-operator** | Cilium 운영자 | ✅ Running |
| **hubble-relay** | 네트워크 관찰성 | ✅ Running |
| **hubble-ui** | UI (선택 사항) | ⏳ 계획 |

**주요 기능**:
- **VXLAN Tunneling**: Multi-node Pod 통신 (Overlay Network)
- **eBPF**: 커널 레벨 패킷 처리 (고성능)
- **NetworkPolicy**: L3/L4 네트워크 격리
- **Hubble**: 네트워크 플로우 관찰

#### Cilium NetworkPolicy

**1. mysql-isolation** (MySQL 보호):
- **Ingress**: was, mysql-backup, mysql-exporter → mysql:3306 허용
- **Egress**: DNS 허용

**2. was-isolation** (WAS 보호):
- **Ingress**: web → was:8080 허용 (HTTP 메서드: GET/POST/PUT/DELETE)
- **Egress**: mysql:3306, DNS, Istio xDS, Tempo:4317, GitHub HTTPS 허용

**3. web-isolation** (WEB 보호):
- **Ingress**: 모든 소스 → web:80 허용 (Ingress 역할)
- **Egress**: was:8080, DNS, Istio xDS 허용

**4. mysql-exporter-isolation**:
- **Ingress**: Prometheus → mysql-exporter:9104 허용
- **Egress**: mysql:3306, DNS 허용

**5. mysql-backup-isolation**:
- **Egress**: mysql:3306, DNS, S3 (world:443) 허용

### MetalLB LoadBalancer

**버전**: MetalLB v0.14.x

| 항목 | 값 |
|------|-----|
| **IP Pool** | 192.168.1.200-192.168.1.210 (11개) |
| **Mode** | Layer 2 (ARP) |
| **할당된 IP** | 192.168.1.200 (istio-ingressgateway) |

**설정**:
```yaml
apiVersion: metallb.io/v1beta1
kind: IPAddressPool
metadata:
  name: default-pool
  namespace: metallb-system
spec:
  addresses:
  - 192.168.1.200-192.168.1.210
```

### Service 목록

| Service | Type | ClusterIP | External IP | Ports | Namespace |
|---------|------|-----------|-------------|-------|-----------|
| **web-service** | ClusterIP | 10.x.x.x | - | 80 | blog-system |
| **was-service** | ClusterIP | 10.x.x.x | - | 8080 | blog-system |
| **mysql-service** | ClusterIP | 10.x.x.x | - | 3306 | blog-system |
| **istio-ingressgateway** | LoadBalancer | 10.x.x.x | 192.168.1.200 | 80, 443 | istio-system |

---

## GitOps & CI/CD

### ArgoCD

**버전**: ArgoCD v2.13.x

| 항목 | 값 |
|------|-----|
| **Namespace** | argocd |
| **URL** | http://argocd.jiminhome.shop |
| **Sync 정책** | Auto-Sync (3초 이내) |
| **Self-Heal** | ✅ Enabled |

**Application**:
- **Name**: `blog-system`
- **Repo**: `https://github.com/wlals2/k8s-manifests.git`
- **Path**: `blog-system/`
- **Target Revision**: `HEAD` (main 브랜치)

**ignoreDifferences**:
```yaml
# Argo Rollouts가 VirtualService weight 동적 수정
- group: networking.istio.io
  kind: VirtualService
  name: web-vsvc
  jsonPointers:
  - /spec/http/0/route
  - /spec/http/1/route

- group: networking.istio.io
  kind: VirtualService
  name: was-vsvc
  jsonPointers:
  - /spec/http/0/route
```

### Argo Rollouts

**버전**: Argo Rollouts v1.7.x

| 항목 | 값 |
|------|-----|
| **Namespace** | argo-rollouts |
| **Dashboard** | kubectl argo rollouts dashboard |

**Rollout 설정**:

**1. web-rollout**:
- **Strategy**: Canary (dynamicStableScale: true)
- **Steps**: 10% (30s) → 50% (30s) → 90% (30s) → 100%
- **Traffic Routing**: Istio VirtualService (web-vsvc)
- **DestinationRule**: web-dest-rule (stable/canary subsets)

**2. was-rollout**:
- **Strategy**: Canary (dynamicStableScale: true)
- **Steps**: 20% (1m) → 50% (1m) → 80% (1m) → 100%
- **Traffic Routing**: Istio VirtualService (was-retry-timeout)
- **DestinationRule**: was-dest-rule (stable/canary subsets)

### GitHub Actions (CI/CD)

**Runner**: Self-hosted (k8s-cp 노드)

| 항목 | 값 |
|------|-----|
| **Runner 위치** | `/home/jimin/actions-runner` |
| **워크플로우** | `.github/workflows/deploy-improved.yml` |
| **트리거** | main 브랜치 push |
| **빌드 시간** | ~35초 |

**워크플로우 단계**:
1. Hugo 빌드 (`hugo --minify`)
2. `/var/www/blog` 배포
3. Cloudflare 캐시 퍼지 (`purge_everything`)
4. 배포 검증 (HTTP 200 확인)

**WEB 이미지 빌드**:
- **Workflow**: `.github/workflows/build-web.yml`
- **Dockerfile**: `Dockerfile.web` (Multi-stage build)
- **Registry**: GHCR (ghcr.io/wlals2/blog-web)
- **최신 태그**: v84

**WAS 이미지 빌드**:
- **Workflow**: `.github/workflows/build-was.yml`
- **Dockerfile**: `blog-k8s-project/was/Dockerfile`
- **Registry**: GHCR (ghcr.io/wlals2/board-was)
- **최신 태그**: v19

---

## Observability (모니터링)

### Prometheus

**버전**: Prometheus v2.55.x

| 항목 | 값 |
|------|-----|
| **Namespace** | monitoring |
| **URL** | http://prometheus.jiminhome.shop:30090 |
| **Replicas** | 1 |
| **리소스** | CPU 500m-1000m, Memory 1Gi-2Gi |
| **Retention** | 15일 |
| **Scrape 주기** | 15초 (기본), 30초 (kube-state-metrics) |

**Targets** (11개):
- kubernetes-nodes (3개)
- kubernetes-pods (~20개)
- kubernetes-cadvisor (3개)
- nginx-exporter (1개)
- mysql-exporter (1개)
- kube-state-metrics (1개)

**메트릭 수**:
- **Node Metrics**: 130+ (CPU, Memory, Disk, Network)
- **Pod Metrics**: 50+ (리소스 사용량, 재시작 횟수)
- **Application Metrics**: 30+ (WEB, WAS, MySQL)

### Grafana

**버전**: Grafana v11.4.x

| 항목 | 값 |
|------|-----|
| **Namespace** | monitoring |
| **URL** | http://monitoring.jiminhome.shop |
| **로그인** | admin / admin |
| **Replicas** | 1 |
| **리소스** | CPU 100m-500m, Memory 256Mi-1Gi |

**Datasources**:
- Prometheus (Metrics)
- Loki (Logs)
- Tempo (Traces) 🆕

**Dashboards** (4개):
1. **System Health Overview**: 전체 시스템 상태
2. **Nginx Dashboard**: WEB 서버 모니터링 (Connections, Request Rate)
3. **MySQL Dashboard**: DB 모니터링 (Connections, Query Rate, Slow Queries)
4. **WAS Dashboard**: Spring Boot 모니터링 (JVM Heap, Threads, GC)

### Loki (로그 집계)

**버전**: Loki v3.3.x

| 항목 | 값 |
|------|-----|
| **Namespace** | monitoring |
| **Replicas** | 1 (StatefulSet) |
| **리소스** | CPU 100m-500m, Memory 256Mi-1Gi |
| **Retention** | 7일 |
| **Storage** | emptyDir (임시, 향후 PVC) |

**로그 수집 대상**:
- blog-system namespace (web, was, mysql)
- argocd namespace
- monitoring namespace

### Grafana Alloy (Agent)

**버전**: Grafana Alloy v1.5.x 🆕

| 항목 | 값 |
|------|-----|
| **Namespace** | monitoring |
| **배포 형태** | DaemonSet (모든 노드) |
| **리소스** | CPU 50m-200m, Memory 128Mi-512Mi |
| **역할** | All-in-One Agent (Promtail + node-exporter 대체) |

**기능**:
- **Logs**: Loki로 로그 전송
- **Metrics**: Prometheus로 메트릭 전송
- **Traces**: (향후 Tempo 연동)

**효과**:
- Pod 감소: Promtail (3개) + node-exporter (3개) → Alloy (3개) = **67% 감소**

### Tempo (분산 추적)

**버전**: Tempo v2.7.x 🆕

| 항목 | 값 |
|------|-----|
| **Namespace** | monitoring |
| **Replicas** | 1 |
| **리소스** | CPU 200m-1000m, Memory 512Mi-2Gi |
| **Retention** | 48시간 |
| **Protocol** | OTLP (gRPC: 4317, HTTP: 4318) |

**연동 애플리케이션**:
- WAS (Spring Boot): OpenTelemetry Java Agent v1.32.0

**Trace 수집 설정** (WAS):
```bash
OTEL_SERVICE_NAME=board-was
OTEL_EXPORTER_OTLP_ENDPOINT=http://tempo.monitoring.svc.cluster.local:4317
OTEL_TRACES_SAMPLER=always_on
```

### AlertManager

**버전**: AlertManager v0.27.x

| 항목 | 값 |
|------|-----|
| **Namespace** | monitoring |
| **Replicas** | 1 |
| **리소스** | CPU 50m-200m, Memory 128Mi-256Mi |
| **알림 채널** | Slack (설정 준비됨, 주석 처리) |

**Alert Rules** (8개):

**Critical (3개)**:
- **PodDown**: Pod 다운 5분 이상
- **HighCPUUsage**: CPU > 80% (10분)
- **MySQLDown**: MySQL 서비스 정지

**Warning (5개)**:
- **HighMemoryUsage**: Memory > 80% (5분)
- **HighRequestRate**: Request > 1000 req/s
- **SlowQueries**: Slow queries > 10 (5분)
- **HighErrorRate**: 5xx errors > 10% (5분)
- **DiskSpaceWarning**: Disk > 80%

### Exporters

| Exporter | 버전 | Namespace | 포트 | 수집 대상 |
|----------|------|-----------|------|-----------|
| **nginx-exporter** | v1.4.x | blog-system | 9113 | WEB Pod 메트릭 |
| **mysql-exporter** | v0.16.0 | blog-system | 9104 | MySQL 메트릭 |
| **kube-state-metrics** | v2.14.x | monitoring | 8080 | Pod/Deployment 상태 |
| **node-exporter** | (Alloy 통합) | monitoring | - | 노드 리소스 |
| **blackbox-exporter** | v0.25.x | monitoring | 9115 | 서비스 가용성 (향후) |

---

## Security (보안)

### Falco (Runtime Security)

**버전**: Falco v0.40.x

| 항목 | 값 |
|------|-----|
| **Namespace** | falco |
| **배포 형태** | DaemonSet (모든 노드) |
| **리소스** | CPU 100m-500m, Memory 256Mi-1Gi |
| **Rule 수** | 50+ (기본 룰셋) |

**탐지 항목**:
- 컨테이너 내부 쉘 실행
- 민감한 파일 접근 (`/etc/shadow`, `/etc/passwd`)
- 권한 상승 시도 (privilege escalation)
- 네트워크 이상 행동
- 파일 무결성 위반

**이벤트 전송**:
- Falcosidekick → Slack (알림)
- Falco Talon → IPS (자동 대응, Dry-Run)

### Falco Talon (IPS)

**버전**: Falco Talon v0.2.x

| 항목 | 값 |
|------|-----|
| **Namespace** | falco |
| **모드** | Dry-Run (로그만, 실제 차단 안 함) |
| **Actions** | Pod 격리, 네트워크 차단, 로그 수집 |

**자동 대응 시나리오** (Dry-Run):
- 쉘 실행 탐지 → Pod 격리 (NetworkPolicy 추가)
- 민감 파일 접근 → 네트워크 차단
- 권한 상승 → Pod 종료

### Istio mTLS

**모드**: PERMISSIVE (평문 + mTLS 혼용)

| 통신 경로 | mTLS 상태 | 이유 |
|----------|-----------|------|
| **Gateway → web** | ❌ Plain HTTP | Nginx Ingress 호환 |
| **web → was** | ❌ Plain HTTP | 내부 통신 (향후 mTLS) |
| **was → mysql** | ❌ Plain TCP | JDBC Wire Protocol (mTLS 불가) |
| **Mesh 내부** | ✅ mTLS 가능 | DestinationRule에서 ISTIO_MUTUAL 명시 시 |

### SecurityContext

**WEB (Nginx)**:
```yaml
securityContext:
  allowPrivilegeEscalation: false
  capabilities:
    drop: [ALL]
    add: [NET_BIND_SERVICE, CHOWN, SETUID, SETGID]
```

**WAS (Spring Boot)**:
```yaml
securityContext:
  runAsNonRoot: true
  runAsUser: 65534
  allowPrivilegeEscalation: false
  capabilities:
    drop: [ALL]
```

**MySQL**:
- (기본 설정, SecurityContext 미적용)

### Cilium NetworkPolicy

**보안 효과**:
- **Lateral Movement 차단**: MySQL은 was에서만 접근 가능
- **Zero Trust**: 최소 권한 원칙 (필요한 트래픽만 허용)
- **Audit Log**: Hubble로 모든 연결 추적

---

## Storage & Backup

### Longhorn (Persistent Storage)

**버전**: Longhorn v1.7.x

| 항목 | 값 |
|------|-----|
| **Namespace** | longhorn-system |
| **UI** | http://longhorn.jiminhome.shop (향후) |
| **Replicas** | 3 (데이터 복제본) |
| **StorageClass** | longhorn (기본) |

**사용 중인 PVC**:
- `mysql-pvc`: 10Gi, RWO (MySQL 데이터)

### MySQL Backup

**CronJob**: `mysql-backup`

| 항목 | 값 |
|------|-----|
| **Namespace** | blog-system |
| **스케줄** | 매일 03:00 KST (UTC 18:00) |
| **방식** | mysqldump → S3 업로드 |
| **보관 기간** | 7일 (S3 Lifecycle Policy) |

**백업 프로세스**:
1. **Init Container** (mysql:8.0): mysqldump 실행 → `/backup/mysql-backup-YYYYMMDD-HHMMSS.sql.gz`
2. **Main Container** (amazon/aws-cli:2.15.0): S3 업로드 → `s3://jimin-mysql-backup/`

**리소스**:
- Init Container: CPU 100m-500m, Memory 256Mi-512Mi
- Main Container: CPU 50m-200m, Memory 128Mi-256Mi

**Secret**:
- `aws-s3-credentials`: AWS Access Key, Secret Key, Region

---

## Autoscaling & HA

### HorizontalPodAutoscaler (HPA)

**버전**: autoscaling/v2

**1. web-hpa**:
| 항목 | 값 |
|------|-----|
| **Target** | Rollout/web |
| **Min Replicas** | 2 |
| **Max Replicas** | 5 |
| **Metrics** | CPU 60%, Network Receive 300KB/s |
| **Behavior** | ScaleUp 1분, ScaleDown 5분 |

**2. was-hpa**:
| 항목 | 값 |
|------|-----|
| **Target** | Rollout/was |
| **Min Replicas** | 2 |
| **Max Replicas** | 10 |
| **Metrics** | CPU 70%, Network Receive 100KB/s |
| **Behavior** | ScaleUp 1분, ScaleDown 5분 |

### VerticalPodAutoscaler (VPA)

**버전**: autoscaling.k8s.io/v1

**모드**: Off (권장 값만 제공, 자동 적용 안 함)

**1. web-vpa**:
| 항목 | 값 |
|------|-----|
| **Target** | Rollout/web |
| **Min Allowed** | CPU 50m, Memory 64Mi |
| **Max Allowed** | CPU 500m, Memory 512Mi |

**2. was-vpa**:
| 항목 | 값 |
|------|-----|
| **Target** | Rollout/was |
| **Min Allowed** | CPU 100m, Memory 256Mi |
| **Max Allowed** | CPU 1000m, Memory 2Gi |

### PodDisruptionBudget (PDB)

**1. web-pdb**:
- **minAvailable**: 1 (최소 1개 Pod 유지)

**2. was-pdb**:
- **minAvailable**: 1

**3. mysql-pdb**:
- **maxUnavailable**: 1 (단일 Pod이므로 재시작 허용)

### Topology Spread Constraints

**목적**: Pod를 여러 노드에 분산 (HA 보장)

**WEB**:
```yaml
topologySpreadConstraints:
  - maxSkew: 1
    topologyKey: kubernetes.io/hostname
    whenUnsatisfiable: ScheduleAnyway  # soft constraint
```

**WAS**:
```yaml
topologySpreadConstraints:
  - maxSkew: 1
    topologyKey: kubernetes.io/hostname
    whenUnsatisfiable: DoNotSchedule  # hard constraint (HA 보장)
```

---

## 서비스 엔드포인트

### 외부 접근 가능 (Public)

| 서비스 | URL | 프로토콜 | 상태 |
|--------|-----|----------|------|
| **Blog (WEB)** | https://blog.jiminhome.shop | HTTPS | ✅ Running |
| **ArgoCD** | http://argocd.jiminhome.shop | HTTP | ✅ Running |
| **Kiali** | http://kiali.jiminhome.shop | HTTP | ✅ Running |

### 내부 접근만 가능 (Private - 192.168.1.0/24)

| 서비스 | URL | 프로토콜 | 상태 |
|--------|-----|----------|------|
| **Grafana** | http://monitoring.jiminhome.shop | HTTP | ✅ Running |
| **Prometheus** | http://prometheus.jiminhome.shop:30090 | HTTP | ✅ Running |
| **AlertManager** | http://monitoring.jiminhome.shop:9093 | HTTP | ✅ Running |
| **Longhorn UI** | (향후) | HTTP | ⏳ 계획 |

### Cluster Internal (ClusterIP)

| 서비스 | FQDN | 포트 | 용도 |
|--------|------|------|------|
| **web-service** | web-service.blog-system.svc.cluster.local | 80 | WEB Pod |
| **was-service** | was-service.blog-system.svc.cluster.local | 8080 | WAS Pod |
| **mysql-service** | mysql-service.blog-system.svc.cluster.local | 3306 | MySQL |
| **prometheus** | prometheus.monitoring.svc.cluster.local | 9090 | 메트릭 쿼리 |
| **loki** | loki-stack.monitoring.svc.cluster.local | 3100 | 로그 쿼리 |
| **tempo** | tempo.monitoring.svc.cluster.local | 3200, 4317 | Trace 쿼리/수집 |

---

## 리소스 사용량

### Namespace별 리소스 요청 (Requests)

| Namespace | Pod 수 | CPU Requests | Memory Requests |
|-----------|--------|--------------|-----------------|
| **blog-system** | 7개 | 850m | 1.7Gi |
| **monitoring** | 10개 | 1200m | 3.5Gi |
| **argocd** | 5개 | 500m | 1Gi |
| **falco** | 4개 | 400m | 1Gi |
| **istio-system** | 2개 | 300m | 640Mi |
| **kube-system** | 12개 | 600m | 1.5Gi |
| **총합** | ~40개 | **3.85 Cores** | **9.4 GB** |

**여유 리소스**:
- CPU: 12 Cores 중 3.85 사용 → **68% 여유**
- Memory: 24 GB 중 9.4 사용 → **61% 여유**

### 애플리케이션별 리소스 (blog-system)

| 애플리케이션 | Replicas | CPU Req/Limit | Memory Req/Limit | 총 CPU Req | 총 Mem Req |
|-------------|----------|---------------|------------------|------------|------------|
| **WEB** | 2 | 100m / 200m | 128Mi / 256Mi | 200m | 256Mi |
| **WAS** | 2 | 250m / 500m | 512Mi / 1Gi | 500m | 1Gi |
| **MySQL** | 1 | 250m / 500m | 512Mi / 1Gi | 250m | 512Mi |
| **mysql-exporter** | 1 | 50m / 200m | 64Mi / 256Mi | 50m | 64Mi |
| **Istio Sidecar** (4개) | - | 10m / 200m | 40Mi / 128Mi | 40m | 160Mi |

### 모니터링 스택 리소스 (monitoring)

| 컴포넌트 | Replicas | CPU Req/Limit | Memory Req/Limit |
|---------|----------|---------------|------------------|
| **Prometheus** | 1 | 500m / 1000m | 1Gi / 2Gi |
| **Grafana** | 1 | 100m / 500m | 256Mi / 1Gi |
| **Loki** | 1 | 100m / 500m | 256Mi / 1Gi |
| **Tempo** | 1 | 200m / 1000m | 512Mi / 2Gi |
| **Grafana Alloy** | 3 | 50m / 200m | 128Mi / 512Mi |
| **AlertManager** | 1 | 50m / 200m | 128Mi / 256Mi |

---

## 주요 이벤트 & 트러블슈팅 사례

### 완료된 트러블슈팅

**1. Nginx Ingress 제거 → Istio Gateway 마이그레이션**:
- **문제**: Nginx Ingress + Istio Gateway 중복 (리소스 낭비)
- **원인**: MetalLB LoadBalancer IP 할당 실패 (loadBalancerIP vs annotation)
- **해결**: annotation 사용, Nginx Ingress 완전 제거
- **효과**: 리소스 10% 절감

**2. MySQL Mesh 제외**:
- **문제**: WAS → MySQL 연결 실패 (Connection Timeout)
- **원인**: JDBC Wire Protocol ↔ Istio Envoy (HTTP/HTTPS) 비호환
- **해결**: `sidecar.istio.io/inject: "false"` annotation 추가
- **효과**: MySQL 연결 정상화

**3. Promtail → Grafana Alloy 전환**:
- **문제**: Promtail + node-exporter 별도 관리 (Pod 6개)
- **원인**: 에이전트 분산
- **해결**: All-in-One Agent (Grafana Alloy) 도입
- **효과**: Pod 67% 감소 (6개 → 3개)

---

## 다음 단계

### ⏳ 30분 내 완료 가능

1. **Slack 알림 활성화** (20분)
   ```bash
   # AlertManager config 수정
   kubectl edit configmap alertmanager-config -n monitoring
   ```

2. **Tempo 완전 통합** (10분)
   - Grafana Alloy에서 Trace 수집 활성화

### 🔜 선택 사항

3. **NetworkPolicy 강화** (1시간)
   - Istio AuthorizationPolicy 추가 (was-authz)
   - Egress 세밀 제어

4. **HTTPS 인증서 추가** (1시간)
   - cert-manager 설치
   - Let's Encrypt 자동 갱신

5. **Longhorn UI 노출** (30분)
   - Istio Gateway 추가
   - VirtualService 생성

---

## 체크리스트

### ✅ 구축 완료

#### Kubernetes 클러스터
- [x] Kubernetes 1.31.13 설치 (kubeadm)
- [x] Cilium CNI 설치 (VXLAN Tunneling)
- [x] 3-node 클러스터 구성 (1 CP + 2 Worker)

#### 애플리케이션
- [x] WEB (Nginx) 배포 (v84)
- [x] WAS (Spring Boot) 배포 (v19)
- [x] MySQL 배포 (8.0)
- [x] Longhorn PVC 연동

#### Service Mesh & Networking
- [x] Istio Service Mesh 설치
- [x] Istio Gateway 설정
- [x] VirtualService & DestinationRule 구성
- [x] mTLS PERMISSIVE 모드
- [x] MetalLB LoadBalancer 설치
- [x] Cilium NetworkPolicy 적용

#### GitOps & CI/CD
- [x] ArgoCD 설치 및 Application 생성
- [x] Argo Rollouts 설치 (Canary 배포)
- [x] GitHub Actions Self-hosted Runner 설치
- [x] 자동 배포 파이프라인 구축
- [x] Cloudflare 캐시 자동 퍼지

#### Observability
- [x] Prometheus 설치 (130+ metrics)
- [x] Grafana 설치 (4 dashboards)
- [x] Loki 설치 (7-day logs)
- [x] Tempo 설치 (48h traces) 🆕
- [x] Grafana Alloy 설치 (All-in-One Agent) 🆕
- [x] AlertManager 설치 (8 alert rules)
- [x] Exporters 설치 (nginx, mysql, kube-state-metrics)

#### Security
- [x] Falco IDS 설치 (50+ rules)
- [x] Falco Talon IPS 설치 (Dry-Run)
- [x] Istio mTLS 설정
- [x] Cilium NetworkPolicy 적용
- [x] SecurityContext 적용 (WEB, WAS)

#### Backup & HA
- [x] MySQL Backup CronJob (S3)
- [x] HPA 설정 (WEB 2-5, WAS 2-10)
- [x] VPA 설정 (Off mode)
- [x] PodDisruptionBudget 설정

### ⏳ 진행 중

- [ ] Slack 알림 활성화
- [ ] Tempo 완전 통합 (Grafana Alloy)

### 🔜 선택 사항

- [ ] HTTPS 인증서 추가 (cert-manager)
- [ ] NetworkPolicy 강화
- [ ] Longhorn UI 노출
- [ ] Hubble UI 설치
- [ ] Kiali 고급 기능 활용

---

**작성일**: 2026-01-27
**운영 기간**: 60일 (2024-11-27~)
**시스템 상태**: ✅ 프로덕션 운영 중
**다음 단계**: Slack 알림 활성화, Tempo 완전 통합
