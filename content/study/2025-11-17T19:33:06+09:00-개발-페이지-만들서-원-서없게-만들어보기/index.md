---
title: "Hugo 블로그 kubernetes 마이그레이션 가이드"
date: 2025-11-17T19:33:06+09:00
draft: false
categories: ["hugo","nginx","kubernetes"]
tags: ["hugo","kuveretnes","nginx","test","dev"]
description: "개발 페이지 만들서 원 서버 문제없게 만들어보기"
author: "늦찌민"
seires: ["Hugo 블로그 kubernetes 마이그레이션 가이드"]
---

# Hugo 블로그 Kubernetes 마이그레이션 가이드

## 목적
현재 Nginx(80/443 포트)에서 실행 중인 Hugo 블로그를 안전하게 Kubernetes로 이전

## 전체 워크플로우

```
[1단계] 테스트 환경 구축 (31313 포트)
   ↓
[2단계] 검증 (원 서버와 비교)
   ↓
[3단계] 프로덕션 배포 (30080 포트)
   ↓
[4단계] 기존 Nginx 종료
   ↓
[5단계] 포트 전환 (80/443)
```

## 사용 방법

### 1단계: Docker 이미지 빌드

```bash
cd ~/test/hugo-k8s-test
./build.sh
```

**결과:**
- Hugo 빌드 (`~/blogsite/public/`)
- Docker 이미지 생성 (`hugo-blog:test`)

### 2단계: 테스트 환경 배포

```bash
# Kubernetes 테스트 환경 배포
kubectl apply -f hugo-test-deployment.yaml

# 배포 확인
kubectl get pods -n hugo-test
kubectl get svc -n hugo-test

# 접속 테스트
curl http://localhost:31313
# 또는 브라우저: http://192.168.1.187:31313
```

**확인 사항:**
- [ ] 페이지가 정상적으로 로드되는가?
- [ ] 모든 링크가 작동하는가?
- [ ] 이미지가 제대로 보이는가?
- [ ] CSS/JS가 정상 적용되는가?

### 3단계: 원 서버(80 포트)와 비교

```bash
# 기존 서버
curl http://localhost/ | head -100 > /tmp/old.html

# 새 서버 (K8s)
curl http://localhost:31313/ | head -100 > /tmp/new.html

# 비교
diff /tmp/old.html /tmp/new.html
```

### 4단계: 프로덕션 배포

**테스트가 성공하면:**

```bash
# 이미지 태그 변경
docker tag hugo-blog:test hugo-blog:latest

# 프로덕션 배포
kubectl apply -f hugo-prod-deployment.yaml

# 확인
kubectl get pods -n hugo-prod
kubectl get svc -n hugo-prod

# 접속 테스트
curl http://localhost:30080
```

### 5단계: 기존 Nginx와 전환

```bash
# 기존 Nginx 중지
sudo systemctl stop nginx

# Kubernetes 서비스를 80 포트로 포워딩 (임시)
kubectl port-forward -n hugo-prod svc/hugo-blog-prod-svc 80:80

# 또는 Ingress 설정 (권장)
kubectl apply -f ingress.yaml
```

## 롤백 방법

문제가 발생하면:

```bash
# 테스트 환경 삭제
kubectl delete -f hugo-test-deployment.yaml

# 프로덕션 삭제
kubectl delete -f hugo-prod-deployment.yaml

# 기존 Nginx 재시작
sudo systemctl start nginx
```

## 디렉토리 구조

```
~/test/hugo-k8s-test/
├── Dockerfile                    # Hugo 블로그 컨테이너화
├── nginx.conf                    # Nginx 설정
├── build.sh                      # 빌드 스크립트
├── hugo-test-deployment.yaml     # 테스트 환경 (31313 포트)
├── hugo-prod-deployment.yaml     # 프로덕션 환경 (30080 포트)
└── README.md                     # 이 파일
```

## 아키텍처 비교

### 현재 (Before)
```
사용자
  ↓
Nginx (80/443)
  ↓
/usr/share/nginx/html/
  ↓
Hugo 정적 파일
```

### 이후 (After)
```
사용자
  ↓
Ingress (80/443)
  ↓
Kubernetes Service
  ↓
Hugo Blog Pods (여러 개)
  ↓
Nginx 컨테이너
  ↓
Hugo 정적 파일
```

## 장점

1. **무중단 배포**: Pod를 하나씩 교체 가능
2. **스케일링**: 트래픽에 따라 Pod 개수 조절
3. **자동 복구**: Pod 장애 시 자동 재시작
4. **일관성**: 다른 앱(Nextcloud 등)과 동일한 방식 관리
5. **안전한 테스트**: 31313 포트로 먼저 검증 후 전환

## 다음 단계

- [ ] Ingress 설정 (80/443 포트)
- [ ] SSL 인증서 적용
- [ ] CI/CD 파이프라인 구축 (Jenkins/Tekton)
- [ ] 자동 빌드 & 배포

