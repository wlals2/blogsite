# Dashboard 접근 가이드

> 192.168.1.195에서만 접근 가능한 보안 설정
> 최종 업데이트: 2026-01-20

---

## 🔐 접근 제한 설정

### 현재 보안 설정

**허용 IP:** `192.168.1.195/32` (단일 IP만 허용)

```yaml
# Ingress 설정
nginx.ingress.kubernetes.io/whitelist-source-range: "192.168.1.195/32"
```

**작동 방식:**
```
192.168.1.195에서 요청
  ↓
Ingress Controller (IP 체크)
  ↓
✅ 192.168.1.195 → 허용 (200 OK)
❌ 다른 IP → 차단 (403 Forbidden)
```

---

## 📊 Dashboard 접근 방법

### 1. 접근 요구사항

**필수:**
- PC IP 주소: `192.168.1.195`
- 네트워크: 동일한 로컬 네트워크 (192.168.1.0/24)

**확인 방법:**
```bash
# 내 IP 확인
ip addr show | grep "inet 192.168.1"

# 또는
ifconfig | grep "inet 192.168.1"
```

---

### 2. DNS 설정 (중요!)

**문제:** `monitoring.jiminhome.shop` 도메인이 DNS에 등록되어 있지 않아 접근 불가

**해결:** 접근할 PC(192.168.1.195)의 hosts 파일에 DNS 엔트리 추가

#### Linux/Mac 사용자

```bash
# /etc/hosts 파일 수정 (sudo 권한 필요)
sudo bash -c 'cat >> /etc/hosts << EOF
192.168.1.200 monitoring.jiminhome.shop
192.168.1.200 prometheus.jiminhome.shop
EOF'

# 확인
getent hosts monitoring.jiminhome.shop
# 결과: 192.168.1.200   monitoring.jiminhome.shop
```

#### Windows 사용자

1. **관리자 권한으로 메모장 실행**
2. 파일 열기: `C:\Windows\System32\drivers\etc\hosts`
3. 파일 끝에 추가:
   ```
   192.168.1.200 monitoring.jiminhome.shop
   192.168.1.200 prometheus.jiminhome.shop
   ```
4. 저장 후 브라우저 재시작

#### 확인 방법

```bash
# Windows (cmd 또는 PowerShell)
ping monitoring.jiminhome.shop

# Linux/Mac
getent hosts monitoring.jiminhome.shop
```

**예상 결과:** 192.168.1.200 IP로 응답

---

### 3. Dashboard URL

| 서비스 | URL | 용도 |
|--------|-----|------|
| **Grafana** | http://monitoring.jiminhome.shop | Dashboard 메인 |
| **Prometheus** | http://prometheus.jiminhome.shop | 메트릭 쿼리 & Alert |

**Grafana 로그인:**
- Username: `admin`
- Password: `admin` (최초 로그인 시 변경 권장)

---

### 4. 주요 Dashboard 직접 링크

| Dashboard | 바로가기 |
|-----------|---------|
| **Nginx** | [monitoring.jiminhome.shop/d/e556538a-2ac3-4662-99c2-ad6748ffda33/nginx-web-server-monitoring](http://monitoring.jiminhome.shop/d/e556538a-2ac3-4662-99c2-ad6748ffda33/nginx-web-server-monitoring) |
| **WAS** | [monitoring.jiminhome.shop/d/c714ed80-f770-4078-b8ce-d7fd721020b5/was-spring-boot-monitoring-dashboard](http://monitoring.jiminhome.shop/d/c714ed80-f770-4078-b8ce-d7fd721020b5/was-spring-boot-monitoring-dashboard) |
| **MySQL** | [monitoring.jiminhome.shop/d/4efa51bd-162a-4707-b733-817a2a2efdb7/mysql-database-monitoring-dashboard](http://monitoring.jiminhome.shop/d/4efa51bd-162a-4707-b733-817a2a2efdb7/mysql-database-monitoring-dashboard) |
| **Overview** | [monitoring.jiminhome.shop/d/be1f8087-43f6-45ac-85a2-028cf125b5c5/blog-system-full-stack-overview](http://monitoring.jiminhome.shop/d/be1f8087-43f6-45ac-85a2-028cf125b5c5/blog-system-full-stack-overview) |

---

## 🧪 접근 테스트

### 192.168.1.195에서 테스트 (허용)

```bash
# HTTP 상태 확인
curl -I http://monitoring.jiminhome.shop

# 예상 결과:
# HTTP/1.1 200 OK
# Server: nginx
# ...
```

### 다른 IP에서 테스트 (차단)

```bash
# 다른 PC나 모바일에서 테스트
curl -I http://monitoring.jiminhome.shop

# 예상 결과:
# HTTP/1.1 403 Forbidden
# ...
# <html>
# <head><title>403 Forbidden</title></head>
# ...
```

---

## 🛠️ 설정 변경 방법

### IP 주소 변경

**다른 IP로 변경:**
```bash
# Grafana Ingress 수정
kubectl apply -f - <<EOF
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: grafana-ingress
  namespace: monitoring
  annotations:
    nginx.ingress.kubernetes.io/rewrite-target: /
    nginx.ingress.kubernetes.io/whitelist-source-range: "192.168.1.XXX/32"
spec:
  ingressClassName: nginx
  rules:
    - host: monitoring.jiminhome.shop
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: grafana
                port:
                  number: 3000
EOF

# Prometheus Ingress도 동일하게 수정
kubectl apply -f - <<EOF
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: prometheus-ingress
  namespace: monitoring
  annotations:
    nginx.ingress.kubernetes.io/rewrite-target: /
    nginx.ingress.kubernetes.io/whitelist-source-range: "192.168.1.XXX/32"
spec:
  ingressClassName: nginx
  rules:
    - host: prometheus.jiminhome.shop
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: prometheus
                port:
                  number: 9090
EOF
```

---

### 여러 IP 허용

**여러 디바이스에서 접근:**
```yaml
# 예: PC(192.168.1.195) + 노트북(192.168.1.100) + 태블릿(192.168.1.50)
nginx.ingress.kubernetes.io/whitelist-source-range: "192.168.1.195/32,192.168.1.100/32,192.168.1.50/32"
```

**전체 서브넷 허용 (덜 안전):**
```yaml
# 192.168.1.0/24 전체 허용
nginx.ingress.kubernetes.io/whitelist-source-range: "192.168.1.0/24"
```

---

### IP 제한 제거 (모든 IP 허용)

**주의:** 보안상 권장하지 않음

```bash
# annotation 제거
kubectl annotate ingress -n monitoring grafana-ingress nginx.ingress.kubernetes.io/whitelist-source-range-
kubectl annotate ingress -n monitoring prometheus-ingress nginx.ingress.kubernetes.io/whitelist-source-range-
```

---

## 🔒 추가 보안 옵션

### 1. Basic Auth 추가 (이중 보안)

**IP 제한 + 패스워드 인증:**

```bash
# 1. htpasswd로 패스워드 파일 생성
htpasswd -c /tmp/auth admin

# 2. Secret 생성
kubectl create secret generic basic-auth --from-file=/tmp/auth -n monitoring

# 3. Ingress에 annotation 추가
kubectl annotate ingress -n monitoring grafana-ingress \
  nginx.ingress.kubernetes.io/auth-type=basic \
  nginx.ingress.kubernetes.io/auth-secret=basic-auth \
  nginx.ingress.kubernetes.io/auth-realm='Authentication Required'
```

**결과:**
- IP 체크 (192.168.1.195만 허용)
- 추가 로그인 프롬프트 (admin / password)

---

### 2. Grafana 자체 보안 강화

**admin 패스워드 변경:**
```bash
# Grafana Pod에서 직접 변경
kubectl exec -n monitoring <grafana-pod> -- \
  grafana-cli admin reset-admin-password <new-strong-password>
```

**사용자 추가 (Grafana UI):**
1. Grafana 로그인
2. Configuration → Users → Invite
3. 역할 선택:
   - **Viewer**: Dashboard만 볼 수 있음
   - **Editor**: Dashboard 수정 가능
   - **Admin**: 모든 권한

---

## 🔍 트러블슈팅

### 문제 1: 404 Not Found 또는 사이트에 연결할 수 없음

**증상:**
- 브라우저에 "사이트에 연결할 수 없음" 표시
- 또는 "404 Not Found" 오류

**원인:**
- DNS 설정이 안 되어 있음 (가장 흔한 원인)
- `monitoring.jiminhome.shop` 도메인이 192.168.1.200으로 resolve 안 됨

**해결:**
```bash
# 1. DNS 확인 (Windows cmd/PowerShell 또는 Linux/Mac)
ping monitoring.jiminhome.shop

# 결과가 "요청 시간 초과" 또는 "알 수 없는 호스트"면 DNS 문제

# 2. hosts 파일에 추가 (위의 "2. DNS 설정" 섹션 참고)
# Windows: C:\Windows\System32\drivers\etc\hosts
# Linux/Mac: /etc/hosts
#
# 다음 라인 추가:
# 192.168.1.200 monitoring.jiminhome.shop
# 192.168.1.200 prometheus.jiminhome.shop

# 3. 브라우저 재시작 후 다시 접속
```

**추가 확인:**
```bash
# Grafana Pod 상태 확인
kubectl get pods -n monitoring | grep grafana

# Ingress 상태 확인
kubectl get ingress -n monitoring

# Ingress Controller 상태 확인
kubectl get pods -n ingress-nginx
```

---

### 문제 2: 192.168.1.195에서도 403 Forbidden

**원인:**
- 실제 IP가 다를 수 있음 (NAT, Proxy)
- Ingress 설정이 적용 안 됨

**해결:**
```bash
# 1. Ingress 설정 확인
kubectl get ingress -n monitoring grafana-ingress -o yaml | grep whitelist

# 2. 실제 접속 IP 확인 (Nginx 로그)
kubectl logs -n ingress-nginx <ingress-controller-pod> | grep monitoring.jiminhome.shop

# 3. Ingress Controller 재시작
kubectl rollout restart deployment -n ingress-nginx ingress-nginx-controller
```

---

### 문제 3: IP 변경 후에도 이전 IP에서 접근됨

**원인:**
- 브라우저 캐시
- Ingress Controller 캐시

**해결:**
```bash
# 1. 브라우저 캐시 삭제 (Ctrl+Shift+Del)

# 2. Ingress 설정 재확인
kubectl get ingress -n monitoring grafana-ingress -o jsonpath='{.metadata.annotations}' | jq

# 3. Ingress Controller 재시작
kubectl rollout restart deployment -n ingress-nginx ingress-nginx-controller
```

---

### 문제 4: 모바일에서 접근하고 싶음

**해결 방법 1: 모바일 IP 추가**
```yaml
# WiFi로 연결 시 192.168.1.XXX IP 확인 후 추가
nginx.ingress.kubernetes.io/whitelist-source-range: "192.168.1.195/32,192.168.1.XXX/32"
```

**해결 방법 2: VPN 사용**
- WireGuard, OpenVPN 등으로 홈 네트워크 접속
- 모바일이 192.168.1.0/24 대역 IP 받음

**해결 방법 3: Port Forward (임시)**
```bash
# kubectl로 임시 포트포워딩
kubectl port-forward -n monitoring svc/grafana 3000:3000

# 로컬호스트로 접근
http://localhost:3000
```

---

## 📊 현재 설정 요약

| 항목 | 값 |
|------|-----|
| **허용 IP** | 192.168.1.0/24 (전체 서브넷) |
| **Grafana URL** | http://monitoring.jiminhome.shop |
| **Prometheus URL** | http://prometheus.jiminhome.shop |
| **Ingress Controller IP** | 192.168.1.200 (LoadBalancer) |
| **externalTrafficPolicy** | Local (원본 IP 보존) |
| **DNS 설정** | /etc/hosts에 192.168.1.200 등록 필요 |
| **인증 방법** | IP Whitelist + Grafana 로그인 |
| **Grafana 로그인** | admin / admin |

---

## 🎯 권장 보안 설정

**현재 (기본):**
- ✅ IP Whitelist (192.168.1.195/32)
- ✅ Grafana 자체 로그인

**권장 (강화):**
- ✅ IP Whitelist (192.168.1.195/32)
- ✅ Grafana admin 패스워드 변경
- ✅ Grafana 사용자 역할 분리
- ⚠️ Basic Auth (선택, 과도할 수 있음)

**최고 (엔터프라이즈):**
- ✅ IP Whitelist
- ✅ TLS/HTTPS (cert-manager)
- ✅ OAuth (Google, GitHub)
- ✅ LDAP 연동

---

## 📋 실제 트러블슈팅 사례

### 사례 1: DNS 설정 누락 + IP Whitelist 차단 (2026-01-20 해결)

**초기 증상:**
1. **404 Not Found** - 브라우저에서 monitoring.jiminhome.shop 접근 시 "사이트에 연결할 수 없음"
2. **403 Forbidden** - DNS 설정 후에도 192.168.1.195에서 접근 차단

---

#### 문제 1: DNS 설정 누락

**증상:**
```
브라우저: "사이트를 찾을 수 없음"
ping monitoring.jiminhome.shop → "알 수 없는 호스트"
```

**원인:**
- `monitoring.jiminhome.shop` 도메인이 공개 DNS에 등록되지 않음
- 로컬 네트워크 전용 도메인이므로 각 PC의 hosts 파일에 등록 필요

**해결:**
```bash
# Windows (PowerShell 관리자 권한)
Add-Content -Path C:\Windows\System32\drivers\etc\hosts -Value "`n192.168.1.200 monitoring.jiminhome.shop"
Add-Content -Path C:\Windows\System32\drivers\etc\hosts -Value "192.168.1.200 prometheus.jiminhome.shop"

# Linux/Mac
sudo bash -c 'cat >> /etc/hosts << EOF
192.168.1.200 monitoring.jiminhome.shop
192.168.1.200 prometheus.jiminhome.shop
EOF'
```

**결과:** DNS 해결 성공, 그러나 여전히 403 Forbidden 발생

---

#### 문제 2: LoadBalancer의 Source IP 손실

**증상:**
```
Ingress Controller 로그:
client: 10.0.1.22 → access forbidden by rule

실제 접근 IP: 192.168.1.195
Ingress가 보는 IP: 10.0.1.22 (Pod IP)
```

**원인:**
```
Windows PC (192.168.1.195)
    ↓
LoadBalancer Service (192.168.1.200)
    ↓ [externalTrafficPolicy: Cluster]
    ↓ ❌ SNAT 발생 - 원본 IP 손실
    ↓
Ingress Controller Pod (10.0.1.22)
    ↓
IP Whitelist 체크: 10.0.1.22 ≠ 192.168.1.195
    ↓
❌ 403 Forbidden
```

**문제의 핵심:**
- LoadBalancer Service의 `externalTrafficPolicy: Cluster` 설정
- Cluster 모드에서는 트래픽이 모든 노드로 분산되며, 이 과정에서 **SNAT(Source NAT)**이 발생하여 원본 클라이언트 IP가 Pod IP로 변경됨

**시도한 해결 방법 (실패):**
```yaml
# Ingress에 annotation 추가 - 효과 없음
nginx.ingress.kubernetes.io/enable-real-ip: "true"
nginx.ingress.kubernetes.io/use-forwarded-headers: "true"
```
→ LoadBalancer에서 이미 IP가 변경되었기 때문에 Ingress 레벨에서 복구 불가능

**최종 해결:**
```bash
# LoadBalancer Service의 externalTrafficPolicy를 Local로 변경
kubectl patch svc -n ingress-nginx ingress-nginx-controller \
  -p '{"spec":{"externalTrafficPolicy":"Local"}}'

# IP Whitelist를 서브넷으로 확장 (필요시)
kubectl annotate ingress -n monitoring grafana-ingress \
  nginx.ingress.kubernetes.io/whitelist-source-range="192.168.1.0/24" --overwrite
```

**externalTrafficPolicy 비교:**

| 설정 | 장점 | 단점 | 원본 IP 보존 |
|------|------|------|-------------|
| **Cluster** | 모든 노드로 로드밸런싱, 장애 허용 높음 | SNAT 발생, 원본 IP 손실 | ❌ |
| **Local** | 원본 IP 보존, 불필요한 홉 제거 | 노드 간 불균형 가능 | ✅ |

**결과:**
```
Windows PC (192.168.1.195)
    ↓
LoadBalancer Service (192.168.1.200)
    ↓ [externalTrafficPolicy: Local]
    ↓ ✅ 원본 IP 유지
    ↓
Ingress Controller (client: 192.168.1.195)
    ↓
IP Whitelist 체크: 192.168.1.195 ∈ 192.168.1.0/24
    ↓
✅ 접근 허용 → Grafana Dashboard
```

---

#### 핵심 교훈

1. **DNS 설정은 필수**
   - 로컬 네트워크 전용 도메인은 hosts 파일 등록 필요
   - 공개 DNS 없이 도메인만 설정하면 접근 불가

2. **LoadBalancer externalTrafficPolicy 이해**
   - `Cluster`: 로드밸런싱 우수, IP 손실
   - `Local`: IP 보존, IP Whitelist 사용 시 필수

3. **Ingress IP Whitelist의 한계**
   - Ingress 레벨에서는 LoadBalancer를 거친 후의 IP만 볼 수 있음
   - 원본 IP 보존은 LoadBalancer 레벨에서 해결해야 함

4. **트러블슈팅 순서**
   - DNS 확인 → 네트워크 연결 확인 → IP 검증 → 로그 분석

---

## 📝 설정 변경 로그

| 날짜 | 변경 내용 |
|------|----------|
| 2026-01-20 | **DNS 설정 추가** (hosts 파일에 192.168.1.200 등록) |
| 2026-01-20 | **externalTrafficPolicy: Local** (원본 IP 보존) |
| 2026-01-20 | **IP Whitelist: 192.168.1.0/24** (전체 서브넷 허용) |
| 2026-01-20 | 초기 IP Whitelist 설정: 192.168.1.195/32 (실패) |
| 2026-01-19 | Grafana/Prometheus Ingress 생성 |

---

**접근 문제가 있거나 설정 변경이 필요하면 언제든지 문의하세요!**
