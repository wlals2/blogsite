---
title: "S3 Versioning 켜면 삭제해도 과금된다 — 107GB 청구서 받기 전에"
date: 2026-03-12T10:00:00+09:00
description: "aws s3 ls는 6GB인데 실제 과금은 107GB. S3 Versioning의 구버전 누적 함정과 Lifecycle로 자동 정리하는 방법"
tags: ["aws", "s3", "versioning", "lifecycle", "cost", "kubernetes", "backup"]
categories: ["study", "Cloud & Terraform"]
---

> 이론 배경: [AWS S3 스토리지 서비스 정리](/study/2026-01-30-aws-스토리지-서비스-정리/)

## 배경

K8s 홈랩에서 etcd, MySQL 백업을 S3에 저장하고 있었다. 어느 날 S3를 점검하다 뭔가 이상했다.

```bash
aws s3 ls s3://jimin-backup/ --recursive --summarize
# Total Objects: 31
# Total Size: 7012598307   ← 약 6.5GB
```

6.5GB면 납득이 가는 수준이다. 그런데 실제 과금 용량을 확인했더니 달랐다.

```bash
aws s3api list-object-versions --bucket jimin-backup | python3 -c "
import json, sys
data = json.load(sys.stdin)
cur = [v for v in data.get('Versions', []) if v.get('IsLatest')]
nc  = [v for v in data.get('Versions', []) if not v.get('IsLatest')]
print(f'현재 버전:  {len(cur):>5}개  {sum(v[\"Size\"] for v in cur)/1024/1024:>10.1f} MB')
print(f'구버전:     {len(nc):>5}개  {sum(v[\"Size\"] for v in nc)/1024/1024:>10.1f} MB')
print(f'Delete Marker: {len(data.get(\"DeleteMarkers\", []))}개')
"
# 현재 버전:     31개      6687.7 MB
# 구버전:       139개    103568.1 MB   ← 101GB가 숨어 있다
# Delete Marker: 139개
```

`aws s3 ls`로는 6.5GB가 보이는데 실제 과금 대상은 **107.7GB**였다.

---

## 왜 이런 일이 생기는가

### S3 Versioning의 동작 방식

S3 Versioning을 켜면 파일을 "삭제"해도 실제로 삭제되지 않는다.

```
Versioning OFF:   s3 rm file.txt  →  파일 즉시 삭제 (과금 종료)

Versioning ON:    s3 rm file.txt  →  Delete Marker 추가
                                      구버전은 그대로 남아서 계속 과금
```

`aws s3 ls` 명령은 **현재 버전**만 보여준다. 구버전은 `list-object-versions`로만 확인할 수 있다.

### 어디서 쌓였는가

```
etcd/2026-03-06/  ← 백업 스크립트 테스트 중 20개 생성 (각 776MB = 15GB)
                     defrag 후 정상화됐지만 이전 776MB 스냅샷들은 구버전으로 남음

mysql-backup-*.sql.gz (루트)  ← 구버전 방식 CronJob이 매일 덮어쓰기
                                  덮어쓸 때마다 이전 버전이 구버전으로 쌓임
```

---

## 해결

### 1단계: 구버전 + Delete Marker 일괄 삭제

```bash
# 구버전 삭제
aws s3api list-object-versions --bucket jimin-backup \
  --query "Versions[?IsLatest==\`false\`].[Key,VersionId]" --output text | \
  while read key vid; do
    aws s3api delete-object --bucket jimin-backup --key "$key" --version-id "$vid"
  done

# Delete Marker 삭제
aws s3api list-object-versions --bucket jimin-backup \
  --query "DeleteMarkers[].[Key,VersionId]" --output text | \
  while read key vid; do
    aws s3api delete-object --bucket jimin-backup --key "$key" --version-id "$vid"
  done
```

### 2단계: Lifecycle 설정으로 재발 방지

수동 삭제만으로는 부족하다. 앞으로도 같은 일이 반복될 수 있으므로 **Lifecycle 정책**을 추가해야 한다.

```bash
aws s3api put-bucket-lifecycle-configuration --bucket jimin-backup \
  --lifecycle-configuration '{
  "Rules": [
    {
      "ID": "DeleteOldEtcdBackups",
      "Filter": { "Prefix": "etcd/" },
      "Status": "Enabled",
      "Expiration": { "Days": 7 },
      "NoncurrentVersionExpiration": { "NoncurrentDays": 1 }
    },
    {
      "ID": "DeleteOldMysqlBackups",
      "Filter": { "Prefix": "mysql/" },
      "Status": "Enabled",
      "Expiration": { "Days": 14 },
      "NoncurrentVersionExpiration": { "NoncurrentDays": 1 }
    },
    {
      "ID": "CleanupNoncurrentAndDeleteMarkers",
      "Filter": {},
      "Status": "Enabled",
      "Expiration": { "ExpiredObjectDeleteMarker": true },
      "NoncurrentVersionExpiration": { "NoncurrentDays": 1 }
    }
  ]
}'
```

`NoncurrentVersionExpiration`이 핵심이다. 구버전이 생기면 N일 후 자동 삭제한다.

---

## Before / After

| 항목 | Before | After |
|------|--------|-------|
| 보이는 용량 (`aws s3 ls`) | 6.5 GB | 250 MB |
| 실제 과금 용량 | **107.7 GB** | 250 MB |
| 구버전 | 139개, 101 GB | 0개 |
| Delete Marker | 139개 | 0개 |
| 재발 방지 | 없음 | Lifecycle 구버전 1일 자동 삭제 |

---

## 핵심 교훈

**S3 Versioning과 Lifecycle은 세트다.**

Versioning만 켜면 삭제해도 과금이 계속된다. Versioning을 켤 때는 반드시 `NoncurrentVersionExpiration`을 함께 설정해야 한다.

```
Versioning ON  →  NoncurrentVersionExpiration 필수
                  ExpiredObjectDeleteMarker 필수
```

실제 과금 확인은 AWS 콘솔의 S3 Storage Lens 또는 `list-object-versions` API로만 가능하다. `aws s3 ls`는 현재 버전만 보여주기 때문에 구버전이 쌓여도 보이지 않는다.
