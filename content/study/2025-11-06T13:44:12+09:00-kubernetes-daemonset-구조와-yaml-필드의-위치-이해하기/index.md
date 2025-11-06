---
title: "Kubernetes DaemonSet 구조와 YAML 필드의 위치 이해하기"
date: 2025-11-06T13:44:12+09:00
draft: false
categories: []
tags: []
description: "Kubernetes DaemonSet 구조와 YAML 필드의 위치 이해하기"
author: "늦찌민"
---

# 🧩 DaemonSet YAML 구조, 왜 이렇게 써야 할까?

쿠버네티스에서 DaemonSet을 작성하다 보면,
`nodeSelector`, `selector`, `containers` 같은 필드가  
**왜 그 위치에 있어야 하는지** 헷갈릴 때가 많습니다.

이 글에서는 단순히 “틀리니까 고쳐라”가 아니라  
**왜 그런 구조로 되어 있는지**를 원리부터 설명합니다.

---

## 🧠 1. `nodeSelector`는 왜 `spec.template.spec` 안에 있을까?

### 📌 핵심 요약
- `nodeSelector`는 **Pod를 어떤 노드에 스케줄링할지 지정하는 필드**입니다.
- 즉, “Pod의 속성”이지 “DaemonSet 컨트롤러의 속성”이 아닙니다.
- 그래서 항상 `spec.template.spec` 안에 들어갑니다.

### ⚙️ 동작 구조
- DaemonSet은 “각 노드에 Pod를 하나씩 띄워라”는 상위 정책일 뿐,  
  실제 스케줄링은 **Pod 단위**로 이뤄집니다.
- 스케줄러는 오직 **Pod의 spec**만 읽기 때문에  
  `nodeSelector`는 Pod 내부(`template.spec`)에 있어야 합니다.

```bash
kubectl explain pod.spec.nodeSelector
kubectl explain ds.spec.template.spec.nodeSelector
```
>### 📘 반대로 kubectl explain ds.spec에는 nodeSelector 항목이 없습니다.
---




## 🎯 2. selector ↔ template.labels는 왜 일치해야 할까?
### 📌 핵심 요약

- 컨트롤러(Deployment, DaemonSet, StatefulSet 등)는
라벨 셀렉터로 자신이 관리할 Pod를 인식합니다.

- spec.selector는 “이 조건의 Pod를 내가 관리할게”
spec.template.labels는 “내가 만들 Pod에는 이 라벨을 붙일게”

- 두 값은 반드시 논리적으로 일치해야 합니다.

### ✅ 규칙

- template.labels는 selector.matchLabels의 부분집합이어야 합니다.

- 즉, selector의 key=value가 모두 템플릿 라벨에 포함되어야 해요.

-템플릿 라벨은 더 많아도 OK, 빠지면 ❌ 오류!

### ⚠️ 왜 이렇게 강제되었을까?

- selector는 컨트롤러의 정체성(Identity) 이라 변경이 위험합니다.
- apps/v1 이후부터는 안전성 보장을 위해 필수 일치로 강화되었습니다.
```bash
kubectl explain ds.spec.selector
kubectl explain ds.spec.template.metadata.labels
```
### 💡 예시
```yaml
selector:
  matchLabels:
    app: main
    tier: frontend
template:
  metadata:
    labels:
      app: main
      tier: frontend
      rel: stable   # 추가 라벨은 OK
```
---
## 🧱 3. containers는 왜 항상 spec.template.spec.containers 아래 있어야 하나?

### 📌 핵심 요약

- 컨테이너는 Pod의 실행 단위입니다.

- DaemonSet, Deployment 등은 “정책”만 정의할 뿐
실제 실행되는 것은 Pod이므로 컨테이너는 Pod spec 내부에 있어야 합니다.

### 🧩 구조적으로 보면
```scss
DaemonSet
└─ spec (DaemonSetSpec)
   └─ template (PodTemplateSpec)
      ├─ metadata
      └─ spec (PodSpec)
         └─ containers[] (Container)
```
즉, `container`는 **PodSpec의 필수 필드**이며,
위 계층 구조를 벗어나면 쿠버네티스가 인식할 수 없습니다.
```bash
kubectl explain ds.spec.template.spec.containers
kubectl explain pod.spec.containers
```
---
### 🔍 마무리 정리
| 항목                         | 올바른 위치                          | 이유                              |
| -------------------------- | ------------------------------- | ------------------------------- |
| nodeSelector               | `spec.template.spec`            | Pod 스케줄링 속성은 Pod spec 안에 있어야 함  |
| selector ↔ template.labels | 반드시 일치(부분집합 허용)                 | 컨트롤러가 Pod를 채택하기 위해 라벨 기반 매칭 필요  |
| containers                 | `spec.template.spec.containers` | 컨테이너는 Pod 실행 단위이며 PodSpec 내부 필드 |

### 💡 정리 문장으로 기억하기
> “컨트롤러는 정책을, Pod는 실행을 담당한다.
> 스케줄링·라벨·컨테이너 같은 ‘실행 세부 정보’는 항상 Pod 스펙 안에 들어가야 한다.”

### ✅ 참고 명령어들
```bash
kubectl explain ds.spec
kubectl explain ds.spec.template
kubectl explain ds.spec.template.spec
kubectl explain pod.spec.containers

```