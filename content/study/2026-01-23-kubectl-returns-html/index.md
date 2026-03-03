---
title: "kubectl이 HTML을 반환하는 문제"
date: 2026-01-23T10:00:00+09:00
description: "kubeconfig 설정 오류로 Jenkins 로그인 페이지가 나오는 문제 해결"
tags: ["kubernetes", "kubectl", "kubeconfig", "troubleshooting"]
categories: ["study", "Kubernetes", "Troubleshooting"]
---

## 상황

kubectl 명령어 실행 시 JSON/YAML 대신 HTML이 반환됨.

```bash
kubectl get deployments -n blog-system
```

```html
<!doctype html>
<html>
<head>
<title>Authentication required</title>
...Jenkins login page...
</html>
```

---

## 원인

### kubeconfig 확인

```bash
kubectl config view
```

```yaml
apiVersion: v1
clusters:
- cluster:
    server: http://localhost:8080  # 잘못된 설정!
  name: default
```

### 문제 분석

| 항목 | 값 | 문제 |
|------|-----|------|
| server | http://localhost:8080 | Jenkins가 8080 사용 중 |
| 예상 | https://192.168.X.187:6443 | K8s API Server |

localhost:8080에 Jenkins가 떠 있어서 로그인 페이지가 반환됨.

---

## 해결

### kubeconfig 재설정

```bash
# Control Plane에서 kubeconfig 복사
mkdir -p $HOME/.kube
sudo cp -i /etc/kubernetes/admin.conf $HOME/.kube/config
sudo chown $(id -u):$(id -g) $HOME/.kube/config
```

### 설정 확인

```bash
kubectl config view
```

```yaml
apiVersion: v1
clusters:
- cluster:
    certificate-authority-data: DATA+OMITTED
    server: https://192.168.X.187:6443  # 정상!
  name: kubernetes
```

---

## 결과

```bash
kubectl get deployments -n blog-system

NAME   READY   UP-TO-DATE   AVAILABLE   AGE
was    2/2     2            2           24h
web    2/2     2            2           24h
```

---

## 정리

### kubeconfig 문제 체크리스트

| 확인 항목 | 명령어 |
|----------|--------|
| 현재 context | `kubectl config current-context` |
| 클러스터 서버 | `kubectl config view -o jsonpath='{.clusters[0].cluster.server}'` |
| kubeconfig 경로 | `echo $KUBECONFIG` |

### 포트 충돌 확인

```bash
# 8080 포트 사용 중인 프로세스
sudo lsof -i :8080

# 6443 포트 (K8s API)
sudo lsof -i :6443
```

---

## 관련 명령어

```bash
# kubeconfig 경로 명시적 지정
export KUBECONFIG=/etc/kubernetes/admin.conf

# 특정 context 사용
kubectl config use-context kubernetes-admin@kubernetes

# API Server 연결 테스트
kubectl cluster-info
```
