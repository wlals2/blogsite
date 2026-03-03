---
title: "AWS-Azure DR 실전 Failover 테스트 가이드"
date: 2026-01-04T10:00:00+09:00
tags: ["Disaster Recovery", "AWS", "Azure", "Multi-Cloud", "High Availability"]
categories: ["study", "Cloud & Terraform"]
series: ["멀티 클라우드 DR 구축기"]
description: "AWS EKS에서 Azure VM으로의 실제 재해 복구 시나리오를 테스트했어요. Pod Scale Down 방식으로 DNS Failover를 트리거하고, Azure에서 서비스를 복원하는 전 과정을 단계별로 공유합니다."
showToc: true
draft: false
cover:
  image: "cover.jpg"
  alt: "AWS-Azure DR 실전 Failover 테스트 가이드"
  relative: true
---

## DR 테스트의 필요성을 느낀 순간

"DR 환경 구축했습니다!" 하고 발표는 했지만, 솔직히 불안했어요. **"진짜 장애가 나면 이게 작동할까?"**

DR 시스템은 실제로 테스트해보지 않으면 의미가 없어요. 그래서 정기적으로 Failover 테스트를 하기로 했고, 이번이 네 번째 테스트입니다.

## 우리 DR 아키텍처 간단 요약

### Primary (AWS EKS)

```
AWS EKS (ap-northeast-2, Seoul)
├── WAS Pods (Spring Boot PetClinic)
├── WEB Pods (nginx)
├── Redis Session Store
└── RDS MySQL (Primary DB)
```

### Secondary (Azure VM)

```
Azure VM (koreacentral, Seoul)
├── Tomcat (PetClinic WAR)
├── nginx (Reverse Proxy)
└── Azure MySQL Flexible Server
```

### Failover 방식

**Route53 Health Check 기반 DNS Failover**:
- AWS 정상: `www.goupang.shop` → ALB
- AWS 장애: `www.goupang.shop` → CloudFront (Blob) → Azure VM

## Failover 테스트 시나리오

### 목표

"AWS 리전 전체가 다운되는 상황"을 시뮬레이션해요.

**왜 이 시나리오인가요?**
- 실제로 2022년 AWS Seoul 리전이 6시간 다운된 적 있음
- 단순 Pod 장애는 Kubernetes가 자동 복구
- **리전 전체 장애만 DR이 필요**

### 테스트 방법: Pod Scale Down

처음엔 Health Check Endpoint를 조작하려고 했는데, 더 직관적인 방법을 찾았어요.

**Pod를 직접 내리기** (ArgoCD auto-sync 비활성화)
- 장점: 더 직관적이고 제어 가능
- 단점: ArgoCD가 자동 복구하지 않도록 주의 필요

## Phase 1: 데이터 백업

### 왜 먼저 백업하나요?

DR 환경의 핵심은 **"데이터 손실 최소화"**예요. Failover 전에 최신 데이터를 백업해야 해요.

### RDS 백업 실행

```bash
kubectl create job rds-backup-dr-test-$(date +%H%M) \
  --from=cronjob/rds-backup -n petclinic
```

**CronJob을 사용하는 이유**:
- 매일 자동 백업되지만, 테스트 시점에 즉시 백업 필요
- `--from=cronjob`으로 즉시 Job 생성

### 백업 로그 실시간 확인

```bash
# 백업 Pod 찾기
POD=$(kubectl get pods -n petclinic | grep rds-backup-dr-test | tail -1 | awk '{print $1}')

# Init Container (mysqldump) 로그
kubectl logs -f $POD -c mysql-backup -n petclinic

# Main Container (Azure 업로드) 로그
kubectl logs -f $POD -c azure-upload -n petclinic
```

**출력 예시**:
```
[mysql-backup] Starting mysqldump...
[mysql-backup] Dumping database petclinic...
[mysql-backup] Compressing with gzip...
[mysql-backup] Backup completed: petclinic-20260104-1430.sql.gz (2.3MB)

[azure-upload] Uploading to Azure Blob...
[azure-upload] Upload completed: https://petclinicdr2025.blob.core.windows.net/mysql-backup/petclinic-20260104-1430.sql.gz
```

### 백업 확인

```bash
az storage blob list \
  --account-name petclinicdr2025 \
  --container-name mysql-backup \
  --auth-mode login \
  --query "[0].{name:name, size:properties.contentLength}" -o table

# 결과:
# Name                              ContentLength
# --------------------------------  ---------------
# petclinic-20260104-1430.sql.gz    2387456
```

**체크리스트**:
- [ ] 백업 파일 생성 완료
- [ ] Azure Blob에 업로드 확인
- [ ] 파일 크기 정상 (> 1MB)

## Phase 2: Failover 트리거

### 1. ArgoCD Auto-Sync 비활성화

**왜 꺼야 하나요?**

ArgoCD는 기본적으로 Git과 실제 상태를 자동으로 동기화해요. Pod를 내려도 3분 내에 다시 올라와요.

```bash
kubectl patch application petclinic -n argocd --type=merge \
  -p '{"spec":{"syncPolicy":null}}'
```

**확인**:
```bash
kubectl get application petclinic -n argocd -o jsonpath='{.spec.syncPolicy}'
# 빈 값이면 정상 (auto-sync 꺼짐)
```

**주의**: 테스트 후 반드시 다시 켜야 해요!

### 2. WAS/WEB Pod Scale Down

```bash
kubectl scale rollout was -n petclinic --replicas=0
kubectl scale deployment web -n petclinic --replicas=0
```

**확인**:
```bash
kubectl get pods -n petclinic

# 예상:
# NAME                   READY   STATUS        RESTARTS   AGE
# was-abc123-xxx         0/1     Terminating   0          5m
# web-def456-aaa         0/1     Terminating   0          5m
# redis-master-0         1/1     Running       0          2d
```

**Redis는 왜 안 내리나요?**

Session Store는 유지해야 Failback 후 사용자 세션을 복원할 수 있어요.

### 3. DNS Failover 확인

```bash
# DNS 전파 대기 (1-2분)
sleep 60

# DNS 조회
nslookup www.goupang.shop
dig +short www.goupang.shop
```

**Before (AWS 정상)**:
```
k8s-petclinic-xxx-123456789.ap-northeast-2.elb.amazonaws.com
```

**After (AWS 장애)**:
```
dfg2fvjjvfrp8.cloudfront.net  (Blob 점검 페이지)
또는
d2npwlhpn3kbha.cloudfront.net  (Azure VM)
```

**체크리스트**:
- [ ] Pod 모두 Terminating → 사라짐
- [ ] DNS가 CloudFront로 변경됨
- [ ] Route53 Health Check 상태 Unhealthy

## Phase 3: Blob 점검 페이지 확인

### 왜 Blob 페이지가 먼저 나오나요?

Azure VM 생성에는 6-7분이 걸려요. 그 사이에 사용자에게 "점검 중"이라는 친절한 메시지를 보여줘야죠.

### 브라우저 확인

```
https://www.goupang.shop/
```

**예상 화면**:
```
╔════════════════════════════════════╗
║     서비스 점검 중입니다          ║
║                                    ║
║  현재 시스템 점검을 진행 중입니다.║
║  빠른 시일 내에 복구하겠습니다.   ║
║                                    ║
║  예상 복구 시간: 약 10분          ║
╚════════════════════════════════════╝
```

### CLI 확인

```bash
curl -I https://www.goupang.shop/

# HTTP/2 200
# server: cloudfront
# content-type: text/html
# x-cache: Hit from cloudfront
```

**체크리스트**:
- [ ] 점검 페이지 정상 표시
- [ ] CloudFront 캐시 활성화
- [ ] 사용자에게 친절한 메시지 표시

## Phase 4: Azure VM 생성 (Warm Standby)

### Terraform Apply

```bash
cd ~/bespin-project/terraform/azure-dr
terraform apply -var="dr_mode=warm" -auto-approve
```

**Warm Standby란?**
- Cold: 평소에 아무것도 없음 (6-7분 소요)
- **Warm**: VM만 미리 생성 (1-2분 소요)
- Hot: 항상 대기 중 (비용 많이 듦)

**소요 시간**: 약 6-7분 (MySQL 생성 포함)

### Output 확인

```bash
terraform output

# 결과:
# dr_vm_public_ip = "20.196.123.45"
# dr_vm_url = "http://20.196.123.45/petclinic/"
# mysql_fqdn = "dr-petclinic-mysql.mysql.database.azure.com"
```

**체크리스트**:
- [ ] VM Public IP 확인
- [ ] Azure MySQL FQDN 확인
- [ ] Terraform Apply 성공 (exit code 0)

## Phase 5: Cloud-init 확인

### VM SSH 접속

```bash
VM_IP=$(terraform output -raw dr_vm_public_ip)
ssh azureuser@$VM_IP
```

### Cloud-init 로그 실시간 확인

```bash
# VM 내부에서
sudo tail -f /var/log/dr-vm-init.log
```

**출력 예시**:
```
[2026-01-04 14:35:00] Installing Java 17...
[2026-01-04 14:35:30] Installing Tomcat 10...
[2026-01-04 14:36:00] Downloading PetClinic WAR from S3...
[2026-01-04 14:36:20] Deploying PetClinic to Tomcat...
[2026-01-04 14:37:00] Starting Tomcat service...
[2026-01-04 14:37:30] Installing nginx...
[2026-01-04 14:38:00] Configuring nginx reverse proxy...
[2026-01-04 14:38:30] Starting nginx service...

=========================================
DR VM Initialization Completed
=========================================
```

### 서비스 확인

```bash
# VM 내부에서
curl -s http://localhost/petclinic/ | grep -o "PetClinic"
# 결과: PetClinic

systemctl status tomcat nginx
# 결과: active (running)
```

**체크리스트**:
- [ ] Cloud-init 완료 메시지 확인
- [ ] Tomcat Running
- [ ] nginx Running
- [ ] PetClinic 응답 확인

## Phase 6: 데이터 복원

### Azure 로그인

```bash
# VM 내부에서
az login --identity
```

**Managed Identity란?**
VM이 비밀번호 없이 Azure 리소스에 접근할 수 있게 해줘요. 안전하고 편리해요.

### 최신 백업 다운로드

```bash
BACKUP=$(az storage blob list \
  --account-name petclinicdr2025 \
  --container-name mysql-backup \
  --auth-mode login \
  --query "[0].name" -o tsv)

echo "Downloading: $BACKUP"

az storage blob download \
  --account-name petclinicdr2025 \
  --container-name mysql-backup \
  --name "$BACKUP" \
  --file /tmp/backup.sql.gz \
  --auth-mode login
```

### 백업 복원

```bash
cd /tmp
gunzip -f backup.sql.gz

# GTID 구문 제거 (AWS RDS → Azure MySQL 호환성)
sed -i -e '/@@GLOBAL.GTID_PURGED/d' -e '/@@SESSION.SQL_LOG_BIN/d' backup.sql

# 복원 실행
MYSQL_PWD='PetclinicDR2024' mysql \
  -h dr-petclinic-mysql.mysql.database.azure.com \
  -u dbadmin \
  -D petclinic < backup.sql
```

**GTID 제거가 왜 필요한가요?**

AWS RDS와 Azure MySQL이 GTID(Global Transaction ID) 처리 방식이 달라요. 호환성을 위해 제거해야 해요.

### 복원 확인

```bash
MYSQL_PWD='PetclinicDR2024' mysql \
  -h dr-petclinic-mysql.mysql.database.azure.com \
  -u dbadmin \
  -D petclinic \
  -e "SELECT COUNT(*) FROM owners;"

# 결과: 10 (정상)
```

**체크리스트**:
- [ ] 백업 파일 다운로드 완료
- [ ] GTID 구문 제거 완료
- [ ] MySQL 복원 성공
- [ ] 데이터 조회 가능

## Phase 7: DR 서비스 확인

### Azure VM PetClinic 접속

```bash
# VM 외부에서
curl -I http://$(terraform output -raw dr_vm_public_ip)/petclinic/

# HTTP/1.1 200 OK
# Server: nginx/1.18.0 (Ubuntu)
# X-DR-Server: Azure-VM
```

### 브라우저 확인

```
https://dr.goupang.shop/petclinic/
```

**확인 사항**:
- [ ] 페이지 로딩 정상
- [ ] Azure 이미지 표시 (AWS와 다른 이미지)
- [ ] 데이터 조회 가능 (Owners 목록)
- [ ] Pet 등록/수정/삭제 기능 정상

**감동의 순간**:
처음 DR 환경에서 PetClinic이 로딩됐을 때, 정말 짜릿했어요. "진짜 되네!" 하는 순간이었죠.

## Phase 8: Failback (AWS 복구)

### 1. WAS/WEB Pod 복구

```bash
kubectl scale rollout was -n petclinic --replicas=2
kubectl scale deployment web -n petclinic --replicas=2
```

### 2. Pod 상태 확인

```bash
kubectl get pods -n petclinic -w

# 예상:
# NAME                   READY   STATUS              RESTARTS   AGE
# was-abc123-new         0/1     ContainerCreating   0          5s
# was-abc123-new         1/1     Running             0          30s
# web-def456-new         1/1     Running             0          25s
```

**-w 옵션**:
실시간으로 상태 변화를 볼 수 있어요. `Ctrl+C`로 중단.

### 3. ArgoCD Auto-Sync 재활성화

```bash
kubectl patch application petclinic -n argocd --type=merge \
  -p '{"spec":{"syncPolicy":{"automated":{"prune":true,"selfHeal":true}}}}'
```

**확인**:
```bash
kubectl get application petclinic -n argocd -o yaml | grep -A5 syncPolicy

# automated:
#   prune: true
#   selfHeal: true
```

### 4. DNS Failback 확인

```bash
sleep 60
dig +short www.goupang.shop

# 예상:
# k8s-petclinic-xxx-123456789.ap-northeast-2.elb.amazonaws.com
```

### 5. 서비스 확인

```bash
curl -I https://www.goupang.shop/petclinic/

# HTTP/2 200
# x-amzn-requestid: xxx-xxx-xxx (ALB 응답)
```

**체크리스트**:
- [ ] Pod 모두 Running (2/2)
- [ ] ArgoCD auto-sync 활성화
- [ ] DNS가 ALB로 복귀
- [ ] HTTPS 접속 정상
- [ ] Session 유지 (Redis 덕분!)

## Phase 9: Azure 리소스 정리 (Cold)

### Terraform Destroy

```bash
cd ~/bespin-project/terraform/azure-dr
terraform apply -var="dr_mode=cold" -auto-approve
```

**Cold 모드란?**
- VM, MySQL 모두 삭제
- Public IP만 유지 (다음 테스트에서 재사용)
- 비용 절감 (VM 삭제 시 요금 안 나옴)

**주의**: Public IP는 `prevent_destroy = true`로 보호돼 있어요.

## 전체 체크리스트

| Phase | 단계 | 확인 |
|-------|------|------|
| 1 | RDS 백업 완료 | ✅ |
| 2 | ArgoCD auto-sync 비활성화 | ✅ |
| 2 | Pod Scale Down (was=0, web=0) | ✅ |
| 3 | www.goupang.shop → 점검 페이지 | ✅ |
| 4 | Azure VM 생성 (warm) | ✅ |
| 5 | Cloud-init 완료 | ✅ |
| 6 | MySQL 데이터 복원 | ✅ |
| 7 | dr.goupang.shop → PetClinic 정상 | ✅ |
| 8 | Pod 복구 (was=2, web=2) | ✅ |
| 8 | ArgoCD auto-sync 재활성화 | ✅ |
| 8 | www.goupang.shop → AWS 복귀 | ✅ |
| 9 | Azure 리소스 정리 (cold) | ✅ |

## 배운 점

### 1. DR은 반드시 테스트해야 해요

구축만 하고 테스트 안 하면 의미가 없어요. 실제 장애 시 "왜 안 돼?!" 하고 당황하게 돼요.

### 2. Cloud-init 로그가 생명줄

VM이 제대로 초기화됐는지 확인하려면 `/var/log/dr-vm-init.log`를 반드시 확인해야 해요.

### 3. GTID 호환성 문제

AWS RDS와 Azure MySQL이 같은 MySQL인데도 GTID 처리가 달라요. 백업 복원 시 꼭 `sed`로 제거해야 해요.

### 4. ArgoCD Auto-Sync 관리

테스트 중 auto-sync를 끄는 걸 잊으면 Pod가 자동으로 다시 올라와서 테스트가 망가져요. **꼭 끄고 시작, 꼭 켜고 마무리!**

### 5. Session Store는 유지

Redis를 내리면 사용자 세션이 다 날아가요. Failback 후에도 로그인 상태를 유지하려면 Redis는 그대로 둬야 해요.

## 트러블슈팅 경험

### Cloud-init 실패

**증상**: VM은 생성됐는데 PetClinic이 안 뜨는 경우

**해결**:
```bash
# VM 내부에서
sudo cat /var/log/cloud-init-output.log
sudo cat /var/log/dr-vm-init.log
```

대부분 Java 설치 실패 또는 WAR 파일 다운로드 실패였어요.

### DNS가 전환되지 않음

**증상**: Pod를 내렸는데도 여전히 ALB로 연결

**원인**: Route53 Health Check가 아직 Healthy 상태

**해결**:
```bash
# Health Check 상태 강제 확인
aws route53 get-health-check-status --health-check-id b1ddbda0-eb95-48a9-a7c3-c42adade7210
```

약 1-2분 기다리면 Unhealthy로 전환돼요.

## 마무리

네 번째 DR 테스트를 성공적으로 완료했어요. 매번 할 때마다 새로운 걸 배우고, 프로세스가 개선돼요.

**이번 테스트에서 확인한 것**:
- Failover 시간: 약 7분 (Blob 점검 페이지 1분 + Azure VM 생성 6분)
- 데이터 손실: 0 (최신 백업 복원)
- Failback 시간: 약 3분 (Pod 재생성 2분 + DNS 전파 1분)
- Session 유지: ✅ (Redis 덕분)

**다음 개선 과제**:
- Warm Standby로 전환 (VM 미리 생성, Failover 시간 1분 이내)
- 자동화 스크립트 작성 (수동 명령어 → 한 번에 실행)
- DR 환경 모니터링 강화 (Azure VM 상태 체크)

DR은 한 번 구축하고 끝이 아니에요. 정기적인 테스트와 개선이 필수입니다! 🚀
