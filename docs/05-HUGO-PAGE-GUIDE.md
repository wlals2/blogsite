# Hugo 정적 페이지 추가 가이드

> **목적**: 새로운 페이지(URL)를 블로그에 추가할 때 따라야 하는 절차 및 주의사항
> **최종 업데이트**: 2026-03-04

---

## 핵심 원칙

Hugo는 **정적 사이트 생성기**다. URL은 런타임에 동적으로 생성되지 않는다.
`content/` 파일 → 빌드 → `public/` HTML → Docker 이미지 → Pod 교체 순서를 모두 거쳐야 URL이 존재한다.

```
content/status.md  ≠  blog.jiminhome.shop/status/ (즉시 반영 아님)
                   ↓  Git push → 빌드(~5분) → 배포 완료 후
                   =  blog.jiminhome.shop/status/ (URL 생성)
```

---

## 페이지 유형별 구조

| 유형 | content 경로 | 생성되는 URL |
|------|-------------|-------------|
| 단일 고정 페이지 | `content/<slug>.md` | `/<slug>/` |
| 단일 고정 페이지 (이미지 포함) | `content/<slug>/index.md` | `/<slug>/` |
| 블로그 포스트 | `content/study/<date>-<slug>/index.md` | `/study/<date>-<slug>/` |
| 섹션 목록 | `content/<section>/_index.md` | `/<section>/` |

**고정 URL이 필요한 경우** (매일 덮어쓰는 `/status/` 같은 경우) frontmatter에 명시:
```yaml
---
url: "/status/"   # 파일명/경로와 무관하게 이 URL로 고정
layout: "single"
---
```

---

## 새 페이지 추가 절차

### Step 1: content 파일 생성

```bash
# 단순 고정 페이지
vim /home/jimin/blogsite/content/<slug>.md

# 이미지 포함 페이지 (디렉토리 구조 권장)
mkdir -p /home/jimin/blogsite/content/<slug>
vim /home/jimin/blogsite/content/<slug>/index.md
```

**frontmatter 최소 구성**:
```yaml
---
title: "페이지 제목"
date: 2026-03-04
layout: "single"     # 단일 페이지 레이아웃 사용 시
url: "/<slug>/"      # 고정 URL 원할 때만 명시
---
```

### Step 2: 메뉴 등록 (선택)

상단 네비게이션에 표시하려면 `config.toml`에 추가:
```toml
[[menu.main]]
  identifier = "<slug>"
  name = "메뉴명"
  url = "/<slug>/"
  weight = 5   # 숫자가 클수록 오른쪽
```

### Step 3: Git push → 빌드 대기

```bash
cd /home/jimin/blogsite
git add content/<slug>.md  # 또는 content/<slug>/
git commit -m "feat: add <slug> page"
git push origin main
# → GitHub Actions 자동 트리거 (빌드 5~10분 소요)
```

### Step 4: 배포 완료 확인

```bash
# GitHub Actions 빌드 상태
GH_PROMPT_DISABLED=1 gh run list --repo wlals2/blogsite --limit 3 \
  --json name,status,conclusion,createdAt

# WEB Pod에 파일 존재 확인
WEB_POD=$(kubectl get pods -n blog-system -l app=web \
  --field-selector=status.phase=Running \
  -o jsonpath='{.items[0].metadata.name}')
kubectl exec -n blog-system $WEB_POD -- \
  ls /usr/share/nginx/html/<slug>/
# → index.html 이 출력되면 배포 완료
```

---

## Kubernetes/Istio 관련 — 추가 작업 불필요

Hugo 정적 페이지는 WEB Pod(NGINX)가 서빙하므로, 아래는 모두 이미 처리되어 있다:

| 항목 | 이유 |
|------|------|
| **VirtualService** | `blog-routes`의 `prefix: /`가 모든 경로 포함 |
| **AuthorizationPolicy** | `web-authz`가 port 80 전체 허용 |
| **CiliumNetworkPolicy** | `web-isolation`에 포함됨 |

→ 새 Hugo 페이지 추가 시 **k8s-manifests 변경 불필요**

---

## WAS API URL과의 차이

| 구분 | Hugo 정적 페이지 | WAS API URL |
|------|-----------------|-------------|
| 서빙 주체 | NGINX (WEB Pod) | Spring Boot (WAS Pod) |
| 추가 시 k8s 변경 | ❌ 불필요 | ✅ was-authz.yaml, blog-routes 수정 필요 |
| 반영 시간 | 빌드 완료 후 (~5-10분) | ArgoCD Sync 후 (~1-2분) |
| URL 패턴 | 모든 경로 | `/api/*`, `/auth/*`, `/board` 등 |

---

## 트러블슈팅

### 빌드는 성공했는데 URL이 없을 때

```bash
# Pod 내부 직접 확인
kubectl exec -n blog-system $WEB_POD -- \
  find /usr/share/nginx/html -maxdepth 1 -type d
# → /status, /about, /study 등 목록 확인
```

없으면 Argo Rollouts가 아직 새 이미지로 교체 중:
```bash
kubectl get rollout web -n blog-system
# DESIRED, CURRENT, UP-TO-DATE 모두 같아지면 완료
```

### 브라우저에서만 안 보일 때 (Pod에는 있음)

```
원인: 브라우저 캐시 또는 Cloudflare 캐시
해결: Ctrl+Shift+R (강제 새로고침) 또는 시크릿 창
```

### daily-report.sh가 status.md를 덮어쓸 때 재빌드 트리거 방법

`publish_blog()` 함수가 git commit & push → GitHub Actions 자동 트리거됨.
별도 수동 조작 불필요.
