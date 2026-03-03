---
title: "EKS 3-Tier 아키텍처 실전 구축: Docker부터 Ingress까지"
date: 2025-11-08T08:50:09+09:00
draft: false
categories: ["study", "Cloud & Terraform", "Development"]
tags: ["aws","eks","3tier","docker","CICD"]
description: "EKS 3-Tier 아키텍처 실전 구축: Docker부터 Ingress까지"
author: "늦찌민"
series: ["EKS 시리즈"]
---

## 들어가며

이전 글에서 EKS 아키텍처 설계 이론을 다뤘다면, 이번에는 실제로 손을 움직여 3-Tier 웹 애플리케이션을 EKS에 배포하는 전 과정을 기록합니다. Docker 이미지 빌드, ECR 푸시, Kubernetes 리소스 배포, 그리고 AWS Load Balancer Controller를 통한 ALB 자동 생성까지 모든 단계를 실습합니다.

---

## 프로젝트 개요

### 목표 아키텍처

```

Internet
  ↓
ALB (Ingress가 자동 생성)
  ↓
┌──────────────┬───────────────┐
│              │               │
Web Tier       WAS Tier
(Nginx)        (Tomcat)
  ↓              ↓
Frontend       Backend
Pods           Pods
               ↓
            RDS (MySQL)

```

### 기술 스택

- **Container:** Docker (Multi-stage Build)
- **Registry:** Amazon ECR
- **Orchestration:** Amazon EKS
- **Web:** Nginx (정적 파일 서빙)
- **WAS:** Tomcat 9 + Spring Boot Petclinic
- **Database:** Amazon RDS MySQL
- **Load Balancer:** AWS ALB (Ingress 자동 생성)
- **Build Tool:** Maven

---

## Part 1: Docker 이미지 빌드

### 1.1 WAS 이미지 - Spring Boot Petclinic

#### 프로젝트 준비

```bash
# 프로젝트 클론
git clone https://github.com/your-repo/petclinic.git
cd petclinic

# 프로젝트 구조 확인
ls -la
# pom.xml, src/, Dockerfile

```

---

#### pom.xml 설정 (DB 정보)

```xml
<properties>
    <db.script>mysql</db.script>
    <jpa.database>MYSQL</jpa.database>
    <jdbc.driverClassName>com.mysql.cj.jdbc.Driver</jdbc.driverClassName>
    <jdbc.url>jdbc:mysql://your-rds-endpoint:3306/petclinic?useUnicode=true</jdbc.url>
    <jdbc.username>admin</jdbc.username>
    <jdbc.password>your-password</jdbc.password>
</properties>

```

**핵심 포인트:**
- 로컬에서 RDS 정보로 수정
- 이 설정이 Docker 이미지에 포함됨
- 환경변수 방식도 가능하지만, 간단한 학습용으로는 하드코딩 선택

---

#### Dockerfile 작성

```dockerfile
# ====================
# Stage 1: 빌드 단계
# ====================
FROM maven:3.8-amazoncorretto-17 AS builder

# 작업 디렉토리 설정
WORKDIR /build

# pom.xml과 소스 코드 복사
COPY pom.xml .
COPY src ./src

# Maven 빌드 (WAR 파일 생성)
# -P MySQL: MySQL 프로파일 활성화
# -DskipTests: 테스트 스킵 (빠른 빌드)
RUN mvn clean package -P MySQL -DskipTests

# 빌드 결과 확인
RUN ls -lh /build/target/

# ====================
# Stage 2: 실행 단계
# ====================
FROM tomcat:9.0.110-jre17

# Tomcat 기본 앱 제거
RUN rm -rf /usr/local/tomcat/webapps/*

# Stage 1에서 빌드한 WAR 파일 복사
# /petclinic.war로 배치 → /petclinic 경로로 접근
COPY --from=builder /build/target/*.war /usr/local/tomcat/webapps/petclinic.war

# 포트 노출
EXPOSE 8080

# Tomcat 실행
CMD ["catalina.sh", "run"]

```

**Multi-stage Build의 장점:**
- Stage 1: Maven과 소스코드로 WAR 빌드
- Stage 2: Tomcat만 포함 (Maven, 소스 제외)
- 최종 이미지 크기: 1GB → 300MB 절감

---

#### 빌드 및 테스트

```bash
# 이미지 빌드
docker build -t petclinic:v1 .

# 로컬 테스트
docker run -d -p 8080:8080 --name petclinic-test petclinic:v1

# 로그 확인
docker logs -f petclinic-test

# 접속 테스트
curl http://localhost:8080/petclinic/

# 정리
docker stop petclinic-test
docker rm petclinic-test

```

---

### 1.2 Web 이미지 - Nginx Frontend

#### index.html 작성

```html
<!DOCTYPE html>
<html lang="ko">
<head>
    <meta charset="UTF-8">
    <title>Goupang Shop</title>
    <style>
        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
        }
        body {
            font-family: 'Arial', sans-serif;
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            min-height: 100vh;
            display: flex;
            justify-content: center;
            align-items: center;
        }
        .container {
            background: white;
            padding: 50px;
            border-radius: 20px;
            box-shadow: 0 20px 60px rgba(0,0,0,0.3);
            text-align: center;
        }
        h1 {
            color: #667eea;
            font-size: 48px;
            margin-bottom: 20px;
        }
        .btn {
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            color: white;
            padding: 15px 40px;
            border: none;
            border-radius: 50px;
            font-size: 18px;
            cursor: pointer;
            margin: 10px;
        }
        .btn:hover {
            transform: translateY(-3px);
            box-shadow: 0 10px 30px rgba(102, 126, 234, 0.4);
        }
    </style>
</head>
<body>
    <div class="container">
        <h1>🛍️ Goupang Shop</h1>
        <p>Welcome to Goupang Online Shopping Mall</p>
        
        <button class="btn" onclick="window.location.href='/petclinic/'">
            🐾 Go to Petclinic
        </button>
        
        <button class="btn" onclick="testAPI()">
            🔌 Test API
        </button>
        
        <div id="result"></div>
    </div>

    <script>
        function testAPI() {
            fetch('/api/owners')
                .then(res => res.json())
                .then(data => {
                    document.getElementById('result').innerHTML = 
                        '✅ API Connected!';
                })
                .catch(err => {
                    document.getElementById('result').innerHTML = 
                        '❌ API Error: ' + err.message;
                });
        }
    </script>
</body>
</html>

```

---

#### Dockerfile 작성

```dockerfile
FROM nginx:alpine

# index.html 복사
COPY index.html /usr/share/nginx/html/

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]

```

**특징:**
- Alpine 기반 (경량, 5MB)
- 정적 파일만 서빙
- 설정 간단

---

#### 빌드

```bash
# Web 디렉토리 생성
mkdir web
cd web

# index.html, Dockerfile 작성 후 빌드
docker build -t goupang-web:v1 .

# 테스트
docker run -d -p 80:80 goupang-web:v1
curl http://localhost/

```

---

## Part 2: ECR 푸시

### 2.1 ECR 리포지토리 생성

```bash
# WAS용 리포지토리
aws ecr create-repository --repository-name petclinic --region ap-northeast-2

# Web용 리포지토리
aws ecr create-repository --repository-name goupang-web --region ap-northeast-2

```

---

### 2.2 ECR 로그인 및 푸시

```bash
# ECR 로그인
aws ecr get-login-password --region ap-northeast-2 | \
  docker login --username AWS --password-stdin \
  ACCOUNT_ID.dkr.ecr.ap-northeast-2.amazonaws.com

# WAS 이미지 태그 및 푸시
docker tag petclinic:v1 ACCOUNT_ID.dkr.ecr.ap-northeast-2.amazonaws.com/petclinic:v1
docker push ACCOUNT_ID.dkr.ecr.ap-northeast-2.amazonaws.com/petclinic:v1

# Web 이미지 태그 및 푸시
docker tag goupang-web:v1 ACCOUNT_ID.dkr.ecr.ap-northeast-2.amazonaws.com/goupang-web:v1
docker push ACCOUNT_ID.dkr.ecr.ap-northeast-2.amazonaws.com/goupang-web:v1

```

---

### 2.3 이미지 확인

```bash
# 푸시된 이미지 확인
aws ecr describe-images --repository-name petclinic --region ap-northeast-2
aws ecr describe-images --repository-name goupang-web --region ap-northeast-2

```

---

## Part 3: Kubernetes 배포

### 3.1 WAS Deployment & Service

**petclinic-deployment.yaml:**

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: petclinic-deployment
  labels:
    app: petclinic
spec:
  replicas: 2
  selector:
    matchLabels:
      app: petclinic
  template:
    metadata:
      labels:
        app: petclinic
    spec:
      containers:
      - name: tomcat
        image: ACCOUNT_ID.dkr.ecr.ap-northeast-2.amazonaws.com/petclinic:v1
        ports:
        - containerPort: 8080
        resources:
          requests:
            memory: "512Mi"
            cpu: "250m"
          limits:
            memory: "1Gi"
            cpu: "500m"
        livenessProbe:
          httpGet:
            path: /petclinic/
            port: 8080
          initialDelaySeconds: 60
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /petclinic/
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 5
---
apiVersion: v1
kind: Service
metadata:
  name: petclinic-service
spec:
  type: ClusterIP  # 내부 로드밸런서 역할
  selector:
    app: petclinic
  ports:
  - port: 8080
    targetPort: 8080

```

**핵심 포인트:**
- ClusterIP Service = 내부 ALB 역할 대체
- 자동 로드밸런싱 (2개 Pod)
- 헬스체크로 장애 Pod 자동 제외

---

### 3.2 Web Deployment & Service

**web-deployment.yaml:**

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: web-deployment
  labels:
    app: web
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
        image: ACCOUNT_ID.dkr.ecr.ap-northeast-2.amazonaws.com/goupang-web:v1
        ports:
        - containerPort: 80
        resources:
          requests:
            memory: "128Mi"
            cpu: "100m"
          limits:
            memory: "256Mi"
            cpu: "200m"
---
apiVersion: v1
kind: Service
metadata:
  name: web-service
spec:
  type: ClusterIP
  selector:
    app: web
  ports:
  - port: 80
    targetPort: 80

```

---

### 3.3 배포 및 확인

```bash
# mgmt 인스턴스 접속
ssh ec2-user@mgmt-ip

# WAS 배포
kubectl apply -f petclinic-deployment.yaml

# Web 배포
kubectl apply -f web-deployment.yaml

# 확인
kubectl get pods
kubectl get svc

# Pod 상태 확인
kubectl describe pod petclinic-deployment-xxx
kubectl logs petclinic-deployment-xxx

```

---

## Part 4: AWS Load Balancer Controller 설치

### 4.1 사전 준비

#### OIDC Provider 연결

```bash
# eksctl 설치 (없는 경우)
curl --silent --location "https://github.com/weaveworks/eksctl/releases/latest/download/eksctl_$(uname -s)_amd64.tar.gz" | tar xz -C /tmp
sudo mv /tmp/eksctl /usr/local/bin

# OIDC Provider 연결
eksctl utils associate-iam-oidc-provider \
  --region=ap-northeast-2 \
  --cluster=eks-product \
  --approve

```

---

#### IAM Policy 생성

```bash
# IAM Policy 다운로드
curl -o iam-policy.json https://raw.githubusercontent.com/kubernetes-sigs/aws-load-balancer-controller/v2.7.0/docs/install/iam_policy.json

# Policy 생성
aws iam create-policy \
    --policy-name AWSLoadBalancerControllerIAMPolicy \
    --policy-document file://iam-policy.json

```

---

#### Service Account 생성

```bash
# IAM Service Account 생성
eksctl create iamserviceaccount \
  --cluster=eks-product \
  --namespace=kube-system \
  --name=aws-load-balancer-controller \
  --attach-policy-arn=arn:aws:iam::ACCOUNT_ID:policy/AWSLoadBalancerControllerIAMPolicy \
  --approve

```

---

### 4.2 Helm으로 Controller 설치

```bash
# Helm 설치
curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash

# Helm Repository 추가
helm repo add eks https://aws.github.io/eks-charts
helm repo update

# VPC ID 확인
VPC_ID=$(aws eks describe-cluster --name eks-product --query 'cluster.resourcesVpcConfig.vpcId' --output text)

# AWS Load Balancer Controller 설치
helm install aws-load-balancer-controller eks/aws-load-balancer-controller \
  -n kube-system \
  --set clusterName=eks-product \
  --set serviceAccount.create=false \
  --set serviceAccount.name=aws-load-balancer-controller \
  --set vpcId=$VPC_ID

```

---

### 4.3 설치 확인

```bash
# Controller Pod 확인
kubectl get pods -n kube-system | grep aws-load-balancer

# 로그 확인 (에러 없어야 함)
kubectl logs -n kube-system deployment/aws-load-balancer-controller

```

---

### 4.4 트러블슈팅: IAM 권한 부족

**문제:**
```

Failed deploy model due to AccessDenied: 
User is not authorized to perform: elasticloadbalancing:DescribeListenerAttributes

```

**해결:**

```bash
# IAM Role 이름 확인
ROLE_NAME=$(kubectl get sa aws-load-balancer-controller -n kube-system -o jsonpath='{.metadata.annotations.eks\.amazonaws\.com/role-arn}' | cut -d'/' -f2)

# ElasticLoadBalancingFullAccess 추가
aws iam attach-role-policy \
  --role-name $ROLE_NAME \
  --policy-arn arn:aws:iam::aws:policy/ElasticLoadBalancingFullAccess

# Controller 재시작
kubectl rollout restart deployment aws-load-balancer-controller -n kube-system

```

---

## Part 5: Ingress 생성 (ALB 자동 생성)

### 5.1 퍼블릭 서브넷 확인

```bash
# 퍼블릭 서브넷 ID 확인
aws ec2 describe-subnets \
  --query 'Subnets[?MapPublicIpOnLaunch==`true`].[SubnetId,AvailabilityZone]' \
  --output table

```

---

### 5.2 Ingress YAML 작성

**ingress.yaml:**

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: goupang-ingress
  annotations:
    # AWS Load Balancer Controller 설정
    alb.ingress.kubernetes.io/scheme: internet-facing
    alb.ingress.kubernetes.io/target-type: ip
    alb.ingress.kubernetes.io/subnets: subnet-xxx,subnet-yyy  # 퍼블릭 서브넷
    alb.ingress.kubernetes.io/listen-ports: '[{"HTTP": 80}]'
    alb.ingress.kubernetes.io/healthcheck-path: /
spec:
  ingressClassName: alb
  rules:
  - http:
      paths:
      # 루트 경로 → Web
      - path: /
        pathType: Prefix
        backend:
          service:
            name: web-service
            port:
              number: 80
      
      # /petclinic → WAS
      - path: /petclinic
        pathType: Prefix
        backend:
          service:
            name: petclinic-service
            port:
              number: 8080
      
      # /api → WAS (필요시)
      - path: /api
        pathType: Prefix
        backend:
          service:
            name: petclinic-service
            port:
              number: 8080

```

**핵심 어노테이션:**
- `scheme: internet-facing` - 인터넷 노출 ALB
- `target-type: ip` - Pod IP로 직접 라우팅
- `subnets` - ALB가 생성될 퍼블릭 서브넷
- `ingressClassName: alb` - AWS LB Controller 사용

---

### 5.3 배포 및 확인

```bash
# Ingress 배포
kubectl apply -f ingress.yaml

# ALB 생성 확인 (1-2분 소요)
kubectl get ingress goupang-ingress -w

# 상세 정보 확인
kubectl describe ingress goupang-ingress

# ALB DNS 확인
kubectl get ingress goupang-ingress -o jsonpath='{.status.loadBalancer.ingress[0].hostname}'

```

---

### 5.4 동작 원리

```

1. Ingress 리소스 생성
   ↓
2. AWS LB Controller가 감지
   ↓
3. ALB 자동 생성 (퍼블릭 서브넷에)
   ↓
4. Target Group 자동 생성
   ↓
5. Pod IP를 Target으로 등록
   ↓
6. ALB Listener 규칙 생성
   (/ → web-service, /petclinic → petclinic-service)

```

**전통적 방식과 비교:**
- 수동: ALB 생성 → Target Group → Listener 규칙
- Ingress: YAML 작성 → 자동 생성!

---

## Part 6: 접속 테스트

### 6.1 ALB DNS 확인

```bash
# ALB DNS 주소 가져오기
ALB_DNS=$(kubectl get ingress goupang-ingress -o jsonpath='{.status.loadBalancer.ingress[0].hostname}')
echo $ALB_DNS

```

---

### 6.2 브라우저 테스트

```

http://ALB_DNS/
  → Goupang Shop 메인 페이지

http://ALB_DNS/petclinic/
  → Spring Boot Petclinic 애플리케이션

http://ALB_DNS/api/owners
  → JSON API 응답

```

---

### 6.3 상태 확인

```bash
# Pod 상태
kubectl get pods -o wide

# Service 엔드포인트
kubectl get endpoints

# Ingress 상태
kubectl describe ingress goupang-ingress

# AWS 콘솔에서 확인
# EC2 → 로드 밸런서 → Target Groups

```

---

## Part 7: 성능 최적화

### 7.1 리소스 증설

```yaml
# petclinic-deployment.yaml 수정
spec:
  replicas: 3  # 2 → 3
  template:
    spec:
      containers:
      - name: tomcat
        resources:
          requests:
            memory: "1Gi"     # 512Mi → 1Gi
            cpu: "500m"       # 250m → 500m
          limits:
            memory: "2Gi"     # 1Gi → 2Gi
            cpu: "1000m"      # 500m → 1000m

```

```bash
kubectl apply -f petclinic-deployment.yaml

```

---

### 7.2 스케일링

```bash
# 수동 스케일링
kubectl scale deployment petclinic-deployment --replicas=3
kubectl scale deployment web-deployment --replicas=3

# 리소스 사용률 확인
kubectl top pods
kubectl top nodes

```

---

### 7.3 HPA (Horizontal Pod Autoscaler)

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: petclinic-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: petclinic-deployment
  minReplicas: 2
  maxReplicas: 10
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70

```

---

## 최종 아키텍처

```

┌─────────────────────────────────────┐
│         Internet (Users)            │
└──────────────┬──────────────────────┘
               │
        ┌──────▼──────┐
        │   Ingress   │ (AWS ALB - 자동 생성)
        │    (ALB)    │
        └──────┬──────┘
               │
        ┌──────┴───────────────┐
        │                      │
   ┌────▼─────┐         ┌─────▼────┐
   │   Web    │         │   WAS    │
   │ Service  │         │ Service  │
   │(ClusterIP)│         │(ClusterIP)│
   └────┬─────┘         └─────┬────┘
        │                     │
   ┌────┴─────┐         ┌─────┴─────┐
   │  Nginx   │         │  Tomcat   │
   │  Pods    │         │   Pods    │
   │  (×3)    │         │   (×3)    │
   └──────────┘         └─────┬─────┘
                              │
                        ┌─────▼─────┐
                        │    RDS    │
                        │  (MySQL)  │
                        └───────────┘

```

---

## 주요 의사결정 정리

### Docker 이미지

| 항목 | 선택 | 이유 |
|------|------|------|
| **빌드 방식** | Multi-stage | 이미지 크기 절감 (1GB → 300MB) |
| **DB 설정** | 하드코딩 | 학습용 간편성 (환경변수도 가능) |
| **베이스 이미지** | maven:corretto-17, tomcat:9 | AWS 환경 최적화 |

---

### Kubernetes 리소스

| 항목 | 선택 | 이유 |
|------|------|------|
| **Service 타입** | ClusterIP | 내부 로드밸런서 (Internal ALB 대체) |
| **Replica** | 2-3개 | 고가용성 + 부하분산 |
| **리소스 제한** | requests/limits 설정 | 안정적 운영 |

---

### Ingress

| 항목 | 선택 | 이유 |
|------|------|------|
| **Controller** | AWS LB Controller | AWS 네이티브 통합 |
| **ALB 타입** | internet-facing | 외부 접근 |
| **Target** | IP 모드 | Pod 직접 라우팅 (성능) |

---

## 핵심 교훈

### 1. Multi-stage Build의 중요성

빌드 환경과 실행 환경을 분리하면:
- 이미지 크기 대폭 감소
- 보안 향상 (불필요한 빌드 도구 제외)
- 빌드 캐싱 효율 증가

---

### 2. Kubernetes Service = Internal ALB

전통적인 Internal ALB가 필요 없음:
- ClusterIP Service가 자동 로드밸런싱
- Pod 자동 등록/해제
- 헬스체크 자동
- 비용 절감 (ALB 비용 $18/월 절약)

---

### 3. Ingress의 강력함

YAML 하나로:
- ALB 자동 생성
- Target Group 자동 구성
- 라우팅 규칙 자동 적용
- 인프라 코드화 (IaC)

---

### 4. 성능 고려사항

EKS 환경은 로컬과 다름:
- 네트워크 홉 증가 (ALB + Service)
- 리소스 제한 (CPU, Memory)
- 레이턴시 추가 (10-50ms)

→ 리소스 튜닝과 스케일링으로 해결

---

## 트러블슈팅 요약

### 문제 1: ECR 푸시 실패

**증상:** `no basic auth credentials`

**해결:**
```bash
aws ecr get-login-password | docker login ...

```

---

### 문제 2: ImagePullBackOff

**증상:** Pod가 이미지 Pull 실패

**해결:**
- ECR 리포지토리 이름 확인
- IAM Role 권한 확인
- 이미지 태그 확인

---

### 문제 3: Controller CrashLoopBackOff

**증상:** `failed to get VPC ID`

**해결:**
```bash
helm upgrade ... --set vpcId=$VPC_ID

```

---

### 문제 4: Ingress AccessDenied

**증상:** `not authorized to perform: elasticloadbalancing:*`

**해결:**
```bash
aws iam attach-role-policy \
  --role-name $ROLE_NAME \
  --policy-arn arn:aws:iam::aws:policy/ElasticLoadBalancingFullAccess

```

---

### 문제 5: Target Group 미등록

**증상:** ALB는 생성되었지만 Target 없음

**해결:**
- Ingress 어노테이션 확인
- Service 이름 매칭 확인
- Controller 로그 확인

---

## 다음 단계

### 1. 모니터링 구축

- CloudWatch Container Insights
- Prometheus + Grafana
- X-Ray 분산 추적

---

### 2. CI/CD 파이프라인

- GitHub Actions
- ArgoCD (GitOps)
- 자동 배포

---

### 3. 보안 강화

- HTTPS (ACM 인증서)
- WAF 적용
- Pod Security Policy
- Network Policy

---

### 4. 고급 스케일링

- Karpenter (노드 Auto Scaling)
- KEDA (이벤트 기반 Auto Scaling)
- Cluster Autoscaler

---

## 마치며

전통적인 EC2 + ALB 방식에서 EKS + Ingress 방식으로 전환하면서 얻은 것들:

**자동화:**
- Infrastructure as Code (IaC)
- 선언적 배포 (Declarative)
- GitOps 가능

**효율성:**
- 리소스 최적화 (리소스 제한)
- 자동 스케일링
- 빠른 배포

**관리 편의성:**
- 일관된 배포 방식
- 롤링 업데이트
- 셀프 힐링

무엇보다:

> "인프라를 YAML로 관리한다는 것은 코드로 관리한다는 의미"

이제 인프라 변경도 Git으로 버전 관리하고, PR로 리뷰하고, 자동으로 배포할 수 있습니다.

---

## 참고 자료

- [Kubernetes Documentation](https://kubernetes.io/docs/)
- [AWS Load Balancer Controller](https://kubernetes-sigs.github.io/aws-load-balancer-controller/)
- [EKS Best Practices Guide](https://aws.github.io/aws-eks-best-practices/)
- [Docker Multi-stage Builds](https://docs.docker.com/build/building/multi-stage/)

---

**작성일:** 2025-11-08  
**태그:** #EKS #Kubernetes #Docker #Ingress #ALB #MultiStage #실전배포

---

## 부록: 빠른 시작 가이드

전체 과정을 빠르게 따라하고 싶다면:

### Step 1: Docker 이미지 빌드

```bash
# WAS
cd petclinic
docker build -t petclinic:v1 .

# Web
cd ../web
docker build -t goupang-web:v1 .

```

### Step 2: ECR 푸시

```bash
# ECR 로그인
aws ecr get-login-password | docker login ...

# 태그 & 푸시
docker tag petclinic:v1 ACCOUNT_ID.dkr.ecr.region.amazonaws.com/petclinic:v1
docker push ACCOUNT_ID.dkr.ecr.region.amazonaws.com/petclinic:v1

docker tag goupang-web:v1 ACCOUNT_ID.dkr.ecr.region.amazonaws.com/goupang-web:v1
docker push ACCOUNT_ID.dkr.ecr.region.amazonaws.com/goupang-web:v1

```

### Step 3: Kubernetes 배포

```bash
# Deployment & Service
kubectl apply -f petclinic-deployment.yaml
kubectl apply -f web-deployment.yaml

# Ingress
kubectl apply -f ingress.yaml

# 확인
kubectl get ingress

```

### Step 4: 접속

```bash
ALB_DNS=$(kubectl get ingress goupang-ingress -o jsonpath='{.status.loadBalancer.ingress[0].hostname}')
echo "http://$ALB_DNS/"

```

**끝!** 🚀

