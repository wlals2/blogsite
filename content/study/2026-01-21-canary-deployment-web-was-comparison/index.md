---
title: "Canary 배포 전략: WEB vs WAS 비교 분석"
date: 2026-01-21
description: "Hugo 정적 블로그와 Spring Boot API의 Canary 배포 전략 차이와 설계 철학"
tags: ["canary-deployment", "argo-rollouts", "istio", "kubernetes", "web", "was"]
categories: ["study"]
---

## 개요

동일한 Canary 배포 전략을 사용하지만, WEB(정적 사이트)와 WAS(Spring Boot API)의 특성에 맞춰 다르게 설계:

| 서비스 | 유형 | Canary 전략 | 설계 철학 |
|--------|------|-------------|----------|
| **WEB** | Hugo 정적 블로그 | 10% → 50% → 90% → 100% (30초 간격) | 속도 우선, 빠른 검증 |
| **WAS** | Spring Boot API | 20% → 50% → 80% → 100% (1분 간격) | 안정성 우선, 느린 검증 |

**주요 차이점**:
- ✅ 가중치: WEB 10% vs WAS 20% 시작
- ✅ 대기 시간: WEB 30초 vs WAS 1분
- ✅ Traffic Mirroring: WEB만 사용
- ✅ Circuit Breaking: WEB만 사용

---

## 1. Canary 배포 전략 비교

### WEB (Hugo Static Site)

**Rollout 설정**:
```yaml
# web-rollout.yaml
strategy:
  canary:
    steps:
    - setWeight: 10   # 1단계: 10% 카나리
    - pause: {duration: 30s}
    - setWeight: 50   # 2단계: 50% 카나리
    - pause: {duration: 30s}
    - setWeight: 90   # 3단계: 90% 카나리
    - pause: {duration: 30s}
    - setWeight: 100  # 4단계: 100% 완료
```

**배포 시간**: 총 1.5분
- 10% → 30초 대기
- 50% → 30초 대기
- 90% → 30초 대기
- 100% 완료

**설계 이유**:
1. **정적 파일**: HTML/CSS/JS는 즉시 확인 가능
2. **빠른 롤백**: 문제 발생 시 즉시 롤백 (비용 낮음)
3. **작은 가중치 시작**: 10%로 초기 문제 빠르게 감지

### WAS (Spring Boot API)

**Rollout 설정**:
```yaml
# was-rollout.yaml
strategy:
  canary:
    steps:
    - setWeight: 20   # 1단계: 20% 카나리
    - pause: {duration: 1m}
    - setWeight: 50   # 2단계: 50% 카나리
    - pause: {duration: 1m}
    - setWeight: 80   # 3단계: 80% 카나리
    - pause: {duration: 1m}
    # 4단계: 100% 자동 전환
```

**배포 시간**: 총 3분
- 20% → 1분 대기
- 50% → 1분 대기
- 80% → 1분 대기
- 100% 완료

**설계 이유**:
1. **DB 연동 API**: 쿼리 성능, 비즈니스 로직 검증 필요
2. **느린 롤백**: DB 변경이 있을 수 있어 신중하게 배포
3. **큰 가중치 시작**: 20%로 통계적으로 유의미한 트래픽 확보

---

## 2. VirtualService 설정 비교

### WEB VirtualService (헤더 기반 라우팅 + Traffic Mirroring)

**설정**:
```yaml
# web-virtualservice.yaml
http:
# Route 1: 관리자 테스트 (헤더 기반)
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

# Route 2: 일반 트래픽 (Rollouts가 weight 조정)
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
  
  # Traffic Mirroring (Shadow Traffic)
  mirror:
    host: web-service
    subset: canary
  mirrorPercentage:
    value: 100.0  # stable 트래픽 100%를 canary에 복사
  
  retries:
    attempts: 3
    perTryTimeout: 2s
  timeout: 10s
```

**주요 기능**:

| 기능 | 목적 | 사용 방법 |
|------|------|----------|
| **헤더 기반 라우팅** | 관리자 먼저 테스트 | `curl -H "x-canary-test: true"` |
| **Traffic Mirroring** | 무위험 검증 | stable 응답만 클라이언트 전달 |
| **Retry 3회** | 일시적 오류 복구 | nginx 재시작 대응 |
| **Timeout 10s** | 무한 대기 방지 | nginx 응답 5s + 여유 5s |

**Traffic Mirroring 동작**:
```
사용자 요청
  ↓
stable Pod → 응답 (클라이언트에 전달) ✅
  │
  └─ (복사) → canary Pod → 응답 (로그만, 버림)
```

### WAS VirtualService (단순 라우팅)

**설정**:
```yaml
# was-retry-timeout.yaml
http:
- name: primary
  route:
  - destination:
      host: was-service
      subset: stable
    weight: 100
  - destination:
      host: was-service
      subset: canary
    weight: 0
  
  retries:
    attempts: 3
    perTryTimeout: 2s
    retryOn: 5xx,reset,connect-failure,refused-stream
  timeout: 5s
```

**주요 차이**:

| 항목 | WEB | WAS | 이유 |
|------|-----|-----|------|
| **헤더 기반 라우팅** | ✅ | ❌ | WAS는 nginx 뒤에 있어 불필요 |
| **Traffic Mirroring** | ✅ | ❌ | DB 변경 위험 (Shadow 불가) |
| **Timeout** | 10s | 5s | WAS는 nginx보다 빨라야 함 |

**WAS에서 Traffic Mirroring 제거한 이유**:
1. **DB 부작용**: Shadow 트래픽이 INSERT/UPDATE 실행 → 데이터 중복
2. **비용**: DB 쿼리 2배 실행
3. **대안**: 20% 실제 트래픽으로 검증

---

## 3. DestinationRule 비교

### WEB DestinationRule (Circuit Breaking 포함)

```yaml
# web-destinationrule.yaml
spec:
  host: web-service
  trafficPolicy:
    tls:
      mode: ISTIO_MUTUAL
    connectionPool:
      http:
        http1MaxPendingRequests: 100
        http2MaxRequests: 100
      tcp:
        maxConnections: 100  # nginx 과부하 방지
    loadBalancer:
      simple: ROUND_ROBIN
    outlierDetection:  # Circuit Breaking
      consecutive5xxErrors: 5      # 5번 연속 5xx
      interval: 10s
      baseEjectionTime: 30s         # 30초 제외
      maxEjectionPercent: 50        # 최대 50% 제외
      minHealthPercent: 30          # 최소 30% 활성
  
  subsets:
  - name: stable
  - name: canary
```

**Circuit Breaking 효과**:
- 장애 Pod 5회 5xx 에러 → 30초간 격리
- 최대 50% Pod만 제외 (가용성 보장)
- 최소 30% Pod는 항상 활성

### WAS DestinationRule (Circuit Breaking 없음)

```yaml
# was-destinationrule.yaml
spec:
  host: was-service
  trafficPolicy:
    tls:
      mode: ISTIO_MUTUAL
    connectionPool:
      http:
        http1MaxPendingRequests: 100
    loadBalancer:
      simple: ROUND_ROBIN
  
  subsets:
  - name: stable
  - name: canary
```

**WAS에서 Circuit Breaking 제거한 이유**:
1. **HPA 자동 스케일**: CPU 70% 기준 2-10 replicas 자동 조정
2. **Spring Boot 자체 복원력**: Tomcat Thread Pool, Connection Pool
3. **단순화**: Istio + HPA 중복 방지

---

## 4. 리소스 제한 비교

### WEB (정적 파일 서빙)

```yaml
resources:
  requests:
    cpu: 100m      # 0.1 CPU
    memory: 128Mi
  limits:
    cpu: 200m      # 0.2 CPU
    memory: 256Mi
```

- CPU/Memory 사용량 낮음
- HPA 없음 (고정 2 replicas)

### WAS (Spring Boot API)

```yaml
resources:
  requests:
    cpu: 250m      # 0.25 CPU
    memory: 512Mi
  limits:
    cpu: 500m      # 0.5 CPU
    memory: 1Gi
```

- CPU/Memory 사용량 높음
- HPA 연동 (2-10 replicas)

---

## 5. Health Check (Probe) 비교

### WEB Probes (빠른 시작)

```yaml
livenessProbe:
  httpGet:
    path: /health
    port: 80
  initialDelaySeconds: 10   # 10초 대기
  periodSeconds: 10
  
readinessProbe:
  httpGet:
    path: /health
    port: 80
  initialDelaySeconds: 5    # 5초 대기
  periodSeconds: 5
```

- nginx는 즉시 준비됨
- 짧은 주기로 빠른 장애 감지

### WAS Probes (느린 시작)

```yaml
livenessProbe:
  httpGet:
    path: /actuator/health
    port: 8080
  initialDelaySeconds: 60   # 60초 대기
  periodSeconds: 10
  
readinessProbe:
  httpGet:
    path: /actuator/health
    port: 8080
  initialDelaySeconds: 50   # 50초 대기
  periodSeconds: 5
```

- Spring Boot + DB 초기화 시간 필요
- `/actuator/health`로 DB 연결 상태 확인

---

## 6. 배포 흐름 비교

### WEB 배포

```
1. Git Push (main)
  ↓
2. GitHub Actions
  ├─ Hugo Build
  ├─ Docker Image (v20)
  └─ GHCR Push
  ↓
3. GitOps Manifest Update
  └─ yq로 web-rollout.yaml 수정
  ↓
4. ArgoCD Auto-Sync (3분)
  ↓
5. Canary 배포 (1.5분)
  ├─ 10% (30초)
  ├─ 50% (30초)
  ├─ 90% (30초)
  └─ 100% 완료
  ↓
6. Cloudflare 캐시 퍼지
  ↓
총 배포 시간: 5-7분
```

### WAS 배포

```
1. 수동 트리거 (workflow_dispatch)
  ↓
2. GitHub Actions
  ├─ Maven Build
  ├─ Docker Image (v9)
  └─ GHCR Push
  ↓
3. GitOps Manifest Update
  └─ yq로 was-rollout.yaml 수정
  ↓
4. ArgoCD Auto-Sync (3분)
  ↓
5. Canary 배포 (3분)
  ├─ 20% (1분)
  ├─ 50% (1분)
  ├─ 80% (1분)
  └─ 100% 완료
  ↓
총 배포 시간: 7-10분
```

**주요 차이**:
- WEB: 자동 트리거 + 빠른 배포
- WAS: 수동 트리거 + 느린 배포 (안정성)

---

## 7. 설계 철학 요약

### WEB (Frontend) - 속도 우선

| 설계 요소 | 설정 | 목적 |
|----------|------|------|
| **Canary 가중치** | 10% 시작 | 빠른 문제 감지 |
| **대기 시간** | 30초 | 즉시 확인 가능 |
| **Traffic Mirroring** | ✅ 사용 | 무위험 검증 |
| **Circuit Breaking** | ✅ 사용 | 장애 Pod 격리 |
| **헤더 라우팅** | ✅ 사용 | 관리자 선 검증 |

**철학**: 정적 파일이라 빠르게 배포하고 빠르게 롤백

### WAS (Backend) - 안정성 우선

| 설계 요소 | 설정 | 목적 |
|----------|------|------|
| **Canary 가중치** | 20% 시작 | 통계적 유의미성 |
| **대기 시간** | 1분 | DB 쿼리 검증 |
| **Traffic Mirroring** | ❌ 제거 | DB 부작용 방지 |
| **Circuit Breaking** | ❌ 제거 | HPA로 대체 |
| **헤더 라우팅** | ❌ 제거 | nginx 뒤라 불필요 |

**철학**: DB 연동 API라 느리지만 안전하게 배포

---

## 8. ArgoCD ignoreDifferences

**공통 설정** (Rollouts 동적 레이블 무시):
```yaml
ignoreDifferences:
# WEB
- group: networking.istio.io
  kind: DestinationRule
  name: web-dest-rule
  jsonPointers:
  - /spec/subsets/0/labels  # stable
  - /spec/subsets/1/labels  # canary

# WAS
- group: networking.istio.io
  kind: DestinationRule
  name: was-dest-rule
  jsonPointers:
  - /spec/subsets/0/labels
  - /spec/subsets/1/labels
```

**왜 필요한가?**
1. Argo Rollouts가 `rollouts-pod-template-hash` 레이블 자동 추가
2. Git Manifest에는 이 레이블 없음
3. ArgoCD가 OutOfSync로 인식하는 것 방지

---

## 9. 개선 가능 항목

### P0 (우선순위 높음)
- [ ] WAS AnalysisTemplate 추가 (에러율 기반 자동 롤백)
- [ ] WEB AnalysisTemplate 추가 (Prometheus 메트릭 연동)

### P1 (우선순위 중간)
- [ ] WAS Circuit Breaking 재검토 (HPA + Circuit Breaking 병행)
- [ ] WEB Canary 가중치 조정 (10% → 20%)

### P2 (우선순위 낮음)
- [ ] Blue-Green 배포 병행 (대규모 변경 시)
- [ ] Feature Flag 연동 (애플리케이션 레벨 Canary)

---

**작성일**: 2026-01-21
**태그**: canary-deployment, argo-rollouts, istio, web, was
**관련 문서**:
- [GitOps CI/CD 파이프라인](/study/2026-01-20-gitops-cicd-pipeline/)
- [Istio Service Mesh 아키텍처](/study/2026-01-22-istio-service-mesh-architecture/)
