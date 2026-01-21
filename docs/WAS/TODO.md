# WAS 개선 계획

> 추후에 할 것들 - 배경 지식, 트레이드오프, 우선순위

---

## 우선순위 요약

### P0 - 즉시 수정 (오늘) 🔴

| 작업 | 시간 | 이유 |
|------|------|------|
| nginx 프록시 설정 | 10분 | 외부 접근 불가 해결 |
| board.html 배포 확인 | 5분 | 사용자 게시판 사용 가능 |

### P1 - 중요 개선 (이번 주) 🟡

| 작업 | 시간 | 이유 |
|------|------|------|
| Pagination 추가 | 30분 | 성능, 사용자 경험 |
| 에러 응답 표준화 | 30분 | 디버깅, API 품질 |
| API 문서화 (Swagger) | 10분 | 개발 생산성 |

### P2 - 장기 개선 (향후) 🟢

- Spring Security + JWT 인증
- Redis 캐싱
- 검색 최적화 (Full-text Search)
- 댓글 기능
- 파일 업로드

---

## P0: 즉시 수정 필요

### 1. nginx 프록시 설정

#### 문제 상황

**현재:**
```
Client → Ingress → web-service → nginx Pod
                                    ↓ (프록시 없음!)
                                404 Not Found
```

**에러:**
```bash
curl https://blog.jiminhome.shop/api/posts
# → 404 Not Found
```

#### 왜 프록시가 필요한가?

**Reverse Proxy 패턴:**
- 클라이언트는 nginx만 알고, WAS는 숨김
- nginx가 요청을 받아서 Backend로 전달
- 보안 향상, 로드 밸런싱, SSL Termination, 캐싱 가능

**우리 구조:**
```
nginx (web-service)
  ├─> /              → Hugo 정적 파일 (블로그)
  └─> /api/          → WAS 프록시 (Spring Boot API)
```

#### 해결 방법 비교

##### 옵션 1: nginx 프록시 설정 (권장) ✅

**web-nginx-config.yaml 수정:**
```nginx
server {
    listen 80;

    # Hugo 정적 파일
    location / {
        root /usr/share/nginx/html;
        try_files $uri $uri/ /index.html;
    }

    # WAS API 프록시 (NEW)
    location /api/ {
        proxy_pass http://was-service:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

**장점:**
- ✅ 단일 도메인 (blog.jiminhome.shop)
- ✅ CORS 문제 없음 (Same-Origin)
- ✅ nginx 캐싱/압축 활용 가능
- ✅ 표준 아키텍처 패턴
- ✅ WAS 직접 노출 방지 (보안)

**단점:**
- ⚠️ nginx 재시작 필요 (Rolling Update)
- ⚠️ nginx 설정 복잡도 증가

**트레이드오프:**

| 항목 | 현재 | 프록시 추가 후 |
|------|------|---------------|
| 복잡도 | 낮음 | 중간 |
| 성능 | N/A | nginx 오버헤드 ~1ms |
| 보안 | N/A | WAS 직접 노출 안 됨 |
| CORS | N/A | 불필요 |

##### 옵션 2: Ingress에서 직접 WAS 라우팅 ❌

**blog-ingress.yaml:**
```yaml
paths:
- path: /api
  backend:
    service:
      name: was-service  # nginx 건너뛰고 직접
      port: 8080
```

**장점:**
- ✅ 단순 (nginx 설정 불필요)
- ✅ nginx 오버헤드 없음

**단점:**
- ❌ WAS 직접 노출 (보안 위험)
- ❌ nginx 캐싱/압축 불가
- ❌ 표준 패턴과 다름

**비추천 이유:**
- WAS 직접 노출은 보안 Best Practice 위반
- 향후 API Gateway 추가 시 nginx를 거쳐야 함

##### 옵션 3: API Gateway (Kong) ⚠️

**구조:**
```
Ingress → web-service (정적)
       → api-gateway → was-service
```

**장점:**
- ✅ API 전용 기능 (Rate Limiting, Auth)
- ✅ API 버저닝 (v1, v2)

**단점:**
- ❌ 오버킬 (현재 API 1개뿐)
- ❌ 복잡도 매우 높음
- ❌ 리소스 추가 필요

**결론:** 현재 규모에 과함. API 10개 이상 시 고려.

##### 옵션 4: CORS 허용 + 별도 도메인 ❌

**구조:**
```
blog.jiminhome.shop  → web-service
api.jiminhome.shop   → was-service
```

**장점:**
- ✅ WEB/WAS 완전 분리
- ✅ 독립적 스케일링

**단점:**
- ❌ CORS Preflight (OPTIONS) 오버헤드
- ❌ 도메인 2개 관리
- ❌ SSL 인증서 2개
- ❌ 브라우저 쿠키 공유 문제

**비추천 이유:**
- Same-Origin이 가장 단순하고 안전

#### 최종 선택: 옵션 1 (nginx 프록시)

**이유:**
1. 표준 패턴 (대부분의 웹 서비스 사용)
2. 보안 (WAS 직접 노출 방지)
3. 단일 도메인 (CORS 불필요)
4. 확장성 (향후 캐싱, Rate Limiting 추가 쉬움)

---

### 2. board.html 배포 확인

#### 문제 상황

**현재:**
- `~/blogsite/static/board.html` 파일 존재
- 하지만 외부 접근 불가

#### Hugo 정적 파일 처리

**Hugo 디렉토리 구조:**
```
blogsite/
├── content/          # Markdown (.md)
├── layouts/          # HTML 템플릿
├── static/           # 정적 파일
│   └── board.html    # ← 여기!
└── public/           # 빌드 결과
    └── board.html    # ← static/에서 자동 복사
```

**Hugo 빌드 프로세스:**
1. `content/**/*.md` → HTML 변환
2. `static/**/*` → `public/`로 **그대로 복사**
3. `public/`를 nginx에 배포

**따라서:**
- `static/board.html`은 올바른 위치
- Hugo 빌드만 하면 `public/board.html`로 복사됨
- 문제는 배포 여부 확인 필요

#### 해결 방법 비교

##### 옵션 1: static/ 유지 + 배포 확인 (권장) ✅

**확인 절차:**
```bash
# 1. Hugo 빌드
cd ~/blogsite
hugo --minify
ls public/board.html  # ✅ 있어야 함

# 2. 배포 확인
ls /var/www/blog/board.html  # ✅ 있어야 함

# 3. 접근 테스트
curl https://blog.jiminhome.shop/board.html
```

**장점:**
- ✅ Hugo 표준 구조
- ✅ 추가 작업 불필요
- ✅ 다른 정적 파일과 동일

**단점:**
- 없음

##### 옵션 2: Hugo 페이지로 통합 ⚠️

**content/board.md 생성:**
```markdown
---
title: "게시판"
layout: "board"
---
```

**layouts/page/board.html:**
```html
{{ define "main" }}
<!-- board.html 내용 복사 -->
{{ end }}
```

**장점:**
- ✅ Hugo 테마 일관성 (헤더, 푸터)
- ✅ SEO 메타 태그 자동

**단점:**
- ⚠️ JavaScript 중복
- ⚠️ 복잡도 증가
- ⚠️ 독립 SPA처럼 작동 어려움

**트레이드오프:**

| 항목 | static/ (옵션1) | Hugo 페이지 (옵션2) |
|------|----------------|-------------------|
| 단순성 | ✅ 매우 단순 | ⚠️ 복잡 |
| 독립성 | ✅ 완전 독립 | ❌ Hugo 의존 |
| 유지보수 | ✅ 쉬움 | ⚠️ 템플릿 이해 필요 |

##### 옵션 3: React/Vue SPA ❌

**별도 프로젝트:**
```
board-frontend/
  ├── src/
  ├── package.json
  └── build/ → static/board/
```

**장점:**
- ✅ 최신 Frontend 프레임워크

**단점:**
- ❌ 오버킬 (현재 Vanilla JS)
- ❌ 빌드 복잡도 증가
- ❌ 번들 크기 증가 (~200KB)

**결론:** 현재 규모에 과함.

#### 최종 선택: 옵션 1 (static/ 유지)

**이유:**
1. Hugo 표준 (static/은 정적 파일 표준 위치)
2. 단순함 (추가 작업 불필요)
3. 독립성 (board.html은 자체 완결 SPA)
4. 성능 (Vanilla JS, 번들러 불필요)

---

## P1: 중요 개선

### 1. Pagination (페이징)

#### 문제 상황

**현재 코드:**
```java
@GetMapping
public ResponseEntity<List<Post>> getAllPosts() {
    List<Post> posts = postService.getAllPosts();
    return ResponseEntity.ok(posts);  // 전체 조회!
}
```

**시나리오:**
- 게시글 1,000개
- 한 번에 1,000개 응답 → 느림, 메모리 낭비
- 프론트엔드도 1,000개 렌더링 → 브라우저 느림

#### 왜 Pagination이 필요한가?

**문제:**
1. **네트워크 대역폭**: 1,000개 × 1KB = 1MB 응답
2. **메모리**: JVM Heap에 1,000개 객체 로드
3. **응답 시간**: 직렬화 시간 증가
4. **프론트엔드**: 1,000개 DOM 생성 → 렌더링 느림

**실제 사례:**
- Instagram: 20개씩 로드
- Twitter: 10개씩 로드
- Reddit: 25개씩 로드

#### 해결 방법 비교

##### 옵션 1: Offset-based Pagination (권장) ✅

**구현:**
```java
@GetMapping
public ResponseEntity<Page<Post>> getAllPosts(
    @RequestParam(defaultValue = "0") int page,
    @RequestParam(defaultValue = "10") int size
) {
    Pageable pageable = PageRequest.of(page, size,
        Sort.by(Sort.Direction.DESC, "createdAt"));
    Page<Post> posts = postRepository.findAll(pageable);
    return ResponseEntity.ok(posts);
}
```

**SQL 쿼리:**
```sql
SELECT * FROM posts
ORDER BY created_at DESC
LIMIT 10 OFFSET 0;  -- 1페이지

LIMIT 10 OFFSET 10;  -- 2페이지
```

**응답:**
```json
{
  "content": [ /* 10개 */ ],
  "totalElements": 1000,
  "totalPages": 100,
  "last": false,
  "first": true
}
```

**장점:**
- ✅ 구현 쉬움 (Spring Data JPA 기본)
- ✅ 페이지 번호 직접 이동 (1, 2, 3...)
- ✅ 총 페이지 수 표시
- ✅ UI: `<< < 1 2 3 4 5 > >>`

**단점:**
- ⚠️ OFFSET 크면 느림 (OFFSET 10000 → 10000개 스캔 후 버림)
- ⚠️ 데이터 추가/삭제 시 페이지 중복/누락 가능

**성능:**

| OFFSET | 속도 | 사용 케이스 |
|--------|------|------------|
| 0-100 | 빠름 | 첫 10페이지 |
| 100-1000 | 중간 | 대부분 사용자 |
| 1000+ | 느림 | 거의 안 씀 |

##### 옵션 2: Cursor-based Pagination ⚠️

**구현:**
```java
@GetMapping
public ResponseEntity<List<Post>> getAllPosts(
    @RequestParam(required = false) Long cursor,
    @RequestParam(defaultValue = "10") int size
) {
    if (cursor == null) {
        return postRepository.findTop10ByOrderByIdDesc();
    }
    return postRepository.findTop10ByIdLessThanOrderByIdDesc(cursor);
}
```

**SQL:**
```sql
-- 1페이지
SELECT * FROM posts ORDER BY id DESC LIMIT 10;

-- 2페이지 (마지막 ID = 990)
SELECT * FROM posts WHERE id < 990 ORDER BY id DESC LIMIT 10;
```

**장점:**
- ✅ 성능 일정 (OFFSET 없음)
- ✅ 데이터 추가/삭제 시 안정적
- ✅ 무한 스크롤 UI 적합

**단점:**
- ❌ 페이지 번호 없음
- ❌ 중간 페이지 이동 불가
- ❌ 총 페이지 수 모름

**사용 사례:** Instagram, Twitter

##### 옵션 3: Keyset Pagination (고성능) ⚠️

**구현:**
```java
@GetMapping
public ResponseEntity<List<Post>> getAllPosts(
    @RequestParam(required = false) LocalDateTime lastCreatedAt,
    @RequestParam(required = false) Long lastId
) {
    return postRepository
        .findTop10ByCreatedAtLessThanOrCreatedAtEqualsAndIdLessThan(...);
}
```

**장점:**
- ✅ 최고 성능 (인덱스 사용)

**단점:**
- ❌ 매우 복잡
- ❌ 복합 인덱스 필요

**결론:** 대용량 (100만+ 행) 아니면 오버킬

#### 최종 선택: 옵션 1 (Offset-based)

**이유:**
1. Spring Data JPA 기본 (5분 구현)
2. UI 친화적 (페이지 번호, 총 페이지)
3. 충분한 성능 (1,000개 이하)
4. 확장 가능 (향후 Cursor 전환 쉬움)

**언제 Cursor로 전환?**
- 게시글 10,000개 이상
- 무한 스크롤 UI 필요
- 실시간 데이터 (채팅, 피드)

---

### 2. 에러 응답 표준화

#### 문제 상황

**현재 코드:**
```java
@GetMapping("/{id}")
public ResponseEntity<Post> getPostById(@PathVariable Long id) {
    try {
        return ResponseEntity.ok(postService.getPostById(id));
    } catch (RuntimeException e) {
        return ResponseEntity.notFound().build();  // ❌ 빈 응답!
    }
}
```

**현재 에러 응답:**
```bash
HTTP/1.1 404 Not Found
(빈 Body)
```

**프론트엔드 문제:**
```javascript
fetch('/api/posts/999')
  .then(res => res.json())
  .catch(err => {
    // "Unexpected end of JSON"
    // ← 진짜 에러가 뭔지 모름!
  });
```

#### 왜 표준화가 필요한가?

**RFC 7807 (Problem Details for HTTP APIs):**
```json
{
  "timestamp": "2026-01-21T10:00:00Z",
  "status": 404,
  "error": "Not Found",
  "message": "게시글을 찾을 수 없습니다. ID: 999",
  "path": "/api/posts/999"
}
```

**장점:**
1. **디버깅**: 개발자가 원인 파악 쉬움
2. **사용자 경험**: 의미있는 에러 메시지
3. **모니터링**: 에러 타입별 집계
4. **API 문서화**: 어떤 에러가 발생하는지 명시

#### 해결 방법 비교

##### 옵션 1: @RestControllerAdvice (권장) ✅

**1. Custom Exception:**
```java
public class PostNotFoundException extends RuntimeException {
    private final Long postId;

    public PostNotFoundException(Long postId) {
        super("게시글을 찾을 수 없습니다. ID: " + postId);
        this.postId = postId;
    }
}
```

**2. ErrorResponse DTO:**
```java
@Data
@AllArgsConstructor
public class ErrorResponse {
    private String timestamp;
    private int status;
    private String error;
    private String message;
    private String path;
}
```

**3. GlobalExceptionHandler:**
```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(PostNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(
        PostNotFoundException ex,
        HttpServletRequest request
    ) {
        ErrorResponse error = new ErrorResponse(
            LocalDateTime.now().toString(),
            404,
            "Not Found",
            ex.getMessage(),
            request.getRequestURI()
        );
        return ResponseEntity.status(404).body(error);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(...) {
        // Validation 에러 처리
    }
}
```

**장점:**
- ✅ 일관된 에러 형식
- ✅ 재사용 가능 (모든 Controller 적용)
- ✅ 자동 처리 (try-catch 불필요)
- ✅ Validation 에러도 표준화

**단점:**
- ⚠️ 초기 구현 시간 (30분)

##### 옵션 2: ResponseEntity 직접 반환 ❌

**코드:**
```java
@GetMapping("/{id}")
public ResponseEntity<?> getPostById(@PathVariable Long id) {
    try {
        return ResponseEntity.ok(postService.getPostById(id));
    } catch (RuntimeException e) {
        return ResponseEntity.status(404).body(
            Map.of("status", 404, "message", e.getMessage())
        );
    }
}
```

**단점:**
- ❌ 코드 중복 (모든 메서드에 try-catch)
- ❌ 일관성 부족
- ❌ Validation 에러 누락 가능

##### 옵션 3: Spring Boot 기본 핸들러 ❌

**기본 응답:**
```json
{
  "timestamp": "2026-01-21T10:00:00",
  "status": 404,
  "error": "Not Found",
  "path": "/api/posts/999"
}
```

**문제:**
- ❌ message 없음 (왜 404인지 모름)
- ❌ 커스터마이징 어려움

#### 트레이드오프

| 방법 | 구현 시간 | 일관성 | 확장성 | 유지보수 |
|------|----------|--------|--------|----------|
| @RestControllerAdvice | 30분 | ✅ 높음 | ✅ 쉬움 | ✅ 쉬움 |
| 직접 반환 | 5분 | ❌ 낮음 | ❌ 어려움 | ❌ 어려움 |
| 기본 핸들러 | 0분 | ⚠️ 중간 | ❌ 불가능 | N/A |

#### 최종 선택: 옵션 1 (@RestControllerAdvice)

**이유:**
1. Spring 표준 패턴
2. 확장성 (새 Exception 추가 쉬움)
3. 일관성 (모든 API 에러 형식 동일)
4. 프론트엔드 친화 (파싱 로직 1개만)

---

### 3. API 문서화 (Swagger/OpenAPI)

#### 문제 상황

**현재:**
- API 문서 없음
- 프론트엔드 개발자가 어떤 API가 있는지 모름
- 커뮤니케이션 비용 높음

#### 왜 문서화가 필요한가?

**OpenAPI (Swagger):**
- RESTful API 명세 표준 (JSON/YAML)
- 자동 UI 생성 (Swagger UI)
- 코드 → 문서 자동 생성

**Swagger UI:**
```
https://blog.jiminhome.shop/swagger-ui.html

GET /api/posts
  Parameters:
    - page (int): 페이지 번호
    - size (int): 페이지 크기
  Responses:
    200: Page<Post>
    500: ErrorResponse

[Try it out] → 실제 API 테스트
```

#### 해결 방법 비교

##### 옵션 1: springdoc-openapi (권장) ✅

**pom.xml:**
```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>2.3.0</version>
</dependency>
```

**자동 생성:**
- `/swagger-ui.html`: Swagger UI
- `/v3/api-docs`: OpenAPI JSON

**추가 Annotation (선택):**
```java
@RestController
@Tag(name = "Posts", description = "게시글 API")
public class PostController {

    @GetMapping("/{id}")
    @Operation(summary = "게시글 조회")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "성공"),
        @ApiResponse(responseCode = "404", description = "없음")
    })
    public ResponseEntity<Post> getPostById(@PathVariable Long id) {
        // ...
    }
}
```

**장점:**
- ✅ 자동 문서 생성 (코드 → 문서)
- ✅ UI 제공 (테스트 가능)
- ✅ Spring Boot 3.x 지원
- ✅ 최소 설정 (pom.xml만)

**단점:**
- ⚠️ 의존성 추가 (~2MB)
- ⚠️ 운영에서 `/swagger-ui` 노출 주의

**보안 설정:**
```yaml
# application-prod.yml
springdoc:
  swagger-ui:
    enabled: false  # 운영 비활성화
```

##### 옵션 2: 수동 문서 (Markdown) ⚠️

**docs/API.md:**
```markdown
## POST /api/posts

### Request
```json
{"title": "제목"}
```

### Response (201)
```json
{"id": 1}
```
```

**장점:**
- ✅ 의존성 없음
- ✅ 자유로운 형식

**단점:**
- ❌ 수동 유지보수 (코드 변경 시 문서도 수정)
- ❌ 실행 불가 (테스트 못 함)
- ❌ 버전 불일치 가능

##### 옵션 3: Postman Collection ⚠️

**장점:**
- ✅ 테스트 가능
- ✅ 팀 공유

**단점:**
- ❌ 별도 도구 필요
- ❌ 자동 생성 불가
- ❌ 웹 접근 불가

#### 트레이드오프

| 방법 | 자동화 | 테스트 | 유지보수 | 접근성 |
|------|--------|--------|----------|--------|
| springdoc-openapi | ✅ | ✅ | ✅ 쉬움 | ✅ 웹 |
| Markdown | ❌ | ❌ | ❌ 어려움 | ⚠️ 파일 |
| Postman | ⚠️ | ✅ | ⚠️ 중간 | ❌ 앱 |

#### 최종 선택: 옵션 1 (springdoc-openapi)

**이유:**
1. 자동화 (코드 → 문서)
2. 실행 가능 (Swagger UI 테스트)
3. 표준 (OpenAPI Spec)
4. 최소 노력 (pom.xml 1줄)

---

## P2: 장기 개선

### 1. Spring Security + JWT 인증

**현재 문제:**
- 누구나 게시글 작성/삭제 가능
- 인증/인가 없음

**개선 방향:**
- Spring Security 적용
- JWT 토큰 기반 인증
- 사용자별 권한 (작성자만 수정/삭제)

### 2. Redis 캐싱

**현재 문제:**
- 매번 DB 조회

**개선 방향:**
- 조회수 많은 게시글 캐싱
- `@Cacheable` 적용
- TTL 설정 (5분)

### 3. 검색 최적화 (Full-text Search)

**현재 문제:**
- `LIKE %keyword%`는 Full Scan

**개선 방향:**
```sql
ALTER TABLE posts ADD FULLTEXT(title, content);

@Query(value = "SELECT * FROM posts WHERE MATCH(title, content) AGAINST(?1)",
       nativeQuery = true)
List<Post> fullTextSearch(String keyword);
```

### 4. 댓글 기능

**추가 엔티티:**
```java
@Entity
public class Comment {
    @Id @GeneratedValue
    private Long id;

    @ManyToOne
    private Post post;

    private String content;
    private String author;
}
```

### 5. 파일 업로드

**구현 방향:**
- 이미지 첨부 기능
- S3/MinIO 연동
- 썸네일 생성

---

**작성일:** 2026-01-21
**다음 업데이트:** P0 작업 완료 후
