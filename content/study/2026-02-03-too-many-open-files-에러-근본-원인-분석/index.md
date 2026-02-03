---
title: '"too many open files" 에러 - 근본 원인 분석'
date: 2026-02-03T20:42:00+09:00
draft: false
description: k8s 운영 중에 한 노드 장애를 겪은 트러블 슈팅내용 feat. File Descriptor(FD)
tags:
  - k8s
  - node
  - FileDescriptor
  - versioning
  - log
categories:
  - study
  - Kubernetes
---
## 배경지식: Linux File Descriptor 란?
---
### 📖 File Descriptor (FD)의 개념
#### 정의 :
- Linux 에서 프로세스가 열어둔 **파일, 소켓, 파이프 등의 리소스를 가리키는 정수 번호**
- 프로세스가 파일을 읽거나 쓸때 사용하는 핸들

#### 예시 :
```
#프로세스가 열면
fd = open("/var/logapp.log", "w") # fd = 3

# 이후 파일좆가시
white(fd, "Hello") # fd 3번을 사용
cluse(fd)          # fd 3번 해제
```

#### 왜 번호 인가?
- 커널이 프로세스 별로 "열린 파일 테이블"을 관리
- 각 파일에 번호를 붙여서 빠르게 참조

#### 기본적으로 열려 있는 FD:
FD 0: stdin (표준 입력)
FD 1: stdout (표준출력)
FD 2: stderr (표준에러)
FD 3~: 프로세그 열은 파일들

## File Descriptor 제한의 의미
---
### ulimit -n 1024의 의미:
- 이프로세스는 동시에 **최대 1024개의 파일/소켓/파이프**만 열 수 있음
- 1025 번째 파일을 열려면 -> **"too many open files"** 에러

### 왜 제한이 있나 ?
- 메모리 보호: 무한정 파일을 열면 메모리 고갈
- 시스템 안정성: 악의적인 프로그램이 시스템 리소스 독점 방지
- 역사적 이유: 옛날 unix 시스템은 리소스가 부족해 기본값을 1024로 설정

## 2. Containerd는 왜 많은 File Descriptor가 필요한가 ?
---
## 🐳 containerd의 역할

### containerd란 ?
- kubernetes의 **Container Runtime(컨테이너 실행 엔진)**
- Docker 같은 도구가 내부적으로 사용하는 핵심 컴포 너트
- **Pod 생성, 이미지 다운로드, 네트워크 설정, 볼륨 마운트** 등 모든 작업 담당

### containerd가 하는일 
```
kubelet 요청 → containerd
    ↓
1. 이미지 다운로드 (quay.io/argoproj/argocd:v3.2.5)
    → 레지스트리와 HTTPS 소켓 연결
    → tar 파일 압축 해제 (여러 파일 열기)
2. 컨테이너 생성
    → /proc, /sys 파일시스템 접근
    → cgroup 파일 생성 (/sys/fs/cgroup/...)
    → namespace 파일 생성 (/proc/.../ns/...)
3. 로그 수집
    → 각 컨테이너 stdout/stderr 파이프 생성
    → /var/log/pods/.../*.log 파일 열기
4. 네트워크 설정
    → CNI 플러그인과 소켓 통신
    → iptables, netlink 소켓 열기
5. 볼륨 마운트
    → Longhorn, NFS 등 스토리지 플러그인과 통신
```

### 각 작업마다 파일을 열어야함 :
```
Pod 1개 생성 시 예상 FD 사용량:
- 이미지 레이어 (5개) = 5 FD
- 컨테이너 stdout/stderr = 2 FD
- cgroup 파일 (cpu, memory, pids 등) = 10 FD
- namespace 파일 (net, pid, mnt 등) = 5 FD
- 볼륨 마운트 = 2 FD
→ 약 24 FD / Pod
```

### worker3에 50개의 Pod가 있다면?
```
50 Pod × 24 FD = 1200 FD
→ 기본 제한(1024) 초과! ❌
```
## Kubernetes 클러스터에서 FD 사용량 증가 이유
---
### 1. Pod 수 증가
- ArgoCD, Cilium, Istio, Monitoring 등 시스템 Pod
- Blog 애플리케이션 pod
- Longhorn 스토리지 Pod
- **총 50 개이상의 pod가 worker3에 스케줄링됨**

### 2. Sidecar 패턴
- Istio Ssidecar (Envoy proxy) -> **각 Pod마다 추가 컨테이너**
- Envoy는 네트워크 프록시이므로 **소켓을 많이 사용**
- 예: WAS Pod = 1개 컨테이너 -> Istio 적용후 = 2개 컨테이너 (메인 + Envoy)
### 3. 로그 수집
- 모든 Pod 로그를 containerd가 파일로 저장
- /var/log/pods/namespace_pod_uid/container/*.log
- 각 로그 마다 FD 1개 사용
### 4. 이미지레이어
- 컨테이너 이미지는 **레이어 구조(OverlayFS)**
- 각 레이어마다 파일 열기 필요
- 예: ArgoCD 이미지 = 10개 레이어 -> 10 FD
### 5. CNI 플러그인(Cilium)
- Cilium이 BPF map, netlink 소켓 사용
- 각 Pod의 네트워크 인터페이스 생성 시 FD 사용

## "too many open files" 에러의 연쇄 반응
---
### 에러 발생 메커니즘

### Step 1: containerd FD 고갈
```
containerd 프로세스:
- 현재 열린 FD: 1020개
- 새 Pod 스케줄링 요청 도착
- 이미지 다운로드 시도 → open("layer.tar") 
→ 에러: "too many open files" (EMFILE)
```
### Step 2: 이미지 다운로드 실패
```
containerd 로그:
# 실제 로그
failed to pull image "quay.io/argoproj/argocd:v3.2.5": 
  too many open files
```
### Step 3: kubelet이 재시도
```
kubelet → containerd: "이미지 다운로드해줘"
containerd → kubelet: "실패했어 (EMFILE)"
kubelet: "다시 시도할게" (BackOff)
```
### Step 4: Pod 상태 변화
```
Pod 상태 변화:
Pending → ContainerCreating → ErrImagePull → ImagePullBackOff
```

### 왜 BackOff 인가 ?
- kubernetes는 실패 시 **지수 백오프**(Exponential Backoff) 재시도
- 1초 -> 2초 -> 4초 -> 8초 ... 최대 5분 간격
- 계속 실패하면 "ImagePullBackOff" 상태로 표시

## CRI 플러그인 로드 실패 (worker2)
---
### Step 1: containerd 시작시 **CRI 플러그인 로드**
```
containerd 시작:
1. 기본 플러그인 로드
2. CRI (Container Runtime Interface) 플러그인 로드 시도
   → Kubernetes와 통신하기 위한 gRPC 서버
3. CRI 플러그인이 CNI 설정 모니터링 시작
   → fsnotify watcher 생성 (파일 변경 감지)
   → open("/etc/cni/net.d/*")
   → 에러: "too many open files" ❌
```
### Step 2: CRI 플러그인 로드 실패 로그
```
# 실제 로그
failed to create CRI service: 
  failed to create cni conf monitor for default: 
  failed to create fsnotify watcher: 
  too many open files
```

### 왜 fsnotify watcher가 필요한가 ?
- CNI 설정 파일이 변경되면 자동으로 재로드
- 예: Cilium 설정 변경시 containerd가 자동 감지
- **inotify** (Linux 파일 변경 감지 시스템) 사용 -> FD 필요

### Step 3:kubelet이 container와 통신 불가
```
kubelet → containerd CRI endpoint (unix:///run/containerd/containerd.sock)
containerd: "CRI 플러그인 없어요" (로드 실패)
kubelet: "Container Runtime 응답 없음"
→ 노드 상태: NotReady, containerd://Unknown
```
## 원인 파악:왜 worker3에만 집중 발생 했나 ?
---

### 🎯 특정 노드 집중 현상
### 1. PodAffinity/Anti-Affinity
- ArgoCD, Cilium 등 일부 Pod는 **특정 노드에 선호도** 설정가능
- 예: "SSD가 있는 노드에 스케줄링"
### 2. Resource 기반 스케줄링
- kubernetes Scheduler는 **가용 CPU/Memory가 많은 노드** 선호
- worker3이 다른 노드보다 리소스 여유 있었을 가능성
- 비교적 최근에 추가한 노드로 많은 Pod가 할당되어 있지 않음
### 3. 이전 Pod 배치 이력
- 한번 많이 배치된 노드는 계속 선택될 확률 높음
- 예: Longhorn 볼륨이 worker3에 있으면 -> 해당 볼륨 사용하는 Pod도 worker3로
### 4. 노드별 containerd 버전 차이
```
worker1, worker2: containerd 2.1.5 (최신)
worker3: containerd 1.7.28 (구버전)
```
- **구버전이 FD 관리가 비효율적일 가능성**
- 새 버전은 FD 재사용, 캐싱 최적화

## 해결 방법: systemd LimitNOFILE
---
### systemd 란 ?
- Linux의 **시스템 초기화 및 서비스 관리자(init 프로세스 대체)**
- 모든 서비스(containerd, kubelet, docker 등)를 실행/관리
### 서비스 파일 예시:
```
# /usr/lib/systemd/system/containerd.service
[Unit]
Description=containerd container runtime

[Service]
ExecStart=/usr/bin/containerd
LimitNOFILE=1024  ← 기본값 (너무 작음!)
Restart=always

[Install]
WantedBy=multi-user.target
```
## Overried 파일의 원리
### 왜 원본 파일을 수정하지 않나?
- `/usr/lib/systemd/system/containerd.service`는 패키지 관리자가 관리
- `apt upgrade` 시 덮어쓰여질 수 있음
- **Override 파일은 사용자 설정이므로 보존됨**

### Override 우선순위:
```
1. /usr/lib/systemd/system/containerd.service (기본)
2. /etc/systemd/system/containerd.service.d/*.conf (사용자 override)
→ 2번이 1번을 덮어씀 (merge)
```

### 생성한 override.confg:
```
# /etc/systemd/system/containerd.service.d/override.conf
[Service]
LimitNOFILE=1048576    # 1024 → 1048576 (1024배 증가)
LimitNPROC=infinity    # 프로세스 수 무제한
LimitCORE=infinity     # Core dump 크기 무제한
TasksMax=infinity      # systemd Task 제한 해제
```
### 각 설정의 의미

- **LimitNOFILE=1048576**: File Descriptor 최대 1,048,576개 (충분!)
- **LimitNPROC=infinity**: 프로세스/스레드 수 제한 없음 (containerd가 많은 goroutine 생성)
- **LimitCORE=infinity**: 크래시 시 core dump 완전히 저장 (디버깅용)
- **TasksMax=infinity**: systemd의 cgroup task 제한 해제 (많은 컨테이너 실행 가능)

## 적용 프로세스
### 1. daemon-reload가 필요한 이유:
`sudo systemctl daemon-reload`
- systemd가 서비스 파일을 **메모리에 캐싱**
- 파일 변경시 캐시 갱신 필요
- reload 없이 restart 하면 -> 이전 설정으로 재시작됨

### 2. 재시작 순서:
`sudo systemctl restart tcontainaerd # 먼저`\
`sudo systemctl restart kubelet # 나중` 
- containerd 재시작 시 모든 컨테이너 정지 (CRI shim 재시작)
- kubelet 재시작 시 containerd에 재연결
- 순서 중요: kubelet이 먼저 재시작되면 containerd 찾지 못함

### 3. 확인 방법:
`cat /proc/$(pidof containerd)/limits | grep "Max open files"` \
`# Max open files  1048576  1048576  files`
- `/proc/[PID]/limits`: 프로세스별 리소스 제한 조회
- soft limit(1열) = 현재 제한
- hard limit(2열) = 최대 상하선

## 교훈 및 예방 조치
### 배운 점

### 1. Kubernetes는 리소스 집약적
- 단순히 CPU/Memory만 보면 안됨
- File Descriptor, PID, Socket 등 커널 리소스도 중요
### 2. 기본 값은 데스크톱 환경 기준
- Ubuntu 기본값 1024 = 개인 사용자용
- 서버 환경에서는 튜닝 필수

### 예방 조치
### 1. 모든 worker 노드에 동일 설정 적용
`worker1, worker2, worker3 모두 LimitNOFILE=1048576`
### 2. Infrastructure as Code
```
# Ansible playbook 예시
- name: Configure containerd limits
  copy:
    dest: /etc/systemd/system/containerd.service.d/override.conf
    content: |
      [Service]
      LimitNOFILE=1048576
```
### 3. 모니터링 추가
```
# Prometheus query
process_open_fds{job="containerd"} / process_max_fds{job="containerd"} > 0.8
```
- FD 사용률 80% 이상 Alert 발송
### 4. 정기 점검
```
# Cron job
0 * * * * lsof -p $(pidof containerd) | wc -l > /var/log/containerd-fd-count.log
```

### 문제의 본질
```
Kubernetes는 수많은 작은 프로세스들(Pod)의 집합체입니다.

각 프로세스는 로그, 네트워크, 볼륨 등 많은 파일을 열어야 합니다.

Linux 기본 설정(1024 FD)은 개인 PC 환경을 가정하므로,

서버 환경에서는 반드시 튜닝이 필요합니다.
```
