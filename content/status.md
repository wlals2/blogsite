---
title: "Infrastructure Status"
date: 2026-03-21
layout: "single"
url: "/status/"
summary: "홈랩 인프라 일일 상태 보고서 — Falco, Trivy, SLO, CI/CD 통합"
showtoc: true
tocopen: false
---
# Daily Report: 2026-03-21
> 기간: 2026-03-18 ~ 2026-03-21 (3일간)
> 생성: 2026-03-21 07:02:11

---

## 🔴 Critical Summary


- 🔴 비정상 Pod 존재

---

## 🛡️ 보안 이벤트 (Falco)

| Priority | 건수 |
|----------|------|
| CRITICAL | 0건 |
| WARNING  | 0건 |
| ERROR    | 0건 |

### Rule별 상세

#### Critical
- 없음

#### Warning (상위 10개)
- 없음

> 📌 노이즈 주의: `Read sensitive file untrusted` 중 gdm/PAM 관련은 로그인 정상 동작

---

## 🖥️ 클러스터 상태

- **노드**: 1/5 정상
- **비정상 Pod**:
  - falco/falco-8jl6c (CrashLoopBackOff)
  - falco/falco-kcqml (CrashLoopBackOff)
  - falco/falco-l6mlt (CrashLoopBackOff)
  - falco/falco-m79hf (CrashLoopBackOff)
  - monitoring/loki-report-query (Terminating)
- **재시작 많은 Pod (5회 이상)**:
  - falco/falco-8jl6c 재시작:154회
  - falco/falco-kcqml 재시작:158회
  - falco/falco-l6mlt 재시작:249회
  - falco/falco-m79hf 재시작:149회
  - kube-system/cilium-8zcqf 재시작:11회
  - kube-system/cilium-envoy-5mgnl 재시작:11회
  - kube-system/cilium-envoy-n2w2s 재시작:14회
  - kube-system/cilium-envoy-xc7q9 재시작:11회
  - kube-system/cilium-envoy-xlkn4 재시작:16회
  - kube-system/cilium-operator-69f67c-pfrx9 재시작:17회

---

## 📊 SLO

| 기간 | Availability | 목표 |
|------|-------------|------|
| 24h  | N/A% | 99% |
| 30d  | N/A% | 99% |

---

## 🔍 이미지 보안 스캔 (Trivy)

> 스캔 범위: CRITICAL + HIGH / 최근 nightly 결과

**스캔 요약**

| 대상 | 타입 | 취약점 수 | Secrets |
|------|------|----------|---------|
| `ghcr.io/wlals2/board-was:latest (alpine 3.23.3)` | alpine | 4 | - |
| `app/app.jar` | jar | 8 | - |

**발견된 취약점**: CRITICAL=1,HIGH=2

| Severity | Library | CVE |
|----------|---------|-----|
| 🟠 HIGH | gnutls | CVE-2026-1584 |
| 🔴 CRITICAL | libexpat | CVE-2026-32767 |
| 🟠 HIGH | libpng | CVE-2026-25646 |

---

## 🚀 CI/CD 이력 (최근 3일)

- ✅ `03-20 17:36` Deploy WAS to Kubernetes
- ✅ `03-20 14:19` Deploy WEB to Kubernetes
- ✅ `03-20 14:19` Deploy WAS to Kubernetes
- ✅ `03-20 12:44` Deploy WEB to Kubernetes
- ✅ `03-20 10:52` Deploy WEB to Kubernetes
- ✅ `03-20 08:26` Deploy WEB to Kubernetes
- ✅ `03-20 08:26` Deploy WAS to Kubernetes
- ✅ `03-20 02:00` Update Homepage Metrics
- ✅ `03-19 17:53` Deploy WAS to Kubernetes
- ✅ `03-19 02:06` Update Homepage Metrics
- ✅ `03-18 18:00` Deploy WAS to Kubernetes
- ✅ `03-18 02:05` Update Homepage Metrics

---

## 📋 TODO

### 🚨 P0 (즉시)
- 없음

### ⚠️ P1 (단기)
- [ ] MySQL HA 구성
- [ ] Control Plane HA

> 전체 목록: /home/jimin/docs/00-TODO.md

---

## 🔗 빠른 링크

| 도구 | URL |
|------|-----|
| Grafana | http://grafana.jiminhome.shop |
| Prometheus | http://prom.jiminhome.shop |
| Falcosidekick UI | http://falco-ui.jiminhome.shop |
| ArgoCD | http://argocd.jiminhome.shop |
