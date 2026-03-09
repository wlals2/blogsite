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

WORKDIR /src

# Hugo 소스 복사
# Why: COPY를 ARG GIT_COMMIT 앞에 배치
#      ARG가 COPY 앞에 있으면 SHA가 바뀔 때마다 COPY 이후 레이어 전체 캐시 무효화
#      COPY 이후에 ARG를 선언하면 hugomods 이미지 레이어는 캐시 HIT 유지
COPY . .

# Why: ARG는 COPY 이후 선언 → hugomods 이미지 레이어 캐시 보호
#      --mount=type=cache → Hugo 내부 캐시(템플릿 파싱 결과)를 빌드 간 영구 보존
#      콘텐츠 변경 시: COPY 레이어만 MISS, Hugo 내부 캐시는 유지
ARG GIT_COMMIT=unknown
RUN --mount=type=cache,target=/tmp/hugo_cache \
    echo "Building from commit: $GIT_COMMIT" && \
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
