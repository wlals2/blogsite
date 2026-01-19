# blog-k8s-project Legacy Files Archive

> 보관 날짜: 2026-01-19
> 이유: GitHub Actions CI/CD로 전환, kubectl 직접 관리로 변경

## 보관된 파일

### web/ 디렉터리
- `Dockerfile` - 이전 web 빌드용 Dockerfile (현재: 루트 Dockerfile 사용)
- `push-to-ghcr.sh` - 수동 GHCR 푸시 스크립트 (현재: GitHub Actions 사용)
- `worker-setup.sh` - Worker 설정 스크립트
- `.env.github` - 환경 변수
- `k8s/` - 초기 Kubernetes 매니페스트

### ingress/ 디렉터리
- `blog-ingress.yaml` - 초기 Ingress 설정
- `web-ingress.yaml` - Web Ingress 설정
- `k8s/` - 기타 매니페스트

### mysql/ 디렉터리
- `k8s/` - MySQL Kubernetes 매니페스트

## 현재 사용 중인 구조

```
blogsite/
├── Dockerfile (WEB 빌드용) ✅
├── blog-k8s-project/
│   └── was/ (WAS 소스 및 Dockerfile) ✅
├── .github/workflows/
│   ├── deploy-web.yml ✅
│   └── deploy-was.yml ✅
```

## 배포 방식 변경

**Before (blog-k8s-project 사용):**
- k8s 매니페스트 파일로 수동 배포
- `kubectl apply -f blog-k8s-project/web/k8s/`

**After (GitHub Actions):**
- CI/CD 자동화
- `kubectl set image deployment/web ...`
- 매니페스트는 클러스터에서 직접 관리

## 복원 방법

필요 시 이 디렉터리에서 파일을 다시 복사할 수 있습니다.

```bash
# 매니페스트 재사용 시
kubectl apply -f archive/blog-k8s-legacy-20260119/ingress/blog-ingress.yaml
```

