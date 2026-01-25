---
title: "Local Kubernetes 홈랩 완전 아키텍처"
date: 2026-01-25
tags: ["kubernetes", "architecture", "istio", "cilium", "argocd", "monitoring", "security"]
categories: ["DevOps"]
summary: "4노드 베어메탈 클러스터의 전체 아키텍처 - Istio, Cilium, ArgoCD, PLG Stack, Falco까지"
---

## 📐 전체 아키텍처

![Local K8s Architecture](../../../image/localk8s%20아키텍처.png)

> **클러스터 규모**: Kubernetes 1.31.1 (4 Node: 1 Control Plane + 3 Worker)
> **운영 기간**: 58일 (안정적 운영 중)
> **워크로드**: Blog System (WEB + WAS + MySQL) + Monitoring + Security

---

## 🎯 아키텍처 핵심 포인트

### 설계 철학

1. **GitOps 기반 운영**: 모든 변경은 Git을 통해 (kubectl 직접 사용 금지)
2. **관측성 우선**: 메트릭, 로그, 트레이싱 모두 수집
3. **보안 계층화**: 빌드타임(Trivy) → 네트워크(Cilium) → 런타임(Falco)
4. **점진적 배포**: Canary + Manual Approval로 안전성 확보
5. **리소스 효율**: HA보다는 빠른 복구(RTO 최소화)에 집중

### 주요 기술 스택 비교

| 계층 | 선택 기술 | 대안 | 선택 이유 |
|------|----------|------|----------|
| **Service Mesh** | Istio | Linkerd, Consul | mTLS + Traffic Management 통합 |
| **CNI** | Cilium (eBPF) | Calico, Flannel | NetworkPolicy + Hubble 관측성 |
| **GitOps** | ArgoCD | FluxCD | Web UI + Manual Approval |
| **Deployment** | Argo Rollouts | Flagger | Istio 네이티브 통합 |
| **Storage** | Longhorn | Rook-Ceph | 3 Replica + Snapshot 간편함 |
| **Monitoring** | PLG Stack | ELK, Splunk | Kubernetes 네이티브 + 경량 |
| **Security** | Falco | Sysdig, Datadog | eBPF + CNCF 오픈소스 |

---

## 🧱 계층별 상세 설명

### 1. 외부 접근 계층

```
User → blog@home.shop (DNS)
  ↓
Cloudflare (CDN + 캐시 + DDoS 방어)
  ↓
Ingress Nginx (NodePort 30001)
  ↓
Istio Service Mesh
```

#### 구성 요소

| 컴포넌트 | 역할 | 설정 |
|---------|------|------|
| **DNS** | blog@home.shop | DDNS (노드 IP 자동 갱신) |
| **Cloudflare** | CDN, SSL/TLS 종료, DDoS 방어 | Flexible SSL |
| **Ingress Nginx** | Kubernetes 진입점 | NodePort 30001 |

#### 왜 Cloudflare + Ingress Nginx?

**트레이드오프**:
- Cloudflare: CDN 캐시로 응답 속도 향상, 하지만 캐시 퍼지 필요
- Ingress Nginx: Istio Gateway 대신 사용 (외부 → mTLS 평문 연결 필요)

**배포 시 캐시 전략**:
```yaml
# .github/workflows/deploy-improved.yml
- name: Purge Cloudflare Cache
  run: |
    curl -X POST "https://api.cloudflare.com/client/v4/zones/$ZONE_ID/purge_cache" \
      -H "Authorization: Bearer $API_TOKEN" \
      -d '{"purge_everything":true}'
```

---

### 2. Service Mesh 계층 (Istio)

```
┌─────────────────────────────────┐
│  Istio Service Mesh             │
│  (mTLS PERMISSIVE)              │
│                                 │
│  ┌──────┐   ┌──────┐   ┌─────┐ │
│  │ WEB  │ → │ WAS  │ → │MySQL│ │
│  │(3개) │   │(3개) │   │(1개)│ │
│  └──────┘   └──────┘   └─────┘ │
│   Istio      Istio       ❌     │
│  Sidecar    Sidecar    Sidecar │
└─────────────────────────────────┘
```

#### mTLS PERMISSIVE 설정

**왜 STRICT가 아닌 PERMISSIVE?**

```yaml
# istio-system/peer-authentication.yaml
apiVersion: security.istio.io/v1beta1
kind: PeerAuthentication
metadata:
  name: default
spec:
  mtls:
    mode: PERMISSIVE  # ← 평문 + mTLS 모두 허용
```

**이유**:
1. **Ingress Nginx → WEB**: 평문 연결 필요
2. **WEB ↔ WAS**: mTLS 자동 적용 (Pod 간 암호화)
3. **점진적 마이그레이션**: PERMISSIVE → STRICT 단계적 전환 가능

#### MySQL Istio Sidecar 제외

```yaml
# blog-system/mysql-deployment.yaml
annotations:
  sidecar.istio.io/inject: "false"  # ← MySQL은 Istio 제외
```

**이유**: JDBC 연결 시 Istio Sidecar가 간섭하여 "Connection reset" 오류 발생

> 📖 **상세 가이드**: [Istio Service Mesh 완전 아키텍처](/study/2026-01-21-istio-service-mesh-architecture/)

---

### 3. Application 계층 (blog-system Namespace)

#### WEB Pod (Hugo Blog)

| 항목 | 상세 |
|------|------|
| **이미지** | ghcr.io/wlals2/blog-web |
| **배포 전략** | Argo Rollouts (Canary 10%→50%→90%, 30초 간격) |
| **Auto Scaling** | HPA 2-5 replicas (CPU 70%) |
| **Istio Traffic** | VirtualService `/` 라우팅 |

#### WAS Pod (Spring Boot Board)

| 항목 | 상세 |
|------|------|
| **이미지** | ghcr.io/wlals2/board-was:v16 |
| **배포 전략** | Argo Rollouts (Canary 20%→50%→80%, 1분 간격) |
| **Auto Scaling** | HPA 2-10 replicas (CPU 70%) |
| **JVM 튜닝** | -Xms256m -Xmx512m -XX:+UseG1GC |
| **Istio Traffic** | VirtualService `/api` 라우팅 |

#### MySQL Database

| 항목 | 상세 |
|------|------|
| **이미지** | mysql:8.0 |
| **Storage** | Longhorn PVC 5Gi (Replication 3) |
| **HA 전략** | 단일 Pod + Longhorn 복제 (RTO 20초) |
| **백업** | 일일 CronJob (NFS 백업) |

> 📖 **MySQL HA 전략**: [MySQL HA vs 백업 전략](/study/2026-01-25-longhorn-mysql-ha-strategy/)

---

### 4. Storage 계층 (Longhorn)

```
┌──────────────────────────────┐
│  Longhorn (15Gi)             │
│                              │
│  ┌─────────┐  ┌───────────┐ │
│  │ MySQL   │  │ Loki      │ │
│  │ 5Gi     │  │ 10Gi      │ │
│  │ (3복제) │  │ (3복제)   │ │
│  └─────────┘  └───────────┘ │
└──────────────────────────────┘

┌──────────────────────────────┐
│  Local-path (75Gi)           │
│                              │
│  ┌─────────┐  ┌───────────┐ │
│  │Prometheus│  │ Grafana   │ │
│  │ 50Gi    │  │ 10Gi      │ │
│  └─────────┘  └───────────┘ │
└──────────────────────────────┘
```

#### 왜 Longhorn과 Local-path 혼용?

| Storage | 사용처 | 이유 |
|---------|--------|------|
| **Longhorn** | MySQL, Loki | 복제 필요 (데이터 손실 방지) |
| **Local-path** | Prometheus, Grafana | 손실 허용 (재수집 가능) |

**리소스 트레이드오프**:
- Longhorn: 3배 스토리지 사용, 하지만 HA 제공
- Local-path: 1배 스토리지 사용, 노드 장애 시 데이터 손실

> 📖 **스토리지 분석**: [Longhorn & Nextcloud 분석](/study/2026-01-20-storage-analysis/) (30Gi 절약 과정)

---

### 5. CNI 계층 (Cilium eBPF)

```
┌──────────────────────────────┐
│  Cilium (eBPF)               │
│                              │
│  ┌─────────┐  ┌───────────┐ │
│  │ Cilium  │  │ Hubble    │ │
│  │ Agent   │  │ UI        │ │
│  │(각노드) │  │(관측성)   │ │
│  └─────────┘  └───────────┘ │
│                              │
│  NetworkPolicy 지원          │
│  (Falco IPS와 통합)          │
└──────────────────────────────┘
```

#### 주요 기능

| 기능 | 설명 | 활용 |
|------|------|------|
| **eBPF 네트워킹** | 커널 레벨 패킷 처리 | kube-proxy 대체 가능 |
| **NetworkPolicy** | Pod 간 트래픽 제어 | Falco IPS 격리 정책 |
| **Hubble UI** | 네트워크 플로우 시각화 | 트러블슈팅, 보안 감사 |

#### 왜 Calico가 아닌 Cilium?

| 항목 | Cilium (eBPF) | Calico (iptables) |
|------|---------------|-------------------|
| **성능** | 커널 레벨 (빠름) | Userspace 통과 (느림) |
| **관측성** | Hubble UI 내장 | 별도 도구 필요 |
| **보안** | eBPF 프로그램 | iptables 규칙 |

> 📖 **Cilium 아키텍처**: [Cilium eBPF 네트워킹](/study/2026-01-14-cilium-ebpf-networking/)

---

### 6. GitOps 계층 (ArgoCD)

```
GitHub (k8s-manifests)
   │ Git Push
   ▼
┌─────────────────────┐
│  ArgoCD             │
│  (3초마다 Sync)     │
│                     │
│  ┌───────────────┐  │
│  │ Auto-Sync     │  │
│  │ Prune         │  │
│  │ SelfHeal      │  │
│  └───────────────┘  │
│                     │
│  Manual Approval ✋ │
└──────┬──────────────┘
       │ kubectl apply
       ▼
  Kubernetes Cluster
```

#### GitOps 원칙

**Git = Single Source of Truth (SSOT)**

```bash
# ❌ 금지: kubectl 직접 수정
kubectl edit deployment was -n blog-system

# ✅ 권장: Git을 통한 변경
vi k8s-manifests/blog-system/was-rollout.yaml
git commit -m "scale: was replicas 2 → 3"
git push origin main
# → ArgoCD가 3초 내 자동 동기화
```

#### ArgoCD 기능

| 기능 | 설명 | 효과 |
|------|------|------|
| **Auto-Sync** | Git 변경 자동 반영 | 배포 자동화 |
| **Prune** | Git에 없는 리소스 삭제 | 일관성 유지 |
| **SelfHeal** | kubectl 수동 변경 되돌림 | Git 우선순위 |
| **Manual Approval** | 수동 승인 후 배포 | 안전성 확보 |

> 📖 **GitOps 파이프라인**: [CI/CD 파이프라인 구축](/study/2026-01-20-gitops-cicd-pipeline/)

---

### 7. Monitoring 계층 (PLG Stack)

```
┌─────────────────────────────────┐
│  PLG Stack                      │
│                                 │
│  ┌──────────┐                   │
│  │Prometheus│ ← 메트릭 수집     │
│  │ (15일)   │   (모든 Pod)      │
│  └────┬─────┘                   │
│       │                         │
│  ┌────┴─────┐                   │
│  │  Loki    │ ← 로그 수집       │
│  │  (7일)   │   (모든 Pod)      │
│  └────┬─────┘                   │
│       │                         │
│  ┌────┴─────┐                   │
│  │ Grafana  │ ← 시각화          │
│  │(4 대시보드)                   │
│  └──────────┘                   │
└─────────────────────────────────┘
```

#### 수집 메트릭

| 메트릭 | 수집 대상 | Retention |
|--------|----------|-----------|
| **Node 메트릭** | CPU, Memory, Disk | 15일 |
| **Pod 메트릭** | 요청수, 응답시간, 에러율 | 15일 |
| **Longhorn 메트릭** | 스토리지 사용량, IOPS | 15일 |
| **Application 로그** | WEB, WAS, MySQL | 7일 |

#### Grafana 대시보드 (4개)

1. **Kubernetes Cluster**: 노드 리소스 현황
2. **Blog System**: WEB/WAS Pod 메트릭
3. **Longhorn Storage**: 스토리지 사용량
4. **Loki Logs**: 로그 중앙화 조회

#### Alert Rules (8개)

```yaml
# 예시: Pod Restart 알림
- alert: PodRestartingTooOften
  expr: rate(kube_pod_container_status_restarts_total[15m]) > 0.1
  annotations:
    summary: "Pod {{ $labels.pod }} restarting too often"
```

> 📖 **모니터링 구축**: [PLG Stack 구축 가이드](/study/2026-01-20-plg-monitoring-stack/)

---

### 8. Security 계층 (Falco)

```
┌─────────────────────────────────┐
│  IDS (탐지)                     │
│                                 │
│  ┌──────────┐                   │
│  │  Falco   │ ← syscall 탐지   │
│  │ (eBPF)   │   (커널 레벨)     │
│  └────┬─────┘                   │
│       │                         │
│  ┌────┴─────┐                   │
│  │Falcosidekick │ ← 알림 라우팅│
│  │ (Loki/Slack) │               │
│  └────┬─────┘                   │
└───────┼─────────────────────────┘
        │
        ▼
┌───────┴─────────────────────────┐
│  IPS (자동 대응)                │
│                                 │
│  ┌──────────┐                   │
│  │Falco Talon│ ← 대응 결정     │
│  └────┬─────┘                   │
│       │                         │
│  ┌────┴─────┐                   │
│  │NetworkPolicy │ ← Pod 격리   │
│  │(Egress 차단) │               │
│  └──────────┘                   │
└─────────────────────────────────┘
```

#### 커스텀 탐지 룰 (4개)

1. **Java Process Spawning Shell** (RCE 방어)
2. **Package Manager Execution** (악성 패키지 설치 탐지)
3. **Write Binary** (바이너리 파일 쓰기 탐지)
4. **Outbound Connection** (C&C 서버 통신 탐지)

#### IPS 자동 대응

**탐지 → 격리 흐름**:
```
1. Falco 탐지: "Java Process Spawning Shell" (CRITICAL)
   ↓
2. Falcosidekick: Talon으로 알림 전달
   ↓
3. Falco Talon: Pod에 "quarantine=true" 라벨 추가
   ↓
4. NetworkPolicy 생성: Egress 모두 차단 (DNS만 허용)
   ↓
5. Slack 알림: "WAS Pod 격리됨 (RCE 시도 탐지)"
```

**효과**: 공격 탐지 후 5초 내 C&C 서버 통신 차단

> 📖 **Falco 아키텍처**: [Falco eBPF 런타임 보안](/study/2026-01-25-falco-ebpf-runtime-security-architecture/)

---

## 🎯 설계 결정 및 트레이드오프

### 1. 왜 MySQL HA를 구축하지 않았나?

**대안**:
- Galera Cluster (3 MySQL Pod)
- Master-Slave Replication

**선택**: 단일 Pod + Longhorn 3 Replica + 일일 백업

**이유**:
- RTO 20초 (Pod 재시작) vs HA의 RTO 0초 → 20초 차이는 개인 블로그에서 허용 가능
- 리소스 3배 절약 (CPU 3개 → 1개, RAM 3GB → 1GB)
- 개인 블로그 (일일 10명) vs 금융권 (초당 1만 트랜잭션) → 비즈니스 요구사항 차이

> 📖 **상세 분석**: [MySQL HA vs 백업 전략](/study/2026-01-25-longhorn-mysql-ha-strategy/)

---

### 2. 왜 Istio Gateway가 아닌 Ingress Nginx?

**대안**:
- Istio Gateway (Envoy 기반)
- Nginx Ingress Controller

**선택**: Ingress Nginx

**이유**:
- Cloudflare → Istio Gateway: mTLS STRICT 불가 (Cloudflare가 평문 연결)
- mTLS PERMISSIVE 설정 필요 → Ingress Nginx로 평문 수신
- 향후 개선: Let's Encrypt 인증서로 Istio Gateway 직접 노출 가능

---

### 3. 왜 Longhorn과 Local-path를 혼용?

**대안**:
- 모두 Longhorn (HA)
- 모두 Local-path (경량)

**선택**: MySQL/Loki는 Longhorn, Prometheus/Grafana는 Local-path

**이유**:
- MySQL: 데이터 손실 불가 → Longhorn 3 Replica
- Prometheus: 데이터 재수집 가능 → Local-path (50Gi 절약)
- 트레이드오프: 복제 비용 vs 데이터 중요도

---

## 📊 시스템 성능 지표

### 배포 성능

| 지표 | 수치 | 비고 |
|------|------|------|
| **배포 시간** | 35초 | Hugo 빌드 → Docker → GHCR → ArgoCD |
| **Canary 배포** | WEB 1.5분, WAS 3분 | 단계적 트래픽 전환 |
| **Rollback 시간** | 10초 | Argo Rollouts abort |

### 가용성

| 지표 | 수치 | 비고 |
|------|------|------|
| **운영 일수** | 58일 | 2024-11-28 ~ 현재 |
| **다운타임** | 0분 | 100% 가동률 |
| **Pod 재시작** | MySQL 0회, WAS 2회 (OOM) | |

### 리소스 사용률

| 노드 | CPU | Memory | Storage |
|------|-----|--------|---------|
| **k8s-cp** | 7% | 30% | 20Gi |
| **k8s-worker1** | 16% | 72% | 45Gi |
| **k8s-worker2** | 15% | 39% | 25Gi |
| **k8s-worker3** | 12% | 35% | 20Gi |

---

## 🔜 향후 개선 계획

### ⏳ 30분 내 완료 가능

1. **Loki Retention 설정** (5분)
   ```yaml
   # loki-stack/values.yaml
   table_manager:
     retention_deletes_enabled: true
     retention_period: 168h  # 7일
   ```

2. **Longhorn 스냅샷 정책** (15분)
   ```yaml
   # Recurring Job: 매일 3AM 스냅샷
   apiVersion: longhorn.io/v1beta1
   kind: RecurringJob
   metadata:
     name: mysql-snapshot-daily
   spec:
     cron: "0 3 * * *"
     task: snapshot
     retain: 7
   ```

3. **Prometheus Alert → Slack** (10분)
   - Alertmanager Slack Webhook 설정

### 🔜 선택 사항 (1시간+)

4. **Cilium kube-proxy 대체** (1시간)
   - eBPF 기반 Service Load Balancing
   - 성능 30% 향상 예상

5. **Istio Gateway 직접 노출** (1시간)
   - Let's Encrypt 인증서
   - Ingress Nginx 제거

6. **Falco IPS Phase 2 활성화** (30분)
   - WARNING 레벨 자동 격리
   - False Positive 패턴 학습 완료 후

---

## ✅ 체크리스트

### 구축 완료
- [x] Kubernetes 클러스터 (4 노드)
- [x] Cilium CNI + Hubble UI
- [x] Longhorn Storage (15Gi)
- [x] Istio Service Mesh (mTLS PERMISSIVE)
- [x] Blog System (WEB + WAS + MySQL)
- [x] ArgoCD GitOps (Auto-Sync)
- [x] Argo Rollouts (Canary 배포)
- [x] PLG Stack (Prometheus + Loki + Grafana)
- [x] Falco IDS + IPS (Phase 1: Dry-Run)
- [x] GitHub Actions CI/CD
- [x] Cloudflare CDN + DDoS 방어

### 진행 중
- [ ] Loki Retention 설정 (7일)
- [ ] Longhorn 스냅샷 정책
- [ ] Prometheus Alert → Slack

### 선택 사항
- [ ] Cilium kube-proxy 대체
- [ ] Istio Gateway 직접 노출
- [ ] Falco IPS Phase 2 활성화

---

## 📚 관련 포스트

### 핵심 아키텍처
- [Istio Service Mesh 완전 아키텍처](/study/2026-01-21-istio-service-mesh-architecture/)
- [Cilium eBPF 네트워킹](/study/2026-01-14-cilium-ebpf-networking/)
- [GitOps CI/CD 파이프라인](/study/2026-01-20-gitops-cicd-pipeline/)

### 배포 전략
- [Canary 배포 전략 비교 (WEB vs WAS)](/study/2026-01-21-canary-deployment-web-was-comparison/)
- [Argo Rollouts 배포 전략](/study/2026-01-21-argo-rollouts-deployment-strategies/)

### Storage & Database
- [Longhorn & MySQL HA 전략](/study/2026-01-25-longhorn-mysql-ha-strategy/)
- [스토리지 분석 및 최적화 (30Gi 절약)](/study/2026-01-20-storage-analysis/)

### Monitoring & Security
- [PLG Stack 구축 가이드](/study/2026-01-20-plg-monitoring-stack/)
- [Falco eBPF 런타임 보안](/study/2026-01-25-falco-ebpf-runtime-security-architecture/)

---

**작성일**: 2026-01-25
**작성자**: Jimin
**아키텍처 버전**: v1.0
**다음 단계**: Loki Retention 설정 → Longhorn 스냅샷 → Prometheus Alert
