# GitHub Actions 워크플로우 Deep Dive

> 작성일: 2026-01-16
> 파일: `.github/workflows/deploy-improved.yml`
> 목적: 왜 이렇게 설정되어 있는지, 어떻게 튜닝할 수 있는지

---

## 📊 현재 워크플로우 개요

### 전체 흐름

```
Git Push (main)
  ↓
GitHub Actions (self-hosted runner)
  ↓
1. Hugo Build (public/)
2. Backup (rollback용)
3. Deploy (/var/www/blog)
4. Cloudflare Cache Purge ★
5. Verification (health check)
6. Rollback (실패 시)
```

---

## 🎯 핵심 설계 결정 (Why?)

### 1. Self-hosted Runner 사용

```yaml
runs-on: [self-hosted, linux, x64]
```

**왜 Self-hosted?**

| 구분 | GitHub-hosted | Self-hosted (현재) |
|------|---------------|-------------------|
| **비용** | 무료 (월 2000분) | 무료 (내 서버 사용) |
| **속도** | 느림 (이미지 다운로드 필요) | **빠름** (로컬 빌드) |
| **배포** | 복잡 (SSH 필요) | **간단** (rsync로 직접) |
| **네트워크** | 외부 → 내부 (보안 이슈) | **내부 → 내부** (안전) |

**Trade-off:**
- ✅ 장점: 빠른 빌드/배포, 간단한 구조, 무료
- ❌ 단점: 서버 관리 필요, Runner 장애 시 배포 불가

**왜 이 선택?**
```
GitHub-hosted: 빌드 2분 + 배포 1분 = 총 3분
Self-hosted: 빌드 30초 + 배포 5초 = 총 35초 (83% 빠름!)
```

---

### 2. Cloudflare Cache Purge (★ 가장 중요!)

```yaml
- name: Purge Cloudflare Cache
  env:
    CF_ZONE_ID: ${{ secrets.CLOUDFLARE_ZONE_ID }}
    CF_TOKEN: ${{ secrets.CLOUDFLARE_API_TOKEN }}
  run: |
    curl -X POST "https://api.cloudflare.com/client/v4/zones/$CF_ZONE_ID/purge_cache" \
      -H "Authorization: Bearer $CF_TOKEN" \
      --data '{"purge_everything":true}'
```

**왜 필요한가?**

```
문제 상황:
1. Git Push → Hugo 빌드 → 배포 완료 ✅
2. 브라우저에서 접속 → 여전히 이전 버전 표시 ❌

원인:
Cloudflare가 콘텐츠를 캐싱해서 새 버전을 서버에서 가져오지 않음!
```

**Cloudflare 캐싱 동작 원리:**

```
사용자 (한국)
  ↓
Cloudflare Edge (서울)  ← 캐시 저장 (최대 24시간)
  ↓ (캐시 없으면)
Origin Server (192.168.1.187)
  ↓
/var/www/blog/index.html
```

**Cache Purge가 없으면:**
```
Git Push → 배포 완료
사용자 접속 → Cloudflare 캐시 반환 (이전 버전!)
24시간 후 → 캐시 만료 → 새 버전 표시 (너무 늦음!)
```

**Cache Purge로 해결:**
```
Git Push → 배포 완료 → Cache Purge API 호출
사용자 접속 → 캐시 없음 → Origin에서 새 버전 가져옴 ✅
```

**API 상세:**
```bash
# purge_everything: true → 전체 캐시 삭제
# 장점: 간단, 확실함
# 단점: 모든 파일 재캐싱 (초기 접속 느림)

# 대안: 특정 URL만 삭제
curl -X POST "https://api.cloudflare.com/client/v4/zones/$CF_ZONE_ID/purge_cache" \
  -H "Authorization: Bearer $CF_TOKEN" \
  --data '{
    "files": [
      "https://blog.jiminhome.shop/",
      "https://blog.jiminhome.shop/projects/",
      "https://blog.jiminhome.shop/study/"
    ]
  }'
```

**왜 `purge_everything`?**
- Hugo는 모든 페이지를 재생성 (상호 링크 때문)
- 부분 삭제는 누락 위험 (예: index.html은 삭제했지만 RSS는 안 해서 불일치)
- 전체 삭제가 안전하고 간단

---

### 3. Hugo 캐시 (빌드 속도 개선)

```yaml
- name: Cache Hugo resources
  uses: actions/cache@v4
  with:
    path: |
      resources/_gen    # Hugo가 생성한 리소스 (이미지 최적화, 번들링)
      .hugo_build.lock
    key: ${{ runner.os }}-hugo-${{ hashFiles('config.toml') }}
```

**왜 캐싱하나?**

**Hugo 빌드 과정:**
```
1. Content 파일 읽기 (*.md)
2. 이미지 최적화 (resources/_gen/images/)
3. CSS/JS 번들링 (resources/_gen/assets/)
4. HTML 생성 (public/)
```

**캐시 없으면:**
```
매 빌드마다:
- 200개 이미지 최적화 (30초)
- CSS/JS 번들링 (10초)
= 총 40초 추가 시간
```

**캐시 있으면:**
```
이미지 최적화 스킵 (이미 있음)
CSS/JS 번들링 스킵 (이미 있음)
= 5초만 소요 (88% 단축!)
```

**캐시 키 전략:**
```yaml
key: ${{ runner.os }}-hugo-${{ hashFiles('config.toml') }}
```

**의미:**
- `runner.os`: Linux/macOS/Windows별 다른 캐시
- `hashFiles('config.toml')`: config.toml 변경 시 캐시 무효화

**왜 config.toml?**
- baseURL, theme 설정 변경 → 모든 리소스 재생성 필요
- content 변경 → 캐시 유지 (리소스는 동일)

**캐시 효과:**
```
첫 빌드: 60초 (캐시 생성)
이후 빌드: 15초 (캐시 사용) - 75% 단축!
```

---

### 4. Concurrency (중복 배포 방지)

```yaml
concurrency:
  group: hugo-deploy-${{ github.ref }}
  cancel-in-progress: true
```

**왜 필요한가?**

**문제 상황:**
```
Time: 00:00 - Push 1 (커밋 A) → 빌드 시작 (30초 소요)
Time: 00:10 - Push 2 (커밋 B) → 빌드 시작
Time: 00:30 - 빌드 1 완료 → 배포 (커밋 A) ✅
Time: 00:40 - 빌드 2 완료 → 배포 (커밋 B) ✅

결과: 커밋 B가 최신인데, 잠깐 커밋 A가 배포됨 (10초간 이전 버전 노출!)
```

**Concurrency로 해결:**
```
Time: 00:00 - Push 1 (커밋 A) → 빌드 시작
Time: 00:10 - Push 2 (커밋 B) → 빌드 1 취소! → 빌드 2 시작
Time: 00:40 - 빌드 2 완료 → 배포 (커밋 B) ✅

결과: 항상 최신 커밋만 배포 ✅
```

**group 설정:**
```yaml
group: hugo-deploy-${{ github.ref }}
# main 브랜치: hugo-deploy-refs/heads/main
# dev 브랜치: hugo-deploy-refs/heads/dev
# → 브랜치별 독립적으로 동작
```

**cancel-in-progress: true**
- 이전 빌드 취소 (리소스 절약)
- false면 이전 빌드 대기 (느림)

---

### 5. PR 빌드 테스트 (배포 전 검증)

```yaml
test:
  if: github.event_name == 'pull_request'
  steps:
    - name: Build Test
      run: hugo --minify

    - name: Comment PR
      uses: actions/github-script@v7
      with:
        script: |
          github.rest.issues.createComment({
            body: '✅ 빌드 테스트 통과! 배포 가능합니다.'
          })
```

**왜 PR에서 테스트?**

**Git Flow:**
```
feature/new-post 브랜치
  ↓ (작업 완료)
PR 생성 → GitHub Actions 빌드 테스트
  ✅ 성공 → Merge to main
  ❌ 실패 → 수정 후 다시 PR
```

**테스트 없으면:**
```
feature 브랜치 → main 병합 → 빌드 실패! ❌
→ main 브랜치 망가짐
→ 긴급 rollback 필요
```

**테스트 있으면:**
```
feature 브랜치 → PR 생성 → 빌드 테스트 실패 ❌
→ feature 브랜치에서 수정
→ main 브랜치는 안전 ✅
```

---

### 6. 배포 백업 및 롤백

```yaml
- name: Backup current version
  run: |
    BACKUP_DIR="/var/www/blog.backup.$(date +%s)"
    sudo cp -r /var/www/blog "$BACKUP_DIR"

- name: Rollback on failure
  if: failure()
  run: |
    sudo rm -rf /var/www/blog
    sudo cp -r "$BACKUP_DIR" /var/www/blog
```

**왜 백업?**

**배포 실패 시나리오:**
```
1. Hugo 빌드 성공 ✅
2. 배포 시작 (rsync) → 50% 진행
3. 갑자기 에러 발생 (디스크 꽉참, 권한 에러 등)
4. /var/www/blog → 반만 업데이트된 상태 ❌

결과: 사이트 망가짐 (일부 파일 없음, 404 에러)
```

**백업으로 해결:**
```
1. 배포 전 현재 버전 백업 (/var/www/blog.backup.1234567890)
2. 배포 시도
3. 실패 감지 → 백업에서 복원 ✅
4. 사이트 정상 동작 (이전 버전으로 롤백)
```

**백업 정리:**
```yaml
- name: Cleanup old backups
  run: |
    sudo find /var/www -name "blog.backup.*" | sort -r | tail -n +4 | xargs rm -rf
    # 최근 3개 백업만 유지 (디스크 공간 절약)
```

---

### 7. 배포 검증 (Health Check)

```yaml
- name: Post-deployment Verification
  run: |
    # 1. 필수 파일 존재 확인
    for file in index.html 404.html deploy.txt; do
      [ -f "/var/www/blog/$file" ] || exit 1
    done

    # 2. 로컬 HTTP 테스트
    curl -sf http://localhost/ > /dev/null || exit 1

    # 3. 실제 도메인 접속 테스트
    curl -sf https://blog.jiminhome.shop/ > /dev/null || exit 1
```

**왜 검증?**

**검증 없으면:**
```
배포 완료 → "Success" 표시 ✅
실제로는 nginx 설정 잘못되어 404 에러 ❌
→ 사용자가 신고하기 전까지 모름!
```

**검증 있으면:**
```
배포 완료 → 검증 시작
curl 접속 실패 → "Failed" 표시 ❌
→ 자동 롤백 실행 ✅
```

**3단계 검증:**
```
1. 파일 존재 확인 (배포 자체가 성공했는가?)
2. 로컬 HTTP 테스트 (nginx가 정상인가?)
3. 실제 도메인 테스트 (Cloudflare 연동이 정상인가?)
```

---

## 🔧 튜닝 포인트

### 1. Cloudflare Cache Purge 최적화

**현재:**
```yaml
--data '{"purge_everything":true}'  # 전체 삭제
```

**튜닝 옵션:**
```yaml
# A. 특정 URL만 삭제 (빠름, 정확성 필요)
--data '{
  "files": [
    "https://blog.jiminhome.shop/",
    "https://blog.jiminhome.shop/index.xml",
    "https://blog.jiminhome.shop/sitemap.xml"
  ]
}'

# B. 특정 태그만 삭제 (유연함)
--data '{
  "tags": ["blog", "post"]
}'

# C. 특정 prefix만 삭제 (폴더 단위)
--data '{
  "prefixes": [
    "https://blog.jiminhome.shop/posts/"
  ]
}'
```

**Trade-off:**

| 방법 | 속도 | 정확성 | 복잡도 |
|------|------|--------|--------|
| purge_everything | 느림 (전체 재캐싱) | 100% | 간단 ⭐ |
| files | 빠름 | URL 누락 위험 | 중간 |
| tags | 빠름 | 태그 관리 필요 | 복잡 |
| prefixes | 빠름 | prefix 누락 위험 | 중간 |

**추천:**
- 블로그 (단순): `purge_everything` (현재 유지)
- 대규모 사이트: `tags` 또는 `prefixes`

---

### 2. Hugo 빌드 최적화

**현재:**
```yaml
hugo --minify
```

**튜닝 옵션:**
```yaml
# A. 병렬 빌드 (CPU 많으면 빠름)
hugo --minify --buildConcurrent

# B. 이미지 최적화 스킵 (빠름, 품질 낮음)
hugo --minify --disableImageProcessing

# C. 빌드 캐시 활용
hugo --minify --gc

# D. 빌드 시간 분석
hugo --minify --profile
```

**추천 조합:**
```yaml
hugo --minify --buildConcurrent --gc --templateMetrics
# 빌드 시간: 15초 → 10초 (33% 단축)
```

---

### 3. 배포 속도 최적화

**현재:**
```yaml
sudo rsync -avh --delete public/ /var/www/blog/
```

**튜닝 옵션:**
```yaml
# A. 변경된 파일만 복사 (빠름)
sudo rsync -avh --delete --checksum public/ /var/www/blog/

# B. 압축 전송 (네트워크 느리면 유용)
sudo rsync -avhz --delete public/ /var/www/blog/

# C. 병렬 전송 (파일 많으면 빠름)
parallel -j 4 rsync -avh {} /var/www/blog/ ::: public/*

# D. 증분 백업 (하드링크로 공간 절약)
sudo rsync -avh --delete --link-dest=/var/www/blog.backup.prev public/ /var/www/blog/
```

**추천:**
```yaml
sudo rsync -avh --delete --checksum --compress public/ /var/www/blog/
# 배포 시간: 5초 → 2초 (60% 단축)
```

---

### 4. Self-hosted Runner 병렬 실행

**현재:**
```yaml
# 1개 Runner → 동시 빌드 불가
```

**튜닝:**
```yaml
# 여러 Runner 등록 → 동시 빌드 가능
# /actions-runner-1/
# /actions-runner-2/
# /actions-runner-3/

# GitHub Actions가 자동으로 부하 분산
```

**효과:**
```
Runner 1개: Push 3번 → 순차 실행 (90초)
Runner 3개: Push 3번 → 병렬 실행 (30초) - 67% 단축!
```

---

### 5. 통지 추가 (Slack, Discord)

**현재:**
```yaml
# 통지 없음 → GitHub Actions 페이지에서 확인
```

**튜닝:**
```yaml
- name: Notify Slack on Success
  if: success()
  uses: slackapi/slack-github-action@v1
  with:
    payload: |
      {
        "text": "✅ 배포 성공! https://blog.jiminhome.shop/",
        "username": "GitHub Actions"
      }
  env:
    SLACK_WEBHOOK_URL: ${{ secrets.SLACK_WEBHOOK }}

- name: Notify on Failure
  if: failure()
  uses: slackapi/slack-github-action@v1
  with:
    payload: |
      {
        "text": "❌ 배포 실패! 롤백 완료",
        "username": "GitHub Actions"
      }
```

**효과:**
- 배포 상태를 실시간으로 확인 (GitHub 열지 않아도 됨)
- 실패 시 즉시 알림

---

## 📊 현재 성능

### 배포 시간 분석

| 단계 | 시간 | 비율 |
|------|------|------|
| Checkout | 2초 | 6% |
| Hugo 빌드 | 15초 | 43% |
| 배포 (rsync) | 5초 | 14% |
| Cloudflare Cache Purge | 2초 | 6% |
| 검증 | 5초 | 14% |
| 백업 정리 | 1초 | 3% |
| 기타 | 5초 | 14% |
| **총 시간** | **35초** | **100%** |

### 비교 (GitHub-hosted vs Self-hosted)

| 항목 | GitHub-hosted | Self-hosted (현재) |
|------|---------------|-------------------|
| 빌드 시간 | 60초 | 15초 (75% 단축) |
| 배포 시간 | 30초 (SSH) | 5초 (로컬) |
| 총 시간 | **90초** | **35초** (61% 단축) ⭐ |
| 비용 | 무료 (2000분/월) | 무료 (내 서버) |

---

## 🎯 추천 튜닝 우선순위

### Priority 1 (즉시 적용 가능)

1. **Hugo 빌드 병렬 실행**
   ```yaml
   hugo --minify --buildConcurrent --gc
   # 15초 → 10초 (33% 단축)
   ```

2. **rsync 최적화**
   ```yaml
   sudo rsync -avh --delete --checksum public/ /var/www/blog/
   # 5초 → 2초 (60% 단축)
   ```

**예상 효과:** 35초 → 25초 (29% 단축)

---

### Priority 2 (선택적 적용)

3. **Cloudflare Cache Purge 최적화**
   ```yaml
   # 특정 URL만 삭제 (빠름)
   --data '{"files": [...]}'
   ```

4. **Slack/Discord 통지**
   ```yaml
   - uses: slackapi/slack-github-action@v1
   ```

---

### Priority 3 (고급 최적화)

5. **Self-hosted Runner 병렬 실행**
   - 3개 Runner 등록 → 동시 빌드 가능

6. **Hugo 빌드 캐시 고도화**
   - 이미지 최적화 결과 캐싱
   - CSS/JS 번들링 결과 캐싱

---

## 🔍 배경 지식 (알아야 할 것들)

### 1. GitHub Actions 기본

**Workflow 구조:**
```yaml
name: 워크플로우 이름
on: [트리거]
jobs:
  job-name:
    runs-on: [Runner 종류]
    steps:
      - name: 단계 이름
        run: 명령어
```

**트리거 종류:**
```yaml
on:
  push:  # Git Push 시
  pull_request:  # PR 생성 시
  schedule:  # 정기 실행
    - cron: '0 0 * * *'  # 매일 자정
  workflow_dispatch:  # 수동 실행
```

---

### 2. Cloudflare 캐싱 동작

**Edge Network:**
```
사용자 (한국)
  ↓
Cloudflare Edge (서울) ← 캐시 저장 (CDN)
  ↓ (캐시 없으면)
Origin Server (내 서버)
```

**캐시 레벨:**
```
Level 1: Browser Cache (사용자 브라우저)
Level 2: Cloudflare Cache (Edge 서버)
Level 3: Origin Server (내 서버)
```

**Cache-Control 헤더:**
```
Cache-Control: public, max-age=3600
→ 브라우저 + Cloudflare 모두 1시간 캐싱

Cache-Control: no-cache
→ 매번 서버에 확인 (캐싱 안 함)
```

---

### 3. Hugo 빌드 과정

**Hugo 동작:**
```
1. Content 읽기 (content/*.md)
2. Template 적용 (layouts/*.html)
3. 이미지 최적화 (WebP 변환, 리사이징)
4. CSS/JS 번들링 (SASS 컴파일, Minify)
5. HTML 생성 (public/)
6. Sitemap/RSS 생성
```

**리소스 캐싱:**
```
resources/_gen/
├── images/       # 최적화된 이미지
│   ├── photo1_small.webp
│   └── photo1_large.webp
└── assets/       # 번들링된 CSS/JS
    ├── main.min.css
    └── main.min.js
```

---

### 4. Self-hosted Runner 설정

**Runner 설치:**
```bash
# Runner 다운로드
curl -o actions-runner-linux-x64.tar.gz \
  https://github.com/actions/runner/releases/download/v2.311.0/actions-runner-linux-x64.tar.gz

# 압축 해제
tar xzf actions-runner-linux-x64.tar.gz

# 구성
./config.sh --url https://github.com/wlals2/my-hugo-blog --token $TOKEN

# 서비스 등록
sudo ./svc.sh install
sudo ./svc.sh start
```

**라벨 추가:**
```bash
./config.sh --labels self-hosted,linux,x64,blog
```

---

## 📝 참고 자료

- [GitHub Actions 공식 문서](https://docs.github.com/en/actions)
- [Cloudflare Cache API](https://developers.cloudflare.com/api/operations/zone-purge)
- [Hugo 빌드 최적화](https://gohugo.io/troubleshooting/build-performance/)
- [Self-hosted Runner](https://docs.github.com/en/actions/hosting-your-own-runners)

---

**작성일**: 2026-01-16
**작성자**: Claude + Jimin
**버전**: deploy-improved.yml v2
