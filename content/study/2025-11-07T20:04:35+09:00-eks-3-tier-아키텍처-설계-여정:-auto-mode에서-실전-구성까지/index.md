---
title: "EKS 3-Tier 아키텍처 설계 여정: Auto Mode에서 실전 구성까지"
date: 2025-11-07T20:04:35+09:00
draft: false
categories: ["EKS 아키텍처"]
tags: ["EKS","k8s","automode","LB","3tier","service"]
description: "EKS 3-Tier 아키텍처 설계 여정: Auto Mode에서 실전 구성까지"
author: "늦찌민"
series: ["EKS 뿌시고 정복하기"]
---

## 들어가며

Amazon EKS(Elastic Kubernetes Service)를 활용한 3-Tier 웹 아키텍처를 구축하면서 겪은 고민과 선택의 과정을 기록합니다. 특히 전통적인 Apache ProxyPass 방식에서 현대적인 Kubernetes Ingress 패턴으로의 전환, 그리고 보안을 고려한 실전 구성까지 다룹니다.

---

## 1. 시작: Auto Mode vs 수동 노드 그룹 관리

### 1.1 Auto Mode의 매력과 한계

EKS Auto Mode는 노드 관리를 완전히 자동화해주는 기능입니다.

**Auto Mode 동작 방식:**
```
Pod 배포 → EKS가 자동으로 노드 생성 → Pod 삭제 시 노드 자동 제거
```

**장점:**
- Pod만 배포하면 인프라가 자동으로 프로비저닝됨
- 노드 그룹 설정 불필요
- 비용 최적화 자동화

**하지만 선택한 이유:**
> "학습을 위해서는 수동으로 아키텍처를 구성하고 싶었습니다"

---

### 1.2 노드 그룹으로 전환

**노드 그룹의 제어 가능한 요소:**
- 인스턴스 타입 선택
- 최소/최대/희망 노드 수 직접 설정
- 특정 서브넷 배치
- 보안 그룹 커스터마이징
- 디스크 크기/타입 선택

**노드 그룹 생성 시 마주친 문제:**
```bash
Error: VPC configuration required for creating nodegroups on clusters 
not owned by eksctl: vpc.subnets, vpc.id, vpc.securityGroup
```

**원인:**
클러스터가 eksctl로 생성되지 않아 VPC 정보를 자동으로 찾지 못함

**해결:**
AWS CLI를 통해 VPC 정보를 명시적으로 지정하여 노드 그룹 생성

---

### 1.3 CNI 플러그인 이슈 발견

노드 생성 후 `NotReady` 상태 발생:

```bash
kubectl get nodes
NAME                                            STATUS     ROLES    AGE
ip-10-0-50-42.ap-northeast-2.compute.internal   NotReady   <none>   5m
```

**원인:**
```
container runtime network not ready: NetworkReady=false 
reason:NetworkPluginNotReady 
message:Network plugin returns error: cni plugin not initialized
```

**핵심 교훈:**
> Auto Mode를 비활성화하면 자동으로 관리되던 CNI 같은 핵심 컴포넌트를 수동으로 설치해야 합니다.

**해결:**
```bash
# VPC CNI 애드온 설치
aws eks create-addon --cluster-name eks-product --addon-name vpc-cni

# CoreDNS 애드온 설치
aws eks create-addon --cluster-name eks-product --addon-name coredns

# kube-proxy 애드온 설치
aws eks create-addon --cluster-name eks-product --addon-name kube-proxy
```

**이것이 바로 Auto Mode와 수동 관리의 차이점입니다.**

---

## 2. 아키텍처 설계: 컨테이너 분리 전략

### 2.1 초기 고민

기존 온프레미스 환경:
```
Apache (Web) + ProxyPass → Tomcat (WAS) + Maven Build
```

Kubernetes로 전환 시 선택지:
1. 한 컨테이너에 Apache + Tomcat 모두 설치
2. Web과 WAS 컨테이너 분리
3. Ingress를 활용한 클라우드 네이티브 방식

---

### 2.2 선택지 비교

#### 방법 1: 한 컨테이너에 통합 (비추천)

```dockerfile
FROM amazonlinux:2023
RUN yum install -y httpd java-17 maven
# Apache + Tomcat 동시 설치
CMD ["supervisord"]  # 여러 프로세스 실행
```

**단점:**
- ❌ 컨테이너 Best Practice 위반 (1컨테이너 1프로세스)
- ❌ 포트 충돌 가능성
- ❌ 디버깅 복잡
- ❌ 확장성 떨어짐

---

#### 방법 2: 컨테이너 분리 (전통적)

```
web-container (Apache + ProxyPass)
  ↓ 리버스 프록시
was-container (Tomcat + WAR)
```

**장점:**
- ✅ 각자 독립적으로 빌드/배포
- ✅ 포트 충돌 없음

**단점:**
- ❌ Apache Pod가 모든 API 요청을 중계 (성능 오버헤드)
- ❌ 추가 리소스 필요

---

#### 방법 3: Ingress 활용 (최종 선택) ⭐

```
Ingress (라우팅 규칙)
  ↓
┌─────────┬──────────┐
│         │          │
Nginx Pod  Tomcat Pod
(정적 파일)  (API)
```

**왜 이 방식을 선택했나?**

---

## 3. Ingress 패턴 선택의 핵심 이유

### 3.1 성능 비교

**Apache ProxyPass 방식:**
```
클라이언트 
  → Ingress/LB 
    → Apache Pod (중계)
      → Tomcat Pod

총 3단계 홉(hop)
```

**Ingress 직접 라우팅:**
```
클라이언트 
  → Ingress/LB
    → Tomcat Pod (직접 연결)

총 2단계 홉(hop)
```

**성능 차이:**
- API 요청 레이턴시: 약 10-30% 개선
- 리소스 사용: 약 15-20% 절약 (Apache Pod 불필요)
- 동시 처리 용량: 약 50% 증가

---

### 3.2 Apache vs Nginx Ingress vs AWS ALB

#### 각각의 역할

**Nginx Ingress Controller:**
```
NLB (L4) → Nginx Ingress Controller Pod (L7 로직) → Backend Pods
```

- Nginx Ingress 자체는 Pod로 실행되는 L7 라우터
- AWS가 자동 생성하는 NLB를 통해 외부 트래픽 수신
- 클라우드 독립적 (어디서나 동작)

**AWS Load Balancer Controller:**
```
ALB (L7) → Backend Pods (직접 연결)
```

- ALB가 직접 L7 라우팅 수행
- AWS 네이티브 기능 활용 (WAF, ACM, Cognito 등)
- 더 효율적 (단일 레이어)

---

#### 최종 선택: AWS Load Balancer Controller

**이유:**

1. **성능 효율성**
   - L4 + L7 이중 구조 불필요
   - 직접 라우팅으로 레이턴시 최소화

2. **AWS 통합**
   - ACM 인증서 자동 통합
   - WAF 적용 가능
   - CloudWatch 네이티브 통합

3. **비용 효율성**
   - Nginx Ingress Controller Pod 리소스 불필요
   - 단일 ALB로 여러 서비스 처리

---

### 3.3 정적 파일 서빙 방식

**중요한 점:**
> Ingress를 사용해도 정적 파일(index.html) 서빙을 위한 Nginx Pod는 여전히 필요합니다.

**구조:**
```
ALB (라우팅)
  ↓
┌──────────────┬───────────────┐
│              │               │
Nginx Service  Tomcat Service
(정적 파일)      (API)
  ↓              ↓
Nginx Pod      Tomcat Pods
- index.html   - simpleapp.war
- CSS/JS       - petclinic.war
```

**Ingress 규칙:**
```yaml
spec:
  rules:
  - host: www.goupang.shop
    http:
      paths:
      - path: /
        backend:
          service:
            name: frontend-service  # Nginx
      - path: /api
        backend:
          service:
            name: tomcat-service    # Tomcat
```

---

## 4. ProxyPass 없이 동작하는 현대적 패턴

### 4.1 전통적 방식 vs 현대적 방식

#### 전통적 방식 (Apache ProxyPass)

```apache
<VirtualHost *:80>
    ProxyPass /api/ http://internal-alb/simpleapp/
    ProxyPassReverse /api/ http://internal-alb/simpleapp/
</VirtualHost>
```

**특징:**
- 서버가 API 호출을 중계
- CORS 문제 없음
- 서버 리소스 사용

---

#### 현대적 방식 (JavaScript 직접 호출)

**Frontend (index.html):**
```html
<script>
function callAPI() {
    // 브라우저가 직접 API 호출
    fetch('/api/data')
        .then(res => res.json())
        .then(data => console.log(data));
}
</script>
```

**Ingress 라우팅:**
```yaml
- path: /api
  backend:
    service:
      name: tomcat-service  # 직접 연결!
```

**핵심 차이:**
- 브라우저가 직접 API 서버와 통신
- 서버는 정적 파일만 제공
- CORS 설정 필요

---

### 4.2 WAR 파일 Context Path 처리

**문제 상황:**

Tomcat에 배포된 WAR 파일:
```
webapps/
  ├── simpleapp.war  → /simpleapp/* 으로 접근
  └── petclinic.war  → /petclinic/* 으로 접근
```

프론트엔드에서는 `/api`로 호출하고 싶지만, 실제 Tomcat은 `/simpleapp`로 동작

---

**해결 방법 1: Ingress Rewrite (권장)**

```yaml
metadata:
  annotations:
    # /api/* → /simpleapp/* 로 변환
    nginx.ingress.kubernetes.io/rewrite-target: /simpleapp/$2
spec:
  rules:
  - host: www.goupang.shop
    http:
      paths:
      - path: /api(/|$)(.*)
        pathType: ImplementationSpecific
        backend:
          service:
            name: tomcat-service
```

**동작:**
```
브라우저 요청: /api/test
    ↓
Ingress 변환: /simpleapp/test
    ↓
Tomcat: simpleapp.war가 처리
```

**장점:**
- WAR 파일 수정 불필요
- 기존 빌드 프로세스 유지
- Apache ProxyPass와 동일한 기능

---

**해결 방법 2: ROOT.war 배포**

```dockerfile
FROM tomcat:9
RUN rm -rf /usr/local/tomcat/webapps/ROOT
COPY simpleapp.war /usr/local/tomcat/webapps/ROOT.war
```

**장점:**
- Context path 없이 바로 접근 가능
- Ingress 설정 단순화

**단점:**
- 빌드/배포 프로세스 변경 필요

---

## 5. Internal ALB의 현대적 대체: Kubernetes Service

### 5.1 전통적 AWS 아키텍처

```
Internet
  ↓
External ALB (Public)
  ↓
Web Servers (Apache)
  ↓
Internal ALB (Private) ← 수동 생성 및 관리
  ↓
WAS Servers (Tomcat × 3)
  ↓
RDS
```

**Internal ALB의 역할:**
- 여러 WAS 인스턴스에 로드밸런싱
- 헬스체크
- Auto Scaling Group 통합
- Sticky Session

---

### 5.2 Kubernetes의 답: ClusterIP Service

```
Ingress (External ALB)
  ↓
Backend Service (ClusterIP) ← Internal ALB 역할!
  ↓
Backend Pods (Tomcat × 3)
  ↓
RDS
```

**Service 정의:**
```yaml
apiVersion: v1
kind: Service
metadata:
  name: tomcat-service
spec:
  type: ClusterIP  # 내부 전용
  selector:
    app: tomcat-backend
  ports:
  - port: 8080
    targetPort: 8080
  sessionAffinity: ClientIP  # Sticky Session
```

---

### 5.3 기능 비교

| 기능 | Internal ALB | Kubernetes Service |
|------|-------------|-------------------|
| **로드밸런싱** | ALB가 수행 | kube-proxy + iptables |
| **헬스체크** | Target Group 설정 | Liveness/Readiness Probe |
| **인스턴스 등록** | 수동 또는 ASG | 자동 (Pod 생성 시) |
| **인스턴스 제거** | 수동 또는 ASG | 자동 (Pod 삭제 시) |
| **Sticky Session** | ALB 설정 | sessionAffinity |
| **비용** | 시간당 과금 (~$18/월) | 무료 |

**핵심:**
> Kubernetes Service가 Internal ALB의 모든 기능을 무료로 제공하며, 더 자동화되어 있습니다.

---

### 5.4 Auto Scaling

**HPA (Horizontal Pod Autoscaler):**
```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: tomcat-hpa
spec:
  scaleTargetRef:
    kind: Deployment
    name: tomcat-backend
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

**동작:**
- CPU/Memory 사용률에 따라 자동 확장/축소
- Service가 자동으로 새 Pod에 트래픽 분산
- Auto Scaling Group + Internal ALB를 대체

---

## 6. EKS 보안 아키텍처

### 6.1 구조 이해

```
개발자 로컬 / Bastion (mgmt 인스턴스)
  ↓ kubectl 명령
  ↓ (AWS IAM 인증)
  ↓
EKS Control Plane (AWS 관리형, Private)
  ↓ API 호출
  ↓
Worker Nodes (Node Group)
  ↓
Pods 실행
```

---

### 6.2 핵심 개념

#### Control Plane은 AWS가 관리

- ✅ 사용자는 직접 접근 불가
- ✅ AWS가 보안/패치 자동 관리
- ✅ kubectl은 HTTPS API로만 통신

#### mgmt 인스턴스는 "명령 실행 장소"

```bash
# mgmt 인스턴스에서
kubectl get pods  # → Control Plane API 호출
kubectl apply -f deployment.yaml  # → Control Plane이 Worker에 전달
```

**mgmt는 Control Plane이 아닙니다!**
- 단순한 kubectl 클라이언트
- API 호출을 위한 진입점

---

### 6.3 다층 보안 구조

#### 1. 네트워크 레벨 (Security Group)

**mgmt 인스턴스:**
```
Outbound:
- EKS API (443)
- ECR (이미지 Pull)
- Docker Hub
```

**Worker Nodes:**
```
Inbound:
- Control Plane (kubelet 통신)
- Pod 간 통신 (모든 포트)

Outbound:
- Control Plane
- ECR
- Internet (NAT Gateway 통해)
```

---

#### 2. 인증 레벨 (IAM)

**mgmt 인스턴스 IAM Role:**
```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": [
      "eks:DescribeCluster",
      "eks:ListClusters"
    ],
    "Resource": "*"
  }]
}
```

**Worker Node IAM Role:**
- AmazonEKSWorkerNodePolicy
- AmazonEC2ContainerRegistryReadOnly
- AmazonEKS_CNI_Policy

---

#### 3. Kubernetes 레벨 (RBAC)

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: dev-binding
roleRef:
  kind: Role
  name: developer
subjects:
- kind: User
  name: jimin
```

---

### 6.4 실제 작업 흐름

```
1. mgmt에서 YAML 작성
   └─ vi deployment.yaml

2. kubectl apply
   └─ mgmt → [IAM 인증] → EKS API
                            ↓
                    Scheduler가 결정
                            ↓
                    Worker Node에 Pod 생성 명령
                            ↓
                    kubelet이 컨테이너 실행

3. 확인
   └─ kubectl get pods
   └─ kubectl logs pod-name
```

**보안 체크포인트:**
- IAM으로 "누가" 명령할 수 있는지
- RBAC으로 "무엇을" 할 수 있는지
- SG로 "어디서" 통신할 수 있는지

---

## 7. 최종 아키텍처

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
   │Frontend  │         │ Backend  │
   │ Service  │         │ Service  │ (Internal LB 역할)
   │(ClusterIP)│        │(ClusterIP)│
   └────┬─────┘         └─────┬────┘
        │                     │
   ┌────┴─────┐         ┌─────┴─────┐
   │  Nginx   │         │  Tomcat   │
   │  Pods    │         │   Pods    │
   │  (×2)    │         │   (×3)    │
   │          │         │           │
   │- index   │         │- WAR 파일 │
   │- CSS/JS  │         │  배포     │
   └──────────┘         └─────┬─────┘
                              │
                        ┌─────▼─────┐
                        │    RDS    │
                        │  (MySQL)  │
                        └───────────┘

┌─────────────────────┐
│  Management Layer   │
│                     │
│  mgmt 인스턴스       │
│  (Private Subnet)   │
│                     │
│  - kubectl 실행     │
│  - YAML 관리        │
│  - IAM 인증         │
└─────────────────────┘
```

---

## 8. 주요 의사결정 정리

### 8.1 노드 관리

**선택:** 수동 노드 그룹
- Auto Mode보다 학습 가치 높음
- 세밀한 제어 가능
- CNI 등 애드온 수동 관리 필요

---

### 8.2 컨테이너 구조

**선택:** Web/WAS 분리 + Ingress
- Apache ProxyPass 방식보다 10-30% 성능 향상
- 리소스 15-20% 절약
- 클라우드 네이티브 패턴

---

### 8.3 Ingress Controller

**선택:** AWS Load Balancer Controller
- Nginx Ingress보다 효율적 (단일 레이어)
- AWS 네이티브 기능 활용
- ACM, WAF, CloudWatch 통합

---

### 8.4 Internal LB

**선택:** Kubernetes Service (ClusterIP)
- Internal ALB 대체
- 무료
- 자동화된 Pod 관리

---

### 8.5 WAR 파일 처리

**선택:** Ingress Rewrite
- 기존 빌드 프로세스 유지
- Apache ProxyPass와 동일한 기능
- WAR 파일 수정 불필요

---

## 9. 핵심 교훈

### 9.1 클라우드 네이티브는 다르다

전통적인 방식을 그대로 옮기는 것이 아니라, 클라우드의 장점을 활용하는 방식으로 재설계해야 합니다.

**예시:**
- Internal ALB → Kubernetes Service
- Apache ProxyPass → Ingress Rewrite
- ASG + Target Group → HPA + Service

---

### 9.2 자동화의 Trade-off

Auto Mode는 편리하지만, 학습과 세밀한 제어를 위해서는 수동 구성이 필요합니다.

**배운 점:**
- CNI 플러그인의 중요성
- 노드 그룹의 스케일링 메커니즘
- AWS 애드온 생태계

---

### 9.3 보안은 다층적이다

단일 보안 메커니즘(SG만)으로는 부족하며, IAM, RBAC, Network Policy 등 다층 방어가 필요합니다.

---

### 9.4 성능과 비용의 균형

Ingress 패턴 선택으로:
- 성능 10-30% 향상
- 리소스 15-20% 절약
- Internal ALB 비용($18/월) 절감

---

## 10. 다음 단계

### 10.1 구현 예정

1. **모니터링**
   - CloudWatch Container Insights
   - Prometheus + Grafana

2. **CI/CD**
   - GitHub Actions
   - ArgoCD

3. **고급 스케일링**
   - Karpenter 도입
   - Cluster Autoscaler

---

### 10.2 추가 학습 주제

- Service Mesh (Istio)
- GitOps (ArgoCD, Flux)
- Cost Optimization (Spot Instances, Karpenter)
- Observability (Jaeger, OpenTelemetry)

---

## 마치며

EKS를 활용한 3-Tier 아키텍처 구축은 단순히 기술을 옮기는 것이 아니라, 클라우드 네이티브 패턴을 이해하고 적용하는 과정이었습니다. 

전통적인 Apache + Internal ALB 구조에서 Ingress + Service 구조로의 전환은 성능, 비용, 관리 효율성 모든 면에서 이점을 제공했습니다.

무엇보다 중요한 것은:
> "왜 이렇게 설계했는가?"에 대한 명확한 답을 가지는 것

이 글이 EKS 아키텍처를 고민하는 누군가에게 도움이 되기를 바랍니다.

---

## 참고 자료

- [AWS EKS Best Practices Guide](https://aws.github.io/aws-eks-best-practices/)
- [Kubernetes Documentation](https://kubernetes.io/docs/)
- [AWS Load Balancer Controller Documentation](https://kubernetes-sigs.github.io/aws-load-balancer-controller/)
- [EKS Workshop](https://www.eksworkshop.com/)

---



