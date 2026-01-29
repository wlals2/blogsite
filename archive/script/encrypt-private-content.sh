#!/bin/bash
# 비공개 콘텐츠 AES-256 암호화 스크립트

set -e

# .env 파일 로드
if [ -f .env ]; then
    source .env
else
    echo "❌ .env 파일이 없습니다"
    echo "   scripts/generate-totp-secret.sh 를 먼저 실행하세요"
    exit 1
fi

# 필수 변수 확인
if [ -z "$PRIVATE_AES_KEY" ]; then
    echo "❌ PRIVATE_AES_KEY가 설정되지 않았습니다"
    exit 1
fi

echo "🔐 비공개 콘텐츠 암호화"
echo "======================="
echo ""

# 출력 디렉토리 생성 (static과 public 둘 다)
mkdir -p static/private-encrypted
mkdir -p public/private-encrypted

# content/private/ 아래의 모든 HTML 파일 암호화
CONTENT_DIR="public/private"

if [ ! -d "$CONTENT_DIR" ]; then
    echo "⚠️  $CONTENT_DIR 디렉토리가 없습니다"
    echo "   먼저 'hugo' 명령으로 빌드하세요"
    exit 1
fi

# 암호화할 파일 찾기
find "$CONTENT_DIR" -name "index.html" | while read -r file; do
    # 상대 경로 추출
    rel_path="${file#$CONTENT_DIR/}"
    dir_name=$(dirname "$rel_path")

    # 출력 파일명 (static과 public 둘 다 생성)
    # JavaScript가 /private-encrypted/디렉토리/index.html.enc 형식으로 요청하므로 맞춰줌
    if [ "$dir_name" = "." ]; then
        static_output="static/private-encrypted/index.html.enc"
        public_output="public/private-encrypted/index.html.enc"
    else
        mkdir -p "static/private-encrypted/$dir_name"
        mkdir -p "public/private-encrypted/$dir_name"
        static_output="static/private-encrypted/${dir_name}/index.html.enc"
        public_output="public/private-encrypted/${dir_name}/index.html.enc"
    fi

    echo "암호화 중: $file → $static_output"

    # AES-256-CBC 암호화 (static)
    openssl enc -aes-256-cbc \
        -in "$file" \
        -out "$static_output" \
        -K "$PRIVATE_AES_KEY" \
        -iv "00000000000000000000000000000000" \
        -base64

    # public에도 복사
    cp "$static_output" "$public_output"
done

echo ""
echo "✅ 암호화 완료!"
echo ""
echo "암호화된 파일:"
find static/private-encrypted -type f

echo ""
echo "⚠️  보안 팁:"
echo "1. public/private/ 폴더는 배포하지 마세요"
echo "2. static/private-encrypted/ 만 배포됩니다"
echo "3. AES_KEY는 절대 Git에 커밋하지 마세요"
