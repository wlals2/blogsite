---
title: "kubectl Connection Refused - Self-hosted Runner로 해결"
date: 2026-01-23
description: "GitHub Actions에서 Private K8s 클러스터 접근 불가 문제 해결"
tags: ["kubernetes", "github-actions", "self-hosted-runner", "troubleshooting"]
categories: ["study"]
---

## 상황

GitHub Actions 워크플로우에서 kubectl 명령어 실행 시 Connection Refused 발생.

```bash
kubectl get deployments -n blog-system
```

```
Error from server (InternalError): Internal error occurred:
failed calling webhook "validate.nginx.ingress.kubernetes.io"
The connection to the server localhost:8080 was refused - did you specify the right host or port?
```

---

## 원인

### 네트워크 구조 문제

| 위치 | IP | 접근 가능 |
|------|-----|----------|
| GitHub Cloud Runner | Public IP | 인터넷만 |
| K8s Control Plane | 192.168.X.187 | Private만 |

```
[ GitHub 클라우드 ]                  [ 홈 네트워크 ]
  ubuntu-latest runner   ❌ 접근 불가   192.168.X.187 (k8s-cp)
  (퍼블릭 IP)                          (RFC 1918 사설 IP)
```

### RFC 1918 사설 IP 범위

| 범위 | 대역 |
|------|------|
| Class A | 10.0.0.0/8 |
| Class B | 172.16.0.0/12 |
| Class C | 192.168.0.0/16 |

192.168.X.X는 사설 IP이므로 인터넷에서 라우팅 불가.

---

## 해결

### Self-hosted Runner 설치

```bash
# k8s-cp 노드에서 실행
cd ~
mkdir actions-runner && cd actions-runner

# 러너 다운로드
curl -o actions-runner-linux-x64-2.311.0.tar.gz -L \
  https://github.com/actions/runner/releases/download/v2.311.0/actions-runner-linux-x64-2.311.0.tar.gz

tar xzf ./actions-runner-linux-x64-2.311.0.tar.gz
```

### 러너 등록

```bash
# GitHub > Settings > Actions > Runners > New self-hosted runner
./config.sh --url https://github.com/wlals2/blogsite --token <TOKEN>
```

### 서비스 등록

```bash
sudo ./svc.sh install
sudo ./svc.sh start
sudo ./svc.sh status
```

### 워크플로우 수정

```yaml
# .github/workflows/deploy-web.yml
jobs:
  build-and-deploy:
    runs-on: self-hosted  # ubuntu-latest → self-hosted
```

---

## 결과

| 항목 | Before | After |
|------|--------|-------|
| Runner | ubuntu-latest (GitHub Cloud) | self-hosted (k8s-cp) |
| K8s 접근 | ❌ Connection Refused | ✅ 정상 |
| 네트워크 | Public → Private 불가 | 같은 네트워크 |

---

## 정리

### Self-hosted Runner 장점

| 장점 | 설명 |
|------|------|
| Private 리소스 접근 | 사설 네트워크 K8s 클러스터 |
| 빠른 빌드 | 로컬 Docker 캐시 활용 |
| 비용 절감 | GitHub Actions 무료 시간 소모 안 함 |

### Self-hosted Runner 단점

| 단점 | 설명 |
|------|------|
| 관리 필요 | 서버 유지보수 |
| 보안 | Public repo는 위험 (아무나 코드 실행 가능) |
| 가용성 | 서버 다운 시 빌드 불가 |

---

## 관련 명령어

```bash
# Runner 상태 확인
sudo ~/actions-runner/svc.sh status

# Runner 로그 확인
tail -f ~/actions-runner/_diag/Runner_*.log

# Runner 재시작
sudo ~/actions-runner/svc.sh restart
```
