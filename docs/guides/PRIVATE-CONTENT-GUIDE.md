# 🔐 비공개 콘텐츠 보안 시스템 가이드

## 🎯 개요

정적 Hugo 블로그에 **TOTP 인증 + AES-256 암호화**를 적용한 비공개 콘텐츠 시스템입니다.

### 보안 기능

1. **TOTP (Time-based OTP)**: Google Authenticator 사용
2. **AES-256 암호화**: 콘텐츠 클라이언트 측 암호화
3. **Rate Limiting**: 무차별 대입 공격 방지 (5회 실패 시 5분 잠금)
4. **Session 관리**: 1시간 유효한 세션
5. **난독화**: 소스 코드 난독화 (선택)

---

## 🚀 빠른 시작

### 1단계: TOTP Secret 생성

```bash
chmod +x scripts/generate-totp-secret.sh
./scripts/generate-totp-secret.sh
```

**출력:**
```
TOTP Secret (Base32): JBSWY3DPEHPK3PXP
QR 코드: (Google Authenticator로 스캔)
```

### 2단계: .env 파일 생성

```bash
cp .env.example .env
```

`.env` 파일 내용:
```bash
# TOTP Secret (Google Authenticator)
PRIVATE_TOTP_SECRET=JBSWY3DPEHPK3PXP

# AES 암호화 키 (64자 hex)
PRIVATE_AES_KEY=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
```

### 3단계: Google Authenticator 등록

1. Google Authenticator 앱 실행
2. "+" 버튼 → "QR 코드 스캔"
3. 생성된 QR 코드 스캔
4. 6자리 OTP 생성 확인

### 4단계: 비공개 콘텐츠 작성

```bash
# 비공개 섹션 생성
mkdir -p content/private/architecture
```

`content/private/architecture/index.md`:
```markdown
---
title: "실제 블로그 아키텍처"
date: 2025-11-17
type: private
---

## 서버 구성

**호스트:** 자택 서버 (Ubuntu 22.04)
**IP:** 192.168.X.100 (내부), 1.2.3.4 (외부)

## 네트워크 토폴로지

```
Internet
  ↓
Cloudflare
  ↓
자택 공유기 (포트포워딩: 80, 443)
  ↓
홈 서버 (192.168.X.100)
  ├── Nginx (리버스 프록시)
  ├── Hugo (정적 사이트)
  └── Docker Containers
```

## 민감한 설정

**Cloudflare Tunnel ID:** abc-123-def-456
**GitHub Runner Token:** ghp_xxxxxxxxxxxxx
```

### 5단계: Hugo 빌드

```bash
# 일반 빌드 (비공개 콘텐츠 포함)
hugo --minify

# 결과 확인
ls -la public/private/
```

### 6단계: 콘텐츠 암호화

```bash
chmod +x scripts/encrypt-private-content.sh
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

### 7단계: 배포

```bash
# 1. 암호화된 콘텐츠만 Git에 커밋
git add static/private-encrypted/
git commit -m "Add: 암호화된 비공개 콘텐츠"

# 2. 환경변수를 GitHub Secrets에 등록
# GitHub Repository → Settings → Secrets → Actions

# 3. 푸시하여 배포
git push origin main
```

---

## 🔧 GitHub Actions 설정

### GitHub Secrets 등록

1. GitHub 저장소 → Settings → Secrets and variables → Actions
2. New repository secret 클릭
3. 다음 Secrets 추가:

| Name | Value |
|------|-------|
| `PRIVATE_TOTP_SECRET` | TOTP Secret (예: JBSWY3DPEHPK3PXP) |
| `PRIVATE_AES_KEY` | AES 키 (64자 hex) |

### deploy.yml 수정

```yaml
- name: Build (production)
  env:
    HUGO_ENV: production
    PRIVATE_TOTP_SECRET: ${{ secrets.PRIVATE_TOTP_SECRET }}
    PRIVATE_AES_KEY: ${{ secrets.PRIVATE_AES_KEY }}
  run: |
    hugo --minify
    # 콘텐츠 암호화
    ./scripts/encrypt-private-content.sh
```

---

## 🎨 사용 방법

### 웹에서 접속

1. https://yourblog.com/private/ 접속
2. Google Authenticator에서 6자리 OTP 확인
3. OTP 입력 후 "AUTHENTICATE" 클릭
4. 인증 성공 시 비공개 콘텐츠 목록 표시

### 보안 기능

#### Rate Limiting
- 5회 연속 실패 시 5분간 잠금
- localStorage에 시도 횟수 기록
- 잠금 시간 동안 입력 불가

#### Session 관리
- 인증 성공 시 sessionStorage에 저장
- 유효 시간: 1시간
- 브라우저 탭 닫으면 세션 삭제

#### 암호화
- AES-256-CBC 암호화
- 콘텐츠는 클라이언트에서 복호화
- 암호화 키는 sessionStorage에만 저장

---

## 🛡️ 보안 고려사항

### 현재 구현의 강점

✅ **TOTP 사용**: 30초마다 변경되는 OTP
✅ **AES-256 암호화**: 강력한 암호화
✅ **Rate Limiting**: 무차별 대입 공격 방지
✅ **Session 타임아웃**: 1시간 후 자동 로그아웃
✅ **원본 콘텐츠 보호**: content/private/는 Git에 없음

### 한계 및 주의사항

⚠️ **JavaScript 기반**: 클라이언트 측 복호화
- AES 키가 sessionStorage에 저장됨
- 개발자 도구로 접근 가능 (인증 후에만)

⚠️ **정적 사이트**: 서버 측 검증 없음
- 백엔드가 없어 서버 로그 없음
- IP 차단 등 고급 방어 불가

⚠️ **브루트포스 가능성**:
- Rate Limiting이 localStorage 기반
- 브라우저 캐시 삭제 시 우회 가능

### 보안 강화 방법

#### 1. Cloudflare Access 추가 (권장!)

```
Cloudflare → Access → Applications
→ Private 영역에 이메일 OTP 추가
```

**장점:**
- TOTP 전에 이메일 인증 추가
- Cloudflare 수준의 보안
- IP 차단, Rate Limiting 자동

#### 2. IP 화이트리스트 (Nginx)

```nginx
# /etc/nginx/sites-available/blog
location /private/ {
    # VPN IP만 허용
    allow 10.0.0.0/8;
    deny all;

    try_files $uri $uri/ =404;
}
```

#### 3. 코드 난독화

```bash
# JavaScript 난독화
npm install -g javascript-obfuscator

javascript-obfuscator \
  layouts/private/list.html \
  --output layouts/private/list.obfuscated.html \
  --compact true \
  --self-defending true
```

#### 4. TOTP + 비밀번호 2단계 인증

```javascript
// 1단계: 비밀번호
const password = prompt('Password:');
const passwordHash = await sha256(password);
if (passwordHash !== STORED_HASH) return;

// 2단계: TOTP
const otp = prompt('OTP:');
verifyTOTP(otp);
```

---

## 📊 보안 수준 비교

| 방법 | 보안 수준 | 구현 난이도 | 유지보수 | 비용 |
|------|-----------|-------------|----------|------|
| **현재 (TOTP + AES)** | ⭐⭐⭐⭐ | 중간 | 쉬움 | 무료 |
| **+ Cloudflare Access** | ⭐⭐⭐⭐⭐ | 쉬움 | 매우 쉬움 | 무료 |
| **+ IP 화이트리스트** | ⭐⭐⭐⭐ | 쉬움 | 쉬움 | 무료 |
| **백엔드 API** | ⭐⭐⭐⭐⭐ | 어려움 | 복잡 | 서버 비용 |

---

## 🔧 문제 해결

### TOTP가 안 맞아요

1. **시간 동기화 확인**
   ```bash
   # 서버 시간 확인
   date

   # NTP 동기화
   sudo timedatectl set-ntp true
   ```

2. **TOTP Secret 확인**
   ```bash
   echo $PRIVATE_TOTP_SECRET
   # Google Authenticator의 Secret과 일치하는지 확인
   ```

3. **Window 설정**
   ```javascript
   // 허용 시간 범위 확대 (±2분)
   const delta = totp.validate({ token: userOTP, window: 4 });
   ```

### 콘텐츠가 복호화 안 돼요

1. **AES 키 확인**
   ```bash
   echo $PRIVATE_AES_KEY
   # 64자 hex 문자열인지 확인
   ```

2. **암호화 파일 확인**
   ```bash
   ls -la static/private-encrypted/
   cat static/private-encrypted/architecture.enc
   ```

3. **브라우저 콘솔 확인**
   ```
   F12 → Console 탭
   → 에러 메시지 확인
   ```

### Rate Limiting이 작동 안 해요

```javascript
// localStorage 초기화
localStorage.removeItem('auth_attempts');
localStorage.removeItem('lockout_until');
```

---

## 📚 참고 자료

- **TOTP 표준**: RFC 6238
- **AES 암호화**: NIST FIPS 197
- **Crypto-JS**: https://cryptojs.gitbook.io/
- **OTPAuth**: https://github.com/hectorm/otpauth

---

## ⚠️ 중요 보안 체크리스트

배포 전 반드시 확인:

- [ ] `.env` 파일이 `.gitignore`에 포함됨
- [ ] `content/private/` 폴더가 Git에 커밋되지 않음
- [ ] GitHub Secrets에 TOTP_SECRET, AES_KEY 등록함
- [ ] Google Authenticator에 TOTP 등록함
- [ ] 테스트로 OTP 인증 성공 확인
- [ ] 암호화된 콘텐츠 복호화 테스트 완료
- [ ] Rate Limiting 작동 테스트 (5회 실패)
- [ ] Session 타임아웃 테스트 (1시간 후)

---

**보안은 끝없는 여정입니다. 정기적으로 업데이트하고 모니터링하세요!** 🔐
