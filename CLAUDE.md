# Blogsite 프로젝트 규칙

> Hugo 정적 블로그 (PaperMod 테마)

---

## 1. 작업 완료 후 요약 (필수!)

### 작업 완료 시점 판단

다음 상황에서 "작업 완료"로 간주:
- ✅ Git 커밋 완료 (`git commit`, `git push`)
- ✅ 파일 생성/수정 완료 (Write, Edit 도구 사용 후)
- ✅ 배포 트리거 완료 (GitHub Actions 시작됨)
- ✅ 조사/분석 완료 (결과 정리 후)

**애매한 경우**: 사용자에게 확인
```
작업이 완료된 것 같습니다. 지금까지 정리가 필요하신가요?
```

### 요약 형식 (간결하게!)

```
## ✅ 완료

### 요청 작업
- [사용자가 요청한 작업] ✅

### 발견 & 해결 (있다면)
- **[문제 제목]**
  - Before: [문제 상황]
  - After: [해결 결과]
  - 파일: [수정된 파일]

### 확인
```bash
# 확인 명령어
# 예상 결과: ...
```
```

### 예시

```
## ✅ 완료

### 요청 작업
- 홈페이지 CSS 박스 추가 ✅

### 발견 & 해결
- **Cloudflare 캐시 문제 발견**
  - Before: 두 워크플로우 실행 → 캐시 안 지워짐
  - After: deploy.yml 삭제 → 캐시 삭제 보장
  - 파일: `.github/workflows/deploy.yml` 삭제

### 확인
```bash
curl -I https://blog.jiminhome.shop/ | grep "cf-cache-status"
# 예상: DYNAMIC (캐시 없음)
```
```

---

## 2. 소통 및 설명 정책 (필수!)

### 파트너십 원칙

**사용자는 파트너입니다. 일방적으로 따라오는 사람이 아닙니다.**

- 모든 작업은 **함께 이해하고** 진행합니다.
- "왜?"와 "어떻게?"를 항상 설명합니다.
- 사용자가 이해하지 못한 채 작업이 진행되면 안 됩니다.

### 정기적 상황 확인 (필수!)

다음 시점에 **반드시** 물어보기:

```
현재까지 진행 상황을 정리해서 알려드릴까요?
```

**물어봐야 하는 시점:**

1. ✅ **3개 이상의 파일**을 수정한 후
2. ✅ **문제 해결 시도가 2회 이상 실패**한 후
3. ✅ **새로운 기술/개념**이 등장했을 때
4. ✅ **사용자가 혼란스러워 보일 때** ("왜?", "모르겠어" 등의 질문)
5. ✅ **작업 방향이 바뀌었을 때** (새로운 해결 방안 시도)

### 상황 정리 형식

```
## 📋 현재 상황 정리

### 요청하신 작업
- [사용자가 원한 것]

### 지금까지 한 작업
1. **[파일명]** - [무엇을 했는지]
   - 왜: [목적]
   - 결과: ✅ 성공 / ❌ 실패 / ⏳ 진행중

2. **[파일명]** - [무엇을 했는지]
   - 왜: [목적]
   - 결과: [상태]

### 현재 문제 (있다면)
- **문제**: [무엇이 안 되는가]
- **원인**: [왜 안 되는가 - 기술적 설명]
- **해결 방안**:
  1. [방법 1] - [장점] / [단점] (예상 시간)
  2. [방법 2] - [장점] / [단점] (예상 시간)
  3. [방법 3] - [장점] / [단점] (예상 시간)

### 다음 단계
어떤 방법을 선택하시겠습니까?
```

### 설명 깊이

**단순 답변 금지. 반드시 "왜?"를 설명:**

```
1. 현상 - 무엇이 일어나는가
2. 원인 - 왜 발생하는가 (가장 중요!)
3. 해결 - 어떻게 고치는가
4. 트레이드오프 - 대안과 비교
```

**예시:**

❌ 나쁨:
```
config.toml을 수정했습니다.
```

✅ 좋음:
```
config.toml에 timeline HTML을 추가했습니다.

**왜?** 메인 페이지에 프로젝트 타임라인을 표시하기 위해
**문제**: PaperMod 테마가 subtitle의 HTML을 자동으로 제거함
**원인**: 테마가 subtitle을 <p> 태그로 감싸면서 내부 HTML 태그를 strip함
**해결 방안**:
1. 테마 구조 완전 변경 (30분) - 완전한 제어 가능, 복잡함
2. About 페이지로 이동 (10분) - 간단함, 메인 페이지엔 안 보임
3. 타임라인 포기 (5분) - 가장 빠름, 애니메이션만 유지
```

---

## 3. 배포 프로세스

### GitHub Actions
- **워크플로우**: `/home/jimin/blogsite/.github/workflows/deploy-improved.yml`
- **트리거**: main 브랜치 push
- **자동 실행**:
  1. Hugo 빌드 (`hugo --minify`)
  2. `/var/www/blog` 배포
  3. **Cloudflare 캐시 삭제** (purge_everything)
  4. 배포 검증

### 배포 확인 방법
```bash
# 1. 워크플로우 로그 확인
tail -f /home/jimin/actions-runner/_diag/Worker_*.log | grep -i "cloudflare"

# 2. 캐시 상태 확인
curl -I https://blog.jiminhome.shop/ | grep "cf-cache-status"

# 3. 실제 콘텐츠 확인
curl -s https://blog.jiminhome.shop/ | grep -o 'class="profile-section"'
```

---

## 4. 주요 파일 (절대 경로)

**프로젝트 루트**: `/home/jimin/blogsite/`

| 파일 | 역할 | 수정 시 주의사항 |
|------|------|-----------------|
| `/home/jimin/blogsite/config.toml` | 사이트 설정, 프로필 | HTML 사용 가능 |
| `/home/jimin/blogsite/static/css/custom.css` | 커스텀 스타일 | 다크모드 고려 |
| `/home/jimin/blogsite/layouts/partials/extend_head.html` | CSS/JS 로드 | Mermaid 지원 포함 |
| `/home/jimin/blogsite/content/projects/**/*.md` | 프로젝트 글 | front matter 필수 |
| `/home/jimin/blogsite/content/posts/**/*.md` | 블로그 포스트 | front matter 필수 |
| `/home/jimin/blogsite/docs/**/*.md` | 프로젝트 문서 | Hugo 빌드 외부 |
| `/home/jimin/blogsite/CLAUDE.md` | Claude 작업 규칙 | 이 파일 |

---

## 5. study 포스트 카테고리 관리 (필수!)

### 개요

**왜?** 카테고리가 매번 달라지면 블로그 탐색이 어려워지고, 일관성이 깨집니다.

**해결책**: 10개 고정 카테고리만 사용하고, Python 스크립트로 자동 제안받습니다.

### 10개 고정 카테고리 (절대 변경 금지!)

**정의 파일**: [`.blog-categories.yaml`](.blog-categories.yaml)

| 카테고리 | 설명 | 주요 키워드 |
|----------|------|-------------|
| **Kubernetes** | 클러스터, GitOps, Helm, 배포 전략 | kubernetes, k8s, helm, argocd, deployment |
| **Service Mesh** | Istio, mTLS, Traffic Management | istio, mtls, gateway, kiali, jaeger |
| **Networking** | Cilium, eBPF, CNI, 네트워크 정책 | cilium, ebpf, cni, hubble, ingress |
| **Security** | Falco, IDS/IPS, Zero Trust, 보안 | falco, security, zero-trust, authorization |
| **Storage** | Longhorn, MySQL, PVC, 백업, HA | longhorn, mysql, pvc, backup, ha |
| **Observability** | Prometheus, Grafana, Loki, 모니터링 | prometheus, grafana, loki, monitoring |
| **Cloud & Terraform** | AWS, Azure, EKS, Terraform, IaC, DR | aws, azure, terraform, eks, iac, dr |
| **Elasticsearch** | ELK, EFK, 검색 엔진, 로그 분석 | elasticsearch, elk, kibana, inverted-index |
| **Troubleshooting** | 문제 해결, 디버깅, 트러블슈팅 | troubleshooting, 트러블슈팅, fix, debug |
| **Development** | Spring Boot, Redis, Docker, CI/CD | spring-boot, redis, docker, cicd |

### 새 study 포스트 작성 시 절차 (필수!)

**Step 1: 카테고리 제안 받기**

```bash
# 작성 전에 반드시 실행
python3 scripts/suggest-category.py \
  "포스트 제목" \
  "tag1,tag2,tag3"

# 예시
python3 scripts/suggest-category.py \
  "Istio Gateway 설정 가이드" \
  "istio,gateway,kubernetes,service-mesh"
```

**출력 예시**:
```
🎯 추천 카테고리 (점수순):

✅ 1. Service Mesh         (점수: 9)
      Istio, mTLS, Traffic Management
      키워드: istio, service-mesh, mtls, gateway...

   2. Kubernetes           (점수: 2)
      클러스터, GitOps, Helm, 배포 전략
      키워드: kubernetes, k8s, helm, argocd...

📄 Front Matter 예시
---
title: "Istio Gateway 설정 가이드"
date: 2026-01-26
categories: ["study", "Service Mesh", "Kubernetes"]
tags: ["istio", "gateway", "kubernetes", "service-mesh"]
---
```

**Step 2: Front Matter 작성**

```markdown
---
title: "포스트 제목"
date: 2026-01-26
description: "한 줄 설명"
categories: ["study", "Service Mesh"]  # ✅ 제안받은 카테고리 사용
tags: ["istio", "gateway", "kubernetes"]
---
```

**Step 3: 포스트 작성 후 검증**

```bash
# 작성 후 확인
python3 scripts/update-categories.py  # 드라이런 (변경 없음)

# 예상 출력: 이미 올바른 카테고리가 설정되어 있음
```

### 카테고리 자동 분류 알고리즘

**점수 계산 방식**:
- 제목 키워드 매칭: **+2점**
- 태그 키워드 매칭: **+1점**
- "트러블슈팅" 제목 포함: **+10점** (특별 규칙)

**선택 기준**:
- 1위 카테고리: 무조건 선택
- 2위 카테고리: 1위의 50% 이상 점수면 추가 선택

### 금지 사항 (필수!)

- ❌ **임의로 새 카테고리 생성 절대 금지**
- ❌ suggest-category.py 없이 카테고리 작성 금지
- ❌ 10개 고정 카테고리 외 사용 금지
- ❌ 카테고리 오타 (예: "Kubenetes", "Service-Mesh")

**올바른 예시**:
```yaml
categories: ["study", "Kubernetes"]           # ✅ 정확한 대소문자
categories: ["study", "Service Mesh"]         # ✅ 공백 포함
categories: ["study", "Cloud & Terraform"]    # ✅ & 기호 포함
```

**잘못된 예시**:
```yaml
categories: ["study", "kubernetes"]           # ❌ 소문자
categories: ["study", "Service-Mesh"]         # ❌ 하이픈 사용
categories: ["study", "Container"]            # ❌ 존재하지 않는 카테고리
categories: ["study", "Kubernetes", "Istio"]  # ❌ 3개 이상 (최대 2개)
```

### 기존 포스트 카테고리 일괄 업데이트

```bash
# 드라이런 (변경 없음)
python3 scripts/update-categories.py

# 실제 적용
python3 scripts/update-categories.py --apply

# 결과 확인
git diff content/study/
```

### 참고 문서

**스크립트**:
- [`scripts/suggest-category.py`](scripts/suggest-category.py) - 신규 포스트 카테고리 제안
- [`scripts/update-categories.py`](scripts/update-categories.py) - 기존 포스트 일괄 업데이트
- [`scripts/README.md`](scripts/README.md) - 사용 가이드

**설정 파일**:
- [`.blog-categories.yaml`](.blog-categories.yaml) - 10개 고정 카테고리 정의

---

## 6. CSS 스타일 클래스

| 클래스 | 용도 |
|--------|------|
| `.profile-section` | 섹션 박스 (호버 효과) |
| `.highlight-box` | 메트릭 하이라이트 |
| `.tech-grid` | 3열 기술 스택 그리드 |
| `.project-card` | 프로젝트 카드 |
| `.interest-box` | 관심사 박스 |
| `.goal-box` | 목표 박스 (점선) |
| `.metric-badge` | 숫자 배지 (67%, 99.9%) |

---

## 7. 금지 사항

- ❌ `deploy.yml` 재생성 금지 (deploy-improved.yml만 사용)
- ❌ config.toml에 Markdown만 사용 (HTML + CSS 클래스 사용)
- ❌ custom.css 없이 인라인 스타일 사용
- ❌ 테스트 없이 바로 main 브랜치 push
- ❌ **study 포스트에 임의 카테고리 생성 금지** (10개 고정 카테고리만 사용)

---

## 8. 문제 해결

### Cloudflare 캐시 안 지워질 때
```bash
# GitHub Secrets 확인 필요
CLOUDFLARE_ZONE_ID
CLOUDFLARE_API_TOKEN
```

### CSS 스타일 안 보일 때
```bash
# 1. custom.css 로드 확인
curl -I https://blog.jiminhome.shop/css/custom.css

# 2. extend_head.html 확인
cat /home/jimin/blogsite/layouts/partials/extend_head.html | grep custom.css
```

### 배포 안 될 때
```bash
# 워크플로우 상태 확인
ls -lt /home/jimin/actions-runner/_diag/*.log | head -1
```

---

## 9. 문서화 정책 (필수!)

### ⛔ 금지 사항
- ❌ **작업 중 자동으로 MD 파일 생성 절대 금지**
- ❌ 가이드 문서를 먼저 작성하지 말 것
- ❌ 사용자가 요청하지 않았는데 문서화하지 말 것

### ✅ 올바른 순서
1. **작업 먼저 완료** (대시보드 생성, 설정 적용 등)
2. **작업 요약 제공** (텍스트로만)
3. **사용자에게 문서화 여부 확인**
4. 사용자가 "예"라고 답하면 → 그때 MD 파일 작성

### 작업 완료 시 반드시 물어보기

작업이 완료되면 다음 형식으로 요약하고 **반드시 질문**:

```
## ✅ 완료

### 요청 작업
- [사용자가 요청한 작업] ✅

### 주요 내용
- [핵심 내용 1-3줄 요약]

### 확인 방법
[확인 명령어 또는 URL]

---

**문서화가 필요하신가요?**
지금까지 작업한 내용을 MD 파일로 정리해드릴까요?
```

### 사용자 응답에 따른 행동

**"예" / "정리해줘" / "문서 만들어줘" 등**:
→ 이때 비로소 아래 문서 작성 규칙에 따라 MD 파일 생성

**"아니오" / "괜찮아" / 무응답**:
→ 작업 종료, MD 파일 생성하지 않음

### 언제 적용되는가?
**오직** 사용자가 명시적으로 요청할 때만:
- "md 파일로 정리해줘"
- "문서화해줘"
- "README 작성해줘"
- "가이드 만들어줘"
- "트러블슈팅 정리해줘"

---

## docs/ 디렉토리 문서화 규칙 (필수!)

### 문서 저장 위치

**우선순위**:
1. **`docs/` 디렉토리 사용** (기본)
   - 통합 문서 관리
   - 번호 지정으로 읽는 순서 명확
   - 기존 파일 확장 방식

2. **`content/posts/` 사용**
   - 블로그 포스트 (일반 독자용)
   - 가이드나 튜토리얼

3. **`.claude/` 사용 금지** (skills 외)
   - ❌ 일반 md 파일을 `.claude/`에 저장하지 말 것
   - ✅ `CLAUDE.md`와 `skills/` 디렉토리만 사용

### docs/ 파일 번호 규칙

**파일명 형식**: `NN-TOPIC-NAME.md`

**현재 구조 (절대 경로: `/home/jimin/blogsite/docs/`)**:
```
/home/jimin/blogsite/docs/
├── README.md                          # 📄 인덱스
├── 02-INFRASTRUCTURE.md               # 📄 인프라 (Kubernetes, Cloudflare, ArgoCD)
├── 03-TROUBLESHOOTING.md              # 📄 트러블슈팅
├── 04-SOURCE-CODE-GUIDE.md            # 📄 소스코드 가이드
├── CURRENT-STATE.md                   # 📄 k8s-manifests 프로젝트 현황
├── PAT-MANAGEMENT.md                  # 📄 Personal Access Token 관리
│
├── istio/                             # 📁 Istio Service Mesh (3 files)
│   ├── COMPLETE-ISTIO-ARCHITECTURE.md # ⭐ 완전한 아키텍처 (추천)
│   ├── NGINX-PROXY-ISTIO-MESH.md      # nginx proxy 통합
│   └── TODO.md                        # 향후 개선 과제
│
├── cilium/                            # 📁 Cilium eBPF (4 files)
│   ├── LOCAL-K8S-CILIUM-ARCHITECTURE.md
│   ├── CILIUM-ENTERPRISE-USE-CASES.md
│   ├── CILIUM-IMPROVEMENT-COMPLETE.md
│   └── MD-FILES-STATUS-REPORT.md
│
├── CICD/                              # 📁 CI/CD 파이프라인 (3 files)
│   ├── CICD-PIPELINE.md
│   ├── CICD-VERIFICATION.md
│   └── GITOPS-IMPLEMENTATION.md
│
├── monitoring/                        # 📁 모니터링 (4 files + archive/)
│   ├── README.md
│   ├── CURRENT-STATUS.md
│   ├── NEXT-STEPS.md
│   ├── TROUBLESHOOTING.md
│   └── archive/
│
├── guides/                            # 📁 가이드 (11 files)
│   ├── README-DEV.md
│   ├── DEV-GUIDE.md
│   ├── QUICK-START-PRIVATE.md
│   ├── PRIVATE-CONTENT-GUIDE.md
│   ├── SECURITY-FEATURES.md
│   ├── DDNS_SETUP_GUIDE.md
│   ├── TEKTON-GUIDE.md
│   ├── MERMAID-TROUBLESHOOTING.md
│   ├── WORKFLOW_EXPLAINED.md
│   ├── TROUBLESHOOTING_PROCESS.md
│   └── TROUBLESHOOTING_SUMMARY.md
│
├── blog-design/                       # 📁 블로그 디자인 (1 file)
│   └── MAIN-PAGE-CUSTOMIZATION.md
│
├── storage/                           # 📁 스토리지 (2 files)
│   ├── README.md
│   └── STORAGE-ANALYSIS.md
│
└── archive/                           # 📁 아카이브 (구버전 문서)
    ├── 01-CICD-GUIDE.md
    ├── README.md
    └── old-docs-20260119/
```

**번호 추가 규칙**:
- 01-09: 핵심 주제 (CI/CD, 인프라, 트러블슈팅)
- 10-19: 세부 주제 (필요 시 추가)
- README.md: 번호 없음 (인덱스 역할)
- 주제별 디렉터리: istio/, cilium/, CICD/, monitoring/, guides/

### 문서 업데이트 방식

**기본 원칙**: **기존 파일에 추가** (새 파일 생성 최소화)

**예시**:
```
ArgoCD 내용 추가 필요?
→ ✅ 01-CICD-GUIDE.md에 "ArgoCD (CD)" 섹션 추가
→ ✅ 02-INFRASTRUCTURE.md에 "GitOps (ArgoCD)" 섹션 추가
→ ❌ 새로운 05-ARGOCD-GUIDE.md 파일 생성 (X)
```

**새 파일 생성 조건**:
- 기존 파일과 주제가 완전히 다를 때
- 파일이 너무 길어질 때 (2000줄 이상)
- 독립적인 주제일 때

### 문서 작성 스타일

**필수 요소 (반드시 포함)**:
1. **프로젝트 개요** - 무엇을 만들었는가
2. **왜 이렇게 구축했는가** - 대안 분석 표 포함, 배경지식 설명
3. **트레이드오프** - 선택의 장단점 명시
4. **설정 방법** - 어떻게 구축했는지 상세 과정
5. **기술 스택 상세** - 각 기술의 버전, 리소스, 역할
6. **시스템 아키텍처** - 박스 다이어그램 또는 플로우차트
7. **실제 시나리오** - Before/After 비교
8. **추후 구축 계획** - 다음 단계, 개선 방향
9. **배경지식** - 기술의 작동 원리, 개념 설명

**작성 원칙**:
- ✅ **"왜?"를 항상 설명** - 단순 How-to가 아닌 Why 중심
- ✅ **배경지식 포함** - 기술 개념, 작동 원리 설명
- ✅ **트레이드오프 명시** - 선택하지 않은 대안과 비교
- ✅ **설정 상세** - 명령어, 설정 파일, 주석 포함
- ✅ **추후 계획** - 다음에 구축할 것, 개선 방향
- ✅ **실제 사례** - Before/After 비교, 수치화

### 문서 구조 템플릿

```markdown
# [프로젝트명] 완전 가이드

> [한 줄 설명]
>
> **프로젝트 목표**: [목표]

**최종 업데이트:** YYYY-MM-DD
**문서 버전:** X.X
**시스템 상태:** [✅ / ⏳ / ❌]

---

## 📋 목차

1. [프로젝트 개요](#프로젝트-개요)
2. [왜 이렇게 구축했는가](#왜-이렇게-구축했는가)
3. [기술 스택 상세](#기술-스택-상세)
4. [시스템 아키텍처](#시스템-아키텍처)
5. [구축 가이드](#구축-가이드)
6. [실제 시나리오](#실제-시나리오)
7. [트러블슈팅](#트러블슈팅)
8. [다음 단계](#다음-단계)

---

## 프로젝트 개요

### 무엇을 만들었는가?

[프로젝트 설명]

**주요 특징:**
- ✅ 특징 1
- ✅ 특징 2

### 시스템 규모

| 항목 | 수치 |
|------|------|
| **항목1** | 값1 |
| **항목2** | 값2 |

### 프로젝트 목적

**학습 목표:**
1. 목표 1
2. 목표 2

**비즈니스 목표:**
1. 목표 1
2. 목표 2

---

## 왜 이렇게 구축했는가?

### 1. 왜 [기술A]를 선택했는가?

**선택한 기술: [기술명]**

#### 대안 분석

| 기술 | 장점 | 단점 | 선택 이유 |
|------|------|------|----------|
| **[선택 기술]** | 장점1<br>장점2 | 단점1 | ✅ **선택** |
| [대안1] | 장점 | 단점 | ❌ 이유 |
| [대안2] | 장점 | 단점 | ❌ 이유 |

#### 선택 이유 (Why [기술]?)

1. **이유1**: 설명
   ```bash
   # 예제 코드
   ```

2. **이유2**: 설명

3. **이유3**: 설명

#### 트레이드오프

**단점:**
- ❌ 단점1
- ❌ 단점2

**하지만:**
- ✅ 장점1
- ✅ 장점2

---

## 기술 스택 상세

### [기술명] ([역할])

**버전**: vX.X.X
**리소스**: CPU XXXm, Memory XXXMi
**데이터 보관**: X일

#### 주요 기능

| 기능 | 설명 | 예제 |
|------|------|------|
| **기능1** | 설명 | 예제 |
| **기능2** | 설명 | 예제 |

#### 예제 코드

\`\`\`bash
# 명령어 예제
\`\`\`

---

## 시스템 아키텍처

### 전체 아키텍처

\`\`\`
┌─────────────────────────┐
│      Component 1        │
└───────┬─────────────────┘
        │
        ▼
┌───────┴─────────────────┐
│      Component 2        │
└─────────────────────────┘
\`\`\`

### 플로우

\`\`\`
1. 단계1
   ↓
2. 단계2
   ↓
3. 단계3
\`\`\`

---

## 실제 시나리오

### 시나리오 1: [문제 상황]

**상황**: [설명]

#### Before (기존 방식)
\`\`\`
1. 문제 발생 (0분)
2. 수동 확인 (5분)
3. 수동 대응 (10분)

총 소요 시간: 15분
사용자 영향: 매우 큼
\`\`\`

#### After (개선 후)
\`\`\`
1. 자동 탐지 (0초)
2. 자동 알람 (1초)
3. 자동 대응 (60초)

총 소요 시간: 1분
사용자 영향: 최소화
\`\`\`

**개선 효과**: 93% 단축

---

## 다음 단계

### ⏳ 30분 내 완료 가능

1. **작업1** (20분)
   \`\`\`bash
   # 명령어
   \`\`\`

2. **작업2** (10분)
   - 설명

### 🔜 선택 사항

3. **작업3** (1시간)
   - 설명

---

## 체크리스트

### ✅ 구축 완료
- [x] 항목1
- [x] 항목2

### ⏳ 진행 중
- [ ] 항목3
- [ ] 항목4

### 🔜 선택 사항
- [ ] 항목5
- [ ] 항목6

---

**작성일**: YYYY-MM-DD
**작성자**: [작성자]
**문서 버전**: X.X
**다음 단계**: [다음 작업]
\`\`\`

### 작성 규칙

1. **상세하게 작성** - "왜?"를 항상 설명
2. **대안 분석 필수** - 다른 선택지와 비교
3. **실제 예제 포함** - 코드, 명령어, 시나리오
4. **Before/After 비교** - 개선 효과 수치화
5. **시스템 규모 표** - 정량적 지표 제시
6. **체크리스트** - 완료/진행/예정 구분
7. **박스 다이어그램** - 아키텍처 시각화
8. **트레이드오프 명시** - 단점도 솔직하게

### 참고 문서

**절대 경로: `/home/jimin/blogsite/docs/`**

- [`/home/jimin/blogsite/docs/README.md`](/home/jimin/blogsite/docs/README.md) - 프로젝트 완전 가이드 예제
- [`/home/jimin/blogsite/docs/istio/COMPLETE-ISTIO-ARCHITECTURE.md`](/home/jimin/blogsite/docs/istio/COMPLETE-ISTIO-ARCHITECTURE.md) - Istio 아키텍처 가이드 (추천)
- [`/home/jimin/blogsite/docs/CICD/CICD-PIPELINE.md`](/home/jimin/blogsite/docs/CICD/CICD-PIPELINE.md) - CI/CD 파이프라인 가이드
- [`/home/jimin/blogsite/docs/monitoring/README.md`](/home/jimin/blogsite/docs/monitoring/README.md) - 모니터링 가이드 예제
