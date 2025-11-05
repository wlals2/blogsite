echo "📦 배포 시작..."

# 1. 디렉토리 생성
sudo mkdir -p /var/www/blog

# 2. 파일 복사
echo "✓ rsync 실행 중..."
sudo rsync -av --delete public/ /var/www/blog/
echo "✓ rsync 완료"

# 3. 소유권 변경 (www-data로 통일!)
echo "✓ 권한 설정 중..."
sudo chown -R www-data:www-data /var/www/blog
sudo chmod -R 755 /var/www/blog
sudo find /var/www/blog -type f -exec chmod 644 {} \;
echo "✓ 권한 설정 완료"

# 4. 파일 확인
echo "✓ 배포된 파일 확인..."
ls -lah /var/www/blog/ | head -10

# 5. Nginx 설정 테스트
echo "✓ nginx 설정 테스트 중..."
sudo nginx -t

# 6. Nginx 재시작
echo "✓ nginx 재시작 중..."
sudo systemctl reload nginx
echo "✓ nginx 재시작 완료"

# 7. 배포 확인
echo "✅ 배포 완료!"
echo "🌐 블로그: https://blog.jiminhome.shop"

# 8. 접속 테스트
echo "✓ 접속 테스트..."
curl -I https://blog.jiminhome.shop || echo "⚠️  접속 테스트 실패"
