# WAS API 레퍼런스

> Spring Boot 게시판 API 완벽 가이드

---

## Base URL

```
Production: https://blog.jiminhome.shop/api
Local: http://localhost:8080/api
```

---

## API 목록

| Method | Endpoint | 설명 | 인증 |
|--------|----------|------|------|
| GET | `/posts` | 전체 게시글 조회 (최신순) | ❌ |
| GET | `/posts/{id}` | 특정 게시글 조회 | ❌ |
| POST | `/posts` | 게시글 작성 | ❌ |
| PUT | `/posts/{id}` | 게시글 수정 | ❌ |
| DELETE | `/posts/{id}` | 게시글 삭제 | ❌ |
| GET | `/posts/search?keyword=XXX` | 제목 검색 | ❌ |

---

## 1. 전체 게시글 조회

### Request

```http
GET /api/posts
```

### Response (200 OK)

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

### cURL 예시

```bash
curl https://blog.jiminhome.shop/api/posts
```

---

## 2. 특정 게시글 조회

### Request

```http
GET /api/posts/{id}
```

**Path Parameters:**
- `id` (Long, required): 게시글 ID

### Response (200 OK)

```json
{
  "id": 1,
  "title": "First Post",
  "content": "Running on Kubernetes",
  "author": "Jimin",
  "createdAt": "2026-01-17T02:49:29.138478"
}
```

### Response (404 Not Found)

```
(현재: 빈 응답)

TODO: 에러 표준화 후
{
  "timestamp": "2026-01-21T10:00:00",
  "status": 404,
  "error": "Not Found",
  "message": "게시글을 찾을 수 없습니다. ID: 999",
  "path": "/api/posts/999"
}
```

### cURL 예시

```bash
# 성공
curl https://blog.jiminhome.shop/api/posts/1

# 실패
curl https://blog.jiminhome.shop/api/posts/999
```

---

## 3. 게시글 작성

### Request

```http
POST /api/posts
Content-Type: application/json

{
  "title": "새 게시글 제목",
  "content": "게시글 내용입니다",
  "author": "지민"
}
```

**Body Parameters:**
- `title` (String, required): 제목 (최대 200자)
- `content` (String, optional): 내용
- `author` (String, optional): 작성자 (최대 50자)

**Validation:**
- `title`: `@NotBlank` (필수), `@Size(max=200)`
- `author`: `@Size(max=50)`

**비즈니스 로직:**
- `title`이 비어있으면 → "제목 없음"
- `author`가 비어있으면 → "익명"

### Response (201 Created)

```json
{
  "id": 3,
  "title": "새 게시글 제목",
  "content": "게시글 내용입니다",
  "author": "지민",
  "createdAt": "2026-01-21T10:05:00"
}
```

### Response (400 Bad Request)

```json
(현재: 복잡한 Spring 기본 에러)

TODO: 에러 표준화 후
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

### cURL 예시

```bash
# 성공
curl -X POST https://blog.jiminhome.shop/api/posts \
  -H "Content-Type: application/json" \
  -d '{
    "title": "테스트 게시글",
    "content": "Kubernetes에서 작성한 글입니다",
    "author": "지민"
  }'

# Validation 실패 (title 누락)
curl -X POST https://blog.jiminhome.shop/api/posts \
  -H "Content-Type: application/json" \
  -d '{
    "content": "내용만 있음",
    "author": "지민"
  }'
```

---

## 4. 게시글 수정

### Request

```http
PUT /api/posts/{id}
Content-Type: application/json

{
  "title": "수정된 제목",
  "content": "수정된 내용"
}
```

**Path Parameters:**
- `id` (Long, required): 수정할 게시글 ID

**Body Parameters (Partial Update):**
- `title` (String, optional): 새 제목
- `content` (String, optional): 새 내용
- `author` (String, optional): 새 작성자

**참고:** 제공된 필드만 업데이트 (Null이면 기존 값 유지)

### Response (200 OK)

```json
{
  "id": 1,
  "title": "수정된 제목",
  "content": "수정된 내용",
  "author": "Jimin",  // 변경 안 함
  "createdAt": "2026-01-17T02:49:29.138478"
}
```

### Response (404 Not Found)

```
(현재: 빈 응답)
```

### cURL 예시

```bash
# 제목만 수정
curl -X PUT https://blog.jiminhome.shop/api/posts/1 \
  -H "Content-Type: application/json" \
  -d '{
    "title": "수정된 제목"
  }'

# 전체 수정
curl -X PUT https://blog.jiminhome.shop/api/posts/1 \
  -H "Content-Type: application/json" \
  -d '{
    "title": "완전히 새로운 제목",
    "content": "완전히 새로운 내용",
    "author": "NewAuthor"
  }'
```

---

## 5. 게시글 삭제

### Request

```http
DELETE /api/posts/{id}
```

**Path Parameters:**
- `id` (Long, required): 삭제할 게시글 ID

### Response (204 No Content)

```
(빈 응답)
```

### Response (404 Not Found)

```
(현재: 빈 응답)
```

### cURL 예시

```bash
# 성공
curl -X DELETE https://blog.jiminhome.shop/api/posts/1

# 실패 (존재하지 않는 ID)
curl -X DELETE https://blog.jiminhome.shop/api/posts/999
```

---

## 6. 게시글 검색

### Request

```http
GET /api/posts/search?keyword=Kubernetes
```

**Query Parameters:**
- `keyword` (String, required): 검색 키워드

**검색 대상:** 제목 (LIKE %keyword%)

### Response (200 OK)

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

**빈 결과:**
```json
[]
```

### cURL 예시

```bash
# 성공
curl "https://blog.jiminhome.shop/api/posts/search?keyword=Kubernetes"

# URL 인코딩 (한글)
curl "https://blog.jiminhome.shop/api/posts/search?keyword=%ED%85%8C%EC%8A%A4%ED%8A%B8"
```

---

## 공통 응답 헤더

```http
Content-Type: application/json;charset=UTF-8
Transfer-Encoding: chunked
Date: Tue, 21 Jan 2026 01:00:00 GMT
```

---

## 에러 코드

| HTTP Status | 설명 | 원인 |
|-------------|------|------|
| **200 OK** | 성공 | 조회, 수정 성공 |
| **201 Created** | 생성 성공 | 게시글 작성 성공 |
| **204 No Content** | 삭제 성공 | 게시글 삭제 성공 |
| **400 Bad Request** | 잘못된 요청 | Validation 실패 |
| **404 Not Found** | 리소스 없음 | 존재하지 않는 게시글 ID |
| **500 Internal Server Error** | 서버 오류 | DB 연결 실패 등 |

---

## Postman Collection

### 컬렉션 임포트

```json
{
  "info": {
    "name": "Blog WAS API",
    "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"
  },
  "item": [
    {
      "name": "Get All Posts",
      "request": {
        "method": "GET",
        "url": "{{base_url}}/posts"
      }
    },
    {
      "name": "Create Post",
      "request": {
        "method": "POST",
        "url": "{{base_url}}/posts",
        "header": [
          {"key": "Content-Type", "value": "application/json"}
        ],
        "body": {
          "mode": "raw",
          "raw": "{\n  \"title\": \"Test Post\",\n  \"content\": \"Content\",\n  \"author\": \"Jimin\"\n}"
        }
      }
    }
  ],
  "variable": [
    {
      "key": "base_url",
      "value": "https://blog.jiminhome.shop/api"
    }
  ]
}
```

---

## JavaScript 예시 (Frontend)

### Fetch API

```javascript
// 전체 조회
async function getAllPosts() {
  const response = await fetch('/api/posts');
  if (!response.ok) throw new Error('Failed to fetch');
  return await response.json();
}

// 게시글 작성
async function createPost(post) {
  const response = await fetch('/api/posts', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(post)
  });
  if (!response.ok) {
    const error = await response.json();
    throw new Error(error.message || 'Failed to create');
  }
  return await response.json();
}

// 사용 예시
const posts = await getAllPosts();
console.log(posts);

const newPost = await createPost({
  title: '새 글',
  content: '내용',
  author: '지민'
});
console.log(newPost.id);  // 생성된 ID
```

---

## Health Check

### Actuator Endpoints

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
    },
    "diskSpace": {
      "status": "UP",
      "details": {
        "total": 10737418240,
        "free": 8589934592,
        "threshold": 10485760
      }
    }
  }
}
```

---

## TODO: 개선 계획

### Pagination API

```http
GET /api/posts?page=0&size=10&sort=createdAt,desc
```

**Response:**
```json
{
  "content": [ /* 10개 게시글 */ ],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 10
  },
  "totalElements": 100,
  "totalPages": 10,
  "last": false,
  "first": true
}
```

### 표준화된 에러 응답

모든 에러에 동일한 형식 적용

### Rate Limiting

```http
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 95
X-RateLimit-Reset: 1642761600
```

### 인증 (Spring Security + JWT)

```http
Authorization: Bearer eyJhbGciOiJIUzI1NiIs...
```

---

**작성일:** 2026-01-21
**API 버전:** v1 (현재 버저닝 없음)
**Base URL:** `https://blog.jiminhome.shop/api`
