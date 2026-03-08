---
title: "[DevSecOps 시리즈 #2] kube-bench CIS Benchmark — Kubernetes Audit Log 구현과 .bak 파일 트러블슈팅"
date: 2026-02-26T10:00:00+09:00
categories:
  - "study"
  - "Security"
tags: ["kubernetes", "audit-log", "kube-bench", "cis-benchmark", "kube-apiserver", "static-pod", "kubelet"]
summary: "kube-bench CIS Benchmark에서 FAIL이 난 Audit Log 4개 항목을 구현하면서 마주친 kubelet static pod 트러블슈팅을 정리한다. manifest 디렉토리의 .bak 파일이 8가지 시도를 모두 무력화한 원인이었다."
showtoc: true
tocopen: true
draft: false
series: ["DevSecOps 시리즈"]
---
## 배경: kube-bench 4개 FAIL

kube-bench로 CIS Kubernetes Benchmark를 실행하면 API 서버 감사 로그 관련 항목들이 FAIL로 나온다.

```bash
sudo kube-bench run --targets master

# 출력:
[FAIL] 1.2.16 Ensure that the --audit-log-path argument is set
[FAIL] 1.2.17 Ensure that the --audit-log-maxage argument is set to 30 or as appropriate
[FAIL] 1.2.18 Ensure that the --audit-log-maxbackup argument is set to 10 or as appropriate
[FAIL] 1.2.19 Ensure that the --audit-log-maxsize argument is set to 100 or as appropriate
```

기존 kube-apiserver에 audit 관련 인자가 전혀 없었다. "누가, 언제, 어떤 리소스를 조작했는가"가 전혀 기록되지 않는 상태였다. 보안 사고 발생 시 추적이 불가능하므로 감사 로그(Audit Log) 설정이 필요했다.

---

## Kubernetes Audit Log란

Kubernetes Audit Log는 **API 서버로 들어오는 모든 요청을 기록**하는 기능이다. `kubectl get secret`, `kubectl create pod` 같은 명령어가 실제로는 kube-apiserver에 대한 HTTP 요청이고, 이를 파일로 남긴다.

| 로그 레벨 | 기록 내용 | 용도 |
|-----------|----------|------|
| `None` | 기록 안 함 | 노이즈 제거 |
| `Metadata` | 요청자, 동사(verb), 리소스만 | 대부분의 API 호출 |
| `Request` | Metadata + 요청 본문 | 변경 내용 추적 |
| `RequestResponse` | Request + 응답 본문 | Secret 등 중요 리소스 |

감사 로그가 있으면 "2월 26일 오전 10시에 kubernetes-admin이 security 네임스페이스의 Secret을 조회했다"를 확인할 수 있다.

---

## 구현 계획

### 필요한 설정 3가지

**1. 로그 저장 디렉토리**
```bash
sudo mkdir -p /var/log/kubernetes
```

**2. Audit Policy 파일** (`/etc/kubernetes/audit-policy.yaml`)
: 어떤 API 요청을 어느 수준으로 기록할지 정의

**3. kube-apiserver manifest 수정** (`/etc/kubernetes/manifests/kube-apiserver.yaml`)
: 5개 인자 + 2개 volumeMount + 2개 volume 추가

kubeadm으로 설치한 클러스터에서 kube-apiserver는 **Static Pod**로 동작한다. kubelet이 `/etc/kubernetes/manifests/` 디렉토리를 감시하다가 파일이 변경되면 자동으로 Pod를 재시작한다. 따라서 파일만 수정하면 별도의 재시작 명령이 필요 없다.

---

## 구현 과정

### Step 1: 로그 디렉토리 생성 (k8s-cp에서 직접)

```bash
sudo mkdir -p /var/log/kubernetes
```

### Step 2: Audit Policy 작성

```bash
sudo tee /etc/kubernetes/audit-policy.yaml << 'EOF'
apiVersion: audit.k8s.io/v1
kind: Policy
rules:
  # Secret 조회/변경: 응답 본문까지 기록 (누가 읽었는지 중요)
  - level: RequestResponse
    resources:
    - group: ""
      resources: ["secrets"]

  # 시스템 컴포넌트 노이즈 제거
  - level: None
    users: ["system:kube-proxy"]
    verbs: ["watch"]
    resources:
    - group: ""
      resources: ["endpoints", "services", "services/status"]

  - level: None
    userGroups: ["system:nodes"]
    verbs: ["get", "list", "watch"]

  # health check 제외
  - level: None
    nonResourceURLs:
    - /healthz*
    - /readyz*
    - /livez*

  # 나머지는 메타데이터만 기록 (요청자 + 동사 + 리소스명)
  - level: Metadata
EOF
```

Policy의 규칙은 **위에서 아래로 첫 번째 매칭 규칙이 적용**된다. Secret은 RequestResponse(가장 상세), 시스템 컴포넌트 정기 요청은 None(제외), 나머지는 Metadata로 처리한다.

### Step 3: kube-apiserver manifest 수정

`/etc/kubernetes/manifests/kube-apiserver.yaml`에 audit 관련 설정을 추가한다.

**command 인자 추가:**
```yaml
- --audit-log-path=/var/log/kubernetes/audit.log
- --audit-log-maxage=30
- --audit-log-maxbackup=10
- --audit-log-maxsize=100
- --audit-policy-file=/etc/kubernetes/audit-policy.yaml
```

**volumeMounts 추가:**
```yaml
- mountPath: /var/log/kubernetes
  name: audit-log
- mountPath: /etc/kubernetes/audit-policy.yaml
  name: audit-policy
  readOnly: true
```

**volumes 추가:**
```yaml
- hostPath:
    path: /var/log/kubernetes
    type: DirectoryOrCreate
  name: audit-log
- hostPath:
    path: /etc/kubernetes/audit-policy.yaml
    type: File
  name: audit-policy
```

kube-apiserver는 컨테이너 안에서 실행되므로, 호스트 파일을 컨테이너 안에 마운트해야 접근할 수 있다.

---

## 트러블슈팅: 8가지 시도가 모두 실패한 이유

manifest 파일 수정 후 kube-apiserver 프로세스를 확인했더니 audit 인자가 반영되지 않았다.

```bash
sudo cat /proc/$(pgrep kube-apiserver)/cmdline | tr '\0' '\n' | grep audit
# 출력: (아무것도 없음)
```

파일 내용은 정확했다. 하지만 프로세스에는 반영되지 않았다. 이후 다음 8가지를 시도했지만 모두 실패했다.

| 시도 | 명령어 | 결과 |
|------|--------|------|
| 1 | `touch kube-apiserver.yaml` | 미반영 |
| 2 | `sudo systemctl restart kubelet` | 미반영 |
| 3 | `kubectl delete pod -n kube-system kube-apiserver-k8s-cp` | 미반영 |
| 4 | `sudo kill -9 $(pgrep kube-apiserver)` | 미반영 |
| 5 | manifest mv → 대기 → 복원 | 미반영 |
| 6 | `/var/lib/kubelet/pods/` 캐시 삭제 | 미반영 |
| 7 | `crictl rm <container-id>` | 미반영 |
| 8 | `crictl rmp <sandbox-id>` | 미반영 |

### 원인: .bak 파일의 존재

`/etc/kubernetes/manifests/` 디렉토리를 확인해 보니 문제의 원인이 보였다.

```bash
ls /etc/kubernetes/manifests/

# 출력:
kube-apiserver.yaml
kube-apiserver.yaml.bak          ← 이게 문제!
kube-controller-manager.yaml
kube-controller-manager.yaml.bak
kube-scheduler.yaml
kube-scheduler.yaml.bak
etcd.yaml
```

이전에 manifest 수정 전 백업으로 생성해 둔 `.bak` 파일들이었다. kubelet은 디렉토리 내의 **모든 파일을 스캔**하는데, `.yaml.bak` 파일도 YAML로 파싱을 시도했다.

**동작 원리:**

1. kubelet이 `kube-apiserver.yaml` 파싱 → Pod spec 생성 (audit 인자 있음)
2. kubelet이 `kube-apiserver.yaml.bak` 파싱 → 같은 이름의 Pod spec 생성 (audit 인자 없음)
3. 두 파일이 동일한 Pod 이름(`kube-apiserver`)을 정의 → 충돌
4. kubelet은 기존에 실행 중인 Pod를 유지하며 새 spec 적용 거부

이미 실행 중인 kube-apiserver의 Mirror Pod(etcd 복사본)가 있는 상태에서 새 kubelet이 "이미 존재한다"는 에러를 내면서 관리권을 획득하지 못했다. 아무리 재시작을 해도 동일한 상황이 반복됐다.

### 해결: .bak 파일 제거 후 clean restart

```bash
# 1. .bak 파일 모두 제거
sudo mv /etc/kubernetes/manifests/*.bak ~/

# 2. manifest를 잠시 제거 (kubelet이 Pod 종료하도록)
sudo mv /etc/kubernetes/manifests/kube-apiserver.yaml /tmp/kube-apiserver-audit.yaml

# 3. 15초 대기 (Pod 완전 종료 확인)
sleep 15

# 4. 새 manifest 복원
sudo mv /tmp/kube-apiserver-audit.yaml /etc/kubernetes/manifests/kube-apiserver.yaml
```

30초 후 확인:

```bash
sudo cat /proc/$(pgrep kube-apiserver)/cmdline | tr '\0' '\n' | grep audit

# 출력:
--audit-log-path=/var/log/kubernetes/audit.log
--audit-log-maxage=30
--audit-log-maxbackup=10
--audit-log-maxsize=100
--audit-policy-file=/etc/kubernetes/audit-policy.yaml
```

---

## 검증

### 로그 파일 생성 확인

```bash
sudo ls -lh /var/log/kubernetes/

# 출력:
-rw------- 1 root root 60M Feb 26 01:11 audit.log
```

### 실제 로그 확인

Secret 조회 후 로그를 확인하면 RequestResponse 레벨로 기록된다.

```bash
kubectl get secret -n security
sudo tail -1 /var/log/kubernetes/audit.log | python3 -m json.tool | grep -E '"username"|"requestURI"|"verb"'

# 출력:
"username": "kubernetes-admin",
"requestURI": "/api/v1/namespaces/security/secrets",
"verb": "list",
```

### kube-bench 재실행

```bash
sudo kube-bench run --targets master | grep -E "1.2.1[6-9]"

# 출력:
[PASS] 1.2.16 Ensure that the --audit-log-path argument is set
[PASS] 1.2.17 Ensure that the --audit-log-maxage argument is set to 30 or as appropriate
[PASS] 1.2.18 Ensure that the --audit-log-maxbackup argument is set to 10 or as appropriate
[PASS] 1.2.19 Ensure that the --audit-log-maxsize argument is set to 100 or as appropriate
```

---

## kubeadm ConfigMap 영구 저장

kubeadm으로 클러스터를 업그레이드하면 kube-apiserver.yaml이 덮어써질 수 있다. 영구적으로 설정을 유지하려면 `kube-system/kubeadm-config` ConfigMap에 반영해야 한다.

```bash
kubectl edit configmap kubeadm-config -n kube-system
```

`ClusterConfiguration`의 `apiServer.extraArgs`에 audit 설정을 추가하고, `extraVolumes`도 함께 추가한다. 이후 `kubeadm upgrade apply` 시 해당 설정이 새 manifest에 자동으로 포함된다.

---

## 성과

| 항목 | Before | After |
|------|--------|-------|
| kube-bench 1.2.16 | FAIL | **PASS** |
| kube-bench 1.2.17 | FAIL | **PASS** |
| kube-bench 1.2.18 | FAIL | **PASS** |
| kube-bench 1.2.19 | FAIL | **PASS** |
| audit.log 파일 | 없음 | **60MB (운영 중)** |
| Secret 조회 기록 | 불가 | **RequestResponse 레벨로 기록** |

---

## 핵심 교훈

**`.bak` 파일은 manifests 디렉토리 밖에 보관해야 한다.**

kubelet은 `/etc/kubernetes/manifests/` 디렉토리의 모든 파일을 스캔한다. 확장자와 관계없이 YAML 파싱을 시도하며, 같은 이름의 Pod를 정의하는 파일이 2개이면 충돌이 발생한다. manifest 수정 전 백업은 반드시 다른 경로(홈 디렉토리 등)에 저장해야 한다.

**Static Pod 트러블슈팅 순서:**
1. 파일 내용 검증 먼저 (`cat` 또는 `python3 -c "import yaml; yaml.safe_load(open(...))"`)
2. 디렉토리 내 다른 파일 확인 (`ls /etc/kubernetes/manifests/`)
3. kubelet 로그 확인 (`journalctl -u kubelet -f`)
4. manifest clean start (mv → sleep → mv 복원)

---

## 다음 단계

kube-bench FAIL 항목은 아직 더 있다. 다음으로 etcd 암호화, RBAC 강화, Pod Security Standards 적용을 진행할 예정이다.
