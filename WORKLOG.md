# Work Log

> 날짜별 작업 일지 - Claude Code와 인간 모두를 위한 상세 기록

---

## 사용 방법

### Claude Code가 새 세션에서 확인할 것
```
1. 이 파일부터 읽기 (최근 작업 파악)
2. CHANGELOG.md 읽기 (공식 변경사항)
3. docs/WAS/ARCHITECTURE.md 읽기 (현재 상태)
```

### 인간이 확인할 것
- "지난주에 뭐했지?" → 이 파일 검색
- "Canary 배포 언제 했지?" → Ctrl+F "Canary"

---

## 2026-01-21 (화)

### ✅ 완료한 작업

#### 1. WAS 문서 대대적 정리 (11:14 - 11:18)
**문제:**
- WAS 문서가 6개로 분산 (README, STATUS-REPORT, IMPROVEMENT-GUIDE, ARCHITECTURE, API-REFERENCE, SETUP)
- 파일이 너무 많아 관리 어려움
- 어디에 무엇이 있는지 찾기 힘듦

**해결:**
```
docs/WAS/
├── ARCHITECTURE.md (21KB)
│   - 전체 시스템 구조, 현재 상태, 소스 코드 분석
│   - API 레퍼런스 (6개 엔드포인트)
│   - 로컬/Docker/K8s 설정 가이드
│
├── TODO.md (19KB)
│   - P0: nginx 프록시, board.html (즉시)
│   - P1: Pagination, 에러 표준화, Swagger (이번 주)
│   - P2: Security, Redis, 검색, 댓글 (장기)
│   - 각 항목마다 배경지식, 트레이드오프, 옵션 비교
│
└── TROUBLESHOOTING.md (15KB)
    - 9가지 일반적 문제 해결 가이드
    - 외부 접근 불가, mTLS 에러, MySQL 연결 등
```

**커밋:** [8559a4d](https://github.com/wlals2/blogsite/commit/8559a4d)

**왜 이렇게 했나:**
- 3개 파일만 보면 모든 정보 파악 가능
- "현재 무엇이 있는가" (ARCHITECTURE) vs "앞으로 무엇을 할 것인가" (TODO) vs "문제 해결" (TROUBLESHOOTING) 명확히 구분
- 내용 손실 없이 재배치

---

#### 2. P0 작업 완료 - API 외부 접근 문제 해결 (12:11 - 12:27)

**배경:**
- 외부에서 `https://blog.jiminhome.shop/api/posts` 접근 시 에러
- nginx → WAS 프록시는 이미 설정되어 있었음
- 실제 문제: Istio mTLS 설정 및 AuthorizationPolicy 충돌

**문제 1: Istio mTLS 에러**

**증상:**
```bash
curl https://blog.jiminhome.shop/api/posts
# → upstream connect error or disconnect/reset before headers
# TLS_error: WRONG_VERSION_NUMBER
```

**원인:**
- nginx는 Plain HTTP로 WAS에 요청 (`proxy_pass http://was-service:8080`)
- DestinationRule이 `tls.mode: ISTIO_MUTUAL` 강제
- Istio sidecar가 mTLS 연결 시도 → TLS 버전 불일치

**해결:**
```yaml
# was-destinationrule.yaml
trafficPolicy:
  tls:
    mode: DISABLE  # ISTIO_MUTUAL → DISABLE
```

**커밋:** [f25bf46](https://github.com/wlals2/k8s-manifests/commit/f25bf46)

**문제 2: AuthorizationPolicy RBAC 에러**

**증상:**
```bash
curl https://blog.jiminhome.shop/api/posts
# → RBAC: access denied

# Istio 로그
kubectl logs -l app=was -c istio-proxy
# rbac_access_denied_matched_policy[none]
```

**원인:**
- mTLS DISABLE 모드에서는 source identity 파악 불가
- AuthorizationPolicy의 `source.principals`, `source.namespaces` 조건 작동 안 함
- `matched_policy[none]` → 어떤 정책도 매치되지 않아 기본 거부

**시도 1 (실패):**
```yaml
# from.source.namespaces만 유지
- from:
  - source:
      namespaces: ["blog-system"]
# 여전히 403 에러 (mTLS 없으면 namespace도 파악 못 함)
```

**해결:**
```yaml
# authz-was.yaml - from 조건 완전 제거
rules:
- to:  # from 조건 없음!
  - operation:
      ports: ["8080"]
      paths: ["/api/*", "/actuator/*"]
```

**커밋:** [78a251a](https://github.com/wlals2/k8s-manifests/commit/78a251a)

**중요 발견:**
- **Pod 재시작 필수**: AuthorizationPolicy 변경 후 반드시 Pod 재시작
- Istio sidecar가 정책을 캐시하므로 재시작 없이는 적용 안 됨

**최종 결과:**
```bash
# ✅ 모든 API 엔드포인트 정상 작동
curl https://blog.jiminhome.shop/api/posts
# [{"id":1,"title":"First Post",...}]

# ✅ CRUD 전체 테스트 성공
GET    /api/posts           ✅
GET    /api/posts/{id}      ✅
POST   /api/posts           ✅
PUT    /api/posts/{id}      ✅
DELETE /api/posts/{id}      ✅
GET    /api/posts/search    ✅

# ✅ board.html도 배포 확인
curl -I https://blog.jiminhome.shop/board.html
# HTTP/2 200
```

**보안 트레이드오프:**
- **변경 전**: namespace + ServiceAccount 기반 접근 제어
- **변경 후**: port + path 기반 접근 제어만
- **완화 요소**: WAS는 Ingress 직접 노출 없음 (nginx 프록시 통해서만)

**학습 내용:**
1. PeerAuthentication PERMISSIVE여도 DestinationRule이 우선 적용됨
2. mTLS DISABLE 환경에서는 source identity 기반 정책 사용 불가
3. Istio 정책 변경 시 Pod 재시작으로 sidecar 캐시 갱신 필요
4. `rbac_access_denied_matched_policy[none]` 로그가 정책 매치 실패 의미

**문서 업데이트:**
- `docs/WAS/TROUBLESHOOTING.md` 업데이트
  - Istio mTLS 에러 섹션 실제 해결 과정 추가
  - AuthorizationPolicy RBAC 에러 신규 섹션 추가
  - 진단 방법, 보안 트레이드오프 상세 설명

---

## 2026-01-20 (월)

### ✅ 완료한 작업

#### 1. WAS Canary 배포 구현 (오후)
**배경:**
- 기존 WEB만 Canary 배포
- WAS는 일반 Deployment (무중단 배포 없음)

**구현 내용:**

1. **was-rollout.yaml 생성** (Deployment → Rollout 전환)
   ```yaml
   strategy:
     canary:
       steps:
       - setWeight: 20   # 20% Canary
       - pause: {duration: 1m}
       - setWeight: 50   # 50% Canary
       - pause: {duration: 1m}
       - setWeight: 80   # 80% Canary
       - pause: {duration: 1m}

       trafficRouting:
         istio:
           virtualService: was-retry-timeout
           routes: [primary]
           destinationRule: was-dest-rule
   ```

2. **was-destinationrule.yaml 수정**
   ```yaml
   subsets:
   - name: stable  # Argo Rollouts가 관리
   - name: canary
   ```

3. **was-retry-timeout.yaml 수정**
   ```yaml
   http:
   - name: primary  # Rollout이 참조할 route 이름
     route:
     - destination:
         host: was-service
         subset: stable
       weight: 100
     - destination:
         host: was-service
         subset: canary
       weight: 0
   ```

4. **argocd-application.yaml 수정**
   ```yaml
   ignoreDifferences:
   - group: networking.istio.io
     kind: DestinationRule
     name: was-dest-rule
     jsonPointers:
     - /spec/subsets/0/labels
     - /spec/subsets/1/labels
   ```

**결과:**
- WAS도 Canary 배포 가능
- 배포 전략: WEB (10→50→90, 30초) vs WAS (20→50→80, 1분)
- WAS가 더 보수적 (DB 연동 API라 검증 시간 더 필요)

**문서화:**
- `docs/CICD/CANARY-COMPARISON.md` 생성
- WEB vs WAS 차이점 상세 설명

**커밋:** [ddbe30b](https://github.com/wlals2/blogsite/commit/ddbe30b)

#### 2. WAS 현황 분석 및 문제점 발견
**조사 내용:**
- Pod 상태: 2/2 Running, Healthy
- CPU: 6-7m (매우 낮음), Memory: 244-255Mi (정상)
- DB: MySQL 연결 성공, posts 테이블 1개 레코드

**발견한 문제:**
1. 🔴 외부 접근 불가 (404)
   ```bash
   curl https://blog.jiminhome.shop/api/posts
   # → 404 Not Found
   ```
   - 원인: nginx → WAS 프록시 설정 누락
   - nginx가 `/api/` 경로를 모름

2. 🔴 Istio mTLS 에러
   ```
   TLS_error: WRONG_VERSION_NUMBER
   ```
   - 원인: nginx → WAS가 Plain HTTP인데 mTLS로 전송

3. 🟡 board.html 미배포
   - 파일은 `static/board.html`에 존재
   - 하지만 외부 접근 불가

**문서화:**
- `docs/WAS/STATUS-REPORT.md` 생성 (현재는 ARCHITECTURE.md에 통합됨)

---

## 2026-01-17 (금)

### ✅ 완료한 작업

#### 1. 블로그 시스템 초기 구축
**구성 요소:**
- Hugo 정적 사이트 (PaperMod 테마)
- Spring Boot 3.5.0 WAS
- MySQL 8.0.44
- Kubernetes (Ingress, Services)
- GitHub Actions CI/CD
- ArgoCD GitOps

#### 2. WAS API 구현
**엔티티:**
```java
Post {
  Long id
  String title (최대 200자)
  String content
  String author (최대 50자)
  LocalDateTime createdAt
}
```

**API 엔드포인트 (6개):**
1. `GET /api/posts` - 전체 조회 (최신순)
2. `GET /api/posts/{id}` - 특정 조회
3. `POST /api/posts` - 작성
4. `PUT /api/posts/{id}` - 수정
5. `DELETE /api/posts/{id}` - 삭제
6. `GET /api/posts/search?keyword=XXX` - 검색

**기술 스택:**
- Spring Data JPA (Repository)
- Hibernate (ORM)
- Bean Validation (@NotBlank, @Size)
- Spring Actuator (Health Check)

#### 3. 데이터베이스
**테이블:**
```sql
CREATE TABLE posts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    content TEXT,
    author VARCHAR(50),
    created_at DATETIME NOT NULL
);
```

**초기 데이터:**
```sql
INSERT INTO posts VALUES (1, 'Jimin', 'Running on Kubernetes', '2026-01-17 02:49:29', 'First Post');
```

---

## 다음 할 일 (우선순위)

### P0 - 즉시 (오늘)
- [ ] nginx 프록시 설정 (`/api/` → was-service:8080)
- [ ] board.html 배포 확인
- [ ] 외부 API 접근 테스트

### P1 - 이번 주
- [ ] Pagination 구현 (Offset-based)
- [ ] 에러 응답 표준화 (@RestControllerAdvice)
- [ ] Swagger UI 추가 (springdoc-openapi)

### P2 - 장기
- [ ] Spring Security + JWT
- [ ] Redis 캐싱
- [ ] Full-text Search
- [ ] 댓글 기능
- [ ] 파일 업로드

---

## 작업 원칙

### 문서화 규칙
1. **모든 주요 변경사항은 CHANGELOG.md에 기록**
2. **날짜별 작업 내용은 이 파일(WORKLOG.md)에 기록**
3. **커밋 메시지는 Conventional Commits 형식**
   ```
   feat: 새 기능
   fix: 버그 수정
   docs: 문서만 변경
   chore: 빌드, 설정 변경
   ```

### Claude Code를 위한 컨텍스트
**새 세션 시작 시 읽어야 할 파일 순서:**
1. `WORKLOG.md` (이 파일) - 최근 작업 파악
2. `CHANGELOG.md` - 공식 변경사항
3. `docs/WAS/ARCHITECTURE.md` - 현재 시스템 상태
4. `docs/WAS/TODO.md` - 다음 할 일

**주요 디렉토리:**
```
~/blogsite/
├── WORKLOG.md              # ← 날짜별 작업 일지
├── CHANGELOG.md            # ← 버전별 변경사항
├── docs/
│   ├── WAS/
│   │   ├── ARCHITECTURE.md # ← WAS 전체 상태
│   │   ├── TODO.md         # ← 개선 계획
│   │   └── TROUBLESHOOTING.md
│   └── CICD/
│       └── CANARY-COMPARISON.md
└── blog-k8s-project/was/  # ← WAS 소스코드

~/k8s-manifests/blog-system/  # ← Kubernetes Manifests
├── was-rollout.yaml
├── was-service.yaml
├── was-destinationrule.yaml
└── ...
```

---

## 알려진 문제 (Known Issues)

### 🔴 Critical
1. **외부 API 접근 불가**
   - URL: `https://blog.jiminhome.shop/api/posts`
   - 에러: 404 Not Found
   - 원인: nginx 프록시 설정 누락
   - 해결: web-nginx-config ConfigMap 수정 필요

2. **Istio mTLS 에러**
   - 에러: `TLS_error: WRONG_VERSION_NUMBER`
   - 원인: nginx → WAS Plain HTTP vs mTLS 불일치
   - 해결: DestinationRule `tls.mode: DISABLE` 또는 PERMISSIVE

### 🟡 Important
1. **board.html 미배포**
   - 파일 위치: `~/blogsite/static/board.html`
   - Hugo 빌드 확인 필요
   - 배포 워크플로우 검증 필요

2. **Pagination 없음**
   - 현재 전체 조회 (성능 문제 가능)
   - Spring Data JPA Page 적용 필요

3. **에러 응답 형식 부족**
   - 404 에러 시 빈 응답
   - @RestControllerAdvice 필요

---

**마지막 업데이트**: 2026-01-21 11:20
**작성자**: Claude Code & Jimin
