# 02. 면접 예상 질문 + 답변 가이드

> 작성일: 2026-03-03
> 면접 형식: 1차 인터뷰 (직무 리더 + CEO 동시 참석)
> 핵심: 4명 팀 스타트업 → "이 사람이 혼자서 인프라를 책임질 수 있는가"

---

## 카테고리별 예상 질문

### A. 직무 역량 (직무 리더 중심)

---

#### Q1. "온프레미스 K8s 클러스터를 처음부터 구축해본 경험이 있나요?"

**왜 물어보는가**: JD 핵심 — "온프레미스 환경에서 Kubernetes 기반 컨테이너 시스템 구축"

**답변 가이드**:
```
"네, 베어메탈 서버 + VMware VM 환경에서 kubeadm으로 5노드 K8s 클러스터를
직접 구축하고 220일 이상 운영하고 있습니다.

구성:
- Control Plane 1대 (베어메탈) + Worker 4대 (VMware VM)
- CNI: Cilium (eBPF 기반, iptables 우회로 성능 + L3/L4 NetworkPolicy)
- Service Mesh: Istio (mTLS 암호화 + L7 트래픽 제어)
- Storage: Longhorn (분산 블록 스토리지)
- 배포: ArgoCD GitOps + Argo Rollouts Canary

관리형(EKS)도 경험했지만, 베어메탈에서 직접 하면서 관리형 서비스가
내부적으로 뭘 해주는지를 이해할 수 있었습니다.
예를 들어 kubelet systemd 관리, etcd 백업, 인증서 갱신 같은 것들은
EKS에서는 안 보이지만 베어메탈에서는 직접 해야 합니다."
```

**후속 질문 대비**:
- "노드 추가할 때 프로세스는?" → kubeadm join + CNI 자동 설치 + Longhorn 스토리지 등록
- "클러스터 업그레이드 경험?" → kubeadm upgrade, 한 번에 minor 1단계씩
- "HA 구성은?" → 현재 CP 1대(SPOF). 프로덕션에서는 3 CP + etcd 클러스터 필수

---

#### Q2. "모니터링 시스템을 어떻게 구성했나요?"

**왜 물어보는가**: JD — "서비스 모니터링을 통한 효율적이고 안정적인 인프라 운영"

**답변 가이드**:
```
"PLG Stack (Prometheus + Loki + Grafana) + Tempo로 구성했습니다.

메트릭(Prometheus):
- SLI/SLO 체계: Availability 99%, Latency p95 < 500ms, Error Rate < 1%
- Recording Rules로 사전 계산 → 대시보드 3-Level (SLO → 서비스 → 상세)
- 실제 30일 Availability가 77.6%로 나왔을 때 원인 분석까지 수행

로그(Loki):
- Pod stdout 자동 수집 (DaemonSet 방식)
- Falco 보안 이벤트 + Audit Log까지 통합

트레이스(Tempo):
- Spring Boot에 OTEL Java Agent 자동 계측
- 실제로 WAS 응답 지연 원인을 트레이스로 발견 → 외부 API 호출이 병목

알람:
- Grafana Alerting → Discord 실시간 알림
- 실제 사례: MySQL 백업 CronJob 실패 → Pushgateway 메트릭 미수신 → 알람 → 수정"
```

---

#### Q3. "CI/CD 파이프라인을 구축해본 경험이 있나요?"

**왜 물어보는가**: JD — "GitLab CI/CD Pipeline 등 DevOps 환경 기본 이해"

**답변 가이드**:
```
"GitHub Actions + ArgoCD GitOps 기반으로 구현했습니다.

파이프라인 흐름:
  Git Push → GitLeaks(시크릿 탐지) → Docker Build → Trivy(CVE 스캔)
  → ghcr.io Push → Manifest 업데이트 → ArgoCD 자동 Sync → Canary 배포

특징:
- 2-Repo GitOps: 소스 코드와 K8s 매니페스트를 분리
- Canary 배포: 20% → 50% → 80% → 100% 단계별 트래픽 전환
- 보안 게이트: GitLeaks + Trivy가 실패하면 배포 차단
- 501회 배포 중 실패 0건

오아시스에서 GitLab 기반이라면, 개념은 동일하고
GitLab Runner + .gitlab-ci.yml로 전환하면 됩니다.
ArgoCD는 Git 소스에 구애받지 않으므로 그대로 사용 가능합니다."
```

**후속 질문 대비**:
- "GitLab CI와 GitHub Actions 차이?" → Runner 방식, YAML 문법, 캐싱 전략 등
- "롤백은 어떻게?" → ArgoCD: Git revert하면 자동 롤백 / Canary: 10초 내 자동 롤백

---

#### Q4. "장애 대응 경험을 말해주세요"

**왜 물어보는가**: JD — "시스템 장애 발생 시 신속한 대응 및 문제 해결"

**답변 가이드**:
```
"여러 건이 있지만, 대표적으로:

1. Istio + MySQL Protocol Detection Deadlock:
   - 증상: MySQL Pod에 Istio sidecar 주입 시 연결 불가
   - 진단: Server-First 프로토콜과 Istio Protocol Detection이 교착
   - 해결: 포트 이름을 tcp-mysql로 변경 + sidecar 제외 결정
   - 배움: '안 된다'에서 멈추지 않고 근본 원인을 2단계로 파악

2. kubelet 중단 → Pod Terminating stuck:
   - 증상: Pod가 Terminating 상태로 수 시간 멈춤
   - 진단: kubectl get nodes → 해당 워커 노드 NotReady
   - 해결: SSH로 접속 → systemctl restart kubelet
   - 배움: Pod 문제처럼 보여도 노드 레벨부터 확인

3. SSH Brute Force 238,903건:
   - 발견: Wazuh 대시보드에서 비정상 SSH 로그인 시도 급증
   - 대응: fail2ban + SSH 키 전용 인증 + 불필요 포트 차단
   - 의미: 실제 공격을 탐지하고 대응한 경험"
```

---

#### Q5. "컨테이너 보안에 대해 어떤 경험이 있나요?"

**왜 물어보는가**: CTI 회사 → 보안에 대한 이해가 중요

**답변 가이드**:
```
"Defense in Depth 6계층 보안 아키텍처를 직접 설계하고 운영했습니다.

① Supply Chain: GitLeaks + Trivy (빌드 시점 보안)
② Container: SecurityContext Non-root, DROP ALL capabilities
③ Network L3/L4: Cilium eBPF NetworkPolicy
④ Transport: Istio mTLS (Pod 간 암호화)
⑤ Application L7: Istio AuthorizationPolicy
⑥ Runtime: Falco(IDS) + Talon(IPS) + Wazuh(SIEM)

특히 Falco-Talon은 넷코아텍에서 UTM IDS/IPS 실무 경험을 기반으로 설계했습니다.
UTM에서 자동 차단 시 오탐 문제를 경험했기 때문에,
CRITICAL(Java RCE 등)만 즉시 격리하고 WARNING은 관찰하는 Phase 전략을 적용했습니다.

또한 Kyverno로 Admission Controller 4개 정책을 운영 중입니다:
- Non-root 컨테이너 강제
- 특권 컨테이너 차단
- 리소스 Limit 필수
- latest 이미지 태그 차단"
```

---

#### Q6. "Python이나 스크립트 작성 능력은 어느 정도인가요?"

**왜 물어보는가**: JD — "Shell, Python 등 스크립트 및 프로그래밍 활용 능력"

**답변 가이드 (솔직하게)**:
```
"Bash는 자동화 스크립트를 직접 작성할 수 있습니다.
Pod 정리 스크립트, 백업 스크립트 등을 --dry-run, --help, 로깅까지 포함해서 만들었습니다.

Python은 솔직히 기본 스크립트 수준입니다.
간단한 자동화(썸네일 생성, 파일 처리)는 가능하지만,
FastAPI로 서비스를 개발하거나 복잡한 로직을 처음부터 작성하는 것은 아직 부족합니다.

다만, 인프라 엔지니어로서 필요한 Python(API 호출, 데이터 파싱, 자동화)은
업무 중에 빠르게 습득할 수 있다고 생각합니다.
AI 도구를 활용해서 코드를 작성하되, 동작 원리를 이해한 후 적용하는 방식으로 일합니다."
```

---

### B. 기술 심화 (직무 리더)

---

#### Q7. "NoSQL과 RDBMS를 어떻게 사용해봤나요?"

**답변 가이드**:
```
"RDBMS(MySQL):
- K8s StatefulSet으로 운영, PV 기반 데이터 지속성
- mysqldump → gzip → S3 CronJob 자동 백업 (매일 03:00)
- Pushgateway로 백업 성공 메트릭 수집

NoSQL은 직접 운영한 경험은 적지만:
- Elasticsearch (ELKF 프로젝트): Ansible로 4대 서버 구성, 로그 수집/분석
- Wazuh Indexer: OpenSearch(Elasticsearch 포크) 3노드 운영 중
- Loki: 로그 저장소로 사용 중

오아시스에서 CTI 데이터 저장에 Elasticsearch를 사용한다면,
ELKF 프로젝트 + Wazuh Indexer 운영 경험이 도움될 것 같습니다."
```

---

#### Q8. "마이크로서비스 아키텍처에 대해 설명해주세요"

**답변 가이드**:
```
"홈랩에서 실제로 MSA를 적용하고 있습니다:

- WEB (Nginx, 프론트엔드) + WAS (Spring Boot, 백엔드) + MySQL (DB) 분리
- 각 컴포넌트가 독립적으로 배포 가능 (Canary 배포)
- Istio Service Mesh로 서비스 간 통신 관리 (mTLS, 트래픽 제어)
- Cilium NetworkPolicy로 서비스 간 접근 제어

장점을 직접 경험한 것:
- WAS만 업데이트할 때 WEB에 영향 없음
- WAS에 문제가 생기면 해당 Pod만 롤백 (10초)
- 서비스별 리소스 독립 관리

단점도 경험:
- 서비스 간 통신 복잡도 증가 (Service Mesh 필요)
- 분산 트레이싱 없으면 디버깅 어려움 (Tempo 도입 이유)"
```

---

### C. 문화 Fit + 성장 의지 (CEO 중심)

---

#### Q9. "왜 오아시스 시큐리티에 지원했나요?"

**답변 가이드**:
```
"세 가지 이유가 있습니다.

첫째, 온프레미스 K8s 인프라를 직접 설계하고 운영할 수 있는 환경입니다.
홈랩에서 220일 동안 혼자 전체 인프라를 운영한 경험이
오아시스 시큐리티의 DevOps 역할과 정확히 맞는다고 생각했습니다.

둘째, 보안 도메인에서 성장하고 싶습니다.
DevSecOps 파이프라인을 직접 구축하면서 보안에 대한 관심이 커졌고,
CTI라는 분야는 방어를 넘어서 공격자의 행동을 예측하는 것이라
더 넓은 시야를 가질 수 있을 것 같습니다.

셋째, AGATHA의 기술이 인상적이었습니다.
기존 CTI가 '공격 후 분석'이라면, AGATHA는 '공격 전 포착'입니다.
MITRE ATT&CK의 PRE-ATT&CK 단계에서 C2 인프라를 탐지하는 것은
기술적으로도 흥미롭고, 실제 북한 해킹그룹 공격을 사전에 포착한 사례가
이 기술의 실효성을 증명한다고 생각합니다."
```

---

#### Q10. "스타트업에서 일한 경험이 있나요? 왜 대기업이 아니라 스타트업?"

**답변 가이드**:
```
"스타트업 경험은 없지만, 홈랩 운영 자체가 스타트업 환경과 유사했습니다.

혼자서 네트워크, K8s, 보안, 모니터링, CI/CD, 백업까지 전부 했습니다.
리소스가 제한된 환경에서 의사결정을 내려야 했고,
예를 들어 MySQL HA를 Galera Cluster가 아닌 단일 Pod + 백업으로 결정한 것은
리소스 대비 효과를 따진 스타트업 사고방식입니다.

대기업에서는 팀이 세분화되어 있어서 인프라 전체를 경험하기 어렵습니다.
지금 단계에서는 작은 팀에서 넓은 범위를 경험하는 것이
엔지니어로서 성장에 더 도움이 된다고 생각합니다."
```

---

#### Q11. "5년 후 어떤 엔지니어가 되고 싶나요?"

**답변 가이드**:
```
"보안 인프라를 설계하고 운영할 수 있는 엔지니어가 되고 싶습니다.

지금은 K8s 인프라와 DevSecOps에 강점이 있지만,
오아시스에서 CTI 인프라를 운영하면서 보안 도메인의 깊이를 더하고 싶습니다.

구체적으로:
- 단기: 오아시스 CTI 서비스의 안정적 운영 기반 구축
- 중기: CTI 데이터 파이프라인 최적화 + 자동화
- 장기: 보안 인프라 아키텍처를 처음부터 설계할 수 있는 수준

오아시스가 성장하면서 인프라도 커질 것이고,
그 과정에서 함께 성장하고 싶습니다."
```

---

#### Q12. "CTI(Cyber Threat Intelligence)에 대해 알고 있는 것이 있나요?"

**답변 가이드**:
```
"면접 준비하면서 공부했습니다.

CTI는 사이버 위협에 대한 정보를 수집, 분석, 공유하는 분야입니다.

MITRE ATT&CK 프레임워크 관점에서:
- 기존 CTI: 공격 실행 단계(Initial Access ~ Impact)의 IOC를 분석
- AGATHA가 차별화되는 점: PRE-ATT&CK 단계(Reconnaissance, Resource Development)에서
  공격자가 C2 서버를 세팅하는 시점에 인프라를 포착

C2(Command & Control) 서버 탐지 원리로는:
- SSL/TLS 인증서 패턴 분석 (Cobalt Strike 등 공격 도구 특유의 인증서 fingerprint)
- 서버 응답 특성 분석 (HTTP 헤더, JARM hash 등)
- 도메인 등록 패턴 (DGA, 최근 등록 도메인, 유사 도메인)

ARTHUR의 다크웹 IP 식별은:
- Tor 히든 서비스가 기술적 실수나 설정 미흡으로 실제 IP를 노출하는 경우를 포착
- 또는 히든 서비스와 일반 인터넷 서비스 간의 상관관계 분석

DevOps 엔지니어로서 제가 기여할 수 있는 부분은
이런 CTI 서비스가 안정적으로 동작할 수 있는 인프라를 만드는 것이고,
대량 스캔 데이터를 효율적으로 처리하고 저장하는 파이프라인을 구축하는 것입니다."
```

---

### D. 포트폴리오/경력 관련

---

#### Q13. "홈랩을 왜 시작했나요?"

**답변 가이드**:
```
"클라우드 관리형 서비스가 내부적으로 뭘 해주는지 이해하고 싶었습니다.

EKS를 쓰면 Control Plane 관리를 AWS가 해주지만,
'그럼 그게 뭘 해주는 건지?'를 모르면 깊이가 없다고 느꼈습니다.

직접 kubeadm으로 구축하면서:
- kubelet이 systemd로 어떻게 관리되는지
- etcd가 어떻게 클러스터 상태를 저장하는지
- 인증서 갱신이 왜 필요한지
이런 것들을 체감할 수 있었고,

결과적으로 EKS를 사용할 때도 '이 옵션이 왜 필요한지'를
더 정확하게 이해하게 되었습니다."
```

---

#### Q14. "AI를 어떻게 활용하나요?"

**답변 가이드**:
```
"AI를 적극 활용합니다. 다만 역할을 명확히 구분합니다.

AI가 하는 것: 정보 제공, 코드 스니펫 참조, 대안 분석
내가 하는 것: 설계 결정, 트레이드오프 판단, 최종 검증

예를 들어 'Cilium vs Calico' 선택에서:
- AI에게 두 CNI의 장단점을 물어봄
- 하지만 '우리 환경에서는 eBPF 성능 + NetworkPolicy L3/L4가 중요하니 Cilium'
  이라는 판단은 내가 함
- 그 근거를 ADR(Architecture Decision Record)로 기록

YAML 작성도 AI를 참조하지만, 왜 이렇게 동작하는지 이해한 후에 적용합니다.
Ansible은 AI 도움이 많았고, 솔직히 인정합니다."
```

---

#### Q15. "실패 경험을 말해주세요"

**답변 가이드**:
```
"SLO 77.6% 사례입니다.

SLI/SLO 체계를 직접 설계하고 대시보드까지 만들었는데,
실제 30일 Availability를 측정하니 77.6%가 나왔습니다.
99% 목표에 한참 못 미치는 수치였습니다.

이것이 의미 있는 이유는:
- SLO 체계를 만든 것 자체가 성과이지만
- 실제로 측정하니 '생각보다 좋지 않았다'는 것을 발견한 것도 성과
- 측정하지 않았다면 '아마 잘 되고 있겠지'로 넘어갔을 것

현재 원인 분석 중이며, 홈랩 특성상 노드 셧다운(VM 호스팅 PC 재시작)이
Availability에 크게 영향을 미친 것으로 보고 있습니다.

이 경험에서 배운 것: '성과를 잘 만들었다'보다 '문제를 발견할 수 있는 체계를 만들었다'가
실제 운영에서 더 중요합니다."
```

---

## 면접 전 최종 체크리스트

```
회사 이해:
  [ ] AGATHA/ARTHUR/DASHIELL 제품 설명 가능?
  [ ] MITRE ATT&CK PRE-ATT&CK 설명 가능?
  [ ] C2 서버 탐지 원리 기본 설명 가능?
  [ ] 회사 최근 뉴스 (안다리엘, 킴수키 사례) 언급 가능?

내 경험 정리:
  [ ] K8s 220일 운영 — 구체적 수치와 함께 설명 가능?
  [ ] DevSecOps 7단계 — 각 단계 설명 가능?
  [ ] 트러블슈팅 3건 — 증상→원인→해결 구조로 설명 가능?
  [ ] 보안 경험 — Falco/Wazuh/kube-bench 수치와 함께?

태도:
  [ ] Python 부족 → 솔직하게 인정 + 성장 의지?
  [ ] AI 활용 → 솔직하게 + 설계 결정은 본인이?
  [ ] 스타트업 → 왜 여기인가 명확하게?

역질문 준비:
  [ ] "현재 인프라 구성이 어떻게 되어 있나요?"
  [ ] "CTI 데이터 파이프라인은 어떤 기술 스택을 사용하나요?"
  [ ] "DevOps 엔지니어에게 가장 기대하는 첫 3개월 목표가 있나요?"
  [ ] "팀의 기술 방향성이나 앞으로 도입하고 싶은 기술이 있나요?"
```

---

## 변경 이력
- 2026-03-03 v1.0.0: 초안 작성 (15개 예상 질문 + 답변 가이드 + 체크리스트)
