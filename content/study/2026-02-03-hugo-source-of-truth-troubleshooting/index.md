---
title: "Hugo 블로그 배포 트러블슈팅: Source of Truth 우선순위 문제"
date: 2026-02-03T05:00:00+09:00
summary: "config.toml vs layouts 충돌로 OLD 콘텐츠가 표시된 문제를 해결한 과정. 우선순위가 명확하지 않으면 오류가 발생한다."
tags: ["hugo", "troubleshooting", "docker", "gitops", "kubernetes"]
categories: ["study"]
series: ["트러블슈팅"]
weight: 1
showtoc: true
tocopen: true
draft: false
---

## 문제 상황

홈페이지에 새로운 프로젝트 카드("멀티클라우드 고가용성 DR 아키텍처", "베어메탈 K8s 제로트러스트")를 추가했는데, https://blog.jiminhome.shop 에는 **OLD 콘텐츠**("📊 핵심 성과", "📅 프로젝트 타임라인")가 계속 표시되었다.

**기대 결과**: 새로운 프로젝트 카드 2개가 크게 표시
**실제 결과**: 이전 섹션들이 그대로 표시

---

## 진단 과정

### 가설 1: config.toml 수정이 반영 안 됨?

**확인**:
```bash
grep "주요 프로젝트" config.toml
grep "멀티클라우드" config.toml
```

**결과**: config.toml에는 NEW 콘텐츠가 **있음** ✅

**결론**: config.toml은 정상. 다른 문제.

---

### 가설 2: Hugo 빌드 캐시 문제?

**확인**:
```bash
rm -rf public resources .hugo_build.lock
hugo --minify --gc
grep "주요 프로젝트" public/index.html
```

**결과**: public/index.html에 OLD 콘텐츠가 있음 ❌

**결론**: Hugo가 OLD 콘텐츠를 생성하고 있음. 빌드 캐시 문제 아님.

---

### 가설 3: Docker 빌드 캐시 문제?

**배경**:
- Git push → GitHub Actions → Docker 이미지 빌드 → GHCR → ArgoCD 배포
- v110-v113까지 같은 Docker 이미지 ID (cb1ad2685a6e)

**의심**:
- Docker가 캐시를 재사용해서 OLD 콘텐츠를 포함하는 게 아닐까?

**시도**:
```dockerfile
# Dockerfile에 ARG 추가
ARG GIT_COMMIT=unknown
RUN echo "Building from commit: $GIT_COMMIT"
```

```yaml
# GitHub Actions workflow
build-args: |
  GIT_COMMIT=${{ github.sha }}
```

**확인**:
```bash
docker build --no-cache -t test:local .
docker run --rm test:local cat /usr/share/nginx/html/index.html | grep "주요 프로젝트"
```

**결과**: 로컬에서 `--no-cache`로 빌드해도 OLD 콘텐츠 ❌

**결론**: Docker 캐시 문제 아님. Hugo 자체가 OLD 콘텐츠를 생성하고 있음.

---

### 가설 4: layouts vs config 충돌? (정답!)

**발견**:
```bash
grep -r "클라우드 엔지니어 지망생" .
# → layouts/index.html에 하드코딩되어 있음!
```

**문제 분석**:

**Hugo Template 우선순위**:
```
1. layouts/index.html (root)     ← 가장 높음
2. config.toml (subtitle)
3. themes/PaperMod/layouts/
```

**실제 상황**:
- `layouts/index.html`: OLD 콘텐츠 하드코딩
- `config.toml`: NEW 콘텐츠 (subtitle에 HTML 200줄)
- Hugo는 `layouts/index.html`을 우선 사용 → OLD 콘텐츠 렌더링

**근본 원인**:
- **두 곳에서 같은 콘텐츠를 관리** → 우선순위 불명확
- config.toml의 subtitle을 읽는다고 생각했지만, 실제로는 layouts가 하드코딩을 override

---

## 해결 과정

### Step 1: layouts/index.html 확인

```bash
cat layouts/index.html | grep -A5 "클라우드 엔지니어"
```

**발견**:
```html
<p>클라우드 엔지니어 지망생입니다.</p>
<h3>📊 핵심 성과</h3>
[OLD 하드코딩된 HTML...]
```

**문제**: 이 하드코딩이 config.toml의 subtitle을 완전히 무시하고 있었음.

---

### Step 2: 임시 해결 (v115 배포)

```html
<!-- layouts/index.html -->
{{ .subtitle | safeHTML }}
```

layouts의 하드코딩을 제거하고 config.toml의 subtitle을 읽도록 수정.

**결과**: v115 배포 후 NEW 콘텐츠 표시 ✅

**하지만**:
- config.toml에 HTML 200줄 → 잘못된 구조
- 우선순위가 여전히 불명확

---

### Step 3: 구조 개선 (근본 해결)

**Before** (문제 있는 구조):
```
config.toml: HTML 200줄 (subtitle)
   ↓
layouts/index.html: {{ .subtitle | safeHTML }}
```

**문제점**:
- config는 **설정용**인데 콘텐츠(HTML)가 들어감
- TOML 문자열로 HTML 관리 → 가독성 최악
- Hugo best practice 위반

**After** (올바른 구조):
```
layouts/index.html: HTML 직접 작성 ⭐
config.toml: subtitle = "" (간소화)
```

**장점**:
- 우선순위 명확: **layouts만 Source of Truth**
- HTML 편집 쉬움
- config는 설정만

---

### Step 4: 최종 수정

**layouts/index.html**:
```html
{{- /* Profile Content - 직접 작성 (Source of Truth) */ -}}
<div class="profile-section">
  <h3>주요 프로젝트</h3>
  <div class="project-card">
    <h3>멀티클라우드 고가용성 DR 아키텍처</h3>
    [HTML 직접 작성...]
  </div>
</div>
```

**config.toml**:
```toml
# subtitle은 layouts/index.html에서 직접 작성됨 (Source of Truth)
subtitle = ""
```

**결과**: 구조 명확화 ✅

---

## 배포 과정에서의 추가 문제

### 문제: v115가 배포되지 않음

**확인**:
```bash
kubectl get rollout web -n blog-system \
  -o jsonpath='{.spec.template.spec.containers[0].image}'
# → ghcr.io/wlals2/blog-web:v114
```

**원인**: Argo Rollouts Canary 배포가 **Paused** 상태

**해결**:
```bash
# 1. ArgoCD 수동 Sync
kubectl patch application blog-system -n argocd \
  --type merge -p '{"operation":{"sync":{"revision":"HEAD"}}}'

# 2. Rollouts 수동 Promote (여러 단계 건너뛰기)
for i in {1..5}; do
  kubectl argo rollouts promote web -n blog-system
  sleep 5
done
```

**결과**: v115 배포 완료 ✅

---

## 교훈 및 행동강령

### 1. Source of Truth 우선순위를 명확히 하라

**원칙**: 같은 콘텐츠를 2곳에서 관리하면 충돌한다.

**Hugo에서**:
```
layouts/ (최상위 우선순위)
  ↓
config.toml (설정만)
  ↓
data/ (구조화된 데이터)
```

**금지**:
- config.toml에 HTML 200줄
- layouts 하드코딩 + config subtitle 동시 사용

**권장**:
- **한 곳만** Source of Truth로 정하기
- layouts에 HTML 직접 작성
- config는 간단한 설정만

---

### 2. 트러블슈팅 순서: 계층별로 진단하라

**순서**:
1. 파일 확인 (config.toml, layouts/)
2. 로컬 빌드 테스트 (Hugo)
3. Docker 이미지 확인
4. 배포 상태 확인 (ArgoCD, Rollouts)

**잘못된 접근**:
- Docker 캐시를 먼저 의심 → 시간 낭비
- 로컬 빌드 테스트 생략 → 문제 위치 파악 못 함

**올바른 접근**:
- 로컬 빌드부터 확인 → 문제 범위 좁히기
- 계층별로 하나씩 검증

---

### 3. "우선순위가 없으면 오류가 발생한다"

**사례**:
- config.toml과 layouts 모두에 콘텐츠 → 충돌
- 어느 게 진짜인지 불명확 → 유지보수 불가능

**원칙**:
- 시스템에는 항상 **우선순위**가 있음
- 우선순위를 모르면 디버깅 불가
- 문서화 필수 (CLAUDE.md Section 14)

---

### 4. GitOps 환경에서 긴급 배포 방법

**정상 흐름** (3-5분):
```bash
git commit && git push
# GitHub Actions → Docker → ArgoCD (자동)
```

**긴급 배포** (30초):
```bash
# ArgoCD Sync
kubectl patch application blog-system -n argocd \
  --type merge -p '{"operation":{"sync":{}}}'

# Rollouts Promote
kubectl argo rollouts promote web -n blog-system
```

**주의**:
- GitOps 철학상 kubectl edit은 금지
- Git Manifest가 항상 Source of Truth
- 긴급 배포 후에도 Git commit 필수

---

## 체크리스트: 홈페이지 수정 시

**작업 전**:
- [ ] layouts/index.html만 수정 (config.toml에 HTML 넣지 않기)
- [ ] 우선순위 명확한가? (한 곳만 Source of Truth)
- [ ] config.toml은 설정만 (title, baseURL 등)

**작업 중**:
- [ ] 로컬 빌드 테스트 (`hugo --minify`)
- [ ] `grep` 으로 결과 확인 (public/index.html)
- [ ] Docker 이미지 빌드 (선택)

**배포 후**:
- [ ] GitHub Actions 완료 확인 (3-5분)
- [ ] ArgoCD Sync 상태 확인
- [ ] Rollouts Paused 시 수동 Promote
- [ ] 실제 사이트 확인 (https://blog.jiminhome.shop)

---

## 관련 문서

- [CLAUDE.md Section 14](https://github.com/wlals2/blogsite/blob/main/CLAUDE.md#14-hugo-블로그-관리-blogsite): Hugo 블로그 관리 규칙
- [Hugo Template Lookup Order](https://gohugo.io/templates/lookup-order/): Hugo 공식 문서
- [Argo Rollouts Canary](https://argoproj.github.io/argo-rollouts/features/canary/): Canary 배포 전략

---

## 요약

| 항목 | 문제 | 해결 |
|------|------|------|
| **증상** | OLD 콘텐츠 표시 | - |
| **원인** | layouts 하드코딩 vs config subtitle 충돌 | 우선순위 불명확 |
| **임시 해결** | layouts에서 `{{ .subtitle }}` 사용 | v115 배포 |
| **근본 해결** | layouts를 Source of Truth로 | 구조 개선 |
| **교훈** | 우선순위 없으면 오류 발생 | 한 곳만 관리 |
| **시간** | 문제 발견 ~ 해결: 약 2시간 | 진단 1.5h, 해결 30m |

**핵심 교훈**: "같은 데이터를 여러 곳에서 관리하면 언제든 충돌한다. Source of Truth를 한 곳으로 정하라."

---

**작성일**: 2026-02-03
**카테고리**: Troubleshooting
**난이도**: 중급
**예상 해결 시간**: 2시간 (진단 1.5h + 해결 30m)
