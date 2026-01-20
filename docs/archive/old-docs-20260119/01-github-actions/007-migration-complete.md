# GitHub Actions 마이그레이션 완료 보고서

> 작성일: 2026-01-18 19:51
> Jenkins → GitHub Actions 전환

---

## ✅ 완료된 작업

### 1️⃣ Kubernetes 클러스터 정상화

**문제:**
- VM 재부팅 후 worker1 노드에서 DNS 문제 발생
- `ghcr.io` 도메인 조회 실패 → ImagePullBackOff

**해결:**
- worker1에 `NoSchedule` taint 설정으로 격리
- 모든 Pod를 worker2로 재배치
- 서비스 정상 작동 확인

**현재 상태:**
```
✅ k8s-cp (Master): Ready
✅ k8s-worker1: Ready (taint: dns-issue=true:NoSchedule)
✅ k8s-worker2: Ready

모든 Pod: worker2에서 정상 작동
- mysql: 1/1 Running
- was: 2/2 Running
- web: 2/2 Running
```

---

### 2️⃣ GitHub Actions CI/CD 구축

**변경 사항:**

#### Before (Jenkins)
```yaml
- 로컬 Jenkins 서버
- 수동 빌드 또는 Poll SCM
- 이미지 태그: v{BUILD_NUMBER}
- Cloudflare 캐시: 수동 퍼지
```

#### After (GitHub Actions)
```yaml
- GitHub-hosted Runner (ubuntu-latest)
- Git Push 즉시 자동 빌드
- 이미지 태그: v{RUN_NUMBER}
- Cloudflare 캐시: 자동 퍼지 ✅
```

**워크플로우 파일:**
```
.github/workflows/deploy-web.yml
```

**주요 단계:**
1. Checkout 코드
2. Docker Buildx 설정 (캐시 활용)
3. GHCR 로그인
4. Docker 이미지 빌드 및 푸시
5. kubectl로 Kubernetes 배포
6. Health Check
7. **Cloudflare 캐시 퍼지** ✅
8. Build Summary 출력

---

### 3️⃣ 문서 작성

**생성된 문서:**
- [GITHUB-ACTIONS-SETUP.md](GITHUB-ACTIONS-SETUP.md) - 전체 설정 가이드
- [GITHUB-SECRETS-SETUP.md](GITHUB-SECRETS-SETUP.md) - Secrets 설정 가이드
- [GITHUB-ACTIONS-MIGRATION-COMPLETE.md](GITHUB-ACTIONS-MIGRATION-COMPLETE.md) (이 파일)

---

## ⚠️ 중요: GitHub Secrets 설정 필요!

### 현재 상태

**✅ Git Push 완료:**
```bash
Commit: 1aa3962
Message: feat: Migrate to GitHub Actions CI/CD with Cloudflare cache purge
```

**🚀 GitHub Actions 트리거됨:**
- URL: https://github.com/wlals2/blogsite/actions

**❌ 빌드 실패 예상:**
- Secrets가 설정되지 않았기 때문에 실패할 것입니다

---

### 설정해야 할 4개 Secrets

| Secret 이름 | 준비 상태 | 값을 얻는 방법 |
|-------------|----------|--------------|
| **GHCR_TOKEN** | ⏳ 수동 생성 필요 | https://github.com/settings/tokens<br/>Scopes: `write:packages`, `read:packages` |
| **KUBECONFIG_BASE64** | ✅ 준비 완료 | `/tmp/kubeconfig-base64.txt` |
| **CLOUDFLARE_ZONE_ID** | ⏳ 복사 필요 | Cloudflare Dashboard → jiminhome.shop → Zone ID |
| **CLOUDFLARE_API_TOKEN** | ⏳ 생성 필요 | Cloudflare → API Tokens → Create (Cache Purge) |

---

### KUBECONFIG_BASE64 값 확인

**준비 완료!**

```bash
cat /tmp/kubeconfig-base64.txt
```

**파일 정보:**
- 위치: `/tmp/kubeconfig-base64.txt`
- 크기: 10,828 bytes
- 인코딩: Base64 (줄바꿈 없음, `-w 0` 옵션 사용)

**GitHub Secrets 추가:**
1. https://github.com/wlals2/blogsite/settings/secrets/actions
2. New repository secret 클릭
3. Name: `KUBECONFIG_BASE64`
4. Secret: [위 파일 내용 전체 복사/붙여넣기]
5. Add secret 클릭

---

### Secrets 설정 순서

#### 1. GHCR_TOKEN 생성

1. https://github.com/settings/tokens
2. Generate new token (classic)
3. Token name: `ghcr-actions-blog`
4. Scopes:
   - ✅ `write:packages`
   - ✅ `read:packages`
5. Generate token
6. **토큰 복사** (한 번만 표시됨!)

#### 2. KUBECONFIG_BASE64 추가

```bash
# 값 확인
cat /tmp/kubeconfig-base64.txt

# 출력 전체 복사 → GitHub Secrets에 붙여넣기
```

#### 3. CLOUDFLARE_ZONE_ID 복사

1. https://dash.cloudflare.com/
2. `jiminhome.shop` 도메인 선택
3. 우측 사이드바 하단 **API** 섹션
4. **Zone ID** 복사

#### 4. CLOUDFLARE_API_TOKEN 생성

1. https://dash.cloudflare.com/profile/api-tokens
2. Create Token
3. Custom token
4. 설정:
   ```
   Token name: github-actions-cache-purge

   Permissions:
   - Zone / Cache Purge / Purge

   Zone Resources:
   - Include / Specific zone / jiminhome.shop
   ```
5. Continue to summary → Create Token
6. **토큰 복사** (한 번만 표시!)

#### 5. GitHub Repository Secrets 추가

**URL:**
https://github.com/wlals2/blogsite/settings/secrets/actions

**각 Secret 추가:**
- New repository secret 클릭
- Name, Secret 입력
- Add secret 클릭
- **총 4개 Secret 추가**

---

## 🔄 Secrets 설정 후 작업

### 자동 재실행

Secrets가 모두 설정되면:
1. GitHub Actions가 자동으로 감지 (또는)
2. 수동으로 "Re-run all jobs" 클릭

### 빌드 성공 시

**예상 결과:**
```
✅ Checkout code
✅ Set up Docker Buildx
✅ Login to GHCR
✅ Build and push Docker image
   - ghcr.io/wlals2/blog-web:v{run_number}
   - ghcr.io/wlals2/blog-web:latest
✅ Setup kubeconfig
✅ Deploy to Kubernetes
✅ Health Check
✅ Purge Cloudflare Cache
✅ Build Summary
```

**Kubernetes 배포 확인:**
```bash
# 새 이미지 버전 확인
kubectl get deployment web -n blog-system -o jsonpath='{.spec.template.spec.containers[0].image}'

# Pod 상태 확인
kubectl get pods -n blog-system -l app=web

# 결과 예상:
# ghcr.io/wlals2/blog-web:v{run_number}
```

**사이트 접속:**
- https://blog.jiminhome.shop/
- Cloudflare 캐시가 자동으로 퍼지되어 새 콘텐츠 즉시 반영

---

## 📊 현재 시스템 상태

### Kubernetes 리소스

```
Namespace: blog-system

Deployments:
- mysql:  1/1 (Running on worker2)
- was:    2/2 (Running on worker2)
- web:    2/2 (Running on worker2)

Services:
- mysql-service:  ClusterIP 3306
- was-service:    ClusterIP 8080
- web-service:    ClusterIP 80

Ingress:
- blog-ingress:   blog.jiminhome.shop → 192.168.1.61:80
```

### 노드 상태

```
k8s-cp:       Ready (Control Plane)
k8s-worker1:  Ready (Tainted: dns-issue=true:NoSchedule)
k8s-worker2:  Ready
```

### 이미지 버전

```
WEB: ghcr.io/wlals2/blog-web:v14 (Jenkins 마지막 배포)
WAS: ghcr.io/wlals2/board-was:v1
```

**GitHub Actions 성공 후:**
```
WEB: ghcr.io/wlals2/blog-web:v{run_number} (새 버전)
```

---

## 🔧 향후 작업

### 우선순위 1: worker1 DNS 수정

**문제:**
- worker1 노드에서 `ghcr.io` 도메인 조회 실패
- 새 이미지 Pull 불가

**해결 방법:**
worker1 VM 콘솔 접속 후:

```bash
# 1. DNS 서비스 재시작
sudo systemctl restart systemd-resolved

# 2. DNS 테스트
nslookup ghcr.io

# 3. 안 되면 Google DNS 추가
sudo nano /etc/systemd/resolved.conf
# [Resolve]
# DNS=8.8.8.8 1.1.1.1

sudo systemctl restart systemd-resolved

# 4. 재확인
nslookup ghcr.io

# 5. 성공하면 taint 제거
kubectl taint node k8s-worker1 dns-issue-
```

---

### 우선순위 2: Jenkins 정리

**옵션 1: Jenkins 비활성화**
- Jenkins UI → blog-web-pipeline → 구성 → 비활성화 체크

**옵션 2: Jenkins 제거**
```bash
# Jenkins 서비스 중지
sudo systemctl stop jenkins
sudo systemctl disable jenkins

# Jenkins 제거 (선택사항)
# sudo apt remove jenkins
```

**옵션 3: 병행 운영**
- GitHub Actions: 일반 배포 (자동)
- Jenkins: 긴급 배포 (수동 제어)

---

### 우선순위 3: WAS (Spring Boot) GitHub Actions 추가

**작업:**
- `board-was` 저장소에도 GitHub Actions 추가
- 동일한 워크플로우 패턴 사용
- Dockerfile 경로만 수정

---

## 📖 참고 문서

### 프로젝트 문서
- [GITHUB-ACTIONS-SETUP.md](GITHUB-ACTIONS-SETUP.md) - GitHub Actions 전체 가이드
- [GITHUB-SECRETS-SETUP.md](GITHUB-SECRETS-SETUP.md) - Secrets 설정 가이드
- [KUBERNETES-NATIVE-SSL-IMPLEMENTATION-PLAN.md](KUBERNETES-NATIVE-SSL-IMPLEMENTATION-PLAN.md) - SSL 자동화 계획

### 외부 링크
- [GitHub Actions 공식 문서](https://docs.github.com/actions)
- [GHCR 가이드](https://docs.github.com/packages/working-with-a-github-packages-registry/working-with-the-container-registry)
- [Cloudflare API 문서](https://developers.cloudflare.com/api/)

---

## ✅ 최종 체크리스트

### 완료된 작업
- [x] Kubernetes 클러스터 정상화
- [x] worker1 격리 (taint 설정)
- [x] GitHub Actions 워크플로우 작성
- [x] Cloudflare 캐시 퍼지 기능 추가
- [x] kubeconfig Base64 인코딩
- [x] 문서 작성
- [x] Git commit 및 push

### 진행 중 (지금 해야 함!)
- [ ] **GitHub Secrets 4개 설정**
  - [ ] GHCR_TOKEN
  - [ ] KUBECONFIG_BASE64
  - [ ] CLOUDFLARE_ZONE_ID
  - [ ] CLOUDFLARE_API_TOKEN
- [ ] GitHub Actions 빌드 성공 확인
- [ ] 배포된 이미지 확인
- [ ] 사이트 접속 및 캐시 확인

### 향후 작업
- [ ] worker1 DNS 수정 (VM 콘솔 접속 시)
- [ ] Jenkins 제거 또는 비활성화
- [ ] WAS (Spring Boot) GitHub Actions 추가
- [ ] MetalLB + cert-manager 구현 (SSL 자동화)

---

## 🎯 다음 단계

### 즉시 (지금!)

1. **GitHub Secrets 설정**
   - URL: https://github.com/wlals2/blogsite/settings/secrets/actions
   - 4개 Secret 추가 (상세 가이드: [GITHUB-SECRETS-SETUP.md](GITHUB-SECRETS-SETUP.md))

2. **GitHub Actions 확인**
   - URL: https://github.com/wlals2/blogsite/actions
   - 빌드 성공 확인 (3-5분 소요)

3. **배포 검증**
   ```bash
   # 이미지 버전 확인
   kubectl get deployment web -n blog-system -o jsonpath='{.spec.template.spec.containers[0].image}'

   # 사이트 접속
   curl -I https://blog.jiminhome.shop/
   ```

### 나중에 (여유 있을 때)

1. **worker1 DNS 수정**
   - VM 콘솔 접속
   - DNS 재설정
   - taint 제거

2. **Jenkins 정리**
   - 비활성화 또는 제거

3. **WAS GitHub Actions 추가**
   - board-was 저장소에 동일 설정

---

> 작성: 2026-01-18 19:51
> 작성자: Claude Sonnet 4.5 + Jimin
> 상태: GitHub Secrets 설정 대기 중
