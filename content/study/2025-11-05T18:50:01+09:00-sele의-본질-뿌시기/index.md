---
title: "Selector의 본질 뿌시기"
date: 2025-11-05T18:50:01+09:00
draft: false
categories: ["k8s","selector",""]
tags: ["k8s","pvc","pv","selector"]
description: "Selector의 본질 뿌시기"
author: "늦찌민"
series: ["K8s 개념 뿌시기"]
---

## K8s-Selector 뿌시기
### Selector의 본질:
- 이 리소스가 어떤 대상을 선택할 것 인가를 정의
- 어떤 컨트롤러/리소스냐에 따라 선택의 목적이 달라짐

---

### ✅ 실제 예시들
### 1️⃣ Service → Pod 선택 (트래픽 라우팅)
```yaml
kind: Service
spec:
  selector:
    app: web
# "app=web" Pod들에게 트래픽 보냄
```

### 2️⃣ Deployment → Pod 선택 (생명주기 관리)
```yaml
kind: Deployment
spec:
  selector:
    matchLabels:
      app: web
# "app=web" Pod들을 관리 (생성/삭제/업데이트)
```

### 3️⃣ PVC → PV 선택 (스토리지 바인딩)
```yaml
kind: PersistentVolumeClaim
spec:
  selector:
    matchLabels:
      type: ssd
# "type=ssd" PV와 연결
```

### 4️⃣ NetworkPolicy → Pod 선택 (방화벽 규칙 적용)
```yaml
kind: NetworkPolicy
spec:
  podSelector:
    matchLabels:
      role: db
# "role=db" Pod에 네트워크 정책 적용
```

## 🎯 용도에 따른 selector.matchExpressions 정리

### 1. 기본 - matchLabels 만 사용
```yaml
selector:
  matchLabels:
    app: backend
    version: v2
```

### 2. Blue-Green 배포 - 여러 버전 동시 관리
```yaml
# Service가 둘 다 선택
selector:
  matchLabels:
    app: api
  matchExpressions:
    - {key: version, operator: In, values: ["blue", "green"]}
```

### 3. 환경별 분리
```yaml
# production 제외하고 모두 선택 (dev, staging만)
selector:
  matchLabels:
    app: frontend
  matchExpressions:
    - {key: env, operator: NotIn, values: ["production"]}
```

### 4. 모니터링/백업 대상 선택
```yaml
# backup 레이블이 있는 것만
matchExpressions:
  - {key: backup, operator: Exists}
```
### 5. 특정 레이블 없는 것 선택
```yaml
# legacy 레이블이 없는 최신 Pod만
matchExpressions:
  - {key: legacy, operator: DoesNotExist}
```

### 6. Canary 배포
```yaml
# v1, v2 모두 트래픽 받도록
selector:
  matchLabels:
    app: payment
  matchExpressions:
    - {key: version, operator: In, values: ["v1", "v2"]}
```

- 대부분은 matchLabels만으로 충분
- matchExpressions는 Service에서 여러 Deployment 묶을 때 주로 사용
- Deployment 자체는 보통 명확한 label 조합 사용

### 정리 :

> Selector = 필터 역할 \
> 무엇을 필터링하고, 필터링 후 무엇을 할지는 리소스 종류가 결정 \
> PVC/PV처럼 selector로 바인딩하는 것도 같은 원리!