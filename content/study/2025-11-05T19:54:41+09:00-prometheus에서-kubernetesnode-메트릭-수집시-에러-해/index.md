---
title: "Prometheus에서 KubernetesNOde 메트릭 수집시 에러 해결"
date: 2025-11-05T19:54:41+09:00
draft: false
categories: ["study","k8s","rbac"]
tags: ["k8s","prometheus","monitoring","rbac","troubleshooting","tls"]
description: "Prometheus에서 KubernetesNOde 메트릭 수집시 에러 해결"
author: "늦찌민"
series: ["K8s 개념 뿌시기"]
---

## 문제 상황

Prometheus에서 `kubernetes-nodes` job으로 kubelet의 메트릭을 수집하려고 할 때 다음과 같은 에러가 발생했습니다.

### 첫 번째 에러: x509 인증서 문제

```
Error scraping target: Get "https://CONTROL_PLANE_IP:10250/metrics": 
tls: failed to verify certificate: x509: cannot validate certificate 
for CONTROL_PLANE_IP because it doesn't contain any IP SANs
```
1
**원인:** kubelet이 사용하는 자체 서명 인증서에 IP 주소가 SAN(Subject Alternative Name)에 포함되어 있지 않아서 발생

**해결:** Prometheus 설정에서 TLS 검증 우회 옵션 추가

```yaml
# prometheus-config.yaml
- job_name: 'kubernetes-nodes'
  kubernetes_sd_configs:
    - role: node
  scheme: https
  tls_config:
    ca_file: /var/run/secrets/kubernetes.io/serviceaccount/ca.crt
    insecure_skip_verify: true  # 이 줄 추가
  bearer_token_file: /var/run/secrets/kubernetes.io/serviceaccount/token
  relabel_configs:
    - action: labelmap
      regex: __meta_kubernetes_node_label_(.+)
```

### 두 번째 에러: 403 Forbidden

TLS 문제를 해결한 후 새로운 에러가 발생:

```
Error scraping target: server returned HTTP status 403 Forbidden
```

**원인:** Prometheus ServiceAccount가 kubelet의 `/metrics` 엔드포인트에 접근할 권한이 없음

## 해결 방법: RBAC 권한 추가

Kubernetes의 RBAC(Role-Based Access Control)을 통해 Prometheus에 필요한 권한을 부여해야 합니다.

### prometheus-rbac-nodes.yaml 생성

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: prometheus-nodes
rules:
- apiGroups: [""]
  resources:
  - nodes/metrics
  - nodes/proxy
  verbs: ["get", "list"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: prometheus-nodes
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: prometheus-nodes
subjects:
- kind: ServiceAccount
  name: prometheus
  namespace: monitoring
```

### 적용

```bash
kubectl apply -f prometheus-rbac-nodes.yaml
```

약 15초 후 Prometheus UI에서 kubernetes-nodes target이 UP 상태로 변경되는 것을 확인할 수 있습니다.

## RBAC 심층 분석

### ClusterRole이란?

ClusterRole은 **클러스터 전체에서 사용할 수 있는 권한 세트**를 정의합니다. 일반 Role과 달리 네임스페이스에 국한되지 않고 클러스터 전역 리소스에 대한 권한을 부여할 수 있습니다.

#### 우리의 ClusterRole 구조

```yaml
kind: ClusterRole
metadata:
  name: prometheus-nodes  # 이 권한 세트의 이름
rules:
- apiGroups: [""]  # 코어 API 그룹 (빈 문자열은 core API를 의미)
  resources:
  - nodes/metrics  # Node의 metrics 하위 리소스
  - nodes/proxy    # Node의 proxy 하위 리소스
  verbs: ["get", "list"]  # 읽기 작업만 허용
```

#### 필드 설명

**1. apiGroups**
- Kubernetes API는 여러 그룹으로 나뉘어 있습니다
- `""` (빈 문자열): 코어 API 그룹 (nodes, pods, services 등)
- `apps`: Deployment, StatefulSet 등
- `batch`: Job, CronJob 등
- 우리는 Node 리소스를 사용하므로 코어 API 그룹 사용

**2. resources**
- 접근하려는 Kubernetes 리소스를 지정
- `nodes/metrics`: kubelet이 노출하는 메트릭 엔드포인트
- `nodes/proxy`: 노드로의 프록시 접근 (kubelet API 접근에 필요)
- `/`로 구분되는 형식은 "하위 리소스"를 나타냄

**3. verbs**
- 허용할 작업을 정의
- `get`: 특정 리소스 조회
- `list`: 리소스 목록 조회
- 읽기 전용이므로 `create`, `update`, `delete` 같은 쓰기 권한은 부여하지 않음

### ClusterRoleBinding이란?

ClusterRoleBinding은 **ClusterRole을 특정 주체(Subject)에게 연결**하는 역할을 합니다. 즉, "누가" 해당 권한을 사용할 수 있는지 지정합니다.

#### 우리의 ClusterRoleBinding 구조

```yaml
kind: ClusterRoleBinding
metadata:
  name: prometheus-nodes  # 이 바인딩의 이름
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: prometheus-nodes  # 위에서 만든 ClusterRole과 연결
subjects:
- kind: ServiceAccount
  name: prometheus  # monitoring 네임스페이스의 prometheus ServiceAccount
  namespace: monitoring
```

#### 필드 설명

**1. roleRef (역할 참조)**
- 어떤 ClusterRole을 바인딩할 것인지 지정
- `apiGroup`: RBAC는 `rbac.authorization.k8s.io` 그룹에 속함
- `kind`: ClusterRole 또는 Role
- `name`: 위에서 생성한 ClusterRole의 이름

**2. subjects (주체)**
- 권한을 부여받을 대상을 지정
- `kind`: ServiceAccount, User, Group 중 선택
- `name`: ServiceAccount의 이름
- `namespace`: ServiceAccount가 속한 네임스페이스

### 왜 ClusterRole과 ClusterRoleBinding이 분리되어 있나?

이 분리 구조는 다음과 같은 이점을 제공합니다:

1. **재사용성**: 하나의 ClusterRole을 여러 주체에게 바인딩 가능
2. **유연성**: 동일한 권한 세트를 다른 ServiceAccount에 쉽게 부여 가능
3. **보안성**: 권한 정의(ClusterRole)와 권한 부여(ClusterRoleBinding)를 분리하여 관리

### 우리 케이스의 전체 흐름

```
1. Prometheus Pod 실행
   ↓
2. Pod는 prometheus ServiceAccount 사용 (monitoring 네임스페이스)
   ↓
3. ClusterRoleBinding이 prometheus ServiceAccount를 확인
   ↓
4. ClusterRole prometheus-nodes의 권한 부여
   ↓
5. Prometheus가 nodes/metrics와 nodes/proxy에 접근 가능
   ↓
6. kubelet의 /metrics 엔드포인트에서 메트릭 수집 성공
```

## 검증

### 권한 확인

```bash
# ClusterRole 확인
kubectl get clusterrole prometheus-nodes

# ClusterRoleBinding 확인
kubectl get clusterrolebinding prometheus-nodes

# 상세 정보 확인
kubectl describe clusterrole prometheus-nodes
kubectl describe clusterrolebinding prometheus-nodes
```

### Prometheus에서 확인

Prometheus UI에서 Status → Targets로 이동하여 `kubernetes-nodes` job의 모든 타겟이 **UP** 상태인지 확인합니다.

## 참고: Role vs ClusterRole

우리는 왜 Role이 아닌 ClusterRole을 사용했을까요?

| 특성 | Role | ClusterRole |
|------|------|-------------|
| 범위 | 특정 네임스페이스 | 클러스터 전체 |
| 사용 대상 | 네임스페이스 리소스 | 클러스터 리소스 또는 모든 네임스페이스 |
| 예시 | Pod, Service, ConfigMap | Node, PersistentVolume, ClusterRole |

**Node는 클러스터 수준 리소스**이므로 네임스페이스에 속하지 않습니다. 따라서 반드시 ClusterRole을 사용해야 합니다.

## 보안 고려사항

### insecure_skip_verify: true 사용에 대하여

이 옵션은 TLS 인증서 검증을 우회합니다. 프로덕션 환경에서는 보안 위험이 있을 수 있지만, 다음과 같은 이유로 일반적으로 허용됩니다:

1. **클러스터 내부 통신**: Prometheus와 kubelet은 모두 클러스터 내부에 있음
2. **Bearer Token 인증**: TLS 검증을 우회해도 ServiceAccount token으로 인증함
3. **kubelet 자체 서명 인증서**: kubelet은 기본적으로 자체 서명 인증서를 사용하므로 공인 CA로 검증 불가

더 강력한 보안이 필요하다면 kubelet 인증서에 IP SAN을 추가하거나, Kubernetes API를 통한 프록시 방식을 사용할 수 있습니다.

## 정리

이번 트러블슈팅을 통해 다음을 배웠습니다:

1. **TLS 인증서 문제**: kubelet의 자체 서명 인증서는 IP SAN을 포함하지 않아 `insecure_skip_verify` 옵션이 필요
2. **RBAC 권한 부여**: Prometheus가 Node 메트릭에 접근하려면 적절한 ClusterRole과 ClusterRoleBinding 필요
3. **ClusterRole의 구조**: apiGroups, resources, verbs로 세밀한 권한 제어
4. **ClusterRoleBinding의 역할**: 권한과 주체를 연결하는 다리 역할

Kubernetes의 RBAC은 처음에는 복잡해 보이지만, 권한 정의와 권한 부여를 분리한 명확한 구조 덕분에 유연하고 안전한 권한 관리가 가능합니다.








