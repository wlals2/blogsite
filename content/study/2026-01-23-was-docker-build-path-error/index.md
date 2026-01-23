---
title: "WAS Docker 빌드 경로 오류 (Dockerfile not found)"
date: 2026-01-23
description: ".gitignore에 포함된 소스코드로 인한 Docker 빌드 실패 해결"
tags: ["docker", "github-actions", "gitignore", "troubleshooting"]
categories: ["study"]
---

## 상황

GitHub Actions에서 WAS Docker 이미지 빌드 실패.

```
ERROR: failed to solve: failed to read dockerfile:
open Dockerfile: no such file or directory
path "./blog-k8s-project/was" not found
```

---

## 원인

### .gitignore 확인

```bash
cat .gitignore | grep was
```

```
blog-k8s-project/was/
```

### 문제 분석

| 위치 | 경로 | WAS 소스 |
|------|------|----------|
| 로컬 개발 | ~/blogsite/blog-k8s-project/was/ | ✅ 존재 |
| Git 저장소 | (없음) | ❌ .gitignore |
| 워크플로우 워킹 디렉토리 | ~/actions-runner/_work/blogsite/blogsite/ | ❌ 없음 |

### 디렉터리 구조

```
~/blogsite/                          # 로컬 소스
├── blog-k8s-project/
│   └── was/                        ✅ 여기에 존재
│       ├── Dockerfile
│       └── src/

~/actions-runner/_work/.../          # GitHub Actions 워킹 디렉터리
├── blog-k8s-project/
│   └── was/                        ❌ Git에 없으므로 clone 안 됨
```

`actions/checkout@v4`는 Git 저장소만 클론하므로 `.gitignore`에 포함된 파일은 복사되지 않음.

---

## 해결

### 방법 1: 워크플로우에 복사 단계 추가 (임시)

```yaml
steps:
  - name: Checkout code
    uses: actions/checkout@v4

  - name: Copy WAS source code
    run: |
      cp -r ~/blogsite/blog-k8s-project/was ./blog-k8s-project/
      ls -la ./blog-k8s-project/was/

  - name: Build and push Docker image
    uses: docker/build-push-action@v5
    with:
      context: ./blog-k8s-project/was
      file: ./blog-k8s-project/was/Dockerfile
```

### 방법 2: Git에 소스 포함 (권장 - SSOT)

```bash
# .gitignore에서 제거
sed -i '/blog-k8s-project\/was/d' .gitignore

# Git에 추가
git add blog-k8s-project/was/
git commit -m "feat: Add WAS source to git (SSOT)"
git push
```

---

## 결과

| 항목 | Before | After |
|------|--------|-------|
| WAS 소스 위치 | 로컬만 | Git 포함 |
| Docker 빌드 | ❌ 실패 | ✅ 성공 |
| SSOT 준수 | ❌ | ✅ |

---

## 정리

### .gitignore와 CI/CD

| 상황 | .gitignore 포함 | 결과 |
|------|----------------|------|
| 로컬 개발 | OK | 문제 없음 |
| GitHub Actions | 문제 | 파일 없음 |
| 다른 개발자 | 문제 | 파일 없음 |

### SSOT (Single Source of Truth)

| 원칙 | 설명 |
|------|------|
| Git = 진실의 원천 | 모든 소스는 Git에 있어야 함 |
| 로컬 전용 파일 | 환경 설정(.env)만 .gitignore |
| 소스코드 | 반드시 Git에 포함 |

### 민감 정보 처리

민감 정보가 있다면:
1. `.env` 파일로 분리 (Git 제외)
2. GitHub Secrets 사용
3. 빌드 시 환경 변수 주입

```yaml
- name: Build with secrets
  env:
    DB_PASSWORD: ${{ secrets.DB_PASSWORD }}
  run: docker build --build-arg DB_PASSWORD=$DB_PASSWORD .
```

---

## 관련 명령어

```bash
# .gitignore 상태 확인
git status --ignored

# 특정 파일이 ignore 되는지 확인
git check-ignore -v blog-k8s-project/was/

# ignore된 파일 강제 추가
git add -f blog-k8s-project/was/
```
