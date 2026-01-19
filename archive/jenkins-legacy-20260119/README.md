# Jenkins Legacy Files Archive

> 보관 날짜: 2026-01-19
> 이유: GitHub Actions로 CI/CD 마이그레이션 완료

## 보관된 파일

### Jenkins 파이프라인 파일
- `Jenkinsfile` - 루트 디렉터리의 Hugo 블로그 파이프라인
- `blog-k8s-project/jenkins/` - Jenkins 관련 디렉터리
  - `Jenkinsfile-web` - WEB 배포 파이프라인
  - `Jenkinsfile-was` - WAS 배포 파이프라인
- `blog-k8s-project/was/Jenkinsfile` - WAS 프로젝트 내 Jenkinsfile

## 현재 사용 중인 CI/CD

**GitHub Actions**로 전환 완료 (2026-01-18)
- `.github/workflows/deploy-web.yml` - WEB 배포
- `.github/workflows/deploy-was.yml` - WAS 배포
- Self-hosted runner 사용 (k8s-cp 노드)

## 마이그레이션 이유

1. **Git 통합**: GitHub과 원활한 통합
2. **빠른 배포**: 35초 (Jenkins 90초 대비 61% 개선)
3. **간단한 설정**: YAML 기반 선언적 설정
4. **무료**: Self-hosted runner로 무제한 사용

## 복원 방법

필요 시 이 디렉터리에서 파일을 다시 복사할 수 있습니다.

```bash
# Jenkins 재활성화 시
cp archive/jenkins-legacy-20260119/Jenkinsfile .
```

