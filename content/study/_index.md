---
title: "Study"
date: 2026-01-13
layout: "list"
url: "/study/"
summary: "클라우드/DevOps 공부 기록 - Kubernetes, EKS, Monitoring, Storage 등"
---

# 공부 기록

> 프로젝트를 진행하며 배운 것들을 정리합니다.

---

## 주제별 분류

### Kubernetes 기초

Kubernetes의 핵심 개념과 트러블슈팅 경험을 정리했습니다.

| 주제 | 내용 |
|------|------|
| **Probe** | LivenessProbe, ReadinessProbe 완벽 가이드 |
| **Service** | ClusterIP, NodePort, LoadBalancer 트러블슈팅 |
| **SecurityContext** | Pod 보안 설정, 80번 포트 문제 해결 |
| **DaemonSet** | 구조와 YAML 필드 이해 |
| **PVC** | 삭제 문제, Longhorn 트러블슈팅 |
| **CNI/CSI** | 네트워크와 스토리지 플러그인 이해 |

---

### Amazon EKS

AWS EKS 클러스터 구축과 운영 경험입니다.

| 주제 | 내용 |
|------|------|
| **EKS 완전 가이드** | Control Plane, Node Group, IRSA |
| **3-Tier 설계** | Auto Mode에서 실전 구성까지 |
| **3-Tier 구축** | Docker부터 Ingress까지 |

---

### Helm 인프라 시리즈

온프레미스 Kubernetes에서 Helm Chart로 인프라를 구축한 시리즈입니다.

| 순서 | 주제 | 내용 |
|------|------|------|
| 1 | **Samba** | 파일 공유 서버 구축 |
| 2 | **EFK** | Elasticsearch + Fluentd + Kibana |
| 3 | **nginx + FTP** | 웹서버 + 파일 서버 |
| 4 | **Apache** | httpd 프록시 설정 |
| 5 | **Prometheus + Grafana** | 모니터링 스택 |
| 6 | **ArgoCD + Jenkins** | CI/CD 파이프라인 |
| 7 | **OpenVPN** | VPN 서버 구축 |

---

### Monitoring

Prometheus, Grafana, CloudWatch를 활용한 모니터링 구축 경험입니다.

| 주제 | 내용 |
|------|------|
| **Prometheus Node 메트릭** | kubernetes-node 에러 해결 |
| **Grafana Dashboard** | 대시보드 구축 가이드 |

---

### Storage

Kubernetes 스토리지와 백업 관련 경험입니다.

| 주제 | 내용 |
|------|------|
| **Longhorn** | VMware 환경 PVC 문제 해결 |
| **Velero** | 클러스터 백업 및 복원 |
| **PVC 삭제** | Finalizer 문제 해결 |

---

### Networking

Kubernetes 네트워킹 심화 내용입니다.

| 주제 | 내용 |
|------|------|
| **Cilium + eBPF** | kube-proxy를 넘어 eBPF로 |
| **MetalLB** | SSH Port Forwarding 외부 접근 |

---

### Elasticsearch

Elasticsearch 인덱싱과 쿼리 관련 내용입니다.

| 주제 | 내용 |
|------|------|
| **아키텍처** | 클러스터 구성 이해 |
| **인덱싱** | 데이터 색인 방법 |
| **Nested Query** | 중첩 문서 검색 |
| **Term Query** | 정확한 값 검색 |

---

### Terraform

Infrastructure as Code 관련 내용입니다.

| 주제 | 내용 |
|------|------|
| **Automation 1** | 기본 자동화 가이드 |
| **Automation 2** | 고급 자동화 패턴 |

---

### 기타 도구

프로젝트에서 사용한 다양한 도구들입니다.

| 주제 | 내용 |
|------|------|
| **Docker** | Windows Desktop 이슈 해결 |
| **GitHub Actions** | 자동 배포 트러블슈팅 |
| **Tekton** | 기본 파이프라인 구축 |
| **nginx** | 설정 및 프록시 가이드 |
| **Java 빌드** | Maven + Tomcat 운영 |

---

## 공부 방법

1. **문제 발생** → 왜 이런 에러가 났지?
2. **원인 분석** → 로그, 문서, 구글링
3. **해결** → 직접 해결 or 우회
4. **기록** → 블로그에 정리

> "3개월 후의 나도 이해할 수 있게" 작성합니다.

---

## 전체 글 목록

아래는 날짜순 전체 글 목록입니다.
