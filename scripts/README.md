# 블로그 카테고리 관리 가이드

> **목적**: study 포스트 카테고리를 일관되게 관리하기 위한 자동화 도구

---

## 📁 파일 구조

```
blogsite/
├── .blog-categories.yaml        # 고정된 카테고리 정의 (10개)
└── scripts/
    ├── update-categories.py     # 기존 포스트 일괄 업데이트
    ├── suggest-category.py      # 새 포스트 카테고리 제안
    └── README.md                # 이 파일
```

---

## 🎯 고정 카테고리 (10개)

| 카테고리 | 개수 | 설명 |
|---------|------|------|
| **Kubernetes** | 37개 | 클러스터, GitOps, Helm, 배포 |
| **Cloud & Terraform** | 15개 | AWS, Azure, EKS, Terraform, DR |
| **Troubleshooting** | 20개 | 문제 해결, 디버깅 |
| **Storage** | 12개 | Longhorn, MySQL, PVC, 백업 |
| **Networking** | 11개 | Cilium, eBPF, Hubble, CNI |
| **Development** | 10개 | Spring Boot, Redis, Docker |
| **Observability** | 6개 | Prometheus, Grafana, Loki |
| **Elasticsearch** | 6개 | ELK, EFK, 검색 엔진 |
| **Service Mesh** | 5개 | Istio, mTLS, Traffic Management |
| **Security** | 4개 | Falco, IDS/IPS, Zero Trust |

---

## 🚀 사용법

### 1. 기존 포스트 일괄 업데이트

**드라이런 (변경 없이 미리보기)**:
```bash
python3 scripts/update-categories.py
```

**실제 적용**:
```bash
python3 scripts/update-categories.py --apply
```

**결과**:
- `categories: ["study"]` → `categories: ["study", "Kubernetes"]`
- 제목과 태그 기반 자동 분류
- 원본 파일은 `.bak`으로 백업

---

### 2. 새 포스트 카테고리 제안

**사용법**:
```bash
python3 scripts/suggest-category.py "포스트 제목" "tag1,tag2,tag3"
```

**예시 1: Service Mesh 포스트**
```bash
python3 scripts/suggest-category.py \
  "Istio Service Mesh 아키텍처 완전 가이드" \
  "kubernetes,istio,service-mesh,mtls"
```

**출력**:
```
🎯 추천 카테고리 (점수순):
✅ 1. Service Mesh (점수: 9)
   2. Kubernetes (점수: 2)

📄 Front Matter 예시:
---
title: "Istio Service Mesh 아키텍처 완전 가이드"
date: 2026-01-26
categories: ['study', 'Service Mesh']
tags: ["kubernetes", "istio", "service-mesh", "mtls"]
---
```

**예시 2: Troubleshooting 포스트**
```bash
python3 scripts/suggest-category.py \
  "Longhorn CSI Plugin CrashLoopBackOff 문제" \
  "kubernetes,longhorn,storage,troubleshooting"
```

**출력**:
```
🎯 추천 카테고리 (점수순):
✅ 1. Troubleshooting (점수: 18)
   2. Storage (점수: 10)
   3. Kubernetes (점수: 2)

📄 Front Matter 예시:
---
title: "Longhorn CSI Plugin CrashLoopBackOff 문제"
date: 2026-01-26
categories: ['study', 'Troubleshooting', 'Storage']
tags: ["kubernetes", "longhorn", "storage", "troubleshooting"]
---
```

---

## 📝 카테고리 선택 규칙

### 1. 자동 분류 로직

```python
# 제목 매칭: 가중치 3
if "istio" in title:
    score["Service Mesh"] += 3

# 태그 매칭: 가중치 2
if "istio" in tags:
    score["Service Mesh"] += 2

# 특별 규칙: Troubleshooting 우선
if "트러블슈팅" in title or "troubleshooting" in title:
    score["Troubleshooting"] += 10
```

### 2. 복수 카테고리

- **1위 카테고리**: 무조건 포함
- **2위 카테고리**: 1위의 50% 이상 점수면 포함

**예시**:
```yaml
# Istio + Kubernetes 모두 높은 점수
categories: ['study', 'Service Mesh', 'Kubernetes']

# Troubleshooting이 압도적
categories: ['study', 'Troubleshooting', 'Storage']
```

### 3. 카테고리 수정

카테고리가 잘못 분류되었다면:

1. **`.blog-categories.yaml` 수정**:
   ```yaml
   kubernetes:
     keywords:
       - kubernetes
       - k8s
       - helm
       - NEW_KEYWORD  # 추가
   ```

2. **재실행**:
   ```bash
   python3 scripts/update-categories.py --apply
   ```

---

## 🎨 UI 개선 (다음 단계)

### 카테고리 필터 추가

**목표**: study 페이지에서 카테고리별 필터링

```
┌─────────────────────────────────────┐
│  Study - 95개 포스트                │
├─────────────────────────────────────┤
│  📁 카테고리                         │
│  ☐ 전체 (95)                        │
│  ☑ Kubernetes (37)     ← 선택       │
│  ☐ Troubleshooting (20)             │
│  ☐ Cloud & Terraform (15)           │
│  ... (더 보기)                      │
└─────────────────────────────────────┘
```

**구현 방법**:
1. `layouts/study/list.html` 수정
2. JavaScript로 필터링 로직 추가
3. 카테고리별 카운트 표시

---

## ⚠️ 주의사항

### 1. 백업 확인

```bash
# 백업 파일 확인
ls content/study/*.bak

# 백업에서 복구
cp content/study/post.md.bak content/study/post.md
```

### 2. Git 커밋 전 확인

```bash
# 변경사항 확인
git diff content/study/

# 일부만 스테이징
git add content/study/2026-*.md
```

### 3. 카테고리 일관성 유지

- ✅ `.blog-categories.yaml`에 정의된 10개만 사용
- ❌ 임의의 카테고리 추가 금지
- ✅ 새 카테고리 필요 시 `.blog-categories.yaml` 먼저 수정

---

## 📊 실행 결과 예시

```
🔍 드라이런 모드 (파일 변경 없음)

✅ istio-service-mesh-architecture/index.md
   → Service Mesh, Kubernetes

✅ longhorn-csi-crashloopbackoff/index.md
   → Troubleshooting, Storage

✅ cilium-ebpf-networking/index.md
   → Networking

================================================================================
📊 카테고리별 통계
================================================================================
  Kubernetes            37개
  Troubleshooting       20개
  Cloud & Terraform     15개
  Storage               12개
  Networking            11개
  Development           10개
  Observability          6개
  Elasticsearch          6개
  Service Mesh           5개
  Security               4개

총 95개 포스트 처리
```

---

**작성일**: 2026-01-26
**최종 업데이트**: 2026-01-26
