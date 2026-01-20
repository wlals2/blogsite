# Dashboard 접근 문제 해결 요약 (2026-01-20)

> monitoring.jiminhome.shop 접근 실패 → 완전 해결

---

## 🎯 최종 결과

✅ **Grafana Dashboard 접근 성공**
- URL: http://monitoring.jiminhome.shop
- 허용 네트워크: 192.168.1.0/24
- 로그인: admin / admin

---

## 🔍 발생한 문제

### 문제 1: 404 Not Found / 사이트 연결 불가
**증상:**
```
브라우저: "사이트를 찾을 수 없음"
ping monitoring.jiminhome.shop → 실패
```

### 문제 2: 403 Forbidden
**증상:**
```
DNS 설정 후에도 192.168.1.195에서 접근 차단
Ingress 로그: client: 10.0.1.22 (실제 192.168.1.195)
```

---

## 🛠️ 해결 과정

### 1단계: DNS 설정 (필수)

**문제:**
- `monitoring.jiminhome.shop` 도메인이 공개 DNS에 미등록
- 로컬 네트워크 전용 도메인

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

**결과:** DNS 해결 → 그러나 여전히 403 Forbidden

---

### 2단계: IP Whitelist 문제 해결

#### 근본 원인 분석

```
Windows PC (192.168.1.195)
    ↓
LoadBalancer Service (192.168.1.200)
    ↓ [externalTrafficPolicy: Cluster] ← 문제!
    ↓ SNAT 발생 - 원본 IP 손실
    ↓
Ingress Controller Pod (10.0.1.22)
    ↓
Ingress가 보는 IP: 10.0.1.22 (Pod IP)
    ↓
IP Whitelist 체크: 10.0.1.22 ≠ 192.168.1.195
    ↓
❌ 403 Forbidden
```

#### 시도 1: Ingress annotation (실패)

```yaml
nginx.ingress.kubernetes.io/enable-real-ip: "true"
nginx.ingress.kubernetes.io/use-forwarded-headers: "true"
```

**실패 이유:** LoadBalancer에서 이미 IP가 변경되었으므로 Ingress 레벨에서 복구 불가능

#### 시도 2: LoadBalancer 설정 변경 (성공)

```bash
# externalTrafficPolicy를 Local로 변경
kubectl patch svc -n ingress-nginx ingress-nginx-controller \
  -p '{"spec":{"externalTrafficPolicy":"Local"}}'
```

**성공 이유:** Local 모드는 원본 클라이언트 IP를 보존

#### 시도 3: IP Whitelist 확장

```bash
# 단일 IP (192.168.1.195/32) → 전체 서브넷 (192.168.1.0/24)
kubectl annotate ingress -n monitoring grafana-ingress \
  nginx.ingress.kubernetes.io/whitelist-source-range="192.168.1.0/24" --overwrite

kubectl annotate ingress -n monitoring prometheus-ingress \
  nginx.ingress.kubernetes.io/whitelist-source-range="192.168.1.0/24" --overwrite
```

---

## 📊 externalTrafficPolicy 비교

| 설정 | 원본 IP 보존 | 로드밸런싱 | 사용 사례 |
|------|-------------|----------|----------|
| **Cluster** | ❌ (SNAT 발생) | ✅ 모든 노드 | 일반적인 서비스 |
| **Local** | ✅ 보존 | ⚠️ 노드 제한 | IP Whitelist 필요 시 |

---

## 🎓 핵심 교훈

### 1. DNS 설정 필수
- 로컬 네트워크 전용 도메인은 hosts 파일 등록 필요
- 공개 DNS 없이는 도메인 접근 불가

### 2. LoadBalancer externalTrafficPolicy 이해
- **Cluster**: 로드밸런싱 우수, 원본 IP 손실 (SNAT 발생)
- **Local**: 원본 IP 보존, IP Whitelist 사용 시 필수
- IP 기반 인증/제한 시 반드시 Local 사용

### 3. Ingress IP Whitelist의 한계
- Ingress는 LoadBalancer를 거친 후의 IP만 확인 가능
- 원본 IP 보존은 LoadBalancer 레벨에서 해결해야 함
- Ingress annotation만으로는 SNAT 문제 해결 불가

### 4. 트러블슈팅 순서
```
1. DNS 확인 (ping, nslookup)
   ↓
2. 네트워크 연결 확인 (curl -I)
   ↓
3. 로그 분석 (kubectl logs)
   ↓
4. IP 추적 (client IP 확인)
   ↓
5. 설정 검증 (Service, Ingress)
```

---

## 📝 최종 설정

### Ingress Controller Service

```yaml
apiVersion: v1
kind: Service
metadata:
  name: ingress-nginx-controller
  namespace: ingress-nginx
spec:
  type: LoadBalancer
  externalTrafficPolicy: Local  # ← 핵심 설정
  loadBalancerIP: 192.168.1.200
```

### Grafana Ingress

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: grafana-ingress
  namespace: monitoring
  annotations:
    nginx.ingress.kubernetes.io/rewrite-target: /
    nginx.ingress.kubernetes.io/whitelist-source-range: "192.168.1.0/24"
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
```

### DNS (hosts 파일)

```
# Windows: C:\Windows\System32\drivers\etc\hosts
# Linux/Mac: /etc/hosts
192.168.1.200 monitoring.jiminhome.shop
192.168.1.200 prometheus.jiminhome.shop
```

---

## 🔄 네트워크 흐름 (최종)

```
Windows PC (192.168.1.195)
    ↓ DNS: monitoring.jiminhome.shop → 192.168.1.200
    ↓
LoadBalancer Service (192.168.1.200)
    ↓ [externalTrafficPolicy: Local]
    ↓ ✅ 원본 IP 유지: 192.168.1.195
    ↓
Ingress Controller
    ↓ client: 192.168.1.195
    ↓
IP Whitelist 체크: 192.168.1.195 ∈ 192.168.1.0/24
    ↓ ✅ 허용
    ↓
Grafana Service (10.105.160.30:3000)
    ↓
Grafana Pod (10.0.1.26:3000)
    ↓
✅ Dashboard 표시
```

---

## 📚 관련 문서

- **메인 가이드**: [README.md](./README.md)
- **접근 가이드**: [ACCESS-GUIDE.md](./ACCESS-GUIDE.md)
- **다음 계획**: [NEXT-STEPS.md](./NEXT-STEPS.md)

---

## 🔧 참고 명령어

### 현재 설정 확인
```bash
# LoadBalancer Service 확인
kubectl get svc -n ingress-nginx ingress-nginx-controller -o yaml | grep externalTrafficPolicy

# Ingress IP Whitelist 확인
kubectl get ingress -n monitoring grafana-ingress -o jsonpath='{.metadata.annotations.nginx\.ingress\.kubernetes\.io/whitelist-source-range}'

# 최근 접근 로그 확인
kubectl logs -n ingress-nginx $(kubectl get pods -n ingress-nginx -o jsonpath='{.items[0].metadata.name}') --tail=20 | grep monitoring.jiminhome.shop
```

### DNS 확인
```bash
# Windows
ping monitoring.jiminhome.shop

# Linux/Mac
getent hosts monitoring.jiminhome.shop
```

### 접근 테스트
```bash
# 서버에서 테스트 (403 예상 - 서버 IP는 화이트리스트에 없음)
curl -I http://monitoring.jiminhome.shop

# 192.168.1.X 대역에서 테스트 (200 예상)
curl -I http://monitoring.jiminhome.shop
```

---

**문제 해결 완료: 2026-01-20**
**소요 시간: 약 2시간** (DNS 설정 누락 발견 → LoadBalancer 설정 변경 → 접근 성공)
