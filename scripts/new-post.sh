#!/bin/bash

# 블로그 글 작성 스크립트
# 사용법: ./new-post.sh [카테고리] [제목]
# 예시: ./new-post.sh study "Istio Gateway 설정"

set -e

# 색상 코드
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 블로그 루트 디렉토리
BLOG_ROOT="/home/jimin/blogsite"
CONTENT_DIR="$BLOG_ROOT/content"

# 카테고리 선택
echo -e "${BLUE}📝 블로그 글 작성${NC}"
echo ""
echo "사용 가능한 카테고리:"
echo "  1) study   - 학습 내용"
echo "  2) posts   - 일반 포스트"
echo "  3) til     - Today I Learned"
echo "  4) projects - 프로젝트"
echo ""

if [ -z "$1" ]; then
    read -p "카테고리를 선택하세요 (기본: study): " CATEGORY
    CATEGORY=${CATEGORY:-study}
else
    CATEGORY=$1
fi

# 카테고리 유효성 검사
if [[ ! "$CATEGORY" =~ ^(study|posts|til|projects)$ ]]; then
    echo -e "${RED}❌ 잘못된 카테고리입니다. study, posts, til, projects 중 하나를 선택하세요.${NC}"
    exit 1
fi

# 제목 입력
if [ -z "$2" ]; then
    read -p "제목을 입력하세요: " TITLE
else
    TITLE=$2
fi

if [ -z "$TITLE" ]; then
    echo -e "${RED}❌ 제목을 입력해주세요.${NC}"
    exit 1
fi

# 태그 입력
echo ""
read -p "태그를 입력하세요 (쉼표로 구분, 예: kubernetes,istio,service-mesh): " TAGS_INPUT

# 날짜 생성
DATE=$(date +%Y-%m-%d)
TIME=$(date +%H:%M:%S)

# 파일명 생성 (제목을 소문자로 변환하고 공백을 하이픈으로 변경)
FILENAME="${DATE}-$(echo "$TITLE" | tr '[:upper:]' '[:lower:]' | sed 's/ /-/g' | sed 's/[^a-z0-9-]//g').md"
FILEPATH="$CONTENT_DIR/$CATEGORY/$FILENAME"

# 카테고리 자동 추천 (study 카테고리인 경우)
SUGGESTED_CATEGORIES=""
if [ "$CATEGORY" = "study" ] && [ -n "$TAGS_INPUT" ]; then
    echo ""
    echo -e "${YELLOW}🤖 카테고리 자동 추천 중...${NC}"

    # Python 스크립트 실행
    SUGGEST_OUTPUT=$(python3 "$BLOG_ROOT/scripts/suggest-category.py" "$TITLE" "$TAGS_INPUT" 2>/dev/null || echo "")

    if [ -n "$SUGGEST_OUTPUT" ]; then
        echo "$SUGGEST_OUTPUT"
        echo ""
        read -p "추천 카테고리를 사용하시겠습니까? (Y/n): " USE_SUGGESTED

        if [[ "$USE_SUGGESTED" != "n" && "$USE_SUGGESTED" != "N" ]]; then
            # 첫 번째 추천 카테고리 추출 (줄에서 "1. " 다음의 카테고리명)
            FIRST_CATEGORY=$(echo "$SUGGEST_OUTPUT" | grep "✅ 1\." | sed 's/.*1\. \([A-Za-z &]*\).*/\1/' | xargs)
            if [ -n "$FIRST_CATEGORY" ]; then
                SUGGESTED_CATEGORIES="\"study\", \"$FIRST_CATEGORY\""
            fi
        fi
    fi
fi

# 카테고리 기본값 설정
if [ -z "$SUGGESTED_CATEGORIES" ]; then
    if [ "$CATEGORY" = "study" ]; then
        read -p "서브 카테고리를 입력하세요 (예: Kubernetes, 선택사항): " SUB_CATEGORY
        if [ -n "$SUB_CATEGORY" ]; then
            SUGGESTED_CATEGORIES="\"study\", \"$SUB_CATEGORY\""
        else
            SUGGESTED_CATEGORIES="\"study\""
        fi
    else
        SUGGESTED_CATEGORIES="\"$CATEGORY\""
    fi
fi

# 태그 배열 생성
if [ -n "$TAGS_INPUT" ]; then
    IFS=',' read -ra TAGS_ARRAY <<< "$TAGS_INPUT"
    TAGS_FORMATTED=""
    for tag in "${TAGS_ARRAY[@]}"; do
        tag=$(echo "$tag" | xargs)  # 공백 제거
        if [ -z "$TAGS_FORMATTED" ]; then
            TAGS_FORMATTED="\"$tag\""
        else
            TAGS_FORMATTED="$TAGS_FORMATTED, \"$tag\""
        fi
    done
else
    TAGS_FORMATTED=""
fi

# Front Matter 생성
cat > "$FILEPATH" <<EOF
---
title: "$TITLE"
date: ${DATE}T${TIME}+09:00
draft: false
description: ""
tags: [$TAGS_FORMATTED]
categories: [$SUGGESTED_CATEGORIES]
---

## 개요



## 내용



## 정리



---

**작성일**: $DATE
EOF

echo ""
echo -e "${GREEN}✅ 글이 생성되었습니다!${NC}"
echo -e "${BLUE}📄 파일 경로: $FILEPATH${NC}"
echo ""

# 에디터로 열기
if command -v code &> /dev/null; then
    echo -e "${GREEN}🚀 VS Code로 파일을 엽니다...${NC}"
    code "$FILEPATH"
elif command -v vim &> /dev/null; then
    echo -e "${GREEN}🚀 Vim으로 파일을 엽니다...${NC}"
    vim "$FILEPATH"
else
    echo -e "${YELLOW}⚠️  에디터를 찾을 수 없습니다. 직접 파일을 여세요.${NC}"
fi

echo ""
echo -e "${BLUE}💡 팁:${NC}"
echo "  - description 필드에 한 줄 요약을 작성하세요"
echo "  - draft: true로 설정하면 배포되지 않습니다"
echo "  - 작성 완료 후 git add, commit, push 하세요"
echo ""
