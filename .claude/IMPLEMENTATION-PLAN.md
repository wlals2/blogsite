# Hugo Blog + Spring Boot WAS - Kubernetes 구현 계획

> 작성일: 2026-01-16
> 상태: 📋 계획 단계
> 목표: 로컬 Kubernetes에 Hugo 블로그 + Spring Boot WAS 배포 (GitOps)

---

## 📊 전체 아키텍처 (최종 확정)

```
┌─────────────────────────────────────────────────────────────────┐
│           Kubernetes Cluster (베어메탈)                          │
│           192.168.1.187:6443                                     │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  Namespace: blog-system                                   │  │
│  │                                                            │  │
│  │  [Ingress Controller] nginx                               │  │
│  │        ↓                                                   │  │
│  │  [Ingress Rules]                                           │  │
│  │     /       → web-service:80 (Hugo 블로그)                │  │
│  │     /board  → was-service:8080 (Spring Boot Board)        │  │
│  │     /api    → was-service:8080 (REST API)                 │  │
│  │                                                            │  │
│  │  ┌───────────────────┐     ┌───────────────────┐         │  │
│  │  │ WEB Pod           │     │ WAS Pod           │         │  │
│  │  │ ┌───────────────┐ │     │ ┌───────────────┐ │         │  │
│  │  │ │ nginx:alpine  │ │     │ │ Spring Boot   │ │         │  │
│  │  │ │ + Hugo public/│ │     │ │ Board App     │ │         │  │
│  │  │ └───────────────┘ │     │ └───────┬───────┘ │         │  │
│  │  │ replicas: 1       │     │         │         │         │  │
│  │  └───────────────────┘     │  replicas: 1      │         │  │
│  │                             │         │         │         │  │
│  │                             │  ┌──────▼──────┐  │         │  │
│  │                             │  │ MySQL Pod   │  │         │  │
│  │                             │  │ mysql:8.0   │  │         │  │
│  │                             │  │ PVC:Longhorn│  │         │  │
│  │                             │  └─────────────┘  │         │  │
│  │                             └───────────────────┘         │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  Jenkins (K8s Pod 또는 Docker)                            │  │
│  │                                                            │  │
│  │  Pipeline 1: Hugo Blog (Jenkinsfile-web)                  │  │
│  │    Git Push → Hugo Build → Docker → K8s Deploy           │  │
│  │                                                            │  │
│  │  Pipeline 2: Spring Boot WAS (Jenkinsfile-was)            │  │
│  │    Git Push → Maven Build → Docker → K8s Deploy          │  │
│  └──────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘

외부 접속:
http://192.168.1.187:30080/       (Ingress NodePort)
http://blog.jiminhome.shop/       (Cloudflare Tunnel - 선택)
```

---

## 🎯 프로젝트 목표

### 기능 요구사항
1. ✅ Hugo 블로그를 K8s Pod로 배포
2. ✅ Spring Boot Board(게시판)를 K8s Pod로 배포
3. ✅ Ingress로 Path-based Routing (`/`, `/board`)
4. ✅ Jenkins CI/CD로 Git Push → 자동 배포
5. ✅ MySQL을 Longhorn PVC로 영구 저장

### 비기능 요구사항
1. ✅ 배포 자동화 (GitOps)
2. ✅ 버전 관리 (Git 커밋 = 배포 버전)
3. ✅ 롤백 가능 (kubectl rollout undo)
4. ✅ 프로덕션급 구조 (bespin 프로젝트와 유사)

---

## 📂 프로젝트 디렉터리 구조

### 생성할 디렉터리

```
/home/jimin/
├── blogsite/                        # Hugo 소스 (기존)
│   ├── content/
│   ├── public/                      # 빌드 결과 (Git ignore)
│   ├── Dockerfile                   # ← 신규 생성
│   └── .claude/
│       ├── context.md
│       ├── skills/blog-k8s.md
│       └── IMPLEMENTATION-PLAN.md   # 이 파일
│
└── blog-k8s-project/                # K8s 프로젝트 (신규 생성)
    ├── README.md
    ├── web/
    │   └── k8s/
    │       ├── namespace.yaml
    │       ├── deployment.yaml
    │       ├── service.yaml
    │       └── ingress.yaml
    ├── was/
    │   ├── src/                     # Spring Boot 소스
    │   │   ├── main/
    │   │   │   ├── java/
    │   │   │   └── resources/
    │   │   └── test/
    │   ├── pom.xml
    │   ├── Dockerfile
    │   └── k8s/
    │       ├── deployment.yaml
    │       ├── service.yaml
    │       └── configmap.yaml
    ├── mysql/
    │   └── k8s/
    │       ├── deployment.yaml
    │       ├── service.yaml
    │       ├── pvc.yaml             # Longhorn
    │       └── secret.yaml
    ├── ingress/
    │   └── ingress.yaml             # 통합 Ingress
    └── jenkins/
        ├── Jenkinsfile-web
        └── Jenkinsfile-was
```

---

## 🤔 기술 선택 이유 (Why?)

> **중요**: 코드를 작성하기 전에 "왜 이 기술을 선택했는지" 이해해야 합니다.

### 왜 Kubernetes인가?

**문제 상황**:
```
Docker Compose로도 WEB + WAS + MySQL 실행 가능
→ 왜 복잡한 Kubernetes를 사용?
```

**Kubernetes 선택 이유**:

| 기능 | Docker Compose | Kubernetes | 선택 이유 |
|------|---------------|-----------|----------|
| **자동 복구** | ❌ 컨테이너 죽으면 수동 재시작 | ✅ Pod 자동 재시작 | HA (High Availability) |
| **스케일링** | ❌ 수동 replica 설정 | ✅ HPA 자동 스케일링 | 트래픽 증가 대응 |
| **로드밸런싱** | ❌ nginx 수동 설정 | ✅ Service 자동 LB | 부하 분산 |
| **롤링 업데이트** | ❌ 수동 배포 | ✅ 무중단 배포 | 서비스 중단 최소화 |
| **헬스체크** | ❌ 수동 모니터링 | ✅ livenessProbe 자동 | 장애 감지 |
| **포트폴리오** | ⚠️ 학습 효과 낮음 | ✅ 프로덕션급 기술 | 취업 경쟁력 |

**결론**: ✅ Kubernetes는 "프로덕션 환경에서 컨테이너를 어떻게 운영하는가"를 학습하는 도구

---

### 왜 Ingress Controller인가?

**문제 상황**:
```
목표: http://192.168.1.187/       → Hugo 블로그
      http://192.168.1.187/board  → 게시판

어떻게 하나의 IP/도메인으로 2개 서비스 제공?
```

**해결 방법 비교**:

#### 방법 1: NodePort만 사용 (❌ 비추천)

```
사용자
  ├─ http://192.168.1.187:30080  → WEB
  └─ http://192.168.1.187:30081  → WAS
```

**문제점**:
- ❌ 포트 2개 (사용자가 포트 번호 기억)
- ❌ Path 구분 불가 (`/board`로 라우팅 못함)
- ❌ SSL 인증서 2개 필요

---

#### 방법 2: nginx Pod 직접 설정 (⚠️ 가능하지만 복잡)

```
nginx Pod (Reverse Proxy)
  ├─ /       → WEB Service
  └─ /board  → WAS Service
```

**nginx.conf 수동 관리**:
```nginx
location / {
    proxy_pass http://web-service;
}
location /board {
    proxy_pass http://was-service:8080;
}
```

**문제점**:
- ❌ 서비스 추가 시마다 nginx.conf 수동 수정
- ❌ nginx 재시작 필요
- ❌ SSL 인증서 수동 관리
- ❌ Kubernetes 방식 아님

---

#### 방법 3: Ingress Controller (✅ 추천!)

```
Ingress Controller (nginx 자동 관리)
  ↓
Ingress 리소스 (YAML 선언)
  ├─ /       → web-service
  └─ /board  → was-service
```

**Ingress YAML만 작성**:
```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: blog-ingress
spec:
  rules:
  - http:
      paths:
      - path: /
        backend:
          service:
            name: web-service
      - path: /board
        backend:
          service:
            name: was-service
```

**장점**:
- ✅ **선언적 관리** (YAML로 설정)
- ✅ **nginx 설정 자동 생성** (수동 관리 불필요)
- ✅ **서비스 추가 시 YAML만 수정** (nginx 재시작 불필요)
- ✅ **SSL/TLS 자동 관리** (cert-manager 연동)
- ✅ **Kubernetes 표준** (어디서나 동일)

---

**Ingress Controller 동작 원리**:

```
┌─────────────────────────────────────────────────────────┐
│  Ingress Controller = "nginx를 자동으로 관리하는 컨트롤러" │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  1. Ingress 리소스 감시 (Watch)                          │
│     └─ Ingress YAML 변경 감지                            │
│                                                          │
│  2. nginx 설정 자동 생성                                  │
│     └─ Ingress → nginx.conf 변환                         │
│                                                          │
│  3. nginx 자동 Reload                                    │
│     └─ 설정 변경 시 무중단 reload                         │
│                                                          │
│  4. Service Discovery                                    │
│     └─ Service의 Pod IP 자동 감지                         │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

**왜 nginx Ingress Controller인가?**

| Ingress Controller | 특징 | 선택 |
|-------------------|------|------|
| **nginx** | 가장 대중적, 안정적, 문서 풍부 | ✅ 추천 (우리 선택) |
| Traefik | 최신, 자동 SSL, 대시보드 | ⚠️ 학습 곡선 |
| HAProxy | 고성능, L4/L7 | ⚠️ 복잡 |
| Kong | API Gateway 기능 | ⚠️ 과한 기능 |

**결론**: ✅ nginx Ingress Controller는 "하나의 IP로 여러 서비스를 Path 기반 라우팅"하는 표준 방법

---

### 왜 Hugo를 Pod로 배포하는가?

**대안**:
```
방법 1: Hugo 로컬 실행 (hugo server)
방법 2: Hugo 빌드 → nginx 직접 실행
방법 3: Hugo → Docker → K8s Pod (우리 선택)
```

**Pod로 배포하는 이유**:

| 항목 | 로컬 실행 | Pod 배포 (우리) |
|------|----------|---------------|
| **일관성** | 로컬 환경 의존 | ✅ 컨테이너화 (어디서나 동일) |
| **자동화** | 수동 배포 | ✅ Jenkins CI/CD |
| **확장성** | 1개만 실행 | ✅ Replica 늘릴 수 있음 |
| **버전 관리** | Git 커밋 ≠ 배포 | ✅ Git 커밋 = Docker 이미지 태그 |
| **롤백** | 수동 | ✅ kubectl rollout undo |
| **프로덕션 유사** | ❌ | ✅ Netlify, Vercel과 동일 패턴 |

**Netlify, Vercel도 동일한 방식**:
```
Git Push
  ↓
자동 빌드 (Hugo/Next.js)
  ↓
Docker 이미지 생성
  ↓
Kubernetes 배포
  ↓
1-2분 후 배포 완료
```

**결론**: ✅ Hugo를 Pod로 배포하면 "정적 사이트도 프로덕션급으로 운영"하는 방법을 학습

---

### 왜 Spring Boot Board인가?

**WAS 선택지**:
1. PetClinic (bespin에서 사용)
2. **Spring Boot Board** (우리 선택)
3. TODO App
4. Bookmark Manager

**Board 선택 이유**:

| 기능 | 학습 효과 | 실용성 |
|------|----------|--------|
| **CRUD** | ✅ JPA, Repository, Service 학습 | ✅ 모든 앱의 기본 |
| **REST API** | ✅ /api/posts 엔드포인트 | ✅ 블로그와 연동 가능 |
| **인증/인가** | ⚠️ 선택 (Spring Security) | ✅ 실전 기능 |
| **검색 기능** | ⚠️ 선택 (Elasticsearch) | ✅ 확장 가능 |

**블로그와 통합 아이디어**:
```
Hugo 블로그
  ↓
"댓글 보기" 버튼 클릭
  ↓
/board/api/comments API 호출
  ↓
Spring Boot가 댓글 반환
  ↓
블로그에 댓글 표시
```

**결론**: ✅ Board는 "블로그와 자연스럽게 연동 가능"하고 "CRUD를 완벽히 학습"할 수 있음

---

### 왜 MySQL을 Pod로 배포하는가?

**대안**:
```
방법 1: MySQL Pod (우리 선택)
방법 2: AWS RDS
방법 3: 로컬 MySQL 직접 설치
```

**Pod 선택 이유**:

| 항목 | MySQL Pod | AWS RDS |
|------|-----------|---------|
| **비용** | ✅ $0 | ❌ ~$15/월 |
| **학습** | ✅ K8s PVC, StatefulSet | ❌ 관리형 서비스 |
| **데이터 영구성** | ✅ Longhorn PVC | ✅ EBS |
| **백업/복구** | ⚠️ 수동 | ✅ 자동 |
| **고가용성** | ❌ Single Pod | ✅ Multi-AZ |

**Longhorn PVC 사용 이유**:
```
emptyDir (임시 저장소)
  ├─ Pod 재시작 시 데이터 삭제 ❌
  └─ 개발 환경에만 적합

Longhorn PVC (영구 저장소)
  ├─ Pod 재시작해도 데이터 유지 ✅
  ├─ 분산 스토리지 (3 replica)
  └─ 프로덕션급
```

**결론**: ✅ MySQL Pod + Longhorn PVC는 "로컬 환경에서도 데이터를 영구 저장"하는 방법

---

### 왜 Jenkins인가?

**CI/CD 도구 비교**:

| 도구 | 장점 | 단점 | 선택 |
|------|------|------|------|
| **Jenkins** | 완전한 제어, 플러그인 풍부 | 설정 복잡 | ✅ 우리 선택 |
| GitHub Actions | 간단, Git 통합 | GitHub 종속 | ⚠️ 대안 |
| ArgoCD | GitOps 특화 | CD만 가능 | ⚠️ 보조 도구 |
| GitLab CI | 통합 플랫폼 | GitLab 종속 | ⚠️ 대안 |

**Jenkins 선택 이유**:
1. ✅ **로컬 환경에서 완전 제어** (GitHub Actions는 클라우드)
2. ✅ **Jenkinsfile로 코드화** (Pipeline as Code)
3. ✅ **bespin 프로젝트와 동일** (학습 일관성)
4. ✅ **취업 시장 수요** (많은 회사가 사용)

**Jenkins 동작 원리**:
```
Git Push (블로그 글 작성)
  ↓
GitHub Webhook → Jenkins 트리거
  ↓
Jenkinsfile 실행
  ├─ Stage 1: Checkout (Git Clone)
  ├─ Stage 2: Build (Hugo/Maven)
  ├─ Stage 3: Docker Build
  ├─ Stage 4: Push to Workers
  └─ Stage 5: Deploy to K8s
  ↓
1-2분 후 자동 배포 완료!
```

**결론**: ✅ Jenkins는 "로컬에서도 프로덕션급 CI/CD"를 구축하는 표준 도구

---

### 왜 GitOps인가?

**배포 방식 비교**:

#### 방법 1: 수동 배포 (❌ 비추천)
```
소스 수정
  ↓
수동으로 빌드
  ↓
수동으로 이미지 전송
  ↓
kubectl apply 수동 실행
```

**문제점**:
- ❌ 실수 가능성 높음
- ❌ 버전 추적 어려움
- ❌ 여러 명이 작업 시 충돌

---

#### 방법 2: GitOps 자동화 (✅ 우리 선택)
```
Git Push
  ↓
자동 빌드
  ↓
자동 배포
  ↓
Git 커밋 = 배포 버전 (추적 가능)
```

**GitOps 원칙**:
1. **Single Source of Truth**: Git이 유일한 진실
2. **Declarative**: 원하는 상태 선언 (YAML)
3. **Automated**: 자동 동기화
4. **Auditable**: Git 히스토리로 추적

**결론**: ✅ GitOps는 "Git 커밋 = 배포 버전"으로 만들어 버전 관리와 배포를 통합

---

## 🎯 전체 아키텍처 이해하기

### 레이어별 역할

```
┌─────────────────────────────────────────────────────────┐
│  L7: 사용자                                              │
│  http://192.168.1.187:30080/                            │
└───────────────────┬─────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────────────────┐
│  L6: Ingress Controller (nginx)                         │
│  "Path 기반 라우팅" - / vs /board 구분                   │
└───────────────────┬─────────────────────────────────────┘
                    ↓
        ┌───────────┴───────────┐
        ↓                       ↓
┌───────────────┐       ┌───────────────┐
│  L5: Service  │       │  L5: Service  │
│  web-service  │       │  was-service  │
│  "로드밸런싱"  │       │  "로드밸런싱"  │
└───────┬───────┘       └───────┬───────┘
        ↓                       ↓
┌───────────────┐       ┌───────────────┐
│  L4: Pod      │       │  L4: Pod      │
│  WEB (nginx)  │       │  WAS (Spring) │
│  Hugo 정적파일 │       │  Board CRUD   │
└───────────────┘       └───────┬───────┘
                                ↓
                        ┌───────────────┐
                        │  L3: Service  │
                        │  mysql-service│
                        └───────┬───────┘
                                ↓
                        ┌───────────────┐
                        │  L2: Pod      │
                        │  MySQL 8.0    │
                        └───────┬───────┘
                                ↓
                        ┌───────────────┐
                        │  L1: Storage  │
                        │  Longhorn PVC │
                        │  (영구 저장)   │
                        └───────────────┘
```

**각 레이어가 왜 필요한가?**

| 레이어 | 역할 | 왜 필요? |
|--------|------|----------|
| **Ingress** | Path 라우팅 | 하나의 IP로 여러 서비스 제공 |
| **Service** | 로드밸런싱 | Pod IP가 변경되어도 고정 엔드포인트 |
| **Pod** | 컨테이너 실행 | 앱 실행 단위 (자동 복구) |
| **PVC** | 영구 저장 | Pod 재시작해도 데이터 유지 |

---

## 🚀 단계별 구현 계획

### Phase 0: 환경 준비 (30분)

#### 왜 이 단계가 필요한가?

**목표**: Kubernetes가 "Path 기반 라우팅"을 할 수 있도록 Ingress Controller 설치

**없으면?**
- Ingress 리소스를 생성해도 작동 안 함
- NodePort로만 서비스 노출 (포트 2개 필요)

#### 작업 내용
1. Ingress Controller 설치
2. Namespace 생성
3. 프로젝트 디렉터리 구조 생성

#### 명령어
```bash
# 1. Ingress Controller 설치
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.11.3/deploy/static/provider/baremetal/deploy.yaml

# 2. 확인
kubectl get pods -n ingress-nginx
kubectl get svc -n ingress-nginx

# 3. Namespace 생성
kubectl create namespace blog-system

# 4. 프로젝트 디렉터리 생성
mkdir -p /home/jimin/blog-k8s-project/{web/k8s,was/k8s,mysql/k8s,ingress,jenkins}
```

#### 검증
- [ ] Ingress Controller Pod가 Running
- [ ] Ingress Controller Service에 NodePort 할당 (예: 30080)
- [ ] blog-system Namespace 생성 확인

---

### Phase 1: Hugo 블로그 Pod 배포 (1시간)

#### 왜 이 단계가 필요한가?

**목표**: Hugo 소스 → Docker 이미지 → K8s Pod로 전환

**왜 이 순서인가?**
1. MySQL보다 먼저 배포 (WEB은 DB 의존성 없음)
2. WAS보다 단순 (정적 파일 서빙만)
3. 먼저 성공해야 자신감 ✅

---

#### 작업 1-1: Dockerfile 작성

**파일**: `/home/jimin/blogsite/Dockerfile`

```dockerfile
# ==============================================================================
# Hugo Blog Dockerfile (Multi-stage Build)
# ==============================================================================

FROM klakegg/hugo:0.146.0-alpine AS builder
WORKDIR /src
COPY . .
RUN hugo --minify --gc

FROM nginx:alpine
COPY --from=builder /src/public /usr/share/nginx/html
RUN echo "OK" > /usr/share/nginx/html/health
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
```

**왜 Multi-stage Build인가?**

```
일반 빌드 (❌):
- Hugo 도구 + 소스 + 빌드 결과 모두 포함
- 이미지 크기: ~500MB

Multi-stage Build (✅):
- Stage 1: Hugo 빌드 (임시)
- Stage 2: 빌드 결과만 복사 (nginx)
- 이미지 크기: ~20MB (25배 작음!)
```

**왜 `klakegg/hugo:0.146.0-alpine`인가?**
- ✅ Hugo 0.146.0과 동일 버전 (로컬 빌드와 일관성)
- ✅ alpine 기반 (작은 크기)
- ✅ 공식 이미지 (신뢰성)

**왜 `nginx:alpine`인가?**
- ✅ 정적 파일 서빙에 최적화
- ✅ 작은 크기 (~20MB)
- ✅ Production-ready (안정적)

**왜 `/usr/share/nginx/html/health`인가?**
- ✅ Kubernetes livenessProbe 용도
- ✅ nginx가 살아있는지 확인
- ✅ 간단한 텍스트 파일로 충분

#### 작업 1-2: K8s Manifest 작성

**파일**: `/home/jimin/blog-k8s-project/web/k8s/deployment.yaml`

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: web
  namespace: blog-system
  labels:
    app: blog
    tier: web
spec:
  replicas: 1
  selector:
    matchLabels:
      app: blog
      tier: web
  template:
    metadata:
      labels:
        app: blog
        tier: web
    spec:
      containers:
      - name: web
        image: blog-web:latest
        imagePullPolicy: Never  # 로컬 이미지
        ports:
        - containerPort: 80
        livenessProbe:
          httpGet:
            path: /health
            port: 80
          initialDelaySeconds: 10
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /health
            port: 80
          initialDelaySeconds: 5
          periodSeconds: 5
        resources:
          requests:
            cpu: 50m
            memory: 64Mi
          limits:
            cpu: 100m
            memory: 128Mi
```

**왜 이 설정인가?**

**1. `imagePullPolicy: Never`**
```
문제: Kubernetes가 Docker Hub에서 이미지를 찾으려 함
  ↓
ErrImagePull 에러 (이미지 없음)

해결: imagePullPolicy: Never
  ↓
로컬 Docker 이미지만 사용 (Worker 노드에서)
```

**2. `replicas: 1`**
- 정적 파일이므로 1개로 충분
- 나중에 HPA로 자동 스케일링 가능
- 리소스 절약

**3. `livenessProbe` vs `readinessProbe`**

| 구분 | 목적 | 실패 시 동작 |
|------|------|------------|
| **livenessProbe** | Pod가 살아있는가? | Pod 재시작 |
| **readinessProbe** | 트래픽 받을 준비됐나? | Service에서 제외 |

**왜 둘 다 필요?**
```
nginx 시작 중...
  ↓
readinessProbe 실패 → Service에서 제외 (트래픽 안 받음)
  ↓
nginx 시작 완료
  ↓
readinessProbe 성공 → Service에 추가 (트래픽 받음)
  ↓
시간이 지나고... nginx 프로세스 죽음
  ↓
livenessProbe 실패 → Pod 재시작 (자동 복구)
```

**4. `initialDelaySeconds` 타이밍**
- livenessProbe: 10초 (nginx 시작 대기)
- readinessProbe: 5초 (더 빨리 트래픽 받기)
- 너무 짧으면 → 시작 중에 실패 → CrashLoopBackOff

**5. Resources 제한**
```
requests (보장):
- cpu: 50m (0.05 Core) - nginx는 가벼움
- memory: 64Mi - 정적 파일만

limits (최대):
- cpu: 100m (0.1 Core) - 급증 시 대비
- memory: 128Mi - OOM 방지
```

**왜 이렇게 작은가?**
- nginx는 매우 효율적 (정적 파일만 서빙)
- Hugo 빌드 결과는 몇 MB 수준
- 과도한 리소스 할당 = 낭비

**파일**: `/home/jimin/blog-k8s-project/web/k8s/service.yaml`

```yaml
apiVersion: v1
kind: Service
metadata:
  name: web-service
  namespace: blog-system
spec:
  selector:
    app: blog
    tier: web
  ports:
  - protocol: TCP
    port: 80
    targetPort: 80
  type: ClusterIP
```

#### 작업 1-3: 빌드 및 배포

```bash
# 1. Docker 이미지 빌드
cd /home/jimin/blogsite
docker build -t blog-web:v1 .
docker tag blog-web:v1 blog-web:latest

# 2. Worker 노드로 이미지 전송
docker save blog-web:v1 | ssh k8s-worker1 docker load
docker save blog-web:v1 | ssh k8s-worker2 docker load

# 3. K8s 배포
kubectl apply -f /home/jimin/blog-k8s-project/web/k8s/deployment.yaml
kubectl apply -f /home/jimin/blog-k8s-project/web/k8s/service.yaml

# 4. 확인
kubectl get pods -n blog-system
kubectl get svc -n blog-system
```

#### 검증
- [ ] WEB Pod가 Running 상태
- [ ] web-service가 생성되고 ClusterIP 할당
- [ ] Pod 내부에서 `/health` 접근 시 "OK" 반환

---

### Phase 2: Spring Boot WAS 개발 및 배포 (3-4시간)

#### 왜 이 단계가 필요한가?

**목표**: 게시판 CRUD 기능을 갖춘 Spring Boot 애플리케이션 개발 및 Pod 배포

**왜 WEB 다음인가?**
1. ✅ WEB이 먼저 성공 → 기본 배포 패턴 이해
2. ✅ WAS는 더 복잡 (DB 연결, 환경변수, ConfigMap)
3. ✅ 단계적 학습

**Phase 2의 핵심 학습**:
- Spring Boot 애플리케이션 컨테이너화
- ConfigMap으로 환경변수 관리
- Secret으로 비밀번호 관리
- JPA로 MySQL 연결

---

#### 작업 2-1: Spring Boot 프로젝트 생성

```bash
# Spring Initializr로 프로젝트 생성
curl https://start.spring.io/starter.zip \
  -d dependencies=web,data-jpa,mysql,lombok,validation,actuator \
  -d type=maven-project \
  -d language=java \
  -d bootVersion=3.2.1 \
  -d groupId=com.jimin \
  -d artifactId=board \
  -d name=Board \
  -d packageName=com.jimin.board \
  -d javaVersion=17 \
  -o board.zip

# 압축 해제
unzip board.zip -d /home/jimin/blog-k8s-project/was
cd /home/jimin/blog-k8s-project/was
```

#### 작업 2-2: 기본 기능 구현

**구현할 기능**:
1. 게시글 Entity (Post)
2. Repository (JpaRepository)
3. Service (CRUD)
4. Controller (REST API)
5. application.yml (DB 설정)

**최소 API**:
- `GET /api/posts` - 게시글 목록
- `GET /api/posts/{id}` - 게시글 상세
- `POST /api/posts` - 게시글 작성
- `PUT /api/posts/{id}` - 게시글 수정
- `DELETE /api/posts/{id}` - 게시글 삭제

#### 작업 2-3: Dockerfile 작성

**파일**: `/home/jimin/blog-k8s-project/was/Dockerfile`

```dockerfile
# ==============================================================================
# Spring Boot Board Dockerfile (Multi-stage Build)
# ==============================================================================

FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN ./mvnw clean package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**왜 Multi-stage Build인가?**

```
일반 빌드 (❌):
- JDK (Java 개발 도구) + Maven + 소스 + JAR
- 이미지 크기: ~400MB

Multi-stage Build (✅):
- Stage 1: JDK로 빌드 (임시)
- Stage 2: JRE로 실행 (JAR만 복사)
- 이미지 크기: ~150MB (3배 작음!)
```

**왜 JDK → JRE인가?**

| Stage | 이미지 | 용도 | 크기 |
|-------|--------|------|------|
| **Builder** | eclipse-temurin:17-jdk | 컴파일 필요 (javac, Maven) | ~300MB |
| **Runtime** | eclipse-temurin:17-jre | 실행만 (java 명령만) | ~150MB |

**왜 alpine인가?**
- ✅ 작은 크기 (일반 리눅스 대비 1/5)
- ✅ 보안 취약점 적음
- ✅ Production 표준

**왜 `-DskipTests`인가?**
- Docker 빌드 시 테스트 스킵 (빌드 시간 단축)
- CI/CD 파이프라인에서 테스트 실행 (별도 단계)

#### 작업 2-4: K8s Manifest 작성

**파일**: `/home/jimin/blog-k8s-project/was/k8s/configmap.yaml`

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: was-config
  namespace: blog-system
data:
  SPRING_DATASOURCE_URL: jdbc:mysql://mysql-service:3306/board
  SPRING_DATASOURCE_USERNAME: root
```

**왜 ConfigMap인가?**

**ConfigMap vs Secret vs 하드코딩**

| 방법 | 보안 | 변경 용이성 | 용도 |
|------|------|------------|------|
| **하드코딩** | ❌ 코드에 노출 | ❌ 재빌드 필요 | 절대 사용 금지 |
| **ConfigMap** | ⚠️ Base64 (약한 암호화) | ✅ 재시작만 | DB URL, Username |
| **Secret** | ✅ 암호화 | ✅ 재시작만 | 비밀번호, 토큰 |

**왜 DB URL과 Username은 ConfigMap인가?**
```
민감하지 않은 정보:
- jdbc:mysql://mysql-service:3306/board (누구나 알 수 있음)
- root (기본 사용자명)

→ ConfigMap으로 충분 (Secret 낭비 방지)

민감한 정보:
- rootpassword (비밀번호)

→ Secret 사용 필수
```

**왜 `mysql-service`인가?**
```
Kubernetes Service Discovery:

Pod → Pod IP 직접 접근 (❌)
  └─ Pod IP는 변경됨 (재시작 시)

Pod → Service DNS (✅)
  └─ mysql-service.blog-system.svc.cluster.local
  └─ 같은 Namespace면 mysql-service로 충분
  └─ Service가 알아서 Pod IP로 라우팅
```

---

**파일**: `/home/jimin/blog-k8s-project/was/k8s/deployment.yaml`

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: was
  namespace: blog-system
spec:
  replicas: 1
  selector:
    matchLabels:
      app: blog
      tier: was
  template:
    metadata:
      labels:
        app: blog
        tier: was
    spec:
      containers:
      - name: was
        image: board-was:latest
        imagePullPolicy: Never
        ports:
        - containerPort: 8080
        env:
        - name: SPRING_DATASOURCE_URL
          valueFrom:
            configMapKeyRef:
              name: was-config
              key: SPRING_DATASOURCE_URL
        - name: SPRING_DATASOURCE_USERNAME
          valueFrom:
            configMapKeyRef:
              name: was-config
              key: SPRING_DATASOURCE_USERNAME
        - name: SPRING_DATASOURCE_PASSWORD
          valueFrom:
            secretKeyRef:
              name: mysql-secret
              key: mysql-root-password
        livenessProbe:
          httpGet:
            path: /actuator/health
            port: 8080
          initialDelaySeconds: 60
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /actuator/health
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 5
        resources:
          requests:
            cpu: 250m
            memory: 512Mi
          limits:
            cpu: 500m
            memory: 1Gi
```

**왜 이 설정인가?**

**1. 환경변수 주입 방식**

```yaml
# ConfigMap에서 가져오기
- name: SPRING_DATASOURCE_URL
  valueFrom:
    configMapKeyRef:
      name: was-config
      key: SPRING_DATASOURCE_URL

# Secret에서 가져오기
- name: SPRING_DATASOURCE_PASSWORD
  valueFrom:
    secretKeyRef:
      name: mysql-secret
      key: mysql-root-password
```

**왜 이렇게 복잡하게?**
```
하드코딩 (❌):
env:
  - name: SPRING_DATASOURCE_PASSWORD
    value: "rootpassword"  # Git에 노출!

ConfigMap/Secret (✅):
- Git에는 참조만 저장 (name: mysql-secret)
- 실제 값은 Kubernetes에만 저장
- 코드와 설정 분리
```

**2. `initialDelaySeconds` 타이밍 차이**

| Pod | initialDelaySeconds | 이유 |
|-----|-------------------|------|
| **WEB (nginx)** | 10초 | nginx는 즉시 시작 (~1초) |
| **WAS (Spring Boot)** | 60초 | Spring Boot는 느림 (~30-50초) |

**왜 Spring Boot가 느린가?**
```
Spring Boot 시작 과정:
1. JVM 초기화 (5초)
2. Spring Context 로딩 (10초)
3. Bean 생성 (10초)
4. DB 연결 풀 초기화 (5초)
5. Hibernate 스키마 검증 (10초)
→ 총 40-50초

initialDelaySeconds를 10초로 설정하면?
→ Spring Boot 시작 전에 Probe 실패
→ CrashLoopBackOff
```

**3. Resources 차이**

| 리소스 | WEB (nginx) | WAS (Spring Boot) | 비율 |
|--------|-------------|------------------|------|
| **CPU requests** | 50m | 250m | **5배** |
| **CPU limits** | 100m | 500m | **5배** |
| **Memory requests** | 64Mi | 512Mi | **8배** |
| **Memory limits** | 128Mi | 1Gi | **8배** |

**왜 이렇게 큰 차이?**

```
nginx (정적 파일):
- 단순 파일 서빙
- 메모리에 파일 캐시만
- CPU/Memory 사용 극소

Spring Boot (동적 처리):
- JVM Heap (256-512MB)
- Bean 객체들 (수십 MB)
- DB 연결 풀 (10-20 connections)
- JPA 캐시 (2nd level cache)
- HTTP 요청 처리 스레드 (200개)
→ 훨씬 많은 리소스 필요
```

**4. `/actuator/health` 엔드포인트**

**왜 `/health`가 아니고 `/actuator/health`인가?**
```
Spring Boot Actuator:
- /actuator/health → 헬스체크
- /actuator/metrics → 메트릭
- /actuator/info → 앱 정보

기본 경로가 /actuator/*
(보안상 별도 경로 사용)
```

---

**파일**: `/home/jimin/blog-k8s-project/was/k8s/service.yaml`

```yaml
apiVersion: v1
kind: Service
metadata:
  name: was-service
  namespace: blog-system
spec:
  selector:
    app: blog
    tier: was
  ports:
  - protocol: TCP
    port: 8080
    targetPort: 8080
  type: ClusterIP
```

---

### Phase 3: MySQL 배포 (30분)

#### 왜 이 단계가 필요한가?

**목표**: WAS가 연결할 MySQL 데이터베이스를 Kubernetes에 배포

**왜 WAS 다음인가?**
- ❌ 잘못된 순서: MySQL 먼저 → WAS가 뭘 저장할지 모름
- ✅ 올바른 순서: WAS 개발 → DB 스키마 확정 → MySQL 배포

**Phase 3의 핵심 학습**:
- PVC (PersistentVolumeClaim)로 영구 저장
- Secret으로 비밀번호 관리
- Longhorn 분산 스토리지 활용
- StatefulSet vs Deployment 차이

**이 단계 완료 후**:
- WAS Pod가 MySQL에 연결 가능
- 데이터 영구 저장 (Pod 재시작해도 유지)

---

#### 작업 3-1: K8s Manifest 작성

**파일**: `/home/jimin/blog-k8s-project/mysql/k8s/secret.yaml`

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: mysql-secret
  namespace: blog-system
type: Opaque
stringData:
  mysql-root-password: rootpassword
  mysql-password: boardpassword
```

**왜 Secret인가?**

**Secret vs ConfigMap**
```
ConfigMap:
- 일반 텍스트 (kubectl get configmap -o yaml로 보임)
- 용도: DB URL, Username 등

Secret:
- Base64 인코딩 (kubectl get secret으로 안 보임)
- etcd에 암호화 저장
- 용도: 비밀번호, API Key, 토큰
```

**왜 `stringData`인가?**
```yaml
# data (❌ 복잡):
data:
  mysql-root-password: cm9vdHBhc3N3b3Jk  # Base64 인코딩 필요

# stringData (✅ 편함):
stringData:
  mysql-root-password: rootpassword  # 평문 작성, 자동 Base64 변환
```

**⚠️ 주의**:
- Production에서는 `rootpassword` 같은 단순 비밀번호 금지
- Vault, AWS Secrets Manager 등 외부 Secret 관리 도구 사용 권장

---

**파일**: `/home/jimin/blog-k8s-project/mysql/k8s/pvc.yaml`

```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: mysql-pvc
  namespace: blog-system
spec:
  accessModes:
  - ReadWriteOnce
  storageClassName: longhorn
  resources:
    requests:
      storage: 5Gi
```

**왜 PVC인가?**

**저장소 비교: emptyDir vs PVC**

| 저장소 | 데이터 유지 | 용도 |
|--------|------------|------|
| **emptyDir** | ❌ Pod 재시작 시 삭제 | 임시 캐시, 로그 |
| **hostPath** | ⚠️ 특정 Node에만 | 테스트용 |
| **PVC** | ✅ Pod 재시작해도 유지 | 데이터베이스, 파일 |

```
emptyDir 사용 시 (❌):
MySQL Pod 생성 → 데이터 저장
  ↓
Pod 재시작 (장애, 업데이트 등)
  ↓
데이터 모두 삭제! → 복구 불가

PVC 사용 시 (✅):
MySQL Pod 생성 → PVC 마운트 → 데이터 저장
  ↓
Pod 재시작
  ↓
같은 PVC 다시 마운트 → 데이터 그대로 유지
```

---

**왜 `storageClassName: longhorn`인가?**

**StorageClass 비교**

| StorageClass | 제공자 | 특징 | 선택 |
|-------------|--------|------|------|
| **local-path** | Kubernetes 기본 | 단일 노드 | ❌ 노드 장애 시 데이터 손실 |
| **hostPath** | 수동 설정 | 특정 노드만 | ❌ Pod 이동 불가 |
| **longhorn** | Longhorn (설치됨) | 분산 스토리지, 3 replica | ✅ 우리 선택 |
| **AWS EBS** | AWS EKS | 클라우드 전용 | ⚠️ 로컬 환경 불가 |

**왜 Longhorn이 좋은가?**
```
Longhorn 동작 원리:

데이터 작성 → Longhorn Controller
  ↓
자동으로 3개 노드에 복제 (replica=3)
  ├─ k8s-cp
  ├─ k8s-worker1
  └─ k8s-worker2

노드 1개 장애 발생?
  ↓
나머지 2개 노드에서 데이터 제공 (계속 작동)
  ↓
자동으로 새 노드에 replica 생성 (자가 치유)
```

---

**왜 `ReadWriteOnce`인가?**

**AccessMode 비교**

| AccessMode | 약자 | 설명 | 용도 |
|-----------|------|------|------|
| **ReadWriteOnce** | RWO | 한 노드에서만 쓰기 | 데이터베이스 (단일 Pod) |
| **ReadOnlyMany** | ROX | 여러 노드에서 읽기만 | 정적 컨텐츠 |
| **ReadWriteMany** | RWX | 여러 노드에서 쓰기 | 파일 공유 (NFS) |

**왜 MySQL은 RWO인가?**
```
MySQL 특성:
- 데이터 일관성 위해 단일 프로세스만 쓰기
- replica: 1 (Pod 1개만)
- 여러 Pod가 동시에 쓰기 → 데이터 손상

→ ReadWriteOnce로 충분
```

---

**왜 5Gi인가?**
- 게시판 데이터 (텍스트): ~100MB
- 여유 공간: 4.9GB
- 필요 시 `kubectl edit pvc`로 확장 가능

---

**파일**: `/home/jimin/blog-k8s-project/mysql/k8s/deployment.yaml`

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: mysql
  namespace: blog-system
spec:
  replicas: 1
  selector:
    matchLabels:
      app: mysql
  template:
    metadata:
      labels:
        app: mysql
    spec:
      containers:
      - name: mysql
        image: mysql:8.0
        env:
        - name: MYSQL_ROOT_PASSWORD
          valueFrom:
            secretKeyRef:
              name: mysql-secret
              key: mysql-root-password
        - name: MYSQL_DATABASE
          value: board
        ports:
        - containerPort: 3306
        volumeMounts:
        - name: mysql-storage
          mountPath: /var/lib/mysql
      volumes:
      - name: mysql-storage
        persistentVolumeClaim:
          claimName: mysql-pvc
```

**왜 Deployment인가? (StatefulSet vs Deployment)**

**StatefulSet vs Deployment 비교**

| 특징 | StatefulSet | Deployment | MySQL 선택 |
|------|------------|------------|-----------|
| **Pod 이름** | mysql-0, mysql-1 (고정) | mysql-abc123 (랜덤) | ⚠️ 둘 다 가능 |
| **PVC 관리** | 자동 생성 (volumeClaimTemplate) | 수동 생성 | ⚠️ 둘 다 가능 |
| **순서 보장** | 순차 시작/종료 | 동시 시작/종료 | ⚠️ 단일 Pod라 무관 |
| **복잡도** | 높음 | 낮음 | ✅ **Deployment (단순함)** |

**언제 StatefulSet을 사용하나?**
```
StatefulSet이 필요한 경우:
1. MySQL Replica (Master-Slave)
   - mysql-0 (Master), mysql-1 (Slave), mysql-2 (Slave)
   - 각각 다른 PVC 필요
   - 순서대로 시작 (Master → Slave)

2. MongoDB Replica Set
3. Kafka Cluster
4. Elasticsearch Cluster

→ 여러 Pod가 각각 다른 역할/데이터
```

**왜 우리는 Deployment인가?**
```
우리 상황:
- MySQL 단일 Pod (replica: 1)
- PVC 1개만 사용
- 순서 무관 (Pod 1개만)

→ StatefulSet의 복잡성 불필요
→ Deployment로 충분
```

**⚠️ Production에서는?**
```
Production 권장:
- AWS RDS (완전 관리형)
- MySQL StatefulSet (Master-Slave)
- External DB (Kubernetes 외부)

로컬 학습 환경:
- Deployment + PVC로 충분 ✅
```

---

**파일**: `/home/jimin/blog-k8s-project/mysql/k8s/service.yaml`

```yaml
apiVersion: v1
kind: Service
metadata:
  name: mysql-service
  namespace: blog-system
spec:
  selector:
    app: mysql
  ports:
  - protocol: TCP
    port: 3306
    targetPort: 3306
  type: ClusterIP
```

#### 작업 3-2: 배포

```bash
cd /home/jimin/blog-k8s-project/mysql/k8s
kubectl apply -f secret.yaml
kubectl apply -f pvc.yaml
kubectl apply -f deployment.yaml
kubectl apply -f service.yaml

# 확인
kubectl get pvc -n blog-system
kubectl get pods -n blog-system
```

#### 검증
- [ ] PVC가 Bound 상태
- [ ] MySQL Pod가 Running
- [ ] mysql-service 생성 확인

---

### Phase 4: Ingress 설정 (30분)

#### 왜 이 단계가 필요한가?

**목표**: WEB과 WAS를 하나의 IP/도메인으로 통합

**왜 마지막 단계인가?**
1. ✅ WEB, WAS, MySQL 모두 준비 완료
2. ✅ 이제 외부 접속 경로만 설정
3. ✅ 전체 시스템 통합 테스트

**Phase 4의 핵심 학습**:
- Path-based Routing (`/` vs `/board`)
- Ingress Annotations (rewrite-target)
- IngressClass 지정
- Service 연결

**이 단계 완료 후**:
```
http://192.168.1.187:30080/
  → Hugo 블로그

http://192.168.1.187:30080/board
  → Spring Boot Board

http://192.168.1.187:30080/api/posts
  → REST API
```

---

#### 작업 4-1: Ingress Manifest 작성

**파일**: `/home/jimin/blog-k8s-project/ingress/ingress.yaml`

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: blog-ingress
  namespace: blog-system
  annotations:
    nginx.ingress.kubernetes.io/rewrite-target: /
spec:
  ingressClassName: nginx
  rules:
  - http:
      paths:
      # Hugo 블로그
      - path: /
        pathType: Prefix
        backend:
          service:
            name: web-service
            port:
              number: 80
      # Spring Boot Board
      - path: /board
        pathType: Prefix
        backend:
          service:
            name: was-service
            port:
              number: 8080
      # REST API
      - path: /api
        pathType: Prefix
        backend:
          service:
            name: was-service
            port:
              number: 8080
```

**왜 이 설정인가?**

**1. `nginx.ingress.kubernetes.io/rewrite-target: /`**

**문제 상황**:
```
사용자가 /board/posts 요청
  ↓
Ingress가 was-service로 전달
  ↓
WAS는 /board/posts를 처리하려 함
  ↓
❌ 404 에러 (WAS는 /posts만 알고 있음)
```

**해결: rewrite-target**
```
사용자: /board/posts
  ↓
Ingress: /board 제거
  ↓
WAS에게: /posts 전달
  ↓
✅ 정상 처리
```

**2. Path 순서의 중요성**

```yaml
# ❌ 잘못된 순서:
paths:
  - path: /          # 가장 먼저 매칭 (모든 요청)
  - path: /board     # 도달 불가!
  - path: /api       # 도달 불가!

# ✅ 올바른 순서:
paths:
  - path: /api       # 가장 구체적 (먼저 확인)
  - path: /board     # 그 다음
  - path: /          # 마지막 (catch-all)
```

**Kubernetes는 위에서 아래로 순차 매칭**:
1. `/api/posts` 요청 → `/api` 매칭 → WAS
2. `/board` 요청 → `/board` 매칭 → WAS
3. `/about` 요청 → `/` 매칭 → WEB
4. `/` 요청 → `/` 매칭 → WEB

**3. `pathType: Prefix`**

**PathType 비교**

| PathType | 매칭 방식 | 예시 |
|----------|----------|------|
| **Exact** | 정확히 일치 | `/api`만 (추가 경로 불가) |
| **Prefix** | 시작 일치 | `/api`, `/api/posts`, `/api/users` 모두 |

```
pathType: Exact, path: /board
  ✅ /board        (OK)
  ❌ /board/posts  (매칭 안 됨)

pathType: Prefix, path: /board
  ✅ /board        (OK)
  ✅ /board/posts  (OK)
  ✅ /board/posts/123 (OK)
```

**4. `ingressClassName: nginx`**

**왜 필요한가?**
```
IngressClass 지정 안 하면:
- 어떤 Ingress Controller가 처리할지 모름
- 여러 Ingress Controller 설치 시 충돌

ingressClassName: nginx
- nginx Ingress Controller가 처리
- 다른 Controller (Traefik 등) 무시
```

---

#### 작업 4-2: 배포 및 테스트

```bash
# 1. Ingress 배포
kubectl apply -f /home/jimin/blog-k8s-project/ingress/ingress.yaml

# 2. 확인
kubectl get ingress -n blog-system
kubectl describe ingress blog-ingress -n blog-system

# 3. Ingress Controller NodePort 확인
kubectl get svc -n ingress-nginx

# 4. 접속 테스트
curl http://192.168.1.187:30080/
curl http://192.168.1.187:30080/board
curl http://192.168.1.187:30080/api/posts
```

#### 검증
- [ ] Ingress 리소스 생성 확인
- [ ] `/` → Hugo 블로그 접속
- [ ] `/board` → Spring Boot 접속
- [ ] `/api/posts` → REST API 응답

---

### Phase 5: Jenkins CI/CD 구축 (2-3시간)

#### 왜 이 단계가 필요한가?

**목표**: Git Push → 자동 빌드 → 자동 배포 (GitOps 완성)

**현재 배포 방식의 문제**:
```
❌ 수동 배포:
1. 블로그 글 작성
2. docker build 수동 실행
3. Worker 노드로 이미지 전송 (수동)
4. kubectl set image 수동 실행
5. 배포 확인 (수동)

→ 실수 가능성, 시간 소모, 재현 어려움
```

**Jenkins로 자동화**:
```
✅ 자동 배포:
1. Git Push
2. Jenkins 자동 트리거 (Webhook)
3. 나머지 모두 자동!

→ 1-2분 후 배포 완료
```

**Phase 5의 핵심 학습**:
- Pipeline as Code (Jenkinsfile)
- Multi-stage Pipeline (Checkout, Build, Push, Deploy)
- Docker + kubectl 통합
- GitHub Webhook 연동

**이 단계 완료 후**:
- Git Push만으로 자동 배포
- Netlify/Vercel과 동일한 경험
- 포트폴리오에 CI/CD 경험 추가

---

#### 작업 5-1: Jenkins 배포 (Docker)

```bash
# Jenkins Docker 실행
docker run -d \
  --name jenkins \
  --restart unless-stopped \
  -p 8080:8080 \
  -p 50000:50000 \
  -v jenkins_home:/var/jenkins_home \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v $HOME/.kube:/root/.kube \
  -v $HOME/.ssh:/root/.ssh \
  jenkins/jenkins:lts

# 초기 비밀번호 확인
docker logs jenkins

# Jenkins 접속
# http://192.168.1.187:8080
```

**왜 이 설정인가?**

**1. Docker vs Kubernetes Pod**

| 배포 방식 | 장점 | 단점 | 선택 |
|----------|------|------|------|
| **Docker** | 간단, 빠름 | K8s 외부 | ✅ 초기 구축 |
| **K8s Pod** | K8s 통합, HA | 복잡, 권한 설정 | ⚠️ 나중에 |

**왜 Docker로 시작?**
```
Jenkins in Docker (더 간단):
- docker run 한 줄로 시작
- 호스트의 Docker/kubectl 직접 사용
- 트러블슈팅 쉬움

Jenkins in K8s (더 복잡):
- ServiceAccount 설정 필요
- RBAC 권한 설정
- PVC 설정
- Docker-in-Docker 또는 Kaniko 필요

→ 학습 목적이면 Docker로 충분
→ Production에서는 K8s Pod 권장
```

---

**2. Volume Mount 이유**

```bash
-v jenkins_home:/var/jenkins_home
```
**왜?** Jenkins 설정/히스토리 영구 저장 (컨테이너 재시작해도 유지)

```bash
-v /var/run/docker.sock:/var/run/docker.sock
```
**왜?** Jenkins가 호스트의 Docker 명령 사용 (이미지 빌드 위해)
```
Jenkins Container 내부에서:
docker build ...
  ↓
/var/run/docker.sock 통해 호스트 Docker Daemon 호출
  ↓
호스트에 이미지 생성
```

```bash
-v $HOME/.kube:/root/.kube
```
**왜?** Jenkins가 kubectl 명령 사용 (K8s 배포 위해)
```
Jenkins Container 내부에서:
kubectl set image ...
  ↓
/root/.kube/config 읽음 (클러스터 접속 정보)
  ↓
Kubernetes API 호출
  ↓
K8s Pod 업데이트
```

```bash
-v $HOME/.ssh:/root/.ssh
```
**왜?** Jenkins가 Worker 노드에 SSH 접속 (이미지 전송 위해)
```
Jenkins Container 내부에서:
docker save ... | ssh k8s-worker1 docker load
  ↓
/root/.ssh/id_rsa 사용 (비밀번호 없이 접속)
  ↓
Worker 노드로 이미지 전송
```

---

**3. 포트 8080과 50000**

| 포트 | 용도 | 설명 |
|------|------|------|
| **8080** | Web UI | Jenkins 대시보드 접속 |
| **50000** | Agent 통신 | Jenkins Agent (별도 빌드 머신) 연결 |

우리는 Agent 사용 안 함 → 50000 생략 가능 (하지만 기본 설정)

---

#### 작업 5-2: Jenkins 플러그인 설치

**필수 플러그인**:
- Git Plugin
- Pipeline Plugin
- Docker Plugin
- Kubernetes Plugin

#### 작업 5-3: Jenkinsfile 작성 (Hugo)

**파일**: `/home/jimin/blog-k8s-project/jenkins/Jenkinsfile-web`

```groovy
pipeline {
    agent any
    environment {
        IMAGE_NAME = 'blog-web'
        IMAGE_TAG = "v${BUILD_NUMBER}"
        NAMESPACE = 'blog-system'
        WORKERS = 'k8s-worker1 k8s-worker2'
    }
    stages {
        stage('Checkout') {
            steps {
                git url: 'https://github.com/wlals2/blogsite.git', branch: 'main'
            }
        }
        stage('Build Docker Image') {
            steps {
                sh "docker build -t ${IMAGE_NAME}:${IMAGE_TAG} ."
                sh "docker tag ${IMAGE_NAME}:${IMAGE_TAG} ${IMAGE_NAME}:latest"
            }
        }
        stage('Push to Workers') {
            steps {
                script {
                    env.WORKERS.split().each { worker ->
                        sh "docker save ${IMAGE_NAME}:${IMAGE_TAG} | ssh ${worker} docker load"
                    }
                }
            }
        }
        stage('Deploy to K8s') {
            steps {
                sh "kubectl set image deployment/web web=${IMAGE_NAME}:${IMAGE_TAG} -n ${NAMESPACE}"
                sh "kubectl rollout status deployment/web -n ${NAMESPACE}"
            }
        }
        stage('Health Check') {
            steps {
                sh "sleep 10"
                sh "curl -f http://192.168.1.187:30080/ || exit 1"
            }
        }
    }
    post {
        success {
            echo "✅ Deployment successful!"
        }
        failure {
            echo "❌ Deployment failed!"
        }
    }
}
```

**왜 이 구조인가?**

**1. Pipeline as Code**

**왜 Jenkinsfile인가?**
```
Jenkins UI 설정 (❌):
- Web에서 클릭 클릭
- 버전 관리 안 됨
- 재현 불가

Jenkinsfile (✅):
- Git으로 관리
- 코드 리뷰 가능
- 동일한 파이프라인 재사용
```

---

**2. 환경변수 사용**

```groovy
environment {
    IMAGE_NAME = 'blog-web'
    IMAGE_TAG = "v${BUILD_NUMBER}"
}
```

**왜?**
- `BUILD_NUMBER`: Jenkins가 자동 증가 (1, 2, 3, ...)
- `blog-web:v1`, `blog-web:v2` → 버전 추적 가능
- 하드코딩 방지 (재사용성)

---

**3. Stage 구조**

| Stage | 역할 | 실패 시 |
|-------|------|--------|
| **Checkout** | Git Clone | 빌드 중단 |
| **Build Docker Image** | 이미지 생성 | 빌드 중단 |
| **Push to Workers** | 노드 전송 | 배포 불가 |
| **Deploy to K8s** | Pod 업데이트 | 롤백 |
| **Health Check** | 배포 검증 | 경고 (수동 확인) |

**왜 Stage로 나누나?**
```
장점:
- 어디서 실패했는지 명확
- 실패한 Stage부터 재실행 가능
- 진행 상황 시각화
- 각 Stage별 시간 측정
```

---

**4. `kubectl set image` vs `kubectl apply`**

**왜 `set image`인가?**
```bash
# kubectl apply (❌ 더 복잡):
1. YAML 파일 수정 (image: blog-web:v2)
2. Git Commit
3. kubectl apply -f deployment.yaml

# kubectl set image (✅ 간단):
kubectl set image deployment/web web=blog-web:v2 -n blog-system
→ 한 줄로 이미지 업데이트
```

---

**5. Health Check 이유**

```groovy
stage('Health Check') {
    steps {
        sh "sleep 10"
        sh "curl -f http://192.168.1.187:30080/ || exit 1"
    }
}
```

**왜 필요?**
```
kubectl set image 성공 ≠ 배포 성공

가능한 문제:
- 이미지는 업데이트됐지만 Pod가 CrashLoopBackOff
- Ingress는 정상이지만 Service 연결 실패
- Pod는 Running이지만 앱이 500 에러

→ 실제 HTTP 요청으로 검증 필수
```

---

#### 작업 5-4: Jenkinsfile 작성 (WAS)

**파일**: `/home/jimin/blog-k8s-project/jenkins/Jenkinsfile-was`

```groovy
pipeline {
    agent any
    environment {
        IMAGE_NAME = 'board-was'
        IMAGE_TAG = "v${BUILD_NUMBER}"
        NAMESPACE = 'blog-system'
        WORKERS = 'k8s-worker1 k8s-worker2'
    }
    stages {
        stage('Checkout') {
            steps {
                git url: 'https://github.com/wlals2/board-was.git', branch: 'main'
            }
        }
        stage('Maven Build') {
            steps {
                sh './mvnw clean package -DskipTests'
            }
        }
        stage('Build Docker Image') {
            steps {
                sh "docker build -t ${IMAGE_NAME}:${IMAGE_TAG} ."
                sh "docker tag ${IMAGE_NAME}:${IMAGE_TAG} ${IMAGE_NAME}:latest"
            }
        }
        stage('Push to Workers') {
            steps {
                script {
                    env.WORKERS.split().each { worker ->
                        sh "docker save ${IMAGE_NAME}:${IMAGE_TAG} | ssh ${worker} docker load"
                    }
                }
            }
        }
        stage('Deploy to K8s') {
            steps {
                sh "kubectl set image deployment/was was=${IMAGE_NAME}:${IMAGE_TAG} -n ${NAMESPACE}"
                sh "kubectl rollout status deployment/was -n ${NAMESPACE}"
            }
        }
        stage('Health Check') {
            steps {
                sh "sleep 20"
                sh "curl -f http://192.168.1.187:30080/actuator/health || exit 1"
            }
        }
    }
}
```

---

## ✅ 체크리스트

### Phase 0: 환경 준비
- [ ] Ingress Controller 설치
- [ ] blog-system Namespace 생성
- [ ] 프로젝트 디렉터리 생성

### Phase 1: Hugo 블로그
- [ ] Dockerfile 작성
- [ ] K8s Manifest 작성 (Deployment, Service)
- [ ] Docker 이미지 빌드
- [ ] K8s 배포
- [ ] Pod Running 확인

### Phase 2: Spring Boot WAS
- [ ] Spring Boot 프로젝트 생성
- [ ] CRUD 기능 구현
- [ ] Dockerfile 작성
- [ ] K8s Manifest 작성 (ConfigMap, Deployment, Service)
- [ ] Docker 이미지 빌드
- [ ] K8s 배포

### Phase 3: MySQL
- [ ] Secret, PVC, Deployment, Service 작성
- [ ] K8s 배포
- [ ] PVC Bound 확인
- [ ] MySQL 연결 테스트

### Phase 4: Ingress
- [ ] Ingress Manifest 작성
- [ ] K8s 배포
- [ ] Path Routing 테스트 (`/`, `/board`, `/api`)

### Phase 5: Jenkins
- [ ] Jenkins 배포 (Docker)
- [ ] 플러그인 설치
- [ ] Jenkinsfile 작성 (WEB, WAS)
- [ ] Pipeline 실행 테스트
- [ ] GitHub Webhook 설정 (선택)

---

## 📊 예상 소요 시간

| Phase | 작업 | 예상 시간 |
|-------|------|----------|
| Phase 0 | 환경 준비 | 30분 |
| Phase 1 | Hugo 블로그 | 1시간 |
| Phase 2 | Spring Boot WAS | 3-4시간 |
| Phase 3 | MySQL | 30분 |
| Phase 4 | Ingress | 30분 |
| Phase 5 | Jenkins | 2-3시간 |
| **총합** | | **8-10시간** |

---

## 🎯 성공 기준

### 최소 요구사항
1. ✅ Hugo 블로그 접속 (`http://192.168.1.187:30080/`)
2. ✅ Spring Boot Board 접속 (`http://192.168.1.187:30080/board`)
3. ✅ REST API 동작 (`/api/posts`)
4. ✅ MySQL 데이터 영구 저장 (Pod 재시작 후에도 유지)

### 추가 목표
1. ✅ Jenkins CI/CD 자동 배포
2. ✅ GitHub Webhook 연동
3. ✅ Monitoring (Grafana Dashboard)

---

## 🚨 주의 사항

### Docker 이미지 관리
- **imagePullPolicy: Never** 반드시 설정 (로컬 이미지 사용)
- Worker 노드 2개 모두에 이미지 전송 필요

### Longhorn PVC
- StorageClass: `longhorn` 확인
- PVC가 Pending 상태면 Longhorn 설치 확인

### Ingress Path 충돌
- `/` 경로가 가장 나중에 매칭되도록 순서 주의
- `/board`, `/api`가 `/`보다 먼저 평가되어야 함

### Spring Boot 시작 시간
- livenessProbe initialDelaySeconds: 60초 (충분한 시간 부여)
- 부족하면 Pod가 CrashLoopBackOff

---

## 📝 다음 단계

이 계획을 확인하셨으면:

1. **Phase 0부터 시작** - 한 단계씩 진행
2. **각 Phase 완료 후 검증** - 체크리스트 확인
3. **문제 발생 시** - 해당 Phase 문서 참조

준비되셨으면 "Phase 0 시작"이라고 말씀해주세요!
