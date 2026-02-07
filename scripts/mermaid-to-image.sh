#!/bin/bash
# 머메이드 차트를 PNG 이미지로 변환하는 스크립트

set -e

# mermaid-cli 설치 확인
if ! command -v mmdc &> /dev/null; then
    echo "❌ mermaid-cli가 설치되지 않았습니다."
    echo "설치: npm install -g @mermaid-js/mermaid-cli"
    exit 1
fi

# 사용법 체크
if [ "$#" -lt 1 ]; then
    echo "사용법: $0 <markdown-file-path>"
    echo "예시: $0 content/study/2026-02-06-kubernetes-service-pod-connection/index.md"
    exit 1
fi

MARKDOWN_FILE="$1"
POST_DIR=$(dirname "$MARKDOWN_FILE")

if [ ! -f "$MARKDOWN_FILE" ]; then
    echo "❌ 파일을 찾을 수 없습니다: $MARKDOWN_FILE"
    exit 1
fi

echo "📝 머메이드 코드 블록 추출 중..."

# 임시 디렉토리 생성
TEMP_DIR=$(mktemp -d)
trap "rm -rf $TEMP_DIR" EXIT

# 머메이드 코드 블록 추출 (```mermaid ... ``` 사이 내용)
awk '/^```mermaid$/,/^```$/ {
    if (/^```mermaid$/) {
        counter++
        next
    }
    if (/^```$/) {
        next
    }
    print > "'"$TEMP_DIR"'/mermaid-" counter ".mmd"
}' "$MARKDOWN_FILE"

# 추출된 머메이드 파일 개수 확인
MERMAID_COUNT=$(ls -1 "$TEMP_DIR"/mermaid-*.mmd 2>/dev/null | wc -l)

if [ "$MERMAID_COUNT" -eq 0 ]; then
    echo "⚠️  머메이드 코드 블록을 찾을 수 없습니다."
    exit 0
fi

echo "✅ $MERMAID_COUNT 개의 머메이드 차트를 찾았습니다."

# 각 머메이드 파일을 PNG로 변환
for mmd_file in "$TEMP_DIR"/mermaid-*.mmd; do
    filename=$(basename "$mmd_file" .mmd)
    png_output="$POST_DIR/${filename}.png"

    echo "🖼️  변환 중: $filename.mmd → ${filename}.png"

    # PNG 생성 (1200px 너비, 투명 배경)
    mmdc -i "$mmd_file" \
         -o "$png_output" \
         -w 1200 \
         -b transparent \
         -t default

    if [ $? -eq 0 ]; then
        echo "   ✅ 생성 완료: $png_output"
    else
        echo "   ❌ 변환 실패: $filename"
    fi
done

echo ""
echo "🎉 완료! 생성된 이미지를 확인하세요:"
ls -lh "$POST_DIR"/mermaid-*.png 2>/dev/null || echo "   (이미지가 없습니다)"
echo ""
echo "📌 다음 단계:"
echo "1. 생성된 PNG 파일을 확인하여 차트가 제대로 렌더링되었는지 검수"
echo "2. index.md에서 \`\`\`mermaid 블록을 이미지로 교체:"
echo "   ![다이어그램 설명](mermaid-1.png)"
