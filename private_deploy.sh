#!/bin/bash

# Private 콘텐츠 배포 스크립트

set -e

echo "🔐 Private 콘텐츠 배포"
echo "======================"
echo ""

# .env 파일 확인
if [ ! -f .env ]; then
    echo "❌ .env 파일이 없습니다!"
    echo "   ./scripts/generate-totp-secret.sh 실행하세요"
    exit 1
fi

# 1. 환경변수 로드
echo "📂 환경변수 로드 중..."
source .env
export PRIVATE_TOTP_SECRET
export PRIVATE_AES_KEY
echo "✅ 환경변수 로드 완료"
echo ""

# 2. Hugo 빌드
echo "📦 Hugo 빌드 중..."
hugo --minify

if [ $? -ne 0 ]; then
    echo "❌ Hugo 빌드 실패!"
    exit 1
fi
echo "✅ Hugo 빌드 완료"
echo ""

# 3. 서버 배포
echo "🚀 서버에 배포 중..."
sudo rsync -avh --delete public/ /var/www/blog/

if [ $? -ne 0 ]; then
    echo "❌ 배포 실패!"
    exit 1
fi
echo ""

echo "======================"
echo "✅ 배포 완료!"
echo ""
echo "📍 접속 URL: https://blog.jiminhome.shop/private/"
echo "🔑 Google Authenticator에서 OTP 확인 후 접속하세요"
echo ""
