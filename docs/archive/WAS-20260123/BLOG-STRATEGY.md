# 블로그 글 업데이트 전략

> Hugo 정적 블로그 vs 게시판 API - 어떻게 사용할 것인가?

---

## 현재 상황

### 보유 시스템

| 시스템 | 기술 | 용도 | 현재 상태 |
|--------|------|------|-----------|
| **Hugo 블로그** | 정적 사이트 | Markdown 콘텐츠 | ✅ 작동 중 |
| **게시판 API** | Spring Boot + MySQL | 동적 CRUD | ⚠️ 외부 접근 불가 |
| **board.html** | Vanilla JS | 게시판 UI | ⚠️ 미배포 |

### 혼란 포인트

- Hugo로 글을 쓸까? 아니면 게시판 API를 쓸까?
- 두 개가 공존해야 할까? 아니면 하나만 쓸까?
- 게시판 시스템은 왜 만들었을까?

---

## 전략 옵션

### 옵션 1: Hugo 중심 + 게시판은 별도 용도 (권장) ✅

**구조:**
```
blog.jiminhome.shop/
├── /                    → Hugo 정적 블로그 (메인)
│   ├── /posts/          → 기술 블로그 글 (Markdown)
│   ├── /projects/       → 프로젝트 소개 (Markdown)
│   └── /about/          → 소개 페이지
│
└── /board/              → 게시판 (동적)
    ├── /board.html      → 게시판 UI
    └── /api/posts       → 게시판 API
```

**역할 분담:**

| 콘텐츠 타입 | 시스템 | 예시 |
|------------|--------|------|
| **기술 블로그 글** | Hugo (Markdown) | "Kubernetes Canary 배포 구축기" |
| **프로젝트 문서** | Hugo (Markdown) | "WAS 아키텍처 설명" |
| **동적 게시판** | Spring Boot API | 방명록, 댓글, 임시 메모 |

**Hugo 블로그 글 작성 방법:**
```bash
# 1. 새 글 작성
cd ~/blogsite
hugo new content/posts/2026-01-21-canary-deployment.md

# 2. Markdown 편집
vi content/posts/2026-01-21-canary-deployment.md

# 3. Git push → 자동 배포
git add content/posts/2026-01-21-canary-deployment.md
git commit -m "post: Add Canary deployment article"
git push origin main

# 4. GitHub Actions → Hugo 빌드 → nginx 배포
# → 3분 이내 자동 배포 완료
```

**게시판 사용 방법:**
```
https://blog.jiminhome.shop/board.html

용도:
- 방명록 (누구나 작성 가능)
- 임시 메모 (빠른 메모용)
- 테스트 용도
```

**장점:**
- ✅ Hugo는 SEO 최적화, 빠름 (정적 파일)
- ✅ 게시판은 동적 콘텐츠 (댓글, 방명록)
- ✅ 각 시스템의 장점을 살림
- ✅ 명확한 역할 분담

**단점:**
- ⚠️ 두 시스템 유지보수 필요
- ⚠️ 게시판 백업 별도 관리

**추천 시나리오:**
- 기술 블로그 + 방명록/댓글 시스템

---

### 옵션 2: Hugo만 사용, 게시판 시스템 제거 ⚠️

**구조:**
```
blog.jiminhome.shop/
├── /posts/              → 기술 블로그 글
├── /projects/           → 프로젝트 소개
└── /about/              → 소개
```

**게시판 시스템:**
- Spring Boot WAS 삭제
- MySQL 삭제
- board.html 삭제

**글 작성 방법:**
```bash
# Markdown으로만 작성
hugo new content/posts/new-article.md
git add content/posts/new-article.md
git commit -m "post: Add new article"
git push origin main
```

**장점:**
- ✅ 단순함 (시스템 하나만)
- ✅ 유지보수 쉬움
- ✅ 정적 사이트 (보안 좋음, 빠름)

**단점:**
- ❌ 동적 기능 없음 (댓글, 검색)
- ❌ 외부에서 글 작성 불가 (Git 필요)
- ❌ 지금까지 만든 WAS 시스템 버림

**추천 시나리오:**
- 개인 기술 블로그만 필요한 경우
- 동적 기능 불필요

---

### 옵션 3: 게시판 API만 사용, Hugo 제거 ❌

**구조:**
```
blog.jiminhome.shop/
└── /                    → React/Vue SPA
    ├── /posts           → 게시판 (Spring Boot API)
    └── /api/posts       → REST API
```

**글 작성 방법:**
```
1. https://blog.jiminhome.shop/ 접속
2. 웹 UI에서 글 작성 (에디터)
3. DB에 저장
```

**장점:**
- ✅ 웹 UI에서 직접 작성 가능
- ✅ 동적 기능 (댓글, 검색, 좋아요)
- ✅ 사용자가 글 작성 가능

**단점:**
- ❌ SEO 나쁨 (SPA는 크롤링 어려움)
- ❌ 속도 느림 (DB 조회 필요)
- ❌ 보안 취약 (공격 표면 넓음)
- ❌ 백업 복잡 (DB 백업 필요)
- ❌ Hugo 테마/기능 버림

**추천 시나리오:**
- 커뮤니티 사이트
- 여러 사람이 글 작성하는 경우

**비추천 이유:**
- 개인 기술 블로그는 정적 사이트가 더 적합
- SEO가 중요

---

### 옵션 4: Hugo + Headless CMS (Notion, Contentful) ⚠️

**구조:**
```
Notion (글 작성)
  ↓ API
Hugo (빌드)
  ↓
nginx (배포)
```

**글 작성 방법:**
```
1. Notion에서 글 작성
2. GitHub Actions가 Notion API 호출
3. Markdown으로 변환
4. Hugo 빌드
5. 자동 배포
```

**장점:**
- ✅ Notion UI로 편하게 작성
- ✅ Hugo SEO 유지
- ✅ 정적 사이트 성능 유지

**단점:**
- ⚠️ Notion API 설정 복잡
- ⚠️ 빌드 파이프라인 복잡
- ⚠️ Notion 의존성

**추천 시나리오:**
- 비개발자도 글 작성하는 경우
- Notion을 이미 사용 중

---

## 트레이드오프 비교

| 기준 | Hugo만 | Hugo + 게시판 | 게시판만 | Hugo + Notion |
|------|--------|--------------|---------|--------------|
| **SEO** | ✅ 최고 | ✅ 최고 | ❌ 나쁨 | ✅ 최고 |
| **속도** | ✅ 최고 | ✅ 최고 | ⚠️ 중간 | ✅ 최고 |
| **보안** | ✅ 최고 | ⚠️ 중간 | ❌ 나쁨 | ✅ 최고 |
| **유지보수** | ✅ 쉬움 | ⚠️ 중간 | ⚠️ 중간 | ⚠️ 복잡 |
| **동적 기능** | ❌ 없음 | ✅ 있음 | ✅ 있음 | ❌ 없음 |
| **글 작성 편의성** | ⚠️ Git | ⚠️ Git | ✅ 웹 UI | ✅ Notion |
| **백업** | ✅ Git | ⚠️ Git + DB | ⚠️ DB | ✅ Git |

---

## 권장: 옵션 1 (Hugo 중심 + 게시판 병행)

### 이유

1. **각 시스템의 장점을 살림**
   - Hugo: SEO, 속도, 보안, Git 버전 관리
   - 게시판: 동적 기능 (방명록, 댓글)

2. **현재 구축한 시스템 활용**
   - 지금까지 WAS, MySQL, Canary 배포 등 구축했음
   - 버리기 아까움

3. **확장 가능**
   - 향후 댓글 시스템으로 확장 가능
   - 방명록, 포트폴리오 신청 등 다양한 용도

### 구체적 사용 시나리오

#### Hugo (메인 블로그)

**콘텐츠:**
- 기술 블로그 글
- 프로젝트 소개
- 개발 일지
- 학습 노트

**작성 방법:**
```bash
# 1. 새 글 작성
hugo new content/posts/kubernetes-canary.md

# 2. Front Matter
---
title: "Kubernetes Canary 배포 구축기"
date: 2026-01-21
tags: ["kubernetes", "devops", "canary"]
categories: ["infrastructure"]
draft: false
---

# Canary 배포란?
...

# 3. Git push
git add content/posts/kubernetes-canary.md
git commit -m "post: Add Kubernetes Canary deployment guide"
git push origin main

# 4. 자동 배포 (3분)
# → https://blog.jiminhome.shop/posts/kubernetes-canary/
```

#### 게시판 (동적 기능)

**콘텐츠:**
- 방명록
- 빠른 메모 (임시)
- 테스트 용도
- 향후 댓글 시스템으로 확장

**작성 방법:**
```
1. https://blog.jiminhome.shop/board 접속
2. 웹 UI에서 작성
3. DB 저장
4. 즉시 반영
```

---

## 실행 계획 (옵션 1 선택 시)

### 1단계: 게시판 시스템 완성 (P0 - 오늘)

**작업:**
- [ ] nginx 프록시 설정 (`/api/` → WAS)
- [ ] board.html 배포 확인
- [ ] 외부 접근 테스트

**결과:**
```
https://blog.jiminhome.shop/board
→ 방명록으로 사용 가능
```

### 2단계: Hugo 블로그 글 작성 프로세스 정립

**템플릿 작성:**
```bash
# archetypes/posts.md
---
title: "{{ replace .Name "-" " " | title }}"
date: {{ .Date }}
tags: []
categories: []
draft: true
description: ""
---

## 개요

## 본문

## 결론
```

**첫 글 작성:**
```bash
hugo new content/posts/blog-system-architecture.md

# Front Matter
---
title: "블로그 시스템 아키텍처"
date: 2026-01-21
tags: ["kubernetes", "hugo", "spring-boot"]
categories: ["project"]
draft: false
---

# 개요
이 블로그는 Hugo + Spring Boot + Kubernetes로 구축되었습니다.

## 아키텍처
[ARCHITECTURE.md 내용 재구성]

## Hugo 선택 이유
- SEO 최적화
- 빠른 속도
- Git 버전 관리

## 게시판 시스템
동적 기능이 필요한 경우 /board 사용
```

### 3단계: 게시판을 방명록으로 전환

**UI 수정:**
```html
<!-- board.html -->
<h1>방명록</h1>
<p>편하게 글 남겨주세요!</p>

<!-- 작성 폼 -->
<input placeholder="이름">
<textarea placeholder="하고 싶은 말"></textarea>
<button>남기기</button>
```

**API 그대로 사용:**
```
POST /api/posts
{
  "title": "방명록",
  "content": "블로그 잘 보고 갑니다!",
  "author": "지민"
}
```

### 4단계: 문서화

**WORKLOG.md 업데이트:**
```markdown
## 2026-01-21

### 블로그 글 업데이트 전략 결정

**선택:** Hugo 중심 + 게시판 병행

**역할 분담:**
- Hugo: 기술 블로그 글 (SEO, 속도)
- 게시판: 방명록, 동적 기능

**작성 방법:**
- Hugo: Markdown → Git push
- 게시판: 웹 UI → DB 저장
```

---

## 다른 사람들은 어떻게 하나?

### 유명 개발자 블로그 사례

| 블로그 | 시스템 | 동적 기능 |
|--------|--------|----------|
| **kakao tech** | Jekyll (정적) | Disqus 댓글 (외부) |
| **우아한형제들** | Medium | Medium 댓글 |
| **Line Engineering** | Hexo (정적) | 없음 |
| **Outsider** | Octopress (정적) | Utterances (GitHub Issues) |

**대부분:**
- 정적 사이트 생성기 (Hugo, Jekyll, Hexo)
- 댓글은 외부 서비스 (Disqus, Utterances)

**왜 정적 사이트?**
1. SEO 최적화
2. 속도 (CDN 캐싱)
3. 보안 (공격 표면 작음)
4. Git 버전 관리
5. 무료 호스팅 (GitHub Pages, Netlify)

---

## 결론

### 권장 전략

**메인: Hugo 정적 블로그**
- 기술 블로그 글
- 프로젝트 문서
- Markdown → Git push
- SEO 최적화

**보조: 게시판 시스템**
- 방명록
- 향후 댓글 시스템으로 확장
- 웹 UI → DB 저장

### 다음 단계

1. **P0 (오늘)**: 게시판 시스템 완성
   - nginx 프록시 설정
   - board.html → 방명록으로 UI 변경
   - 외부 접근 테스트

2. **P1 (이번 주)**: Hugo 블로그 글 작성
   - 첫 글: "블로그 시스템 아키텍처"
   - 템플릿 정립
   - 카테고리/태그 체계

3. **P2 (향후)**: 고급 기능
   - Hugo 댓글 (Utterances)
   - RSS Feed
   - 검색 기능 (Algolia)

---

**작성일**: 2026-01-21
**결정**: 옵션 1 (Hugo 중심 + 게시판 병행)
**다음 작업**: nginx 프록시 설정
