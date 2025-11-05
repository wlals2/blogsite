---
title: "Kubernetes ReplicationContoller 트러블 슈팅 가이드"
date: 2025-11-05T14:38:54+09:00
draft: false
categories: ["k8s","ReplicationContoller","Troubleshooting"]
tags: ["k8s","ReplicationContoller","Troubleshooting"]
description: "Kubernetes ReplicationContoller 트러블 슈팅 가이드"
author: "늦찌민"
series: ["K8s 개념 뿌시기"]
---

# Kubernetes ReplicationController 트러블슈팅 가이드

## 문제 상황: RC가 replicas 3개를 생성하지 않는다?

### 초기 증상
```yaml
apiVersion: v1
kind: ReplicationController
metadata:
  name: rc-nginx
spec:
  replicas: 3
  selector:
    app: web
  template:
    metadata:
      name: nginx-pod
      labels:
        app: web  
    spec:
      containers:
      - name: nginx-containers
        image: nginx:1.25
```

위 RC를 생성했는데 pod가 2개만 생성되는 현상 발생:
```bash
$ kubectl get pods
NAME                         READY   STATUS    RESTARTS       AGE
nginx-pod                    1/1     Running   1 (3h1m ago)   20h
rc-nginx-5dgbn               1/1     Running   0              85s
rc-nginx-tnwxs               1/1     Running   0              3m19s
```

---

## 원인 분석

### ReplicationController의 동작 원리

RC는 다음과 같이 동작합니다:

1. **selector에 매칭되는 모든 pod를 카운트**
2. **현재 pod 수와 replicas 수를 비교**
3. **부족하면 생성, 초과하면 삭제**

중요한 점은 **RC가 직접 생성한 pod만 관리하는 게 아니라**, selector label이 일치하는 **모든 pod를 관리 대상으로 인식**한다는 것입니다.

### 문제의 핵심
```bash
# 기존에 존재하던 nginx-pod 확인
$ kubectl get pod nginx-pod --show-labels
NAME        READY   STATUS    RESTARTS   AGE   LABELS
nginx-pod   1/1     Running   1          20h   app=web
```

기존 `nginx-pod`가 `app=web` 레이블을 가지고 있었습니다!

**RC의 계산:**
- nginx-pod (수동 생성) ← `app=web` 레이블 보유
- rc-nginx-5dgbn (RC 생성)
- rc-nginx-tnwxs (RC 생성)
- **총 3개 = replicas 목표 달성!**

따라서 RC는 더 이상 pod를 생성하지 않았습니다.

---

## 검증 실험: 수동 pod 생성 시 동작

### 실험 설정

RC가 3개의 pod를 관리하는 상태에서, 같은 레이블을 가진 전혀 다른 pod를 생성:
```yaml
apiVersion: v1
kind: Pod
metadata:
  name: pod-other
  labels:
    app: web  # RC와 동일한 레이블
spec:
  containers:
  - name: appjs-container
    image: jjmin/appjs
    ports:
    - containerPort: 8080
```

### 실험 결과
```bash
$ kubectl apply -f pod-other.yaml
pod/pod-other created

# 몇 초 후...
$ kubectl get pods -l app=web
NAME             READY   STATUS        RESTARTS   AGE
rc-nginx-5dgbn   1/1     Running       0          10m
rc-nginx-tnwxs   1/1     Running       0          12m
nginx-pod        1/1     Running       1          20h
pod-other        0/1     Terminating   0          3s  # 즉시 삭제됨!
```

**pod-other가 즉시 삭제되었습니다!**

### 삭제 우선순위 알고리즘

RC가 pod를 삭제할 때의 우선순위:

1. **Pending 상태의 pod 우선 삭제**
2. **같은 상태라면 가장 최근에 생성된 pod 삭제**
3. **기존에 Running 중인 안정적인 pod 보호**

이는 클러스터 안정성을 위한 설계입니다. 새로 생성된 pod보다 이미 정상 동작 중인 pod를 우선 보호하는 것이죠.

### 주요 발견사항

**RC는 pod의 내용을 전혀 확인하지 않습니다:**
- nginx 이미지든, appjs 이미지든 상관없음
- 컨테이너 설정이 다르든 상관없음
- **오직 label selector만 확인**

따라서 `app=web` 레이블만 같다면:
- ✅ 같은 pod로 간주
- ✅ replicas 카운트에 포함
- ✅ 초과 시 삭제 대상

---

## 핵심 교훈

### 1. RC의 Label Selector는 사실상 "예약됨"

RC가 사용하는 label selector는 해당 RC가 독점합니다. 같은 label을 가진 다른 pod는:
- 자동으로 RC 관리 대상이 됨
- replicas를 초과하면 삭제됨
- **의도와 상관없이 RC에 종속됨**

### 2. 수동 생성 Pod와 RC는 공존 불가
```bash
# RC가 app=web을 관리 중이라면
# 이 label을 가진 pod는 수동으로 관리 불가능

✅ 가능: 다른 label 사용 (app=other)
❌ 불가능: 같은 label로 별도 pod 생성
```

### 3. Label 설계의 중요성
```yaml
# 나쁜 예 - 너무 일반적
selector:
  app: web

# 좋은 예 - 구체적이고 명확
selector:
  app: nginx
  version: v1.25
  tier: frontend
  managed-by: rc-nginx
```

더 구체적인 label을 사용하면:
- 의도하지 않은 pod가 포함될 위험 감소
- 다른 용도의 pod와 충돌 방지
- 관리 목적 명확화

---

## 해결 방법

### 방법 1: 기존 수동 생성 Pod 삭제
```bash
# 수동으로 생성했던 pod 삭제
$ kubectl delete pod nginx-pod

# RC가 자동으로 3번째 pod 생성
$ kubectl get pods -l app=web
NAME             READY   STATUS    RESTARTS   AGE
rc-nginx-5dgbn   1/1     Running   0          15m
rc-nginx-tnwxs   1/1     Running   0          17m
rc-nginx-xyz12   1/1     Running   0          5s   # 새로 생성됨
```

### 방법 2: 기존 Pod의 Label 변경
```bash
# 기존 pod의 레이블 제거
$ kubectl label pod nginx-pod app-

# 또는 다른 값으로 변경
$ kubectl label pod nginx-pod app=manual --overwrite

# RC가 새 pod 생성
```

### 방법 3: 별도 Label 사용 (권장)
```yaml
# 수동 관리용 pod는 다른 label 사용
apiVersion: v1
kind: Pod
metadata:
  name: pod-other
  labels:
    app: manual-web  # RC와 다른 label
spec:
  containers:
  - name: appjs-container
    image: jjmin/appjs
```

### 방법 4: Namespace 분리
```bash
# 다른 namespace에서는 같은 label 사용 가능
$ kubectl create namespace manual
$ kubectl apply -f pod-other.yaml -n manual
```

---

## 디버깅 명령어 모음
```bash
# 1. RC 상태 확인
$ kubectl get rc rc-nginx
$ kubectl describe rc rc-nginx

# 2. 특정 label의 모든 pod 확인
$ kubectl get pods -l app=web --show-labels

# 3. Pod 생성 시간 순 정렬
$ kubectl get pods -l app=web --sort-by=.metadata.creationTimestamp

# 4. 실시간 pod 변화 모니터링
$ kubectl get pods -l app=web -w

# 5. RC 이벤트 확인
$ kubectl get events --sort-by='.lastTimestamp' | grep rc-nginx

# 6. 특정 pod의 레이블 확인
$ kubectl get pod <pod-name> --show-labels
```

---

## ReplicationController vs Deployment

이러한 RC의 한계 때문에 현재는 **Deployment**를 주로 사용합니다:

| 기능 | ReplicationController | Deployment |
|------|---------------------|------------|
| 롤링 업데이트 | ❌ 수동 처리 | ✅ 자동 지원 |
| 롤백 | ❌ 불가능 | ✅ 가능 |
| Template 관리 | ❌ Label만 확인 | ✅ Template 변경 감지 |
| 업데이트 전략 | ❌ 없음 | ✅ 다양한 전략 |
```yaml
# 현대적인 방식 - Deployment 사용 권장
apiVersion: apps/v1
kind: Deployment
metadata:
  name: nginx-deployment
spec:
  replicas: 3
  selector:
    matchLabels:
      app: web
  template:
    metadata:
      labels:
        app: web
    spec:
      containers:
      - name: nginx
        image: nginx:1.25
```

---

## 결론

**ReplicationController의 핵심 동작 원리:**

1. **Label Selector만으로 pod를 식별**
2. **pod의 실제 내용(이미지, 설정 등)은 무시**
3. **생성 주체와 관계없이 모든 매칭 pod를 관리**
4. **최신 pod를 먼저 삭제하여 안정성 유지**

이러한 특성을 이해하면 RC 관련 트러블슈팅이 훨씬 쉬워집니다. 하지만 현업에서는 더 강력한 기능을 제공하는 **Deployment 사용을 권장**합니다.

