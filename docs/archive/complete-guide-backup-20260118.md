# Kubernetes 기반 블로그 + 게시판 플랫폼 완전 가이드

> 최종 업데이트: 2026-01-17
> 작성자: Jimin
> 프로젝트: Hugo Blog + Spring Boot WAS on Kubernetes (Self-hosted)

---

## 📋 목차

1. [프로젝트 개요](#1-프로젝트-개요)
2. [전체 아키텍처](#2-전체-아키텍처)
3. [왜 이 기술 스택인가](#3-왜-이-기술-스택인가)
4. [알아야 할 배경 지식](#4-알아야-할-배경-지식)
5. [구축 과정 (Phase 0-6)](#5-구축-과정-phase-0-6)
6. [트러블슈팅 전체 기록](#6-트러블슈팅-전체-기록)
7. [현재 상태 및 성과](#7-현재-상태-및-성과)
8. [앞으로 할 것들](#8-앞으로-할-것들)
9. [운영 가이드](#9-운영-가이드)

---

## 1. 프로젝트 개요

### 1.1 무엇을 만들었는가

**Hugo 정적 블로그 + Spring Boot 게시판**을 Kubernetes에 배포한 **완전 자동화 플랫폼**

```
사용자
  ↓
CloudFlare CDN (blog.jiminhome.shop)
  ↓
로컬 nginx (SSL 443)
  ↓
Kubernetes Ingress (NodePort 31852)
  ├─ / → Hugo 블로그 (2 pods)
  ├─ /api → Spring Boot API (2 pods)
  └─ /board → Spring Boot 게시판 (2 pods)
       ↓
     MySQL (1 pod)
```

### 1.2 핵심 기능

| 기능 | 설명 | 상태 |
|------|------|------|
| **Hugo 블로그** | 정적 사이트 생성기, Markdown 글 작성 | ✅ 완료 |
| **Spring Boot 게시판** | REST API + 웹 게시판 | ✅ 완료 |
| **MySQL DB** | 게시판 데이터 저장 | ✅ 완료 |
| **Jenkins CI/CD** | Git push → 자동 빌드/배포 | ✅ 완료 |
| **Kubernetes** | 컨테이너 오케스트레이션, 고가용성 | ✅ 완료 |
| **CloudFlare CDN** | 글로벌 캐시, HTTPS | ✅ 완료 |

### 1.3 핵심 목표와 달성 여부

| 목표 | 달성 여부 | 증거 |
|------|----------|------|
| 완전 자동 배포 | ✅ | Git push → 3분 내 반영 |
| 고가용성 (HA) | ✅ | WEB/WAS 각 2개 Pod, 노드 분산 |
| 빠른 빌드 | ✅ | Multi-stage Docker (이미지 90% 감소) |
| 무중단 배포 | ✅ | Rolling Update |
| 비용 절감 | ✅ | Self-hosted (GitHub Actions 무료) |

---

## 2. 전체 아키텍처

### 2.1 계층별 구조 (Layer Architecture)

```
┌───────────────────────────────────────────────────────────────┐
│ L7: CDN & DNS                                                 │
│ CloudFlare (blog.jiminhome.shop)                              │
│ - Global CDN (14ms 응답)                                      │
│ - DDoS Protection                                             │
│ - Auto Purge on Deploy                                        │
└─────────────────────────┬─────────────────────────────────────┘
                          │ HTTPS (443)
┌─────────────────────────▼─────────────────────────────────────┐
│ L6: TLS Termination                                           │
│ nginx (로컬 서버)                                             │
│ - Let's Encrypt SSL 인증서                                    │
│ - CloudFlare 실제 IP 인식                                     │
│ - Reverse Proxy to K8s                                        │
└─────────────────────────┬─────────────────────────────────────┘
                          │ HTTP (31852)
┌─────────────────────────▼─────────────────────────────────────┐
│ L5: Ingress Controller                                        │
│ nginx-ingress (Kubernetes)                                    │
│ - Path-based Routing                                          │
│   • / → web-service                                           │
│   • /api, /board → was-service                                │
└─────────────────────────┬─────────────────────────────────────┘
                          │
         ┌────────────────┴────────────────┐
         │                                  │
┌────────▼───────────┐          ┌──────────▼────────────┐
│ L4: Application    │          │ L4: Application       │
│ Hugo Blog (WEB)    │          │ Spring Boot (WAS)     │
│ - 2 Pods           │          │ - 2 Pods              │
│ - nginx:alpine     │          │ - JDK 17 + Tomcat     │
│ - Static HTML      │          │ - REST API + Web      │
└────────────────────┘          └──────────┬────────────┘
                                           │
                                ┌──────────▼────────────┐
                                │ L3: Database          │
                                │ MySQL 8.0             │
                                │ - 1 Pod               │
                                │ - PersistentVolume    │
                                └───────────────────────┘
```

### 2.2 데이터 흐름 (Data Flow)

#### 블로그 글 조회 (정적 콘텐츠)
```
User
  → CloudFlare CDN (Cache Hit: 14ms 응답) ✅
  ↓ Cache Miss
  → nginx (SSL)
  → K8s Ingress (/)
  → web-service
  → web Pod (nginx)
  → Static HTML (Hugo 빌드 결과)
```

#### 게시판 API 요청 (동적 콘텐츠)
```
User
  → CloudFlare CDN (동적 콘텐츠는 캐시 안 함)
  → nginx (SSL)
  → K8s Ingress (/api)
  → was-service (ClusterIP, Load Balanced)
  → was Pod (Spring Boot)
  → MySQL (3306)
  → Response (JSON)
```

### 2.3 CI/CD 파이프라인

#### WEB (Hugo) 파이프라인
```
Developer
  ↓ 1. Markdown 파일 작성
  ↓ 2. git push origin main
GitHub Repository
  ↓ 3. Webhook (또는 수동 트리거)
Jenkins (localhost:8080)
  ├─ Stage 1: Git Checkout (5초)
  ├─ Stage 2: Docker Build (Multi-stage)
  │   ├─ Builder: Hugo 빌드 (Alpine + Hugo)
  │   └─ Runtime: nginx 서빙
  ├─ Stage 3: Push to GHCR (ghcr.io/wlals2/blog-web:v11)
  ├─ Stage 4: Deploy to K8s (kubectl set image)
  └─ Stage 5: Health Check (curl)
Kubernetes
  └─ Rolling Update (무중단 배포)
     └─ 새 Pod 생성 → Ready → 기존 Pod 종료
```

#### WAS (Spring Boot) 파이프라인
```
Developer
  ↓ 1. Java 코드 수정
  ↓ 2. git push origin main
GitHub Repository
  ↓ 3. Webhook (또는 수동 트리거)
Jenkins
  ├─ Stage 1: Git Checkout
  ├─ Stage 2: Maven Build (./mvnw clean package)
  ├─ Stage 3: Docker Build (Multi-stage)
  │   ├─ Builder: Maven compile + package
  │   └─ Runtime: JDK 17 + JAR
  ├─ Stage 4: Push to GHCR (ghcr.io/wlals2/board-was:v2)
  ├─ Stage 5: Deploy to K8s
  └─ Stage 6: Health Check (curl /api/posts)
Kubernetes
  └─ Rolling Update
```

### 2.4 컴포넌트 간 관계

```
┌─────────────────────────────────────────────────────────────┐
│                     blog-system Namespace                   │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌────────────┐   ┌────────────┐   ┌────────────┐          │
│  │ web Pod 1  │   │ was Pod 1  │   │ mysql Pod  │          │
│  │ nginx      │   │ spring     │   │ mysql:8.0  │          │
│  │ Worker1    │   │ Worker2    │   │ Worker1    │          │
│  └─────┬──────┘   └─────┬──────┘   └─────┬──────┘          │
│        │                │                │                 │
│  ┌─────▼──────┐   ┌─────▼──────┐   ┌─────▼──────┐          │
│  │ web Pod 2  │   │ was Pod 2  │   │            │          │
│  │ nginx      │   │ spring     │   │            │          │
│  │ Worker2    │   │ Worker2    │   │            │          │
│  └─────┬──────┘   └─────┬──────┘   │            │          │
│        │                │           │            │          │
│  ┌─────▼──────────┬─────▼───────────▼──────────┐           │
│  │ web-service    │ was-service    │mysql-svc  │           │
│  │ ClusterIP:80   │ ClusterIP:8080 │ClusterIP  │           │
│  └────────────────┴────────────────┴───────────┘           │
│                                                             │
│  ┌──────────────────────────────────────────────┐           │
│  │            blog-ingress (nginx)              │           │
│  │  / → web-service:80                          │           │
│  │  /api, /board → was-service:8080             │           │
│  └──────────────────────────────────────────────┘           │
│                                                             │
│  ┌──────────────────────────────────────────────┐           │
│  │          ConfigMap & Secret                  │           │
│  │  - was-config (DB URL, Username)             │           │
│  │  - mysql-secret (DB Password)                │           │
│  └──────────────────────────────────────────────┘           │
└─────────────────────────────────────────────────────────────┘
```

---

## 3. 왜 이 기술 스택인가

### 3.1 Hugo (정적 사이트 생성기)

**선택 이유**:
- ✅ **빌드 속도**: Jekyll 대비 10배 빠름 (Go 언어 기반)
- ✅ **단순성**: 템플릿 엔진이 직관적
- ✅ **성능**: 정적 HTML → CDN 캐시 → 초고속 응답
- ✅ **비용**: 동적 서버 불필요 → 서버 비용 $0

**대안과 비교**:
| 도구 | 빌드 속도 | 학습 곡선 | 플러그인 생태계 |
|------|----------|----------|----------------|
| Hugo | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐ |
| Jekyll | ⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ |
| Gatsby | ⭐⭐⭐ | ⭐⭐ | ⭐⭐⭐⭐ |

**트레이드오프**:
- ❌ 플러그인 생태계가 Jekyll보다 작음
- ❌ 복잡한 동적 기능 구현 어려움
- ✅ 하지만 블로그 용도로는 충분 (721 페이지 빌드 5초)

---

### 3.2 Spring Boot (WAS)

**선택 이유**:
- ✅ **익숙함**: Java 기반, Spring 생태계
- ✅ **생산성**: Auto-configuration, Spring Data JPA
- ✅ **안정성**: 프로덕션 환경에서 검증됨
- ✅ **통합**: Actuator (Health Check), Prometheus 메트릭

**왜 Node.js/Python이 아닌가?**:
| 항목 | Spring Boot | Node.js | Python (Flask) |
|------|-------------|---------|----------------|
| 타입 안정성 | ✅ Java (강타입) | ⚠️ TypeScript 필요 | ⚠️ 약타입 |
| ORM | ✅ JPA/Hibernate | Sequelize | SQLAlchemy |
| 생태계 | ✅ 성숙함 | ✅ 성숙함 | ⚠️ 작음 |
| 성능 | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ |

**선택한 이유**: 익숙함 + 안정성 > 약간의 성능 차이

---

### 3.3 Kubernetes (오케스트레이션)

**선택 이유**:
- ✅ **고가용성**: Pod 자동 복구, 노드 분산
- ✅ **무중단 배포**: Rolling Update
- ✅ **스케일링**: HPA (향후 구현 예정)
- ✅ **학습 가치**: 산업 표준 (배우면 어디서든 사용)

**왜 Docker Compose가 아닌가?**:
| 항목 | Kubernetes | Docker Compose |
|------|------------|----------------|
| 고가용성 | ✅ Pod 자동 복구 | ❌ 수동 재시작 |
| 무중단 배포 | ✅ Rolling Update | ❌ 다운타임 발생 |
| 노드 분산 | ✅ Multi-node | ❌ Single-host |
| 학습 곡선 | ⚠️ 높음 | ✅ 낮음 |

**트레이드오프**:
- ❌ 복잡도 높음 (YAML 파일 많음)
- ❌ 초기 학습 비용 높음
- ✅ 하지만 프로덕션 환경에서는 필수

---

### 3.4 Jenkins (CI/CD)

**선택 이유**:
- ✅ **로컬 K8s 접근**: kubectl 직접 실행 가능
- ✅ **무료**: Self-hosted (GitHub Actions 무료 한도 초과 시)
- ✅ **유연성**: Groovy 스크립트로 복잡한 파이프라인 가능

**왜 GitHub Actions Self-Hosted가 아닌가?**:
| 항목 | Jenkins | GitHub Actions Self-Hosted |
|------|---------|----------------------------|
| 파이프라인 시각화 | ✅ Blue Ocean | ⚠️ 웹 UI만 |
| 복잡한 로직 | ✅ Groovy | ⚠️ YAML 제약 |
| 멀티 프로젝트 | ✅ 여러 Job | ⚠️ 여러 Runner 필요 |
| 설정 복잡도 | ⚠️ 높음 | ✅ 낮음 |

**현재 선택**: Jenkins (WEB + WAS 통합 관리)

---

### 3.5 GHCR (Container Registry)

**선택 이유**:
- ✅ **GitHub 통합**: 소스 코드와 이미지가 한 곳에
- ✅ **무료**: Public repo는 무제한
- ✅ **간단한 인증**: GitHub PAT만 있으면 됨

**왜 Docker Hub가 아닌가?**:
| 항목 | GHCR | Docker Hub |
|------|------|-----------|
| 무료 플랜 | ✅ 무제한 (Public) | ⚠️ 6개월 미사용 시 삭제 |
| GitHub 통합 | ✅ 네이티브 | ❌ 별도 계정 |
| 빌드 속도 | ✅ 빠름 | ⚠️ 비슷 |

---

### 3.6 Multi-stage Docker Build

**선택 이유**:
- ✅ **이미지 크기 90% 감소**: 200MB → 20MB
- ✅ **보안**: 빌드 도구가 최종 이미지에 포함 안 됨
- ✅ **빠른 배포**: Pull/Push 시간 단축

**작동 원리**:
```dockerfile
# Stage 1: Builder (Hugo 빌드)
FROM alpine:latest AS builder
RUN apk add hugo tzdata
COPY . .
RUN hugo --minify --gc
# 이 단계 결과: public/ 디렉토리

# Stage 2: Runtime (nginx만)
FROM nginx:alpine
COPY --from=builder /src/public /usr/share/nginx/html
# Hugo 도구는 포함 안 됨! → 이미지 크기 90% 감소
```

**왜 Single-stage가 아닌가?**:
```dockerfile
# Single-stage (나쁜 예)
FROM alpine:latest
RUN apk add hugo nginx  # Hugo + nginx 둘 다 포함
COPY . .
RUN hugo --minify
# 문제: Hugo 바이너리가 최종 이미지에 포함 (불필요)
# 이미지 크기: ~200MB
```

---

## 4. 알아야 할 배경 지식

### 4.1 Kubernetes 핵심 개념

#### Pod (파드)
**정의**: 가장 작은 배포 단위, 1개 이상의 컨테이너를 포함

**왜 필요한가?**:
- 컨테이너만으로는 네트워크, 스토리지 관리가 어려움
- Pod는 컨테이너 + 네트워크 + 볼륨을 묶어서 관리

**실제 예시**:
```yaml
# web Pod
apiVersion: v1
kind: Pod
metadata:
  name: web-795b44bf96-2qbdj
spec:
  containers:
  - name: nginx
    image: ghcr.io/wlals2/blog-web:v11
    ports:
    - containerPort: 80
```

**Pod의 생명주기**:
```
Pending → Running → Succeeded/Failed → Terminated
         ↑                ↓
      Ready?        Health Check
```

---

#### Deployment (배포)
**정의**: Pod를 관리하는 상위 개념, 원하는 상태(Desired State)를 유지

**왜 필요한가?**:
- Pod가 죽으면? → Deployment가 자동으로 새 Pod 생성
- 이미지 업데이트? → Rolling Update로 무중단 배포

**실제 예시**:
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: web
spec:
  replicas: 2  # 2개 Pod 유지
  selector:
    matchLabels:
      app: web
  template:  # Pod 템플릿
    spec:
      containers:
      - name: nginx
        image: ghcr.io/wlals2/blog-web:v11
```

**Rolling Update 동작**:
```
Before:  [Pod v10] [Pod v10]
         ↓ kubectl set image ...
Step 1:  [Pod v10] [Pod v10] [Pod v11 (Creating)]
Step 2:  [Pod v10] [Pod v11 (Running)]
Step 3:  [Pod v11] [Pod v11]
```

---

#### Service (서비스)
**정의**: Pod에 대한 안정적인 네트워크 엔드포인트

**왜 필요한가?**:
- Pod IP는 재시작 시 변경됨 → Service가 고정 IP 제공
- 여러 Pod에 Load Balancing

**Service 타입**:
| 타입 | 용도 | 예시 |
|------|------|------|
| **ClusterIP** | 내부 통신만 | web-service (80) |
| **NodePort** | 외부 접근 가능 | ingress (31852) |
| **LoadBalancer** | 클라우드 LB | AWS ELB (미사용) |

**실제 예시**:
```yaml
apiVersion: v1
kind: Service
metadata:
  name: web-service
spec:
  type: ClusterIP  # 클러스터 내부에서만 접근
  selector:
    app: web  # app=web 라벨을 가진 Pod로 라우팅
  ports:
  - port: 80  # Service 포트
    targetPort: 80  # Pod 포트
```

**Service Discovery**:
```bash
# Pod 내부에서
curl http://web-service.blog-system.svc.cluster.local:80
# → Kubernetes DNS가 자동으로 Service IP로 변환
```

---

#### Ingress (인그레스)
**정의**: L7 (HTTP/HTTPS) 라우팅 규칙

**왜 필요한가?**:
- Service는 L4 (TCP/UDP) 라우팅만 가능
- Path-based, Host-based 라우팅 필요 → Ingress

**실제 예시**:
```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: blog-ingress
spec:
  rules:
  - http:
      paths:
      - path: /  # / 경로
        backend:
          service:
            name: web-service
            port:
              number: 80
      - path: /api  # /api 경로
        backend:
          service:
            name: was-service
            port:
              number: 8080
```

**Ingress Controller**:
- nginx-ingress (이 프로젝트)
- Traefik
- HAProxy

---

#### ConfigMap / Secret
**정의**: 설정 데이터를 Pod와 분리

**왜 필요한가?**:
- 환경변수를 이미지에 하드코딩 → 환경별로 이미지 다시 빌드
- ConfigMap/Secret 사용 → 이미지 재사용 가능

**실제 예시**:
```yaml
# ConfigMap (비밀이 아닌 설정)
apiVersion: v1
kind: ConfigMap
metadata:
  name: was-config
data:
  SPRING_DATASOURCE_URL: "jdbc:mysql://mysql-service:3306/board"
  SPRING_DATASOURCE_USERNAME: "root"

# Secret (비밀 정보)
apiVersion: v1
kind: Secret
metadata:
  name: mysql-secret
type: Opaque
data:
  mysql-root-password: cGFzc3dvcmQxMjM=  # Base64 인코딩
```

**Pod에서 사용**:
```yaml
env:
- name: SPRING_DATASOURCE_URL
  valueFrom:
    configMapKeyRef:
      name: was-config
      key: SPRING_DATASOURCE_URL

- name: SPRING_DATASOURCE_PASSWORD
  valueFrom:
    secretKeyRef:
      name: mysql-secret
      key: mysql-root-password
```

---

### 4.2 Docker 핵심 개념

#### 이미지 vs 컨테이너
**이미지**: 읽기 전용 템플릿 (실행 파일)
**컨테이너**: 이미지 인스턴스 (실행 중인 프로세스)

**비유**:
- 이미지 = 클래스 (Class)
- 컨테이너 = 객체 (Object)

**실제 예시**:
```bash
# 이미지 빌드
docker build -t blog-web:v1 .

# 컨테이너 실행 (이미지 → 컨테이너)
docker run -p 8080:80 blog-web:v1
docker run -p 8081:80 blog-web:v1  # 같은 이미지로 2개 컨테이너
```

---

#### Layer Caching (레이어 캐싱)
**정의**: Docker 이미지는 여러 레이어로 구성, 변경된 레이어만 다시 빌드

**왜 중요한가?**:
- 캐시 없이: 매번 전체 빌드 (40초)
- 캐시 있으면: 변경된 부분만 빌드 (5초)

**실제 예시**:
```dockerfile
# 나쁜 예 (캐시 활용 못함)
FROM alpine:latest
COPY . .  # 소스 전체 복사 → 코드 1줄만 바꿔도 전체 다시 빌드
RUN apk add hugo
RUN hugo --minify

# 좋은 예 (캐시 활용)
FROM alpine:latest
RUN apk add hugo  # 1. 의존성 먼저 (자주 안 바뀜) → 캐시됨
COPY . .  # 2. 소스 나중에 (자주 바뀜)
RUN hugo --minify  # 3. 빌드
```

**Layer 구조**:
```
ghcr.io/wlals2/blog-web:v11
├─ Layer 1: nginx:alpine (Base)  ← 캐시됨
├─ Layer 2: /usr/share/nginx/html  ← 변경됨 (재빌드)
└─ Layer 3: /usr/share/nginx/html/health  ← 변경됨
```

---

### 4.3 Jenkins Pipeline 핵심

#### Declarative Pipeline vs Scripted Pipeline
**Declarative** (선언적): YAML 스타일, 단순
**Scripted** (스크립트형): Groovy 코드, 유연

**이 프로젝트**: Declarative Pipeline

**실제 예시**:
```groovy
pipeline {
    agent any  // 어떤 Agent에서든 실행

    environment {
        IMAGE_NAME = 'ghcr.io/wlals2/blog-web'
        IMAGE_TAG = "v${BUILD_NUMBER}"
    }

    stages {
        stage('Checkout') {
            steps {
                git url: 'https://github.com/wlals2/blogsite.git', branch: 'main'
            }
        }

        stage('Build') {
            steps {
                sh 'docker build -t ${IMAGE_NAME}:${IMAGE_TAG} .'
            }
        }
    }
}
```

---

#### Jenkins Credentials
**정의**: 민감한 정보 (패스워드, 토큰)를 안전하게 저장

**왜 필요한가?**:
```groovy
// 나쁜 예 (절대 하지 말 것!)
sh 'docker login ghcr.io -u wlals2 -p ghp_nhiAUxW...'

// 좋은 예 (Credentials 사용)
withCredentials([usernamePassword(
    credentialsId: 'ghcr-credentials',
    usernameVariable: 'GHCR_USER',
    passwordVariable: 'GHCR_TOKEN'
)]) {
    sh 'echo $GHCR_TOKEN | docker login ghcr.io -u $GHCR_USER --password-stdin'
}
```

---

### 4.4 Hugo 핵심 개념

#### 정적 사이트 생성기 (SSG)
**정의**: Markdown + 템플릿 → HTML 생성

**왜 정적 사이트인가?**:
| 정적 사이트 (Hugo) | 동적 사이트 (WordPress) |
|--------------------|-------------------------|
| ✅ 초고속 (CDN 캐시) | ⚠️ DB 쿼리 필요 |
| ✅ 보안 (공격 벡터 없음) | ⚠️ PHP 취약점 |
| ✅ 비용 $0 | ⚠️ 서버 비용 |
| ❌ 동적 기능 제약 | ✅ 댓글, 검색 등 |

**Hugo 빌드 과정**:
```
Markdown 파일 (content/posts/my-post.md)
  ↓
템플릿 적용 (themes/PaperMod/layouts/)
  ↓
HTML 생성 (public/posts/my-post/index.html)
  ↓
nginx 서빙
```

---

#### Front Matter (메타데이터)
**정의**: Markdown 파일 상단의 YAML 메타데이터

**실제 예시**:
```markdown
---
title: "Kubernetes 완벽 가이드"
date: 2026-01-17
tags: ["kubernetes", "docker"]
---

# 본문 시작
Kubernetes는...
```

**Hugo 템플릿에서 사용**:
```html
<h1>{{ .Title }}</h1>
<time>{{ .Date.Format "2006-01-02" }}</time>
```

---

### 4.5 Spring Boot 핵심

#### Auto-configuration (자동 설정)
**정의**: 의존성을 보고 자동으로 Bean 설정

**예시**:
```xml
<!-- pom.xml에 의존성만 추가 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>

<!-- 자동 설정됨:
    - DataSource
    - EntityManagerFactory
    - TransactionManager
    - JpaRepository
-->
```

---

#### Actuator (Health Check)
**정의**: 프로덕션 환경 모니터링 엔드포인트

**실제 예시**:
```yaml
# application.yml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
```

**Kubernetes에서 사용**:
```yaml
livenessProbe:
  httpGet:
    path: /actuator/health
    port: 8080
  initialDelaySeconds: 60  # Spring Boot 시작 대기
  periodSeconds: 10
```

---

## 5. 구축 과정 (Phase 0-6)

### Phase 0: 사전 준비 (로컬 Kubernetes 클러스터)

**목적**: Kubernetes 클러스터 구축 (Control Plane + Worker Nodes)

#### 5.0.1 환경 정보
```bash
# 노드 구성
k8s-cp       192.168.1.187  Control Plane  2 CPU, 4GB RAM
k8s-worker1  192.168.1.61   Worker         2 CPU, 4GB RAM
k8s-worker2  192.168.1.62   Worker         2 CPU, 4GB RAM

# Kubernetes 버전
v1.31.13
```

#### 5.0.2 Ingress Controller 설치
**왜 필요한가?**: 외부에서 K8s 내부 서비스에 접근하려면 Ingress Controller 필요

```bash
# nginx-ingress 설치
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.8.1/deploy/static/provider/baremetal/deploy.yaml

# NodePort 확인 (31852)
kubectl get svc -n ingress-nginx
# NAME                    TYPE       CLUSTER-IP     EXTERNAL-IP   PORT(S)
# ingress-nginx-controller NodePort  10.96.100.100  <none>        80:31852/TCP,443:31853/TCP
```

**왜 NodePort?**:
- LoadBalancer 타입은 클라우드 환경에서만 가능
- 로컬 환경 → NodePort로 외부 접근

---

### Phase 1: Namespace 및 기본 리소스 생성

**목적**: blog-system Namespace 생성, 리소스 격리

```bash
# Namespace 생성
kubectl create namespace blog-system

# 왜 Namespace?
# - 리소스 격리 (blog-system의 리소스만 관리)
# - RBAC (권한 관리)
# - 리소스 쿼터 (향후 적용 가능)
```

---

### Phase 2: MySQL 구축

**목적**: Spring Boot WAS가 사용할 데이터베이스

#### 5.2.1 MySQL Deployment
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: mysql
  namespace: blog-system
spec:
  replicas: 1  # MySQL은 단일 인스턴스 (StatefulSet 권장, 단순화 위해 Deployment)
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
          value: "board"
        ports:
        - containerPort: 3306
        volumeMounts:
        - name: mysql-storage
          mountPath: /var/lib/mysql
      volumes:
      - name: mysql-storage
        emptyDir: {}  # 간단한 예시 (PersistentVolume 권장)
```

**왜 emptyDir?**:
- PersistentVolume은 설정 복잡
- 개발 환경 → emptyDir (Pod 재시작 시 데이터 유실)
- **향후 개선**: PersistentVolumeClaim 사용

#### 5.2.2 MySQL Secret
```bash
# Secret 생성
kubectl create secret generic mysql-secret \
  --from-literal=mysql-root-password='password123' \
  -n blog-system

# Base64 인코딩 확인
echo -n 'password123' | base64
# cGFzc3dvcmQxMjM=
```

**왜 Secret?**:
- 평문 패스워드를 YAML에 저장 → Git에 노출 위험
- Secret 사용 → Base64 인코딩 (암호화는 아님!)
- **향후 개선**: Sealed Secrets, Vault

#### 5.2.3 MySQL Service
```yaml
apiVersion: v1
kind: Service
metadata:
  name: mysql-service
  namespace: blog-system
spec:
  type: ClusterIP
  selector:
    app: mysql
  ports:
  - port: 3306
    targetPort: 3306
```

**왜 ClusterIP?**:
- MySQL은 외부 접근 불필요 (보안)
- WAS Pod만 접근 → ClusterIP 충분

---

### Phase 3: WEB (Hugo) 구축

**목적**: Hugo 정적 블로그를 Kubernetes에 배포

#### 5.3.1 Dockerfile 작성
```dockerfile
# Multi-stage Build
FROM alpine:latest AS builder
RUN apk add --no-cache hugo tzdata
WORKDIR /src
COPY . .
RUN hugo --minify --gc

FROM nginx:alpine
COPY --from=builder /src/public /usr/share/nginx/html
RUN echo "OK" > /usr/share/nginx/html/health
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
```

**핵심 포인트**:
- `tzdata` 패키지: Asia/Seoul 타임존 지원
- `hugo --minify`: HTML/CSS/JS 압축
- Multi-stage: 이미지 크기 90% 감소

#### 5.3.2 Deployment
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: web
  namespace: blog-system
spec:
  replicas: 2  # 고가용성
  selector:
    matchLabels:
      app: web
  template:
    metadata:
      labels:
        app: web
    spec:
      containers:
      - name: nginx
        image: ghcr.io/wlals2/blog-web:v11
        imagePullPolicy: Always
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
```

**왜 livenessProbe / readinessProbe?**:
- **livenessProbe**: Pod가 살아있는지 (응답 없으면 재시작)
- **readinessProbe**: 트래픽 받을 준비됐는지 (준비 안 되면 Service에서 제외)

#### 5.3.3 Service
```yaml
apiVersion: v1
kind: Service
metadata:
  name: web-service
  namespace: blog-system
spec:
  type: ClusterIP
  selector:
    app: web
  ports:
  - port: 80
    targetPort: 80
```

---

### Phase 4: WAS (Spring Boot) 구축

**목적**: Spring Boot 게시판 API를 Kubernetes에 배포

#### 5.4.1 Dockerfile 작성
```dockerfile
# Multi-stage Build
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app
COPY .mvn .mvn
COPY mvnw .
COPY pom.xml .
RUN ./mvnw dependency:go-offline  # 의존성 캐싱
COPY src ./src
RUN ./mvnw clean package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**핵심 포인트**:
- Maven Wrapper (`./mvnw`): 로컬 Maven 설치 불필요
- `dependency:go-offline`: 의존성 다운로드 캐싱 (빌드 시간 단축)
- JDK (빌드) → JRE (실행): 이미지 크기 감소

#### 5.4.2 ConfigMap (DB 연결 정보)
```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: was-config
  namespace: blog-system
data:
  SPRING_DATASOURCE_URL: "jdbc:mysql://mysql-service:3306/board"
  SPRING_DATASOURCE_USERNAME: "root"
```

**왜 ConfigMap?**:
- 환경별로 DB URL 다름 (개발/프로덕션)
- ConfigMap 분리 → 이미지 재빌드 불필요

#### 5.4.3 Deployment
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: was
  namespace: blog-system
spec:
  replicas: 2  # 고가용성
  selector:
    matchLabels:
      app: was
  template:
    metadata:
      labels:
        app: was
    spec:
      containers:
      - name: spring-boot
        image: ghcr.io/wlals2/board-was:v1
        imagePullPolicy: Always
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
          initialDelaySeconds: 60  # Spring Boot 시작 대기
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /actuator/health
            port: 8080
          initialDelaySeconds: 50
          periodSeconds: 5
      topologySpreadConstraints:
      - maxSkew: 1
        topologyKey: kubernetes.io/hostname
        whenUnsatisfiable: ScheduleAnyway
        labelSelector:
          matchLabels:
            app: was
```

**핵심 포인트**:
- `topologySpreadConstraints`: Pod를 여러 노드에 분산 (고가용성)
- `initialDelaySeconds: 60`: Spring Boot 시작에 시간 소요 (JVM 초기화 등)

---

### Phase 5: Ingress 설정

**목적**: Path-based 라우팅 (/, /api, /board)

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: blog-ingress
  namespace: blog-system
spec:
  ingressClassName: nginx
  rules:
  - http:
      paths:
      - path: /api
        pathType: Prefix
        backend:
          service:
            name: was-service
            port:
              number: 8080
      - path: /board
        pathType: Prefix
        backend:
          service:
            name: was-service
            port:
              number: 8080
      - path: /
        pathType: Prefix
        backend:
          service:
            name: web-service
            port:
              number: 80
```

**라우팅 순서 중요!**:
- `/api` 먼저 → `/` 나중에 (가장 구체적인 경로 우선)
- `/` 가 먼저면 모든 요청이 web-service로 감

**확인**:
```bash
# NodePort 확인
kubectl get svc -n ingress-nginx
# ingress-nginx-controller NodePort 10.96.100.100 <none> 80:31852/TCP

# 테스트
curl http://192.168.1.187:31852/  # → web-service
curl http://192.168.1.187:31852/api/posts  # → was-service
```

---

### Phase 6: Jenkins CI/CD 구축

**목적**: Git push → 자동 빌드/배포

#### 5.6.1 WEB (Hugo) Jenkins Pipeline
[상세 내용은 기존 HUGO-WEB-CICD-SETUP.md 참조]

**핵심 단계**:
```groovy
pipeline {
    agent any
    environment {
        IMAGE_NAME = 'ghcr.io/wlals2/blog-web'
        IMAGE_TAG = "v${BUILD_NUMBER}"
    }
    stages {
        stage('Checkout') { ... }
        stage('Build Docker Image') { ... }
        stage('Push to GHCR') { ... }
        stage('Deploy to K8s') {
            sh """
                kubectl set image deployment/web \
                    nginx=${IMAGE_NAME}:${IMAGE_TAG} \
                    -n blog-system
            """
        }
        stage('Health Check') { ... }
    }
}
```

#### 5.6.2 WAS (Spring Boot) Jenkins Pipeline
**핵심 차이점**: Maven 빌드 추가

```groovy
stage('Maven Build') {
    steps {
        sh """
            chmod +x mvnw
            ./mvnw clean package -DskipTests
        """
    }
}
```

**왜 `-DskipTests`?**:
- 빌드 시간 단축 (테스트는 별도 파이프라인)
- CI/CD는 빠른 배포가 목적

---

### Phase 7: 로컬 nginx 연동 (HTTPS)

**목적**: CloudFlare (HTTPS) → 로컬 nginx → K8s Ingress

#### 5.7.1 nginx 설정
```nginx
# /etc/nginx/sites-available/blog
server {
    listen 80;
    server_name blog.jiminhome.shop;

    location / {
        return 301 https://$host$request_uri;
    }
}

server {
    listen 443 ssl http2;
    server_name blog.jiminhome.shop;

    ssl_certificate     /etc/letsencrypt/live/blog.jiminhome.shop/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/blog.jiminhome.shop/privkey.pem;

    # CloudFlare 실제 IP 인식
    set_real_ip_from 173.245.48.0/20;
    # ... (CloudFlare IP 범위 생략)
    real_ip_header CF-Connecting-IP;

    # Kubernetes Ingress로 프록시
    location / {
        proxy_pass http://192.168.1.187:31852;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";

        proxy_connect_timeout 60s;
        proxy_send_timeout 60s;
        proxy_read_timeout 60s;
    }
}
```

**왜 이렇게?**:
- CloudFlare는 표준 포트 (80, 443)만 프록시
- NodePort (31852)는 CloudFlare 프록시 불가
- 로컬 nginx가 중간에서 변환

**흐름**:
```
CloudFlare (HTTPS:443)
  → nginx (HTTPS:443 → HTTP:31852)
  → K8s Ingress (HTTP:31852)
  → Services
```

---

## 6. 트러블슈팅 전체 기록

### 6.1 Hugo 빌드 에러

#### 문제 1: Timezone 에러
```
Error: failed to init config: invalid timeZone for language "ko": unknown time zone Asia/Seoul
```

**근본 원인**:
- Alpine Linux에 timezone 데이터베이스 없음
- `config.toml`에서 `timeZone = "Asia/Seoul"` 설정했으나 Hugo가 읽을 수 없음

**해결**:
```dockerfile
# Dockerfile
RUN apk add --no-cache hugo tzdata
```

**왜 tzdata?**:
```bash
# Alpine 컨테이너 진입
docker run -it alpine:latest sh

# timezone 데이터 확인
ls /usr/share/zoneinfo/
# (없음!)

# tzdata 설치 후
apk add tzdata
ls /usr/share/zoneinfo/Asia/Seoul
# (존재 ✅)
```

**학습 포인트**:
- Alpine은 경량 이미지 → 기본 패키지 최소화
- timezone 데이터도 포함 안 됨
- 필요한 패키지는 명시적으로 설치

---

#### 문제 2: Git 서브모듈 - 레이아웃 파일 없음
```
WARN found no layout file for "html" for kind "page"
```

**근본 원인**:
- PaperMod 테마가 Git 서브모듈로 관리됨
- `COPY . .`는 서브모듈 내용을 복사하지 않음
- `themes/PaperMod/` 디렉토리가 비어있음

**디버깅 과정**:
```bash
# 로컬에서 확인
ls -la themes/PaperMod/
# .git 파일만 존재 (gitdir: ../../.git/modules/themes/PaperMod)

# Docker 빌드 중 확인
docker run --rm -it <image-id> sh
ls /src/themes/PaperMod/
# 비어있음!
```

**해결 방법 비교**:

**방법 1: Docker 빌드 시 서브모듈 초기화**
```dockerfile
RUN apk add git
RUN git submodule update --init --recursive
# 문제: .git 디렉토리 필요, 빌드 시간 증가
```

**방법 2: 서브모듈을 일반 디렉토리로 변환 ✅ (선택)**
```bash
# 서브모듈 제거
git submodule deinit -f themes/PaperMod
git rm -f themes/PaperMod
rm -rf .git/modules/themes/PaperMod

# 일반 디렉토리로 클론
git clone --depth 1 https://github.com/adityatelange/hugo-PaperMod.git themes/PaperMod
rm -rf themes/PaperMod/.git

# Git에 직접 추가
git add themes/PaperMod
git commit -m "Convert PaperMod from submodule to regular directory"
```

**왜 방법 2?**:
- 단순함 (Docker 빌드 시 git 불필요)
- 빠름 (서브모듈 초기화 시간 절약)
- 재현 가능 (모든 파일이 Git에 포함)

**트레이드오프**:
- ❌ 테마 업데이트 어려움 (수동으로 다시 클론 필요)
- ✅ 버전 고정 (테마 변경으로 인한 호환성 문제 없음)

---

### 6.2 Jenkins 관련 에러

#### 문제 3: Maven Tool 에러
```
Tool type "maven" does not have an install of "Maven-3.9" configured
```

**근본 원인**:
- Jenkinsfile에 `tools { maven 'Maven-3.9' }` 설정
- Jenkins Global Tool Configuration에 Maven 3.9 없음
- 프로젝트는 Maven Wrapper (`./mvnw`) 사용 → 외부 Maven 불필요

**해결**:
```groovy
// Before
tools {
    maven 'Maven-3.9'
}

// After
// Maven Wrapper 사용 (mvnw) - tools 블록 불필요
```

**학습 포인트**:
- Maven Wrapper는 프로젝트에 Maven 내장
- 시스템 Maven 설치 불필요
- tools 블록은 시스템 도구 참조용

---

#### 문제 4: Credentials ID 오타
```
ERROR: Could not find credentials entry with ID 'ghcr-credentials'
```

**근본 원인**:
- Credentials ID가 `ghcr-credentinals` (오타!)
- Jenkinsfile에서 `ghcr-credentials` 참조

**디버깅**:
```bash
# Credentials 확인
sudo cat /var/lib/jenkins/credentials.xml | grep "<id>"
# <id>ghcr-credentinals</id>  ← 오타!
```

**해결**:
```bash
# sed로 직접 수정 (백업 먼저!)
sudo cp /var/lib/jenkins/credentials.xml /var/lib/jenkins/credentials.xml.bak

sudo sed -i 's/<id>ghcr-credentinals<\/id>/<id>ghcr-credentials<\/id>/g' \
    /var/lib/jenkins/credentials.xml

# Jenkins 재시작
sudo systemctl restart jenkins
```

**왜 직접 수정?**:
- Jenkins UI에서 ID 변경 불가 (삭제 후 재생성 필요)
- credentials.xml 직접 수정이 빠름

---

#### 문제 5: EKS vs 로컬 Kubernetes
```
dial tcp: lookup 8C99496E4F5EEF33595FEC273FB4A47F.gr7.ap-northeast-2.eks.amazonaws.com: no such host
```

**근본 원인**:
- Jenkins 사용자(`jenkins`)의 kubeconfig가 EKS 클러스터 설정
- 실제로는 로컬 Kubernetes 사용

**디버깅**:
```bash
# 현재 사용자 (jimin) kubeconfig
kubectl config view
# server: https://192.168.1.187:6443 (로컬 K8s)

# Jenkins 사용자 kubeconfig
sudo -u jenkins kubectl config view
# server: https://8C99...EKS.amazonaws.com (EKS)
```

**해결**:
```bash
# 현재 사용자의 config 복사
sudo mkdir -p /var/lib/jenkins/.kube
sudo cp ~/.kube/config /var/lib/jenkins/.kube/config
sudo chown -R jenkins:jenkins /var/lib/jenkins/.kube

# 테스트
sudo -u jenkins kubectl get nodes
# NAME          STATUS   ROLES           AGE   VERSION
# k8s-cp        Ready    control-plane   52d   v1.31.13
```

**학습 포인트**:
- Jenkins는 `jenkins` 사용자로 실행
- kubectl은 `~/.kube/config` 참조
- Jenkins 사용자 홈: `/var/lib/jenkins/`

---

#### 문제 6: 컨테이너 이름 불일치
```
error: unable to find container named "web"
```

**근본 원인**:
- Deployment 이름: `web`
- 컨테이너 이름: `nginx` (다름!)
- Jenkinsfile에서 Deployment 이름을 컨테이너 이름으로 사용

**디버깅**:
```bash
# Deployment 상세 확인
kubectl get deployment web -n blog-system -o jsonpath='{.spec.template.spec.containers[*].name}'
# nginx  ← 실제 컨테이너 이름!
```

**Jenkinsfile 코드**:
```groovy
// Before (틀림)
kubectl set image deployment/${DEPLOYMENT_NAME} \
    ${DEPLOYMENT_NAME}=${IMAGE_NAME}:${IMAGE_TAG}
    # deployment/web web=ghcr.io/wlals2/blog-web:v10
    # 문제: 컨테이너 이름이 "nginx"인데 "web"으로 지정

// After (맞음)
environment {
    DEPLOYMENT_NAME = 'web'
    CONTAINER_NAME = 'nginx'  // 실제 컨테이너 이름
}

kubectl set image deployment/${DEPLOYMENT_NAME} \
    ${CONTAINER_NAME}=${IMAGE_NAME}:${IMAGE_TAG}
```

**kubectl set image 구문**:
```bash
kubectl set image deployment/<DEPLOYMENT_NAME> <CONTAINER_NAME>=<IMAGE>
                               ^^^^^^^^^^^^^^^^ ^^^^^^^^^^^^^^^
                               Deployment 이름   컨테이너 이름 (다를 수 있음!)
```

**학습 포인트**:
- Deployment 이름 ≠ 컨테이너 이름
- 항상 `kubectl get deployment -o yaml`로 확인

---

### 6.3 GitHub 관련 에러

#### 문제 7: Push Protection (Secret 감지)
```
remote: error: GH013: Repository rule violations found for refs/heads/main.
remote: Push cannot contain secrets
remote: GitHub Personal Access Token
```

**근본 원인**:
- `.env.github` 파일에 PAT (Personal Access Token) 포함
- GitHub Push Protection이 자동 감지

**해결**:
```bash
# 1. Git 캐시에서 제거
git rm --cached blog-k8s-project/web/.env.github

# 2. .gitignore에 추가
echo "*.env*" >> .gitignore
git add .gitignore

# 3. 커밋 수정 (amend)
git commit --amend -m "Remove sensitive files, add .gitignore"

# 4. Force push
git push -f origin main
```

**예방 방법**:
- `.env` 파일은 **절대** 커밋하지 않기
- 프로젝트 시작 시 `.gitignore`에 미리 추가
- Jenkins Credentials 사용 (환경변수)

---

### 6.4 Pod 배포 관련 에러

#### 문제 8: ImagePullBackOff
```
Events:
  Type     Reason     Message
  ----     ------     -------
  Warning  Failed     Failed to pull image "ghcr.io/wlals2/blog-web:v10": pull access denied
```

**근본 원인**:
- GHCR에 이미지가 없음 (Jenkins 빌드 실패)
- 또는 Private 이미지인데 imagePullSecrets 없음

**디버깅**:
```bash
# 이미지 존재 확인
docker pull ghcr.io/wlals2/blog-web:v10
# Error: unauthorized

# GHCR 로그인 후
echo $GHCR_TOKEN | docker login ghcr.io -u wlals2 --password-stdin
docker pull ghcr.io/wlals2/blog-web:v10
# (성공!)
```

**해결 방법 1: Public으로 변경**
```bash
# GitHub → Packages → blog-web → Settings → Change visibility → Public
```

**해결 방법 2: imagePullSecrets 추가**
```bash
# Docker Registry Secret 생성
kubectl create secret docker-registry ghcr-secret \
  --docker-server=ghcr.io \
  --docker-username=wlals2 \
  --docker-password=$GHCR_TOKEN \
  -n blog-system

# Deployment에 추가
spec:
  template:
    spec:
      imagePullSecrets:
      - name: ghcr-secret
      containers:
      - name: nginx
        image: ghcr.io/wlals2/blog-web:v10
```

**선택**: Public으로 변경 (간단함)

---

#### 문제 9: CrashLoopBackOff
```
NAME                   READY   STATUS             RESTARTS   AGE
was-6d84c9d55c-abc12   0/1     CrashLoopBackOff   5          5m
```

**근본 원인**:
- Spring Boot 시작 실패 (DB 연결 실패 등)

**디버깅**:
```bash
# 로그 확인
kubectl logs was-6d84c9d55c-abc12 -n blog-system

# 에러 메시지
com.mysql.cj.jdbc.exceptions.CommunicationsException: Communications link failure
The last packet sent successfully to the server was 0 milliseconds ago.
```

**원인**: MySQL Service가 없음 또는 잘못된 URL

**해결**:
```yaml
# ConfigMap 확인
kubectl get configmap was-config -n blog-system -o yaml

# 올바른 URL
SPRING_DATASOURCE_URL: "jdbc:mysql://mysql-service:3306/board"
                                    ^^^^^^^^^^^^^^
                                    Service 이름 (DNS)
```

**학습 포인트**:
- Kubernetes DNS: `<service-name>.<namespace>.svc.cluster.local`
- 같은 Namespace → `<service-name>`만 써도 됨
- `mysql-service` = `mysql-service.blog-system.svc.cluster.local`

---

## 7. 현재 상태 및 성과

### 7.1 배포 현황

**Namespace: blog-system**
```bash
kubectl get all -n blog-system
```

| 리소스 | 이름 | 상태 | 노드 | 이미지 |
|--------|------|------|------|--------|
| Pod | web-795b44bf96-2qbdj | Running | worker1 | ghcr.io/wlals2/blog-web:v11 |
| Pod | web-795b44bf96-67822 | Running | worker2 | ghcr.io/wlals2/blog-web:v11 |
| Pod | was-5cd686f586-95gp6 | Running | worker2 | ghcr.io/wlals2/board-was:v1 |
| Pod | was-5cd686f586-hqt9j | Running | worker2 | ghcr.io/wlals2/board-was:v1 |
| Pod | mysql-65f4d695d4-w4rrp | Running | worker1 | mysql:8.0 |

**Pod 분산**:
```
k8s-worker1: web (1), mysql (1)
k8s-worker2: web (1), was (2)
```

**고가용성**:
- ✅ WEB: 2개 Pod, 2개 노드 분산
- ✅ WAS: 2개 Pod (현재 worker2에만 있음, topologySpread 설정됨)
- ⚠️ MySQL: 1개 Pod (SPOF - 향후 개선 필요)

---

### 7.2 성능 지표

#### 배포 시간
| 단계 | WEB (Hugo) | WAS (Spring Boot) |
|------|-----------|-------------------|
| Git Checkout | 5초 | 5초 |
| 빌드 (Hugo/Maven) | 5초 | 4분 (Maven) |
| Docker Build | 30초 | 2분 |
| GHCR Push | 20초 | 40초 |
| K8s Deploy | 10초 | 30초 (Rolling Update) |
| Health Check | 10초 | 30초 (Spring Boot 시작) |
| **총 시간** | **~1.5분** | **~8분** |

#### 이미지 크기
| 컴포넌트 | Single-stage | Multi-stage | 감소율 |
|----------|--------------|-------------|--------|
| WEB (Hugo) | ~200MB | ~20MB | **90%** |
| WAS (Spring Boot) | ~400MB | ~180MB | **55%** |

#### 리소스 사용량
```bash
kubectl top pod -n blog-system
```
| Pod | CPU | Memory |
|-----|-----|--------|
| web | 1m | 8Mi |
| was | 100m | 512Mi (JVM) |
| mysql | 50m | 200Mi |

---

### 7.3 가용성 테스트

#### Pod 장애 시나리오
```bash
# Pod 강제 삭제
kubectl delete pod web-795b44bf96-2qbdj -n blog-system

# 즉시 새 Pod 생성됨
kubectl get pods -n blog-system -w
# NAME                   READY   STATUS              RESTARTS   AGE
# web-795b44bf96-2qbdj   1/1     Terminating         0          2h
# web-795b44bf96-xyz45   0/1     ContainerCreating   0          1s
# web-795b44bf96-xyz45   1/1     Running             0          5s
```

**결과**: ✅ 자동 복구 (5초 내)

#### 무중단 배포 테스트
```bash
# 배포 시작
kubectl set image deployment/web nginx=ghcr.io/wlals2/blog-web:v12 -n blog-system

# Pod 상태 확인
kubectl get pods -n blog-system -w
# NAME                   READY   STATUS
# web-795b44bf96-2qbdj   1/1     Running      # 기존 Pod
# web-795b44bf96-67822   1/1     Running      # 기존 Pod
# web-abc123def4-xyz45   0/1     Creating     # 새 Pod 생성
# web-abc123def4-xyz45   1/1     Running      # 새 Pod Ready
# web-795b44bf96-2qbdj   1/1     Terminating  # 기존 Pod 종료
# ...
```

**결과**: ✅ 무중단 배포 (다운타임 0초)

---

### 7.4 비용 분석

| 항목 | 기존 (수동) | 현재 (자동) | 절감 |
|------|------------|------------|------|
| **배포 시간** | 10분 (수동) | 3분 (자동) | **70%** |
| **빌드 서버** | GitHub Actions 무료 | Jenkins (Self-hosted) | **$0** |
| **컨테이너 레지스트리** | Docker Hub | GHCR (Public) | **$0** |
| **클라우드 비용** | AWS EKS | 로컬 K8s | **$75/월 절감** |
| **CDN** | CloudFlare (무료) | CloudFlare (무료) | **$0** |

**총 절감**: $75/월 + 배포 시간 70% 단축

---

## 8. 앞으로 할 것들

### 8.1 우선순위 P0 (필수)

#### 1. MySQL 고가용성 (StatefulSet + PersistentVolume)
**현재 문제**:
- MySQL이 emptyDir 사용 → Pod 재시작 시 데이터 유실
- 단일 Pod → SPOF (Single Point of Failure)

**해결 방법**:
```yaml
# StatefulSet + PersistentVolumeClaim
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: mysql
spec:
  serviceName: "mysql"
  replicas: 1
  volumeClaimTemplates:
  - metadata:
      name: mysql-data
    spec:
      accessModes: [ "ReadWriteOnce" ]
      storageClassName: "local-path"  # 또는 NFS
      resources:
        requests:
          storage: 10Gi
```

**구현 단계**:
1. PersistentVolume 생성 (NFS 또는 local-path-provisioner)
2. StatefulSet으로 변환
3. 기존 데이터 마이그레이션
4. 테스트: Pod 재시작 후 데이터 유지 확인

**예상 시간**: 4시간

---

#### 2. Ingress HTTPS 인증서 (cert-manager)
**현재 문제**:
- 로컬 nginx에서 SSL 처리 → Kubernetes 외부
- Let's Encrypt 인증서 수동 갱신

**해결 방법**:
```yaml
# cert-manager 설치
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.13.0/cert-manager.yaml

# Issuer 생성
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: letsencrypt-prod
spec:
  acme:
    server: https://acme-v02.api.letsencrypt.org/directory
    email: your-email@example.com
    privateKeySecretRef:
      name: letsencrypt-prod
    solvers:
    - http01:
        ingress:
          class: nginx

# Ingress에 TLS 추가
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: blog-ingress
  annotations:
    cert-manager.io/cluster-issuer: "letsencrypt-prod"
spec:
  tls:
  - hosts:
    - blog.jiminhome.shop
    secretName: blog-tls
  rules:
  - host: blog.jiminhome.shop
    http:
      paths:
      - path: /
        backend:
          service:
            name: web-service
            port:
              number: 80
```

**구현 단계**:
1. cert-manager 설치
2. ClusterIssuer 생성
3. Ingress TLS 설정
4. 로컬 nginx → Kubernetes Ingress로 전환 (LoadBalancer 또는 NodePort)

**예상 시간**: 3시간

---

#### 3. Monitoring (Prometheus + Grafana)
**현재 문제**:
- 리소스 사용량 모니터링 없음
- Pod 상태 확인은 kubectl 수동 실행

**해결 방법**:
```yaml
# kube-prometheus-stack 설치 (Helm)
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm install kube-prometheus-stack prometheus-community/kube-prometheus-stack \
  --namespace monitoring --create-namespace

# Grafana 대시보드 접속
kubectl port-forward -n monitoring svc/kube-prometheus-stack-grafana 3000:80
# http://localhost:3000 (admin/prom-operator)
```

**모니터링 메트릭**:
- Pod CPU/Memory 사용량
- Deployment 상태 (Replicas, Ready)
- Ingress Request Count, Latency
- MySQL 연결 수, 쿼리 수

**구현 단계**:
1. kube-prometheus-stack 설치
2. ServiceMonitor 생성 (WAS Actuator 연동)
3. Grafana Dashboard 구성
4. 알림 설정 (Pod Down, High CPU 등)

**예상 시간**: 6시간

---

### 8.2 우선순위 P1 (중요)

#### 4. HPA (Horizontal Pod Autoscaler)
**목적**: CPU/Memory 사용량에 따라 자동 스케일링

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: was-hpa
  namespace: blog-system
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: was
  minReplicas: 2
  maxReplicas: 10
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70  # CPU 70% 초과 시 스케일 아웃
```

**시나리오**:
```
평시: WAS 2개 Pod (CPU 30%)
트래픽 증가: CPU 80% → HPA가 Pod 4개로 증가
트래픽 감소: CPU 50% → HPA가 Pod 2개로 감소
```

**구현 단계**:
1. Metrics Server 설치 (`kubectl apply -f metrics-server.yaml`)
2. HPA 생성 (WAS, WEB)
3. 부하 테스트 (`ab -n 10000 -c 100 http://blog.jiminhome.shop/api/posts`)
4. HPA 동작 확인

**예상 시간**: 2시간

---

#### 5. ArgoCD (GitOps)
**목적**: Git → Kubernetes 자동 동기화

**현재 방식 (Push-based)**:
```
Jenkins → kubectl set image → Kubernetes
```

**GitOps 방식 (Pull-based)**:
```
Git (manifestrepo) → ArgoCD → Kubernetes
```

**장점**:
- ✅ Git이 Single Source of Truth
- ✅ 변경 이력 추적 (Git commit)
- ✅ 롤백 쉬움 (git revert)

**구현 단계**:
1. ArgoCD 설치 (`kubectl apply -n argocd -f install.yaml`)
2. manifestrepo 생성 (deployment.yaml, service.yaml 등)
3. Application 생성 (ArgoCD가 Git → K8s 동기화)
4. Jenkins 파이프라인 수정 (이미지 빌드 → manifestrepo 업데이트)

**예상 시간**: 4시간

---

### 8.3 우선순위 P2 (개선)

#### 6. Namespace별 리소스 쿼터
**목적**: Namespace별로 리소스 제한 (무한 리소스 사용 방지)

```yaml
apiVersion: v1
kind: ResourceQuota
metadata:
  name: blog-system-quota
  namespace: blog-system
spec:
  hard:
    requests.cpu: "4"  # 최대 4 CPU
    requests.memory: 8Gi  # 최대 8GB RAM
    pods: "20"  # 최대 20 Pod
```

---

#### 7. Network Policy (네트워크 격리)
**목적**: Pod 간 통신 제한 (보안)

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: mysql-policy
  namespace: blog-system
spec:
  podSelector:
    matchLabels:
      app: mysql
  policyTypes:
  - Ingress
  ingress:
  - from:
    - podSelector:
        matchLabels:
          app: was  # WAS Pod만 MySQL 접근 허용
    ports:
    - protocol: TCP
      port: 3306
```

---

#### 8. CI/CD 최적화
**목표**: 빌드 시간 단축

**현재 WAS 빌드 시간**: 8분

**최적화 방법**:
1. **Docker Layer Caching**: `pom.xml` 먼저 복사 → 의존성 다운로드 캐싱
   ```dockerfile
   COPY pom.xml .
   RUN ./mvnw dependency:go-offline  # 캐시됨
   COPY src ./src  # 소스는 나중에
   RUN ./mvnw package
   ```
   **예상 효과**: 4분 → 2분

2. **Parallel Builds**: WEB + WAS 동시 빌드
   ```groovy
   parallel {
       stage('Build WEB') { ... }
       stage('Build WAS') { ... }
   }
   ```
   **예상 효과**: 순차 9.5분 → 병렬 8분

3. **Test 분리**: Unit Test만 CI, Integration Test는 Nightly
   ```groovy
   sh './mvnw test'  # Unit Test만 (빠름)
   ```
   **예상 효과**: 30초 단축

---

## 9. 운영 가이드

### 9.1 일상적인 운영 작업

#### 블로그 글 작성 및 배포
```bash
# 1. 로컬에서 Markdown 파일 작성
cd ~/blogsite
vim content/posts/kubernetes-guide.md

# 2. Git push
git add content/posts/kubernetes-guide.md
git commit -m "Add Kubernetes guide"
git push origin main

# 3. Jenkins 자동 빌드 (또는 수동 트리거)
# http://localhost:8080/job/blog-web/build

# 4. 배포 확인 (약 1.5분 후)
curl https://blog.jiminhome.shop/posts/kubernetes-guide/
```

---

#### WAS 코드 수정 및 배포
```bash
# 1. Java 코드 수정
cd ~/board-was
vim src/main/java/com/example/board/controller/PostController.java

# 2. Git push
git add .
git commit -m "Fix API bug"
git push origin main

# 3. Jenkins 자동 빌드 (약 8분)
# http://localhost:8080/job/board-was/build

# 4. 배포 확인
curl https://blog.jiminhome.shop/api/posts | jq
```

---

### 9.2 모니터링 및 상태 확인

#### Pod 상태 확인
```bash
# 전체 Pod 상태
kubectl get pods -n blog-system -o wide

# 특정 Pod 로그
kubectl logs -f was-5cd686f586-95gp6 -n blog-system

# Pod 이벤트 확인
kubectl describe pod was-5cd686f586-95gp6 -n blog-system
```

---

#### 리소스 사용량 확인
```bash
# 노드 리소스
kubectl top nodes

# Pod 리소스
kubectl top pods -n blog-system
```

---

#### Ingress 상태 확인
```bash
# Ingress 정보
kubectl get ingress -n blog-system

# Ingress Controller 로그
kubectl logs -n ingress-nginx -l app.kubernetes.io/component=controller -f
```

---

### 9.3 트러블슈팅 절차

#### Pod가 Running이 아닐 때
```bash
# 1. Pod 상태 확인
kubectl get pods -n blog-system

# 2. Pod 이벤트 확인
kubectl describe pod <pod-name> -n blog-system

# 3. 로그 확인
kubectl logs <pod-name> -n blog-system

# 4. 이전 컨테이너 로그 (CrashLoopBackOff 시)
kubectl logs <pod-name> -n blog-system --previous

# 5. Pod 재시작
kubectl delete pod <pod-name> -n blog-system
```

---

#### 배포 실패 시
```bash
# 1. Deployment 상태 확인
kubectl rollout status deployment/was -n blog-system

# 2. Rollout 히스토리
kubectl rollout history deployment/was -n blog-system

# 3. 이전 버전으로 롤백
kubectl rollout undo deployment/was -n blog-system

# 4. 특정 버전으로 롤백
kubectl rollout undo deployment/was --to-revision=2 -n blog-system
```

---

#### Ingress 접속 안 될 때
```bash
# 1. Ingress 규칙 확인
kubectl describe ingress blog-ingress -n blog-system

# 2. Service 확인
kubectl get svc -n blog-system

# 3. Endpoint 확인 (Service → Pod 연결)
kubectl get endpoints -n blog-system

# 4. nginx-ingress 로그
kubectl logs -n ingress-nginx -l app.kubernetes.io/component=controller --tail=100

# 5. 로컬 nginx 확인 (HTTPS)
sudo nginx -t
sudo systemctl status nginx
```

---

#### MySQL 연결 실패 시
```bash
# 1. MySQL Pod 상태 확인
kubectl get pods -n blog-system -l app=mysql

# 2. MySQL 로그
kubectl logs <mysql-pod> -n blog-system

# 3. MySQL 접속 테스트
kubectl exec -it <was-pod> -n blog-system -- \
  mysql -h mysql-service -u root -p'password123' -D board

# 4. Service DNS 확인
kubectl exec -it <was-pod> -n blog-system -- nslookup mysql-service
```

---

### 9.4 백업 및 복구

#### MySQL 백업
```bash
# 1. MySQL Pod에서 mysqldump
kubectl exec -it mysql-65f4d695d4-w4rrp -n blog-system -- \
  mysqldump -u root -p'password123' board > backup-$(date +%Y%m%d).sql

# 2. 로컬로 복사
kubectl cp blog-system/mysql-65f4d695d4-w4rrp:/backup.sql ./backup.sql
```

---

#### MySQL 복구
```bash
# 1. 백업 파일 복사
kubectl cp ./backup.sql blog-system/mysql-65f4d695d4-w4rrp:/backup.sql

# 2. 복구
kubectl exec -it mysql-65f4d695d4-w4rrp -n blog-system -- \
  mysql -u root -p'password123' board < /backup.sql
```

---

### 9.5 스케일링

#### 수동 스케일링
```bash
# WEB Pod 수 증가
kubectl scale deployment web --replicas=4 -n blog-system

# 확인
kubectl get pods -n blog-system -l app=web
```

---

#### 자동 스케일링 (HPA 구축 후)
```bash
# HPA 상태 확인
kubectl get hpa -n blog-system

# HPA 이벤트 확인
kubectl describe hpa was-hpa -n blog-system
```

---

## 10. 참고 자료

### 10.1 내부 문서
- [HUGO-WEB-CICD-SETUP.md](./HUGO-WEB-CICD-SETUP.md) - Hugo CI/CD 상세 가이드
- [why-self-hosted-runner.md](./why-self-hosted-runner.md) - Self-Hosted Runner 선택 이유
- [QUICK-REFERENCE.md](./QUICK-REFERENCE.md) - 빠른 참조 카드

### 10.2 공식 문서
- Kubernetes: https://kubernetes.io/docs/
- Hugo: https://gohugo.io/documentation/
- Spring Boot: https://spring.io/projects/spring-boot
- Docker: https://docs.docker.com/
- Jenkins: https://www.jenkins.io/doc/

### 10.3 주요 파일 위치
```
/home/jimin/blogsite/
├── Dockerfile (WEB)
├── Jenkinsfile (WEB)
├── config.toml (Hugo 설정)
├── content/ (Markdown 파일)
├── themes/PaperMod/ (Hugo 테마)
└── blog-k8s-project/
    ├── web/k8s/ (WEB Deployment, Service)
    ├── was/
    │   ├── Dockerfile (WAS)
    │   ├── Jenkinsfile (WAS)
    │   ├── pom.xml (Maven)
    │   └── k8s/ (WAS Deployment, Service)
    ├── mysql/k8s/ (MySQL Deployment, Service)
    └── ingress/k8s/ (Ingress)

/etc/nginx/sites-available/blog (로컬 nginx 설정)
/var/lib/jenkins/.kube/config (Jenkins kubeconfig)
```

---

## 11. 요약

### 11.1 핵심 성과

| 지표 | 결과 |
|------|------|
| **배포 자동화** | ✅ Git push → 3분 내 배포 |
| **이미지 크기** | ✅ 90% 감소 (200MB → 20MB) |
| **고가용성** | ✅ WEB/WAS 각 2개 Pod, 노드 분산 |
| **비용 절감** | ✅ $75/월 (EKS → 로컬 K8s) |
| **빌드 시간** | ✅ WEB 1.5분, WAS 8분 |

---

### 11.2 학습한 기술

1. **Kubernetes**: Pod, Deployment, Service, Ingress, ConfigMap, Secret
2. **Docker**: Multi-stage Build, Layer Caching, GHCR
3. **Jenkins**: Declarative Pipeline, Credentials, kubectl 통합
4. **Hugo**: SSG, Front Matter, 테마 관리
5. **Spring Boot**: Actuator, JPA, Health Check
6. **네트워크**: nginx Reverse Proxy, CloudFlare CDN, DNS

---

### 11.3 트러블슈팅 경험

1. Timezone 에러 → tzdata 패키지
2. Git 서브모듈 → 일반 디렉토리로 변환
3. Maven 에러 → Maven Wrapper 사용
4. Credentials 오타 → credentials.xml 직접 수정
5. EKS vs 로컬 K8s → kubeconfig 복사
6. 컨테이너 이름 불일치 → CONTAINER_NAME 변수
7. Push Protection → .gitignore 추가
8. ImagePullBackOff → Public 이미지
9. CrashLoopBackOff → DB URL 수정

---

### 11.4 다음 단계 우선순위

**P0 (필수)**:
1. MySQL 고가용성 (StatefulSet + PV)
2. Ingress HTTPS (cert-manager)
3. Monitoring (Prometheus + Grafana)

**P1 (중요)**:
4. HPA (Auto Scaling)
5. ArgoCD (GitOps)

**P2 (개선)**:
6. ResourceQuota
7. NetworkPolicy
8. CI/CD 최적화

---

**최종 업데이트**: 2026-01-17
**작성자**: Jimin
**검토**: Claude Code (AI Assistant)
**문서 상태**: ✅ 완료

---

## 부록 A: 용어 사전

| 용어 | 설명 |
|------|------|
| **Pod** | Kubernetes의 가장 작은 배포 단위 (1개 이상의 컨테이너) |
| **Deployment** | Pod를 관리하는 상위 개념, 원하는 상태 유지 |
| **Service** | Pod에 대한 안정적인 네트워크 엔드포인트 |
| **Ingress** | L7 (HTTP/HTTPS) 라우팅 규칙 |
| **ConfigMap** | 설정 데이터를 Pod와 분리 (비밀 아님) |
| **Secret** | 민감한 정보 (Base64 인코딩) |
| **Rolling Update** | 무중단 배포 (새 Pod 생성 → 기존 Pod 종료) |
| **SSG** | Static Site Generator (정적 사이트 생성기) |
| **Multi-stage Build** | Docker 이미지 빌드 최적화 (빌드 / 실행 단계 분리) |
| **Layer Caching** | Docker 이미지 레이어 캐싱 (변경된 부분만 재빌드) |

---

## 부록 B: 자주 묻는 질문 (FAQ)

### Q1: 왜 Kubernetes를 로컬에 구축했나요?
**A**: 학습 목적 + 비용 절감. AWS EKS는 $75/월, 로컬은 $0.

### Q2: 왜 Docker Compose가 아닌 Kubernetes인가요?
**A**: 고가용성, 무중단 배포, 노드 분산 등 프로덕션 환경 기능 필요. 학습 가치도 높음.

### Q3: Jenkins vs GitHub Actions?
**A**: 로컬 Kubernetes 직접 접근 가능 (kubectl), 무료 (Self-hosted).

### Q4: Multi-stage Build가 왜 중요한가요?
**A**: 이미지 크기 90% 감소 → Pull/Push 시간 단축, 보안 강화.

### Q5: MySQL이 emptyDir인데 데이터 유실 위험은?
**A**: 맞습니다. P0 우선순위로 PersistentVolume 적용 예정.

### Q6: Hugo를 왜 선택했나요?
**A**: 빌드 속도 (Jekyll 대비 10배), 단순성, 성능 (CDN 캐시).

### Q7: Spring Boot를 왜 선택했나요?
**A**: 익숙함 (Java), 생산성 (Auto-configuration), 안정성.

### Q8: 로컬 nginx가 왜 필요한가요?
**A**: CloudFlare는 표준 포트 (80, 443)만 프록시. NodePort (31852)는 프록시 불가.

### Q9: 앞으로 가장 중요한 개선 사항은?
**A**: MySQL 고가용성 (StatefulSet + PV), Monitoring (Prometheus + Grafana).

### Q10: 이 프로젝트의 가장 큰 학습 포인트는?
**A**: Kubernetes 오케스트레이션, CI/CD 자동화, 트러블슈팅 경험.
