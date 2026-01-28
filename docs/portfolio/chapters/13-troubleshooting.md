# Slide 13: Troubleshooting & Lessons Learned

> **실전 문제 해결 경험 (4가지 핵심 이슈)**

---

## Issue 1: Istio mTLS STRICT 모드 실패

### 문제
```
Istio mTLS STRICT 적용 → 전체 서비스 503 에러 (5분 다운타임)
```

### 원인
- Istio Gateway → Service 연결 시 mTLS 인증서 없음
- Gateway Pod는 Envoy Sidecar 없음 (Proxy 자체)

### 해결
```yaml
# PeerAuthentication: STRICT → PERMISSIVE
spec:
  mtls:
    mode: PERMISSIVE  # 평문 + mTLS 모두 허용
```

### 교훈
- ✅ Gateway → Service는 항상 평문 또는 PERMISSIVE
- ✅ Kiali로 mTLS 상태 즉시 시각화
- ✅ 점진적 적용 (PERMISSIVE → 검증 → STRICT)

---

## Issue 2: Cilium NetworkPolicy 과도 적용

### 문제
```
WAS → MySQL 연결 끊김 (3시간 영향)
Hubble UI: DROPPED 패킷 확인
```

### 원인
```yaml
# NetworkPolicy Label 불일치
fromEndpoints:
- matchLabels:
    app: was
    version: v1  # ❌ WAS Pod에 version 레이블 없음
```

### 해결
```yaml
# version 레이블 제거
fromEndpoints:
- matchLabels:
    app: was  # ✅ 최소 Label만 사용
```

### 교훈
- ✅ 최소 권한 원칙 (Allow All → 점진적 제한)
- ✅ Hubble UI로 NetworkPolicy DROP 즉시 확인
- ✅ Argo Rollouts는 `rollouts-pod-template-hash` 사용

---

## Issue 3: MySQL Istio Mesh 포함 시 JDBC 연결 실패

### 문제
```
WAS → MySQL JDBC 연결 지속 끊김
Spring Boot: "Communications link failure"
```

### 원인
- Envoy Sidecar가 MySQL Wire Protocol 이해 못 함
- HTTP/gRPC만 지원, Binary Protocol 파싱 실패

### 해결
```yaml
# MySQL Pod에서 Sidecar 제외
metadata:
  annotations:
    sidecar.istio.io/inject: "false"
```

### 교훈
- ✅ Istio Mesh = HTTP/gRPC 서비스만
- ✅ MySQL, Redis, Kafka는 Mesh 제외
- ✅ Protocol Awareness 중요

---

## Issue 4: Nginx Ingress + Istio Gateway 중복 라우팅

### 문제
```
Cloudflare → MetalLB → Nginx Ingress → Istio Gateway → Pod
                         ↓ 중복 Hop   ↓
                      L7 라우팅     L7 라우팅
```

**성능 측정**:
| 메트릭 | Before | After | 개선 |
|--------|--------|-------|------|
| P50 | 60ms | 45ms | **-25%** |
| P95 | 180ms | 120ms | **-33%** |

### 해결
```yaml
# 2026-01-24: Nginx Ingress 완전 제거
# Istio Gateway에 LoadBalancer IP 직접 할당
annotations:
  metallb.universe.tf/loadBalancerIPs: 192.168.1.200
```

### 교훈
- ✅ 아키텍처 단순화 = 성능 향상
- ✅ 메트릭 기반 의사결정
- ✅ 점진적 마이그레이션 (추가 → 검증 → 제거)

---

## 핵심 교훈 요약

### 1. Observability 도구 활용

| 도구 | 용도 | 핵심 기능 |
|------|------|----------|
| **Kiali** | Istio 시각화 | mTLS 상태 즉시 확인 |
| **Hubble UI** | Cilium 플로우 | NetworkPolicy DROP 원인 |
| **Grafana** | 메트릭 대시보드 | Before/After 비교 |

### 2. 점진적 적용 원칙

```
1. 최소 권한으로 시작
   └─ Istio PERMISSIVE, NetworkPolicy Allow All

2. 검증 단계
   └─ Kiali, Hubble로 트래픽 확인

3. 점진적 제한
   └─ STRICT mTLS, NetworkPolicy Deny

4. 롤백 준비
   └─ Git Revert, ArgoCD Rollback
```

### 3. 문제 해결 프로세스

```
1. 현상 파악 (사용자 영향, 에러 메시지)
   ↓
2. 관찰 도구 (Kiali, Hubble, Grafana, Loki)
   ↓
3. 가설 수립 (로그, 메트릭 기반)
   ↓
4. 검증 (임시 수정 → 효과 확인)
   ↓
5. 영구 수정 (Git 커밋, 문서화)
```

---

**핵심 메시지**: 실제 운영 환경에서만 경험할 수 있는 **깊이 있는 트러블슈팅 능력**
