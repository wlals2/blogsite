# Cloudflare DDNS 설정 가이드

## 1단계: Cloudflare API Token 생성

1. https://dash.cloudflare.com/profile/api-tokens 접속
2. "Create Token" 클릭
3. "Edit zone DNS" 템플릿 사용
4. 권한 설정:
   - Zone - DNS - Edit
   - Zone Resources: jiminhome.shop
5. 토큰 복사

## 2단계: Zone ID 확인

1. https://dash.cloudflare.com 접속
2. jiminhome.shop 선택
3. 우측 "API" 섹션에서 Zone ID 복사

## 3단계: 스크립트 설정

```bash
cd /home/jimin/blogsite
nano update-cloudflare-ip.sh
```

다음 항목 수정:
- `ZONE_ID="여기에_Zone_ID_입력"`
- `API_TOKEN="여기에_API_Token_입력"`

## 4단계: 수동 테스트

```bash
cd /home/jimin/blogsite
./update-cloudflare-ip.sh
```

## 5단계: 자동 실행 설정 (Cron)

5분마다 자동으로 IP 확인 및 업데이트:

```bash
crontab -e
```

다음 줄 추가:
```
*/5 * * * * /home/jimin/blogsite/update-cloudflare-ip.sh >> /home/jimin/blogsite/ddns.log 2>&1
```

## 6단계: Cloudflare Proxy 설정 (선택사항)

- **Proxy OFF (DNS only)**: 직접 서버 IP로 연결 (DDNS 사용 시 추천)
- **Proxy ON**: Cloudflare를 거쳐서 연결 (DDoS 방어, 하지만 SSL 설정 필요)

Proxy OFF로 설정하려면 스크립트에서:
```bash
"proxied":false
```

Proxy ON으로 설정하려면:
```bash
"proxied":true
```

## 트러블슈팅

### 로그 확인
```bash
tail -f /home/jimin/blogsite/ddns.log
```

### 현재 DNS 확인
```bash
nslookup blog.jiminhome.shop
```

### 현재 공인 IP 확인
```bash
curl ifconfig.me
```

## 보안 주의사항

- API Token은 절대 GitHub 등에 커밋하지 마세요
- .gitignore에 추가:
```bash
echo "update-cloudflare-ip.sh" >> .gitignore
echo "ddns.log" >> .gitignore
```
