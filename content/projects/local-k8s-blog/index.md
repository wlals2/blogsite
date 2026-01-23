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

## 프로젝트 개요

| 항목 | 내용 |
|------|------|
| **상태** | ✅ Production 운영 중 (58일) |
| **시작일** | 2025-11-27 |
| **환경** | 베어메탈 Kubernetes 3노드 클러스터 |
| **목적** | 이 블로그를 직접 K8s Pod로 배포하며 실전 경험 축적 |

---

## 전체 아키텍처

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

## 기술 스택 상세

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

### Monitoring

| 기술 | 역할 | 상세 글 |
|------|------|---------|
| **Prometheus** | 메트릭 수집 | PLG Stack |
| **Loki** | 로그 수집 | Falco Alert 연동 |
| **Grafana** | 대시보드 | 4개 Dashboard |

---

## 주요 트러블슈팅

실제 운영 중 발생한 문제들과 해결 과정:

| 문제 | 원인 | 상세 글 |
|------|------|---------|
| kubectl Connection Refused | Private 클러스터 접근 | [해결 가이드](/study/2026-01-23-kubectl-connection-refused/) |
| kubectl HTML 반환 | kubeconfig 오류 | [해결 가이드](/study/2026-01-23-kubectl-returns-html/) |
| Cloudflare 캐시 미삭제 | ZONE_ID Secret 누락 | [해결 가이드](/study/2026-01-23-cloudflare-cache-purge-fail/) |
| Docker 빌드 실패 | .gitignore 문제 | [해결 가이드](/study/2026-01-23-was-docker-build-path-error/) |
| Canary Pod Pending | TopologySpread 충돌 | [해결 가이드](/study/2026-01-23-canary-topology-spread/) |

---

## 클러스터 구성

### 노드 구성

| 노드 | 역할 | IP | 스펙 |
|------|------|-----|------|
| k8s-cp | Control Plane | 192.168.0.101 | Master, etcd |
| k8s-worker1 | Worker | 192.168.0.61 | 대부분의 워크로드 |
| k8s-worker2 | Worker | 192.168.0.62 | 분산 배치 |

### 버전 정보

```
Kubernetes: v1.32.0
CNI: Cilium (eBPF)
CSI: Longhorn v1.7.2
Service Mesh: Istio
```

---

## 운영 현황

| 지표 | 수치 |
|------|------|
| **운영 기간** | 58일 (2025.11.27~) |
| **배포 속도** | 약 35초 |
| **Uptime** | 99%+ |
| **Pod 수** | ~100개 |
| **PVC 용량** | 90Gi |

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

## 다음 단계

- [ ] Prometheus Alert → Slack 연동
- [ ] MySQL HA (Primary-Replica)
- [ ] SealedSecrets (GitOps Secret 관리)
- [ ] SLO/SLI 대시보드

---

## 관련 문서

- [전체 아키텍처 문서](/docs/05-ARCHITECTURE.md)
- [DevSecOps 아키텍처](/k8s-manifests/docs/DEVSECOPS-ARCHITECTURE.md)
- [트러블슈팅 모음](/docs/03-TROUBLESHOOTING.md)

---

**최종 업데이트**: 2026-01-23
**상태**: ✅ Production 운영 중
