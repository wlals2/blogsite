#!/bin/bash
# TOTP Secret 생성 스크립트

set -e

echo "🔐 TOTP Secret 생성"
echo "===================="
echo ""

# Base32 인코딩된 Secret 생성 (32자)
SECRET=$(openssl rand -base64 32 | head -c 32)
echo "TOTP Secret (Base32):"
echo "$SECRET"
echo ""

# QR 코드 URL 생성 (Google Authenticator용)
ISSUER="BlogPrivate"
ACCOUNT="your-email@example.com"
QR_URL="otpauth://totp/${ISSUER}:${ACCOUNT}?secret=${SECRET}&issuer=${ISSUER}"

echo "Google Authenticator QR 코드 URL:"
echo "$QR_URL"
echo ""

# QR 코드 생성 (qrencode 필요)
if command -v qrencode &> /dev/null; then
    echo "QR 코드 생성 중..."
    qrencode -t ANSIUTF8 "$QR_URL"
    echo ""
    echo "위 QR 코드를 Google Authenticator로 스캔하세요"
else
    echo "📱 QR 코드 온라인 생성:"
    echo "https://www.qr-code-generator.com/"
    echo "위 URL을 입력하세요: $QR_URL"
fi

echo ""
echo "⚠️  보안 중요!"
echo "1. TOTP Secret을 .env 파일에 저장하세요"
echo "2. .env 파일을 .gitignore에 추가하세요"
echo "3. GitHub Secrets에도 등록하세요"
echo ""

# .env 파일 생성
cat > .env.example << EOF
# TOTP Secret (실제 값은 .env에 저장)
PRIVATE_TOTP_SECRET=${SECRET}

# AES 암호화 키 (32바이트 hex)
PRIVATE_AES_KEY=$(openssl rand -hex 32)
EOF

echo "✅ .env.example 파일이 생성되었습니다"
echo "   실제 사용할 .env 파일을 복사하여 사용하세요:"
echo "   cp .env.example .env"
