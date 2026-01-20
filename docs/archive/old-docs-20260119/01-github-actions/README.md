# GitHub Actions CI/CD

이 폴더는 GitHub Actions를 사용한 자동 배포 시스템에 관한 모든 문서를 포함합니다.

## 📚 문서 목록 (읽는 순서)

### 1. [001-setup.md](001-setup.md)
**GitHub Actions 설정 가이드**

Jenkins와 병행하여 GitHub Actions 설정:
- 목표 및 장점
- 파일 구조
- 워크플로우 파일 작성
- Self-hosted runner 설정
- Secrets 등록

**읽어야 하는 경우:**
- 처음부터 설정하는 경우
- 새로운 프로젝트에 적용할 때

---

### 2. [002-secrets-setup.md](002-secrets-setup.md)
**GitHub Secrets 설정 가이드**

Kubernetes 배포를 위한 Secrets 설정:
- GHCR_TOKEN 생성
- KUBECONFIG_BASE64 설정
- CLOUDFLARE_ZONE_ID/API_TOKEN 설정
- 검증 방법

**읽어야 하는 경우:**
- Secrets를 처음 설정하는 경우
- Secrets 관련 오류가 발생할 때

---

### 3. [003-guide.md](003-guide.md)
**GitHub Actions 완벽 가이드**

GitHub Actions의 모든 것:
- GitHub Actions 개념 설명
- 현재 배포 워크플로우 분석
- Self-Hosted Runner 이해하기
- 각 단계 상세 설명
- 트리거 조건 (on)
- 개선 가능한 옵션
- 트러블슈팅 및 보안

**읽어야 하는 경우:**
- GitHub Actions를 처음 배우는 경우
- 워크플로우 전체를 이해하고 싶을 때
- 상세한 설명이 필요할 때

---

### 4. [004-deployment-verification.md](004-deployment-verification.md)
**배포 플로우 검증 및 아키텍처 확인**

실제 배포가 어떻게 동작하는지 검증:
- 핵심 질문과 답변 (K8s vs 로컬)
- 실제 배포 플로우 추적
- 잘못된 이해와 올바른 이해
- 아키텍처 검증 과정
- 사용 안 하는 설정 정리

**읽어야 하는 경우:**
- 블로그가 실제로 어디서 실행되는지 확인하고 싶을 때
- 배포 플로우를 명확히 이해하고 싶을 때
- 설정 정리가 필요할 때

---

### 5. [005-why-self-hosted.md](005-why-self-hosted.md)
**Self-Hosted Runner 선택 이유**

GitHub-Hosted vs Self-Hosted 비교:
- 선택 이유 (WHY)
- 성능 측정 데이터
- 트레이드오프 분석
- 대안 분석
- 실제 효과

**읽어야 하는 경우:**
- Self-hosted runner 사용 이유가 궁금할 때
- GitHub-hosted와 비교하고 싶을 때
- 의사결정 근거를 알고 싶을 때

---

### 6. [006-deep-dive.md](006-deep-dive.md)
**워크플로우 Deep Dive**

현재 워크플로우의 설계 결정 분석:
- 전체 흐름 설명
- 핵심 설계 결정 (Why?)
- 각 단계별 상세 분석
- 성능 최적화
- 대안 비교

**읽어야 하는 경우:**
- 왜 이렇게 설정되어 있는지 궁금할 때
- 워크플로우를 튜닝하고 싶을 때
- 설계 의도를 이해하고 싶을 때

---

### 7. [007-migration-complete.md](007-migration-complete.md)
**GitHub Actions 마이그레이션 완료 기록**

Jenkins에서 GitHub Actions로 마이그레이션 완료 기록

**읽어야 하는 경우:**
- 마이그레이션 작업 기록을 확인하고 싶을 때

---

## 🔗 관련 문서

- **프로젝트 전체 가이드**: [00-overview/002-complete-guide.md](../00-overview/002-complete-guide.md)
- **Cloudflare 캐시 설정**: [03-cloudflare/001-auto-purge.md](../03-cloudflare/001-auto-purge.md)
- **빠른 참조**: [00-overview/001-quick-reference.md](../00-overview/001-quick-reference.md)
