# ==============================================================================
# Hugo Blog Dockerfile (Simple Build)
# ==============================================================================
# 로컬에서 Hugo 빌드 완료 후 사용
# Hugo v0.146.0이 로컬에 설치되어 있으므로, Docker에서는 nginx만 사용
#
# 사용 방법:
# 1. 로컬에서 빌드: hugo --minify
# 2. Docker 이미지 빌드: docker build -t blog-web:v1 .
# 3. K8s 배포
#
# 왜 이 방식인가?
# - PaperMod 테마가 Hugo v0.146.0+ 요구
# - klakegg/hugo에는 0.146.0 없음
# - 로컬 Hugo (v0.146.0)로 빌드 → 결과물만 Docker로 복사
# ==============================================================================

FROM nginx:alpine

# Hugo 빌드 결과 복사
# public/ (Hugo 빌드 결과) → /usr/share/nginx/html (nginx 서빙 디렉터리)
COPY public /usr/share/nginx/html

# Health Check 파일 생성
# Kubernetes livenessProbe/readinessProbe 용도
RUN echo "OK" > /usr/share/nginx/html/health

# 포트 노출
EXPOSE 80

# nginx 실행
# -g "daemon off;": Foreground 실행 (Docker 컨테이너 종료 방지)
CMD ["nginx", "-g", "daemon off;"]
