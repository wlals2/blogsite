---
title: "Infrastructure Status"
date: 2026-04-27
layout: "single"
url: "/status/"
summary: "홈랩 인프라 일일 상태 보고서 — Falco, Trivy, SLO, CI/CD 통합"
showtoc: true
tocopen: false
---
# Daily Report: 2026-04-27
> 기간: 2026-04-24 ~ 2026-04-27 (3일간)
> 생성: 2026-04-27 07:00:07

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
  - calico-system/calico-node-4pp94 재시작:5회
  - calico-system/calico-node-5wmmb 재시작:5회
  - calico-system/calico-node-lnqsx 재시작:9회
  - calico-system/calico-node-x8lbh 재시작:5회
  - calico-system/calico-typha-ddf4dbbdd-7zxhc 재시작:9회
  - calico-system/calico-typha-ddf4dbbdd-lv6cc 재시작:5회
  - calico-system/calico-typha-ddf4dbbdd-pgcdk 재시작:5회
  - calico-system/csi-node-driver-5bjnz 재시작:10회
  - calico-system/csi-node-driver-cd9sm 재시작:10회
  - calico-system/csi-node-driver-nfjnn 재시작:18회

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

- ✅ `04-26 17:36` Trivy Security Scan
- ✅ `04-26 02:37` Update Homepage Metrics
- ✅ `04-25 17:35` Trivy Security Scan
- ✅ `04-25 02:22` Update Homepage Metrics
- ✅ `04-24 17:48` Trivy Security Scan
- ✅ `04-24 02:32` Update Homepage Metrics

---

## 📋 TODO

### 🚨 P0 (즉시)
- [ ] - [ ] worker 4대 NotReady 복구 (VMware VM 전원 확인 + kubelet 재시작)
- [ ] - [ ] hugo-blog Terminating Pod 강제 삭제 (`--force --grace-period=0`)
- [ ] - [ ] core.md 실제 클러스터 상태 동기화

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
