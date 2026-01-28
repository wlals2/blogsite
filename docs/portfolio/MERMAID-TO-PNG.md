# Mermaid → PNG 변환 가이드

> **슬라이드용 고품질 이미지 생성**

---

## 🎯 권장 방법 (3가지)

### 방법 1: Mermaid Live Editor (가장 쉬움) ⭐

1. **https://mermaid.live/ 접속**

2. **Mermaid 코드 복사**
   ```bash
   # 예: network-flow.md
   cat diagrams/network-flow.md
   ```

3. **Mermaid 코드 블록 복사**
   - ````mermaid` ~ ````` 사이 코드만 복사
   - 왼쪽 에디터에 붙여넣기

4. **PNG Export**
   - 우측 상단 "Actions" 클릭
   - "PNG" 선택
   - 다운로드: `network-flow.png`
   - `images/` 폴더에 저장

---

### 방법 2: VSCode 확장 (로컬 작업) ⭐

1. **Mermaid Extension 설치**
   ```bash
   code --install-extension bierner.markdown-mermaid
   ```

2. **Markdown Preview**
   ```bash
   # VSCode에서 파일 열기
   code diagrams/network-flow.md

   # Preview 열기
   Ctrl+Shift+V (또는 Cmd+Shift+V)
   ```

3. **이미지 Export**
   - 다이어그램 우클릭
   - "Copy Image" 또는 "Save Image As..."
   - `images/` 폴더에 저장

---

### 방법 3: Mermaid CLI (고품질) ⭐⭐⭐

```bash
# 1. Puppeteer 설치 (한 번만)
npm install -g @mermaid-js/mermaid-cli

# 2. 각 파일 변환
cd /home/jimin/blogsite/docs/portfolio

# network-flow.md
mmdc -i diagrams/network-flow.md -o images/network-flow.png -w 1920 -H 1080

# cicd-pipeline.md
mmdc -i diagrams/cicd-pipeline.md -o images/cicd-pipeline.png -w 1920 -H 1080

# ha-failover.md
mmdc -i diagrams/ha-failover.md -o images/ha-failover.png -w 1920 -H 1080

# security-layers.md
mmdc -i diagrams/security-layers.md -o images/security-layers.png -w 1920 -H 1080
```

**고품질 옵션**:
- `-w 1920`: 너비 1920px (Full HD)
- `-H 1080`: 높이 1080px
- `-b transparent`: 투명 배경
- `-s 2`: 스케일 2배 (Retina)

---

## 📄 파일별 Mermaid 코드 위치

### network-flow.md
```bash
# 코드 추출
awk '/^```mermaid$/,/^```$/ {if (!/^```/) print}' diagrams/network-flow.md
```

### cicd-pipeline.md
```bash
awk '/^```mermaid$/,/^```$/ {if (!/^```/) print}' diagrams/cicd-pipeline.md
```

### ha-failover.md
```bash
awk '/^```mermaid$/,/^```$/ {if (!/^```/) print}' diagrams/ha-failover.md
```

### security-layers.md
```bash
awk '/^```mermaid$/,/^```$/ {if (!/^```/) print}' diagrams/security-layers.md
```

---

## 🎨 슬라이드용 추천 설정

### PowerPoint / Google Slides
- 해상도: 1920x1080 (Full HD)
- 포맷: PNG (투명 배경)
- 위치: 슬라이드 중앙

### 이미지 크기
| 다이어그램 | 권장 크기 | 비율 |
|-----------|----------|------|
| network-flow | 1600x900 | 16:9 |
| cicd-pipeline | 1600x900 | 16:9 |
| ha-failover | 1600x1000 | 16:10 |
| security-layers | 1400x1000 | 7:5 |

---

## 🛠️ 트러블슈팅

### Q1: Mermaid Live Editor에서 렌더링 안 됨
```
원인: 구문 오류
해결:
1. 코드 블록 앞뒤 ``` 제거
2. 들여쓰기 확인
3. Mermaid Live Editor에서 에러 메시지 확인
```

### Q2: PNG 품질이 낮음
```
해결:
1. Mermaid CLI 사용 (-w 1920 -H 1080)
2. 스케일 2배 (-s 2)
3. SVG로 Export 후 PNG 변환
```

### Q3: 투명 배경 필요
```bash
# Mermaid CLI
mmdc -i diagrams/network-flow.md -o images/network-flow.png -b transparent
```

---

## 📋 빠른 변환 체크리스트

### 준비 (한 번만)
- [ ] Mermaid Live Editor 북마크
- [ ] 또는 VSCode Extension 설치
- [ ] 또는 Mermaid CLI 설치

### 각 파일 변환
- [ ] `diagrams/network-flow.md` → `images/network-flow.png`
- [ ] `diagrams/cicd-pipeline.md` → `images/cicd-pipeline.png`
- [ ] `diagrams/ha-failover.md` → `images/ha-failover.png`
- [ ] `diagrams/security-layers.md` → `images/security-layers.png`

### 확인
- [ ] 이미지 크기 확인 (1920x1080)
- [ ] 이미지 선명도 확인
- [ ] 슬라이드에 삽입 테스트

---

## 🎯 실전 예시

### network-flow.md 변환

**1단계: 코드 확인**
```bash
cat diagrams/network-flow.md
```

**2단계: Mermaid Live Editor 접속**
```
https://mermaid.live/
```

**3단계: 코드 복사 & 붙여넣기**
```mermaid
flowchart TD
    User[👤 사용자<br/>https://blog.jiminhome.shop/]
    CDN[☁️ Cloudflare CDN<br/>SSL/TLS 종료<br/>DDoS 방어]
    ... (전체 코드)
```

**4단계: PNG Export**
```
Actions → PNG → Download
→ images/network-flow.png로 저장
```

---

## 📊 결과물

### 생성될 이미지 (4개)
```
images/
├── network-flow.png       # 7-Step 트래픽 플로우
├── cicd-pipeline.png      # GitOps 배포 워크플로우
├── ha-failover.png        # Node 장애 Failover
└── security-layers.png    # 5-Layer 보안 모델
```

### 슬라이드 적용
```
Slide 6: Network Flow → images/network-flow.png
Slide 9: CI/CD Pipeline → images/cicd-pipeline.png
Slide 11: Security → images/security-layers.png
Slide 12: HA → images/ha-failover.png
```

---

**핵심**: **Mermaid Live Editor (https://mermaid.live/)** 가장 간단하고 빠름!
