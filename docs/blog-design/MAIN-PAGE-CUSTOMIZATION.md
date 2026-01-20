# 메인 페이지 커스터마이징 가이드

> Hugo PaperMod 테마 완전 우회 방식

---

## 📌 요약

### 구현한 기능
- 📅 프로젝트 타임라인 (3개 항목, 호버 효과)
- 📊 스킬 바 애니메이션 (4개 스킬, 퍼센트 표시)
- 🔢 숫자 카운트업 애니메이션 (67%, 99.9%, 94%)
- 💫 스크롤 기반 Fade-in 효과
- 🎨 다크모드 지원

### 핵심 해결 과제
**PaperMod 테마가 subtitle의 HTML을 제거하는 문제를 완전히 우회**

---

## 🔍 문제 발견 과정

### 1단계: config.toml에 HTML 추가 시도
```toml
[params.profileMode]
  subtitle = """
  <div class="timeline">
    <div class="timeline-item">...</div>
  </div>
  """
```

**결과**: ❌ 실패
- **원인**: PaperMod의 `themes/PaperMod/layouts/partials/index_profile.html`이 subtitle을 `<p>` 태그로 감싸면서 내부 HTML 태그 제거

### 2단계: 테마 파일 직접 수정 시도
```html
{{- /* themes/PaperMod/layouts/partials/index_profile.html */ -}}
<span>{{ .subtitle | safeHTML }}</span>
```

**결과**: ❌ 실패
- **원인**: Hugo의 템플릿 우선순위 때문에 `<p>` 태그 렌더링 강제됨
- **문제**: `<p>` 안에 `<div>` 같은 블록 요소를 넣으면 브라우저가 자동으로 태그를 분리/제거

### 3단계: layouts/partials/ 생성 시도
```bash
mkdir -p layouts/partials
cp themes/PaperMod/layouts/partials/index_profile.html layouts/partials/
```

**결과**: ❌ 실패
- **원인**: Hugo가 여전히 `themes/PaperMod/layouts/_default/list.html`을 사용
- `list.html`이 `partial "index_profile.html"`을 호출하지만, 상위 템플릿 로직은 여전히 PaperMod 것 사용

---

## ✅ 최종 해결 방법

### 방법 3: layouts/index.html 생성 (완전 우회)

**Hugo 템플릿 우선순위:**
```
1. layouts/index.html          ← 최우선
2. layouts/_default/list.html
3. themes/PaperMod/layouts/_default/list.html
```

**핵심 아이디어**:
- PaperMod 테마 전체를 우회하고 완전히 새로운 HTML 구조 작성
- config.toml의 subtitle을 사용하지 않고, 모든 HTML을 layouts/index.html에 직접 작성

---

## 📂 파일별 역할

### 1. `/home/jimin/blogsite/layouts/index.html`
**역할**: 메인 페이지 HTML 구조 정의 (200+ 라인)

**주요 코드 구조**:
```html
{{- define "main" }}

<div class="profile">
    {{- with site.Params.profileMode }}
    <div class="profile_inner">
        {{- /* 프로필 이미지 처리 (PaperMod 로직 복사) */ -}}
        {{- if .imageUrl -}}
          {{- /* ... 이미지 리사이징 로직 ... */ -}}
        {{- end }}

        {{- /* 제목 */ -}}
        <h1>{{ .title | default site.Title | markdownify }}</h1>

        {{- /* 커스텀 콘텐츠 - HTML 직접 작성 */ -}}
        <div class="profile-content">
            <p style="margin-bottom: 2rem;">클라우드 엔지니어 지망생입니다.</p>

            {{- /* 핵심 성과 섹션 */ -}}
            <div class="profile-section">
                <h3>📊 핵심 성과</h3>
                <div class="highlight-box">
                    배포 시간 <span class='metric-badge' data-count='67' data-suffix='% 단축'>0%</span> (30분 → 10분) ·
                    가용성 <span class='metric-badge' data-count='99.9' data-suffix='%'>0%</span> 달성 ·
                    인프라 구축 <span class='metric-badge' data-count='94' data-suffix='% 자동화'>0%</span>
                </div>
            </div>

            {{- /* 타임라인 섹션 */ -}}
            <div class="profile-section">
                <h3>📅 프로젝트 타임라인</h3>
                <div class="timeline">
                    <div class="timeline-item">
                        <div class="timeline-date">2025.11 ~ 현재</div>
                        <div class="timeline-content">
                            <strong>AWS EKS + Multi-Cloud DR</strong><br>
                            3-Tier 아키텍처 구축 · 99.9% 가용성 달성 · Canary 배포 자동화<br>
                            <small style="color: var(--secondary);">Kubernetes, EKS, Route53, Azure, Terraform</small>
                        </div>
                    </div>
                    {{- /* 2개 타임라인 아이템 더 ... */ -}}
                </div>
            </div>

            {{- /* 기술 스택 섹션 */ -}}
            <div class="profile-section">
                <h3>💻 기술 스택</h3>
                <div style="margin-bottom: 1.5rem;">
                    {{- /* 스킬 바 4개 */ -}}
                    <div style="margin-bottom: 1rem;">
                        <div style="display: flex; justify-content: space-between; margin-bottom: 0.3rem;">
                            <span><strong>Kubernetes & Container</strong></span>
                            <span>85%</span>
                        </div>
                        <div class="skill-bar">
                            <div class="skill-bar-fill" data-percentage="85"></div>
                        </div>
                    </div>
                    {{- /* ... 나머지 3개 스킬 바 ... */ -}}
                </div>
            </div>

            {{- /* 관심사, 블로그 목표 섹션 */ -}}
        </div>

        {{- /* 소셜 아이콘 & 버튼 (PaperMod 로직 사용) */ -}}
        {{- partial "social_icons.html" . -}}
        {{- with .buttons }}
          {{- /* ... 버튼 렌더링 ... */ -}}
        {{- end }}
    </div>
    {{- end }}
</div>

{{- end }}{{- /* end main */ -}}
```

**핵심 포인트**:
- `{{- define "main" }}` 블록으로 전체 메인 콘텐츠 재정의
- PaperMod의 프로필 이미지 로직만 재사용 (6-35행)
- 나머지는 모두 직접 작성한 HTML

---

### 2. `/home/jimin/blogsite/static/css/custom.css`
**역할**: 타임라인, 스킬바, 애니메이션 스타일 정의

**주요 CSS 클래스**:
```css
/* 섹션 박스 */
.profile-section {
    background: var(--entry);
    border: 1px solid var(--border);
    border-radius: 8px;
    padding: 1.5rem;
    margin-bottom: 1.5rem;
    transition: all 0.3s ease;
}

.profile-section:hover {
    transform: translateY(-2px);
    box-shadow: 0 4px 12px rgba(0,0,0,0.1);
}

/* 타임라인 */
.timeline {
    position: relative;
    padding-left: 2rem;
}

.timeline::before {
    content: '';
    position: absolute;
    left: 0;
    top: 0;
    bottom: 0;
    width: 2px;
    background: linear-gradient(to bottom, var(--primary), var(--secondary));
}

.timeline-item {
    position: relative;
    margin-bottom: 2rem;
    padding-left: 1.5rem;
}

.timeline-item::before {
    content: '●';
    position: absolute;
    left: -2.2rem;
    color: var(--primary);
    font-size: 1.2rem;
}

/* 스킬 바 */
.skill-bar {
    background-color: var(--code-bg);
    border-radius: 10px;
    height: 20px;
    overflow: hidden;
    position: relative;
}

.skill-bar-fill {
    height: 100%;
    background: linear-gradient(90deg, var(--primary), var(--secondary));
    width: 0; /* JavaScript가 애니메이션으로 변경 */
    transition: width 1.5s cubic-bezier(0.4, 0, 0.2, 1);
    border-radius: 10px;
}

/* 메트릭 배지 */
.metric-badge {
    display: inline-block;
    background: linear-gradient(135deg, var(--primary), var(--secondary));
    color: white;
    padding: 0.2rem 0.6rem;
    border-radius: 12px;
    font-weight: 600;
    font-size: 0.9rem;
    box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
}

/* 다크모드 변수 */
:root {
    --primary: #1e90ff;
    --secondary: #00bfff;
    --entry: #ffffff;
    --border: #e0e0e0;
    --code-bg: #f5f5f5;
}

[data-theme="dark"] {
    --primary: #4a9eff;
    --secondary: #00d4ff;
    --entry: #1e1e1e;
    --border: #3a3a3a;
    --code-bg: #2a2a2a;
}
```

---

### 3. `/home/jimin/blogsite/static/js/animations.js`
**역할**: 스킬바 애니메이션, 숫자 카운트업, 스크롤 효과

**주요 함수**:

#### 1) 스킬 바 애니메이션
```javascript
function animateSkillBars() {
    const skillBars = document.querySelectorAll('.skill-bar-fill');

    const observer = new IntersectionObserver((entries) => {
        entries.forEach(entry => {
            if (entry.isIntersecting) {
                const bar = entry.target;
                let percentage = bar.getAttribute('data-percentage');

                if (percentage) {
                    setTimeout(() => {
                        bar.style.width = percentage + '%'; // CSS transition 실행
                    }, 100);
                }

                observer.unobserve(bar);
            }
        });
    }, { threshold: 0.5 }); // 50% 이상 보이면 실행

    skillBars.forEach(bar => observer.observe(bar));
}
```

**작동 원리**:
1. `IntersectionObserver`로 화면에 스킬바가 50% 이상 보이는지 감지
2. 보이면 `data-percentage` 속성 읽기 (예: `data-percentage="85"`)
3. `bar.style.width = "85%"` 설정
4. CSS의 `transition: width 1.5s` 효과로 부드럽게 애니메이션

#### 2) 숫자 카운트업 애니메이션
```javascript
function animateCounter(element) {
    let target = element.getAttribute('data-count');
    let suffix = element.getAttribute('data-suffix') || '';

    if (!target) {
        // 텍스트에서 숫자 추출 (백업)
        const text = element.textContent.trim();
        const match = text.match(/(\d+\.?\d*)/);
        if (!match) return;

        target = parseFloat(match[1]);
        suffix = text.replace(match[1], '').trim();
    } else {
        target = parseFloat(target);
    }

    const duration = 2000; // 2초
    const steps = 60;
    const increment = target / steps;
    let current = 0;

    const timer = setInterval(() => {
        current += increment;
        if (current >= target) {
            const finalValue = target % 1 === 0 ? target : target.toFixed(1);
            element.textContent = finalValue + suffix;
            clearInterval(timer);
        } else {
            const currentValue = target % 1 === 0 ? Math.floor(current) : current.toFixed(1);
            element.textContent = currentValue + suffix;
        }
    }, duration / steps);
}
```

**작동 원리**:
1. HTML: `<span class='metric-badge' data-count='67' data-suffix='% 단축'>0%</span>`
2. JavaScript가 `data-count="67"`, `data-suffix="% 단축"` 읽기
3. 0부터 67까지 2초 동안 60단계로 증가
4. 최종: `67% 단축` 표시

---

### 4. `/home/jimin/blogsite/layouts/partials/extend_head.html`
**역할**: CSS/JS 파일 로드

**수정 전 (문제 발생)**:
```html
<link rel="stylesheet" href="{{ "css/custom.css" | absURL }}" />
<script src="{{ "js/animations.js" | absURL }}" defer></script>
```

**수정 후 (해결)**:
```html
<link rel="stylesheet" href="/css/custom.css" />
<script src="/js/animations.js" defer></script>
```

**왜 수정했나?**
- Hugo의 `absURL` 함수는 `http://localhost:1313/css/custom.css` 같은 **절대 URL** 생성
- 사용자가 `192.168.1.187:1313`로 접속하면 Hugo는 여전히 `localhost` URL 생성
- 브라우저가 잘못된 도메인으로 요청하고 **취소(cancelled)** 처리
- `/css/custom.css` 같은 **루트 상대 경로**를 사용하면 어떤 도메인으로 접속해도 작동

---

## ⚖️ 트레이드오프 (장단점 비교)

### 방법 1: config.toml 수정 (실패)
```toml
[params.profileMode]
  subtitle = "<div class='timeline'>...</div>"
```

| 장점 | 단점 |
|------|------|
| 설정 파일만 수정 (간단) | ❌ PaperMod가 HTML 제거 |
| 테마 업데이트 영향 없음 | ❌ 블록 요소 불가능 |

**결론**: 불가능

---

### 방법 2: 테마 파일 직접 수정 (실패)
```bash
vim themes/PaperMod/layouts/partials/index_profile.html
```

| 장점 | 단점 |
|------|------|
| 테마 로직 완전 제어 | ❌ 테마 업데이트 시 덮어씌워짐 |
| - | ❌ Git submodule 충돌 가능 |
| - | ❌ 여전히 `<p>` 태그 강제 |

**결론**: 유지보수 불가능

---

### 방법 3: layouts/index.html 생성 (채택) ✅
```bash
vim layouts/index.html
```

| 장점 | 단점 |
|------|------|
| ✅ PaperMod 완전 우회 | ⚠️ HTML 200+ 라인 (복잡) |
| ✅ HTML 구조 완전 제어 | ⚠️ 테마 업데이트 혜택 못 받음 |
| ✅ 테마 업데이트 영향 없음 | ⚠️ 이미지 로직 수동 복사 필요 |
| ✅ Git 관리 가능 | - |
| ✅ 재사용 가능 (partials로 분리 가능) | - |

**결론**: 완전한 제어가 필요한 경우 최적 ✅

---

## 🛠️ 트러블슈팅

### 문제 1: CSS/JS 파일 로드 실패 (Network: cancelled)

**증상**:
```
브라우저 Network 탭:
custom.css   Status: (cancelled)   Type: stylesheet
animations.js Status: (cancelled)  Type: script
```

**원인**:
- `absURL` 함수가 `http://localhost:1313/css/custom.css` 생성
- 사용자가 `192.168.1.187:1313`로 접속
- 브라우저가 `localhost`로 요청 → 실패 → 취소

**해결**:
```html
<!-- Before -->
<link rel="stylesheet" href="{{ "css/custom.css" | absURL }}" />

<!-- After -->
<link rel="stylesheet" href="/css/custom.css" />
```

**확인 방법**:
```bash
# 1. 파일 존재 확인
ls -lh /home/jimin/blogsite/static/css/custom.css

# 2. Hugo 서버에서 서빙 확인
curl -I http://localhost:1313/css/custom.css
# 예상 결과: HTTP/1.1 200 OK

# 3. 브라우저 Network 탭 확인
# Status: 200 OK (더 이상 cancelled 아님)
```

---

### 문제 2: 스킬바 애니메이션 작동 안 함

**증상**:
- 스킬바가 0% 상태로 고정
- `data-percentage` 속성이 있어도 애니메이션 없음

**원인**:
- `animations.js` 파일 로드 실패
- 또는 IntersectionObserver가 요소를 감지 못함

**해결**:
```javascript
// 브라우저 개발자 도구 Console에서 확인
console.log(document.querySelectorAll('.skill-bar-fill').length);
// 예상: 4 (스킬바 4개)

console.log(document.querySelector('.skill-bar-fill').getAttribute('data-percentage'));
// 예상: "85"
```

**디버깅 단계**:
1. F12 → Console → 에러 메시지 확인
2. Network → `animations.js` 200 OK인지 확인
3. Console → `✨ Animations initialized` 로그 확인

---

### 문제 3: 다크모드에서 스타일 깨짐

**증상**:
- 라이트 모드에서는 정상, 다크 모드에서 색상 이상함
- 텍스트가 안 보이거나 배경색이 이상함

**원인**:
- CSS 변수 (`--primary`, `--entry` 등) 다크모드 정의 누락

**해결**:
```css
/* custom.css에 추가 */
:root {
    --primary: #1e90ff;
    --secondary: #00bfff;
    --entry: #ffffff;
    --border: #e0e0e0;
}

[data-theme="dark"] {
    --primary: #4a9eff;
    --secondary: #00d4ff;
    --entry: #1e1e1e;
    --border: #3a3a3a;
}
```

**확인 방법**:
```javascript
// 브라우저 Console에서
getComputedStyle(document.documentElement).getPropertyValue('--entry')
// 라이트 모드: "#ffffff"
// 다크 모드: "#1e1e1e"
```

---

## 🔄 다른 페이지에 적용하는 방법

### 현재 상황
- ✅ **메인 페이지 (`/`)**: `layouts/index.html` 사용
- ❓ **다른 페이지 (프로젝트, About 등)**: 아직 적용 안 됨

### 문제점
**Hugo의 페이지 타입별 템플릿**:
```
/               → layouts/index.html
/posts/post1/   → layouts/_default/single.html
/about/         → layouts/_default/single.html
/projects/      → layouts/_default/list.html
```

**현재는 메인 페이지만 `layouts/index.html`로 커스터마이징됨!**

---

### 해결 방법 1: Partial로 분리 (추천) ✅

#### 1단계: 공통 컴포넌트를 Partial로 추출
```bash
# 타임라인 컴포넌트 생성
vim layouts/partials/timeline.html
```

**`layouts/partials/timeline.html`**:
```html
<div class="profile-section">
    <h3>📅 프로젝트 타임라인</h3>
    <div class="timeline">
        {{- range .timelines }}
        <div class="timeline-item">
            <div class="timeline-date">{{ .date }}</div>
            <div class="timeline-content">
                <strong>{{ .title }}</strong><br>
                {{ .description }}<br>
                <small style="color: var(--secondary);">{{ .tech }}</small>
            </div>
        </div>
        {{- end }}
    </div>
</div>
```

#### 2단계: Front Matter에 데이터 정의
**`content/about.md`**:
```yaml
---
title: "About Me"
layout: "about"
timelines:
  - date: "2025.11 ~ 현재"
    title: "AWS EKS + Multi-Cloud DR"
    description: "3-Tier 아키텍처 구축 · 99.9% 가용성 달성"
    tech: "Kubernetes, EKS, Route53, Azure, Terraform"
  - date: "2025.09 ~ 2025.10"
    title: "CI/CD Pipeline 구축"
    description: "Jenkins + ArgoCD 기반 GitOps 파이프라인"
    tech: "Jenkins, ArgoCD, Kubernetes"
---

여기는 About 페이지 내용입니다.
```

#### 3단계: 커스텀 레이아웃 생성
**`layouts/_default/about.html`**:
```html
{{- define "main" }}

<article class="post-single">
  <header class="post-header">
    <h1>{{ .Title }}</h1>
  </header>

  {{- /* 타임라인 추가 */ -}}
  {{- if .Params.timelines }}
    {{- partial "timeline.html" (dict "timelines" .Params.timelines) }}
  {{- end }}

  {{- /* 본문 */ -}}
  <div class="post-content">
    {{ .Content }}
  </div>
</article>

{{- end }}
```

#### 4단계: 다른 페이지에도 적용
**`content/projects/_index.md`**:
```yaml
---
title: "프로젝트"
layout: "project-list"
skills:
  - name: "Kubernetes & Container"
    percentage: 85
  - name: "AWS (EKS, VPC, RDS)"
    percentage: 80
---

프로젝트 소개입니다.
```

**`layouts/_default/project-list.html`**:
```html
{{- define "main" }}

<article class="post-single">
  <header class="post-header">
    <h1>{{ .Title }}</h1>
  </header>

  {{- /* 스킬바 추가 */ -}}
  {{- if .Params.skills }}
    {{- partial "skillbars.html" (dict "skills" .Params.skills) }}
  {{- end }}

  {{- /* 프로젝트 목록 */ -}}
  <div class="post-content">
    {{ .Content }}
  </div>

  {{- /* 프로젝트 카드들 */ -}}
  {{- range .Pages }}
    <article class="project-card">
      <h2>{{ .Title }}</h2>
      <p>{{ .Summary }}</p>
    </article>
  {{- end }}
</article>

{{- end }}
```

---

### 해결 방법 2: CSS 클래스만 적용 (간단)

**어떤 페이지든 Markdown에 HTML 추가**:

**`content/posts/my-project.md`**:
```markdown
---
title: "내 프로젝트"
---

## 프로젝트 개요

<div class="profile-section">
  <h3>📊 성과</h3>
  <div class="highlight-box">
    배포 시간 <span class='metric-badge' data-count='50' data-suffix='% 단축'>0%</span> 단축
  </div>
</div>

<div class="profile-section">
  <h3>기술 스택</h3>
  <div class="skill-bar">
    <div class="skill-bar-fill" data-percentage="90"></div>
  </div>
</div>
```

**장점**:
- ✅ 간단함 (Markdown에 HTML만 추가)
- ✅ 페이지별 커스터마이징 가능

**단점**:
- ⚠️ 중복 코드 발생
- ⚠️ 유지보수 어려움

---

### 해결 방법 3: Shortcode 사용 (중간)

**`layouts/shortcodes/timeline.html`**:
```html
<div class="profile-section">
    <h3>📅 {{ .Get "title" }}</h3>
    <div class="timeline">
        {{ .Inner }}
    </div>
</div>
```

**`layouts/shortcodes/timeline-item.html`**:
```html
<div class="timeline-item">
    <div class="timeline-date">{{ .Get "date" }}</div>
    <div class="timeline-content">
        <strong>{{ .Get "title" }}</strong><br>
        {{ .Inner }}
    </div>
</div>
```

**사용 예시**:
```markdown
---
title: "About"
---

{{< timeline title="프로젝트 타임라인" >}}
  {{< timeline-item date="2025.11 ~ 현재" title="AWS EKS" >}}
    3-Tier 아키텍처 구축 · 99.9% 가용성 달성
  {{< /timeline-item >}}

  {{< timeline-item date="2025.09 ~ 2025.10" title="CI/CD Pipeline" >}}
    Jenkins + ArgoCD 기반 GitOps
  {{< /timeline-item >}}
{{< /timeline >}}
```

**장점**:
- ✅ 재사용 가능
- ✅ Markdown에서 사용 간편

**단점**:
- ⚠️ Shortcode 문법 익혀야 함

---

## 📊 비교표: 다른 페이지 적용 방법

| 방법 | 재사용성 | 유지보수 | 복잡도 | 추천도 |
|------|---------|---------|--------|--------|
| **Partial** | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | ✅ 추천 |
| **Shortcode** | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐ | 👍 좋음 |
| **직접 HTML** | ⭐ | ⭐ | ⭐⭐⭐⭐⭐ | ⚠️ 비추천 |

---

## 🎯 추천 작업 순서

### 1단계: Partial 컴포넌트 생성
```bash
mkdir -p layouts/partials/components
```

**생성할 파일**:
- `layouts/partials/components/timeline.html`
- `layouts/partials/components/skillbars.html`
- `layouts/partials/components/metric-badges.html`

### 2단계: layouts/index.html 리팩터링
```html
{{- define "main" }}
<div class="profile">
    {{- with site.Params.profileMode }}
    <div class="profile_inner">
        {{- /* 제목 */ -}}
        <h1>{{ .title }}</h1>

        {{- /* Partial 사용 */ -}}
        {{- partial "components/timeline.html" . }}
        {{- partial "components/skillbars.html" . }}
    </div>
    {{- end }}
</div>
{{- end }}
```

### 3단계: 다른 페이지에 적용
```markdown
---
title: "About"
layout: "about"
---

내용...
```

```html
<!-- layouts/_default/about.html -->
{{- define "main" }}
  {{- partial "components/timeline.html" . }}
  {{ .Content }}
{{- end }}
```

---

## 📝 요약

### 핵심 파일
1. **`layouts/index.html`** - 메인 페이지 HTML (PaperMod 우회)
2. **`static/css/custom.css`** - 스타일 정의
3. **`static/js/animations.js`** - 애니메이션 로직
4. **`layouts/partials/extend_head.html`** - CSS/JS 로드 (루트 상대 경로 사용)

### 핵심 기술
- **Hugo 템플릿 우선순위**: `layouts/index.html`이 테마보다 우선
- **IntersectionObserver**: 스크롤 기반 애니메이션 트리거
- **CSS Variables**: 다크모드 지원 (`--primary`, `--entry` 등)
- **루트 상대 경로**: `/css/custom.css` (absURL 대신)

### 다음 단계
1. ✅ **현재**: 메인 페이지만 적용됨
2. ⏳ **다음**: Partial로 컴포넌트 분리
3. ⏳ **최종**: About, Projects 페이지에도 적용

---

**문서 작성일**: 2026-01-19
**작성자**: Claude (Sonnet 4.5) + Jimin
**프로젝트**: /home/jimin/blogsite
