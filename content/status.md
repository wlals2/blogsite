---
title: "Infrastructure Status"
date: 2026-03-04
layout: "single"
url: "/status/"
summary: "홈랩 인프라 일일 상태 보고서 — Falco, Trivy, SLO, CI/CD 통합"
showtoc: true
tocopen: false
---
# Daily Report: 2026-03-04
> 기간: 2026-03-01 ~ 2026-03-04 (3일간)
> 생성: 2026-03-04 18:17:58

---

## 🔴 Critical Summary


- 🔴 Falco CRITICAL 182건 발생
- 🔴 비정상 Pod 존재
- 🟡 SLO 30d 89.9% (목표 99%)

---

## 🛡️ 보안 이벤트 (Falco)

| Priority | 건수 |
|----------|------|
| CRITICAL | 182건 |
| WARNING  | 133건 |
| ERROR    | 185건 |

### Rule별 상세

#### Critical
- `Drop and execute new binary in container`: 182건

#### Warning (상위 10개)
- `Read sensitive file untrusted`: 124건
- `Launch Package Management Process in Container`: 5건
- `Clear Log Activities`: 4건

> 📌 노이즈 주의: `Read sensitive file untrusted` 중 gdm/PAM 관련은 로그인 정상 동작

---

## 🖥️ 클러스터 상태

- **노드**: 5/5 정상
- **비정상 Pod**:
  - security-test/attack-simulator (ImagePullBackOff)
- **재시작 많은 Pod (5회 이상)**:
  - argo-rollouts/argo-rollouts-95dd6b7f7-9km4r 재시작:7회
  - falco/falco-fmq8b 재시작:9회
  - falco/falco-ggnbz 재시작:8회
  - falco/falco-k8696 재시작:8회
  - falco/falco-m2w4t 재시작:8회
  - kube-system/cilium-66lz2 재시작:5회
  - kube-system/cilium-envoy-9fcqn 재시작:12회
  - kube-system/cilium-envoy-gm8rs 재시작:5회
  - kube-system/cilium-envoy-qv4vs 재시작:50회
  - kube-system/cilium-envoy-w9p6v 재시작:10회

---

## 📊 SLO

| 기간 | Availability | 목표 |
|------|-------------|------|
| 24h  | 53.3% | 99% |
| 30d  | 89.9% | 99% |

---

## 🔍 이미지 보안 스캔 (Trivy)

> 스캔 범위: CRITICAL + HIGH / 최근 nightly 결과

**스캔 요약**

| 대상 | 타입 | 취약점 수 | Secrets |
|------|------|----------|---------|
| `ghcr.io/wlals2/board-was:latest (alpine 3.22.2)` | alpine | 29 | - |
| `app/app.jar` | jar | 5 | - |

**발견된 취약점**: CRITICAL=2,HIGH=1

| Severity | Library | CVE |
|----------|---------|-----|
| 🟠 HIGH | gnupg | CVE-2025-68973 |
| 🔴 CRITICAL | libcrypto3 | CVE-2025-15467 |
| 🔴 CRITICAL | libssl3 | CVE-2025-15467 |

---

## 🚀 CI/CD 이력 (최근 3일)

- 🔄 `03-04 09:14` Deploy WEB to Kubernetes
- ✅ `03-04 09:10` Deploy WEB to Kubernetes
- ✅ `03-04 08:44` Deploy WEB to Kubernetes
- ✅ `03-04 08:12` Deploy WEB to Kubernetes
- ✅ `03-04 03:58` Deploy WAS to Kubernetes
- ✅ `03-04 01:56` Update Homepage Metrics
- ❌ `03-03 18:33` Deploy WEB to Kubernetes
- ✅ `03-03 18:33` Deploy WAS to Kubernetes
- ✅ `03-03 15:32` Deploy WEB to Kubernetes
- ✅ `03-03 15:32` Deploy WAS to Kubernetes
- ✅ `03-03 10:18` Deploy WEB to Kubernetes
- ✅ `03-03 08:54` Deploy WEB to Kubernetes
- ❌ `03-03 08:54` Deploy WAS to Kubernetes
- ✅ `03-03 04:04` Deploy WAS to Kubernetes
- ✅ `03-03 03:07` Deploy WEB to Kubernetes
- ✅ `03-03 02:18` Deploy WEB to Kubernetes
- ✅ `03-03 02:10` Deploy WEB to Kubernetes
- ✅ `03-03 02:02` Update Homepage Metrics
- ✅ `03-03 01:10` Deploy WEB to Kubernetes
- ✅ `03-02 16:51` Deploy WEB to Kubernetes

---

## 📋 TODO

### 🚨 P0 (즉시)
- [ ] 30일 SLO Availability 77.6% 원인 분석

### ⚠️ P1 (단기)
- [ ] MySQL HA 구성
- [ ] Control Plane HA
- [ ] etcd Secret 암호화 (CIS 1.2.27, 1.2.28)

> 전체 목록: /home/jimin/docs/00-TODO.md

---

## 🔗 빠른 링크

| 도구 | URL |
|------|-----|
| Grafana | http://grafana.jiminhome.shop |
| Prometheus | http://prom.jiminhome.shop |
| Falcosidekick UI | http://falco-ui.jiminhome.shop |
| ArgoCD | http://argocd.jiminhome.shop |
