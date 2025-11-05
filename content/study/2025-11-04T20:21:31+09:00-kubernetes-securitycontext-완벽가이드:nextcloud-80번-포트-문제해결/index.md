---
title: "Kubernetes securityContext 완벽가이드:Nextcloud 80번 포트 문제해결"
date: 2025-11-04T20:21:31+09:00
draft: false
categories: ["k8s","securityContext","runAsuser","yaml"]
tags: ["k8s","securityContext","runAsuser","yaml","pod","troubleshooting","apache","fsGroup"]
description: "Kubernetes securityContext 완벽가이드:Nextcloud 80번 포트 문제해결"
author: "늦찌민"
series: ["K8s 개념 뿌시기"]
---

## 문제 상황

Nextcloud를 Kubernetes에 배포했는데 Pod가 CrashLoopBackOff 상태로 계속 재시작됩니다.

**에러 로그:**
```
(13)Permission denied: AH00072: make_sock: could not bind to address :80
no listening sockets available, shutting down
```

## securityContext란?

### 기본 개념

**securityContext**는 Kubernetes에서 Pod나 Container가 **어떤 권한으로 실행될지** 결정하는 설정입니다.

```yaml
spec:
  securityContext:          # Pod 레벨 (모든 컨테이너에 적용)
    runAsUser: 1000         # UID 1000으로 실행
    runAsGroup: 3000        # GID 3000으로 실행
    fsGroup: 2000           # 볼륨 파일 그룹 ID
    runAsNonRoot: true      # root 실행 금지
  containers:
  - name: myapp
    securityContext:        # Container 레벨 (개별 컨테이너)
      allowPrivilegeEscalation: false
      capabilities:
        drop: ["ALL"]
```

### 주요 옵션 설명

#### 1. runAsUser
**Pod/Container가 실행될 User ID(UID)를 지정**

```yaml
securityContext:
  runAsUser: 33  # UID 33(www-data)으로 실행
```

**의미:**
- 컨테이너 내부의 프로세스가 해당 UID로 실행됨
- `ps aux`로 확인 시 해당 UID가 표시됨
- 파일 접근, 네트워크 바인딩 등 모든 작업이 해당 UID 권한으로 수행

**예시:**
```bash
# runAsUser: 33 설정 시
$ kubectl exec -it pod-name -- ps aux
USER       PID  COMMAND
www-data     1  apache2
```

#### 2. runAsGroup
**Pod/Container의 Primary Group ID(GID) 지정**

```yaml
securityContext:
  runAsGroup: 3000
```

#### 3. fsGroup
**볼륨(PVC) 마운트 시 파일의 소유 그룹 설정**

```yaml
securityContext:
  fsGroup: 33
```

**동작 방식:**
```bash
# fsGroup: 33 설정 시
$ kubectl exec -it pod-name -- ls -la /var/www/html
drwxrwsr-x 2 root   33  4096 Nov  4 10:00 data
-rw-rw-r-- 1 nobody 33  1234 Nov  4 10:01 config.php
```

**중요 포인트:**
- 파일 소유자(UID)는 변경 안 됨
- **그룹(GID)만 fsGroup으로 변경**
- setgid 비트(`s`) 자동 설정 → 새 파일도 같은 그룹

#### 4. runAsNonRoot
**root(UID 0) 실행 금지**

```yaml
securityContext:
  runAsNonRoot: true
```

- 컨테이너 이미지가 root로 실행하려 하면 **시작 차단**
- 보안 강화용

## Linux의 특권 포트(Privileged Ports)

### 포트 바인딩 규칙

Linux 커널은 포트를 두 가지로 구분합니다:

| 포트 범위 | 이름 | 바인딩 권한 |
|----------|------|------------|
| **1-1023** | **특권 포트** | **root(UID 0) 필요** |
| 1024-65535 | 비특권 포트 | 일반 유저 가능 |

### 왜 이런 규칙이 있나?

**역사적 이유 (보안):**
- 80(HTTP), 443(HTTPS), 22(SSH) 같은 중요 포트
- 일반 유저가 악의적으로 가짜 웹서버 띄우는 것 방지
- root만 신뢰할 수 있는 서비스 실행 가능

### 예시

```bash
# 일반 유저(UID 1000)
$ python3 -m http.server 80
OSError: [Errno 13] Permission denied

# root(UID 0)
$ sudo python3 -m http.server 80
Serving HTTP on 0.0.0.0 port 80...  # ✅ 성공
```

## Nextcloud 컨테이너의 동작 방식

### Nextcloud 이미지 내부 구조

```dockerfile
# 간소화된 Nextcloud Dockerfile
FROM php:apache

# Apache는 80번 포트 사용
EXPOSE 80

# entrypoint.sh 스크립트
ENTRYPOINT ["/entrypoint.sh"]
```

### 실행 흐름

```
┌─────────────────────────────────────────┐
│ 1. 컨테이너 시작 (root, UID 0)          │
│    - /entrypoint.sh 실행               │
└──────────────┬──────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────┐
│ 2. Apache 설정 및 80 포트 바인딩 (root) │
│    ✅ 특권 포트라 root 권한 필요         │
└──────────────┬──────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────┐
│ 3. Apache가 자식 프로세스 생성          │
│    - Worker: www-data(UID 33)로 실행   │
│    - PHP 처리: www-data 권한           │
└─────────────────────────────────────────┘
```

**핵심:**
- **마스터 프로세스**: root로 실행 (80번 포트 점유)
- **워커 프로세스**: www-data로 실행 (실제 웹 처리)

### 실제 프로세스 확인

```bash
$ kubectl exec -it nextcloud-pod -- ps aux
USER       PID  COMMAND
root         1  apache2 -DFOREGROUND        # 마스터
www-data    15  apache2 -DFOREGROUND        # 워커
www-data    16  apache2 -DFOREGROUND        # 워커
```

## 문제 발생 시나리오

### ❌ runAsUser: 33 설정 시

```yaml
securityContext:
  runAsUser: 33        # UID 33(www-data)로 강제 실행
  runAsNonRoot: true
```

**실행 흐름:**
```
┌──────────────────────────────────────────┐
│ 1. 컨테이너 시작 (www-data, UID 33)      │
│    ❌ root가 아님!                        │
└──────────────┬───────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────┐
│ 2. Apache가 80 포트 바인딩 시도          │
│    ❌ Permission denied (UID 33은 불가)  │
└──────────────┬───────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────┐
│ 3. Apache 시작 실패 → Exit Code 1       │
│    🔄 Kubernetes가 재시작 시도           │
│    🔄 CrashLoopBackOff                   │
└──────────────────────────────────────────┘
```

**에러 로그:**
```
AH00072: make_sock: could not bind to address :80
(13)Permission denied
```

## 다른 서비스는 왜 괜찮나?

### Prometheus (9090번 포트)

```yaml
securityContext:
  runAsUser: 65534     # ✅ 가능
  runAsNonRoot: true
```

**이유:**
- 9090번 포트 = **비특권 포트** (1024 이상)
- UID 65534(nobody)도 바인딩 가능

### Grafana (3000번 포트)

```yaml
securityContext:
  runAsUser: 472       # ✅ 가능
  runAsNonRoot: true
```

**이유:**
- 3000번 포트 = 비특권 포트
- UID 472도 바인딩 가능

### MariaDB (3306번 포트)

```yaml
securityContext:
  runAsUser: 999       # ✅ 가능
  runAsNonRoot: true
```

**이유:**
- 3306번 포트 = 비특권 포트
- UID 999도 바인딩 가능

### 비교 표

| 서비스 | 포트 | 특권 포트? | runAsUser 가능? |
|--------|------|-----------|----------------|
| Nextcloud | 80 | ✅ 예 (1-1023) | ❌ 불가 (root 필요) |
| Prometheus | 9090 | ❌ 아니오 | ✅ 가능 |
| Grafana | 3000 | ❌ 아니오 | ✅ 가능 |
| MariaDB | 3306 | ❌ 아니오 | ✅ 가능 |

## 해결 방법

### 1. runAsUser 제거 (채택한 방법)

```yaml
securityContext:
  fsGroup: 33  # PVC 권한만 해결
  # runAsUser: 33        ← 삭제
  # runAsNonRoot: true   ← 삭제
```

**장점:**
- ✅ 80번 포트 바인딩 가능 (root로 시작)
- ✅ PVC 파일 권한 해결 (fsGroup: 33)
- ✅ Apache 워커는 여전히 www-data로 실행 (보안)

**동작:**
```
1. 컨테이너 시작 → root(UID 0)
2. Apache 마스터 → root (80번 포트 OK)
3. Apache 워커 → www-data (UID 33)
4. PVC 파일 그룹 → 33 (fsGroup)
```

### 2. 비특권 포트 사용 (대안)

```yaml
containers:
- name: nextcloud
  image: nextcloud:latest
  ports:
  - containerPort: 8080  # 80 → 8080 변경

securityContext:
  runAsUser: 33
  runAsNonRoot: true
```

**단점:**
- Nextcloud 이미지를 수정해야 함
- Apache 설정 변경 필요

### 3. CAP_NET_BIND_SERVICE 추가 (고급)

```yaml
securityContext:
  runAsUser: 33
  capabilities:
    add:
    - NET_BIND_SERVICE  # 특권 포트 허용
```

**의미:**
- UID 33도 특권 포트 바인딩 가능하도록 권한 추가
- Linux Capabilities 활용

**단점:**
- 복잡하고 보안 위험 증가

## fsGroup의 역할

### 문제: PVC 권한 불일치

**Longhorn PV 기본 권한:**
```bash
$ ls -la /var/lib/longhorn/
drwxr-xr-x 2 root root 4096 data/
```

**Nextcloud가 파일 쓰기 시도:**
```bash
# www-data(UID 33)가 쓰기 시도
$ touch /var/www/html/data/test.txt
Permission denied  # ❌ root 소유라 실패
```

### fsGroup으로 해결

```yaml
securityContext:
  fsGroup: 33
```

**변경된 권한:**
```bash
$ ls -la /var/www/html/
drwxrwsr-x 2 root   33 4096 data/
-rw-rw-r-- 1 nobody 33 1234 config.php
```

**변경 사항:**
- 그룹이 33(www-data)으로 변경
- 그룹 쓰기 권한(`w`) 추가
- setgid 비트(`s`) 설정

**결과:**
```bash
# www-data가 쓰기 가능!
$ touch /var/www/html/data/test.txt  # ✅ 성공
```

## 최종 권장 설정

### Nextcloud (80번 포트 사용)

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: nextcloud
spec:
  template:
    spec:
      securityContext:
        fsGroup: 33  # PVC 그룹 권한만 설정
        # runAsUser 설정 안 함 (root 허용)
      containers:
      - name: nextcloud
        image: nextcloud:latest
        ports:
        - containerPort: 80
```

### 일반 애플리케이션 (비특권 포트)

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: myapp
spec:
  template:
    spec:
      securityContext:
        fsGroup: 1000
        runAsUser: 1000      # 가능
        runAsNonRoot: true   # 권장
      containers:
      - name: myapp
        image: myapp:latest
        ports:
        - containerPort: 8080  # 비특권 포트
```

## 트러블슈팅 체크리스트

### Pod가 CrashLoopBackOff일 때

- [ ] **로그 확인**: `kubectl logs pod-name`
  - "Permission denied" + 포트 번호?
- [ ] **포트 확인**: 1-1023번 포트 사용하는가?
- [ ] **securityContext 확인**: `runAsUser` 설정되어 있나?
- [ ] **해결**: 특권 포트면 `runAsUser` 제거

### PVC 권한 문제일 때

- [ ] **에러 메시지**: "Permission denied" + 파일 경로?
- [ ] **fsGroup 설정**: PVC 마운트 시 필수
- [ ] **값 확인**: 애플리케이션 UID에 맞춰 설정
  - Nextcloud: 33
  - Nginx: 101
  - Node.js 앱: 1000 (이미지마다 다름)

### 애플리케이션별 UID

| 애플리케이션 | UID | 용도 |
|------------|-----|------|
| Nextcloud | 33 | www-data |
| Grafana | 472 | grafana |
| Prometheus | 65534 | nobody |
| MariaDB | 999 | mysql |
| Nginx | 101 | nginx |

## 참고 자료

### Kubernetes 공식 문서
- [Configure a Security Context](https://kubernetes.io/docs/tasks/configure-pod-container/security-context/)
- [Pod Security Standards](https://kubernetes.io/docs/concepts/security/pod-security-standards/)

### Linux Capabilities
- [capabilities(7) man page](https://man7.org/linux/man-pages/man7/capabilities.7.html)

### 특권 포트 해제 (참고용)
```bash
# Linux에서 특권 포트 제한 해제 (비권장)
sysctl -w net.ipv4.ip_unprivileged_port_start=0
```

## 요약

1. **securityContext**는 Pod/Container의 실행 권한 설정
2. **runAsUser**: 프로세스 실행 UID 지정
3. **fsGroup**: PVC 파일 그룹 권한 설정
4. **특권 포트(1-1023)**: root(UID 0) 권한 필요
5. **Nextcloud는 80번 포트** → runAsUser 설정 불가
6. **비특권 포트 앱**은 runAsUser로 보안 강화 권장
7. **fsGroup은 PVC 사용 시 필수**

**핵심 원칙:**
- 특권 포트 사용 → `runAsUser` 제거, `fsGroup`만 설정
- 비특권 포트 사용 → `runAsUser` + `runAsNonRoot` + `fsGroup` 모두 설정











