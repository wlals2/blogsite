# GitHub Actions Quick Reference

> 자주 사용하는 명령어와 트러블슈팅 빠른 참조

---

## 🚀 일반적인 사용

### 배포하기
```bash
git add .
git commit -m "Update content"
git push  # 자동으로 배포됨
```

### 수동 배포 트리거
1. GitHub → Actions 탭
2. "Deploy Hugo Blog" 선택
3. "Run workflow" 클릭

---

## 📊 상태 확인

### GitHub Actions 로그
```bash
# 웹: https://github.com/wlals2/my-hugo-blog/actions

# 또는 CLI
gh run list
gh run view <run-id> --log
```

### Runner 상태
```bash
sudo systemctl status actions-runner
journalctl -u actions-runner -f  # 실시간 로그
```

### 배포 정보
```bash
curl https://blog.jiminhome.shop/deploy.txt
```

---

## 🔧 트러블슈팅

### 워크플로우가 실행 안 됨
```bash
# 1. 브랜치 확인
git branch  # main 브랜치인지 확인

# 2. 변경된 파일 확인
git diff --name-only HEAD^

# 3. paths 필터 확인 (.github/workflows/deploy.yml)
# content/**, static/**, themes/** 등만 트리거됨
```

### Runner 오프라인
```bash
sudo systemctl restart actions-runner
sudo systemctl status actions-runner
```

### Cloudflare 캐시 안 지워짐
```bash
# Secrets 확인
# GitHub → Settings → Secrets에서
# CLOUDFLARE_ZONE_ID
# CLOUDFLARE_API_TOKEN
# 2개 있는지 확인

# 수동 Purge
export CLOUDFLARE_ZONE_ID="..."
export CLOUDFLARE_API_TOKEN="..."
curl -X POST "https://api.cloudflare.com/client/v4/zones/$CLOUDFLARE_ZONE_ID/purge_cache" \
  -H "Authorization: Bearer $CLOUDFLARE_API_TOKEN" \
  -H "Content-Type: application/json" \
  --data '{"purge_everything":true}'
```

### 빌드 실패
```bash
# 로컬에서 테스트
cd ~/blogsite
hugo --minify

# Submodule 업데이트
git submodule update --init --recursive
```

---

## 📁 중요한 파일 위치

```
~/blogsite/
├── .github/workflows/deploy.yml  # 워크플로우 설정
├── docs/
│   ├── GITHUB-ACTIONS-GUIDE.md         # 완전한 가이드
│   ├── CLOUDFLARE-AUTO-PURGE-SETUP.md  # Cloudflare 설정
│   └── QUICK-REFERENCE.md              # 이 파일
└── scripts/
    └── encrypt-private-content.sh      # Private 암호화

/var/www/blog/                    # 배포 경로
~/actions-runner/                 # Runner 설치 경로
```

---

## 🔐 Secrets 관리

### 현재 등록된 Secrets
- `PRIVATE_TOTP_SECRET` - OTP 인증
- `PRIVATE_AES_KEY` - Private 컨텐츠 암호화
- `CLOUDFLARE_ZONE_ID` - Cloudflare Zone
- `CLOUDFLARE_API_TOKEN` - Cloudflare API

### Secret 추가/수정
GitHub → Settings → Secrets and variables → Actions

---

## 📚 상세 가이드

전체 문서는 다음 파일 참조:
- [GITHUB-ACTIONS-GUIDE.md](GITHUB-ACTIONS-GUIDE.md) - 완전한 설명
- [CLOUDFLARE-AUTO-PURGE-SETUP.md](CLOUDFLARE-AUTO-PURGE-SETUP.md) - Cloudflare 설정

---

**마지막 업데이트:** 2026-01-12
