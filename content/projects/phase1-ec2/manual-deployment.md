---
title: "수동 배포의 고통: SSH 4번, 30분, 주 4회 휴먼 에러"
date: 2025-10-20
summary: "EC2 수동 배포 과정과 자동화가 필요했던 이유"
tags: ["ec2", "manual-deployment", "ssh", "troubleshooting", "lessons-learned"]
categories: ["projects"]
series: ["Infrastructure Learning Journey"]
weight: 1
showtoc: true
tocopen: true
---

# 수동 배포의 고통: SSH 4번, 30분, 주 4회 휴먼 에러

> 자동화 전에 겪었던 수동 배포의 모든 고통을 기록합니다

---

## 📖 배경: EC2 인스턴스 기반 3-Tier

Phase 1에서는 전통적인 EC2 인스턴스 기반 3-Tier 아키텍처를 구축했습니다.

```
사용자
  ↓
ALB
  ↓
EC2 (nginx) × 2대 (WEB Tier)
  ↓
EC2 (Tomcat) × 2대 (WAS Tier)
  ↓
RDS MySQL (DB Tier)
```

**배포 대상:**
- WEB Tier: nginx 설정 파일 + 정적 파일
- WAS Tier: WAR 파일 (Spring Boot 애플리케이션)

---

## 🚨 수동 배포 절차 (30분 소요)

### Step 1: 로컬에서 빌드 (5분)

```bash
# 1. 소스 코드 최신화
cd ~/workspace/petclinic
git pull origin main

# 2. Maven 빌드
mvn clean package -DskipTests -P MySQL

# 결과:
# [INFO] Building war: /home/jimin/workspace/petclinic/target/petclinic-3.0.0.war
# [INFO] BUILD SUCCESS
# [INFO] Total time: 4:32 min
```

**문제점:**
- 로컬 환경마다 빌드 결과 다름
- 의존성 버전 충돌 빈번
- `-DskipTests` 사용 → 테스트 안 함

---

### Step 2: WEB Tier 배포 (10분, 2대)

#### WEB 1 배포

```bash
# 1. nginx 설정 파일 복사
scp ~/workspace/petclinic/nginx.conf \
    ec2-user@10.0.1.47:/tmp/

# 2. SSH 접속
ssh ec2-user@10.0.1.47

# 3. nginx 설정 적용
sudo cp /tmp/nginx.conf /etc/nginx/nginx.conf
sudo nginx -t  # 설정 파일 문법 검사
# nginx: configuration file /etc/nginx/nginx.conf test is successful

# 4. nginx 재시작
sudo systemctl reload nginx

# 5. 확인
curl http://localhost/health
# HTTP/1.1 200 OK

# 6. 로그아웃
exit
```

#### WEB 2 배포 (동일 반복)

```bash
scp ~/workspace/petclinic/nginx.conf \
    ec2-user@10.0.2.89:/tmp/

ssh ec2-user@10.0.2.89
sudo cp /tmp/nginx.conf /etc/nginx/nginx.conf
sudo nginx -t
sudo systemctl reload nginx
curl http://localhost/health
exit
```

**문제점:**
- **동일한 작업 2번 반복** → 지루함
- **설정 파일 복사 실수** → WEB 1과 WEB 2가 다름
- **휴먼 에러**: WEB 1에만 적용하고 WEB 2 깜빡함

---

### Step 3: WAS Tier 배포 (15분, 2대)

#### WAS 1 배포

```bash
# 1. WAR 파일 복사 (45MB, 30초 소요)
scp ~/workspace/petclinic/target/petclinic-3.0.0.war \
    ec2-user@10.0.11.47:/tmp/
# petclinic-3.0.0.war  100%   45MB   5.1MB/s   00:08

# 2. SSH 접속
ssh ec2-user@10.0.11.47

# 3. Tomcat 중지
sudo systemctl stop tomcat
# → 다운타임 시작 ⚠️

# 4. 기존 WAR 삭제
sudo rm -rf /opt/tomcat/webapps/petclinic*

# 5. 새 WAR 배포
sudo cp /tmp/petclinic-3.0.0.war /opt/tomcat/webapps/petclinic.war
sudo chown tomcat:tomcat /opt/tomcat/webapps/petclinic.war

# 6. Tomcat 시작
sudo systemctl start tomcat

# 7. 시작 대기 (30초)
sleep 30

# 8. Health Check
curl http://localhost:8080/petclinic/actuator/health
# {"status":"UP"}  ✅

# 9. 로그 확인
sudo tail -f /opt/tomcat/logs/catalina.out
# ...
# 2025-10-20 14:32:15 INFO  - Started PetClinicApplication in 28.3 seconds
# → 정상 시작 확인 후 Ctrl+C

# 10. 로그아웃
exit
```

#### WAS 2 배포 (동일 반복)

```bash
scp ~/workspace/petclinic/target/petclinic-3.0.0.war \
    ec2-user@10.0.12.89:/tmp/

ssh ec2-user@10.0.12.89
sudo systemctl stop tomcat
sudo rm -rf /opt/tomcat/webapps/petclinic*
sudo cp /tmp/petclinic-3.0.0.war /opt/tomcat/webapps/petclinic.war
sudo chown tomcat:tomcat /opt/tomcat/webapps/petclinic.war
sudo systemctl start tomcat
sleep 30
curl http://localhost:8080/petclinic/actuator/health
sudo tail -f /opt/tomcat/logs/catalina.out
exit
```

**문제점:**
- **다운타임 발생**: Tomcat 중지 → 재시작 (약 1분)
- **동일한 작업 2번 반복** → 매우 지루함
- **실수 가능성 높음**:
  - WAR 파일 경로 오타
  - 권한 설정 누락 (`chown`)
  - Health Check 확인 안 함

---

### Step 4: 최종 확인 (5분)

```bash
# 1. ALB 헬스체크 확인
aws elbv2 describe-target-health \
  --target-group-arn arn:aws:elasticloadbalancing:ap-northeast-2:123456789012:targetgroup/was-tg/abc123

# 출력:
# {
#     "TargetHealthDescriptions": [
#         {
#             "Target": { "Id": "i-0abc123", "Port": 8080 },
#             "HealthCheckPort": "8080",
#             "TargetHealth": { "State": "healthy" }  ✅
#         },
#         {
#             "Target": { "Id": "i-0def456", "Port": 8080 },
#             "HealthCheckPort": "8080",
#             "TargetHealth": { "State": "healthy" }  ✅
#         }
#     ]
# }

# 2. 브라우저 테스트
# https://www.goupang.shop/petclinic/ 접속
# → 로그인 테스트
# → 주요 기능 테스트

# 3. 로그 모니터링 (5분)
ssh ec2-user@10.0.11.47
sudo tail -f /opt/tomcat/logs/catalina.out
# → 에러 없는지 확인

# 4. 완료 ✅
```

---

## 🔥 실제 발생한 문제들

### 문제 1: 잘못된 서버에 배포 (주 1회)

**상황 (2025-10-05):**
```bash
# 개발 서버 IP: 10.0.11.100
# 운영 서버 IP: 10.0.11.47

# 의도: 운영 서버에 배포
# 실수: 개발 서버에 배포
scp petclinic.war ec2-user@10.0.11.100:/tmp/  # ❌ 잘못된 IP

# 결과:
# - 개발 서버: 운영 버전으로 덮어씀 (개발 중이던 기능 소실)
# - 운영 서버: 여전히 이전 버전
# - 고객: "왜 버그가 안 고쳐졌나요?" ❌
```

**해결 시간: 30분** (개발 서버 복구 + 운영 서버 재배포)

---

### 문제 2: 설정 파일 누락 (주 2회)

**상황 (2025-10-12):**
```bash
# WAS 1 배포
scp petclinic.war ec2-user@10.0.11.47:/tmp/
ssh ec2-user@10.0.11.47
sudo systemctl stop tomcat
sudo cp /tmp/petclinic.war /opt/tomcat/webapps/
sudo systemctl start tomcat
exit

# WAS 2 배포
scp petclinic.war ec2-user@10.0.12.89:/tmp/
ssh ec2-user@10.0.12.89
sudo systemctl stop tomcat
sudo cp /tmp/petclinic.war /opt/tomcat/webapps/
# ← chown 누락! ❌
sudo systemctl start tomcat

# Tomcat 시작 실패:
# java.io.FileNotFoundException: /opt/tomcat/webapps/petclinic.war (Permission denied)
```

**해결 시간: 20분** (디버깅 + 재배포)

---

### 문제 3: Tomcat 메모리 부족 (주 1회)

**상황 (2025-10-18):**
```bash
# 새 버전 배포 (메모리 사용량 증가)
sudo systemctl start tomcat

# 30초 대기...
curl http://localhost:8080/petclinic/actuator/health
# curl: (7) Failed to connect to localhost port 8080: Connection refused

# 로그 확인
sudo tail -f /opt/tomcat/logs/catalina.out
# java.lang.OutOfMemoryError: Java heap space

# 원인: 기본 메모리 설정 (512MB)이 부족
# 해결: /opt/tomcat/bin/setenv.sh 수정
sudo vi /opt/tomcat/bin/setenv.sh
# CATALINA_OPTS="-Xms512m -Xmx1g"  # 512m → 1g 증가

sudo systemctl restart tomcat
```

**해결 시간: 1시간** (원인 파악 30분 + 해결 30분)

**문제점:**
- WAS 1에서 해결 → WAS 2도 동일 문제 발생
- 2대 모두 수동으로 설정 변경 필요
- **환경 일관성 없음**

---

### 문제 4: 배포 순서 실수 (월 1회)

**상황 (2025-10-25):**
```bash
# 잘못된 순서:
# 1. Tomcat 시작 (WAR 파일 복사 전) ❌
sudo systemctl start tomcat

# 2. WAR 파일 복사
sudo cp /tmp/petclinic.war /opt/tomcat/webapps/

# 결과:
# - Tomcat이 빈 상태로 시작
# - WAR 파일 복사해도 자동 배포 안 됨
# - 다시 재시작 필요

# 올바른 순서:
# 1. WAR 파일 복사
# 2. Tomcat 시작
```

**해결 시간: 10분** (재시작)

---

## 📊 수동 배포 통계 (1개월)

### 배포 현황

```
총 배포 횟수: 16회
성공: 12회 (75%)
실패: 4회 (25%)

평균 배포 시간: 35분
최소 배포 시간: 28분 (모든 것이 순조로울 때)
최대 배포 시간: 2시간 15분 (문제 발생 시)

다운타임:
- WAS 1: 평균 1분 10초
- WAS 2: 평균 1분 15초
- 총 다운타임: 평균 2분 25초

야간 배포 (22시~익일 2시): 12회 (75%)
→ 고객 영향 최소화를 위해 야간 배포
```

---

### 휴먼 에러 통계

| 에러 유형 | 빈도 | 평균 해결 시간 |
|----------|------|--------------|
| **잘못된 서버에 배포** | 주 1회 | 30분 |
| **설정 파일 누락** | 주 2회 | 20분 |
| **Tomcat 재시작 실패** | 주 1회 | 1시간 |
| **배포 순서 실수** | 월 1회 | 10분 |
| **총 에러** | **주 4건** | **평균 30분** |

**월 휴먼 에러 시간:**
- 주 4건 × 4주 = 16건
- 16건 × 30분 = **480분 = 8시간**

**연 휴먼 에러 시간:**
- 8시간 × 12개월 = **96시간 = 12일**

---

## 💡 왜 이렇게 힘들었나?

### 1. 수동 작업의 한계

```
사람 = 실수함
특히 반복 작업 = 더 실수함
야간 배포 = 졸림 = 더더욱 실수함
```

**교훈:**
- 사람은 기계보다 느리고 실수함
- 반복 작업은 자동화 필수

---

### 2. 환경 불일치

```
로컬 (개발자 PC)
  ↓
WEB 1 (t3.medium, nginx 1.18)
  ↓
WEB 2 (t3.medium, nginx 1.20)  ← 버전 다름!
  ↓
WAS 1 (t3.medium, JDK 17, Xmx512m)
  ↓
WAS 2 (t3.medium, JDK 17, Xmx1g)  ← 메모리 설정 다름!
```

**교훈:**
- 수동 관리 → 환경 일관성 보장 불가
- 컨테이너(Docker) 필요

---

### 3. 추적 불가능

```
Q: 누가 언제 무엇을 배포했는가?
A: 모른다. (기록 없음)

Q: 이전 버전은 무엇인가?
A: 모른다. (WAR 파일 덮어씀)

Q: 이 설정은 왜 이렇게 했는가?
A: 모른다. (문서 없음)
```

**교훈:**
- Git으로 이력 관리 필수
- 배포 이력 추적 필요

---

## 🚀 Phase 2에서의 해결

Phase 2에서 **Jenkins + ArgoCD GitOps**로 모든 문제를 해결했습니다.

| 문제 | Phase 1 (수동) | Phase 2 (자동) |
|------|---------------|--------------|
| **배포 시간** | 30분 | 10분 (67% 단축) |
| **휴먼 에러** | 주 4건 | 0건 (100% 제거) |
| **환경 일관성** | 불일치 | 일치 (Docker) |
| **배포 이력** | 없음 | Git 커밋 이력 |
| **롤백** | 30분 (재배포) | 1분 (Git revert) |
| **다운타임** | 2분 | 0분 (Rolling Update) |
| **야간 배포** | 75% | 0% (언제든 가능) |

---

## 📖 핵심 교훈

### 1. 자동화는 선택이 아닌 필수

**수동 배포 비용 (1년):**
- 배포 시간: 16회/월 × 35분 × 12개월 = **112시간**
- 에러 해결: 16건/월 × 30분 × 12개월 = **96시간**
- **총 208시간 = 26일**

**자동화 도입 시간:**
- Jenkins + ArgoCD 구축: **40시간 = 5일**

**ROI (Return on Investment):**
- 투자: 5일
- 절감: 26일/년
- **1년 ROI: 420%**

**교훈:**
- 자동화는 처음엔 시간 들지만
- 장기적으로 엄청난 시간 절약

---

### 2. 휴먼 에러는 피할 수 없다

**사람의 실수:**
- 피곤할 때
- 졸릴 때
- 급할 때
- 반복 작업 시

**해결 방법:**
- 사람 = 전략, 설계, 의사결정
- 기계 = 반복 작업, 배포, 테스트

**교훈:**
- 사람을 탓하지 말고
- 시스템을 개선하라

---

### 3. 야근은 생산성을 떨어뜨린다

**야간 배포 (22시~2시):**
- 피곤함
- 졸림
- 집중력 저하
- 실수 증가

**자동화 후:**
- 낮에 언제든 배포 가능
- 무중단 배포 (Rolling Update)
- 야근 불필요 ✅

**교훈:**
- 야근으로 해결하지 말고
- 자동화로 해결하라

---

## 📚 관련 문서

- [Phase 2: CI/CD 파이프라인 구현](../phase2-k8s/cicd-pipeline.md)
- [Terraform IaC로 인프라 자동화](./index.md)
- [수동에서 IaC로 전환 케이스 스터디](./case-study.md)

---

**다음 읽기:**
- [Terraform으로 4시간 작업을 15분으로 단축](./index.md)
- [Phase 2: Kubernetes + CI/CD 도입](../phase2-k8s/index.md)
