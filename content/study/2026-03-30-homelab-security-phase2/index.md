---
title: "홈랩 DevSecOps Phase 2: CIS Benchmark부터 Falco·Wazuh XDR까지"
date: 2026-03-30T10:00:00+09:00
draft: false
categories: ["study", "Security"]
summary: "K8s 홈랩에 kube-bench CIS 개선(FAIL 5→2), Falco 6차 오탐 튜닝, Wazuh 교차 소스 상관분석 구축으로 탐지-대응 자동화 파이프라인을 완성했다."
---

## 1. 배경 (Why)

Phase 1에서 CI/CD 파이프라인과 Kyverno 정책 게이트를 구축해 코드 품질과 컨테이너 정책은 어느 정도 자리를 잡았다. 그런데 막상 배포가 완료된 이후가 문제였다.

"배포가 끝난 컨테이너 안에서 무언가 이상한 일이 생겨도 알 방법이 없었다."

이미지 빌드 시점의 취약점은 Trivy가 잡아준다. Kyverno는 잘못된 정책의 배포를 막아준다. 그런데 이미 떠 있는 컨테이너에서 누군가 `/bin/bash`를 실행하거나, Java 프로세스가 외부 서버에 역접속을 시도하거나, `kubectl exec`로 접속해 파일을 무단으로 읽어가도 — 아무도 몰랐다.

Phase 2의 목표는 이 공백을 메우는 것이었다.

- **CIS Benchmark(kube-bench)** 로 클러스터 기준선을 측정하고 개선
- **Falco** 로 런타임 이상 행위를 실시간 탐지
- **Wazuh** 로 여러 소스를 묶어 "공격 스토리"를 구성하는 상관 분석까지

탐지 → 대응 자동화 파이프라인을 완성하는 것이 Phase 2의 완료 기준이었다.

---

## 2. 보안 스택 개요 (What)

### kube-bench

CIS Kubernetes Benchmark를 자동화된 체크리스트로 실행한다. "지금 클러스터가 업계 기준에 맞는가?"를 PASS / FAIL / WARN 수치로 보여준다. 수치 자체보다 FAIL 항목 하나하나가 왜 위험한지를 이해하는 것이 핵심이다.

### Falco

eBPF 기반 런타임 IDS(침입 탐지 시스템)다. 컨테이너 내부의 시스템 콜을 실시간으로 감시하여 비정상 행위를 탐지한다. 예를 들어 컨테이너 안에서 쉘이 실행되거나, 민감한 파일(`/etc/passwd`, `/etc/shadow`)을 읽거나, 예상치 못한 포트로 외부 연결을 시도하면 즉시 알림을 발생시킨다.

### Wazuh

SIEM(보안 정보 및 이벤트 관리) 플랫폼이다. 여러 소스(K8s Audit Log, Falco, Cilium Hubble)의 이벤트를 수집·상관분석하여 단순 탐지를 넘어 "공격 스토리"를 구성한다. Falco가 "지금 이 컨테이너에서 쉘이 실행됐다"고 알려준다면, Wazuh는 "그 직전에 누가 kubectl exec를 했고, 이후에 외부 통신이 차단됐다"는 전체 그림을 그려준다.

---

## 3. 구축 과정 (How)

### 3-1. kube-bench로 기준선 측정

kube-bench는 바이너리를 직접 실행하거나 Job으로 클러스터에서 실행할 수 있다. 홈랩에서는 컨트롤 플레인 노드에 직접 바이너리를 놓고 실행했다.

```bash
sudo /tmp/kube-bench run --targets master --config-dir /tmp/cfg --version 1.29 2>&1 | grep "^\[FAIL\]"
# 출력: 5개 FAIL 항목 확인
```

처음 실행했을 때 FAIL이 5개였다. 각 항목이 왜 위험한지를 먼저 파악하고 우선순위를 정했다.

**주요 개선 항목**:

**1.2.16~1.2.19 — Audit Log 미활성화**: kube-apiserver가 어떤 API 호출을 받았는지 기록을 남기지 않는 상태였다. `kubectl exec`, `kubectl get secrets` 같은 민감한 동작도 흔적 없이 사라졌다. `/etc/kubernetes/manifests/kube-apiserver.yaml`에 Audit Log 관련 플래그를 추가했다.

**kubelet TLS 클라이언트 인증서 검증 비활성화**: kubelet이 API 서버의 인증서를 검증하지 않으면 중간자 공격에 취약하다. kubelet config를 수정해 검증을 활성화했다.

**1.2.27/28 — etcd 암호화**: etcd에 저장된 Secret이 평문이었다. `encryption-config.yaml`을 적용해 AES-CBC 암호화를 활성화했다.

```yaml
# kube-apiserver.yaml 추가 항목
- --audit-log-path=/var/log/kubernetes/audit.log
- --audit-log-maxage=30
- --audit-log-maxbackup=10
- --audit-log-maxsize=100
- --audit-policy-file=/etc/kubernetes/audit-policy.yaml
- --encryption-provider-config=/etc/kubernetes/encryption-config.yaml
```

kube-apiserver는 Static Pod이므로 manifest를 수정하면 kubelet이 자동으로 재시작한다. 단, `/etc/kubernetes/manifests/` 안에 `.bak` 파일을 남기면 kubelet이 그것도 Pod으로 띄우려다 충돌이 생긴다. 백업 파일은 반드시 `~/backup/`에 보관해야 한다.

### 3-2. Falco IDS — 6차 오탐 튜닝

처음 Falco를 붙였을 때 하루 1,800건의 알림이 쏟아졌다. 대부분 정상 동작이었다.

**오탐의 원인**: Falco의 기본 규칙은 "무엇이든 비정상적인 것"을 잡도록 설계되어 있다. 그래서 Samba 파일 공유 데몬(smbd), 패키지 인증 도구(pkexec), Docker 이미지 빌드 도구(moby/buildkit)까지 탐지 대상이 됐다. 이것들은 모두 의도된 동작이지만 Falco 입장에서는 비정상으로 보였다.

6번의 튜닝 이터레이션을 거쳤다.

| 차수 | 날짜 | 주요 변경 | 결과 |
|------|------|---------|------|
| 1차 | 2026-03-05 | Rule 5 추가 (Talon 라벨링 검증) | 탐지→대응 1초 달성 |
| 2차 | 2026-03-06 | 오탐 근본 원인 분석 | 1,800건/일 원인 파악 |
| 3차 | 2026-03-06 | 종합 튜닝 | 오탐 급감 |
| 4차 | 2026-03-08 | smbd, pkexec, moby/buildkit 예외 | False Positive 근본 제거 |
| 5차 | 2026-03-08 | mysql-backup 컨테이너명 예외 | Rule 정확성 향상 |
| 6차 | 2026-03-09 | metallb speaker, longhorn 예외 최종화 | 운영 정밀도 달성 |

이 과정에서 두 가지 중요한 사실을 배웠다.

**첫째, `container.name`의 기준**: K8s에서 Falco가 보는 `container.name`은 `metadata.name`(Pod 이름)이 아니라 `spec.containers[].name`(컨테이너 이름)이다. CronJob으로 생성된 Pod는 매번 이름이 달라지지만, 컨테이너 이름은 고정이다. 처음에 Pod 이름으로 예외를 걸었다가 전혀 동작하지 않았고, 이 차이를 파악하는 데 꽤 시간이 걸렸다.

**둘째, K8s 밖 컨테이너 처리**: Docker로 직접 실행한 컨테이너(K8s가 관리하지 않는 것)는 `k8s.ns.name=<NA>`로 표시된다. namespace 필터로 K8s Pod만 골라내려 했던 예외 규칙들이 이 컨테이너에는 전혀 먹히지 않았다. 이런 경우에는 `container.image.repository`로 예외를 처리해야 한다.

```yaml
# Falco 커스텀 규칙 예시 (smbd 예외)
- rule: Outbound Connection to Unexpected Port (Custom)
  condition: >
    outbound and not fd.sport in (allowed_ports) and
    not proc.name in (smbd, pkexec) and
    k8s.ns.name != "<NA>"
  ...
```

### 3-3. Wazuh SIEM — 3개 소스 교차 상관

단일 도구의 탐지는 오탐이 많다. 두 개 이상의 독립 소스가 같은 시간대에 연관된 이상 징후를 보고하면, 실제 공격일 가능성이 급격히 높아진다. Wazuh는 이 교차 검증을 자동화한다.

**데이터 수집 체인**:

```
K8s Audit Log (/var/log/kubernetes/audit.log)
      ↓
Wazuh logcollector (Rule 100210, 100212)

Falco 알림
      ↓
Falcosidekick → Loki → Wazuh

Cilium Hubble 이벤트
      ↓
events.log → Wazuh logcollector
```

**교차 소스 상관 규칙**:

**Rule 100410**: `kubectl exec`로 컨테이너에 접속하고(K8s Audit Log에서 탐지), 그 안에서 Shell이 실행됐다면(Falco에서 탐지) → Level 12로 에스컬레이션.

단순히 `kubectl exec`를 쓰는 것은 운영 행위일 수 있다. Falco가 Shell 실행을 탐지하는 것도 충분히 오탐이 될 수 있다. 그런데 두 가지가 짧은 시간 안에 함께 발생했다면 — 누군가 exec로 들어가서 쉘을 실행한 것이다.

**Rule 100420**: Java 프로세스가 쉘을 실행하고(Falco에서 탐지), Cilium이 Egress를 DROPPED했다면 → Level 15로 에스컬레이션.

Log4Shell 같은 RCE 취약점이 터지면 Java 프로세스에서 쉘이 실행되고, 이후 외부 C2 서버로 연결을 시도한다. Cilium이 그 외부 연결을 차단한다. 이 두 이벤트가 연속으로 발생하면 사실상 공격으로 봐야 한다.

---

## 4. 성과 (Result)

### kube-bench Before/After

| 항목 | Phase 2 시작 | Phase 2 완료 | 변화 |
|------|------------|------------|------|
| PASS | 47 | 47 | - |
| FAIL | 5 | 2 | -3 (60% 감소) |
| WARN | 12 | 10 | -2 |

남은 FAIL 2개는 환경 충돌 때문에 적용 보류 중이다(MetalLB, Cilium CNI 관련). 무조건 수치를 0으로 만드는 것이 목표가 아니라, 각 항목이 내 환경에서 실제로 위험한지를 판단하는 것이 더 중요했다.

### Falco 오탐 Before/After

| 항목 | 초기 | 6차 튜닝 후 |
|------|------|-----------|
| 일일 알림 건수 | ~1,800건 | 운영 노이즈 최소화 |
| 튜닝 이터레이션 | 0 | 6차 |
| 탐지→대응 지연 | N/A | 1초 (Rule 5) |

### Talon E2E 검증

자동화 대응 도구인 Talon과 연동해 Java RCE 시나리오를 검증했다.

| 시나리오 | Java RCE → 자동 격리 |
|----------|---------------------|
| 탐지 (Falco) | 1초 |
| 격리 완료 (NetworkPolicy + Egress 차단) | 16초 |
| 결과 | Pass |

탐지부터 격리까지 16초. 사람이 개입하지 않아도 된다는 것이 핵심이다.

---

## 5. 다음 단계

Phase 3에서 구축한 교차 소스 상관 분석 Rule 100410/100420은 현재 운영 중이다.

**Phase 4(예정)**: Talon dryRun 해제 → 실제 프로덕션에서 자동 격리 활성화.

현재는 모든 Talon 대응이 `dryRun` 모드로만 실행된다. 실제 NetworkPolicy를 생성하지 않고 "만약 이렇게 했다면"만 로그로 남긴다. Phase 4에서 이를 해제하면 탐지 즉시 자동 격리가 현실이 된다.

dryRun을 유지하는 이유는 아직 오탐이 완전히 제거됐다는 확신이 없기 때문이다. 자동 격리가 정상 Pod에 잘못 발동되면 서비스 장애로 이어진다. 충분한 운영 데이터를 쌓은 뒤 해제할 예정이다.
