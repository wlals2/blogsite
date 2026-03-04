# 02. JD 분석 — 매칭 + 강점/약점

> 작성일: 2026-03-04
> 포지션: 아이나비 IT 시스템 운영 및 관리 (신입)
> 목적: 내 경험을 JD 언어로 번역 + 약점 파악 + 서류 작성 전략

---

## 1. JD 전체 항목별 분석

### 수행업무

| JD 항목 | 내 경험 | 수준 | 서류/면접 활용 포인트 |
|--------|--------|------|-------------------|
| **IT 인프라 시스템 전반 관리** | K8s 홈랩 전체 설계+운영 220일 | ★★★★★ | "1인 5노드 클러스터 전체 설계/운영" |
| **서버 Linux 구축/관리** | Ubuntu 베어메탈, kubeadm, systemd, SSH 하드닝 | ★★★★☆ | "베어메탈 서버 직접 운영 220일+" |
| **서버 Windows 구축/관리** | 경험 없음 | ★★☆☆☆ | "Windows Server 학습 중" (솔직하게) |
| **스토리지 구축/관리** | Longhorn 분산 스토리지 (K8s), S3 백업 자동화 | ★★★☆☆ | "K8s PV 기반 분산 스토리지 운영" |
| **네트워크 보안장비 구축/관리** | **넷코아텍 UTM IDS/IPS 실무 운영** | ★★★★★ | "실무 UTM 운영 경험 + 238K 공격 탐지" |
| **네트워크 L3/L4 구축/관리** | Cilium eBPF L3/L4, MetalLB L4 | ★★★★☆ | "eBPF 기반 네트워크 정책 운영" |
| **네트워크 L2 구축/관리** | VLAN, 스위칭 등 L2는 실습 수준 | ★★☆☆☆ | 솔직하게 부족함 인정 |
| **Private Cloud 구축/관리** | 베어메탈 K8s = 사실상 Private Cloud | ★★★★★ | "온프레미스 K8s로 Private Cloud 직접 구축" |
| **Public Cloud AWS 구축/관리** | EKS+VPC 직접 설계, Terraform | ★★★★☆ | "EKS 멀티AZ 클러스터 설계, Terraform IaC" |
| **사내 인트라넷 시스템 관리** | 경험 없음 | ★★☆☆☆ | "빠르게 습득 가능" |
| **전산 자산 관리** | 경험 없음 | ★☆☆☆☆ | 솔직하게 인정 |

---

### 우대사항

| 우대 항목 | 내 경험 | 충족도 |
|---------|--------|-------|
| 전산관련학과 | 부트캠프 (베스핀글로벌 클라우드 아키텍트 과정) | △ |
| 자격증 | 없음 (준비 중이면 언급) | ✗ |
| **보안솔루션 운영 경험** | **넷코아텍 UTM IDS/IPS 실무 + Falco/Wazuh/Kyverno** | **✅ 핵심 강점** |
| IDC 경험 | 없음 | ✗ |
| 운전 가능 | 운전면허 보유 여부 확인 | ? |

---

## 2. 강점 → 서류에서 강조할 것

### 강점 1: 보안솔루션 운영 (우대사항 정확 충족)

```
넷코아텍 실무:
  - UTM IDS/IPS 운영 (방화벽, DMZ, 보안 정책 관리)
  - 오탐률 문제 직접 경험 → 자동 차단 vs 수동 정책의 트레이드오프 이해

홈랩 보안 아키텍처:
  - Defense in Depth 6계층 직접 설계
  - Falco (런타임 IDS) + Talon (IPS) + Wazuh (SIEM)
  - SSH Brute Force 238,903건 탐지 + 대응
  - kube-bench CIS Benchmark 47 PASS / 0 FAIL

서류/면접 표현:
  "UTM 장비 운영 실무 경험을 바탕으로 현재 K8s 환경에서
   Falco IDS + Talon IPS + Wazuh SIEM으로 구성된
   Defense in Depth 보안 아키텍처를 직접 설계하고 운영 중입니다."
```

### 강점 2: Private Cloud = 온프레미스 K8s

```
JD: "Private/Public Cloud 구축/관리"

내 경험:
  - 베어메탈 5노드 K8s 클러스터를 kubeadm으로 직접 구축
  - Control Plane + Worker Node 역할 분리
  - Longhorn 분산 스토리지 운영
  - MetalLB LoadBalancer 구성
  - 220일 운영, 501회 배포 실패 0

서류/면접 표현:
  "온프레미스 환경에서 베어메탈 Kubernetes 클러스터를 직접 구축하고
   220일 이상 운영하며 Private Cloud 환경의 전반적인 구성 요소를
   직접 관리한 경험이 있습니다."
```

### 강점 3: 네트워크 + 보안장비 이해

```
JD: "네트워크(보안장비, L4, L3, L2) 구축/관리"

내 경험 (L3/L4 수준):
  - Cilium eBPF: L3/L4 NetworkPolicy (IP/Port 기반 접근 제어)
  - Istio: L7 트래픽 제어 (mTLS, AuthorizationPolicy)
  - MetalLB: L4 LoadBalancer 구현
  - 넷코아텍 UTM: 방화벽, DMZ, IDS/IPS

서류/면접 표현:
  "넷코아텍에서 UTM 보안장비(방화벽, IDS/IPS) 실무 운영 경험과
   K8s 환경에서 eBPF 기반 L3/L4 네트워크 정책을 설계한 경험이 있습니다."
```

### 강점 4: AWS + Terraform (Public Cloud)

```
JD: "Public Cloud(AWS) 구축/관리"

내 경험:
  - EKS Multi-AZ 클러스터 설계
  - VPC/Subnet/NAT Gateway/Security Group 직접 설계
  - Terraform으로 전체 인프라 코드화 (S3 State + DynamoDB Lock)
  - Route53 Health Check + Failover (87초 자동 전환)
  - 멀티클라우드 DR (AWS + Azure) 구현

서류/면접 표현:
  "AWS EKS 기반 Multi-AZ 아키텍처를 설계하고 Terraform으로 전체 인프라를
   코드화하여 DR 시스템을 구현한 경험이 있습니다."
```

---

## 3. 약점 → 솔직하게 인정 + 보완 방향

### 약점 1: Windows Server 운영 경험 없음

```
솔직한 수준: 개인 Windows 사용자 수준, 서버 버전 운영 경험 없음

면접 대응:
  "Windows Server 직접 운영 경험은 없습니다만,
   Linux 베어메탈 운영에서 쌓은 서버 운영 원리를 바탕으로
   빠르게 습득할 수 있다고 생각합니다."

사전 준비:
  - Windows Server 기본 개념 (AD, IIS, RDS) 간단히 공부
  - Linux vs Windows Server 차이점 파악
```

### 약점 2: IDC 경험 없음

```
솔직한 수준: 물리 데이터센터에서 일한 경험 없음

면접 대응:
  "IDC 직접 경험은 없지만, 베어메탈 서버 직접 설치/운영 경험이 있어서
   물리 인프라에 대한 기본적인 이해는 있습니다."

실제 근거:
  - 베어메탈 서버에 Ubuntu 직접 설치
  - 하드웨어 레벨 트러블슈팅 (네트워크 인터페이스, 스토리지)
```

### 약점 3: 전산 자산 관리 경험 없음

```
솔직한 수준: PC/SW 자산 관리 시스템(ITSM) 경험 없음

면접 대응:
  "전산 자산 관리 시스템은 직접 경험이 없습니다.
   다만 K8s 리소스 관리에서 리소스 현황 파악, 정책 기반 관리를 경험했고
   유사한 원리로 접근할 수 있을 것 같습니다."
```

### 약점 4: L2 네트워크 (VLAN, 스위칭) 깊이 부족

```
솔직한 수준: 개념은 알지만 실제 스위치 설정 경험 없음

면접 대응:
  "L2 스위칭/VLAN 실제 장비 설정 경험은 아직 부족합니다.
   L3/L4 레벨의 네트워크 정책은 운영 경험이 있고,
   L2는 빠르게 보완하겠습니다."
```

---

## 4. 서류(자유 양식 PDF) 구성 전략

### JD가 "자유 양식 PDF"를 요구하는 이유

```
"정해진 양식 없이 자유로운 형식의 지원서 PDF를 제출"

의미:
  - 지원자가 스스로 강점을 구성하고 표현하는 능력을 봄
  - 포트폴리오 스타일도 OK, 이력서 스타일도 OK
  - 단, 구체적 수치와 역할이 명확해야 함
```

### 권장 구성 (A4 2~3장)

```
Page 1: 요약 + 핵심 강점
  - 이름, 연락처
  - 한 줄 소개: "온프레미스 K8s + 보안솔루션 운영 경험의 IT 인프라 엔지니어"
  - 핵심 역량 3가지 (박스 형태):
    1. 온프레미스/클라우드 인프라 구축·운영
    2. 보안솔루션 운영 (UTM 실무 + DevSecOps)
    3. 자동화 기반 안정적 운영 (501회 배포 실패 0)

Page 2: 프로젝트 (3개)
  - 홈랩 K8s 프로젝트 (220일, 핵심 성과 포함)
  - Bespin EKS+DR 프로젝트 (RTO 30분 달성)
  - 넷코아텍 UTM 운영 (실무 경험)

Page 3: 기술 스택 + 자기소개
  - 기술 스택 (JD 관련 항목 중심)
  - 지원 동기 (왜 팅크웨어인가)
```

### 핵심 수치 (서류에 반드시 포함)

```
홈랩:
  - 220일+ 운영
  - 501회 배포, 실패 0건
  - CPU 리소스 22.4 → 6.25 Core (-72%)
  - SSH Brute Force 238,903건 탐지
  - kube-bench CIS PASS 47건

Bespin DR:
  - 장애 복구: 5시간+ → 2분 (자동 Failover)
  - 전체 복구(RTO): 6시간 목표 → 30분 달성
  - 스케일링: 30분 → 3분

넷코아텍:
  - UTM IDS/IPS 실무 운영 (기간 명시)
```

---

## 5. JD 키워드 → 내 언어로 매핑 (서류 작성 시 참조)

| JD 표현 | 내 실제 경험 | 서류 표현 |
|--------|-----------|---------|
| IT 인프라 시스템 전반 관리 | 홈랩 전체 설계+운영 1인 | "5노드 K8s 클러스터 전 영역 단독 설계/운영" |
| 서버 구축/관리 | Ubuntu 베어메탈 + kubeadm | "베어메탈 Ubuntu 서버 구축 및 K8s Control Plane/Worker Node 운영" |
| 네트워크 보안장비 | UTM 실무 + Falco/Wazuh | "UTM IDS/IPS 운영 실무 + eBPF 기반 런타임 보안 아키텍처 설계" |
| Private Cloud | 온프레미스 K8s | "온프레미스 Kubernetes 기반 Private Cloud 구축/운영" |
| Public Cloud(AWS) | EKS + Terraform | "AWS EKS Multi-AZ + Terraform IaC로 DR 시스템 구축" |
| 시스템 장애 대응 | 트러블슈팅 6건 | "Istio Protocol Detection 교착, kubelet 장애 등 6건+ 직접 해결" |

---

## 변경 이력
- 2026-03-04 v1.0.0: 초안 작성
