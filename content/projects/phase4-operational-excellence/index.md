---
title: "Phase 4: MSA (Microservices Architecture)"
date: 2026-02-01
summary: "Monolith 한계 극복 - Service Mesh로 기능별 독립 배포 및 스케일링"
tags: ["MSA", "Istio", "Kafka", "Service Mesh"]
weight: 4
---

# Phase 4: MSA (계획 중)

> **예상 기간**: 2026.02 ~ (Phase 3 완료 후)

## 왜 필요한가?

**현재 한계 (Monolithic):**
- 전체 애플리케이션 하나의 WAR
- 작은 변경에도 전체 재배포
- 기능별 독립 스케일링 불가

## 계획

**목표:**
- Microservices 아키텍처 (User, Pet, Vet, Visit)
- Service Mesh (Istio) - mTLS, Circuit Breaker
- Event-Driven (Kafka) - 비동기 통신
- API Gateway (Spring Cloud Gateway)

**기대 효과:**
- 기능별 독립 배포
- 장애 격리 (Circuit Breaker)
- 독립 스케일링

---

**[← 프로젝트 전체 보기](/projects/)**
