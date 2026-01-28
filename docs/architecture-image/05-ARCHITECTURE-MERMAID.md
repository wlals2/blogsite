# 블로그 시스템 아키텍처 (Mermaid 버전)

> WEB + WAS 3-TIER 구조 (Hugo 정적 블로그 + Spring Boot API)

**작성일**: 2026-01-24
**상태**: ✅ Production 운영 중 (58일+)

---

## 전체 아키텍처 (Mermaid)

```mermaid
graph TB
    subgraph External["외부"]
        User([사용자<br/>HTTPS])
    end

    subgraph Cloudflare["☁️ Cloudflare (CDN + Security)"]
        CF_CDN[DDoS Protection L3/4/7<br/>SSL/TLS Termination<br/>WAF<br/>Cache]
        CF_Tunnel[Cloudflare Tunnel]
        CF_CDN --> CF_Tunnel
    end

    User --> CF_CDN

    subgraph K8s["🎯 Kubernetes Cluster v1.31.13 (4 nodes)"]

        subgraph Ingress["Ingress Layer"]
            MetalLB[MetalLB LoadBalancer<br/>192.168.X.200]
            Nginx[Nginx Ingress Controller<br/>NodePort 30080]
            MetalLB --> Nginx
        end

        subgraph Istio["🔒 Istio Service Mesh (mTLS PERMISSIVE)"]
            VS[VirtualService<br/>blog-virtualservice]

            subgraph WEB_App["WEB (Argo Rollout)"]
                WEB[nginx:alpine<br/>Hugo 정적<br/>━━━━━━━━━<br/>CPU: 100m<br/>Mem: 128Mi<br/>Replicas: 2-5<br/>HPA: 60%]
                WEB_Security["🔐 SecurityContext:<br/>- runAsNonRoot<br/>- drop ALL caps<br/>- Private GHCR"]
                WEB_Canary["📊 Canary 배포<br/>10% → 50% → 90%<br/>Istio Traffic Split"]
            end

            subgraph WAS_App["WAS (Argo Rollout)"]
                WAS[Spring Boot<br/>board-was<br/>━━━━━━━━━<br/>CPU: 250m<br/>Mem: 512Mi<br/>Replicas: 2-10<br/>HPA: 70%]
                WAS_Security["🔐 SecurityContext:<br/>- runAsNonRoot<br/>- drop ALL caps<br/>- Private GHCR"]
            end

            subgraph DB["Database"]
                MySQL[(MySQL 8.0<br/>━━━━━━━━━<br/>CPU: 200m<br/>Mem: 512Mi<br/>PVC: 5Gi Longhorn)]
                MySQL_Storage["💾 Storage:<br/>- Replica: 3<br/>- S3 Backup daily 3AM<br/>- RTO: 5분<br/>- RPO: 24h"]
            end

            VS -->|"/ (정적)"| WEB
            VS -->|"/api/** (API)"| WAS
            WAS -->|"평문 TCP<br/>(Istio Mesh 제외)"| MySQL
            WEB <-.->|"mTLS 🔒"| WAS
        end

        subgraph Security["🛡️ 보안 계층 (Falco Runtime Security)"]
            Falco[Falco DaemonSet<br/>4 nodes<br/>━━━━━━━━━<br/>eBPF syscall 모니터링<br/>CVE, RCE, Reverse Shell]
            Sidekick[Falcosidekick<br/>Alert Routing Hub]
            Talon[Falco Talon IPS<br/>━━━━━━━━━<br/>Dry-Run Phase 1<br/>NetworkPolicy 격리<br/>CRITICAL → 즉시 격리]

            Falco -->|"이상 탐지 시"| Sidekick
            Sidekick -->|"1. 장기 보관"| Loki_Sec[Loki 7일]
            Sidekick -->|"2. 자동 대응"| Talon
            Talon -->|"NetworkPolicy 생성"| K8s_API[Kubernetes API<br/>Pod 격리]
        end

        subgraph Monitoring["📊 모니터링 & 로깅 (PLG Stack)"]
            Prom[Prometheus<br/>━━━━━━━━━<br/>Node Exporter<br/>MySQL Exporter<br/>Pushgateway<br/>Istio Telemetry]
            Loki[Loki<br/>━━━━━━━━━<br/>로그 수집 & 검색<br/>Retention: 7일 168h<br/>자동 삭제]
            Grafana[Grafana<br/>━━━━━━━━━<br/>통합 대시보드<br/>K8s, MySQL, Istio]

            Prom --> Grafana
            Loki --> Grafana
        end

        subgraph GitOps["⚙️ GitOps 배포 (ArgoCD)"]
            ArgoCD[ArgoCD<br/>━━━━━━━━━<br/>Auto-Sync: 3초<br/>Self-Heal<br/>Argo Rollouts Canary]
            Git[github.com/wlals2/<br/>k8s-manifests]
            Git --> ArgoCD
        end

        subgraph Storage["💾 스토리지 (Longhorn)"]
            Longhorn[Longhorn CSI<br/>━━━━━━━━━<br/>Replica: 3<br/>worker1, 2, 3<br/>Auto Failover<br/>S3 Backup daily 3AM]
        end

        subgraph Network["🌐 CNI (Cilium v1.18.4)"]
            Cilium[Cilium eBPF<br/>━━━━━━━━━<br/>NetworkPolicy 지원<br/>Service LB<br/>Hubble Observability]
        end

        Nginx --> VS
        MySQL -.-> Longhorn
        Talon -.->|"NetworkPolicy 관리"| Cilium
    end

    CF_Tunnel --> MetalLB

    style User fill:#E8F5E9
    style CF_CDN fill:#FF9800
    style CF_Tunnel fill:#FFA726
    style MetalLB fill:#2196F3
    style Nginx fill:#009688
    style VS fill:#673AB7
    style WEB fill:#4CAF50
    style WAS fill:#03A9F4
    style MySQL fill:#F44336
    style Falco fill:#E91E63
    style Sidekick fill:#EC407A
    style Talon fill:#C2185B
    style Prom fill:#FF5722
    style Loki fill:#FF7043
    style Grafana fill:#FF6F00
    style ArgoCD fill:#3F51B5
    style Longhorn fill:#9C27B0
    style Cilium fill:#00BCD4
```

---

## 보안 이벤트 플로우 (Falco IDS/IPS)

```mermaid
sequenceDiagram
    participant Pod as 🐳 Pod (의심 행위)
    participant Falco as 🛡️ Falco DaemonSet
    participant Sidekick as 📡 Falcosidekick
    participant Loki as 📝 Loki (7일 보관)
    participant Talon as ⚔️ Falco Talon IPS
    participant K8s as ☸️ Kubernetes API

    Pod->>Pod: 의심스러운 프로세스 실행<br/>(reverse shell)
    Pod->>Falco: eBPF syscall 모니터링

    Note over Falco: CRITICAL 이벤트 탐지<br/>- RCE 공격<br/>- 민감 파일 접근<br/>- 권한 상승 시도

    Falco->>Sidekick: Alert 전송 (JSON)

    par 병렬 처리
        Sidekick->>Loki: 1. 장기 보관 & 분석
        Note over Loki: Grafana에서<br/>시각화 가능
    and
        Sidekick->>Talon: 2. 자동 대응 요청
        Note over Talon: Dry-Run Phase 1<br/>(로그만 기록)
        Talon->>K8s: NetworkPolicy 생성
        Note over K8s: - Ingress: DENY ALL<br/>- Egress: DENY ALL<br/>- Pod 완전 격리
    end

    K8s-->>Pod: 네트워크 격리 적용

    Note over Pod: 효과:<br/>✅ C2 서버 연결 차단<br/>✅ 내부 확산 방지<br/>✅ Pod는 Running 유지<br/>(포렌식 조사 가능)
```

---

## CI/CD 파이프라인

```mermaid
graph LR
    subgraph Developer["👨‍💻 개발자"]
        Code[코드 작성]
        Commit[Git Commit]
    end

    subgraph GitHub["🐙 GitHub"]
        Push[Git Push<br/>main branch]
        Actions[GitHub Actions]
    end

    subgraph Build["🔨 Build & Push"]
        Maven[Maven Build<br/>./mvnw clean package]
        Docker[Docker Build<br/>Dockerfile]
        GHCR[GHCR Push<br/>ghcr.io/wlals2/<br/>board-was:SHA]
    end

    subgraph GitOps["⚙️ GitOps"]
        Manifest[Manifest Update<br/>k8s-manifests repo]
        ArgoCD[ArgoCD Auto-Sync<br/>3초 이내 감지]
    end

    subgraph Deploy["🚀 배포"]
        Rollout[Argo Rollout<br/>Canary 배포]
        Traffic1[10% 트래픽<br/>30초 대기]
        Traffic2[50% 트래픽<br/>30초 대기]
        Traffic3[90% 트래픽<br/>30초 대기]
        Complete[100% 배포 완료]
    end

    Code --> Commit --> Push --> Actions
    Actions --> Maven --> Docker --> GHCR
    GHCR --> Manifest --> ArgoCD
    ArgoCD --> Rollout --> Traffic1 --> Traffic2 --> Traffic3 --> Complete

    style Code fill:#E8F5E9
    style Actions fill:#2196F3
    style Maven fill:#FF9800
    style Docker fill:#2196F3
    style GHCR fill:#9C27B0
    style ArgoCD fill:#3F51B5
    style Rollout fill:#4CAF50
    style Complete fill:#66BB6A
```

---

## 네트워크 플로우 (Traffic Routing)

```mermaid
graph TB
    User([👤 사용자])

    subgraph External["🌐 External"]
        CF[Cloudflare CDN<br/>DDoS, SSL/TLS, Cache]
        Tunnel[Cloudflare Tunnel]
    end

    subgraph K8s["☸️ Kubernetes Cluster"]
        MB[MetalLB<br/>192.168.X.200]
        NI[Nginx Ingress<br/>L4 LoadBalancer]

        subgraph Istio["Istio Service Mesh"]
            VS[VirtualService<br/>L7 Routing]

            WEB_SVC[web-service<br/>ClusterIP:80]
            WAS_SVC[was-service<br/>ClusterIP:8080]
            MySQL_SVC[mysql-service<br/>ClusterIP:3306]

            WEB1[WEB Pod 1]
            WEB2[WEB Pod 2]
            WAS1[WAS Pod 1]
            WAS2[WAS Pod 2]
            MySQL[MySQL Pod]
        end
    end

    User -->|HTTPS| CF
    CF --> Tunnel
    Tunnel --> MB
    MB --> NI
    NI --> VS

    VS -->|"/ → web-service"| WEB_SVC
    VS -->|"/api/** → was-service"| WAS_SVC

    WEB_SVC --> WEB1
    WEB_SVC --> WEB2
    WAS_SVC --> WAS1
    WAS_SVC --> WAS2

    WAS1 -->|mTLS 🔒| WEB1
    WAS2 -->|mTLS 🔒| WEB2

    WAS1 -->|평문 TCP| MySQL_SVC
    WAS2 -->|평문 TCP| MySQL_SVC
    MySQL_SVC --> MySQL

    style User fill:#E8F5E9
    style CF fill:#FF9800
    style Tunnel fill:#FFA726
    style MB fill:#2196F3
    style NI fill:#009688
    style VS fill:#673AB7
    style WEB_SVC fill:#4CAF50
    style WAS_SVC fill:#03A9F4
    style MySQL_SVC fill:#F44336
    style WEB1 fill:#66BB6A
    style WEB2 fill:#66BB6A
    style WAS1 fill:#29B6F6
    style WAS2 fill:#29B6F6
    style MySQL fill:#EF5350
```

---

## 스토리지 아키텍처 (Longhorn)

```mermaid
graph TB
    subgraph Worker["☸️ Worker Nodes"]
        W1[worker1<br/>192.168.X.61]
        W2[worker2<br/>192.168.X.62]
        W3[worker3<br/>192.168.X.60]
    end

    subgraph Longhorn["💾 Longhorn Distributed Storage"]
        Manager[Longhorn Manager<br/>CSI Driver]

        subgraph Replicas["Replica Distribution"]
            R1[Replica 1<br/>worker1]
            R2[Replica 2<br/>worker2]
            R3[Replica 3<br/>worker3]
        end

        Volume[Volume<br/>MySQL PVC 5Gi]
    end

    subgraph Backup["☁️ S3 Backup"]
        S3[AWS S3<br/>━━━━━━━━<br/>Daily 03:00 KST<br/>RTO: 5분<br/>RPO: 24시간]
    end

    subgraph MySQL_Pod["🗄️ MySQL Pod"]
        MySQL[MySQL 8.0<br/>PVC Mount]
    end

    MySQL --> Volume
    Volume --> Manager
    Manager --> R1
    Manager --> R2
    Manager --> R3

    R1 -.-> W1
    R2 -.-> W2
    R3 -.-> W3

    Manager -->|"CronJob<br/>매일 03:00"| S3

    Note1[Failover 시나리오:<br/>1. worker1 장애 발생<br/>2. Longhorn가 30초 내 감지<br/>3. Replica 2, 3에서 자동 복구<br/>4. 새 Replica를 다른 노드에 생성]

    style W1 fill:#4CAF50
    style W2 fill:#4CAF50
    style W3 fill:#4CAF50
    style Manager fill:#9C27B0
    style R1 fill:#BA68C8
    style R2 fill:#BA68C8
    style R3 fill:#BA68C8
    style Volume fill:#AB47BC
    style S3 fill:#FF9800
    style MySQL fill:#F44336
    style Note1 fill:#FFF9C4
```

---

## 사용 방법

### 1. Hugo 블로그에 삽입
05-ARCHITECTURE.md 파일에 위 Mermaid 코드 블록을 복사해서 붙여넣으면 자동으로 렌더링됩니다.

### 2. GitHub README에 삽입
k8s-manifests/README.md에도 동일하게 사용 가능합니다.

### 3. PNG로 내보내기
```bash
# Mermaid CLI 설치 (Node.js 필요)
npm install -g @mermaid-js/mermaid-cli

# PNG 생성
mmdc -i architecture.md -o architecture.png
```

### 4. 온라인 에디터
https://mermaid.live/ 에서 실시간으로 편집하고 미리보기 가능

---

**작성:** Claude Code
**최종 수정:** 2026-01-24
**도구:** Mermaid.js
