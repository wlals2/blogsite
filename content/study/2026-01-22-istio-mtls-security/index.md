---
title: "Istio mTLS와 AuthorizationPolicy로 Zero Trust 보안 구현하기"
date: 2026-01-22
description: "Istio Service Mesh에서 mTLS 암호화와 AuthorizationPolicy를 활용한 프로덕션급 보안 구현"
tags: ["istio", "kubernetes", "mtls", "security", "zero-trust", "authorization"]
categories: ["study", "Security", "Service Mesh"]
---

## 개요

이 글은 실제 blog-system에서 구현한 **Istio 보안 정책**을 정리한 것입니다.

**구현된 보안 기능:**
- mTLS: PERMISSIVE + DestinationRule ISTIO_MUTUAL
- AuthorizationPolicy: Zero Trust 접근 제어
- Defense in Depth: 다층 방어

---

## Defense in Depth (다층 방어)

```
Layer 1: Network Policy (Kubernetes)
         ├─ Namespace isolation
         └─ Pod selector

Layer 2: Istio AuthorizationPolicy (Service Mesh)
         ├─ was-authz: blog-system/web만 허용
         └─ web-authz: 포트 80 전체 허용

Layer 3: Istio mTLS (Transport Security)
         ├─ DestinationRule: ISTIO_MUTUAL
         └─ 자동 인증서 관리

Layer 4: Application (Spring Boot)
         ├─ Spring Security
         └─ CORS, CSRF
```

---

## mTLS 구성

### 왜 STRICT가 아닌 PERMISSIVE인가?

**문제:**
```
Nginx Ingress Controller (mesh 외부)
        ↓ plain text (HTTP)
web-service:80 ← 502 Bad Gateway (STRICT mTLS 요구 시)
```

**원인:**
- Nginx Ingress Controller는 Istio mesh 외부에서 동작
- STRICT 모드: mTLS만 허용 → plain text 거부 → 502 에러

### PeerAuthentication 설정

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

### 구간별 프로토콜

| 구간 | 프로토콜 | 이유 |
|------|----------|------|
| Nginx Ingress → web:80 | Plain text | PERMISSIVE 허용 |
| **web → was:8080** | **mTLS** | DestinationRule ISTIO_MUTUAL |
| was → mysql:3306 | Plain text | JDBC 호환성, mesh 제외 |

---

## AuthorizationPolicy

### Zero Trust 원칙

```
1. Never Trust, Always Verify
   - 모든 요청을 검증
   - 기본은 거부 (DENY)
   - 명시적 허용만 (ALLOW)

2. Least Privilege
   - 최소 권한만 부여
   - was: web에서만 접근 가능
   - 경로 제한: /api/*, /actuator/*

3. Verify Explicitly
   - source.principals 확인
   - source.namespaces 확인
   - 포트 및 경로 제한
```

### web-authz (Ingress 허용)

```yaml
apiVersion: security.istio.io/v1beta1
kind: AuthorizationPolicy
metadata:
  name: web-authz
  namespace: blog-system
spec:
  selector:
    matchLabels:
      app: web
  action: ALLOW
  rules:
  - to:
    - operation:
        ports: ["80"]
```

**왜 포트 80 전체 허용인가?**
- Nginx Ingress는 mesh 외부에서 동작
- source identity가 없음 (mesh에 속하지 않음)
- `source.namespaces`로 매치 불가

### was-authz (web만 허용)

```yaml
apiVersion: security.istio.io/v1beta1
kind: AuthorizationPolicy
metadata:
  name: was-authz
  namespace: blog-system
spec:
  selector:
    matchLabels:
      app: was
  action: ALLOW
  rules:
  - from:
    - source:
        principals: ["cluster.local/ns/blog-system/sa/default"]
        namespaces: ["blog-system"]
    to:
    - operation:
        ports: ["8080"]
        paths: ["/api/*", "/actuator/*"]
```

### 검증

```bash
# 정상 접근 (외부 → web → was)
curl https://blog.jiminhome.shop/api/posts
# HTTP 200 OK

# 비정상 접근 (직접 was 접근)
kubectl run test-authz --rm -it --image=curlimages/curl -- \
  curl http://was-service.blog-system.svc.cluster.local:8080/api/posts
# RBAC: access denied (403)
```

### 효과

| 접근 경로 | 허용 여부 | 정책 |
|-----------|----------|------|
| **외부 → web:80** | 허용 | authz-web |
| **web → was:8080** | 허용 | authz-was |
| **외부 → was:8080** | 차단 | authz-was (403) |
| **임의 pod → was** | 차단 | authz-was (namespace 제한) |

---

## 트러블슈팅

### RBAC: access denied (403)

**증상:**
```bash
curl https://blog.jiminhome.shop/api/posts
# RBAC: access denied
```

**진단:**
```bash
kubectl logs -n blog-system -l app=web -c istio-proxy --tail=20
# rbac_access_denied_matched_policy[none]
```

**원인:**
- AuthorizationPolicy 규칙이 Nginx Ingress를 매치하지 못함
- Nginx Ingress는 mesh 외부이므로 source identity 없음

**해결:**
```yaml
# web-authz: 포트 80 전체 허용 (mesh 외부 Ingress 호환)
rules:
- to:
  - operation:
      ports: ["80"]
```

### 502 Bad Gateway (STRICT mTLS)

**증상:**
```bash
curl https://blog.jiminhome.shop/
# 502 Bad Gateway
```

**원인:**
```yaml
spec:
  mtls:
    mode: STRICT  # plain text 거부
```

**해결:**
```yaml
spec:
  mtls:
    mode: PERMISSIVE  # plain text + mTLS 둘 다 허용
```

---

## 공격 시나리오별 방어

| 공격 시나리오 | Before | After |
|---------------|--------|-------|
| **외부 → was:8080** | 가능 | 403 Forbidden |
| **web → mysql 직접** | 가능 | 경로 제한으로 차단 |
| **임의 pod → was** | 가능 | namespace 제한으로 차단 |
| **패킷 스니핑** | 가능 | mTLS 암호화 |

---

## 참고 자료

- [Istio Security](https://istio.io/latest/docs/concepts/security/)
- 내부 문서: `docs/istio/COMPLETE-ISTIO-ARCHITECTURE.md`
- 내부 문서: `docs/istio/NGINX-PROXY-ISTIO-MESH.md`
