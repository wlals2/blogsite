---
title: "Docker BuildKit 캐시가 작동하는 원리와 홈랩 CI/CD 최적화 실전기"
date: 2026-03-09T10:00:00+09:00
draft: false
categories: ["study", "Development"]
tags: ["Docker", "BuildKit", "CI/CD", "GitHub Actions", "Self-hosted Runner", "캐시 최적화"]
summary: "docker build가 왜 어떤 날은 빠르고 어떤 날은 느린지 — BuildKit 레이어 캐시 원리부터 홈랩 self-hosted runner에서 로컬 디스크 캐시로 빌드 시간을 줄이기까지"
---

## 배경

Hugo 블로그 콘텐츠를 push하면 GitHub Actions가 자동으로 Docker 이미지를 빌드하고 Kubernetes에 배포한다. 그런데 글 하나 올릴 때마다 6분을 기다려야 했다.

처음에는 "Hugo가 1,000개 넘는 글을 빌드하니까 느린 거 아닐까" 싶었다. 실제 로그를 확인해보니 Hugo 빌드는 **3.7초**였다. 6분의 진짜 원인은 `hugomods/hugo:0.146.0` 이미지를 매 빌드마다 인터넷에서 새로 다운로드하는 것이었다.

Docker 캐시를 설정했는데도 왜 매번 다운로드가 일어났는지 — 원인을 파악하려면 BuildKit 캐시가 어떻게 동작하는지부터 이해해야 한다.

---

## Docker BuildKit 캐시의 동작 원리

### 레이어 캐시: Dockerfile 한 줄 = 캐시 하나

Docker는 Dockerfile의 각 명령어(`FROM`, `RUN`, `COPY` 등)를 **레이어**로 변환한다. BuildKit은 이전 빌드와 동일한 레이어가 있으면 다시 실행하지 않고 캐시를 그대로 쓴다.

```dockerfile
FROM hugomods/hugo:0.146.0 AS builder   # 레이어 1: 이미지 pull
ARG GIT_COMMIT=unknown
RUN echo "Building from commit: $GIT_COMMIT"  # 레이어 2: SHA 출력
WORKDIR /src
COPY . .                                 # 레이어 3: 소스 복사
RUN hugo --minify --gc                   # 레이어 4: Hugo 빌드
```

캐시 무효화 규칙은 단순하다: **한 레이어가 바뀌면 그 이후 모든 레이어 캐시가 무효화된다.**

```
레이어 1 (FROM)   → 캐시 HIT  ✅
레이어 2 (ARG)    → GIT_COMMIT이 매 빌드마다 다름 → 캐시 MISS ❌
레이어 3 (COPY)   → 이전 레이어가 MISS → 자동 MISS ❌
레이어 4 (hugo)   → 자동 MISS ❌ → 항상 Hugo 빌드 실행
```

`GIT_COMMIT` ARG가 `COPY . .` 앞에 있어서 매 빌드마다 Hugo 이미지 레이어 이후 모든 것이 재실행되고 있었다.

### BuildKit 캐시 저장 방식: inline vs local vs registry

기본 `docker build`는 레이어 캐시를 **로컬 daemon**에만 저장한다. CI/CD 환경에서는 Runner가 재시작되거나 교체되면 이 캐시가 사라진다.

BuildKit은 캐시를 외부로 내보내는 `cache-to` / `cache-from` 옵션을 제공한다:

| 방식 | 저장 위치 | 특징 |
|------|----------|------|
| `type=inline` | 이미지 레이어 안에 포함 | 별도 저장 불필요, 제한적 |
| `type=gha` | GitHub Actions 캐시 서버 | GitHub-hosted runner 최적, self-hosted에서 느림 |
| `type=local` | 로컬 디렉토리 | self-hosted runner에서 가장 빠름 |
| `type=registry` | 컨테이너 레지스트리 | runner 재시작 후에도 유지, 네트워크 왕복 발생 |

---

## 홈랩 환경에서의 선택: type=local

홈랩 self-hosted runner는 `k8s-cp` 노드(베어메탈) 위에서 동작한다. `type=gha`를 쓰면 캐시를 GitHub 서버에 올렸다가 다시 내려받아야 해서 오히려 20분이 걸린다는 문제가 있었다. `type=local`로 runner와 같은 머신의 영구 디스크를 쓰는 게 합리적인 선택이었다.

```yaml
# deploy-web.yml
- name: Build and push Docker image
  uses: docker/build-push-action@v5
  with:
    cache-from: type=local,src=/mnt/data/ci/cache/buildkit-web
    cache-to: type=local,dest=/mnt/data/ci/cache/buildkit-web,mode=max
```

`/mnt/data`는 재부팅 후에도 유지되는 916GB 영구 디스크다. `/tmp`는 재부팅 시 초기화되므로 사용하지 않는다.

현재 캐시 현황:

```bash
$ du -sh /mnt/data/ci/cache/buildkit-was/
616M    /mnt/data/ci/cache/buildkit-was/

$ du -sh /mnt/data/ci/cache/buildkit-web/
4.0K    /mnt/data/ci/cache/buildkit-web/   # WEB 캐시: 아직 초기화 상태
```

WAS 캐시는 616MB가 정상적으로 쌓여있고, WEB은 lock 충돌로 저장에 실패한 뒤 비어있는 상태다.

---

## 문제: BuildKit lock 충돌로 캐시 저장 실패

로그에서 발견한 실제 에러:

```
WARNING: local cache import at /mnt/data/ci/cache/buildkit-web not found
due to err: could not read index.json: no such file or directory

ERROR: (*service).Write failed: rpc error: code = Unavailable
desc = ref layer-sha256:... locked for ... unavailable
```

**원인**: 블로그 글을 연속으로 여러 번 push했을 때, 이전 빌드가 캐시를 디스크에 쓰는 도중에 새 빌드가 같은 폴더를 읽으려 하면서 lock 충돌이 발생한다.

결과적으로 캐시 저장 자체가 실패하고, 다음 빌드에서도 캐시가 없어 다시 처음부터 이미지를 다운로드하는 악순환이 반복됐다.

### 해결: concurrency 설정으로 동시 빌드 차단

```yaml
# deploy-web.yml, deploy-was.yml 양쪽에 추가
# Why: 새 push가 오면 이전 빌드를 취소하고 새 것만 실행
#      → 같은 캐시 폴더에 동시 접근하는 상황 자체를 차단
concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true
```

`group`은 같은 workflow + 같은 branch 조합으로 묶는다. 새 빌드가 시작되면 같은 그룹의 이전 빌드는 자동으로 취소된다.

---

## nginx:alpine 비고정 태그 문제

`hugomods/hugo:0.146.0`처럼 버전이 명시된 태그는 캐시가 안정적으로 유지된다. 그런데 `nginx:alpine`은 버전이 없는 **비고정 태그**다.

비고정 태그의 특성:
- Docker Hub에서 `nginx:alpine`이 가리키는 실제 이미지 digest는 언제든 바뀔 수 있다
- 로컬에 `nginx:alpine`이 캐시되어 있으면 Docker는 새 버전이 나와도 자동으로 확인하지 않는다
- 보안 패치가 포함된 새 버전이 나왔을 때 캐시 때문에 업데이트가 안 될 수 있다

이를 위해 빌드 전에 명시적 pull 단계를 추가했다:

```yaml
- name: Pull base images
  run: |
    # Why: 비고정 태그는 캐시가 있으면 Docker Hub를 확인하지 않음
    #      명시적 pull로 최신 보안 패치 포함된 이미지 보장
    docker pull nginx:alpine
```

`hugomods/hugo:0.146.0`은 버전이 고정되어 있어 Dockerfile이 변경될 때만 캐시가 무효화되므로 pull 불필요다.

---

## .dockerignore: 빌드 컨텍스트 최적화

`docker build` 시 BuildKit은 `context` 경로 전체를 Docker daemon으로 전송한다. 불필요한 파일이 포함되면 전송 시간이 늘어난다.

`.dockerignore` 추가 전후:

| 제외 대상 | 이유 |
|----------|------|
| `public/`, `resources/` | Hugo 빌드 출력물 (builder stage에서 새로 생성) |
| `.git/` | Git 메타데이터 (빌드에 불필요) |
| `docs/`, `.github/`, `.claude/` | 개발 문서 |
| `*.log`, `*.bak` | 임시 파일 |
| `node_modules/` | Hugo pipes 미사용 |

```
# .dockerignore
.git
public/
resources/
docs/
.github/
.claude/
*.sh
*.log
node_modules/
```

---

## Before / After

| 항목 | 개선 전 | 개선 후 |
|------|---------|--------|
| 첫 빌드 (캐시 없음) | 5분 28초 | 5분 28초 (동일, 캐시 생성) |
| 이후 빌드 (캐시 히트) | 5분 28초 (hugomods 170초 다운로드) | **2분 56초** (hugomods 0.0초 HIT) |
| hugomods 이미지 pull | 170초 | 0.0초 (캐시 HIT) |
| 캐시 lock 충돌 | 연속 push 시 발생 | concurrency로 차단 |
| 빌드 컨텍스트 크기 | 전체 repo | 필수 파일만 (.dockerignore) |

---

## 핵심 정리

1. **레이어 캐시 무효화는 전파된다** — 중간 레이어 하나가 바뀌면 이후 레이어는 전부 재실행
2. **self-hosted runner에서는 `type=local`이 최적** — GitHub 서버 왕복 없이 로컬 디스크 직접 접근
3. **비고정 태그는 명시적 pull로 보완** — `nginx:alpine` 같은 태그는 캐시가 보안 업데이트를 막을 수 있음
4. **동시 빌드는 로컬 캐시의 적** — `concurrency: cancel-in-progress: true`로 lock 충돌 원천 차단
5. **`.dockerignore`는 전송 비용을 줄인다** — 빌드와 무관한 파일을 제외해 빌드 컨텍스트 최소화
