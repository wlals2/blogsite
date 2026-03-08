---
title: "kubelet 인증서와 etcd 데이터 관리 — 백업 CronJob이 실패하기 전에 알아야 할 것들"
date: 2026-03-07T14:00:00+09:00
categories: ["study", "Kubernetes"]
tags: ["kubernetes", "kubelet", "tls", "certificate", "etcd", "compaction", "defrag", "csr"]
summary: "kube-apiserver와 kubelet은 어떻게 통신하는가? kubelet 인증서의 IP SAN은 왜 중요한가? etcd는 왜 점점 커지는가? Backup CronJob 복구 실습 전에 필요한 배경지식을 정리한다."
showtoc: true
tocopen: true
draft: false
---

> 이 글은 이론편입니다. 실제 트러블슈팅 과정은 [실습편](/study/2026-03-07-kubelet-cert-etcd-practice/)에서 다룹니다.

---

## 1. kube-apiserver와 kubelet은 어떻게 통신하는가?

Kubernetes 클러스터에서 `kubectl logs`, `kubectl exec`, `kubectl port-forward` 같은 명령어를 실행하면, 단순히 Pod에 직접 접근하는 것이 아니다.

```
사용자 → kube-apiserver → kubelet → 컨테이너
```

### 통신 흐름

```
1. kubectl logs my-pod
   ↓
2. kube-apiserver가 Pod이 어느 노드에 있는지 확인
   ↓
3. kube-apiserver가 해당 노드의 kubelet에 HTTPS 요청
   GET https://192.168.1.187:10250/containerLogs/namespace/pod/container
   ↓
4. kubelet이 컨테이너 런타임에서 로그를 가져와 응답
   ↓
5. kube-apiserver가 사용자에게 전달
```

여기서 핵심은 **3번 단계**다. kube-apiserver는 kubelet에 **HTTPS**로 접근하므로, kubelet이 제시하는 TLS 인증서를 검증한다.

### kubelet의 TLS 인증서

kubelet은 10250 포트에서 HTTPS 서버를 운영한다. 이 서버의 인증서에는 **SAN(Subject Alternative Name)**이 포함되어야 한다.

```
SAN이란?
  인증서가 "어떤 이름/IP로 접근할 때 유효한지"를 명시하는 필드

예시:
  DNS: k8s-worker1          ← 호스트네임으로 접근 시 유효
  IP Address: 192.168.1.61  ← IP로 접근 시 유효
```

kube-apiserver는 kubelet에 접근할 때 **노드의 IP 주소**를 사용한다. 따라서 kubelet 인증서에 `IP Address: 192.168.1.61` 같은 IP SAN이 반드시 포함되어야 한다.

**만약 IP SAN이 없으면?**

```
x509: cannot validate certificate for 192.168.1.187
because it doesn't contain any IP SANs
```

kube-apiserver가 kubelet의 인증서를 신뢰할 수 없어서 통신 자체가 거부된다. `kubectl logs`, `kubectl exec` 모두 실패한다.

---

## 2. kubelet은 인증서를 어떻게 발급받는가?

kubelet의 인증서 발급 방식은 두 가지가 있다.

### 방식 1: 자체 서명 (Self-Signed) — kubeadm 기본값

```
kubelet이 스스로 CA를 만들고, 자기가 자기 인증서를 발급
  → 파일: /var/lib/kubelet/pki/kubelet.crt
  → 문제: DNS만 포함하고 IP SAN을 포함시키는 옵션이 없음
```

kubeadm으로 클러스터를 구성하면 이것이 기본 동작이다. kubelet이 시작할 때 인증서가 없으면 자체 CA(`<hostname>-ca`)를 만들고 인증서를 자동 생성한다.

이 방식의 문제: kubelet이 자체 생성하는 인증서에는 **DNS SAN만 포함**되고 IP SAN이 빠진다.

### 방식 2: serverTLSBootstrap — CSR 기반 발급

```yaml
# /var/lib/kubelet/config.yaml
rotateCertificates: true
serverTLSBootstrap: true    # ← 이것을 추가
```

이 설정을 켜면 kubelet의 동작이 바뀐다:

```
1. kubelet 시작
2. 자체 CA 대신, 클러스터 CA(kube-apiserver)에 CSR(Certificate Signing Request) 제출
3. CSR에 노드의 DNS + IP를 SAN으로 자동 포함
4. 관리자가 CSR 승인 → 클러스터 CA가 인증서 발급
5. kubelet이 발급받은 인증서로 HTTPS 서버 운영
```

두 방식의 차이를 표로 정리하면:

| 항목 | 자체 서명 (기본) | serverTLSBootstrap |
|------|-----------------|-------------------|
| CA | kubelet 자체 CA | 클러스터 CA |
| SAN | DNS만 포함 | DNS + IP 포함 |
| 인증서 파일 | `kubelet.crt` | `kubelet-server-current.pem` |
| 승인 필요 | 없음 (자동) | 관리자 수동 승인 |
| kube-apiserver 신뢰 | 기본적으로 안 함 | 클러스터 CA이므로 신뢰 |

---

## 3. CSR(Certificate Signing Request)이란?

CSR은 "인증서를 발급해주세요"라는 요청서다.

```
CSR 내용:
  - 요청자: system:node:k8s-worker1
  - 용도: kubelet serving certificate
  - SAN: DNS:k8s-worker1, IP:192.168.1.61    ← 핵심!
  - 공개키: (포함됨)
```

Kubernetes에서 CSR 워크플로우:

```bash
# 1. kubelet이 CSR 생성 → kube-apiserver에 제출
kubectl get csr
# NAME        REQUESTOR                 CONDITION
# csr-abc12   system:node:k8s-worker1   Pending

# 2. 관리자가 CSR 내용 확인 (SAN에 IP가 포함되었는지)
kubectl get csr csr-abc12 -o jsonpath='{.spec.request}' | base64 -d | \
  openssl req -noout -text | grep "Subject Alternative Name" -A1
# DNS:k8s-worker1, IP Address:192.168.1.61

# 3. 승인
kubectl certificate approve csr-abc12

# 4. kubelet이 발급된 인증서를 자동으로 적용
# /var/lib/kubelet/pki/kubelet-server-current.pem
```

주의사항: `serverTLSBootstrap`을 사용하면 CSR이 **자동 승인되지 않는다**. 관리자가 수동으로 승인해야 하며, 인증서 갱신 시에도 매번 승인이 필요하다.

---

## 4. etcd의 데이터 저장 방식 — 왜 커지는가?

etcd는 Kubernetes의 모든 상태(Pod, Service, ConfigMap 등)를 저장하는 Key-Value 저장소다. 핵심 특징은 **MVCC(Multi-Version Concurrency Control)** — 모든 변경 이력을 보관한다.

```
ConfigMap을 3번 수정하면:

리비전 1: data: "v1"   ← 과거 버전 (여전히 디스크에 존재)
리비전 2: data: "v2"   ← 과거 버전 (여전히 디스크에 존재)
리비전 3: data: "v3"   ← 현재 버전

→ 3개 모두 디스크에 저장됨
```

K8s 클러스터에서는 Pod 생성/삭제, Lease 갱신, Event 생성 등으로 **매초 수십~수백 개의 리비전이 생성**된다. 이것을 정리하지 않으면 etcd DB가 계속 커진다.

### Compaction이란?

**특정 리비전 이전의 과거 버전을 삭제하는 것**이다.

```
Compaction 전 (리비전 1~100 존재):
  [v1][v2][v3]...[v100]  ← 100개 버전 모두 저장

Compaction(리비전 100) 후:
  [v100]                 ← 리비전 100의 현재 값만 유지, 1~99 삭제
```

과거 리비전은 `etcdctl watch --rev` 같은 이력 조회에만 쓰인다. 실제 클러스터 운영에는 최신 값만 필요하므로 compaction해도 문제없다.

### Defrag이란?

Compaction은 **논리적으로** 데이터를 삭제하지만, **DB 파일 크기는 줄어들지 않는다**. 데이터베이스에서 `DELETE` 후 `VACUUM`하는 것과 같은 개념이다.

```
Compaction 후 etcd DB 파일:
  [유효 30MB][빈 공간 784MB]  ← 파일 크기는 여전히 814MB

Defrag 후:
  [유효 30MB]                 ← 실제 파일 크기 30MB로 축소
```

### auto-compaction-retention

etcd에 자동 compaction을 설정하는 옵션이다.

```yaml
# /etc/kubernetes/manifests/etcd.yaml
- --auto-compaction-mode=periodic
- --auto-compaction-retention=1h    # 1시간 이전 리비전 자동 삭제
```

kubeadm 기본 설정에는 이 옵션이 **없다**. 설정하지 않으면 클러스터 생성 이후 모든 리비전이 영구 보관된다. 이것이 etcd가 계속 커지는 근본 원인이다.

---

## 5. CronJob 실패 진단 순서

CronJob이 실패했을 때 확인하는 정석적인 순서:

```
1단계: CronJob 상태 확인
  kubectl get cronjob -n <ns>
  → LAST SCHEDULE, ACTIVE, SUSPEND 확인
  → "스케줄대로 실행은 되고 있는가?"

2단계: Job 이력 확인
  kubectl get jobs --sort-by=.metadata.creationTimestamp
  → Complete / Failed 확인
  → "언제부터 실패했는가? 마지막 성공은 언제?"

3단계: 실패 Job의 상태 확인
  kubectl describe job <job-name>
  → Conditions: BackoffLimitExceeded? DeadlineExceeded?
  → "어떤 이유로 실패했는가?"

4단계: Pod 로그 확인 ← 가장 중요
  kubectl logs <pod-name>
  → "실제로 어떤 에러가 발생했는가?"

5단계: Pod 상태 확인 (로그가 안 보이면)
  kubectl describe pod <pod-name>
  → Events: 스케줄링 실패? 이미지 풀 실패? OOMKilled?
```

대부분의 원인은 **4단계(Pod 로그)**에서 드러난다. 하지만 4단계 자체가 실패하는 경우도 있다 — kubelet 인증서 문제처럼 `kubectl logs`가 안 되는 상황이 그 예다.

---

## 6. MySQL 8.0 인증 플러그인 — caching_sha2_password

MySQL 8.0부터 기본 인증 플러그인이 `mysql_native_password`에서 `caching_sha2_password`로 변경되었다.

| 항목 | mysql_native_password | caching_sha2_password |
|------|----------------------|----------------------|
| MySQL 버전 | 5.7 이하 기본 | 8.0 이상 기본 |
| 보안 | SHA-1 해시 | SHA-256 + RSA |
| 호환성 | 거의 모든 클라이언트 | 공식 MySQL 클라이언트만 |

문제가 되는 상황: **Alpine Linux의 `mysql-client` 패키지는 실제로 MariaDB 클라이언트**다. MariaDB 클라이언트는 `caching_sha2_password` 플러그인을 지원하지 않는다.

```
alpine:3.19 + apk add mysql-client
  → MariaDB 클라이언트 설치됨
  → MySQL 8.0 서버 접속 시 caching_sha2_password 에러

mysql:8.0-debian
  → 공식 MySQL 클라이언트 포함
  → caching_sha2_password 정상 지원
```

백업 CronJob에서 Alpine 이미지를 사용할 때 흔히 빠지는 함정이다.

---

## Sources

- [Kubernetes PKI Certificates](https://kubernetes.io/docs/setup/best-practices/certificates/)
- [kubelet TLS Bootstrap](https://kubernetes.io/docs/reference/access-authn-authz/kubelet-tls-bootstrapping/)
- [etcd Maintenance](https://etcd.io/docs/v3.5/op-guide/maintenance/)
- [MySQL 8.0 Authentication Plugin Changes](https://dev.mysql.com/doc/refman/8.0/en/upgrading-from-previous-series.html#upgrade-caching-sha2-password)
