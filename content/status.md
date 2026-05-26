---
title: "Infrastructure Status"
date: 2026-05-27
layout: "single"
url: "/status/"
summary: "홈랩 인프라 일일 상태 보고서 — Falco, Trivy, SLO, CI/CD 통합"
showtoc: true
tocopen: false
---
# Daily Report: 2026-05-27
> 기간: 2026-05-24 ~ 2026-05-27 (3일간)
> 생성: 2026-05-27 07:00:06

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

- **노드**: 4/6 정상
- **비정상 Pod**:
  - ai-bug-hunting/postgres-0 (Terminating)
  - calico-apiserver/calico-apiserver-d6d78c54b-zm2gz (Terminating)
  - calico-system/calico-kube-controllers-c89744dfd-4dnrv (Terminating)
  - calico-system/calico-typha-ddf4dbbdd-7zxhc (Terminating)
  - local-path-storage/helper-pod-delete-pvc-54fd143c-6575-4092-a314-75b1b892c0a6 (Terminating)
- **재시작 많은 Pod (5회 이상)**:
  - calico-apiserver/calico-apiserver-d6d78c54b-zm2gz 재시작:75회
  - calico-system/calico-kube-controllers-c89744dfd-4dnrv 재시작:80회
  - calico-system/calico-node-4pp94 재시작:12회
  - calico-system/calico-node-5wmmb 재시작:15회
  - calico-system/calico-node-lnqsx 재시작:14회
  - calico-system/calico-node-x8lbh 재시작:13회
  - calico-system/calico-node-xgjt5 재시작:5회
  - calico-system/calico-typha-ddf4dbbdd-7zxhc 재시작:14회
  - calico-system/calico-typha-ddf4dbbdd-lv6cc 재시작:12회
  - calico-system/calico-typha-ddf4dbbdd-pgcdk 재시작:13회

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

- ✅ `05-26 19:19` Trivy Security Scan
- ✅ `05-26 03:25` Update Homepage Metrics
- ✅ `05-25 18:23` Trivy Security Scan
- ✅ `05-25 03:40` Update Homepage Metrics
- ✅ `05-24 17:57` Trivy Security Scan
- ✅ `05-24 03:29` Update Homepage Metrics

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
