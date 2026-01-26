---
title: "Canary 배포에서 Pod가 Pending 되는 문제"
date: 2026-01-23
description: "TopologySpreadConstraints + Argo Rollouts Canary 배포에서 발생한 삽질 기록"
tags: ["kubernetes", "argo-rollouts", "canary", "troubleshooting"]
categories: ["study", "Kubernetes", "Troubleshooting"]
---

## 상황

블로그 이미지 업데이트하려고 `git push` 했는데 Canary pod가 Pending 상태로 멈췄다.

```bash
kubectl get pods -n blog-system -l app=web

NAME                   READY   STATUS    AGE
web-56956db584-q2xxg   2/2     Running   3h
web-56956db584-xtkc7   2/2     Running   4h
web-6c7c9fb85d-8lqpc   0/2     Pending   5m   # 새 canary pod
```

---

## 첫 번째 문제: Pod Pending

### 에러 메시지

```bash
kubectl describe pod web-6c7c9fb85d-8lqpc -n blog-system | tail -10
```

```
Events:
  Warning  FailedScheduling  default-scheduler
  0/3 nodes are available: 2 node(s) didn't match pod topology spread constraints
```

### 원인

현재 설정이 `DoNotSchedule`이었다.

```yaml
topologySpreadConstraints:
  - maxSkew: 1
    topologyKey: kubernetes.io/hostname
    whenUnsatisfiable: DoNotSchedule
```

| 노드 | 현재 Pod | Canary 배치 시 |
|------|----------|---------------|
| worker1 | stable-1 | skew = 2 (위반) |
| worker2 | stable-2 | skew = 2 (위반) |

maxSkew=1인데 어느 쪽에 추가해도 skew=2가 되어버림. `DoNotSchedule`이라 배치 자체를 거부.

### 해결

```yaml
topologySpreadConstraints:
  - maxSkew: 1
    topologyKey: kubernetes.io/hostname
    whenUnsatisfiable: ScheduleAnyway  # soft constraint
```

```bash
cd ~/k8s-manifests
git add blog-system/web-rollout.yaml
git commit -m "fix: Change topologySpreadConstraints to ScheduleAnyway"
git push
```

---

## 두 번째 문제: CrashLoopBackOff

Pending은 해결됐는데 이번엔 CrashLoopBackOff.

### 에러 메시지

```bash
kubectl logs web-7cc569d77c-hphf2 -n blog-system -c nginx --tail=5
```

```
nginx: [emerg] chown("/var/cache/nginx/client_temp", 101) failed
(1: Operation not permitted)
```

### 원인

SecurityContext에서 CHOWN capability를 drop 해놔서 nginx가 cache 디렉토리 소유권을 변경할 수 없었다.

| Capability | 용도 | 기존 설정 |
|------------|------|----------|
| NET_BIND_SERVICE | 80 포트 바인딩 | 추가됨 |
| CHOWN | cache 디렉토리 소유권 | 없음 |
| SETUID | worker process 전환 | 없음 |
| SETGID | worker process 그룹 | 없음 |

### 해결

```yaml
securityContext:
  capabilities:
    drop:
      - ALL
    add:
      - NET_BIND_SERVICE
      - CHOWN
      - SETUID
      - SETGID
```

```bash
git add blog-system/web-rollout.yaml
git commit -m "fix: Add CHOWN, SETUID, SETGID capabilities for nginx"
git push
```

---

## 결과

```bash
kubectl argo rollouts get rollout web -n blog-system

Name:            web
Status:          Healthy
Strategy:        Canary
Images:          ghcr.io/wlals2/blog-web:v47 (stable)
```

---

## 정리

### DoNotSchedule vs ScheduleAnyway

| 설정 | 동작 | 사용 시점 |
|------|------|----------|
| DoNotSchedule | 조건 불만족 시 배치 안 함 | 노드 수 충분할 때 |
| ScheduleAnyway | 조건 불만족해도 배치 | Canary 배포, 노드 제한적 |

### nginx 필수 capabilities

| Capability | 용도 |
|------------|------|
| NET_BIND_SERVICE | 1024 이하 포트 (80) |
| CHOWN | /var/cache/nginx 소유권 |
| SETUID | root → nginx 전환 |
| SETGID | 그룹 전환 |

---

## 관련 명령어

```bash
# Pod Events 확인
kubectl describe pod <pod-name> -n blog-system | tail -20

# Container 로그
kubectl logs <pod-name> -n blog-system -c nginx --tail=50

# Rollout 상태
kubectl argo rollouts get rollout web -n blog-system

# Canary → Stable 승격
kubectl argo rollouts promote web -n blog-system --full
```
