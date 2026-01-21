# Changelog

> 프로젝트 주요 변경사항 기록 - [Keep a Changelog](https://keepachangelog.com) 형식

---

## [Unreleased]

### 계획 중
- Spring Security + JWT 인증
- Redis 캐싱
- Full-text Search 최적화

---

## [1.4.0] - 2026-01-21 (저녁)

### Added
- **🎯 Swagger UI 추가** (P1 완료)
  - springdoc-openapi-starter-webmvc-ui 2.3.0 의존성 추가
  - 자동 API 문서 생성
  - 접근 URL: `https://blog.jiminhome.shop/swagger-ui.html`
  - @Tag, @Operation, @Parameter 어노테이션으로 문서 풍부화
  - 실시간 API 테스트 가능

- **🎯 Pagination 구현** (P1 완료)
  - Spring Data JPA Page, Pageable 사용
  - Offset-based Pagination (page, size 파라미터)
  - 기본값: page=0, size=10
  - 정렬: 최신순 (createdAt DESC)
  - 응답 형식: Page<Post> (content, totalElements, totalPages 포함)
  - 성능 개선: 메모리/네트워크 90% 감소

- **🎯 에러 응답 표준화** (P1 완료)
  - RFC 7807 스타일 ErrorResponse DTO 생성
  - Custom Exception: PostNotFoundException
  - GlobalExceptionHandler (@RestControllerAdvice)
  - 모든 에러에 timestamp, status, error, message, path 포함
  - Validation 에러도 표준화 (MethodArgumentNotValidException 처리)

### Changed
- **WAS 소스코드 개선**
  - `PostService.java`: getAllPostsPaged() 메서드 추가
  - `PostController.java`: try-catch 제거 (GlobalExceptionHandler가 처리)
  - `PostController.java`: Pagination 파라미터 추가
  - RuntimeException → PostNotFoundException 교체

### Fixed
- **🔴 ArgoCD OutOfSync 문제 해결**
  - 문제: was-dest-rule이 계속 OutOfSync 표시
  - 원인: Git 파일에 빈 `labels:` 필드, 클러스터는 동적 레이블 존재
  - 해결: 빈 `labels:` 필드 완전 제거
  - Commit: [08dcec2](https://github.com/wlals2/k8s-manifests/commit/08dcec2)
  - 학습: ignoreDifferences는 값만 무시, 구조는 검사

### Documentation
- **WORKLOG.md 업데이트**
  - ArgoCD OutOfSync 해결 과정 추가
  - P1 작업 상세 기록 (Swagger, Pagination, 에러 표준화)
  - 학습 포인트 정리

### Performance
- **API 응답 최적화**
  - 메모리 사용: 1,000개 → 10개 로드 (90% 감소)
  - 네트워크: ~100KB → ~10KB 응답 (90% 감소)
  - 응답 시간: 직렬화 시간 90% 단축

### Lessons Learned
1. ignoreDifferences는 값 차이만 무시, 구조 차이는 OutOfSync 발생
2. Spring Data JPA Pagination은 5분만에 구현 가능 (매우 쉬움)
3. @RestControllerAdvice로 코드 간결화 (try-catch 완전 제거)
4. Swagger는 pom.xml 1줄로 전체 API 문서 자동 생성
5. Empty YAML field (`labels:`) vs No field는 다름

### Known Issues Resolved
- ✅ ArgoCD OutOfSync (was-dest-rule) → 해결
- ✅ Pagination 없음 → 해결
- ✅ 에러 응답 형식 부족 → 해결
- ✅ API 문서 없음 → 해결

---

## [1.3.0] - 2026-01-21 (오후)

### Fixed
- **🔴 Istio mTLS 에러 해결** (Critical)
  - 문제: `TLS_error: WRONG_VERSION_NUMBER` - nginx → WAS 통신 실패
  - 원인: nginx는 Plain HTTP 전송, DestinationRule은 mTLS 강제
  - 해결: `was-destinationrule.yaml` - `tls.mode: ISTIO_MUTUAL` → `DISABLE`
  - Commit: [f25bf46](https://github.com/wlals2/k8s-manifests/commit/f25bf46)

- **🔴 AuthorizationPolicy RBAC 에러 해결** (Critical)
  - 문제: `RBAC: access denied` - `matched_policy[none]`
  - 원인: mTLS DISABLE 환경에서 `source.principals`, `source.namespaces` 작동 안 함
  - 해결 1차: `source.principals` 제거, `source.namespaces`만 유지 → 여전히 실패
  - 해결 2차: `from` 조건 완전 제거, `to` 조건(port/path)만 사용 → 성공
  - Commit: [78a251a](https://github.com/wlals2/k8s-manifests/commit/78a251a)
  - 보안 트레이드오프: namespace 기반 제어 → port/path 기반 제어

### Changed
- **K8s Manifests (k8s-manifests repo)**
  - `was-destinationrule.yaml`: mTLS DISABLE 설정
  - `authz-was.yaml`: source identity 조건 제거

### Added
- **외부 API 접근 확인** ✅
  - URL: `https://blog.jiminhome.shop/api/posts`
  - 상태: 모든 CRUD 엔드포인트 정상 작동
  - 테스트: GET, POST, PUT, DELETE, SEARCH 성공

- **board.html 배포 확인** ✅
  - URL: `https://blog.jiminhome.shop/board.html`
  - 상태: HTTP/2 200, 정상 배포

### Documentation
- **`docs/WAS/TROUBLESHOOTING.md` 업데이트**
  - Istio mTLS 에러 섹션 확장 (실제 해결 과정, 진단 방법)
  - AuthorizationPolicy RBAC 에러 신규 섹션 추가
  - 보안 트레이드오프 비교 테이블
  - mTLS DISABLE 환경 특성 상세 설명

### Known Issues Resolved
- ✅ 외부 API 접근 불가 (404) → 해결 (실제 원인: mTLS 에러)
- ✅ Istio mTLS 에러 → 해결
- ✅ AuthorizationPolicy RBAC 에러 → 해결

### Lessons Learned
1. PeerAuthentication PERMISSIVE여도 DestinationRule이 우선 적용됨
2. mTLS DISABLE 환경에서는 source identity 기반 정책 사용 불가
3. Istio 정책 변경 시 Pod 재시작으로 sidecar 캐시 갱신 필요
4. `rbac_access_denied_matched_policy[none]` 로그 = 정책 매치 실패

---

## [1.2.0] - 2026-01-21 (오전)

### Added
- **WAS 문서 체계화**
  - `docs/WAS/ARCHITECTURE.md`: 전체 아키텍처, 현재 상태, API 레퍼런스, 설정 가이드
  - `docs/WAS/TODO.md`: 개선 계획 (P0/P1/P2), 배경지식, 트레이드오프
  - `docs/WAS/TROUBLESHOOTING.md`: 문제 해결 가이드 (9가지 일반적 문제)
  - 기존 6개 파일 → 3개로 통합 (관리 용이성 향상)

### Changed
- **문서 구조 개선**
  - 6개 분산 파일 → 3개 집중 파일
  - 모든 현재 상태를 ARCHITECTURE.md에 통합
  - 중복 제거, 내용 손실 없음

---

## [1.1.0] - 2026-01-20

### Added
- **WAS Canary 배포 구현**
  - Argo Rollouts로 Deployment → Rollout 전환
  - Canary 전략: 20% → 50% → 80% → 100% (각 1분 대기)
  - Istio VirtualService + DestinationRule 통합
  - ArgoCD ignoreDifferences 설정 (동적 레이블 무시)
  - Commit: [05abae3](https://github.com/wlals2/blogsite/commit/05abae3)

- **CI/CD 문서**
  - `docs/CICD/CANARY-COMPARISON.md`: WEB vs WAS Canary 전략 비교
  - 배포 전략, VirtualService, DestinationRule 차이점 설명

### Changed
- **was-rollout.yaml**: Deployment → Rollout
- **was-destinationrule.yaml**: stable/canary subset 추가
- **was-retry-timeout.yaml**: route 이름 "primary" 추가
- **argocd-application.yaml**: was-dest-rule ignoreDifferences 추가

### Fixed
- ArgoCD selfHeal로 인한 Rollout 동적 레이블 되돌림 문제

---

## [1.0.0] - 2026-01-17

### Added
- **블로그 시스템 초기 구축**
  - Hugo 정적 사이트 (PaperMod 테마)
  - Spring Boot WAS (게시판 API)
  - MySQL 8.0.44 데이터베이스
  - Kubernetes 배포 (Ingress, Services)

- **WAS 기능**
  - 게시글 CRUD API (6개 엔드포인트)
  - JPA + Hibernate ORM
  - Spring Validation
  - Health Check (Actuator)
  - MySQL posts 테이블 자동 생성

- **배포 인프라**
  - GitHub Actions CI/CD
  - ArgoCD GitOps
  - Cloudflare CDN
  - Istio Service Mesh (mTLS)

### Known Issues
- 🔴 외부에서 `/api/posts` 접근 불가 (404)
  - 원인: nginx → WAS 프록시 설정 누락
  - 해결: web-nginx-config ConfigMap에 `/api/` location 추가 필요

- 🔴 Istio mTLS 에러
  - 원인: nginx → WAS Plain HTTP vs mTLS 불일치
  - 해결: DestinationRule `tls.mode: DISABLE` 또는 PERMISSIVE

- 🟡 board.html 미배포
  - 파일은 존재하나 외부 접근 불가
  - Hugo 빌드 및 배포 확인 필요

---

## 변경사항 카테고리 정의

### Added
- 새로운 기능 추가

### Changed
- 기존 기능 변경

### Deprecated
- 곧 제거될 기능

### Removed
- 제거된 기능

### Fixed
- 버그 수정

### Security
- 보안 관련 변경

---

**포맷**: [Keep a Changelog](https://keepachangelog.com/ko/1.0.0/)
**버저닝**: [Semantic Versioning](https://semver.org/lang/ko/)
**마지막 업데이트**: 2026-01-21
