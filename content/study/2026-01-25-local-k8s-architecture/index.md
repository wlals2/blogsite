---
title: "집에서 굴리는 Kubernetes 클러스터 아키텍처"
date: 2026-01-25
tags: ["kubernetes", "architecture", "istio", "cilium", "argocd", "monitoring", "security"]
categories: ["DevOps"]
summary: "4대의 서버로 만든 홈랩 Kubernetes - 58일간 운영하면서 배운 것들"
---

## 시작하며

집에 놀고 있는 서버 4대로 Kubernetes 클러스터를 만들고 58일째 운영 중입니다. 처음에는 "그냥 블로그 하나 띄우는 건데 이렇게까지 해야 하나?" 싶었는데, 막상 만들고 나니 이게 진짜 공부가 되더라고요.

이 글에서는 어떤 구조로 만들었는지, 왜 이런 선택을 했는지, 그리고 운영하면서 배운 것들을 정리해봤습니다.

![Local K8s Architecture](../../../image/localk8s%20아키텍처.png)

**현재 상태:**
- 4대 서버 (Control Plane 1대 + Worker 3대)
- Kubernetes 1.31.1
- 58일간 다운타임 0분
- 매일 실제로 사용 중인 블로그

---

## 설계할 때 고민했던 것들

### 어떤 원칙으로 만들었나?

처음부터 완벽하게 계획한 건 아니었어요. 하나씩 만들다 보니 자연스럽게 이런 원칙들이 생겼습니다:

**1. Git을 믿자**
- kubectl로 직접 수정하면 나중에 뭘 바꿨는지 기억이 안 나더라고요
- 모든 변경은 Git Push로만 하기로 했습니다
- ArgoCD가 3초마다 자동으로 동기화해줘요

**2. 눈에 보이게 만들자**
- 문제가 생기면 바로 알 수 있어야 한다고 생각했어요
- Prometheus로 메트릭, Loki로 로그, Grafana로 시각화
- Hubble UI로 네트워크 플로우도 볼 수 있게

**3. 보안은 계층별로**
- 빌드할 때 (Trivy로 이미지 스캔)
- 네트워크 레벨 (Cilium NetworkPolicy)
- 런타임 (Falco로 이상 행동 탐지)

**4. 천천히 배포하자**
- 한 번에 모든 트래픽을 옮기면 문제 생겼을 때 대응이 어렵더라고요
- Canary 배포로 10% → 50% → 90% 단계적으로
- Manual Approval로 확인 후 진행

**5. HA보다는 빠른 복구**
- MySQL을 3개 띄우면 리소스가 3배 들어요
- 개인 블로그에서 20초 다운타임은 괜찮다고 판단했어요
- 대신 Longhorn으로 데이터는 3벌 복제

### 기술 선택할 때 고민들

매번 선택지가 여러 개였어요. 왜 이걸 골랐는지 간단히 정리하면:

| 필요한 것 | 선택한 기술 | 버린 선택지 | 이유 |
|-----------|------------|-------------|------|
| Service Mesh | Istio | Linkerd, Consul | mTLS랑 트래픽 관리가 한 번에 |
| CNI | Cilium | Calico, Flannel | eBPF가 빠르고 Hubble UI가 편함 |
| GitOps | ArgoCD | FluxCD | Web UI가 있어서 보기 편함 |
| 배포 전략 | Argo Rollouts | Flagger | Istio랑 궁합이 좋음 |
| Storage | Longhorn | Rook-Ceph | 설정이 간단함 |
| Monitoring | PLG Stack | ELK, Splunk | 가볍고 Kubernetes랑 잘 맞음 |
| Security | Falco | Sysdig | 오픈소스고 eBPF 기반 |

---

## 계층별로 어떻게 구성했나?

### 1. 외부에서 블로그에 접속하는 과정

처음에 가장 막막했던 게 "집 서버를 어떻게 외부에서 접속하게 하지?"였어요.

```
사용자 → blog@home.shop (DNS)
  ↓
Cloudflare (CDN + 캐시)
  ↓
Ingress Nginx (NodePort 30001)
  ↓
Istio Service Mesh
```

**왜 Cloudflare를 썼나?**
- 집 공인 IP가 자주 바뀌는데, DDNS로 자동 갱신되게 했어요
- CDN 캐시 덕분에 블로그가 빨라졌고
- DDoS 공격도 Cloudflare가 막아줘요
- SSL/TLS도 Cloudflare가 처리

**문제: 배포할 때마다 캐시 때문에 업데이트가 안 보임**

처음에는 배포하고 10분씩 기다렸어요. 나중에 알고 보니 Cloudflare 캐시가 남아있었던 거였어요. GitHub Actions에 캐시 퍼지를 추가했습니다:

```yaml
- name: Purge Cloudflare Cache
  run: |
    curl -X POST "https://api.cloudflare.com/client/v4/zones/$ZONE_ID/purge_cache" \
      -H "Authorization: Bearer $API_TOKEN" \
      -d '{"purge_everything":true}'
```

이제 배포하면 바로 반영돼요.

---

### 2. Service Mesh - Istio로 Pod 간 통신 암호화

WEB Pod, WAS Pod, MySQL Pod가 서로 통신하는데, 평문으로 주고받으면 불안하잖아요? Istio를 써서 mTLS로 암호화했습니다.

```
┌─────────────────────────────────┐
│  Istio Service Mesh             │
│  (mTLS PERMISSIVE)              │
│                                 │
│  WEB Pod ──→ WAS Pod ──→ MySQL │
│  (암호화)      (암호화)    (제외) │
└─────────────────────────────────┘
```

**왜 mTLS STRICT가 아니라 PERMISSIVE?**

처음에는 STRICT로 설정했어요. 그랬더니 Ingress Nginx에서 들어오는 트래픽을 막더라고요. Cloudflare가 평문으로 보내는데, Istio가 "mTLS 아니면 거부!"라고 해서...

PERMISSIVE는 평문이랑 mTLS 둘 다 받아요:
- 외부 → WEB: 평문 OK
- WEB ↔ WAS: mTLS 자동 적용

나중에 Let's Encrypt 인증서를 Istio Gateway에 직접 달면 STRICT로 바꿀 수 있을 것 같아요.

**MySQL은 왜 Istio Sidecar를 뺐나?**

MySQL에 Istio Sidecar를 달았더니 WAS에서 "Connection reset" 에러가 계속 났어요. JDBC가 Istio Sidecar랑 잘 안 맞는다고 하더라고요. 그래서 MySQL만 Istio에서 제외했습니다:

```yaml
annotations:
  sidecar.istio.io/inject: "false"
```

> 더 자세한 내용: [Istio Service Mesh 완전 아키텍처](/study/2026-01-21-istio-service-mesh-architecture/)

---

### 3. 실제 Application - WEB, WAS, MySQL

**WEB (Hugo 블로그)**
- 이 블로그 자체를 Docker 이미지로 만들어서 Pod에 띄웠어요
- Argo Rollouts로 Canary 배포 (10% → 50% → 90%, 30초 간격)
- HPA로 CPU 70% 넘으면 자동으로 Pod 추가 (2~5개)

**WAS (Spring Boot)**
- 게시판 기능 (CRUD API)
- Canary 배포 더 신중하게 (20% → 50% → 80%, 1분 간격)
- HPA 범위를 넓게 (2~10개)
- JVM 튜닝: `-Xms256m -Xmx512m -XX:+UseG1GC`

**MySQL**
- 처음에는 "3개 띄워서 Galera Cluster 만들까?" 고민했어요
- 근데 리소스가 3배 들고, 개인 블로그에서 20초 다운타임은 괜찮다고 판단
- 대신 Longhorn으로 데이터는 3벌 복제 (노드 장애 시에도 안전)
- 매일 밤 3시에 자동 백업

> MySQL HA 안 한 이유: [MySQL HA vs 백업 전략](/study/2026-01-25-longhorn-mysql-ha-strategy/)

---

### 4. Storage - Longhorn이랑 Local-path 섞어 쓰기

스토리지도 고민이 많았어요. 처음에는 "다 Longhorn으로 하면 되겠지?" 했는데, 복제 때문에 용량이 3배씩 들더라고요.

```
Longhorn (15Gi)
├─ MySQL (5Gi × 3복제)
└─ Loki (10Gi × 3복제)

Local-path (75Gi)
├─ Prometheus (50Gi)
└─ Grafana (10Gi)
```

**왜 섞어 쓰나?**

| 데이터 | Storage | 이유 |
|--------|---------|------|
| MySQL | Longhorn | 데이터 손실 절대 안 됨 |
| Loki | Longhorn | 로그 날아가면 히스토리 못 봄 |
| Prometheus | Local-path | 재수집하면 되니까 |
| Grafana | Local-path | 대시보드는 Git에 있으니까 |

Longhorn 3복제 때문에 스토리지 3배 쓰지만, 노드 죽어도 데이터 안 날아가요. 실제로 Worker 노드 재부팅했을 때 MySQL이 다른 노드로 옮겨가면서 데이터 손실 0이었습니다.

> 스토리지 최적화 과정: [Longhorn & Nextcloud 분석](/study/2026-01-20-storage-analysis/) (30Gi 절약)

---

### 5. 네트워크 - Cilium eBPF로 빠르게

CNI는 Calico 쓸까 Cilium 쓸까 고민했어요.

| 비교 | Cilium (eBPF) | Calico (iptables) |
|------|---------------|-------------------|
| **속도** | 커널 레벨 (빠름) | Userspace 거쳐감 |
| **관측성** | Hubble UI 내장 | 별도 도구 필요 |
| **보안** | eBPF 프로그램 | iptables 규칙 |

Cilium 골랐어요. Hubble UI가 진짜 편합니다. 네트워크 플로우를 웹에서 바로 볼 수 있어요.

```
Cilium Agent (각 노드)
  ↓
Hubble UI (웹 대시보드)
  → 어떤 Pod가 어디랑 통신하는지 한눈에
```

Falco IPS랑도 연동했어요. 공격 탐지되면 Cilium NetworkPolicy로 자동 격리되게.

> Cilium 상세: [Cilium eBPF 네트워킹](/study/2026-01-14-cilium-ebpf-networking/)

---

### 6. GitOps - ArgoCD로 Git만 믿고 가기

"kubectl로 직접 수정하면 나중에 뭘 바꿨는지 모르겠더라..."

그래서 ArgoCD 도입했습니다. 이제 모든 변경은 Git Push로만:

```
GitHub (k8s-manifests)
   │
   ▼ Git Push
ArgoCD (3초마다 확인)
   │
   ▼ kubectl apply
Kubernetes Cluster
```

**실제로 어떻게 쓰나?**

```bash
# 예: WAS Pod를 2개에서 3개로 늘리기

# 1. manifest 파일 수정
vi k8s-manifests/blog-system/was-rollout.yaml
# replicas: 2 → 3

# 2. Git 커밋
git commit -m "scale: was replicas 2 → 3"
git push origin main

# 3. 3초 후 ArgoCD가 자동으로 반영
# kubectl 안 써도 됨!
```

**SelfHeal 기능이 신기해요**

누가 kubectl로 직접 수정하면 ArgoCD가 다시 Git대로 되돌려놔요. Git이 진짜 Source of Truth가 됩니다.

> GitOps 파이프라인: [CI/CD 파이프라인 구축](/study/2026-01-20-gitops-cicd-pipeline/)

---

### 7. Monitoring - PLG Stack으로 관측성 확보

"Pod가 왜 재시작됐지?"
"CPU 사용률이 언제 튀었지?"
"에러 로그가 어디 있지?"

이런 걸 알려면 모니터링이 필수더라고요.

```
Prometheus (메트릭 수집, 15일 보관)
  ↓
Loki (로그 수집, 7일 보관)
  ↓
Grafana (시각화)
  → 대시보드 4개 (클러스터, 블로그, 스토리지, 로그)
```

**수집하는 메트릭들:**
- 노드: CPU, Memory, Disk
- Pod: 요청수, 응답시간, 에러율
- Longhorn: 스토리지 사용량, IOPS
- Application 로그: WEB, WAS, MySQL 전부

**Alert Rule 8개 설정**

예를 들어 Pod가 15분 동안 계속 재시작하면 알림 오게:

```yaml
- alert: PodRestartingTooOften
  expr: rate(kube_pod_container_status_restarts_total[15m]) > 0.1
  annotations:
    summary: "Pod {{ $labels.pod }} 계속 재시작 중"
```

아직 Slack 연동은 안 했는데, 곧 할 예정이에요.

> PLG 구축 과정: [PLG Stack 구축 가이드](/study/2026-01-20-plg-monitoring-stack/)

---

### 8. Security - Falco로 런타임 보안

Trivy로 이미지 스캔은 하는데, 런타임에 이상한 짓 하면 어떡하지? 싶었어요.

Falco를 써서 eBPF로 syscall을 모니터링합니다:

```
┌─ IDS (탐지) ─────────────────┐
│                              │
│  Falco (eBPF)                │
│   → syscall 감시            │
│                              │
│  Falcosidekick               │
│   → Loki/Slack 알림         │
└──────────┬───────────────────┘
           │
           ▼
┌─ IPS (자동 대응) ────────────┐
│                              │
│  Falco Talon                 │
│   → "격리할까?" 판단        │
│                              │
│  NetworkPolicy 생성          │
│   → Egress 차단 (DNS만 허용)│
└──────────────────────────────┘
```

**커스텀 룰 4개 만들었어요:**

1. **Java가 Shell 실행** → RCE 공격 의심
2. **Package Manager 실행** → 악성 패키지 설치 시도?
3. **바이너리 파일 쓰기** → 백도어 설치 시도?
4. **외부 통신** → C&C 서버 연결 시도?

**실제 공격 시나리오 (Log4Shell RCE)**

```
1. Falco 탐지: "Java Process Spawning Shell" (CRITICAL)
   ↓ (1초 내)
2. Falcosidekick → Talon으로 알림
   ↓ (2초)
3. Talon: Pod에 "quarantine=true" 라벨 추가
   ↓ (1초)
4. NetworkPolicy 자동 생성 → Egress 차단
   ↓ (1초)
5. Slack 알림: "WAS Pod 격리됨 (RCE 시도 탐지)"

총 5초 만에 C&C 서버 통신 차단!
```

Pod를 죽이는 대신 격리하는 이유는 로그를 분석할 수 있게 하려고요.

> Falco 상세: [Falco eBPF 런타임 보안](/study/2026-01-25-falco-ebpf-runtime-security-architecture/)

---

## 설계 결정할 때 고민들

### MySQL HA를 안 한 이유

"MySQL 1개면 죽으면 어떡해요?"

맞는 말이에요. Galera Cluster로 3개 띄우면 HA 되죠. 근데:

**리소스 계산:**
- MySQL HA: CPU 3개, RAM 3GB, Storage 15Gi
- 현재: CPU 1개, RAM 1GB, Storage 5Gi (Longhorn 복제는 별도)

**RTO (복구 시간):**
- MySQL HA: 0초 (다른 Pod로 즉시 연결)
- 현재: 20초 (Kubernetes가 Pod 재시작)

**비즈니스 요구사항:**
- 개인 블로그: 하루 방문자 10명
- 금융권: 초당 1만 트랜잭션

20초 다운타임이 개인 블로그에서 큰 문제일까요? 리소스를 3배 쓸 가치가 있을까요?

저는 아니라고 판단했어요. 대신:
- Longhorn 3 Replica로 데이터 안전성 확보
- 매일 밤 3시 자동 백업
- 58일 운영하면서 다운타임 0분 달성

> 상세 분석: [MySQL HA vs 백업 전략](/study/2026-01-25-longhorn-mysql-ha-strategy/)

---

### Istio Gateway 대신 Ingress Nginx를 쓴 이유

"Service Mesh 썼으면 Istio Gateway 쓰는 게 깔끔한 거 아니에요?"

맞아요. 근데 Cloudflare 때문에 문제가 생겼어요.

**문제 상황:**
- Cloudflare → Istio Gateway: mTLS STRICT 불가
- Cloudflare가 평문으로 연결을 보냄
- Istio: "mTLS 아니면 거부!"

**해결:**
- Ingress Nginx를 앞에 두고 평문 수신
- Istio는 mTLS PERMISSIVE (평문 + mTLS 둘 다 허용)

**향후 개선 계획:**
- Let's Encrypt 인증서를 Istio Gateway에 직접 달기
- 그러면 Ingress Nginx 제거하고 Istio Gateway만 쓸 수 있을 듯

---

### Longhorn과 Local-path를 혼용하는 이유

"스토리지 통일하면 관리가 편한 거 아니에요?"

맞아요. 근데 리소스 효율을 생각하면:

**모두 Longhorn으로 하면:**
- Prometheus 50Gi × 3복제 = 150Gi 필요
- Grafana 10Gi × 3복제 = 30Gi 필요
- 총 180Gi → 집 서버에서 부담됨

**모두 Local-path로 하면:**
- MySQL 노드 죽으면 데이터 손실
- Loki 로그 날아가면 히스토리 못 봄

**그래서 섞어 쓰기로:**
- 중요한 데이터 (MySQL, Loki): Longhorn
- 재수집 가능 (Prometheus, Grafana): Local-path

트레이드오프를 고려한 선택이었어요.

---

## 실제 운영 성능

### 배포는 얼마나 빠른가?

| 단계 | 시간 |
|------|------|
| Hugo 빌드 → Docker 이미지 | 15초 |
| GHCR Push | 10초 |
| ArgoCD Sync | 5초 |
| Cloudflare 캐시 퍼지 | 5초 |
| **총 배포 시간** | **35초** |

Canary 배포까지 하면:
- WEB: 1.5분 (10% → 50% → 90%, 30초 간격)
- WAS: 3분 (20% → 50% → 80%, 1분 간격)

롤백은 Argo Rollouts abort 명령으로 10초 안에 가능해요.

### 가용성은?

| 지표 | 수치 |
|------|------|
| 운영 일수 | 58일 (2024-11-28~) |
| 다운타임 | 0분 |
| Pod 재시작 | MySQL 0회, WAS 2회 (OOM) |

WAS가 OOM으로 2번 재시작됐는데, JVM 튜닝 후에는 안정적이에요.

### 리소스 사용률은?

| 노드 | CPU | Memory | Storage |
|------|-----|--------|---------|
| k8s-cp (Control Plane) | 7% | 30% | 20Gi |
| k8s-worker1 | 16% | 72% | 45Gi |
| k8s-worker2 | 15% | 39% | 25Gi |
| k8s-worker3 | 12% | 35% | 20Gi |

Worker1이 메모리를 많이 쓰는데, Prometheus랑 Loki가 여기 있어서 그래요.

---

## 다음에 할 일들

### 빨리 할 것들 (30분 내)

**1. Loki Retention 설정 (5분)**

지금은 로그가 무한정 쌓이는데, 7일로 제한하려고요:

```yaml
table_manager:
  retention_deletes_enabled: true
  retention_period: 168h  # 7일
```

**2. Longhorn 스냅샷 정책 (15분)**

매일 밤 3시에 MySQL 스냅샷 자동으로:

```yaml
apiVersion: longhorn.io/v1beta1
kind: RecurringJob
metadata:
  name: mysql-snapshot-daily
spec:
  cron: "0 3 * * *"
  task: snapshot
  retain: 7
```

**3. Prometheus Alert → Slack (10분)**

지금은 Grafana에서 직접 봐야 하는데, Slack으로 알림 오게 하려고요.

### 나중에 해볼 것들 (1시간+)

**4. Cilium kube-proxy 대체**
- eBPF로 Service Load Balancing
- 성능 30% 향상 예상
- 복잡도가 올라가서 신중하게

**5. Istio Gateway 직접 노출**
- Let's Encrypt 인증서
- Ingress Nginx 제거
- 아키텍처가 깔끔해질 듯

**6. Falco IPS Phase 2**
- 지금은 Dry-Run 모드
- WARNING 레벨도 자동 격리
- False Positive 패턴 학습 후 진행

---

## 체크리스트

### 구축 완료한 것들
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

### 나중에 해볼 것들
- [ ] Cilium kube-proxy 대체
- [ ] Istio Gateway 직접 노출
- [ ] Falco IPS Phase 2 활성화

---

## 마치며

58일간 운영하면서 많이 배웠어요. 처음에는 "블로그 하나 띄우는 데 왜 이렇게 복잡해?" 싶었는데, 하나씩 문제를 해결하다 보니 Kubernetes 생태계를 제대로 이해하게 됐습니다.

특히 좋았던 점:
- **GitOps**: 모든 변경이 Git에 기록돼서 추적 가능
- **Canary 배포**: 문제 생겨도 일부만 영향받음
- **관측성**: 뭔가 이상하면 바로 알 수 있음
- **실제 운영 경험**: 책으로만 보던 걸 직접 해보니 다르더라고요

아쉬운 점:
- 리소스 부족으로 HA를 못한 부분들 (MySQL, Prometheus)
- Istio Gateway를 제대로 못 쓴 것
- 아직 Slack 알림 연동 안 한 것

앞으로도 계속 개선해나갈 예정입니다!

---

## 관련 글들

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
