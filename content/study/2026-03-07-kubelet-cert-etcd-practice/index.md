---
title: "22일간 멈춘 Backup CronJob 복구기 — kubelet 인증서, etcd 814MB, Alpine MariaDB"
date: 2026-03-07T15:00:00+09:00
categories:
  - Troubleshooting
tags: ["kubernetes", "etcd", "kubelet", "tls", "backup", "cronjob", "troubleshooting", "kyverno"]
summary: "Backup CronJob 3개가 22일간 실패한 원인을 하나씩 파헤친 기록. kubelet 인증서 IP SAN 누락, etcd 814MB, Alpine MariaDB 호환성, Kyverno 차단까지 5개의 문제를 해결한다."
showtoc: true
tocopen: true
draft: false
series: ["홈랩 Kubernetes 운영 시리즈"]
---

> 이 글은 실습편입니다. 배경지식이 필요하면 [이론편](/study/2026-03-07-kubelet-cert-etcd-theory/)을 먼저 읽어주세요.

---

## 증상 발견

etcd-backup, mysql-backup-to-s3, mysql-backup(blog-system) 3개 CronJob이 최근 3회 연속 실패하고 있었다.

```bash
kubectl get jobs -A --sort-by=.metadata.creationTimestamp | grep -E 'backup|etcd' | tail -10
# 출력:
# etcd-backup-to-s3-29514360    Complete   1/1   22d    ← 마지막 성공
# mysql-backup-29542680         Failed     0/1   2d
# etcd-backup-to-s3-29543160    Failed     0/1   2d
# mysql-backup-to-s3-29543190   Failed     0/1   2d
# ... (이후 전부 Failed)
```

마지막 성공이 22일 전이었다. **22일간 백업이 없었다는 뜻이다.**

---

## 문제 1: kubectl logs가 안 된다 (진단의 장벽)

CronJob 실패 진단 순서의 4단계(Pod 로그 확인)에서 바로 막혔다.

```bash
kubectl logs etcd-backup-debug-g6zvf -n backup-system
# 출력:
# Error from server: Get "https://192.168.1.187:10250/containerLogs/...":
#   tls: failed to verify certificate: x509: cannot validate certificate
#   for 192.168.1.187 because it doesn't contain any IP SANs
```

이건 CronJob 문제가 아니라 **kube-apiserver → kubelet 통신 문제**였다. kubelet 인증서를 확인했다:

```bash
sudo openssl x509 -in /var/lib/kubelet/pki/kubelet.crt -noout -text | \
  grep -A1 "Subject Alternative Name"
# 출력:
# X509v3 Subject Alternative Name:
#     DNS:k8s-cp                  ← IP SAN이 없다!
```

`DNS:k8s-cp`만 있고 `IP:192.168.1.187`이 없었다. kube-apiserver는 IP로 접근하므로 인증서 검증 실패.

### 해결: serverTLSBootstrap 활성화

```bash
# 1. kubelet 설정에 serverTLSBootstrap 추가
sudo vi /var/lib/kubelet/config.yaml
# rotateCertificates: true 아래에 추가:
# serverTLSBootstrap: true

# 2. 기존 자체 서명 인증서 삭제 + kubelet 재시작
sudo rm -f /var/lib/kubelet/pki/kubelet.crt /var/lib/kubelet/pki/kubelet.key
sudo systemctl restart kubelet

# 3. CSR 확인 — SAN에 IP가 포함되었는지 검증
kubectl get csr
# NAME        REQUESTOR              CONDITION
# csr-cltw9   system:node:k8s-cp     Pending

kubectl get csr csr-cltw9 -o jsonpath='{.spec.request}' | base64 -d | \
  openssl req -noout -text | grep "Subject Alternative Name" -A1
# 출력:
# DNS:k8s-cp, IP Address:192.168.1.187    ← IP SAN 포함 확인!

# 4. CSR 승인
kubectl certificate approve csr-cltw9

# 5. 발급된 인증서 확인
sudo openssl x509 -in /var/lib/kubelet/pki/kubelet-server-current.pem \
  -noout -text | grep -A1 "Subject Alternative Name"
# 출력:
# DNS:k8s-cp, IP Address:192.168.1.187    ← 정상!
```

이 작업을 **5개 노드(cp + worker1~4) 모두**에 수행했다.

| 노드 | IP | CSR 승인 | 결과 |
|------|-----|---------|------|
| k8s-cp | 192.168.1.187 | csr-cltw9 | DNS:k8s-cp, IP:192.168.1.187 |
| k8s-worker1 | 192.168.1.61 | csr-tqf7s | DNS:k8s-worker1, IP:192.168.1.61 |
| k8s-worker2 | 192.168.1.62 | csr-k9m2p | DNS:k8s-worker2, IP:192.168.1.62 |
| k8s-worker3 | 192.168.1.60 | csr-w4x8n | DNS:k8s-worker3, IP:192.168.1.60 |
| k8s-worker4 | 192.168.1.64 | csr-p6r3v | DNS:k8s-worker4, IP:192.168.1.64 |

**결과**: `kubectl logs`, `kubectl exec`가 정상 작동. 이제 CronJob 실패 원인을 볼 수 있게 되었다.

---

## 문제 2: etcd 스냅샷 814MB → S3 업로드 타임아웃

인증서를 고친 후 etcd-backup을 수동 실행하고 로그를 확인했다:

```bash
kubectl create job --from=cronjob/etcd-backup-to-s3 etcd-backup-test -n backup-system
kubectl logs -l job-name=etcd-backup-test -n backup-system
# 출력:
# Snapshot saved at /tmp/etcd-snapshot-20260306-083410.db
# | HASH     | REVISION  | TOTAL KEYS | TOTAL SIZE |
# | 6da37873 | 23545183  | 6883       | 814 MB     |   ← 비정상!
# Uploading to S3: s3://jimin-backup/etcd/...
# (10분 이상 멈춤 → Pod 재시작 → BackoffLimitExceeded)
```

일반적인 etcd 크기는 **수십 MB**다. 우리 클러스터는 **814MB**. 홈랩 업로드 대역폭(약 0.85 MB/s)으로 814MB를 올리면 16분 이상 걸려 타임아웃.

원인을 확인했다:

```bash
sudo grep 'auto-compaction' /etc/kubernetes/manifests/etcd.yaml
# (출력 없음) ← auto-compaction 설정이 없다!
```

클러스터 운영 100일간 모든 리비전이 누적되어 814MB가 된 것이다.

### 해결: Compaction + Defrag + auto-compaction 설정

```bash
# 현재 상태 확인
sudo ETCDCTL_API=3 etcdctl endpoint status --write-out=table \
  --endpoints=https://127.0.0.1:2379 \
  --cacert=/etc/kubernetes/pki/etcd/ca.crt \
  --cert=/etc/kubernetes/pki/etcd/server.crt \
  --key=/etc/kubernetes/pki/etcd/server.key
# | ENDPOINT                 | DB SIZE  |
# | https://127.0.0.1:2379   | 814 MB   |

# Step 1: Compaction (과거 리비전 논리 삭제)
rev=$(sudo ETCDCTL_API=3 etcdctl endpoint status --write-out=json \
  --endpoints=https://127.0.0.1:2379 \
  --cacert=/etc/kubernetes/pki/etcd/ca.crt \
  --cert=/etc/kubernetes/pki/etcd/server.crt \
  --key=/etc/kubernetes/pki/etcd/server.key | \
  python3 -c "import json,sys; print(json.load(sys.stdin)[0]['Status']['header']['revision'])")

sudo ETCDCTL_API=3 etcdctl compact $rev \
  --endpoints=https://127.0.0.1:2379 \
  --cacert=/etc/kubernetes/pki/etcd/ca.crt \
  --cert=/etc/kubernetes/pki/etcd/server.crt \
  --key=/etc/kubernetes/pki/etcd/server.key
# 출력: compacted revision 23558715

# Compaction 후 확인 — 아직 814MB (논리 삭제만 됨)

# Step 2: Defrag (디스크 공간 실제 회수)
# 주의: 수초간 etcd 응답 지연 발생
sudo ETCDCTL_API=3 etcdctl defrag \
  --endpoints=https://127.0.0.1:2379 \
  --cacert=/etc/kubernetes/pki/etcd/ca.crt \
  --cert=/etc/kubernetes/pki/etcd/server.crt \
  --key=/etc/kubernetes/pki/etcd/server.key
# 출력: Finished defragmenting etcd member[...]

# Defrag 후 확인
# | ENDPOINT                 | DB SIZE  |
# | https://127.0.0.1:2379   | 25 MB    |   ← 814MB → 25MB!
```

재발 방지를 위해 auto-compaction을 설정했다:

```yaml
# /etc/kubernetes/manifests/etcd.yaml의 command에 추가
- --auto-compaction-mode=periodic
- --auto-compaction-retention=1h
```

etcd는 static pod이므로 manifest 수정 시 kubelet이 자동으로 재시작한다.

| 지표 | Before | After |
|------|--------|-------|
| etcd DB 크기 | **814MB** | **25MB** (97% 감소) |
| 스냅샷 S3 업로드 | 타임아웃 (16분+) | **101초** |
| auto-compaction | 미설정 | 1시간 주기 |

**결과**: etcd-backup 테스트 성공 (31.6MB, 101초)

---

## 문제 3: mysql-backup-to-s3 — Alpine MariaDB 호환성

```bash
kubectl logs -l job-name=mysql-s3-test -n backup-system
# 출력:
# mysqldump: Got error: 1045: "Plugin caching_sha2_password could not be loaded:
#   Error loading shared library /usr/lib/mariadb/plugin/caching_sha2_password.so:
#   No such file or directory"
```

Alpine Linux의 `mysql-client` 패키지는 실제로는 **MariaDB 클라이언트**다. MariaDB 클라이언트는 MySQL 8.0의 기본 인증 플러그인인 `caching_sha2_password`를 지원하지 않는다.

### 해결: 이미지 변경

처음에 `mysql:8.0`으로 변경했으나, 이 이미지는 **Oracle Linux 기반**이라 `apt-get`이 없었다.

```bash
# mysql:8.0 (Oracle Linux) → apt-get 없음
apt-get update
# /bin/sh: apt-get: command not found
```

최종적으로 `mysql:8.0-debian`을 사용했다. Debian 기반이라 `apt-get`으로 `awscli` 설치가 가능하다.

```yaml
# Before
image: alpine:3.19
# apk add --no-cache mysql-client aws-cli    ← MariaDB 클라이언트

# After
image: mysql:8.0-debian
# apt-get update -qq && apt-get install -y -qq awscli curl gzip
```

추가로, `hostNetwork: true` 환경에서 Pushgateway 메트릭 push가 클러스터 DNS를 해석하지 못하는 문제도 수정했다:

```bash
# hostNetwork: true → 호스트의 /etc/resolv.conf 사용 → 클러스터 DNS 미해석
curl http://pushgateway.monitoring.svc.cluster.local:9091/...
# curl: (6) Could not resolve host

# 해결: set -e에서 스크립트가 중단되지 않도록 || true 추가
curl -s --max-time 5 --data-binary @- http://pushgateway...|| true
```

**결과**: mysql-backup-to-s3 테스트 성공 (855KB, 25초)

---

## 문제 4: mysql-backup (blog-system) — Kyverno 차단 + S3 버킷명 오류

```bash
kubectl describe job mysql-blog-test -n blog-system | grep Events -A5
# Events:
#   Warning  FailedCreate  job-controller
#     admission webhook "validate.kyverno.svc-ignore" denied the request:
#     policy require-non-root/require-non-root-pod fail
```

Pod 생성 자체가 Kyverno 정책에 의해 차단되었다. `securityContext`가 없어서 `require-non-root` 정책 위반.

```yaml
# 추가한 설정
spec:
  template:
    spec:
      securityContext:
        runAsNonRoot: true
        runAsUser: 1000
```

Kyverno를 통과한 후에는 S3 버킷명 오류가 나왔다:

```bash
# NoSuchBucket: The specified bucket does not exist
# 설정: jimin-mysql-backup (존재하지 않음)
# 실제: jimin-backup
```

S3_BUCKET 값을 `jimin-backup`으로 수정했다.

**결과**: mysql-backup 테스트 성공 (854KB, 9초)

---

## 최종 결과

| CronJob | Before | After | 개선 |
|---------|--------|-------|------|
| **etcd-backup-to-s3** | 814MB → S3 타임아웃 → Failed | 31.6MB, 101초 | 성공 |
| **mysql-backup-to-s3** | caching_sha2_password 에러 → Failed | 855KB, 25초 | 성공 |
| **mysql-backup** (blog-system) | Kyverno 차단 + S3 버킷 오류 → Failed | 854KB, 9초 | 성공 |

## 배운 점

### 문제는 겹친다

하나의 CronJob 실패 뒤에 **5개의 독립적인 문제**가 숨어 있었다:

```
1. kubelet 인증서 IP SAN 누락 → 진단 자체가 불가능
2. etcd 814MB → S3 업로드 타임아웃
3. Alpine MariaDB → MySQL 8.0 인증 플러그인 미지원
4. Kyverno securityContext 미설정
5. S3 버킷명 오류
```

하나를 해결하면 다음 레이어의 문제가 드러나는 구조였다.

### etcd auto-compaction은 필수

kubeadm 기본 설정에 `auto-compaction-retention`이 없다. 설정하지 않으면 클러스터 운영 기간에 비례해서 etcd DB가 계속 커진다. 운영 환경에서는 반드시 설정해야 한다.

### 진단 도구가 안 되면 진단 도구부터 고쳐라

CronJob 실패를 진단하려면 `kubectl logs`가 필요하고, 이것이 안 되면 원인 파악 자체가 불가능하다. "진단 인프라"가 먼저 정상이어야 한다.

---

## Sources

- [Kubernetes PKI Certificates](https://kubernetes.io/docs/setup/best-practices/certificates/)
- [kubelet TLS Bootstrap](https://kubernetes.io/docs/reference/access-authn-authz/kubelet-tls-bootstrapping/)
- [etcd Maintenance](https://etcd.io/docs/v3.5/op-guide/maintenance/)
- [kubeadm etcd Configuration](https://kubernetes.io/docs/setup/production-environment/tools/kubeadm/setup-ha-etcd-with-kubeadm/)
