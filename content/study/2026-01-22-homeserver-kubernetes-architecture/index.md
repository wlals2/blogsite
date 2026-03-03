---
title: "Homeserver K8s 현재 상태 및 다음 구축 계획"
date: 2026-01-22T12:00:00+09:00
summary: "Homeserver Kubernetes 아키텍처 완성: GitHub Actions CI + ArgoCD GitOps + Argo Rollouts Canary 배포 구현 완료"
tags: ["kubernetes", "homeserver", "architecture", "argocd", "argo-rollouts", "gitops"]
categories: ["study", "Kubernetes"]
series: ["홈랩 K8s 아키텍처 시리즈"]
weight: 1
showtoc: true
tocopen: true
draft: false
---

## 📌 현재 상태 (2026-01-22 업데이트)

처음 Kubernetes를 시작했을 때는 "과연 내가 이걸 구축할 수 있을까?"라는 의구심이 들었어요. 근데 지금은 완전한 GitOps CI/CD 파이프라인까지 만들어냈습니다!

### 실제 구축된 아키텍처

```
외부 (192.168.1.200)
  ↓
MetalLB (Load Balancer)
  ↓
Ingress Nginx Controller
  ↓
┌─────────────────────────────────────┐
│  blog-system Namespace              │
│                                     │
│  ┌─────┐    ┌─────┐    ┌───────┐  │
│  │ WEB │ →  │ WAS │ →  │ MySQL │  │
│  │nginx│    │Java │    │  Pod  │  │
│  └─────┘    └─────┘    └───────┘  │
│                                     │
└─────────────────────────────────────┘

기반 인프라:
- Cilium CNI (eBPF 기반)
- Longhorn Storage (분산 스토리지)
- Prometheus + Grafana (모니터링)
- Cert-manager (TLS 인증서)
```

---

## 현재 구축 완료된 것

### 1. 네트워킹

```bash
kubectl get namespaces | grep -E "ingress|metallb"

# 결과:
ingress-nginx          Active   3d7h
metallb-system         Active   46h
```

처음엔 "LoadBalancer가 뭐지?"라고 궁금했는데, 알고 보니 외부에서 접속하려면 꼭 필요한 거더라구요.

**Ingress Nginx**:
- LoadBalancer 타입 (MetalLB 연동)
- 외부 IP: 192.168.1.200
- HTTP(80), HTTPS(443) 포트

**MetalLB**:
- 베어메탈 환경의 LoadBalancer 구현
- IP Pool: 192.168.1.200-192.168.1.210

---

### 2. CNI (Container Network Interface)

```bash
kubectl get pods -n kube-system | grep cilium

# Cilium DaemonSet이 각 노드에서 실행 중
```

**Cilium**:
- eBPF 기반 고성능 네트워킹
- Hubble UI 활성화됨 (NodePort 31234)
- 네트워크 플로우 관찰 가능

처음엔 "Calico vs Cilium 뭘 쓰지?"라고 고민했는데, Cilium의 eBPF 기술이 매력적이어서 선택했어요.

---

### 3. 스토리지

```bash
kubectl get namespace longhorn-system

# 결과:
longhorn-system   Active   54d
```

**Longhorn**:
- 분산 블록 스토리지
- 3 replica 설정
- MySQL PVC로 사용 중

데이터가 날아가면 안 되니까... Longhorn으로 안전하게 보관하고 있어요.

---

### 4. 애플리케이션 (blog-system)

```bash
kubectl get pods -n blog-system

# 결과:
NAME                              READY   STATUS
web-5bd74744c7-9b98q              1/1     Running
web-5bd74744c7-ctf6v              1/1     Running
was-d85c45cdb-qwjn7               1/1     Running
was-d85c45cdb-r96vr               1/1     Running
mysql-65f4d695d4-wpmtg            1/1     Running
mysql-exporter-59b58fdd67-6wlkv   1/1     Running
```

**WEB (nginx)**:
- Replicas: 2
- Hugo 블로그 정적 파일 서빙

**WAS (Spring Boot)**:
- Replicas: 2
- 게시판 CRUD 애플리케이션

**MySQL**:
- Replicas: 1
- Longhorn PVC 사용 (영구 저장)

**MySQL Exporter**:
- Prometheus 메트릭 수집

---

### 5. 모니터링

```bash
kubectl get namespace monitoring

# 결과:
monitoring   Active   54d
```

**스택**:
- Prometheus: 메트릭 수집 (NodePort 30090)
- Grafana: 대시보드 (NodePort 30300)
- Pushgateway: 커스텀 메트릭 (NodePort 30091)

이제는 뭔가 문제가 생기면 Grafana부터 확인하게 됐어요.

---

### 6. TLS 인증서

```bash
kubectl get namespace cert-manager

# 결과:
cert-manager   Active   46h
```

**Cert-manager**:
- Let's Encrypt 자동 인증서 발급
- HTTPS 지원

처음엔 수동으로 인증서를 갱신했는데, cert-manager가 자동으로 해주니까 정말 편해요.

---

### 7. GitOps (ArgoCD) - ✅ 완료!

```bash
kubectl get pods -n argocd

# 결과:
NAME                                                READY   STATUS
argocd-application-controller-0                     1/1     Running
argocd-applicationset-controller-6564d9cfd8-p2djw   1/1     Running
argocd-dex-server-6ff5b587ff-5lmlb                  1/1     Running
argocd-notifications-controller-6594786484-npvkg    1/1     Running
argocd-redis-7d6fd75fcb-926x8                       1/1     Running
argocd-repo-server-567d8799cf-fs94b                 1/1     Running
argocd-server-5f8b4dfd84-bbqlt                      1/1     Running
```

**ArgoCD**:
- 설치 방법: **Helm Chart** (argo/argo-cd)
- 버전: v3.2.5
- 초기 비밀번호: `saDtmwkg-ZyKLv2T` (admin)
- 접속 방법:
  - 로컬: `https://192.168.1.200/` (Host: argocd.jiminhome.shop)
  - 외부: `https://argocd.jiminhome.shop` (Cloudflare Tunnel DNS 추가됨)
- Ingress: nginx (192.168.1.200:443)
- Cloudflare Tunnel: DNS 라우팅 추가 완료

처음 ArgoCD를 설치했을 때는 "이게 정말 Git으로 자동 배포되는 건가?"라고 의심했어요. 근데 지금은 Git push만 하면 3초 안에 배포되더라구요!

**다음 단계**: Application 생성 및 Git Repository 연동

---

## CI/CD 완료 (2026-01-22 업데이트)

### 1. GitOps 자동 배포 - 완료!
- **GitHub Actions**: CI 파이프라인 (Self-hosted Runner, 35초 배포)
- **ArgoCD**: GitOps 완전 자동화 (Auto-Sync, Prune, SelfHeal)
- **Argo Rollouts**: Canary 배포 구현 완료 (20% → 50% → 80% → 100%)

처음엔 "배포가 이렇게 빠를 수 있나?"라고 놀랐어요. GitHub Actions + ArgoCD 조합이 정말 강력하더라구요.

**현재 배포 방식**: Git Push → GitHub Actions → ArgoCD Auto-Sync (완전 자동화)

---

### 2. Service Mesh
- Istio: mTLS, Traffic Routing, Tracing 없음
- Cilium: L3-4 네트워킹만 (L7 Service Mesh 아님)

Istio는 나중에 필요할 때 추가하기로 했어요. 지금은 Argo Rollouts만으로도 충분해요.

---

## 구축 로드맵

### Phase 1: GitOps 자동 배포 (ArgoCD) - ✅ 완료!

**목표**: Git Push → 자동 배포

**구축 순서**:
1. ArgoCD 설치 ✅
2. Git Repository 준비 (Manifest 저장) ✅
3. Application 생성 (blog-system) ✅
4. Sync Policy 설정 (자동 동기화) ✅

처음엔 "과연 3시간 안에 끝낼 수 있을까?"라고 걱정했는데, 생각보다 수월했어요.

**예상 소요 시간**: 2-3시간

**배운 것**:
- GitOps 원리 (Pull 방식)
- Declarative Configuration
- Self-healing

---

### Phase 2: Canary 배포 (Argo Rollouts) - ✅ 완료!

**목표**: 안전한 단계적 배포 (20% → 50% → 80% → 100%)

**구축 순서**:
1. Argo Rollouts 설치 ✅
2. Deployment → Rollout 변환 ✅
3. Canary 전략 정의 ✅
4. 실제 배포 테스트 ✅

처음 Canary 배포를 봤을 때 "이게 정말 20%만 배포되는 건가?"라고 신기했어요. 모니터링하면서 점진적으로 늘려가니까 안전하더라구요.

**예상 소요 시간**: 2-3시간

**배운 것**:
- Canary 배포 원리
- Rollback 전략
- Blast Radius 최소화

---

### Phase 3: Service Mesh (Istio) - 선택 사항

**목표**:
- 정확한 트래픽 제어 (정확히 10%)
- mTLS (Pod 간 암호화)
- Distributed Tracing
- Circuit Breaker

**구축 순서**:
1. Istio 설치 (minimal 프로파일)
2. Sidecar Injection
3. VirtualService + DestinationRule
4. mTLS 활성화
5. Jaeger Tracing

처음엔 "Istio를 꼭 써야 하나?"라고 고민했는데, 지금은 필요할 때 추가하기로 했어요.

**예상 소요 시간**: 6-8시간 (트러블슈팅 포함)

**배우는 것**:
- Service Mesh 아키텍처
- Envoy Proxy 동작 원리
- mTLS Handshake
- L7 트래픽 관리

**트레이드오프**:
- 고급 기능 (mTLS, Tracing, Circuit Breaker)
- 메모리 +100MB/Pod
- 지연시간 +5ms
- 복잡도 증가

---

## Istio vs Ingress Nginx 비교

### 현재 (Ingress Nginx)

**역할**: L7 Reverse Proxy + Load Balancer

```
외부 요청
  ↓
Ingress Nginx (192.168.1.200:80/443)
  ↓ (Path-based Routing)
/        → web-service
/board   → was-service
```

**기능**:
- HTTP/HTTPS Routing
- TLS Termination
- Path-based Routing
- Canary 배포 (가중치 제어)
- mTLS (Pod 간 암호화)
- Distributed Tracing

---

### Istio 추가 시 (Istio Ingress Gateway)

**역할**: Ingress Nginx를 대체 또는 함께 사용

**Option 1: Istio만 사용** (Ingress Nginx 제거)
```
외부 요청
  ↓
Istio Ingress Gateway (192.168.1.200:80/443)
  ↓
VirtualService (Canary 10%)
  ↓
Envoy Sidecar (mTLS)
  ↓
Pod
```

**장점**:
- Canary 배포 (정확히 10% 트래픽)
- Header 기반 라우팅 (A/B 테스트)
- Fault Injection (카오스 엔지니어링)

**단점**:
- 복잡도 증가
- 메모리 사용량 증가

---

**Option 2: 함께 사용** (추천)
```
외부 요청
  ↓
Ingress Nginx (TLS Termination)
  ↓
Istio Ingress Gateway
  ↓
VirtualService + Envoy Sidecar
  ↓
Pod
```

**왜?**
- Ingress Nginx: TLS 처리 (Cert-manager 연동)
- Istio: 내부 트래픽 관리 (Canary, mTLS)

---

## 🔄 구축 우선순위 (추천)

### 1순위: ArgoCD (필수) - ✅ 완료!

**이유**:
- 수동 배포 → 자동 배포 (생산성 향상)
- Git = Source of Truth (감사 추적)
- 가볍고 간단함

처음 ArgoCD를 도입했을 때 정말 감동받았어요. Git push만 하면 알아서 배포되니까요!

**시작 시점**: 즉시

---

### 2순위: Argo Rollouts (권장) - ✅ 완료!

**이유**:
- Canary 배포 (안전한 배포)
- ArgoCD와 함께 사용 (시너지)
- Istio 없이도 가능

**시작 시점**: ArgoCD 구축 후 1주일

---

### 3순위: Istio (선택)

**이유**:
- 고급 기능 (mTLS, Tracing, Circuit Breaker)
- 학습 가치 (Service Mesh 경험)

**시작 조건** (다음 중 1개라도 해당 시):
- Argo Rollouts의 트래픽 제어가 부정확함을 체감
- Pod 간 mTLS 필요성 발생
- Distributed Tracing 필요 (복잡한 디버깅)
- 서비스가 10개 이상으로 증가

**시작 시점**: Argo Rollouts 구축 후 1-2주 (또는 필요성 체감 시)

---

## 리소스 사용량 예측

### 현재 상태
```bash
kubectl top nodes

# 예상 (8GB RAM 홈서버):
NAME           CPU    MEMORY
master-node    15%    3500Mi (43%)
worker-node1   20%    4000Mi (50%)
```

---

### ArgoCD 추가 후
```
Memory: +300MB (ArgoCD Pods)
Total: 4300Mi (53%)
```

**여유**: 충분 ✅

---

### Argo Rollouts 추가 후
```
Memory: +100MB (Rollouts Controller)
Total: 4400Mi (55%)
```

**여유**: 충분 ✅

---

### Istio 추가 후
```
Memory:
  - Istiod (Control Plane): +200MB
  - Envoy Sidecar: +100MB × 6 Pods = +600MB
Total: 5200Mi (65%)
```

**여유**: 제한적 ⚠️

**대책**:
1. Envoy 리소스 제한 (64Mi → 128Mi)
2. 불필요한 Pod Sidecar Injection 제외
3. Ambient Mesh 검토 (Sidecar-less)

---

## 다음 행동

### 1단계: ArgoCD 구축 (진행 중) - ✅ 완료!

**목표**: Git Push → 자동 배포

처음 시작할 때는 막막했는데, 하나씩 해나가니까 완성됐어요!

**진행 상황**:
- [x] **Helm vs kubectl apply 비교 분석** (완료)
  - 결론: Helm 사용 (버전 관리, 롤백 가능)
  - 26,951줄 YAML vs values.yaml 수정만

- [x] **ArgoCD Helm 설치** (완료)
  - Chart: argo/argo-cd v9.3.4
  - App Version: v3.2.5
  - Namespace: argocd
  - 모든 Pod Running 확인

- [x] **Ingress 설정** (완료)
  - Host: argocd.jiminhome.shop
  - Service: argocd-server:443
  - TLS Passthrough (Self-signed 인증서)
  - 로컬 접속 테스트 완료: `curl -I -H "Host: argocd.jiminhome.shop" https://192.168.1.200/`

- [x] **Cloudflare Tunnel DNS 라우팅** (완료)
  - DNS: argocd.jiminhome.shop → home-network tunnel
  - 명령어: `cloudflared tunnel route dns home-network argocd.jiminhome.shop`
  - 외부 접속: https://argocd.jiminhome.shop (DNS 전파 대기 중)

- [x] **Git Repository 준비** ✅
  - k8s-manifests repo (https://github.com/wlals2/k8s-manifests)
  - blog-system namespace manifests 관리

- [x] **Application 생성** ✅
  - blog-system Application 생성 완료
  - Git Repository 연동 완료

- [x] **Sync Policy 자동화** ✅
  - Auto-Sync 활성화
  - Self-Heal 설정
  - Auto-Prune 설정

- [x] **실제 배포 테스트** ✅
  - Git Push → 3초 내 자동 동기화
  - 35초 완전 배포 (GitHub Actions + ArgoCD)

**배운 것**:
- Helm의 가치: 26,951줄 YAML을 values.yaml 수정만으로 관리
- Helm 히스토리: `helm history argocd`로 버전 관리
- Helm 롤백: `helm rollback argocd 1`로 즉시 복구 가능
- Cloudflare Tunnel: `cloudflared tunnel route dns`로 DNS 추가
- Ingress 동작 원리: Host 헤더 기반 라우팅

**트러블슈팅 경험**:
1. **Cloudflared 설정 미반영 문제**:
   - 원인: Systemd가 `/etc/cloudflared/config.yml` 사용, 사용자 config 우선순위 혼동
   - 해결: 두 설정 파일 모두 업데이트 + `warp-routing` 제거
   - 대안: DNS 라우팅 직접 추가 (`cloudflared tunnel route dns`)

2. **Ingress TLS 설정**:
   - Self-signed 인증서 → `noTLSVerify: true` 필요
   - Annotation: `nginx.ingress.kubernetes.io/ssl-passthrough: "true"`

---

### 2단계: Argo Rollouts 구축 - ✅ 완료!

**목표**: Canary 배포 (20% → 50% → 80% → 100%)

**체크리스트**:
- [x] Argo Rollouts 설치 ✅
- [x] Rollout 정의 (WEB + WAS) ✅
- [x] Canary 배포 테스트 ✅
- [x] Istio Traffic Routing 연동 ✅
- [x] dynamicStableScale + topologySpreadConstraints ✅

---

### 3단계: Istio 검토 (필요 시)

**판단 기준**:
- Argo Rollouts로 해결 안 되는 문제 발생?
- mTLS 필요성 발생?
- Tracing 필요성 발생?
- 메모리 여유 확인 (50% 이하?)

---

**최초 작성일**: 2026-01-19
**최종 업데이트**: 2026-01-22
**환경**: Homeserver Kubernetes (Cilium + Ingress Nginx + ArgoCD + Argo Rollouts)
**현재 단계**: ✅ Phase 1 (ArgoCD) + Phase 2 (Argo Rollouts) 완료!
**다음 단계**: Phase 3 (Istio Service Mesh) - 필요 시 구축
