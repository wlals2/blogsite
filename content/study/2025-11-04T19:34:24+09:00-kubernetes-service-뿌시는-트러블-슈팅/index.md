---
title: "Kubernetes Service 뿌시는 트러블 슈팅"
date: 2025-11-04T19:34:24+09:00
draft: false
categories: ["study", "Kubernetes"]
tags: ["k8s","데이터 영속성","longhorn","service","nodeport","네트워크","deployment","k9s"]
description: "Kubernetes Service 뿌시는 트러블 슈팅 Service 완벽 정복(?)"
author: "늦찌민"
series: ["Kubernetes 기초 시리즈"]
---

>NodePort Service를 만들었는데 외부에서 접속이 안 된다면?  
>아래 순서대로 하나씩 점검하면 대부분 원인을 찾을 수 있습니다 ⚙️

## 🧭 트러블슈팅 순서


### 1️⃣ Endpoints 확인
Service가 Pod를 제대로 찾고 있는지 확인합니다.

```bash
kubectl get endpoints <service-name> -n <namespace>

```
-  none ->  Service가 Pod를 못 찾고 있음 → selector 문제
-  IP:Port → Pod와 연결됨 → 다음 단계로 진행
---


### 2️⃣ Selector 일치 확인
Service와 Pod의 Label이 일치하는지 확인합니다.

```bash
# Service selector
kubectl get svc <svc> -n <ns> -o yaml | grep -A 3 selector

# Pod labels
kubectl get pod -n <ns> --show-labels

```

#### 흔한 실수
- app: grafana1G vs app: grafana
- selector에 label이 누락됨
---
### 3️⃣ Pod 상태 확인
Pod가 정상적으로 동작 중인지 확인합니다.

```bash
kubectl get pods -n <namespace>
kubectl describe pod <pod-name> -n <namespace>

```

#### 확인해야 할 항목:

- Pod 상태가 Running 인가?
- Readiness probe 통과했는가?
- PVC 마운트 실패가 없는가?
---

### 4️⃣ 포트 매핑 확인
Service와 Pod 간 포트 매칭을 확인합니다.

```bash
kubectl describe svc <service-name> -n <namespace>

```

```bash
ports:
- port: 80          # Service 포트 (외부 → Service)
  targetPort: 8000  # Pod 내부 포트 (Service → Pod)
  nodePort: 31250   # NodePort (외부 접근)

```
---

### 5️⃣ Pod 내부에서 직접 테스트
Pod 내부 통신이 정상인지 확인합니다.

```bash
kubectl exec -n <ns> <pod> -- curl http://localhost:<port>

```
- 응답이 온다면 → Pod 내부는 정상
- 응답이 없다면 → 애플리케이션 포트 설정 문제
---
### 6️⃣ CNI / 방화벽 확인
Cilium, Calico 등의 CNI가 Service를 인식하는지와 방화벽 설정을 점검합니다.

```bash
# Cilium이 Service를 인식하는지 확인
kubectl exec -n kube-system <cilium-pod> -- cilium service list | grep <port>

# 방화벽 포트 열기 (Ubuntu 예시)
sudo ufw allow <nodeport>/tcp

```

---
## 실제 사례

### 🧩 Case 1: Selector 오타
증상: `Endpoint`가 `<none>`으로 표시됨

```bash
# Service
selector:
  app: grafana1G  # ❌ 오타

# Pod
labels:
  app: grafana

```

✅ **해결**: selector를 Pod label과 **동일하게 수정**

---
### 🧩 Case 2: TargetPort 불일치
**증상**: NodePort 접속 시 Connection refused
```bash
# Service
targetPort: 80  # ❌

# Pod
containerPort: 8000  # 실제 포트

```

### 🧩 Case 3: 방화벽 미개방
증상: Pod는 정상인데 외부 접속 불가

✅ 해결:

```bash
sudo ufw allow 30888/tcp

```
---

### 체크리스트

| 항목                   | 확인 명령어                                               | 상태 |
| -------------------- | ---------------------------------------------------- | -- |
| Pod 연결됨?             | `kubectl get endpoints`                              |    |
| Selector / Label 일치? | `kubectl get svc` / `kubectl get pods --show-labels` |    |
| Pod 상태 정상?           | `kubectl get pods`                                   |    |
| TargetPort 올바름?      | `kubectl describe svc`                               |    |
| 방화벽 포트 열림?           | `sudo ufw status`                                    |    |
| CNI Service 인식됨?     | `kubectl exec cilium... -- cilium service list`      |    |


---

### 유용한 명령어 모음

```bash
# 전체 리소스 한 번에 확인
kubectl get svc,endpoints,pods -n <namespace>

# Service 상세 정보
kubectl describe svc <service-name> -n <namespace>

# 이벤트 타임라인 확인
kubectl get events -n <namespace> --sort-by='.lastTimestamp'

```

### 🧠 정리 요약

| 원인             | 증상                   | 해결 방법                        |
| -------------- | -------------------- | ---------------------------- |
| Selector 오타    | Endpoints `<none>`   | Label / Selector 수정          |
| TargetPort 불일치 | `Connection refused` | TargetPort 일치시키기             |
| Pod 비정상        | No response          | Pod 상태 점검, ReadinessProbe 수정 |
| 방화벽 차단         | NodePort 접속 불가       | `ufw allow <port>/tcp`       |
| CNI 미등록        | Service 미동작          | `cilium service list` 확인     |


---

> **🧩 핵심 정리:** \
> NodePort 문제의 80%는 **Selector 불일치 또는 Port** 불일치입니다. \
> 나머지 20%는 **방화벽 / CNI / ReadinessProbe** 문제입니다.