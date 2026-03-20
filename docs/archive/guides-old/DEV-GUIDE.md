# 🛠️ Hugo 블로그 격리된 개발 환경 가이드

## 🎯 왜 필요한가?

로컬 환경에 Hugo를 직접 설치하지 않고, **Docker 컨테이너**로 완전히 격리된 환경에서 블로그를 빌드/테스트할 수 있습니다.

**장점:**
- ✅ 로컬 환경에 영향 없음
- ✅ Hugo 버전 관리 쉬움
- ✅ 어디서든 동일한 환경
- ✅ 문제 발생 시 컨테이너만 삭제하면 됨

---

## 🚀 빠른 시작

### 1. 개발 서버 (실시간 미리보기)

```bash
# Hugo 개발 서버 시작
docker compose -f docker-compose.dev.yml up hugo-dev
```

**브라우저 접속:** http://localhost:1313

**특징:**
- 파일 저장 시 자동 새로고침
- Draft 글도 표시됨
- 로컬 파일 변경 즉시 반영

**종료:** `Ctrl + C`

---

### 2. 프로덕션 빌드 테스트

#### Step 1: 빌드 실행

```bash
# Hugo 빌드 (public-test/ 폴더에 생성됨)
docker compose -f docker-compose.dev.yml run --rm hugo-build
```

#### Step 2: Nginx로 결과 확인

```bash
# Nginx 서버 시작
docker compose -f docker-compose.dev.yml up nginx-test
```

**브라우저 접속:** http://localhost:8080

**특징:**
- 실제 프로덕션과 동일한 빌드
- Nginx로 서빙 (실제 배포 환경과 유사)
- `public-test/` 폴더에 결과물 저장

---

### 3. 빠른 테스트 스크립트

```bash
# 대화형 메뉴
./test-dev-env.sh
```

**메뉴:**
1. Hugo 개발 서버
2. 프로덕션 빌드 + Nginx
3. 빌드만 실행
4. 전체 정리

---

## 📖 상세 사용법

### 개발 서버로 새 글 작성

```bash
# 1. 새 글 생성 (로컬에서)
hugo new posts/my-new-post.md

# 2. 개발 서버 시작
docker compose -f docker-compose.dev.yml up hugo-dev

# 3. 브라우저에서 http://localhost:1313 접속

# 4. 글 작성 (에디터로)
# 저장하면 브라우저 자동 새로고침!
```

### 프로덕션 빌드 확인

```bash
# 1. 빌드 실행
docker compose -f docker-compose.dev.yml run --rm hugo-build

# 2. 빌드 결과 확인
ls -la public-test/
find public-test -name '*.html' | wc -l

# 3. Nginx로 서빙
docker compose -f docker-compose.dev.yml up nginx-test

# 4. http://localhost:8080 접속
```

### 특정 버전의 Hugo 사용

`docker-compose.dev.yml` 파일 수정:

```yaml
services:
  hugo-dev:
    image: klakegg/hugo:0.111.3-ext-alpine  # 버전 변경
```

사용 가능한 버전: https://hub.docker.com/r/klakegg/hugo/tags

---

## 🔧 문제 해결

### 컨테이너가 안 떠요

```bash
# 컨테이너 상태 확인
docker ps -a

# 로그 확인
docker compose -f docker-compose.dev.yml logs hugo-dev

# 컨테이너 재시작
docker compose -f docker-compose.dev.yml restart
```

### 포트가 이미 사용 중이에요

```bash
# 1313 포트 사용 중인 프로세스 확인
sudo lsof -i :1313

# 또는 다른 포트 사용 (docker-compose.dev.yml 수정)
ports:
  - "3000:1313"  # 3000 포트로 변경
```

### 빌드가 안 돼요

```bash
# 컨테이너 안에서 직접 확인
docker compose -f docker-compose.dev.yml run --rm hugo-dev sh

# 컨테이너 내부에서
cd /src
hugo version
hugo --minify
```

### 이전 빌드 결과 삭제

```bash
# public-test 폴더 삭제
rm -rf public-test/

# 또는 스크립트 사용
./test-dev-env.sh
# → 메뉴 4번 선택
```

---

## 🧹 정리

### 모든 컨테이너 중지

```bash
docker compose -f docker-compose.dev.yml down
```

### 빌드 결과물 삭제

```bash
rm -rf public-test/
```

### Docker 이미지 삭제 (공간 확보)

```bash
docker rmi klakegg/hugo:0.111.3-ext-alpine
docker rmi nginx:alpine
```

---

## 🎓 다음 단계: Tekton 환경

### 1. k3s로 로컬 Kubernetes 클러스터

```bash
# k3s 설치 (경량 Kubernetes)
curl -sfL https://get.k3s.io | sh -

# 확인
sudo k3s kubectl get nodes
```

### 2. Tekton 설치

```bash
# Tekton Pipelines 설치
kubectl apply -f \
  https://storage.googleapis.com/tekton-releases/pipeline/latest/release.yaml

# Tekton Dashboard 설치
kubectl apply -f \
  https://storage.googleapis.com/tekton-releases/dashboard/latest/release.yaml
```

### 3. Hugo 빌드 Pipeline 작성

```yaml
# hugo-pipeline.yaml (예시)
apiVersion: tekton.dev/v1beta1
kind: Pipeline
metadata:
  name: hugo-build
spec:
  tasks:
    - name: git-clone
      taskRef:
        name: git-clone
    - name: hugo-build
      taskRef:
        name: hugo-build
```

---

## 💡 팁

### 개발 서버를 백그라운드로 실행

```bash
# 백그라운드 실행
docker compose -f docker-compose.dev.yml up -d hugo-dev

# 로그 확인
docker compose -f docker-compose.dev.yml logs -f hugo-dev

# 종료
docker compose -f docker-compose.dev.yml down
```

### 로컬 파일과 컨테이너 동기화 확인

```bash
# 컨테이너 내부 파일 확인
docker compose -f docker-compose.dev.yml run --rm hugo-dev ls -la /src

# 로컬 파일과 비교
ls -la .
```

### Hugo 버전 비교

```bash
# 로컬 Hugo 버전
hugo version

# 컨테이너 Hugo 버전
docker compose -f docker-compose.dev.yml run --rm hugo-dev hugo version
```

---

## 📚 참고 자료

- Hugo 공식 문서: https://gohugo.io/documentation/
- Hugo Docker 이미지: https://hub.docker.com/r/klakegg/hugo
- Docker Compose 문서: https://docs.docker.com/compose/
- Tekton 공식 문서: https://tekton.dev/docs/

---

**문제가 있으면 이슈 등록:** [GitHub Issues]
