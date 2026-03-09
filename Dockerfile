# ==============================================================================
# Hugo Blog Dockerfile (Multi-stage Build)
# ==============================================================================
# 목적: Hugo 정적 사이트를 빌드하고 nginx로 서빙
#
# Multi-stage Build:
# - Stage 1 (builder): Hugo로 정적 사이트 빌드
# - Stage 2 (runtime): nginx로 정적 파일 서빙
# - 이미지 크기: ~20MB (Hugo 도구 제외)
# ==============================================================================

# ==============================================================================
# Stage 1: Builder - Hugo Build
# ==============================================================================
# Why: hugomods/hugo 공식 이미지 사용
#      apk add hugo 불필요 → Alpine 패키지 서버 통신 없음 → 빌드 10~15분 → 3분으로 단축
#      alpine:latest + apk add hugo 방식은 매 빌드마다 Hugo 바이너리를 인터넷에서 다운로드
FROM hugomods/hugo:0.146.0 AS builder

# Git commit SHA를 받아서 캐시 무효화
ARG GIT_COMMIT=unknown
RUN echo "Building from commit: $GIT_COMMIT"

WORKDIR /src

# Hugo 소스 복사
COPY . .

# Hugo 빌드 (public/ 디렉토리에 정적 파일 생성)
# --minify: HTML/CSS/JS 압축
# --gc: 사용하지 않는 캐시 정리
# Why: --mount=type=cache → 빌드 간 Hugo 내부 캐시(템플릿 파싱 결과)를 영구 보존
#      COPY . .가 변경돼도 이 마운트는 날아가지 않음
#      콘텐츠 1개 추가 시: 변경된 글만 재처리, 나머지 글은 캐시 히트
RUN --mount=type=cache,target=/tmp/hugo_cache \
    hugo --minify --gc --cacheDir /tmp/hugo_cache

# ==============================================================================
# Stage 2: Runtime - nginx
# ==============================================================================
FROM nginx:alpine

# Builder에서 생성된 정적 파일만 복사
COPY --from=builder /src/public /usr/share/nginx/html

# 헬스체크용 파일 생성
RUN echo "OK" > /usr/share/nginx/html/health

# 포트 노출
EXPOSE 80

# nginx 실행
CMD ["nginx", "-g", "daemon off;"]
