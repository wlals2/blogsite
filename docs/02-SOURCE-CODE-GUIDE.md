# 블로그 소스코드 구조 및 커스터마이징 가이드

> **작성일**: 2026-01-19
> **최종 수정**: 2026-01-23
> **목적**: Hugo 블로그의 전체 소스코드 구조, 커스터마이징 내역, 수정 방법 문서화

---

## 📁 전체 디렉토리 구조

```
/home/jimin/blogsite/
├── config.toml                    # Hugo 사이트 설정 (메뉴, 프로필, 테마)
├── CLAUDE.md                      # 프로젝트 규칙 (Claude 작업 가이드)
│
├── content/                       # 콘텐츠 (Markdown 파일)
│   ├── about.md                   # About 페이지
│   ├── architecture.md            # 아키텍처 설명 페이지
│   ├── status.md                  # 인프라 일일 상태 보고서 (daily-report.sh가 매일 덮어씀)
│   ├── projects/                  # 프로젝트 글
│   │   ├── _index.md              # 프로젝트 메인 페이지
│   │   ├── phase1-ec2/            # Phase 1: Terraform 3-Tier
│   │   ├── phase2-k8s/            # Phase 2: K8s on EC2
│   │   ├── phase3-eks-dr/         # Phase 3: EKS + Multi-Cloud DR
│   │   └── local-k8s-blog/        # 홈서버 K8s 프로젝트
│   ├── study/                     # 학습 노트
│   ├── docs/                      # 문서
│   └── til/                       # Today I Learned
│
├── layouts/                       # 커스텀 레이아웃 (Hugo 템플릿)
│   ├── index.html                 # 메인 페이지 레이아웃 ⭐
│   ├── _default/
│   │   ├── about.html             # About 페이지 레이아웃 ⭐
│   │   └── list.html              # 목록 페이지 레이아웃
│   └── partials/
│       ├── extend_head.html       # <head> 확장 (CSS/JS 로드) ⭐
│       ├── index_profile.html     # 메인 프로필 섹션
│       └── components/            # 재사용 컴포넌트 ⭐
│           ├── metrics.html       # 메트릭 배지 (67%, 99.9%)
│           ├── timeline.html      # 타임라인 (프로젝트 이력)
│           └── skillbars.html     # 스킬바 (진행률 표시)
│
├── static/                        # 정적 파일 (이미지, CSS, JS)
│   ├── css/
│   │   └── custom.css             # 커스텀 스타일 ⭐
│   ├── js/
│   │   └── animations.js          # 애니메이션 스크립트 ⭐
│   └── images/
│       ├── profile.png            # 프로필 이미지
│       └── architecture/          # 아키텍처 다이어그램 (신규) ⭐
│           ├── phase1-3tier-architecture.png
│           ├── phase2-k8s-architecture.png
│           └── phase3-multicloud-dr-architecture.png
│
├── docs/                          # 운영 문서
│   ├── SOURCE-CODE-GUIDE.md       # 이 파일
│   ├── 01-CICD-GUIDE.md           # CI/CD 가이드
│   ├── 02-INFRASTRUCTURE.md       # 인프라 가이드
│   └── blog-design/               # 디자인 관련 문서
│
├── .github/workflows/             # GitHub Actions 워크플로우
│   └── deploy-improved.yml        # 배포 자동화 + Cloudflare 캐시 삭제
│
└── themes/PaperMod/               # PaperMod 테마 (수정하지 않음)
```

---

## ⭐ 커스터마이징한 주요 파일

### 1. 레이아웃 파일 (layouts/)

#### 1.1. `/layouts/index.html` - 메인 페이지

**목적**: 블로그 첫 화면 커스터마이징

**주요 섹션**:
```html
<div class="profile">
  <h1>지민 (Jimin)</h1>
  <p>클라우드 엔지니어 지망생입니다.</p>

  <!-- 핵심 성과 메트릭 -->
  <div class="profile-section">
    <h3>📊 핵심 성과</h3>
    <div class="highlight-box">
      배포 시간 <span class='metric-badge' data-count='67' data-suffix='% 단축'>0% 단축</span>
      가용성 <span class='metric-badge' data-count='99.9' data-suffix='%'>0%</span> 달성
    </div>
  </div>

  <!-- 홈서버 인프라 (2026-01-19 추가) -->
  <div class="profile-section">
    <h3>🏠 홈서버 인프라</h3>
    <div class="highlight-box">
      이 블로그는 홈서버 Kubernetes Pod에서 운영 중입니다!
      <span class='metric-badge' data-count='58' data-suffix='일'>0일</span> 무중단 운영 중
      <a href="/architecture/">📐 전체 아키텍처 보기 →</a>
    </div>
  </div>

  <!-- 타임라인 -->
  <div class="profile-section">
    <h3>🚀 프로젝트 타임라인</h3>
    <div class="timeline">
      <div class="timeline-item">
        <div class="timeline-date">2025.11 ~ 현재</div>
        <div class="timeline-content">
          <h4>AWS EKS + Multi-Cloud DR</h4>
          <p>99.9% 가용성 달성 · Canary 배포 자동화</p>
        </div>
      </div>
    </div>
  </div>

  <!-- 스킬바 -->
  <div class="profile-section">
    <h3>💪 기술 스택</h3>
    <div class="skill-item">
      <div class="skill-info">
        <span class="skill-name">Kubernetes & Container</span>
        <span class="skill-percentage">85%</span>
      </div>
      <div class="skill-bar">
        <div class="skill-bar-fill" data-percentage="85"></div>
      </div>
    </div>
  </div>
</div>
```

**사용 컴포넌트**:
- `metrics.html`: 숫자 카운트업 애니메이션
- `timeline.html`: 프로젝트 이력 타임라인
- `skillbars.html`: 기술 스택 진행률 바

**연동 CSS**: `/static/css/custom.css`
**연동 JS**: `/static/js/animations.js`

---

#### 1.2. `/layouts/_default/about.html` - About 페이지

**목적**: About 페이지에 메트릭, 타임라인, 스킬바 컴포넌트 자동 렌더링

**구조**:
```html
{{- define "main" }}
<article class="post-single">
  <header class="post-header">
    <h1>{{ .Title }}</h1>
  </header>

  <!-- 메트릭 배지 -->
  {{- partial "components/metrics.html" . }}

  <!-- 타임라인 -->
  {{- partial "components/timeline.html" . }}

  <!-- 스킬바 -->
  {{- partial "components/skillbars.html" . }}

  <!-- 본문 -->
  <div class="post-content">
    {{ .Content }}
  </div>
</article>
{{- end }}
```

**Front Matter 데이터 사용**:
- `content/about.md`에 정의된 `metrics`, `timelines`, `skills` 데이터를 자동으로 렌더링

---

#### 1.3. `/layouts/partials/extend_head.html` - HEAD 확장

**목적**: 커스텀 CSS/JS 및 Mermaid 다이어그램 지원

**주요 내용**:
```html
<!-- Mermaid Diagram 지원 -->
<script src="https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.min.js"></script>
<script>
  document.addEventListener("DOMContentLoaded", function() {
    const isDark = document.documentElement.dataset.theme === "dark" ||
                   window.matchMedia("(prefers-color-scheme: dark)").matches;
    mermaid.initialize({
      startOnLoad: false,
      theme: isDark ? "dark" : "default",
      securityLevel: "loose",
      fontFamily: "inherit"
    });
    document.querySelectorAll(".mermaid").forEach((element, index) => {
      const id = 'mermaid-' + index;
      element.id = id;
      mermaid.run({ nodes: [element] });
    });
  });
</script>

<!-- Custom CSS -->
<link rel="stylesheet" href="/css/custom.css" />

<!-- Custom JavaScript -->
<script src="/js/animations.js" defer></script>
```

**주의사항**:
- CSS/JS 경로는 `/css/`, `/js/` (상대 경로 사용)
- `absURL` 대신 `relURL` 사용 (Hugo 서버와 프로덕션 환경 모두 대응)

---

### 2. 컴포넌트 (layouts/partials/components/)

#### 2.1. `metrics.html` - 메트릭 배지

**용도**: 숫자 카운트업 애니메이션이 있는 메트릭 표시

**사용법**:
```yaml
# content/about.md front matter
metrics:
  - value: 67
    suffix: "% 단축"
    label: "배포 시간"
    description: "(30분 → 10분)"
  - value: 99.9
    suffix: "%"
    label: "가용성"
    description: "달성"
```

**렌더링 결과**:
```html
<div class="profile-section">
  <h3>📊 핵심 성과</h3>
  <div class="highlight-box">
    배포 시간 <span class='metric-badge' data-count='67' data-suffix='% 단축'>0% 단축</span> (30분 → 10분) ·
    가용성 <span class='metric-badge' data-count='99.9' data-suffix='%'>0%</span> 달성
  </div>
</div>
```

**애니메이션**: `animations.js`에서 0부터 target 값까지 카운트업

---

#### 2.2. `timeline.html` - 타임라인

**용도**: 프로젝트 이력을 시간순으로 표시

**사용법**:
```yaml
# content/about.md front matter
timelines:
  - date: "2025.11 ~ 현재"
    title: "AWS EKS + Multi-Cloud DR"
    description: "3-Tier 아키텍처 구축 · 99.9% 가용성 달성"
    tech: "Kubernetes, EKS, Route53, Azure, Terraform"
```

**CSS 클래스**:
- `.timeline`: 타임라인 컨테이너
- `.timeline-item`: 각 이벤트 아이템
- `.timeline-date`: 날짜
- `.timeline-content`: 내용

---

#### 2.3. `skillbars.html` - 스킬바

**용도**: 기술 스택을 진행률 바로 표시

**사용법**:
```yaml
# content/about.md front matter
skills:
  - name: "Kubernetes & Container"
    percentage: 85
  - name: "AWS (EKS, VPC, RDS, Route53)"
    percentage: 80
```

**렌더링 결과**:
```html
<div class="skill-item">
  <div class="skill-info">
    <span class="skill-name">Kubernetes & Container</span>
    <span class="skill-percentage">85%</span>
  </div>
  <div class="skill-bar">
    <div class="skill-bar-fill" data-percentage="85"></div>
  </div>
</div>
```

**애니메이션**: `animations.js`에서 0%부터 target %까지 애니메이션

---

### 3. 스타일 (static/css/custom.css)

**주요 CSS 클래스**:

#### 3.1. 프로필 섹션
```css
.profile-section {
  background: var(--entry);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 1.5rem;
  margin: 1.5rem 0;
  transition: all 0.3s ease;
}

.profile-section:hover {
  transform: translateY(-4px);
  box-shadow: 0 8px 24px rgba(0,0,0,0.12);
}
```

#### 3.2. 메트릭 배지
```css
.metric-badge {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  padding: 0.3rem 0.8rem;
  border-radius: 20px;
  font-weight: 700;
  font-size: 1.1rem;
  display: inline-block;
  transition: all 0.3s ease;
}

.metric-badge:hover {
  transform: scale(1.05);
  box-shadow: 0 4px 12px rgba(102, 126, 234, 0.4);
}
```

#### 3.3. 타임라인
```css
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
  background: linear-gradient(to bottom, #667eea, #764ba2);
}

.timeline-item::before {
  content: '';
  position: absolute;
  left: -2rem;
  top: 0.5rem;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: #667eea;
  box-shadow: 0 0 0 4px var(--entry);
}
```

#### 3.4. 스킬바
```css
.skill-bar {
  height: 10px;
  background: var(--code-bg);
  border-radius: 10px;
  overflow: hidden;
}

.skill-bar-fill {
  height: 100%;
  background: linear-gradient(90deg, #667eea 0%, #764ba2 100%);
  border-radius: 10px;
  transition: width 2s ease-out;
  width: 0; /* 초기값 0, JS에서 애니메이션 */
}
```

---

### 4. JavaScript (static/js/animations.js)

**주요 기능**:

#### 4.1. 메트릭 배지 카운트업 애니메이션
```javascript
// 숫자 카운트업 애니메이션
document.querySelectorAll('.metric-badge').forEach(badge => {
  const target = parseFloat(badge.getAttribute('data-count'));
  const suffix = badge.getAttribute('data-suffix') || '';
  let current = 0;
  const increment = target / 60; // 60 프레임

  const timer = setInterval(() => {
    current += increment;
    if (current >= target) {
      current = target;
      clearInterval(timer);
    }
    badge.textContent = current.toFixed(1) + suffix;
  }, 30); // 30ms마다 업데이트
});
```

#### 4.2. 스킬바 애니메이션
```javascript
// 스킬바 진행률 애니메이션
const observer = new IntersectionObserver((entries) => {
  entries.forEach(entry => {
    if (entry.isIntersecting) {
      const fill = entry.target.querySelector('.skill-bar-fill');
      const percentage = fill.getAttribute('data-percentage');
      setTimeout(() => {
        fill.style.width = percentage + '%';
      }, 100);
      observer.unobserve(entry.target);
    }
  });
});

document.querySelectorAll('.skill-item').forEach(item => {
  observer.observe(item);
});
```

---

## 📝 콘텐츠 구조

### 프로젝트 페이지 (content/projects/)

#### Phase 1: Terraform 3-Tier (phase1-ec2/index.md)

**Front Matter**:
```yaml
---
title: "Phase 1: Terraform IaC로 AWS 3-Tier 인프라 구축"
date: 2025-10-09
summary: "수동 구축 4시간 → Terraform 15분으로 단축"
tags: ["project", "phase1", "terraform", "aws", "iac"]
weight: 1
showtoc: true
tocopen: true
---
```

**아키텍처 이미지 삽입** (2026-01-19 추가):
```markdown
### 3-Tier 아키텍처

![Phase 1 - 3-Tier Architecture](/images/architecture/phase1-3tier-architecture.png)

**아키텍처 구성 요소:**

#### Public Tier (Availability Zone: 2a, 2c, 2d)
- **Internet Gateway**: 외부 인터넷과 VPC 연결
- **Public Subnet**:
  - **ALB (Application Load Balancer)**: HTTPS 트래픽 분산
  - **Bastion Host**: Private 자원 접근용 점프 서버
- **NAT Gateway**: Private Subnet에서 인터넷 아웃바운드 통신

#### Private Tier - Web Layer
- **Private Subnet (web-a, web-c)**: nginx 웹 서버
- **Auto Scaling Group**: 트래픽에 따라 WEB 인스턴스 자동 증감
- **Security Group**: ALB에서만 8080 포트 허용

#### Private Tier - WAS Layer
- **Private Subnet (was-a, was-c)**: Tomcat 애플리케이션 서버
- **Internal ALB**: WEB과 WAS 사이 L7 라우팅
- **Auto Scaling Group**: CPU 70% 초과 시 스케일 아웃
- **Security Group**: WEB 계층에서만 8080 포트 허용

#### Private Tier - DB Layer
- **Private Subnet (db-a, db-c)**: MySQL RDS
- **Multi-AZ**: Primary (2a) + Standby (2c) 자동 복제
- **Security Group**: WAS 계층에서만 3306 포트 허용
- **자동 백업**: 매일 새벽 3시 (7일 보관)
```

---

#### Phase 2: K8s on EC2 (phase2-k8s/index.md)

**아키텍처 이미지** (2026-01-19 추가):
```markdown
### 상세 아키텍처

![Phase 2 - Kubernetes on EC2 Architecture](/images/architecture/phase2-k8s-architecture.png)

**아키텍처 구성 요소:**

#### Networking & Ingress
- **Route53**: DNS 기반 Health Check 및 트래픽 라우팅
- **ALB (Application Load Balancer)**: HTTPS Listener → Kubernetes Ingress 연결
- **Nginx Ingress Controller**: L7 라우팅 (/, /board 경로 분기)

#### Kubernetes Cluster (Self-Managed on EC2)
**Availability Zone A:**
- **Jenkins (Public Subnet)**: CI/CD 파이프라인 실행
- **Master Node**: kubeadm으로 구축한 Control Plane
- **WEB Pod**: nginx 정적 파일 서빙
- **WAS Pod**: Spring Boot 애플리케이션
- **MySQL StatefulSet**: Primary 데이터베이스

**Availability Zone C:**
- **Worker Node**: kubeadm으로 조인한 Worker
- **ArgoCD**: GitOps 기반 배포 자동화
- **WEB/WAS Pod** (Replica)
- **MySQL StatefulSet**: Standby 데이터베이스

#### Monitoring & Observability
- **CloudWatch**: AWS 리소스 메트릭 수집
- **AWS WAF**: 웹 애플리케이션 방화벽
- **Secrets Manager**: DB 자격증명 관리
```

**철자 수정** (2026-01-19):
- `CloudWAF` → `AWS WAF`
- `Secret Manager` → `Secrets Manager`

---

#### Phase 3: EKS + Multi-Cloud DR (phase3-eks-dr/index.md)

**아키텍처 이미지** (2026-01-19 추가):
```markdown
### 상세 Multi-Cloud DR 아키텍처

![Phase 3 - Multi-Cloud DR Architecture](/images/architecture/phase3-multicloud-dr-architecture.png)

**아키텍처 구성 요소:**

#### Primary Environment (AWS Cloud)

**Networking Layer:**
- **Route53**: Health Check 기반 Failover 라우팅
  - Primary: AWS ALB (정상 시)
  - Failover: CloudFront (AWS 장애 시 → 점검 페이지)
  - Failover Secondary: Azure DR (장기 장애 시)
- **ALB**: TLS 종료, EKS Ingress 연결

**EKS Cluster (Availability Zone A, C):**
- **Jenkins**: CI/CD 파이프라인
- **Karpenter**: 노드 자동 스케일링
- **ArgoCD**: GitOps 배포 자동화
- **Argo Rollouts**: Canary 배포 (10% → 50% → 90% → 100%)
- **Redis**: Session Clustering
- **RDS MySQL**: Multi-AZ (Primary + Standby)

**Monitoring & Security:**
- **CloudWatch**: 메트릭 수집
- **AWS WAF**: 웹 방화벽
- **Secrets Manager**: 자격증명 관리

#### Disaster Recovery (Azure DR)

**DR Site (RTO: 30분):**
- **AppGW (Application Gateway)**: L7 로드밸런서
- **WEB VM (PetClinic)**: Tomcat
- **Azure MySQL**: Flexible Server
- **Blob Storage**: 정적 웹 백업

**DR Failover Flow:**
1. AWS 장애 감지 (Route53 Health Check 실패 3회)
2. CloudFront 점검 페이지 활성화 (1분 이내)
3. Azure VM 자동 시작 (Terraform Lambda 트리거)
4. MySQL Restore (최신 Blob Backup)
5. Route53 Secondary 전환 → Azure AppGW (2분 이내)
```

---

### Architecture 페이지 (content/architecture.md)

**목적**: 홈서버 K8s와 AWS EKS 두 개의 독립 프로젝트 비교 및 트레이드오프 분석

**주요 섹션**:
```markdown
## 🎯 프로젝트 구분

### Project 1: 홈서버 Kubernetes (이 블로그)
- 목적: 베어메탈 K8s 학습 + 블로그 운영
- 환경: 홈서버 (4노드 클러스터)
- 애플리케이션: Hugo 블로그 + Spring Boot 게시판
- 비용: 무료 (전기료만)
- 운영: 58일+
- 보안: Private GHCR + imagePullSecrets

### Project 2: AWS EKS + Azure DR (PetClinic)
- 목적: 프로덕션급 HA + Multi-Cloud DR 구축
- 환경: AWS EKS + Azure
- 애플리케이션: PetClinic (샘플 앱)
- 비용: $258/월
- 운영: 90일+

**핵심:** 이 두 프로젝트는 서로 연결되어 있지 않습니다.

## ⚖️ 트레이드오프 분석

### 1. 비용 vs 제어
홈서버 K8s: 비용 무료, 제어 100% → 학습에 최적
AWS EKS: 비용 $258/월, 제어 제한적 → 프로덕션에 최적

### 2. 학습 경험
홈서버에서만 배울 수 있는 것:
- kubeadm으로 클러스터 초기화
- CNI 플러그인 선택/설치 (Cilium)
- 스토리지 직접 구축 (Longhorn)

EKS에서만 배울 수 있는 것:
- 관리형 K8s 운영 경험
- AWS 서비스 통합
- Multi-Cloud 아키텍처
```

**프라이버시**: 모든 IP 주소 마스킹 (`192.168.x.x`)

---

### Status 페이지 (content/status.md)

**추가일**: 2026-03-04
**URL**: `blog.jiminhome.shop/status/`
**목적**: 홈랩 인프라 일일 상태를 블로그에서 실시간 확인

**특이사항**: 이 파일은 `scripts/daily-report.sh`가 **매일 07:00 cron으로 자동 덮어씀**.
직접 편집해도 다음날 아침 덮어써지므로, 내용 변경은 `daily-report.sh`를 수정해야 함.

**포함 내용**:
```
- 보안 이벤트 (Falco): CRITICAL/WARNING/ERROR 건수 + Rule별 상세
- 클러스터 상태: 노드 수, 비정상 Pod, 재시작 횟수
- SLO: 24h/30d Availability
- 이미지 보안 스캔 (Trivy): CVE 목록
- CI/CD 이력: 최근 3일 배포 성공/실패
- TODO: P0/P1 작업 목록
```

**자동화 흐름**:
```
cron 07:00 → daily-report.sh
  → Prometheus/Loki/GitHub API 데이터 수집
  → /home/jimin/reports/YYYY-MM-DD.md 생성
  → Discord 전송
  → content/status.md 덮어쓰기 (frontmatter 유지, body 교체)
  → git commit & push → Hugo 빌드 → blog.jiminhome.shop/status/ 반영
```

---

## 🔧 설정 파일 (config.toml)

### 메인 메뉴 구조

```toml
[[menu.main]]
  identifier = "about"
  name = "About"
  url = "/about/"
  weight = 1

[[menu.main]]
  identifier = "architecture"
  name = "Architecture"
  url = "/architecture/"
  weight = 2  # ← 2026-01-19 추가

[[menu.main]]
  identifier = "projects"
  name = "Projects"
  url = "/projects/"
  weight = 3

[[menu.main]]
  identifier = "study"
  name = "Study"
  url = "/study/"
  weight = 4

[[menu.main]]
  identifier = "docs"
  name = "Docs"
  url = "https://github.com/wlals2/blogsite/tree/main/docs"
  weight = 4

[[menu.main]]
  identifier = "status"
  name = "Status"
  url = "/status/"
  weight = 5  # ← 2026-03-04 추가 (daily-report.sh 연동 인프라 상태 페이지)

[[menu.main]]
  identifier = "tags"
  name = "Tags"
  url = "/tags/"
  weight = 6
```

### 프로필 설정 (HTML 지원)

```toml
[params.profileMode]
  enabled = true
  title = "지민 (Jimin)"
  subtitle = """
클라우드 엔지니어 지망생입니다.<br><br>
<div class='profile-section'>
  <h3>📊 핵심 성과</h3>
  <div class='highlight-box'>
    배포 시간 <span class='metric-badge' data-count='67' data-suffix='% 단축'>0%</span>
  </div>
</div>
"""
  imageUrl = "/images/profile.png"
  imageTitle = "Profile"
  imageWidth = 200
  imageHeight = 200
```

**주의**: `subtitle`에서 HTML 사용 가능 (CSS 클래스 적용)

---

## 🚀 배포 프로세스

### GitHub Actions (`.github/workflows/deploy-improved.yml`)

**트리거**: `main` 브랜치에 push

**주요 단계**:
```yaml
name: Deploy Blog with Cloudflare Cache Purge

on:
  push:
    branches: [main]

jobs:
  deploy:
    runs-on: self-hosted
    steps:
      - name: Checkout
        uses: actions/checkout@v4
        with:
          submodules: recursive

      - name: Build Hugo
        run: hugo --minify

      - name: Deploy to /var/www/blog
        run: sudo rsync -av --delete public/ /var/www/blog/

      - name: Purge Cloudflare Cache
        env:
          CLOUDFLARE_ZONE_ID: ${{ secrets.CLOUDFLARE_ZONE_ID }}
          CLOUDFLARE_API_TOKEN: ${{ secrets.CLOUDFLARE_API_TOKEN }}
        run: |
          curl -X POST "https://api.cloudflare.com/client/v4/zones/${CLOUDFLARE_ZONE_ID}/purge_cache" \
            -H "Authorization: Bearer ${CLOUDFLARE_API_TOKEN}" \
            -H "Content-Type: application/json" \
            --data '{"purge_everything":true}'

      - name: Verify Deployment
        run: |
          sleep 5
          curl -I https://blog.jiminhome.shop/ | grep "200 OK"
```

**중요**: Cloudflare 캐시 삭제를 통해 변경사항 즉시 반영

---

## 📊 최근 작업 히스토리 (2026-01-19)

### 1. 아키텍처 다이어그램 추가

**목적**: 포트폴리오 PDF의 아키텍처 이미지를 블로그 글에 삽입

**작업 내용**:
1. 이미지 저장 폴더 생성:
   ```bash
   mkdir -p /home/jimin/blogsite/static/images/architecture/
   ```

2. 3개 프로젝트 페이지에 이미지 추가:
   - `phase1-ec2/index.md`: 3-Tier 아키텍처 이미지
   - `phase2-k8s/index.md`: K8s on EC2 아키텍처 이미지
   - `phase3-eks-dr/index.md`: Multi-Cloud DR 아키텍처 이미지

3. 각 이미지마다 상세 설명 추가:
   - 네트워킹 레이어
   - 클러스터 구성 (AZ별)
   - 모니터링 & 보안
   - CI/CD 파이프라인
   - DR Failover Flow

### 2. 철자 오류 수정

**수정 내용**:
- `CloudWAF` → `AWS WAF` (정확한 서비스명)
- `Secret Manager` → `Secrets Manager` (복수형)
- EKS vs kubeadm 구분 명확화

### 3. Architecture 페이지 생성

**목적**: 홈서버 K8s와 AWS EKS를 명확히 구분하고 트레이드오프 분석

**파일**: `content/architecture.md`

**주요 내용**:
- 두 프로젝트가 **독립적**임을 명시
- 비교표: 비용, 목적, 환경, 애플리케이션
- 트레이드오프 분석
- 면접 차별화 전략

### 4. 메인 페이지 업데이트

**추가 섹션**:
```html
<div class="profile-section">
  <h3>🏠 홈서버 인프라</h3>
  <div class="highlight-box">
    <strong>이 블로그는 홈서버 Kubernetes Pod에서 운영 중입니다!</strong>
    베어메탈 K8s 클러스터 직접 구축 (4노드) · Hugo Blog Pod
    <span class='metric-badge' data-count='58' data-suffix='일'>0일</span> 무중단 운영 중
    <a href="/architecture/">📐 전체 아키텍처 보기 →</a>
  </div>
</div>
```

---

## 🔍 문제 해결 가이드

### 1. 이미지가 표시되지 않을 때

**증상**: `![이미지](/images/architecture/xxx.png)` 경로에 이미지가 없음

**원인**: 이미지 파일이 `static/images/architecture/` 폴더에 없음

**해결**:
```bash
# 이미지 파일 확인
ls -lh /home/jimin/blogsite/static/images/architecture/

# 없다면 이미지 복사
cp /path/to/image.png /home/jimin/blogsite/static/images/architecture/phase1-3tier-architecture.png
```

### 2. CSS/JS가 로드되지 않을 때

**증상**: 커스텀 스타일이나 애니메이션이 작동하지 않음

**원인**: `extend_head.html`에서 잘못된 경로 사용

**해결**:
```html
<!-- ❌ 잘못된 경로 -->
<link rel="stylesheet" href="{{ absURL "css/custom.css" }}" />

<!-- ✅ 올바른 경로 -->
<link rel="stylesheet" href="/css/custom.css" />
```

### 3. Hugo 서버 localhost 리다이렉트 문제

**증상**: `192.168.X.187:1313` 접속 시 `localhost:1313`으로 리다이렉트

**해결**:
```bash
# 명시적 baseURL 지정
hugo server -p 1313 --bind 0.0.0.0 --baseURL http://192.168.X.187:1313/ --disableFastRender
```

### 4. Cloudflare 캐시가 삭제되지 않을 때

**증상**: 배포 후에도 변경사항이 반영되지 않음

**원인**: GitHub Secrets 미설정 또는 잘못된 API 토큰

**해결**:
```bash
# GitHub Secrets 확인
# Settings → Secrets and variables → Actions
# CLOUDFLARE_ZONE_ID
# CLOUDFLARE_API_TOKEN

# 수동 캐시 삭제
curl -X POST "https://api.cloudflare.com/client/v4/zones/${CLOUDFLARE_ZONE_ID}/purge_cache" \
  -H "Authorization: Bearer ${CLOUDFLARE_API_TOKEN}" \
  -H "Content-Type: application/json" \
  --data '{"purge_everything":true}'
```

---

## 📚 참고 자료

### Hugo 공식 문서
- [Hugo Documentation](https://gohugo.io/documentation/)
- [PaperMod Theme](https://github.com/adityatelange/hugo-PaperMod)

### 커스터마이징 참고
- [Hugo Layouts](https://gohugo.io/templates/introduction/)
- [Hugo Partials](https://gohugo.io/templates/partials/)
- [Front Matter](https://gohugo.io/content-management/front-matter/)

### 배포 참고
- [GitHub Actions Self-Hosted Runner](https://docs.github.com/en/actions/hosting-your-own-runners)
- [Cloudflare API](https://developers.cloudflare.com/api/)

---

## ✅ 체크리스트: 새 기능 추가 시

### 1. 컴포넌트 추가
- [ ] `layouts/partials/components/` 에 HTML 파일 생성
- [ ] Front Matter에 데이터 구조 정의
- [ ] `custom.css`에 스타일 추가
- [ ] 필요 시 `animations.js`에 애니메이션 추가

### 2. 페이지 추가
- [ ] `content/` 에 Markdown 파일 생성
- [ ] Front Matter 작성 (title, date, summary, tags, weight)
- [ ] 필요 시 커스텀 레이아웃 생성 (`layouts/_default/`)
- [ ] `config.toml` 메뉴에 추가

### 3. 이미지 추가
- [ ] `static/images/` 에 이미지 저장
- [ ] Markdown에서 `/images/xxx.png` 경로로 참조
- [ ] 이미지 최적화 (크기, 포맷)

### 4. 배포 확인
- [ ] Hugo 서버에서 로컬 테스트 (`http://192.168.X.187:1313/`)
- [ ] Git commit & push
- [ ] GitHub Actions 워크플로우 성공 확인
- [ ] 프로덕션 사이트 확인 (`https://blog.jiminhome.shop/`)
- [ ] Cloudflare 캐시 삭제 확인 (`cf-cache-status: DYNAMIC`)

---

**작성일**: 2026-01-19
**최종 업데이트**: 2026-01-23
**작성자**: Claude (with Jimin)
**문서 버전**: 1.1.0 (운영 일수 및 보안 설정 반영)
