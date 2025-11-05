---
title: "Prometheus에서 KubernetesNOde 메트릭 수집시 에러 해결2(추가설명)"
date: 2025-11-05T20:37:28+09:00
draft: false
categories: ["k8s","RBAC","prometheus"]
tags: ["prometheus","troubleshooting","metric error","monitoring","CA","RBAC","k8s"]
description: "Prometheus에서 KubernetesNOde 메트릭 수집시 에러 해결2(추가설명)"
author: "늦찌민"
series:  "k8s 개념 뿌시기"
---

## 메트릭 해결 및 추가 설명
### 현재 상황:
#### 1.  CA 파일은 읽을 수 있었습니다 - 이미 Prometheus Pod 안에 마운트되어 있음
```yaml
   # Prometheus가 이미 접근 가능한 파일들
   ca_file: /var/run/secrets/kubernetes.io/serviceaccount/ca.crt  ✓
   bearer_token_file: /var/run/secrets/kubernetes.io/serviceaccount/token  ✓
```

#### 2. 문제는 "kubelet API 엔드포인트"에 접근할 권한이 없었던 것
- 파일 시스템 권한 문제 ✗
- Kubernetes API 접근 권한 문제 ✓


### 전체 흐름:
```bash
1. Prometheus Pod 시작
   → ServiceAccount "prometheus"의 token과 CA 인증서가 자동으로 마운트됨
   
2. Prometheus가 kubelet의 /metrics 엔드포인트에 HTTPS 요청
   https://192.168.1.61:10250/metrics
   
3. kubelet이 요청을 받고 확인:
   "이 요청에 포함된 ServiceAccount token이 누구 것인가?"
   → prometheus ServiceAccount (monitoring 네임스페이스)
   
4. Kubernetes RBAC 시스템에 확인:
   "prometheus ServiceAccount가 nodes/metrics를 읽을 권한이 있나?"
   → 권한 없음 ✗
   
5. kubelet이 403 Forbidden 응답
```

### 파일 읽기 vs API 접근 권한
```
로컬 파일 읽기:          Kubernetes API 접근:
/var/run/secrets/...    https://NODE_IP:10250/metrics
↓                       ↓
리눅스 파일 권한         Kubernetes RBAC
(chmod, chown)          (ClusterRole, ClusterRoleBinding)
```
---

## 왜 RBAC만 가능 한가 ?

### Kubernetes 보안모델 
kubernetes는 **기본적으로 몯느 것을 차단**하고 명시적으로 허용된 것만 접근 가능하게 설게되어 있다.
```bash
# 이런 식으로 생각하면 됩니다:

# 일반 리눅스:
기본값 = 열려있음 → 명시적으로 차단
(모든 프로세스가 파일에 접근 가능 → chmod로 제한)

# Kubernetes:
기본값 = 차단됨 → 명시적으로 허용
(모든 요청이 거부됨 → RBAC로 허용)

```
### kubelet API는 파일이 아닌 네트워크 엔드포인트
```bash
# 이건 파일이 아닙니다:
https://192.168.1.61:10250/metrics
       ↑
       네트워크 주소 = 반드시 인증/권한 체크 필요

# 이건 파일입니다:
/var/run/secrets/kubernetes.io/serviceaccount/token
↑
로컬 파일 시스템 = 리눅스 파일 권한으로 제어
```
---

## 다른 방법은 있나 ?
### 1. Kubernetes API Server를 통한 프록시 방식
```yaml
# 방법 1: 우리가 사용한 방식 (직접 접근)
https://NODE_IP:10250/metrics
→ RBAC 권한 필요: nodes/metrics, nodes/proxy

# 방법 2: API Server 프록시 (대안)
https://kubernetes.default.svc/api/v1/nodes/NODE_NAME/proxy/metrics
→ RBAC 권한 필요: nodes/proxy 또는 더 많은 권한
```
> 결론: 어떤 방식이든 RBAC 권한은 필수입니다.

### 2. HostNetwork 모드로 실행 (비추천)
```yaml
# Prometheus를 호스트 네트워크로 실행
spec:
  hostNetwork: true
```
- 이렇게 하면 노드 자체에서 실행되는 것처럼 작동
- 하지만 여전히 RBAC 권한 필요
- 보안상 매우 위험함

### 3. Node Exporter 사용 (우리도 이미 사용 중)
```bash
# kubelet metrics 대신 Node Exporter 사용
kubelet metrics (시스템 전체) → RBAC 필요
Node Exporter (시스템 리소스) → RBAC 불필요 (DaemonSet으로 접근)
```
우리는 이미 Node Exporter를 사용하고 있어서, 실제로는 kubelet metrics 없이도 대부분의 정보를 수집할 수 있습니다.

## 근본적으로 Kubernetes는 왜 이렇게 만들었을까 ?
### 보안원칙: `"최소 권한의 원칙"`
```bash
# 만약 기본적으로 모든 것이 읽을 수 있다면?

악의적인 Pod → 다른 노드의 정보 수집
              → 민감한 메트릭 노출
              → 클러스터 전체 정보 유출

# RBAC로 명시적 허용:

Prometheus만 → nodes/metrics 읽기 가능
다른 Pod들 → 접근 불가
```
### 멀티 테넌시 환경
```bash
# 같은 클러스터에 여러 팀이 있다면?

Team A의 Prometheus → Team A의 리소스만 볼 수 있음
Team B의 Prometheus → Team B의 리소스만 볼 수 있음

→ RBAC 없이는 불가능
## 정리

Q: RBAC 밖에 방법이 없나?
A: kubelet API 같은 Kubernetes 내부 리소스에 접근하려면 반드시 RBAC 필요

Q: 로컬 파일을 기본적으로 읽을 수 없나?
A: 
- 로컬 파일 (Pod 내부)**: 리눅스 파일 권한으로 제어 가능
- Kubernetes API (네트워크)**: 반드시 RBAC으로 제어

Q: 이게 최선이라서 그런거야 아니면 무조건 이래야 하는거야?**
A: 무조건 이래야 합니다. Kubernetes의 핵심 보안 모델이기 때문에 우회할 수 없습니다.

### 비유로 설명하면:

로컬 파일 읽기 = 내 집 안의 서랍 열기
→ 집 주인(파일 소유자)이면 열 수 있음

Kubernetes API = 은행 금고 열기
→ 집 주인이어도 은행 직원(RBAC)의 허가 필요
```
