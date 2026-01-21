# WEB vs WAS Canary 배포 설정 비교

> Hugo 정적 블로그(WEB)와 Spring Boot API(WAS)의 Canary 배포 전략 비교

---

## 1. Canary 배포 전략 비교

### WEB (Hugo Static Site)

```yaml
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

**설계 이유:**
- **빠른 배포 (총 1.5분)**: 정적 파일이라 롤백 비용이 낮음
- **작은 가중치 시작 (10%)**: 초기 문제 빠르게 감지
- **짧은 대기 시간 (30초)**: HTML/CSS/JS는 즉시 확인 가능

### WAS (Spring Boot API)

```yaml
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

**설계 이유:**
- **느린 배포 (총 3분)**: DB 연동 API는 안정성 우선
- **큰 가중치 시작 (20%)**: 통계적으로 유의미한 트래픽 필요
- **긴 대기 시간 (1분)**: DB 쿼리, 비즈니스 로직 검증 시간 확보

---

## 2. VirtualService 설정 비교

### WEB VirtualService (web-vsvc)

```yaml
http:
# Route 1: 관리자 테스트 (헤더 기반 카나리 라우팅)
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

# Route 2: 일반 트래픽 (Argo Rollouts가 weight 조정)
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
    value: 100.0  # stable 트래픽의 100%를 canary에 복사
```

**주요 기능:**

| 기능 | 목적 | 사용 방법 |
|------|------|----------|
| **헤더 기반 라우팅** | 관리자가 먼저 카나리 테스트 | `curl -H "x-canary-test: true"` |
| **Traffic Mirroring** | 무위험 카나리 검증 | stable 응답만 클라이언트에 전달, canary는 로그만 |
| **Retry (3회)** | 일시적 네트워크 오류 복구 | nginx 재시작, Pod termination 대응 |
| **Timeout (10s)** | 무한 대기 방지 | nginx 응답 5s + 여유 5s |

### WAS VirtualService (was-retry-timeout)

```yaml
http:
- name: primary  # Rollout이 참조
  route:
  - destination:
      host: was-service
      subset: stable
      port:
        number: 8080
    weight: 100
  - destination:
      host: was-service
      subset: canary
      port:
        number: 8080
    weight: 0

  retries:
    attempts: 3
    perTryTimeout: 2s
    retryOn: 5xx,reset,connect-failure,refused-stream

  timeout: 5s
```

**주요 차이점:**

| 항목 | WEB | WAS | 이유 |
|------|-----|-----|------|
| **헤더 기반 라우팅** | ✅ 있음 | ❌ 없음 | WAS는 nginx 뒤에 있어 불필요 |
| **Traffic Mirroring** | ✅ 있음 | ❌ 없음 | WAS는 DB 변경이 있어 Shadow 위험 |
| **Timeout** | 10s | 5s | WAS는 nginx보다 빨라야 함 |
| **Retry perTryTimeout** | 2s | 2s | 동일 (표준 API 응답 시간) |

**WAS에서 헤더 기반 라우팅을 제거한 이유:**
1. **트래픽 흐름**: `Client → Ingress → web-service (nginx) → was-service`
2. nginx가 WAS로 프록시하므로, WAS 레벨에서 헤더 라우팅은 불필요
3. WEB 레벨에서 이미 카나리 테스트 가능

**WAS에서 Traffic Mirroring을 제거한 이유:**
1. **DB 부작용**: Shadow 트래픽이 DB INSERT/UPDATE 실행 시 데이터 중복
2. **비용**: DB 쿼리 2배 실행으로 부하 증가
3. **대안**: Canary 가중치를 20%로 시작하여 실제 트래픽으로 검증

---

## 3. DestinationRule 설정 비교

### WEB DestinationRule

```yaml
spec:
  host: web-service
  trafficPolicy:
    tls:
      mode: ISTIO_MUTUAL  # mTLS
    connectionPool:
      http:
        http1MaxPendingRequests: 100
        http2MaxRequests: 100
        maxRequestsPerConnection: 10
      tcp:
        maxConnections: 100  # nginx 동시 연결 제한
    loadBalancer:
      simple: ROUND_ROBIN
    outlierDetection:  # Circuit Breaking
      consecutive5xxErrors: 5
      interval: 10s
      baseEjectionTime: 30s
      maxEjectionPercent: 50
      minHealthPercent: 30

  subsets:
  - name: stable
  - name: canary
```

**주요 기능:**

| 기능 | 설정값 | 목적 |
|------|--------|------|
| **Circuit Breaking** | 5xx 5회 → 30초 제외 | 장애 Pod 자동 격리 |
| **TCP maxConnections** | 100 | nginx 과부하 방지 |
| **maxEjectionPercent** | 50% | 최대 절반까지만 제외 (가용성 보장) |
| **minHealthPercent** | 30% | 최소 30% Pod는 항상 활성 |

### WAS DestinationRule

```yaml
spec:
  host: was-service
  trafficPolicy:
    tls:
      mode: ISTIO_MUTUAL
    connectionPool:
      http:
        http1MaxPendingRequests: 100
        http2MaxRequests: 100
        maxRequestsPerConnection: 10
    loadBalancer:
      simple: ROUND_ROBIN

  subsets:
  - name: stable
  - name: canary
```

**주요 차이점:**

| 항목 | WEB | WAS | 이유 |
|------|-----|-----|------|
| **Circuit Breaking** | ✅ 있음 | ❌ 없음 | WAS는 HPA로 자동 스케일, Circuit Breaking 불필요 |
| **TCP maxConnections** | 100 | ❌ 없음 | Spring Boot는 Tomcat Thread Pool로 자체 관리 |

**WAS에서 Circuit Breaking을 제거한 이유:**
1. **HPA 자동 스케일**: CPU/Memory 기반 2-10 replicas 자동 조정
2. **Spring Boot 자체 복원력**: Tomcat Thread Pool, Connection Pool이 이미 존재
3. **단순화**: Istio Circuit Breaking + HPA 중복 방지

---

## 4. 리소스 제한 비교

### WEB (nginx)

```yaml
resources:
  requests:
    cpu: 100m      # 0.1 CPU
    memory: 128Mi
  limits:
    cpu: 200m      # 0.2 CPU
    memory: 256Mi
```

- **정적 파일 서빙**: CPU/Memory 사용량 낮음
- **HPA 없음**: 트래픽 변동 적음 (고정 2 replicas)

### WAS (Spring Boot)

```yaml
resources:
  requests:
    cpu: 250m      # 0.25 CPU
    memory: 512Mi
  limits:
    cpu: 500m      # 0.5 CPU
    memory: 1Gi
```

- **DB 쿼리, 비즈니스 로직**: CPU/Memory 사용량 높음
- **HPA 연동**: 2-10 replicas 자동 조정 (CPU 70% 기준)

---

## 5. Probe 설정 비교

### WEB Probes

```yaml
livenessProbe:
  httpGet:
    path: /health
    port: 80
  initialDelaySeconds: 10   # 10초 대기
  periodSeconds: 10
  timeoutSeconds: 5
  failureThreshold: 3

readinessProbe:
  httpGet:
    path: /health
    port: 80
  initialDelaySeconds: 5    # 5초 대기
  periodSeconds: 5
  timeoutSeconds: 3
  failureThreshold: 2
```

- **빠른 시작 (5-10초)**: nginx는 즉시 준비됨
- **짧은 주기 (5-10초)**: 빠른 장애 감지

### WAS Probes

```yaml
livenessProbe:
  httpGet:
    path: /actuator/health
    port: 8080
  initialDelaySeconds: 60   # 60초 대기
  periodSeconds: 10
  timeoutSeconds: 5
  failureThreshold: 3

readinessProbe:
  httpGet:
    path: /actuator/health
    port: 8080
  initialDelaySeconds: 50   # 50초 대기
  periodSeconds: 5
  timeoutSeconds: 3
  failureThreshold: 2
```

- **느린 시작 (50-60초)**: Spring Boot + DB 연결 초기화 시간
- **Spring Actuator**: `/actuator/health`로 DB 연결 상태까지 확인

---

## 6. ArgoCD ignoreDifferences

**공통 설정:**

```yaml
ignoreDifferences:
# WEB DestinationRule
- group: networking.istio.io
  kind: DestinationRule
  name: web-dest-rule
  jsonPointers:
  - /spec/subsets/0/labels  # stable subset
  - /spec/subsets/1/labels  # canary subset

# WAS DestinationRule
- group: networking.istio.io
  kind: DestinationRule
  name: was-dest-rule
  jsonPointers:
  - /spec/subsets/0/labels
  - /spec/subsets/1/labels
```

**왜 필요한가?**

1. **Argo Rollouts의 동적 레이블 관리**
   - Rollout이 배포 시 `rollouts-pod-template-hash` 레이블 자동 추가
   - stable Pod: `rollouts-pod-template-hash: 69b6795bb4`
   - canary Pod: `rollouts-pod-template-hash: f9f55456`

2. **ArgoCD selfHeal 충돌 방지**
   - ArgoCD는 Git 상태로 되돌리려 함 (빈 labels)
   - Rollouts는 동적으로 labels 수정
   - `ignoreDifferences`로 ArgoCD가 무시하도록 설정

---

## 7. 배포 흐름 비교

### WEB 배포

```
1. Git Push (main 브랜치)
   └─> GitHub Actions 트리거

2. Hugo 빌드 + Docker 이미지 생성 (v20)
   └─> GHCR 푸시

3. GitOps Manifest 업데이트
   └─> yq로 web-rollout.yaml 수정

4. ArgoCD Auto-Sync (3분 이내)
   └─> Rollout 이미지 변경 감지

5. Canary 배포 시작 (총 1.5분)
   ├─> 10% 카나리 (30초 대기)
   ├─> 50% 카나리 (30초 대기)
   ├─> 90% 카나리 (30초 대기)
   └─> 100% 완료

6. Cloudflare 캐시 퍼지
   └─> 전체 CDN 캐시 삭제

총 배포 시간: 5-7분
```

### WAS 배포

```
1. 수동 트리거 (workflow_dispatch)
   └─> 로컬 소스 복사 (Git에 없음)

2. Maven 빌드 + Docker 이미지 생성 (v9)
   └─> GHCR 푸시

3. GitOps Manifest 업데이트
   └─> yq로 was-rollout.yaml 수정

4. ArgoCD Auto-Sync (3분 이내)
   └─> Rollout 이미지 변경 감지

5. Canary 배포 시작 (총 3분)
   ├─> 20% 카나리 (1분 대기)
   ├─> 50% 카나리 (1분 대기)
   ├─> 80% 카나리 (1분 대기)
   └─> 100% 완료

총 배포 시간: 7-10분
```

**주요 차이:**
- WEB: Git 관리 + 자동 트리거 + 빠른 배포
- WAS: 로컬 소스 + 수동 트리거 + 느린 배포 (안정성 우선)

---

## 8. 설계 철학 요약

### WEB (Frontend)
- **속도 우선**: 정적 파일이라 빠르게 배포하고 빠르게 롤백
- **무위험 검증**: Traffic Mirroring으로 Shadow 테스트
- **헤더 기반 테스트**: 관리자가 먼저 카나리 검증 후 일반 사용자에게 배포
- **Circuit Breaking**: 장애 Pod 자동 격리로 가용성 보장

### WAS (Backend)
- **안정성 우선**: DB 연동 API라 느리지만 안전하게 배포
- **실제 트래픽 검증**: Mirroring 없이 20%부터 실제 사용자 트래픽으로 검증
- **HPA 기반 복원력**: Circuit Breaking 대신 자동 스케일로 부하 대응
- **긴 검증 시간**: 각 단계마다 1분 대기로 DB 쿼리, 비즈니스 로직 안정성 확보

---

## 9. 개선 가능 항목

### P0 (우선순위 높음)
- [ ] WAS AnalysisTemplate 추가 (에러율 기반 자동 롤백)
- [ ] WEB AnalysisTemplate 추가 (Prometheus 메트릭 연동)
- [ ] 두 서비스 모두 Progressive Delivery (점진적 배포) 자동화

### P1 (우선순위 중간)
- [ ] WAS Circuit Breaking 재검토 (HPA + Circuit Breaking 병행 가능)
- [ ] WEB Canary 가중치 조정 (10% → 20% 시작 고려)
- [ ] WAS Traffic Mirroring 안전 조건 검토 (Read-only API만 Mirror)

### P2 (우선순위 낮음)
- [ ] Blue-Green 배포 전략 병행 (대규모 변경 시 사용)
- [ ] Feature Flag 연동 (애플리케이션 레벨 Canary)

---

**작성일:** 2026-01-21
**버전:** WEB v27, WAS v9
**참고 파일:**
- [web-rollout.yaml](../../k8s-manifests/blog-system/web-rollout.yaml)
- [was-rollout.yaml](../../k8s-manifests/blog-system/was-rollout.yaml)
- [web-virtualservice.yaml](../../k8s-manifests/blog-system/web-virtualservice.yaml)
- [was-retry-timeout.yaml](../../k8s-manifests/blog-system/was-retry-timeout.yaml)
