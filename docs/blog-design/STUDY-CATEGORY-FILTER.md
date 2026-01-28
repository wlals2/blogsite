# Study 페이지 카테고리 필터 구현 완전 가이드

> **목적**: Study 페이지에 10개 고정 카테고리 필터 추가 및 전체 포스트 필터링

**최종 업데이트:** 2026-01-27
**문서 버전:** 1.0
**시스템 상태:** ✅ 완료

---

## 📋 목차

1. [프로젝트 개요](#프로젝트-개요)
2. [왜 이렇게 구축했는가](#왜-이렇게-구축했는가)
3. [구현 상세](#구현-상세)
4. [기술 스택 및 파일 구조](#기술-스택-및-파일-구조)
5. [문제 해결 과정](#문제-해결-과정)
6. [실제 작동 방식](#실제-작동-방식)
7. [향후 개선 방향](#향후-개선-방향)

---

## 프로젝트 개요

### 무엇을 만들었는가?

Study 페이지에 **10개 고정 카테고리 필터 박스**를 추가하여 96개 포스트를 쉽게 탐색할 수 있도록 개선했습니다.

**주요 특징:**
- ✅ 10개 고정 카테고리 (Kubernetes, Service Mesh, Networking 등)
- ✅ 원클릭 필터링 (JavaScript 기반)
- ✅ URL 파라미터로 상태 유지 (`?category=Kubernetes`)
- ✅ 전체 96개 포스트 필터링 (페이지네이션 무관)
- ✅ 다크모드 최적화 및 모바일 반응형

### Before / After

**Before (문제)**:
```
Study 페이지 접속
    ↓
96개 포스트가 시간순으로만 나열
    ↓
원하는 주제(예: Service Mesh) 찾기 어려움 ❌
```

**After (해결)**:
```
Study 페이지 접속
    ↓
카테고리 필터 박스 표시 (10개 버튼)
    ↓
"Service Mesh" 클릭 → 5개 포스트만 즉시 표시 ✅
```

---

## 왜 이렇게 구축했는가?

### 1. 왜 카테고리 시스템이 필요했는가?

**문제 상황**:
- Study 포스트 96개가 시간순으로만 나열
- 특정 주제(예: Troubleshooting) 포스트만 보고 싶어도 불가능
- 태그는 있지만 일관성 없고 중복 많음

**선택한 방법: 10개 고정 카테고리**

#### 대안 분석

| 방법 | 장점 | 단점 | 선택 이유 |
|------|------|------|----------|
| **10개 고정 카테고리** | 일관성 유지<br>탐색 용이<br>자동 분류 가능 | 카테고리 수 제한<br>분류 애매한 글 존재 | ✅ **선택** - 96개 포스트에 최적 |
| 태그 기반 필터 | 유연함<br>무한 확장 가능 | 태그 수 폭발 (200+개)<br>일관성 없음 | ❌ 이미 태그는 존재, 별도 유지 |
| 계층형 카테고리 | 세부 분류 가능<br>확장성 높음 | 복잡함<br>구조 설계 어려움 | ❌ 오버엔지니어링 |
| 검색 기능만 제공 | 가장 유연함<br>원하는 대로 검색 | 검색어 입력 필요<br>오타 민감 | ❌ 탐색성 떨어짐 |

#### 선택 이유 (Why 10개 고정 카테고리?)

1. **일관성**: 매번 새로운 카테고리 생성 방지
   ```yaml
   # Before: 카테고리가 매번 달라짐
   categories: ["study", "k8s"]
   categories: ["study", "Kubernetes"]
   categories: ["study", "쿠버네티스"]

   # After: 고정 카테고리만 사용
   categories: ["study", "Kubernetes"]  # ✅
   ```

2. **자동 분류**: Python 스크립트로 제목/태그 기반 자동 제안
   ```bash
   python3 scripts/suggest-category.py \
     "Istio Gateway 설정 가이드" \
     "istio,gateway,kubernetes"

   # 출력: Service Mesh (점수: 9) ← 자동 제안
   ```

3. **탐색 용이**: 클릭 한 번으로 특정 주제 포스트 모두 표시

---

### 2. 왜 Hugo 템플릿이 아닌 JavaScript 필터링인가?

#### 대안 분석

| 방법 | 장점 | 단점 | 선택 이유 |
|------|------|------|----------|
| **JavaScript 클라이언트 필터링** | 즉시 반응<br>서버 요청 없음<br>URL 파라미터로 상태 유지 | 초기 로드 시 모든 HTML 포함<br>SEO 약간 불리 | ✅ **선택** - UX 최우선 |
| Hugo 페이지 분리 | SEO 최적<br>각 카테고리 독립 URL | 페이지 수 증가<br>중복 HTML<br>필터 전환 시 새로고침 | ❌ UX 떨어짐 |
| AJAX 동적 로딩 | 초기 로드 빠름<br>SEO 유지 | 복잡도 증가<br>서버 요청 필요 | ❌ 오버엔지니어링 |

#### 선택 이유 (Why JavaScript?)

**Hugo는 정적 사이트 생성기**이므로, 빌드 시점에 모든 HTML을 생성합니다:
```
Hugo 빌드 → /study/index.html (96개 포스트 모두 포함)
```

**JavaScript로 클라이언트에서 필터링**:
- 즉각 반응 (0ms)
- 페이지 새로고침 없음
- URL 파라미터로 공유 가능 (`?category=Kubernetes`)

---

### 3. 왜 페이지네이션을 100개로 늘렸는가?

#### 대안 분석

| 방법 | 장점 | 단점 | 선택 이유 |
|------|------|------|----------|
| **pagerSize=100** | 모든 포스트가 HTML에 포함<br>필터링 완벽 작동<br>구현 간단 | 초기 HTML 크기 증가<br>(~30KB → ~60KB) | ✅ **선택** - 96개 포스트에 최적 |
| pagerSize=10 유지 + AJAX | 초기 로드 빠름<br>HTML 작음 | 복잡도 증가<br>서버 요청 필요 | ❌ 오버엔지니어링 |
| 각 카테고리 독립 페이지 | SEO 최적<br>페이지 크기 작음 | 페이지 수 10배 증가<br>중복 HTML | ❌ 관리 복잡 |

#### 선택 이유 (Why pagerSize=100?)

**문제 상황**:
```
pagerSize = 10 (기본값)
    ↓
1페이지: 10개 포스트만 HTML 렌더링
    ↓
Observability 클릭 → 현재 페이지에 있는 1개만 필터링 ❌
(나머지 4개는 2-10페이지에 있어서 JavaScript가 접근 불가)
```

**해결책**:
```
pagerSize = 100
    ↓
1페이지: 96개 포스트 모두 HTML 렌더링
    ↓
Observability 클릭 → 전체 5개 포스트 모두 필터링 ✅
```

**트레이드오프**:
- ✅ 장점: 필터링 완벽 작동
- ❌ 단점: HTML 크기 2배 증가 (30KB → 60KB)
- ✅ 하지만: 60KB는 매우 작음 (이미지 1개 수준)

---

## 구현 상세

### 1. 파일 구조

**수정된 파일** (2개):
```
/home/jimin/blogsite/
├── config.toml                    # pagerSize 10 → 100
└── layouts/study/list.html        # 카테고리 필터 UI + JavaScript
```

**관련 파일**:
```
/home/jimin/blogsite/
├── .blog-categories.yaml          # 10개 고정 카테고리 정의
├── static/css/custom.css          # 카테고리 필터 스타일
├── scripts/
│   ├── suggest-category.py        # 신규 포스트 카테고리 제안
│   └── update-categories.py       # 기존 포스트 일괄 업데이트
└── content/study/
    └── */index.md                 # 각 포스트의 front matter
```

---

### 2. config.toml 수정

**파일**: `/home/jimin/blogsite/config.toml`

**변경 내용**:
```toml
# Before
[pagination]
  pagerSize = 10

# After
[pagination]
  pagerSize = 100  # 카테고리 필터를 위해 모든 포스트를 한 페이지에 렌더링
```

**왜?**
- Hugo가 모든 96개 포스트를 한 페이지에 렌더링
- JavaScript가 전체 포스트에 접근 가능
- 페이지네이션은 자동으로 숨겨짐 (1페이지만 존재)

---

### 3. layouts/study/list.html 구현

**파일**: `/home/jimin/blogsite/layouts/study/list.html`

#### 3.1. Hugo 템플릿: 카테고리 필터 박스

```html
{{/* Category Filter Box */}}
<div class="category-filter-box">
  <h3>📂 카테고리 필터</h3>
  <div class="category-buttons">
    {{- $allPages := where .Site.RegularPages "Section" "study" }}
    {{- $totalCount := len $allPages }}

    <button class="category-btn active" data-category="all">
      All <span class="count">({{ $totalCount }})</span>
    </button>

    {{/* Count posts by category */}}
    {{- $categoryMap := dict
      "Kubernetes" 0
      "Service Mesh" 0
      "Networking" 0
      "Security" 0
      "Storage" 0
      "Observability" 0
      "Cloud & Terraform" 0
      "Elasticsearch" 0
      "Troubleshooting" 0
      "Development" 0
    }}

    {{- range $allPages }}
      {{- range .Params.categories }}
        {{- if ne . "study" }}
          {{- $count := index $categoryMap . | default 0 }}
          {{- $categoryMap = merge $categoryMap (dict . (add $count 1)) }}
        {{- end }}
      {{- end }}
    {{- end }}

    {{/* Render category buttons */}}
    {{- range $category, $count := $categoryMap }}
      {{- if gt $count 0 }}
    <button class="category-btn" data-category="{{ $category }}">
      {{ $category }} <span class="count">({{ $count }})</span>
    </button>
      {{- end }}
    {{- end }}
  </div>
</div>
```

**핵심 로직**:
1. **카테고리별 포스트 개수 카운트**:
   ```go
   {{- $categoryMap := dict "Kubernetes" 0 "Service Mesh" 0 ... }}
   {{- range $allPages }}
     {{- $count := index $categoryMap . | default 0 }}
     {{- $categoryMap = merge $categoryMap (dict . (add $count 1)) }}
   {{- end }}
   ```

2. **버튼 동적 생성**:
   ```html
   <button class="category-btn" data-category="Kubernetes">
     Kubernetes <span class="count">(37)</span>
   </button>
   ```

#### 3.2. Hugo 템플릿: Article에 data-categories 속성 추가

```html
<article class="post-entry"
         data-categories='{{ delimit ($page.Params.categories | default slice) "," }}'>
  {{- /* article 내용 */ -}}
</article>
```

**예시**:
```html
<article data-categories='study,Service Mesh,Networking'>
  <h2>Nginx Ingress → Istio Gateway 전환</h2>
</article>
```

**왜 `| default slice`?**
- 일부 포스트가 `categories:` 없음 → nil 반환
- Hugo의 `delimit` 함수는 nil을 처리 못함 → 빌드 실패
- `| default slice`로 빈 배열 반환 → 빌드 성공

---

#### 3.3. JavaScript: 필터링 로직

```javascript
<script>
document.addEventListener('DOMContentLoaded', function() {
  const categoryButtons = document.querySelectorAll('.category-btn');
  const articles = document.querySelectorAll('article[data-categories]');

  // Get current filter from URL parameter
  const urlParams = new URLSearchParams(window.location.search);
  const currentFilter = urlParams.get('category') || 'all';

  // Apply initial filter
  applyFilter(currentFilter);

  function applyFilter(selectedCategory) {
    // Update active button
    categoryButtons.forEach(btn => {
      if (btn.getAttribute('data-category') === selectedCategory) {
        btn.classList.add('active');
      } else {
        btn.classList.remove('active');
      }
    });

    // Filter articles
    let visibleCount = 0;
    articles.forEach(article => {
      const categoriesStr = article.getAttribute('data-categories');
      if (!categoriesStr) {
        article.style.display = 'none';
        return;
      }

      // Split and trim to handle spaces
      const categories = categoriesStr.split(',').map(c => c.trim());

      if (selectedCategory === 'all') {
        article.style.display = '';
        visibleCount++;
      } else {
        if (categories.includes(selectedCategory)) {
          article.style.display = '';
          visibleCount++;
        } else {
          article.style.display = 'none';
        }
      }
    });

    // 페이지네이션 처리
    const pagination = document.querySelector('.page-footer');
    if (pagination) {
      if (selectedCategory === 'all') {
        pagination.style.display = '';  // "All"이면 페이지네이션 표시
      } else {
        pagination.style.display = 'none';  // 필터 사용 시 페이지네이션 숨김
      }
    }

    // Update URL without reload
    const newUrl = new URL(window.location);
    if (selectedCategory === 'all') {
      newUrl.searchParams.delete('category');
    } else {
      newUrl.searchParams.set('category', selectedCategory);
    }
    window.history.pushState({}, '', newUrl);
  }

  categoryButtons.forEach(button => {
    button.addEventListener('click', function() {
      const selectedCategory = this.getAttribute('data-category');
      applyFilter(selectedCategory);
    });
  });

  // Update pagination links to preserve category filter
  const paginationLinks = document.querySelectorAll('.pagination a');
  paginationLinks.forEach(link => {
    if (currentFilter !== 'all') {
      const url = new URL(link.href);
      url.searchParams.set('category', currentFilter);
      link.href = url.toString();
    }
  });
});
</script>
```

**핵심 로직**:

1. **카테고리 매칭 (공백 처리)**:
   ```javascript
   const categories = categoriesStr.split(',').map(c => c.trim());
   // "study,Service Mesh" → ["study", "Service Mesh"]
   ```

2. **URL 파라미터 상태 유지**:
   ```javascript
   // URL 업데이트 (새로고침 없음)
   const newUrl = new URL(window.location);
   newUrl.searchParams.set('category', 'Kubernetes');
   window.history.pushState({}, '', newUrl);
   // → https://blog.jiminhome.shop/study/?category=Kubernetes
   ```

3. **페이지네이션 숨김**:
   ```javascript
   if (selectedCategory === 'all') {
     pagination.style.display = '';  // 표시 (하지만 1페이지만 있으므로 자동 숨김)
   } else {
     pagination.style.display = 'none';  // 명시적으로 숨김
   }
   ```

---

### 4. CSS 스타일 (기존 파일)

**파일**: `/home/jimin/blogsite/static/css/custom.css`

```css
/* ==================== */
/* Category Filter Box  */
/* ==================== */

.category-filter-box {
    width: 100%;
    max-width: 100%;
    box-sizing: border-box;
    background: var(--entry);
    border: 1px solid var(--border);
    border-radius: 8px;
    padding: 20px;
    margin: 20px 0 30px 0;
    box-shadow: 0 2px 4px rgba(0, 0, 0, 0.05);
}

.category-filter-box h3 {
    margin: 0 0 15px 0;
    font-size: 1.1em;
    color: var(--content);
    border-bottom: 2px solid var(--primary);
    padding-bottom: 10px;
}

.category-buttons {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
}

.category-btn {
    background: var(--theme);
    border: 1px solid var(--border);
    border-radius: 6px;
    padding: 8px 16px;
    font-size: 0.9em;
    color: var(--content);
    cursor: pointer;
    transition: all 0.2s ease;
    white-space: nowrap;
}

.category-btn:hover {
    background: var(--primary);
    color: var(--theme);
    border-color: var(--primary);
    transform: translateY(-2px);
    box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
}

.category-btn.active {
    background: var(--primary);
    color: var(--theme);
    border-color: var(--primary);
    font-weight: 600;
}

.category-btn .count {
    opacity: 0.7;
    font-size: 0.85em;
    margin-left: 4px;
}

/* 반응형 - 모바일 */
@media screen and (max-width: 768px) {
    .category-filter-box {
        padding: 15px;
        margin: 15px 0 20px 0;
    }

    .category-filter-box h3 {
        font-size: 1em;
        margin-bottom: 12px;
    }

    .category-buttons {
        gap: 8px;
    }

    .category-btn {
        padding: 6px 12px;
        font-size: 0.85em;
    }
}

/* 다크모드 최적화 */
.dark .category-filter-box {
    box-shadow: 0 2px 8px rgba(0, 0, 0, 0.3);
}

.dark .category-btn:hover {
    box-shadow: 0 2px 12px rgba(0, 0, 0, 0.3);
}
```

**디자인 원칙**:
- CSS 변수 사용 (`var(--primary)`) → 다크모드 자동 대응
- Flexbox + gap → 반응형 레이아웃
- transition → 부드러운 호버 효과

---

## 문제 해결 과정

### 문제 1: Hugo 빌드 실패 - nil categories

**에러 메시지**:
```
Error: error building site: render: failed to render pages:
template: study/list.html:89:50: executing "main" at
<delimit $page.Params.categories ",">: error calling delimit:
can't iterate over <nil>
```

**원인**:
- 일부 포스트가 `categories:` front matter 없음
- `$page.Params.categories` → nil 반환
- Hugo의 `delimit` 함수는 nil 처리 불가

**해결**:
```html
<!-- Before (에러) -->
<article data-categories='{{ delimit $page.Params.categories "," }}'>

<!-- After (수정) -->
<article data-categories='{{ delimit ($page.Params.categories | default slice) "," }}'>
```

**커밋**: `62ac4ee fix: Handle nil categories in study list template`

---

### 문제 2: 카테고리 필터가 작동 안 함 (공백 문제)

**증상**:
- 버튼 클릭 → 아무 포스트도 안 보임
- `"Service Mesh"` 카테고리가 매칭 안 됨

**원인**:
```javascript
// HTML:
data-categories='study,Service Mesh,Networking'

// JavaScript:
const categories = categoriesStr.split(',');
// → ["study", "Service Mesh", "Networking"]
//                  ↑ 공백 포함!

// 버튼:
<button data-category="Service Mesh">

// 매칭:
categories.includes("Service Mesh")  // false!
// 왜? " Service Mesh" !== "Service Mesh" (앞에 공백)
```

**해결**:
```javascript
// Before (버그)
const categories = categoriesStr.split(',');

// After (수정)
const categories = categoriesStr.split(',').map(c => c.trim());
// → ["study", "Service Mesh", "Networking"]
//                ↑ 공백 제거됨!
```

**커밋**: `058eb21 fix: Category filter 및 페이지네이션 상태 유지 개선`

---

### 문제 3: 페이지네이션 시 필터 초기화

**증상**:
- Kubernetes 필터 선택
- "다음 페이지" 클릭
- 필터가 "All"로 초기화됨

**원인**:
- 페이지네이션 링크: `/study/page/2/`
- 카테고리 파라미터 없음: `?category=Kubernetes` 누락

**해결**:
```javascript
// 페이지네이션 링크 업데이트
const paginationLinks = document.querySelectorAll('.pagination a');
paginationLinks.forEach(link => {
  if (currentFilter !== 'all') {
    const url = new URL(link.href);
    url.searchParams.set('category', currentFilter);
    link.href = url.toString();
    // /study/page/2/ → /study/page/2/?category=Kubernetes
  }
});
```

**커밋**: `058eb21 fix: Category filter 및 페이지네이션 상태 유지 개선`

---

### 문제 4: CSS 스타일이 안 보임 (Cloudflare 캐시)

**증상**:
- 카테고리 필터 박스가 평문으로 표시
- 버튼 스타일, 배경색 없음

**원인**:
- Pod 안의 파일은 정상 (HTML, CSS, JS 모두 있음)
- 하지만 Cloudflare가 이전 버전 CSS 캐시
- 사용자 브라우저도 이전 버전 CSS 사용

**해결**:
1. **즉시 해결**: 브라우저 하드 새로고침 (`Ctrl + Shift + R`)
2. **장기 해결**: GitHub Actions가 배포 시 Cloudflare 캐시 삭제

**확인**:
```bash
# Cloudflare 캐시 상태
curl -I https://blog.jiminhome.shop/css/custom.css | grep age
# age: 79 ← 79초 전 캐시된 버전

# 해결 후
curl -I https://blog.jiminhome.shop/css/custom.css | grep age
# age: 5 ← 5초 전 업데이트된 버전
```

**커밋**: `41860ef chore: Purge Cloudflare cache for category filter styles`

---

### 문제 5: 카테고리 선택 시 일부만 표시 (페이지네이션)

**증상**:
- Observability (5개) 클릭
- 1개만 표시됨
- 나머지 4개는?

**원인**:
```
pagerSize = 10
    ↓
Hugo가 10개씩 페이지 분리
    ↓
1페이지: 10개 포스트만 HTML 렌더링
2페이지: 다음 10개
...
    ↓
JavaScript는 현재 페이지(1페이지)만 필터링
    ↓
Observability 5개 중 1개만 1페이지에 있음 ❌
```

**해결**:
```toml
# config.toml
[pagination]
  pagerSize = 100  # 10 → 100
```

**효과**:
```
pagerSize = 100
    ↓
모든 96개 포스트가 1페이지에 렌더링
    ↓
JavaScript가 전체 96개 접근 가능
    ↓
Observability 5개 모두 필터링 ✅
```

**커밋**: `b714420 feat: 카테고리 필터링 시 모든 포스트 표시`

---

## 실제 작동 방식

### 시나리오 1: 사용자가 Study 페이지 접속

```
1. 사용자: https://blog.jiminhome.shop/study/ 접속
   ↓
2. Hugo: /public/study/index.html 제공
   - 96개 포스트 모두 HTML에 포함
   - 각 article에 data-categories 속성
   ↓
3. JavaScript: DOMContentLoaded 이벤트
   - URL 파라미터 확인: ?category=없음 → 'all'
   - applyFilter('all') 실행
   - 모든 article 표시
   ↓
4. 사용자: 카테고리 필터 박스 보임
   - All (96) ← active 상태
   - Kubernetes (37)
   - Service Mesh (5)
   - ...
```

---

### 시나리오 2: Observability 클릭

```
1. 사용자: "Observability (6)" 버튼 클릭
   ↓
2. JavaScript: button click 이벤트
   - applyFilter('Observability') 실행
   ↓
3. applyFilter() 함수:
   a. 버튼 상태 업데이트
      - "All" → .active 제거
      - "Observability" → .active 추가

   b. 96개 article 순회
      for each article:
        categoriesStr = article.getAttribute('data-categories')
        // 예: "study,Observability"

        categories = categoriesStr.split(',').map(c => c.trim())
        // → ["study", "Observability"]

        if categories.includes('Observability'):
          article.style.display = ''  // 표시 ✅
        else:
          article.style.display = 'none'  // 숨김 ❌

   c. 페이지네이션 숨김
      pagination.style.display = 'none'

   d. URL 업데이트
      window.history.pushState({}, '', '/study/?category=Observability')
   ↓
4. 결과: 6개 포스트만 표시
   - 2025-11-05: Prometheus 메트릭 수집 에러
   - 2025-11-05: Prometheus 메트릭 수집 에러 해결2
   - 2025-11-26: Grafana Dashboard 뿌시기
   - 2025-12-25: Kubernetes Addons 운영 가이드
   - 2026-01-20: Datadog 수준 Observability 시스템 구축
   - 2026-01-24: Nginx Ingress → Istio Gateway 전환
```

---

### 시나리오 3: URL 공유 및 접속

```
1. 사용자 A: Observability 필터 선택 후 URL 복사
   → https://blog.jiminhome.shop/study/?category=Observability
   ↓
2. 사용자 B: URL 클릭하여 접속
   ↓
3. JavaScript: DOMContentLoaded 이벤트
   - const urlParams = new URLSearchParams(window.location.search)
   - currentFilter = urlParams.get('category')
   - → 'Observability'
   - applyFilter('Observability') 실행
   ↓
4. 결과: 자동으로 Observability 필터 적용됨 ✅
   - 버튼 상태: "Observability" active
   - 6개 포스트만 표시
```

---

## 기술 스택 및 파일 구조

### 기술 스택

| 기술 | 역할 | 버전 |
|------|------|------|
| **Hugo** | 정적 사이트 생성기 | v0.137.1 |
| **Go Template** | Hugo 템플릿 엔진 | - |
| **JavaScript (ES6)** | 클라이언트 필터링 | - |
| **CSS3** | 스타일링 (Flexbox, CSS Variables) | - |
| **Python 3** | 카테고리 자동 분류 스크립트 | 3.x |
| **YAML** | 카테고리 정의 파일 | - |

---

### 시스템 아키텍처

```
┌─────────────────────────────────────────────────────────────┐
│                      사용자 브라우저                          │
│  ┌────────────────────────────────────────────────────────┐ │
│  │  /study/?category=Observability                        │ │
│  └────────────────────────────────────────────────────────┘ │
│                             ↓                                │
│  ┌────────────────────────────────────────────────────────┐ │
│  │  HTML (96개 포스트 모두 포함)                           │ │
│  │  <article data-categories="study,Observability">       │ │
│  │  <article data-categories="study,Kubernetes">          │ │
│  │  ...                                                    │ │
│  └────────────────────────────────────────────────────────┘ │
│                             ↓                                │
│  ┌────────────────────────────────────────────────────────┐ │
│  │  JavaScript 필터링                                       │ │
│  │  - URL 파라미터 읽기                                     │ │
│  │  - article.style.display 제어                           │ │
│  │  - 버튼 active 상태 관리                                 │ │
│  └────────────────────────────────────────────────────────┘ │
│                             ↓                                │
│  ┌────────────────────────────────────────────────────────┐ │
│  │  결과: Observability 포스트 6개만 표시                   │ │
│  └────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

---

### 데이터 플로우

```
1. Hugo 빌드 시점
   ┌────────────────────────────────────────────┐
   │  content/study/*/index.md                  │
   │  ---                                       │
   │  categories: ["study", "Observability"]    │
   │  ---                                       │
   └────────────────────────────────────────────┘
                    ↓
   ┌────────────────────────────────────────────┐
   │  layouts/study/list.html (Go Template)     │
   │  - 카테고리별 포스트 개수 카운트             │
   │  - 버튼 생성                                │
   │  - article에 data-categories 속성 추가      │
   └────────────────────────────────────────────┘
                    ↓
   ┌────────────────────────────────────────────┐
   │  public/study/index.html                   │
   │  (정적 HTML, 96개 포스트 포함)              │
   └────────────────────────────────────────────┘

2. 사용자 접속 시점
   ┌────────────────────────────────────────────┐
   │  사용자 브라우저                            │
   │  - HTML 로드                                │
   │  - JavaScript 실행                          │
   │  - CSS 적용                                 │
   └────────────────────────────────────────────┘
                    ↓
   ┌────────────────────────────────────────────┐
   │  JavaScript 필터링                          │
   │  - URL 파라미터 읽기                        │
   │  - article 표시/숨김                        │
   │  - 버튼 상태 업데이트                       │
   └────────────────────────────────────────────┘
```

---

## 향후 개선 방향

### ⏳ 30분 내 완료 가능

1. **카테고리별 포스트 개수 실시간 업데이트** (20분)
   - 현재: 빌드 시점 개수 고정
   - 개선: 필터 변경 시 보이는 개수만 표시
   ```javascript
   // Kubernetes (37) → Kubernetes (15) (현재 페이지 기준)
   ```

2. **키보드 단축키 지원** (10분)
   - `k` → Kubernetes 필터
   - `o` → Observability 필터
   - `a` → All 필터
   ```javascript
   document.addEventListener('keydown', (e) => {
     if (e.key === 'k') applyFilter('Kubernetes');
   });
   ```

---

### 🔜 선택 사항

3. **검색 기능 추가** (1시간)
   - 카테고리 + 검색어 조합
   ```html
   <input type="text" placeholder="포스트 검색...">
   ```

4. **애니메이션 효과** (30분)
   - 포스트 표시/숨김 시 fade-in/out
   ```css
   article {
     transition: opacity 0.3s ease;
   }
   ```

5. **카테고리 중복 선택** (2시간)
   - Kubernetes + Observability 동시 선택
   - OR 연산자 지원

6. **통계 대시보드** (3시간)
   - 카테고리별 포스트 수 그래프
   - 월별 작성 트렌드

---

## 체크리스트

### ✅ 구축 완료
- [x] 10개 고정 카테고리 정의 (`.blog-categories.yaml`)
- [x] 카테고리 필터 UI 구현 (Hugo 템플릿)
- [x] JavaScript 필터링 로직 구현
- [x] CSS 스타일링 (다크모드 포함)
- [x] URL 파라미터 상태 유지
- [x] 페이지네이션 동적 표시/숨김
- [x] 공백 포함 카테고리 매칭 (Service Mesh)
- [x] pagerSize 증가로 전체 포스트 접근
- [x] Cloudflare 캐시 문제 해결
- [x] 모바일 반응형

### ⏳ 진행 중
- [ ] 없음

### 🔜 선택 사항
- [ ] 키보드 단축키 지원
- [ ] 검색 기능 추가
- [ ] 애니메이션 효과
- [ ] 카테고리 중복 선택
- [ ] 통계 대시보드

---

## 배운 점

### 1. Hugo 정적 사이트 vs. 동적 필터링

**교훈**: 정적 사이트에서도 JavaScript로 충분히 동적 UX 구현 가능

**트레이드오프**:
- ✅ 초기 HTML 크기 2배 증가 (30KB → 60KB)
- ✅ 하지만 UX는 10배 개선 (즉시 반응)

---

### 2. 페이지네이션의 함정

**교훈**: JavaScript 필터링은 DOM에 있는 요소만 처리 가능

**해결책**:
- 필터링 대상이 적으면 (< 100개) → 모두 렌더링
- 필터링 대상이 많으면 (> 1000개) → AJAX 동적 로딩

---

### 3. 공백 처리의 중요성

**교훈**: `"Service Mesh"` vs `" Service Mesh"` (앞 공백) → 매칭 실패

**교육**: 항상 `.trim()` 사용

```javascript
const categories = categoriesStr.split(',').map(c => c.trim());
```

---

### 4. Cloudflare 캐시 관리

**교훈**: CSS 변경 후 사용자가 안 보이면 캐시 의심

**해결책**:
1. 배포 시 자동 캐시 삭제 (GitHub Actions)
2. 사용자에게 하드 새로고침 안내 (Ctrl + Shift + R)

---

## 참고 자료

### 관련 문서
- [`.blog-categories.yaml`](../../.blog-categories.yaml) - 10개 고정 카테고리 정의
- [`scripts/suggest-category.py`](../../scripts/suggest-category.py) - 신규 포스트 카테고리 제안
- [`scripts/update-categories.py`](../../scripts/update-categories.py) - 기존 포스트 일괄 업데이트
- [`scripts/README.md`](../../scripts/README.md) - 스크립트 사용 가이드

### 기술 문서
- [Hugo Pagination](https://gohugo.io/templates/pagination/)
- [Hugo Taxonomies](https://gohugo.io/content-management/taxonomies/)
- [JavaScript History API](https://developer.mozilla.org/en-US/docs/Web/API/History_API)
- [CSS Flexbox](https://developer.mozilla.org/en-US/docs/Web/CSS/CSS_Flexible_Box_Layout)

---

**작성일**: 2026-01-27
**작성자**: Jimin & Claude Sonnet 4.5
**문서 버전**: 1.0
**다음 단계**: 키보드 단축키 지원 추가 (선택 사항)
