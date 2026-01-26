---
title: "Tekton 처음부터 이해하기 - Kubernetes 네이티브 CI/CD의 시작"
date: 2025-11-17T00:00:00
draft: false
categories: ["study", "Kubernetes"]
tags: ["DevOps","Kubernetes","CI/CD","Tekton","Cloud Native","Pipeline"]
author: "늦찌민"
series: ["좀 더 고급으로 변하는 인프라 구조"]
description: "Jenkins는 알겠는데 Tekton은 뭐지? 아키텍처부터 천천히 이해해보자"
---

## 🧭 개요

Jenkins로 CI/CD를 구현했다. ArgoCD로 배포 자동화도 했다.
그런데 **Tekton**이라는 게 있다고 한다.
"Kubernetes 네이티브 CI/CD"라는데, 대체 뭐가 다른 걸까?

이번에는 **맡기지 않고 직접 이해하면서** 공부해보려 한다.
아키텍처를 모르면 결국 트러블슈팅도 못 한다는 걸 깨달았기 때문이다.


## 🤔 왜 Tekton을 배워야 하나?

### Jenkins와 뭐가 다른가?

| 항목 | Jenkins | Tekton |
|------|---------|--------|
| **실행 환경** | 별도 Jenkins 서버 필요 | Kubernetes Pod로 실행 |
| **파이프라인 정의** | Jenkinsfile (Groovy 스크립트) | YAML (Kubernetes manifest) |
| **리소스 사용** | 항상 서버가 떠있어야 함 | 필요할 때만 Pod 생성 |
| **확장성** | 플러그인 설치 필요 | Kubernetes Custom Resource |
| **학습 곡선** | 상대적으로 쉬움 | Kubernetes 개념 필요 |
| **클라우드 네이티브** | 전통적인 방식 | 완전히 Kubernetes 네이티브 |

**간단히 말하면:**
- Jenkins: "전용 건물(서버)에서 작업하는 공장"
- Tekton: "필요할 때마다 임시 작업장(Pod)을 만들어 작업하는 공장"


## 📚 Tekton 핵심 개념 (천천히 이해하기)

Tekton은 **4가지 핵심 리소스**로 구성된다.
레고 블록처럼 하나하나 조립하는 방식이다.

### 1. Step (가장 작은 단위)

**"하나의 명령어를 실행하는 것"**

```yaml
- name: say-hello
  image: ubuntu
  command:
    - echo
  args:
    - "Hello World"

```

**이게 뭐냐면:**
- ubuntu 이미지를 사용해서
- echo 명령어를 실행
- "Hello World" 출력

→ 단 하나의 작업만 한다. 이게 Step.


### 2. Task (Step들의 모음)

**"여러 Step을 순서대로 실행하는 작업 단위"**

```yaml
apiVersion: tekton.dev/v1beta1
kind: Task
metadata:
  name: build-app
spec:
  steps:
    # Step 1: 소스코드 확인
    - name: check-source
      image: ubuntu
      command: ["ls", "-la"]

    # Step 2: 빌드 실행
    - name: build
      image: maven
      command: ["mvn", "clean", "install"]

    # Step 3: 결과 확인
    - name: check-result
      image: ubuntu
      command: ["ls", "-la", "target/"]

```

**이게 뭐냐면:**
- Step 1 → Step 2 → Step 3 순서로 실행
- 각 Step은 독립적인 컨테이너에서 실행
- 한 Step이 실패하면 다음 Step은 실행되지 않음

→ Jenkins의 "stage"와 비슷한 개념


### 3. Pipeline (Task들의 조합)

**"여러 Task를 조합해서 전체 워크플로우 정의"**

```yaml
apiVersion: tekton.dev/v1beta1
kind: Pipeline
metadata:
  name: my-cicd-pipeline
spec:
  tasks:
    # Task 1: 소스코드 가져오기
    - name: fetch-source
      taskRef:
        name: git-clone

    # Task 2: 빌드 (Task 1이 끝난 후)
    - name: build
      taskRef:
        name: build-app
      runAfter:
        - fetch-source

    # Task 3: 테스트 (Task 2와 병렬 실행 가능)
    - name: test
      taskRef:
        name: run-tests
      runAfter:
        - fetch-source

    # Task 4: 배포 (Task 2, 3 모두 끝난 후)
    - name: deploy
      taskRef:
        name: deploy-app
      runAfter:
        - build
        - test

```

**이게 뭐냐면:**
- 여러 Task를 연결
- `runAfter`로 실행 순서 제어
- 병렬 실행도 가능

→ Jenkins의 "pipeline"과 동일한 개념


### 4. PipelineRun (실제 실행)

**"지금 당장 Pipeline을 실행해!"**

```yaml
apiVersion: tekton.dev/v1beta1
kind: PipelineRun
metadata:
  name: my-cicd-run-1
spec:
  pipelineRef:
    name: my-cicd-pipeline

```

**이게 뭐냐면:**
- `my-cicd-pipeline`을 지금 실행
- 실행할 때마다 새로운 PipelineRun 생성
- 각 실행은 독립적인 기록으로 남음

→ Jenkins의 "Build Now" 버튼과 동일


## 🎯 전체 구조 정리 (머릿속에 그림 그리기)

```

┌─────────────────────────────────────────────────────┐
│              PipelineRun (실행 명령)                 │
│                      ↓                              │
│              Pipeline (전체 워크플로우)               │
│         ┌──────────┬──────────┬──────────┐          │
│         │  Task 1  │  Task 2  │  Task 3  │          │
│         └──────────┴──────────┴──────────┘          │
│              ↓          ↓          ↓                │
│         ┌────────┐ ┌────────┐ ┌────────┐           │
│         │ Step 1 │ │ Step 1 │ │ Step 1 │           │
│         │ Step 2 │ │ Step 2 │ │ Step 2 │           │
│         │ Step 3 │ │ Step 3 │ │ Step 3 │           │
│         └────────┘ └────────┘ └────────┘           │
│              ↓          ↓          ↓                │
│         (Pod 생성) (Pod 생성) (Pod 생성)            │
└─────────────────────────────────────────────────────┘

```

**핵심 원리:**
1. Pipeline을 YAML로 정의 (선언적)
2. PipelineRun을 생성하면 실행 시작
3. 각 Task마다 새로운 Pod가 생성됨
4. Step들이 순차적으로 실행
5. 완료되면 Pod는 자동 삭제 (또는 보관)


## 🛠️ Tekton 설치 (실습 준비)

### 1단계: Tekton Pipelines 설치

```bash
# Tekton Pipelines 설치 (핵심 컴포넌트)
kubectl apply -f https://storage.googleapis.com/tekton-releases/pipeline/latest/release.yaml

# 설치 확인
kubectl get pods -n tekton-pipelines

```

**예상 결과:**
```

NAME                                           READY   STATUS    RESTARTS   AGE
tekton-pipelines-controller-xxxxx              1/1     Running   0          1m
tekton-pipelines-webhook-xxxxx                 1/1     Running   0          1m

```

모든 Pod가 `Running` 상태가 되어야 한다.


### 2단계: Tekton Dashboard 설치 (웹 UI)

```bash
# Dashboard 설치
kubectl apply -f https://storage.googleapis.com/tekton-releases/dashboard/latest/release.yaml

# NodePort로 변경 (외부 접속 가능하게)
kubectl patch svc tekton-dashboard -n tekton-pipelines \
  -p '{"spec":{"type":"NodePort","ports":[{"port":9097,"nodePort":32090}]}}'

# 접속 확인
kubectl get svc -n tekton-pipelines

```

**웹 브라우저 접속:**
```

http://<Kubernetes-Node-IP>:32090

```

예시: `http://<본인의-클러스터-IP>:32090`


### 3단계: Tekton CLI 설치 (선택사항)

CLI가 있으면 훨씬 편하다.

```bash
# Linux 설치
curl -LO https://github.com/tektoncd/cli/releases/download/v0.35.0/tkn_0.35.0_Linux_x86_64.tar.gz
sudo tar xvzf tkn_0.35.0_Linux_x86_64.tar.gz -C /usr/local/bin/ tkn

# 설치 확인
tkn version

```

**유용한 명령어:**
```bash
tkn pipeline list           # Pipeline 목록
tkn pipelinerun list        # 실행 기록
tkn pipelinerun logs -f     # 실시간 로그
tkn task list               # Task 목록

```


## 🎓 첫 번째 실습: Hello World

이론만 보면 헷갈린다. 직접 실행해보자.

### 실습 디렉토리 생성

```bash
mkdir -p ~/test/tekton-practice
cd ~/test/tekton-practice

```

### 파일 1: hello-task.yaml

```yaml
apiVersion: tekton.dev/v1beta1
kind: Task
metadata:
  name: hello-task
spec:
  steps:
    - name: say-hello
      image: ubuntu
      command:
        - echo
      args:
        - "안녕하세요! Tekton 첫 실습입니다!"

    - name: show-date
      image: ubuntu
      command:
        - date

    - name: show-hostname
      image: ubuntu
      command:
        - hostname

```

**이 Task는:**
- Step 1: 인사 메시지 출력
- Step 2: 현재 시간 출력
- Step 3: 컨테이너 hostname 출력

→ 3개의 Step이 순서대로 실행된다.


### 파일 2: hello-taskrun.yaml

```yaml
apiVersion: tekton.dev/v1beta1
kind: TaskRun
metadata:
  name: hello-taskrun
spec:
  taskRef:
    name: hello-task

```

**이 TaskRun은:**
- `hello-task`를 실행하라는 명령


### 실행해보기

```bash
# 1. Task 등록
kubectl apply -f hello-task.yaml

# 2. Task 확인
kubectl get tasks

# 3. Task 실행
kubectl apply -f hello-taskrun.yaml

# 4. 실행 상태 확인
kubectl get taskruns

# 5. 로그 확인
kubectl logs -l tekton.dev/taskRun=hello-taskrun --all-containers

```

**예상 출력:**
```

안녕하세요! Tekton 첫 실습입니다!
Sun Nov 17 12:00:00 UTC 2025
hello-taskrun-pod-xxxxx

```


## 🤔 생각해볼 점 (스스로 답해보기)

### 질문 1: Pod는 언제 생성되나?
TaskRun을 apply한 순간? Task를 apply한 순간?

<details>
<summary>정답 보기</summary>

**TaskRun을 생성한 순간**
Task는 "설계도"일 뿐이고, TaskRun이 "실제 실행 명령"이다.
</details>

### 질문 2: 같은 Task를 3번 실행하면 어떻게 되나?
Pod가 3개 생성될까? 아니면 하나를 재사용할까?

<details>
<summary>정답 보기</summary>

**Pod가 3개 생성된다**
각 실행마다 독립적인 Pod가 생성되어 실행된다.
이게 Tekton의 핵심 철학: "Stateless하고 재현 가능한 실행"
</details>

### 질문 3: Step 2가 실패하면 Step 3는 실행될까?

<details>
<summary>정답 보기</summary>

**실행되지 않는다**
Step은 순차적으로 실행되며, 하나라도 실패하면 중단된다.
</details>


## 📌 다음 단계 예고

이제 기본 개념은 이해했다.
다음에는 실제로 **Docker 이미지 빌드**를 해볼 것이다.

**다음 글 주제:**
1. Git에서 소스코드 가져오기
2. Docker 이미지 빌드
3. Docker Hub에 푸시
4. Jenkins와 비교

→ 내가 이미 Jenkins로 구현한 `myapp` 빌드를
Tekton으로 똑같이 해볼 것이다.


## 🎯 학습 체크리스트

스스로 아래 질문에 답할 수 있으면 이 글을 이해한 것이다:

- [ ] Tekton의 4가지 핵심 리소스를 설명할 수 있는가?
- [ ] Task와 Pipeline의 차이를 설명할 수 있는가?
- [ ] TaskRun은 왜 필요한가?
- [ ] Jenkins와 Tekton의 근본적인 차이는?
- [ ] 왜 각 Task마다 새로운 Pod가 생성되는가?


## 🔒 보안 주의사항

블로그에 글을 올릴 때 주의해야 할 점:

### 절대 공개하면 안 되는 정보
- ❌ 실제 IP 주소 (192.168.x.x 등)
- ❌ 비밀번호, 토큰
- ❌ Docker Hub / GitHub 실제 계정명
- ❌ Secret, Credential ID

### 예제 작성 시

```yaml
# 나쁜 예
password: "jenkinsadmin123"
image: jjmin/myapp:1

# 좋은 예
password: "your-secure-password"
image: your-dockerhub-username/myapp:1

```

**실습할 때는 본인의 실제 정보를 사용하되,
블로그에는 일반화된 예제로 작성하자!**


## 💭 마치며

처음엔 "Jenkins로 충분한데 왜 Tekton을 배우지?" 싶었다.
하지만 공부해보니 **아키텍처 철학이 완전히 다르다**는 걸 알았다.

Jenkins: "중앙화된 서버 방식" (전통적)
Tekton: "분산되고 선언적인 방식" (클라우드 네이티브)

어느 게 더 좋다기보다, **상황에 맞게 선택**하는 게 중요하다.
내가 이해하면서 배우는 이유도 그것이다.

다음 글에서는 실제로 **코드를 빌드하고 배포**해볼 것이다.
천천히, 하나씩 이해하면서.
