# Blog System

> Hugo 정적 블로그 + Spring Boot API + Kubernetes

---

## 🚀 빠른 시작

### 새로운 Claude Code 세션에서 시작할 때

**반드시 이 순서대로 읽기:**

1. **[WORKLOG.md](WORKLOG.md)** - 최근에 뭐했는지 파악
2. **[CHANGELOG.md](CHANGELOG.md)** - 공식 변경사항 확인
3. **[docs/WAS/ARCHITECTURE.md](docs/WAS/ARCHITECTURE.md)** - 현재 시스템 상태
4. **[docs/WAS/TODO.md](docs/WAS/TODO.md)** - 다음 할 일

### 인간이 확인할 때

```bash
# 지난주에 뭐했지?
cat WORKLOG.md | grep "2026-01-"

# Canary 배포 언제 했지?
grep -n "Canary" CHANGELOG.md

# 현재 WAS 상태는?
cat docs/WAS/ARCHITECTURE.md | head -50

# 문제 해결 방법은?
cat docs/WAS/TROUBLESHOOTING.md | grep -A 20 "외부 접근 불가"
```

---

## 📁 프로젝트 구조

```
blogsite/
├── WORKLOG.md              # 날짜별 작업 일지 (상세)
├── CHANGELOG.md            # 버전별 변경사항 (공식)
├── README.md               # 이 파일
│
├── docs/                   # 문서
│   ├── WAS/
│   │   ├── ARCHITECTURE.md     # 시스템 아키텍처, 현재 상태
│   │   ├── TODO.md             # 개선 계획 (P0/P1/P2)
│   │   └── TROUBLESHOOTING.md  # 문제 해결 가이드
│   └── CICD/
│       └── CANARY-COMPARISON.md
│
├── content/                # Hugo 콘텐츠 (Markdown)
├── static/                 # 정적 파일 (CSS, JS, Images)
│   └── board.html          # 게시판 UI
├── layouts/                # Hugo 템플릿
└── blog-k8s-project/       # WAS 소스코드
    └── was/
        ├── pom.xml
        ├── Dockerfile
        └── src/main/java/com/jimin/board/
```

---

## 🏗️ 시스템 아키텍처

```
Client (브라우저)
  ↓ HTTPS
Cloudflare CDN
  ↓ HTTPS
Ingress (NGINX)
  ↓ HTTP
web-service (nginx Pod)
  ├─> /        → Hugo 정적 파일 (블로그)
  └─> /api/    → WAS 프록시 (Spring Boot API) ← 현재 미구현!
        ↓
   was-service (Spring Boot)
        ↓
   mysql-service (MySQL 8.0.44)
```

**상세:** [docs/WAS/ARCHITECTURE.md](docs/WAS/ARCHITECTURE.md)

---

## 🔧 기술 스택

### Frontend
- **Hugo** 0.x (정적 사이트 생성기)
- **PaperMod** 테마
- **Vanilla JavaScript** (board.html)

### Backend (WAS)
- **Spring Boot** 3.5.0
- **Java** 17
- **MySQL** 8.0.44
- **Hibernate** (JPA)

### 인프라
- **Kubernetes** 1.28+
- **Istio** Service Mesh
- **Argo Rollouts** (Canary 배포)
- **ArgoCD** (GitOps)
- **GitHub Actions** (CI/CD)
- **Cloudflare** (CDN, SSL)

---

## 📊 현재 상태 (2026-01-21)

### ✅ 작동 중
- Hugo 블로그 (https://blog.jiminhome.shop)
- WAS Pod (2/2 Running, Canary 배포 완료)
- MySQL 데이터베이스 (posts 테이블, 1개 레코드)
- GitHub Actions CI/CD
- ArgoCD GitOps

### ⚠️ 알려진 문제
1. 🔴 **외부 API 접근 불가** (404)
   - URL: `https://blog.jiminhome.shop/api/posts`
   - 원인: nginx → WAS 프록시 설정 누락
   - 해결: [docs/WAS/TODO.md](docs/WAS/TODO.md#1-nginx-프록시-설정)

2. 🔴 **Istio mTLS 에러**
   - 에러: `TLS_error: WRONG_VERSION_NUMBER`
   - 원인: nginx → WAS Plain HTTP vs mTLS 불일치
   - 해결: [docs/WAS/TROUBLESHOOTING.md](docs/WAS/TROUBLESHOOTING.md#istio-mtls-에러)

3. 🟡 **board.html 미배포**
   - 파일 존재하나 외부 접근 불가
   - Hugo 빌드 확인 필요

**상세:** [docs/WAS/ARCHITECTURE.md#현재-상태](docs/WAS/ARCHITECTURE.md#현재-상태)

---

## 🎯 다음 할 일

### P0 - 즉시 (오늘)
- [ ] nginx 프록시 설정 (`/api/` → WAS)
- [ ] board.html 배포 확인

### P1 - 이번 주
- [ ] Pagination 구현
- [ ] 에러 응답 표준화 (@RestControllerAdvice)
- [ ] Swagger UI 추가

### P2 - 장기
- [ ] Spring Security + JWT
- [ ] Redis 캐싱
- [ ] Full-text Search

**상세:** [docs/WAS/TODO.md](docs/WAS/TODO.md)

---

## 🚀 최근 업데이트

### 2026-01-21
- ✅ WAS 문서 체계화 (6개 파일 → 3개로 통합)
- ✅ WORKLOG.md 추가 (날짜별 작업 일지)
- ✅ CHANGELOG.md 추가 (버전별 변경사항)

### 2026-01-20
- ✅ WAS Canary 배포 구현 (Argo Rollouts)
- ✅ Istio VirtualService + DestinationRule 통합
- ✅ ArgoCD ignoreDifferences 설정

### 2026-01-17
- ✅ 블로그 시스템 초기 구축
- ✅ WAS API 구현 (6개 엔드포인트)
- ✅ MySQL 데이터베이스 설정

**상세:** [CHANGELOG.md](CHANGELOG.md)

---

## 📖 문서 가이드

### Claude Code용 (새 세션 시작 시)
```
1. WORKLOG.md        # 최근 뭐했는지
2. CHANGELOG.md      # 공식 변경사항
3. docs/WAS/ARCHITECTURE.md  # 현재 상태
4. docs/WAS/TODO.md  # 다음 할 일
```

### 인간용
| 문서 | 언제 보는가 |
|------|-----------|
| **WORKLOG.md** | 지난주에 뭐했지? |
| **CHANGELOG.md** | v1.2.0에서 뭐 바뀌었지? |
| **docs/WAS/ARCHITECTURE.md** | WAS 구조가 어떻게 되지? |
| **docs/WAS/TODO.md** | 다음에 뭐 할까? (트레이드오프 비교) |
| **docs/WAS/TROUBLESHOOTING.md** | 404 에러 어떻게 고치지? |

---

## 🔗 링크

- **Production**: https://blog.jiminhome.shop
- **GitHub**: https://github.com/wlals2/blogsite
- **GitHub Actions**: https://github.com/wlals2/blogsite/actions

---

## 📝 작업 원칙

### 문서화 규칙
1. **모든 주요 변경사항 → CHANGELOG.md**
2. **날짜별 작업 내용 → WORKLOG.md**
3. **커밋 메시지는 Conventional Commits**
   ```
   feat: 새 기능
   fix: 버그 수정
   docs: 문서만 변경
   chore: 빌드, 설정 변경
   ```

### Git 워크플로우
1. 로컬에서 작업
2. `git add` → `git commit` → `git push`
3. GitHub Actions 자동 빌드
4. ArgoCD 자동 배포 (3분 이내)

---

**마지막 업데이트**: 2026-01-21
**버전**: 1.2.0
