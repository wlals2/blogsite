---
title: "MetalLB와 SSH Port Forwarding을 활용한 Kubernetes 클러스터 외부 접근 구성"
date: 2025-11-06T18:37:51+09:00
draft: false
categories: ["Metallb 를 이용한 AWS와 K8s 연결 "]
tags: ["LB","metallb","aws","ssh-portforward","clusterIP","podIP","metalIP"]
description: "MetalLB와 SSH Port Forwarding을 활용한 Kubernetes 클러스터 외부 접근 구성"
author: "늦찌민"
---

# MetalLB와 SSH Port Forwarding을 활용한 Kubernetes 클러스터 외부 접근 구성

## 문제 상황

AWS EC2에서 Kubernetes 클러스터를 운영할 때, LoadBalancer 타입의 서비스를 생성하면 일반적으로 클라우드 프로바이더의 로드밸런서(ELB/ALB)가 자동으로 프로비저닝됩니다. 하지만 자체 구축한 Kubernetes 클러스터(self-managed)에서는 이러한 클라우드 로드밸런서 통합이 없어 LoadBalancer 서비스가 `Pending` 상태로 남게 됩니다.

```bash
# LoadBalancer가 Pending 상태로 유지됨
NAME   TYPE           CLUSTER-IP       EXTERNAL-IP   PORT(S)
ui     LoadBalancer   10.108.202.210        80:31126/TCP
```

## MetalLB란?

**MetalLB**는 클라우드 프로바이더가 제공하지 않는 환경(온프레미스, 자체 구축 클러스터)에서 LoadBalancer 타입 서비스를 구현할 수 있게 해주는 네트워크 로드밸런서입니다.

### MetalLB의 핵심 개념

#### 1. Layer 2 Mode (ARP 기반)
- 클러스터 노드 중 하나가 서비스 IP의 "소유자"가 됨
- ARP 요청에 응답하여 자신의 MAC 주소를 알려줌
- 간단하지만 단일 노드에 트래픽 집중

#### 2. BGP Mode
- 라우터와 BGP 프로토콜로 통신
- 진정한 로드밸런싱 가능
- 더 복잡하지만 프로덕션 환경에 적합

### MetalLB IP Pool 설정

```yaml
apiVersion: metallb.io/v1beta1
kind: IPAddressPool
metadata:
  name: first-pool
  namespace: metallb-system
spec:
  addresses:
  - 10.0.0.240-10.0.0.250  # 할당할 IP 범위
```

이 설정으로 LoadBalancer 서비스가 생성되면 MetalLB가 이 범위에서 IP를 자동으로 할당합니다.

```bash
# MetalLB가 IP를 할당한 상태
NAME   TYPE           CLUSTER-IP       EXTERNAL-IP   PORT(S)
ui     LoadBalancer   10.108.202.210   10.0.0.241    80:31126/TCP
```

## 하지만 여전히 남은 문제
MetalLB가 `10.0.0.241`이라는 EXTERNAL-IP를 할당했지만, 이것은 **AWS EC2 인스턴스의 내부(Private) IP 대역**입니다.

```
로컬 PC ──X──> 10.0.0.241 (접근 불가능)
               │
               └─ AWS EC2 Private Network 내부에만 존재
```

### 왜 접근이 안 될까?

1. **10.0.0.0/24는 AWS VPC 내부 Private IP 대역**
2. 인터넷에서 직접 라우팅 불가능
3. AWS Security Group, VPC 라우팅 테이블에도 없는 "가상" IP

## SSH Port Forwarding으로 해결하기

SSH Port Forwarding을 사용하면 로컬 PC에서 마치 클러스터 내부에 있는 것처럼 접근할 수 있습니다.

### SSH Port Forwarding의 작동 원리

```
로컬 PC                    SSH 터널                   EC2 인스턴스
localhost:8080  ────────> SSH Connection ────────> 10.0.0.241:80
                          (암호화된 터널)
```

### Local Port Forwarding 명령어

```bash
ssh -L 8080:10.0.0.241:80 ubuntu@ -i your-key.pem
```

#### 명령어 분석

- `-L` : Local Port Forwarding 옵션
- `8080` : 로컬 PC에서 열 포트
- `10.0.0.241:80` : 최종 목적지 (MetalLB가 할당한 IP:포트)
- `ubuntu@<EC2-Public-IP>` : SSH 접속할 EC2 인스턴스

### 트래픽 흐름

```
1. 브라우저: localhost:8080 접속
   ↓
2. SSH 클라이언트: 트래픽을 SSH 터널로 전송
   ↓
3. EC2 인스턴스: 터널을 통해 받은 트래픽을 10.0.0.241:80으로 전달
   ↓
4. MetalLB: 해당 IP를 가진 서비스로 라우팅
   ↓
5. Kubernetes Service: Pod로 트래픽 전달
   ↓
6. 응답이 같은 경로로 역순으로 돌아옴
```

## 전체 아키텍처 이해

```
┌─────────────────────────────────────────────────────────────┐
│ Local PC                                                     │
│                                                              │
│  Browser → localhost:8080                                   │
│                ↓                                            │
│           SSH Client                                         │
└────────────────┼────────────────────────────────────────────┘
                 │ SSH Tunnel (암호화)
                 ↓
┌─────────────────────────────────────────────────────────────┐
│ AWS EC2 Instance (Public IP: x.x.x.x)                       │
│                                                              │
│  SSH Server → 10.0.0.241:80 forwarding                     │
│                     ↓                                       │
│  ┌──────────────────────────────────────────┐              │
│  │ Kubernetes Cluster                        │              │
│  │                                           │              │
│  │  ┌─────────────────────────────────┐    │              │
│  │  │ MetalLB (10.0.0.240-250 관리)  │    │              │
│  │  └──────────────┬──────────────────┘    │              │
│  │                 ↓                         │              │
│  │  ┌─────────────────────────────────┐    │              │
│  │  │ Service: ui                     │    │              │
│  │  │ Type: LoadBalancer              │    │              │
│  │  │ EXTERNAL-IP: 10.0.0.241        │    │              │
│  │  │ Port: 80 → 31126               │    │              │
│  │  └──────────────┬──────────────────┘    │              │
│  │                 ↓                         │              │
│  │  ┌─────────────────────────────────┐    │              │
│  │  │ Pods (ui application)           │    │              │
│  │  │ - pod-1: 10.244.x.x:80         │    │              │
│  │  │ - pod-2: 10.244.x.x:80         │    │              │
│  │  └─────────────────────────────────┘    │              │
│  └──────────────────────────────────────────┘              │
└─────────────────────────────────────────────────────────────┘
```

## MetalLB IP와 다른 네트워크 대역 비교

### 1. Cluster IP (10.108.x.x)
- Kubernetes 내부에서만 사용
- kube-proxy가 관리
- 클러스터 외부에서 직접 접근 불가

### 2. Pod IP (10.244.x.x)
- CNI(Container Network Interface)가 할당
- Pod 간 통신에 사용
- Service를 통해 추상화됨

### 3. MetalLB IP (10.0.0.240-250)
- **EC2 인스턴스가 속한 서브넷 대역과 동일**
- LoadBalancer Service의 EXTERNAL-IP로 사용
- 물리적으로는 존재하지 않는 "가상" IP
- Layer 2 모드에서는 노드의 네트워크 인터페이스가 ARP로 응답

### 왜 EC2 서브넷 대역을 사용하는가?

```yaml
# EC2 인스턴스
- Private IP: 10.0.0.100
- Subnet: 10.0.0.0/24

# MetalLB IP Pool
addresses:
  - 10.0.0.240-10.0.0.250  # 같은 서브넷 내 미사용 IP 범위
```

**이유:**
1. **동일 네트워크 세그먼트**: EC2 인스턴스의 네트워크 인터페이스가 ARP 요청에 응답 가능
2. **라우팅 불필요**: 같은 서브넷이므로 추가 라우팅 설정 없이 통신 가능
3. **Layer 2 동작**: ARP 프로토콜이 동일 네트워크 내에서만 작동

## SSH Port Forwarding의 대안들

### 1. AWS Load Balancer 사용
```bash
# EKS를 사용하거나 AWS Load Balancer Controller 설치
kubectl apply -k "github.com/aws/eks-charts/stable/aws-load-balancer-controller/crds"
```
- 장점: 실제 Public IP 할당, 자동 스케일링
- 단점: 비용 발생, 복잡한 설정

### 2. NodePort + Security Group
```yaml
apiVersion: v1
kind: Service
spec:
  type: NodePort
  ports:
  - port: 80
    nodePort: 31126
```
```bash
# EC2 Public IP로 직접 접근
http://:31126
```
- 장점: 간단, 무료
- 단점: 높은 포트 번호, Security Group 설정 필요

### 3. Ingress + NodePort
```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: ui-ingress
spec:
  rules:
  - http:
      paths:
      - path: /
        pathType: Prefix
        backend:
          service:
            name: ui
            port:
              number: 80
```
- 장점: HTTP/HTTPS 라우팅, 도메인 기반 라우팅
- 단점: Ingress Controller 필요

## 실전 활용 시나리오

### 개발/테스트 환경
```bash
# 로컬에서 개발하면서 클러스터 서비스 테스트
ssh -L 8080:10.0.0.241:80 ubuntu@ec2-instance -i key.pem

# 브라우저에서
http://localhost:8080
```

### 여러 서비스 동시 포워딩
```bash
ssh -L 8080:10.0.0.241:80 \
    -L 8081:10.0.0.242:80 \
    -L 3000:10.0.0.243:3000 \
    ubuntu@ec2-instance -i key.pem
```

### 백그라운드 실행
```bash
# 터널을 백그라운드로 유지
ssh -f -N -L 8080:10.0.0.241:80 ubuntu@ec2-instance -i key.pem

# 프로세스 확인
ps aux | grep ssh

# 종료
kill 
```

## 보안 고려사항

### 1. SSH Key 관리
```bash
# 키 권한 설정 (필수)
chmod 400 your-key.pem
```

### 2. 포트 바인딩 제한
```bash
# 127.0.0.1에만 바인딩 (더 안전)
ssh -L 127.0.0.1:8080:10.0.0.241:80 ubuntu@ec2-instance

# 0.0.0.0에 바인딩 (위험 - 외부 노출)
ssh -L 0.0.0.0:8080:10.0.0.241:80 ubuntu@ec2-instance
```

### 3. AWS Security Group
```
Inbound Rules:
- SSH (22) from My IP only
- 다른 포트는 필요한 경우만 열기
```

## 정리

### MetalLB의 역할
- **클라우드 없는 환경에서 LoadBalancer 구현**
- AWS VPC 내부 IP 대역에서 가상 IP 할당
- Layer 2/BGP 모드로 트래픽 라우팅

### SSH Port Forwarding의 역할
- **Private IP를 로컬에서 접근 가능하게 만듦**
- 암호화된 터널로 안전한 통신
- 개발/테스트 환경에서 유용

### 언제 사용하는가?

| 상황 | 권장 방법 |
|------|----------|
| 개발/테스트 (일시적) | SSH Port Forwarding |
| 개발/테스트 (지속적) | NodePort + Security Group |
| 프로덕션 (소규모) | MetalLB + Ingress |
| 프로덕션 (대규모) | AWS Load Balancer |

### 핵심 개념

1. **MetalLB는 "가상의" 외부 IP를 제공**하지만, 실제로는 클러스터 내부 메커니즘
2. **SSH Port Forwarding은 "터널"**을 만들어 Private 네트워크를 로컬처럼 사용
3. **10.0.0.241은 물리적으로 존재하지 않는 IP**지만, MetalLB가 ARP로 응답하여 실제처럼 동작
4. **프로덕션 환경에서는 적절한 Load Balancer 솔루션** 사용 권장

## 참고 자료

- [MetalLB 공식 문서](https://metallb.universe.tf/)
- [Kubernetes Service Types](https://kubernetes.io/docs/concepts/services-networking/service/#publishing-services-service-types)
- [SSH Port Forwarding Guide](https://www.ssh.com/academy/ssh/tunneling/example)

---

이 구성은 학습과 개발 목적으로는 훌륭하지만, 프로덕션에서는 보안, 고가용성, 모니터링 등을 고려한 적절한 솔루션을 선택해야 합니다.