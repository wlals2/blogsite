# 🎯 Tekton 환경 구축 가이드 (k3s + Tekton)

## 전제 조건

- Docker 설치됨
- 최소 2GB 메모리 여유

---

## 1단계: k3s 설치 (경량 Kubernetes)

```bash
# k3s 설치 (단일 노드)
curl -sfL https://get.k3s.io | sh -s - --write-kubeconfig-mode 644

# 확인
sudo k3s kubectl get nodes
```

**결과:**
```
NAME        STATUS   ROLES                  AGE   VERSION
localhost   Ready    control-plane,master   1m    v1.28.3+k3s1
```

---

## 2단계: kubectl 설정

```bash
# kubeconfig 복사
mkdir -p ~/.kube
sudo cp /etc/rancher/k3s/k3s.yaml ~/.kube/config
sudo chown $USER:$USER ~/.kube/config

# 확인
kubectl get nodes
```

---

## 3단계: Tekton 설치

```bash
# Tekton Pipelines 설치
kubectl apply -f \
  https://storage.googleapis.com/tekton-releases/pipeline/latest/release.yaml

# 설치 확인
kubectl get pods -n tekton-pipelines

# 모든 Pod가 Running이 될 때까지 대기 (1~2분)
kubectl wait --for=condition=ready pod --all -n tekton-pipelines --timeout=300s
```

**결과:**
```
NAME                                           READY   STATUS
tekton-pipelines-controller-7d8c9f8d9d-abc123  1/1     Running
tekton-pipelines-webhook-5f7b8c9d8f-def456     1/1     Running
```

---

## 4단계: Tekton CLI 설치

```bash
# Linux
curl -LO https://github.com/tektoncd/cli/releases/download/v0.32.0/tkn_0.32.0_Linux_x86_64.tar.gz
tar xvzf tkn_0.32.0_Linux_x86_64.tar.gz -C /usr/local/bin/ tkn

# 확인
tkn version
```

---

## 5단계: Hugo 빌드 Task 작성

`tekton/hugo-build-task.yaml`:

```yaml
apiVersion: tekton.dev/v1beta1
kind: Task
metadata:
  name: hugo-build
spec:
  workspaces:
    - name: source
      description: Hugo 소스 코드
  params:
    - name: hugoVersion
      type: string
      default: "0.111.3"
  steps:
    - name: build
      image: klakegg/hugo:$(params.hugoVersion)-ext-alpine
      workingDir: $(workspaces.source.path)
      script: |
        #!/bin/sh
        set -ex

        echo "====== Hugo 버전 확인 ======"
        hugo version

        echo "====== 소스 디렉토리 확인 ======"
        ls -la

        echo "====== Hugo 빌드 시작 ======"
        hugo --minify

        echo "====== 빌드 결과 확인 ======"
        ls -la public/
        echo "HTML 페이지: $(find public -name '*.html' | wc -l)개"
```

---

## 6단계: Git Clone Task (Tekton Hub에서)

```bash
# Tekton Hub의 git-clone Task 설치
kubectl apply -f \
  https://raw.githubusercontent.com/tektoncd/catalog/main/task/git-clone/0.9/git-clone.yaml
```

---

## 7단계: Pipeline 작성

`tekton/hugo-pipeline.yaml`:

```yaml
apiVersion: tekton.dev/v1beta1
kind: Pipeline
metadata:
  name: hugo-blog-pipeline
spec:
  params:
    - name: git-url
      type: string
      description: Git 저장소 URL
    - name: git-revision
      type: string
      default: main
      description: Git 브랜치
  workspaces:
    - name: shared-workspace
      description: 공유 작업 공간
  tasks:
    # 1. Git Clone
    - name: fetch-source
      taskRef:
        name: git-clone
      workspaces:
        - name: output
          workspace: shared-workspace
      params:
        - name: url
          value: $(params.git-url)
        - name: revision
          value: $(params.git-revision)

    # 2. Hugo Build
    - name: build
      taskRef:
        name: hugo-build
      workspaces:
        - name: source
          workspace: shared-workspace
      runAfter:
        - fetch-source
```

---

## 8단계: PipelineRun 실행

`tekton/hugo-pipelinerun.yaml`:

```yaml
apiVersion: tekton.dev/v1beta1
kind: PipelineRun
metadata:
  generateName: hugo-build-run-
spec:
  pipelineRef:
    name: hugo-blog-pipeline
  params:
    - name: git-url
      value: https://github.com/your/blog.git  # 실제 저장소로 변경
    - name: git-revision
      value: main
  workspaces:
    - name: shared-workspace
      volumeClaimTemplate:
        spec:
          accessModes:
            - ReadWriteOnce
          resources:
            requests:
              storage: 1Gi
```

---

## 9단계: 실행 및 확인

```bash
# 1. Task 생성
kubectl apply -f tekton/hugo-build-task.yaml

# 2. Pipeline 생성
kubectl apply -f tekton/hugo-pipeline.yaml

# 3. PipelineRun 실행
kubectl create -f tekton/hugo-pipelinerun.yaml

# 4. 로그 확인 (실시간)
tkn pipelinerun logs --last -f

# 5. 상태 확인
tkn pipelinerun list
kubectl get pipelineruns
```

---

## 10단계: Tekton Dashboard 설치 (선택)

```bash
# Dashboard 설치
kubectl apply -f \
  https://storage.googleapis.com/tekton-releases/dashboard/latest/release.yaml

# Port-forward로 접속
kubectl port-forward -n tekton-pipelines \
  svc/tekton-dashboard 9097:9097

# 브라우저 접속
# http://localhost:9097
```

---

## 🎓 사용 예시

### 빠른 빌드 실행

```bash
# CLI로 실행
tkn pipeline start hugo-blog-pipeline \
  --param git-url=https://github.com/your/blog.git \
  --param git-revision=main \
  --workspace name=shared-workspace,volumeClaimTemplateFile=pvc.yaml \
  --showlog
```

### 특정 PipelineRun 로그 확인

```bash
# 목록 확인
tkn pipelinerun list

# 로그 확인
tkn pipelinerun logs hugo-build-run-001 -f
```

### 실패한 빌드 디버깅

```bash
# Pod 확인
kubectl get pods

# Pod 로그
kubectl logs hugo-build-run-001-build-pod

# Pod 접속
kubectl exec -it hugo-build-run-001-build-pod -- sh
```

---

## 🧹 정리

### Tekton 제거

```bash
kubectl delete -f \
  https://storage.googleapis.com/tekton-releases/pipeline/latest/release.yaml
```

### k3s 완전 제거

```bash
/usr/local/bin/k3s-uninstall.sh
```

---

## 💡 Jenkins vs Tekton 비교 (실전)

| 항목 | Jenkins | Tekton |
|------|---------|--------|
| 설치 | Docker 한 줄 | k3s + Tekton |
| 메모리 | 500MB~1GB | 200MB |
| 빌드 실행 | Jenkins 내부 | Pod 생성/삭제 |
| 파이프라인 | Groovy | YAML |
| UI | 강력 | 기본적 |
| 디버깅 | 쉬움 | 어려움 |
| 격리 | 약함 | 강함 |

---

## 🚀 다음 단계

1. **Tekton Triggers**: Git push 시 자동 실행
2. **Argo CD**: GitOps로 자동 배포
3. **Multi-cluster**: 여러 클러스터 관리

---

**참고 자료:**
- Tekton 문서: https://tekton.dev/docs/
- Tekton Hub: https://hub.tekton.dev/
- k3s 문서: https://docs.k3s.io/
