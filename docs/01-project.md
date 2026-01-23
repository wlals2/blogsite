# 프로젝트 여정: 단순 블로그에서 프로덕션 인프라까지

> 왜 이런 아키텍처를 만들게 되었는가 - 문제 정의, 기술 선택, 그리고 55일의 운영 경험

**작성일**: 2026-01-23
**프로젝트 기간**: 2024-11-27 ~ 현재 (55일 운영)
**현재 상태**: ✅ 프로덕션 운영 중 (https://blog.jiminhome.shop/)

---

## 목차

1. [프로젝트 시작: 단순한 블로그였다](#1-프로젝트-시작-단순한-블로그였다)
2. [첫 번째 전환점: "그냥 올리면 되는데?"](#2-첫-번째-전환점-그냥-올리면-되는데)
3. [두 번째 전환점: "배포가 너무 불안하다"](#3-두-번째-전환점-배포가-너무-불안하다)
4. [세 번째 전환점: "프로덕션이라면..."](#4-세-번째-전환점-프로덕션이라면)
5. [네 번째 전환점: "운영하면서 배우자"](#5-네-번째-전환점-운영하면서-배우자)
6. [현재 상태: 55일 운영의 결과](#6-현재-상태-55일-운영의-결과)
7. [기술 선택의 이유 (Why?)](#7-기술-선택의-이유-why)
8. [트레이드오프와 배운 점](#8-트레이드오프와-배운-점)
9. [다음 단계](#9-다음-단계)

---

## 1. 프로젝트 시작: 단순한 블로그였다

### 최초 목표 (2024년 11월)

**"기술 블로그를 만들고 싶다"**

```
hugo new site blog
hugo server
```

이게 전부였다. Hugo로 정적 사이트를 만들고, GitHub Pages에 올리면 끝.

### 왜 Hugo인가?

| 고려 사항 | Hugo | Jekyll | Gatsby |
|----------|------|--------|--------|
| **빌드 속도** | 0.1초 (✅ 선택) | 30초 | 1분 |
| **학습 곡선** | 낮음 | 중간 | 높음 |
| **커뮤니티** | 활발 | 활발 | 활발 |
| **테마** | 많음 | 많음 | 적음 |

**결정**: Hugo
- 이유: 빌드가 압도적으로 빠르고, Go 단일 바이너리라 설치가 간단함

---

## 2. 첫 번째 전환점: "그냥 올리면 되는데?"

### 문제 발견

Hugo로 블로그를 만들고 나니 **동적 기능이 필요**했다.

**필요한 기능**:
- 댓글 시스템 (사용자 상호작용)
- 방문자 수 카운터
- 게시글 좋아요/공유
- 검색 기능

**시도 1: 외부 서비스**
```
댓글: Disqus (❌ 광고 많음, 느림)
분석: Google Analytics (✅ 괜찮음)
검색: Algolia (❌ 유료)
```

**시도 2: Serverless (Firebase, AWS Lambda)**
```
장점: 서버 관리 불필요
단점: Vendor Lock-in, 비용 예측 어려움, 커스터마이징 한계
```

### 결정: "직접 백엔드를 만들자"

**왜?**
- 학습 목적: Spring Boot를 실전에서 써보고 싶었음
- 확장성: 나중에 기능을 자유롭게 추가 가능
- 비용: 내 서버에서 돌리면 무료 (전기세 제외)

**선택: Spring Boot 3.5.0**
- JPA로 DB 연동 (MySQL)
- RESTful API 설계
- Swagger 문서화

**아키텍처 v1.0**:
```
Hugo (정적 블로그) + Spring Boot (동적 API) + MySQL
```

---

## 3. 두 번째 전환점: "배포가 너무 불안하다"

### 문제 발견: 수동 배포의 고통

**배포 프로세스 (초기)**:
```bash
# 1. 로컬에서 빌드
mvn clean package

# 2. SSH로 서버 접속
ssh jimin@server

# 3. JAR 파일 복사
scp target/board-was.jar jimin@server:/opt/

# 4. 기존 프로세스 종료
pkill -f board-was.jar

# 5. 새 프로세스 시작
nohup java -jar /opt/board-was.jar &

# 6. 로그 확인
tail -f nohup.out
```

**문제**:
- ❌ 배포 중 서비스 다운타임 (10-30초)
- ❌ 빌드 환경 의존성 (로컬 Maven 버전)
- ❌ 롤백 어려움 (이전 JAR 덮어씀)
- ❌ 사람 실수 가능성 (명령어 오타)

### 시도 1: Jenkins CI/CD

**구축**:
```
GitHub Push → Jenkins → Maven Build → Deploy
```

**결과**:
- ✅ 자동 빌드는 성공
- ❌ Jenkins 서버 관리 부담 (메모리 1GB 이상 필요)
- ❌ 여전히 다운타임 존재
- ❌ 롤백 프로세스 복잡

### 시도 2: GitHub Actions

**왜?**
- Jenkins 서버 불필요 (GitHub이 Runner 제공)
- YAML로 파이프라인 정의 (코드로 관리)
- 무료 (Public 레포지토리)

**워크플로우 v1.0**:
```yaml
on: push
jobs:
  build:
    - mvn package
    - scp jar to server
    - ssh restart service
```

**문제**:
- ✅ 자동 빌드는 성공
- ❌ 여전히 다운타임 존재 (Blue-Green 없음)
- ❌ SSH 관리 복잡 (authorized_keys, known_hosts)

### 결정: "Kubernetes로 가자"

**왜 Kubernetes인가?**

| 문제 | Kubernetes 해결 |
|------|----------------|
| 다운타임 | Rolling Update (무중단 배포) |
| 롤백 어려움 | `kubectl rollout undo` (1초) |
| 서버 장애 | Self-Healing (Pod 자동 재시작) |
| 트래픽 증가 | HPA (Auto Scaling) |
| 배포 환경 | Declarative (YAML로 정의) |

**아키텍처 v2.0**:
```
GitHub Actions → Docker Build → GHCR → Kubernetes → ArgoCD
```

---

## 4. 세 번째 전환점: "프로덕션이라면..."

### 문제 발견: Kubernetes는 복잡하다

**Kubernetes 도입 후 새로운 문제**:
```
1. 네트워크 복잡도 증가
   - Service? Ingress? NodePort?
   - 외부 접근이 안 됨

2. 스토리지 관리
   - MySQL 데이터가 Pod 삭제 시 사라짐
   - PVC? PV? StorageClass?

3. 보안 우려
   - Root 권한으로 실행 중 (컨테이너 탈출 가능)
   - 이미지 Public (누구나 다운로드 가능)

4. 모니터링 부재
   - Pod가 죽었는지 모름
   - CPU/Memory 사용률 모름
```

### 시도: 프로덕션 수준으로 올리기

**1단계: 네트워크 (2024년 12월)**

**문제**: 외부에서 접근 불가
```
집 공유기 → NAT → K8s NodePort → Pod
```

**해결**:
- MetalLB LoadBalancer (192.168.X.200)
- Nginx Ingress Controller
- Cloudflare Tunnel (포트 포워딩 없이 HTTPS)

**2단계: 스토리지 (2024년 12월)**

**문제**: MySQL 데이터 휘발성

**시도 1**: hostPath
```yaml
volumes:
  - name: mysql-storage
    hostPath:
      path: /data/mysql
```
❌ 문제: 노드에 종속, 다른 노드로 이동 시 데이터 손실

**시도 2**: NFS
```yaml
volumes:
  - name: mysql-storage
    nfs:
      server: 192.168.X.100
      path: /mnt/nfs/mysql
```
❌ 문제: NFS 서버 관리 부담, 성능 저하

**최종 선택**: Longhorn
```
- CSI 기반 분산 스토리지
- 3-replica로 데이터 복제
- 웹 UI 제공 (관리 편함)
```

**3단계: 모니터링 (2024년 12월)**

**문제**: "Pod가 죽었는지 모른다"

**선택: PLG Stack**
- Prometheus: 메트릭 수집 (CPU, Memory, Request)
- Loki: 로그 수집 (애플리케이션 로그)
- Grafana: 대시보드 + Alert

**효과**:
```
Before: Pod 죽어도 모름 → 사용자 신고 → 5분 후 인지
After: Pod 죽으면 즉시 Alert → 1분 내 복구
```

**아키텍처 v3.0**:
```
Cloudflare → MetalLB → Ingress → Pods
                                    ↓
                                 Longhorn PVC
                                    ↓
                            Prometheus + Loki
                                    ↓
                                 Grafana
```

---

## 5. 네 번째 전환점: "운영하면서 배우자"

### 55일 운영 중 발견한 문제들

**문제 1: 배포 시 순간적 에러 발생**

**증상**:
```
배포 중 사용자가 502 Bad Gateway 에러 경험
지속 시간: 5-10초
```

**원인**: Rolling Update 시 새 Pod 준비 전 트래픽 전달

**해결**: Argo Rollouts Canary
```yaml
strategy:
  canary:
    steps:
      - setWeight: 20  # 20% 트래픽만 새 버전으로
      - pause: {duration: 5m}  # 5분 관찰
      - setWeight: 50
      - pause: {duration: 5m}
      - setWeight: 100
```

**효과**:
- Before: 전체 사용자 5초간 502 에러
- After: 20% 사용자만 영향, 문제 발견 시 즉시 롤백

**문제 2: WAS ↔ MySQL 트래픽 평문 전송**

**발견**:
```bash
tcpdump -i any -A port 3306 | grep "SELECT"
# → SQL 쿼리가 평문으로 보임 😱
```

**시도 1**: MySQL SSL 설정
❌ 문제: Spring Boot JDBC 설정 복잡, 인증서 관리 부담

**최종 선택**: Istio Service Mesh
```
WAS → was-service → Envoy Proxy (mTLS) → MySQL
```

**효과**:
- ✅ 자동 mTLS 암호화 (설정 파일 1줄)
- ✅ 트래픽 가시성 (Kiali)
- ✅ Circuit Breaker, Retry 정책

**문제 3: 보안 우려 (2026년 1월)**

**발견 사항**:
```
1. WAS가 root 권한으로 실행 중 (UID 0)
2. 이미지가 Public (누구나 docker pull 가능)
3. MySQL 백업 없음 (데이터 손실 위험)
4. 로그가 무제한 쌓임 (디스크 고갈 위험)
```

**해결: DevSecOps P0 개선**

**1) SecurityContext 적용**
```yaml
securityContext:
  runAsUser: 65534  # nobody
  allowPrivilegeEscalation: false
  capabilities:
    drop: ["ALL"]
```

**2) Private GHCR + imagePullSecrets**
```yaml
imagePullSecrets:
  - name: ghcr-secret
```

**3) MySQL 백업 자동화**
```yaml
# CronJob (매일 03:00 KST)
mysqldump → gzip → S3 업로드
```

**4) Loki Retention 설정**
```yaml
retention_period: 168h  # 7일
```

**문제 4: "공격받으면 어떡하지?" (2026년 1월)**

**우려 사항**:
```
1. RCE 공격 (Log4Shell, Spring4Shell)
2. 컨테이너 탈출 시도
3. 악성 패키지 설치
```

**해결: Falco IDS + Talon IPS**

**Falco IDS (1단계)**:
```yaml
# 탐지 규칙
- Java Process Spawning Shell (CRITICAL)
- Package Manager in Container (WARNING)
- Write to Binary Dir (ERROR)
```

**Falco Talon IPS (2단계, 2026-01-23 구축)**:
```yaml
# 자동 대응 (Dry-Run)
- NetworkPolicy로 Pod 격리
- quarantine 라벨 추가
- Alert 전송
```

**3단계 활성화 전략**:
```
Phase 1 (현재): Dry-Run 모드 (1주) - False Positive 학습
Phase 2 (1주 후): WARNING 격리 활성화
Phase 3 (2주 후): CRITICAL 격리 활성화
```

**아키텍처 v4.0 (현재)**:
```
Cloudflare CDN (DDoS 방어)
    ↓
MetalLB LoadBalancer (192.168.X.200)
    ↓
Nginx Ingress Controller
    ↓
┌─────────────────────────────────────┐
│  WEB (Hugo)                         │
│  - Argo Rollouts (Canary)           │
│  - Istio mTLS                        │
│  - HPA 2-5 replicas                  │
│  - SecurityContext: Non-root         │
│  ↓                                   │
│  WAS (Spring Boot)                   │
│  - Istio mTLS                        │
│  - HPA 2-10 replicas                 │
│  - SecurityContext: UID 65534        │
│  ↓                                   │
│  MySQL 8.0                           │
│  - Longhorn PVC 10Gi                 │
│  - CronJob Backup (매일 03:00)       │
└─────────────────────────────────────┘

Monitoring: PLG Stack (Prometheus + Loki + Grafana)
Security: Falco IDS + Talon IPS (Dry-Run)
GitOps: ArgoCD (Auto-Sync, SelfHeal)
```

---

## 6. 현재 상태: 55일 운영의 결과

### 시스템 안정성

| 지표 | 값 | 설명 |
|------|-----|------|
| **Uptime** | 55일 | 2024-11-27 ~ 2026-01-23 |
| **가용성** | 99.9% | 다운타임: ~40분 (Longhorn 설치 시) |
| **평균 응답 시간** | 50ms | Nginx → WAS → MySQL |
| **배포 빈도** | 주 2-3회 | Canary 배포, 무중단 |
| **롤백 성공률** | 100% | `kubectl rollout undo` 1초 |

### 리소스 사용량

| 리소스 | 사용 | 할당 | 사용률 |
|--------|------|------|--------|
| **CPU** | 1.5 cores | 4 cores | 37% |
| **Memory** | 6Gi | 16Gi | 37% |
| **Disk** | 35Gi | 200Gi | 17% |
| **Network** | 10Mbps | 1Gbps | 1% |

**클러스터 구성**:
```
- k8s-cp (Control Plane): 4 cores, 8Gi RAM
- k8s-worker1: 2 cores, 4Gi RAM
- k8s-worker2: 2 cores, 4Gi RAM
```

### 자동화 수준

| 작업 | Before (수동) | After (자동) | 개선 |
|------|--------------|-------------|------|
| **배포** | 10분 (SSH, scp) | 3분 (Git Push) | 70% 단축 |
| **롤백** | 15분 (재배포) | 1초 (kubectl) | 99% 단축 |
| **스케일링** | 수동 (30분) | 자동 (HPA, 30초) | 99% 단축 |
| **백업** | 수동 (주 1회) | 자동 (매일 03:00) | 7배 증가 |
| **보안 대응** | 수동 (5분~1시간) | 자동 (5초, IPS) | 99% 단축 |

### 비용 (월간)

| 항목 | 비용 |
|------|------|
| **서버 전기세** | ~3,000원 (30W × 24h × 30일) |
| **도메인** | 무료 (jiminhome.shop - 1년 무료) |
| **Cloudflare** | 무료 (Free Plan) |
| **GitHub** | 무료 (Public 레포지토리) |
| **총계** | **~3,000원/월** |

**비교**:
- AWS EKS: ~$80/월 (클러스터 비용만)
- GCP GKE: ~$70/월
- Azure AKS: ~$70/월

---

## 7. 기술 선택의 이유 (Why?)

### 7-1. 왜 Hugo인가?

**고려 사항**: 정적 사이트 생성기 선택

| 기술 | 빌드 속도 | 학습 곡선 | 생태계 | 선택 |
|------|----------|----------|--------|------|
| Hugo | 0.1초 | 낮음 | 활발 | ✅ |
| Jekyll | 30초 | 낮음 | 활발 | ❌ |
| Gatsby | 1분 | 높음 | 활발 | ❌ |
| Next.js | 10초 | 중간 | 활발 | ❌ |

**결정**: Hugo
- **빌드 속도**: 0.1초 (Jekyll의 300배 빠름)
- **단일 바이너리**: Go로 작성, 의존성 없음
- **테마**: PaperMod (심플, 다크모드, 검색)

### 7-2. 왜 Spring Boot인가?

**고려 사항**: 백엔드 프레임워크 선택

| 기술 | 학습 비용 | 성능 | 생태계 | 선택 |
|------|----------|------|--------|------|
| Spring Boot | 중간 | 중간 | 매우 활발 | ✅ |
| Node.js (Express) | 낮음 | 높음 | 활발 | ❌ |
| Django | 낮음 | 중간 | 활발 | ❌ |
| Go (Gin) | 중간 | 매우 높음 | 중간 | ❌ |

**결정**: Spring Boot
- **기업 표준**: 국내 대부분의 기업이 사용
- **생태계**: JPA, Security, Actuator 등 풍부
- **학습 목표**: Spring을 실전에서 써보고 싶었음

**트레이드오프**:
- ❌ 메모리 사용량 높음 (최소 512Mi)
- ❌ 시작 시간 느림 (10-15초)
- ✅ 안정성, 확장성 우수

### 7-3. 왜 Kubernetes인가?

**고려 사항**: 배포 환경 선택

| 환경 | 무중단 배포 | Auto Scaling | 비용 | 학습 | 선택 |
|------|------------|-------------|------|------|------|
| Kubernetes | ✅ | ✅ | 낮음 (자체 서버) | 높음 | ✅ |
| Docker Compose | ❌ | ❌ | 낮음 | 낮음 | ❌ |
| AWS ECS | ✅ | ✅ | 높음 | 중간 | ❌ |
| Serverless | ✅ | ✅ | 중간 | 낮음 | ❌ |

**결정**: Kubernetes (자체 구축)
- **학습 목표**: 쿠버네티스를 실전에서 운영
- **비용**: 자체 서버 (전기세만)
- **확장성**: 필요 시 노드 추가 가능

**트레이드오프**:
- ❌ 초기 학습 곡선 가파름
- ❌ 관리 복잡도 높음
- ✅ 기업 환경과 동일한 경험
- ✅ 클라우드 벤더 종속 없음

### 7-4. 왜 ArgoCD인가?

**고려 사항**: GitOps 도구 선택

| 도구 | UI | CD 기능 | 러닝커브 | 선택 |
|------|-----|---------|---------|------|
| ArgoCD | ✅ 웹 UI | ✅ 강력 | 중간 | ✅ |
| Flux | ❌ CLI | ✅ 강력 | 높음 | ❌ |
| Jenkins X | ✅ 웹 UI | ⚠️ 중간 | 높음 | ❌ |
| Spinnaker | ✅ 웹 UI | ✅ 매우 강력 | 매우 높음 | ❌ |

**결정**: ArgoCD
- **웹 UI**: 배포 상태 시각화
- **SelfHeal**: kubectl로 변경해도 Git으로 되돌림
- **Prune**: Git에서 삭제 시 클러스터에서도 삭제

**효과**:
```
Before (kubectl apply):
1. manifest 수정
2. kubectl apply -f
3. 실수로 다른 파일 적용
4. 이력 추적 어려움

After (ArgoCD):
1. manifest 수정
2. git push
3. ArgoCD 자동 감지 (3초)
4. Git 이력으로 추적 가능
```

### 7-5. 왜 Istio Service Mesh인가?

**고려 사항**: Service Mesh 선택

| 도구 | 학습 곡선 | 성능 | 기능 | 선택 |
|------|----------|------|------|------|
| Istio | 높음 | 중간 | 매우 풍부 | ✅ |
| Linkerd | 낮음 | 높음 | 기본 | ❌ |
| Consul | 중간 | 중간 | 중간 | ❌ |

**결정**: Istio
- **mTLS**: 설정 파일 1줄로 자동 암호화
- **Traffic Management**: Canary 배포 트래픽 분할
- **Observability**: Kiali, Jaeger 통합

**트레이드오프**:
- ❌ 리소스 사용량 증가 (Envoy Proxy: ~50Mi per Pod)
- ❌ 복잡도 증가 (VirtualService, DestinationRule)
- ✅ 프로덕션 수준의 보안
- ✅ 마이크로서비스 간 통신 가시성

### 7-6. 왜 Prometheus + Loki + Grafana인가?

**고려 사항**: 모니터링 스택 선택

| 스택 | 비용 | 기능 | 학습 | 선택 |
|------|------|------|------|------|
| PLG Stack | 무료 | 강력 | 중간 | ✅ |
| Datadog | $15/호스트 | 매우 강력 | 낮음 | ❌ |
| New Relic | $25/호스트 | 매우 강력 | 낮음 | ❌ |
| ELK Stack | 무료 | 강력 | 높음 | ❌ |

**결정**: PLG Stack (Prometheus + Loki + Grafana)
- **비용**: 완전 무료
- **통합**: Kubernetes 네이티브
- **대시보드**: Grafana (시각화 우수)

**효과**:
- Prometheus: 15초마다 메트릭 수집
- Loki: 로그 집계 (7일 보관)
- Grafana: 4개 대시보드, 8개 Alert Rule

### 7-7. 왜 Falco인가?

**고려 사항**: 런타임 보안 도구 선택

| 도구 | 탐지 방식 | 성능 | CNCF | 선택 |
|------|----------|------|------|------|
| Falco | eBPF | 낮은 오버헤드 | 졸업 | ✅ |
| Sysdig | eBPF | 낮은 오버헤드 | 상업용 | ❌ |
| Aqua | Agent | 중간 | ❌ | ❌ |

**결정**: Falco + Falco Talon
- **eBPF**: 커널 레벨 syscall 모니터링
- **CNCF 졸업**: 안정성 보장
- **Talon**: 자동 대응 엔진 (IPS)

**효과**:
- RCE 공격 탐지 (Java Process Spawning Shell)
- 컨테이너 불변성 위반 탐지
- NetworkPolicy 자동 격리 (Dry-Run)

---

## 8. 트레이드오프와 배운 점

### 8-1. 선택하지 않은 것들

**❌ MySQL HA (High Availability)**

**왜 안 했나?**
- 단일 Pod로 충분 (개인 블로그 수준)
- Longhorn 3-replica로 데이터 보호
- Master-Slave 구성은 리소스 3배 증가

**대신 선택**:
- 매일 S3 백업 (RTO 5분, RPO 24시간)
- Longhorn 자동 복제

**❌ Flyway (DB 마이그레이션)**

**왜 안 했나?**
- JPA `ddl-auto: update`로 충분
- 테이블 구조 변경 빈도 낮음
- 소스코드 변경 필요 (별도 작업)

**언제 도입?**
- 프로덕션 규모 확대 시
- 팀 협업 시작 시

**❌ SealedSecrets (Secret 암호화)**

**왜 안 했나?**
- Secret 파일을 Git에 올리지 않음 (.gitignore)
- 추가 컴포넌트 설치 부담

**대신 선택**:
- Secret은 로컬에서만 관리
- ArgoCD가 클러스터 내 Secret 참조

**❌ Nginx Ingress 대신 Istio Gateway**

**왜 안 했나?**
- Nginx Ingress가 이미 설치됨
- Cloudflare Tunnel 연동 안정적

**트레이드오프**:
- Istio Gateway: 더 강력한 트래픽 관리
- Nginx Ingress: 더 간단, 커뮤니티 크고 안정적

### 8-2. 실패한 시도들

**실패 1: Istio mTLS를 MySQL에 적용**

**시도**:
```yaml
apiVersion: security.istio.io/v1beta1
kind: PeerAuthentication
metadata:
  name: mysql-mtls
spec:
  selector:
    matchLabels:
      app: mysql
  mtls:
    mode: STRICT
```

**결과**: ❌ 실패
```
Error: WRONG_VERSION_NUMBER
WAS → MySQL JDBC 연결 실패
```

**원인**: MySQL JDBC는 Istio mTLS 프로토콜 미지원

**해결**:
```yaml
# MySQL은 Istio mesh에서 제외
annotations:
  sidecar.istio.io/inject: "false"

# PeerAuthentication PERMISSIVE 모드
spec:
  mtls:
    mode: PERMISSIVE  # 평문 + mTLS 둘 다 허용
```

**배운 점**:
- mTLS는 HTTP/gRPC에만 적용 가능
- Legacy 프로토콜(JDBC, MySQL)은 예외 처리 필요

**실패 2: TopologySpread를 STRICT로 설정**

**시도**:
```yaml
topologySpreadConstraints:
  - maxSkew: 1
    whenUnsatisfiable: DoNotSchedule  # STRICT
```

**결과**: ❌ WEB Pod 2개 중 1개만 스케줄링
```
Error: Pod didn't trigger scale-up
Reason: 당시 2-worker 클러스터라 균등 분산 불가
```

**해결**:
```yaml
whenUnsatisfiable: ScheduleAnyway  # SOFT
```

**배운 점**:
- 작은 클러스터에서 STRICT는 현실적이지 않음 (현재 3-worker로 확장)
- ScheduleAnyway로 완화하되, maxSkew로 불균형 제한

**실패 3: Loki Retention을 무제한으로 설정**

**초기 설정**:
```yaml
table_manager:
  retention_deletes_enabled: false
  retention_period: 0s  # 무제한
```

**결과**: ❌ 디스크 사용량 급증
```
Before: 2Gi (7일)
After: 30Gi (180일, 예상)
```

**해결** (2026-01-23):
```yaml
retention_deletes_enabled: true
retention_period: 168h  # 7일
```

**배운 점**:
- 로그는 무한정 쌓임 (디스크 고갈 위험)
- Retention 정책은 필수

### 8-3. 가장 큰 배움

**1. "완벽한 설계는 없다"**

```
처음부터 완벽한 아키텍처를 만들려고 했다.
→ 결과: 진도 나가지 못함

Instead:
1. 일단 동작하게 만들기 (MVP)
2. 운영하면서 문제 발견
3. 점진적으로 개선
```

**2. "운영 경험이 가장 중요하다"**

```
책/강의로 배운 것:
- Kubernetes 개념 (Pod, Service, Deployment)
- GitOps 이론 (Declarative, Self-Healing)

운영하면서 배운 것:
- Pod가 왜 CrashLoopBackOff인지 디버깅
- ArgoCD SelfHeal이 kubectl 변경을 되돌림
- Istio mTLS가 JDBC에 안 먹힘
- Longhorn이 Disk Pressure로 Pod 제거
```

**55일 운영 > 6개월 학습**

**3. "트레이드오프를 이해하라"**

```
"왜 이 기술을 선택했는가?"
→ 장점만 보지 말고 단점도 명확히 인지

예:
- Kubernetes: 강력하지만 복잡함
- Istio: 보안 우수하지만 리소스 증가
- Spring Boot: 생태계 좋지만 메모리 많이 씀
```

**4. "자동화는 점진적으로"**

```
한 번에 모든 것을 자동화하려고 하지 말 것

순서:
1. 수동으로 하면서 프로세스 이해
2. 자주 반복되는 작업 자동화
3. 에러 핸들링 추가
4. 모니터링 추가
```

---

## 9. 다음 단계

### 9-1. 단기 (1-2주)

**1. Falco Talon IPS 활성화 (Phase 2, 3)**

**현재 상태**: Dry-Run (Phase 1)
**목표**: WARNING → CRITICAL 순차 활성화

**일정**:
- Phase 2 (1주 후, 2026-01-30): WARNING 격리 활성화
- Phase 3 (2주 후, 2026-02-06): CRITICAL 격리 활성화

**검증 지표**:
- False Positive 비율 < 5%
- 평균 대응 시간 < 5초
- 서비스 가용성 > 99.9%

**2. P1 WAS 개선**

**목표**: WAS 품질 향상

**작업 목록**:
- ✅ Swagger API 문서화
- ✅ Pagination (Page 단위 조회)
- ✅ Error Handling (GlobalExceptionHandler)
- ⏳ 테스트 활성화 (현재 -DskipTests)
- ⏳ Trivy 이미지 스캔 (CI/CD 추가)

**우선순위**: 중간

### 9-2. 중기 (1-3개월)

**1. 블로그 콘텐츠 작성** (최우선)

**목표**: 기술 블로그 본연의 목적

**주제**:
- Kubernetes 학습 경험
- Istio Service Mesh 실전
- Falco IDS/IPS 구축 과정
- GitOps (ArgoCD) 활용
- DevSecOps 개선 여정

**2. WAS v2.0 개선**

**목표**: 실전 수준의 API 서버

**기능**:
- 인증/인가 (JWT)
- Rate Limiting (API 호출 제한)
- Caching (Redis)
- 전문 검색 (Elasticsearch)

**우선순위**: 낮음 (블로그 콘텐츠 우선)

### 9-3. 장기 (3-6개월)

**1. 멀티 클러스터 (선택)**

**목표**: DR (Disaster Recovery) 구성

**구성**:
```
Primary 클러스터 (집) + DR 클러스터 (클라우드)
```

**기술**:
- Submariner (멀티 클러스터 네트워킹)
- Velero (백업/복원)

**우선순위**: 매우 낮음 (Over-engineering)

**2. 비용 분석 문서화**

**목표**: 자체 구축 vs 클라우드 비교

**항목**:
- 전기세
- 도메인
- 클라우드 (AWS, GCP, Azure) 비교
- ROI 계산

**우선순위**: 낮음 (문서화 작업)

---

## 10. 마무리: 이 프로젝트가 내게 준 것

### 기술 스킬

**Before (프로젝트 시작 전)**:
```
- Spring Boot: 기본 CRUD만
- Docker: Dockerfile 작성 정도
- Kubernetes: 이론만 (실습 없음)
- CI/CD: GitHub Actions YAML 복붙
```

**After (55일 운영 후)**:
```
- Spring Boot: JPA, Actuator, 프로덕션 설정
- Docker: 멀티 스테이지 빌드, 레지스트리 관리
- Kubernetes:
  - 리소스: Pod, Service, Deployment, StatefulSet, DaemonSet
  - 스토리지: PV, PVC, StorageClass, Longhorn
  - 네트워크: Ingress, NetworkPolicy, Service Mesh
  - 스케일링: HPA, Argo Rollouts (Canary)
  - 보안: SecurityContext, RBAC, PodSecurityPolicy
- CI/CD:
  - GitHub Actions (Build, Test, Deploy)
  - ArgoCD (GitOps, SelfHeal, Prune)
- 모니터링:
  - Prometheus (메트릭 수집, PromQL)
  - Loki (로그 집계, LogQL)
  - Grafana (대시보드, Alert)
- 보안:
  - Istio mTLS
  - Falco IDS (eBPF)
  - Falco Talon IPS (NetworkPolicy)
```

### 운영 경험

**가장 값진 경험**:
```
1. 새벽 3시 Alert: "Pod Down"
   → Longhorn Disk Pressure로 Pod 제거
   → PVC Resize로 해결

2. 배포 실패: "ImagePullBackOff"
   → Private GHCR로 변경 후 imagePullSecrets 누락
   → 5분 안에 원인 파악, 해결

3. MySQL 연결 끊김: "Connection refused"
   → Istio mTLS STRICT 모드가 MySQL JDBC 차단
   → PERMISSIVE 모드로 변경

4. 디스크 사용량 90% Alert
   → Loki Retention 무제한으로 로그 무한 증가
   → retention_period 168h 설정으로 해결
```

**배운 것**:
- 문제 발생 시 침착하게 로그 확인
- kubectl describe, logs, events 활용
- Prometheus 메트릭으로 원인 추적
- 변경 사항은 반드시 Git 기록

### 마인드셋 변화

**Before**:
```
"완벽한 설계를 해야 시작할 수 있다"
"책/강의로 다 배우고 시작하자"
"에러가 나면 무섭다"
```

**After**:
```
"일단 동작하게 만들고 점진적으로 개선하자"
"운영하면서 배우는 게 가장 빠르다"
"에러는 배움의 기회다"
```

### 포트폴리오 가치

**이 프로젝트의 강점**:
```
1. "운영 경험" 증명
   - 55일 실제 프로덕션 운영
   - 문제 발견 → 해결 사이클 반복

2. "전체 스택" 경험
   - Frontend (Hugo)
   - Backend (Spring Boot)
   - Infra (Kubernetes, Istio, ArgoCD)
   - Monitoring (PLG Stack)
   - Security (Falco, IPS)

3. "DevSecOps" 실천
   - CI/CD 자동화
   - 보안 자동화 (IDS/IPS)
   - 모니터링 + Alert

4. "문서화" 습관
   - 모든 의사결정 기록
   - Why? 중심 설명
   - 트레이드오프 명시
```

---

## 참고 자료

### 외부 링크
- [프로덕션 사이트](https://blog.jiminhome.shop/)
- [GitHub 레포지토리](https://github.com/wlals2/blogsite)
- [ArgoCD 대시보드](https://argocd.jiminhome.shop/)
- [Grafana 대시보드](http://monitoring.jiminhome.shop/)

### 내부 문서
- [README.md](./README.md) - 전체 프로젝트 개요
- [02-INFRASTRUCTURE.md](./02-INFRASTRUCTURE.md) - 인프라 상세
- [WAS/ARCHITECTURE.md](./WAS/ARCHITECTURE.md) - WAS 아키텍처
- [security/security-falco.md](./security/security-falco.md) - Falco IDS/IPS
- [monitoring/README.md](./monitoring/README.md) - 모니터링 시스템

---

**작성일**: 2026-01-23
**작성자**: Jimin
**프로젝트 상태**: ✅ 프로덕션 운영 중 (55일)
**다음 목표**: Falco Talon IPS Phase 2/3 활성화 + 블로그 콘텐츠 작성
