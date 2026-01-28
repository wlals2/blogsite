#!/bin/bash

# Mermaid 차트를 PNG로 변환하는 스크립트 (슬라이드용 최적화)
# 사용법: ./convert-mermaid.sh

set -e

MERMAID_DIR="./mermaid-codes"
OUTPUT_DIR="./images"
MMDC="$HOME/.local/node_modules/.bin/mmdc"

# mmdc 설치 확인
if [ ! -f "$MMDC" ]; then
    echo "❌ mermaid-cli가 설치되지 않았습니다."
    echo ""
    echo "설치 방법:"
    echo "  npm install --prefix ~/.local @mermaid-js/mermaid-cli"
    echo ""
    exit 1
fi

# 출력 디렉터리 생성
mkdir -p "$OUTPUT_DIR"

echo "🎨 Mermaid 차트를 PNG로 변환합니다..."
echo "📡 Using mermaid-cli (mmdc) - High Quality"
echo ""

# 각 .mmd 파일 처리
for mmd_file in "$MERMAID_DIR"/*.mmd; do
    if [ ! -f "$mmd_file" ]; then
        echo "⚠️  No .mmd files found in $MERMAID_DIR"
        exit 1
    fi

    filename=$(basename "$mmd_file" .mmd)
    # 번호 제거 (01-network-flow → network-flow)
    output_name="${filename#??-}"
    output_file="$OUTPUT_DIR/${output_name}.png"

    echo "📄 Processing: $filename"
    echo "🔄 Converting to PNG..."

    # mermaid-cli 사용 (고품질 PNG)
    "$MMDC" -i "$mmd_file" \
            -o "$output_file" \
            -w 1600 \
            -b white \
            2>&1 | grep -v "DevTools" | tail -1

    if [ -f "$output_file" ]; then
        size=$(ls -lh "$output_file" | awk '{print $5}')
        echo "✅ Saved: $output_file ($size)"
    else
        echo "❌ Failed to convert $filename"
    fi

    echo ""
done

echo "🎉 변환 완료! 이미지는 $OUTPUT_DIR/ 에 저장되었습니다."
echo ""
echo "📋 생성된 파일:"
ls -lh "$OUTPUT_DIR"/*.png 2>/dev/null || echo "❌ No PNG files generated"
