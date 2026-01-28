# D2 아키텍처 다이어그램 사용 가이드

> D2 (Declarative Diagramming) - 코드 기반 현대적 다이어그램 도구

**작성일**: 2026-01-24
**D2 버전**: v0.6+
**파일**: `05-ARCHITECTURE-D2.d2`

---

## 1. D2란?

**D2 (Declarative Diagramming)**는 Terrastruct에서 개발한 모던 다이어그램 도구입니다.

### Mermaid vs D2 비교

| 항목 | Mermaid | D2 |
|------|---------|-----|
| **렌더링** | 브라우저 자동 (Hugo, GitHub) | CLI로 SVG/PNG 생성 |
| **디자인** | 기본적 | ⭐ **매우 세련됨** |
| **스타일링** | 제한적 | 완전한 CSS 제어 |
| **레이아웃** | 자동 | 고급 레이아웃 엔진 (TALA) |
| **사용처** | 문서 내 삽입 | 고품질 이미지 내보내기 |
| **학습 곡선** | 쉬움 | 보통 |

**결론**:
- **Mermaid**: Hugo 블로그, GitHub README 직접 삽입용
- **D2**: 포트폴리오, 프레젠테이션용 고품질 이미지

---

## 2. 설치 방법

### Linux (Ubuntu/Debian)

```bash
# 1. D2 설치
curl -fsSL https://d2lang.com/install.sh | sh -s --

# 2. 설치 확인
d2 --version
# 예상 출력: v0.6.7

# 3. PATH 추가 (필요시)
echo 'export PATH="$HOME/.local/bin:$PATH"' >> ~/.bashrc
source ~/.bashrc
```

### macOS

```bash
# Homebrew로 설치
brew install d2

# 설치 확인
d2 --version
```

### Windows

```powershell
# Scoop으로 설치
scoop install d2

# 또는 수동 설치
# https://github.com/terrastruct/d2/releases
```

---

## 3. 사용 방법

### 기본 렌더링

```bash
# SVG 생성 (기본)
d2 /home/jimin/blogsite/docs/05-ARCHITECTURE-D2.d2 architecture.svg

# PNG 생성
d2 /home/jimin/blogsite/docs/05-ARCHITECTURE-D2.d2 architecture.png

# 고해상도 PNG (DPI 조정)
d2 --scale 2 /home/jimin/blogsite/docs/05-ARCHITECTURE-D2.d2 architecture@2x.png
```

### 테마 적용

D2는 다양한 테마를 제공합니다:

```bash
# 테마 목록 확인
d2 themes

# Dark 테마
d2 --theme=101 05-ARCHITECTURE-D2.d2 architecture-dark.svg

# Terminal 테마 (개발자 친화적)
d2 --theme=200 05-ARCHITECTURE-D2.d2 architecture-terminal.svg

# Grape Soda (보라색 계열)
d2 --theme=102 05-ARCHITECTURE-D2.d2 architecture-grape.svg
```

**추천 테마**:
- `101` (Dark) - 프레젠테이션용
- `0` (Neutral Default) - 범용
- `200` (Terminal) - 기술 문서용

### 레이아웃 엔진 선택

```bash
# TALA (기본, 고품질)
d2 --layout=tala 05-ARCHITECTURE-D2.d2 output.svg

# ELK (복잡한 다이어그램)
d2 --layout=elk 05-ARCHITECTURE-D2.d2 output.svg

# Dagre (빠른 렌더링)
d2 --layout=dagre 05-ARCHITECTURE-D2.d2 output.svg
```

**추천**: `tala` (기본값) - 가장 아름다운 레이아웃

### Watch 모드 (실시간 미리보기)

```bash
# 파일 변경 시 자동 재생성
d2 --watch 05-ARCHITECTURE-D2.d2 output.svg

# 브라우저 자동 새로고침
d2 --watch --browser 05-ARCHITECTURE-D2.d2
```

---

## 4. 포트폴리오용 이미지 생성

### 고품질 PNG (포트폴리오용)

```bash
cd /home/jimin/blogsite/docs/

# 1. 기본 PNG (1920x1080 권장)
d2 --theme=0 05-ARCHITECTURE-D2.d2 architecture-portfolio.png

# 2. 고해상도 PNG (Retina 디스플레이용)
d2 --theme=0 --scale=2 05-ARCHITECTURE-D2.d2 architecture-portfolio@2x.png

# 3. 다크 테마 버전
d2 --theme=101 --scale=2 05-ARCHITECTURE-D2.d2 architecture-portfolio-dark@2x.png

# 4. SVG (벡터, 확대해도 깨지지 않음)
d2 --theme=0 05-ARCHITECTURE-D2.d2 architecture-portfolio.svg
```

### Hugo 블로그에 삽입

```bash
# 1. 이미지 생성
d2 05-ARCHITECTURE-D2.d2 /home/jimin/blogsite/static/images/architecture.svg

# 2. 마크다운에 삽입
# content/projects/architecture.md
```

```markdown
## 시스템 아키텍처

![전체 아키텍처](/images/architecture.svg)
```

---

## 5. D2 파일 구조 설명

### 05-ARCHITECTURE-D2.d2 주요 구성

```d2
# 1. 방향 설정
direction: down  # 위에서 아래로

# 2. 타이틀
title: 블로그 시스템 전체 아키텍처 {
  near: top-center
  style: {
    font-size: 24
    bold: true
  }
}

# 3. 노드 정의
user: 사용자 (HTTPS) {
  shape: person              # 사람 모양
  style.fill: "#E8F5E9"      # 배경색
  style.stroke: "#4CAF50"    # 테두리색
}

# 4. 그룹 (컨테이너)
k8s: Kubernetes Cluster {
  style.fill: "#E3F2FD"      # 그룹 배경색

  # 내부 노드
  pod1: Web Pod
  pod2: WAS Pod
}

# 5. 연결 (화살표)
user -> k8s.pod1: HTTPS {
  style.stroke: "#4CAF50"    # 화살표 색상
  style.stroke-width: 3      # 화살표 굵기
}

# 6. 점선 연결
pod1 -> pod2: mTLS {
  style.stroke-dash: 3       # 점선
}

# 7. 특수 모양
database: MySQL {
  shape: cylinder            # 원통형 (DB용)
}
```

### 주요 스타일 옵션

| 속성 | 값 예시 | 설명 |
|------|---------|------|
| `shape` | `person`, `cylinder`, `document`, `cloud` | 도형 모양 |
| `style.fill` | `"#FF5722"` | 배경색 (Hex) |
| `style.stroke` | `"#E64A19"` | 테두리색 |
| `style.stroke-width` | `3` | 테두리 두께 |
| `style.stroke-dash` | `3` | 점선 (숫자는 간격) |
| `style.font-color` | `"#FFFFFF"` | 글자 색 |
| `style.font-size` | `16` | 글자 크기 |
| `style.bold` | `true` | 굵은 글씨 |

---

## 6. 실전 예제

### 예제 1: 간단한 3-tier 아키텍처

```d2
direction: down

user: 사용자 {
  shape: person
}

web: Web Server {
  style.fill: "#4CAF50"
}

app: Application Server {
  style.fill: "#03A9F4"
}

db: Database {
  shape: cylinder
  style.fill: "#F44336"
}

user -> web: HTTPS
web -> app: API
app -> db: SQL
```

**렌더링**:
```bash
d2 example.d2 example.svg
```

### 예제 2: Kubernetes Pod 간 통신

```d2
k8s: Kubernetes {
  web_pod: Web Pod {
    nginx: nginx {
      style.fill: "#009688"
    }
    istio_proxy: istio-proxy {
      style.fill: "#673AB7"
    }
  }

  was_pod: WAS Pod {
    spring: Spring Boot {
      style.fill: "#03A9F4"
    }
    istio_proxy: istio-proxy {
      style.fill: "#673AB7"
    }
  }

  web_pod.istio_proxy -> was_pod.istio_proxy: mTLS 🔒 {
    style.stroke: "#4CAF50"
    style.stroke-width: 3
  }
}
```

---

## 7. 트러블슈팅

### 문제 1: d2 명령어를 찾을 수 없음

**증상**: `d2: command not found`

**해결**:
```bash
# PATH 확인
echo $PATH | grep ".local/bin"

# PATH 추가
export PATH="$HOME/.local/bin:$PATH"

# 영구 적용
echo 'export PATH="$HOME/.local/bin:$PATH"' >> ~/.bashrc
source ~/.bashrc
```

### 문제 2: 한글 폰트 깨짐

**증상**: 한글이 네모(□)로 표시됨

**해결**:
```bash
# 한글 폰트 설치 (Ubuntu/Debian)
sudo apt update
sudo apt install fonts-nanum fonts-nanum-coding

# D2에 폰트 지정
d2 --font-regular="Nanum Gothic" 05-ARCHITECTURE-D2.d2 output.svg
```

### 문제 3: SVG 파일이 너무 큼

**증상**: SVG 파일이 10MB 이상

**해결**:
```bash
# SVG 최적화 도구 설치
sudo apt install scour

# SVG 압축
scour -i architecture.svg -o architecture-optimized.svg

# 또는 PNG로 변환
d2 05-ARCHITECTURE-D2.d2 architecture.png
```

---

## 8. GitHub README에 삽입

### 방법 1: PNG 이미지로 삽입

```bash
# 1. PNG 생성
d2 --theme=0 05-ARCHITECTURE-D2.d2 architecture.png

# 2. GitHub에 커밋
git add docs/architecture.png
git commit -m "docs: Add architecture diagram"
git push

# 3. README.md에 삽입
```

```markdown
## 시스템 아키텍처

![Architecture](docs/architecture.png)
```

### 방법 2: SVG로 삽입 (GitHub 지원)

```markdown
## 시스템 아키텍처

![Architecture](docs/architecture.svg)
```

**장점**: 벡터 그래픽, 확대해도 깨지지 않음

---

## 9. 추천 워크플로우

### 개발 단계

```bash
# 1. Watch 모드로 실시간 편집
d2 --watch --browser 05-ARCHITECTURE-D2.d2
```

### 완성 단계

```bash
# 2. 여러 버전 생성
d2 --theme=0 05-ARCHITECTURE-D2.d2 architecture-light.svg
d2 --theme=101 05-ARCHITECTURE-D2.d2 architecture-dark.svg
d2 --theme=0 --scale=2 05-ARCHITECTURE-D2.d2 architecture@2x.png

# 3. 최적화
scour -i architecture-light.svg -o architecture-light-opt.svg

# 4. Git 커밋
git add docs/05-ARCHITECTURE-D2.d2
git add docs/architecture-*.svg
git commit -m "docs: Add D2 architecture diagrams"
git push
```

---

## 10. D2 vs Mermaid 사용 가이드

| 사용 목적 | 추천 도구 | 이유 |
|----------|----------|------|
| **Hugo 블로그 글** | Mermaid | 코드 블록에 직접 삽입 가능 |
| **GitHub README** | Mermaid | 자동 렌더링 지원 |
| **포트폴리오 PDF** | D2 | 고품질 PNG/SVG |
| **프레젠테이션** | D2 | 세련된 디자인 |
| **문서화** | Mermaid | 유지보수 편리 |
| **인쇄물** | D2 | 고해상도 출력 |

**최종 전략**:
1. **Mermaid**: 05-ARCHITECTURE-MERMAID.md → Hugo 블로그, GitHub
2. **D2**: 05-ARCHITECTURE-D2.d2 → 포트폴리오 이미지

---

## 11. 참고 자료

- **D2 공식 문서**: https://d2lang.com/tour/intro
- **D2 Playground**: https://play.d2lang.com/
- **D2 GitHub**: https://github.com/terrastruct/d2
- **D2 Examples**: https://github.com/terrastruct/d2/tree/master/docs/examples

---

**작성:** Claude Code
**최종 수정:** 2026-01-24
**도구:** D2 (Declarative Diagramming)
**파일:** `05-ARCHITECTURE-D2.d2`
