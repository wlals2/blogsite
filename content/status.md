---
title: "Infrastructure Status"
date: 2026-03-08
layout: "single"
url: "/status/"
summary: "홈랩 인프라 일일 상태 보고서 — Falco, Trivy, SLO, CI/CD 통합"
showtoc: true
tocopen: false
---
# Daily Report: 2026-03-08
> 기간: 2026-03-05 ~ 2026-03-08 (3일간)
> 생성: 2026-03-08 07:03:10

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
  - blog-system/was-5bdb86c799-xkvn7 (Terminating)
  - blog-system/web-7d4f585f76-7xrhm (Terminating)
  - falco/falco-falcosidekick-bbb8468f8-sgtjb (Terminating)
  - falco/falco-falcosidekick-ui-redis-0 (ContainerCreating)
  - istio-system/istio-ingressgateway-5dbff5cbd5-cdsm5 (Terminating)
  - local-path-storage/local-path-provisioner-5b7668cc5b-xphbc (Terminating)
  - longhorn-system/csi-attacher-74657df76b-bcffl (Terminating)
  - longhorn-system/csi-attacher-74657df76b-dwrdx (Terminating)
  - longhorn-system/csi-attacher-74657df76b-lwrl5 (Terminating)
  - longhorn-system/csi-provisioner-55c9cb4745-6phdz (Terminating)
  - longhorn-system/csi-provisioner-55c9cb4745-cdsnr (Terminating)
  - longhorn-system/csi-provisioner-55c9cb4745-k8ntd (Terminating)
  - longhorn-system/csi-resizer-ffb7b57c9-2rhvp (Terminating)
  - longhorn-system/csi-resizer-ffb7b57c9-pswtl (Terminating)
  - longhorn-system/csi-resizer-ffb7b57c9-qgvbf (Terminating)
  - longhorn-system/csi-snapshotter-d4584fdbf-92g9x (Terminating)
  - longhorn-system/csi-snapshotter-d4584fdbf-m6dls (Terminating)
  - longhorn-system/csi-snapshotter-d4584fdbf-wr2w5 (Terminating)
  - longhorn-system/csi-snapshotter-d4584fdbf-zcfnw (ContainerCreating)
  - longhorn-system/instance-manager-17b29fc55ac09dcabad52e8864829f9b (Terminating)
  - longhorn-system/longhorn-driver-deployer-5c69c6c4d6-lkl7r (Terminating)
  - longhorn-system/longhorn-ui-84d97876df-b6tfg (Terminating)
  - longhorn-system/longhorn-ui-84d97876df-vlfvp (Terminating)
  - longhorn-system/longhorn-ui-84d97876df-zpxbc (ContainerCreating)
  - monitoring/prometheus-kube-prometheus-stack-prometheus-0 (Terminating)
- **재시작 많은 Pod (5회 이상)**:
  - argo-rollouts/argo-rollouts-95dd6b7f7-9km4r 재시작:62회
  - argocd/argocd-repo-server-856df98bfd-v4scn 재시작:10회
  - cert-manager/cert-manager-85f97d9b4c-v6pvj 재시작:7회
  - kube-system/kube-controller-manager-k8s-cp 재시작:109회
  - kube-system/kube-scheduler-k8s-cp 재시작:107회
  - kubernetes-dashboard/kubernetes-dashboard-94d885f76-65g5b 재시작:5회
  - kyverno/kyverno-admission-controller-fdb6dfcfb-c6lgd 재시작:53회
  - kyverno/kyverno-background-controller-6674dc69f5-rsxmd 재시작:51회
  - kyverno/kyverno-cleanup-controller-5bb56f66f4-8c7h8 재시작:53회
  - kyverno/kyverno-reports-controller-647dd56678-8dfqh 재시작:52회

---

## 📊 SLO

| 기간 | Availability | 목표 |
|------|-------------|------|
| 24h  | N/A% | 99% |
| 30d  | N/A% | 99% |

---

## 🔍 이미지 보안 스캔 (Trivy)

> 스캔 범위: CRITICAL + HIGH / 최근 nightly 결과

✅ **이상 없음** — CRITICAL/HIGH 취약점 없음

---

## 🚀 CI/CD 이력 (최근 3일)

- ✅ `03-07 17:22` Deploy WAS to Kubernetes
- ✅ `03-07 14:26` Deploy WEB to Kubernetes
- ✅ `03-07 07:01` Deploy WEB to Kubernetes
- ✅ `03-07 01:52` Update Homepage Metrics
- ✅ `03-06 17:36` Deploy WAS to Kubernetes
- ✅ `03-06 10:21` Deploy WEB to Kubernetes
- ✅ `03-06 09:27` Deploy WEB to Kubernetes
- ✅ `03-06 09:22` Deploy WAS to Kubernetes
- ✅ `03-06 02:00` Update Homepage Metrics
- ✅ `03-05 18:47` Deploy WAS to Kubernetes
- ✅ `03-05 12:23` Deploy WEB to Kubernetes
- ✅ `03-05 10:53` Deploy WAS to Kubernetes
- ✅ `03-05 10:53` Deploy WEB to Kubernetes
- ❌ `03-05 10:44` Deploy WEB to Kubernetes
- ✅ `03-05 10:44` Deploy WAS to Kubernetes
- ✅ `03-05 10:38` Deploy WEB to Kubernetes
- ✅ `03-05 08:06` Deploy WEB to Kubernetes
- ❌ `03-05 07:21` Deploy WEB to Kubernetes
- ✅ `03-05 07:19` Deploy WEB to Kubernetes
- ✅ `03-05 05:45` Deploy WAS to Kubernetes

---

## 📋 TODO

### 🚨 P0 (즉시)
- 없음

### ⚠️ P1 (단기)
- [ ] MySQL HA 구성
- [ ] Control Plane HA
- [ ] RBAC 정리

> 전체 목록: /home/jimin/docs/00-TODO.md

---

## 🔗 빠른 링크

| 도구 | URL |
|------|-----|
| Grafana | http://grafana.jiminhome.shop |
| Prometheus | http://prom.jiminhome.shop |
| Falcosidekick UI | http://falco-ui.jiminhome.shop |
| ArgoCD | http://argocd.jiminhome.shop |
