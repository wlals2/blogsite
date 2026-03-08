---
title: "[Falco/Wazuh 시리즈 #5] Falco + Wazuh 공격 탐지 파이프라인"
date: 2026-02-24T14:00:00+09:00
categories:
  - "study"
  - "Security"
tags: ["falco", "wazuh", "siem", "attack-detection", "mitre-attack", "defense-in-depth"]
summary: "Falco의 컨테이너 런타임 이벤트를 Wazuh SIEM으로 수집하는 파이프라인을 구축하고, 5가지 공격 시나리오로 탐지 성능을 검증한 과정을 정리한다."
showtoc: true
tocopen: true
draft: false
series: ["Falco/Wazuh 시리즈"]
---
## 배경: Falco와 Wazuh가 따로 노는 문제

Falco(컨테이너 런타임 보안)와 Wazuh(SIEM)가 각각 독립적으로 동작하고 있었다. Falco는 컨테이너 내부의 의심스러운 행위를 탐지하고, Wazuh는 호스트 레벨의 보안 이벤트를 수집한다. 문제는 두 시스템이 **연결되지 않아** Falco 알림을 Wazuh에서 통합 분석할 수 없었다는 점이다.

| 도구 | 역할 | 탐지 범위 |
|------|------|----------|
| Falco | 컨테이너 런타임 보안 | syscall 기반 — 프로세스 실행, 파일 접근, 네트워크 연결 |
| Wazuh | 호스트 SIEM | 로그 분석, 파일 무결성, 취약점 스캔, 규정 준수 |

목표는 Falco → Wazuh 로그 파이프라인을 구축하고, 실제 공격 시나리오로 탐지 성능을 검증하는 것이었다.

---

## 파이프라인 아키텍처

```
컨테이너 내부 → Falco(eBPF) → JSON 로그 → 노드 /var/log/containers/
    → Wazuh Agent(logcollector) → Wazuh Manager(analysisd)
    → Decoder(JSON 파싱) → Rules(탐지/에스컬레이션) → Dashboard
```

### 구성 요소별 역할

**1. Falco (DaemonSet)**: 모든 노드에서 eBPF로 syscall을 모니터링하고, 커스텀 룰에 매칭되면 JSON 로그를 `/var/log/containers/falco-*.log`에 기록한다.

**2. Wazuh Agent (Worker 4대)**: `logcollector` 모듈이 Falco 컨테이너 로그 파일을 실시간으로 읽어 Manager로 전송한다.

**3. Wazuh Manager (k8s-cp)**: `analysisd`가 수신한 로그를 Decoder로 파싱하고, Rules로 위협 수준을 판정한다.

---

## 과정 1: Wazuh Decoder 작성

Falco 로그는 Kubernetes 컨테이너 로그 포맷으로 저장된다. `stdout F` 프리픽스 뒤에 JSON이 오는 구조다.

```
2026-02-24T05:30:12.345Z stdout F {"output":"CRITICAL: Java...","priority":"Critical","rule":"Java Process Spawning Shell",...}
```

이 포맷을 파싱하는 Decoder를 작성했다.

```xml
<!-- /var/ossec/etc/decoders/falco-decoder.xml -->
<decoder name="falco">
  <prematch>stdout F</prematch>
</decoder>

<decoder name="falco-json">
  <parent>falco</parent>
  <plugin_decoder>JSON_Decoder</plugin_decoder>
  <after_prematch>stdout F </after_prematch>
</decoder>
```

**동작 원리**:
1. `prematch: stdout F` — 컨테이너 로그 포맷 식별
2. `after_prematch` — `stdout F ` 이후의 JSON 문자열을 추출
3. `JSON_Decoder` — JSON 필드를 Wazuh 내부 변수로 매핑 (`priority`, `rule`, `output` 등)

---

## 과정 2: Wazuh Rules 작성 (MITRE ATT&CK 매핑)

Falco 이벤트를 Wazuh에서 탐지하고 위협 수준을 분류하는 커스텀 룰을 작성했다. 각 룰은 MITRE ATT&CK 프레임워크에 매핑된다.

```xml
<!-- /var/ossec/etc/rules/falco-rules.xml -->
<!-- 기본 Falco 이벤트 (Level 3) -->
<rule id="100100" level="3">
  <decoded_as>falco</decoded_as>
  <description>Falco Alert: $(priority)</description>
</rule>

<!-- Credential Access: /etc/shadow 읽기 (Level 10) -->
<rule id="100120" level="10">
  <if_sid>100100</if_sid>
  <field name="rule">Read sensitive file untrusted</field>
  <description>MITRE T1003 Credential Access - 민감 파일 읽기 시도</description>
  <mitre><id>T1003</id></mitre>
</rule>

<!-- Package Manager 실행 (Level 7) -->
<rule id="100160" level="7">
  <if_sid>100100</if_sid>
  <field name="rule">Launch Package Management Process in Container</field>
  <description>MITRE T1059 - 컨테이너 내 패키지 매니저 실행</description>
  <mitre><id>T1059</id></mitre>
</rule>

<!-- Package Manager 반복 실행 → 에스컬레이션 (Level 12) -->
<rule id="100161" level="12" frequency="3" timeframe="60">
  <if_matched_sid>100160</if_matched_sid>
  <description>ESCALATION: 패키지 매니저 반복 실행 (공격 도구 설치 의심)</description>
  <mitre><id>T1059</id></mitre>
</rule>

<!-- Binary Directory 쓰기 (Level 10) -->
<rule id="100170" level="10">
  <if_sid>100100</if_sid>
  <field name="rule">Write to Binary Dir</field>
  <description>MITRE T1543 Persistence - 바이너리 디렉터리 쓰기</description>
  <mitre><id>T1543</id></mitre>
</rule>

<!-- Binary Directory 쓰기 반복 → 에스컬레이션 (Level 15) -->
<rule id="100171" level="15" frequency="2" timeframe="120">
  <if_matched_sid>100170</if_matched_sid>
  <description>CRITICAL ESCALATION: 바이너리 디렉터리 반복 쓰기 (백도어 설치)</description>
  <mitre><id>T1543</id></mitre>
</rule>
```

**에스컬레이션 룰 설계**: 단일 이벤트는 오탐일 수 있지만, 같은 행위가 짧은 시간 내 반복되면 공격 가능성이 높다. `frequency`와 `timeframe`으로 반복 패턴을 탐지하여 Level을 올린다.

| Level | 의미 | 예시 |
|-------|------|------|
| 7 | 주의 | 패키지 매니저 1회 실행 |
| 10 | 위험 | /etc/shadow 읽기, /bin/ 쓰기 |
| 12 | 에스컬레이션 | 패키지 매니저 60초 내 3회 |
| 15 | 심각 | /bin/ 쓰기 120초 내 2회 |

---

## 과정 3: Agent 설정 배포

Worker 4대의 Wazuh Agent가 Falco 로그를 수집하도록 설정해야 했다. 각 Agent에 SSH 접속하여 개별 설정하는 대신, **Manager의 shared agent.conf**를 이용했다.

```xml
<!-- /var/ossec/etc/shared/default/agent.conf -->
<agent_config>
  <localfile>
    <log_format>syslog</log_format>
    <location>/var/log/containers/falco-*_falco_falco-*.log</location>
  </localfile>
</agent_config>
```

Manager가 이 파일을 default 그룹의 모든 Agent에 자동 배포한다. Agent는 주기적으로(기본 10분) Manager와 동기화하여 새 설정을 가져온다.

```bash
# Agent 설정 수신 확인 (Worker에서)
sudo cat /var/ossec/etc/shared/agent.conf
# 출력:
# <agent_config>
#   <localfile>
#     <log_format>syslog</log_format>
#     <location>/var/log/containers/falco-*_falco_falco-*.log</location>
#   </localfile>
# </agent_config>
```

주의: Agent가 설정을 수신하기 전에 발생한 Falco 이벤트는 탐지되지 않는다. `logcollector`는 파일의 끝(tail)부터 읽기 때문이다.

---

## 과정 4: 공격 시나리오 실행

Worker 1~4에 ubuntu:22.04 공격 Pod를 배포하고, 5가지 공격 시나리오를 실행했다.

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: attack-worker1
  namespace: default
spec:
  nodeName: k8s-worker1
  containers:
  - name: attacker
    image: ubuntu:22.04
    command: ["sleep", "600"]
```

### 시나리오 1: Credential Access (T1003)

```bash
kubectl exec -it attack-worker1 -- cat /etc/shadow
# 출력:
# root:*:19766:0:99999:7:::
# daemon:*:19766:0:99999:7:::
# ...
```

공격자가 컨테이너 내에서 `/etc/shadow`(패스워드 해시)를 읽는 행위. 실제 공격에서 크레덴셜 탈취의 첫 단계다.

### 시나리오 2: Package Manager 실행 (T1059)

```bash
kubectl exec -it attack-worker3 -- apt-get update
kubectl exec -it attack-worker3 -- apt-get update
kubectl exec -it attack-worker3 -- apt-get update
kubectl exec -it attack-worker3 -- apt-get update
# 4회 연속 실행 → 에스컬레이션 룰 트리거
```

공격자가 공격 도구(netcat, nmap 등)를 설치하기 위해 패키지 매니저를 실행하는 패턴.

### 시나리오 3: Binary Directory 쓰기 (T1543)

```bash
kubectl exec -it attack-worker4 -- touch /bin/backdoor
kubectl exec -it attack-worker4 -- touch /usr/bin/rootkit
kubectl exec -it attack-worker4 -- touch /sbin/malware
# 시스템 바이너리 디렉터리에 파일 생성 → 백도어 설치 시도
```

### 시나리오 4: Shell Spawn (T1059)

```bash
kubectl exec -it attack-worker2 -- bash
# 컨테이너 내부에서 쉘 실행 (RCE 탐지)
```

### 시나리오 5: 에스컬레이션 트리거

모든 공격을 짧은 시간 내 반복 실행하여, `frequency` 기반 에스컬레이션 룰이 동작하는지 검증했다.

---

## 과정 5: 오탐 분석 및 Falco 튜닝

공격 탐지 전에 먼저 해결해야 할 문제가 있었다. Wazuh Dashboard에서 **8,958건의 Outbound Connection 이벤트**가 쌓여 있었고, 대부분이 오탐이었다.

### 오탐 분석 결과

```bash
# Wazuh API로 R100111 이벤트 분석
# 출력:
# ai-response-service: 5,141건 (66%) — Claude API 호출
# coredns: 1,720건 (22%) — DNS 해석
# cadvisor: 445건 (6%) — 메트릭 수집
# kube-apiserver: 174건 (2%) — API 호출
```

| 프로세스 | 건수 | 비율 | 판정 |
|----------|------|------|------|
| ai-response-service | 5,141 | 66% | 정상 (Claude API 호출) |
| coredns | 1,720 | 22% | 정상 (DNS 해석) |
| cadvisor | 445 | 6% | 정상 (메트릭 수집) |
| kube-apiserver | 174 | 2% | 정상 (K8s API) |
| 기타 | 478 | 4% | 분석 필요 |

### Falco 룰 튜닝

Outbound Connection 룰에 시스템 프로세스 예외를 추가했다.

```yaml
# values.yaml - Unexpected Outbound Connection 룰
- rule: Unexpected Outbound Connection
  condition: >
    outbound and container and
    fd.type in (ipv4, ipv6) and
    not fd.lport in (80, 443, 8080, 3306, 53) and
    not fd.sip in ("127.0.0.1", "::1") and
    not proc.name in (coredns, kube-apiserver, cadvisor,
      grafana-server, node_exporter, mysqld_exporter,
      speaker, metrics-server, argocd-repo-serv,
      vpa-admission-co) and
    not container.name startswith ai-response
  priority: NOTICE
```

**튜닝 원칙**: `proc.name`(프로세스 이름) 기반 예외만 추가했다. namespace 전체를 예외 처리하면(`k8s.ns.name not in kube-system`) kube-system에서 발생하는 실제 공격을 놓칠 수 있기 때문이다.

| 튜닝 방식 | 안전성 | 이유 |
|-----------|--------|------|
| `proc.name` 예외 | 안전 | 특정 프로세스만 제외, 새 프로세스는 탐지 |
| `k8s.ns.name` 예외 | 위험 | namespace 전체 제외 → 해당 namespace 공격 미탐지 |
| `curl/wget` 예외 | 위험 | 공격자의 주요 도구 → 절대 예외 불가 |

---

## 과정 6: Defense in Depth (다층 방어)

오탐 튜닝이 실제 공격 탐지를 놓치지 않는지 검증했다. 핵심은 **5개 Falco 룰이 공격의 서로 다른 단계를 탐지**한다는 점이다.

```
공격 Kill Chain:
  1. Initial Access (침입)
  2. Execution     ← Shell Spawn 룰 탐지
  3. Persistence   ← Binary Dir Write 룰 탐지
  4. Credential Access ← Sensitive File Read 룰 탐지
  5. Installation  ← Package Manager 룰 탐지
  6. Exfiltration  ← Outbound Connection 룰 탐지 (튜닝 적용)
```

Outbound Connection 룰에 예외를 추가하더라도, **공격 초기 단계(2~5단계)의 4개 룰에는 예외가 없다**. 공격자가 Outbound Connection을 시도하기 전에 반드시 거쳐야 하는 쉘 실행, 도구 설치, 크레덴셜 접근 단계에서 탐지된다.

| 룰 | 탐지 단계 | 예외 유무 | 비고 |
|----|----------|----------|------|
| Shell Spawn | Execution | 예외 없음 | Java→Shell은 100% 비정상 |
| Package Manager | Installation | 예외 없음 | 런타임 설치는 100% 비정상 |
| Binary Dir Write | Persistence | 예외 없음 | /bin/ 쓰기는 100% 비정상 |
| Credential Access | Credential Access | 예외 없음 | /etc/shadow 읽기 탐지 |
| Outbound Connection | Exfiltration | 시스템 프로세스 예외 | 마지막 방어선 |

---

## 탐지 성과

5가지 공격 시나리오 실행 후, Wazuh에서 총 **396건의 공격 이벤트**를 탐지했다.

```bash
sudo cat /var/ossec/logs/alerts/alerts.json | \
  jq -r 'select(.rule.id | startswith("1001")) | "\(.rule.id) \(.rule.level) \(.agent.name)"' | \
  sort | uniq -c | sort -rn
# 출력:
# 377  100120 10  (Credential Access — 4개 노드)
# 8    100160 7   (Package Manager — 2개 노드)
# 2    100161 12  (Package Manager 에스컬레이션)
# 4    100170 10  (Binary Dir Write — 2개 노드)
# 5    100171 15  (Binary Dir Write 에스컬레이션)
```

### 노드별 탐지 분포

| 룰 ID | Level | 공격 유형 | k8s-cp | worker1 | worker2 | worker3 | worker4 |
|--------|-------|----------|--------|---------|---------|---------|---------|
| 100120 | 10 | Credential Access | 78 | 105 | 97 | 97 | - |
| 100160 | 7 | Package Manager | 2 | - | - | 6 | - |
| 100161 | 12 | PM 에스컬레이션 | - | - | - | 2 | - |
| 100170 | 10 | Binary Dir Write | 2 | - | - | - | 2 |
| 100171 | 15 | BD 에스컬레이션 | - | - | - | - | 5 |

### 에스컬레이션 룰 검증

- **R100161 (Level 12)**: worker3에서 `apt-get update` 4회 연속 실행 → 60초 내 3회 초과 → 에스컬레이션 발동 (2건)
- **R100171 (Level 15)**: worker4에서 `/bin/backdoor`, `/usr/bin/rootkit`, `/sbin/malware` 생성 → 120초 내 2회 초과 → 에스컬레이션 발동 (5건)

---

## 성과

| 항목 | Before | After |
|------|--------|-------|
| Falco-Wazuh 연동 | 미연결 (각각 독립 동작) | 파이프라인 구축 완료 |
| 공격 탐지 | Falco 알림만 (Loki/Sidekick) | Wazuh SIEM 통합 분석 + MITRE 매핑 |
| 탐지 룰 | Falco 기본 룰 4개 | Wazuh 커스텀 룰 8개 (에스컬레이션 포함) |
| 공격 검증 | 미검증 | 5가지 시나리오, 396건 탐지 확인 |
| 오탐 비율 | Outbound Connection 8,958건 (96% 오탐) | 시스템 프로세스 예외 적용 후 노이즈 제거 |
| 에스컬레이션 | 없음 | Level 7→12, Level 10→15 자동 상승 |
| Kill Chain 커버리지 | Execution만 (Falco) | Execution → Persistence → Credential → Installation → Exfiltration (5단계) |

---

## 다음 단계

- Grafana 보안 대시보드 구축 (Wazuh 데이터 시각화)
- Discord 알람 연동 (Level 10+ 이벤트 즉시 알림)
- Wazuh Active Response 구성 (Level 15 이벤트 자동 차단)
