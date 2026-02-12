---
title: "Wazuh Dashboard 외부 접근 트러블슈팅 (upstream connect error → HTTP 전환)"
date: 2026-02-12T18:30:00+09:00
draft: false
categories:
  - study
  - Troubleshooting
tags:
  - Wazuh
  - Istio
  - Kubernetes
  - TLS
  - HTTP
  - VirtualService
  - Service
  - Troubleshooting
summary: "Wazuh Dashboard 외부 접근 시 발생한 upstream connect error를 5번의 시도 끝에 해결한 과정. Port 매핑, Protocol 불일치, TLS Origination 실패, Config 우선순위 문제를 순차적으로 해결했다."
---

## 문제 상황

Wazuh Dashboard에 `http://wazuh.jiminhome.shop`로 접근 시 다음 에러가 발생했다:

```
upstream connect error or disconnect/reset before headers. reset reason: connection termination
```

Grafana, Prometheus 등 다른 서비스는 정상 동작하는데, Wazuh Dashboard만 접근이 안 되는 상황이었다.

## 환경

**홈랩 아키텍처**:
- Windows 워크스테이션: 192.168.1.195
- Kubernetes Control Plane (k8s-cp): 192.168.1.187
- Worker Nodes: 192.168.1.60/61/62 (VMware VM)
- MetalLB LoadBalancer: 192.168.1.200
- Istio Ingress Gateway: HTTP (80) only

**Windows hosts 파일**:
```
192.168.1.200 wazuh.jiminhome.shop
```

**Istio Gateway 설정**:
```yaml
servers:
- port:
    number: 80
    name: http
    protocol: HTTP
  hosts:
  - "*.jiminhome.shop"
# HTTPS (443) 포트는 주석 처리됨
```

**Wazuh Dashboard 초기 설정**:
- Service: LoadBalancer, port 443 (HTTPS)
- VirtualService: port 443
- Dashboard Pod: HTTPS (5601)

## 에러 분석

**"upstream connect error"의 의미**:

Istio Envoy Proxy가 upstream (백엔드 서비스)에 연결을 시도했지만 실패했다는 의미이다.

**가능한 원인**:
1. **Port 불일치**: Gateway와 Service 포트가 다름
2. **Protocol 불일치**: HTTP vs HTTPS
3. **Backend 미응답**: Pod가 준비되지 않음
4. **Network Policy**: L3/L4 차단

**초기 가설**:
- Gateway는 HTTP (80)만 지원
- 하지만 VirtualService는 port 443 사용
- 사용자는 `http://wazuh.jiminhome.shop`로 접근 (HTTP, port 80)
- **Port 불일치 문제로 예상**

## 트러블슈팅 과정

### 시도 1: Service 포트 변경 (443 → 80)

**가설**:
- Gateway가 HTTP (80)만 지원하므로, Service도 port 80으로 변경하면 해결될 것

**변경 사항**:

**dashboard-svc.yaml**:
```yaml
ports:
  - name: http
    port: 80        # 443 → 80
    targetPort: 5601
```

**wazuh-dashboard-virtualservice.yaml**:
```yaml
route:
  - destination:
      host: dashboard.security.svc.cluster.local
      port:
        number: 80  # 443 → 80
```

**결과**: 실패 (여전히 "upstream connect error")

**원인**:
- Service port를 80으로 변경했지만, **targetPort 5601은 여전히 HTTPS**
- Envoy가 HTTP로 요청하면 Dashboard Pod (HTTPS)가 거부함

### 시도 2: TLS Origination (DestinationRule)

**가설**:
- Istio의 **TLS Origination** 기능을 사용하여, Envoy가 HTTP를 받아 HTTPS로 변환

**TLS Origination이란?**:
- Istio Envoy Proxy가 클라이언트의 HTTP 요청을 받아, 백엔드로는 HTTPS로 전달하는 기능
- 클라이언트는 HTTP만 사용하면 되고, TLS 암호화는 Envoy가 담당
- 일반적으로 외부 API 호출 시 사용 (예: `http://my-service` → `https://external-api.com`)

**변경 사항**:

**wazuh-dashboard-destinationrule.yaml** (신규 생성):
```yaml
apiVersion: networking.istio.io/v1
kind: DestinationRule
metadata:
  name: wazuh-dashboard-tls
  namespace: security
spec:
  host: dashboard.security.svc.cluster.local
  trafficPolicy:
    portLevelSettings:
      - port:
          number: 80
        tls:
          mode: SIMPLE  # HTTP → HTTPS 변환
```

**결과**: 실패

**에러**:
```
upstream connect error or disconnect/reset before headers. retried and the latest reset reason: remote connection failure, transport failure reason: TLS error:|268435581:SSL routines:OPENSSL_internal:CERTIFICATE_VERIFY_FAILED:TLS error_end
```

**원인**:
- Dashboard는 **Self-signed 인증서** 사용
- Envoy가 인증서 검증을 시도했지만, CA를 신뢰하지 않아 실패
- `mode: SIMPLE`은 인증서 검증을 수행함

**대안**:
- `mode: DISABLE` (TLS 검증 비활성화)
- 하지만 내부 네트워크에서 TLS를 사용할 필요가 없음
- **HTTP-only로 전환하는 것이 더 단순함**

### 시도 3: 환경 변수로 HTTP 모드 전환

**가설**:
- Dashboard Pod를 HTTP 모드로 전환하면, TLS Origination 없이도 작동

**변경 사항**:

**dashboard-deploy.yaml**:
```yaml
env:
  - name: SERVER_SSL_ENABLED
    value: "false"  # HTTP 모드
```

**Git commit & push → ArgoCD Sync → Pod 재시작**

**결과**: 실패 (여전히 HTTPS로 동작)

**검증**:
```bash
kubectl exec -it wazuh-dashboard-xxx -n security -- curl http://localhost:5601
# 000 (connection refused)

kubectl exec -it wazuh-dashboard-xxx -n security -- curl https://localhost:5601
# 503 (connection accepted)
```

**원인**:
- 환경 변수만으로는 HTTP 모드가 활성화되지 않음
- **Configuration 파일 우선순위 문제**

### 시도 4: Config 파일 직접 수정 (성공!)

**가설**:
- `opensearch_dashboards.yml` 파일이 환경 변수보다 우선순위가 높음
- 파일에서 `server.ssl.enabled: true`를 `false`로 변경해야 함

**Configuration 우선순위**:
1. **Config 파일** (`opensearch_dashboards.yml`) - 최우선
2. 환경 변수 (`SERVER_SSL_ENABLED`)
3. 기본값

**변경 사항**:

**opensearch_dashboards.yml**:
```yaml
server.host: 0.0.0.0
server.port: 5601
# Why: HTTP 모드로 변경 (Gateway HTTP (80)와 일치, 내부 네트워크만 접근)
server.ssl.enabled: false
# server.ssl.key: "/usr/share/wazuh-dashboard/certs/key.pem"
# server.ssl.certificate: "/usr/share/wazuh-dashboard/certs/cert.pem"
opensearch.hosts: https://indexer:9200
opensearch.ssl.verificationMode: none
```

**Git commit & push → ArgoCD Sync → Pod 재시작**

**결과**: 성공!

**검증**:
```bash
kubectl logs wazuh-dashboard-xxx -n security
# Server running at http://0.0.0.0:5601

curl http://wazuh.jiminhome.shop
# 200 OK (Dashboard UI 로드)
```

**동작 원리**:
1. Windows → `http://wazuh.jiminhome.shop` (HTTP, port 80)
2. DNS → 192.168.1.200 (MetalLB)
3. Istio Gateway (HTTP 80) → VirtualService
4. VirtualService → Service `dashboard.security` (port 80)
5. Service → Pod (targetPort 5601, HTTP)
6. Dashboard Pod → Indexer (HTTPS 9200)

### 시도 5: Indexer 연결 실패 해결

**문제**:
- Dashboard UI는 열리지만, Indexer 연결 실패

**에러 로그**:
```
[ConnectionError]: connect ECONNREFUSED 10.102.101.242:9200
```

**원인**:
- Dashboard가 먼저 시작되어 Indexer 연결 실패
- 또는 이전 연결이 캐시됨

**해결**:
```bash
kubectl delete pod wazuh-dashboard-xxx -n security
# Pod 재시작 → 새로운 Indexer 연결 시도
```

**결과**: 성공!

**검증**:
```bash
kubectl logs wazuh-dashboard-xxx -n security
# Server running at http://0.0.0.0:5601
# No Indexer connection errors
```

**Credentials**:
- Username: `kibanaserver`
- Password: `kibanaserver`

## 최종 아키텍처

```
Windows 워크스테이션 (192.168.1.195)
  |
  | http://wazuh.jiminhome.shop (HTTP, port 80)
  |
  v
192.168.1.200 (MetalLB LoadBalancer)
  |
  v
Istio Ingress Gateway (HTTP 80)
  |
  v
VirtualService (wazuh-dashboard-routes)
  | host: dashboard.security.svc.cluster.local
  | port: 80
  v
Service (dashboard.security)
  | ClusterIP: 10.xxx.xxx.xxx
  | port: 80 → targetPort: 5601
  v
Dashboard Pod (wazuh-dashboard)
  | HTTP 5601 (server.ssl.enabled: false)
  |
  | HTTPS 9200
  v
Indexer Pod (wazuh-indexer)
  | HTTPS 9200 (opensearch.ssl.verificationMode: none)
```

**핵심 변경 사항**:
1. Service port: 443 → 80
2. VirtualService port: 443 → 80
3. Dashboard Config: `server.ssl.enabled: true` → `false`
4. Gateway와 전체 경로 일치: HTTP (80)

## 교훈

### 1. Service Port vs targetPort 이해

**Service Port**:
- 클라이언트가 접근하는 포트 (ClusterIP에서 Listen)
- 임의로 선택 가능 (80, 443, 8080 등)

**targetPort**:
- Pod가 실제 Listen하는 포트
- Pod의 containerPort와 일치해야 함

**예시**:
```yaml
Service:
  port: 80          # 클라이언트 → Service (ClusterIP:80)
  targetPort: 5601  # Service → Pod (Pod:5601)

Pod:
  containerPort: 5601  # Pod가 실제 Listen
```

### 2. HTTP/HTTPS Protocol 일치

**Istio 환경에서 Protocol 불일치 시 발생하는 문제**:

| Client | Gateway | Service | Pod | 결과 |
|--------|---------|---------|-----|------|
| HTTP | HTTP | 80 | HTTPS 5601 | ❌ 실패 (Protocol 불일치) |
| HTTP | HTTP | 80 | HTTP 5601 | ✅ 성공 |

**해결 방법**:
1. **TLS Origination** (Istio DestinationRule)
   - HTTP → HTTPS 변환
   - 단, Self-signed 인증서는 검증 실패 가능
2. **Backend를 HTTP로 변경**
   - 내부 네트워크에서는 HTTP만으로도 충분
   - 더 단순하고 안정적

### 3. Configuration 우선순위

**Kubernetes에서 Config 우선순위**:
1. **Config 파일** (ConfigMap으로 마운트)
2. 환경 변수 (`env`)
3. 기본값

**실수**:
- 환경 변수만 변경하고 Config 파일을 수정하지 않음
- Config 파일이 환경 변수를 override

**교훈**:
- **Config 파일을 먼저 확인**하고 수정
- 환경 변수는 보조 수단

### 4. TLS Origination의 한계

**TLS Origination 사용 상황**:
- 외부 API 호출 시 (예: `http://my-service` → `https://external-api.com`)
- 레거시 클라이언트가 HTTP만 지원할 때

**한계**:
- **Self-signed 인증서 검증 실패**
- `mode: DISABLE`로 검증 비활성화 가능하지만, 보안 위험

**대안**:
- 내부 네트워크에서는 HTTP-only 사용
- TLS는 외부 경계에서만 (Istio Gateway)

### 5. Istio Gateway의 역할

**Istio Gateway**:
- 클러스터 외부 → 내부로의 진입점 (North-South 트래픽)
- TLS Termination 수행 (외부 HTTPS → 내부 HTTP)
- 내부는 mTLS 또는 HTTP 사용

**우리 환경**:
- Gateway는 HTTP (80)만 지원 (HTTPS는 주석 처리)
- 모든 서비스는 HTTP로 통일 (Grafana, Prometheus, Wazuh)
- **내부 네트워크 192.168.1.0/24만 접근 가능** (AuthorizationPolicy)

### 6. Pod 재시작의 중요성

**Config 변경 후 반드시 Pod 재시작**:
- ConfigMap 변경 → ArgoCD Sync만으로는 부족
- Pod가 기존 Config를 캐시하고 있을 수 있음
- `kubectl delete pod`로 강제 재시작

**Indexer 연결 실패 시**:
- 이전 연결이 캐시됨
- Pod 재시작으로 새로운 연결 시도

## 참고 자료

- [Istio Traffic Management](https://istio.io/latest/docs/concepts/traffic-management/)
- [Istio DestinationRule - TLS Settings](https://istio.io/latest/docs/reference/config/networking/destination-rule/#ClientTLSSettings)
- [OpenSearch Dashboards Configuration](https://opensearch.org/docs/latest/install-and-configure/configuring-dashboards/)
- [Kubernetes Service](https://kubernetes.io/docs/concepts/services-networking/service/)

## 결론

Wazuh Dashboard 외부 접근 문제를 5번의 시도 끝에 해결했다.

**핵심 원인**:
1. Gateway (HTTP 80) vs Backend (HTTPS 5601) Protocol 불일치
2. Config 파일 우선순위 이해 부족

**최종 해결**:
- **opensearch_dashboards.yml**에서 `server.ssl.enabled: false` 설정
- Service port를 80으로 변경
- 전체 경로를 HTTP로 통일

**교훈**:
- Protocol 일치가 중요 (HTTP vs HTTPS)
- Config 파일 우선순위 확인
- 내부 네트워크에서는 HTTP-only도 충분
- TLS Origination은 Self-signed 인증서와 호환성 문제

이 트러블슈팅 과정을 통해 Istio VirtualService, Service Port 매핑, TLS Origination의 동작 원리를 깊이 이해할 수 있었다.
