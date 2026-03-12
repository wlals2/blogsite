---
title: "Infrastructure Status"
date: 2026-03-13
layout: "single"
url: "/status/"
summary: "홈랩 인프라 일일 상태 보고서 — Falco, Trivy, SLO, CI/CD 통합"
showtoc: true
tocopen: false
---
# Daily Report: 2026-03-13
> 기간: 2026-03-10 ~ 2026-03-13 (3일간)
> 생성: 2026-03-13 07:00:26

---

## 🔴 Critical Summary


- 🔴 Falco CRITICAL 20건 발생
- 🔴 비정상 Pod 존재

---

## 🛡️ 보안 이벤트 (Falco)

| Priority | 건수 |
|----------|------|
| CRITICAL | 20건 |
| WARNING  | 44건 |
| ERROR    | 2건 |

### Rule별 상세

#### Critical
- `Drop and execute new binary in container`: 20건

#### Warning (상위 10개)
- `Shell Spawned in Application Container`: 17건
- `Read sensitive file untrusted`: 13건
- `Clear Log Activities`: 8건
- `Launch Package Management Process in Container`: 6건

> 📌 노이즈 주의: `Read sensitive file untrusted` 중 gdm/PAM 관련은 로그인 정상 동작

---

## 🖥️ 클러스터 상태

- **노드**: 5/5 정상
- **비정상 Pod**:
  - backup-system/mysql-backup-to-s3-29550390-j4xjd (ErrImagePull)
  - monitoring/prometheus-kube-prometheus-stack-prometheus-0 (Init:0/1)
- **재시작 많은 Pod (5회 이상)**:
  - argo-rollouts/argo-rollouts-95dd6b7f7-9km4r 재시작:80회
  - argocd/argocd-repo-server-856df98bfd-v4scn 재시작:15회
  - blog-system/mysql-exporter-6c7bc4b867-9mhnn 재시작:5회
  - cert-manager/cert-manager-85f97d9b4c-v6pvj 재시작:9회
  - falco/falco-falcosidekick-bbb8468f8-wnblg 재시작:8회
  - falco/falco-falcosidekick-ui-5d46747685-t52hd 재시작:10회
  - istio-system/prometheus-5fb677579f-pjxcn 재시작:5회
  - kube-system/cilium-operator-69f67c-nqbqn 재시작:12회
  - kube-system/cilium-operator-69f67c-pfrx9 재시작:5회
  - kube-system/hubble-ui-6b65d5f8f5-jd52j 재시작:5회

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
- ✅ `03-10 17:47` Deploy WAS to Kubernetes
- ✅ `03-10 17:30` Deploy WEB to Kubernetes
- ✅ `03-10 01:54` Update Homepage Metrics
- ✅ `03-09 22:00` Deploy WEB to Kubernetes

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
