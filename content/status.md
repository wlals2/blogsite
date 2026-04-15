---
title: "Infrastructure Status"
date: 2026-04-16
layout: "single"
url: "/status/"
summary: "홈랩 인프라 일일 상태 보고서 — Falco, Trivy, SLO, CI/CD 통합"
showtoc: true
tocopen: false
---
# Daily Report: 2026-04-16
> 기간: 2026-04-13 ~ 2026-04-16 (3일간)
> 생성: 2026-04-16 07:00:07

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

- **노드**: 5/5 정상
- **비정상 Pod**:
  - calico-apiserver/calico-apiserver-d6d78c54b-95l99 (ContainerCreating)
  - calico-apiserver/calico-apiserver-d6d78c54b-hlf6m (ContainerCreating)
  - calico-system/calico-kube-controllers-c89744dfd-xv7x9 (ContainerCreating)
  - calico-system/csi-node-driver-7nzck (ContainerCreating)
- **재시작 많은 Pod (5회 이상)**:
  - calico-system/csi-node-driver-nfjnn 재시작:8회

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

- ✅ `04-15 18:03` Trivy Security Scan
- ✅ `04-15 02:26` Update Homepage Metrics
- ✅ `04-14 18:03` Trivy Security Scan
- ✅ `04-14 02:28` Update Homepage Metrics
- ✅ `04-13 17:58` Trivy Security Scan
- ✅ `04-13 02:34` Update Homepage Metrics

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
