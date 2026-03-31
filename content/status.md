---
title: "Infrastructure Status"
date: 2026-04-01
layout: "single"
url: "/status/"
summary: "홈랩 인프라 일일 상태 보고서 — Falco, Trivy, SLO, CI/CD 통합"
showtoc: true
tocopen: false
---
# Daily Report: 2026-04-01
> 기간: 2026-03-29 ~ 2026-04-01 (3일간)
> 생성: 2026-04-01 07:02:08

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
  - argo-rollouts/argo-rollouts-95dd6b7f7-2q5vf (CrashLoopBackOff)
  - kyverno/kyverno-admission-controller-fdb6dfcfb-8drq2 (Terminating)
  - kyverno/kyverno-cleanup-controller-5bb56f66f4-rt84p (Terminating)
  - kyverno/kyverno-reports-controller-647dd56678-m4hj9 (Terminating)
  - monitoring/loki-report-query (Terminating)
  - pxc-operator/pxc-operator-5b7fbb5bfc-mmmkq (CrashLoopBackOff)
- **재시작 많은 Pod (5회 이상)**:
  - argo-rollouts/argo-rollouts-95dd6b7f7-2q5vf 재시작:26회
  - argocd/argocd-application-controller-0 재시작:17회
  - argocd/argocd-applicationset-controller-6f7d847ddc-h7fr6 재시작:16회
  - argocd/argocd-dex-server-676c5dd554-t8ldq 재시작:16회
  - argocd/argocd-notifications-controller-b5bc6998f-5smst 재시작:17회
  - argocd/argocd-redis-6574878d7b-l22gs 재시작:17회
  - argocd/argocd-repo-server-856df98bfd-zdsjw 재시작:49회
  - argocd/argocd-server-6557d867f-8nmbp 재시작:25회
  - blog-system/mysql-pxc-haproxy-0 재시작:16회
  - blog-system/mysql-pxc-haproxy-1 재시작:20회

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

- ✅ `03-31 17:54` Trivy Security Scan
- ✅ `03-31 02:12` Update Homepage Metrics
- ✅ `03-30 17:53` Trivy Security Scan
- ✅ `03-30 02:25` Update Homepage Metrics
- ✅ `03-29 22:55` Deploy WEB to Kubernetes
- ✅ `03-29 22:03` Deploy WEB to Kubernetes
- ✅ `03-29 17:27` Trivy Security Scan
- ✅ `03-29 02:23` Update Homepage Metrics

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
