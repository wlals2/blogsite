---
title: "Infrastructure Status"
date: 2026-03-05
layout: "single"
url: "/status/"
summary: "홈랩 인프라 일일 상태 보고서 — Falco, Trivy, SLO, CI/CD 통합"
showtoc: true
tocopen: false
---
# Daily Report: 2026-03-05
> 기간: 2026-03-02 ~ 2026-03-05 (3일간)
> 생성: 2026-03-05 16:18:06

---

## 🔴 Critical Summary


- 🔴 Falco CRITICAL 247건 발생
- 🟡 SLO 30d 89.2% (목표 99%)

---

## 🛡️ 보안 이벤트 (Falco)

| Priority | 건수 |
|----------|------|
| CRITICAL | 247건 |
| WARNING  | 74건 |
| ERROR    | 179건 |

### Rule별 상세

#### Critical
- `Drop and execute new binary in container`: 247건

#### Warning (상위 10개)
- `Read sensitive file untrusted`: 65건
- `Launch Package Management Process in Container`: 7건
- `Clear Log Activities`: 2건

> 📌 노이즈 주의: `Read sensitive file untrusted` 중 gdm/PAM 관련은 로그인 정상 동작

---

## 🖥️ 클러스터 상태

- **노드**: 5/5 정상
- **비정상 Pod**:
  - 없음
- **재시작 많은 Pod (5회 이상)**:
  - argo-rollouts/argo-rollouts-95dd6b7f7-9km4r 재시작:36회
  - kube-system/cilium-66lz2 재시작:5회
  - kube-system/cilium-envoy-9fcqn 재시작:12회
  - kube-system/cilium-envoy-gm8rs 재시작:5회
  - kube-system/cilium-envoy-qv4vs 재시작:50회
  - kube-system/cilium-envoy-w9p6v 재시작:10회
  - kube-system/cilium-f2mxf 재시작:5회
  - kube-system/cilium-operator-5876d57d59-dkrc2 재시작:19회
  - kube-system/cilium-operator-5876d57d59-mkfc9 재시작:193회
  - kube-system/kube-controller-manager-k8s-cp 재시작:94회

---

## 📊 SLO

| 기간 | Availability | 목표 |
|------|-------------|------|
| 24h  | 86.5% | 99% |
| 30d  | 89.2% | 99% |

---

## 🔍 이미지 보안 스캔 (Trivy)

> 스캔 범위: CRITICAL + HIGH / 최근 nightly 결과

⚠️ **수집 실패**: log fetch failed

---

## 🚀 CI/CD 이력 (최근 3일)

- ✅ `03-05 05:45` Deploy WAS to Kubernetes
- ❌ `03-05 05:45` Deploy WAS to Kubernetes
- ✅ `03-05 05:01` Deploy WEB to Kubernetes
- ✅ `03-05 05:01` Deploy WAS to Kubernetes
- ✅ `03-05 04:40` Deploy WAS to Kubernetes
- ✅ `03-05 04:40` Deploy WEB to Kubernetes
- ✅ `03-05 04:40` Deploy WAS to Kubernetes
- ✅ `03-05 01:59` Update Homepage Metrics
- ✅ `03-05 01:16` Deploy WAS to Kubernetes
- ✅ `03-04 17:40` Deploy WAS to Kubernetes
- ✅ `03-04 14:54` Deploy WAS to Kubernetes
- ✅ `03-04 14:54` Deploy WEB to Kubernetes
- ✅ `03-04 14:23` Deploy WEB to Kubernetes
- ✅ `03-04 14:23` Deploy WAS to Kubernetes
- ✅ `03-04 12:58` Deploy WAS to Kubernetes
- ✅ `03-04 12:58` Deploy WEB to Kubernetes
- ✅ `03-04 11:22` Deploy WAS to Kubernetes
- ✅ `03-04 11:22` Deploy WEB to Kubernetes
- ✅ `03-04 10:36` Deploy WAS to Kubernetes
- ✅ `03-04 10:36` Deploy WEB to Kubernetes

---

## 📋 TODO

### 🚨 P0 (즉시)
- [ ] 30일 SLO Availability 77.6% 원인 분석

### ⚠️ P1 (단기)
- [ ] MySQL HA 구성
- [ ] Control Plane HA
- [ ] etcd Secret 암호화 (CIS 1.2.27, 1.2.28)

> 전체 목록: /home/jimin/docs/00-TODO.md

---

## 🔗 빠른 링크

| 도구 | URL |
|------|-----|
| Grafana | http://grafana.jiminhome.shop |
| Prometheus | http://prom.jiminhome.shop |
| Falcosidekick UI | http://falco-ui.jiminhome.shop |
| ArgoCD | http://argocd.jiminhome.shop |
