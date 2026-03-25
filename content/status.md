---
title: "Infrastructure Status"
date: 2026-03-26
layout: "single"
url: "/status/"
summary: "홈랩 인프라 일일 상태 보고서 — Falco, Trivy, SLO, CI/CD 통합"
showtoc: true
tocopen: false
---
# Daily Report: 2026-03-26
> 기간: 2026-03-23 ~ 2026-03-26 (3일간)
> 생성: 2026-03-26 07:02:08

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
  - monitoring/loki-report-query (Terminating)
- **재시작 많은 Pod (5회 이상)**:
  - argocd/argocd-application-controller-0 재시작:8회
  - argocd/argocd-applicationset-controller-6f7d847ddc-h7fr6 재시작:7회
  - argocd/argocd-dex-server-676c5dd554-t8ldq 재시작:7회
  - argocd/argocd-notifications-controller-b5bc6998f-5smst 재시작:8회
  - argocd/argocd-redis-6574878d7b-l22gs 재시작:8회
  - argocd/argocd-repo-server-856df98bfd-zdsjw 재시작:10회
  - argocd/argocd-server-6557d867f-8nmbp 재시작:8회
  - blog-system/mysql-pxc-haproxy-0 재시작:36회
  - blog-system/mysql-pxc-haproxy-1 재시작:37회
  - cert-manager/cert-manager-85f97d9b4c-prk5l 재시작:60회

---

## 📊 SLO

| 기간 | Availability | 목표 |
|------|-------------|------|
| 24h  | N/A% | 99% |
| 30d  | N/A% | 99% |

---

## 🔍 이미지 보안 스캔 (Trivy)

> 스캔 범위: CRITICAL + HIGH / 최근 nightly 결과

⚠️ **수집 실패**: no WAS run

---

## 🚀 CI/CD 이력 (최근 3일)

- ✅ `03-25 17:56` Trivy Security Scan
- ✅ `03-25 02:05` Update Homepage Metrics
- ✅ `03-24 17:54` Trivy Security Scan
- ✅ `03-24 01:59` Update Homepage Metrics
- ✅ `03-23 17:50` Trivy Security Scan
- ✅ `03-23 02:07` Update Homepage Metrics
- ✅ `03-22 22:03` Deploy WEB to Kubernetes

---

## 📋 TODO

### 🚨 P0 (즉시)
- 없음

### ⚠️ P1 (단기)
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
