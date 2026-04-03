---
title: "Infrastructure Status"
date: 2026-04-04
layout: "single"
url: "/status/"
summary: "홈랩 인프라 일일 상태 보고서 — Falco, Trivy, SLO, CI/CD 통합"
showtoc: true
tocopen: false
---
# Daily Report: 2026-04-04
> 기간: 2026-04-01 ~ 2026-04-04 (3일간)
> 생성: 2026-04-04 07:00:07

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
  - argocd/argocd-applicationset-controller-6f7d847ddc-h7fr6 (Terminating)
  - argocd/argocd-applicationset-controller-6f7d847ddc-wcgc4 (Pending)
  - argocd/argocd-dex-server-676c5dd554-r9tpr (Pending)
  - argocd/argocd-dex-server-676c5dd554-t8ldq (Terminating)
  - blog-system/mysql-pxc-haproxy-0 (CrashLoopBackOff)
  - blog-system/mysql-pxc-pxc-0 (Terminating)
  - blog-system/was-8dbc9d7bf-rtfph (Terminating)
  - blog-system/web-5d88cd87b8-4fb8q (Terminating)
  - falco/falco-falcosidekick-ui-5d46747685-2b4z8 (Pending)
  - falco/falco-falcosidekick-ui-5d46747685-zn8rx (Terminating)
  - falco/falco-falcosidekick-ui-redis-0 (Terminating)
  - istio-system/kiali-55994c949-txn8p (Terminating)
  - istio-system/kiali-55994c949-z422q (Pending)
  - kyverno/kyverno-admission-controller-5887b6c78f-2xrzg (CrashLoopBackOff)
  - kyverno/kyverno-admission-controller-fdb6dfcfb-8drq2 (Terminating)
  - kyverno/kyverno-background-controller-ff7f4c59b-dffsb (CrashLoopBackOff)
  - kyverno/kyverno-cleanup-controller-5877bf5d47-glhn6 (CrashLoopBackOff)
  - kyverno/kyverno-cleanup-controller-5bb56f66f4-rt84p (Terminating)
  - kyverno/kyverno-reports-controller-647dd56678-m4hj9 (Terminating)
  - kyverno/kyverno-reports-controller-86cc858b4f-5vlbr (CrashLoopBackOff)
  - longhorn-system/csi-provisioner-6b6c48558b-72pzq (Terminating)
  - longhorn-system/csi-provisioner-6b6c48558b-7pljc (Pending)
  - longhorn-system/csi-resizer-84756bdffd-gh2nj (Pending)
  - longhorn-system/csi-resizer-84756bdffd-wh7lf (Terminating)
  - longhorn-system/instance-manager-10b0884f01c2bc3dee349453211a4a50 (Terminating)
  - longhorn-system/longhorn-driver-deployer-65f694cdc7-ldqxp (CrashLoopBackOff)
  - longhorn-system/longhorn-ui-6d844b6fbb-48kcx (Terminating)
  - longhorn-system/longhorn-ui-6d844b6fbb-db2rc (Pending)
  - monitoring/alertmanager-kube-prometheus-stack-alertmanager-0 (Terminating)
  - monitoring/blackbox-exporter-746fd95696-6sw4n (Terminating)
  - monitoring/blackbox-exporter-746fd95696-bg84x (Pending)
  - monitoring/grafana-render-query (Terminating)
  - monitoring/kube-prometheus-stack-grafana-764b566c99-55jlq (Terminating)
  - monitoring/kube-prometheus-stack-grafana-764b566c99-5zj84 (Pending)
  - monitoring/loki-report-query (Terminating)
  - monitoring/loki-stack-0 (Terminating)
  - monitoring/prom-report-query (Terminating)
  - monitoring/prometheus-kube-prometheus-stack-prometheus-0 (Terminating)
  - pxc-operator/pxc-operator-5b7fbb5bfc-mmmkq (Terminating)
  - pxc-operator/pxc-operator-5b7fbb5bfc-nvjwn (Pending)
- **재시작 많은 Pod (5회 이상)**:
  - argo-rollouts/argo-rollouts-95dd6b7f7-2q5vf 재시작:39회
  - argocd/argocd-application-controller-0 재시작:18회
  - argocd/argocd-applicationset-controller-6f7d847ddc-h7fr6 재시작:16회
  - argocd/argocd-dex-server-676c5dd554-t8ldq 재시작:16회
  - argocd/argocd-notifications-controller-b5bc6998f-5smst 재시작:18회
  - argocd/argocd-redis-6574878d7b-l22gs 재시작:18회
  - argocd/argocd-repo-server-856df98bfd-zdsjw 재시작:50회
  - argocd/argocd-server-6557d867f-8nmbp 재시작:26회
  - blog-system/mysql-pxc-haproxy-0 재시작:31회
  - blog-system/mysql-pxc-haproxy-1 재시작:25회

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

- ✅ `04-03 17:32` Trivy Security Scan
- ✅ `04-03 02:10` Update Homepage Metrics
- ✅ `04-02 17:48` Trivy Security Scan
- ✅ `04-02 13:13` Deploy WEB to Kubernetes
- ✅ `04-02 02:09` Update Homepage Metrics
- ✅ `04-01 17:48` Trivy Security Scan
- ✅ `04-01 02:30` Update Homepage Metrics

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
