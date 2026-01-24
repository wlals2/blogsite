# Istio Service Mesh 현재 상태

**최종 업데이트**: 2026-01-24
**상태**: ✅ Nginx Ingress 제거, Istio Gateway 일원화 완료

---

## 현재 아키텍처 (2026-01-24)

### 전체 트래픽 플로우

```
[사용자]
  ↓ HTTPS
[Cloudflare CDN]
  ├─ SSL/TLS 종료
  ├─ DDoS 방어
  └─ Origin: 192.168.1.200
  ↓ HTTP (평문)
[MetalLB LoadBalancer: 192.168.1.200]
  ↓
[Istio Gateway] (istio-ingressgateway Pod)
  ├─ blog.jiminhome.shop → blog-system/web-service:80
  ├─ monitoring.jiminhome.shop → monitoring/grafana:3000
  ├─ argocd.jiminhome.shop → argocd/argocd-server:80
  └─ kiali.jiminhome.shop → istio-system/kiali:20001
  ↓ mTLS DISABLE (Gateway → Service는 평문)
[Kubernetes Services]
  ├─ web-service → web Pods (nginx)
  ├─ grafana → Grafana Pods
  ├─ argocd-server → ArgoCD Pods
  └─ kiali → Kiali Pods
  ↓
[web Pod 내부 nginx proxy]
  ├─ / → 정적 파일 (Hugo 빌드 결과)
  └─ /api → was-service:8080
      ↓ mTLS ISTIO_MUTUAL (선택 가능, 현재 DISABLE)
      [was Pod] (Spring Boot)
      ↓ 평문 TCP (Istio mesh 제외)
      [mysql Pod]
```

## 주요 변경 사항 (2026-01-24)

### Before (2026-01-23까지)

```
Cloudflare → MetalLB (192.168.1.200) → Nginx Ingress → Istio Gateway → Services
                                          ↓ 중복 Hop  ↓
                                       L7 라우팅    L7 라우팅
```

### After (2026-01-24)

```
Cloudflare → MetalLB (192.168.1.200) → Istio Gateway → Services
                                          ↓
                                     단일 L7 진입점
```

**개선 효과**:
- ✅ Nginx Ingress 제거 (중복 레이어 제거)
- ✅ 아키텍처 단순화
- ✅ 레이턴시 감소 (Hop 1개 제거)
- ✅ Istio 기능 완전 활용 (Retry, Timeout, Circuit Breaker)

## 서비스별 라우팅

### 1. blog.jiminhome.shop (블로그)

**VirtualService**: `blog-system/blog-routes.yaml`

```yaml
spec:
  hosts:
  - "blog.jiminhome.shop"
  gateways:
  - blog-gateway
  http:
  - match:
    - uri:
        prefix: "/api"  # WAS API
    route:
    - destination:
        host: web-service  # WEB nginx → WAS proxy
        subset: stable
        port:
          number: 80
  - match:
    - uri:
        prefix: "/"  # 정적 파일
    route:
    - destination:
        host: web-service
        subset: stable
```

**내부 nginx proxy** (`web Pod` 내부):
```nginx
location /api {
    proxy_pass http://was-service:8080;  # Istio mesh 통과
}
```

### 2. monitoring.jiminhome.shop (Grafana)

**VirtualService**: `monitoring/monitoring-routes.yaml`

```yaml
spec:
  hosts:
  - "monitoring.jiminhome.shop"
  gateways:
  - blog-system/blog-gateway  # Cross-namespace 참조
  http:
  - route:
    - destination:
        host: grafana  # Same namespace
        port:
          number: 3000
```

### 3. argocd.jiminhome.shop (ArgoCD)

**VirtualService**: `argocd/argocd-routes.yaml`

```yaml
spec:
  hosts:
  - "argocd.jiminhome.shop"
  gateways:
  - blog-system/blog-gateway
  http:
  - route:
    - destination:
        host: argocd-server
        port:
          number: 80
```

### 4. kiali.jiminhome.shop (Kiali)

**VirtualService**: `istio-system/kiali-routes.yaml`

```yaml
spec:
  hosts:
  - "kiali.jiminhome.shop"
  gateways:
  - blog-system/blog-gateway
  http:
  - route:
    - destination:
        host: kiali
        port:
          number: 20001
```

## Istio 리소스 현황

### Gateway

**파일**: `blog-system/istio-gateway.yaml`

```yaml
apiVersion: networking.istio.io/v1beta1
kind: Gateway
metadata:
  name: blog-gateway
  namespace: blog-system
spec:
  selector:
    istio: ingressgateway
  servers:
  - port:
      number: 80
      name: http
      protocol: HTTP
    hosts:
    - "*.jiminhome.shop"  # 모든 서브도메인
```

### DestinationRule (web-service)

**파일**: `blog-system/web-destinationrule.yaml`

```yaml
spec:
  host: web-service
  trafficPolicy:
    tls:
      mode: DISABLE  # Gateway → Service는 평문
  subsets:
  - name: stable  # Argo Rollouts stable
  - name: canary  # Argo Rollouts canary
```

### LoadBalancer Service (Istio Gateway)

**파일**: `istio-system/istio-ingressgateway-svc.yaml`

```yaml
apiVersion: v1
kind: Service
metadata:
  name: istio-ingressgateway
  namespace: istio-system
  annotations:
    metallb.universe.tf/ip-allocated-from-pool: local-pool
    metallb.universe.tf/loadBalancerIPs: 192.168.1.200
spec:
  type: LoadBalancer
  selector:
    istio: ingressgateway
  ports:
  - name: http2
    port: 80
    targetPort: 8080
  - name: https
    port: 443
    targetPort: 8443
```

## Istio Mesh 설정

### mTLS 모드

**PeerAuthentication**: PERMISSIVE (기본값)

```yaml
apiVersion: security.istio.io/v1beta1
kind: PeerAuthentication
metadata:
  name: default
  namespace: blog-system
spec:
  mtls:
    mode: PERMISSIVE  # 평문과 mTLS 모두 허용
```

**DestinationRule mTLS**:
- Gateway → Service: `DISABLE` (평문 HTTP)
- Service ↔ Service: `DISABLE` 또는 `ISTIO_MUTUAL` (선택 가능)
- MySQL: Istio mesh 제외 (`sidecar.istio.io/inject: "false"`)

### Istio Sidecar 주입 현황

| Namespace | Injection | 이유 |
|-----------|-----------|------|
| **blog-system** | ✅ Enabled | web, was Pods에 Envoy proxy 주입 |
| **monitoring** | ✅ Enabled | Grafana, Prometheus |
| **argocd** | ✅ Enabled | ArgoCD server |
| **istio-system** | ✅ Enabled | Kiali |
| **metallb-system** | ❌ Disabled | 네트워크 충돌 방지 |

**MySQL Pod**: `sidecar.istio.io/inject: "false"` (JDBC 호환성)

## 확인 명령어

### Istio Gateway 상태

```bash
# LoadBalancer 확인
kubectl get svc -n istio-system istio-ingressgateway
# EXTERNAL-IP: 192.168.1.200 ✅

# Gateway 리소스
kubectl get gateway -n blog-system
# NAME           AGE
# blog-gateway   1d

# VirtualService 전체 조회
kubectl get virtualservice -A
```

### 서비스 테스트

```bash
# Blog
curl -I http://192.168.1.200/ -H "Host: blog.jiminhome.shop"
# HTTP/1.1 200 OK ✅

# Monitoring (Grafana)
curl -I http://192.168.1.200/ -H "Host: monitoring.jiminhome.shop"
# HTTP/1.1 302 Found ✅

# ArgoCD
curl -I http://192.168.1.200/ -H "Host: argocd.jiminhome.shop"
# HTTP/1.1 200 OK ✅

# Kiali
curl -I http://192.168.1.200/ -H "Host: kiali.jiminhome.shop"
# HTTP/1.1 302 Found ✅
```

### Kiali 트래픽 시각화

```bash
# 트래픽 생성
for i in {1..50}; do
  curl -s http://blog.jiminhome.shop/ > /dev/null
  curl -s http://blog.jiminhome.shop/api/posts > /dev/null
  sleep 1
done

# Kiali 접속
open http://kiali.jiminhome.shop
# Graph → Namespace: blog-system
# 예상: web → was → mysql 연결 시각화
```

## 관련 문서

- **전체 아키텍처**: [COMPLETE-ISTIO-ARCHITECTURE.md](./COMPLETE-ISTIO-ARCHITECTURE.md)
- **인프라 가이드**: [../02-INFRASTRUCTURE.md](../02-INFRASTRUCTURE.md)
- **트러블슈팅**: [../03-TROUBLESHOOTING.md](../03-TROUBLESHOOTING.md)

## 다음 단계

### 선택 사항

1. **HTTPS 443 포트 추가** (현재 Cloudflare에서 SSL 종료)
2. **Service ↔ Service mTLS 활성화** (`tls.mode: ISTIO_MUTUAL`)
3. **추가 서비스 VirtualService 생성** (prometheus, jaeger)
4. **Istio AuthorizationPolicy** 적용 (IP 기반 접근 제어)

### 완료 사항

- [x] Nginx Ingress 제거
- [x] Istio Gateway 192.168.1.200 할당
- [x] 모든 서비스 VirtualService 생성
- [x] Gateway → Service mTLS DISABLE
- [x] 전체 서비스 접근 테스트
