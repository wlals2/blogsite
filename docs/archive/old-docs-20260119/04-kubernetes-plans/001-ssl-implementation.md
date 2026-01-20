# Kubernetes 네이티브 SSL 구현 계획

> 작성일: 2025-01-17
> 목표: cert-manager + MetalLB로 로컬 nginx 제거 및 완전 자동화

---

## 1. 구현 목표

### Before (현재)
```
CloudFlare (443) → 로컬 nginx (SSL + Proxy) → K8s NodePort (31852) → Ingress
                   ↑
                   수동 SSL 갱신 (90일), 외부 의존성
```

### After (목표)
```
CloudFlare (443) → MetalLB LoadBalancer (443) → Ingress (TLS) → Services
                   ↑
                   cert-manager 자동 SSL 관리 (90일)
```

### 달성 효과
- ✅ SSL 인증서 자동 발급 + 갱신
- ✅ 로컬 nginx 제거 (외부 의존성 제거)
- ✅ 완전한 Kubernetes 네이티브 아키텍처
- ✅ LoadBalancer Service 사용 가능 (표준 K8s API)
- ✅ GitOps 가능 (모든 설정이 YAML)

---

## 2. 우선순위 및 예상 시간

### P0 (Critical - 필수): MetalLB + cert-manager 기본 설치
**예상 시간**: 1.5시간
**내용**:
- MetalLB 설치 및 IP Pool 설정
- cert-manager 설치 및 ClusterIssuer 생성
- 기본 동작 검증

**왜 P0인가**: 이것 없이는 나머지 작업 불가능

---

### P1 (Important - 중요): WEB (Hugo) 서비스 마이그레이션
**예상 시간**: 1시간
**내용**:
- WEB Ingress TLS 설정
- WEB Service → LoadBalancer 변경
- CloudFlare DNS 변경
- 로컬 nginx 중지 및 검증

**왜 P1인가**: WEB이 외부 노출되는 유일한 서비스 (우선 마이그레이션)

---

### P2 (Nice-to-have - 추가): WAS 서비스 준비
**예상 시간**: 30분
**내용**:
- WAS Ingress TLS 설정 (미래 대비)
- WAS 외부 노출 준비 (현재는 내부만)

**왜 P2인가**: WAS는 현재 외부 노출 안 함 (미래 대비용)

---

### P3 (Optional - 선택): 로컬 nginx 완전 제거
**예상 시간**: 15분
**내용**:
- nginx 패키지 제거
- 설정 파일 백업
- 포트 443 완전 해제

**왜 P3인가**: 롤백 가능성 대비 일단 중지만 유지

---

**총 예상 시간**: **3-3.5시간** (P0 + P1 + P2 + P3)
**최소 필수 시간**: **2.5시간** (P0 + P1만)

---

## 3. 단계별 구현 계획

---

## Phase 0: 사전 준비 (10분)

### 작업 내용
1. 현재 설정 백업
2. 롤백 계획 준비
3. 필요 정보 수집

### 실행 스크립트
```bash
# 1. nginx 설정 백업
sudo cp /etc/nginx/sites-available/blog /etc/nginx/sites-available/blog.backup.$(date +%Y%m%d)

# 2. 현재 Service 상태 저장
kubectl get svc -n blog-system -o yaml > ~/blogsite/docs/backup/services-before-metallb.yaml

# 3. 현재 Ingress 상태 저장
kubectl get ingress -n blog-system -o yaml > ~/blogsite/docs/backup/ingress-before-tls.yaml

# 4. CloudFlare 현재 DNS 기록
echo "현재 CloudFlare DNS: blog.jiminhome.shop -> $(dig +short blog.jiminhome.shop)" > ~/blogsite/docs/backup/cloudflare-dns-before.txt

# 5. IP Pool 범위 확인 (라우터 DHCP 범위 피하기)
ip addr show
# 사용 가능 범위: 192.168.1.200-192.168.1.210 (DHCP 범위 밖)
```

### 확인 방법
```bash
# 백업 파일 확인
ls -lh /etc/nginx/sites-available/blog.backup.*
ls -lh ~/blogsite/docs/backup/
```

### 예상 결과
- ✅ nginx 설정 백업 완료
- ✅ 현재 K8s 리소스 YAML 저장
- ✅ CloudFlare DNS 기록 저장

### 롤백 방법
```bash
# nginx 설정 복구
sudo cp /etc/nginx/sites-available/blog.backup.YYYYMMDD /etc/nginx/sites-available/blog
sudo systemctl restart nginx
```

---

## Phase 1: MetalLB 설치 (30분) - P0

### 왜 필요한가?
로컬 Kubernetes에서 LoadBalancer Service를 사용 가능하게 함.
클라우드 없이 `type: LoadBalancer`로 외부 IP 할당.

### 작업 내용
1. MetalLB 설치 (Namespace, Controller, Speaker)
2. IP Pool 설정 (L2 모드)
3. L2Advertisement 설정

### 실행 스크립트
```bash
# 1. MetalLB 설치 (최신 stable 버전)
kubectl apply -f https://raw.githubusercontent.com/metallb/metallb/v0.14.3/config/manifests/metallb-native.yaml

# 2. MetalLB Namespace 확인
kubectl get namespace metallb-system

# 3. MetalLB Pods 확인 (Ready 대기)
kubectl wait --namespace metallb-system \
    --for=condition=ready pod \
    --selector=app=metallb \
    --timeout=90s

# 4. IP Pool 생성 (L2 모드)
cat <<EOF | kubectl apply -f -
apiVersion: metallb.io/v1beta1
kind: IPAddressPool
metadata:
  name: local-pool
  namespace: metallb-system
spec:
  addresses:
  - 192.168.1.200-192.168.1.210  # 사용 가능한 IP 범위 (DHCP 밖)
---
apiVersion: metallb.io/v1beta1
kind: L2Advertisement
metadata:
  name: local-l2
  namespace: metallb-system
spec:
  ipAddressPools:
  - local-pool
EOF

# 5. 설정 확인
kubectl get ipaddresspool -n metallb-system
kubectl get l2advertisement -n metallb-system
```

### 확인 방법
```bash
# 1. MetalLB Pods 상태
kubectl get pods -n metallb-system
# 예상 출력:
# NAME                          READY   STATUS    RESTARTS   AGE
# controller-*                  1/1     Running   0          1m
# speaker-*                     1/1     Running   0          1m
# speaker-*                     1/1     Running   0          1m

# 2. IPAddressPool 확인
kubectl get ipaddresspool -n metallb-system
# 예상 출력:
# NAME         AUTO ASSIGN   AVOID BUGGY IPS   ADDRESSES
# local-pool   true          false             ["192.168.1.200-192.168.1.210"]

# 3. L2Advertisement 확인
kubectl get l2advertisement -n metallb-system
# 예상 출력:
# NAME       IPADDRESSPOOLS   IPADDRESSPOOL SELECTORS   INTERFACES
# local-l2   ["local-pool"]
```

### 예상 결과
- ✅ MetalLB Controller + Speaker 실행 중
- ✅ IP Pool 192.168.1.200-210 생성
- ✅ L2Advertisement 활성화

### 트러블슈팅
**문제**: Speaker Pod가 CrashLoopBackOff
**원인**: kube-proxy가 IPVS 모드일 때 strictARP 설정 필요
**해결**:
```bash
kubectl edit configmap -n kube-system kube-proxy
# strictARP: true로 변경
kubectl rollout restart daemonset kube-proxy -n kube-system
```

### 롤백 방법
```bash
kubectl delete -f https://raw.githubusercontent.com/metallb/metallb/v0.14.3/config/manifests/metallb-native.yaml
kubectl delete ipaddresspool local-pool -n metallb-system
kubectl delete l2advertisement local-l2 -n metallb-system
```

---

## Phase 2: cert-manager 설치 (30분) - P0

### 왜 필요한가?
Let's Encrypt SSL 인증서를 자동으로 발급하고 90일마다 자동 갱신.

### 작업 내용
1. cert-manager 설치 (CRDs, Controller, Webhook)
2. ClusterIssuer 생성 (Let's Encrypt Production)
3. HTTP-01 Challenge 설정

### 실행 스크립트
```bash
# 1. cert-manager 설치 (최신 stable 버전)
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.14.1/cert-manager.yaml

# 2. cert-manager Namespace 확인
kubectl get namespace cert-manager

# 3. cert-manager Pods 확인 (Ready 대기)
kubectl wait --namespace cert-manager \
    --for=condition=ready pod \
    --selector=app.kubernetes.io/instance=cert-manager \
    --timeout=90s

# 4. ClusterIssuer 생성 (Let's Encrypt Production)
cat <<EOF | kubectl apply -f -
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: letsencrypt-prod
spec:
  acme:
    # Let's Encrypt Production Server
    server: https://acme-v02.api.letsencrypt.org/directory
    email: wlals2@naver.com  # Let's Encrypt 알림 받을 이메일
    privateKeySecretRef:
      name: letsencrypt-prod  # Private Key 저장할 Secret
    solvers:
    - http01:
        ingress:
          class: nginx  # nginx-ingress 사용
EOF

# 5. ClusterIssuer 확인
kubectl get clusterissuer
kubectl describe clusterissuer letsencrypt-prod
```

### 확인 방법
```bash
# 1. cert-manager Pods 상태
kubectl get pods -n cert-manager
# 예상 출력:
# NAME                                       READY   STATUS    RESTARTS   AGE
# cert-manager-*                             1/1     Running   0          1m
# cert-manager-cainjector-*                  1/1     Running   0          1m
# cert-manager-webhook-*                     1/1     Running   0          1m

# 2. ClusterIssuer 상태
kubectl get clusterissuer letsencrypt-prod
# 예상 출력:
# NAME               READY   AGE
# letsencrypt-prod   True    1m

# 3. ClusterIssuer 상세 확인
kubectl describe clusterissuer letsencrypt-prod | grep -A 5 "Status:"
# 예상: Status: Ready
```

### 예상 결과
- ✅ cert-manager Controller + Webhook 실행 중
- ✅ ClusterIssuer "letsencrypt-prod" Ready
- ✅ HTTP-01 Challenge 준비 완료

### 트러블슈팅
**문제**: ClusterIssuer가 Ready가 아님 (False)
**원인**: Let's Encrypt API 연결 실패 또는 이메일 형식 오류
**해결**:
```bash
kubectl describe clusterissuer letsencrypt-prod
# Events 확인하여 에러 메시지 확인
```

### 롤백 방법
```bash
kubectl delete -f https://github.com/cert-manager/cert-manager/releases/download/v1.14.1/cert-manager.yaml
kubectl delete clusterissuer letsencrypt-prod
```

---

## Phase 3: WEB Ingress TLS 설정 (30분) - P1

### 왜 필요한가?
cert-manager가 자동으로 SSL 인증서를 발급하도록 Ingress에 annotation 추가.

### 작업 내용
1. 현재 WEB Ingress 확인
2. TLS 설정 추가 (annotations + tls 섹션)
3. Certificate 자동 발급 확인

### 실행 스크립트
```bash
# 1. 현재 WEB Ingress 확인
kubectl get ingress -n blog-system web-ingress -o yaml

# 2. WEB Ingress TLS 설정 추가
cat <<EOF | kubectl apply -f -
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: web-ingress
  namespace: blog-system
  annotations:
    # cert-manager가 인증서 발급하도록 지정
    cert-manager.io/cluster-issuer: "letsencrypt-prod"
    # nginx-ingress 클래스 지정
    kubernetes.io/ingress.class: nginx
    # SSL Redirect 활성화 (HTTP → HTTPS)
    nginx.ingress.kubernetes.io/ssl-redirect: "true"
    # HTTPS만 허용
    nginx.ingress.kubernetes.io/force-ssl-redirect: "true"
spec:
  tls:
  - hosts:
    - blog.jiminhome.shop
    secretName: blog-tls  # cert-manager가 자동으로 생성할 Secret
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
EOF

# 3. Certificate 생성 확인 (cert-manager가 자동 생성)
kubectl get certificate -n blog-system

# 4. Certificate 발급 상태 확인
kubectl describe certificate blog-tls -n blog-system

# 5. Certificate Request 확인 (진행 중인 요청)
kubectl get certificaterequest -n blog-system
```

### 확인 방법
```bash
# 1. Certificate 상태 확인
kubectl get certificate -n blog-system blog-tls
# 예상 출력 (초기):
# NAME       READY   SECRET     AGE
# blog-tls   False   blog-tls   30s

# 2. Certificate 발급 과정 확인
kubectl describe certificate blog-tls -n blog-system
# Events 확인:
# - Created CertificateRequest
# - Waiting for HTTP-01 challenge
# - Certificate issued

# 3. Challenge 확인 (HTTP-01)
kubectl get challenge -n blog-system
# 예상 출력:
# NAME                              STATE     AGE
# blog-tls-*-*-*                    pending   10s

# 4. 최종 확인 (발급 완료 시)
kubectl get certificate -n blog-system blog-tls
# 예상 출력 (완료):
# NAME       READY   SECRET     AGE
# blog-tls   True    blog-tls   2m

# 5. Secret 확인 (인증서 데이터)
kubectl get secret blog-tls -n blog-system -o yaml
# tls.crt, tls.key 확인
```

### 예상 결과
- ✅ Certificate "blog-tls" 생성됨
- ✅ HTTP-01 Challenge 통과
- ✅ Secret "blog-tls"에 인증서 저장
- ✅ Certificate READY = True (2-5분 소요)

### 트러블슈팅

**문제 1**: Certificate가 READY가 안 됨 (계속 False)
**원인**: HTTP-01 Challenge 실패 (Let's Encrypt가 /.well-known/acme-challenge/ 접근 못함)
**해결**:
```bash
# Challenge 상세 확인
kubectl describe challenge -n blog-system

# 일반적 원인:
# 1. Ingress가 외부에서 접근 불가 (아직 NodePort 사용 중)
# 2. CloudFlare Proxy 활성화 (DNS Only로 변경 필요)
# 3. nginx-ingress가 /.well-known/acme-challenge/ 차단

# 임시 해결: Challenge 수동 확인
curl http://blog.jiminhome.shop/.well-known/acme-challenge/TEST
```

**문제 2**: Challenge가 pending에서 진행 안 됨
**원인**: cert-manager webhook이 Challenge Pod 생성 못함
**해결**:
```bash
# cert-manager 로그 확인
kubectl logs -n cert-manager -l app=cert-manager

# webhook 로그 확인
kubectl logs -n cert-manager -l app=webhook
```

### 롤백 방법
```bash
# TLS 설정 제거 (원래 Ingress로 복구)
kubectl apply -f ~/blogsite/docs/backup/ingress-before-tls.yaml

# Certificate 삭제
kubectl delete certificate blog-tls -n blog-system
kubectl delete secret blog-tls -n blog-system
```

---

## Phase 4: WEB Service LoadBalancer 변경 (20분) - P1

### 왜 필요한가?
MetalLB가 외부 IP를 할당하고 표준 포트(80, 443)로 노출.

### 작업 내용
1. WEB Service를 LoadBalancer로 변경
2. External IP 할당 확인
3. nginx-ingress Service도 LoadBalancer로 변경

### 실행 스크립트
```bash
# 1. nginx-ingress Service를 LoadBalancer로 변경
kubectl patch svc nginx-ingress-controller -n blog-system \
    -p '{"spec":{"type":"LoadBalancer"}}'

# 2. External IP 할당 확인 (MetalLB가 할당)
kubectl get svc nginx-ingress-controller -n blog-system -w
# EXTERNAL-IP가 <pending> → 192.168.1.200으로 변경될 때까지 대기

# 3. 할당된 IP 저장
METALLB_IP=$(kubectl get svc nginx-ingress-controller -n blog-system -o jsonpath='{.status.loadBalancer.ingress[0].ip}')
echo "MetalLB IP: $METALLB_IP"

# 4. 로컬에서 접근 테스트 (HTTP)
curl -H "Host: blog.jiminhome.shop" http://$METALLB_IP/

# 5. HTTPS 테스트 (cert-manager 인증서 사용)
curl -kv -H "Host: blog.jiminhome.shop" https://$METALLB_IP/
# -k: 자체 서명 인증서 허용 (최초 테스트용)
```

### 확인 방법
```bash
# 1. Service 상태 확인
kubectl get svc -n blog-system
# 예상 출력:
# NAME                       TYPE           EXTERNAL-IP       PORT(S)
# nginx-ingress-controller   LoadBalancer   192.168.1.200     80:31234/TCP,443:31235/TCP
# web-service                ClusterIP      10.96.123.45      80/TCP

# 2. MetalLB가 할당한 IP 확인
kubectl get svc nginx-ingress-controller -n blog-system -o jsonpath='{.status.loadBalancer.ingress[0].ip}'
# 예상: 192.168.1.200

# 3. 로컬 curl 테스트
curl -I http://192.168.1.200
# HTTP/1.1 301 Moved Permanently (SSL Redirect)
# Location: https://192.168.1.200/

curl -Ik https://192.168.1.200
# HTTP/2 200 OK (SSL 인증서로 HTTPS 작동)

# 4. Host 헤더로 테스트
curl -I -H "Host: blog.jiminhome.shop" http://192.168.1.200
# 실제 Hugo 페이지 반환 확인
```

### 예상 결과
- ✅ nginx-ingress-controller EXTERNAL-IP = 192.168.1.200
- ✅ HTTP (80) → HTTPS (443) Redirect
- ✅ HTTPS (443) → Hugo 페이지 반환
- ✅ cert-manager 인증서로 TLS 작동

### 트러블슈팅

**문제**: EXTERNAL-IP가 계속 `<pending>`
**원인**: MetalLB가 IP Pool에서 할당 못함
**해결**:
```bash
# MetalLB Controller 로그 확인
kubectl logs -n metallb-system -l app=metallb,component=controller

# IPAddressPool 확인
kubectl get ipaddresspool -n metallb-system local-pool -o yaml

# Service가 IPAddressPool을 사용하는지 확인
kubectl describe svc nginx-ingress-controller -n blog-system
# Events 확인
```

### 롤백 방법
```bash
# NodePort로 복구
kubectl patch svc nginx-ingress-controller -n blog-system \
    -p '{"spec":{"type":"NodePort"}}'
```

---

## Phase 5: CloudFlare DNS 변경 (10분) - P1

### 왜 필요한가?
CloudFlare가 MetalLB IP로 직접 연결하도록 DNS A 레코드 변경.

### 작업 내용
1. MetalLB IP 확인
2. CloudFlare에서 A 레코드 변경
3. DNS 전파 확인

### 실행 스크립트
```bash
# 1. MetalLB IP 확인
METALLB_IP=$(kubectl get svc nginx-ingress-controller -n blog-system -o jsonpath='{.status.loadBalancer.ingress[0].ip}')
echo "MetalLB IP: $METALLB_IP"
# 예상: 192.168.1.200

# 2. CloudFlare 현재 DNS 확인
dig +short blog.jiminhome.shop
# 현재: 192.168.1.187 (로컬 nginx)

# 3. CloudFlare에서 A 레코드 변경 (웹 UI)
# blog.jiminhome.shop: 192.168.1.187 → 192.168.1.200

# 4. DNS 전파 확인 (5-10분 소요)
watch -n 5 'dig +short blog.jiminhome.shop'
# 192.168.1.200으로 변경될 때까지 대기

# 5. TTL 확인 (빠른 전파 위해 낮춤)
dig blog.jiminhome.shop
# TTL: 300 (5분) → 60 (1분)으로 변경 권장
```

### 확인 방법
```bash
# 1. DNS 전파 확인
dig +short blog.jiminhome.shop
# 예상: 192.168.1.200

# 2. 외부에서 접근 테스트 (실제 사용자 경험)
curl -I https://blog.jiminhome.shop/
# HTTP/2 200 OK

# 3. CloudFlare CDN 경유 확인
curl -I https://blog.jiminhome.shop/ | grep -i cf-ray
# cf-ray: XXXXXXXX (CloudFlare CDN 경유 증거)

# 4. Let's Encrypt 인증서 확인
echo | openssl s_client -showcerts -servername blog.jiminhome.shop -connect blog.jiminhome.shop:443 2>/dev/null | openssl x509 -inform pem -noout -text | grep "Issuer:"
# 예상: Issuer: C = US, O = Let's Encrypt, CN = R3
```

### 예상 결과
- ✅ DNS: blog.jiminhome.shop → 192.168.1.200
- ✅ HTTPS 접근 성공
- ✅ Let's Encrypt 인증서 사용 중
- ✅ CloudFlare CDN 경유 확인

### 트러블슈팅

**문제**: DNS 변경했는데 여전히 로컬 nginx로 연결됨
**원인**: DNS 캐시 (브라우저, OS, ISP)
**해결**:
```bash
# 로컬 DNS 캐시 클리어 (Ubuntu)
sudo systemd-resolve --flush-caches

# 브라우저 캐시 클리어 (Chrome)
# chrome://net-internals/#dns → Clear host cache

# ISP DNS 캐시 대기 (TTL만큼)
# 또는 Google DNS 사용: 8.8.8.8
```

### 롤백 방법
```bash
# CloudFlare에서 A 레코드 복구
# blog.jiminhome.shop: 192.168.1.200 → 192.168.1.187

# DNS 전파 대기
watch -n 5 'dig +short blog.jiminhome.shop'
```

---

## Phase 6: 로컬 nginx 중지 및 검증 (10분) - P1

### 왜 필요한가?
MetalLB + cert-manager가 모든 역할을 대체하므로 로컬 nginx 불필요.

### 작업 내용
1. nginx 중지 (완전 제거는 아직 아님)
2. 포트 443 해제 확인
3. 전체 서비스 정상 동작 확인

### 실행 스크립트
```bash
# 1. nginx 현재 상태 확인
sudo systemctl status nginx

# 2. nginx 중지
sudo systemctl stop nginx

# 3. nginx 자동 시작 비활성화
sudo systemctl disable nginx

# 4. 포트 443 해제 확인
sudo ss -tlnp | grep :443
# 출력 없음 (nginx만 사용 중이었다면)

# 5. 전체 서비스 테스트
curl -I https://blog.jiminhome.shop/
# HTTP/2 200 OK (nginx 없이도 작동!)

# 6. Hugo 페이지 렌더링 확인
curl -s https://blog.jiminhome.shop/ | grep "<title>"
# <title>My Hugo Blog</title>
```

### 확인 방법
```bash
# 1. nginx 중지 확인
sudo systemctl is-active nginx
# 예상: inactive

# 2. 포트 사용 확인
sudo ss -tlnp | grep :443
# 출력 없음 또는 다른 프로세스만 (nginx 없음)

# 3. 웹 브라우저 테스트
# https://blog.jiminhome.shop/ 접속
# - ✅ 정상 렌더링
# - ✅ HTTPS 자물쇠 아이콘
# - ✅ Let's Encrypt 인증서

# 4. SSL 인증서 확인
echo | openssl s_client -showcerts -servername blog.jiminhome.shop -connect blog.jiminhome.shop:443 2>/dev/null | openssl x509 -inform pem -noout -text
# Subject: CN=blog.jiminhome.shop
# Issuer: CN=R3, O=Let's Encrypt
# Validity: Not After (90일)
```

### 예상 결과
- ✅ nginx 중지됨
- ✅ 포트 443 해제됨
- ✅ blog.jiminhome.shop HTTPS 정상 작동
- ✅ Let's Encrypt 인증서 사용 중

### 트러블슈팅

**문제**: nginx 중지했는데 HTTPS 접속 안 됨
**원인**: MetalLB IP 또는 cert-manager 설정 문제
**해결**:
```bash
# 1. MetalLB External IP 확인
kubectl get svc nginx-ingress-controller -n blog-system
# EXTERNAL-IP가 없으면 Phase 4 재확인

# 2. Certificate 상태 확인
kubectl get certificate -n blog-system blog-tls
# READY가 False면 Phase 3 재확인

# 3. 임시 롤백 (nginx 재시작)
sudo systemctl start nginx
sudo systemctl enable nginx
```

### 롤백 방법
```bash
# nginx 재시작
sudo systemctl start nginx
sudo systemctl enable nginx

# CloudFlare DNS 복구 (192.168.1.187로)
# (Phase 5 롤백 참조)
```

---

## Phase 7: WAS Ingress TLS 설정 (미래 대비) (15분) - P2

### 왜 필요한가?
WAS도 나중에 외부 노출할 수 있도록 미리 TLS 설정.

### 작업 내용
1. WAS Ingress TLS 설정 추가
2. Certificate 자동 발급 확인
3. 현재는 내부용으로만 사용 (외부 노출 안 함)

### 실행 스크립트
```bash
# 1. WAS Ingress 확인
kubectl get ingress -n blog-system was-ingress -o yaml

# 2. WAS Ingress TLS 설정 추가
cat <<EOF | kubectl apply -f -
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: was-ingress
  namespace: blog-system
  annotations:
    cert-manager.io/cluster-issuer: "letsencrypt-prod"
    kubernetes.io/ingress.class: nginx
    nginx.ingress.kubernetes.io/ssl-redirect: "true"
spec:
  tls:
  - hosts:
    - was.jiminhome.shop  # 미래 WAS 도메인 (현재 DNS 없음)
    secretName: was-tls
  rules:
  - host: was.jiminhome.shop
    http:
      paths:
      - path: /
        pathType: Prefix
        backend:
          service:
            name: was-service
            port:
              number: 8080
EOF

# 3. Certificate 생성 확인
kubectl get certificate -n blog-system was-tls
```

### 확인 방법
```bash
# 1. Certificate 상태
kubectl get certificate -n blog-system was-tls
# 예상: READY = False (DNS 없으므로 발급 실패 정상)

# 2. 내부 접근 테스트 (ClusterIP)
kubectl run curl-test --rm -i --restart=Never --image=curlimages/curl -- \
    curl -I http://was-service.blog-system.svc.cluster.local:8080
# HTTP/1.1 200 OK (WAS 내부 정상 작동)
```

### 예상 결과
- ✅ WAS Ingress TLS 설정 완료
- ⚠️ Certificate 발급 실패 (DNS 없음 - 정상)
- ✅ 내부 접근 정상 작동

### 롤백 방법
```bash
# WAS Ingress TLS 제거
kubectl delete certificate was-tls -n blog-system
kubectl delete secret was-tls -n blog-system
```

---

## Phase 8: 로컬 nginx 완전 제거 (선택) (15분) - P3

### 왜 필요한가?
더 이상 사용하지 않는 nginx 완전 제거 (디스크 공간, 보안).

### 작업 내용
1. nginx 설정 파일 백업
2. nginx 패키지 제거
3. 관련 파일 정리

### 실행 스크립트
```bash
# 1. nginx 설정 백업 (최종)
sudo tar -czf ~/blogsite/docs/backup/nginx-config-final-backup.tar.gz \
    /etc/nginx/sites-available/blog* \
    /etc/nginx/nginx.conf

# 2. Let's Encrypt 인증서 백업 (혹시 몰라)
sudo tar -czf ~/blogsite/docs/backup/letsencrypt-certs-backup.tar.gz \
    /etc/letsencrypt/

# 3. nginx 패키지 제거
sudo apt-get remove --purge nginx nginx-common -y

# 4. 설정 파일 정리 (완전 삭제는 신중히)
sudo rm -rf /etc/nginx/
sudo rm -rf /var/log/nginx/

# 5. 포트 확인
sudo ss -tlnp | grep -E ':(80|443)'
# 출력 없음 (모두 해제)
```

### 확인 방법
```bash
# 1. nginx 제거 확인
dpkg -l | grep nginx
# 출력 없음

# 2. 백업 파일 확인
ls -lh ~/blogsite/docs/backup/nginx-config-final-backup.tar.gz
ls -lh ~/blogsite/docs/backup/letsencrypt-certs-backup.tar.gz

# 3. 서비스 정상 작동 확인
curl -I https://blog.jiminhome.shop/
# HTTP/2 200 OK (nginx 없이도 정상)
```

### 예상 결과
- ✅ nginx 완전 제거
- ✅ 설정 파일 백업 완료
- ✅ 서비스 정상 작동 유지

### 롤백 방법
```bash
# nginx 재설치
sudo apt-get update
sudo apt-get install nginx -y

# 설정 복구
sudo tar -xzf ~/blogsite/docs/backup/nginx-config-final-backup.tar.gz -C /

# nginx 재시작
sudo systemctl start nginx
sudo systemctl enable nginx

# CloudFlare DNS 복구 (192.168.1.187로)
```

---

## 4. 검증 체크리스트

### 전체 시스템 검증

```bash
#!/bin/bash
# 전체 검증 스크립트

echo "=== Kubernetes 네이티브 SSL 검증 ==="

# 1. MetalLB 상태
echo -e "\n[1/8] MetalLB 상태 확인"
kubectl get pods -n metallb-system
kubectl get ipaddresspool -n metallb-system

# 2. cert-manager 상태
echo -e "\n[2/8] cert-manager 상태 확인"
kubectl get pods -n cert-manager
kubectl get clusterissuer

# 3. Certificate 상태
echo -e "\n[3/8] Certificate 상태 확인"
kubectl get certificate -n blog-system

# 4. Service External IP
echo -e "\n[4/8] Service External IP 확인"
kubectl get svc nginx-ingress-controller -n blog-system

# 5. Ingress TLS 설정
echo -e "\n[5/8] Ingress TLS 설정 확인"
kubectl get ingress -n blog-system -o wide

# 6. DNS 확인
echo -e "\n[6/8] DNS 확인"
dig +short blog.jiminhome.shop

# 7. HTTPS 접근 테스트
echo -e "\n[7/8] HTTPS 접근 테스트"
curl -I https://blog.jiminhome.shop/

# 8. SSL 인증서 확인
echo -e "\n[8/8] SSL 인증서 확인"
echo | openssl s_client -showcerts -servername blog.jiminhome.shop -connect blog.jiminhome.shop:443 2>/dev/null | openssl x509 -inform pem -noout -issuer -subject -dates

echo -e "\n=== 검증 완료 ==="
```

### 예상 정상 출력

```
=== Kubernetes 네이티브 SSL 검증 ===

[1/8] MetalLB 상태 확인
NAME                          READY   STATUS    RESTARTS   AGE
controller-*                  1/1     Running   0          30m
speaker-*                     1/1     Running   0          30m

NAME         AUTO ASSIGN   AVOID BUGGY IPS   ADDRESSES
local-pool   true          false             ["192.168.1.200-192.168.1.210"]

[2/8] cert-manager 상태 확인
NAME                                       READY   STATUS    RESTARTS   AGE
cert-manager-*                             1/1     Running   0          25m
cert-manager-cainjector-*                  1/1     Running   0          25m
cert-manager-webhook-*                     1/1     Running   0          25m

NAME               READY   AGE
letsencrypt-prod   True    20m

[3/8] Certificate 상태 확인
NAME       READY   SECRET     AGE
blog-tls   True    blog-tls   15m

[4/8] Service External IP 확인
NAME                       TYPE           EXTERNAL-IP       PORT(S)
nginx-ingress-controller   LoadBalancer   192.168.1.200     80:31234/TCP,443:31235/TCP

[5/8] Ingress TLS 설정 확인
NAME          CLASS    HOSTS                 ADDRESS         PORTS     AGE
web-ingress   <none>   blog.jiminhome.shop   192.168.1.200   80, 443   10m

[6/8] DNS 확인
192.168.1.200

[7/8] HTTPS 접근 테스트
HTTP/2 200
server: nginx/1.25.3
content-type: text/html; charset=utf-8
...

[8/8] SSL 인증서 확인
issuer=C = US, O = Let's Encrypt, CN = R3
subject=CN = blog.jiminhome.shop
notBefore=Jan 17 00:00:00 2025 GMT
notAfter=Apr 17 00:00:00 2025 GMT

=== 검증 완료 ===
```

---

## 5. 롤백 계획 (전체)

### 긴급 롤백 (5분 이내)

**문제**: 전체 서비스 중단
**해결**: nginx 재시작 + DNS 복구

```bash
# 1. nginx 재시작
sudo systemctl start nginx
sudo systemctl enable nginx

# 2. CloudFlare DNS 복구
# blog.jiminhome.shop: 192.168.1.200 → 192.168.1.187

# 3. DNS 전파 대기
watch -n 5 'dig +short blog.jiminhome.shop'

# 4. 서비스 확인
curl -I https://blog.jiminhome.shop/
```

### 단계별 롤백

| Phase | 롤백 방법 | 예상 시간 |
|-------|----------|----------|
| Phase 8 (nginx 제거) | nginx 재설치 + 백업 복구 | 10분 |
| Phase 7 (WAS TLS) | Certificate/Secret 삭제 | 1분 |
| Phase 6 (nginx 중지) | `systemctl start nginx` | 1분 |
| Phase 5 (DNS 변경) | CloudFlare DNS 복구 | 5분 |
| Phase 4 (LoadBalancer) | `type: NodePort`로 변경 | 2분 |
| Phase 3 (Ingress TLS) | 백업 YAML 적용 | 2분 |
| Phase 2 (cert-manager) | cert-manager 삭제 | 5분 |
| Phase 1 (MetalLB) | MetalLB 삭제 | 5분 |

---

## 6. 문서화 계획

구현 완료 후 다음 문서 작성:

1. **METALLB-SETUP-GUIDE.md** (Phase 1 상세)
   - MetalLB 개념 및 아키텍처
   - L2 vs BGP 모드 비교
   - IP Pool 설정 방법
   - 트러블슈팅 사례

2. **CERT-MANAGER-SETUP-GUIDE.md** (Phase 2-3 상세)
   - cert-manager 개념 및 워크플로
   - ClusterIssuer vs Issuer
   - HTTP-01 vs DNS-01 Challenge
   - Certificate 자동 갱신 메커니즘

3. **KUBERNETES-NATIVE-SSL-FINAL.md** (전체 요약)
   - Before/After 비교
   - 트레이드오프 분석
   - 운영 가이드 (인증서 갱신, 모니터링)
   - FAQ

---

## 7. 예상 결과 요약

### Before (현재)
```
CloudFlare (443) → 로컬 nginx (SSL + Proxy) → K8s NodePort (31852)
                   ↑
                   수동 SSL 갱신 (90일)
                   외부 의존성
```

### After (목표)
```
CloudFlare (443) → MetalLB LoadBalancer (443) → Ingress (TLS)
                   ↑
                   cert-manager 자동 SSL (90일)
                   완전 Kubernetes 네이티브
```

### 달성 효과
- ✅ **SSL 자동화**: 수동 certbot → cert-manager 자동 갱신
- ✅ **외부 의존성 제거**: 로컬 nginx 불필요
- ✅ **표준 K8s API**: LoadBalancer Service 사용
- ✅ **고가용성**: MetalLB 다중 노드 분산
- ✅ **GitOps 가능**: 모든 설정 YAML로 버전 관리

---

## 8. 사용자 승인 요청

### 구현 제안

위 계획대로 **cert-manager + MetalLB** 구축을 시작하겠습니다.

**총 예상 시간**: 3-3.5시간 (P0 + P1 + P2 + P3)
**최소 필수 시간**: 2.5시간 (P0 + P1만)

**우선순위**:
- **P0** (MetalLB + cert-manager): 1.5시간 - 필수 기반 인프라
- **P1** (WEB 마이그레이션): 1시간 - 실제 서비스 전환
- **P2** (WAS 준비): 30분 - 미래 대비
- **P3** (nginx 제거): 15분 - 선택적 정리

**롤백 계획**: 모든 Phase마다 롤백 가능 (긴급 시 5분 이내)

**승인 후 작업 순서**:
1. Phase 0: 백업 (10분)
2. Phase 1: MetalLB 설치 (30분)
3. Phase 2: cert-manager 설치 (30분)
4. Phase 3: WEB Ingress TLS (30분)
5. Phase 4: LoadBalancer 변경 (20분)
6. Phase 5: DNS 변경 (10분)
7. Phase 6: nginx 중지 (10분)
8. Phase 7-8: 선택적 (45분)

**구축을 시작할까요?**

---

**관련 문서**:
- [CLOUDFLARE-NGINX-ALTERNATIVES.md](CLOUDFLARE-NGINX-ALTERNATIVES.md) - 트레이드오프 분석
- [COMPLETE-PROJECT-GUIDE.md](COMPLETE-PROJECT-GUIDE.md) - 전체 프로젝트 가이드
- (작성 예정) METALLB-SETUP-GUIDE.md
- (작성 예정) CERT-MANAGER-SETUP-GUIDE.md
- (작성 예정) KUBERNETES-NATIVE-SSL-FINAL.md

---

**최종 업데이트**: 2025-01-17
