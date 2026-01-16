# Blog K8s Project - 현재 상태

> 최종 업데이트: 2026-01-16
> 상태: ⏳ 계획 단계

---

## 1. 프로젝트 개요

### 목표
로컬 Kubernetes 클러스터에 Hugo 블로그 + Spring Boot WAS를 배포하는 프로젝트

### 프로젝트 정보
- **프로젝트명**: Blog K8s Project
- **도메인**: blog.jiminhome.shop
- **환경**: 로컬 Kubernetes (베어메탈)
- **재사용**: bespin 프로젝트 구조 참고

---

## 2. 현재 Kubernetes 환경

### 클러스터 정보
```
Control Plane:  k8s-cp (192.168.1.187:6443)
Worker Nodes:   k8s-worker1, k8s-worker2
Kubernetes:     v1.31.13
운영 기간:      51일
```

### 설치된 컴포넌트
| 컴포넌트 | 상태 | 네임스페이스 | 접속 정보 |
|---------|------|-------------|----------|
| **CNI** | ✅ Cilium | cilium-secrets | - |
| **Storage** | ✅ Longhorn | longhorn-system | 분산 스토리지 |
| **Monitoring** | ✅ Prometheus + Grafana | monitoring | Grafana: 30300, Prometheus: 30090 |
| **Dashboard** | ✅ Kubernetes Dashboard | kubernetes-dashboard | - |
| **Ingress** | ❌ 미설치 | - | **설치 필요!** |

### 기존 네임스페이스
```
default
kube-system
monitoring
nextcloud
kubernetes-dashboard
longhorn-system
cilium-secrets
local-path-storage
kube-node-lease
kube-public
```

---

## 3. 프로젝트 아키텍처

### 전체 구조

```
┌─────────────────────────────────────────────────────────────────┐
│              Kubernetes Cluster (베어메탈)                       │
│              192.168.1.187:6443                                  │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  Namespace: blog-system (신규 생성 예정)                  │  │
│  │                                                            │  │
│  │  [Ingress Controller] (nginx - 설치 예정)                 │  │
│  │        ↓                                                   │  │
│  │  [Ingress Rules]                                           │  │
│  │     /       → web-service (Hugo 블로그)                   │  │
│  │     /board  → was-service (Spring Boot Board)             │  │
│  │     /api    → was-service (REST API)                      │  │
│  │                                                            │  │
│  │  ┌───────────────┐     ┌───────────────┐                 │  │
│  │  │ WEB Pod       │     │ WAS Pod       │                 │  │
│  │  │ nginx:alpine  │     │ Spring Boot   │                 │  │
│  │  │ Hugo 정적파일  │     │ Board App     │                 │  │
│  │  └───────────────┘     └───────┬───────┘                 │  │
│  │                                 │                         │  │
│  │                        ┌────────▼────────┐                │  │
│  │                        │ MySQL Pod       │                │  │
│  │                        │ mysql:8.0       │                │  │
│  │                        │ PVC: Longhorn   │                │  │
│  │                        └─────────────────┘                │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  Namespace: monitoring (기존)                             │  │
│  │                                                            │  │
│  │  - Grafana:    192.168.1.187:30300                        │  │
│  │  - Prometheus: 192.168.1.187:30090                        │  │
│  └──────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘

외부 접속:
- http://192.168.1.187:30080/       (Ingress NodePort)
- http://blog.jiminhome.shop/       (Cloudflare Tunnel 또는 DNS)
```

---

## 4. 기술 스택

### 계획된 기술 스택

| 레이어 | 기술 | 상태 | 비고 |
|--------|------|------|------|
| **Kubernetes** | K8s v1.31.13 | ✅ 구축됨 | 베어메탈 멀티 노드 |
| **CNI** | Cilium | ✅ 구축됨 | 고성능 네트워킹 |
| **Storage** | Longhorn | ✅ 구축됨 | 분산 스토리지 |
| **Ingress** | nginx-ingress | ❌ 미설치 | **Phase 1** |
| **WEB** | Hugo + nginx:alpine | ⏳ 개발 중 | Hugo v0.146.0 |
| **WAS** | Spring Boot 3.2 | ⏳ 계획 | Board 애플리케이션 |
| **DB** | MySQL 8.0 | ⏳ 계획 | Longhorn PVC 사용 |
| **CI/CD** | Jenkins | ⏳ 계획 | K8s Pod 또는 Docker |
| **Monitoring** | Prometheus + Grafana | ✅ 구축됨 | 기존 사용 |

---

## 5. 디렉터리 구조

### 현재 상태

```
/home/jimin/
├── blogsite/                    # Hugo 블로그 소스
│   ├── config.toml
│   ├── content/
│   ├── public/                  # 빌드 결과
│   └── .claude/
│       ├── context.md           # 이 파일
│       └── skills/
│           └── blog-k8s.md
│
└── blog-k8s/                    # K8s 프로젝트 (생성 예정)
    ├── web/
    │   ├── Dockerfile
    │   └── k8s/
    │       ├── deployment.yaml
    │       ├── service.yaml
    │       └── ingress.yaml
    ├── was/
    │   ├── src/                 # Spring Boot 소스
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
    │       ├── pvc.yaml         # Longhorn
    │       └── secret.yaml
    └── jenkins/
        ├── Jenkinsfile-web
        └── Jenkinsfile-was
```

---

## 6. 구현 계획 (Phase별)

### Phase 1: Ingress Controller 설치 (15분)

**상태**: ⏳ 대기 중

**작업 내용**:
```bash
# nginx Ingress Controller 설치
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.11.3/deploy/static/provider/baremetal/deploy.yaml

# 확인
kubectl get pods -n ingress-nginx
kubectl get svc -n ingress-nginx
```

**검증**:
- Ingress Controller Pod Running
- NodePort 할당 확인 (보통 30080)

---

### Phase 2: Namespace 생성 (5분)

**상태**: ⏳ 대기 중

```bash
kubectl create namespace blog-system
kubectl get namespaces
```

---

### Phase 3: Hugo 블로그 배포 (1시간)

**상태**: ⏳ 대기 중

**작업 내용**:
1. Dockerfile 작성 (Multi-stage Build)
2. Docker 이미지 빌드
3. Worker 노드로 이미지 전송
4. K8s Deployment/Service 작성
5. Ingress 설정 (`/` → WEB)
6. 배포 및 검증

---

### Phase 4: Spring Boot WAS 개발 (3-4시간)

**상태**: ⏳ 대기 중 (WAS 선택 필요)

**추천 옵션**:
- Spring Boot Board (게시판 CRUD)
- Spring Boot TODO App
- Spring Boot Bookmark Manager

**기능**:
- JPA + MySQL
- REST API
- Spring Security (선택)
- Swagger UI

---

### Phase 5: MySQL 배포 (30분)

**상태**: ⏳ 대기 중

**작업 내용**:
- Longhorn PVC 생성 (영구 저장)
- MySQL Deployment
- Secret (비밀번호)
- Service (ClusterIP)

---

### Phase 6: Ingress 설정 (30분)

**상태**: ⏳ 대기 중

**Path Routing**:
- `/` → web-service:80
- `/board` → was-service:8080
- `/api` → was-service:8080

---

### Phase 7: Jenkins CI/CD (2-3시간)

**상태**: ⏳ 대기 중

**배포 방식**:
- K8s Pod (권장) - Longhorn PVC 사용
- 또는 Docker Container

**파이프라인**:
- Jenkinsfile-web: Hugo → Docker → K8s
- Jenkinsfile-was: Maven → Docker → K8s

---

## 7. 운영 정보

### 접속 정보

| 서비스 | URL | 포트 |
|--------|-----|------|
| **Grafana** | http://192.168.1.187:30300 | 30300 |
| **Prometheus** | http://192.168.1.187:30090 | 30090 |
| **Hugo 블로그** (예정) | http://192.168.1.187:30080/ | 30080 |
| **Board** (예정) | http://192.168.1.187:30080/board | 30080 |
| **Jenkins** (예정) | http://192.168.1.187:30081 | 30081 |

### 리소스 현황

```bash
# 노드 리소스 확인
kubectl top nodes

# Pod 리소스 확인
kubectl top pods -A

# Longhorn 스토리지 확인
kubectl get pvc -A
```

---

## 8. 참고 프로젝트

### bespin-project (EKS)

기존 bespin 프로젝트 구조를 참고합니다:

| 항목 | bespin (EKS) | 새 프로젝트 (로컬 K8s) |
|------|--------------|----------------------|
| 환경 | AWS EKS | 베어메탈 K8s |
| WEB | nginx (정적) | Hugo 블로그 |
| WAS | PetClinic | Spring Boot Board |
| DB | RDS MySQL | MySQL Pod (Longhorn) |
| CI/CD | Jenkins (Pod) + ArgoCD | Jenkins (Pod) |
| Ingress | ALB Ingress Controller | nginx Ingress |
| 도메인 | www.goupang.shop | blog.jiminhome.shop |

**재사용 가능**:
- Dockerfile 구조
- Jenkinsfile 파이프라인
- K8s Manifest 템플릿
- Ingress Path Routing 패턴

---

## 9. 다음 단계

### 즉시 작업 가능

1. **Phase 1**: Ingress Controller 설치 (승인 필요)
2. **WAS 선택**: Spring Boot 애플리케이션 종류 결정
3. **프로젝트 생성**: `/home/jimin/blog-k8s/` 디렉터리 구조 생성

---

## 10. 트러블슈팅

### Ingress 접속 불가

**증상**: Ingress 배포 후 접속 안 됨

**확인 사항**:
```bash
# Ingress Controller Pod 확인
kubectl get pods -n ingress-nginx

# Service 확인
kubectl get svc -n ingress-nginx

# Ingress 리소스 확인
kubectl get ingress -n blog-system
kubectl describe ingress blog-ingress -n blog-system
```

### 이미지 Pull 실패

**증상**: Worker 노드에서 이미지를 찾을 수 없음

**해결**:
```bash
# 로컬 이미지를 모든 Worker 노드로 전송
docker save blog-web:v1 | ssh k8s-worker1 docker load
docker save blog-web:v1 | ssh k8s-worker2 docker load
```

또는 Local Registry 구축:
```bash
# Control Plane에 Registry 생성
docker run -d -p 5000:5000 --name registry registry:2

# 이미지 Push
docker tag blog-web:v1 192.168.1.187:5000/blog-web:v1
docker push 192.168.1.187:5000/blog-web:v1
```

---

## 11. 관련 문서

- [Hugo 공식 문서](https://gohugo.io/documentation/)
- [Kubernetes Ingress](https://kubernetes.io/docs/concepts/services-networking/ingress/)
- [Longhorn 문서](https://longhorn.io/docs/)
- [Cilium 문서](https://docs.cilium.io/)

---

**마지막 업데이트**: 2026-01-16
**작성자**: Claude Code + Jimin
**프로젝트 상태**: ⏳ Phase 1 대기 중
