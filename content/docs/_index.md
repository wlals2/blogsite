---
title: "📚 Documentation"
date: 2026-01-21
draft: false
description: "기술 문서 모음 - Kubernetes, Istio Service Mesh, Cilium eBPF, Networking 등"
---

# 기술 문서 모음

프로젝트를 진행하며 작성한 기술 문서들입니다.

## 📂 카테고리

### 🕸️ Istio Service Mesh (프로젝트 실전 문서)
**blog-system 프로젝트에 Istio Service Mesh 구축 완전 가이드**

- [완전한 Istio 아키텍처 가이드](istio/COMPLETE-ISTIO-ARCHITECTURE.md) - **추천** 전체 구축 과정, 모든 이슈 & 트러블슈팅
- [nginx Proxy Istio Mesh 통합](istio/NGINX-PROXY-ISTIO-MESH.md) - nginx 프록시 Istio mesh 통합 상세 가이드
- [향후 개선 작업 (TODO)](istio/TODO.md) - 프로덕션 환경 고도화 6가지 과제

**구축 완료 항목:**
- ✅ mTLS 암호화 (ISTIO_MUTUAL)
- ✅ Circuit Breaking, Zero Trust 보안
- ✅ Retry, Timeout, Traffic Mirroring
- ✅ Jaeger 분산 추적, Kiali 통합

---

### 🐝 Cilium eBPF (프로젝트 실전 문서)
**로컬 Kubernetes 클러스터에 Cilium 구축 가이드**

- [로컬 K8s Cilium 아키텍처](cilium/LOCAL-K8S-CILIUM-ARCHITECTURE.md) - Homeserver K8s + Cilium CNI 구축
- [Cilium 엔터프라이즈 활용 사례](cilium/CILIUM-ENTERPRISE-USE-CASES.md) - 프로덕션 고급 활용
- [Cilium 개선 완료](cilium/CILIUM-IMPROVEMENT-COMPLETE.md) - 문제 해결 및 최적화 기록
- [MD 파일 상태 보고서](cilium/MD-FILES-STATUS-REPORT.md) - 문서화 작업 현황

---

### 🌐 Networking (일반 가이드)
**네트워킹 기술 상세 분석**

- [Cilium 실전 활용 가이드](networking/cilium-usage-guide/) - eBPF 기반 네트워킹 구축
- [Cilium eBPF Deep Dive](networking/cilium-ebpf-deep-dive/) - eBPF 동작 원리 상세 분석

---

### 📊 시스템 현황
- [CURRENT-STATE.md](CURRENT-STATE.md) - k8s-manifests 프로젝트 전체 현황

---

**최근 업데이트**: 2026-01-21
