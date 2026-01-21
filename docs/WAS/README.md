# WAS (Spring Boot API) 문서

> Spring Boot 3.5.0 + MySQL 게시판 API 완벽 가이드

---

## 개요

**WAS (Web Application Server)**는 블로그 시스템의 Backend API를 담당하는 Spring Boot 애플리케이션입니다.

### 기본 정보

| 항목 | 내용 |
|------|------|
| **프레임워크** | Spring Boot 3.5.0 |
| **Java 버전** | JDK 17 |
| **데이터베이스** | MySQL 8.0.44 |
| **ORM** | Hibernate (JPA) |
| **빌드 도구** | Maven |
| **컨테이너 이미지** | `ghcr.io/wlals2/board-was:v9` |
| **배포 방식** | Argo Rollouts (Canary) |

### 주요 기능

- ✅ **게시글 CRUD**: 작성, 조회, 수정, 삭제
- ✅ **검색**: 제목 기반 키워드 검색
- ✅ **정렬**: 최신순 정렬
- ✅ **Validation**: 입력값 검증 (제목, 작성자 길이)
- ✅ **Health Check**: Spring Actuator (`/actuator/health`)

### 아키텍처

```
Client (브라우저)
  ↓ HTTPS
Cloudflare CDN
  ↓ HTTPS
Ingress (NGINX)
  ↓ HTTP
web-service (nginx Pod)
  ↓ HTTP (mTLS)
was-service (Spring Boot) ← WAS
  ↓ TCP
mysql-service (MySQL)
```

---

## 📚 문서 목록

### 1. [현황 분석 (STATUS-REPORT.md)](STATUS-REPORT.md)

**내용:**
- ✅ 현재 Pod/Rollout 상태
- ✅ 소스 코드 분석 (Entity, Controller, Service, Repository)
- ⚠️ 문제점 발견 (외부 접근 불가, Istio mTLS 에러)
- 📋 개선 제안 (P0/P1/P2 우선순위)

**언제 읽나?**
- WAS 전체 상태 파악 필요 시
- 트러블슈팅 시작 전

### 2. [개선 가이드 (IMPROVEMENT-GUIDE.md)](IMPROVEMENT-GUIDE.md)

**내용:**
- 🔍 nginx 프록시 설정 (왜 필요한가?)
- 🔍 board.html 배포 전략
- 🔍 Pagination, 에러 표준화, Swagger
- 📊 각 옵션의 트레이드오프 비교

**언제 읽나?**
- 개선 작업 전 배경 지식 습득
- 기술 선택 이유 이해 필요 시
- 대안 비교 필요 시

### 3. [아키텍처 (ARCHITECTURE.md)](ARCHITECTURE.md)

**내용:**
- 🏗️ 전체 시스템 구조
- 🔄 트래픽 흐름 (Ingress → nginx → WAS → MySQL)
- 📦 컴포넌트 상세 설명
- 🔐 보안 설정 (Istio mTLS, Secret)

**언제 읽나?**
- 신규 팀원 온보딩
- 아키텍처 변경 계획 시

### 4. [설정 가이드 (SETUP.md)](SETUP.md)

**내용:**
- ⚙️ 로컬 개발 환경 설정
- 🐳 Docker 빌드 및 실행
- ☸️ Kubernetes 배포 방법
- 🧪 테스트 방법 (API, DB)

**언제 읽나?**
- 로컬 개발 시작 시
- 새 개발자 환경 셋업

### 5. [API 레퍼런스 (API-REFERENCE.md)](API-REFERENCE.md)

**내용:**
- 📋 전체 API 목록
- 📝 요청/응답 예시
- 🔴 에러 코드 및 메시지
- 🧪 cURL 예시

**언제 읽나?**
- API 사용법 확인
- 프론트엔드 개발 시

---

## 🚀 빠른 시작

### 1. 로컬 실행

```bash
# 1. WAS 소스 디렉토리로 이동
cd ~/blogsite/blog-k8s-project/was

# 2. MySQL 실행 (Docker)
docker run -d \
  --name mysql \
  -e MYSQL_ROOT_PASSWORD=rootpassword \
  -e MYSQL_DATABASE=board \
  -p 3306:3306 \
  mysql:8.0.44

# 3. application.properties 설정
cat > src/main/resources/application.properties <<EOF
spring.application.name=Board
spring.datasource.url=jdbc:mysql://localhost:3306/board
spring.datasource.username=root
spring.datasource.password=rootpassword
spring.jpa.hibernate.ddl-auto=update
EOF

# 4. Maven 빌드 및 실행
./mvnw spring-boot:run

# 5. 테스트
curl http://localhost:8080/api/posts
curl http://localhost:8080/actuator/health
```

### 2. Docker 빌드

```bash
# 1. Docker 이미지 빌드
cd ~/blogsite/blog-k8s-project/was
docker build -t board-was:local .

# 2. Docker 실행
docker run -d \
  --name board-was \
  -e SPRING_DATASOURCE_URL=jdbc:mysql://host.docker.internal:3306/board \
  -e SPRING_DATASOURCE_USERNAME=root \
  -e SPRING_DATASOURCE_PASSWORD=rootpassword \
  -p 8080:8080 \
  board-was:local

# 3. 로그 확인
docker logs -f board-was
```

### 3. Kubernetes 배포

```bash
# 1. 이미지 빌드 및 푸시 (GitHub Actions 자동)
# .github/workflows/deploy-was.yml 참고

# 2. ArgoCD 자동 배포 (3분 이내)
kubectl get rollout was -n blog-system --watch

# 3. Pod 상태 확인
kubectl get pods -n blog-system -l app=was

# 4. 로그 확인
kubectl logs -n blog-system -l app=was -c spring-boot --tail=100
```

---

## 📊 현재 상태

### Pod 정보

```bash
NAME                 READY   STATUS    RESTARTS   AGE
was-f9f55456-2cklv   2/2     Running   0          1h
was-f9f55456-5kmmn   2/2     Running   0          1h
```

### 리소스 사용량

| Metric | 현재 | 요청 | 제한 | 사용률 |
|--------|------|------|------|--------|
| **CPU** | 6-7m | 250m | 500m | 3% |
| **Memory** | 244-255Mi | 512Mi | 1Gi | 48% |

### 배포 상태

- **Rollout**: Healthy (Canary 100%)
- **Image**: `ghcr.io/wlals2/board-was:v9`
- **Replicas**: 2/2 Ready

---

## 🔧 주요 설정 파일

### Kubernetes Manifests

| 파일 | 위치 | 설명 |
|------|------|------|
| **Rollout** | `~/k8s-manifests/blog-system/was-rollout.yaml` | Canary 배포 설정 |
| **Service** | `~/k8s-manifests/blog-system/was-service.yaml` | ClusterIP :8080 |
| **ConfigMap** | `~/k8s-manifests/blog-system/was-configmap.yaml` | DB 연결 정보 |
| **DestinationRule** | `~/k8s-manifests/blog-system/was-destinationrule.yaml` | Istio mTLS, subsets |
| **VirtualService** | `~/k8s-manifests/blog-system/was-retry-timeout.yaml` | Retry, Timeout |

### 애플리케이션 설정

| 파일 | 위치 | 설명 |
|------|------|------|
| **pom.xml** | `~/blogsite/blog-k8s-project/was/pom.xml` | Maven 의존성 |
| **Dockerfile** | `~/blogsite/blog-k8s-project/was/Dockerfile` | 멀티 스테이지 빌드 |
| **application.properties** | `src/main/resources/` | 거의 비어있음 (ConfigMap 사용) |

---

## 🐛 트러블슈팅

### 외부 접근 불가 (404)

**증상:**
```bash
curl https://blog.jiminhome.shop/api/posts
# → 404 Not Found
```

**원인:** nginx → WAS 프록시 설정 누락

**해결:** [IMPROVEMENT-GUIDE.md](IMPROVEMENT-GUIDE.md) 참고

### Pod 재시작 반복

**증상:**
```bash
kubectl get pods -n blog-system -l app=was
# RESTARTS: 3
```

**원인 체크:**
1. Liveness Probe 실패 (60초 이내 시작 못 함)
2. OOMKilled (메모리 부족)
3. DB 연결 실패

**해결:**
```bash
# 로그 확인
kubectl logs -n blog-system POD_NAME -c spring-boot --previous

# DB 연결 확인
kubectl exec -n blog-system mysql-POD -- mysql -uroot -prootpassword -e "SELECT 1"
```

### Canary 배포 실패

**증상:**
```bash
kubectl argo rollouts get rollout was -n blog-system
# Status: Degraded
```

**원인:** AnalysisTemplate 실패 또는 수동 Pause

**해결:**
```bash
# 수동 Promote
kubectl argo rollouts promote was -n blog-system

# 롤백
kubectl argo rollouts undo was -n blog-system
```

---

## 📈 모니터링

### Health Check

```bash
# Liveness/Readiness
kubectl describe pod -n blog-system POD_NAME | grep -A 10 "Liveness\|Readiness"

# Actuator
kubectl port-forward -n blog-system svc/was-service 8080:8080
curl http://localhost:8080/actuator/health
```

### 메트릭 확인

```bash
# CPU/Memory
kubectl top pods -n blog-system -l app=was

# Prometheus (TODO)
# Grafana Dashboard (TODO)
```

### 로그 확인

```bash
# 실시간 로그
kubectl logs -n blog-system -l app=was -c spring-boot -f

# 최근 100줄
kubectl logs -n blog-system -l app=was -c spring-boot --tail=100

# 에러만
kubectl logs -n blog-system -l app=was -c spring-boot | grep -i error
```

---

## 🔐 보안

### Secrets

```bash
# MySQL 비밀번호
kubectl get secret mysql-secret -n blog-system -o jsonpath='{.data.mysql-root-password}' | base64 -d

# 절대 Git에 커밋하지 말 것!
```

### Istio mTLS

```yaml
# was-destinationrule.yaml
spec:
  trafficPolicy:
    tls:
      mode: ISTIO_MUTUAL  # Pod 간 암호화
```

### Network Policy (TODO)

- WAS → MySQL만 허용
- 외부 → WAS 직접 접근 차단

---

## 🚀 CI/CD

### GitHub Actions

**워크플로우:** `.github/workflows/deploy-was.yml`

**트리거:**
- 수동 실행 (`workflow_dispatch`)
- 워크플로우 파일 변경 시

**단계:**
1. 로컬 WAS 소스 복사 (Git에 없음)
2. Maven 빌드 → Docker 이미지
3. GHCR 푸시 (`ghcr.io/wlals2/board-was:vN`)
4. GitOps Manifest 업데이트 (`was-rollout.yaml`)
5. ArgoCD 자동 배포 (3분 이내)
6. 이메일 알림

### ArgoCD

**Application:** `blog-system`

```bash
# 동기화 상태
kubectl get application blog-system -n argocd

# 수동 동기화
argocd app sync blog-system
```

---

## 📝 다음 할 일

### P0 (즉시)

- [ ] nginx 프록시 설정 (`/api/` → WAS)
- [ ] board.html 배포 확인

### P1 (이번 주)

- [ ] Pagination 구현
- [ ] 에러 응답 표준화 (`@RestControllerAdvice`)
- [ ] Swagger UI 추가

### P2 (장기)

- [ ] Spring Security + JWT
- [ ] Redis 캐싱
- [ ] 댓글 기능
- [ ] 파일 업로드

---

## 📞 참고 링크

- [Spring Boot 3.x 문서](https://docs.spring.io/spring-boot/docs/3.5.0/reference/html/)
- [Spring Data JPA](https://docs.spring.io/spring-data/jpa/docs/current/reference/html/)
- [Argo Rollouts](https://argoproj.github.io/argo-rollouts/)
- [Istio Traffic Management](https://istio.io/latest/docs/concepts/traffic-management/)

---

**작성일:** 2026-01-21
**버전:** WAS v9
**작성자:** Claude & Jimin
