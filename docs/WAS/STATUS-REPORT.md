# WAS (Spring Boot API) 현황 분석 및 개선 제안

> 작성일: 2026-01-21
> 대상: board-was (Spring Boot 3.5.0 + MySQL 8.0.44)

---

## 1. 현재 상태 요약

### ✅ 정상 작동 중

| 항목 | 상태 | 설명 |
|------|------|------|
| **Pod 상태** | ✅ Running | 2/2 Ready (Canary 배포 완료) |
| **이미지** | `ghcr.io/wlals2/board-was:v9` | 최신 배포 완료 |
| **Rollout** | ✅ Healthy | Canary 100% (Stable) |
| **리소스 사용량** | CPU 6-7m / Memory 244-255Mi | 매우 낮음 (요청의 3% / 50%) |
| **DB 연결** | ✅ MySQL 연결 성공 | HikariCP 정상 |
| **데이터** | ✅ 테이블 생성됨 | `posts` 테이블, 1개 레코드 존재 |

### ⚠️ 문제점 발견

| 문제 | 심각도 | 설명 |
|------|--------|------|
| **외부 접근 불가** | 🔴 높음 | `/api/posts` 엔드포인트가 외부에서 접근 안 됨 |
| **Istio mTLS 에러** | 🔴 높음 | TLS_error: WRONG_VERSION_NUMBER |
| **Frontend 미연결** | 🟡 중간 | board.html이 있지만 작동하지 않음 |
| **테스트 부족** | 🟡 중간 | 실제 CRUD 작동 검증 안 됨 |

---

## 2. 소스 코드 분석

### 파일 구조

```
blog-k8s-project/was/
├── pom.xml                     # Spring Boot 3.5.0, JDK 17
├── Dockerfile
└── src/
    └── main/
        ├── java/com/jimin/board/
        │   ├── BoardApplication.java       # Main 클래스
        │   ├── entity/Post.java            # Post 엔티티
        │   ├── repository/PostRepository.java  # JPA Repository
        │   ├── service/PostService.java    # 비즈니스 로직
        │   └── controller/PostController.java  # REST API 컨트롤러
        └── resources/
            └── application.properties      # 거의 비어있음 (ConfigMap 사용)
```

### 구현된 기능

#### ✅ Post 엔티티

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
- ✅ Lombok 활용 (코드 간결)
- ⚠️ `updatedAt` 없음 (수정 시간 추적 불가)

#### ✅ REST API 엔드포인트

| Method | Endpoint | 기능 | 상태 |
|--------|----------|------|------|
| GET | `/api/posts` | 전체 게시글 조회 (최신순) | ✅ 구현됨 |
| GET | `/api/posts/{id}` | 특정 게시글 조회 | ✅ 구현됨 |
| POST | `/api/posts` | 게시글 작성 | ✅ 구현됨 |
| PUT | `/api/posts/{id}` | 게시글 수정 | ✅ 구현됨 |
| DELETE | `/api/posts/{id}` | 게시글 삭제 | ✅ 구현됨 |
| GET | `/api/posts/search?keyword=XXX` | 제목 검색 | ✅ 구현됨 |

**설계 평가:**
- ✅ RESTful 원칙 준수
- ✅ HTTP Status Code 올바름 (200, 201, 204, 404)
- ✅ Validation (`@Valid`)
- ⚠️ 에러 응답 형식 부족 (RuntimeException만 던짐)
- ⚠️ Pagination 없음 (전체 조회 시 성능 문제 가능)

#### ✅ Service Layer

```java
@Service
@Transactional(readOnly = true)
public class PostService {
    // 읽기: readOnly = true (성능 최적화)
    public List<Post> getAllPosts() { ... }

    // 쓰기: @Transactional (롤백 가능)
    @Transactional
    public Post createPost(Post post) {
        // 비즈니스 로직: 기본값 설정
        if (title == null) post.setTitle("제목 없음");
        if (author == null) post.setAuthor("익명");
    }
}
```

**설계 평가:**
- ✅ 트랜잭션 관리 (`@Transactional`)
- ✅ 읽기/쓰기 분리 (readOnly 최적화)
- ✅ 비즈니스 로직 존재 (기본값 설정)
- ⚠️ 예외 처리 부족 (RuntimeException만 사용)

#### ✅ Repository

```java
public interface PostRepository extends JpaRepository<Post, Long> {
    List<Post> findByTitleContaining(String keyword);
    List<Post> findByAuthor(String author);
    List<Post> findAllByOrderByCreatedAtDesc();
}
```

**설계 평가:**
- ✅ Spring Data JPA 활용 (쿼리 메서드 네이밍)
- ✅ 정렬 기능 (`OrderByCreatedAtDesc`)
- ⚠️ 검색 성능 (`LIKE %keyword%`는 Full Scan)

### 의존성 (pom.xml)

```xml
<dependencies>
    <!-- Spring Boot 3.5.0 -->
    <dependency>spring-boot-starter-web</dependency>
    <dependency>spring-boot-starter-data-jpa</dependency>
    <dependency>spring-boot-starter-validation</dependency>
    <dependency>spring-boot-starter-actuator</dependency>

    <!-- MySQL -->
    <dependency>mysql-connector-j</dependency>

    <!-- Lombok -->
    <dependency>lombok</dependency>

    <!-- Test -->
    <dependency>spring-boot-starter-test</dependency>
</dependencies>
```

**평가:**
- ✅ 최신 버전 (Spring Boot 3.5.0, JDK 17)
- ✅ Actuator 포함 (Health Check)
- ⚠️ 보안 라이브러리 없음 (Spring Security)
- ⚠️ API 문서화 없음 (Swagger/OpenAPI)
- ⚠️ 로깅 라이브러리 기본 (Logback)

---

## 3. 프론트엔드 분석

### board.html

**위치:** `/home/jimin/blogsite/static/board.html`

**기능:**
- ✅ Bootstrap 5 UI
- ✅ CRUD 전체 구현 (작성, 조회, 수정, 삭제)
- ✅ Fetch API 사용 (`/api/posts`)
- ✅ 반응형 디자인 (모바일 지원)

**문제점:**
1. **API 경로가 상대 경로** (`/api/posts`)
   - nginx가 WAS로 프록시해야 하는데 설정 누락
2. **Istio mTLS 에러**
   - Frontend → Backend 통신 시 TLS 문제
3. **정적 파일 미배포**
   - Hugo 빌드 후 배포되지 않음

---

## 4. 네트워크 구성 분석

### 현재 트래픽 흐름

```
Client (브라우저)
  ↓ HTTPS
Cloudflare CDN
  ↓ HTTPS
Ingress (NGINX)
  ↓ HTTP
web-service (nginx Pod) - ClusterIP
  ↓ HTTP (mTLS?)
was-service (Spring Boot) - ClusterIP :8080
  ↓ TCP
mysql-service (MySQL) - ClusterIP :3306
```

### 문제점

#### 1. nginx → WAS 프록시 설정 누락

**web-nginx-config ConfigMap 확인 필요:**
```nginx
# 예상되는 설정 (현재 없을 가능성)
location /api/ {
    proxy_pass http://was-service:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
}
```

#### 2. Istio mTLS 설정 문제

**현재 에러:**
```
TLS_error: WRONG_VERSION_NUMBER
```

**가능한 원인:**
- WAS Service가 mTLS를 기대하는데 plain HTTP 전송
- DestinationRule의 mTLS 설정 vs VirtualService 불일치

**해결 방법:**
1. nginx → WAS는 mTLS 사용하지 않도록 설정
2. was-destinationrule.yaml에서 `tls.mode: DISABLE` 또는 `ISTIO_MUTUAL` 확인

#### 3. Ingress 경로 설정

**현재 blog-ingress.yaml:**
```yaml
paths:
- path: /api       # WAS API
  backend:
    service:
      name: web-service  # ❌ 잘못됨! was-service여야 함
      port: 80
```

**문제:** `/api` 경로가 web-service로 라우팅되는데, 이는 nginx Pod입니다. nginx가 WAS로 프록시하지 않으면 404 발생.

---

## 5. 데이터베이스 상태

### MySQL 연결 정보

```yaml
# was-config ConfigMap
SPRING_DATASOURCE_URL: jdbc:mysql://mysql-service:3306/board
SPRING_DATASOURCE_USERNAME: root
SPRING_DATASOURCE_PASSWORD: rootpassword (Secret에서 주입)
```

### 데이터 확인

```sql
mysql> SELECT * FROM posts;
+----+--------+------------------------+----------------------------+------------+
| id | author | content                | created_at                 | title      |
+----+--------+------------------------+----------------------------+------------+
|  1 | Jimin  | Running on Kubernetes  | 2026-01-17 02:49:29.138478 | First Post |
+----+--------+------------------------+----------------------------+------------+
```

**평가:**
- ✅ 테이블 자동 생성 (Hibernate DDL)
- ✅ 데이터 삽입 성공
- ⚠️ 운영 환경에서 `spring.jpa.hibernate.ddl-auto=update` 위험 (스키마 자동 변경)

---

## 6. 개선 제안

### P0 (즉시 수정 필요) 🔴

#### 1. nginx → WAS 프록시 설정 추가

**web-nginx-config.yaml 수정:**
```nginx
server {
    listen 80;
    server_name _;

    # 정적 파일
    location / {
        root /usr/share/nginx/html;
        try_files $uri $uri/ /index.html;
    }

    # WAS API 프록시 (신규 추가)
    location /api/ {
        proxy_pass http://was-service:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # Timeout 설정 (WAS보다 길게)
        proxy_connect_timeout 10s;
        proxy_send_timeout 10s;
        proxy_read_timeout 10s;
    }
}
```

#### 2. board.html 배포

**Hugo에 통합:**
```bash
# Option 1: layouts/page/board.html로 이동
cp static/board.html layouts/page/board.html

# Option 2: static/board.html 유지 (권장)
# → Hugo 빌드 시 자동 복사됨
```

**content/board.md 생성:**
```markdown
---
title: "게시판"
layout: "board"
url: "/board/"
---
```

#### 3. Istio mTLS 설정 확인

**was-destinationrule.yaml 수정 필요 시:**
```yaml
spec:
  host: was-service
  trafficPolicy:
    tls:
      mode: DISABLE  # 또는 ISTIO_MUTUAL → SIMPLE
```

**또는 nginx → WAS는 Istio Sidecar 우회:**
```yaml
# web Pod annotation 추가
traffic.sidecar.istio.io/excludeOutboundIPRanges: "10.97.248.192/32"
```

### P1 (중요 개선) 🟡

#### 1. 에러 응답 표준화

**현재:**
```java
throw new RuntimeException("게시글을 찾을 수 없습니다");
```

**개선:**
```java
// ErrorResponse DTO 추가
@Data
@AllArgsConstructor
public class ErrorResponse {
    private String timestamp;
    private int status;
    private String error;
    private String message;
    private String path;
}

// @RestControllerAdvice로 전역 예외 처리
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(PostNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(
        PostNotFoundException e, HttpServletRequest request
    ) {
        ErrorResponse error = new ErrorResponse(
            LocalDateTime.now().toString(),
            404,
            "Not Found",
            e.getMessage(),
            request.getRequestURI()
        );
        return ResponseEntity.status(404).body(error);
    }
}
```

#### 2. Pagination 추가

**현재:**
```java
@GetMapping
public ResponseEntity<List<Post>> getAllPosts() {
    return ResponseEntity.ok(postService.getAllPosts());
}
```

**개선:**
```java
@GetMapping
public ResponseEntity<Page<Post>> getAllPosts(
    @RequestParam(defaultValue = "0") int page,
    @RequestParam(defaultValue = "10") int size,
    @RequestParam(defaultValue = "createdAt,desc") String sort
) {
    Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
    return ResponseEntity.ok(postService.getAllPosts(pageable));
}
```

#### 3. API 문서화 (Swagger/OpenAPI)

**pom.xml 추가:**
```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>2.3.0</version>
</dependency>
```

**접근:** `http://localhost:8080/swagger-ui.html`

#### 4. 검색 성능 개선

**현재:**
```java
List<Post> findByTitleContaining(String keyword);  // LIKE %keyword%
```

**개선 (Full-text Search):**
```sql
-- MySQL Full-text Index 추가
ALTER TABLE posts ADD FULLTEXT(title, content);
```

```java
@Query(value = "SELECT * FROM posts WHERE MATCH(title, content) AGAINST(?1 IN NATURAL LANGUAGE MODE)", nativeQuery = true)
List<Post> fullTextSearch(String keyword);
```

#### 5. 수정 시간 추적

**Post 엔티티 추가:**
```java
@Column(name = "updated_at")
private LocalDateTime updatedAt;

@PreUpdate
protected void onUpdate() {
    updatedAt = LocalDateTime.now();
}
```

### P2 (추후 개선) 🟢

#### 1. Spring Security 추가

- 사용자 인증/인가
- JWT 토큰 기반 API 인증
- CORS 설정

#### 2. 캐싱 (Redis)

- 조회수 많은 게시글 캐싱
- `@Cacheable` 적용

#### 3. 파일 업로드

- 이미지 첨부 기능
- S3/MinIO 연동

#### 4. 댓글 기능

- Comment 엔티티 추가
- Post ↔ Comment OneToMany 관계

#### 5. 좋아요/조회수

- Like 엔티티
- View Count 필드

#### 6. 로깅 개선

- SLF4J + Logback 설정
- 구조화된 로그 (JSON)
- ELK Stack 연동

---

## 7. 테스트 계획

### 1단계: 네트워크 수정 (nginx 프록시)

```bash
# 1. web-nginx-config 수정
kubectl edit configmap web-nginx-config -n blog-system

# 2. nginx Pod 재시작
kubectl rollout restart deployment web -n blog-system

# 3. 테스트
curl https://blog.jiminhome.shop/api/posts
```

### 2단계: board.html 배포

```bash
# Hugo 빌드 확인
hugo --minify

# board.html이 public/에 복사되는지 확인
ls -la public/board.html

# GitHub Actions 트리거 → 자동 배포
```

### 3단계: CRUD 기능 테스트

```bash
# 1. 게시글 조회
curl https://blog.jiminhome.shop/api/posts

# 2. 게시글 작성
curl -X POST https://blog.jiminhome.shop/api/posts \
  -H "Content-Type: application/json" \
  -d '{
    "title": "테스트 게시글",
    "content": "Kubernetes에서 작성한 글입니다",
    "author": "지민"
  }'

# 3. 검색
curl "https://blog.jiminhome.shop/api/posts/search?keyword=Kubernetes"
```

### 4단계: 브라우저 테스트

```
https://blog.jiminhome.shop/board.html
→ 게시글 작성, 수정, 삭제 테스트
```

---

## 8. 결론

### 현재 상태

| 구분 | 평가 | 비고 |
|------|------|------|
| **Backend (WAS)** | 🟢 80점 | 기본 CRUD 완성, 코드 품질 양호 |
| **Database** | 🟢 90점 | MySQL 연결 정상, 데이터 존재 |
| **Frontend** | 🟡 50점 | HTML 작성되었으나 미배포 |
| **Network** | 🔴 30점 | nginx 프록시 누락, 외부 접근 불가 |
| **운영 안정성** | 🟢 85점 | Canary 배포, HPA, Probes 완성 |

### 총평

**✅ 잘된 점:**
1. Spring Boot 3.x 최신 스택 사용
2. RESTful API 설계 준수
3. JPA + Hibernate 적절한 활용
4. Kubernetes 인프라 완성도 높음 (Canary, Istio, ArgoCD)

**⚠️ 개선 필요:**
1. **nginx 프록시 설정** → 외부 접근 불가 해결
2. **board.html 배포** → 사용자가 사용할 수 있도록
3. **에러 처리 표준화** → API 응답 일관성
4. **Pagination** → 대용량 데이터 대비

### 다음 액션

**즉시 작업 (1시간):**
1. web-nginx-config에 `/api/` 프록시 추가
2. board.html Hugo 통합 및 배포
3. 외부 접근 테스트

**이번 주 작업:**
1. ErrorResponse DTO + GlobalExceptionHandler
2. Swagger UI 추가
3. Pagination 구현

**장기 계획:**
1. Spring Security + JWT 인증
2. Redis 캐싱
3. 댓글/좋아요 기능 추가

---

**작성자:** Claude (AI Assistant)
**검토 필요:** nginx 설정, Istio mTLS 설정
**다음 문서:** [NGINX-PROXY-SETUP.md](./NGINX-PROXY-SETUP.md)
