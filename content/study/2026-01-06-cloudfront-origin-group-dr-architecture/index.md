---
title: "CloudFront Origin Group으로 3단계 DR 구축하기"
date: 2026-01-06T10:00:00+09:00
tags: ["AWS", "CloudFront", "DR", "Azure", "High Availability", "Failover"]
categories: ["study", "Cloud & Terraform"]
description: "AWS EKS → Azure VM → Static Web Apps 3단계 자동 Failover를 CloudFront Origin Group으로 구현한 경험을 공유합니다."
cover:
  image: "cover.jpg"
  alt: "CloudFront Origin Group으로 3단계 DR 구축하기"
  relative: true
---

## 들어가며

DR(Disaster Recovery)은 항상 중요하다고 들었지만, 실제로 구축해보기 전까지는 그 복잡함을 몰랐어요. 단순히 "백업 서버 하나 더 만들면 되는 거 아냐?"라고 생각했거든요.

하지만 실제로 AWS EKS를 운영하면서 "만약 AWS 전체가 다운되면?" "Azure VM마저 장애가 나면?"이라는 질문들에 답해야 했고, 그 결과 3단계 자동 Failover 아키텍처를 만들게 됐습니다.

이 글에서는 Route53 Failover + CloudFront Origin Group을 조합해서 어떻게 **AWS → Azure VM → 점검 페이지**까지 완벽한 Failover를 구현했는지 공유해보려고 해요.

---

## 왜 3단계 DR이 필요했나?

### 초기 구상: 2단계 DR (AWS ↔ Azure)

처음에는 간단하게 생각했어요.

```
AWS EKS (PRIMARY) ← 평상시
    ↓ (장애 시)
Azure VM (SECONDARY) ← Failover
```

Route53 Health Check로 AWS EKS를 모니터링하고, 장애 감지 시 Azure VM으로 DNS를 바꾸면 끝이라고요.

### 문제 발견: Azure VM도 장애 날 수 있다

하지만 실제로 테스트하다 보니 문제가 보였어요:

1. **Azure VM도 장애가 날 수 있음**
   - VM 자체 장애
   - Azure 리전 장애
   - 네트워크 문제

2. **사용자 경험 악화**
   ```
   AWS 다운 → Azure로 Failover ✅
   Azure마저 다운 → ??? (503 에러) ❌
   ```

3. **모니터링 공백**
   - "지금 DR 서버가 죽었는데 아무도 모르는" 상황

### 최종 결정: 3단계 DR

```
1차: AWS EKS (PRIMARY)
  ↓ (Health Check 실패)
2차: Azure VM (SECONDARY)
  ↓ (VM도 실패)
3차: Azure Static Web Apps (점검 페이지)
```

마지막 보루로 Static Web Apps를 배치하면, **절대 실패하지 않는 점검 페이지**를 보여줄 수 있어요. 사용자는 최소한 "지금 점검 중이구나"라는 걸 알 수 있죠.

---

## 아키텍처 설계

### 전체 구조

```
                              ┌─────────────────────────────────────────┐
                              │           Route53 Failover              │
                              │         www.goupang.shop                │
                              └───────────────┬─────────────────────────┘
                                              │
                 ┌────────────────────────────┼────────────────────────────┐
                 │                            │                            │
                 ▼                            ▼                            │
        ┌────────────────┐          ┌────────────────────┐                 │
        │    PRIMARY     │          │     SECONDARY      │                 │
        │                │          │                    │                 │
        │   AWS ALB      │          │ CloudFront         │                 │
        │   (EKS)        │          │ d2npwlhpn3kbha     │                 │
        │                │          │                    │                 │
        │  ┌──────────┐  │          │  ┌──────────────┐  │                 │
        │  │ WAS Pod  │  │          │  │ Origin Group │  │                 │
        │  │ WEB Pod  │  │          │  │              │  │                 │
        │  └──────────┘  │          │  │ ┌──────────┐ │  │                 │
        │       │        │          │  │ │ Azure VM │◄┼──┼── Priority 1   │
        │       ▼        │          │  │ │ (nginx)  │ │  │                 │
        │  ┌──────────┐  │          │  │ └──────────┘ │  │                 │
        │  │ RDS      │  │          │  │      │       │  │                 │
        │  │ MySQL    │  │          │  │      ▼ 실패시 │  │                 │
        │  └──────────┘  │          │  │ ┌──────────┐ │  │                 │
        │                │          │  │ │   SWA    │◄┼──┼── Priority 2   │
        └────────────────┘          │  │ │(점검페이지)│ │  │                 │
                                    │  │ └──────────┘ │  │                 │
                                    │  └──────────────┘  │                 │
                                    │         │          │                 │
                                    └─────────┼──────────┘                 │
                                              │                            │
                                              ▼                            │
                                    ┌────────────────┐                     │
                                    │  Azure MySQL   │                     │
                                    │  Flexible      │                     │
                                    └────────────────┘                     │
```

핵심은 **CloudFront Origin Group**이에요. 이게 Azure VM과 Static Web Apps 간의 자동 Failover를 담당합니다.

### Failover 흐름

```
평상시:
  사용자 → www.goupang.shop → Route53 PRIMARY → AWS ALB → EKS PetClinic

AWS 장애 (EKS Down):
  사용자 → www.goupang.shop → Route53 SECONDARY → CloudFront
         → Origin Group → Azure VM → PetClinic (DR)

AWS + VM 장애:
  사용자 → www.goupang.shop → Route53 SECONDARY → CloudFront
         → Origin Group → VM 실패 → SWA → 점검 페이지
```

---

## 구현 과정

### 1단계: Route53 Failover 설정

먼저 AWS와 CloudFront 간의 Failover를 설정했어요.

#### Health Check 생성

```bash
aws route53 create-health-check \
  --health-check-config \
    Type=HTTPS,\
    ResourcePath=/petclinic/,\
    FullyQualifiedDomainName=k8s-petclinicgroup-*.elb.amazonaws.com,\
    Port=443,\
    RequestInterval=30,\
    FailureThreshold=2
```

**왜 이 설정?**
- `RequestInterval=30`: 30초마다 체크 (빠른 감지)
- `FailureThreshold=2`: 2번 실패하면 Unhealthy (60초 내 감지)
- `HTTPS`: 실제 서비스와 동일한 프로토콜로 체크

#### Failover 레코드 생성

```
PRIMARY 레코드:
  - Type: CNAME
  - Value: k8s-petclinicgroup-*.elb.amazonaws.com
  - Failover: PRIMARY
  - Health Check: ✅ 활성화

SECONDARY 레코드:
  - Type: CNAME
  - Value: d2npwlhpn3kbha.cloudfront.net
  - Failover: SECONDARY
  - Health Check: 없음 (CloudFront는 항상 정상으로 간주)
```

---

### 2단계: CloudFront Distribution 생성

CloudFront를 중간에 배치한 이유는 **Origin Group 기능**을 사용하기 위해서예요.

```bash
aws cloudfront create-distribution \
  --distribution-config '{
    "Aliases": {
      "Items": ["www.goupang.shop", "dr.goupang.shop"]
    },
    "Origins": {
      "Items": [
        {
          "Id": "Azure-VM",
          "DomainName": "dr-vm-poc.koreacentral.cloudapp.azure.com",
          "CustomOriginConfig": {
            "HTTPPort": 80,
            "OriginProtocolPolicy": "http-only"
          }
        },
        {
          "Id": "Azure-SWA",
          "DomainName": "ashy-bay-04d02d100.6.azurestaticapps.net",
          "CustomOriginConfig": {
            "HTTPSPort": 443,
            "OriginProtocolPolicy": "https-only"
          }
        }
      ]
    }
  }'
```

---

### 3단계: Origin Group 설정

이 부분이 가장 중요했어요. Origin Group은 **Primary Origin 실패 시 자동으로 Secondary Origin으로 전환**해주거든요.

```json
{
  "Id": "Azure-Failover-Group",
  "FailoverCriteria": {
    "StatusCodes": {
      "Items": [500, 502, 503, 504, 403, 404]
    }
  },
  "Members": {
    "Items": [
      {
        "OriginId": "Azure-VM",
        "Priority": 1
      },
      {
        "OriginId": "Azure-SWA",
        "Priority": 2
      }
    ]
  }
}
```

**동작 원리:**
1. CloudFront가 Azure VM에 요청
2. VM에서 500/502/503/504/403/404 중 하나를 반환
3. CloudFront가 자동으로 Static Web Apps로 Failover
4. 점검 페이지 표시

---

### 4단계: Behavior 분리 (GET vs POST)

여기서 큰 문제를 발견했어요.

**문제:** CloudFront Origin Group은 **POST/PUT/DELETE 메서드를 허용하지 않음**

```
POST /owners/new → 403 Forbidden ❌
```

**해결:** Behavior를 경로별로 분리

```json
{
  "Behaviors": {
    "Items": [
      {
        "PathPattern": "/*",
        "TargetOriginId": "Azure-Failover-Group",
        "AllowedMethods": ["GET", "HEAD", "OPTIONS"]
      },
      {
        "PathPattern": "/owners/*",
        "TargetOriginId": "Azure-VM",
        "AllowedMethods": ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
      },
      {
        "PathPattern": "/petclinic/owners/*",
        "TargetOriginId": "Azure-VM",
        "AllowedMethods": ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
      }
    ]
  }
}
```

**왜 이렇게?**
- **조회(GET)**: Origin Group → VM 실패 시 SWA Failover ✅
- **쓰기(POST)**: VM Direct → VM 실패 시 503 에러 (합리적)

쓰기 작업은 어차피 VM이 다운되면 할 수 없으니, 점검 페이지로 Failover하는 것보다 명확한 에러를 보여주는 게 낫다고 판단했어요.

---

### 5단계: Azure 리소스 준비

#### Azure VM (DR PetClinic)

```bash
# VM 생성
az vm create \
  --resource-group rg-petclinic-dr \
  --name petclinic-dr-vm \
  --location koreacentral \
  --image UbuntuLTS \
  --size Standard_B2s \
  --public-ip-address-dns-name dr-vm-poc

# Nginx 설치 및 리버스 프록시 설정
ssh azureuser@dr-vm-poc.koreacentral.cloudapp.azure.com

sudo apt update
sudo apt install nginx -y

sudo vi /etc/nginx/sites-available/petclinic
# location / {
#   proxy_pass http://localhost:8080/petclinic/;
# }

sudo systemctl restart nginx
```

#### Azure MySQL

```bash
az mysql flexible-server create \
  --resource-group rg-petclinic-dr \
  --name dr-petclinic-mysql \
  --location koreacentral \
  --admin-user dbadmin \
  --database-name petclinic
```

#### Azure Static Web Apps (점검 페이지)

```html
<!-- index.html -->
<!DOCTYPE html>
<html>
<head>
    <title>시스템 점검 중</title>
    <meta charset="UTF-8">
    <style>
        body {
            font-family: Arial, sans-serif;
            text-align: center;
            padding: 50px;
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            color: white;
        }
        .container {
            background: rgba(255, 255, 255, 0.1);
            padding: 40px;
            border-radius: 10px;
            backdrop-filter: blur(10px);
        }
    </style>
</head>
<body>
    <div class="container">
        <h1>🔧 시스템 점검 중입니다</h1>
        <p>더 나은 서비스 제공을 위해 시스템을 점검하고 있습니다.</p>
        <p>빠른 시일 내에 복구하겠습니다.</p>
    </div>
</body>
</html>
```

GitHub에 푸시하면 Azure Static Web Apps가 자동으로 배포해줘요.

---

## Failover 테스트

### 테스트 시나리오

실제로 Failover가 잘 동작하는지 검증했어요.

```bash
# 1. AWS EKS WAS/WEB replica → 0
kubectl scale rollout was -n petclinic --replicas=0
kubectl scale deployment web -n petclinic --replicas=0

# 2. Health Check 실패 대기 (~60초)
watch -n 5 'aws route53 get-health-check-status \
  --health-check-id b1ddbda0-eb95-48a9-a7c3-c42adade7210'

# 3. DNS 확인
dig www.goupang.shop +short
# d2npwlhpn3kbha.cloudfront.net ← SECONDARY로 변경됨 ✅

# 4. 실제 접속
curl -I https://www.goupang.shop/petclinic/
# server: nginx/1.18.0 (Ubuntu) ← Azure VM ✅
```

### 테스트 결과

| 테스트 항목 | 결과 | 응답 서버 |
|------------|------|----------|
| Route53 Health Check 실패 감지 | ✅ 성공 | - |
| SECONDARY로 Failover | ✅ 성공 | CloudFront |
| GET /petclinic/ | ✅ 200 OK | Azure VM (nginx) |
| POST /petclinic/owners/new | ✅ 200 OK | Azure VM (nginx) |

**Failover 시간:**

| 단계 | 소요 시간 |
|------|----------|
| Health Check 실패 감지 | ~60초 (30초 × 2회) |
| DNS TTL 만료 | ~60초 |
| CloudFront 라우팅 | 즉시 |
| **총 Failover 시간** | **~2분** |

2분이면 충분히 빠르다고 생각했어요. 대부분의 사용자는 2분 안에 재접속하니까요.

---

## 트러블슈팅

### 문제 1: CloudFront가 SWA로만 가는 경우

**증상:** VM이 살아있는데 SWA 응답 (Windows-Azure-Web)

```bash
curl -I https://www.goupang.shop/petclinic/
# server: Windows-Azure-Web/1.0 ❌ (VM이 아닌 SWA)
```

**원인:**
1. CloudFront Alias에 www.goupang.shop이 없음
2. VM Origin 헬스체크 실패

**해결:**

```bash
# CloudFront Alias 확인
aws cloudfront get-distribution --id E1HQPVP1WX3OKF \
  --query 'Distribution.DistributionConfig.Aliases'

# Alias 추가 (없으면)
aws cloudfront update-distribution ...

# VM 직접 확인
curl -I http://dr-vm-poc.koreacentral.cloudapp.azure.com/petclinic/
# HTTP/1.1 200 OK ✅
```

Alias를 추가하고 CloudFront가 캐시를 갱신하니 정상 동작했어요.

---

### 문제 2: POST 요청이 403 Forbidden

**증상:** POST /owners/new → 403

```bash
curl -X POST https://www.goupang.shop/petclinic/owners/new
# 403 Forbidden ❌
```

**원인:**
1. Behavior 경로 불일치
2. Origin Group에서 POST 차단

**해결:**

Behavior를 확인하고 `/owners/*` 경로를 VM Direct로 라우팅하도록 수정했어요.

```bash
# CloudFront Behaviors 확인
aws cloudfront get-distribution --id E1HQPVP1WX3OKF \
  --query 'Distribution.DistributionConfig.CacheBehaviors.Items[*].{Path: PathPattern, Target: TargetOriginId, Methods: AllowedMethods.Items}'

# POST 허용 확인
# Path: /owners/*
# Methods: ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"] ✅
```

---

## 운영 가이드

### Failover 발생 시 확인 사항

```bash
# 1. Health Check 상태 확인
aws route53 get-health-check-status \
  --health-check-id b1ddbda0-eb95-48a9-a7c3-c42adade7210

# 2. DNS 확인
dig www.goupang.shop +short

# 3. 응답 서버 확인
curl -sI https://www.goupang.shop/petclinic/ | grep server
# 정상: server: nginx/1.18.0 (Ubuntu) = Azure VM
```

### 수동 Failover 트리거

긴급하게 DR로 전환해야 할 때:

```bash
# Health Check 비활성화 (즉시 Failover)
aws route53 update-health-check \
  --health-check-id b1ddbda0-eb95-48a9-a7c3-c42adade7210 \
  --disabled
```

### Failback (AWS 복구)

AWS가 복구되면 다시 PRIMARY로 전환:

```bash
# 1. EKS WAS/WEB 복구
kubectl scale rollout was -n petclinic --replicas=2
kubectl scale deployment web -n petclinic --replicas=2

# 2. Health Check 활성화
aws route53 update-health-check \
  --health-check-id b1ddbda0-eb95-48a9-a7c3-c42adade7210 \
  --no-disabled

# 3. Health Check 정상 확인 후 자동 Failback
aws route53 get-health-check-status \
  --health-check-id b1ddbda0-eb95-48a9-a7c3-c42adade7210
```

---

## 배운 점

### 1. CloudFront Origin Group의 강력함

단순히 CDN으로만 알고 있던 CloudFront가 **자동 Failover 기능**도 제공한다는 걸 알게 됐어요. 별도의 헬스체크나 스크립트 없이, HTTP 상태 코드만으로 자동 전환이 가능하더라고요.

### 2. 점검 페이지의 중요성

사용자 경험 관점에서 "503 에러"보다 "점검 중입니다"라는 메시지가 훨씬 낫다는 걸 깨달았어요. Static Web Apps는 거의 100% 가용성을 보장하니, 마지막 보루로 완벽했죠.

### 3. 쓰기 작업은 신중하게

POST/PUT 같은 쓰기 작업은 Failover하면 안 된다는 걸 배웠어요. 잘못하면 데이터 불일치가 발생할 수 있거든요. 읽기만 Failover하고, 쓰기는 명확한 에러를 반환하는 게 맞아요.

### 4. Health Check는 빠를수록 좋다

30초 간격, 2번 실패로 설정해서 총 60초 만에 장애를 감지했어요. 이 정도면 대부분의 서비스에 충분하다고 생각해요.

---

## 마치며

3단계 DR을 구축하면서 **가용성(Availability)**과 **복원력(Resilience)**의 차이를 명확히 이해하게 됐어요.

- **가용성**: 서비스가 얼마나 자주 정상 동작하는가
- **복원력**: 장애가 발생했을 때 얼마나 빨리 복구하는가

CloudFront Origin Group + Route53 Failover 조합은 두 가지를 모두 만족시켜줬어요. AWS가 다운되어도, Azure VM이 다운되어도, 사용자는 최소한 점검 페이지를 볼 수 있으니까요.

DR은 "만들어두면 안 쓸 수도 있는" 시스템이지만, 정작 필요할 때 없으면 정말 큰일 나는 시스템이에요. 여러분도 꼭 구축해보시길 추천드립니다.
