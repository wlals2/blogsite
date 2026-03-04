---
title: "[Falco/Wazuh 시리즈 #8] Wazuh SIEM — K8s 컨테이너에서 OS 직접 설치로 전환한 이유"
date: 2026-02-24T10:00:00+09:00
categories:
  - Security
tags: ["wazuh", "kubernetes", "siem", "troubleshooting", "architecture"]
summary: "Wazuh를 K8s에서 운영하며 겪은 불안정성 문제와, OS 직접 설치로 전환하면서 해결한 과정을 정리한다."
showtoc: true
tocopen: true
draft: false
series: ["Falco/Wazuh 시리즈"]
---
## 배경: K8s Wazuh의 불안정성

Wazuh를 Kubernetes에 Kustomize로 배포하여 운영했다. Manager(master 1 + worker 2), Indexer 3대, Dashboard, Agent DaemonSet 5대 — 총 12개 Pod가 security namespace에서 실행되었다.

문제는 **안정성**이었다. Wazuh Manager는 StatefulSet으로 동작하는데, Pod 재시작 시마다 Agent 연결이 끊기고, Indexer 클러스터가 split-brain에 빠지는 상황이 반복되었다. 특히 Worker 노드의 리소스 부족으로 Wazuh Pod가 Evicted되면 전체 SIEM이 마비되었다.

```
# K8s Wazuh 리소스 사용량
kubectl get pvc -n security
# 6개 PVC, 총 ~71Gi 점유
# Manager Master: 40Gi, Workers: 500Mi × 2, Indexer: 10Gi × 3
```

결국 SIEM이 안정적이지 않으면 보안 모니터링 자체가 무의미하다는 판단으로, **OS 직접 설치(All-in-One)**로 전환하기로 했다.

---

## 전환 전 아키텍처 비교

| 항목 | K8s 컨테이너 (Before) | OS 직접 설치 (After) |
|------|----------------------|---------------------|
| Manager | StatefulSet (master 1 + worker 2) | 단일 프로세스 (k8s-cp) |
| Indexer | StatefulSet 3 replica | 단일 인스턴스 |
| Dashboard | Deployment 1 replica | 단일 인스턴스 |
| Agent | DaemonSet 5대 | OS 패키지 4대 (Worker) |
| 데이터 | PVC 71Gi (Worker 분산) | SSD + HDD symlink |
| 안정성 | Pod 재시작 시 장애 | 프로세스 수준, systemd 관리 |
| 리소스 | Worker 노드 부담 | Control Plane 집중 |

---

## 과정 1: HDD 데이터 정책 수립

k8s-cp 서버에는 SSD(219G)와 HDD(916G, 1TB)가 있다. Wazuh Indexer 데이터가 수십 GB로 커질 수 있으므로, 설치 전에 HDD 활용 정책을 먼저 수립했다.

### HDD 디렉터리 구조

```
/mnt/data/                          # HDD 마운트 포인트 (916G)
├── archive/                        # 홈 아카이브 (14G)
├── backups/{configs,etcd,mysql}    # 백업 (향후 사용)
├── ci/actions-runner/              # GitHub Actions Runner (4.8G)
├── k8s/local-path-provisioner/     # K8s PVC 데이터 (1.6G)
├── logs/journal/                   # systemd journal (4.1G)
└── wazuh/
    ├── indexer/                     # Wazuh Indexer 데이터
    └── backups/                    # Wazuh 백업 (향후 사용)
```

### Symlink 전략

SSD의 대용량 디렉터리를 HDD로 이동하고, 원래 경로에 symlink를 생성했다.

```bash
# 예시: archive 이동
sudo mv /home/jimin/archive /mnt/data/archive
ln -s /mnt/data/archive /home/jimin/archive

# 결과 확인
ls -la /home/jimin/archive
# lrwxrwxrwx 1 jimin jimin 18 Feb 24 /home/jimin/archive -> /mnt/data/archive
```

| 이동 대상 | 크기 | SSD → HDD |
|-----------|------|-----------|
| archive | 14G | `/home/jimin/archive` → `/mnt/data/archive` |
| journal | 4.1G | `/var/log/journal` → `/mnt/data/logs/journal` |
| local-path-provisioner | 1.6G | `/opt/local-path-provisioner` → `/mnt/data/k8s/local-path-provisioner` |
| actions-runner | 4.8G | `~/actions-runner` → `/mnt/data/ci/actions-runner` |

| 항목 | Before | After |
|------|--------|-------|
| SSD 사용량 | 116G (56%) | 105G (48%) |
| 절약 | - | 11G |

---

## 과정 2: Wazuh 설치와 Unix Socket 문제

k8s-cp에 이미 Wazuh v4.14.3 All-in-One이 설치되어 있었다(이전 테스트에서). Manager, Indexer, Dashboard, Filebeat 모두 동작 중이었다.

### Unix Socket은 HDD symlink를 넘지 못한다

Wazuh의 `/var/ossec/queue`와 `/var/ossec/logs`를 HDD로 옮기려 했으나 실패했다.

```bash
sudo mv /var/ossec/queue /mnt/data/wazuh/queue
sudo ln -s /mnt/data/wazuh/queue /var/ossec/queue
sudo /var/ossec/bin/wazuh-control start
# 출력:
# wazuh-db: CRITICAL: Unable to bind to socket 'queue/db/wdb': 'No such file or directory'
```

**원인**: Wazuh 내부 프로세스들은 `queue/db/wdb` 같은 Unix domain socket으로 IPC 통신한다. Unix socket은 파일시스템 경로에 바인딩되는데, symlink를 통해 다른 파일시스템(HDD)으로 연결하면 socket 생성이 실패한다.

**해결**: `/var/ossec/queue`와 `/var/ossec/logs`는 SSD에 유지하고, Unix socket이 없는 **Indexer 데이터만** HDD로 이동했다.

```bash
sudo mv /var/lib/wazuh-indexer /mnt/data/wazuh/indexer
sudo ln -s /mnt/data/wazuh/indexer /var/lib/wazuh-indexer
```

| 경로 | 위치 | 이유 |
|------|------|------|
| `/var/ossec/queue` (8.3G) | SSD | Unix socket 사용 (IPC) |
| `/var/ossec/logs` | SSD | analysisd가 socket 생성 |
| `/var/lib/wazuh-indexer` | HDD | 순수 데이터 파일 (socket 없음) |

---

## 과정 3: systemd 타임아웃 문제

Wazuh Manager를 `systemctl start`로 시작하면 실패했다.

```bash
sudo systemctl start wazuh-manager
# 출력:
# Job for wazuh-manager.service failed: start operation timed out.
```

**원인**: systemd 기본 `TimeoutSec=45`초인데, Wazuh Manager가 모든 프로세스(analysisd, remoted, logcollector 등)를 순차 시작하는 데 45초 이상 소요된다.

**해결**: systemd override로 타임아웃 연장.

```bash
sudo mkdir -p /etc/systemd/system/wazuh-manager.service.d
sudo tee /etc/systemd/system/wazuh-manager.service.d/timeout.conf <<'EOF'
[Service]
# Why: Wazuh 시작 시 45초 초과 → systemd 실패 처리 방지
TimeoutStartSec=180
TimeoutStopSec=120
EOF
sudo systemctl daemon-reload
sudo systemctl start wazuh-manager
```

```bash
sudo systemctl status wazuh-manager
# 출력:
# Active: active (running)
# Process: ExecStart (code=exited, status=0/SUCCESS)
# Tasks: 312
# Memory: 269.0M
```

주의: `wazuh-control start`를 직접 실행하면 프로세스는 시작되지만 systemd가 추적하지 못한다. 반드시 `systemctl`을 통해 관리해야 한다.

---

## 과정 4: Worker Agent 설치 (nsenter 방식)

Worker 4대에 Wazuh Agent를 설치해야 했다. 문제는 Worker 1/2/3의 `k8s` 사용자가 sudo 비밀번호를 요구한다는 점이었다.

**해결**: K8s Job에서 `nsenter`로 호스트에 직접 패키지를 설치했다.

```yaml
apiVersion: batch/v1
kind: Job
metadata:
  name: install-wazuh-agent-worker1
spec:
  template:
    spec:
      nodeName: k8s-worker1
      hostPID: true
      hostNetwork: true
      containers:
      - name: installer
        image: ubuntu:22.04
        command: ["nsenter", "-t", "1", "-m", "-u", "-i", "-n", "-p", "--", "bash", "-c", "...설치 스크립트..."]
        securityContext:
          privileged: true
      restartPolicy: Never
```

`nsenter -t 1 -m -u -i -n -p`는 PID 1(호스트의 init 프로세스)의 모든 namespace에 진입한다. 이를 통해 호스트 OS에서 직접 `apt-get install wazuh-agent`를 실행할 수 있다.

```bash
sudo /var/ossec/bin/agent_control -l
# 출력:
# ID: 000, Name: k8s-cp (server), Active/Local
# ID: 001, Name: k8s-worker4, Active
# ID: 002, Name: k8s-worker1, Active
# ID: 003, Name: k8s-worker2, Active
# ID: 004, Name: k8s-worker3, Active
```

---

## 과정 5: K8s Wazuh 정리

OS Wazuh가 안정적으로 동작하는 것을 확인한 후, K8s Wazuh를 정리했다.

```bash
# ArgoCD Application 삭제 (Git에서 wazuh-app.yaml 제거)
cd ~/k8s-manifests
git rm argocd/wazuh-app.yaml
git rm -rf apps/security/wazuh/
git commit -m "remove: K8s Wazuh manifests 삭제 (OS 직접 설치로 전환)"
git push
# root-app(selfHeal=true)이 wazuh Application을 prune → 하위 리소스 자동 삭제
```

| 항목 | Before | After |
|------|--------|-------|
| Wazuh Pod | 12개 | 0개 |
| PVC | 6개 (~71Gi) | 0개 |
| LoadBalancer IP | 3개 (201, 202, 203) | 0개 |
| ArgoCD Application | wazuh (Synced) | 삭제 |

---

## 성과

| 항목 | K8s 컨테이너 (Before) | OS 직접 설치 (After) |
|------|----------------------|---------------------|
| 안정성 | Pod Eviction/재시작 시 장애 | systemd 관리, 프로세스 수준 안정 |
| SSD 사용량 | 116G (PVC 71G 별도) | 105G (11G 절약) |
| Agent 연결 | DaemonSet 재배포 시 끊김 | OS 패키지, 독립적 동작 |
| 관리 복잡도 | 12 Pod + 6 PVC + 3 Service | 1 systemd unit + 4 Agent |
| systemd timeout | 45초 (시작 실패) | 180초 (안정적 시작) |
| Dashboard 접근 | VirtualService + MetalLB | `https://192.168.1.187` 직접 접근 |

---

## 다음 단계

- Falco → Wazuh 로그 수집 파이프라인 구성 (다음 글)
- Grafana 보안 대시보드 구축 (Wazuh 데이터 시각화)
- Discord 알람 연동 (Level 10+ 이벤트)
