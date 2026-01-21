# WAS 아키텍처 & 상태

> Spring Boot + MySQL 게시판 시스템 - 전체 아키텍처 및 현재 상태

---

## 목차

1. [기본 정보](#기본-정보)
2. [전체 시스템 구조](#전체-시스템-구조)
3. [현재 상태](#현재-상태)
4. [소스 코드 분석](#소스-코드-분석)
5. [API 레퍼런스](#api-레퍼런스)
6. [트래픽 흐름](#트래픽-흐름)
7. [Istio Service Mesh](#istio-service-mesh)
8. [로컬 개발 환경](#로컬-개발-환경)
9. [Docker 빌드](#docker-빌드)
10. [Kubernetes 배포](#kubernetes-배포)

---

## 기본 정보

### 스택

| 항목 | 내용 |
|------|------|
| **프레임워크** | Spring Boot 3.5.0 |
| **Java 버전** | JDK 17 |
| **데이터베이스** | MySQL 8.0.44 |
| **ORM** | Hibernate (JPA) |
| **빌드 도구** | Maven |
| **이미지** | `ghcr.io/wlals2/board-was:v9` |
| **배포 방식** | Argo Rollouts (Canary) |

### 주요 기능

- ✅ 게시글 CRUD (작성, 조회, 수정, 삭제)
- ✅ 검색 (제목 키워드 검색)
- ✅ 정렬 (최신순)
- ✅ Validation (입력값 검증)
- ✅ Health Check (`/actuator/health`)
- ⚠️ Pagination 없음 (전체 조회)
- ⚠️ 인증/인가 없음

---

## 전체 시스템 구조

```
┌─────────────────────────────────────────────────────────────────┐
│                    Client (브라우저)                             │
│              https://blog.jiminhome.shop                        │
└────────────────────────────┬────────────────────────────────────┘
                             │ HTTPS
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│                   Cloudflare CDN                                │
│              (SSL, DDoS 보호, 캐싱)                              │
└────────────────────────────┬────────────────────────────────────┘
                             │ HTTPS
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│               Ingress (NGINX Controller)                        │
│             blog-ingress (blog-system)                          │
│                                                                 │
│  ┌──────────────┐        ┌──────────────┐                      │
│  │ /            │        │ /api (TODO)  │                      │
│  │ → web-service│        │ → web-service│                      │
│  └──────────────┘        └──────────────┘                      │
└────────────────────────────┬────────────────────────────────────┘
                             │ HTTP
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│          web-service (ClusterIP :80)                            │
│                Istio Sidecar                                    │
└────────────────────────────┬────────────────────────────────────┘
                             │
              ┌──────────────┴──────────────┐
              │                             │
              ▼                             ▼
┌─────────────────────────┐   ┌─────────────────────────────────┐
│  web Pod (nginx)        │   │  WAS Pod (Spring Boot)          │
│  - Hugo 정적 파일       │   │  - REST API 처리                │
│  - /api → WAS 프록시    │   │  - 비즈니스 로직                │
│    (현재 설정 없음!)    │   │  - Istio Sidecar                │
└────────────┬────────────┘   └────────────┬────────────────────┘
             │                              │ HTTP (Istio mTLS)
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
              │  MySQL Pod (StatefulSet)    │
              │  - PVC: 1Gi                 │
              │  - DB: board                │
              │  - Port: 3306               │
              └─────────────────────────────┘
```

### 컴포넌트

#### 1. WEB (nginx)

**역할:**
- Hugo 정적 파일 서빙 (`/`)
- WAS API 프록시 (`/api/`) **← 현재 미구현**

**리소스:**
- Replicas: 2 (고정)
- CPU: 100m-200m
- Memory: 128Mi-256Mi
- Image: `ghcr.io/wlals2/blog-web:v28`

**Canary 배포:**
- 10% → 50% → 90% → 100%
- 각 단계 30초 대기

#### 2. WAS (Spring Boot)

**역할:**
- REST API 제공 (`/api/posts`)
- 비즈니스 로직 처리
- DB 트랜잭션 관리

**리소스:**
- Replicas: 2-10 (HPA)
- CPU: 250m-500m
- Memory: 512Mi-1Gi
- Image: `ghcr.io/wlals2/board-was:v9`

**Canary 배포:**
- 20% → 50% → 80% → 100%
- 각 단계 1분 대기

**Health Check:**
- Liveness: `/actuator/health` (60초 후, 10초 주기)
- Readiness: `/actuator/health` (50초 후, 5초 주기)

#### 3. MySQL

**역할:**
- 게시글 데이터 저장
- `board` 데이터베이스

**리소스:**
- Replicas: 1 (StatefulSet)
- Storage: 1Gi PVC
- Image: `mysql:8.0.44`

---

## 현재 상태

### Pod 상태

| 항목 | 상태 | 설명 |
|------|------|------|
| **Pod** | ✅ Running | 2/2 Ready (Canary 100%) |
| **이미지** | `ghcr.io/wlals2/board-was:v9` | 최신 배포 완료 |
| **Rollout** | ✅ Healthy | Canary 100% (Stable) |
| **CPU** | 6-7m | 매우 낮음 (요청의 3%) |
| **Memory** | 244-255Mi | 정상 (요청의 48%) |
| **DB 연결** | ✅ MySQL 연결 성공 | HikariCP 정상 |
| **데이터** | ✅ 테이블 생성됨 | `posts` 테이블, 1개 레코드 |

### 문제점 발견

| 문제 | 심각도 | 설명 |
|------|--------|------|
| **외부 접근 불가** | 🔴 높음 | `/api/posts` 404 (nginx 프록시 누락) |
| **Istio mTLS 에러** | 🔴 높음 | TLS_error: WRONG_VERSION_NUMBER |
| **Frontend 미연결** | 🟡 중간 | board.html 미배포 |

### 리소스 사용량

| Metric | 현재 | 요청 | 제한 | 사용률 |
|--------|------|------|------|--------|
| **CPU** | 6-7m | 250m | 500m | 3% |
| **Memory** | 244-255Mi | 512Mi | 1Gi | 48% |

---

## 소스 코드 분석

### 파일 구조

```
blog-k8s-project/was/
├── pom.xml                     # Spring Boot 3.5.0, JDK 17
├── Dockerfile                  # 멀티 스테이지 빌드
└── src/
    └── main/
        ├── java/com/jimin/board/
        │   ├── BoardApplication.java       # Main 클래스
        │   ├── entity/Post.java            # Post 엔티티
        │   ├── repository/PostRepository.java  # JPA Repository
        │   ├── service/PostService.java    # 비즈니스 로직
        │   └── controller/PostController.java  # REST API
        └── resources/
            └── application.properties      # 거의 비어있음 (ConfigMap 사용)
```

### Post 엔티티

```java
@Entity
@Table(name = "posts")
public class Post {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank @Size(max = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Size(max = 50)
    private String author;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
```

**설계 평가:**
- ✅ Validation 적용 (`@NotBlank`, `@Size`)
- ✅ 자동 타임스탬프 (`@PrePersist`)
- ✅ Lombok 활용
- ⚠️ `updatedAt` 없음

### REST API 엔드포인트

| Method | Endpoint | 기능 | 구현 |
|--------|----------|------|------|
| GET | `/api/posts` | 전체 조회 (최신순) | ✅ |
| GET | `/api/posts/{id}` | 특정 조회 | ✅ |
| POST | `/api/posts` | 작성 | ✅ |
| PUT | `/api/posts/{id}` | 수정 | ✅ |
| DELETE | `/api/posts/{id}` | 삭제 | ✅ |
| GET | `/api/posts/search?keyword=XXX` | 검색 | ✅ |

**설계 평가:**
- ✅ RESTful 원칙 준수
- ✅ HTTP Status Code 올바름
- ✅ Validation (`@Valid`)
- ⚠️ 에러 응답 형식 부족
- ⚠️ Pagination 없음

### Service Layer

```java
@Service
@Transactional(readOnly = true)
public class PostService {
    // 읽기: readOnly = true (성능 최적화)
    public List<Post> getAllPosts() { ... }

    // 쓰기: @Transactional (롤백 가능)
    @Transactional
    public Post createPost(Post post) {
        if (title == null) post.setTitle("제목 없음");
        if (author == null) post.setAuthor("익명");
        return postRepository.save(post);
    }
}
```

**설계 평가:**
- ✅ 트랜잭션 관리
- ✅ 읽기/쓰기 분리
- ✅ 비즈니스 로직 존재

### Repository

```java
public interface PostRepository extends JpaRepository<Post, Long> {
    List<Post> findByTitleContaining(String keyword);  // LIKE %keyword%
    List<Post> findByAuthor(String author);
    List<Post> findAllByOrderByCreatedAtDesc();        // 최신순 정렬
}
```

**설계 평가:**
- ✅ Spring Data JPA 쿼리 메서드
- ✅ 정렬 기능
- ⚠️ 검색 성능 (`LIKE %keyword%`는 Full Scan)

### MySQL 테이블

```sql
CREATE TABLE posts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    content TEXT,
    author VARCHAR(50),
    created_at DATETIME NOT NULL
);
```

**현재 데이터:**
```sql
mysql> SELECT * FROM posts;
+----+--------+------------------------+----------------------------+------------+
| id | author | content                | created_at                 | title      |
+----+--------+------------------------+----------------------------+------------+
|  1 | Jimin  | Running on Kubernetes  | 2026-01-17 02:49:29.138478 | First Post |
+----+--------+------------------------+----------------------------+------------+
```

---

## API 레퍼런스

### Base URL

```
Production: https://blog.jiminhome.shop/api (현재 404)
Local: http://localhost:8080/api
```

### 1. 전체 게시글 조회

**Request:**
```http
GET /api/posts
```

**Response (200 OK):**
```json
[
  {
    "id": 2,
    "title": "두 번째 글",
    "content": "내용입니다",
    "author": "지민",
    "createdAt": "2026-01-21T10:00:00"
  },
  {
    "id": 1,
    "title": "First Post",
    "content": "Running on Kubernetes",
    "author": "Jimin",
    "createdAt": "2026-01-17T02:49:29.138478"
  }
]
```

**cURL:**
```bash
curl https://blog.jiminhome.shop/api/posts
```

### 2. 특정 게시글 조회

**Request:**
```http
GET /api/posts/{id}
```

**Response (200 OK):**
```json
{
  "id": 1,
  "title": "First Post",
  "content": "Running on Kubernetes",
  "author": "Jimin",
  "createdAt": "2026-01-17T02:49:29.138478"
}
```

**Response (404):**
```
(현재: 빈 응답)
```

### 3. 게시글 작성

**Request:**
```http
POST /api/posts
Content-Type: application/json

{
  "title": "새 게시글",
  "content": "내용",
  "author": "지민"
}
```

**Validation:**
- `title`: 필수, 최대 200자
- `author`: 최대 50자

**Response (201 Created):**
```json
{
  "id": 3,
  "title": "새 게시글",
  "content": "내용",
  "author": "지민",
  "createdAt": "2026-01-21T10:05:00"
}
```

**cURL:**
```bash
curl -X POST https://blog.jiminhome.shop/api/posts \
  -H "Content-Type: application/json" \
  -d '{"title":"테스트","content":"내용","author":"지민"}'
```

### 4. 게시글 수정

**Request:**
```http
PUT /api/posts/{id}
Content-Type: application/json

{
  "title": "수정된 제목",
  "content": "수정된 내용"
}
```

**Response (200 OK):**
```json
{
  "id": 1,
  "title": "수정된 제목",
  "content": "수정된 내용",
  "author": "Jimin",
  "createdAt": "2026-01-17T02:49:29.138478"
}
```

### 5. 게시글 삭제

**Request:**
```http
DELETE /api/posts/{id}
```

**Response (204 No Content):**
```
(빈 응답)
```

### 6. 게시글 검색

**Request:**
```http
GET /api/posts/search?keyword=Kubernetes
```

**Response (200 OK):**
```json
[
  {
    "id": 1,
    "title": "First Post",
    "content": "Running on Kubernetes",
    "author": "Jimin",
    "createdAt": "2026-01-17T02:49:29.138478"
  }
]
```

### Health Check

**Request:**
```http
GET /actuator/health
```

**Response (200 OK):**
```json
{
  "status": "UP",
  "components": {
    "db": {
      "status": "UP",
      "details": {
        "database": "MySQL",
        "validationQuery": "isValid()"
      }
    }
  }
}
```

### 에러 코드

| HTTP Status | 설명 | 원인 |
|-------------|------|------|
| **200 OK** | 성공 | 조회, 수정 성공 |
| **201 Created** | 생성 성공 | 게시글 작성 |
| **204 No Content** | 삭제 성공 | 게시글 삭제 |
| **400 Bad Request** | 잘못된 요청 | Validation 실패 |
| **404 Not Found** | 리소스 없음 | 존재하지 않는 ID |
| **500 Internal Server Error** | 서버 오류 | DB 연결 실패 등 |

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

### 데이터 흐름 예시

#### 게시글 작성 (POST /api/posts)

```
1. Client → POST /api/posts
   Body: {"title": "제목", "content": "내용"}

2. nginx → was-service:8080 (프록시, 현재 없음!)

3. WAS → PostController.createPost()
   ├─ @Valid 검증
   └─ PostService.createPost()
       ├─ title == null → "제목 없음"
       └─ PostRepository.save()
           └─ Hibernate → INSERT SQL

4. MySQL → INSERT INTO posts (...) VALUES (...)

5. WAS → 201 Created
   Body: {"id": 2, "title": "제목", ...}

6. Client ← 201 Created
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
    mode: PERMISSIVE  # Plain HTTP도 허용

# was-destinationrule.yaml
spec:
  trafficPolicy:
    tls:
      mode: ISTIO_MUTUAL  # WAS는 mTLS 사용
```

**현재 문제:**
- `TLS_error: WRONG_VERSION_NUMBER`
- nginx → WAS가 Plain HTTP인데 mTLS로 전송됨

### Traffic Routing

**VirtualService (was-retry-timeout.yaml):**
```yaml
spec:
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
    timeout: 5s
```

**DestinationRule (was-destinationrule.yaml):**
```yaml
spec:
  subsets:
  - name: stable  # Argo Rollouts가 레이블 관리
  - name: canary
```

---

## 로컬 개발 환경

### 사전 요구사항

```bash
# Java 17
java -version
# openjdk version "17.0.x"

# Maven
mvn -version

# MySQL (Docker 권장)
docker --version
```

### MySQL 실행

```bash
# Docker로 MySQL 실행
docker run -d \
  --name mysql-dev \
  -e MYSQL_ROOT_PASSWORD=rootpassword \
  -e MYSQL_DATABASE=board \
  -p 3306:3306 \
  mysql:8.0.44

# 연결 확인
docker exec -it mysql-dev mysql -uroot -prootpassword -e "SHOW DATABASES;"
```

### application.properties 설정

```bash
cd ~/blogsite/blog-k8s-project/was

cat > src/main/resources/application.properties <<EOF
spring.application.name=Board

# MySQL 연결
spring.datasource.url=jdbc:mysql://localhost:3306/board
spring.datasource.username=root
spring.datasource.password=rootpassword

# JPA
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=true
spring.jpa.properties.hibernate.format_sql=true

# Actuator
management.endpoints.web.exposure.include=health,info
EOF
```

### WAS 실행

```bash
# Maven Wrapper로 실행
./mvnw spring-boot:run

# 또는 JAR 빌드 후 실행
./mvnw clean package -DskipTests
java -jar target/board-0.0.1-SNAPSHOT.jar
```

### API 테스트

```bash
# Health Check
curl http://localhost:8080/actuator/health

# 게시글 조회
curl http://localhost:8080/api/posts

# 게시글 작성
curl -X POST http://localhost:8080/api/posts \
  -H "Content-Type: application/json" \
  -d '{"title":"로컬 테스트","content":"로컬에서 작성","author":"개발자"}'
```

---

## Docker 빌드

### Dockerfile

```dockerfile
# Stage 1: Build
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Runtime
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### 로컬 빌드

```bash
cd ~/blogsite/blog-k8s-project/was

# 빌드
docker build -t board-was:local .

# 실행
docker run -d \
  --name board-was \
  --network host \
  -e SPRING_DATASOURCE_URL=jdbc:mysql://localhost:3306/board \
  -e SPRING_DATASOURCE_USERNAME=root \
  -e SPRING_DATASOURCE_PASSWORD=rootpassword \
  board-was:local

# 로그 확인
docker logs -f board-was

# 테스트
curl http://localhost:8080/api/posts
```

---

## Kubernetes 배포

### 이미지 빌드 및 푸시

```bash
# GitHub Actions 워크플로우 수동 실행
# https://github.com/wlals2/blogsite/actions/workflows/deploy-was.yml
# → Run workflow

# 또는 로컬 빌드 (권장 안 함)
docker build -t ghcr.io/wlals2/board-was:v10 .
docker push ghcr.io/wlals2/board-was:v10
```

### Manifest 업데이트 (GitOps)

```bash
cd ~/k8s-manifests/blog-system

# 이미지 태그 변경
yq eval '.spec.template.spec.containers[0].image = "ghcr.io/wlals2/board-was:v10"' \
  -i was-rollout.yaml

# Git Commit & Push
git add was-rollout.yaml
git commit -m "chore: Update WAS image to v10"
git push origin main
```

### ArgoCD 자동 배포 확인

```bash
# ArgoCD 동기화 대기 (3분 이내)
watch kubectl get application blog-system -n argocd

# Rollout 상태 확인
kubectl argo rollouts get rollout was -n blog-system --watch

# Pod 상태
kubectl get pods -n blog-system -l app=was
```

### 배포 검증

```bash
# Health Check
kubectl exec -n blog-system $(kubectl get pod -n blog-system -l app=web -o jsonpath='{.items[0].metadata.name}') \
  -c nginx -- curl -s http://was-service:8080/actuator/health

# API 테스트 (외부)
curl https://blog.jiminhome.shop/api/posts
```

---

## v1.4.0 변경사항 및 확인 방법

> P1 작업 완료 (2026-01-21) - Swagger UI, Pagination, 에러 응답 표준화

### 🎯 1. Swagger UI 추가

#### 어디서 확인하나요?

**로컬 환경:**
```bash
# WAS 실행 후 브라우저에서 접속
http://localhost:8080/swagger-ui/index.html
```

**프로덕션 (배포 후):**
```bash
# nginx 프록시 설정 필요 (현재 미구현)
https://blog.jiminhome.shop/api/swagger-ui/index.html
```

#### 무엇이 달라졌나요?

**Before (v9):**
- API 문서 없음
- Postman/cURL로만 테스트 가능
- 각 API 스펙을 README에서 찾아야 함

**After (v1.4.0):**
- 자동 생성된 인터랙티브 API 문서
- 브라우저에서 바로 API 테스트 가능
- Request/Response 예시 자동 표시
- Validation 규칙 자동 문서화

**Swagger UI 스크린샷 예시:**
```
┌─────────────────────────────────────────────────┐
│ 게시글 API                                       │
│                                                 │
│ GET    /api/posts        게시글 목록 조회        │
│ POST   /api/posts        게시글 작성            │
│ GET    /api/posts/{id}   특정 게시글 조회       │
│ PUT    /api/posts/{id}   게시글 수정            │
│ DELETE /api/posts/{id}   게시글 삭제            │
│ GET    /api/posts/search 게시글 검색            │
└─────────────────────────────────────────────────┘
```

**추가된 파일:**
- [pom.xml](../../blog-k8s-project/was/pom.xml#L50-L55): `springdoc-openapi-starter-webmvc-ui` 의존성

**수정된 파일:**
- [PostController.java](../../blog-k8s-project/was/src/main/java/com/jimin/board/controller/PostController.java#L30): `@Tag` 애노테이션 추가
- [PostController.java](../../blog-k8s-project/was/src/main/java/com/jimin/board/controller/PostController.java#L65): `@Operation`, `@Parameter` 애노테이션 추가

---

### 🎯 2. Pagination 구현

#### 어디서 확인하나요?

**API 호출 방법:**
```bash
# 기본 (page=0, size=10)
curl http://localhost:8080/api/posts

# 페이지 지정
curl "http://localhost:8080/api/posts?page=0&size=5"
curl "http://localhost:8080/api/posts?page=1&size=5"

# 큰 페이지 사이즈
curl "http://localhost:8080/api/posts?page=0&size=20"
```

#### 무엇이 달라졌나요?

**Before (v9) - 전체 조회:**

```bash
GET /api/posts
```

**응답:**
```json
[
  {"id": 100, "title": "글 100", ...},
  {"id": 99, "title": "글 99", ...},
  ...
  {"id": 1, "title": "글 1", ...}
]
```

**문제점:**
- 1,000개 게시글 → 1,000개 모두 반환 (~100KB)
- 메모리: 1,000개 객체 직렬화
- 네트워크: 대역폭 낭비
- 클라이언트: 렌더링 느림

---

**After (v1.4.0) - Pagination:**

```bash
GET /api/posts?page=0&size=10
```

**응답:**
```json
{
  "content": [
    {"id": 100, "title": "글 100", ...},
    {"id": 99, "title": "글 99", ...},
    ...
    {"id": 91, "title": "글 91", ...}
  ],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 10,
    "sort": {"sorted": true, "unsorted": false},
    "offset": 0,
    "paged": true,
    "unpaged": false
  },
  "totalElements": 1000,
  "totalPages": 100,
  "last": false,
  "number": 0,
  "size": 10,
  "numberOfElements": 10,
  "first": true,
  "empty": false
}
```

**개선 효과:**
- 메모리: 1,000개 → 10개 로드 (90% 감소)
- 네트워크: ~100KB → ~10KB (90% 감소)
- 응답 속도: 빠름
- UX: 무한 스크롤 구현 가능

---

**SQL 쿼리 비교:**

Before:
```sql
SELECT * FROM posts ORDER BY created_at DESC;
-- 1,000 rows
```

After:
```sql
SELECT * FROM posts ORDER BY created_at DESC LIMIT 10 OFFSET 0;
-- 10 rows (90% 감소)
```

---

**변경된 코드:**

1. **PostService.java**
   - [PostService.java:33-35](../../blog-k8s-project/was/src/main/java/com/jimin/board/service/PostService.java#L33-L35): 기존 `getAllPosts()` → `@deprecated` 표시
   - [PostService.java:60-62](../../blog-k8s-project/was/src/main/java/com/jimin/board/service/PostService.java#L60-L62): 새로운 `getAllPostsPaged(Pageable)` 추가

2. **PostController.java**
   - [PostController.java:66-76](../../blog-k8s-project/was/src/main/java/com/jimin/board/controller/PostController.java#L66-L76): `page`, `size` 파라미터 추가, `Page<Post>` 반환

---

### 🎯 3. 에러 응답 표준화

#### 어디서 확인하나요?

**404 Not Found 테스트:**
```bash
# 존재하지 않는 게시글 조회
curl -i http://localhost:8080/api/posts/999
```

**400 Bad Request 테스트:**
```bash
# Validation 실패 (title 누락)
curl -X POST http://localhost:8080/api/posts \
  -H "Content-Type: application/json" \
  -d '{"content":"내용만 있음"}'
```

#### 무엇이 달라졌나요?

**Before (v9) - 일관성 없는 에러:**

```bash
# 404 Not Found
curl -i http://localhost:8080/api/posts/999
```

**응답:**
```http
HTTP/1.1 500 Internal Server Error
Content-Length: 0
```

**문제점:**
- 빈 응답 (에러 이유 불명)
- 500 에러 (실제로는 404여야 함)
- 클라이언트가 디버깅 불가

---

**After (v1.4.0) - RFC 7807 표준:**

```bash
# 404 Not Found
curl -i http://localhost:8080/api/posts/999
```

**응답:**
```http
HTTP/1.1 404 Not Found
Content-Type: application/json

{
  "timestamp": "2026-01-21T15:30:00",
  "status": 404,
  "error": "Not Found",
  "message": "게시글을 찾을 수 없습니다. ID: 999",
  "path": "/api/posts/999"
}
```

---

**Validation 에러:**

```bash
# 400 Bad Request
curl -X POST http://localhost:8080/api/posts \
  -H "Content-Type: application/json" \
  -d '{"content":"제목 없음"}'
```

**응답:**
```http
HTTP/1.1 400 Bad Request
Content-Type: application/json

{
  "timestamp": "2026-01-21T15:31:00",
  "status": 400,
  "error": "Bad Request",
  "message": "입력값 검증 실패: title: 공백일 수 없습니다",
  "path": "/api/posts"
}
```

---

**개선 효과:**
- ✅ 표준화된 에러 형식 (RFC 7807)
- ✅ 올바른 HTTP Status Code
- ✅ 명확한 에러 메시지
- ✅ 에러 발생 경로 포함
- ✅ 클라이언트 디버깅 용이

---

**추가된 파일:**

1. **[PostNotFoundException.java](../../blog-k8s-project/was/src/main/java/com/jimin/board/exception/PostNotFoundException.java)**
   - 커스텀 예외 클래스
   - `RuntimeException` 상속
   - `postId` 필드로 컨텍스트 저장

2. **[ErrorResponse.java](../../blog-k8s-project/was/src/main/java/com/jimin/board/dto/ErrorResponse.java)**
   - 표준화된 에러 응답 DTO
   - RFC 7807 스타일
   - 필드: `timestamp`, `status`, `error`, `message`, `path`

3. **[GlobalExceptionHandler.java](../../blog-k8s-project/was/src/main/java/com/jimin/board/exception/GlobalExceptionHandler.java)**
   - `@RestControllerAdvice` 전역 예외 처리
   - 3가지 예외 처리:
     - `PostNotFoundException` → 404
     - `MethodArgumentNotValidException` → 400
     - `Exception` (Fallback) → 500

**수정된 파일:**

1. **PostService.java**
   - [PostService.java:69-72](../../blog-k8s-project/was/src/main/java/com/jimin/board/service/PostService.java#L69-L72): `RuntimeException` → `PostNotFoundException` 변경
   - [PostService.java:127-133](../../blog-k8s-project/was/src/main/java/com/jimin/board/service/PostService.java#L127-L133): `deletePost()`도 동일 변경

2. **PostController.java**
   - [PostController.java:98-102](../../blog-k8s-project/was/src/main/java/com/jimin/board/controller/PostController.java#L98-L102): try-catch 제거 (3곳)
   - GlobalExceptionHandler가 자동 처리

**코드 간소화:**
```java
// Before
@GetMapping("/{id}")
public ResponseEntity<Post> getPostById(@PathVariable Long id) {
    try {
        Post post = postService.getPostById(id);
        return ResponseEntity.ok(post);
    } catch (RuntimeException e) {
        return ResponseEntity.notFound().build();
    }
}

// After
@GetMapping("/{id}")
public ResponseEntity<Post> getPostById(@PathVariable Long id) {
    Post post = postService.getPostById(id);  // GlobalExceptionHandler가 처리
    return ResponseEntity.ok(post);
}
```

---

### 📊 성능 비교

| 항목 | Before (v9) | After (v1.4.0) | 개선율 |
|------|-------------|----------------|--------|
| **응답 크기** | ~100KB (1,000개) | ~10KB (10개) | 90% ↓ |
| **메모리 사용** | 1,000 객체 | 10 객체 | 90% ↓ |
| **DB 조회** | SELECT * (Full) | SELECT * LIMIT 10 | 90% ↓ |
| **API 문서** | 없음 | Swagger UI | ✅ |
| **에러 응답** | 빈 응답 | RFC 7807 JSON | ✅ |
| **코드 복잡도** | try-catch 3곳 | 0곳 (전역 처리) | ✅ |

---

### 🧪 테스트 방법

#### 1. Swagger UI 테스트

```bash
# 1. WAS 실행
cd ~/blogsite/blog-k8s-project/was
./mvnw spring-boot:run

# 2. 브라우저 접속
open http://localhost:8080/swagger-ui/index.html

# 3. "Try it out" 버튼 클릭
# 4. 파라미터 입력 후 "Execute" 클릭
# 5. Response 확인
```

#### 2. Pagination 테스트

```bash
# 1,000개 게시글 생성 (테스트 데이터)
for i in {1..1000}; do
  curl -X POST http://localhost:8080/api/posts \
    -H "Content-Type: application/json" \
    -d "{\"title\":\"게시글 $i\",\"content\":\"내용 $i\",\"author\":\"테스터\"}"
done

# 첫 페이지 (최신 10개)
curl "http://localhost:8080/api/posts?page=0&size=10" | jq '.content[].title'

# 두 번째 페이지
curl "http://localhost:8080/api/posts?page=1&size=10" | jq '.content[].title'

# 전체 페이지 수 확인
curl "http://localhost:8080/api/posts?page=0&size=10" | jq '.totalPages'
# 출력: 100
```

#### 3. 에러 응답 테스트

```bash
# 404 Not Found
curl -i http://localhost:8080/api/posts/999999 | grep -A 20 "HTTP"

# 400 Bad Request (Validation)
curl -i -X POST http://localhost:8080/api/posts \
  -H "Content-Type: application/json" \
  -d '{"content":"제목 없음"}' | grep -A 20 "HTTP"

# 예상 출력:
# HTTP/1.1 400 Bad Request
# {
#   "timestamp": "...",
#   "status": 400,
#   "error": "Bad Request",
#   "message": "입력값 검증 실패: title: 공백일 수 없습니다",
#   "path": "/api/posts"
# }
```

---

### 📚 학습 포인트

#### 1. Swagger/OpenAPI
- **springdoc-openapi**: Spring Boot 3.x와 호환
- **자동 문서화**: `@Operation`, `@Parameter` 애노테이션만 추가
- **대안**: Springfox (Spring Boot 2.x, deprecated)

#### 2. Pagination
- **Spring Data JPA**: `Page<T>`, `Pageable` 인터페이스
- **Offset 방식**: `LIMIT 10 OFFSET 0` (표준적, 구현 쉬움)
- **대안**: Cursor 방식 (무한 스크롤, 성능 좋음, 구현 복잡)
- **Trade-off**: Offset은 깊은 페이지(page=1000)에서 느림 → Cursor 고려

#### 3. 예외 처리
- **@RestControllerAdvice**: Spring 4.3+ 전역 예외 처리
- **RFC 7807**: Problem Details for HTTP APIs 표준
- **장점**: Controller 코드 간결, 에러 응답 일관성
- **단점**: 전역 처리로 특정 Controller만 다르게 처리 어려움 (해결: `@ControllerAdvice(basePackages)`)

---

**작성일:** 2026-01-21
**버전:** WAS v1.4.0 (이미지 v9 기반)
**마지막 업데이트:** P1 작업 완료 후 검증 가이드 추가
