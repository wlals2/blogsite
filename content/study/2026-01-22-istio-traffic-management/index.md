---
title: "Istio Traffic Management 실전 가이드"
date: 2026-01-22
description: "VirtualService와 DestinationRule을 활용한 Retry, Timeout, Circuit Breaking, Traffic Mirroring 구현"
tags: ["istio", "kubernetes", "traffic-management", "circuit-breaking", "canary"]
categories: ["study"]
---

## 개요

이 글은 실제 blog-system에서 구현한 **Istio Traffic Management** 설정을 정리한 것입니다.

**구현된 기능:**
- Retry: 3회 자동 재시도
- Timeout: 10초 제한
- Circuit Breaking: 5xx 5회 → 30초 격리
- Traffic Mirroring: canary shadow traffic
- 헤더 기반 카나리 라우팅

---

## VirtualService 설정

### 전체 구조

```yaml
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: web-vsvc
  namespace: blog-system
spec:
  hosts:
  - web-service
  http:
  # Route 1: 관리자 트래픽 (헤더 기반 카나리)
  - name: canary-testing
    match:
    - headers:
        x-canary-test:
          exact: "true"
    route:
    - destination:
        host: web-service
        subset: canary
      weight: 100
    retries:
      attempts: 2
      perTryTimeout: 3s
    timeout: 15s

  # Route 2: 일반 트래픽
  - name: primary
    route:
    - destination:
        host: web-service
        subset: stable
      weight: 100
    - destination:
        host: web-service
        subset: canary
      weight: 0
    retries:
      attempts: 3
      perTryTimeout: 2s
      retryOn: 5xx,reset,connect-failure,refused-stream
    timeout: 10s
    mirror:
      host: web-service
      subset: canary
    mirrorPercentage:
      value: 100.0
```

---

## Retry 정책

### 설정

```yaml
retries:
  attempts: 3              # 3회 재시도
  perTryTimeout: 2s        # 재시도당 2초
  retryOn: 5xx,reset,connect-failure,refused-stream
```

### 효과

```
Request 1: connect failure → Retry 1: 5xx → Retry 2: 200 OK
         ↓                           ↓              ↓
   (실패)                       (실패)         (성공)

사용자는 200 OK만 경험 (내부 재시도 숨김)
```

### 트레이드오프

| 항목 | 장점 | 단점 |
|------|------|------|
| **Retry** | 일시적 오류 복구 | 지연 증가 (최대 6s) |

---

## Timeout 정책

### 설정

```yaml
timeout: 10s
```

### 효과

```
Request 1: 5s 소요 → 200 OK
Request 2: 12s 소요 → 10s timeout → 504 Gateway Timeout

10초 이상 대기하지 않음 → 리소스 절약
```

---

## Circuit Breaking (DestinationRule)

### 설정

```yaml
apiVersion: networking.istio.io/v1beta1
kind: DestinationRule
metadata:
  name: web-dest-rule
  namespace: blog-system
spec:
  host: web-service
  trafficPolicy:
    connectionPool:
      http:
        http1MaxPendingRequests: 100
        http2MaxRequests: 100
        maxRequestsPerConnection: 10
      tcp:
        maxConnections: 100
    outlierDetection:
      consecutive5xxErrors: 5      # 5번 연속 5xx
      interval: 10s                 # 10초마다 체크
      baseEjectionTime: 30s         # 30초간 제외
      maxEjectionPercent: 50        # 최대 50% Pod 제외
      minHealthPercent: 30          # 최소 30% Pod 유지
```

### 동작

```
Pod A (장애) ← 5xx 5번 → 30초간 격리
Pod B (정상) ← 트래픽 전달 → 사용자 정상 응답
Pod C (정상) ← 트래픽 전달 → 사용자 정상 응답

30초 후:
Pod A ← 트래픽 재전달 (자동 복구 시도)
```

---

## Traffic Mirroring

### 설정

```yaml
mirror:
  host: web-service
  subset: canary
mirrorPercentage:
  value: 100.0
```

### 동작

```
사용자 요청 → stable (실제 응답) → 사용자
          ↓
          ├─ canary (shadow) → 응답 버림
          └─ Prometheus/Grafana로 메트릭 수집
```

### 효과

| 배포 방식 | 사용자 영향 | 테스트 범위 |
|-----------|-------------|-------------|
| **Canary (10%)** | 10% 사용자 | 실제 트래픽 |
| **Mirroring** | **0% 사용자** | 실제 트래픽 |

---

## 헤더 기반 카나리 라우팅

### 사용법

```bash
# 일반 사용자 (stable)
curl https://blog.jiminhome.shop/api/posts

# 관리자 (canary)
curl -H "x-canary-test: true" https://blog.jiminhome.shop/api/posts
```

### 효과

- 관리자가 canary 버전 의도적 테스트 가능
- 일반 사용자는 stable 버전만 접근
- Argo Rollouts weight와 독립적으로 동작

---

## 검증

### Retry/Timeout 확인

```bash
kubectl get virtualservice -n blog-system web-vsvc \
  -o jsonpath='{.spec.http[1].retries.attempts}'
# 3

kubectl get virtualservice -n blog-system web-vsvc \
  -o jsonpath='{.spec.http[1].timeout}'
# 10s
```

### Circuit Breaking 확인

```bash
kubectl get destinationrule -n blog-system web-dest-rule \
  -o jsonpath='{.spec.trafficPolicy.outlierDetection.consecutive5xxErrors}'
# 5
```

### Traffic Mirroring 확인

```bash
kubectl get virtualservice -n blog-system web-vsvc \
  -o jsonpath='{.spec.http[1].mirror}'
# {"host":"web-service","subset":"canary"}
```

---

## ArgoCD selfHeal 주의사항

kubectl로 직접 수정하면 ArgoCD가 Git 상태로 되돌립니다.

**올바른 순서:**
```bash
# 1. Git 커밋 먼저
git add blog-system/web-virtualservice.yaml
git commit -m "feat: Add advanced traffic management"
git push

# 2. ArgoCD 동기화 대기 또는 수동 sync
argocd app sync blog-system
```

---

## 참고 자료

- [Istio Traffic Management](https://istio.io/latest/docs/concepts/traffic-management/)
- 내부 문서: `docs/istio/COMPLETE-ISTIO-ARCHITECTURE.md`
