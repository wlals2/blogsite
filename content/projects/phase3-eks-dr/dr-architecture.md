---
title: "Multi-Cloud DR 아키텍처: AWS 장애에도 2분 안에 서비스 복구"
date: 2026-01-10
summary: "Route53 Failover + CloudFront + Lambda@Edge로 99.9% 가용성 달성"
tags: ["dr", "multi-cloud", "route53", "cloudfront", "azure", "high-availability"]
categories: ["projects"]
series: ["Infrastructure Learning Journey"]
weight: 3
showtoc: true
tocopen: true
---

# Multi-Cloud DR 아키텍처: AWS 장애에도 2분 안에 서비스 복구

> 단일 클라우드 의존에서 벗어나 Multi-Cloud DR로 99.9% 가용성 달성

---

## 🚨 문제의 시작: 새벽 3시 장애

**2025년 11월 7일 새벽 3시**

온프레미스 Kubernetes 클러스터가 완전히 중단되었습니다.

```
03:00 - 회사 전력 차단기 문제로 서버실 전원 중단
03:05 - 모니터링 알림: All services down
03:10 - 긴급 출근 (집에서 30분 거리)
03:40 - 서버실 도착, 전원 복구 시도
04:00 - Kubernetes 클러스터 재시작
04:30 - 모든 서비스 복구 완료

다운타임: 1시간 30분
영향: 전체 고객 (100%)
```

**CEO의 질문:**
> "왜 AWS 쓰면서 장애가 났나요? 클라우드가 안정적이라며?"

**나의 대답:**
> "온프레미스 Kubernetes를 사용해서 그렇습니다. AWS EKS로 옮기겠습니다."

**CEO의 추가 질문:**
> "AWS도 장애 나면 어떻게 하죠? 2023년에 AWS 서울 리전 장애 있었잖아요?"

**깨달음:**
> **단일 클라우드 의존 = 단일 장애점 (SPOF)**

---

## 목표 설정

### 1. 가용성 목표

| 지표 | 현재 (온프레미스) | 목표 | 근거 |
|------|-----------------|------|------|
| **가용성** | 95% (월 36시간 다운) | 99.9% (월 43분) | 업계 표준 (Three Nines) |
| **DR RTO** | 없음 (수동 복구 4시간) | 2분 | Route53 TTL + Health Check |
| **DR RPO** | 24시간 (일일 백업) | 24시간 | 비용 대비 적정 |

**99.9% 가용성 의미:**
- 월 43분 다운타임 허용
- 연 8.76시간 다운타임 허용
- 단일 장애로 전체 중단 불가

---

### 2. DR 전략 선택

| DR 전략 | RTO | RPO | 비용 | 선택 |
|---------|-----|-----|------|------|
| **백업/복원** | 4시간+ | 24시간 | 낮음 | ❌ RTO 너무 김 |
| **Pilot Light** | 30분 | 15분 | 중간 | ❌ RPO 24시간 목표 |
| **Warm Standby** | 10분 | 5분 | 높음 | ❌ 비용 과다 |
| **Multi-Site Active** | 0분 | 0분 | 매우 높음 | ❌ 비용 과다 |
| **Static Site Failover** | 2분 | N/A | 낮음 | ✅ **선택** |

**Static Site Failover 선택 이유:**
- **비용 효율적**: CloudFront + S3/Azure Blob만 필요
- **빠른 RTO**: Route53 TTL 30초 + Health Check 30초 = **2분**
- **간단한 운영**: 정적 점검 페이지만 관리
- **목표 충족**: 99.9% 가용성 달성 가능

---

## 🏗️ 아키텍처 설계

### 전체 구성도

```
                    Route53 Failover (30초 TTL)
                         www.goupang.shop
                    PRIMARY ───────► SECONDARY
                       │                 │
         ──────────────┘                 └──────────────
         │                                             │
         ▼                                             ▼
┌─────────────────────────┐              ┌─────────────────────────┐
│   AWS (Primary)         │              │   Azure (DR)            │
│                         │              │                         │
│  ALB (HTTPS, ACM)       │              │  CloudFront + Lambda    │
│       │                 │              │       │                 │
│       ▼                 │              │       ▼                 │
│  EKS Cluster            │              │  Azure Blob Storage     │
│  (Multi-AZ: 2a, 2c)     │              │  (점검 페이지 HTML)     │
│       │                 │              │                         │
│  ┌────┴────┐            │              │  "서비스 점검 중"       │
│  │ WEB/WAS │            │              │  "10분 후 복구 예정"    │
│  │ 2-10 Pods│           │              │                         │
│  └────┬────┘            │              └─────────────────────────┘
│       │                 │
│  ┌────┴────┐            │
│  │  Redis  │            │
│  └────┬────┘            │
│       │                 │
│  ┌────┴────┐            │
│  │   RDS   │            │
│  └─────────┘            │
└─────────────────────────┘
```

---

### 핵심 컴포넌트

#### 1. Route53 Failover

**Primary Record:**
```hcl
resource "aws_route53_record" "primary" {
  zone_id = "Z123456789"
  name    = "www.goupang.shop"
  type    = "A"

  alias {
    name                   = aws_lb.alb.dns_name
    zone_id                = aws_lb.alb.zone_id
    evaluate_target_health = true
  }

  failover_routing_policy {
    type = "PRIMARY"
  }

  set_identifier  = "primary"
  health_check_id = aws_route53_health_check.primary.id
}
```

**Secondary Record:**
```hcl
resource "aws_route53_record" "secondary" {
  zone_id = "Z123456789"
  name    = "www.goupang.shop"
  type    = "A"

  alias {
    name    = aws_cloudfront_distribution.blob.domain_name
    zone_id = aws_cloudfront_distribution.blob.hosted_zone_id
  }

  failover_routing_policy {
    type = "SECONDARY"
  }

  set_identifier = "secondary"
}
```

**Health Check:**
```hcl
resource "aws_route53_health_check" "primary" {
  fqdn              = "www.goupang.shop"
  port              = 443
  type              = "HTTPS"
  resource_path     = "/petclinic/actuator/health"
  request_interval  = 30
  failure_threshold = 2

  tags = {
    Name = "Primary Health Check"
  }
}
```

**동작 원리:**
```
1. Route53이 30초마다 Primary Health Check
   ↓
2. 2번 연속 실패 (60초) → Unhealthy 판정
   ↓
3. Secondary (CloudFront)로 트래픽 전환
   ↓
4. CloudFront → Azure Blob → 점검 페이지 표시
```

**RTO 계산:**
- Health Check 간격: 30초
- Failure Threshold: 2회
- DNS TTL: 30초
- **총 RTO: 30초 × 2 + 30초 = 90초 ≈ 2분**

---

#### 2. CloudFront + Lambda@Edge

**왜 CloudFront가 필요한가?**

Azure Blob은 Host 헤더 검증으로 Azure 도메인만 허용합니다.

```
사용자 → www.goupang.shop
         ↓
CloudFront (HTTPS, ACM 인증서)
         ↓
Lambda@Edge (Origin Request)
  Host: www.goupang.shop → Host: drbackupstorage2024.z12.web.core.windows.net
         ↓
Azure Blob (Host 헤더 검증 통과 ✅)
         ↓
점검 페이지 반환
```

**CloudFront 설정:**
```hcl
resource "aws_cloudfront_distribution" "blob" {
  enabled             = true
  is_ipv6_enabled     = true
  price_class         = "PriceClass_100"  # 북미+유럽
  aliases             = ["www.goupang.shop"]

  origin {
    domain_name = "drbackupstorage2024.z12.web.core.windows.net"
    origin_id   = "AzureBlobOrigin"

    custom_origin_config {
      http_port              = 80
      https_port             = 443
      origin_protocol_policy = "https-only"
      origin_ssl_protocols   = ["TLSv1.2"]
    }
  }

  default_cache_behavior {
    allowed_methods  = ["GET", "HEAD"]
    cached_methods   = ["GET", "HEAD"]
    target_origin_id = "AzureBlobOrigin"

    forwarded_values {
      query_string = false
      cookies {
        forward = "none"
      }
    }

    viewer_protocol_policy = "redirect-to-https"
    min_ttl                = 0
    default_ttl            = 3600
    max_ttl                = 86400

    lambda_function_association {
      event_type   = "origin-request"
      lambda_arn   = aws_lambda_function.host_rewrite.qualified_arn
      include_body = false
    }
  }

  viewer_certificate {
    acm_certificate_arn = aws_acm_certificate.wildcard.arn
    ssl_support_method  = "sni-only"
  }
}
```

**Lambda@Edge (Host Rewrite):**
```javascript
exports.handler = async (event) => {
    const request = event.Records[0].cf.request;

    // Host 헤더를 Azure Blob 도메인으로 변경
    request.headers['host'] = [{
        key: 'Host',
        value: 'drbackupstorage2024.z12.web.core.windows.net'
    }];

    return request;
};
```

**왜 Lambda@Edge?**
- CloudFront는 Origin으로 요청 시 Host 헤더를 사용자 도메인(www.goupang.shop)으로 전달
- Azure Blob은 자기 도메인만 허용
- Lambda@Edge로 Origin Request 시 Host 헤더 수정 필요

---

#### 3. ACM 인증서 (HTTPS)

**왜 2개 리전에 인증서가 필요한가?**

| 리전 | 인증서 | 용도 | 이유 |
|------|--------|------|------|
| **us-east-1** | *.goupang.shop | CloudFront용 | CloudFront는 전역 서비스라 us-east-1만 가능 |
| **ap-northeast-2** | *.goupang.shop | ALB용 | ALB는 리전 서비스라 서울 리전 필요 |

**ACM 인증서 생성:**
```hcl
# us-east-1 (CloudFront용)
provider "aws" {
  alias  = "us_east_1"
  region = "us-east-1"
}

resource "aws_acm_certificate" "cloudfront" {
  provider          = aws.us_east_1
  domain_name       = "*.goupang.shop"
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

# ap-northeast-2 (ALB용)
resource "aws_acm_certificate" "alb" {
  domain_name       = "*.goupang.shop"
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}
```

---

#### 4. Azure Blob Storage (정적 사이트 호스팅)

**점검 페이지 업로드:**
```bash
# Azure Blob Storage에 정적 웹사이트 활성화
az storage blob service-properties update \
  --account-name drbackupstorage2024 \
  --static-website \
  --index-document index.html

# 점검 페이지 업로드
az storage blob upload \
  --account-name drbackupstorage2024 \
  --container-name '$web' \
  --name index.html \
  --file maintenance.html
```

**점검 페이지 (index.html):**
```html
<!DOCTYPE html>
<html lang="ko">
<head>
    <meta charset="UTF-8">
    <title>서비스 점검 중 - PetClinic</title>
    <style>
        body {
            font-family: 'Noto Sans KR', sans-serif;
            display: flex;
            justify-content: center;
            align-items: center;
            height: 100vh;
            margin: 0;
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
        }
        .container {
            text-align: center;
            background: white;
            padding: 60px;
            border-radius: 20px;
            box-shadow: 0 10px 40px rgba(0,0,0,0.2);
        }
        h1 {
            color: #333;
            font-size: 48px;
            margin-bottom: 20px;
        }
        p {
            color: #666;
            font-size: 20px;
            margin: 10px 0;
        }
        .status {
            margin-top: 30px;
            padding: 20px;
            background: #f0f0f0;
            border-radius: 10px;
        }
    </style>
</head>
<body>
    <div class="container">
        <h1>🔧 서비스 점검 중</h1>
        <p>보다 나은 서비스 제공을 위해 일시적으로 점검 중입니다.</p>
        <div class="status">
            <p><strong>예상 복구 시간:</strong> 10분 이내</p>
            <p><strong>현재 시각:</strong> <span id="time"></span></p>
        </div>
        <p>이용에 불편을 드려 죄송합니다.</p>
    </div>
    <script>
        setInterval(() => {
            document.getElementById('time').textContent =
                new Date().toLocaleTimeString('ko-KR');
        }, 1000);
    </script>
</body>
</html>
```

---

## Failover 테스트

### 시나리오 1: AWS ALB 강제 중단

```bash
# 1. Primary Health Check 강제 실패
aws elbv2 modify-target-group \
  --target-group-arn $TG_ARN \
  --health-check-path /nonexistent

# 2. Route53 Health Check 모니터링
watch -n 5 'aws route53 get-health-check-status --health-check-id $HC_ID'

# 결과:
# T+0s: Healthy (정상)
# T+30s: Healthy (첫 번째 체크)
# T+60s: Unhealthy (두 번째 실패 → Failover 트리거!)
# T+90s: Unhealthy (DNS TTL 만료 → Secondary로 전환 완료)

# 3. 사용자 접속 확인
curl -I https://www.goupang.shop/
# HTTP/2 200
# server: CloudFront
# x-cache: Hit from cloudfront
# → Azure Blob 점검 페이지 표시 ✅
```

**Failover 소요 시간:**
- Health Check 실패 감지: 60초
- DNS 전파: 30초
- **총 RTO: 90초 ≈ 2분** ✅

---

### 시나리오 2: AWS 전체 리전 장애 시뮬레이션

실제 AWS 서울 리전 장애 시나리오를 가정했습니다.

```bash
# 1. EKS 클러스터 완전 중단
kubectl scale deployment --all --replicas=0 -n petclinic

# 2. ALB Health Check 실패
# (Target이 없으므로 자동 Unhealthy)

# 3. 사용자 경험 시뮬레이션
for i in {1..10}; do
  echo "T+${i}0s:"
  curl -s -o /dev/null -w "Status: %{http_code}, Time: %{time_total}s\n" \
    https://www.goupang.shop/
  sleep 10
done

# 결과:
# T+0s:  Status: 200, Time: 0.523s  (AWS ALB - 정상)
# T+10s: Status: 200, Time: 0.498s  (AWS ALB - 정상)
# T+20s: Status: 200, Time: 0.512s  (AWS ALB - 정상)
# T+30s: Status: 503, Time: 1.234s  (AWS ALB - Health Check 실패 시작)
# T+40s: Status: 503, Time: 1.201s  (AWS ALB - Health Check 실패 2회)
# T+50s: Status: 503, Time: 1.189s  (AWS ALB - Health Check 실패)
# T+60s: Status: 503, Time: 1.245s  (AWS ALB - Unhealthy 판정)
# T+70s: Status: 503, Time: 1.198s  (DNS 전환 진행 중...)
# T+80s: Status: 200, Time: 0.301s  (CloudFront - Failover 완료! ✅)
# T+90s: Status: 200, Time: 0.289s  (CloudFront - 점검 페이지)
```

**사용자 영향:**
- 정상 응답 (0-30초): 4회
- 503 에러 (30-80초): 5회 (약 50초 다운타임)
- 점검 페이지 (80초+): 정상 응답 (200 OK)

**개선 가능 포인트:**
- Health Check 간격 30초 → 10초로 단축
- Failure Threshold 2회 → 1회로 단축
- **예상 RTO: 30초 이내 가능**

---

## 성과 요약

### 정량적 성과

| 지표 | Before (온프레미스) | After (Multi-Cloud DR) | 개선 |
|------|-------------------|----------------------|------|
| **가용성** | 95% (월 36시간 다운) | **99.9%** (월 43분) | ✅ +4.9% |
| **DR RTO** | 4시간 (수동 복구) | **2분** (자동 Failover) | ✅ 120배 단축 |
| **DR 테스트** | 연 1회 (수동) | **매주 자동** (CI/CD) | ✅ 52배 증가 |
| **장애 대응** | 수동 (새벽 긴급 출근) | **자동** (Route53 Failover) | ✅ 완전 자동화 |
| **인프라 비용** | $200/월 | **$250/월** | ⚠️ +25% (고가용성 비용) |

---

### Failover 테스트 결과 (1개월)

```
총 Failover 테스트: 12회
성공: 12회 (100%)
평균 RTO: 95초 (목표: 120초)
최소 RTO: 78초
최대 RTO: 112초

False Positive (오탐지): 0건
False Negative (미탐지): 0건
```

---

## 핵심 교훈

### 1. 단일 클라우드 의존의 위험성

**Before (단일 클라우드):**
```
AWS 장애 → 전체 서비스 중단 (100%)
온프레미스 장애 → 전체 서비스 중단 (100%)
→ 고객 신뢰 하락
→ 매출 손실
```

**After (Multi-Cloud DR):**
```
AWS 장애 → 점검 페이지 표시 (고객에게 상황 안내)
→ 2분 내 Azure로 자동 전환
→ 서비스 연속성 유지
→ 고객 신뢰 유지 ✅
```

**교훈:**
- **No Single Point of Failure**
- 클라우드도 장애 날 수 있음 (AWS 서울 2023년 장애)
- Multi-Cloud 전략 필수

---

### 2. RTO vs 비용 트레이드오프

| DR 전략 | RTO | 월 비용 | 선택 |
|---------|-----|---------|------|
| **Backup/Restore** | 4시간+ | $10 | ❌ RTO 너무 김 |
| **Static Site Failover** | 2분 | $50 | ✅ **선택** |
| **Warm Standby** | 10분 | $500 | ❌ 비용 과다 |
| **Multi-Site Active** | 0분 | $1000+ | ❌ 비용 과다 |

**교훈:**
- 완벽한 DR (RTO 0분)은 비용이 매우 높음
- **목표에 맞는 적정 수준** 선택 중요
- 99.9% 가용성은 RTO 2분으로도 충분

---

### 3. 자동화의 중요성

**Before (수동 DR):**
```
1. 장애 감지 (모니터링 알림)
2. 긴급 출근 (30분+)
3. 장애 원인 파악 (30분+)
4. 수동 복구 (1시간+)
→ 총 RTO: 4시간+
```

**After (자동 DR):**
```
1. Route53 Health Check 자동 감지 (60초)
2. DNS 자동 전환 (30초)
3. CloudFront 자동 서빙 (즉시)
→ 총 RTO: 90초
→ 사람 개입 불필요 ✅
```

**교훈:**
- **자동화 = 빠른 RTO**
- 사람은 느리고 실수함
- 시스템이 자동으로 복구해야 함

---

## 🚧 남은 과제

### 1. Azure VM Failover (POC 완료)

현재는 점검 페이지만 표시하지만, Azure VM으로 전체 서비스 제공 가능합니다.

**POC 구현 완료:**
- Azure VM (nginx + Tomcat)
- Azure MySQL
- CloudFront → Azure VM
- **dr.goupang.shop** 도메인으로 테스트 완료 ✅

**Production 적용 시 고려사항:**
- DB 동기화 (AWS RDS → Azure MySQL)
- WAR 파일 동기화 (주간 백업)
- 비용 증가 (VM 24시간 운영)

---

### 2. Health Check 간격 단축

현재 30초 간격을 10초로 단축하면 RTO를 더 줄일 수 있습니다.

| 설정 | 현재 | 개선안 | RTO |
|------|------|--------|-----|
| **Interval** | 30초 | 10초 | - |
| **Threshold** | 2회 | 2회 | - |
| **DNS TTL** | 30초 | 10초 | - |
| **총 RTO** | 90초 | **30초** | ✅ 3배 단축 |

**비용 영향:**
- Health Check 요금: $0.50/월 → $1.50/월 (+$1)

---

## 관련 문서

- [AWS Route53 Failover Routing](https://docs.aws.amazon.com/Route53/latest/DeveloperGuide/routing-policy-failover.html)
- [CloudFront with Custom Origin](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/DownloadDistS3AndCustomOrigins.html)
- [Lambda@Edge Use Cases](https://docs.aws.amazon.com/lambda/latest/dg/lambda-edge.html)
- [DR 전체 가이드](https://github.com/wlals2/bespin-project/blob/main/docs/dr/DR-GUIDE.md)
- [CloudFront Lambda@Edge 구현](https://github.com/wlals2/bespin-project/blob/main/docs/dr/archive/046-cloudfront-lambda-edge-implementation.md)

---

**다음 읽기:**
- [Canary 배포: 무중단 배포와 즉시 롤백](./canary-deployment.md)
- [Redis Session Clustering](./redis-session.md)
