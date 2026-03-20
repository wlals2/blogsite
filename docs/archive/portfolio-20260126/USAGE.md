# 포트폴리오 슬라이드 제작 가이드

> **슬라이드용 간결한 자료 활용법**

---

## 📁 파일 구조

```
docs/portfolio/
├── README.md                  # 전체 목차 (12-15장)
│
├── chapters/                  # 슬라이드 내용
│   ├── 02-executive-summary.md
│   └── 13-troubleshooting.md
│
├── tables/                    # 비교 표
│   ├── technology-decisions.md
│   └── metrics.md
│
├── diagrams/                  # Mermaid 차트
│   ├── network-flow.md        # Network Flow
│   ├── cicd-pipeline.md       # CI/CD Pipeline
│   ├── ha-failover.md         # HA Failover
│   └── security-layers.md     # Security Layers
│
├── images/                    # 변환된 PNG (자동 생성)
│   ├── network-flow.png
│   ├── cicd-pipeline.png
│   ├── ha-failover.png
│   └── security-layers.png
│
├── convert-mermaid.sh         # Mermaid → PNG 변환 스크립트
└── USAGE.md                   # 이 파일
```

---

## 🎨 Mermaid → PNG 변환 방법

### 옵션 1: 자동 변환 스크립트 (권장)

```bash
cd /home/jimin/blogsite/docs/portfolio
./convert-mermaid.sh
```

**결과**:
- `diagrams/*.md`에서 Mermaid 코드 추출
- `images/*.png` 생성 (Mermaid Ink API 사용)

---

### 옵션 2: 온라인 변환 (수동)

1. **Mermaid Live Editor** 접속
   - https://mermaid.live/

2. **Mermaid 코드 복사**
   ```bash
   # network-flow.md 예시
   cat diagrams/network-flow.md
   # ```mermaid ~ ``` 사이 코드 복사
   ```

3. **PNG Export**
   - 우측 상단 "Actions" → "PNG"
   - `images/` 디렉터리에 저장

---

### 옵션 3: VSCode 확장 (로컬)

1. **확장 설치**
   ```bash
   code --install-extension bierner.markdown-mermaid
   ```

2. **Markdown Preview**
   - `diagrams/*.md` 파일 열기
   - Ctrl+Shift+V (Preview)
   - 다이어그램 우클릭 → "Copy Image"

---

## 📊 슬라이드 제작 순서

### 1단계: 내용 확인
```bash
# 각 파일 내용 확인
cat chapters/02-executive-summary.md    # Slide 2
cat diagrams/network-flow.md            # Slide 6
cat diagrams/cicd-pipeline.md           # Slide 9
cat diagrams/security-layers.md         # Slide 11
cat diagrams/ha-failover.md             # Slide 12
cat chapters/13-troubleshooting.md      # Slide 13
cat tables/technology-decisions.md      # Appendix
cat tables/metrics.md                   # Appendix
```

### 2단계: 이미지 생성
```bash
./convert-mermaid.sh

# 결과 확인
ls -lh images/*.png
```

### 3단계: 슬라이드 작성
- PowerPoint / Google Slides / Keynote 사용
- `chapters/*.md` 내용을 각 슬라이드에 복사
- `images/*.png`를 삽입

---

## 📋 슬라이드 구성 (12-15장)

### Part 1: Introduction (3장)

**Slide 1: Cover**
- 제목: Production-Grade Kubernetes 홈랩
- 부제: Istio Service Mesh & GitOps
- 기간: 2024-11 ~ 2026-01 (58일 운영)

**Slide 2: Executive Summary**
- 파일: `chapters/02-executive-summary.md`
- 핵심 성과: 99.9% Uptime, 60회 배포, $280/월 절감

**Slide 3: Background**
- 문제: AWS 비용 증가, 실전 경험 부족
- 목표: 엔터프라이즈급 환경 구축

---

### Part 2: Architecture (5장)

**Slide 4: Full Architecture**
- 이미지: `content/image/localk8s 아키텍처.png` (기존)
- 전체 시스템 다이어그램

**Slide 5: Infrastructure**
- 4-Node Cluster 구성
- 기술 스택: Istio, Cilium, Longhorn, ArgoCD

**Slide 6: Network Flow**
- 파일: `diagrams/network-flow.md`
- 이미지: `images/network-flow.png`
- 7-Step 트래픽 플로우

**Slide 7: Service Mesh**
- Istio vs Cilium 역할 구분 (표)
- mTLS, NetworkPolicy

**Slide 8: Application**
- WEB (Hugo + nginx)
- WAS (Spring Boot)
- MySQL (Longhorn)

---

### Part 3: Operations (4장)

**Slide 9: CI/CD Pipeline**
- 파일: `diagrams/cicd-pipeline.md`
- 이미지: `images/cicd-pipeline.png`
- GitOps 워크플로우, Canary 배포

**Slide 10: Observability**
- PLG Stack (Prometheus, Loki, Grafana)
- 주요 대시보드

**Slide 11: Security**
- 파일: `diagrams/security-layers.md`
- 이미지: `images/security-layers.png`
- 5-Layer 보안 모델

**Slide 12: High Availability**
- 파일: `diagrams/ha-failover.md`
- 이미지: `images/ha-failover.png`
- Failover 시나리오, RTO 2분

---

### Part 4: Insights (3장)

**Slide 13: Troubleshooting**
- 파일: `chapters/13-troubleshooting.md`
- 4가지 핵심 이슈 & 해결

**Slide 14: Roadmap**
- 단기: mTLS STRICT, Jaeger Tracing
- 장기: Multi-Cluster, Kubernetes Operator

**Slide 15: Conclusion**
- 핵심 성과 요약
- 기술적 성장
- 마무리 메시지

---

## 📊 Appendix (표 자료)

**Technology Decisions**
- 파일: `tables/technology-decisions.md`
- 5가지 기술 선택 이유 (Istio, Cilium, Longhorn, ArgoCD, Cloudflare)

**Metrics & Performance**
- 파일: `tables/metrics.md`
- 실제 운영 데이터 (Uptime, 배포 성능, 비용 분석)

---

## 🎯 슬라이드 디자인 팁

### 1. 한 슬라이드 = 한 메시지
- ✅ 제목: 명확한 메시지
- ✅ 내용: 3-5개 bullet points
- ✅ 이미지: 1개 (큰 크기)
- ❌ 텍스트 과다: 피하기

### 2. 숫자 강조
```
❌ "성능이 향상되었습니다"
✅ "P95 레이턴시 33% 감소 (180ms → 120ms)"
```

### 3. Before/After 비교
```
Before: Nginx Ingress + Istio Gateway (중복)
After:  Istio Gateway 일원화
결과:   레이턴시 33% 감소
```

### 4. 컬러 코드
- 🟢 성공: #90ee90
- 🔴 실패/장애: #ff6b6b
- 🟡 경고: #ffd700
- 🔵 정보: #87ceeb

---

## 🛠️ 트러블슈팅

### Q1: Mermaid 변환 실패
```bash
# 수동 확인
cat diagrams/network-flow.md | grep -A 50 "```mermaid"

# 온라인 도구 사용
# https://mermaid.live/
```

### Q2: 이미지 품질 낮음
```bash
# Mermaid Ink API 대신 CLI 사용 (고품질)
npm install -g @mermaid-js/mermaid-cli
mmdc -i diagrams/network-flow.md -o images/network-flow.png -w 1920
```

### Q3: 표 깨짐
- Markdown 표를 Excel/Sheets로 복사
- 슬라이드에 표로 삽입 (이미지 아님)

---

## 📝 체크리스트

### 슬라이드 제작 전
- [ ] 모든 md 파일 내용 확인
- [ ] Mermaid 차트 PNG 변환 완료
- [ ] 기존 아키텍처 이미지 준비 (`content/image/localk8s 아키텍처.png`)

### 슬라이드 제작 중
- [ ] 각 슬라이드에 제목 + 1문장 요약
- [ ] 숫자로 증명 (99.9%, 2분, 33% 등)
- [ ] Before/After 비교 (개선 효과)
- [ ] 핵심 메시지 강조

### 슬라이드 완료 후
- [ ] 3분 발표 연습 (Executive Summary)
- [ ] 10분 발표 연습 (전체)
- [ ] 질문 예상 (Why Istio? Troubleshooting 사례?)

---

## 🎤 발표 준비

### 3분 버전 (핵심만)
1. **30초**: 프로젝트 개요 (99.9% Uptime, 60회 배포)
2. **1분**: 아키텍처 하이라이트 (Istio 일원화, Cilium eBPF)
3. **1분**: 실전 경험 (Troubleshooting 1-2개)
4. **30초**: 기술적 성장 & 마무리

### 10분 버전 (전체)
1. **1분**: Introduction (문제 정의, 목표)
2. **4분**: Architecture (Network Flow, CI/CD, Security, HA)
3. **3분**: Troubleshooting (4가지 이슈)
4. **2분**: Metrics, Roadmap, Conclusion

---

**작성일**: 2026-01-26
**최종 업데이트**: 2026-01-26
