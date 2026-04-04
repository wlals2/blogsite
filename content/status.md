---
title: "Infrastructure Status"
date: 2026-04-05
layout: "single"
url: "/status/"
summary: "홈랩 인프라 일일 상태 보고서 — Falco, Trivy, SLO, CI/CD 통합"
showtoc: true
tocopen: false
---
# Daily Report: 2026-04-05
> 기간: 2026-04-02 ~ 2026-04-05 (3일간)
> 생성: 2026-04-05 07:02:07

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
  - argocd/argocd-dex-server-676c5dd554-r9tpr (PodInitializing)
  - falco/falco-falcosidekick-ui-5d46747685-2b4z8 (Init:0/1)
  - longhorn-system/csi-attacher-6c7f6bb4c-qg8mq (Error)
  - longhorn-system/csi-provisioner-6b6c48558b-kk78d (Error)
  - longhorn-system/csi-snapshotter-6ddbd9fbbf-n22l8 (Error)
  - monitoring/kube-prometheus-stack-grafana-764b566c99-5zj84 (ContainerCreating)
  - monitoring/loki-report-query (Terminating)
  - monitoring/tempo-5977d45fd8-z9brl (ContainerCreating)
  - trivy-system/scan-vulnerabilityreport-5d6bc7d67d-vbnwp (Error)
- **재시작 많은 Pod (5회 이상)**:
  - argo-rollouts/argo-rollouts-95dd6b7f7-2q5vf 재시작:43회
  - argocd/argocd-application-controller-0 재시작:19회
  - argocd/argocd-notifications-controller-b5bc6998f-5smst 재시작:19회
  - argocd/argocd-redis-6574878d7b-l22gs 재시작:19회
  - argocd/argocd-repo-server-856df98bfd-zdsjw 재시작:51회
  - argocd/argocd-server-6557d867f-8nmbp 재시작:27회
  - blog-system/mysql-pxc-haproxy-0 재시작:34회
  - blog-system/mysql-pxc-haproxy-1 재시작:42회
  - cert-manager/cert-manager-85f97d9b4c-prk5l 재시작:72회
  - cert-manager/cert-manager-cainjector-f4d9bd564-7ndgd 재시작:19회

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

- ✅ `04-04 17:27` Trivy Security Scan
- ✅ `04-04 02:03` Update Homepage Metrics
- ✅ `04-03 17:32` Trivy Security Scan
- ✅ `04-03 02:10` Update Homepage Metrics
- ✅ `04-02 17:48` Trivy Security Scan
- ✅ `04-02 13:13` Deploy WEB to Kubernetes
- ✅ `04-02 02:09` Update Homepage Metrics

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
