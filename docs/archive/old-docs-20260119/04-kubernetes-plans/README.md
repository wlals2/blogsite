# Kubernetes 개선 계획

이 폴더는 Kubernetes 인프라의 미래 개선 계획 및 연구 문서를 포함합니다.

> **Note**: 이 문서들은 **계획/연구 단계**이며, 아직 구현되지 않았습니다.

## 📚 문서 목록 (읽는 순서)

### 1. [001-ssl-implementation.md](001-ssl-implementation.md)
**Kubernetes 네이티브 SSL 구현 계획**

cert-manager + MetalLB로 로컬 nginx 제거 및 완전 자동화:

**Before (현재):**
```
CloudFlare (443) → 로컬 nginx (SSL + Proxy) → K8s NodePort (31852)
                   ↑
                   수동 SSL 갱신 (90일), 외부 의존성
```

**After (목표):**
```
CloudFlare (443) → MetalLB LoadBalancer (443) → Ingress (TLS)
                   ↑
                   cert-manager 자동 SSL 관리
```

**주요 내용:**
- 구현 목표 및 달성 효과
- 필요한 컴포넌트 (cert-manager, MetalLB)
- 단계별 구현 계획
- 예상 문제 및 해결책
- Rollback 전략

**읽어야 하는 경우:**
- 로컬 nginx를 제거하고 완전 Kubernetes 네이티브로 전환하고 싶을 때
- cert-manager + MetalLB 구현 방법을 알고 싶을 때
- SSL 인증서 자동 관리 시스템을 구축하고 싶을 때

---

### 2. [002-metallb-solutions.md](002-metallb-solutions.md)
**MetalLB + Public/Private IP 문제 해결 방법 비교**

로컬 Kubernetes MetalLB를 Public IP 환경에서 사용하는 방법:

**문제 상황:**
```
Public IP:  122.46.102.252 (외부 인터페이스)
Private IP: 192.168.1.187   (내부 네트워크)
MetalLB IP: 192.168.1.200   (Private - 외부 접근 불가)
```

**해결 방법 비교:**
1. **iptables DNAT** (현재 nginx 방식 - 추천)
2. **MetalLB Public IP 할당** (가능하나 위험)
3. **ExternalIPs 사용** (간단하나 제한적)
4. **CloudFlare Tunnel** (Public IP 불필요)

**주요 내용:**
- 각 방법의 작동 원리
- 장단점 비교
- 구현 난이도
- 보안/안정성
- 최종 권장사항

**읽어야 하는 경우:**
- MetalLB를 Public IP에 노출하고 싶을 때
- Private IP와 Public IP 문제를 해결하고 싶을 때
- 각 방법의 장단점을 비교하고 싶을 때

---

## 🔗 관련 문서

- **Cloudflare 대안 가이드**: [03-cloudflare/002-nginx-alternatives.md](../03-cloudflare/002-nginx-alternatives.md)
- **프로젝트 전체 가이드**: [00-overview/002-complete-guide.md](../00-overview/002-complete-guide.md)
- **현재 아키텍처**: [00-overview/002-complete-guide.md#2-전체-아키텍처](../00-overview/002-complete-guide.md#2-전체-아키텍처)

---

## ⚠️ 주의사항

이 폴더의 문서들은 **계획 단계**입니다:
- ✅ 연구 및 분석 완료
- ⏳ 구현 대기 중
- ❌ 프로덕션 미적용

실제 구현 전에 반드시:
1. 테스트 환경에서 검증
2. Rollback 계획 수립
3. 다운타임 최소화 전략 수립
