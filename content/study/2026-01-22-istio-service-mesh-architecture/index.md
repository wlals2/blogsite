---
title: "Istio Service Mesh 아키텍처 구축 실전기"
date: 2026-01-22
description: "nginx proxy를 통한 Istio mesh 통합과 mTLS 암호화 구현 경험"
tags: ["istio", "kubernetes", "service-mesh", "mtls", "kiali"]
categories: ["study"]
---

## 개요

이 글은 실제 로컬 Kubernetes 클러스터에서 **Istio Service Mesh**를 구축한 경험을 정리한 것입니다.

**실제 환경:**
- 클러스터: local-k8s (3 노드)
- Namespace: blog-system, istio-system
- 구성: Nginx Ingress → web → was → mysql

---

## 초기 상태 (Before)

```
[Nginx Ingress Controller]
         ↓ /api → was-service:8080 (직접)
[was-service] ← Istio mesh 우회
         ↓
   [was pod] → [mysql]
```

**문제점:**
- API 트래픽이 Istio mesh 완전 우회
- mTLS 암호화 없음
- Kiali에서 web → was 연결 안 보임
- 보안 정책 (AuthorizationPolicy) 없음

---

## 최종 상태 (After)

```
[External Traffic]
       ↓ HTTPS
[Nginx Ingress Controller]
       ↓ HTTP (plain text)
[web-service:80] ← PERMISSIVE mTLS 허용
       ↓
[web pod]
 ├─ nginx (reverse proxy)
 │   └─ proxy_pass → was-service:8080
 │       Host: was-service (FQDN)
 ├─ istio-proxy (sidecar)
 │   ├─ mTLS encryption (ISTIO_MUTUAL)
 │   └─ Distributed Tracing (Jaeger)
       ↓ mTLS (encrypted)
[was-service:8080]
       ↓
[was pod]
 ├─ Spring Boot WAS
 ├─ istio-proxy (sidecar)
       ↓ plain text (JDBC)
[mysql] ← mesh 제외
```

---

## 핵심 구성 요소

### 시스템 규모

| 항목 | 수치 |
|------|------|
| **Namespace** | 2개 (blog-system, istio-system) |
| **Services** | 3개 (web, was, mysql) |
| **Mesh Coverage** | 66% (web, was - mysql 제외) |
| **DestinationRules** | 3개 |
| **VirtualServices** | 1개 |
| **AuthorizationPolicies** | 2개 |
| **PeerAuthentication** | 2개 |

---

## mTLS 구성

### PeerAuthentication

```yaml
apiVersion: security.istio.io/v1beta1
kind: PeerAuthentication
metadata:
  name: default
  namespace: blog-system
spec:
  mtls:
    mode: PERMISSIVE
```

**왜 PERMISSIVE인가?**
- Nginx Ingress Controller는 mesh 외부에서 동작
- STRICT로 설정하면 Ingress → web 통신 시 502 에러 발생
- PERMISSIVE로 plain text + mTLS 둘 다 허용

### DestinationRule로 mTLS 강제

```yaml
apiVersion: networking.istio.io/v1beta1
kind: DestinationRule
metadata:
  name: was-dest-rule
  namespace: blog-system
spec:
  host: was-service
  trafficPolicy:
    tls:
      mode: ISTIO_MUTUAL  # mTLS 명시적 강제
```

**효과:**
| 구간 | 프로토콜 |
|------|----------|
| Nginx Ingress → web:80 | Plain text |
| **web → was:8080** | **mTLS** |
| was → mysql:3306 | Plain text |

---

## nginx Host 헤더 문제 해결

### 문제

```nginx
# Before (잘못된 설정)
proxy_set_header Host $host;  # → blog.jiminhome.shop (외부 도메인)
```

Istio가 `blog.jiminhome.shop`을 외부 트래픽으로 판단하여 PassthroughCluster로 처리.

### 해결

```nginx
location /api {
    proxy_pass http://was-service.blog-system.svc.cluster.local:8080;
    proxy_set_header Host was-service;  # 서비스명으로 변경
}
```

**확인:**
```bash
kubectl logs -n blog-system -l app=web -c istio-proxy --tail=50 | grep was-service
# outbound|8080||was-service.blog-system.svc.cluster.local
```

---

## Kiali로 확인

Kiali에서 Workload graph 확인:

```
web-service → web → was-service → was
     ↓                   ↓
  (녹색)             (녹색) mTLS
```

- PassthroughCluster 사라짐
- 모든 연결이 녹색 (mesh 내부)

**접속:**
```bash
kubectl port-forward -n istio-system svc/kiali 20001:20001
# http://localhost:20001/kiali
```

---

## 참고 자료

- [Istio Documentation](https://istio.io/latest/docs/)
- 내부 문서: `docs/istio/COMPLETE-ISTIO-ARCHITECTURE.md`
