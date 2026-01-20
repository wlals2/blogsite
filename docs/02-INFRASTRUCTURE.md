# 인프라 통합 가이드

> Cloudflare, Kubernetes, GitOps, 모니터링
> 최종 업데이트: 2026-01-20

---

## 목차

1. [Cloudflare CDN](#cloudflare-cdn)
2. [Kubernetes 아키텍처](#kubernetes-아키텍처)
3. [Kubernetes 현재 구성](#kubernetes-현재-구성)
4. [GitOps (ArgoCD)](#gitops-argocd)
5. [향후 개선 계획](#향후-개선-계획)
6. [모니터링](#모니터링)

---

## Cloudflare CDN

### 개요

**Cloudflare**는 전 세계 분산 CDN(Content Delivery Network)으로 다음을 제공합니다:
- ✅ 콘텐츠 캐싱 (응답 속도 개선)
- ✅ DDoS 방어
- ✅ SSL/TLS (무료 인증서)
- ✅ DNS 관리

### 현재 설정

**도메인:** jiminhome.shop
**서브도메인:** blog.jiminhome.shop

**DNS 레코드:**
```
A     blog      192.168.1.187    (Proxied - 주황색 구름)
                                  ⚠️ 현재 로컬 nginx 주소
                                  향후 192.168.1.200 (MetalLB)로 변경 예정
CNAME www       blog.jiminhome.shop (Proxied)
```

**Proxy Status:**
- ✅ Proxied (주황색 구름): Cloudflare CDN 통과
- ⚪ DNS only (회색 구름): 직접 연결

### Cloudflare 캐시 동작

**Before (수동 퍼지):**
```bash
# 콘텐츠 업데이트 후
git push
# → 배포 완료

# 하지만 사용자는 여전히 이전 콘텐츠 봄 (캐시)
curl -I https://blog.jiminhome.shop/ | grep "cf-cache-status"
# cf-cache-status: HIT (캐시된 콘텐츠)

# 수동으로 캐시 퍼지 필요
# Cloudflare 대시보드 → Caching → Purge Everything
```

**After (자동 퍼지):**
```bash
git push
# → GitHub Actions 자동 빌드 + 배포
# → Cloudflare 캐시 자동 퍼지 ✅

curl -I https://blog.jiminhome.shop/ | grep "cf-cache-status"
# cf-cache-status: DYNAMIC (최신 콘텐츠)
```

### 자동 캐시 퍼지 구현

**GitHub Actions에 통합:**
```yaml
# .github/workflows/deploy-web.yml
- name: Purge Cloudflare Cache
  if: success()
  run: |
    curl -X POST "https://api.cloudflare.com/client/v4/zones/${{ secrets.CLOUDFLARE_ZONE_ID }}/purge_cache" \
      -H "Authorization: Bearer ${{ secrets.CLOUDFLARE_API_TOKEN }}" \
      -H "Content-Type: application/json" \
      --data '{"purge_everything":true}'
    echo "Cloudflare cache purged successfully"
```

**필요한 Secrets:**
1. **CLOUDFLARE_ZONE_ID**: `7895fe2aef761351db71892fb7c22b52`
2. **CLOUDFLARE_API_TOKEN**: Cloudflare API Token (Cache Purge 권한)

### Cloudflare API Token 생성

**절차:**
1. Cloudflare 대시보드 로그인
2. **My Profile** → **API Tokens**
3. **Create Token** 클릭
4. Template: "Edit zone DNS" 또는 Custom
5. Permissions:
   - Zone - Cache Purge - Purge
6. Zone Resources:
   - Include - Specific zone - jiminhome.shop
7. **Continue to summary** → **Create Token**
8. Token 복사 (한 번만 표시!)

### Zone ID 조회 방법

**방법 1: Cloudflare 대시보드**
1. jiminhome.shop 도메인 선택
2. 오른쪽 사이드바 하단 "Zone ID" 복사

**방법 2: API**
```bash
curl -X GET "https://api.cloudflare.com/client/v4/zones" \
  -H "Authorization: Bearer <CLOUDFLARE_API_TOKEN>" \
  -H "Content-Type: application/json" \
  | jq -r '.result[] | select(.name=="jiminhome.shop") | .id'

# 출력: 7895fe2aef761351db71892fb7c22b52
```

### 캐시 상태 확인

**1. HTTP 헤더 확인**
```bash
curl -I https://blog.jiminhome.shop/ | grep "cf-cache-status"
```

**가능한 상태:**
- `HIT`: 캐시에서 제공 (빠름)
- `MISS`: 캐시 없음, 원본 서버에서 가져옴
- `EXPIRED`: 캐시 만료, 원본에서 재검증
- `DYNAMIC`: 캐시하지 않음 (항상 최신)
- `BYPASS`: 캐시 우회

**2. 캐시 TTL 확인**
```bash
curl -I https://blog.jiminhome.shop/ | grep "cache-control"
# cache-control: public, max-age=3600
```

### 캐시 퍼지 방법

**1. 전체 퍼지 (현재 사용)**
```bash
curl -X POST "https://api.cloudflare.com/client/v4/zones/7895fe2aef761351db71892fb7c22b52/purge_cache" \
  -H "Authorization: Bearer <CLOUDFLARE_API_TOKEN>" \
  -H "Content-Type: application/json" \
  --data '{"purge_everything":true}'
```

**2. 선택적 퍼지 (파일/태그별)**
```bash
# 특정 URL만 퍼지
curl -X POST "https://api.cloudflare.com/client/v4/zones/7895fe2aef761351db71892fb7c22b52/purge_cache" \
  -H "Authorization: Bearer <CLOUDFLARE_API_TOKEN>" \
  -H "Content-Type: application/json" \
  --data '{"files":["https://blog.jiminhome.shop/index.html","https://blog.jiminhome.shop/css/main.css"]}'

# 특정 태그만 퍼지
curl -X POST "https://api.cloudflare.com/client/v4/zones/7895fe2aef761351db71892fb7c22b52/purge_cache" \
  -H "Authorization: Bearer <CLOUDFLARE_API_TOKEN>" \
  -H "Content-Type: application/json" \
  --data '{"tags":["blog-posts"]}'
```

### Cloudflare vs nginx 비교

**Cloudflare (CDN):**
- ✅ 전 세계 분산 (낮은 레이턴시)
- ✅ DDoS 방어
- ✅ 무료 SSL
- ✅ 자동 압축 (Brotli, Gzip)
- ❌ 캐시 관리 필요 (퍼지)

**로컬 nginx:**
- ✅ 완전한 제어
- ✅ 추가 비용 없음
- ✅ 즉시 반영
- ❌ 단일 위치 (레이턴시)
- ❌ DDoS 취약

### 개선 옵션

**현재 (Cloudflare + nginx):**
```
사용자
  ↓
Cloudflare CDN (캐시)
  ↓
로컬 nginx (SSL + Proxy)
  ↓
Kubernetes Ingress
  ↓
web Pods
```

**대안 1: Cloudflare만 사용 (nginx 제거)**
```
사용자
  ↓
Cloudflare CDN (SSL + Proxy)
  ↓
Kubernetes LoadBalancer (MetalLB)
  ↓
web Pods
```
- ✅ 아키텍처 단순화
- ✅ SSL 관리 자동화 (cert-manager)
- ❌ Cloudflare 의존성 증가

**대안 2: nginx만 사용 (Cloudflare 제거)**
```
사용자
  ↓
로컬 nginx (SSL + Cache + Proxy)
  ↓
Kubernetes Ingress
  ↓
web Pods
```
- ✅ 완전한 제어
- ❌ 글로벌 성능 저하
- ❌ DDoS 방어 없음

**선택: 현재 유지 (Cloudflare + nginx)**
- Cloudflare: 글로벌 CDN + DDoS
- nginx: SSL 종료 + 로컬 프록시
- 추후 MetalLB + cert-manager로 nginx 제거 고려

---

## Kubernetes 아키텍처

### 현재 클러스터 구성

**노드:**
```
k8s-cp (Control Plane)
  - IP: 192.168.1.187
  - 역할: API Server, Scheduler, etcd

k8s-worker1 (Worker)
  - IP: 192.168.1.61
  - 상태: Ready

k8s-worker2 (Worker)
  - IP: 192.168.1.62
  - 상태: Ready
```

**Namespace:**
```bash
kubectl get ns blog-system
# NAME          STATUS   AGE
# blog-system   Active   24h
```

### 배포 리소스

**Deployments:**
```yaml
# WEB (Hugo 블로그)
apiVersion: apps/v1
kind: Deployment
metadata:
  name: web
  namespace: blog-system
spec:
  replicas: 2
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
        image: ghcr.io/wlals2/blog-web:v10
        ports:
        - containerPort: 80
        resources:
          requests:
            cpu: 100m
            memory: 128Mi
          limits:
            cpu: 200m
            memory: 256Mi

---
# WAS (Spring Boot)
apiVersion: apps/v1
kind: Deployment
metadata:
  name: was
  namespace: blog-system
spec:
  replicas: 2
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
        image: ghcr.io/wlals2/board-was:v3
        ports:
        - containerPort: 8080
        env:
        - name: SPRING_DATASOURCE_URL
          value: jdbc:mysql://mysql-service:3306/board
        - name: SPRING_DATASOURCE_USERNAME
          valueFrom:
            secretKeyRef:
              name: mysql-secret
              key: username
        - name: SPRING_DATASOURCE_PASSWORD
          valueFrom:
            secretKeyRef:
              name: mysql-secret
              key: password
        resources:
          requests:
            cpu: 250m
            memory: 512Mi
          limits:
            cpu: 500m
            memory: 1Gi

---
# MySQL
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
        ports:
        - containerPort: 3306
        env:
        - name: MYSQL_ROOT_PASSWORD
          valueFrom:
            secretKeyRef:
              name: mysql-secret
              key: root-password
        - name: MYSQL_DATABASE
          value: board
        volumeMounts:
        - name: mysql-storage
          mountPath: /var/lib/mysql
      volumes:
      - name: mysql-storage
        persistentVolumeClaim:
          claimName: mysql-pvc
```

**Services:**
```yaml
# WEB Service (ClusterIP)
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

---
# WAS Service (ClusterIP)
apiVersion: v1
kind: Service
metadata:
  name: was-service
  namespace: blog-system
spec:
  type: ClusterIP
  selector:
    app: was
  ports:
  - port: 8080
    targetPort: 8080

---
# MySQL Service (ClusterIP)
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

**Ingress:**
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
  - host: blog.jiminhome.shop
    http:
      paths:
      - path: /
        pathType: Prefix
        backend:
          service:
            name: web-service
            port:
              number: 80
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
```

### 네트워크 플로우

```
외부 사용자
    ↓
https://blog.jiminhome.shop (Cloudflare)
    ↓
192.168.1.200:80/443 (MetalLB LoadBalancer)
    ↓
Ingress Controller (nginx-ingress)
    │
    ├─ / → web-service:80
    │   ↓
    │   web Pods (ghcr.io/wlals2/blog-web:v10)
    │
    ├─ /api → was-service:8080
    │   ↓
    │   was Pods (ghcr.io/wlals2/board-was:v3)
    │
    └─ /board → was-service:8080
        ↓
        was Pods → mysql-service:3306
            ↓
            mysql Pod
```

### 리소스 할당

**WEB (nginx):**
- Replicas: 2
- CPU: 100m (request), 200m (limit)
- Memory: 128Mi (request), 256Mi (limit)

**WAS (Spring Boot):**
- Replicas: 2
- CPU: 250m (request), 500m (limit)
- Memory: 512Mi (request), 1Gi (limit)

**MySQL:**
- Replicas: 1
- PVC: 10Gi (Local Path Provisioner)

### 확인 명령어

**Pod 상태:**
```bash
kubectl get pods -n blog-system -o wide
# NAME                    READY   STATUS    IP           NODE
# web-xxx                 1/1     Running   10.0.2.2     k8s-worker2
# web-yyy                 1/1     Running   10.0.1.188   k8s-worker2
# was-xxx                 1/1     Running   10.0.2.3     k8s-worker2
# was-yyy                 1/1     Running   10.0.1.189   k8s-worker2
# mysql-xxx               1/1     Running   10.0.2.4     k8s-worker2
```

**Service 엔드포인트:**
```bash
kubectl get endpoints -n blog-system
# NAME            ENDPOINTS
# web-service     10.0.1.188:80,10.0.2.2:80
# was-service     10.0.1.189:8080,10.0.2.3:8080
# mysql-service   10.0.2.4:3306
```

**Ingress 상태:**
```bash
kubectl get ingress -n blog-system
# NAME           CLASS   HOSTS                  ADDRESS   PORTS   AGE
# blog-ingress   nginx   blog.jiminhome.shop              80      5d
```

**리소스 사용량:**
```bash
kubectl top pods -n blog-system
# NAME                    CPU(cores)   MEMORY(bytes)
# web-xxx                 10m          50Mi
# was-xxx                 150m         600Mi
# mysql-xxx               50m          300Mi
```

---

## Kubernetes 현재 구성

### MetalLB LoadBalancer (구현 완료)

**상태:** ✅ 구현 완료

**구성:**
- **LoadBalancer IP:** 192.168.1.200
- **서비스 타입:** LoadBalancer
- **네임스페이스:** ingress-nginx
- **포트:** 80 (HTTP), 443 (HTTPS)

**현재 아키텍처:**
```
CloudFlare → MetalLB LoadBalancer (192.168.1.200) → Ingress Controller → Services
                      ↓
                 표준 80/443 포트 사용
                 완전 K8s 네이티브
```

**달성된 효과:**
- ✅ LoadBalancer Service 사용 (표준 K8s API)
- ✅ NodePort 제거 (고정 포트 관리 불필요)
- ✅ 표준 포트 (80, 443) 사용 가능
- ✅ Kubernetes 네이티브 아키텍처

**MetalLB 설정 확인:**
```bash
# LoadBalancer Service 확인
kubectl get svc -n ingress-nginx
# NAME                       TYPE           EXTERNAL-IP     PORT(S)
# ingress-nginx-controller   LoadBalancer   192.168.1.200   80:31852/TCP,443:30732/TCP

# MetalLB Pod 상태
kubectl get pods -n metallb-system
# NAME                          READY   STATUS    RESTARTS   AGE
# controller-xxx                1/1     Running   0          Xd
# speaker-xxx                   1/1     Running   0          Xd

# IP Pool 확인
kubectl get ipaddresspool -n metallb-system
# NAME         AUTO ASSIGN   AVOID BUGGY IPS   ADDRESSES
# local-pool   true          false             ["192.168.1.200-192.168.1.210"]
```

**MetalLB IP Pool 설정:**
```yaml
apiVersion: metallb.io/v1beta1
kind: IPAddressPool
metadata:
  name: local-pool
  namespace: metallb-system
spec:
  addresses:
  - 192.168.1.200-192.168.1.210  # DHCP 범위 밖
---
apiVersion: metallb.io/v1beta1
kind: L2Advertisement
metadata:
  name: local-l2
  namespace: metallb-system
spec:
  ipAddressPools:
  - local-pool
```

---

### HPA (Horizontal Pod Autoscaler) (구현 완료)

**상태:** ✅ 구현 완료

**왜 HPA가 필요한가?**
- ✅ 트래픽 증가 시 자동 스케일 아웃
- ✅ 유휴 시간 리소스 절약 (스케일 인)
- ✅ 안정적인 서비스 제공 (Pod 장애 시 자동 복구)
- ✅ CPU/Memory 사용률 기반 자동 조절

**현재 구성:**

**1. WAS HPA**
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
  - type: Resource
    resource:
      name: memory
      target:
        type: Utilization
        averageUtilization: 80  # Memory 80% 초과 시 스케일 아웃
```

**2. WEB HPA**
```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: web-hpa
  namespace: blog-system
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: web
  minReplicas: 2
  maxReplicas: 5
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 60  # CPU 60% 초과 시 스케일 아웃
```

**HPA 동작 원리:**
1. **메트릭 수집**: Metrics Server가 Pod CPU/Memory 사용률 수집 (15초 간격)
2. **임계값 확인**: 현재 사용률이 target 초과 여부 판단
3. **스케일 아웃**: CPU/Memory 임계값 초과 → Pod 추가 (최대 maxReplicas)
4. **스케일 인**: 사용률 낮음 → Pod 제거 (최소 minReplicas 유지)
5. **안정화 시간**: 급격한 변동 방지 (스케일 아웃: 3분, 스케일 인: 5분)

**HPA 상태 확인:**
```bash
# HPA 상태
kubectl get hpa -n blog-system
# NAME       REFERENCE        TARGETS                       MINPODS   MAXPODS   REPLICAS   AGE
# was-hpa    Deployment/was   cpu: 0%/70%, memory: 40%/80%  2         10        2          31h
# web-hpa    Deployment/web   cpu: 1%/60%                   2         5         2          31h

# HPA 상세 정보
kubectl describe hpa was-hpa -n blog-system

# 실시간 Pod 수 변화 확인
kubectl get pods -n blog-system --watch
```

**트레이드오프:**

| 항목 | 장점 | 단점 |
|------|------|------|
| **높은 임계값 (80%)** | 리소스 효율적 | 급증 트래픽 대응 느림 |
| **낮은 임계값 (50%)** | 빠른 트래픽 대응 | 리소스 낭비 가능 |
| **짧은 안정화 시간** | 빠른 스케일링 | 불필요한 Pod 생성/삭제 |
| **긴 안정화 시간** | 안정적 운영 | 트래픽 급증 대응 느림 |

**현재 설정 근거:**
- **WAS**: CPU 70%, Memory 80% (리소스 집약적, 안정적 운영 우선)
- **WEB**: CPU 60% (가벼운 Nginx, 빠른 트래픽 대응)
- **minReplicas 2**: 고가용성 (1개 Pod 장애 시에도 서비스 가능)
- **maxReplicas**: WAS 10, WEB 5 (클러스터 리소스 고려)

---

## GitOps (ArgoCD)

### 상태

**구축 완료:** ✅ ArgoCD 설치 완료 (2026-01-20)
**다음 단계:** ⏳ Application 생성 및 Git Repository 연동

### 왜 ArgoCD인가?

**문제: 기존 배포 방식 (kubectl apply)**
```bash
# 수동 배포
vi deployment.yaml
kubectl apply -f deployment.yaml

# 문제점:
- ❌ Git과 클러스터 상태 불일치 (Drift)
- ❌ 배포 이력 추적 어려움
- ❌ 롤백 시 이전 YAML 찾기 어려움
- ❌ 여러 사람 작업 시 충돌
```

**해결: GitOps with ArgoCD**
```bash
# Git Push만으로 자동 배포
git commit -m "scale: replicas 5 → 10"
git push

# ArgoCD가 자동으로:
1. 변경 감지 (3초 이내)
2. 클러스터에 자동 동기화
3. Slack 알림
4. selfHeal 활성화 시 자동 복구
```

**GitOps 원칙:**
- **Git이 Single Source of Truth** (유일한 진실의 원천)
- **선언적 배포** (Desired State in Git)
- **자동 동기화** (Git → Kubernetes)
- **Pull 모델** (ArgoCD가 Git을 감시)

### 설치 과정

**1. Helm vs kubectl apply 비교**

| 항목 | Helm | kubectl apply |
|------|------|---------------|
| **YAML 크기** | values.yaml만 수정 | 26,951줄 전체 관리 |
| **업그레이드** | `helm upgrade` | 전체 YAML 재배포 |
| **롤백** | `helm rollback` ✅ | ❌ 불가능 |
| **히스토리** | `helm history` ✅ | ❌ 없음 |

**결론:** Helm 선택 (버전 관리, 롤백 가능)

**2. 설치 명령어**
```bash
# Namespace 생성
kubectl create namespace argocd

# Helm으로 설치
helm repo add argo https://argoproj.github.io/argo-helm
helm install argocd argo/argo-cd -n argocd --create-namespace

# 초기 비밀번호 확인
kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath="{.data.password}" | base64 -d
# 결과: saDtmwkg-ZyKLv2T
```

**3. 설치 확인**
```bash
kubectl get pods -n argocd

# 결과 (7개 Pod):
NAME                                                READY   STATUS
argocd-application-controller-0                     1/1     Running
argocd-applicationset-controller-6564d9cfd8-p2djw   1/1     Running
argocd-dex-server-6ff5b587ff-5lmlb                  1/1     Running
argocd-notifications-controller-6594786484-npvkg    1/1     Running
argocd-redis-7d6fd75fcb-926x8                       1/1     Running
argocd-repo-server-567d8799cf-fs94b                 1/1     Running
argocd-server-5f8b4dfd84-bbqlt                      1/1     Running
```

### 구성 요소

**ArgoCD Architecture:**
```
┌─────────────────────────────────────────┐
│         Git Repository                  │
│  (manifests: deployment.yaml)           │
└────────────────┬────────────────────────┘
                 │ (1) Git Polling (3초)
                 ▼
┌─────────────────────────────────────────┐
│      argocd-repo-server                 │
│  - Git Clone                            │
│  - Helm Template 렌더링                 │
└────────────────┬────────────────────────┘
                 │ (2) Manifest 전달
                 ▼
┌─────────────────────────────────────────┐
│  argocd-application-controller          │
│  - Desired vs Actual State 비교         │
│  - kubectl apply (Sync)                 │
└────────────────┬────────────────────────┘
                 │ (3) kubectl apply
                 ▼
┌─────────────────────────────────────────┐
│       Kubernetes Cluster                │
│  - Deployment, Service, Ingress         │
└─────────────────────────────────────────┘
```

**구성 요소 상세:**

| Pod | 역할 | 리소스 |
|-----|------|--------|
| **argocd-server** | Web UI + API | 1 replica |
| **argocd-repo-server** | Git Repository 연동, Helm 렌더링 | 1 replica |
| **argocd-application-controller** | 동기화 컨트롤러 (Desired ↔ Actual) | StatefulSet |
| **argocd-dex-server** | SSO 인증 (OIDC, SAML) | 1 replica |
| **argocd-redis** | 캐시 (Git 메타데이터) | 1 replica |
| **argocd-notifications-controller** | 알림 (Slack, Email) | 1 replica |
| **argocd-applicationset-controller** | ApplicationSet 관리 | 1 replica |

### Ingress 설정

**로컬 접속 (Ingress Nginx)**
```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: argocd-server-ingress
  namespace: argocd
  annotations:
    nginx.ingress.kubernetes.io/ssl-passthrough: "true"
    nginx.ingress.kubernetes.io/backend-protocol: "HTTPS"
spec:
  ingressClassName: nginx
  rules:
  - host: argocd.jiminhome.shop
    http:
      paths:
      - path: /
        pathType: Prefix
        backend:
          service:
            name: argocd-server
            port:
              number: 443
```

**중요 설정:**
- `ssl-passthrough: "true"` - ArgoCD 자체 TLS 사용 (Self-signed)
- `backend-protocol: "HTTPS"` - Backend가 HTTPS로 통신

**접속 확인:**
```bash
curl -k -I -H "Host: argocd.jiminhome.shop" https://192.168.1.200/
# HTTP/2 200 ✅
```

### Cloudflare Tunnel 설정

**DNS 라우팅 추가:**
```bash
cloudflared tunnel route dns home-network argocd.jiminhome.shop
# 2026-01-19T15:26:13Z INF Added CNAME argocd.jiminhome.shop ✅
```

**config.yml 업데이트:**
```yaml
tunnel: 65759494-dae6-4287-b92d-02a918b34722
credentials-file: /etc/cloudflared/65759494-dae6-4287-b92d-02a918b34722.json

ingress:
  - hostname: blog.jiminhome.shop
    service: http://192.168.1.200:80
  - hostname: argocd.jiminhome.shop
    service: https://192.168.1.200:443
    originRequest:
      noTLSVerify: true  # Self-signed 인증서 허용
  - service: http_status:404
```

**접속 방법:**
- **로컬:** `https://192.168.1.200/` (Host: argocd.jiminhome.shop)
- **외부:** `https://argocd.jiminhome.shop/` (Cloudflare Tunnel)

**로그인 정보:**
- 아이디: `admin`
- 비밀번호: `saDtmwkg-ZyKLv2T`

### Pull vs Push 모델

**Push 모델 (Jenkins):**
```
Jenkins → kubectl apply → Kubernetes
  문제:
  - ❌ 클러스터 credential 필요 (보안 위험)
  - ❌ 상태 불일치 가능 (Drift)
  - ❌ 자동 복구 없음
```

**Pull 모델 (ArgoCD):**
```
ArgoCD → Git 감지 → Sync → Kubernetes
  장점:
  - ✅ 클러스터 credential 불필요 (보안)
  - ✅ selfHeal 자동 복구
  - ✅ Git이 Single Source of Truth
```

**selfHeal 예시:**
```
1. Git: replicas=5
2. ArgoCD Sync: replicas=5 ✅
3. 관리자 실수: kubectl scale --replicas=2
4. ArgoCD 감지 (3초): "Git과 불일치!"
5. selfHeal 자동 복구: replicas=5 ✅
6. Slack 알림: "Application out of sync (auto-healed)"
```

### 트러블슈팅 경험

**1. Cloudflared 설정 미반영**
- **원인:** Systemd vs 사용자 config 우선순위 혼동, `warp-routing` 구문 오류
- **해결:**
  - 두 설정 파일 모두 업데이트 (`/etc/cloudflared/config.yml`, `~/.cloudflared/config.yml`)
  - `warp-routing` 섹션 제거
  - DNS 라우팅 직접 추가: `cloudflared tunnel route dns home-network argocd.jiminhome.shop`

**2. Ingress TLS 설정**
- **문제:** ArgoCD는 Self-signed 인증서 사용
- **해결:**
  - Annotation: `nginx.ingress.kubernetes.io/ssl-passthrough: "true"`
  - Cloudflare: `noTLSVerify: true`

### 다음 단계

**⏳ 30분 내 완료 가능**

**1. Git Repository 준비** (10분)
- blog-system manifest 정리
- Git Repository 생성 (GitHub)

**2. ArgoCD UI 로그인** (5분)
```bash
# 접속
https://argocd.jiminhome.shop/

# 로그인
아이디: admin
비밀번호: saDtmwkg-ZyKLv2T
```

**3. 첫 번째 Application 생성** (10분)
- Application 생성 (blog-system)
- Git Repository 연동
- Auto-Sync 활성화

**4. 실제 배포 테스트** (5분)
- Git에서 replicas 변경
- ArgoCD 자동 동기화 확인

### Application 생성 예시 (예정)

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: blog-system
  namespace: argocd
spec:
  project: default
  source:
    repoURL: https://github.com/wlals2/k8s-manifests.git
    targetRevision: main
    path: blog-system
  destination:
    server: https://kubernetes.default.svc
    namespace: blog-system
  syncPolicy:
    automated:
      prune: true        # 삭제된 리소스 자동 제거
      selfHeal: true     # Drift 자동 복구
      allowEmpty: false
    syncOptions:
    - CreateNamespace=true
```

**Sync Policy 설명:**
- `automated` - Git Push 시 자동 동기화
- `prune` - Git에서 삭제된 리소스 자동 제거
- `selfHeal` - kubectl로 수정해도 Git 상태로 자동 복구
- `allowEmpty` - 빈 디렉토리 허용 여부

### 배운 것

**1. Helm의 가치**
- 26,951줄 YAML → values.yaml 수정만
- `helm history`로 버전 관리
- `helm rollback`으로 즉시 복구

**2. GitOps 원칙**
- Git이 Single Source of Truth
- Pull 모델 (보안)
- selfHeal (자동 복구)

**3. Ingress 동작 원리**
- Host 헤더 기반 라우팅
- ssl-passthrough (TLS Passthrough)
- Backend Protocol (HTTPS)

**4. Cloudflare Tunnel**
- DNS vs Ingress 규칙 (둘 다 필요)
- `cloudflared tunnel route dns`
- Config 우선순위 (Systemd > /etc > ~/)

---

## 향후 개선 계획

### 현재 미해결 사항

**SSL 인증서 관리:**
- ⚠️ 로컬 nginx에서 수동 SSL 관리 (Let's Encrypt 90일 갱신)
- ⚠️ Kubernetes 외부 의존성
- ⚠️ GitOps 불가 (nginx는 K8s 외부)

**개선 목표:**
- cert-manager 도입하여 SSL 자동 발급 + 갱신
- 로컬 nginx 제거
- 완전한 Kubernetes 네이티브 아키텍처

### Phase 1: cert-manager 설치 (예정)

**목적:** Let's Encrypt SSL 인증서 자동 발급 + 갱신

**설치:**
```bash
# 1. cert-manager 설치
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.14.1/cert-manager.yaml

# 2. ClusterIssuer 생성
cat <<EOF | kubectl apply -f -
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: letsencrypt-prod
spec:
  acme:
    server: https://acme-v02.api.letsencrypt.org/directory
    email: wlals2@naver.com
    privateKeySecretRef:
      name: letsencrypt-prod
    solvers:
    - http01:
        ingress:
          class: nginx
EOF
```

**확인:**
```bash
kubectl get pods -n cert-manager
kubectl get clusterissuer letsencrypt-prod
```

### Phase 2: Ingress TLS 설정 (예정)

**Ingress 수정:**
```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: blog-ingress
  namespace: blog-system
  annotations:
    cert-manager.io/cluster-issuer: "letsencrypt-prod"  # ✅ 추가
spec:
  ingressClassName: nginx
  tls:  # ✅ TLS 설정 추가
  - hosts:
    - blog.jiminhome.shop
    secretName: blog-tls  # cert-manager가 자동 생성
  rules:
  - host: blog.jiminhome.shop
    http:
      paths:
      - path: /
        pathType: Prefix
        backend:
          service:
            name: web-service
            port:
              number: 80
```

**Certificate 확인:**
```bash
kubectl get certificate -n blog-system
# NAME       READY   SECRET     AGE
# blog-tls   True    blog-tls   5m

kubectl describe certificate blog-tls -n blog-system
# Status: Certificate is up to date and has not expired
```

### Phase 3: Cloudflare DNS 변경 (예정)

**현재:**
```
A  blog  192.168.1.187  (로컬 nginx 주소)
```

**변경 후:**
```
A  blog  192.168.1.200  (MetalLB LoadBalancer IP)
```

**변경 후 확인:**
```bash
dig +short blog.jiminhome.shop
# 192.168.1.200 (또는 Cloudflare Proxy IP)

curl -I https://blog.jiminhome.shop/
# HTTP/2 200
# server: nginx
```

### Phase 4: 로컬 nginx 중지 (예정)

**nginx 중지:**
```bash
# 1. nginx 중지
sudo systemctl stop nginx
sudo systemctl disable nginx

# 2. 포트 443 확인
sudo ss -tlnp | grep 443
# 아무것도 출력되지 않아야 함

# 3. 서비스 확인
curl -I https://blog.jiminhome.shop/
# HTTP/2 200 (여전히 정상 작동)
```

**롤백 방법:**
```bash
sudo systemctl start nginx
sudo systemctl enable nginx
```

### 구현 예상 시간

| Phase | 작업 | 시간 |
|-------|------|------|
| ~~Phase 0~~ | ~~MetalLB 설치~~ | ~~완료~~ ✅ |
| Phase 1 | cert-manager 설치 | 30분 |
| Phase 2 | Ingress TLS 설정 | 30분 |
| Phase 3 | DNS 변경 | 15분 |
| Phase 4 | nginx 중지 | 15분 |
| **총** |  | **1.5시간** |

---

## 모니터링

### 필요성

**왜 모니터링이 필요한가?**
- ✅ 리소스 사용량 추적 (CPU, Memory, Disk)
- ✅ 성능 병목 지점 파악
- ✅ 장애 사전 감지 (알림)
- ✅ 트래픽 패턴 분석
- ✅ 용량 계획 (Capacity Planning)

### PLG Stack (권장)

**구성:**
- **Prometheus**: 메트릭 수집 (시계열 데이터베이스)
- **Loki**: 로그 수집 및 저장
- **Grafana**: 시각화 대시보드

**왜 PLG Stack?**
- ✅ Kubernetes 네이티브
- ✅ 오픈소스 (무료)
- ✅ 통합 대시보드 (Grafana)
- ✅ 낮은 리소스 사용량

### 모니터링 대상

**1. 노드 메트릭**
- CPU 사용률
- Memory 사용률
- Disk I/O
- Network I/O

**2. Pod 메트릭**
- CPU/Memory 사용량
- Restart 횟수
- OOMKilled 이벤트

**3. 애플리케이션 메트릭**
- HTTP 요청 수 (RPS)
- 응답 시간 (Latency)
- 에러 비율 (Error Rate)

**4. Kubernetes 이벤트**
- Pod Eviction
- Node NotReady
- Deployment Rollout 실패

### 현재 구축 상태 ✅ (2024-11-26 ~ 현재 55일 운영)

**구축 완료:**
- ✅ **Prometheus 2.x**: Running (메트릭 수집, 8개 Alert Rules)
- ✅ **Grafana 12.3.1**: Running (4개 Custom Dashboards)
- ✅ **Loki**: Running (로그 수집 및 저장)
- ✅ **AlertManager v0.27.0**: Running (Slack 알림 템플릿)
- ✅ **Exporters**: nginx-exporter, mysql-exporter v0.16.0, node-exporter, cadvisor, kube-state-metrics

**접속 정보:**
- **Grafana URL**: http://monitoring.jiminhome.shop
- **IP 화이트리스트**: 192.168.1.0/24
- **Namespace**: monitoring

**대시보드 (4개):**
1. **Nginx Dashboard**: HTTP 요청 수, 응답 시간, 에러율
2. **WAS Dashboard**: Spring Boot 메트릭, JVM 상태, 요청 처리
3. **MySQL Dashboard**: 쿼리 성능, 커넥션 풀, Slow Query
4. **Full Stack Overview**: 전체 시스템 통합 뷰

**알림 규칙 (8개):**
```yaml
# 구성된 Alert Rules
- PodDown: Pod 비정상 종료
- MySQLDown: MySQL 서비스 중단
- HighCPUUsage: CPU 80% 초과 (5분)
- HighMemoryUsage: Memory 80% 초과 (5분)
- HighDiskUsage: Disk 80% 초과
- PodCrashLooping: Pod Restart 반복
- HighErrorRate: HTTP 5xx 에러 5% 초과
- HighResponseTime: 응답 시간 2초 초과
```

**상세 가이드:**
- 전체 설정 및 트러블슈팅: [docs/monitoring/README.md](./monitoring/README.md)
- 접속 방법: [docs/monitoring/ACCESS-GUIDE.md](./monitoring/ACCESS-GUIDE.md)
- 향후 개선 계획: [docs/monitoring/NEXT-STEPS.md](./monitoring/NEXT-STEPS.md)

**모니터링 명령어:**
```bash
# 대시보드 접속
open http://monitoring.jiminhome.shop

# 모니터링 Pod 상태 확인
kubectl get pods -n monitoring

# Prometheus 메트릭 확인
kubectl port-forward -n monitoring svc/prometheus 9090:9090

# Grafana 로그 확인
kubectl logs -n monitoring -l app=grafana
```

---

## 요약

### 현재 인프라

**Cloudflare:**
- ✅ CDN 활성화 (캐싱, DDoS)
- ✅ 자동 캐시 퍼지 (GitHub Actions)
- ✅ Zone ID: `7895fe2aef761351db71892fb7c22b52`
- ✅ Cloudflare Tunnel: blog.jiminhome.shop, argocd.jiminhome.shop

**Kubernetes:**
- ✅ 3-node 클러스터 (k8s-cp, k8s-worker1, k8s-worker2)
- ✅ Namespace: blog-system, argocd, monitoring
- ✅ Deployments: web (v10, 2 replicas), was (v1, 2 replicas), mysql (1 replica)
- ✅ Ingress: nginx-ingress (LoadBalancer via MetalLB)
- ✅ MetalLB: 192.168.1.200 (LoadBalancer IP)
- ✅ **HPA**: was-hpa (2-10 replicas, CPU 70%/Memory 80%), web-hpa (2-5 replicas, CPU 60%)

**GitOps:**
- ✅ ArgoCD 설치 완료 (Helm Chart, 7 pods)
- ✅ Ingress 설정 (argocd.jiminhome.shop)
- ⏳ Application 생성 대기

**모니터링:**
- ✅ PLG Stack 운영 중 (55일, Grafana 12.3.1, Prometheus 2.x, Loki, AlertManager v0.27.0)
- ✅ 4개 대시보드, 8개 Alert Rules
- ✅ 접속: http://monitoring.jiminhome.shop

### 개선 계획 우선순위

**P1 (높음) - cert-manager:**
- 목표: 로컬 nginx 제거, SSL 자동화
- 예상 시간: 1.5시간
- 효과: 완전 Kubernetes 네이티브
- 현재: MetalLB 이미 구축 완료 ✅

**P2 (중간) - 모니터링 (PLG Stack):**
- 목표: Prometheus + Loki + Grafana 구축
- 예상 시간: 3시간
- 효과: 리소스 가시성 확보

**P3 (낮음) - Auto Scaling:**
- 목표: HPA (Horizontal Pod Autoscaler)
- 예상 시간: 1시간
- 효과: 트래픽 증가 시 자동 확장

### 다음 단계

1. **블로그 콘텐츠 작성** (현재 우선순위)
2. ~~MetalLB 구축~~ ✅ (완료)
3. cert-manager 구축 (SSL 자동화)
4. PLG Stack 모니터링 구축
