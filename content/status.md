---
title: "Infrastructure Status"
date: 2026-03-16
layout: "single"
url: "/status/"
summary: "홈랩 인프라 일일 상태 보고서 — Falco, Trivy, SLO, CI/CD 통합"
showtoc: true
tocopen: false
---
# Daily Report: 2026-03-16
> 기간: 2026-03-13 ~ 2026-03-16 (3일간)
> 생성: 2026-03-16 07:03:10

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
  - blog-system/mysql-pxc-pxc-0 (Terminating)
  - blog-system/was-f9858654d-26zbv (Terminating)
  - falco/falco-falcosidekick-ui-redis-0 (Terminating)
  - longhorn-system/instance-manager-4336265ce20c5c2419edac9e081210a6 (Terminating)
  - monitoring/loki-stack-0 (Terminating)
  - monitoring/tempo-5977d45fd8-dmwtg (Terminating)
  - monitoring/tempo-5977d45fd8-x8tgw (Pending)
- **재시작 많은 Pod (5회 이상)**:
  - blog-system/mysql-pxc-haproxy-0 재시작:239회
  - blog-system/mysql-pxc-haproxy-1 재시작:254회
  - blog-system/was-f9858654d-sr4p4 재시작:165회
  - kube-system/cilium-operator-69f67c-pfrx9 재시작:6회
  - kube-system/kube-controller-manager-k8s-cp 재시작:119회
  - kube-system/kube-scheduler-k8s-cp 재시작:117회
  - longhorn-system/engine-image-ei-3154f3aa-6rdg2 재시작:10회
  - longhorn-system/engine-image-ei-3154f3aa-84t8n 재시작:11회
  - longhorn-system/engine-image-ei-3154f3aa-d6xh6 재시작:36회
  - longhorn-system/engine-image-ei-3154f3aa-hb5wh 재시작:26회

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
| `ghcr.io/wlals2/board-was:latest (alpine 3.23.3)` | alpine | 3 | - |
| `app/app.jar` | jar | 8 | - |

**발견된 취약점**: CRITICAL=1,HIGH=1

| Severity | Library | CVE |
|----------|---------|-----|
| 🟠 HIGH | gnutls | CVE-2026-1584 |
| 🔴 CRITICAL | zlib | CVE-2026-22184 |

---

## 🚀 CI/CD 이력 (최근 3일)

- ✅ `03-15 17:29` Deploy WAS to Kubernetes
- ✅ `03-15 02:12` Update Homepage Metrics
- ✅ `03-14 22:00` Deploy WEB to Kubernetes
- ✅ `03-14 17:28` Deploy WAS to Kubernetes
- ✅ `03-14 09:54` Deploy WEB to Kubernetes
- ✅ `03-14 03:26` Deploy WEB to Kubernetes
- ✅ `03-14 01:56` Update Homepage Metrics
- ✅ `03-13 22:04` Deploy WEB to Kubernetes
- ✅ `03-13 19:18` Deploy WEB to Kubernetes
- ✅ `03-13 18:50` Deploy WEB to Kubernetes
- ✅ `03-13 17:36` Deploy WAS to Kubernetes
- ✅ `03-13 05:43` Deploy WEB to Kubernetes
- ✅ `03-13 01:58` Update Homepage Metrics

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
