---
title: "Amazon EKS 완전 가이드"
date: 2025-11-07T09:31:48+09:00
draft: false
categories: ["study", "Cloud & Terraform"]
tags: ["eks","k8s","k9s","study","karpenter"]
description: "Amazon EKS 완전 가이드"
author: "늦찌민"
series: ["EKS 정복기"]
---

## 목차
1. [EKS란 무엇인가?](#eks란-무엇인가)
2. [EKS 아키텍처와 동작 원리](#eks-아키텍처와-동작-원리)
3. [EKS vs 직접 구축한 K8s 비교](#eks-vs-직접-구축한-k8s-비교)
4. [실전: EKS 클러스터 시작하기](#실전-eks-클러스터-시작하기)
5. [첫 애플리케이션 배포](#첫-애플리케이션-배포)
6. [비용 최적화 팁](#비용-최적화-팁)

## EKS란 무엇인가?

**Amazon EKS(Elastic Kubernetes Service)**는 AWS에서 관리형 Kubernetes 서비스입니다.

### 핵심 특징
- **Control Plane 관리**: AWS가 마스터 노드(API 서버, etcd, 스케줄러 등)를 완전히 관리
- **고가용성**: Control Plane이 멀티 AZ로 자동 배포
- **자동 업그레이드**: Kubernetes 버전 관리 간소화
- **보안**: IAM과 네이티브 통합

---

## EKS 아키텍처와 동작 원리

### 전체 구조

```

┌─────────────────────────────────────────────────────┐
│                   AWS 관리 영역                      │
│  ┌──────────────────────────────────────────┐       │
│  │         EKS Control Plane                │       │
│  │  ┌──────────┐  ┌──────────┐  ┌────────┐  │       │
│  │  │API Server│  │Scheduler │  │  etcd  │  │       │
│  │  └──────────┘  └──────────┘  └────────┘  │       │
│  │  (Multi-AZ로 자동 배포 및 관리)           │        │
│  └──────────────────────────────────────────┘       │
└─────────────────────────────────────────────────────┘
                      ↕ (kubectl, API 호출)
┌─────────────────────────────────────────────────────┐
│                사용자 관리 영역 (VPC)                |
│                                                     │
│  ┌─────────────────────────────────────────────┐    │
│  │           Worker Nodes (EC2 or Fargate)     │    │
│  │                                             │    │
│  │  [Node 1]    [Node 2]    [Node 3]           │    │
│  │   Pods        Pods        Pods              │    │
│  │   kubelet     kubelet     kubelet           │    │
│  │                                             │    │
│  └─────────────────────────────────────────────┘    │
│                                                     │
│  ┌─────────────────────────────────────────────┐    │
│  │         VPC CNI Plugin                      │    │
│  │  (Pod에 VPC IP 할당)                         │    │
│  └─────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────┘

```

### 주요 컴포넌트 동작 방식

#### 1. **Control Plane (AWS 관리)**

```

┌─────────────────────────────────────────┐
│  API Server                              │
│  - 모든 요청의 진입점                      │
│  - IAM 인증/인가 처리                      │
│  - Multi-AZ HA 구성                       │
└─────────────────────────────────────────┘
         ↓
┌─────────────────────────────────────────┐
│  etcd (분산 key-value 저장소)             │
│  - 클러스터 상태 저장                      │
│  - 자동 백업 및 암호화                     │
└─────────────────────────────────────────┘
         ↓
┌─────────────────────────────────────────┐
│  Scheduler & Controller Manager          │
│  - Pod 배치 결정                          │
│  - Desired state 유지                     │
└─────────────────────────────────────────┘

```

#### 2. **Data Plane (사용자 관리)**

```

Worker Node (EC2 Instance)
├── kubelet: Control Plane과 통신, Pod 관리
├── kube-proxy: 네트워크 규칙 관리
├── Container Runtime: Docker/containerd
└── VPC CNI: Pod에 ENI IP 할당

```

#### 3. **네트워킹 흐름**

```

[외부 요청] 
    ↓
[LoadBalancer Service / Ingress]
    ↓
[VPC CNI가 할당한 Pod IP]
    ↓
[Container 내 애플리케이션]

```

**특징**: EKS는 VPC CNI를 사용하여 Pod에 VPC IP를 직접 할당합니다.
- Pod가 VPC의 1급 시민처럼 동작
- Security Group을 Pod 레벨에서 적용 가능
- 기존 AWS 네트워킹 도구와 통합 쉬움

---
## EKS vs 직접 구축한 K8s 비교

| 항목 | EKS | 직접 구축 (kops, kubeadm) |
|------|-----|---------------------------|
| Control Plane 관리 | AWS 자동 관리 | 사용자가 직접 관리 |
| HA 구성 | 자동 (Multi-AZ) | 수동 설정 필요 |
| 업그레이드 | 원클릭 업그레이드 | 복잡한 수동 작업 |
| 비용 | $0.10/시간 (약 $73/월) + 노드 비용 | 노드 비용만 (하지만 마스터 노드 포함) |
| 시작 시간 | 15-20분 | 1-2시간+ |
| 보안 패치 | AWS 자동 적용 | 수동 적용 |

---

## 실전: EKS 클러스터 시작하기

### 사전 준비사항

```bash
# 1. AWS CLI 설치 및 설정
aws configure
# Access Key, Secret Key, Region 입력

# 2. kubectl 설치
curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"
sudo install -o root -g root -m 0755 kubectl /usr/local/bin/kubectl

# 3. eksctl 설치 (EKS 관리 CLI 도구)
curl --silent --location "https://github.com/weaveworks/eksctl/releases/latest/download/eksctl_$(uname -s)_amd64.tar.gz" | tar xz -C /tmp
sudo mv /tmp/eksctl /usr/local/bin

# 버전 확인
eksctl version
kubectl version --client

```

### 방법 1: eksctl로 간단하게 시작 (추천)

```bash
# 기본 클러스터 생성 (가장 간단)
eksctl create cluster \
  --name my-first-eks \
  --region ap-northeast-2 \
  --nodegroup-name standard-workers \
  --node-type t3.medium \
  --nodes 2 \
  --nodes-min 1 \
  --nodes-max 3 \
  --managed

# 약 15-20분 소요
# - VPC, 서브넷 자동 생성
# - Control Plane 생성
# - Worker Node Group 생성
# - kubeconfig 자동 설정

```

### 방법 2: YAML 파일로 세밀하게 제어

```yaml
# cluster-config.yaml
apiVersion: eksctl.io/v1alpha5
kind: ClusterConfig

metadata:
  name: my-production-eks
  region: ap-northeast-2
  version: "1.28"

# VPC 설정
vpc:
  cidr: 10.0.0.0/16
  nat:
    gateway: Single  # 비용 절감: NAT Gateway 1개만
  
# IAM OIDC Provider (IRSA를 위해 필요)
iam:
  withOIDC: true

# Managed Node Group
managedNodeGroups:
  - name: general-workload
    instanceType: t3.medium
    desiredCapacity: 2
    minSize: 1
    maxSize: 4
    volumeSize: 20
    volumeType: gp3
    labels:
      role: general
    tags:
      Environment: production
    iam:
      withAddonPolicies:
        imageBuilder: true
        autoScaler: true
        ebs: true
        efs: true
        albIngress: true
        cloudWatch: true

# Add-ons
addons:
  - name: vpc-cni
    version: latest
  - name: coredns
    version: latest
  - name: kube-proxy
    version: latest

```

```bash
# YAML로 클러스터 생성
eksctl create cluster -f cluster-config.yaml

```

### 클러스터 생성 확인

```bash
# 클러스터 상태 확인
eksctl get cluster --region ap-northeast-2

# kubectl 연결 확인
kubectl get nodes
kubectl get pods -A

# 클러스터 정보 상세 확인
kubectl cluster-info

```

---

## 첫 애플리케이션 배포

### 1. 간단한 Nginx 배포

```yaml
# nginx-deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: nginx-demo
spec:
  replicas: 3
  selector:
    matchLabels:
      app: nginx
  template:
    metadata:
      labels:
        app: nginx
    spec:
      containers:
      - name: nginx
        image: nginx:latest
        ports:
        - containerPort: 80
---
apiVersion: v1
kind: Service
metadata:
  name: nginx-service
spec:
  type: LoadBalancer  # AWS ELB 자동 생성
  selector:
    app: nginx
  ports:
  - protocol: TCP
    port: 80
    targetPort: 80

```

```bash
# 배포
kubectl apply -f nginx-deployment.yaml

# 서비스 확인 (LoadBalancer DNS 확인)
kubectl get svc nginx-service

# 출력 예시:
# NAME            TYPE           EXTERNAL-IP                                   
# nginx-service   LoadBalancer   a7f2...ap-northeast-2.elb.amazonaws.com

# 브라우저에서 EXTERNAL-IP로 접속하면 Nginx 페이지 확인 가능

```

### 2. 실전 예제: Hugo 블로그 배포 (당신의 경험 활용)

```yaml
# hugo-blog-deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: hugo-blog
spec:
  replicas: 2
  selector:
    matchLabels:
      app: hugo-blog
  template:
    metadata:
      labels:
        app: hugo-blog
    spec:
      containers:
      - name: hugo
        image: your-dockerhub/hugo-blog:latest
        ports:
        - containerPort: 1313
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
  name: hugo-blog-service
spec:
  type: LoadBalancer
  selector:
    app: hugo-blog
  ports:
  - protocol: TCP
    port: 80
    targetPort: 1313

```

### 3. Ingress 컨트롤러 설치 (비용 절감)

LoadBalancer 대신 Ingress를 사용하면 하나의 ALB로 여러 서비스 처리 가능:

```bash
# AWS Load Balancer Controller 설치
kubectl apply -k "github.com/aws/eks-charts/stable/aws-load-balancer-controller//crds?ref=master"

helm repo add eks https://aws.github.io/eks-charts
helm install aws-load-balancer-controller eks/aws-load-balancer-controller \
  -n kube-system \
  --set clusterName=my-first-eks \
  --set serviceAccount.create=false \
  --set serviceAccount.name=aws-load-balancer-controller

```

```yaml
# ingress.yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: my-ingress
  annotations:
    alb.ingress.kubernetes.io/scheme: internet-facing
    alb.ingress.kubernetes.io/target-type: ip
spec:
  ingressClassName: alb
  rules:
  - host: blog.jiminhome.shop
    http:
      paths:
      - path: /
        pathType: Prefix
        backend:
          service:
            name: hugo-blog-service
            port:
              number: 80

```

---

## 비용 최적화 팁

### 1. Spot Instances 활용

```yaml
managedNodeGroups:
  - name: spot-workers
    instanceTypes: 
      - t3.medium
      - t3a.medium
    spot: true
    desiredCapacity: 2
    minSize: 1
    maxSize: 5

```

**절감 효과**: 최대 90% 비용 절감 (비프로덕션 워크로드에 적합)

### 2. Fargate 사용 (서버리스 노드)

```yaml
fargateProfiles:
  - name: fp-default
    selectors:
      - namespace: default
      - namespace: production

```

**장점**: 
- EC2 관리 불필요
- 사용한 만큼만 과금
- Auto Scaling 자동

**단점**: 
- 비용이 더 높을 수 있음
- 일부 기능 제약 (DaemonSet, HostNetwork 등)

### 3. Cluster Autoscaler 설정

```bash
kubectl apply -f https://raw.githubusercontent.com/kubernetes/autoscaler/master/cluster-autoscaler/cloudprovider/aws/examples/cluster-autoscaler-autodiscover.yaml

```

워크로드에 따라 노드를 자동으로 증감하여 비용 절감.

### 4. 비용 모니터링

```bash
# Kubecost 설치 (무료 오픈소스)
kubectl create namespace kubecost
helm repo add kubecost https://kubecost.github.io/cost-analyzer/
helm install kubecost kubecost/cost-analyzer --namespace kubecost

```

---

## 클러스터 관리 명령어 모음

### 기본 관리

```bash
# 클러스터 목록
eksctl get cluster

# 노드그룹 확인
eksctl get nodegroup --cluster my-first-eks

# 클러스터 스케일링
eksctl scale nodegroup --cluster my-first-eks \
  --name standard-workers --nodes 5

# 클러스터 삭제 (주의!)
eksctl delete cluster --name my-first-eks

```

### 디버깅

```bash
# 노드 상태 확인
kubectl describe node 

# Pod 로그 확인
kubectl logs  -f

# Pod 내부 접속
kubectl exec -it  -- /bin/bash

# 리소스 사용량 확인
kubectl top nodes
kubectl top pods

```

### kubeconfig 관리

```bash
# kubeconfig 업데이트
aws eks update-kubeconfig --region ap-northeast-2 --name my-first-eks

# 여러 클러스터 간 전환
kubectl config get-contexts
kubectl config use-context 

```

---

## 다음 단계: 고급 기능

### 1. **서비스 메시 (Istio/Linkerd)**
마이크로서비스 간 통신 관리, 트래픽 제어, 보안 강화

### 2. **GitOps (ArgoCD/Flux)**
Git을 단일 진실 공급원으로 사용하여 자동 배포

### 3. **Observability**
- Prometheus + Grafana: 메트릭 수집
- ELK Stack: 로그 집계
- Jaeger: 분산 추적

### 4. **보안 강화**
- Pod Security Standards
- Network Policies
- Secrets 암호화 (KMS 통합)
- IRSA (IAM Roles for Service Accounts)

---

## 요약

### EKS의 핵심 가치
1. **관리 부담 감소**: Control Plane을 AWS가 관리
2. **빠른 시작**: 15분 안에 프로덕션 레디 클러스터
3. **AWS 통합**: IAM, VPC, ELB, CloudWatch 등과 네이티브 통합
4. **확장성**: 수천 개의 Pod를 손쉽게 관리

### 시작 체크리스트
- [ ] AWS CLI, kubectl, eksctl 설치
- [ ] 첫 클러스터 생성 (`eksctl create cluster`)
- [ ] 샘플 앱 배포 (Nginx 등)
- [ ] LoadBalancer/Ingress로 외부 노출
- [ ] 비용 모니터링 설정
- [ ] Auto Scaling 설정

---

## 유용한 리소스

- [공식 EKS 문서](https://docs.aws.amazon.com/eks/)
- [eksctl GitHub](https://github.com/weaveworks/eksctl)
- [AWS EKS Workshop](https://www.eksworkshop.com/)
- [Kubernetes 공식 문서](https://kubernetes.io/docs/)

---

**작성일**: 2025-11-07  
**버전**: EKS 1.28 기준
