---
title: "Local K8s Blog - 베어메탈 Kubernetes 실전 운영"
date: 2026-02-19
summary: "베어메탈 Kubernetes 448일 운영: DevSecOps 파이프라인으로 개발·운영·보안이 하나의 흐름으로"
tags: ["kubernetes", "bare-metal", "devsecops", "gitops", "istio", "cilium", "falco", "wazuh", "argocd", "homelab"]
categories: ["projects"]
series: ["Infrastructure Learning Journey"]
weight: 4
showtoc: true
tocopen: true
draft: false
---

## 프로젝트 개요

> **상태**: ✅ **Production 운영 중** (448일, 2024.11.28 시작)
> **환경**: 베어메탈 Kubernetes 클러스터 (홈서버 4대)
> **핵심 철학**: 개발(Dev) · 보안(Sec) · 운영(Ops)이 별개의 단계가 아닌 **하나의 흐름**

이 블로그를 보고 있다면, 지금 이 순간에도 이 인프라가 돌아가고 있는 것입니다.

---

## 왜 DevSecOps인가

많은 팀에서 보안은 "배포 전에 한 번 확인하는 것"으로 취급됩니다. 개발자는 코드를 작성하고, 운영자는 배포하고, 보안 담당자는 사후에 감사합니다. 이 구조의 문제는 **피드백 루프가 너무 길다**는 것입니다.

이 프로젝트에서 목표한 것은 달랐습니다.

```
코드 Push
  → (즉시) Secrets 스캔 + 취약점 분석 + 단위 테스트  ← 개발 단계에서 보안 게이트
  → (3분 내) Canary 배포 + 이상 감지 알림            ← 배포가 곧 운영 모니터링
  → (실시간) 런타임 이상 행위 탐지 + 자동 대응        ← 운영이 곧 보안
```

보안이 파이프라인의 별도 단계가 아니라 **각 단계에 내장**됩니다.

---

## 전체 아키텍처

![Local K8s Architecture](../../../image/localk8s%20아키텍처.png)

### 9개 계층 구조

| 계층 | 기술 | 역할 |
|------|------|------|
| **1. Ingress** | Cloudflare + Istio Gateway | CDN 캐시, SSL/TLS 종료, DDoS 방어 |
| **2. Service Mesh** | Istio (mTLS) | Pod 간 암호화 통신, L7 트래픽 제어 |
| **3. Application** | WEB (Hugo) + WAS (Spring Boot) + MySQL | 블로그 + API + DB |
| **4. Storage** | Longhorn (3-replica) + Local-path | HA 분산 스토리지 |
| **5. CNI** | Cilium eBPF + Hubble | 네트워크 정책, 플로우 시각화 |
| **6. GitOps** | ArgoCD + Argo Rollouts | Git Push → 3초 내 자동 Canary 배포 |
| **7. Observability** | Prometheus + Loki + Grafana + Tempo | 메트릭 · 로그 · 트레이싱 통합 |
| **8. Runtime Security** | Falco + Falcosidekick + Talon | syscall 탐지 → 자동 격리 |
| **9. SIEM** | Wazuh | 보안 이벤트 중앙화, 규정 준수 |

---

## DevSecOps 파이프라인

개발·보안·운영이 하나의 흐름으로 연결되는 방식입니다.

### CI 파이프라인 (코드 Push 시)

```
git push
    │
    ├─[병렬] GitLeaks        Git 히스토리 전체 Secrets 탐지
    │        └─ 평문 토큰, 비밀번호 커밋 이력까지 검출
    │
    ├─[병렬] 단위 테스트      H2 In-Memory DB → MySQL 없이 검증
    │        └─ 테스트 리포트 아티팩트 자동 저장
    │
    └─[병렬] Docker 빌드     멀티스테이지 빌드 → GHCR 푸시
             └─ 빌드 검증 (이미지 크기 50MB 이상 확인)
                        │
             [3개 게이트 모두 통과 시]
                        │
                       CD → k8s-manifests 이미지 태그 업데이트
                          → ArgoCD Auto-Sync (3초 내)
                          → Argo Rollouts Canary 배포
                          → Discord 알림
```

**야간 보안 스캔 (매일 02:00 KST)**:
```
Trivy 야간 CVE 스캔
  → OS 패키지 + Java 라이브러리 통합
  → 매일 최신 CVE DB 기준 (어제 발표된 CVE도 탐지)
  → CRITICAL/HIGH 발견 시 Discord 알림
```

### CD 파이프라인 (Canary 전략)

```
WAS 배포: 20% → 50% → 80% → 100% (각 단계 1분 대기)
WEB 배포: 10% → 50% → 90% → 100% (각 단계 30초 대기)

이상 감지 시: 즉시 Rollback (10초)
정상 완료: 이전 버전 자동 삭제
```

### Runtime Security (배포 후 실시간)

```
애플리케이션 실행 중
    │
    Falco (eBPF syscall 감시)
    ├─ 이상 행위 탐지 (파일 접근, 권한 상승, 네트워크 이상)
    │
    Falcosidekick (이벤트 라우팅)
    ├─ → Loki (로그 저장)
    ├─ → Discord (즉시 알림)
    ├─ → Wazuh (SIEM 보안 이벤트 기록)
    └─ → Falco Talon (자동 대응)
              └─ CiliumNetworkPolicy 생성 → Pod 자동 격리
```

### SIEM 통합 (Wazuh)

```
이벤트 소스들
  Falco (런타임 이상 행위)
  Kubernetes Audit Log (API 접근 기록)
  OS Syslog (시스템 이벤트)
      │
      Wazuh Manager (룰 엔진 + 상관 분석)
      └─ Wazuh Dashboard (보안 이벤트 시각화)
```

Wazuh는 단순 로그 수집이 아닙니다. 여러 소스의 이벤트를 **상관 분석**해서 단독 이벤트로는 보이지 않던 패턴을 찾아냅니다.

---

## 운영 현황 (448일)

| 지표 | 수치 | 비고 |
|------|------|------|
| **운영 기간** | 448일 | 2024-11-28 ~ 현재 |
| **다운타임** | 계획 외 0 | 노드 장애 시 자동 복구 |
| **WAS 배포** | 327회 | Argo Rollouts 누적 generation |
| **WEB 배포** | 174회 | Hugo 빌드 포함 |
| **Canary 배포 시간** | WAS 3분, WEB 1.5분 | 단계적 트래픽 전환 |
| **Rollback** | 10초 | `argo rollouts abort` |
| **CI 게이트** | 3중 (Secrets + 테스트 + 빌드) | 모두 통과해야 배포 |
| **야간 보안 스캔** | 매일 02:00 | Trivy CVE |
| **런타임 보안** | 24/7 | Falco eBPF 상시 감시 |

---

## 기술 스택

### Platform

| 컴포넌트 | 버전 | 역할 |
|---------|------|------|
| Kubernetes | v1.31.13 | 베어메탈 4노드 클러스터 |
| containerd | v2.1.5 | 컨테이너 런타임 |
| Cilium CNI | - | eBPF 기반 네트워크 |
| Longhorn | - | 3-replica 분산 스토리지 |

### DevSecOps

| 컴포넌트 | 단계 | 역할 |
|---------|------|------|
| **GitLeaks** | CI (Push 즉시) | Git 히스토리 Secrets 스캔 |
| **단위 테스트** | CI (Push 즉시) | H2 In-Memory 기능 검증 |
| **Trivy** | CI 야간 스케줄 | OS + Java 라이브러리 CVE |
| **SealedSecrets** | 빌드 | Git에 암호화된 Secret 저장 |
| **Falco** | 런타임 | eBPF syscall 이상 행위 탐지 |
| **Falco Talon** | 런타임 | 탐지 → CiliumNetworkPolicy 자동 생성 |
| **Wazuh** | 운영 | SIEM, 보안 이벤트 상관 분석 |
| **Istio AuthorizationPolicy** | 운영 | L7 API 접근 제어 |
| **CiliumNetworkPolicy** | 운영 | L3/L4 Pod 간 트래픽 제어 |

### Observability

| 컴포넌트 | 역할 | Retention |
|---------|------|-----------|
| Prometheus | 메트릭 수집 | 15일 |
| Loki | 로그 중앙화 | 7일 |
| Grafana | 시각화 | - |
| Tempo | 분산 트레이싱 | - |
| OpenTelemetry | WAS → Tempo 트레이싱 자동 수집 | - |

### Application

| 컴포넌트 | 기술 | 역할 |
|---------|------|------|
| WEB | Hugo + Nginx | 정적 블로그 |
| WAS | Spring Boot (JDK 21) | REST API, RSS 뉴스 수집 |
| DB | MySQL 8.0 | 게시판 + 뉴스 데이터 |
| Registry | GHCR (ghcr.io) | Private 컨테이너 이미지 |

---

## Phase 3 (EKS)와 비교

| 항목 | Phase 3 (EKS) | 현재 (Homelab K8s) |
|------|--------------|-------------------|
| **환경** | AWS EKS (매니지드) | 베어메탈 kubeadm |
| **비용** | $258/월 | $0 |
| **보안 파이프라인** | 없음 | GitLeaks + Trivy + Falco + Wazuh |
| **배포 전략** | Blue-Green | Canary (Argo Rollouts) |
| **서비스 메시** | 없음 | Istio mTLS |
| **네트워크** | AWS VPC CNI | Cilium eBPF |
| **SIEM** | 없음 | Wazuh |
| **트레이싱** | 없음 | OpenTelemetry + Tempo |
| **실사용** | 샘플 앱 (PetClinic) | 실제 블로그 (매일 사용) |

---

## 핵심 학습 포인트

### 보안은 파이프라인에 내장된다

보안을 "나중에"로 미루면 복잡도만 높아집니다. 이 프로젝트에서 확인한 것은, CI 단계에 GitLeaks와 Trivy를 붙이는 것이 생각보다 어렵지 않다는 것입니다. 어렵게 느껴지는 이유는 대부분 "어디서부터 시작해야 하는가"를 몰라서입니다.

```
시작점: GitHub Actions에 gitleaks-action 한 줄 추가
→ 즉시 Git 히스토리 전체 Secrets 탐지
→ 비용: 0원, 추가 인프라: 없음
```

### 런타임 보안은 배포 후에도 필요하다

이미지 스캔과 테스트를 통과한 코드도, 실행 중에 예상치 못한 방식으로 동작할 수 있습니다. Falco는 **syscall 레벨**에서 감시하므로, 애플리케이션 코드의 취약점이 실제로 악용되는 순간을 잡을 수 있습니다.

```
예시: /etc/shadow 파일 읽기 시도
  → 정상 앱에서는 발생하지 않음
  → Falco가 즉시 감지 → Discord 알림 → Talon이 Pod 격리
  → 피해 범위가 해당 Pod로 제한됨
```

### GitOps는 감사 로그를 자동으로 만든다

`kubectl edit`으로 수동 변경하면 누가 언제 무엇을 바꿨는지 추적이 어렵습니다. Git을 Single Source of Truth로 사용하면 모든 변경이 커밋 히스토리로 남습니다.

```
Git commit history = 인프라 변경 감사 로그
```

### 베어메탈은 추상화가 없다

EKS에서는 노드 장애가 나도 AWS가 대부분 처리합니다. 베어메탈에서는 VMware 네트워크 끊김, Longhorn 볼륨 affinity, kubelet 타임아웃까지 직접 마주칩니다. 불편하지만, 이것이 실제로 Kubernetes가 어떻게 동작하는지를 이해하는 방법입니다.

---

## 주요 트러블슈팅

| 문제 | 원인 | 해결 |
|------|------|------|
| 뉴스 API 403 | Istio AuthorizationPolicy에 OPTIONS 메서드 누락 | `methods: ["GET", "OPTIONS"]` 추가 |
| 뉴스 미표시 | Spring Boot CORS 헤더 없음 | `WebMvcConfigurer` CORS 전역 설정 추가 |
| WAS CrashLoopBackOff | Cilium Egress가 RSS 외부 HTTPS 차단 | `toEntities: world` + port 443으로 변경 |
| Worker NotReady | VMware 네트워크 어댑터 간헐적 끊김 | 네트워크 어댑터 절전 비활성화 |
| Longhorn FailedAttachVolume | 볼륨 replicas가 SchedulingDisabled 노드에만 존재 | `kubectl uncordon` |
| Wazuh Dashboard 접근 불가 | Config 파일보다 환경 변수가 낮은 우선순위 | `opensearch_dashboards.yml` 직접 수정 |

---

## 관련 글

- [전체 아키텍처 가이드](/study/2026-01-25-local-k8s-architecture/)
- [Wazuh 실전 보안 모니터링](/study/2026-02-16-wazuh-실전-보안-모니터링/)
- [Falco → Wazuh 연동 (Helm Wrapper Chart)](/study/2026-02-12-falco-wazuh-helm-wrapper-chart/)
- [Wazuh Dashboard HTTP 트러블슈팅](/study/2026-02-12-wazuh-dashboard-http-troubleshooting/)
- [Cilium Hubble 관측성](/study/2026-01-22-cilium-hubble-observability/)
- [Canary 배포 전략 비교](/study/2026-01-21-canary-deployment-web-was-comparison/)
- [Longhorn & MySQL HA 전략](/study/2026-01-25-longhorn-mysql-ha-strategy/)

---

## 업데이트 로그

| 날짜 | 내용 |
|------|------|
| 2026-02-19 | Wazuh SIEM, DevSecOps 파이프라인, 운영 448일 반영하여 전면 개편 |
| 2026-01-25 | 전체 아키텍처 가이드 작성 |
| 2026-01-23 | Falco Runtime Security 구축 완료 |
| 2026-01-22 | Istio Service Mesh 전환 완료 |
| 2026-01-20 | GitOps 파이프라인 완성 |
| 2025-11-28 | 프로젝트 시작 |
