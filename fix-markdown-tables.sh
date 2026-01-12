#!/bin/bash
# Hugo 블로그의 모든 Markdown 파일에서 테이블/코드블록 포맷 수정

set -e

CONTENT_DIR="$HOME/blogsite/content"

echo "🔧 Fixing Markdown table/code formatting for Hugo..."
echo "📂 Directory: $CONTENT_DIR"
echo ""

# 임시 Python 스크립트 생성
cat > /tmp/fix_hugo_markdown.py << 'EOF'
import sys
import re

file_path = sys.argv[1]

try:
    with open(file_path, 'r', encoding='utf-8') as f:
        content = f.read()

    original = content

    # 1. 테이블 앞에 빈 줄 추가 (헤더 h1-h6 모두 포함)
    content = re.sub(r'(^#{1,6}\s+[^\n]*)\n(\|[^\n]*\|)', r'\1\n\n\2', content, flags=re.MULTILINE)

    # 2. 일반 텍스트 다음 테이블에도 빈 줄 추가
    content = re.sub(r'(^[^|\n#\-*][^\n]+)\n(\|[^\n]*\|)', r'\1\n\n\2', content, flags=re.MULTILINE)

    # 3. 테이블 뒤에 빈 줄 추가 (헤더 앞)
    content = re.sub(r'(\|[^\n]*\|)\n(#{1,6}\s)', r'\1\n\n\2', content, flags=re.MULTILINE)

    # 4. 테이블 뒤에 빈 줄 추가 (일반 텍스트 앞)
    content = re.sub(r'(\|[^\n]*\|)\n([^|\n#\-*])', r'\1\n\n\2', content, flags=re.MULTILINE)

    # 5. 코드블록 앞에 빈 줄 추가
    content = re.sub(r'(^#{1,6}\s+[^\n]*)\n(```)', r'\1\n\n\2', content, flags=re.MULTILINE)
    content = re.sub(r'(^[^`\n#\-*][^\n]+)\n(```)', r'\1\n\n\2', content, flags=re.MULTILINE)

    # 6. 코드블록 뒤에 빈 줄 추가
    content = re.sub(r'(```)\n(#{1,6}\s)', r'\1\n\n\2', content, flags=re.MULTILINE)
    content = re.sub(r'(```)\n([^`\n#\-])', r'\1\n\n\2', content, flags=re.MULTILINE)

    # 7. blockquote 앞에 빈 줄 추가
    content = re.sub(r'(^[^>\n#\-*][^\n]+)\n(^>)', r'\1\n\n\2', content, flags=re.MULTILINE)

    # 8. 중복된 빈 줄 제거 (4개 이상 → 3개로)
    content = re.sub(r'\n{5,}', '\n\n\n\n', content)

    # 파일이 변경되었으면 저장
    if content != original:
        with open(file_path, 'w', encoding='utf-8') as f:
            f.write(content)
        print(f"✅ Fixed")
    else:
        print(f"⏭️  No changes")

except Exception as e:
    print(f"❌ Error: {e}")
    sys.exit(1)
EOF

# 모든 Markdown 파일 처리
find "$CONTENT_DIR" -name "*.md" ! -name "_index.md" | while read -r file; do
    echo -n "📝 ${file#$CONTENT_DIR/} ... "
    python3 /tmp/fix_hugo_markdown.py "$file"
done

# 임시 파일 삭제
rm -f /tmp/fix_hugo_markdown.py

echo ""
echo "🎉 Processing complete!"
echo ""
echo "📊 Summary:"
find "$CONTENT_DIR" -name "*.md" ! -name "_index.md" | wc -l | xargs echo "   Total files processed:"
echo ""
echo "🚀 Next steps:"
echo "   cd ~/blogsite"
echo "   git diff content/projects/bespin-project.md | head -100  # 확인"
echo "   git add ."
echo "   git commit -m 'Fix: Hugo table/code block formatting'"
echo "   git push"
