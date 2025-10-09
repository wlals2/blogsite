---
title: "Hugo 블로그 자동 날짜 관리로 손쉽게 최신 글 상단 배치하기"
date: 2025-10-09T09:30:00+09:00
draft: false
tags: ["hugo", "automation", "blog", "tips"]
categories: ["Blog"]
---

## 문제 상황

Hugo 블로그를 운영하다 보면 다음과 같은 불편함이 있습니다:

1. **매번 날짜를 수동으로 입력해야 함** - 새 글을 쓸 때마다 frontmatter에 날짜를 작성
2. **기존 글 수정 시 날짜가 업데이트되지 않음** - 글을 수정해도 목록 순서가 변하지 않음
3. **정렬 순서가 마음대로 안 됨** - 최신 글이 맨 위로 올라오지 않음

이런 문제들을 Hugo의 자동 날짜 관리 기능으로 한 번에 해결할 수 있습니다!

---

## 해결 방법

### 1. 게시물 날짜 역순 정렬

먼저 최신 글이 맨 위에 오도록 정렬 기능을 추가합니다.

**파일 생성:** `layouts/_default/list.html`

테마의 기본 `list.html`을 커스터마이징하기 위해 프로젝트 루트에 `layouts/_default/` 디렉토리를 만들고 파일을 생성합니다.

```bash
mkdir -p layouts/_default
```

`list.html` 파일에서 페이지를 가져오는 부분에 정렬 코드를 추가합니다:

```go
{{- $pages := union .RegularPages .Sections }}

{{- if .IsHome }}
{{- $pages = where site.RegularPages "Type" "in" site.Params.mainSections }}
{{- $pages = where $pages "Params.hiddenInHomeList" "!=" "true"  }}
{{- end }}

{{/* 이 부분을 추가! */}}
{{- $pages = $pages.ByDate.Reverse }}

{{- $paginator := .Paginate $pages }}
```

**핵심 코드:** `{{- $pages = $pages.ByDate.Reverse }}`
- `ByDate`: 날짜순으로 정렬
- `Reverse`: 역순 (최신이 위로)

---

### 2. 자동 날짜 관리 설정

이제 가장 중요한 부분입니다. `config.toml`에 자동 날짜 관리 설정을 추가합니다.

```toml
# Git 정보 활성화
enableGitInfo = true

# Frontmatter 날짜 우선순위 설정
[frontmatter]
  date = [":git", ":fileModTime", "date", "publishDate", "lastmod"]
```

#### 설정 설명

**`enableGitInfo = true`**
- Git commit 정보를 Hugo가 사용할 수 있도록 활성화합니다
- Git 저장소에서만 작동합니다

**`[frontmatter]` 섹션의 `date` 배열**

Hugo는 배열의 **왼쪽부터 우선순위**를 가집니다:

1. **`:git`** (최우선)
   - Git commit 날짜를 사용
   - 파일이 마지막으로 커밋된 시간
   - Git 저장소가 필요합니다

2. **`:fileModTime`** (2순위)
   - 파일 시스템의 수정 시간
   - 파일을 저장하면 자동으로 업데이트
   - Git 없이도 작동합니다

3. **`date`** (3순위)
   - Frontmatter에 직접 작성한 `date:` 필드
   - 수동으로 날짜를 지정하고 싶을 때 사용

4. **`publishDate`** (4순위)
   - 발행 날짜 필드

5. **`lastmod`** (최하위)
   - 마지막 수정 날짜 필드

---

## 동작 원리

### 시나리오 1: 새 글 작성

```bash
# 새 글 생성
hugo new posts/my-new-post.md
```

1. 파일이 생성됨 → `:fileModTime`이 현재 시간으로 설정
2. 글 작성 후 Git commit → `:git`이 커밋 시간으로 설정
3. **결과:** 최신 커밋이므로 블로그 맨 위에 표시됨!

### 시나리오 2: 기존 글 수정

```bash
# 글 수정 후 저장
vim content/posts/old-post.md

# Git commit
git add content/posts/old-post.md
git commit -m "글 내용 업데이트"
```

1. 파일 저장 → `:fileModTime`이 업데이트됨
2. Git commit → `:git`이 현재 시간으로 업데이트됨
3. **결과:** 수정한 글이 자동으로 맨 위로 올라감!

### 시나리오 3: 날짜 고정하기

특정 날짜로 고정하고 싶다면 frontmatter에 직접 작성:

```yaml
---
title: "과거 날짜 고정 글"
date: 2024-01-01
draft: false
---
```

이렇게 하면 파일을 수정해도 날짜가 2024-01-01로 고정됩니다.

---

## 장점

### ✅ 자동화
- 날짜를 신경 쓰지 않아도 됨
- 파일 수정만 해도 자동으로 최신 글로 인식

### ✅ 유연성
- Git commit 시간과 파일 수정 시간 중 선택 가능
- 원하면 수동 날짜도 설정 가능

### ✅ 정확성
- Git commit 기록이 정확한 날짜 정보 제공
- 파일 시스템 시간으로 백업 가능

---

## 주의사항

### Git 저장소가 필요
`:git` 옵션을 사용하려면 Git 저장소여야 합니다:

```bash
# Git 저장소 초기화 (아직 안 했다면)
git init
git add .
git commit -m "Initial commit"
```

### 날짜 우선순위 이해하기

- **파일을 수정만** 하면: `:fileModTime`이 업데이트
- **Git commit**까지 하면: `:git`이 업데이트
- **Frontmatter에 date 작성**하면: 그 날짜로 고정

---

## 추가 팁

### 1. 작성 날짜와 수정 날짜 분리

```toml
[frontmatter]
  date = ["date", "publishDate"]           # 작성 날짜 (고정)
  lastmod = [":git", ":fileModTime"]       # 수정 날짜 (자동)
```

### 2. 날짜 포맷 표시

게시물 템플릿에서 수정 날짜를 표시:

```html
<div class="post-meta">
  작성: {{ .Date.Format "2006-01-02" }}
  {{ with .Lastmod }}
  | 수정: {{ .Format "2006-01-02" }}
  {{ end }}
</div>
```

### 3. 여러 정렬 옵션

Hugo는 다양한 정렬 방법을 지원합니다:

```go
{{- $pages.ByDate }}           # 오래된 글 먼저
{{- $pages.ByDate.Reverse }}   # 최신 글 먼저
{{- $pages.ByTitle }}          # 제목순
{{- $pages.ByWeight }}         # Weight 필드순
```

---

## 결론

Hugo의 자동 날짜 관리 기능을 활용하면:
- ✅ 날짜 입력 불필요
- ✅ 자동으로 최신 글이 위로
- ✅ Git 기반의 정확한 날짜 관리

이제 글 작성과 수정에만 집중하고, 날짜는 Hugo가 알아서 관리하게 하세요!

---

## 참고 자료

- [Hugo Front Matter Configuration](https://gohugo.io/getting-started/configuration/#configure-front-matter)
- [Hugo Page Variables](https://gohugo.io/variables/page/)
- [Hugo List Templates](https://gohugo.io/templates/lists/)
