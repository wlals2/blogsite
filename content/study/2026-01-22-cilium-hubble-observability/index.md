---
title: "Cilium Hubble로 Kubernetes 네트워크 트래픽 관찰하기"
date: 2026-01-22
description: "Cilium Hubble Relay & UI를 활용한 실시간 네트워크 모니터링 실전 가이드"
tags: ["cilium", "hubble", "kubernetes", "observability", "ebpf", "network"]
categories: ["study", "Networking"]
---

## 개요

이 글은 실제 로컬 Kubernetes 클러스터에서 **Cilium Hubble**을 설정하고 활용한 경험을 정리한 것입니다.

**실제 환경:**
- 클러스터: local-k8s (3 노드)
- Cilium 버전: v1.18.4
- Hubble CLI: v1.18.5

---

## Hubble이란?

Hubble은 Cilium에서 제공하는 네트워크 관측 도구입니다. eBPF를 통해 수집된 네트워크 플로우를 시각화하고 분석할 수 있습니다.

**구성 요소:**
| 구성 요소 | 역할 | 상태 |
|----------|------|------|
| **Hubble Relay** | 네트워크 플로우 수집 및 집계 | 1 replica (Running) |
| **Hubble UI** | 웹 기반 대시보드 | 1 replica (Running) |
| **Hubble CLI** | 커맨드라인 인터페이스 | v1.18.5 |

---

## 실제 접속 정보

Hubble UI는 NodePort 서비스로 노출되어 있습니다:

```
Hubble UI (웹 대시보드):
- http://192.168.1.187:31234 (k8s-cp)
- http://192.168.1.61:31234 (k8s-worker1)
- http://192.168.1.62:31234 (k8s-worker2)

Hubble CLI:
hubble observe --server localhost:4245
```

---

## Hubble UI 기능

웹 대시보드에서 제공하는 기능:

1. **Service Dependency Map**: 어떤 Pod가 어디에 연결되는지 시각화
2. **네트워크 플로우 실시간 시각화**: 패킷 흐름 확인
3. **거부된 트래픽 확인**: 보안 이벤트 모니터링
4. **L7 HTTP 트래픽 분석**: HTTP 요청/응답 분석

### 사용 예시

1. 특정 Namespace 선택 (예: kube-system)
2. Service Dependency Map 확인
3. 플로우 리스트에서 DROP된 트래픽 확인

---

## Hubble CLI 명령어

### 기본 조회

```bash
# 실시간 네트워크 플로우 모니터링
hubble observe

# 최근 50개 플로우
hubble observe --last 50

# 특정 Namespace만
hubble observe --namespace kube-system

# 특정 Pod만
hubble observe --pod cilium-ksv4c
```

### 보안 이벤트 조회

```bash
# 거부된 트래픽만 (보안 중요!)
hubble observe --verdict DROPPED

# 특정 시간대 이벤트
hubble observe --since 2026-01-14T00:00:00Z
```

### L7 트래픽 분석

```bash
# HTTP 트래픽만
hubble observe --protocol http

# DNS 쿼리만
hubble observe --protocol dns

# TCP 연결만
hubble observe --type trace:to-endpoint
```

---

## 실제 검증 결과

클러스터에서 Hubble observe를 실행한 결과:

```bash
$ hubble observe --last 10
Jan 14 10:19:43.212: longhorn-system/csi-provisioner -> kube-apiserver
Jan 14 10:19:44.726: coredns -> kube-apiserver
Jan 14 10:19:45.526: hubble-relay -> cilium-agent (3 nodes)
...
```

네트워크 플로우가 정상적으로 수집되고 있습니다.

---

## 감사 로그 활용

### 감사 로그 수집

```bash
# 1월 전체 로그 내보내기
hubble observe --since 2026-01-01T00:00:00Z \
  --until 2026-01-31T23:59:59Z \
  --output json > audit-log-jan-2026.json

# 거부된 트래픽만 (보안 이벤트)
hubble observe --verdict DROPPED \
  --since 2026-01-01T00:00:00Z \
  --output json > security-events-jan-2026.json
```

### 감사 리포트 생성

```bash
# 거부된 트래픽 통계
jq -r '.flow | select(.verdict == "DROPPED") | "\(.time) \(.source.pod_name) -> \(.destination.pod_name) (\(.l7.http.method) \(.l7.http.url))"' audit-log.json
```

---

## 개선 효과

### Before (Hubble 설치 전)

| 항목 | 상태 |
|------|------|
| **Cilium** | v1.18.4 (Agent, Envoy, Operator) |
| **Hubble** | ConfigMap에서 활성화 (Pod 없음) |
| **관측성** | 제한적 |

### After (Hubble 설치 후)

| 항목 | 상태 | 개선 효과 |
|------|------|----------|
| **Cilium** | v1.18.4 | 동일 |
| **Hubble Relay** | Running (1 replica) | 네트워크 플로우 수집 |
| **Hubble UI** | Running (http://192.168.1.187:31234) | 웹 대시보드 |
| **Hubble CLI** | v1.18.5 설치 | CLI로 네트워크 플로우 조회 |
| **관측성** | **대폭 향상** | 실시간 네트워크 모니터링 |

---

## 참고 자료

- [Cilium Hubble Documentation](https://docs.cilium.io/en/stable/gettingstarted/hubble/)
- 내부 문서: `docs/cilium/CILIUM-IMPROVEMENT-COMPLETE.md`
