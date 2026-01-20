# Cloudflare 설정

이 폴더는 Cloudflare CDN 및 관련 인프라 설정에 관한 문서를 포함합니다.

## 📚 문서 목록 (읽는 순서)

### 1. [001-auto-purge.md](001-auto-purge.md)
**Cloudflare Cache 자동 Purge 설정 가이드**

배포 후 Cloudflare CDN 캐시를 자동으로 삭제하는 방법:
- 왜 필요한가? (캐시로 인한 변경사항 미반영 문제)
- Cloudflare API Token 발급 방법
- Zone ID 확인 방법
- GitHub Secrets 등록
- 워크플로우 적용
- 테스트 및 트러블슈팅

**읽어야 하는 경우:**
- 배포 완료했는데 변경사항이 안 보일 때
- GitHub Actions에서 자동으로 캐시를 삭제하고 싶을 때
- Cloudflare API 사용법을 배우고 싶을 때

**주요 내용:**
```bash
# 자동 실행: GitHub Actions 워크플로우에서
curl -X POST "https://api.cloudflare.com/client/v4/zones/$ZONE_ID/purge_cache" \
  -H "Authorization: Bearer $API_TOKEN" \
  --data '{"purge_everything":true}'
```

---

### 2. [002-nginx-alternatives.md](002-nginx-alternatives.md)
**CloudFlare + 로컬 nginx 대안 가이드**

로컬 Kubernetes 환경에서 외부 노출 방법 비교:
- 현재 상황 (CloudFlare → nginx → K8s NodePort)
- 왜 이 문제가 발생하는가?
- 해결 방법 비교:
  1. **현재 방식**: CloudFlare + 로컬 nginx (빠르고 안정적)
  2. **cert-manager + MetalLB**: Kubernetes 네이티브 (완전 자동화)
  3. **CloudFlare Tunnel**: 제로 트러스트 (Public IP 불필요)
  4. **Ingress-nginx SSL**: NodePort + SSL (단순)
- 각 방법의 장단점 및 구현 복잡도
- 최종 권장사항

**읽어야 하는 경우:**
- 로컬 nginx를 제거하고 싶을 때
- Kubernetes 네이티브 아키텍처로 전환하고 싶을 때
- CloudFlare + nginx를 왜 사용하는지 궁금할 때
- 대안들을 비교하고 싶을 때

**핵심 질문:**
> "CloudFlare + 로컬 nginx를 어쩔 수 없이 사용하고 있는 건가요?"
>
> **답변**: 아니요! 가장 빠르고 안정적인 방법이지만, cert-manager + MetalLB로 완전 자동화 가능합니다.

---

## 🔗 관련 문서

- **GitHub Actions 워크플로우**: [01-github-actions/003-guide.md](../01-github-actions/003-guide.md)
- **Kubernetes SSL 계획**: [04-kubernetes-plans/001-ssl-implementation.md](../04-kubernetes-plans/001-ssl-implementation.md)
- **MetalLB 솔루션**: [04-kubernetes-plans/002-metallb-solutions.md](../04-kubernetes-plans/002-metallb-solutions.md)
- **빠른 참조**: [00-overview/001-quick-reference.md](../00-overview/001-quick-reference.md)
