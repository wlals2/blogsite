---
title: "nginx와 친해지기"
date: 2025-11-17T09:09:15+09:00
draft: false
categories: ["study", "Networking"]
tags: ["nginx","명령어","명령어 정리"]
description: "nginx와 친해지기"
author: "늦찌민"
---

1단계: Nginx 실행 확인
# Nginx 프로세스 확인
ps aux | grep nginx
출력 예시:
root         959  nginx: master process
www-data    1234  nginx: worker process
의미: master process가 메인, worker process가 실제 처리
2단계: Nginx 실행 파일 위치 확인
# Nginx 실행 파일 찾기
which nginx

# 또는
whereis nginx
출력:
/usr/sbin/nginx
3단계: 메인 설정 파일 위치 찾기
# Nginx가 사용 중인 설정 파일 확인
sudo nginx -V 2>&1 | grep -o 'conf-path=\S*'
출력:
conf-path=/etc/nginx/nginx.conf
또는 간단하게:
sudo nginx -t
출력:
nginx: configuration file /etc/nginx/nginx.conf test is successful
4단계: 메인 설정 파일 구조 확인
# nginx.conf 파일 읽기
cat /etc/nginx/nginx.conf
중요한 부분 찾기:
# include 지시어 찾기 (다른 설정 파일들이 어디 있는지)
grep -n "include" /etc/nginx/nginx.conf
일반적인 출력:
14:    include /etc/nginx/modules-enabled/*.conf;
62:    include /etc/nginx/mime.types;
89:    include /etc/nginx/conf.d/*.conf;
90:    include /etc/nginx/sites-enabled/*;
5단계: 활성화된 사이트 설정 찾기
# sites-enabled 디렉토리 확인
ls -la /etc/nginx/sites-enabled/
출력 예시:
default -> /etc/nginx/sites-available/default
blog -> /etc/nginx/sites-available/blog
의미: 심볼릭 링크 → 실제 파일 위치
6단계: 모든 설정 파일 위치 확인
# Nginx 관련 모든 디렉토리 확인
ls -la /etc/nginx/
주요 디렉토리:
nginx.conf - 메인 설정
sites-available/ - 사용 가능한 사이트 설정
sites-enabled/ - 활성화된 사이트 (심볼릭 링크)
conf.d/ - 추가 설정
snippets/ - 재사용 가능한 설정 조각
7단계: 특정 도메인 설정 찾기
# 'blog.jiminhome.shop' 문자열을 포함한 설정 파일 찾기
grep -r "blog.jiminhome.shop" /etc/nginx/
또는:
# server_name 지시어 찾기
grep -r "server_name" /etc/nginx/sites-enabled/
8단계: 설정 파일 내용 확인
# 찾은 설정 파일 읽기
cat /etc/nginx/sites-enabled/blog
또는 줄번호와 함께:
cat -n /etc/nginx/sites-enabled/blog
9단계: 주요 설정 값 추출
# 리스닝 포트 확인
grep "listen" /etc/nginx/sites-enabled/*

# 도메인 확인
grep "server_name" /etc/nginx/sites-enabled/*

# 문서 루트 확인
grep "root" /etc/nginx/sites-enabled/*

# SSL 인증서 확인
grep "ssl_certificate" /etc/nginx/sites-enabled/*
10단계: 로그 파일 위치 확인
# 로그 파일 위치 찾기
grep -r "access_log\|error_log" /etc/nginx/sites-enabled/
일반적인 위치:
/var/log/nginx/access.log
/var/log/nginx/error.log
11단계: 문서 루트(실제 파일 위치) 확인
# root 지시어에서 문서 위치 추출
grep -h "root" /etc/nginx/sites-enabled/* | grep -v "#"
출력 예시:
root /var/www/blog;
해당 디렉토리 확인:
ls -lah /var/www/blog/ | head -10
12단계: 전체 설정 검증
# 설정 파일 문법 검사
sudo nginx -t

# 설정 파일 전체 내용 확인 (include 파일까지)
sudo nginx -T
실전 예시: 처음부터 끝까지
# 1. Nginx 실행 확인
systemctl status nginx

# 2. 메인 설정 파일 찾기
sudo nginx -t

# 3. 메인 설정 파일 읽기
cat /etc/nginx/nginx.conf | grep include

# 4. 활성화된 사이트 목록
ls -la /etc/nginx/sites-enabled/

# 5. 특정 사이트 설정 읽기
cat /etc/nginx/sites-enabled/blog

# 6. 문서 루트 확인
grep "root" /etc/nginx/sites-enabled/blog

# 7. 실제 파일 확인
ls -lah /var/www/blog/

# 8. 로그 확인
tail -f /var/log/nginx/access.log
추가 팁
설정 파일 백업
sudo cp -r /etc/nginx /etc/nginx.backup.$(date +%Y%m%d)
설정 변경 후 재시작
# 1. 문법 검사
sudo nginx -t

# 2. 재시작
sudo systemctl reload nginx
특정 포트 사용 중인 프로세스 확인
sudo netstat -tlnp | grep :443
# 또는
sudo ss -tlnp | grep :443
이 순서대로 하면 Nginx 설정을 완벽히 파악할 수 있습니다!

