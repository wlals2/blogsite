---
title: "Infrastructure Status"
date: 2026-03-17
layout: "single"
url: "/status/"
summary: "홈랩 인프라 일일 상태 보고서 — Falco, Trivy, SLO, CI/CD 통합"
showtoc: true
tocopen: false
---
# Daily Report: 2026-03-17
> 기간: 2026-03-14 ~ 2026-03-17 (3일간)
> 생성: 2026-03-17 07:03:11

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
  - argocd/argocd-application-controller-0 (Terminating)
  - argocd/argocd-applicationset-controller-6f7d847ddc-hx95t (Pending)
  - argocd/argocd-applicationset-controller-6f7d847ddc-vvwhr (Terminating)
  - argocd/argocd-repo-server-856df98bfd-llm5n (Terminating)
  - argocd/argocd-repo-server-856df98bfd-mc2mr (Pending)
  - blog-system/mysql-pxc-haproxy-0 (Terminating)
  - blog-system/mysql-pxc-pxc-0 (Terminating)
  - blog-system/was-f9858654d-vnxsf (Terminating)
  - blog-system/web-7dd4476b5c-ng9ff (Terminating)
  - cert-manager/cert-manager-cainjector-f4d9bd564-mrb49 (Pending)
  - cert-manager/cert-manager-cainjector-f4d9bd564-sltsg (Terminating)
  - cert-manager/cert-manager-webhook-644f5f74fb-d27t5 (Pending)
  - cert-manager/cert-manager-webhook-644f5f74fb-jpsbd (Terminating)
  - falco/falco-falcosidekick-bbb8468f8-khj4w (Pending)
  - falco/falco-falcosidekick-bbb8468f8-m5r2b (Terminating)
  - falco/falco-falcosidekick-ui-redis-0 (Terminating)
  - falco/falco-talon-656bb9d8f5-pwcmg (Terminating)
  - falco/falco-talon-656bb9d8f5-r78wc (Pending)
  - istio-system/istio-ingressgateway-5dbff5cbd5-szl49 (Pending)
  - istio-system/istio-ingressgateway-5dbff5cbd5-twq2f (Terminating)
  - kyverno/kyverno-background-controller-6674dc69f5-5mmph (Pending)
  - kyverno/kyverno-background-controller-6674dc69f5-drpvv (Terminating)
  - local-path-storage/local-path-provisioner-5b7668cc5b-7j44c (Terminating)
  - local-path-storage/local-path-provisioner-5b7668cc5b-8f6ms (Pending)
  - longhorn-system/csi-attacher-74657df76b-gp64z (Pending)
  - longhorn-system/csi-attacher-74657df76b-stz6j (Terminating)
  - longhorn-system/csi-provisioner-55c9cb4745-4bd26 (Terminating)
  - longhorn-system/csi-provisioner-55c9cb4745-7dsp7 (Pending)
  - longhorn-system/csi-resizer-ffb7b57c9-46nfn (Pending)
  - longhorn-system/csi-resizer-ffb7b57c9-sjqgh (Terminating)
  - longhorn-system/csi-snapshotter-d4584fdbf-4mfsq (Pending)
  - longhorn-system/csi-snapshotter-d4584fdbf-r7h7v (Terminating)
  - longhorn-system/instance-manager-4336265ce20c5c2419edac9e081210a6 (Terminating)
  - longhorn-system/longhorn-driver-deployer-5c69c6c4d6-h5dkx (Pending)
  - longhorn-system/longhorn-driver-deployer-5c69c6c4d6-tc95l (Terminating)
  - longhorn-system/longhorn-ui-84d97876df-24g9m (Pending)
  - longhorn-system/longhorn-ui-84d97876df-dfbtq (Terminating)
  - monitoring/alertmanager-kube-prometheus-stack-alertmanager-0 (Terminating)
  - monitoring/blackbox-exporter-746fd95696-rzmgx (Terminating)
  - monitoring/blackbox-exporter-746fd95696-xs6z9 (Pending)
  - monitoring/kube-prometheus-stack-grafana-764b566c99-vnd7j (Pending)
  - monitoring/kube-prometheus-stack-grafana-764b566c99-wsr8b (Terminating)
  - monitoring/kube-prometheus-stack-grafana-image-renderer-bf584649d-7rbth (Terminating)
  - monitoring/kube-prometheus-stack-grafana-image-renderer-bf584649d-q84fv (Pending)
  - monitoring/kube-prometheus-stack-kube-state-metrics-54c6589d9c-msdf8 (Terminating)
  - monitoring/kube-prometheus-stack-kube-state-metrics-54c6589d9c-skd7g (Pending)
  - monitoring/kube-state-metrics-5c4979bbf8-qrxqb (Terminating)
  - monitoring/kube-state-metrics-5c4979bbf8-rpqpr (Pending)
  - monitoring/loki-stack-0 (Terminating)
  - monitoring/tempo-5977d45fd8-f4875 (Pending)
  - monitoring/tempo-5977d45fd8-ltp6k (Terminating)
- **재시작 많은 Pod (5회 이상)**:
  - blog-system/was-f9858654d-8dd6p 재시작:10회
  - blog-system/was-f9858654d-vnxsf 재시작:12회
  - falco/falco-7qdpd 재시작:6회
  - falco/falco-zrjxg 재시작:6회
  - kube-system/cilium-8zcqf 재시작:5회
  - kube-system/cilium-envoy-n2w2s 재시작:6회
  - kube-system/cilium-envoy-xc7q9 재시작:5회
  - kube-system/cilium-operator-69f67c-pfrx9 재시작:6회
  - kube-system/cilium-rvqbd 재시작:6회
  - kube-system/kube-controller-manager-k8s-cp 재시작:119회

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

- ✅ `03-16 18:00` Deploy WAS to Kubernetes
- ✅ `03-16 02:22` Update Homepage Metrics
- ✅ `03-15 17:29` Deploy WAS to Kubernetes
- ✅ `03-15 02:12` Update Homepage Metrics
- ✅ `03-14 22:00` Deploy WEB to Kubernetes
- ✅ `03-14 17:28` Deploy WAS to Kubernetes
- ✅ `03-14 09:54` Deploy WEB to Kubernetes
- ✅ `03-14 03:26` Deploy WEB to Kubernetes
- ✅ `03-14 01:56` Update Homepage Metrics
- ✅ `03-13 22:04` Deploy WEB to Kubernetes

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
