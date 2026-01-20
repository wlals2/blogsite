# 🛠️ Hugo 블로그 개발 환경 가이드

## 🎯 목적

로컬 환경에 영향을 주지 않고, 격리된 컨테이너 환경에서 Hugo 블로그를 빌드/테스트합니다.

---

## 🚀 빠른 시작

### 1. Hugo 개발 서버 실행 (실시간 미리보기)

```bash
# Hugo 개발 서버 시작
docker-compose -f docker-compose.dev.yml up hugo-dev

# 브라우저에서 확인
# http://localhost:1313
```

**특징:**
- 파일 저장하면 자동 새로고침
- draft 글도 보임
- 로컬 파일 실시간 반영

### 2. 프로덕션 빌드 테스트

```bash
# Hugo 빌드 + Nginx 서버 시작
docker-compose -f docker-compose.dev.yml up hugo-build nginx-test

# 빌드 결과를 Nginx로 확인
# http://localhost:8080
```

**특징:**
- `public-test/` 폴더에 빌드 결과 생성
- 실제 프로덕션과 동일한 환경
- Nginx로 서빙되는 모습 확인

### 3. 한 번에 빌드만 실행

```bash
# 빌드만 실행 (백그라운드)
docker-compose -f docker-compose.dev.yml run --rm hugo-build

# 빌드 결과 확인
ls -la public-test/
```

---

## 📝 사용 예시

### 새 글 작성 후 테스트

```bash
# 1. 새 글 작성
hugo new posts/my-new-post.md

# 2. 개발 서버로 미리보기
docker-compose -f docker-compose.dev.yml up hugo-dev

# 3. 확인 후 프로덕션 빌드 테스트
docker-compose -f docker-compose.dev.yml down
docker-compose -f docker-compose.dev.yml up hugo-build nginx-test

# 4. http://localhost:8080에서 최종 확인
```

### 문제 발생 시 디버깅

```bash
# 컨테이너 내부 접속
docker exec -it hugo-dev sh

# Hugo 버전 확인
hugo version

# 빌드 로그 확인
docker-compose -f docker-compose.dev.yml logs hugo-build
```

---

## 🧹 정리

```bash
# 모든 컨테이너 종료
docker-compose -f docker-compose.dev.yml down

# 빌드 결과물 삭제
rm -rf public-test/

# 볼륨까지 삭제
docker-compose -f docker-compose.dev.yml down -v
```

---

## 🎓 다음 단계: Tekton 환경

개발 환경에 익숙해지면:
1. k3s로 로컬 Kubernetes 클러스터 구축
2. Tekton 설치
3. Hugo 빌드 파이프라인 작성
