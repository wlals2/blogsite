# 블로그 콘텐츠 정책 v1.0

**작성일**: 2026-03-04
**목적**: Study 섹션 정렬, 카테고리, 시리즈, 레이아웃의 일관성 확보

---

## 1. 카테고리 정책

### 1.1 허용 카테고리 (10개 고정)

| 카테고리 | 표준 표기 | 대상 글 |
|----------|----------|---------|
| Kubernetes | `Kubernetes` | K8s 운영, Pod/Deployment, 클러스터 관리 |
| Service Mesh | `Service Mesh` | Istio, Linkerd, mTLS, VirtualService |
| Networking | `Networking` | CNI, Cilium, DNS, MetalLB, Hubble |
| Security | `Security` | Falco, Wazuh, Kyverno, CIS Benchmark, 취약점 |
| Storage | `Storage` | Longhorn, PVC, 볼륨 관리 |
| Observability | `Observability` | Prometheus, Grafana, Loki, 모니터링 |
| Cloud & Terraform | `Cloud & Terraform` | AWS, EKS, Terraform, NCP |
| Elasticsearch | `Elasticsearch` | ES 구축, 검색, 인덱스 설계 |
| Troubleshooting | `Troubleshooting` | 문제 해결 사례, 트러블슈팅 |
| Development | `Development` | 코드, Hugo, CI/CD 개발 관련 |

### 1.2 카테고리 규칙

- **`study`는 카테고리가 아님** → frontmatter에 절대 포함 금지
  - `study`는 Hugo Section 이름 (URL 경로)이며 콘텐츠 분류 카테고리가 아님
  - 잘못된 예: `categories: ["study", "Security"]`
  - 올바른 예: `categories: ["Security"]`

- **복수 카테고리 허용** (최대 2개 권장)
  - 주제가 2개 이상에 걸치면 복수 선택 가능
  - 예: `categories: ["Security", "Kubernetes"]`

- **모든 포스트는 categories 필드 필수**
  - 누락 시 All 탭에만 표시되고 카테고리 필터가 작동하지 않음

- **대소문자, 공백, & 기호 정확히 일치 필수**
  - 잘못된 예: `kubernetes`, `service mesh`, `Cloud&Terraform`, `트러블슈팅`
  - 올바른 예: `Kubernetes`, `Service Mesh`, `Cloud & Terraform`, `Troubleshooting`

---

## 2. 시리즈 정책

### 2.1 표준 시리즈 목록

| 시리즈명 (표준) | 분류 |
|----------------|------|
| `Kubernetes 기초 시리즈` | Kubernetes |
| `Cilium 시리즈` | Networking |
| `Istio 실전 시리즈` | Service Mesh |
| `ArgoCD/GitOps 시리즈` | Kubernetes |
| `Falco/Wazuh 시리즈` | Security |
| `DevSecOps 시리즈` | Security |
| `Kyverno 실전 시리즈` | Security |
| `공격자의 시선으로 보는 보안` | Security |
| `홈랩 보안 시리즈` | Security |
| `Prometheus/Observability 시리즈` | Observability |
| `Longhorn/Storage 시리즈` | Storage |
| `EKS 시리즈` | Cloud & Terraform |
| `Hugo 시리즈` | Development |
| `좀 더 고급으로 변하는 인프라 구조` | Kubernetes |
| `홈랩 Kubernetes 운영 시리즈` | Kubernetes |

### 2.2 시리즈 규칙

- **포맷 통일**: `series: ["시리즈명"]` (배열 형태, 따옴표 포함)
- **1개 글 = 1개 시리즈** (복수 시리즈 금지)
- **시리즈 없는 글**: frontmatter에서 `series` 필드 자체를 생략 (빈 배열 금지)
  - 잘못된 예: `series: []` 또는 `series: ["Other"]`
  - 올바른 예: 필드 없음
- **`Other` 시리즈 금지**: 시리즈에 속하지 않으면 필드 삭제

---

## 3. 레이아웃 정책

### 3.1 Study 목록 페이지 (`/study/`)

**3단 레이아웃 → 2단 레이아웃으로 변경**:
- 왼쪽 사이드바: 카테고리 트리 (유지)
- 중앙: 글 카드 그리드 (유지)
- ~~오른쪽 사이드바: 시리즈 목록~~ → **제거**

**이유**:
- 시리즈는 각 글 내부에서 확인 가능 (글 내부 시리즈 목차 유지)
- 오른쪽 사이드바가 좁은 화면에서 카드 그리드를 압박
- 카테고리 + 글 카드로 충분한 탐색 경험

### 3.2 정렬

- 날짜 역순 고정 (최신 글이 최상단)
- 필터 변경 시에도 날짜 역순 유지

---

## 4. 썸네일 (cover.jpg) 정책

- **모든 포스트 cover.jpg 필수**
- 생성 명령: `python3 scripts/generate-thumbnails.py --slug <디렉토리명>`
- 카테고리에 맞는 아이콘 자동 선택 (scripts/ 내 매핑 참조)

---

## 5. 정리 대상 (현황 기준 2026-03-04)

### 5.1 categories 누락 포스트 (5개)

| 파일 | 시리즈 | 부여할 카테고리 |
|------|--------|--------------|
| `2026-03-03-why-istio-over-linkerd` | Istio 실전 시리즈 | `Service Mesh` |
| `2026-03-03-falco-runtime-security-concept` | Falco/Wazuh 시리즈 | `Security` |
| `2026-03-03-why-cilium-over-calico-flannel` | Cilium 시리즈 | `Networking` |
| `2026-03-03-cilium-ebpf-concept` | Cilium 시리즈 | `Networking` |
| `2026-03-03-gitops-argocd-concept` | ArgoCD/GitOps 시리즈 | `Kubernetes` |

### 5.2 잘못된 categories 포스트

| 문제 | 처리 방법 |
|------|----------|
| `categories: ["study", "Kubernetes"]` | `study` 제거 → `categories: ["Kubernetes"]` |
| `categories: ["study", "트러블슈팅"]` | `study` 제거, `트러블슈팅` → `Troubleshooting` |
| `categories: ["study"]` | `study` 제거 → 적절한 카테고리로 대체 |
| `categories: ["Tutorial"]` | 표준 카테고리로 대체 |

### 5.3 시리즈 정리 대상

| 문제 | 처리 방법 |
|------|----------|
| `series: ["Other"]` | `series` 필드 삭제 |
| `series: ["트러블슈팅"]` | 해당 글에 맞는 표준 시리즈로 교체 또는 삭제 |

### 5.4 레이아웃 변경

- `layouts/study/list.html` — 오른쪽 사이드바(`study-sidebar-right`) 블록 제거

---

## 6. 검토 체크리스트 (포스트 작성 시)

```yaml
# 올바른 frontmatter 예시
---
title: "..."
date: YYYY-MM-DDTHH:MM:SS+09:00
categories:
  - Security          # 10개 표준 중 1-2개
series: ["Falco/Wazuh 시리즈"]   # 없으면 필드 생략
tags: [...]
showtoc: true
tocopen: true
draft: false
---
```

체크리스트:
- [ ] `study`가 categories에 없는가?
- [ ] 카테고리가 표준 10개 중 하나인가? (대소문자 확인)
- [ ] 시리즈가 표준 목록에 있는가?
- [ ] cover.jpg 생성했는가?
