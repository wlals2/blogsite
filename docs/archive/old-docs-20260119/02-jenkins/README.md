# Jenkins CI/CD

이 폴더는 Jenkins를 사용한 CI/CD 파이프라인 구축 관련 문서를 포함합니다.

> **Note**: 현재는 GitHub Actions를 주로 사용하고 있으며, Jenkins는 병행 또는 참고 목적으로 남아 있습니다.

## 📚 문서 목록 (읽는 순서)

### 1. [001-cicd-setup.md](001-cicd-setup.md)
**Hugo Blog CI/CD Pipeline 구축 가이드**

Jenkins를 사용한 자동 빌드/배포 시스템:
- 목적 및 문제 상황
- 아키텍처 (Jenkins → GHCR → Kubernetes)
- 구축 과정 단계별 설명
- 트러블슈팅 기록
- 최종 구성
- 교훈

**읽어야 하는 경우:**
- Jenkins CI/CD 파이프라인을 구축하고 싶을 때
- Jenkins와 Kubernetes 연동 방법을 알고 싶을 때
- GHCR (GitHub Container Registry) 사용법을 배우고 싶을 때
- 트러블슈팅 경험을 참고하고 싶을 때

**주요 내용:**
- Git Push → Jenkins 빌드 → GHCR 업로드 → K8s 무중단 배포
- Multi-stage Docker Build
- Immutable Infrastructure

---

## 🔗 관련 문서

- **GitHub Actions 가이드**: [01-github-actions/](../01-github-actions/) (현재 주로 사용)
- **프로젝트 전체 가이드**: [00-overview/002-complete-guide.md](../00-overview/002-complete-guide.md)
- **Kubernetes 계획**: [04-kubernetes-plans/](../04-kubernetes-plans/)
