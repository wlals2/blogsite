# WAS 개선 작업 - 배경 지식 및 트레이드오프 분석

> 왜 이 작업들을 해야 하는가? 대안은 무엇이며, 각 선택의 장단점은?

---

## 목차

1. [nginx 프록시 설정](#1-nginx-프록시-설정)
2. [board.html 배포 전략](#2-boardhtml-배포-전략)
3. [API 기능 개선](#3-api-기능-개선)

---

## 1. nginx 프록시 설정

### 문제 상황

**현재:**
```
Client → Cloudflare → Ingress → web-service (nginx)
                                      ↓ (프록시 없음!)
                                  was-service (Spring Boot)
```

**에러:**
```bash
curl https://blog.jiminhome.shop/api/posts
# → 404 Not Found (nginx가 /api 경로를 모름)
```

### 왜 프록시가 필요한가?

#### 배경 지식: Reverse Proxy Pattern

**Reverse Proxy란?**
- 클라이언트는 nginx만 알고, 실제 Backend(WAS)는 숨김
- nginx가 요청을 받아서 Backend로 전달 (Proxy)
- Backend 응답을 다시 클라이언트에게 전달

**장점:**
1. **보안**: WAS를 직접 노출하지 않음
2. **로드 밸런싱**: nginx가 여러 WAS로 분산
3. **SSL Termination**: nginx에서 HTTPS 처리, WAS는 HTTP만
4. **캐싱**: 정적 응답을 nginx에서 캐싱
5. **압축**: gzip 압축을 nginx에서 처리

**우리 구조:**
```
nginx (web-service)
  ├─> /              → Hugo 정적 파일 (블로그)
  └─> /api/          → WAS 프록시 (Spring Boot API)
```

### 해결 방법 비교

#### 옵션 1: nginx 프록시 설정 (권장) ✅

**web-nginx-config.yaml:**
```nginx
server {
    listen 80;

    # Hugo 정적 파일
    location / {
        root /usr/share/nginx/html;
        try_files $uri $uri/ /index.html;
    }

    # WAS API 프록시
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
- ✅ SSL Termination (Ingress에서 처리)
- ✅ 표준 아키텍처 패턴

**단점:**
- ⚠️ nginx 재시작 필요 (Rolling Update)
- ⚠️ nginx 설정 복잡도 증가
- ⚠️ nginx가 SPOF (Single Point of Failure) - 하지만 Pod 2개라 괜찮음

**트레이드오프:**
| 항목 | 현재 | 프록시 추가 후 |
|------|------|---------------|
| **복잡도** | 낮음 | 중간 |
| **성능** | N/A | nginx 오버헤드 ~1ms |
| **보안** | N/A | WAS 직접 노출 안 됨 |
| **CORS** | N/A | 불필요 |

#### 옵션 2: Ingress에서 직접 WAS 라우팅 ❌

**blog-ingress.yaml:**
```yaml
paths:
- path: /api
  backend:
    service:
      name: was-service  # nginx 건너뛰고 직접 WAS
      port: 8080
```

**장점:**
- ✅ 단순함 (nginx 설정 불필요)
- ✅ nginx 오버헤드 없음

**단점:**
- ❌ WAS가 직접 노출됨 (보안 위험)
- ❌ nginx 캐싱/압축 사용 불가
- ❌ 정적 파일(/)과 API(/api)가 다른 Service
- ❌ Istio mTLS 설정 복잡 (Ingress → WAS 직접 통신)
- ❌ 표준 패턴과 다름

**비추천 이유:**
1. WAS를 직접 노출하는 건 보안 Best Practice 위반
2. 향후 API Gateway 추가 시 nginx를 거쳐야 함
3. Cloudflare → Ingress → WAS 경로가 비효율적

#### 옵션 3: API Gateway (Kong, NGINX Plus) ⚠️

**구조:**
```
Ingress → web-service (정적 파일)
       → api-gateway → was-service
```

**장점:**
- ✅ API 전용 기능 (Rate Limiting, Authentication)
- ✅ API 버저닝 (v1, v2)
- ✅ API Aggregation (Microservices)

**단점:**
- ❌ 오버킬 (현재 API 1개뿐)
- ❌ 복잡도 매우 높음
- ❌ 리소스 추가 필요 (Kong Pod)

**결론:** 현재 규모에는 과함. 향후 API가 10개 이상 되면 고려.

#### 옵션 4: CORS 허용 + 별도 도메인 ❌

**구조:**
```
blog.jiminhome.shop  → web-service (블로그)
api.jiminhome.shop   → was-service (API)
```

**WAS CORS 설정:**
```java
@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("https://blog.jiminhome.shop");
    }
}
```

**장점:**
- ✅ WEB/WAS 완전 분리
- ✅ 독립적 스케일링

**단점:**
- ❌ CORS Preflight 요청 (OPTIONS) 오버헤드
- ❌ 도메인 2개 관리
- ❌ SSL 인증서 2개 필요
- ❌ 브라우저 쿠키 공유 문제
- ❌ 복잡도 증가

**비추천 이유:**
- CORS는 보안상 필요할 때만 사용 (3rd-party API)
- Same-Origin이 가장 단순하고 안전

### 최종 선택: 옵션 1 (nginx 프록시)

**이유:**
1. **표준 패턴**: 대부분의 웹 서비스가 사용
2. **보안**: WAS 직접 노출 방지
3. **단일 도메인**: CORS 불필요
4. **확장성**: 향후 API 캐싱, Rate Limiting 추가 쉬움
5. **리소스 효율**: 추가 Pod 불필요

---

## 2. board.html 배포 전략

### 문제 상황

**현재:**
- `~/blogsite/static/board.html` 파일 존재
- 하지만 Hugo 빌드 시 무시됨
- 사용자가 접근 불가

### 배경 지식: Hugo의 정적 파일 처리

#### Hugo 디렉토리 구조

```
blogsite/
├── content/          # Markdown 콘텐츠 (.md)
│   └── posts/
├── layouts/          # HTML 템플릿
│   └── partials/
├── static/           # 정적 파일 (CSS, JS, images)
│   ├── css/
│   └── board.html    # ← 여기!
└── public/           # 빌드 결과물 (hugo 명령 실행 시)
    └── board.html    # ← static/에서 자동 복사됨
```

**Hugo 빌드 프로세스:**
1. `content/**/*.md` → HTML 변환 (템플릿 적용)
2. `static/**/*` → `public/`로 **그대로 복사**
3. `public/`를 nginx에 배포

**따라서:**
- `static/board.html`은 이미 올바른 위치!
- `hugo` 명령만 실행하면 `public/board.html`로 복사됨
- **문제는 빌드 후 배포가 안 된 것**

### 해결 방법 비교

#### 옵션 1: static/ 유지 + 배포 확인 (권장) ✅

**현재 상태:**
```bash
~/blogsite/static/board.html  # ✅ 이미 올바른 위치
```

**Hugo 빌드:**
```bash
cd ~/blogsite
hugo --minify
ls public/board.html  # ✅ 자동 복사됨
```

**배포 확인:**
```bash
# GitHub Actions가 빌드 후 /var/www/blog에 배포했는지 확인
ls /var/www/blog/board.html

# 만약 없다면 워크플로우 문제
```

**장점:**
- ✅ Hugo 표준 구조
- ✅ 추가 작업 불필요
- ✅ 다른 정적 파일(CSS, JS)과 동일한 방식

**단점:**
- 없음 (표준 방식)

**확인 사항:**
1. `public/board.html` 존재 여부
2. `/var/www/blog/board.html` 존재 여부
3. GitHub Actions 배포 로그

#### 옵션 2: Hugo 페이지로 통합 ⚠️

**content/board.md 생성:**
```markdown
---
title: "게시판"
layout: "board"
type: "page"
---
```

**layouts/page/board.html 생성:**
```html
{{ define "main" }}
<div id="app">
  <!-- board.html 내용 복사 -->
</div>
<script>
  // JavaScript 코드
</script>
{{ end }}
```

**장점:**
- ✅ Hugo 테마 일관성 (헤더, 푸터 자동)
- ✅ SEO 메타 태그 자동
- ✅ Hugo shortcode 사용 가능

**단점:**
- ⚠️ JavaScript 중복 (Hugo 템플릿 + board.js)
- ⚠️ 복잡도 증가
- ⚠️ 독립적인 SPA처럼 작동하기 어려움
- ⚠️ board.html을 템플릿으로 변환해야 함

**트레이드오프:**
| 항목 | static/ (옵션1) | Hugo 페이지 (옵션2) |
|------|----------------|-------------------|
| **단순성** | ✅ 매우 단순 | ⚠️ 복잡 |
| **독립성** | ✅ 완전 독립 | ❌ Hugo 의존 |
| **유지보수** | ✅ 쉬움 | ⚠️ 템플릿 이해 필요 |
| **테마 일관성** | ❌ 없음 | ✅ 있음 |

#### 옵션 3: React/Vue SPA 별도 빌드 ❌

**구조:**
```
board-frontend/  (별도 프로젝트)
  ├── src/
  ├── package.json
  └── build/ → static/board/
```

**빌드:**
```bash
cd board-frontend
npm run build  # → ../static/board/
```

**장점:**
- ✅ 최신 Frontend 프레임워크
- ✅ Hot Reload 개발 환경
- ✅ TypeScript 지원

**단점:**
- ❌ 오버킬 (현재 board.html은 Vanilla JS)
- ❌ 빌드 복잡도 증가
- ❌ 번들 크기 증가 (~200KB)
- ❌ 유지보수 복잡

**결론:** 현재 규모에는 과함. 향후 복잡한 SPA 필요 시 고려.

### 최종 선택: 옵션 1 (static/ 유지)

**이유:**
1. **Hugo 표준**: `static/`은 정적 파일 표준 위치
2. **단순함**: 추가 작업 불필요
3. **독립성**: board.html은 자체 완결적 SPA
4. **성능**: Vanilla JS (번들러 불필요)

**확인 필요:**
```bash
# 1. Hugo 빌드 확인
hugo --minify
ls public/board.html

# 2. 배포 확인
ls /var/www/blog/board.html

# 3. 접근 테스트
curl https://blog.jiminhome.shop/board.html
```

---

## 3. API 기능 개선

### 3-1. Pagination (페이징)

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

**배경 지식: N+1 Problem과 대용량 데이터**

**데이터베이스 쿼리:**
```sql
SELECT * FROM posts;  -- 1,000개 행 반환
```

**문제:**
1. **네트워크 대역폭**: 1,000개 × 1KB = 1MB 응답
2. **메모리**: JVM Heap에 1,000개 객체 로드
3. **응답 시간**: 직렬화 시간 증가
4. **프론트엔드**: 1,000개 DOM 생성 → 렌더링 느림

**실제 사례:**
- Instagram: 게시물 20개씩만 로드
- Twitter: 트윗 10개씩 로드
- Reddit: 게시글 25개씩 로드

#### 해결 방법 비교

##### 옵션 1: Offset-based Pagination (권장) ✅

**구현:**
```java
@GetMapping
public ResponseEntity<Page<Post>> getAllPosts(
    @RequestParam(defaultValue = "0") int page,      // 페이지 번호
    @RequestParam(defaultValue = "10") int size,     // 페이지 크기
    @RequestParam(defaultValue = "createdAt,desc") String sort
) {
    Pageable pageable = PageRequest.of(page, size,
        Sort.by(Sort.Direction.DESC, "createdAt"));

    Page<Post> posts = postRepository.findAll(pageable);
    return ResponseEntity.ok(posts);
}
```

**SQL 쿼리 (MySQL):**
```sql
SELECT * FROM posts
ORDER BY created_at DESC
LIMIT 10 OFFSET 0;  -- 1페이지

SELECT * FROM posts
ORDER BY created_at DESC
LIMIT 10 OFFSET 10;  -- 2페이지
```

**응답 형식:**
```json
{
  "content": [ /* 10개 게시글 */ ],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 10
  },
  "totalElements": 1000,
  "totalPages": 100,
  "last": false,
  "first": true
}
```

**장점:**
- ✅ 구현 쉬움 (Spring Data JPA 기본 제공)
- ✅ 페이지 번호 직접 이동 가능 (1, 2, 3...)
- ✅ 총 페이지 수 표시 가능
- ✅ UI: `<< < 1 2 3 4 5 > >>`

**단점:**
- ⚠️ OFFSET이 크면 느림 (OFFSET 10000 → 10000개 스캔 후 버림)
- ⚠️ 데이터 추가/삭제 시 페이지 중복/누락 가능

**성능:**
```sql
-- OFFSET 0: 0.01초
SELECT * FROM posts LIMIT 10 OFFSET 0;

-- OFFSET 10000: 0.5초 (느림!)
SELECT * FROM posts LIMIT 10 OFFSET 10000;
```

**트레이드오프:**
| OFFSET | 속도 | 사용 케이스 |
|--------|------|------------|
| 0-100 | 빠름 | 첫 10페이지 |
| 100-1000 | 중간 | 대부분 사용자 |
| 1000+ | 느림 | 거의 안 씀 (구글 검색도 10페이지까지) |

##### 옵션 2: Cursor-based Pagination (무한 스크롤) ⚠️

**구현:**
```java
@GetMapping
public ResponseEntity<List<Post>> getAllPosts(
    @RequestParam(required = false) Long cursor,  // 마지막 ID
    @RequestParam(defaultValue = "10") int size
) {
    List<Post> posts;
    if (cursor == null) {
        posts = postRepository.findTop10ByOrderByIdDesc();
    } else {
        posts = postRepository.findTop10ByIdLessThanOrderByIdDesc(cursor);
    }
    return ResponseEntity.ok(posts);
}
```

**SQL 쿼리:**
```sql
-- 1페이지
SELECT * FROM posts
ORDER BY id DESC
LIMIT 10;

-- 2페이지 (마지막 ID = 990)
SELECT * FROM posts
WHERE id < 990
ORDER BY id DESC
LIMIT 10;
```

**장점:**
- ✅ 성능 일정 (OFFSET 없음)
- ✅ 데이터 추가/삭제 시 안정적
- ✅ 무한 스크롤 UI에 적합

**단점:**
- ❌ 페이지 번호 없음 (1, 2, 3...)
- ❌ 중간 페이지 이동 불가
- ❌ 총 페이지 수 모름
- ❌ 정렬 복잡 (createdAt이면 cursor = createdAt+id 필요)

**사용 사례:**
- Instagram, Twitter (무한 스크롤)
- Facebook News Feed

##### 옵션 3: Keyset Pagination (고성능) ⚠️

**구현:**
```java
@GetMapping
public ResponseEntity<List<Post>> getAllPosts(
    @RequestParam(required = false) LocalDateTime lastCreatedAt,
    @RequestParam(required = false) Long lastId,
    @RequestParam(defaultValue = "10") int size
) {
    if (lastCreatedAt == null) {
        return postRepository.findTop10ByOrderByCreatedAtDesc();
    }
    return postRepository
        .findTop10ByCreatedAtLessThanOrCreatedAtEqualsAndIdLessThan(
            lastCreatedAt, lastCreatedAt, lastId
        );
}
```

**SQL 쿼리:**
```sql
SELECT * FROM posts
WHERE (created_at, id) < ('2026-01-20 10:00:00', 990)
ORDER BY created_at DESC, id DESC
LIMIT 10;
```

**인덱스 필요:**
```sql
CREATE INDEX idx_created_id ON posts(created_at DESC, id DESC);
```

**장점:**
- ✅ 최고 성능 (인덱스 사용)
- ✅ OFFSET 없음

**단점:**
- ❌ 매우 복잡
- ❌ 복합 인덱스 필요
- ❌ 정렬 변경 어려움

**결론:** 대용량 (100만+ 행) 아니면 오버킬

#### 최종 선택: 옵션 1 (Offset-based Pagination)

**이유:**
1. **Spring Data JPA 기본 제공**: 5분 구현
2. **UI 친화적**: 페이지 번호, 총 페이지 수
3. **충분한 성능**: 게시글 1,000개 이하면 OFFSET 문제 없음
4. **확장 가능**: 향후 Cursor로 전환 쉬움

**언제 Cursor로 전환?**
- 게시글 10,000개 이상
- 무한 스크롤 UI 필요
- 실시간 데이터 (채팅, 피드)

---

### 3-2. 에러 응답 표준화

#### 문제 상황

**현재 코드:**
```java
@GetMapping("/{id}")
public ResponseEntity<Post> getPostById(@PathVariable Long id) {
    try {
        Post post = postService.getPostById(id);
        return ResponseEntity.ok(post);
    } catch (RuntimeException e) {
        return ResponseEntity.notFound().build();  // ❌ 에러 메시지 없음!
    }
}
```

**현재 에러 응답:**
```bash
HTTP/1.1 404 Not Found
(빈 응답 Body)
```

**프론트엔드 문제:**
```javascript
fetch('/api/posts/999')
  .then(res => res.json())
  .catch(err => {
    // err.message = "Unexpected end of JSON input"
    // ← 진짜 에러가 뭔지 모름!
  });
```

#### 왜 표준화가 필요한가?

**배경 지식: REST API 에러 처리 Best Practices**

**RFC 7807 (Problem Details for HTTP APIs):**
```json
{
  "type": "https://api.example.com/errors/not-found",
  "title": "Not Found",
  "status": 404,
  "detail": "게시글을 찾을 수 없습니다. ID: 999",
  "instance": "/api/posts/999",
  "timestamp": "2026-01-21T10:00:00Z"
}
```

**왜 필요한가?**
1. **디버깅**: 개발자가 원인 파악 쉬움
2. **사용자 경험**: 의미있는 에러 메시지 표시
3. **모니터링**: 에러 타입별 집계 (404, 500...)
4. **API 문서화**: 어떤 에러가 발생하는지 명시

#### 해결 방법 비교

##### 옵션 1: Custom Exception + @RestControllerAdvice (권장) ✅

**1단계: Custom Exception 정의**
```java
// PostNotFoundException.java
public class PostNotFoundException extends RuntimeException {
    private final Long postId;

    public PostNotFoundException(Long postId) {
        super("게시글을 찾을 수 없습니다. ID: " + postId);
        this.postId = postId;
    }

    public Long getPostId() {
        return postId;
    }
}
```

**2단계: ErrorResponse DTO**
```java
@Data
@AllArgsConstructor
public class ErrorResponse {
    private String timestamp;
    private int status;
    private String error;
    private String message;
    private String path;
    private Map<String, Object> details;  // 추가 정보
}
```

**3단계: GlobalExceptionHandler**
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
            request.getRequestURI(),
            Map.of("postId", ex.getPostId())
        );
        return ResponseEntity.status(404).body(error);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
        MethodArgumentNotValidException ex,
        HttpServletRequest request
    ) {
        Map<String, String> errors = ex.getBindingResult()
            .getFieldErrors()
            .stream()
            .collect(Collectors.toMap(
                FieldError::getField,
                FieldError::getDefaultMessage
            ));

        ErrorResponse error = new ErrorResponse(
            LocalDateTime.now().toString(),
            400,
            "Validation Failed",
            "입력값이 올바르지 않습니다",
            request.getRequestURI(),
            Map.of("errors", errors)
        );
        return ResponseEntity.status(400).body(error);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(
        Exception ex,
        HttpServletRequest request
    ) {
        ErrorResponse error = new ErrorResponse(
            LocalDateTime.now().toString(),
            500,
            "Internal Server Error",
            "서버 오류가 발생했습니다",
            request.getRequestURI(),
            Map.of("exception", ex.getClass().getSimpleName())
        );
        return ResponseEntity.status(500).body(error);
    }
}
```

**4단계: Controller 수정**
```java
@GetMapping("/{id}")
public ResponseEntity<Post> getPostById(@PathVariable Long id) {
    Post post = postService.getPostById(id);  // Exception 던지면 GlobalHandler가 처리
    return ResponseEntity.ok(post);
}
```

**에러 응답 예시:**
```json
// GET /api/posts/999
{
  "timestamp": "2026-01-21T10:00:00",
  "status": 404,
  "error": "Not Found",
  "message": "게시글을 찾을 수 없습니다. ID: 999",
  "path": "/api/posts/999",
  "details": {
    "postId": 999
  }
}

// POST /api/posts (title 누락)
{
  "timestamp": "2026-01-21T10:00:00",
  "status": 400,
  "error": "Validation Failed",
  "message": "입력값이 올바르지 않습니다",
  "path": "/api/posts",
  "details": {
    "errors": {
      "title": "제목은 필수입니다",
      "author": "작성자는 최대 50자입니다"
    }
  }
}
```

**장점:**
- ✅ 일관된 에러 형식
- ✅ 재사용 가능 (모든 Controller에 적용)
- ✅ 자동 처리 (try-catch 불필요)
- ✅ Validation 에러도 표준화
- ✅ 프론트엔드 파싱 쉬움

**단점:**
- ⚠️ 초기 구현 시간 (30분)
- ⚠️ Exception 클래스 여러 개 필요

##### 옵션 2: ResponseEntity.status() 직접 반환 ❌

**코드:**
```java
@GetMapping("/{id}")
public ResponseEntity<?> getPostById(@PathVariable Long id) {
    try {
        Post post = postService.getPostById(id);
        return ResponseEntity.ok(post);
    } catch (RuntimeException e) {
        Map<String, Object> error = Map.of(
            "status", 404,
            "message", e.getMessage()
        );
        return ResponseEntity.status(404).body(error);
    }
}
```

**단점:**
- ❌ 코드 중복 (모든 메서드에 try-catch)
- ❌ 일관성 부족 (개발자마다 다름)
- ❌ Validation 에러 처리 누락 가능

##### 옵션 3: Spring Boot 기본 에러 핸들러 ❌

**현재 Spring Boot 기본 응답:**
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
- ❌ 프론트엔드가 title/content/author 누락 구분 못 함

#### 트레이드오프

| 방법 | 구현 시간 | 일관성 | 확장성 | 유지보수 |
|------|----------|--------|--------|----------|
| @RestControllerAdvice | 30분 | ✅ 높음 | ✅ 쉬움 | ✅ 쉬움 |
| 직접 반환 | 5분 | ❌ 낮음 | ❌ 어려움 | ❌ 어려움 |
| 기본 핸들러 | 0분 | ⚠️ 중간 | ❌ 불가능 | N/A |

#### 최종 선택: 옵션 1 (@RestControllerAdvice)

**이유:**
1. **Spring 표준 패턴**: 대부분의 Spring 프로젝트 사용
2. **확장성**: 새 Exception 추가 쉬움
3. **일관성**: 모든 API 에러 형식 동일
4. **프론트엔드 친화**: 파싱 로직 1개만

**구현 순서:**
1. ErrorResponse DTO (5분)
2. Custom Exceptions (10분)
3. GlobalExceptionHandler (15분)
4. 테스트 (10분)

---

### 3-3. API 문서화 (Swagger/OpenAPI)

#### 문제 상황

**현재:**
- API 문서가 없음
- 프론트엔드 개발자가 어떤 API가 있는지 모름
- 요청/응답 형식 커뮤니케이션 비용 높음

#### 왜 API 문서화가 필요한가?

**배경 지식: OpenAPI Specification**

**OpenAPI (Swagger)란?**
- RESTful API 명세 표준 (JSON/YAML)
- 자동 UI 생성 (Swagger UI)
- 코드 → 문서 자동 생성

**Swagger UI 예시:**
```
https://blog.jiminhome.shop/swagger-ui.html

GET /api/posts
  Description: 모든 게시글 조회
  Parameters:
    - page (int, optional): 페이지 번호 (default: 0)
    - size (int, optional): 페이지 크기 (default: 10)
  Responses:
    200: Page<Post>
    500: ErrorResponse

[Try it out] 버튼 → 실제 API 테스트
```

#### 해결 방법 비교

##### 옵션 1: springdoc-openapi (권장) ✅

**pom.xml 추가:**
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
@RequestMapping("/api/posts")
@Tag(name = "Posts", description = "게시글 API")
public class PostController {

    @GetMapping("/{id}")
    @Operation(summary = "게시글 조회", description = "ID로 특정 게시글을 조회합니다")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "성공"),
        @ApiResponse(responseCode = "404", description = "게시글 없음")
    })
    public ResponseEntity<Post> getPostById(
        @Parameter(description = "게시글 ID") @PathVariable Long id
    ) {
        // ...
    }
}
```

**장점:**
- ✅ 자동 문서 생성 (코드 → 문서)
- ✅ UI 제공 (테스트 가능)
- ✅ Spring Boot 3.x 최신 지원
- ✅ 최소 설정 (pom.xml만)

**단점:**
- ⚠️ 의존성 추가 (~2MB)
- ⚠️ 운영 환경에서 `/swagger-ui` 노출 주의

**보안 설정:**
```yaml
# application-prod.yml
springdoc:
  swagger-ui:
    enabled: false  # 운영 환경 비활성화
```

##### 옵션 2: 수동 문서 (Markdown) ⚠️

**docs/API.md:**
```markdown
## POST /api/posts

### Request
```json
{
  "title": "제목",
  "content": "내용",
  "author": "작성자"
}
```

### Response (201)
```json
{
  "id": 1,
  "title": "제목",
  ...
}
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

**Postman:**
- API 테스트 도구
- Collection 공유 가능

**장점:**
- ✅ 테스트 가능
- ✅ 팀 공유

**단점:**
- ❌ 별도 도구 필요
- ❌ 자동 생성 불가
- ❌ 웹에서 접근 불가

#### 트레이드오프

| 방법 | 자동화 | 테스트 | 유지보수 | 접근성 |
|------|--------|--------|----------|--------|
| springdoc-openapi | ✅ | ✅ | ✅ 쉬움 | ✅ 웹 |
| Markdown | ❌ | ❌ | ❌ 어려움 | ⚠️ 파일 |
| Postman | ⚠️ | ✅ | ⚠️ 중간 | ❌ 앱 |

#### 최종 선택: 옵션 1 (springdoc-openapi)

**이유:**
1. **자동화**: 코드 → 문서 자동 생성
2. **실행 가능**: Swagger UI에서 직접 테스트
3. **표준**: OpenAPI Spec은 업계 표준
4. **최소 노력**: pom.xml 1줄 추가

**사용 방법:**
1. pom.xml에 의존성 추가
2. 애플리케이션 재시작
3. `http://localhost:8080/swagger-ui.html` 접근
4. 운영 환경: `springdoc.swagger-ui.enabled=false`

---

## 요약 및 우선순위

### 즉시 작업 (P0) - 오늘

| 작업 | 시간 | 이유 | 선택한 방법 |
|------|------|------|------------|
| **nginx 프록시** | 10분 | 외부 접근 불가 해결 | nginx location /api/ 추가 |
| **board.html 배포** | 5분 | 사용자가 게시판 사용 | Hugo 빌드 확인 |

### 이번 주 작업 (P1)

| 작업 | 시간 | 이유 | 선택한 방법 |
|------|------|------|------------|
| **Pagination** | 30분 | 성능, 사용자 경험 | Offset-based (Spring Data JPA) |
| **에러 표준화** | 30분 | 디버깅, API 품질 | @RestControllerAdvice |
| **API 문서화** | 10분 | 개발 생산성 | springdoc-openapi |

### 장기 계획 (P2)

- Spring Security
- Redis 캐싱
- 검색 최적화 (Full-text Search)

---

## 트레이드오프 총정리

### 1. nginx 프록시

| 옵션 | 복잡도 | 성능 | 보안 | 권장 |
|------|--------|------|------|------|
| nginx 프록시 | 중간 | 높음 | ✅ | ✅ |
| Ingress 직접 | 낮음 | 매우 높음 | ❌ | ❌ |
| API Gateway | 높음 | 중간 | ✅ | 향후 |
| CORS 허용 | 낮음 | 높음 | ⚠️ | ❌ |

### 2. board.html 배포

| 옵션 | 단순성 | 독립성 | 테마 일관성 | 권장 |
|------|--------|--------|-------------|------|
| static/ 유지 | ✅ | ✅ | ❌ | ✅ |
| Hugo 페이지 | ⚠️ | ❌ | ✅ | 향후 |
| React SPA | ❌ | ✅ | ❌ | 오버킬 |

### 3. Pagination

| 옵션 | 성능 | UI | 복잡도 | 권장 |
|------|------|----|----|------|
| Offset | 중간 | ✅ 페이지 번호 | 낮음 | ✅ |
| Cursor | 높음 | ⚠️ 무한 스크롤 | 중간 | 대용량 |
| Keyset | 매우 높음 | ⚠️ 무한 스크롤 | 높음 | 100만+ |

### 4. 에러 표준화

| 옵션 | 일관성 | 확장성 | 초기 비용 | 권장 |
|------|--------|--------|-----------|------|
| @RestControllerAdvice | ✅ | ✅ | 30분 | ✅ |
| 직접 반환 | ❌ | ❌ | 5분 | ❌ |
| 기본 핸들러 | ⚠️ | ❌ | 0분 | ❌ |

### 5. API 문서화

| 옵션 | 자동화 | 테스트 | 표준 | 권장 |
|------|--------|--------|------|------|
| springdoc-openapi | ✅ | ✅ | ✅ | ✅ |
| Markdown | ❌ | ❌ | ⚠️ | ❌ |
| Postman | ⚠️ | ✅ | ⚠️ | 보조 |

---

## 다음 단계

1. **nginx 프록시 설정 + board.html 배포** (15분)
   - 외부 접근 가능하게
2. **API 개선 3종 세트** (1시간)
   - Pagination, 에러 표준화, Swagger
3. **테스트 및 문서화** (30분)

**시작하시겠습니까?** 어떤 작업부터 진행할까요?
