#!/bin/bash
# Hugo 개발 환경 빠른 테스트 스크립트

set -e

echo "🚀 Hugo 개발 환경 테스트"
echo "========================"
echo ""

# 메뉴 선택
echo "어떤 환경을 실행하시겠습니까?"
echo "1) Hugo 개발 서버 (실시간 미리보기, http://localhost:1313)"
echo "2) 프로덕션 빌드 테스트 (Nginx, http://localhost:8080)"
echo "3) 빌드만 실행 (public-test/ 폴더에 저장)"
echo "4) 전체 중지 및 정리"
echo ""
read -p "선택 (1-4): " choice

case $choice in
  1)
    echo ""
    echo "📝 Hugo 개발 서버 시작 중..."
    echo "   - http://localhost:1313 으로 접속하세요"
    echo "   - 파일 저장 시 자동 새로고침됩니다"
    echo "   - Ctrl+C로 종료하세요"
    echo ""
    docker compose -f docker-compose.dev.yml up hugo-dev
    ;;

  2)
    echo ""
    echo "🔨 프로덕션 빌드 + Nginx 서버 시작 중..."
    echo ""

    # 기존 빌드 결과 삭제
    rm -rf public-test/

    # 빌드 실행
    echo "1/2) Hugo 빌드 중..."
    docker compose -f docker-compose.dev.yml run --rm hugo-build

    # 빌드 결과 확인
    echo ""
    echo "빌드 완료! 결과:"
    echo "  - HTML 페이지: $(find public-test -name '*.html' 2>/dev/null | wc -l)개"
    echo "  - 전체 파일: $(find public-test -type f 2>/dev/null | wc -l)개"
    echo ""

    # Nginx 시작
    echo "2/2) Nginx 서버 시작 중..."
    echo "   - http://localhost:8080 으로 접속하세요"
    echo "   - Ctrl+C로 종료하세요"
    echo ""
    docker compose -f docker-compose.dev.yml up nginx-test
    ;;

  3)
    echo ""
    echo "🔨 빌드만 실행 중..."
    echo ""

    # 기존 빌드 결과 삭제
    rm -rf public-test/

    # 빌드 실행
    docker compose -f docker-compose.dev.yml run --rm hugo-build

    # 결과 출력
    echo ""
    echo "✅ 빌드 완료!"
    echo ""
    echo "빌드 결과 (public-test/):"
    ls -lh public-test/ | head -15
    echo ""
    echo "통계:"
    echo "  - HTML 페이지: $(find public-test -name '*.html' | wc -l)개"
    echo "  - 이미지: $(find public-test -name '*.jpg' -o -name '*.png' -o -name '*.webp' | wc -l)개"
    echo "  - 전체 크기: $(du -sh public-test/ | cut -f1)"
    ;;

  4)
    echo ""
    echo "🧹 전체 중지 및 정리 중..."

    # 컨테이너 중지
    docker compose -f docker-compose.dev.yml down

    # 빌드 결과 삭제
    rm -rf public-test/

    echo "✅ 정리 완료!"
    ;;

  *)
    echo "잘못된 선택입니다."
    exit 1
    ;;
esac
