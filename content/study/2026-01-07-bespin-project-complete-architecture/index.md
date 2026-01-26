---
title: "Bespin 프로젝트 완전한 아키텍처 가이드"
date: 2026-01-07
categories: ["study", "Cloud & Terraform", "Kubernetes"]
tags: ["AWS", "EKS", "Kubernetes", "ArgoCD", "Multi-Cloud", "DR", "GitOps", "Terraform", "PetClinic"]
description: "Spring PetClinic을 AWS EKS 기반 3-Tier 아키텍처로 구축하고, Azure를 활용한 멀티 클라우드 DR 환경까지 구현한 완전한 여정입니다."
slug: bespin-project-complete-architecture
---

> **작성 배경**: "단순한 Spring Boot 애플리케이션을 프로덕션 수준의 클라우드 네이티브 시스템으로 발전시킨다면?" 이 질문에서 시작된 6개월간의 여정입니다.

---

## 프롤로그: 왜 이 프로젝트를 시작했나요?

2025년 중반, 저는 Spring PetClinic이라는 간단한 애플리케이션을 가지고 있었어요.

```
Spring Boot + Tomcat + MySQL
단일 서버에서 실행
배포 = FTP + 수동 재시작
```

"이걸 진짜 프로덕션 환경에서 운영한다면 어떻게 구축해야 할까?" 하는 궁금증이 생겼어요.

그래서 시작했어요. **Bespin 프로젝트**를.

### 목표 설정

```
✅ 고가용성: Multi-AZ Pod 분산
✅ 무중단 배포: Canary Rollout
✅ 자동 스케일링: HPA + Karpenter
✅ GitOps: ArgoCD
✅ 모니터링: Prometheus + Grafana
✅ 재해복구: Multi-Cloud (AWS + Azure)
✅ 비용 추적: OpenCost
```

처음에는 "이게 다 가능할까?" 의심했는데, 하나씩 구현하다 보니 완성됐어요.

---

## Chapter 1: AWS EKS 클러스터 구축

### 처음 만난 선택: eksctl vs Terraform

EKS 클러스터를 만드는 방법은 크게 두 가지예요.

**eksctl**: 간단하지만 Infrastructure as Code가 아님
**Terraform**: 복잡하지만 모든 인프라를 코드로 관리

저는 **Terraform**을 선택했어요. 이유는 간단해요.

"나중에 클러스터를 재구축하거나, 다른 리전에 똑같은 환경을 만들 수 있을까?"

eksctl로 만들면 어떤 옵션을 줬는지 기억하기 어렵지만, Terraform은 모든 게 코드로 남아요.

### Terraform 코드 작성

`terraform/eks/main.tf`에 이런 설정을 했어요.

```hcl
module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "~> 19.0"

  cluster_name    = "eks-dev-cluster"
  cluster_version = "1.28"

  vpc_id     = module.vpc.vpc_id
  subnet_ids = module.vpc.private_subnets

  eks_managed_node_groups = {
    system = {
      min_size     = 2
      max_size     = 4
      desired_size = 2

      instance_types = ["t3.medium"]

      # Multi-AZ 분산
      subnet_ids = [
        module.vpc.private_subnets[0],  # 2a
        module.vpc.private_subnets[1],  # 2c
      ]
    }
  }
}
```

**핵심 결정사항**:

1. **t3.medium**: t3.small은 메모리가 부족했어요 (OOM 발생)
2. **Multi-AZ**: 2a, 2c 두 개 가용 영역에 노드 분산
3. **Managed Node Group**: 직접 관리하는 것보다 AWS가 관리하는 게 편해요

### 첫 번째 실패: 노드가 클러스터에 조인 안 됨

```bash
$ terraform apply
...
Success! (20분 소요)

$ kubectl get nodes
No resources found
```

"뭐야! 클러스터는 만들어졌는데 노드가 없잖아?"

알고 보니 **IAM Role 권한 문제**였어요.

노드가 EKS 클러스터에 조인하려면 특정 IAM 권한이 필요한데, 제가 만든 Role에 빠져있었던 거죠.

**해결**:
```hcl
resource "aws_iam_role_policy_attachment" "node_AmazonEKSWorkerNodePolicy" {
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKSWorkerNodePolicy"
  role       = aws_iam_role.node.name
}

resource "aws_iam_role_policy_attachment" "node_AmazonEC2ContainerRegistryReadOnly" {
  policy_arn = "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly"
  role       = aws_iam_role.node.name
}
```

이 정책들을 추가하고 노드를 재생성하니 정상적으로 조인됐어요!

```bash
$ kubectl get nodes
NAME                                          STATUS   ROLES    AGE
ip-10-0-11-123.ap-northeast-2.compute.internal   Ready    <none>   5m
ip-10-0-12-456.ap-northeast-2.compute.internal   Ready    <none>   5m
```

---

## Chapter 2: VPC 네트워크 설계

### Public vs Private Subnet

EKS를 구축하면서 가장 고민했던 건 **네트워크 구조**였어요.

"Pod를 Public Subnet에 둘까, Private Subnet에 둘까?"

| 방식 | 장점 | 단점 |
|------|------|------|
| **Public** | 설정 간단 | 보안 위험 |
| **Private** | 보안 강화 | NAT Gateway 비용 (~$30/월) |

프로덕션 환경을 가정했기 때문에 **Private Subnet**을 선택했어요.

### 서브넷 설계

```
AWS VPC (10.0.0.0/16)
│
├── AZ: ap-northeast-2a
│   ├── Public Subnet (10.0.1.0/24)     ← ALB
│   ├── Private Subnet (10.0.11.0/24)   ← EKS Nodes, Pods
│   └── DB Subnet (10.0.100.0/24)       ← RDS
│
├── AZ: ap-northeast-2c
│   ├── Public Subnet (10.0.2.0/24)     ← ALB
│   ├── Private Subnet (10.0.12.0/24)   ← EKS Nodes, Pods
│   └── DB Subnet (10.0.101.0/24)       ← RDS
```

**왜 이렇게 나눴나요?**

1. **Public Subnet**: ALB만 배치해서 외부 트래픽 수신
2. **Private Subnet**: Pod와 Node는 외부에서 직접 접근 불가
3. **DB Subnet**: RDS는 가장 안쪽에 격리

### NAT Gateway 딜레마

Private Subnet의 Pod가 인터넷에 접근하려면 **NAT Gateway**가 필요해요.

문제는 비용이에요. NAT Gateway는 **가용 영역당 1개**가 필요한데, 각각 약 $30/월이거든요.

```
2개 AZ × $30/월 = $60/월
```

"개인 프로젝트에 $60는 너무 비싸..."

그래서 **1개 AZ에만 NAT Gateway**를 두기로 했어요.

```hcl
resource "aws_nat_gateway" "main" {
  count = 1  # 1개만 생성

  allocation_id = aws_eip.nat[0].id
  subnet_id     = module.vpc.public_subnets[0]
}
```

대신 HA는 포기했어요. 2a AZ가 다운되면 NAT Gateway도 같이 다운되죠.

**트레이드오프**:
- 절약: $30/월
- 위험: 단일 장애점 (SPOF)

프로덕션에서는 절대 하면 안 되는 선택이지만, 개인 프로젝트라서 감수했어요.

---

## Chapter 3: 애플리케이션 배포 - 3-Tier 아키텍처

### WEB (nginx) + WAS (Spring Boot) 분리

처음에는 "Spring Boot만 배포하면 되지 않나?" 생각했어요.

하지만 프로덕션 환경에서는 **WEB 서버를 앞단에 두는 게 표준**이에요.

```
사용자 → ALB → nginx (WEB) → Tomcat (WAS) → MySQL
```

**왜 nginx가 필요한가요?**

1. **정적 파일 서빙**: CSS, JS, 이미지 등을 nginx가 직접 처리
2. **리버스 프록시**: 백엔드 WAS 숨김 (보안)
3. **로드 밸런싱**: 여러 WAS Pod로 트래픽 분산
4. **Health Check**: nginx가 WAS 상태 확인

### nginx 설정

`configmap.yaml`에 이런 설정을 했어요.

```nginx
upstream was_backend {
    server was-service:8080;
    keepalive 32;
}

server {
    listen 80;

    location /health {
        access_log off;
        return 200 "OK";
    }

    location / {
        root /usr/share/nginx/html;
        index index.html;
    }

    location /petclinic {
        proxy_pass http://was_backend;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
}
```

**핵심 포인트**:

1. **upstream**: WAS Service를 백엔드로 지정
2. **keepalive**: 커넥션 재사용으로 성능 향상
3. **proxy_pass**: `/petclinic` 경로를 WAS로 프록시

### 트러블슈팅: Redirect Loop

배포하고 접속했더니... **무한 리다이렉트**에 빠졌어요!

```
https://www.goupang.shop/petclinic
  → http://www.goupang.shop/petclinic (HTTP로 리다이렉트)
  → https://www.goupang.shop/petclinic (HTTPS로 다시 리다이렉트)
  → ... (무한 반복)
```

**원인**: ALB가 HTTPS를 종료하고 Pod에 HTTP로 요청을 보내는데, nginx가 이걸 모르고 계속 HTTP로 리다이렉트했어요.

**해결**: `X-Forwarded-Proto` 헤더를 전달했어요.

```nginx
proxy_set_header X-Forwarded-Proto $http_x_forwarded_proto;
proxy_redirect http://www.goupang.shop/ https://www.goupang.shop/;
```

이제 Spring Boot가 원래 프로토콜(HTTPS)을 인식해서 올바른 리다이렉트를 하기 시작했어요!

---

## Chapter 4: 무중단 배포 - Argo Rollouts Canary

### 왜 Deployment가 아닌 Rollout인가요?

처음에는 Kubernetes의 기본 **Deployment**로 배포했어요.

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: was
spec:
  replicas: 2
  strategy:
    type: RollingUpdate
```

이게 문제가 뭐냐면...

**RollingUpdate**는 새 버전을 바로 50% 이상 배포해요. 만약 새 버전에 버그가 있으면? **사용자의 절반이 에러를 경험**하는 거죠.

"더 안전한 방법이 없을까?" 고민하다가 **Argo Rollouts**를 발견했어요.

### Canary 배포 전략

Canary는 새 버전을 **점진적으로** 배포해요.

```
1. 10% 트래픽 → 새 버전 (30초 대기)
2. 50% 트래픽 → 새 버전 (2분 대기)
3. 90% 트래픽 → 새 버전 (30초 대기)
4. 100% 완료
```

각 단계에서 문제가 생기면 **즉시 중단**하고 이전 버전으로 롤백할 수 있어요.

### Rollout 설정

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Rollout
metadata:
  name: was
  namespace: petclinic
spec:
  replicas: 2
  strategy:
    canary:
      canaryService: was-canary
      stableService: was-stable
      trafficRouting:
        alb:
          ingress: petclinic-ingress
          servicePort: 8080
      steps:
        - setWeight: 10
        - pause: {duration: 30s}
        - setWeight: 50
        - pause: {duration: 2m}
        - setWeight: 90
        - pause: {duration: 30s}
```

**왜 이 비율을 선택했나요?**

- **10%**: 문제 조기 감지 (사용자 영향 최소화)
- **50%**: 본격 검증 (충분한 트래픽)
- **90%**: 최종 확인 (거의 모든 트래픽)

### 첫 Canary 배포 경험

새 버전을 배포했을 때의 긴장감이 아직도 생생해요.

```bash
$ kubectl argo rollouts set image was was=IMAGE:v2
rollout "was" image updated

$ kubectl argo rollouts get rollout was --watch
Name:            was
Namespace:       petclinic
Status:          ॥ Paused
Message:         CanaryPauseStep
Strategy:        Canary
  Step:          1/6
  SetWeight:     10
  ActualWeight:  10
Images:          was:v1 (stable)
                 was:v2 (canary)
Replicas:
  Desired:       2
  Current:       3
  Updated:       1
  Ready:         3
  Available:     3
```

10% 트래픽이 새 버전으로 가고 있어요. Grafana를 열어서 에러율을 확인했죠.

```
Error Rate: 0.0%
Latency P95: 120ms (정상)
```

완벽! 30초 대기하고 다음 단계로 진행했어요.

```bash
$ kubectl argo rollouts promote was
rollout 'was' promoted
```

50% → 90% → 100% 순차적으로 배포되면서 **단 한 건의 에러도 발생하지 않았어요!**

---

## Chapter 5: 세션 클러스터링 - Spring Session + Redis

### 로그인 무한 루프 사건

WAS Pod를 2개로 늘렸을 때 큰 문제가 생겼어요.

"로그인이 안 돼!"

사용자가 로그인 → 다음 요청이 다른 Pod로 → 세션 없음 → 로그인 페이지로

**원인**: 각 WAS Pod가 독립적인 메모리에 세션 저장

**해결**: Spring Session + Redis로 세션 공유

```yaml
spring:
  session:
    store-type: redis
  redis:
    host: redis-master.petclinic.svc.cluster.local
    port: 6379
```

이제 모든 WAS Pod가 Redis에서 세션을 공유해요!

```
WAS Pod 1 → 로그인 → 세션 저장 (Redis)
WAS Pod 2 → 요청 → 세션 조회 (Redis) → 로그인 유지 ✅
```

### Redis SPOF 이슈

하지만 새로운 문제가 생겼어요.

"Redis가 죽으면 모든 세션이 사라지는데...?"

맞아요. Redis는 **단일 장애점(SPOF)**이에요.

**해결 방법**:
1. Redis Sentinel (자동 Failover)
2. ElastiCache (AWS 관리형)

개인 프로젝트라서 일단 Priority 2로 미뤘어요. 나중에 구축 예정이에요.

---

## Chapter 6: HPA - 자동 스케일링

### CPU 기반 스케일링

트래픽이 늘어나면 Pod를 자동으로 늘려야 해요. 그래서 **HPA**를 설정했어요.

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: was-hpa
spec:
  scaleTargetRef:
    apiVersion: argoproj.io/v1alpha1
    kind: Rollout
    name: was
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

**작동 원리**:
- CPU 사용률이 70% 넘으면 → Pod 증가
- CPU 사용률이 70% 미만이면 → Pod 감소

### 부하 테스트

HPA가 정말 동작하는지 테스트했어요.

```bash
# Apache Bench로 부하 발생
$ ab -n 10000 -c 100 https://www.goupang.shop/petclinic/
```

Grafana에서 CPU 사용률을 확인했어요.

```
CPU: 45% → 70% → 85%

HPA 동작:
Pod 2개 → 4개 → 6개 (약 3분 소요)
```

부하 테스트를 중단하니...

```
CPU: 85% → 50% → 30%

HPA 동작:
Pod 6개 → 4개 → 2개 (약 5분 소요)
```

완벽하게 동작했어요!

### 트러블슈팅: HPA vs ArgoCD 충돌

HPA와 ArgoCD를 함께 사용하니 문제가 생겼어요.

```
HPA: replicas를 4로 변경
ArgoCD: "Git에는 replicas: 2인데?" → 다시 2로 변경
HPA: "왜 2야? 다시 4로!" → 4로 변경
... (무한 반복)
```

**해결**: ArgoCD에 **ignoreDifferences** 설정을 추가했어요.

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
spec:
  ignoreDifferences:
    - group: argoproj.io
      kind: Rollout
      jsonPointers:
        - /spec/replicas
```

이제 ArgoCD가 replicas 필드를 무시하고, HPA가 자유롭게 관리해요!

---

## Chapter 7: CI/CD - Jenkins + ArgoCD GitOps

### Jenkins 파이프라인

코드를 Git에 푸시하면 자동으로 배포되도록 **Jenkins 파이프라인**을 구축했어요.

```groovy
pipeline {
    stages {
        stage('Checkout') {
            steps {
                checkout scm
                script {
                    env.IMAGE_TAG = "v${BUILD_NUMBER}-${GIT_COMMIT_SHORT}"
                }
            }
        }

        stage('Maven Build') {
            steps {
                sh 'mvn clean package -DskipTests'
            }
        }

        stage('Docker Build & Push') {
            steps {
                sh """
                    docker build -t ${ECR_REGISTRY}/${ECR_REPO}:${IMAGE_TAG} .
                    docker push ${ECR_REGISTRY}/${ECR_REPO}:${IMAGE_TAG}
                """
            }
        }

        stage('Update Manifest') {
            steps {
                sshagent(['github-ssh-key']) {
                    sh """
                        git clone ${MANIFEST_REPO} manifest
                        cd manifest
                        sed -i 's|image:.*|image: ${ECR_REGISTRY}/${ECR_REPO}:${IMAGE_TAG}|' was/rollout.yaml
                        git commit -am "Update WAS image to ${IMAGE_TAG}"
                        git push
                    """
                }
            }
        }
    }
}
```

### ArgoCD 자동 동기화

Manifest Repository가 업데이트되면 **ArgoCD가 자동으로 감지**해서 배포해요.

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
spec:
  source:
    repoURL: https://github.com/wlals2/manifestrepo.git
    path: .
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
```

**GitOps 흐름**:
```
1. 개발자가 코드 푸시 (sourece-repo)
2. Jenkins가 빌드 & ECR 푸시
3. Jenkins가 Manifest 업데이트 (manifestrepo)
4. ArgoCD가 변경 감지 (3초 이내)
5. Canary 배포 시작
6. 약 4분 후 배포 완료
```

**총 소요 시간**: 빌드 10분 + Canary 4분 = **약 14분**

---

## Chapter 8: 모니터링 - Prometheus + Grafana

### 8개의 대시보드

모니터링은 **8개 Grafana 대시보드**로 구성했어요.

| UID | 이름 | 용도 |
|-----|------|------|
| 001 | System Overview | 전체 시스템 상태 한눈에 |
| 002 | AWS Infrastructure | ALB, RDS, NAT Gateway |
| 003 | JVM Monitoring | Heap, GC, Thread Pool |
| 004 | Karpenter | 노드 오토스케일링 |
| 005 | OpenCost | 리소스별 비용 분석 |
| 006 | DR Status | Failover 상태 모니터링 |
| 007 | Session Monitoring | Redis 세션 추적 |
| 008 | Argo Rollouts | Canary 배포 진행 상황 |

### 가장 중요한 대시보드: 001 System Overview

이 대시보드를 열면 **한 화면에 모든 게** 보여요.

```
┌─────────────────────────────────────────────────────────┐
│                  System Overview                        │
├─────────────────────────────────────────────────────────┤
│ Cluster Status                                          │
│   Nodes: 5/5 Ready          Pods: 12/12 Running        │
│   CPU: 45%                  Memory: 60%                 │
├─────────────────────────────────────────────────────────┤
│ Application Health                                      │
│   WAS: 2 pods (Healthy)     WEB: 2 pods (Healthy)      │
│   Redis: UP                 RDS: UP                     │
├─────────────────────────────────────────────────────────┤
│ Traffic & Performance                                   │
│   Requests: 150/s           Error Rate: 0.0%            │
│   Latency P95: 120ms        Latency P99: 250ms         │
└─────────────────────────────────────────────────────────┘
```

문제가 생기면 즉시 알 수 있어요!

---

## Chapter 9: DR (재해복구) - Multi-Cloud

### 왜 Multi-Cloud인가요?

"AWS가 다운되면 어떻게 하지?" 하는 공포가 있었어요.

실제로 2021년 AWS us-east-1 리전이 장애가 났을 때, 수많은 서비스가 다운됐거든요.

"백업 계획이 필요해!"

그래서 **Azure**를 Secondary로 추가했어요.

### Route53 Failover

Route53 Health Check로 AWS ALB를 모니터링해요.

```hcl
resource "aws_route53_health_check" "primary" {
  fqdn              = "k8s-petclinicgroup-xxx.elb.amazonaws.com"
  port              = 443
  type              = "HTTPS"
  resource_path     = "/health"
  failure_threshold = 3
  request_interval  = 30
}
```

**작동 원리**:
1. 정상: www.goupang.shop → AWS ALB
2. AWS 장애: Health Check 3회 실패
3. Failover: www.goupang.shop → CloudFront → Azure

### CloudFront + Lambda@Edge

Azure Blob Storage를 CloudFront로 감쌌어요.

**왜 CloudFront가 필요한가요?**

Azure Blob은 자체 도메인만 허용해요. `www.goupang.shop` 요청 시 400 에러가 나죠.

**해결**: Lambda@Edge로 Host 헤더 변환

```javascript
exports.handler = async (event) => {
    const request = event.Records[0].cf.request;

    // Host 헤더를 Azure Blob 도메인으로 변경
    request.headers['host'] = [{
        key: 'Host',
        value: 'drbackupstorage2024.z12.web.core.windows.net'
    }];

    return request;
};
```

### Failover 테스트

실제로 Failover가 동작하는지 테스트했어요.

```bash
# AWS ALB 강제 중단 (테스트)
$ kubectl delete ingress petclinic-ingress -n petclinic

# Route53 Health Check 확인
$ aws route53 get-health-check-status --health-check-id xxx
Status: Unhealthy

# 2분 대기...

# DR 사이트 접속
$ curl -I https://www.goupang.shop/
...
x-amz-cf-id: xxx  ← CloudFront!
```

완벽하게 Failover됐어요! **RTO: 약 2분**

---

## Chapter 10: 비용 분석 - OpenCost

### 월 운영 비용

프로젝트를 운영하면서 가장 궁금했던 건 **"얼마나 드는 거지?"**였어요.

**AWS 비용**:
```
EKS 클러스터:      $73.00
EC2 (t3.medium×5): $50.00
RDS (db.t3.micro): $25.00
ALB:               $16.20
NAT Gateway:       $10.00
Route53:           $1.25
CloudFront:        $10.00
──────────────────────────
AWS 소계:         ~$185/월
```

**Azure 비용** (Warm Standby):
```
VM (Standard_B2s): $20.00
MySQL:             $40.00
Blob Storage:      $5.00
──────────────────────────
Azure 소계:       ~$65/월
```

**총 비용: 약 $250/월**

### OpenCost로 상세 분석

OpenCost를 설치해서 **리소스별 비용**을 추적했어요.

```bash
$ kubectl port-forward -n opencost svc/opencost 9003:9003
```

Grafana Dashboard 005에서 확인한 결과:

```
WAS Pod 1개:     $15/월
WEB Pod 1개:     $3/월
Redis:           $5/월
Prometheus:      $8/월
Grafana:         $4/월
```

"WAS가 생각보다 비싸네..." 하면서 리소스를 최적화했어요.

**최적화 후**:
- CPU Request: 1000m → 500m
- Memory Request: 2Gi → 1Gi
- 월 비용: $15 → $8 (약 50% 절감!)

---

## Chapter 11: 배운 것들

### 1. Infrastructure as Code는 필수

처음에는 "Web Console에서 클릭하면 되는데 왜 Terraform을 써?" 생각했어요.

하지만 6개월 후... Terraform 코드를 보면서 "아, 이래서 이렇게 설정했구나" 하고 다시 깨달았어요.

**Terraform의 장점**:
- 인프라 재구축: 10분이면 완료
- 변경 이력: Git으로 추적
- 문서화: 코드 자체가 문서

### 2. 모니터링 없이는 운영 불가

"애플리케이션만 잘 만들면 되는 거 아냐?" 착각했었어요.

실제로 운영하니...

- CPU 사용률이 갑자기 튀어서 Pod가 OOM으로 죽거나
- Redis 메모리가 부족해서 세션이 삭제되거나
- RDS 연결 수가 고갈되거나

**모니터링 덕분에 문제를 사전에 감지**할 수 있었어요.

### 3. 작은 것부터 시작, 점진적 개선

처음부터 Redis Sentinel, ElastiCache, Multi-AZ NAT Gateway를 다 구축할 필요는 없어요.

1. 단순하게 시작 (단일 Redis, 1개 NAT Gateway)
2. 모니터링 설정
3. 문제 발생 시 개선
4. 반복

이 순서가 가장 효율적이에요.

### 4. GitOps는 게임 체인저

ArgoCD를 도입하기 전:
```
배포 = kubectl apply (수동)
롤백 = kubectl rollout undo (패닉)
이력 = ??? (기억 안 남)
```

ArgoCD 도입 후:
```
배포 = Git Push (자동)
롤백 = Git Revert (안전)
이력 = Git Log (완벽)
```

**Git이 Single Source of Truth**가 되니까 모든 게 명확해졌어요.

---

## 에필로그: 앞으로의 계획

### ⏳ 30분 내 완료 가능

1. **Karpenter Spot 인스턴스** (20분)
   - 현재: On-Demand만 사용
   - 개선: Spot으로 비용 70% 절감

2. **Alert Slack 연동** (10분)
   - 현재: 수동 확인
   - 개선: 자동 알림

### 🔜 다음 단계 (Priority 2)

3. **Redis Sentinel** (2시간)
   - SPOF 해결
   - 자동 Failover

4. **ElastiCache 마이그레이션** (4시간)
   - 완전 관리형
   - Multi-AZ 지원

5. **Istio Service Mesh** (8시간)
   - 트래픽 제어
   - mTLS
   - Observability 강화

---

## 마무리

"단순한 Spring Boot 애플리케이션"에서 시작해서, AWS EKS 기반의 **프로덕션급 클라우드 네이티브 시스템**을 구축했어요.

처음에는 "이게 다 가능할까?" 의심했지만, 하나씩 해나가다 보니 완성됐어요.

**핵심 교훈**:
1. **Infrastructure as Code**: Terraform으로 모든 걸 관리
2. **GitOps**: ArgoCD로 배포 자동화
3. **Observability**: 모니터링 없이는 운영 불가
4. **점진적 개선**: 완벽한 시스템은 없어요. 계속 개선하는 거죠.
5. **비용 최적화**: OpenCost로 추적하고 최적화

이 가이드가 Kubernetes 클러스터를 구축하시는 분들께 도움이 되었으면 좋겠어요.

6개월간의 여정을 함께 나눌 수 있어서 행복했어요. 혹시 궁금한 점이나 개선 아이디어가 있으면 언제든지 공유해 주세요!

함께 성장하는 게 최고니까요! 😊

---

**프로젝트 상태**: Production Ready (2026-01-07 기준)
**GitHub**: [wlals2/manifestrepo](https://github.com/wlals2/manifestrepo)
**접속**: [https://www.goupang.shop/petclinic/](https://www.goupang.shop/petclinic/)

**관련 문서**:
- [Kubernetes 운영 도구 완벽 설치 가이드](../2025-12-25-kubernetes-addons-operational-guide/)
- [Redis Session 모니터링 완벽 가이드](../2025-12-29-redis-session-monitoring-complete-guide/)
