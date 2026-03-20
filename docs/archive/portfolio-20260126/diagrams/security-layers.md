# Slide 11: Security Architecture

> **Multi-Layer 보안 모델 (5-Layer Defense)**

---

## Mermaid Diagram

```mermaid
flowchart TD
    Internet[🌐 Internet<br/>악의적 트래픽]

    L7_CDN[🛡️ Layer 7: Cloudflare WAF<br/>• SQL Injection 차단<br/>• XSS 차단<br/>• DDoS 방어<br/>• Bot 탐지]

    L7_Istio[🔐 Layer 7: Istio mTLS<br/>• Service 간 암호화 (PERMISSIVE)<br/>• AuthorizationPolicy<br/>• JWT Validation]

    L4_Cilium[🚧 Layer 4: Cilium NetworkPolicy<br/>• Pod 간 트래픽 제어<br/>• L7 Protocol 인식<br/>• Falco 통합]

    Runtime[⚡ Runtime: Falco IDS/IPS<br/>• syscall 모니터링 (eBPF)<br/>• Talon 자동 격리<br/>• NetworkPolicy 생성]

    App[🔒 Application: SecurityContext<br/>• runAsNonRoot<br/>• drop ALL capabilities<br/>• readOnlyRootFilesystem<br/>• Private GHCR]

    Pod[📦 Application Pod<br/>WEB, WAS, MySQL]

    Internet -->|공격 시도| L7_CDN
    L7_CDN -->|필터링 통과| L7_Istio
    L7_Istio -->|mTLS 검증| L4_Cilium
    L4_Cilium -->|NetworkPolicy 허용| Runtime
    Runtime -->|정상 syscall| App
    App -->|보안 제약 준수| Pod

    L7_CDN -.->|차단| Internet
    L7_Istio -.->|차단| Internet
    L4_Cilium -.->|DROP| Internet
    Runtime -.->|격리| Pod
    App -.->|실행 불가| Pod

    style Internet fill:#ff6b6b
    style L7_CDN fill:#ffd700
    style L7_Istio fill:#87ceeb
    style L4_Cilium fill:#98fb98
    style Runtime fill:#ffb6c1
    style App fill:#dda0dd
    style Pod fill:#90ee90
```

---

## 5-Layer 보안 모델

### Layer 7: Cloudflare WAF (외부)

| 공격 유형 | 방어 방법 | 결과 |
|----------|----------|------|
| **SQL Injection** | WAF 규칙 | 차단 |
| **XSS** | WAF 규칙 | 차단 |
| **DDoS** | Cloudflare 자동 방어 | 무제한 |
| **Bot** | Cloudflare Bot Management | 차단 |

---

### Layer 7: Istio mTLS (Service Mesh)

```yaml
# PeerAuthentication (PERMISSIVE)
spec:
  mtls:
    mode: PERMISSIVE  # 평문 + mTLS 허용
```

**적용 범위**:
- ✅ Service ↔ Service: mTLS 가능 (선택)
- ⚠️ Gateway → Service: 평문 (필수)
- ❌ MySQL: Mesh 제외 (JDBC 호환)

---

### Layer 4: Cilium NetworkPolicy

```yaml
# MySQL 접근 제어
apiVersion: cilium.io/v2
kind: CiliumNetworkPolicy
metadata:
  name: mysql-netpol
spec:
  endpointSelector:
    matchLabels:
      app: mysql
  ingress:
  - fromEndpoints:
    - matchLabels:
        app: was
    toPorts:
    - ports:
      - port: "3306"
```

**특징**:
- ✅ L7 Protocol 인식 (HTTP, gRPC)
- ✅ Falco와 통합 (자동 규칙 생성)
- ✅ Hubble UI로 시각화

---

### Runtime: Falco IDS/IPS

#### 탐지 규칙

| 규칙 | Critical | 대응 |
|------|---------|------|
| **Privilege Escalation** | ✅ | Pod 즉시 격리 |
| **Unexpected File Write** | ✅ | NetworkPolicy 추가 |
| **Shell in Container** | ⚠️ | 알람 |
| **Suspicious Network** | ⚠️ | 로그 |

#### Falco Talon 자동 대응

```
1. Falco: Critical 이벤트 탐지
   ├─ Privilege Escalation
   └─ /etc/passwd 수정 시도
   ↓
2. Talon: 자동 대응 시작
   ├─ Pod 격리 (NetworkPolicy Deny All)
   ├─ Pod Label 추가 (quarantine: "true")
   └─ Slack 알림
   ↓
3. 결과: 악성 활동 차단 ✅
```

---

### Application: SecurityContext

```yaml
# WEB Pod SecurityContext
spec:
  securityContext:
    runAsNonRoot: true
    runAsUser: 1000
    runAsGroup: 3000
    fsGroup: 2000
    seccompProfile:
      type: RuntimeDefault
  containers:
  - name: web
    securityContext:
      allowPrivilegeEscalation: false
      capabilities:
        drop:
        - ALL
      readOnlyRootFilesystem: true
```

**제약 사항**:
- ❌ Root 실행 금지
- ❌ Privilege Escalation 금지
- ❌ 모든 Capabilities 제거
- ✅ 읽기 전용 Root Filesystem

---

## Private GHCR 접근 제어

### imagePullSecrets

```yaml
# GHCR Secret
apiVersion: v1
kind: Secret
metadata:
  name: ghcr-secret
type: kubernetes.io/dockerconfigjson
data:
  .dockerconfigjson: <base64-encoded-PAT>

---
# Deployment
spec:
  template:
    spec:
      imagePullSecrets:
      - name: ghcr-secret
```

**PAT 관리**:
- 🔄 90일마다 갱신
- 🔐 최소 권한 (read:packages)
- 📝 Git 관리 (Sealed Secrets)

---

## 실제 보안 이벤트

### Case 1: Falco Talon 자동 격리 (2025-12-18)

```
15:30:00 - test-pod에서 /etc/passwd 수정 시도
15:30:05 - Falco Critical 알람
15:30:10 - Talon 자동 대응 시작
  ├─ NetworkPolicy Deny All 적용
  ├─ Pod Label: quarantine="true"
  └─ Slack 알림 발송
15:30:15 - Pod 격리 완료 (5초)

사용자 영향: 0 (격리 완료)
수동 개입: 불필요 (자동 처리)
```

---

### Case 2: Cloudflare WAF 차단 (매일)

```
일일 통계 (평균)
├─ Total Requests: 20,000
├─ WAF Blocked: 150 (0.75%)
│   ├─ SQL Injection: 50
│   ├─ XSS: 30
│   ├─ Bot: 50
│   └─ DDoS: 20
└─ Allowed: 19,850
```

---

## 보안 지표

### Falco 탐지 현황 (58일)

| Level | 횟수 | 비율 |
|-------|------|------|
| **Critical** | 5 | 0.4% |
| **Warning** | 150 | 12.5% |
| **Info** | 1,045 | 87.1% |
| **합계** | 1,200 | 100% |

**False Positive**: 10% → 5% (규칙 튜닝)

---

### SSL/TLS 등급

| 평가 항목 | 등급 | 비고 |
|----------|------|------|
| **SSL Labs** | A+ | Cloudflare SSL |
| **TLS 버전** | TLS 1.3 | 최신 |
| **HSTS** | ✅ | max-age=31536000 |
| **인증서** | ✅ | Cloudflare Universal SSL |

---

## 개선 계획

### 단기 (1-2개월)
- ⏳ Istio mTLS STRICT 모드 전환
- ⏳ Falco 규칙 커스터마이징
- ⏳ AuthorizationPolicy (IP 기반 제어)

### 장기 (6개월+)
- ⏳ OPA (Open Policy Agent) 통합
- ⏳ Vulnerability Scanning (Trivy)
- ⏳ SIEM 연동 (Elastic Security)

---

**핵심 메시지**: **Multi-Layer Defense** - 5단계 보안으로 다층 방어
