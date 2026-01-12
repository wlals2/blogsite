---
title: "pod 트러블 슈팅 뿌시기 feat.PVC"
date: 2025-11-19T15:10:12+09:00
draft: false
categories: ["pod","trouble shooting","PVC"]
tags: ["k8s","PVC","trouble shooting","longhorn","init_container"]
description: "pod 트러블 슈팅 뿌시기 feat.PVC"
author: "늦찌민"
---

# Nextcloud Pod가 PodInitializing에서 멈추는 문제 해결

> 이전 글: [Kubernetes + Longhorn + VMware Worker 환경에서 PVC가 계속 망가지는 문제 해결](./longhorn-vmware-troubleshooting.md)

## 문제 상황

### 배경
이전 트러블슈팅에서 Longhorn 설정을 완료하고 모든 PVC를 재생성했습니다:
- Control Plane을 주 스토리지로 설정 ✅
- Replica 개수 1로 조정 ✅
- 모니터링 시스템 (Grafana, Prometheus) 정상 동작 ✅

하지만 Nextcloud는 여전히 문제가 있었습니다.

### 증상

```bash
$ kubectl -n nextcloud get pods
NAME                            READY   STATUS            RESTARTS   AGE
nextcloud-749ff94d7c-xsfx7      0/1     PodInitializing   0          5m
nextcloud-db-5f696d4f47-vdc78   1/1     Running           0          5m

```

- **nextcloud-db**: 정상 실행 ✅
- **nextcloud**: PodInitializing 상태로 멈춤 ⏳

### PVC 상태는 정상

```bash
$ kubectl -n nextcloud get pvc
NAME                 STATUS   VOLUME                                     CAPACITY   STORAGECLASS
nextcloud-app-pvc    Bound    pvc-548af1dd-5d39-45e0-9d4c-749ca3cc4596   2Gi        longhorn
nextcloud-data-pvc   Bound    pvc-da572f49-4485-403d-918a-92e6a4d36452   3Gi        longhorn
nextcloud-db-pvc     Bound    pvc-c6b5e7f8-9a1b-4c2d-8d3e-5f6a7b8c9d0e   3Gi        longhorn

$ kubectl -n longhorn-system get volume
NAME                                       STATE      ROBUSTNESS   NODE
pvc-548af1dd-5d39-45e0-9d4c-749ca3cc4596   attached   healthy      jimin-ab350m-gaming-3
pvc-da572f49-4485-403d-918a-92e6a4d36452   attached   healthy      jimin-ab350m-gaming-3
pvc-c6b5e7f8-9a1b-4c2d-8d3e-5f6a7b8c9d0e   attached   healthy      jimin-ab350m-gaming-3

```

모든 볼륨이 `healthy` 상태인데 왜 Pod는 시작이 안 되는가? 🤔

---

## 문제 진단

### 1단계: Pod 상세 정보 확인

```bash
$ kubectl -n nextcloud describe pod nextcloud-749ff94d7c-xsfx7

```

**핵심 발견:**

```yaml
Init Containers:
  fix-permissions:
    State:          Terminated
      Reason:       Completed
      Exit Code:    0
    Ready:          True

Containers:
  nextcloud:
    State:          Running
      Started:      Wed, 19 Nov 2025 13:40:09 +0900
    Ready:          True

```

**어? Init Container는 완료되었고 메인 컨테이너도 Running인데?**

### 2단계: Events 확인

```yaml
Events:
  Type     Reason                  Age    Message
  ----     ------                  ----   -------
  Warning  FailedScheduling        13m    persistentvolumeclaim "nextcloud-app-pvc" not found
  Normal   Scheduled               8m40s  Successfully assigned nextcloud/nextcloud-749ff94d7c-xsfx7
  Normal   SuccessfulAttachVolume  8m30s  AttachVolume.Attach succeeded for volume "pvc-548af..."
  Normal   SuccessfulAttachVolume  8m30s  AttachVolume.Attach succeeded for volume "pvc-da572..."
  Normal   Pulling                 8m28s  Pulling image "busybox:latest"
  Normal   Pulled                  8m26s  Successfully pulled image "busybox:latest"
  Normal   Started                 8m26s  Started container fix-permissions
  Normal   Pulling                 8m25s  Pulling image "nextcloud:latest"
  Normal   Pulled                  22s    Successfully pulled image "nextcloud:latest" in 8m2.796s  # ← 여기!
  Normal   Created                 22s    Created container: nextcloud
  Normal   Started                 22s    Started container nextcloud

```

**핵심 발견:**
- `Pulling image "nextcloud:latest"` 시작: 8분 25초 전
- `Successfully pulled image`: 22초 전
- **소요 시간: 8분 2.796초** 😱

---

## 근본 원인

### Nextcloud 이미지 크기

```bash
$ kubectl -n nextcloud describe pod nextcloud-749ff94d7c-xsfx7 | grep "Image size"
Image size: 523988958 bytes  # 약 524MB

```

**문제:**
- Nextcloud 이미지: **524MB** (매우 큼)
- busybox 이미지: **2.2MB** (빠르게 다운로드됨)
- 네트워크 속도에 따라 이미지 다운로드에 5~10분 소요

### PodInitializing의 의미

Kubernetes의 Pod 라이프사이클:

```

Pending → PodInitializing → Running
  ↓              ↓              ↓
스케줄링     Init 컨테이너    메인 컨테이너
             + 이미지 다운     실행 중

```

**PodInitializing 단계에서 하는 일:**
1. Init Container 실행 (fix-permissions)
2. **메인 컨테이너 이미지 다운로드** ← 여기서 시간 소요!
3. 메인 컨테이너 시작

### 왜 Grafana/Prometheus는 빨랐나?

```bash
# 이미지 크기 비교
Grafana:      ~200MB
Prometheus:   ~250MB
Nextcloud:    ~524MB  ← 2배 이상 큼!

```

---

## 해결 방법

### 방법 1: 기다리기 (권장)

**가장 간단한 해결책**: 그냥 기다리면 됩니다.

```bash
# 실시간 상태 모니터링
$ kubectl -n nextcloud get pods --watch

# 이미지 다운로드 진행 상황 확인 (다른 터미널에서)
$ kubectl -n nextcloud describe pod nextcloud-xxx | grep -A 5 "Events:"

```

**예상 시간:**
- 빠른 네트워크 (100Mbps+): 2~5분
- 보통 네트워크 (50Mbps): 5~10분
- 느린 네트워크 (10Mbps): 10~20분

### 방법 2: 이미지 사전 다운로드 (최적화)

만약 자주 재배포한다면 이미지를 미리 받아두는 것이 좋습니다:

```bash
# 모든 노드에서 이미지 다운로드
$ kubectl get nodes -o name | while read node; do
    kubectl debug $node -it --image=nextcloud:latest -- sh -c "echo 'Image pulled'"
done

# 또는 crictl로 직접 다운로드 (각 노드에서)
$ sudo crictl pull nextcloud:latest

```

### 방법 3: 특정 버전 고정 (안정성)

`latest` 태그 대신 특정 버전을 사용하면 예측 가능:

```yaml
# deployment.yaml
spec:
  containers:
  - name: nextcloud
    image: nextcloud:29.0.8  # 특정 버전 명시
    imagePullPolicy: IfNotPresent  # 로컬에 있으면 재다운로드 안 함

```

**장점:**
- 첫 다운로드 후 재배포 시 이미지 다운로드 생략
- 예기치 않은 버전 변경 방지

### 방법 4: Local Registry 구축 (고급)

프로덕션 환경이라면 로컬 레지스트리 추천:

```bash
# Harbor 또는 Docker Registry 구축
$ helm install harbor harbor/harbor

# Nextcloud 이미지를 로컬 레지스트리로 복사
$ docker pull nextcloud:latest
$ docker tag nextcloud:latest harbor.local/nextcloud:latest
$ docker push harbor.local/nextcloud:latest

```

---

## 최종 확인

### Pod 정상 실행

```bash
$ kubectl -n nextcloud get pods
NAME                            READY   STATUS    RESTARTS   AGE
nextcloud-749ff94d7c-xsfx7      1/1     Running   0          13m
nextcloud-db-5f696d4f47-vdc78   1/1     Running   0          13m

```

### Nextcloud 로그 확인

```bash
$ kubectl -n nextcloud logs nextcloud-749ff94d7c-xsfx7 --tail=20
Initializing nextcloud 32.0.1.2 ...
New nextcloud instance
Initializing finished
AH00558: apache2: Could not reliably determine the server's fully qualified domain name
Apache/2.4.65 (Debian) PHP/8.3.27 configured -- resuming normal operations

```

### 서비스 접속

```bash
$ kubectl -n nextcloud get svc
NAME           TYPE        CLUSTER-IP       EXTERNAL-IP   PORT(S)
nextcloud      NodePort    10.97.128.183    <none>        80:30888/TCP
nextcloud-db   ClusterIP   10.103.159.120   <none>        3306/TCP

# 브라우저에서 접속
http://192.168.1.187:30888

```

---

## 배운 교훈

### 1. PodInitializing ≠ 문제
- PodInitializing 상태가 길어도 정상일 수 있음
- 큰 이미지 다운로드 중일 수 있으므로 `describe pod`로 Events 확인 필수

### 2. 문제 진단 순서

```bash
# 1. Pod 상태
kubectl get pods

# 2. Pod 상세 정보 (가장 중요!)
kubectl describe pod <pod-name>

# 3. Events 확인
kubectl get events --sort-by='.lastTimestamp'

# 4. 로그 확인
kubectl logs <pod-name>

```

### 3. 이미지 크기 최적화
- 프로덕션 환경: 특정 버전 태그 사용 (`nextcloud:29.0.8`)
- `imagePullPolicy: IfNotPresent` 설정
- 필요하다면 alpine 기반 이미지 고려

### 4. 인내심
- Kubernetes 문제의 50%는 "기다리면 해결됨"
- 급하게 Pod를 재시작하지 말고 Events 먼저 확인

---

## 참고: Init Container의 역할

Nextcloud deployment에서 사용한 Init Container:

```yaml
initContainers:
- name: fix-permissions
  image: busybox:latest
  command:
    - sh
    - -c
    - |
      chown -R 33:33 /var/www/html/data
      chmod -R 770 /var/www/html/data
  volumeMounts:
  - name: nextcloud-data
    mountPath: /var/www/html/data

```

**목적:**
- Nextcloud 컨테이너가 시작되기 전에 권한 설정
- UID 33 (www-data): Nextcloud의 Apache 사용자
- PVC에 저장된 데이터 디렉토리 권한 수정

**왜 필요한가?**
- Longhorn PVC는 기본적으로 root 소유
- Nextcloud는 www-data 사용자로 실행
- 권한이 맞지 않으면 "Permission denied" 에러 발생

---

## 시리즈 정리

### 1편: [Longhorn + VMware Worker 기본 설정](./longhorn-vmware-troubleshooting.md)
- VMware VM 재시작 시 PVC faulted 문제
- Control Plane을 주 스토리지로 설정
- Replica 개수 조정

### 2편: Nextcloud Pod PodInitializing 문제 (본 문서)
- 큰 이미지 다운로드로 인한 긴 대기 시간
- PodInitializing 상태의 정확한 의미
- Init Container를 통한 권한 설정

### 다음 편 예고
- Nextcloud 데이터 백업 전략
- S3 호환 스토리지 연동
- Longhorn 백업 자동화

---

## 참고 자료
- [Kubernetes Pod Lifecycle](https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/)
- [Container Image Pull Policy](https://kubernetes.io/docs/concepts/containers/images/#image-pull-policy)
- [Init Containers](https://kubernetes.io/docs/concepts/workloads/pods/init-containers/)


