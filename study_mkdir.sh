#!/bin/bash

#Hugo 포스트/스터디 생성 스크립트

echo "Hugo 콘텐츠 생성기"
echo "===================="

#타입 선택 (study or postss)
echo "1. study"
echo "2. posts"
read -p "선택하세요 (1 or 2): " type_choice

#제목 입력
read -p "제목을 입력하세요: " title

echo "선택 $type_choice, 제목: $title"

if [ "$type_choice" = "1" ]; then
        content_type="study"
elif [ "$type_choice" = "2" ]; then
        content_type="posts"
else
        echo "잘못된 선택입니다!"
        exit 1
fi

echo "타입: $content_type"
echo "제목: $title"

current_date=$(date +%Y-%m-%dT%H:%M:%S%:z)  # 타임존 포함

# 제목을 소문자로 변환하고 공백을 하이픈으로 (slug 만들기)
slug=$(echo "$title" | tr '[:upper:]' '[:lower:]' | tr ' ' '-')

dir_name="${current_date}-${slug}"
echo "생성될 디렉터리: content/$content_type/$dir_name"

# 전체 경로
full_path="content/$content_type/$dir_name"

#디렉터리 생성
mkdir -p "$full_path"

# index.md
cat > "$full_path/index.md" << EOF
---
title: "$title"
date: $current_date
draft: false
categories: []
tags: []
description: "$title"
author: "늦찌민"
---

## 내용을 작성하세요

EOF

echo "✅ 생성 완료: $full_path/index.md"
