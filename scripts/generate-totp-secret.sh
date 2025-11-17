#!/bin/bash
# TOTP Secret 생성 스크립트 (대화형)

set -e

echo "🔐 TOTP Secret 생성기"
echo "===================="
echo ""
echo "Google Authenticator에 등록할 정보를 입력하세요"
echo ""

# 사용자 입력 받기
read -p "📧 이메일 (예: your@email.com): " USER_EMAIL
read -p "🏷️  서비스 이름 (예: MyBlog, Private): " SERVICE_NAME

# 기본값 설정
if [ -z "$USER_EMAIL" ]; then
    USER_EMAIL="admin@blog.local"
    echo "   → 기본값 사용: $USER_EMAIL"
fi

if [ -z "$SERVICE_NAME" ]; then
    SERVICE_NAME="BlogPrivate"
    echo "   → 기본값 사용: $SERVICE_NAME"
fi

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

# Base32 Secret 생성
# TOTP는 Base32를 사용하므로 A-Z, 2-7 문자만 사용
SECRET=$(cat /dev/urandom | tr -dc 'A-Z2-7' | fold -w 32 | head -n 1)

echo "✅ TOTP Secret 생성 완료!"
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "📱 Google Authenticator 등록 정보"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "계정: $USER_EMAIL"
echo "발급자: $SERVICE_NAME"
echo ""
echo "🔑 TOTP Secret (Base32):"
echo "$SECRET"
echo ""

# QR 코드 URL 생성
QR_URL="otpauth://totp/${SERVICE_NAME}:${USER_EMAIL}?secret=${SECRET}&issuer=${SERVICE_NAME}"

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "📲 QR 코드"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

# qrencode 설치 확인
if command -v qrencode &> /dev/null; then
    echo "방법 1: 터미널에서 QR 코드 스캔"
    echo ""
    qrencode -t ANSIUTF8 "$QR_URL"
    echo ""
    echo "위 QR 코드를 Google Authenticator로 스캔하세요"
    echo ""
else
    echo "⚠️  qrencode가 설치되지 않았습니다"
    echo "   설치: sudo apt install qrencode (Ubuntu/Debian)"
    echo "        brew install qrencode (macOS)"
    echo ""
fi

echo "방법 2: 온라인 QR 코드 생성"
echo ""
echo "   1. https://www.qr-code-generator.com/ 접속"
echo "   2. 'URL' 선택"
echo "   3. 아래 URL 입력:"
echo ""
echo "   $QR_URL"
echo ""
echo "   4. 생성된 QR 코드를 Google Authenticator로 스캔"
echo ""

echo "방법 3: 수동 입력 (QR 코드 스캔 불가 시)"
echo ""
echo "   Google Authenticator → '+' → 'Enter a setup key'"
echo ""
echo "   Account: $USER_EMAIL"
echo "   Key: $SECRET"
echo "   Type of key: Time based"
echo ""

# AES 암호화 키 생성
AES_KEY=$(openssl rand -hex 32)

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "💾 환경변수 파일 생성"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

# .env 파일 생성
if [ -f .env ]; then
    echo "⚠️  .env 파일이 이미 존재합니다"
    read -p "   덮어쓰시겠습니까? (y/N): " OVERWRITE
    if [ "$OVERWRITE" != "y" ] && [ "$OVERWRITE" != "Y" ]; then
        echo "   → .env 파일을 건드리지 않습니다"
        echo "   → .env.example 파일만 생성합니다"
        ENV_FILE=".env.example"
    else
        ENV_FILE=".env"
    fi
else
    ENV_FILE=".env"
fi

cat > "$ENV_FILE" << EOF
# TOTP 설정
PRIVATE_TOTP_SECRET=$SECRET

# AES-256 암호화 키 (64자 hex)
PRIVATE_AES_KEY=$AES_KEY

# Google Authenticator 정보
TOTP_ACCOUNT=$USER_EMAIL
TOTP_ISSUER=$SERVICE_NAME
EOF

echo "✅ $ENV_FILE 파일이 생성되었습니다"
echo ""

# .env.example도 생성 (Git에 커밋용)
if [ "$ENV_FILE" = ".env" ]; then
    cat > .env.example << EOF
# TOTP 설정 (실제 값은 .env 파일에 저장)
PRIVATE_TOTP_SECRET=YOUR_TOTP_SECRET_HERE

# AES-256 암호화 키 (64자 hex)
PRIVATE_AES_KEY=YOUR_AES_KEY_HERE

# Google Authenticator 정보
TOTP_ACCOUNT=your-email@example.com
TOTP_ISSUER=BlogPrivate
EOF
    echo "✅ .env.example 파일도 생성되었습니다 (Git 커밋용)"
    echo ""
fi

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "⚠️  보안 체크리스트"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "1. ✅ Google Authenticator에 QR 코드 등록"
echo "2. ✅ OTP 6자리 생성 확인"
echo "3. ✅ .env 파일이 .gitignore에 포함되어 있는지 확인"
echo "4. ✅ GitHub Secrets에 등록 (나중에):"
echo "   - PRIVATE_TOTP_SECRET"
echo "   - PRIVATE_AES_KEY"
echo ""

# .gitignore 확인
if grep -q "^\.env$" .gitignore 2>/dev/null; then
    echo "✅ .env가 .gitignore에 이미 포함되어 있습니다"
else
    echo "⚠️  .env를 .gitignore에 추가하는 중..."
    echo "" >> .gitignore
    echo "# Environment variables (TOTP secrets)" >> .gitignore
    echo ".env" >> .gitignore
    echo ".env.local" >> .gitignore
    echo "✅ .gitignore에 추가했습니다"
fi

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "📝 다음 단계"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "1. Google Authenticator 앱을 열고 QR 코드를 스캔하세요"
echo ""
echo "2. OTP가 생성되는지 확인하세요 (6자리 숫자)"
echo ""
echo "3. 비공개 콘텐츠를 작성하세요:"
echo "   mkdir -p content/private/architecture"
echo "   vim content/private/architecture/index.md"
echo ""
echo "4. Hugo 빌드 후 암호화하세요:"
echo "   hugo --minify"
echo "   ./scripts/encrypt-private-content.sh"
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "✨ 생성 완료! Google Authenticator를 확인하세요"
echo ""
