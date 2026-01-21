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

**작성일:** 2026-01-21
**버전:** WAS v9
**마지막 업데이트:** 현재 상태 통합
