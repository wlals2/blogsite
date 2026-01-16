# Blog K8s Skill

> Skill 이름: blog-k8s
> 용도: 블로그 + WAS Kubernetes 프로젝트 운영 및 배포

---

## Skill 개요

이 Skill은 Hugo 블로그와 Spring Boot WAS를 로컬 Kubernetes 클러스터에 배포하고 운영하는 작업을 지원합니다.

---

## 프로젝트 정보 (빠른 참조)

### 환경
- **K8s 클러스터**: 192.168.1.187:6443
- **노드**: k8s-cp, k8s-worker1, k8s-worker2
- **Kubernetes**: v1.31.13
- **CNI**: Cilium
- **Storage**: Longhorn

### 네임스페이스
- `blog-system` (신규 - 블로그 + WAS)
- `monitoring` (기존 - Prometheus + Grafana)

### 접속 정보
- Grafana: http://192.168.1.187:30300
- Prometheus: http://192.168.1.187:30090
- 블로그 (예정): http://192.168.1.187:30080/
- Board (예정): http://192.168.1.187:30080/board

---

## 자주 사용하는 명령어

### Kubernetes 기본

```bash
# 클러스터 상태 확인
kubectl cluster-info
kubectl get nodes

# 네임스페이스 확인
kubectl get namespaces

# Pod 상태 확인
kubectl get pods -n blog-system
kubectl get pods -A

# 리소스 사용량
kubectl top nodes
kubectl top pods -n blog-system
```

### Ingress 관련

```bash
# Ingress Controller 설치
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.11.3/deploy/static/provider/baremetal/deploy.yaml

# Ingress 상태 확인
kubectl get ingressclass
kubectl get pods -n ingress-nginx
kubectl get svc -n ingress-nginx

# Ingress 리소스 확인
kubectl get ingress -n blog-system
kubectl describe ingress blog-ingress -n blog-system
```

### 배포 관련

```bash
# 이미지 빌드 (Hugo 블로그)
cd /home/jimin/blogsite
docker build -t blog-web:v1 .

# 이미지 전송 (Worker 노드)
docker save blog-web:v1 | ssh k8s-worker1 docker load
docker save blog-web:v1 | ssh k8s-worker2 docker load

# K8s 배포
kubectl apply -f k8s/deployment.yaml
kubectl apply -f k8s/service.yaml
kubectl apply -f k8s/ingress.yaml

# 배포 확인
kubectl rollout status deployment/web -n blog-system

# 재시작
kubectl rollout restart deployment/web -n blog-system
```

### 로그 및 디버깅

```bash
# Pod 로그 확인
kubectl logs -f deployment/web -n blog-system
kubectl logs -f deployment/was -n blog-system

# Pod 내부 접속
kubectl exec -it <pod-name> -n blog-system -- /bin/sh

# Ingress Controller 로그
kubectl logs -n ingress-nginx -l app.kubernetes.io/name=ingress-nginx
```

### Storage (Longhorn)

```bash
# PVC 확인
kubectl get pvc -n blog-system

# PV 확인
kubectl get pv

# Longhorn UI 접속 (NodePort)
kubectl get svc -n longhorn-system
```

---

## 배포 워크플로우

### 1. Hugo 블로그 배포

```bash
# 1. 블로그 빌드 및 이미지 생성
cd /home/jimin/blogsite
docker build -t blog-web:v$(date +%Y%m%d%H%M) .

# 2. Worker 노드로 전송
IMAGE_TAG=v$(date +%Y%m%d%H%M)
docker save blog-web:${IMAGE_TAG} | ssh k8s-worker1 docker load
docker save blog-web:${IMAGE_TAG} | ssh k8s-worker2 docker load

# 3. K8s Manifest 업데이트
cd /home/jimin/blog-k8s/web/k8s
kubectl set image deployment/web web=blog-web:${IMAGE_TAG} -n blog-system

# 4. 확인
kubectl get pods -n blog-system
curl http://192.168.1.187:30080/
```

### 2. Spring Boot WAS 배포

```bash
# 1. Maven 빌드
cd /home/jimin/blog-k8s/was
./mvnw clean package -DskipTests

# 2. Docker 이미지 빌드
docker build -t board-was:v$(date +%Y%m%d%H%M) .

# 3. Worker 노드로 전송
IMAGE_TAG=v$(date +%Y%m%d%H%M)
docker save board-was:${IMAGE_TAG} | ssh k8s-worker1 docker load
docker save board-was:${IMAGE_TAG} | ssh k8s-worker2 docker load

# 4. K8s 배포
kubectl set image deployment/was was=board-was:${IMAGE_TAG} -n blog-system

# 5. 확인
kubectl get pods -n blog-system
curl http://192.168.1.187:30080/board
```

---

## Jenkins 파이프라인 (예정)

### Jenkinsfile-web

```groovy
pipeline {
    agent any
    environment {
        IMAGE_NAME = "blog-web"
        IMAGE_TAG = "v${BUILD_NUMBER}"
        WORKERS = "k8s-worker1 k8s-worker2"
    }
    stages {
        stage('Checkout') {
            steps {
                git url: 'https://github.com/wlals2/blogsite.git', branch: 'main'
            }
        }
        stage('Build Docker Image') {
            steps {
                sh 'docker build -t ${IMAGE_NAME}:${IMAGE_TAG} .'
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
                sh 'kubectl set image deployment/web web=${IMAGE_NAME}:${IMAGE_TAG} -n blog-system'
                sh 'kubectl rollout status deployment/web -n blog-system'
            }
        }
    }
}
```

---

## 트러블슈팅 체크리스트

### Ingress 접속 불가

- [ ] Ingress Controller Pod가 Running인가?
- [ ] Service에 NodePort가 할당되었는가?
- [ ] Ingress 리소스가 생성되었는가?
- [ ] Backend Service가 존재하는가?
- [ ] Pod가 Running 상태인가?

### Pod가 Pending 상태

- [ ] 노드 리소스가 충분한가? (`kubectl top nodes`)
- [ ] PVC가 Bound 상태인가?
- [ ] 이미지가 모든 Worker 노드에 있는가?
- [ ] ImagePullPolicy가 올바른가? (`Never` for local images)

### 이미지 Pull 실패

- [ ] 이미지가 로컬에 있는가? (`docker images`)
- [ ] Worker 노드에 이미지가 전송되었는가?
- [ ] ImagePullPolicy가 `Never`로 설정되어 있는가?

---

## 모니터링

### Prometheus 쿼리 (예정)

```promql
# Pod CPU 사용량
sum(rate(container_cpu_usage_seconds_total{namespace="blog-system"}[5m])) by (pod)

# Pod Memory 사용량
sum(container_memory_working_set_bytes{namespace="blog-system"}) by (pod)

# Ingress 요청 수
rate(nginx_ingress_controller_requests[5m])
```

### Grafana Dashboard (예정)

- Blog System Overview
- WAS Performance (JVM, Heap, Thread)
- MySQL Metrics (Connections, Queries)

---

## 참고 자료

### 프로젝트 파일
- Context: `/home/jimin/blogsite/.claude/context.md`
- Hugo 소스: `/home/jimin/blogsite/`
- K8s 프로젝트: `/home/jimin/blog-k8s/`

### 외부 문서
- [Kubernetes Ingress](https://kubernetes.io/docs/concepts/services-networking/ingress/)
- [Hugo Documentation](https://gohugo.io/documentation/)
- [Longhorn Docs](https://longhorn.io/docs/)

---

**마지막 업데이트**: 2026-01-16
