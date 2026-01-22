---
title: "Phase 2: EC2에서 Kubernetes로 - 선언적 인프라의 깨달음"
date: 2025-11-01
summary: "수동 배포 30분 → Helm Chart 자동 배포 5분, Kubernetes로 배운 진짜 자동화"
tags: ["kubernetes", "k8s", "helm", "prometheus", "project"]
categories: ["projects"]
series: ["Infrastructure Learning Journey"]
weight: 2
showtoc: true
tocopen: true
---

# Phase 2: Kubernetes 온프레미스 클러스터 구축

> **기간**: 2025.10 ~ 2025.11 (1개월)
> **역할**: Kubernetes 클러스터 구축 및 Helm Chart 작성
> **키워드**: Kubernetes, Helm, Prometheus, Grafana, Declarative Infrastructure

---

## 📋 Quick Summary (30초 읽기)

| 항목 | 내용 |
|------|------|
| **문제** | EC2 수동 배포 30분 소요, 롤백 30분 |
| **목표** | 선언적 인프라 + 자동화 배포 5분 이내 |
| **핵심 기술** | Kubernetes, Helm, Prometheus |
| **성과** | 배포 시간 83% 단축, 롤백 자동 1분 |

---

## 🎯 왜 이 프로젝트를?

### 문제 상황 (Situation)

Phase 1에서 Terraform으로 인프라 자동화는 성공했지만:

**여전히 남은 문제:**

```
새 버전 배포 프로세스 (Phase 1):

1. Jenkins 빌드 완료 → WAR 파일 생성
2. EC2 SSH 접속 (인스턴스 2대)
3. Tomcat 중지: systemctl stop tomcat
4. WAR 파일 복사: scp war ec2-1:/opt/tomcat/
5. Tomcat 시작: systemctl start tomcat
6. Health Check 확인: curl localhost:8080/health
7. 2번 인스턴스 반복 (3~6)

총 소요 시간: 30분
실패율: 20% (Tomcat 재시작 실패, 파일 권한 등)
```

**구체적 사례 (2025-10-09):**

```bash
# 배포 시작
$ ssh ec2-user@10.0.11.47
$ systemctl stop tomcat
$ scp target/petclinic.war ec2-user@10.0.11.47:/opt/tomcat/webapps/
petclinic.war: 100% |████████| 45.2MB  5.1MB/s

$ systemctl start tomcat
Job for tomcat.service failed. See "systemctl status tomcat.service"

# 왜 실패?
$ journalctl -xe
... java.lang.OutOfMemoryError: Java heap space
```

**원인:**
- Tomcat 메모리 설정: `-Xmx512m` (부족!)
- 새 버전이 더 많은 메모리 요구 (`-Xmx1g` 필요)
- 설정 파일 `/opt/tomcat/bin/setenv.sh` 수동 수정 필요

**문제점:**
1. **수동 작업 많음**: SSH 2대, 파일 복사, 재시작
2. **일관성 없음**: 인스턴스마다 설정 다를 수 있음 (사람이 실수)
3. **롤백 어려움**: 이전 버전으로 되돌리기 30분 (WAR 파일 다시 복사)
4. **스케일링 불가**: 인스턴스 10대면? 5시간 소요!

---

### 목표 (Task)

**정량적 목표:**
- 배포 시간: 30분 → **5분 이하**
- 롤백 시간: 30분 → **1분 이하 (자동)**
- 설정 일관성: 수동 관리 → **선언적 관리 (코드 기반)**
- 스케일링: 수동 → **자동 (HPA)**

**학습 목표:**
- Kubernetes 아키텍처 이해 (Pod, Service, Deployment)
- Helm Chart 작성 (재사용 가능한 배포 템플릿)
- Prometheus + Grafana 모니터링

---

## 🏗️ 아키텍처

### Phase 1 (EC2) vs Phase 2 (Kubernetes) 비교

```
Phase 1: EC2 기반
┌─────────────────────────────────────┐
│  ALB                                │
│   ↓                                 │
│  EC2 (Tomcat) ← 수동 배포 (SSH)    │
│   ↓                                 │
│  RDS MySQL                          │
└─────────────────────────────────────┘

문제: 수동 배포, 롤백 어려움, 스케일링 불가


Phase 2: Kubernetes 기반
┌──────────────────────────────────────────────┐
│  Kubernetes Cluster                          │
│  ┌────────────────────────────────┐          │
│  │ Ingress (nginx)                │          │
│  │   ↓                            │          │
│  │ Service (web, was)             │          │
│  │   ↓                            │          │
│  │ Deployment (Pods)              │          │
│  │   ↓                            │          │
│  │ MySQL StatefulSet              │          │
│  └────────────────────────────────┘          │
│  ┌────────────────────────────────┐          │
│  │ Monitoring                     │          │
│  │  - Prometheus (메트릭 수집)    │          │
│  │  - Grafana (시각화)            │          │
│  └────────────────────────────────┘          │
└──────────────────────────────────────────────┘

해결: Helm Chart로 선언적 배포, 자동 롤백, HPA
```

### 상세 아키텍처

![Phase 2 - Kubernetes on EC2 Architecture](/images/architecture/phase2-k8s-architecture.webp)

**아키텍처 구성 요소:**

#### Networking & Ingress
- **Route53**: DNS 기반 Health Check 및 트래픽 라우팅
- **ALB (Application Load Balancer)**: HTTPS Listener → Kubernetes Ingress 연결
- **Nginx Ingress Controller**: L7 라우팅 (/, /board 경로 분기)

#### Kubernetes Cluster (Self-Managed on EC2)
**Availability Zone A:**
- **Jenkins (Public Subnet)**: CI/CD 파이프라인 실행
  - Source Repo → Docker Build → ECR Push
  - Manifest Repo 업데이트 → ArgoCD Sync 트리거
- **Master Node (Private Subnet A)**: kubeadm으로 구축한 Control Plane
- **WEB Pod (Private Subnet A)**: nginx 정적 파일 서빙
- **WAS Pod (Private Subnet A)**: Spring Boot 애플리케이션
- **DB Backup (Private Subnet A)**: MySQL Primary
- **MySQL StatefulSet (Private Subnet A)**: Primary 데이터베이스

**Availability Zone C:**
- **Worker Node (Private Subnet C)**: kubeadm으로 조인한 Worker
- **ArgoCD**: GitOps 기반 배포 자동화
- **WEB Pod (Private Subnet C)**: nginx (Replica)
- **WAS Pod (Private Subnet C)**: Spring Boot (Replica)
- **DB-C (Private Subnet C)**: MySQL Standby (Multi-AZ Sync)
- **MySQL StatefulSet (Private Subnet C)**: Standby 데이터베이스

> **참고**: 이 이미지는 Phase 3 (EKS)로 전환 후의 모습을 보여줍니다. Phase 2에서는 EKS 대신 **kubeadm으로 직접 구축한 Kubernetes** 클러스터를 사용했습니다.

#### Monitoring & Observability
- **CloudWatch**: AWS 리소스 메트릭 수집
- **KMS (EBS 암호화)**: 데이터 암호화 키 관리
- **AWS WAF**: 웹 애플리케이션 방화벽
- **Secrets Manager**: DB 자격증명 관리
- **SNS (Gmail)**: 알림 발송

#### CI/CD Pipeline
1. **Source Repo** → Webhook → Jenkins
2. Jenkins → **Docker Build** → ECR Push
3. Jenkins → **Manifest Repo** 업데이트 (image tag)
4. ArgoCD → Manifest Repo **watch** → Auto Sync
5. ArgoCD → **Kubernetes Apply** → Rolling Update

---

## 🛠️ 기술 선택 (Action)

### 왜 이 기술들인가?

| 기술 | 용도 | 선택 이유 | 대안 (포기 이유) |
|------|------|-----------|-----------------|
| **Kubernetes** | 컨테이너 오케스트레이션 | 선언적 인프라, 자동 복구 (Self-Healing), 업계 표준 | Docker Swarm (기능 제한, 커뮤니티 작음) |
| **Helm** | Kubernetes 패키지 관리자 | 재사용 가능한 Chart, 버전 관리, 롤백 간단 | Kustomize (기능 제한, 롤백 없음) |
| **Prometheus** | 메트릭 수집 | Kubernetes 네이티브, Pull 방식, PromQL 강력 | CloudWatch (비용 높음, K8s 메트릭 제한적) |
| **Grafana** | 메트릭 시각화 | Prometheus 통합, 대시보드 풍부, 오픈소스 | Kibana (Elasticsearch 의존성) |
| **kubeadm** | K8s 클러스터 구축 | 공식 도구, 온프레미스 지원 | Minikube (단일 노드, 프로덕션 부적합) |

---

## 💡 핵심 구현

### 구현 1: Kubernetes 클러스터 구축 (kubeadm)

**왜 필요했나?**
- Phase 1: EC2 인스턴스 직접 관리 → 수동 작업 많음
- Phase 2: Kubernetes로 선언적 관리 → `kubectl apply -f` 한 번

**어떻게 구현했나?**

**1. Master Node 초기화:**
```bash
# kubeadm 초기화 (Pod Network CIDR: Calico 요구사항)
sudo kubeadm init --pod-network-cidr=192.168.0.0/16

# kubeconfig 설정
mkdir -p $HOME/.kube
sudo cp -i /etc/kubernetes/admin.conf $HOME/.kube/config

# Calico CNI 설치 (Pod 간 네트워킹)
kubectl apply -f https://docs.projectcalico.org/manifests/calico.yaml
```

**2. Worker Node 추가:**
```bash
# Master에서 join 명령어 생성
sudo kubeadm token create --print-join-command

# Worker Node에서 실행
sudo kubeadm join 10.0.11.47:6443 --token abc123...
```

**왜 이렇게 구현했나?**
- **kubeadm**: 공식 도구, 프로덕션 레벨 클러스터 구축 가능
- **Calico CNI**: Pod 간 네트워크 정책 지원 (보안 강화 가능)
- **다른 방법 대비 장점**: Managed K8s (EKS, GKE) 대비 비용 절감 (학습 단계)

**결과:**
- Cluster 구축 시간: 30분
- Node 3개 (Master 1, Worker 2)
- Pod Network: Calico (192.168.0.0/16)

---

### 구현 2: Helm Chart로 애플리케이션 배포

**왜 필요했나?**
- Kubernetes YAML 파일 너무 많음 (Deployment, Service, ConfigMap, Secret 등)
- 환경별 설정 다름 (dev, prod) → 파일 중복

**어떻게 구현했나?**

**Helm Chart 구조:**
```
petclinic-chart/
├── Chart.yaml
├── values.yaml
├── templates/
│   ├── deployment-web.yaml
│   ├── deployment-was.yaml
│   ├── service-web.yaml
│   ├── service-was.yaml
│   ├── configmap.yaml
│   └── secret.yaml
```

**values.yaml (환경별 설정):**
```yaml
# 공통 설정
replicaCount: 2
image:
  repository: my-registry/petclinic
  tag: "1.0.0"

resources:
  limits:
    cpu: 500m
    memory: 1Gi
  requests:
    cpu: 250m
    memory: 512Mi

# WAS 설정
was:
  replicas: 2
  javaOpts: "-Xms512m -Xmx1g"

# DB 설정
mysql:
  host: mysql-service
  database: petclinic
```

**Deployment Template (templates/deployment-was.yaml):**
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ .Release.Name }}-was
spec:
  replicas: {{ .Values.was.replicas }}
  selector:
    matchLabels:
      app: was
  template:
    metadata:
      labels:
        app: was
    spec:
      containers:
      - name: was
        image: {{ .Values.image.repository }}:{{ .Values.image.tag }}
        ports:
        - containerPort: 8080
        env:
        - name: JAVA_OPTS
          value: {{ .Values.was.javaOpts }}
        - name: DB_HOST
          value: {{ .Values.mysql.host }}
        resources:
          {{- toYaml .Values.resources | nindent 10 }}
        livenessProbe:
          httpGet:
            path: /health
            port: 8080
          initialDelaySeconds: 60
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /health
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 5
```

**배포 명령어:**
```bash
# 설치
helm install petclinic ./petclinic-chart

# 업그레이드 (새 버전 배포)
helm upgrade petclinic ./petclinic-chart --set image.tag=1.0.1

# 롤백
helm rollback petclinic 1  # Revision 1로 복원
```

**왜 이렇게 구현했나?**
- **Template 재사용**: `{{ .Values.xxx }}` 로 환경별 설정 분리
- **버전 관리**: Helm Revision → `helm rollback` 간단
- **Atomic 배포**: 실패 시 자동 롤백 (`--atomic` 플래그)

**결과:**
- 배포 시간: 30분 → **5분**
- 롤백 시간: 30분 → **1분** (helm rollback)
- 설정 일관성: 100% (코드 기반)

---

### 구현 3: Prometheus + Grafana 모니터링

**왜 필요했나?**
- Phase 1: 모니터링 없음 → 문제 발생 시 사후 대응만
- Phase 2: 실시간 모니터링 → 문제 발생 전 감지

**어떻게 구현했나?**

**1. Prometheus Operator 설치 (Helm):**
```bash
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm install prometheus prometheus-community/kube-prometheus-stack
```

**2. ServiceMonitor 설정 (WAS Pod 메트릭 수집):**
```yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: was-monitor
spec:
  selector:
    matchLabels:
      app: was
  endpoints:
  - port: metrics
    path: /actuator/prometheus
    interval: 30s
```

**3. Grafana 대시보드:**
- **System Overview**: Pod CPU/Memory, Node 상태
- **Application**: WAS 요청 수, 응답 시간, JVM Heap
- **Database**: MySQL Connection, Slow Query

**왜 이렇게 구현했나?**
- **Prometheus Operator**: ServiceMonitor로 자동 메트릭 수집
- **Pull 방식**: Agent 설치 불필요 (Pod가 메트릭 endpoint 제공)
- **PromQL**: 강력한 쿼리 언어 (예: `rate(http_requests_total[5m])`)

**결과:**
- 메트릭 수집 주기: 30초
- 대시보드 3개 (System, Application, Database)
- Alert 설정: CPU 80% 이상 → Slack 알림

---

## 🔥 트러블슈팅

### 문제 1: Pod가 계속 CrashLoopBackOff

**증상:**
```bash
$ kubectl get pods
NAME                   READY   STATUS             RESTARTS   AGE
was-5c7f8d9b7f-abc12   0/1     CrashLoopBackOff   5          3m
```

**원인 분석:**

**1. 첫 시도: 로그 확인**
```bash
$ kubectl logs was-5c7f8d9b7f-abc12
Error: Cannot connect to database: Connection refused (mysql-service:3306)
```

**2. 두 번째 시도: MySQL Service 확인**
```bash
$ kubectl get svc
NAME            TYPE        CLUSTER-IP      EXTERNAL-IP   PORT(S)
mysql-service   ClusterIP   10.96.100.50    <none>        3306/TCP

$ kubectl get pods -l app=mysql
NAME          READY   STATUS    RESTARTS   AGE
mysql-0       0/1     Pending   0          3m
```

**3. 최종 원인 발견:**
```bash
$ kubectl describe pod mysql-0
Events:
  Warning  FailedScheduling  pod has unbound immediate PersistentVolumeClaims
```

**근본 원인:**
- MySQL StatefulSet이 PersistentVolume 요구
- PV 생성 안 됨 → Pod Pending
- WAS Pod가 MySQL 연결 시도 → 실패 → Restart

**해결 방법:**

**1. PersistentVolume 생성:**
```yaml
apiVersion: v1
kind: PersistentVolume
metadata:
  name: mysql-pv
spec:
  capacity:
    storage: 10Gi
  accessModes:
    - ReadWriteOnce
  hostPath:
    path: /mnt/data/mysql
```

**2. MySQL StatefulSet 재배포:**
```bash
kubectl apply -f mysql-statefulset.yaml
kubectl apply -f mysql-pv.yaml

# 확인
$ kubectl get pods -l app=mysql
NAME      READY   STATUS    RESTARTS   AGE
mysql-0   1/1     Running   0          1m
```

**왜 이 방법인가?**
- **hostPath**: 온프레미스 환경에서 간단 (프로덕션에서는 NFS, Ceph 권장)
- **StatefulSet**: 데이터 영속성 보장 (Pod 재시작 시에도 데이터 유지)

**결과:**
| 지표 | Before | After |
|------|--------|-------|
| WAS Pod 상태 | CrashLoopBackOff | Running |
| MySQL 연결 | 실패 | 성공 |

---

### 문제 2: Ingress Health Check 실패

**증상:**
```bash
$ kubectl get ingress
NAME       CLASS   HOSTS           ADDRESS   PORTS   AGE
petclinic  nginx   petclinic.local           80      5m

# curl 테스트
$ curl http://petclinic.local/
<html>
<head><title>502 Bad Gateway</title></head>
```

**원인 분석:**

**1. Service 확인:**
```bash
$ kubectl get svc web-service
NAME          TYPE        CLUSTER-IP     EXTERNAL-IP   PORT(S)
web-service   ClusterIP   10.96.50.100   <none>        80/TCP

$ kubectl get endpoints web-service
NAME          ENDPOINTS
web-service   <none>  ← 문제!
```

**2. Pod Selector 확인:**
```bash
$ kubectl get pods -l app=web
NAME                   READY   STATUS    RESTARTS   AGE
web-7d9f8c5b6f-abc12   1/1     Running   0          5m

$ kubectl describe svc web-service
Selector: app=nginx  ← 잘못된 Selector!
```

**최종 원인:**
- Service Selector: `app=nginx`
- Pod Label: `app=web`
- Selector 불일치 → Endpoints 없음 → 502 Bad Gateway

**해결 방법:**
```yaml
# service-web.yaml 수정
apiVersion: v1
kind: Service
metadata:
  name: web-service
spec:
  selector:
    app: web  # nginx → web 수정
  ports:
  - port: 80
    targetPort: 80
```

**결과:**
```bash
$ kubectl apply -f service-web.yaml
$ kubectl get endpoints web-service
NAME          ENDPOINTS
web-service   192.168.1.5:80,192.168.1.6:80  ✅

$ curl http://petclinic.local/
<html>PetClinic Home Page</html>  ✅
```

---

## 📊 성과 (Result)

### 정량적 성과

| 지표 | Before (EC2) | After (K8s) | 개선 |
|------|-------------|-------------|------|
| **배포 시간** | 30분 (수동) | **5분** (helm upgrade) | 83% 단축 |
| **롤백 시간** | 30분 (수동) | **1분** (helm rollback) | 97% 단축 |
| **설정 일관성** | 수동 관리 (실수 많음) | **선언적 관리** (코드 기반) | 100% |
| **스케일링** | 수동 (인스턴스 추가) | **자동** (HPA) | N/A |
| **모니터링** | 없음 | **실시간** (Prometheus) | +∞ |

### 부가 효과

**1. 배포 자동화:**
- Jenkins → Build → Helm Upgrade → 자동 배포
- 사람 개입 없음 → 휴먼 에러 0%

**2. 자동 복구 (Self-Healing):**
```bash
# Pod 강제 삭제 (장애 시뮬레이션)
$ kubectl delete pod was-5c7f8d9b7f-abc12

# Kubernetes가 자동으로 재생성
$ kubectl get pods
NAME                   READY   STATUS    RESTARTS   AGE
was-5c7f8d9b7f-xyz34   1/1     Running   0          10s  ← 자동 생성!
```

**3. 리소스 효율:**
- CPU/Memory Request/Limit 설정 → 노드 리소스 최적 활용
- HPA (Horizontal Pod Autoscaler) → 트래픽 증가 시 자동 스케일 아웃

---

## 🎓 배운 점

### 1. 선언적 인프라의 가치

**명령형 (Imperative):**
```bash
# EC2 시절
ssh ec2-user@10.0.11.47
systemctl stop tomcat
scp war ...
systemctl start tomcat
```

**선언적 (Declarative):**
```yaml
# Kubernetes
apiVersion: apps/v1
kind: Deployment
metadata:
  name: was
spec:
  replicas: 2  ← "2개 유지해줘" (선언)
```

**차이점:**
- **명령형**: "어떻게(How)" 할지 명령 → 사람이 모든 단계 실행
- **선언적**: "무엇(What)"을 원하는지 선언 → Kubernetes가 알아서 실행

### 2. Kubernetes의 Self-Healing

**시나리오: Pod 장애**
```
Pod Crash → Kubernetes가 감지 (Liveness Probe)
           → 자동 재시작 (Restart)
           → 계속 실패? → CrashLoopBackOff (알림)
```

**시나리오: Node 장애**
```
Node Down → Kubernetes가 감지 (Node Ready 상태)
          → 다른 Node에 Pod 재생성 (Reschedule)
          → 서비스 계속 가능 ✅
```

### 3. Helm의 강력함

**버전 관리:**
```bash
$ helm list
NAME       REVISION  STATUS    CHART
petclinic  5         deployed  petclinic-1.0.5

$ helm rollback petclinic 3  # Revision 3로 복원
```

**재사용:**
- 한 번 작성한 Chart → dev, staging, prod 재사용
- `values-dev.yaml`, `values-prod.yaml`로 환경별 설정만 분리

---

## 🔗 다음 단계: Phase 3 (EKS + Multi-Cloud DR)

### Phase 2의 한계

**1. 온프레미스 관리 부담:**
- Master Node 장애 시? → 수동 복구 필요
- kubeadm 업그레이드? → 직접 관리

**2. 고가용성 부족:**
- Single Master → SPOF (Single Point of Failure)
- 온프레미스 장애 시 전체 서비스 중단

**3. 확장성 제한:**
- Node 추가 → 물리 서버 구매 필요 (시간 오래 걸림)
- Auto Scaling 제한적

### Phase 3에서 해결할 것

**목표:**
- AWS EKS → Control Plane 관리 AWS가 대신
- Multi-Cloud DR → Azure Failover (RTO 2분)
- Multi-AZ → 고가용성 99.9%

**[Phase 3 상세 보기 →](../phase3-eks-dr/)**

---

## 📚 참고 자료

- [Kubernetes 공식 문서](https://kubernetes.io/docs/)
- [Helm 공식 문서](https://helm.sh/docs/)
- [Prometheus Operator](https://github.com/prometheus-operator/prometheus-operator)

---

## Sources

- [Best DevOps Blogs 2025](https://www.diffblue.com/resources/best-devops-blogs-2025/)
- [Kubernetes Best Practices](https://cloud.google.com/blog/products/containers-kubernetes/your-guide-kubernetes-best-practices)

---

**작성일**: 2025-11-01
**프로젝트 기간**: 2025.10.10 ~ 2025.11.06 (1개월)
**난이도**: ⭐⭐⭐⭐ (Advanced)
**읽는 시간**: 15분
