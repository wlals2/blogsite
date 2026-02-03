---
title: "Kubernetes 운영 도구 완벽 설치 가이드"
date: 2025-12-25
categories: ["study", "Observability", "Kubernetes"]
tags: ["Kubernetes", "Monitoring", "Metrics Server", "Redis Exporter", "Grafana", "Prometheus", "DevOps"]
description: "Kubernetes 클러스터에서 핵심 아키텍처와 분리된 운영/모니터링 도구를 설치하고 관리하는 완전한 가이드입니다."
slug: kubernetes-addons-operational-guide
---

> **작성 배경**: PetClinic 프로젝트를 운영하면서 핵심 애플리케이션과 분리된 운영 도구들을 체계적으로 관리하기 위해 정리한 가이드입니다.

---

## 왜 이 가이드를 작성하게 되었나요?

Kubernetes 클러스터를 운영하다 보니 고민이 생겼어요. 핵심 애플리케이션(WAS, WEB, Ingress)은 Git으로 관리되는 YAML 파일로 깔끔하게 관리되고 있었는데, Metrics Server나 Redis Exporter 같은 운영 도구들은 어디서 어떻게 설치했는지 기억이 안 나는 거예요.

"이거 나중에 재구축할 때 어떻게 하지?" 하는 불안감이 들더라고요.

그래서 **핵심 아키텍처**와 **운영 도구**를 명확히 분리해서 관리하기로 결정했어요.

### 분리 원칙

```
✅ 핵심 아키텍처 (WAS/WEB/Ingress)
   → k8s-manifests/ YAML로 Git 관리
   → ArgoCD로 자동 배포

✅ 운영 도구 (Metrics, Monitoring)
   → 이 가이드를 참조하여 수동 설치
   → 설치 스크립트 백업 (/tmp/k8s-manifests-backup/)
```

처음에는 "모든 걸 Git으로 관리해야 하나?" 고민했는데, 운영 도구는 설치 빈도가 낮고 클러스터 전역에 영향을 주기 때문에 **별도 가이드로 관리하는 게 낫다**는 결론을 내렸어요.

---

## 1. Metrics Server - HPA의 필수 요소

### 왜 Metrics Server가 필요한가요?

처음 HPA(Horizontal Pod Autoscaler)를 설정했을 때 이런 에러를 만났어요.

```bash
$ kubectl describe hpa was-hpa
...
unable to get metrics for resource cpu: no metrics returned from resource metrics API
```

알고 보니 HPA는 CPU/Memory 메트릭을 기반으로 동작하는데, 이 메트릭을 제공하는 **Metrics Server**가 없었던 거예요.

Metrics Server는 각 노드의 kubelet에서 메트릭을 수집해서 Kubernetes API 서버에 제공하는 역할을 해요. HPA뿐만 아니라 `kubectl top` 명령어도 Metrics Server 없이는 동작하지 않아요.

### 설치 방법

엄청 간단했어요. 공식 YAML 파일 하나로 끝이었죠.

```bash
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
```

하지만 여기서 문제가 발생했어요.

### 트러블슈팅: Pod가 시작되지 않는 문제

```bash
$ kubectl get pods -n kube-system | grep metrics-server
metrics-server-xxx   0/1     CrashLoopBackOff   3          2m
```

로그를 확인해보니...

```bash
$ kubectl logs -n kube-system metrics-server-xxx
E1225 unable to fetch pod metrics: x509: certificate signed by unknown authority
```

**원인**: Metrics Server가 kubelet의 TLS 인증서를 검증하는데, 우리 클러스터의 kubelet 인증서가 자체 서명(self-signed)이었어요.

**해결**: TLS 검증을 비활성화했어요.

```bash
kubectl edit deployment metrics-server -n kube-system

# containers.args에 추가:
- --kubelet-insecure-tls
```

저장하고 나니 Pod가 정상적으로 시작되더라고요!

### 설치 확인

```bash
# Metrics Server Pod 상태
$ kubectl get pods -n kube-system | grep metrics-server
metrics-server-xxx   1/1     Running   0          5m

# 노드 메트릭 확인
$ kubectl top nodes
NAME                                          CPU(cores)   CPU%   MEMORY(bytes)   MEMORY%
ip-10-0-11-123.ap-northeast-2.compute.internal   250m        12%    1500Mi          45%

# Pod 메트릭 확인
$ kubectl top pods -n petclinic
NAME                   CPU(cores)   MEMORY(bytes)
was-xxx                450m         950Mi
web-xxx                50m          100Mi
```

완벽했어요! 이제 HPA가 정상적으로 동작할 준비가 된 거죠.

---

## 2. Redis Exporter - 세션 모니터링의 핵심

### 왜 Redis Exporter가 필요했나요?

WAS Pod가 여러 개 동작하면서 Spring Session + Redis로 세션 클러스터링을 구현했어요. 그런데 문제가 생겼어요.

"지금 Redis에 세션이 몇 개 저장되어 있지?"
"Redis 메모리는 충분한가?"
"세션이 제대로 공유되고 있나?"

이런 질문들에 답할 수가 없었어요. Redis는 Prometheus 메트릭을 직접 노출하지 않거든요.

그래서 **Redis Exporter**가 필요했어요. Redis의 INFO 명령 결과를 Prometheus 형식으로 변환해주는 도구죠.

```
Redis (INFO 명령) → Redis Exporter (변환) → Prometheus (수집) → Grafana (시각화)
```

### 설치 과정

Redis Exporter는 간단한 Deployment로 배포했어요.

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: redis-exporter
  namespace: petclinic
spec:
  replicas: 1
  selector:
    matchLabels:
      app: redis-exporter
  template:
    metadata:
      labels:
        app: redis-exporter
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/port: "9121"
        prometheus.io/path: "/metrics"
    spec:
      containers:
      - name: redis-exporter
        image: oliver006/redis_exporter:latest
        ports:
        - containerPort: 9121
          name: metrics
        env:
        - name: REDIS_ADDR
          value: "redis-master.petclinic.svc.cluster.local:6379"
        resources:
          requests:
            cpu: 50m
            memory: 64Mi
          limits:
            cpu: 100m
            memory: 128Mi
```

**핵심 포인트**:

1. **annotation**: Prometheus가 자동으로 발견할 수 있도록 설정했어요.
2. **REDIS_ADDR**: Redis 주소를 Kubernetes Service DNS 형식으로 지정했어요.
3. **리소스**: Redis Exporter는 가볍게 동작하기 때문에 최소한의 리소스만 할당했어요.

### 설치 확인

```bash
# Pod 확인
$ kubectl get pods -n petclinic | grep redis-exporter
redis-exporter-6c689496d5-hmc28   1/1     Running   0          1m

# 메트릭 확인
$ kubectl port-forward -n petclinic svc/redis-exporter 9121:9121
$ curl http://localhost:9121/metrics | grep redis_up
redis_up 1
```

`redis_up 1`이 나오면 성공이에요! Redis 연결이 정상이라는 뜻이죠.

### Prometheus가 수집하는지 확인

하지만 여기서 끝이 아니었어요. Redis Exporter가 메트릭을 노출해도 **Prometheus가 수집하지 않으면 소용없거든요**.

처음에는 annotation만 추가하면 자동으로 수집될 줄 알았는데... Grafana 대시보드에 데이터가 안 보이더라고요.

**원인**: Prometheus Operator를 사용하는 환경에서는 **ServiceMonitor**라는 리소스가 필요했어요.

```yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: redis-exporter
  namespace: petclinic
spec:
  selector:
    matchLabels:
      app: redis-exporter
  endpoints:
  - port: metrics
    interval: 30s
```

이걸 추가하고 나니 Prometheus가 정상적으로 메트릭을 수집하기 시작했어요!

```bash
# Prometheus 타겟 확인
$ kubectl port-forward -n monitoring svc/kube-prometheus-prometheus 9090:9090
# 브라우저: http://localhost:9090/targets
# redis-exporter (1/1 up) ✅
```

---

## 3. Grafana Dashboard 007 - 세션 모니터링 대시보드

### 대시보드가 필요했던 이유

메트릭을 수집했으니 이제 시각화가 필요했어요. PromQL로 직접 쿼리하는 건 너무 불편하거든요.

Grafana Dashboard를 만들면서 고민했던 건 "어떤 메트릭을 보여줄 것인가?"였어요.

**핵심 질문**:
- Redis가 살아있나?
- 현재 세션 수는?
- 메모리는 충분한가?
- WAS Pod들이 정상적으로 연결되어 있나?

이런 질문에 답할 수 있는 대시보드를 만들었어요.

### 자동 로드 vs 수동 Import

대시보드를 배포하는 방법은 두 가지예요.

#### 옵션 A: ConfigMap으로 자동 로드

Grafana는 `grafana_dashboard: "1"` 레이블이 있는 ConfigMap을 자동으로 로드해요.

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: grafana-dashboard-007-v4
  namespace: monitoring
  labels:
    grafana_dashboard: "1"
data:
  007-session-monitoring.json: |
    {
      "dashboard": { ... }
    }
```

이 방법의 장점은 Grafana Pod를 재시작하면 자동으로 대시보드가 로드된다는 거예요.

```bash
kubectl rollout restart deployment kube-prometheus-grafana -n monitoring
```

하지만 처음에는 안 됐어요...

**트러블슈팅**: Dashboard 자동 로드 안 됨

Grafana Pod에 **Dashboard Sidecar** 컨테이너가 있는데, 이 컨테이너의 로그를 확인해봤어요.

```bash
$ kubectl logs -n monitoring kube-prometheus-grafana-xxx -c grafana-sc-dashboard
Error: label selector does not match: grafana_dashboard=1
```

**원인**: ConfigMap의 레이블 키가 `grafana_dashboard`가 아니라 다른 값이었어요.

**해결**: 레이블을 정확히 맞춰주니 자동 로드가 되기 시작했어요!

#### 옵션 B: Grafana UI에서 수동 Import

자동 로드가 안 되거나 빠르게 테스트하고 싶을 때는 수동 Import를 했어요.

```
1. Grafana 접속: https://www.goupang.shop/grafana
2. + (Create) → Import
3. Upload JSON file 선택
4. UID: 007-session-monitoring
5. Folder: General
6. Import 클릭
```

이 방법이 더 직관적이고 빠르긴 한데, Git으로 관리가 안 된다는 단점이 있어요.

---

## 4. Monitoring Ingress - 외부 접근 설정

### 왜 Monitoring Ingress가 필요한가요?

Grafana와 Prometheus는 클러스터 내부 Service로만 노출되어 있어서, 외부에서 접근하려면 매번 `kubectl port-forward`를 해야 했어요.

```bash
kubectl port-forward -n monitoring svc/kube-prometheus-grafana 3000:80
```

이게 너무 불편하더라고요. 그래서 **ALB Ingress**를 만들어서 HTTPS로 접근할 수 있게 했어요.

### ALB Ingress Group 활용

여기서 중요한 건 **ALB Ingress Group**을 활용했다는 거예요.

```yaml
annotations:
  alb.ingress.kubernetes.io/group.name: petclinic-group  # 기존 ALB 재사용
```

이렇게 하면 기존 PetClinic 애플리케이션의 ALB를 재사용해서 **비용을 절감**할 수 있어요!

ALB는 개당 약 $16/월이거든요. Ingress마다 새로운 ALB를 만들면 비용이 엄청나게 늘어나요.

### 전체 Ingress 설정

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: monitoring-ingress
  namespace: monitoring
  annotations:
    alb.ingress.kubernetes.io/scheme: internet-facing
    alb.ingress.kubernetes.io/target-type: ip
    alb.ingress.kubernetes.io/listen-ports: '[{"HTTP": 80}, {"HTTPS": 443}]'
    alb.ingress.kubernetes.io/ssl-redirect: '443'
    alb.ingress.kubernetes.io/certificate-arn: arn:aws:acm:ap-northeast-2:YOUR_ACCOUNT:certificate/YOUR_CERT_ID
    alb.ingress.kubernetes.io/group.name: petclinic-group
    alb.ingress.kubernetes.io/healthcheck-path: /grafana/api/health
spec:
  ingressClassName: alb
  rules:
  - host: www.goupang.shop
    http:
      paths:
      - path: /grafana
        pathType: Prefix
        backend:
          service:
            name: kube-prometheus-grafana
            port:
              number: 80
      - path: /prometheus
        pathType: Prefix
        backend:
          service:
            name: kube-prometheus-kube-prome-prometheus
            port:
              number: 9090
```

**핵심 포인트**:

1. **ssl-redirect**: HTTP 요청을 자동으로 HTTPS로 리다이렉트
2. **certificate-arn**: ACM 인증서 ARN 지정
3. **healthcheck-path**: Grafana의 Health Check 엔드포인트 지정

### 주의사항: ACM 인증서 ARN

처음에 설치 가이드를 작성할 때 ACM 인증서 ARN을 하드코딩했다가 큰 실수를 했어요.

```bash
# 잘못된 방법
alb.ingress.kubernetes.io/certificate-arn: arn:aws:acm:ap-northeast-2:123456789012:certificate/abc-def

# 올바른 방법
# 먼저 인증서 ARN 확인
aws acm list-certificates --region ap-northeast-2

# 출력에서 goupang.shop 인증서의 ARN 복사
# 그 ARN을 annotation에 입력
```

다른 AWS 계정에서 재구축할 때 인증서 ARN이 달라서 Ingress가 생성되지 않는 문제가 있었어요.

### 설치 확인

```bash
# Ingress 확인
$ kubectl get ingress -n monitoring
NAME                  CLASS   HOSTS                 ADDRESS                          PORTS     AGE
monitoring-ingress    alb     www.goupang.shop      k8s-petclinicgroup-xxx.elb...   80, 443   5m

# 접속 테스트
$ curl -I https://www.goupang.shop/grafana/api/health
HTTP/2 200
```

성공! 이제 브라우저에서 `https://www.goupang.shop/grafana`로 접근할 수 있어요.

---

## 5. 전체 설치 스크립트 - 자동화의 힘

### 왜 스크립트가 필요한가요?

각 도구를 하나씩 설치하면서 "나중에 재구축할 때 이걸 다 기억할 수 있을까?" 고민했어요.

그래서 **한 번에 모든 애드온을 설치하는 스크립트**를 만들었어요.

### install-addons.sh

```bash
#!/bin/bash

echo "📦 Kubernetes 애드온 설치 중..."

# 1. Metrics Server
echo "1️⃣ Metrics Server 설치..."
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml

# 2. Redis Exporter
echo "2️⃣ Redis Exporter 설치..."
kubectl apply -f /tmp/k8s-manifests-backup/redis-exporter.yaml

# 3. Grafana Dashboard 007
echo "3️⃣ Grafana Dashboard 007 설치..."
kubectl apply -f /tmp/k8s-manifests-backup/grafana-dashboard-007-configmap.yaml
kubectl rollout restart deployment kube-prometheus-grafana -n monitoring

# 4. Monitoring Ingress
echo "4️⃣ Monitoring Ingress 설치..."
kubectl apply -f /tmp/k8s-manifests-backup/monitoring-ingress.yaml

echo "✅ 애드온 설치 완료"
```

### 실행 방법

```bash
chmod +x /tmp/k8s-manifests-backup/install-addons.sh
/tmp/k8s-manifests-backup/install-addons.sh
```

이렇게 하면 약 5분 만에 모든 애드온이 설치돼요!

### 백업 파일 관리

중요한 건 **모든 YAML 파일을 백업**해둔 거예요.

```bash
/tmp/k8s-manifests-backup/
├── metrics-server.yaml
├── redis-exporter.yaml
├── grafana-dashboard-007-configmap.yaml
├── grafana-dashboard-007-session.json
├── monitoring-ingress.yaml
├── install-addons.sh
└── setup-eks-addons.sh
```

`/tmp` 디렉터리는 재부팅 시 삭제되기 때문에, 영구 백업도 했어요.

```bash
cp -r /tmp/k8s-manifests-backup ~/bespin-project/docs/operations/backup/
```

---

## 6. 운영하면서 배운 것들

### 1. 트러블슈팅은 로그부터

문제가 생기면 무조건 로그부터 확인했어요.

```bash
# Pod 로그
kubectl logs -n petclinic redis-exporter-xxx

# Prometheus Operator 로그
kubectl logs -n monitoring prometheus-operator-xxx

# Grafana Sidecar 로그
kubectl logs -n monitoring kube-prometheus-grafana-xxx -c grafana-sc-dashboard
```

특히 Grafana Sidecar 로그는 대시보드 자동 로드 문제를 해결하는 데 큰 도움이 됐어요.

### 2. 백업의 중요성

처음에는 "kubectl로 바로 설치하면 되지 뭐" 생각했는데, 나중에 재구축하려니 어떤 옵션을 줬는지 기억이 안 나더라고요.

**꼭 백업하세요!**
- YAML 파일
- 설치 명령어
- 트러블슈팅 과정

### 3. 문서화의 힘

이 가이드를 작성하면서 느낀 건, **문서화는 미래의 나를 위한 것**이라는 거예요.

3개월 후에 "Redis Exporter 어떻게 설치했지?" 고민하지 않아도 되니까 정말 편해요.

---

## 7. 다음 단계

현재 구축은 완료됐지만, 개선할 점들이 있어요.

### ⏳ 30분 내 완료 가능

1. **Alert 설정** (20분)
   - PrometheusRule 추가
   - Redis Down, 세션 수 급증 등 알림 설정

2. **Metrics Server TLS 정식 인증서** (10분)
   - 현재는 `--kubelet-insecure-tls` 사용 중
   - 프로덕션에서는 정식 인증서 설정 권장

### 🔜 선택 사항

3. **Karpenter 노드 모니터링** (1시간)
   - Karpenter 메트릭 수집
   - 노드 오토스케일링 상태 모니터링

4. **OpenCost 통합** (1시간)
   - 비용 분석 대시보드 추가
   - 리소스별 비용 추적

---

## 마무리

처음에는 "운영 도구 설치가 이렇게 복잡할 줄이야..." 했는데, 하나씩 정리하고 나니 큰 그림이 보이더라고요.

**핵심은 분리**예요.
- 애플리케이션은 Git으로 관리
- 운영 도구는 별도 가이드로 관리
- 백업은 필수

이 가이드가 Kubernetes 클러스터를 운영하시는 분들께 도움이 되었으면 좋겠어요!

혹시 궁금한 점이나 개선 아이디어가 있으면 언제든지 피드백 주세요. 함께 성장하는 게 최고니까요! 😊

---

**참고 문서**:
- [Session Monitoring Guide](../2025-12-29-redis-session-monitoring-complete-guide/)
- [Metrics Server 공식 문서](https://github.com/kubernetes-sigs/metrics-server)
- [Redis Exporter 공식 문서](https://github.com/oliver006/redis_exporter)
