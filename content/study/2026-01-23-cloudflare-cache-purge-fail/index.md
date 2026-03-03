---
title: "Cloudflare 캐시 퍼지 실패 문제"
date: 2026-01-23T10:00:00+09:00
description: "GitHub Actions에서 Cloudflare API 호출 시 Zone ID 누락 문제 해결"
tags: ["cloudflare", "github-actions", "cache", "troubleshooting"]
categories: ["study", "Troubleshooting"]
---

## 상황

GitHub Actions에서 Cloudflare 캐시 퍼지 단계 실패.

```
curl: (3) URL using bad/illegal format or missing URL
```

---

## 원인

### 워크플로우 코드

```yaml
- name: Purge Cloudflare Cache
  run: |
    curl -X POST "https://api.cloudflare.com/client/v4/zones/${{ secrets.CLOUDFLARE_ZONE_ID }}/purge_cache" \
      -H "Authorization: Bearer ${{ secrets.CLOUDFLARE_API_TOKEN }}" \
      --data '{"purge_everything":true}'
```

### 문제 분석

| 항목 | 값 | 문제 |
|------|-----|------|
| CLOUDFLARE_ZONE_ID | (빈 문자열) | Secret 미설정 |
| 생성된 URL | `zones//purge_cache` | 잘못된 형식 |

Secret이 비어있어서 URL 형식이 깨짐.

---

## 해결

### 1. Zone ID 조회

**방법 1: Cloudflare API**

```bash
curl -X GET "https://api.cloudflare.com/client/v4/zones" \
  -H "Authorization: Bearer <CLOUDFLARE_API_TOKEN>" \
  -H "Content-Type: application/json" | jq -r '.result[] | select(.name=="jiminhome.shop") | .id'
```

```
7895fe2aef761351db71892fb7c22b52
```

**방법 2: Cloudflare 대시보드**

1. Cloudflare 로그인
2. 도메인 선택 (jiminhome.shop)
3. 오른쪽 사이드바 하단 "Zone ID" 복사

### 2. GitHub Secret 추가

```
Repository > Settings > Secrets and variables > Actions > New repository secret

Name: CLOUDFLARE_ZONE_ID
Value: 7895fe2aef761351db71892fb7c22b52
```

### 3. 테스트

```bash
curl -X POST "https://api.cloudflare.com/client/v4/zones/7895fe2aef761351db71892fb7c22b52/purge_cache" \
  -H "Authorization: Bearer $CLOUDFLARE_API_TOKEN" \
  -H "Content-Type: application/json" \
  --data '{"purge_everything":true}'
```

```json
{"success":true,"errors":[],"messages":[],"result":{"id":"..."}}
```

---

## 결과

| 항목 | Before | After |
|------|--------|-------|
| CLOUDFLARE_ZONE_ID | 미설정 | 7895fe2aef761351db71892fb7c22b52 |
| 캐시 퍼지 | ❌ 실패 | ✅ 성공 |

---

## 정리

### Cloudflare API 필수 Secret

| Secret | 용도 | 위치 |
|--------|------|------|
| CLOUDFLARE_ZONE_ID | 도메인 식별자 | 대시보드 > Zone ID |
| CLOUDFLARE_API_TOKEN | API 인증 | My Profile > API Tokens |

### API Token 권한

캐시 퍼지에 필요한 최소 권한:

| 권한 | 값 |
|------|-----|
| Zone | Cache Purge |
| Zone Resources | Include > Specific zone > jiminhome.shop |

---

## 관련 명령어

```bash
# Zone 목록 조회
curl -X GET "https://api.cloudflare.com/client/v4/zones" \
  -H "Authorization: Bearer $CLOUDFLARE_API_TOKEN" | jq '.result[].name'

# 특정 URL만 퍼지
curl -X POST "https://api.cloudflare.com/client/v4/zones/<ZONE_ID>/purge_cache" \
  -H "Authorization: Bearer $CLOUDFLARE_API_TOKEN" \
  --data '{"files":["https://blog.jiminhome.shop/"]}'

# 캐시 상태 확인
curl -I https://blog.jiminhome.shop/ | grep cf-cache-status
```
