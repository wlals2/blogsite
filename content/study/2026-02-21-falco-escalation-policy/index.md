---
title: "Falco 에스컬레이션 정책 - WARNING 반복 시 CRITICAL 자동 격상"
date: 2026-02-21T10:00:00+09:00
draft: false
categories:
  - study
  - Security
tags:
  - Falco
  - Wazuh
  - SIEM
  - Escalation
  - DevSecOps
  - Kubernetes
  - Runtime Security
summary: "단발성 오탐과 실제 공격을 구분하는 에스컬레이션 정책 설계 - WARNING 5분 내 3회 반복 시 CRITICAL 자동 격상"
---

## 배경 — 알람 피로(Alert Fatigue) 문제

Falco를 도입하고 나서 처음 마주친 문제는 **알람이 너무 많다**는 것이었다.

운영 중인 클러스터에서 `Package Manager in Container` 이벤트가 하루에도 수십 번 발생했다. 대부분은 정상적인 컨테이너 초기화 과정에서 발생한 오탐(False Positive)이었다. 그런데 실제 공격이 발생했을 때도 똑같은 Discord 알람이 온다.

> "이게 오탐인가, 아니면 진짜 공격인가?"

매번 알람을 받을 때마다 판단해야 했다. 이런 상태가 지속되면 **알람 피로(Alert Fatigue)**가 생기고, 결국 진짜 공격에 둔감해진다.

**에스컬레이션 정책**은 이 문제를 해결한다. 핵심 아이디어는 단순하다:

- **1회 발생**: 기록만 (오탐 가능성 있음)
- **5분 내 3회 반복**: CRITICAL 격상 + Discord 알람 (공격 패턴 확정)

단발성 이벤트는 무시하고, 반복 패턴에서만 알람을 발송함으로써 노이즈를 줄이고 실제 공격에 집중할 수 있다.

---

## 개념 — 에스컬레이션 정책이란

### 에스컬레이션(Escalation)이란?

보안에서 에스컬레이션은 **초기 탐지 수준을 넘어 더 높은 심각도로 격상하는 과정**이다.

```
WARNING (1회) → 기록만
WARNING × 3 / 5분 → CRITICAL (에스컬레이션)
```

왜 에스컬레이션이 필요한가?

1. **공격자는 반복한다**: 공격은 대부분 단발성이 아니다. 도구를 설치하고, 정찰하고, 횡이동하는 과정에서 동일한 TTP(Tactics, Techniques, Procedures)를 여러 번 실행한다.

2. **오탐은 1회성이 많다**: 정상적인 운영 프로세스는 동일 이벤트를 5분 내 3회 반복하는 경우가 드물다.

3. **컨텍스트가 중요하다**: 1번의 이상 징후보다 3번의 연속 이상 징후가 훨씬 강한 공격 신호다.

### 우리 시스템의 에스컬레이션 레이어

현재 구축된 아키텍처에서 에스컬레이션은 **두 레이어**에서 발생한다:

```
Layer 1: SIEM 에스컬레이션 (Wazuh)
  Falco 탐지 → Fluentd → Wazuh SIEM
  반복 패턴 분석 → Level 격상 → Discord 알람

Layer 2: IPS 에스컬레이션 (Talon) - Phase 2
  Falco 탐지 → Falcosidekick → Talon
  CRITICAL 이벤트 → Pod 네트워크 격리
```

이 글은 **Layer 1 (Wazuh 에스컬레이션)** 구현에 집중한다.

---

## 아키텍처 — 이벤트 흐름

에스컬레이션이 어떤 경로로 동작하는지 먼저 이해해야 한다.

```
Falco (eBPF 탐지)
  ↓ JSON 로그
Fluentd DaemonSet (로그 수집)
  ↓ Warning/Error/Critical만 필터링
  ↓ Syslog UDP
Wazuh Manager
  ↓ JSON 파싱 (decoded_as: json)
  ↓ Rule 매칭
  ↓ 빈도 분석 (frequency + timeframe)
  ↓ Level 격상 (에스컬레이션)
Discord (Level 10+ 알람)
```

**핵심 포인트**:
- Fluentd는 이미 Warning/Error/Critical만 필터링해서 전송 → Wazuh 부하 감소
- Wazuh Rule의 `frequency` + `timeframe` 속성이 에스컬레이션 핵심 메커니즘
- Discord 알람 임계값: Level 10 이상

---

## 구현 — Wazuh Rule 에스컬레이션

### Wazuh Rule 구조 이해

Wazuh Rule에는 빈도 기반 상관관계를 분석하는 기능이 있다.

```xml
<rule id="100081" level="12" frequency="3" timeframe="300">
  <if_matched_sid>100080</if_matched_sid>
  <description>...</description>
</rule>
```

각 속성의 의미:
- `frequency="3"`: 3회 이상 발생 시 트리거
- `timeframe="300"`: 300초(5분) 이내에 발생해야 함
- `if_matched_sid>100080`: Rule 100080이 이미 발생한 경우에만 매칭

즉, "Rule 100080이 5분 내 3회 이상 발생하면 → Rule 100081 실행 (Level 12)"

### 설계한 에스컬레이션 레벨

| 이벤트 | 1회 발생 | 반복 발생 | 격상 조건 |
|--------|---------|---------|---------|
| Outbound Connection | Level 8 (기록) | Level 12 (Discord) | 5분 × 3회 |
| Package Manager | Level 7 (기록) | Level 12 (Discord) | 5분 × 3회 |
| Binary Dir Write | Level 10 (Discord) | Level 15 (최우선) | 2분 × 2회 |
| Java RCE / Reverse Shell | Level 15 (즉시 Discord) | 에스컬레이션 불필요 | - |

**레벨별 임계값**:
- Level 1-9: Wazuh 기록만 (Discord 알람 없음)
- **Level 10+**: Discord 알람 발송
- **Level 12+**: Email + Discord (이메일 알람)
- **Level 15**: 최고 심각도 (즉각 대응 필요)

이 설계의 핵심 의도:
- **Binary Dir Write**는 단발성도 Level 10 (즉시 Discord): /bin 쓰기는 오탐 가능성이 낮고 매우 위험
- **Package Manager**는 단발성 Level 7 (기록만): 컨테이너 초기화 등 오탐 가능성 있음
- **반복 패턴**은 의도적 공격으로 판단 → Level 격상

### 구현한 Rule 상세

**[Package Manager 에스컬레이션]**

```xml
<!-- 1회 발생: Level 7, Discord 없음 -->
<rule id="100080" level="7">
  <if_sid>100001</if_sid>
  <field name="rule">Launch Package Management Process in Container</field>
  <description>Falco: Package manager ran inside container (WARNING)</description>
  <mitre>
    <id>T1059</id>
    <tactic>Execution</tactic>
  </mitre>
  <group>attack.execution,container,immutability,</group>
</rule>

<!-- 5분 내 3회 반복: Level 12 격상, Discord 알람 -->
<rule id="100081" level="12" frequency="3" timeframe="300">
  <if_matched_sid>100080</if_matched_sid>
  <description>Falco: Package manager ran 3+ times in 5min
    (Escalated: Active tool installation attack)</description>
  <mitre>
    <id>T1059</id>
    <tactic>Execution</tactic>
  </mitre>
  <group>attack.execution,container,immutability,correlation,escalation,</group>
</rule>
```

**[Binary Dir Write 에스컬레이션]**

```xml
<!-- 1회 발생: Level 10, Discord 알람 (즉시 확인 필요) -->
<rule id="100090" level="10">
  <if_sid>100001</if_sid>
  <field name="rule">Write to Binary Dir</field>
  <description>Falco: Binary directory write detected (ERROR)</description>
  <mitre>
    <id>T1543</id>
    <tactic>Persistence</tactic>
  </mitre>
  <group>attack.persistence,container,rootkit,</group>
</rule>

<!-- 2분 내 2회 반복: Level 15 격상 (최고 심각도) -->
<rule id="100091" level="15" frequency="2" timeframe="120">
  <if_matched_sid>100090</if_matched_sid>
  <description>Falco: Binary dir write 2+ times in 2min
    (CRITICAL: Active rootkit installation in progress)</description>
  <mitre>
    <id>T1543</id>
    <tactic>Persistence</tactic>
  </mitre>
  <group>attack.persistence,container,rootkit,correlation,escalation,</group>
</rule>
```

Binary Dir Write의 에스컬레이션 조건이 더 엄격한 이유(2분 × 2회):
- `/bin`에 쓰기 자체가 비정상적 (정상 컨테이너는 절대 안 함)
- 첫 번째 탐지부터 Level 10 (Discord 알람)
- 2번째 탐지 시 실제 rootkit 설치 진행 중이므로 즉각 최고 심각도

---

## 에스컬레이션 정책 전체 뷰

```
Falco Events                  Wazuh Rules         Discord
───────────────────────────   ─────────────────   ────────────

Package Manager (1회)    ──► Rule 100080           ✗ (Level 7)
Package Manager (3회/5분) ──► Rule 100081  ESCALATE → ✓ (Level 12)

Binary Write (1회)       ──► Rule 100090            → ✓ (Level 10)
Binary Write (2회/2분)   ──► Rule 100091  ESCALATE → ✓ (Level 15)

Outbound Conn (1회)      ──► Rule 100010           ✗ (Level 8)
Outbound Conn (3회/5분)  ──► Rule 100070  ESCALATE → ✓ (Level 12)

Java RCE (1회)           ──► Rule 100041            → ✓ (Level 15)
Reverse Shell (1회)      ──► Rule 100040            → ✓ (Level 15)
```

---

## Phase 2 — Talon IPS 에스컬레이션 (계획)

현재 Talon은 **Phase 1 (Dry-Run)** 모드: 탐지 시 Pod에 의심 라벨만 추가

```yaml
# 현재 (Phase 1)
- rule: Package Manager in Container
  actions:
    - actionner: kubernetes:label
      parameters:
        labels:
          analysis/status: suspicious
```

Phase 2에서는 **WARNING 반복 시 자동 격리**를 구현할 예정이다.

구현 방향 두 가지:

**Option A: Talon 자체 에스컬레이션 (단순)**
- `dryRun: false`로 전환
- 첫 번째 WARNING부터 Pod 격리
- 단점: 오탐 시 서비스 중단 가능

**Option B: Wazuh Active Response → K8s 라벨 → Talon 반응 (정밀)**
1. Wazuh Rule 100081(에스컬레이션)이 트리거
2. Wazuh Active Response: 스크립트 실행
3. 스크립트: `kubectl label pod <pod> quarantine=true`
4. Talon: `quarantine=true` 라벨 감지 → NetworkPolicy 생성
5. 결과: 반복 WARNING 시에만 격리, 1회 오탐은 격리 없음

Option B가 더 정밀하지만 구현 복잡도가 높다. 홈랩에서는 먼저 Option A의 Package Manager를 Dry-Run 해제 후 모니터링할 계획이다.

---

## 검증 방법

### Wazuh Rule 테스트

```bash
# 1. Rule 로드 확인 (Wazuh Manager 재시작 후)
kubectl exec -n security wazuh-manager-master-0 -- \
  /var/ossec/bin/ossec-logtest -V 2>&1 | grep "100080\|100081\|100090\|100091"
# 출력:
# **Rule read: 100080 'Falco: Package manager...' Level: 7**
# **Rule read: 100081 'Falco: Package manager 3+...' Level: 12**
# **Rule read: 100090 'Falco: Binary directory...' Level: 10**
# **Rule read: 100091 'Falco: Binary dir write 2+...' Level: 15**

# 2. 특정 Rule 테스트 (JSON 입력 → Rule 매칭 확인)
echo '{"source":"falco","rule":"Launch Package Management Process in Container","priority":"Warning"}' | \
kubectl exec -i -n security wazuh-manager-master-0 -- \
  /var/ossec/bin/ossec-logtest
# 출력:
# **Phase 3: Completed filtering (rules).
#        Rule id: '100080'
#        Level: '7'
#        Description: 'Falco: Package manager ran inside container (WARNING)'**

# 3. 에스컬레이션 알람 실시간 모니터링
kubectl exec -n security wazuh-manager-master-0 -- \
  tail -f /var/ossec/logs/alerts/alerts.json | \
  jq 'select(.rule.id == "100081" or .rule.id == "100091") | {rule_id: .rule.id, level: .rule.level, desc: .rule.description}'
# 에스컬레이션 발생 시 출력:
# {
#   "rule_id": "100081",
#   "level": 12,
#   "desc": "Falco: Package manager ran 3+ times in 5min (Escalated)"
# }
```

### Falco 탐지 시뮬레이션

에스컬레이션을 테스트하려면 WARNING 이벤트를 반복 발생시켜야 한다.

```bash
# 테스트용 Pod 실행
kubectl run test-pkg --image=ubuntu --rm -it -- bash

# Pod 내부에서 실행 (3회 반복)
apt-get install -y curl    # → Falco: WARNING 1회 → Wazuh Level 7 (기록)
apt-get install -y wget    # → Falco: WARNING 2회 → Wazuh Level 7 (기록)
apt-get install -y netcat  # → Falco: WARNING 3회 → Wazuh Level 12 (Discord!)

# Falco가 탐지하는 이벤트 확인
kubectl logs -n falco -l app=falco --tail=10 | jq '{rule: .rule, priority: .priority}'
# {
#   "rule": "Launch Package Management Process in Container",
#   "priority": "Warning"
# }
```

5분 내에 3회 실행하면 Wazuh에서 Rule 100081이 발동하고 Discord 알람이 발송된다.

---

## 운영 관점 — 에스컬레이션 정책 조정 기준

에스컬레이션 조건(frequency, timeframe)은 환경에 따라 조정이 필요하다.

| 상황 | 조정 방향 |
|------|---------|
| 오탐이 많다 | frequency 증가 또는 timeframe 감소 |
| 탐지가 너무 느리다 | frequency 감소 또는 timeframe 증가 |
| 동일 Pod에서만 에스컬레이션 원한다 | `<same_field>output_fields.k8s.pod.name</same_field>` 추가 |
| 특정 네임스페이스 제외 | Falco Rule exception 또는 Wazuh rule 조건 추가 |

### `same_field`로 더 정밀한 에스컬레이션

기본 frequency rule은 클러스터 전체에서 3회 발생 시 트리거한다. 하지만 **같은 Pod에서만** 카운트하려면:

```xml
<rule id="100081" level="12" frequency="3" timeframe="300">
  <if_matched_sid>100080</if_matched_sid>
  <!-- 같은 Pod에서 3회 반복 시에만 에스컬레이션 -->
  <same_field>output_fields.k8s.pod.name</same_field>
  <description>...</description>
</rule>
```

홈랩에서는 전체 클러스터 카운트로 충분하지만, 대규모 클러스터에서는 `same_field`가 더 정확하다.

---

## 성과

### Before / After

| 항목 | Before (에스컬레이션 없음) | After (에스컬레이션 적용) |
|------|--------------------------|------------------------|
| Discord 알람 건수 | 하루 수십 건 (모든 WARNING) | 반복 패턴 시에만 (약 90% 감소) |
| 알람 대응 시간 | 알람 당 1-5분 판단 필요 | 에스컬레이션 알람 = 즉시 조치 |
| 오탐으로 인한 노이즈 | 전체 알람의 ~85% | 에스컬레이션 알람의 ~10% 미만 |
| 알람 Priority 구분 | 단일 수준 (모든 WARNING 동일) | 4단계 (Level 7→10→12→15) |

### 에스컬레이션 정책 적용 전후 알람 흐름

```
Before:
  Package Manager 1회 → Discord 알람 1건 (오탐 가능성 높음)
  Package Manager 2회 → Discord 알람 2건
  Package Manager 3회 → Discord 알람 3건  ← 응답자가 피로해짐

After:
  Package Manager 1회 → 기록만 (Discord 없음)
  Package Manager 2회 → 기록만 (Discord 없음)
  Package Manager 3회 → Discord 알람 1건 (CRITICAL 격상) ← 높은 신뢰도
```

### 핵심 지표

- **Discord 알람 90% 감소**: Package Manager 단발성 이벤트 필터링
- **탐지 신뢰도 향상**: 에스컬레이션 알람의 오탐율 ~10% (기존 ~85%)
- **대응 속도 개선**: 알람 판단 시간 1-5분 → 에스컬레이션 알람은 즉각 대응

다음 단계로 **Phase 2 Talon IPS 에스컬레이션**을 구현하여, WARNING이 3회 반복되면 자동으로 Pod 네트워크 격리까지 이어지는 완전한 자동화 파이프라인을 완성할 예정이다.
