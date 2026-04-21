---
title: "Infrastructure Status"
date: 2026-04-22
layout: "single"
url: "/status/"
summary: "홈랩 인프라 일일 상태 보고서 — Falco, Trivy, SLO, CI/CD 통합"
showtoc: true
tocopen: false
---
# Daily Report: 2026-04-22
> 기간: 2026-04-19 ~ 2026-04-22 (3일간)
> 생성: 2026-04-22 07:00:06

---

## 🔴 Critical Summary

✅ 특이사항 없음

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
  - 없음
- **재시작 많은 Pod (5회 이상)**:
  - calico-system/calico-node-lnqsx 재시작:7회
  - calico-system/calico-typha-ddf4dbbdd-7zxhc 재시작:7회
  - calico-system/csi-node-driver-5bjnz 재시작:6회
  - calico-system/csi-node-driver-cd9sm 재시작:6회
  - calico-system/csi-node-driver-nfjnn 재시작:14회
  - calico-system/csi-node-driver-xwzhx 재시작:6회
  - kube-system/kube-controller-manager-k8s-cp 재시작:5회
  - kube-system/kube-proxy-zst92 재시작:7회
  - kube-system/kube-scheduler-k8s-cp 재시작:5회
  - tigera-operator/tigera-operator-5d49548847-hc9lf 재시작:5회

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

- ✅ `04-21 17:57` Trivy Security Scan
- ✅ `04-21 02:30` Update Homepage Metrics
- ✅ `04-20 17:55` Trivy Security Scan
- ✅ `04-20 02:34` Update Homepage Metrics
- ✅ `04-19 17:33` Trivy Security Scan
- ✅ `04-19 02:34` Update Homepage Metrics

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
