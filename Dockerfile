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
FROM alpine:latest AS builder

# Hugo 설치
RUN apk add --no-cache hugo

WORKDIR /src

# Hugo 소스 복사
COPY . .

# Hugo 빌드 (public/ 디렉토리에 정적 파일 생성)
# --minify: HTML/CSS/JS 압축
# --gc: 사용하지 않는 캐시 정리
RUN hugo --minify --gc

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
