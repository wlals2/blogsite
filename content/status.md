---
title: "Infrastructure Status"
date: 2026-03-11
layout: "single"
url: "/status/"
summary: "홈랩 인프라 일일 상태 보고서 — Falco, Trivy, SLO, CI/CD 통합"
showtoc: true
tocopen: false
---
# Daily Report: 2026-03-11
> 기간: 2026-03-08 ~ 2026-03-11 (3일간)
> 생성: 2026-03-11 07:03:10

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
  - backup-system/etcd-backup-to-s3-29550360-m6cxd (ImagePullBackOff)
  - backup-system/mysql-backup-to-s3-29550390-9wpjt (ImagePullBackOff)
- **재시작 많은 Pod (5회 이상)**:
  - argo-rollouts/argo-rollouts-95dd6b7f7-9km4r 재시작:69회
  - argocd/argocd-repo-server-856df98bfd-v4scn 재시작:12회
  - cert-manager/cert-manager-85f97d9b4c-v6pvj 재시작:8회
  - falco/falco-falcosidekick-bbb8468f8-wnblg 재시작:7회
  - kube-system/cilium-operator-69f67c-nqbqn 재시작:7회
  - kube-system/kube-controller-manager-k8s-cp 재시작:112회
  - kube-system/kube-scheduler-k8s-cp 재시작:111회
  - kube-system/sealed-secrets-controller-58fc7b9bd6-hn8ss 재시작:7회
  - kube-system/vpa-recommender-7cc5d65847-crjx5 재시작:6회
  - kubernetes-dashboard/kubernetes-dashboard-94d885f76-65g5b 재시작:6회

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

- ✅ `03-10 17:47` Deploy WAS to Kubernetes
- ✅ `03-10 17:30` Deploy WEB to Kubernetes
- ✅ `03-10 01:54` Update Homepage Metrics
- ✅ `03-09 22:00` Deploy WEB to Kubernetes
- ✅ `03-09 17:47` Deploy WAS to Kubernetes
- ✅ `03-09 07:57` Deploy WEB to Kubernetes
- ✅ `03-09 07:46` Deploy WEB to Kubernetes
- ✅ `03-09 06:07` Deploy WEB to Kubernetes
- ✅ `03-09 05:51` Deploy WEB to Kubernetes
- ✅ `03-09 05:24` Deploy WEB to Kubernetes
- ✅ `03-09 05:24` Deploy WAS to Kubernetes
- ✅ `03-09 02:02` Update Homepage Metrics
- ✅ `03-08 18:20` Deploy WEB to Kubernetes
- ❌ `03-08 18:05` Deploy WEB to Kubernetes
- ❌ `03-08 17:57` Deploy WEB to Kubernetes
- ❌ `03-08 17:51` Deploy WEB to Kubernetes
- ✅ `03-08 17:24` Deploy WAS to Kubernetes
- ✅ `03-08 02:01` Update Homepage Metrics
- ✅ `03-07 22:04` Build Backup Images
- ✅ `03-07 22:04` Deploy WEB to Kubernetes

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
