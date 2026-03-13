---
title: "Infrastructure Status"
date: 2026-03-14
layout: "single"
url: "/status/"
summary: "홈랩 인프라 일일 상태 보고서 — Falco, Trivy, SLO, CI/CD 통합"
showtoc: true
tocopen: false
---
# Daily Report: 2026-03-14
> 기간: 2026-03-11 ~ 2026-03-14 (3일간)
> 생성: 2026-03-14 07:03:10

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
  - argo-rollouts/argo-rollouts-95dd6b7f7-9km4r (Terminating)
  - argo-rollouts/argo-rollouts-95dd6b7f7-qcd2z (Pending)
  - argocd/argocd-applicationset-controller-6f7d847ddc-c4k9j (Pending)
  - argocd/argocd-applicationset-controller-6f7d847ddc-d54s2 (Terminating)
  - argocd/argocd-dex-server-676c5dd554-bgjxf (Terminating)
  - argocd/argocd-dex-server-676c5dd554-qfrs6 (Pending)
  - argocd/argocd-notifications-controller-b5bc6998f-9jkl4 (Terminating)
  - argocd/argocd-notifications-controller-b5bc6998f-x5szh (Pending)
  - argocd/argocd-redis-6574878d7b-4zf6m (Pending)
  - argocd/argocd-redis-6574878d7b-nvmpb (Terminating)
  - argocd/argocd-repo-server-856df98bfd-qgqfl (Pending)
  - argocd/argocd-repo-server-856df98bfd-v4scn (Terminating)
  - argocd/argocd-server-6557d867f-cvgn2 (Terminating)
  - argocd/argocd-server-6557d867f-j4hzs (Pending)
  - backup-system/mysql-backup-to-s3-29550390-j4xjd (ImagePullBackOff)
  - cert-manager/cert-manager-85f97d9b4c-99pgv (Pending)
  - cert-manager/cert-manager-85f97d9b4c-v6pvj (Terminating)
  - cert-manager/cert-manager-cainjector-f4d9bd564-chtjr (Pending)
  - cert-manager/cert-manager-cainjector-f4d9bd564-v9tnq (Terminating)
  - cert-manager/cert-manager-webhook-644f5f74fb-4gd7h (Pending)
  - cert-manager/cert-manager-webhook-644f5f74fb-vddls (Terminating)
  - falco/falco-falcosidekick-ui-5d46747685-qhssv (Pending)
  - falco/falco-falcosidekick-ui-5d46747685-t52hd (Terminating)
  - istio-system/istio-egressgateway-bccf77d7f-vvt99 (Terminating)
  - istio-system/istio-egressgateway-bccf77d7f-w49hh (Pending)
  - istio-system/istio-ingressgateway-5dbff5cbd5-fv445 (Terminating)
  - istio-system/istio-ingressgateway-5dbff5cbd5-nvlkv (Pending)
  - istio-system/istiod-54647dcd54-flpml (Pending)
  - istio-system/istiod-54647dcd54-rd24f (Terminating)
  - istio-system/jaeger-d6bb55c9f-jn9s8 (Terminating)
  - istio-system/jaeger-d6bb55c9f-mm7db (Pending)
  - istio-system/kiali-55994c949-fxwcg (Pending)
  - istio-system/kiali-55994c949-rt97v (Terminating)
  - istio-system/prometheus-5fb677579f-pjxcn (Terminating)
  - istio-system/prometheus-5fb677579f-pvgw5 (Pending)
  - kubernetes-dashboard/dashboard-metrics-scraper-59748c6dc6-7w26t (Terminating)
  - kubernetes-dashboard/dashboard-metrics-scraper-59748c6dc6-h6cgw (Pending)
  - kubernetes-dashboard/kubernetes-dashboard-94d885f76-65g5b (Terminating)
  - kubernetes-dashboard/kubernetes-dashboard-94d885f76-tb8gh (Pending)
  - kyverno/kyverno-admission-controller-fdb6dfcfb-5cfxp (Pending)
  - kyverno/kyverno-admission-controller-fdb6dfcfb-c6lgd (Terminating)
  - kyverno/kyverno-background-controller-6674dc69f5-rc67c (Pending)
  - kyverno/kyverno-background-controller-6674dc69f5-rsxmd (Terminating)
  - kyverno/kyverno-cleanup-controller-5bb56f66f4-8c7h8 (Terminating)
  - kyverno/kyverno-cleanup-controller-5bb56f66f4-th2b2 (Pending)
  - kyverno/kyverno-reports-controller-647dd56678-8dfqh (Terminating)
  - kyverno/kyverno-reports-controller-647dd56678-sl6x5 (Pending)
  - longhorn-system/csi-attacher-74657df76b-6db92 (Terminating)
  - longhorn-system/csi-attacher-74657df76b-g926j (Pending)
  - longhorn-system/csi-provisioner-55c9cb4745-dqz8d (Terminating)
  - longhorn-system/csi-provisioner-55c9cb4745-vlfpv (Pending)
  - longhorn-system/csi-resizer-ffb7b57c9-h7w5w (Pending)
  - longhorn-system/csi-resizer-ffb7b57c9-xcc5l (Terminating)
  - longhorn-system/csi-snapshotter-d4584fdbf-mjrnq (Pending)
  - longhorn-system/csi-snapshotter-d4584fdbf-rstnt (Terminating)
  - longhorn-system/instance-manager-0feebbbb5bf7f25ca4115b61d49815ac (Terminating)
  - metallb-system/controller-648459856d-pqd2f (Pending)
  - monitoring/alertmanager-86d494b869-9t986 (Pending)
  - monitoring/alertmanager-86d494b869-zzlvx (Terminating)
  - monitoring/alertmanager-kube-prometheus-stack-alertmanager-0 (Terminating)
  - monitoring/kube-prometheus-stack-kube-state-metrics-54c6589d9c-4plgw (Pending)
  - monitoring/kube-prometheus-stack-kube-state-metrics-54c6589d9c-ksw5k (Terminating)
  - monitoring/kube-prometheus-stack-operator-76c64d5fdc-ljcbr (Pending)
  - monitoring/kube-prometheus-stack-operator-76c64d5fdc-xztm4 (Terminating)
  - monitoring/kube-state-metrics-5c4979bbf8-5f66c (Terminating)
  - monitoring/kube-state-metrics-5c4979bbf8-rx272 (Pending)
  - monitoring/prometheus-adapter-76cf9d778b-fl2xn (Pending)
  - monitoring/prometheus-adapter-76cf9d778b-gcm9z (Terminating)
  - monitoring/prometheus-kube-prometheus-stack-prometheus-0 (Init:0/1)
  - monitoring/pushgateway-7bb569d7-d6nwn (Terminating)
  - monitoring/pushgateway-7bb569d7-gq7g2 (Pending)
- **재시작 많은 Pod (5회 이상)**:
  - argo-rollouts/argo-rollouts-95dd6b7f7-9km4r 재시작:81회
  - argocd/argocd-repo-server-856df98bfd-v4scn 재시작:15회
  - cert-manager/cert-manager-85f97d9b4c-v6pvj 재시작:9회
  - falco/falco-falcosidekick-bbb8468f8-wnblg 재시작:8회
  - falco/falco-falcosidekick-ui-5d46747685-t52hd 재시작:10회
  - istio-system/prometheus-5fb677579f-pjxcn 재시작:5회
  - kube-system/cilium-operator-69f67c-nqbqn 재시작:13회
  - kube-system/cilium-operator-69f67c-pfrx9 재시작:6회
  - kube-system/hubble-ui-6b65d5f8f5-jd52j 재시작:5회
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
- ✅ `03-11 18:17` Deploy WEB to Kubernetes
- ❌ `03-11 17:58` Deploy WEB to Kubernetes
- ✅ `03-11 17:52` Deploy WAS to Kubernetes
- ✅ `03-11 17:50` Deploy WAS to Kubernetes
- ✅ `03-11 01:54` Update Homepage Metrics
- ✅ `03-10 22:04` Deploy WEB to Kubernetes

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
