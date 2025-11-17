#!/bin/bash

#Hugo 포스트/스터디/비공개 생성 스크립트

echo "Hugo 콘텐츠 생성기"
echo "===================="

#타입 선택 (study or posts or private)
echo "1. study"
echo "2. posts"
echo "3. private (OTP 보호)"
read -p "선택하세요 (1/2/3): " type_choice

#제목 입력
read -p "제목을 입력하세요: " title

echo "선택 $type_choice, 제목: $title"

if [ "$type_choice" = "1" ]; then
        content_type="study"
elif [ "$type_choice" = "2" ]; then
        content_type="posts"
elif [ "$type_choice" = "3" ]; then
        content_type="private"
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

# ========== Private 콘텐츠 자동 배포 ==========
if [ "$content_type" = "private" ]; then
    echo ""
    echo "🔐 비공개 콘텐츠 배포 시작..."
    echo "================================"

    # .env 파일에서 환경변수 로드
    if [ -f .env ]; then
        source .env
        echo "✅ .env 파일 로드 완료"
    else
        echo "❌ .env 파일이 없습니다!"
        echo "   ./scripts/generate-totp-secret.sh 실행하세요"
        exit 1
    fi

    # 1. Hugo 빌드
    echo ""
    echo "📦 Hugo 빌드 중..."
    export PRIVATE_TOTP_SECRET
    export PRIVATE_AES_KEY
    hugo --minify

    if [ $? -ne 0 ]; then
        echo "❌ Hugo 빌드 실패!"
        exit 1
    fi
    echo "✅ Hugo 빌드 완료"

    # 2. 배포
    echo ""
    echo "🚀 서버에 배포 중..."
    sudo rsync -avh --delete public/ /var/www/blog/

    if [ $? -ne 0 ]; then
        echo "❌ 배포 실패!"
        exit 1
    fi

    echo ""
    echo "================================"
    echo "✅ 비공개 콘텐츠 배포 완료!"
    echo ""
    echo "📍 접속 URL: https://blog.jiminhome.shop/private/"
    echo "🔑 Google Authenticator에서 OTP 확인 후 접속하세요"
    echo ""
fi
