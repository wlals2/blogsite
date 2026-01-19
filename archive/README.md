# Archive 디렉터리

> 현재 사용하지 않지만 참고용으로 보관하는 파일들

---

## 📦 보관된 아카이브

### 1. jenkins-legacy-20260119/
**보관 이유:** GitHub Actions로 CI/CD 마이그레이션 완료

**포함 파일:**
- `Jenkinsfile` (루트)
- `jenkins/` (blog-k8s-project)
- `Jenkinsfile-was-project`

**대체:** `.github/workflows/deploy-web.yml`, `.github/workflows/deploy-was.yml`

---

### 2. blog-k8s-legacy-20260119/
**보관 이유:** 디렉터리 구조 단순화, GitHub Actions 전환

**포함 디렉터리:**
- `web/` - 이전 web Dockerfile 및 스크립트
- `ingress/` - 초기 Ingress 매니페스트
- `mysql/` - 초기 MySQL 매니페스트

**현재 사용:**
- `blog-k8s-project/was/` (WAS 소스 및 Dockerfile만 유지)
- 루트 `Dockerfile` (WEB 빌드용)

---

## 🔄 복원 방법

필요 시 각 디렉터리의 README.md를 참조하여 복원할 수 있습니다.

```bash
# Jenkins 재활성화
cp archive/jenkins-legacy-20260119/Jenkinsfile .

# 이전 k8s 매니페스트 사용
kubectl apply -f archive/blog-k8s-legacy-20260119/ingress/
```

---

## 📅 보관 이력

| 날짜 | 아카이브 | 이유 |
|------|----------|------|
| 2026-01-19 | jenkins-legacy | GitHub Actions 전환 |
| 2026-01-19 | blog-k8s-legacy | 디렉터리 구조 단순화 |

