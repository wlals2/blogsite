---
title: "AWS와 Azure를 연결한 하이브리드 클라우드 DR 구축기"
date: 2026-01-04T00:00:00+09:00
tags: ["DR", "Disaster Recovery", "AWS", "Azure", "Route53", "CloudFront", "Multi-Cloud"]
categories: ["DR & High Availability"]
description: "AWS 장애 시 Azure로 자동 Failover되는 듀얼 도메인 DR 시스템을 구축하면서 겪은 CloudFront 제약과 해결 과정"
---

## AWS가 죽으면 어떻게 하지?

EKS에서 PetClinic을 운영하다가 문득 이런 생각이 들었어요. "만약 AWS 전체가 다운되면?" 실제로 2021년 12월 AWS us-east-1 리전이 몇 시간 다운된 적이 있었거든요.

그래서 Azure를 Secondary로 사용하는 DR(Disaster Recovery) 시스템을 구축해봤습니다.

**목표:**
- AWS 장애 시 Azure VM으로 자동 전환
- 사용자는 도메인만 바꿔서 접속 (www → dr)
- RDS 백업을 Azure MySQL로 복원

---

## 처음 구상: 하나의 도메인으로 모두 해결?

처음엔 간단하게 생각했어요.

```
www.goupang.shop → CloudFront Origin Group
  ├─ Primary: AWS ALB
  └─ Secondary: Azure VM CloudFront (자동 Failover)
```

"CloudFront Origin Group에 AWS ALB를 Primary, Azure VM을 Secondary로 설정하면 자동으로 전환되겠지?"

---

## 막힌 벽 1: CloudFront Origin Group은 GET만 지원

실제로 Origin Group을 테스트해보니 **충격적인 제약**을 발견했어요.

| HTTP Method | Origin Failover 지원 |
|-------------|---------------------|
| GET | ✅ 지원 |
| HEAD | ✅ 지원 |
| POST | ❌ **미지원** |
| PUT | ❌ **미지원** |
| DELETE | ❌ **미지원** |

PetClinic은 Pet 등록, 수정, 삭제에 POST/PUT/DELETE를 사용해요. Origin Group으로는 불가능했습니다.

**AWS 공식 문서 확인:**
> "Origin failover supports only GET, HEAD, and OPTIONS requests."

---

## 막힌 벽 2: CloudFront Alias 제약

그럼 이렇게 하면 되지 않을까?

```
www.goupang.shop
  ├─ Route53 Failover: ALB (Primary)
  └─ Route53 Failover: CloudFront (Secondary)
     └─ CloudFront Alias: www.goupang.shop ← 여기서 막힘!
```

CloudFront에서 커스텀 도메인(Alternate Domain Names)을 설정하려면 **하나의 도메인은 하나의 Distribution에만** 설정 가능해요.

```
Distribution A: www.goupang.shop (ALB)
Distribution B: www.goupang.shop (Azure VM) ← ❌ 에러!

"The alternate domain name www.goupang.shop is already in use by another distribution"
```

---

## 해결책: 2개 도메인 전략

고민 끝에 내린 결론은 **도메인을 2개 사용**하는 것이었어요.

### 최종 아키텍처

```
┌─────────────────────────────────────────────────────────────────┐
│                        정상 상태                                 │
├─────────────────────────────────────────────────────────────────┤
│  www.goupang.shop → ALB → EKS PetClinic                        │
│  dr.goupang.shop  → ALB → EKS PetClinic (동일)                  │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                       AWS 장애 시                                │
├─────────────────────────────────────────────────────────────────┤
│  www.goupang.shop → Blob CloudFront → 점검 페이지               │
│                     "서비스 점검 중, dr.goupang.shop 이용"       │
│                                                                 │
│  dr.goupang.shop  → VM CloudFront → Azure VM PetClinic ✅       │
│                     (자동 Failover - Health Check 연동)          │
└─────────────────────────────────────────────────────────────────┘
```

**핵심 아이디어:**
1. **www.goupang.shop**: 평소엔 ALB → 장애 시 점검 페이지 (Azure Blob Storage)
2. **dr.goupang.shop**: 평소엔 ALB → 장애 시 Azure VM (전체 서비스)

### 왜 이렇게 했는가?

| 선택 | 이유 |
|------|------|
| **2개 도메인** | CloudFront Alias 제약 우회 |
| **www → 점검 페이지** | 사용자에게 안내 메시지 (dr 도메인 안내) |
| **dr → 전체 서비스** | POST/PUT/DELETE 지원 (Origin Group 사용 안 함) |
| **ALB Health Check 1개만 조작** | 양쪽 도메인 동시 전환 |

---

## Route53 Health Check의 마법

가장 중요한 부분이에요. **ALB를 바라보는 Health Check 1개만 조작**하면 2개 도메인이 모두 전환됩니다.

### Health Check 설정

| Health Check ID | 용도 | 엔드포인트 | 역할 |
|-----------------|------|-----------|------|
| `b1ddbda0-...` | www Primary | ALB HTTPS:443 /petclinic/ | www Failover 트리거 |
| `9629bc98-...` | dr Primary | ALB HTTPS:443 /petclinic/ | dr Failover 트리거 |
| `fbfd0487-...` | dr Secondary | VM CloudFront | VM 상태 확인 |

**동작 원리:**

```bash
# 정상 상태: ALB Health Check 성공
www.goupang.shop → ALB (healthy) ✅
dr.goupang.shop  → ALB (healthy) ✅

# Failover 트리거 (Health Check를 고의로 실패시킴)
aws route53 update-health-check \
  --health-check-id b1ddbda0-... \
  --inverted  # ← 결과를 반대로! (healthy → unhealthy)

# 5초 후...
www.goupang.shop → Blob CloudFront (점검 페이지) ✅
dr.goupang.shop  → VM CloudFront (Azure VM) ✅
```

**`--inverted` 플래그의 마법:**
- 실제 ALB는 정상이지만 (healthy)
- Health Check 결과를 반대로 해석 (unhealthy로 인식)
- Route53 Failover 정책이 Secondary로 전환

---

## Azure 쪽 구성

### 리소스

| 리소스 | 값 | 용도 |
|--------|-----|------|
| **VM** | Ubuntu 20.04, Standard_B2s | Tomcat + PetClinic WAR |
| **MySQL** | dr-petclinic-mysql.mysql.database.azure.com | Azure Database for MySQL |
| **Storage Account** | petclinicdr2025 | RDS 백업 파일 보관 (mysql-backup 컨테이너) |

### VM 초기화 (cloud-init)

VM이 생성되면 자동으로 다음 작업을 수행해요:

```yaml
# Terraform으로 VM 생성 시 cloud-init 스크립트 주입
custom_data = base64encode(<<-EOF
#!/bin/bash
# 1. Tomcat 9 설치
apt update && apt install -y tomcat9

# 2. Azure Storage에서 최신 RDS 백업 다운로드
az login --identity
BACKUP=$(az storage blob list \
  --account-name petclinicdr2025 \
  --container-name mysql-backup \
  --auth-mode login \
  --query "[0].name" -o tsv)
az storage blob download \
  --account-name petclinicdr2025 \
  --name "$BACKUP" \
  --file /tmp/backup.sql.gz

# 3. MySQL 복원
gunzip /tmp/backup.sql.gz
mysql -h dr-petclinic-mysql.mysql.database.azure.com \
  -u dbadmin -p'PetclinicDR2024' \
  -D petclinic < /tmp/backup.sql

# 4. PetClinic WAR 배포
wget https://원격저장소/petclinic.war -O /var/lib/tomcat9/webapps/petclinic.war
systemctl restart tomcat9
EOF
)
```

**왜 cloud-init인가?**
- VM 생성 후 수동 작업 없이 자동 구성
- Terraform으로 DR 모드 전환 시 항상 최신 상태로 시작

---

## Terraform으로 DR 모드 전환

### Warm Standby vs Cold Standby

| 모드 | VM 상태 | 비용 | RTO | 사용 시점 |
|------|---------|------|-----|----------|
| **Cold** | VM 삭제됨 | ~$0/월 | 10분 (VM 생성 + 복원) | 평상시 |
| **Warm** | VM 대기 중 | ~$30/월 | 1분 (Health Check 전환만) | DR 훈련/긴급 |

### 명령어

```bash
cd ~/bespin-project/terraform/azure-dr

# Cold → Warm (DR 훈련 전)
terraform apply -var="dr_mode=warm" -auto-approve
# → VM 생성, cloud-init으로 자동 복원

# Warm → Cold (평상시 복귀)
terraform apply -var="dr_mode=cold" -auto-approve
# → VM 삭제, 비용 절감
```

**실전 시나리오:**
```
평상시: Cold (VM 없음, 비용 $0)
  ↓
월 1회 DR 훈련: Warm으로 전환 → 테스트 → Cold 복귀
  ↓
실제 AWS 장애: Warm으로 전환 → Failover 트리거
```

---

## 실제 Failover 테스트 시나리오

### 1단계: 백업 생성 (30초)

```bash
# Kubernetes CronJob으로 매일 자동 백업
kubectl create job rds-backup-manual-$(date +%H%M) \
  --from=cronjob/rds-backup -n petclinic

# 백업 파일 Azure Storage로 전송 (CronJob이 자동 수행)
# → petclinicdr2025/mysql-backup/backup-20260104.sql.gz
```

### 2단계: Azure VM 생성 (5분)

```bash
terraform apply -var="dr_mode=warm" -auto-approve

# 대기 중 확인
watch terraform show -json | jq '.values.root_module.resources[] | select(.type=="azurerm_linux_virtual_machine") | .values.provisioning_state'
# → "Succeeded"
```

### 3단계: Failover 트리거 (5초)

```bash
# Health Check를 반대로 해석 → Failover 발동
aws route53 update-health-check \
  --health-check-id b1ddbda0-eb95-48a9-a7c3-c42adade7210 \
  --inverted
```

**5초 후:**
```bash
curl -I https://www.goupang.shop/
# x-amz-cf-id: ... (CloudFront)
# x-cache: Hit from cloudfront
# → Blob Storage 점검 페이지

curl -I https://dr.goupang.shop/petclinic/
# HTTP/1.1 200 OK
# Server: Apache-Coyote/1.1 (Tomcat)
# → Azure VM PetClinic ✅
```

### 4단계: 데이터 확인 (1분)

```bash
# Azure VM에 SSH 접속
az vm run-command invoke \
  --resource-group dr-petclinic-rg \
  --name dr-petclinic-vm \
  --command-id RunShellScript \
  --scripts "mysql -h dr-petclinic-mysql.mysql.database.azure.com -u dbadmin -p'PetclinicDR2024' -D petclinic -e 'SELECT COUNT(*) FROM owners'"

# 출력: 10 (백업 시점의 데이터 복원됨)
```

### 5단계: Failback (복구)

```bash
# AWS 복구 후 원래대로
aws route53 update-health-check \
  --health-check-id b1ddbda0-... \
  --no-inverted

# 5초 후
# www.goupang.shop → ALB (AWS) ✅
# dr.goupang.shop  → ALB (AWS) ✅
```

---

## 트러블슈팅 경험담

### 문제 1: Blob CloudFront 502 에러

**증상:**
```bash
curl https://www.goupang.shop/
# 502 Bad Gateway
```

**원인:**
CloudFront Origin에 잘못된 Storage Account 설정

```bash
# Origin 확인
aws cloudfront get-distribution --id E1DJ8TDD0AODCX \
  --query "Distribution.DistributionConfig.Origins.Items[0].DomainName"

# 출력: "petclinicdr2024.blob.core.windows.net" ← 틀림!
# 정답: "petclinicdr2025.blob.core.windows.net"
```

**해결:**
Terraform에서 Storage Account 이름 수정 후 재배포

---

### 문제 2: MySQL 복원 시 GTID 에러

**증상:**
```bash
mysql < backup.sql
# ERROR 1839 (HY000): @@GLOBAL.GTID_PURGED can only be set when @@GLOBAL.GTID_EXECUTED is empty
```

**원인:**
AWS RDS의 GTID 설정이 Azure MySQL과 호환 안 됨

**해결:**
```bash
# 백업 파일에서 GTID 관련 줄 제거
sed -i -e '/@@GLOBAL.GTID_PURGED/d' \
       -e '/@@SESSION.SQL_LOG_BIN/d' \
       backup.sql

# 다시 복원 → 성공!
```

---

### 문제 3: cloud-init 실패 디버깅

**증상:**
VM은 생성됐는데 Tomcat이 안 뜸

**확인:**
```bash
# VM 내부에서
sudo cat /var/log/dr-vm-init.log

# 에러: "az: command not found"
# → cloud-init 스크립트에서 Azure CLI 설치 추가 필요
```

**해결:**
```bash
#!/bin/bash
# Azure CLI 설치 추가
curl -sL https://aka.ms/InstallAzureCLIDeb | sudo bash
az login --identity
# ... (나머지 스크립트)
```

---

## 알게 된 CloudFront 제약사항

### 1. CloudFront Alias 제약

**문제:**
- 동일 도메인을 여러 CloudFront Distribution에 설정 불가
- `www.goupang.shop`을 Distribution A(ALB), B(Azure VM) 동시 사용 불가

**해결:**
- 2개 도메인 사용 (www, dr)

---

### 2. Origin Group 제약

**문제:**
- Origin Group은 GET/HEAD만 Failover 지원
- POST/PUT/DELETE는 Primary 실패 시 에러 반환

**해결:**
- Origin Group 사용 안 함
- Route53 Failover + 독립적인 CloudFront Distribution

---

### 3. ALB 공유 영향

**문제:**
- PetClinic, Grafana, ArgoCD가 동일 ALB 사용
- AWS 장애 시 모니터링 도구도 접근 불가

**트레이드오프:**
- DR 상태는 Azure Portal 또는 AWS CLI로 확인
- 향후 개선: 외부 모니터링 도구 (Datadog, New Relic)

---

## 배운 점과 트레이드오프

### 선택한 것

| 항목 | 선택 | 이유 |
|------|------|------|
| **Cloud** | AWS + Azure (Multi-Cloud) | 단일 클라우드 장애 대비 |
| **도메인** | 2개 (www, dr) | CloudFront 제약 우회 |
| **Failover** | Route53 Health Check | 5초 내 자동 전환 |
| **Standby** | Cold (평상시 VM 삭제) | 비용 절감 (~$30/월 → $0) |
| **백업** | RDS 자동 백업 → Azure Storage | 별도 인프라 보관 |

### 포기한 것

| 항목 | 포기한 이유 |
|------|-----------|
| **단일 도메인** | CloudFront Alias 제약 |
| **Origin Group** | POST/PUT/DELETE 미지원 |
| **Active-Active** | 비용 부담 (Azure VM 항상 켜둠) |
| **모니터링 HA** | DR 시 일시적 모니터링 불가 (수동 확인) |

### 실제 가치

**비용 vs 안정성:**
```
Cold Standby:
- 평상시: $0/월
- DR 테스트 (월 1회 1시간): ~$1/월
- 실제 장애 (1주일): ~$30/월

→ 연간 ~$50 정도로 AWS 전체 장애 대비 가능
```

**RTO (Recovery Time Objective):**
```
Cold: 10분 (VM 생성 5분 + cloud-init 5분)
Warm: 1분 (Health Check 전환 5초 + DNS 전파 1분)

→ 월 1회 DR 훈련 시 Warm으로 미리 준비
```

---

## 다음 단계

### 30분 내 개선 가능

1. **Azure MySQL Replica 자동화** (20분)
   - RDS → Azure MySQL 스트리밍 복제
   - 현재: 백업 파일 복원 (최대 24시간 데이터 손실)
   - 개선: 실시간 복제 (RPO 0초)

2. **Failover 알림 추가** (10분)
   ```bash
   # Route53 Health Check Alarm
   aws cloudwatch put-metric-alarm \
     --alarm-name "DR-Failover-Triggered" \
     --alarm-actions "arn:aws:sns:...:slack-notification"
   ```

### 1시간+ 개선 항목

3. **Active-Active 구성** (4시간)
   - 양쪽 클라우드 동시 운영
   - Route53 Weighted Routing (50:50)
   - 더 높은 비용 (~$60/월)

4. **DB Cross-Region 복제** (2시간)
   - AWS DMS로 RDS → Azure MySQL 실시간 복제
   - RPO 거의 0초

---

## 관련 명령어 모음

### Failover 트리거
```bash
aws route53 update-health-check \
  --health-check-id b1ddbda0-eb95-48a9-a7c3-c42adade7210 \
  --inverted
```

### Failback
```bash
aws route53 update-health-check \
  --health-check-id b1ddbda0-eb95-48a9-a7c3-c42adade7210 \
  --no-inverted
```

### VM 생성/삭제
```bash
cd ~/bespin-project/terraform/azure-dr

# Warm Standby
terraform apply -var="dr_mode=warm" -auto-approve

# Cold Standby
terraform apply -var="dr_mode=cold" -auto-approve
```

### RDS 백업 수동 실행
```bash
kubectl create job rds-backup-manual-$(date +%H%M) \
  --from=cronjob/rds-backup -n petclinic
```

### Azure VM에서 MySQL 복원
```bash
# VM SSH 접속 후
az login --identity
BACKUP=$(az storage blob list \
  --account-name petclinicdr2025 \
  --container-name mysql-backup \
  --auth-mode login \
  --query "[0].name" -o tsv)

az storage blob download \
  --account-name petclinicdr2025 \
  --name "$BACKUP" \
  --file /tmp/backup.sql.gz \
  --auth-mode login

cd /tmp && gunzip -f backup.sql.gz
sed -i -e '/@@GLOBAL.GTID_PURGED/d' \
       -e '/@@SESSION.SQL_LOG_BIN/d' backup.sql

MYSQL_PWD='PetclinicDR2024' mysql \
  -h dr-petclinic-mysql.mysql.database.azure.com \
  -u dbadmin -D petclinic < backup.sql
```

---

## 체크리스트

### DR 테스트 전 확인

- [ ] RDS 백업 완료 (`kubectl get cronjob -n petclinic`)
- [ ] Azure Storage에 백업 파일 존재 확인
- [ ] Terraform 상태 확인 (`terraform plan`)
- [ ] Health Check 정상 (`aws route53 get-health-check`)

### Failover 테스트

- [ ] VM 생성 (Warm) - 5분
- [ ] Failover 트리거 - 5초
- [ ] www.goupang.shop → 점검 페이지 확인
- [ ] dr.goupang.shop → PetClinic 정상 동작 확인
- [ ] 데이터 정합성 확인 (Owner 개수 등)

### Failback

- [ ] AWS 서비스 복구 확인
- [ ] Health Check 정상화 (`--no-inverted`)
- [ ] www/dr 모두 AWS ALB로 복귀 확인
- [ ] VM 삭제 (Cold) - 비용 절감

---

**작성일:** 2026-01-04
**아키텍처:** AWS EKS + Azure VM
**Failover RTO:** Cold 10분 / Warm 1분
**비용:** ~$1/월 (Cold), ~$30/월 (Warm)
