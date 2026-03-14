---
title: "Infrastructure Status"
date: 2026-03-15
layout: "single"
url: "/status/"
summary: "홈랩 인프라 일일 상태 보고서 — Falco, Trivy, SLO, CI/CD 통합"
showtoc: true
tocopen: false
---
# Daily Report: 2026-03-15
> 기간: 2026-03-12 ~ 2026-03-15 (3일간)
> 생성: 2026-03-15 07:00:20

---

## 🔴 Critical Summary


- 🔴 비정상 Pod 존재
- 🟡 SLO 30d 89.2% (목표 99%)

---

## 🛡️ 보안 이벤트 (Falco)

| Priority | 건수 |
|----------|------|
| CRITICAL | 0건 |
| WARNING  | 500건 |
| ERROR    | 0건 |

### Rule별 상세

#### Critical
- 없음

#### Warning (상위 10개)
- `Shell Spawned in Application Container`: 500건

> 📌 노이즈 주의: `Read sensitive file untrusted` 중 gdm/PAM 관련은 로그인 정상 동작

---

## 🖥️ 클러스터 상태

- **노드**: 5/5 정상
- **비정상 Pod**:
  - blog-system/was-f9858654d-sr4p4 (CrashLoopBackOff)
  - blog-system/was-f9858654d-ztv29 (CrashLoopBackOff)
- **재시작 많은 Pod (5회 이상)**:
  - blog-system/mysql-pxc-haproxy-0 재시작:154회
  - blog-system/mysql-pxc-haproxy-1 재시작:163회
  - blog-system/was-f9858654d-sr4p4 재시작:93회
  - blog-system/was-f9858654d-ztv29 재시작:93회
  - kube-system/cilium-operator-69f67c-pfrx9 재시작:6회
  - kube-system/kube-controller-manager-k8s-cp 재시작:119회
  - kube-system/kube-scheduler-k8s-cp 재시작:117회
  - longhorn-system/engine-image-ei-3154f3aa-6rdg2 재시작:9회
  - longhorn-system/engine-image-ei-3154f3aa-84t8n 재시작:10회
  - longhorn-system/engine-image-ei-3154f3aa-d6xh6 재시작:35회

---

## 📊 SLO

| 기간 | Availability | 목표 |
|------|-------------|------|
| 24h  | 50.0% | 99% |
| 30d  | 89.2% | 99% |

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
- ✅ `03-12 17:49` Deploy WAS to Kubernetes
- ✅ `03-12 06:20` Deploy WEB to Kubernetes
- ✅ `03-12 05:39` Deploy WEB to Kubernetes
- ✅ `03-12 02:00` Update Homepage Metrics
- ✅ `03-11 22:00` Deploy WEB to Kubernetes

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
