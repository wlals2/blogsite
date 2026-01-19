#!/bin/bash
# ==============================================================================
# GitHub Container Registry에 이미지 Push
# ==============================================================================
# 사용 방법:
# export GITHUB_USERNAME="your-username"
# export GITHUB_TOKEN="ghp_xxxxx"
# ./push-to-ghcr.sh
# ==============================================================================

set -e

# GitHub 정보 확인
if [ -z "$GITHUB_USERNAME" ]; then
    echo "❌ GITHUB_USERNAME 환경변수가 설정되지 않았습니다."
    echo "export GITHUB_USERNAME='your-username' 실행 후 다시 시도하세요."
    exit 1
fi

if [ -z "$GITHUB_TOKEN" ]; then
    echo "❌ GITHUB_TOKEN 환경변수가 설정되지 않았습니다."
    echo "export GITHUB_TOKEN='ghp_xxxxx' 실행 후 다시 시도하세요."
    exit 1
fi

echo "🔐 GitHub Container Registry 로그인 중..."
echo $GITHUB_TOKEN | docker login ghcr.io -u $GITHUB_USERNAME --password-stdin

echo ""
echo "🏷️  이미지 태그 변경 중..."
docker tag blog-web:v1 ghcr.io/$GITHUB_USERNAME/blog-web:v1
docker tag blog-web:v1 ghcr.io/$GITHUB_USERNAME/blog-web:latest

echo ""
echo "📤 이미지 Push 중..."
docker push ghcr.io/$GITHUB_USERNAME/blog-web:v1
docker push ghcr.io/$GITHUB_USERNAME/blog-web:latest

echo ""
echo "✅ Push 완료!"
echo ""
echo "📋 다음 단계:"
echo "1. GitHub에서 패키지를 Public으로 변경"
echo "   https://github.com/$GITHUB_USERNAME?tab=packages"
echo "   → blog-web 패키지 클릭 → Package settings → Change visibility → Public"
echo ""
echo "2. deployment.yaml 수정:"
echo "   image: ghcr.io/$GITHUB_USERNAME/blog-web:v1"
