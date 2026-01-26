---
title: "블로그 docker 구축 환경으로 부터온 트러블 슈팅 feat 글자이슈"
date: 2025-11-19T15:50:53+09:00
draft: false
categories: ["study", "Development"]
tags: ["docker_dev","trouble shooting","font","css","public","error"]
description: "블로그 docker 구축 환경으로 부터온 트러블 슈팅 feat 글자이슈"
author: "늦찌민"
---

# Hugo 블로그 트러블슈팅: 한글 폰트 깨짐 & Docker 배포 이슈 해결

## 발생한 문제

### 1. 한글 코드 블록 글자 겹침 현상
- 코드 블록 내 한글이 겹쳐서 읽을 수 없음
- ASCII 아트 다이어그램의 한글이 깨져서 표시됨

### 2. 최신 블로그 글(11월 18-19일)이 외부 웹사이트에 표시되지 않음

---

## 문제 1: 한글 코드 블록 글자 겹침

### 증상

코드 블록에 한글을 입력하면 다음과 같이 표시됨:

```

이후 (After)

人蔗‍
  ↓
Ingress (80/443)
  ↓
Kubernetes Service

```

글자가 완전히 겹쳐서 읽을 수 없는 상태.

### 원인 분석

#### 1차 원인: Hugo GoAT (Go ASCII Tool) 다이어그램 자동 변환

Hugo v0.93.0+부터 도입된 기능:
- 코드 블록의 ASCII 아트를 **자동으로 SVG 다이어그램으로 변환**
- 영어는 정상 작동하지만, **한글 문자를 개별 SVG 텍스트 요소로 쪼개면서 배치가 깨짐**
- 각 글자가 겹쳐서 렌더링됨

**문제가 되는 코드:**
```markdown
```

[1단계] 테스트 환경 구축
   ↓
[2단계] 검증

```
```

Hugo가 이를 GoAT으로 인식 → SVG 변환 → 한글 깨짐

#### 2차 원인: 한글 Monospace 폰트 미지정

- CSS에 한글을 지원하는 고정폭(monospace) 폰트가 없음
- 시스템 기본 폰트 사용으로 인한 글자 간격 불일치
- `letter-spacing`, `word-spacing` 등 미조정

### 해결 방법

#### Step 1: GoAT 다이어그램 비활성화

Hugo의 GoAT 렌더 훅을 오버라이드하여 일반 코드 블록으로 렌더링:

**파일 생성:** `layouts/_default/_markup/render-codeblock-goat.html`

```html
{{/* GoAT 다이어그램을 일반 코드 블록으로 렌더링 (한글 깨짐 방지) */}}
<div class="highlight">
  <pre tabindex="0" style="color:#f8f8f2;background-color:#272822;-moz-tab-size:4;-o-tab-size:4;tab-size:4;"><code>{{ .Inner }}</code></pre>
</div>

```

#### Step 2: 한글 코드 블록 폰트 설정

**파일 생성:** `assets/css/extended/custom.css`

```css
/* 한글 코드 블록 폰트 수정 */
code, pre, pre code, .highlight pre, .highlight code {
    font-family: 'Nanum Gothic Coding', 'Noto Sans Mono', 'Noto Sans Mono CJK KR',
                 'D2Coding', 'Consolas', 'Monaco', 'Courier New', monospace !important;
    letter-spacing: 0 !important;
    word-spacing: 0 !important;
    font-feature-settings: normal !important;
}

/* 한글이 포함된 코드 블록의 줄 높이 조정 */
.highlight pre code {
    line-height: 1.7 !important;
    font-size: 0.95em !important;
}

/* 일반 텍스트와 코드의 간격 조정 */
pre {
    line-height: 1.7 !important;
    overflow-x: auto;
}

/* 코드 블록 내부 간격 조정 */
.highlight pre {
    padding: 1em !important;
}

/* 인라인 코드 */
code {
    font-size: 0.9em !important;
}
```

#### Step 3: Google Fonts 웹폰트 로드

시스템에 폰트가 없어도 웹폰트로 fallback 가능하도록 설정:

**파일 생성:** `layouts/partials/extend_head.html`

```html
<!-- Google Fonts - 한글 코딩 폰트 -->
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Noto+Sans+Mono:wght@400;700&display=swap" rel="stylesheet">

<!-- 나눔고딕코딩 폰트 (한글 코드용) -->
<link href="https://fonts.googleapis.com/css2?family=Nanum+Gothic+Coding:wght@400;700&display=swap" rel="stylesheet">

```

#### Step 4: Hugo 설정 업데이트 (선택사항)

**파일 수정:** `config.toml`

```toml
[markup]
  [markup.goldmark]
    [markup.goldmark.renderer]
      unsafe = true
    [markup.goldmark.parser]
      [markup.goldmark.parser.attribute]
        block = true
        title = true
  [markup.highlight]
    noClasses = false
  # GoAT 다이어그램 비활성화 (한글 깨짐 방지)
  [markup.goldmark.extensions]
    [markup.goldmark.extensions.passthrough]
      enable = false

```

### 결과

✅ 한글 코드 블록이 깔끔하게 표시됨
✅ ASCII 아트 다이어그램도 정상 렌더링
✅ 모든 브라우저에서 일관된 폰트 적용

---

## 문제 2: 최신 글이 외부 웹사이트에 표시되지 않음

### 증상

- 로컬 `public/` 디렉토리: 11월 18-19일 글 존재 ✅
- 외부 웹사이트 (https://blog.jiminhome.shop): 11월 17일까지만 표시 ❌

### 원인 분석

```

로컬 소스              빌드 결과            Docker               Nginx 서버            외부 접속
────────────────────────────────────────────────────────────────────────────────────────────
content/study/        public/study/        hugo-test           /var/www/blog/        https://blog.jiminhome.shop
2025-11-18 ✅  ─hugo→  2025-11-18 ✅  ───X─→ (21시간 전 이미지) ───X─→ 2025-11-17까지만 ───→ 11/17까지만 표시
2025-11-19 ✅         2025-11-19 ✅                                    2025-11-17까지만

```

**문제의 흐름:**

1. ✅ 마크다운 파일은 작성됨 (`content/study/`)
2. ✅ Hugo 빌드는 정상 완료 (`public/study/`)
3. ❌ Docker 컨테이너 `hugo-test`가 **21시간 전에 빌드된 오래된 이미지** 사용 중
4. ❌ Nginx는 `/var/www/blog/`를 서빙하는데 **최신 빌드가 배포되지 않음**

### 진단 과정

#### 1. 로컬 빌드 확인

```bash
ls /home/jimin/blogsite/public/study/

```

**결과:** 2025-11-18, 2025-11-19 디렉토리 존재 ✅

#### 2. Docker 컨테이너 확인

```bash
docker ps | grep hugo

```

**결과:**
```

hugo-test   21 hours ago   Up 21 hours   0.0.0.0:1313->80/tcp

```

❌ 21시간 전에 시작된 컨테이너가 오래된 이미지 사용 중

#### 3. 실제 서비스 확인

```bash
curl -s https://blog.jiminhome.shop/study/ | grep -o "2025-11-[0-9][0-9]" | sort -u

```

**결과:**
```

2025-11-05
2025-11-06
2025-11-07
2025-11-08
2025-11-17

```

❌ 11월 18일, 19일 없음

#### 4. Nginx 문서 루트 확인

```bash
sudo nginx -T | grep -B 2 -A 5 "blog.jiminhome"

```

**결과:**
```nginx
server {
    listen 443 ssl http2;
    server_name blog.jiminhome.shop;
    root /var/www/blog;
    ...
}
```

#### 5. Nginx 서버 디렉토리 확인

```bash
ls /var/www/blog/study/ | grep "2025-11-1[89]"

```

**결과:** 없음 ❌

### 해결 과정

#### Step 1: Docker 컨테이너 제거

더 이상 컨테이너화 방식을 사용하지 않기로 결정:

```bash
# 컨테이너 중지 및 삭제
docker stop hugo-test
docker rm hugo-test

# 이미지 삭제
docker rmi hugo-blog:test

```

**이유:**
- 오래된 이미지가 계속 실행되면서 최신 콘텐츠 반영 안 됨
- 매번 이미지 재빌드/재배포가 번거로움
- Nginx 직접 서빙이 더 간단하고 즉각적

#### Step 2: 최신 빌드 생성

캐시를 완전히 삭제하고 새로 빌드:

```bash
cd ~/blogsite
sudo rm -rf public/ resources/
hugo

```

**결과:**
```

Pages            | 569
Paginator pages  |  10
Total in 494 ms

```

#### Step 3: Nginx 서버로 배포

```bash
sudo rsync -av --delete /home/jimin/blogsite/public/ /var/www/blog/
sudo systemctl reload nginx

```

**`rsync` 옵션 설명:**
- `-a`: archive 모드 (권한, 타임스탬프 유지)
- `-v`: verbose (진행 상황 표시)
- `--delete`: 대상에만 있는 파일 삭제 (동기화)

#### Step 4: 검증

```bash
# 파일 존재 확인
ls /var/www/blog/study/ | grep "2025-11-1[89]"

```

**결과:**
```

2025-11-18t193030+0900-kubernetes-+-longhorn-+-vmware-worker-환경에서-pvc가-계속-망가지는-문제-해결
2025-11-18t195454+0900-longhorn-트러블-슈팅-정리-가이드
2025-11-19t151012+0900-pod-트러블-슈팅-뿌시기-feat.pvc

```

✅ 파일 존재

```bash
# 웹사이트 확인
curl -s https://blog.jiminhome.shop/study/ | grep "11월 19일"

```

**결과:**
```html
<span title='2025-11-19 15:10:12 +0900 KST'>2025년 11월 19일</span>

```

✅ 정상 표시

### 브라우저 캐시 이슈

서버는 최신 콘텐츠를 서빙하고 있지만 브라우저에서 안 보이는 경우:

**원인:** 브라우저의 로컬 캐시

**해결방법:**
1. **강력 새로고침**:
   - Windows/Linux: `Ctrl + Shift + R` 또는 `Ctrl + F5`
   - Mac: `Cmd + Shift + R`

2. **브라우저 캐시 삭제**:
   - Chrome: `Ctrl + Shift + Delete` → "캐시된 이미지 및 파일" 삭제

3. **시크릿 모드**:
   - `Ctrl + Shift + N` (Chrome)
   - 캐시 없이 새로 로드됨

---

## 배포 프로세스 변경

### Before (문제 있음)

```

1. Hugo 빌드 (~/blogsite/public/)
2. Docker 이미지 빌드 (hugo-blog:test)
3. Docker 컨테이너 실행 (hugo-test)
   └─ 문제: 이미지 재빌드 안 하면 오래된 콘텐츠 계속 서빙

```

### After (개선됨)

```

1. Hugo 빌드 (~/blogsite/public/)
2. Nginx로 직접 배포 (rsync)
3. Nginx 리로드
   └─ 장점: 즉각 반영, 간단한 프로세스

```

---

## 최종 배포 워크플로우

### 새 글 작성 후 배포

```bash
# 1. Hugo 빌드
cd ~/blogsite
hugo

# 2. Nginx 서버로 배포
sudo rsync -av --delete public/ /var/www/blog/

# 3. Nginx 리로드
sudo systemctl reload nginx

```

### 자동화 스크립트 (선택사항)

`~/blogsite/deploy.sh` 파일 생성:

```bash
#!/bin/bash

echo "🚀 Hugo 블로그 배포 시작..."

# Hugo 빌드
echo "📦 1. Hugo 빌드 중..."
cd ~/blogsite
hugo

if [ $? -ne 0 ]; then
    echo "❌ Hugo 빌드 실패"
    exit 1
fi

# Nginx로 배포
echo "📤 2. Nginx 서버로 배포 중..."
sudo rsync -av --delete public/ /var/www/blog/

# Nginx 리로드
echo "🔄 3. Nginx 리로드 중..."
sudo systemctl reload nginx

echo "✅ 배포 완료!"
echo "🌐 https://blog.jiminhome.shop 확인"

```

실행:

```bash
chmod +x ~/blogsite/deploy.sh
~/blogsite/deploy.sh

```

---

## 변경된 파일 목록

| 파일 경로 | 목적 | 상태 |
|-----------|------|------|
| `layouts/_default/_markup/render-codeblock-goat.html` | GoAT 다이어그램 비활성화 | 신규 생성 |
| `assets/css/extended/custom.css` | 한글 폰트 설정 | 신규 생성 |
| `layouts/partials/extend_head.html` | 웹폰트 로드 | 신규 생성 |
| `config.toml` | markup 설정 추가 | 수정 |

---

## 교훈 및 Best Practices

### 1. Hugo의 자동 기능 주의
- GoAT 같은 자동 변환 기능은 **다국어(특히 한글) 환경에서 검증 필요**
- 문제 발생 시 렌더 훅 오버라이드로 해결 가능

### 2. 한글 웹사이트 폰트 설정
- **명시적으로 한글 지원 폰트 지정** 필요
- 특히 `monospace` 폰트는 한글 지원 확인 필수
- 웹폰트로 fallback 제공

### 3. 컨테이너 배포 주의사항
- Docker 사용 시 **이미지 갱신 자동화** 필요
- 간단한 정적 사이트는 **직접 서빙이 더 효율적**일 수 있음
- 불필요한 중간 단계는 복잡도만 증가

### 4. 배포 파이프라인 설계

```

복잡함                             간단함
Docker → K8s → ...    vs.    Hugo → Nginx
(대규모, 자동화 필요)           (소규모, 빠른 반영)

```

### 5. 캐시 관리
- **다층 캐시 구조** 이해 필요:
  - 브라우저 캐시
  - CDN 캐시 (Cloudflare 등)
  - 서버 캐시 (Nginx 등)
- 문제 진단 시 각 계층별 확인

---

## 참고 자료

- [Hugo Diagrams - GoAT](https://gohugo.io/content-management/diagrams/)
- [Hugo Render Hooks](https://gohugo.io/templates/render-hooks/)
- [Google Fonts - Nanum Gothic Coding](https://fonts.google.com/specimen/Nanum+Gothic+Coding)
- [rsync 사용법](https://linux.die.net/man/1/rsync)

