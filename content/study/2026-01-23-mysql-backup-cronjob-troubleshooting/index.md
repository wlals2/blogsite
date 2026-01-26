---
title: "MySQL 백업 CronJob 트러블슈팅 (Cilium, Istio, Longhorn, S3)"
date: 2026-01-23
description: "CiliumNetworkPolicy, Istio Sidecar, Longhorn PVC, AWS S3 인증 문제 해결"
tags: ["kubernetes", "mysql", "cronjob", "cilium", "istio", "longhorn", "s3", "troubleshooting"]
categories: ["study", "Troubleshooting", "Storage"]
---

## 개요

MySQL 백업 CronJob 구축 중 발생한 4가지 문제:

| 번호 | 문제 | 원인 |
|------|------|------|
| 1 | MySQL 연결 거부 | CiliumNetworkPolicy |
| 2 | Job Init:0/2 멈춤 | Istio Sidecar 주입 |
| 3 | PVC Attach 타임아웃 | Longhorn Multi-Attach |
| 4 | S3 업로드 실패 | AWS 인증 문제 |

---

## 1. CiliumNetworkPolicy로 인한 MySQL 연결 거부

### 상황

```bash
kubectl logs job/mysql-backup -n blog-system -c mysqldump
```

```
ERROR 2003 (HY000): Can't connect to MySQL server on 'mysql-service:3306' (110)
```

### 원인

CiliumNetworkPolicy `mysql-isolation`이 `app: was` 레이블만 허용:

```yaml
# 기존 mysql-isolation 정책
ingress:
- fromEndpoints:
  - matchLabels:
      app: was  # mysql-backup 없음!
```

**Hubble CLI로 확인**:

```bash
hubble observe --namespace blog-system --protocol TCP --port 3306
```

```
mysql-backup → mysql-service:3306 DROPPED (policy denied)
```

### 해결

**1. mysql-isolation에 mysql-backup 허용 추가**:

```yaml
# cilium-netpol.yaml - ingress rule 추가
ingress:
- fromEndpoints:
  - matchLabels:
      app: mysql-backup
      io.kubernetes.pod.namespace: blog-system
  toPorts:
  - ports:
    - port: "3306"
      protocol: TCP
```

**2. mysql-backup-isolation 정책 생성 (Egress)**:

```yaml
apiVersion: cilium.io/v2
kind: CiliumNetworkPolicy
metadata:
  name: mysql-backup-isolation
  namespace: blog-system
spec:
  endpointSelector:
    matchLabels:
      app: mysql-backup
  egress:
  # MySQL 접근
  - toEndpoints:
    - matchLabels:
        app: mysql
    toPorts:
    - ports:
      - port: "3306"
        protocol: TCP
  # DNS 허용
  - toEndpoints:
    - matchLabels:
        io.kubernetes.pod.namespace: kube-system
        k8s-app: kube-dns
    toPorts:
    - ports:
      - port: "53"
        protocol: UDP
  # S3 접근 (HTTPS)
  - toEntities:
    - world
    toPorts:
    - ports:
      - port: "443"
        protocol: TCP
```

### 확인

```bash
# 정책 적용 확인
kubectl get ciliumnetworkpolicies -n blog-system

# Hubble로 트래픽 확인
hubble observe --namespace blog-system -l app=mysql-backup

# 수동 백업 테스트
kubectl create job --from=cronjob/mysql-backup mysql-backup-test -n blog-system
kubectl logs job/mysql-backup-test -n blog-system -c mysqldump
```

---

## 2. Istio Sidecar 주입으로 인한 Job 실패

### 상황

```bash
kubectl get pods -n blog-system -l job-name=mysql-backup-manual
```

```
NAME                          READY   STATUS     RESTARTS   AGE
mysql-backup-manual-xxxxx     0/2     Init:0/2   0          2m
```

Job이 `Init:0/2` 상태에서 멈춤.

### 원인

Istio sidecar가 Job Pod에 자동 주입되어 initContainer가 2개 생성됨:

| initContainer | 출처 | 역할 |
|---------------|------|------|
| istio-init | Istio 자동 주입 | iptables 설정 |
| mysqldump | 우리 설정 | 백업 실행 |

**잘못된 annotation 위치**:

```yaml
# ❌ spec 레벨 - 무시됨
spec:
  annotations:
    sidecar.istio.io/inject: "false"
```

### 해결

**annotation을 template.metadata 레벨로 이동**:

```yaml
apiVersion: batch/v1
kind: CronJob
spec:
  jobTemplate:
    spec:
      template:
        metadata:
          labels:
            app: mysql-backup
          annotations:  # ✅ 올바른 위치
            sidecar.istio.io/inject: "false"
        spec:
          # ...
```

### 확인

```bash
# Sidecar 없는지 확인
kubectl get pod -l app=mysql-backup -n blog-system \
  -o jsonpath='{.items[*].spec.containers[*].name}'
# 예상: s3-upload (istio-proxy 없음)

# initContainer 개수 확인
kubectl get pod -l app=mysql-backup -n blog-system \
  -o jsonpath='{.items[*].spec.initContainers[*].name}'
# 예상: mysqldump (istio-init 없음)
```

### 결과

| 항목 | Before | After |
|------|--------|-------|
| annotation 위치 | spec 레벨 | template.metadata |
| initContainers | 2개 (istio-init, mysqldump) | 1개 (mysqldump) |
| Pod 상태 | Init:0/2 (멈춤) | Running → Completed |

---

## 3. Longhorn PVC Attach 타임아웃

### 상황

```
Warning  FailedAttachVolume  Multi-Attach error for volume "pvc-xxx"
AttachVolume.Attach failed for volume "pvc-xxx": rpc error: code = DeadlineExceeded
```

### 원인

| 원인 | 설명 |
|------|------|
| 볼륨 이미 Attach | 다른 노드에서 사용 중 |
| replica 3 설정 | 2노드 클러스터에서 스케줄링 실패 |
| 마이그레이션 지연 | 노드 간 볼륨 이동 시간 |

### 해결

**emptyDir 사용 (권장)**:

```yaml
volumes:
- name: backup-storage
  emptyDir: {}  # Job 종료 시 자동 정리
```

**장단점**:

| 구분 | 내용 |
|------|------|
| **장점** | PVC 연결 문제 없음<br>노드 간 스케줄링 자유<br>S3가 primary storage이므로 데이터 손실 없음 |
| **단점** | Pod 재시작 시 임시 파일 손실 (S3 업로드 전) |

**대안: RWX PVC**:

```yaml
apiVersion: v1
kind: PersistentVolumeClaim
spec:
  accessModes:
    - ReadWriteMany  # RWX
  storageClassName: longhorn
```

> **주의**: Longhorn RWX는 NFS 기반으로 성능 저하 가능

---

## 4. AWS S3 인증 실패

### 상황

```
An error occurred (InvalidAccessKeyId) when calling the PutObject operation
```

### 원인

| 원인 | 설명 |
|------|------|
| Secret 없음 | AWS 자격 증명 Secret 미생성 |
| IAM 권한 없음 | S3 접근 권한 미부여 |
| 버킷 정책 | 버킷이 접근 차단 |

### 해결

**1. Secret 생성**:

```bash
kubectl create secret generic aws-s3-credentials \
  -n blog-system \
  --from-literal=AWS_ACCESS_KEY_ID=AKIA... \
  --from-literal=AWS_SECRET_ACCESS_KEY=... \
  --from-literal=AWS_DEFAULT_REGION=ap-northeast-2
```

**2. IAM 최소 권한 정책**:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "s3:PutObject",
        "s3:GetObject",
        "s3:ListBucket"
      ],
      "Resource": [
        "arn:aws:s3:::jimin-mysql-backup",
        "arn:aws:s3:::jimin-mysql-backup/*"
      ]
    }
  ]
}
```

**3. 버킷 존재 확인**:

```bash
aws s3 ls s3://jimin-mysql-backup/
```

### 확인

```bash
# Secret 확인
kubectl get secret aws-s3-credentials -n blog-system

# 백업 Job 로그
kubectl logs job/mysql-backup -n blog-system -c s3-upload
```

---

## 정리

### MySQL 백업 CronJob 체크리스트

| 항목 | 확인 사항 |
|------|----------|
| **CiliumNetworkPolicy** | mysql-backup → mysql:3306 허용 |
| **Istio Sidecar** | `template.metadata.annotations`에 inject: false |
| **Storage** | emptyDir 사용 (PVC 문제 회피) |
| **AWS 인증** | Secret + IAM 권한 설정 |

### 최종 CronJob 구조

```yaml
apiVersion: batch/v1
kind: CronJob
metadata:
  name: mysql-backup
  namespace: blog-system
spec:
  schedule: "0 3 * * *"  # 매일 03:00
  jobTemplate:
    spec:
      template:
        metadata:
          labels:
            app: mysql-backup
          annotations:
            sidecar.istio.io/inject: "false"  # Istio 제외
        spec:
          initContainers:
          - name: mysqldump
            image: mysql:8.0
            command: ["mysqldump"]
            args: ["-h", "mysql-service", "-u", "root", "-p$(MYSQL_ROOT_PASSWORD)", "--all-databases"]
            env:
            - name: MYSQL_ROOT_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: mysql-secret
                  key: MYSQL_ROOT_PASSWORD
            volumeMounts:
            - name: backup-storage
              mountPath: /backup
          containers:
          - name: s3-upload
            image: amazon/aws-cli:2.15.0
            command: ["aws", "s3", "cp", "/backup/dump.sql", "s3://jimin-mysql-backup/"]
            envFrom:
            - secretRef:
                name: aws-s3-credentials
            volumeMounts:
            - name: backup-storage
              mountPath: /backup
          volumes:
          - name: backup-storage
            emptyDir: {}  # PVC 대신 emptyDir
          restartPolicy: OnFailure
```

---

## 관련 명령어

```bash
# 백업 상태 확인
kubectl get cronjob mysql-backup -n blog-system
kubectl get jobs -n blog-system -l app=mysql-backup

# 수동 백업 실행
kubectl create job --from=cronjob/mysql-backup manual-backup -n blog-system

# Cilium 정책 확인
kubectl get ciliumnetworkpolicies -n blog-system
hubble observe --namespace blog-system -l app=mysql-backup

# S3 백업 파일 확인
aws s3 ls s3://jimin-mysql-backup/ --recursive
```
