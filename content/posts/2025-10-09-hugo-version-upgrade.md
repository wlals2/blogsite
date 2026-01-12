---
title: "Hugo 버전 업그레이드 및 PaperMod 테마 호환성 해결"
date: 2025-10-09T09:00:00+09:00
draft: false
tags: ["hugo", "papermod", "troubleshooting", "blog"]
categories: ["DevOps"]
---

## 문제 상황

Hugo 블로그를 실행하려고 했더니 다음과 같은 에러가 발생했습니다:

```

WARN  Module "PaperMod" is not compatible with this Hugo version
ERROR => hugo v0.146.0 or greater is required for hugo-PaperMod to build

```

기존에 설치된 Hugo 버전은 v0.120.4였고, PaperMod 테마는 v0.146.0 이상을 요구하고 있었습니다.

---

## 해결 과정

### 1. Hugo 버전 업그레이드

```bash
# snap으로 설치된 Hugo 업데이트
sudo snap refresh hugo

# 버전 확인
hugo version

```

업데이트 후 v0.146.0으로 정상 업그레이드 되었습니다.

---

### 2. Google Analytics 호환성 문제

Hugo v0.146.0에서는 `.Site.GoogleAnalytics`가 deprecated 되어 새로운 에러가 발생했습니다:

```

error calling partial: can't evaluate field GoogleAnalytics in type page.Site

```

**해결 방법:** `layouts/partials/google_analytics.html` 파일 수정

```html
<!-- 기존 코드 -->
{{- if .Site.GoogleAnalytics }}
<script async src="https://www.googletagmanager.com/gtag/js?id={{ .Site.GoogleAnalytics }}"></script>
{{- end }}

<!-- 수정된 코드 -->
{{- with .Site.Config.Services.GoogleAnalytics.ID }}
<script async src="https://www.googletagmanager.com/gtag/js?id={{ . }}"></script>
{{- end }}

```

---

### 3. 게시물 날짜 정렬 문제

블로그 목록에서 게시물이 날짜순으로 정렬되지 않는 문제가 있었습니다.

**해결 방법 1:** `layouts/_default/list.html` 파일 생성 및 수정

테마의 기본 list.html을 복사한 후, 다음 라인을 추가:

```go
{{- $pages = $pages.ByDate.Reverse }}

```

이렇게 하면 최신 게시물이 맨 위로 올라옵니다.

**해결 방법 2:** 자동 날짜 관리 설정

매번 날짜를 수동으로 관리하는 것이 번거로워서 자동화 설정을 추가했습니다.

`config.toml`에 다음 설정 추가:

```toml
enableGitInfo = true

[frontmatter]
  date = [":git", ":fileModTime", "date", "publishDate", "lastmod"]

```

이제 날짜 우선순위가 다음과 같이 설정됩니다:
1. Git commit 날짜 (`:git`)
2. 파일 수정 시간 (`:fileModTime`)
3. Frontmatter의 수동 date 필드

이렇게 설정하면 파일을 수정만 해도 자동으로 최신 글이 위로 올라옵니다!

---

## 결과

모든 문제가 해결되어 Hugo 블로그가 정상적으로 작동합니다:
- ✅ Hugo v0.146.0으로 업그레이드
- ✅ PaperMod 테마 정상 작동
- ✅ Google Analytics 호환성 해결
- ✅ 게시물 자동 날짜 정렬

---

## 교훈

1. **테마 호환성 확인**: Hugo 업그레이드 시 테마가 요구하는 버전을 먼저 확인해야 합니다.
2. **Deprecated API 대응**: 새 버전에서는 기존 API가 변경될 수 있으므로 공식 문서를 참고해야 합니다.
3. **자동화의 중요성**: 반복 작업(날짜 관리)은 자동화 설정으로 편리하게 관리할 수 있습니다.

---

## 참고 링크

- [Hugo Documentation](https://gohugo.io/documentation/)
- [PaperMod Theme](https://github.com/adityatelange/hugo-PaperMod)
- [Hugo Date Configuration](https://gohugo.io/getting-started/configuration/#configure-dates)
