---
title: "MySQL CronJob이 Reverse Shell로 오탐된 이유 — Falco 예외 설계와 Wazuh 에스컬레이션 분리"
date: 2026-03-07T22:00:00+09:00
categories:
  - study
  - Security
tags: ["falco", "wazuh", "false-positive", "cronjob", "runtime-security", "defense-in-depth"]
series: ["Falco/Wazuh 시리즈"]
summary: "MySQL 백업 CronJob이 Wazuh Level 15 Reverse Shell 알림을 유발한 근본 원인을 분석하고, Falco container.name 기반 예외와 Wazuh 상관분석 룰 분리로 오탐 0건 + 미탐 방지를 달성한 과정"
showtoc: true
tocopen: true
draft: false
---

> **시리즈**: [Falco/Wazuh 시리즈]
> - 이전 글: [Falco 오탐 1,800건/일을 0건으로 — Wazuh SIEM 로그 분석 기반 체계적 튜닝](/study/2026-03-06-falco-false-positive-tuning/)
> - 다음 글: (예정)

---

## 1. 배경 — 튜닝 완료 하루 만에 다시 울린 Level 15

2026-03-06에 Falco 오탐 1,800건/일을 0건으로 줄이는 튜닝을 완료했다. Wazuh SIEM 로그를 기반으로 오탐 패턴을 분류하고, Falco Rule별 예외를 체계적으로 적용한 결과였다.

하루 뒤인 2026-03-07, Wazuh Dashboard에서 **Level 15 "Reverse shell detected"** 알림이 다시 발생했다.

- 발생 위치: k8s-worker4
- 원인 Pod: mysql-backup CronJob의 S3 업로드 컨테이너
- 알림 내용: 애플리케이션 컨테이너에서 shell이 실행됨

Level 15는 Wazuh의 최고 심각도 등급이다. 실제 공격인지, 아니면 정상 동작의 오탐인지 즉시 판별해야 했다.

결론부터 말하면, 두 가지 독립적인 문제가 겹쳐 있었다.

1. Falco 예외에 등록한 이름이 실제 컨테이너 이름과 달랐다
2. Wazuh 룰이 shell 실행만으로 Reverse Shell을 선언했다

---

## 2. 개념 — 두 가지 핵심 구분

### 2-A. container.name vs metadata.name

Falco에서 예외 처리할 때 사용하는 필드는 `container.name`이다. 이 값은 Kubernetes Pod spec의 `spec.containers[].name` 또는 `spec.initContainers[].name`에서 온다.

CronJob 리소스의 `metadata.name`과는 다른 값이다.

```yaml
# CronJob 정의 예시
apiVersion: batch/v1
kind: CronJob
metadata:
  name: mysql-backup          # metadata.name = "mysql-backup"
spec:
  jobTemplate:
    spec:
      template:
        spec:
          initContainers:
            - name: mysqldump  # container.name = "mysqldump"
          containers:
            - name: s3-upload  # container.name = "s3-upload"
```

`metadata.name`인 `mysql-backup`을 Falco 예외에 넣어도, Falco가 실제로 보는 `container.name`은 `mysqldump`와 `s3-upload`이므로 예외가 작동하지 않는다.

### 2-B. Shell Spawn과 Reverse Shell의 차이

Shell 실행(shell spawn)은 컨테이너 내부에서 `bash`, `sh` 등의 shell 프로세스가 생성되는 것이다. CronJob이 스크립트를 실행할 때, 헬스체크가 shell 명령을 호출할 때 등 정상적인 상황에서도 빈번하게 발생한다.

Reverse shell은 shell 실행에 더해 **외부 C2(Command and Control) 서버로의 네트워크 연결**이 함께 있어야 성립한다. 공격자가 내부 시스템의 shell을 외부에서 제어하기 위한 기법이다.

즉, shell spawn은 reverse shell의 필요조건이지 충분조건이 아니다. shell이 실행되었다고 곧바로 reverse shell이라고 판단하면 오탐이 발생한다.

---

## 3. 과정 — 두 가지 수정

### Fix A: Falco Rule 5 예외에 실제 container.name 등록

먼저 CronJob의 실제 컨테이너 이름을 확인했다.

```bash
kubectl get cronjob mysql-backup -n blog-system \
  -o jsonpath='{.spec.jobTemplate.spec.template.spec}' | python3 -m json.tool | grep '"name"'

# 출력:
#     "name": "mysqldump"
#     "name": "s3-upload"
```

`mysql-backup`이 아니라 `mysqldump`와 `s3-upload`가 실제 컨테이너 이름이었다. Falco values.yaml의 Rule 5 예외를 수정했다.

```yaml
# Before (틀린 예외 — metadata.name을 사용)
not container.name in (istio-init, otel-agent-download, mysql-backup, mysql, etcd-backup)

# After (올바른 예외 — spec.containers[].name을 사용)
not container.name in (istio-init, otel-agent-download, mysqldump, s3-upload, mysql, etcd-backup)
```

이 수정으로 MySQL 백업 CronJob의 shell spawn이 더 이상 Falco에서 탐지되지 않는다.

### Fix B: Wazuh 에스컬레이션 분리 — 단일 이벤트로 Level 15 금지

기존 Wazuh 룰은 shell spawn 단일 이벤트만으로 Level 15 "Reverse shell detected"를 발동했다. 이것은 shell spawn과 reverse shell을 동일시하는 과대평가다.

```xml
<!-- Before: Shell spawn 단일 이벤트 = Level 15 Reverse Shell -->
<rule id="100140" level="15">
  <if_sid>100100</if_sid>
  <field name="data.output">%Shell spawned%application container%</field>
  <description>Falco: Reverse shell detected in application container</description>
</rule>
```

이것을 두 단계로 분리했다.

```xml
<!-- After Step 1: Shell spawn 단일 이벤트 = Level 8 (주의) -->
<rule id="100140" level="8">
  <if_sid>100100</if_sid>
  <field name="data.output">%Shell spawned%application container%</field>
  <description>Falco: Shell spawned in application container (investigate)</description>
</rule>

<!-- After Step 2: 5분 내 2회 이상 반복 = Level 15 (위험) -->
<rule id="100142" level="15" frequency="2" timeframe="300">
  <if_matched_sid>100140</if_matched_sid>
  <same_source_ip />
  <description>Falco: Shell spawn + Outbound connection correlation (CRITICAL: Reverse Shell)</description>
  <mitre><id>T1059.004</id></mitre>
</rule>
```

변경의 핵심 논리는 다음과 같다.

- shell이 한 번 실행되는 것은 정상 동작일 수 있다 (Level 8로 기록, 조사 대상)
- 동일 출처에서 5분 내 2회 이상 반복되면 공격 가능성이 높다 (Level 15로 에스컬레이션)
- MITRE ATT&CK T1059.004 (Unix Shell) 태깅으로 위협 분류 명확화

---

## 4. 미탐 방지 — "예외를 넣으면 진짜 공격을 놓치지 않는가?"

Falco Rule 5에서 `mysqldump`와 `s3-upload` 컨테이너를 예외 처리하면, 이 컨테이너가 실제로 침해당했을 때 shell spawn을 탐지하지 못하는 것 아닌가? 이 질문에 대한 답은 Defense in Depth(심층 방어) 구조에 있다.

### 독립적인 탐지 레이어

Falco Rule 4(Unexpected Outbound Connection)는 Rule 5와 독립적으로 동작한다. Rule 4는 허용되지 않은 외부 네트워크 연결을 탐지한다.

진짜 reverse shell이 성립하려면 반드시 외부 C2 서버로의 네트워크 연결이 필요하다. 이 연결은 Rule 5의 예외와 무관하게 Rule 4에서 탐지된다.

```
공격 시나리오: mysqldump 컨테이너 침해 → shell 실행 + 외부 연결

  Rule 5 (Shell Spawn):     예외 처리됨 → 탐지 안 됨
  Rule 4 (Outbound Conn):   예외 아님   → 탐지됨 (독립 동작)

  결과: 공격은 Rule 4에서 잡힘
```

### 오탐 튜닝 3대 원칙

이 경험에서 도출한 원칙 세 가지다.

**1. Pinpoint Whitelisting**

예외 범위를 최소화한다. namespace나 pod 단위가 아닌 `container.name` 단위로 예외를 건다. `mysql-backup` CronJob 전체를 예외 처리하는 것이 아니라, `mysqldump`와 `s3-upload` 컨테이너만 특정한다.

**2. Defense in Depth Cross-Check**

Rule X에 예외를 추가할 때, 동일한 공격 시나리오를 Rule Y가 독립적으로 탐지할 수 있는지 확인한다. Rule 5(shell spawn) 예외 시 Rule 4(outbound connection)가 reverse shell의 네트워크 연결을 잡는지 검증했다.

**3. Correlation-based Escalation**

단일 이벤트만으로 최고 심각도를 부여하지 않는다. 반복 패턴이나 복수 룰의 상관관계가 확인될 때만 에스컬레이션한다. shell spawn 1회 = Level 8, 5분 내 2회 이상 반복 = Level 15.

---

## 5. 성과

| 항목 | Before | After |
|------|--------|-------|
| CronJob 오탐 (일) | 매일 2건 Level 15 | 0건 |
| Shell Spawn 기본 Level | 15 (Reverse Shell) | 8 (Shell Spawned) |
| Reverse Shell 탐지 방식 | 단일 이벤트 기반 (오탐 다수) | 상관분석 기반 (5분 내 2회 이상) |
| CronJob 미탐 위험 | - | 없음 (Rule 4 독립 탐지) |

핵심 개선 두 가지를 정리하면 다음과 같다.

- **오탐 제거**: `container.name` 필드를 정확히 사용하여 CronJob 예외가 실제로 동작하도록 수정
- **에스컬레이션 정밀화**: shell spawn 단일 이벤트와 reverse shell 상관분석을 분리하여 심각도 판단의 정확도 향상

---

## 6. 다음 단계

- Wazuh Dashboard에서 Level 8 Shell Spawn 이벤트 모니터링 추가
- Rule 4 + Rule 5 상관분석 고도화 검토 (Outbound Connection과 Shell Spawn이 동일 컨테이너에서 동시 발생 시 자동 에스컬레이션)
- Falco Talon 자동 대응 고도화 (시리즈 다음 글 예정)

---

> **시리즈**: [Falco/Wazuh 시리즈]
> - 이전 글: [Falco 오탐 1,800건/일을 0건으로 — Wazuh SIEM 로그 분석 기반 체계적 튜닝](/study/2026-03-06-falco-false-positive-tuning/)
> - 다음 글: (예정)
