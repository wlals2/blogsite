---
title: "Prometheus에서 KubernetesNOde 메트릭 수집시 에러 해결2(추가설명)"
date: 2025-11-05T20:37:28+09:00
draft: false
categories: ["k8s","RBAC","prometheus"]
tags: ["prometheus","troubleshooting","metric error","monitoring","CA","RBAC","k8s"]
description: "Prometheus에서 KubernetesNOde 메트릭 수집시 에러 해결2(추가설명)"
author: "늦찌민"
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

