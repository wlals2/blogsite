# Istio 실전 시리즈 — 계획

> 작성일: 2026-02-24
> 상태: 기존 5개 연결 완료, 신규 글 계획 중
> 시리즈 태그: `series: ["Istio 실전 시리즈"]`

---

## 방향성

**우리만의 방식**:
- 교과서식 설명 X → 홈랩에서 실제 겪은 문제 중심
- "이론 → 막힌 부분 → 해결 → 이해" 순서
- lati-tech처럼 9주 커리큘럼이 아닌, 실전 경험 기반 시리즈

---

## 현재 시리즈 구성 (5편, 연결 완료)

| 순서 | 날짜 | 제목 | 핵심 내용 |
|------|------|------|----------|
| 1 | 2026-01-22 | [Istio Service Mesh 아키텍처 완전 가이드](../content/study/2026-01-22-istio-service-mesh-architecture/index.md) | Data Plane/Control Plane, Envoy Sidecar 개념 |
| 2 | 2026-01-20 | [PassthroughCluster 문제 해결](../content/study/2026-01-20-nginx-proxy-istio-mesh-passthrough/index.md) | Nginx proxy→mesh 통합, Kiali 검정 연결 문제 |
| 3 | 2026-01-22 | [mTLS + Zero Trust 보안](../content/study/2026-01-22-istio-mtls-security/index.md) | PERMISSIVE→ISTIO_MUTUAL, AuthorizationPolicy |
| 4 | 2026-01-22 | [Traffic Management 실전](../content/study/2026-01-22-istio-traffic-management/index.md) | Retry/Timeout/Circuit Breaking/Mirroring |
| 5 | 2026-01-24 | [Nginx Ingress 제거 → Gateway 일원화](../content/study/2026-01-24-nginx-ingress-to-istio-gateway/index.md) | L7 Hop 2→1, 아키텍처 단순화 |

---

## 앞으로 추가할 글 (후보)

### 우선순위 높음 (실제 경험 있음)

| 제목 (안) | 핵심 내용 | 근거 |
|-----------|----------|------|
| **Istio 베어메탈 설치 가이드** | helm으로 설치, istiod 설정, sidecar 주입 방식 | 시리즈 1편(아키텍처) 전에 설치를 모름 → 독자가 따라하기 어려움 |
| **Protocol Detection 문제** | HTTP/gRPC 자동 감지 실패 → 수동 port naming | 실제 홈랩에서 경험한 문제, CLAUDE.md 07-security.md에 기록됨 |
| **Kiali로 서비스 토폴로지 시각화** | 트래픽 흐름 시각화, 헬스 체크 | 현재 실제 운영 중, 스크린샷 있음 |

### 우선순위 보통 (이론 보강)

| 제목 (안) | 핵심 내용 |
|-----------|----------|
| **Istio Ingress Gateway 라우팅 심화** | VirtualService 고급 매칭 (헤더, prefix) |
| **DestinationRule 완전 가이드** | subset, loadBalancing, connectionPool |
| **Envoy Filter로 커스텀 정책** | 헤더 추가/수정 실전 |

---

## 시리즈 특징 (lati-tech 대비 차별점)

| 비교 | lati-tech | 우리 시리즈 |
|------|-----------|-----------|
| 방식 | 이론 주도 (9주 커리큘럼) | 경험 주도 (문제 → 해결) |
| 환경 | 일반 K8s | 베어메탈 홈랩 (VMware + MetalLB + Cilium) |
| 내용 | Istio 공식 기능 설명 | 실제 막혔던 지점 + 해결 과정 |
| 증거 | 코드 예시 | Kiali 스크린샷, 실제 로그 |

---

## 다음 작업

- [ ] 설치 가이드 작성 (series의 실질적인 1편으로)
- [ ] Protocol Detection 글 작성 (실제 경험 기반)
- [ ] 기존 5편에 cover.jpg 생성 확인
- [ ] `/series/istio-실전-시리즈/` 페이지 확인 (자동 생성)
