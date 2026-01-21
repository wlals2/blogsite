# WAS 아키텍처

> Spring Boot + MySQL 게시판 시스템 아키텍처 상세 설명

---

## 전체 시스템 구조

```
┌─────────────────────────────────────────────────────────────────┐
│                         Client (브라우저)                        │
│                  https://blog.jiminhome.shop                    │
└────────────────────────────┬────────────────────────────────────┘
                             │ HTTPS
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│                      Cloudflare CDN                             │
│                    (SSL, DDoS 보호, 캐싱)                        │
└────────────────────────────┬────────────────────────────────────┘
                             │ HTTPS
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Ingress (NGINX Controller)                   │
│                  blog-ingress (blog-system)                     │
│                                                                 │
│  ┌──────────────┐        ┌──────────────┐                      │
│  │ / (블로그)   │        │ /api (TODO)  │                      │
│  │ → web-service│        │ → web-service│                      │
│  └──────────────┘        └──────────────┘                      │
└────────────────────────────┬────────────────────────────────────┘
                             │ HTTP
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│               web-service (ClusterIP :80)                       │
│                     Istio Sidecar                               │
└────────────────────────────┬────────────────────────────────────┘
                             │
              ┌──────────────┴──────────────┐
              │                             │
              ▼                             ▼
┌─────────────────────────┐   ┌─────────────────────────────────┐
│    web Pod (nginx)      │   │    WAS Pod (Spring Boot)        │
│  - Hugo 정적 파일 서빙   │   │  - REST API 처리                 │
│  - /api → WAS 프록시     │   │  - 비즈니스 로직                 │
│  - Istio Sidecar        │   │  - Istio Sidecar                │
└────────────┬────────────┘   └────────────┬────────────────────┘
             │ (현재 프록시 없음)            │ HTTP (mTLS)
             └──────────────┬───────────────┘
                            │
                            ▼
              ┌─────────────────────────────┐
              │  was-service (ClusterIP)    │
              │        :8080                │
              └────────────┬────────────────┘
                           │ HTTP (Istio mTLS)
                           ▼
              ┌─────────────────────────────┐
              │   MySQL Pod (StatefulSet)   │
              │   - PVC: 1Gi                │
              │   - DB: board               │
              │   - Port: 3306              │
              └─────────────────────────────┘
```

---

## 컴포넌트 상세

### 1. WEB (nginx)

**역할:**
- Hugo 정적 파일 서빙 (`/`)
- WAS API 프록시 (`/api/` → was-service) **← 현재 미구현**

**리소스:**
- Replicas: 2 (고정)
- CPU: 100m-200m
- Memory: 128Mi-256Mi
- Image: `ghcr.io/wlals2/blog-web:v28`

**배포 전략:**
- Argo Rollouts (Canary)
- 10% → 50% → 90% → 100%
- 각 단계 30초 대기

**설정:**
```yaml
# web-nginx-config ConfigMap
server {
    listen 80;
    location / {
        root /usr/share/nginx/html;
        try_files $uri $uri/ /index.html;
    }
    # TODO: /api/ 프록시 추가
}
```

### 2. WAS (Spring Boot)

**역할:**
- REST API 제공 (`/api/posts`)
- 비즈니스 로직 처리
- DB 트랜잭션 관리

**리소스:**
- Replicas: 2-10 (HPA)
- CPU: 250m-500m
- Memory: 512Mi-1Gi
- Image: `ghcr.io/wlals2/board-was:v9`

**배포 전략:**
- Argo Rollouts (Canary)
- 20% → 50% → 80% → 100%
- 각 단계 1분 대기

**주요 클래스:**
```java
PostController → PostService → PostRepository → MySQL
     ↓               ↓              ↓
  REST API      비즈니스 로직    JPA/Hibernate
```

**Health Check:**
- Liveness: `/actuator/health` (60초 후, 10초 주기)
- Readiness: `/actuator/health` (50초 후, 5초 주기)

### 3. MySQL

**역할:**
- 게시글 데이터 저장
- `board` 데이터베이스

**리소스:**
- Replicas: 1 (StatefulSet)
- Storage: 1Gi PVC
- Image: `mysql:8.0.44`

**테이블 스키마:**
```sql
CREATE TABLE posts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    content TEXT,
    author VARCHAR(50),
    created_at DATETIME NOT NULL
);
```

**연결 정보:**
```yaml
# was-config ConfigMap
SPRING_DATASOURCE_URL: jdbc:mysql://mysql-service:3306/board
SPRING_DATASOURCE_USERNAME: root
SPRING_DATASOURCE_PASSWORD: (Secret에서 주입)
```

---

## 트래픽 흐름

### 1. 블로그 조회 (`/`)

```
Client → Cloudflare → Ingress → web-service → nginx Pod
                                                  ↓
                                          Hugo 정적 파일 반환
```

### 2. API 호출 (`/api/posts`) - **현재 작동 안 함**

**의도된 흐름:**
```
Client → Cloudflare → Ingress → web-service → nginx Pod
                                                  ↓ (프록시)
                                            was-service → WAS Pod
                                                             ↓
                                                        MySQL Pod
```

**현재 문제:**
```
nginx Pod에서 WAS로 프록시 설정이 없음
→ nginx가 /api 경로를 모름
→ 404 Not Found
```

**해결 방법:**
web-nginx-config ConfigMap에 다음 추가:
```nginx
location /api/ {
    proxy_pass http://was-service:8080;
    proxy_set_header Host $host;
}
```

---

## Istio Service Mesh

### mTLS (Mutual TLS)

**목적:** Pod 간 통신 암호화

**설정:**
```yaml
# mtls-peerauthentication.yaml
spec:
  mtls:
    mode: PERMISSIVE  # mTLS 선택적 (nginx → WAS는 Plain HTTP 가능)

# was-destinationrule.yaml
spec:
  trafficPolicy:
    tls:
      mode: ISTIO_MUTUAL  # WAS 트래픽은 mTLS 사용
```

**문제:**
- 현재 `TLS_error: WRONG_VERSION_NUMBER`
- nginx → WAS가 Plain HTTP인데 mTLS로 전송

**해결 옵션:**
1. nginx → WAS는 mTLS 제외
2. DestinationRule의 `tls.mode: DISABLE`

### Traffic Routing

**VirtualService (was-retry-timeout.yaml):**
```yaml
spec:
  http:
  - name: primary  # Rollout이 참조
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
    timeout: 5s
```

**DestinationRule (was-destinationrule.yaml):**
```yaml
spec:
  subsets:
  - name: stable  # Argo Rollouts가 레이블 자동 관리
  - name: canary
```

---

## 데이터 흐름

### 게시글 작성 (POST /api/posts)

```
1. Client → POST /api/posts
   Body: {"title": "제목", "content": "내용", "author": "지민"}

2. Ingress → web-service:80

3. nginx → was-service:8080  (프록시)

4. WAS → PostController.createPost()
   ├─ @Valid 검증 (title, author 길이)
   └─ PostService.createPost()
       ├─ 비즈니스 로직 (title == null → "제목 없음")
       └─ PostRepository.save()
           └─ Hibernate → INSERT SQL

5. MySQL → INSERT INTO posts (...) VALUES (...)

6. WAS → 201 Created
   Body: {"id": 2, "title": "제목", "content": "내용", ...}

7. Client ← 201 Created
```

### 게시글 조회 (GET /api/posts)

```
1. Client → GET /api/posts

2. WAS → PostController.getAllPosts()
   └─ PostService.getAllPosts()
       └─ PostRepository.findAllByOrderByCreatedAtDesc()
           └─ Hibernate → SELECT * FROM posts ORDER BY created_at DESC

3. MySQL → 결과 반환

4. WAS → 200 OK
   Body: [{"id": 2, ...}, {"id": 1, ...}]

5. Client ← 200 OK
```

---

## 보안 계층

### 1. 네트워크 보안

| 계층 | 보안 기능 |
|------|----------|
| **Cloudflare** | DDoS 보호, SSL/TLS 암호화 |
| **Ingress** | TLS Termination, Rate Limiting (TODO) |
| **Istio mTLS** | Pod 간 암호화 통신 |
| **Network Policy** | Pod 간 트래픽 제한 (TODO) |

### 2. 애플리케이션 보안

| 기능 | 상태 | 비고 |
|------|------|------|
| **Input Validation** | ✅ | `@Valid`, `@NotBlank`, `@Size` |
| **SQL Injection 방지** | ✅ | JPA Prepared Statement |
| **XSS 방지** | ⚠️ | 프론트엔드에서 `escapeHtml()` 사용 |
| **CSRF 방지** | ❌ | Spring Security 미적용 |
| **인증/인가** | ❌ | 현재 누구나 CRUD 가능 (TODO) |

### 3. 시크릿 관리

```yaml
# mysql-secret (Kubernetes Secret)
apiVersion: v1
kind: Secret
metadata:
  name: mysql-secret
data:
  mysql-root-password: cm9vdHBhc3N3b3Jk  # base64(rootpassword)
```

**주의:**
- ❌ Git에 커밋 금지
- ✅ ArgoCD에서 `ignoreDifferences` 설정
- ⚠️ 운영 환경에서 강력한 비밀번호 사용 필요

---

## 확장성 및 고가용성

### HPA (Horizontal Pod Autoscaler)

**WAS만 HPA 적용:**
```yaml
# was-hpa.yaml (TODO)
spec:
  minReplicas: 2
  maxReplicas: 10
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70
```

**현재 상태:**
- CPU 사용률 3% (매우 낮음)
- 트래픽 증가 시 자동 스케일

### Canary 배포

**WEB vs WAS 전략 비교:**

| | WEB | WAS |
|---|-----|-----|
| **속도** | 빠름 (1.5분) | 느림 (3분) |
| **가중치** | 10→50→90 | 20→50→80 |
| **대기 시간** | 30초 | 1분 |
| **이유** | 정적 파일, 빠른 롤백 | API 안정성, DB 검증 |

### 장애 복구

**Pod 장애:**
- Kubernetes가 자동 재시작
- Liveness Probe 실패 시 재시작
- Readiness Probe 실패 시 트래픽 차단

**DB 장애:**
- MySQL PVC 유지 (Pod 재생성 시 데이터 보존)
- TODO: MySQL Replication (Master-Slave)

**네트워크 장애:**
- Istio Retry (3회)
- Timeout (5초)
- Circuit Breaking (WEB만)

---

## 모니터링 (TODO)

### Prometheus 메트릭

```yaml
# WAS에서 수집할 메트릭
- http_requests_total (API 호출 수)
- http_request_duration_seconds (응답 시간)
- jvm_memory_used_bytes (메모리 사용량)
- hikari_connections_active (DB 연결 수)
```

### Grafana 대시보드

**패널:**
1. API 요청 수 (시간별)
2. 응답 시간 (P50, P95, P99)
3. 에러율 (4xx, 5xx)
4. JVM Heap 사용량
5. DB Connection Pool

---

## 성능 최적화

### 현재 상태

| Metric | 값 | 평가 |
|--------|----|----|
| **응답 시간** | 미측정 | - |
| **처리량** | 미측정 | - |
| **DB 쿼리** | N+1 없음 (JPA) | ✅ |
| **메모리** | 250Mi / 1Gi | ✅ 효율적 |
| **CPU** | 7m / 500m | ✅ 매우 낮음 |

### 개선 계획

1. **Pagination**: 전체 조회 → 10개씩
2. **DB 인덱스**: `created_at` 인덱스 추가
3. **캐싱**: Redis 도입 (조회 API)
4. **Connection Pool**: HikariCP 튜닝

---

**작성일:** 2026-01-21
**버전:** v9
