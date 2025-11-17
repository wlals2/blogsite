# 🚀 비공개 콘텐츠 빠른 시작 가이드

## 1단계: TOTP Secret 생성 (5분)

```bash
./scripts/generate-totp-secret.sh
```

**입력 예시:**
```
📧 이메일: jimin@example.com
🏷️  서비스 이름: JiminBlog
```

**출력 결과:**
```
✅ TOTP Secret 생성 완료!

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📱 Google Authenticator 등록 정보
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

계정: jimin@example.com
발급자: JiminBlog

🔑 TOTP Secret (Base32):
JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📲 QR 코드
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

방법 1: 터미널에서 QR 코드 스캔
(QR 코드 출력됨)

방법 2: 온라인 QR 코드 생성
1. https://www.qr-code-generator.com/ 접속
2. 'URL' 선택
3. 아래 URL 입력:

otpauth://totp/JiminBlog:jimin@example.com?secret=JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP&issuer=JiminBlog

방법 3: 수동 입력
Google Authenticator → '+' → 'Enter a setup key'
Account: jimin@example.com
Key: JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP
Type of key: Time based
```

---

## 2단계: Google Authenticator 등록

### 방법 A: QR 코드 스캔 (추천)

**터미널에 QR 코드가 표시되면:**
1. Google Authenticator 앱 실행
2. "+" 버튼 클릭
3. "QR 코드 스캔" 선택
4. 터미널 QR 코드에 카메라 맞추기

**온라인으로 QR 코드 생성:**
1. https://www.qr-code-generator.com/ 접속
2. "URL" 선택
3. 스크립트가 출력한 `otpauth://` URL 복사 붙여넣기
4. 생성된 QR 코드를 Google Authenticator로 스캔

### 방법 B: 수동 입력

**QR 코드 스캔이 안 될 때:**
1. Google Authenticator → "+" → "Enter a setup key"
2. 정보 입력:
   ```
   Account: jimin@example.com (스크립트 출력 참고)
   Key: JBSWY3DP... (TOTP Secret)
   Type of key: Time based
   ```

---

## 3단계: OTP 확인

Google Authenticator에 등록되면:

```
JiminBlog
jimin@example.com
────────────
  524 891   ⏱️ 15s
────────────
```

- 6자리 숫자가 30초마다 자동 변경
- 이 숫자를 블로그 로그인에 사용

---

## 4단계: 비공개 콘텐츠 작성

```bash
# 디렉토리 생성
mkdir -p content/private/architecture
mkdir -p content/private/secrets
```

**예시: `content/private/architecture/index.md`**

```markdown
---
title: "블로그 실제 아키텍처"
date: 2025-11-17
type: private
---

## 서버 정보

**호스트:** 자택 서버
**OS:** Ubuntu 22.04
**내부 IP:** 192.168.1.100
**외부 IP:** 123.45.67.89

## Cloudflare 설정

**Tunnel ID:** abc-123-def-456
**Zone ID:** xyz-789

## GitHub Runner

**Token:** ghp_xxxxxxxxxxxxxxxxxxxx
```

---

## 5단계: 빌드 및 암호화

```bash
# 1. Hugo 빌드
hugo --minify

# 2. 콘텐츠 암호화
./scripts/encrypt-private-content.sh
```

**출력:**
```
🔐 비공개 콘텐츠 암호화
=======================

암호화 중: public/private/architecture/index.html → static/private-encrypted/architecture.enc
암호화 중: public/private/secrets/index.html → static/private-encrypted/secrets.enc

✅ 암호화 완료!
```

---

## 6단계: 로컬 테스트

```bash
# Hugo 개발 서버 시작
hugo server -D
```

**브라우저 접속:**
1. http://localhost:1313/private/
2. Google Authenticator에서 OTP 확인
3. 6자리 숫자 입력
4. "AUTHENTICATE" 클릭

**성공하면:**
- ✅ ACCESS GRANTED 메시지
- 비공개 콘텐츠 목록 표시
- 각 글 클릭하여 확인

---

## 7단계: GitHub Secrets 등록

### GitHub 저장소 설정

1. **GitHub 저장소 접속**
   ```
   https://github.com/your-username/your-repo
   ```

2. **Settings → Secrets and variables → Actions**

3. **New repository secret 클릭**

4. **다음 2개 Secret 추가:**

#### Secret 1: PRIVATE_TOTP_SECRET

```
Name: PRIVATE_TOTP_SECRET
Secret: JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP
(스크립트가 생성한 TOTP Secret)
```

#### Secret 2: PRIVATE_AES_KEY

```
Name: PRIVATE_AES_KEY
Secret: 0123456789abcdef0123456789abcdef...
(.env 파일의 PRIVATE_AES_KEY 값)
```

### .env 파일 확인

```bash
cat .env
```

출력 예시:
```bash
# TOTP 설정
PRIVATE_TOTP_SECRET=JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP

# AES-256 암호화 키 (64자 hex)
PRIVATE_AES_KEY=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef

# Google Authenticator 정보
TOTP_ACCOUNT=jimin@example.com
TOTP_ISSUER=JiminBlog
```

위 값들을 GitHub Secrets에 등록

---

## 8단계: 배포

```bash
# 1. Git 커밋 (암호화된 파일만)
git add static/private-encrypted/
git add layouts/private/
git commit -m "Add: 암호화된 비공개 콘텐츠"

# 2. 푸시
git push origin main
```

**GitHub Actions 자동 실행:**
- Hugo 빌드
- 콘텐츠 암호화
- 배포

---

## 9단계: 실제 블로그에서 테스트

1. **비공개 영역 접속**
   ```
   https://yourblog.com/private/
   ```

2. **Google Authenticator 확인**
   - 현재 OTP 확인 (예: 524891)

3. **OTP 입력**
   - 6자리 숫자 입력
   - "AUTHENTICATE" 클릭

4. **콘텐츠 접근**
   - 인증 성공 시 목록 표시
   - 각 글 클릭하여 확인

---

## 🎯 요약

```
1. ./scripts/generate-totp-secret.sh  → TOTP Secret 생성
2. Google Authenticator 등록         → QR 코드 스캔
3. content/private/ 글 작성          → 비공개 콘텐츠
4. hugo --minify                      → 빌드
5. ./scripts/encrypt-private-content.sh → 암호화
6. GitHub Secrets 등록                → TOTP, AES Key
7. git push                           → 배포
8. /private/ 접속                     → OTP로 인증
```

---

## ⚠️ 주의사항

### 절대 Git에 커밋하지 마세요

```bash
❌ .env                    # TOTP Secret, AES Key
❌ content/private/        # 암호화 전 원본
❌ public/private/         # 빌드된 원본

✅ static/private-encrypted/  # 암호화된 파일만 OK
✅ .env.example            # 템플릿만 OK
```

### .gitignore 확인

```bash
cat .gitignore | grep -E "(env|private)"
```

출력:
```
.env
.env.local
content/private/
```

---

## 🔧 문제 해결

### OTP가 안 맞아요

1. **시간 동기화 확인**
   ```bash
   # 서버 시간 확인
   date

   # NTP 동기화
   sudo timedatectl set-ntp true
   ```

2. **Google Authenticator 재등록**
   - 기존 항목 삭제
   - QR 코드 다시 스캔

3. **TOTP Secret 확인**
   ```bash
   cat .env | grep TOTP_SECRET
   ```

### 암호화가 안 돼요

```bash
# .env 파일 확인
cat .env

# AES_KEY가 있는지 확인
# 64자 hex 문자열 (0-9, a-f)
```

### 배포 후 접속 안 돼요

```bash
# GitHub Secrets 확인
# Settings → Secrets → Actions
# PRIVATE_TOTP_SECRET 등록 확인
# PRIVATE_AES_KEY 등록 확인
```

---

## 💡 다음 단계

### Cloudflare Access 추가 (보안 2배 강화)

```
1. Cloudflare Dashboard 접속
2. Access → Applications
3. Add an application
4. Path: yourblog.com/private/*
5. Policy: Email OTP
```

**효과:**
- 이메일 OTP (1차) + TOTP (2차)
- 이중 인증
- Cloudflare 수준 보안

---

**완료! 궁금한 점 있으면 PRIVATE-CONTENT-GUIDE.md 참고** 🎉
