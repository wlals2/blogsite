# K8s 온프레미스 포트폴리오 — 클라우드 엔지니어용

> 작성일: 2026-02-24
> 상태: 초안 (도입 배경 검증 필요)
> 형식: PDF (사람인 첨부용), 6장
> 원칙: Case Study 형식 (문제 → 비교 → 해결 → 증거)

---

## 프로젝트 기본 정보

| 항목 | 내용 |
|------|------|
| 프로젝트명 | Bare-Metal Kubernetes 블로그 플랫폼 |
| 기간 | 2025.07 ~ 현재 (220일+, 운영 중) |
| 팀 구성 | 1인 (전체 역할) |
| 역할 | 설계 + 구축 + 운영 + 보안 + 관측성 전체 |
| 애플리케이션 | 본인 기술 블로그 (blog.jiminhome.shop, 실제 서비스) |
| 인프라 | 베어메탈 CP + VMware Worker 3대 (4노드) |
| 배포 | ArgoCD GitOps + Argo Rollouts Canary |

---

## [7장] 프로젝트 개요

### 내용
- 1인 프로젝트 / 220일+ 운영 중
- 왜 온프레미스? → 클라우드 비용 $0 + "관리형 서비스가 해주는 것"을 직접 구축하며 학습
- 전체 아키텍처 다이어그램 (4노드 + 서비스 배치)
- 블로그 링크

### 핵심 성과 요약
```
· 220일 운영, 계획된 다운타임 0분
· CPU Requests 22.4 Core → 6.25 Core (-72%, 정책 기반 최적화)
· Falco IPS 자동 격리: 수동 5분 → 자동 5초 (99% 단축)
```

### ⚠️ 검증 필요
- "왜 온프레미스?"의 실제 이유 — 학습? 비용? 둘 다?
- 220일 시작점이 정확한지 (2025.07?)

---

## [8장] Case Study ⑤ — GitOps + Canary 배포

### 도입 배경 (검증 필요)
```
1. kubectl apply 수동 배포 → 실수/롤백 어려움
2. ArgoCD GitOps 도입 → Git push만으로 3초 내 자동 배포
3. Argo Rollouts Canary → 무중단 배포 (WAS: 20→50→80→100%)
4. App of Apps 패턴 → root-app 하나로 전체 클러스터 부트스트랩
```

### 핵심 의사결정
| 결정 | 왜? |
|------|-----|
| 2-Repo GitOps | Source(코드)와 Manifest(배포) 분리 — 관심사 분리 |
| App of Apps | 서비스 추가 시 YAML 1개 + git push만으로 완료 |
| selfHeal: true | kubectl 수동 수정 즉시 롤백 → Git이 유일한 진실 |
| Canary 20→50→80→100 | 단계별 트래픽 이동으로 안전한 릴리스 |

### 성과
- 배포 총 501회 (WAS 327 + WEB 174), 실패 0
- Canary 배포 시간: WAS 3분 / WEB 1.5분 / 롤백 10초

### 사용할 이미지
- ArgoCD 앱 목록 (블로그에 있음)
- Canary 배포 진행 화면

### ⚠️ 검증 필요
- kubectl 수동 배포 시절이 실제로 있었는지?
- 배포 실패 경험이 있었는지?

---

## [9장] Case Study ⑥ — 플랫폼 의사결정

### 도입 배경: 관리형 서비스 없이 직접 선택해야 하는 것들

### 의사결정 ① CNI 선택
| 옵션 | 장점 | 단점 | 선택 |
|------|------|------|------|
| Flannel | 가장 간단 | NetworkPolicy 미지원, iptables 기반 | 탈락 |
| Calico | NetworkPolicy 지원 | iptables 기반, 성능 제한 | 탈락 |
| **Cilium** | eBPF 기반 (iptables 우회), L3/L4 정책 | 학습 곡선 | **채택** |

선택 근거: eBPF로 30-40% 네트워킹 성능 향상 + NetworkPolicy L3/L4 제어

### 의사결정 ② MySQL HA
| 옵션 | 장점 | 단점 | 선택 |
|------|------|------|------|
| Galera Cluster | 자동 Failover, RTO 0 | CPU×3, RAM×3 | 탈락 |
| **단일 Pod + 백업** | 리소스 절약 | RTO ~20초 (Pod 재생성) | **채택** |

선택 근거: 블로그 서비스에 Galera는 과도 → 단일 Pod + CronJob 백업 + S3 저장

### 의사결정 ③ Nginx Ingress 제거
```
Before: Client → MetalLB → Nginx Ingress → Pod (L7 hop 2개)
After:  Client → MetalLB → Istio Gateway → Pod (L7 hop 1개)
```
이유: Istio Gateway가 L7 라우팅도 하므로 Nginx Ingress 중복 제거

### 사용할 이미지
- 아키텍처 다이어그램 (4노드 + 서비스 배치)

### ⚠️ 검증 필요
- Cilium 선택이 성능 때문인지, 보안(NetworkPolicy) 때문인지, 학습 때문인지?
- MySQL Galera를 실제로 검토했는지?
- Nginx Ingress → Istio Gateway 전환 시 실제 문제가 있었는지?

---

## [10장] Case Study ⑦ — 관측성 + SLO

### 도입 배경 (검증 필요)
```
PLG Stack (Prometheus + Loki + Grafana)
  + Tempo (분산 트레이싱)
  + SLI/SLO 체계
  + Discord 알람
```

### SLO 체계
| SLI | 목표 | 측정 방법 |
|-----|------|----------|
| Availability | 99% (30일) | Istio 요청 성공률 |
| Latency p95 | < 500ms | Istio Histogram |
| Error Rate 5xx | < 1% | Istio 응답 코드 |

### 대시보드 3-Level 구조
| Level | 대시보드 | 목적 |
|-------|---------|------|
| L1 | SLO Overview | "문제가 있나?" — 30초 판단 |
| L2 | Service/Node Detail | "어디서?" — RED/USE |
| L3 | MySQL/CronJob/MITRE | "원인은?" — 상세 분석 |

### Tempo 분산 트레이싱
```
WAS (OTEL Java Agent) → Tempo (gRPC 4317)
  → Grafana Explore (트레이스 시각화)
  → Tempo metrics_generator → Prometheus (RED 메트릭 자동 생성)
```
핵심: 별도 APM 없이 Tempo가 스팬에서 RED 메트릭 자동 생성 → Prometheus에 Push

### 성과: Alertmanager → Grafana Alerting 전환
```
문제: Alertmanager → Discord webhook = 400 Bad Request
원인: Alertmanager 페이로드가 Discord 형식과 불일치
해결: Grafana Alerting 사용 (Discord Contact Point 네이티브 지원)
```

### 사용할 이미지
- SLO 대시보드 캡처
- MITRE ATT&CK Heatmap 캡처
- Discord 알람 캡처

### ⚠️ 검증 필요
- SLO 99% 달성하고 있는지? 실제 수치?
- Tempo로 실제 문제를 발견한 사례가 있는지?

---

## [11장] Case Study ⑧ — 운영 + 최적화

### CPU 최적화 (-72%)
```
문제: k8s-worker3 CPU Requests 99% → 새 Pod 스케줄 불가
원인: 대부분의 Pod가 실사용 대비 10-50배 과도한 Requests
정책: Requests = 실제 사용량 × 2배 (10m 단위 반올림)

Before: 22.4 Core (전체 노드)
After:  6.25 Core (-72%)
```

### 220일 무중단 운영
```
비결:
  → Canary 배포 (실패 시 자동 롤백)
  → PDB (minAvailable: 1)
  → TopologySpreadConstraints (노드 분산)
  → HPA (CPU 70% + 네트워크 100KB/s)
  → VPA Off 모드 (권장값만 수집)
```

### MySQL 백업 체계
```
CronJob (매일 03:00 KST)
  → mysqldump --single-transaction
  → gzip 압축
  → AWS S3 업로드 (7일 보관)
  → Pushgateway에 성공 메트릭 전송
  → Grafana 대시보드에서 확인
```

### 사용할 이미지
- CPU 최적화 Before/After 차트 (블로그에 있음: chart-before-after.png)

### ⚠️ 검증 필요
- 220일 동안 실제 장애가 있었는지? 어떻게 해결했는지?
- k8s-worker3 문제가 실제로 Pod 스케줄 불가까지 갔는지?

---

## [12장] 트러블슈팅

### ① Istio + MySQL Protocol Detection Deadlock
```
증상: MySQL Pod에 Istio sidecar 주입 시 연결 불가
원인 (2단계):
  1단계: MySQL = Server-First 프로토콜, Istio는 Client-First 기대 → 교착
  2단계: 포트명을 tcp-mysql로 변경해도 JDBC가 mTLS 협상 불가
해결: sidecar.istio.io/inject: "false" (공식 권고)
문서화: ADR에 2단계 원인 분석 기록 (MySQL Native TLS 도입 시 재검토)
```

### ② Falco File Descriptor Limit
```
증상: Falco Pod CrashLoopBackOff
원인: eBPF 프로그램이 FD를 대량 소비 → 기본 limit 초과
해결: securityContext.capabilities + ulimit 조정
```

### ③ kubelet 중단 → Pod Terminating 무한 대기
```
증상: Pod이 Terminating 상태로 수 시간째 멈춤
원인: k8s-worker3 kubelet 중단 → graceful shutdown 확인 불가
진단: kubectl get nodes → NotReady 노드 확인
해결: sudo systemctl restart kubelet
```

### ④ SSH Brute Force 발견
```
증상: Wazuh 대시보드에서 비정상 SSH 로그인 시도 급증
발견: 7개 외부 IP에서 반복적 SSH 접속 시도
대응: fail2ban + SSH 키 전용 인증 + 패스워드 로그인 차단
의미: 실제 공격 → 보안 관제의 필요성 증명
```

---

## 확보된 스크린샷/증거 목록 (확인 필요)

| 장 | 증거 | 상태 |
|----|------|------|
| 7장 | 홈랩 아키텍처 다이어그램 | ✅ 있음 (localk8s 아키텍처.png) |
| 8장 | ArgoCD 앱 목록 | 확인 필요 |
| 8장 | Canary 배포 진행 화면 | 확인 필요 |
| 10장 | SLO 대시보드 | 확인 필요 (캡처 필요) |
| 10장 | MITRE ATT&CK Heatmap | 확인 필요 (캡처 필요) |
| 10장 | Discord 알람 | ✅ 있음 (모니터링 블로그 포스트) |
| 11장 | CPU Before/After 차트 | ✅ 있음 (chart-before-after.png) |
| 12장 | Wazuh SSH brute force | 확인 필요 (캡처 필요) |

---

## 성과 정리 (면접관 관점 강도 평가)

| Case Study | 핵심 성과 | 강도 |
|------------|----------|------|
| ⑤ GitOps | 501회 배포, 실패 0, 롤백 10초 | ★★ |
| ⑥ 플랫폼 | Cilium eBPF 선택, MySQL 단일 Pod 설계 | ★★ |
| ⑦ 관측성 | SLO 체계 + Tempo 트레이싱 + 알람 | ★★ |
| ⑧ 운영 | **CPU -72% 최적화 + 220일 무중단** | ★★★ |
| 트러블슈팅 | Protocol Detection 2단계 분석 + SSH brute force 발견 | ★★★ |

> Bespin과 반대: 이 프로젝트는 "운영 프로젝트"이므로 장기 운영 성과가 강점
> Bespin = 의사결정 스토리 / Homelab = 운영 + 깊이

---

## 포트폴리오 제작 방향 — 최종 정리

### 두 프로젝트 포지셔닝

| | Bespin (6장) | Homelab (6장) |
|---|---|---|
| **메시지** | "클라우드에서 비용과 가용성을 설계한다" | "직접 구축하고 장기 운영할 수 있다" |
| **강점** | 팀 협업 + 비용 의사결정 | 플랫폼 깊이 + 운영 성과 |
| **약점** | 운영 기간 짧음 | 1인 프로젝트 |
| **보완** | Homelab의 운영 경험이 보완 | Bespin의 팀 경험이 보완 |

### 제작 방식 결정 필요

| 방식 | 도구 | 장점 | 단점 |
|------|------|------|------|
| **PPT → PDF** | PowerPoint / Google Slides | 디자인 자유도 | 수작업 |
| **Canva** | canva.com | 템플릿 풍부, 빠름 | 커스텀 한계 |
| **Markdown → PDF** | pandoc / typst | 텍스트 기반, 버전 관리 | 디자인 제한 |
| **Notion → PDF** | Notion | 표/이미지 편리 | 디자인 제한 |

### 다음 작업
- [ ] Homelab 도입 배경 검증 (⚠️ 표시된 항목들)
- [ ] 포트폴리오 제작 도구 선택
- [ ] 실제 작성 시작
