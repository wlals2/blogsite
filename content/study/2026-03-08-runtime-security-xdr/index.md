---
title: "IDS + IPS + SIEM + XDR — 컨테이너 런타임 보안 4계층 구축기"
date: 2026-03-08T10:00:00+09:00
categories:
  - study
  - Security
tags: ["falco", "talon", "wazuh", "cilium", "xdr", "siem", "runtime-security", "kubernetes"]
summary: "Falco(IDS)로 탐지하고, Talon(IPS)으로 NetworkPolicy 격리하고, Wazuh(SIEM)로 3개 소스 교차 상관 분석까지 — 홈랩 K8s 런타임 보안 구축 전 과정"
showtoc: true
tocopen: true
draft: false
---

이론 배경: [홈랩 보안 아키텍처 전체 개요](../2026-03-06-homelab-security-architecture-overview)

---

## 배경: 탐지만으로 부족하다

Falco로 syscall 기반 이상 행동을 탐지하고 있었다. Java 프로세스가 shell을 실행하면 Log4Shell RCE로 판정하고, apt가 컨테이너 안에서 실행되면 공격 도구 설치 시도로 기록했다.

문제는 **탐지 이후**였다.

- 탐지 이벤트가 Loki에 쌓이지만 아무도 실시간으로 보지 않는다
- Discord 알림은 오탐이 너무 많아 신뢰하기 어렵다
- 공격이 성공해도 격리되지 않는다 — 탐지와 대응 사이에 수동 개입이 필요하다

목표를 4계층으로 정리했다.

```
IDS (탐지)   — Falco: syscall 기반 이상 행동 실시간 탐지
IPS (격리)   — Talon: 탐지 이벤트 → NetworkPolicy 자동 격리
SIEM (분석)  — Wazuh: 3개 소스 수집 + 에스컬레이션 + Discord 알림
XDR (교차)   — Wazuh: Audit Log + Falco + Cilium 교차 상관 분석
```

---

## 1단계: IPS — Talon NetworkPolicy 격리 활성화 (Phase 3)

### Talon이 하는 일

Falco → Falcosidekick → Talon 순서로 이벤트가 전달된다. Talon은 Falco 이벤트를 받아 K8s API를 직접 호출해서 대응한다.

```
Falco (탐지) → Falcosidekick (라우팅) → Talon (IPS 대응)
                                              │
                                              ├─ kubernetes:label   (라벨 부착)
                                              └─ kubernetes:networkpolicy (격리)
```

CRITICAL 이벤트(Java RCE)에는 NetworkPolicy 격리를, WARNING 이벤트(패키지 설치, shell 실행)에는 라벨 부착만 한다. WARNING을 바로 격리하면 오탐 시 서비스 장애로 이어지기 때문이다.

### E2E 검증: Java RCE → NetworkPolicy 격리까지

테스트 Pod를 blog-system에 배포했다.

```yaml
# 테스트용 Java Pod (Kyverno 정책 준수 필요)
containers:
- name: java-rce-test
  image: eclipse-temurin:17-jdk-jammy   # latest 태그 금지 (Kyverno)
  securityContext:
    runAsNonRoot: true
    runAsUser: 1000
```

`ProcessBuilder("sh", "-c", "id")` 를 실행하면:

```
[흐름]
Java ProcessBuilder("sh") 실행
  → Falco Rule 1: Java Process Spawning Shell (CRITICAL)
  → Falcosidekick → Talon
  → Talon: kubectl create networkpolicy (deny all ingress/egress)
  → 격리 완료
```

**결과: 탐지부터 NetworkPolicy 생성까지 16초**

```bash
# 격리 확인
kubectl get networkpolicy -n blog-system
# NAME                           POD-SELECTOR
# isolate-java-rce-test-xxxxx    app=java-rce-test

# Egress 차단 확인 (격리된 Pod에서)
kubectl exec java-rce-test -- curl -s --max-time 3 https://google.com
# curl: (28) Connection timed out  ← 차단 확인
```

격리 해제는 NetworkPolicy 삭제로 즉시 가능하다.

```bash
kubectl delete networkpolicy -l quarantine=true -n blog-system
kubectl label pod <pod-name> quarantine- falco-response- -n blog-system
```

### 오탐 방지 설계

격리가 활성화되면 오탐 = 서비스 장애다. 예외 설정이 핵심이다.

```yaml
# apps/talon/values.yaml
exceptions:
  - namespaces:
      - kube-system    # CNI, DNS 등 인프라
      - monitoring     # Prometheus, Grafana
      - argocd         # GitOps
      - falco          # Falco 자신
# blog-system은 예외 없음 — 실제 공격 대상
```

인프라 네임스페이스를 일괄 제외하고, 실제 서비스 네임스페이스(blog-system)는 예외 없이 탐지한다.

---

## 2단계: SIEM — Wazuh 3개 소스 수집

### 기존 구성의 한계

기존에는 Falco 컨테이너 로그만 수집하고 있었다.

```
Before: Falco 컨테이너 로그만 → 같은 소스 내 에스컬레이션만 가능
```

공격이 실제로 일어날 때 남기는 흔적은 여러 소스에 분산된다.

- `kubectl exec` → K8s Audit Log에 기록
- shell 실행 → Falco에 기록
- CiliumNetworkPolicy 위반 → Cilium hubble에 기록

이 세 가지를 각각 보면 "가능성"이지만, 연결하면 "확정"이 된다.

### K8s Audit Log 수집

kube-apiserver는 모든 API 호출을 Audit Log에 기록한다. `/var/log/kubernetes/audit.log`에 JSON 형식으로 저장된다.

```xml
<!-- /var/ossec/etc/ossec.conf -->
<localfile>
  <log_format>json</log_format>
  <location>/var/log/kubernetes/audit.log</location>
</localfile>
```

`log_format=json`을 지정하면 Wazuh 내장 json decoder가 자동으로 파싱한다.

탐지 규칙:

```xml
<!-- kubectl exec 탐지 (Rule 100210) -->
<rule id="100210" level="8">
  <if_sid>100200</if_sid>
  <field name="objectRef.subresource">exec</field>
  <description>K8s Audit: kubectl exec detected</description>
  <mitre><id>T1609</id></mitre>
</rule>

<!-- kubectl exec 3회/5분 에스컬레이션 (Rule 100212) -->
<rule id="100212" level="12" frequency="3" timeframe="300">
  <if_matched_sid>100210</if_matched_sid>
  <description>K8s Audit: kubectl exec 3+ in 5min - Possible enumeration</description>
</rule>
```

검증:

```bash
kubectl exec -it was-xxx -n blog-system -- /bin/bash
# → Wazuh alerts.log에서 Rule 100210 확인
sudo grep "kubectl exec" /var/ossec/logs/alerts/alerts.log | tail -3
```

### Cilium hubble.export 수집

Cilium은 CiliumNetworkPolicy에 의해 차단된 연결을 hubble flow로 기록한다. `hubble.export`를 활성화하면 노드의 hostPath 파일로 저장된다.

```yaml
# apps/cilium/values.yaml
hubble:
  export:
    static:
      enabled: true
      filePath: /var/run/cilium/hubble/events.log
      allowList:
        - '{"verdict":["DROPPED"]}'   # DROPPED만 저장 — FORWARDED는 수백만 건/일
      fieldMask:
        - source
        - destination
        - verdict
        - drop_reason
        - IP
        - l4
        - time
```

DROPPED만 저장하는 이유: 정상 FORWARDED 이벤트는 하루 수백만 건이 발생해서 디스크 폭발 위험이 있다. CiliumNetworkPolicy에 의해 차단된 연결만 = 정책 위반 = 공격 시도 또는 격리된 Pod의 C2 통신 시도다.

```bash
# helm upgrade로 적용 (Cilium은 ArgoCD 미관리 — CNI 부트스트랩 순서 문제)
helm upgrade cilium cilium/cilium -n kube-system -f apps/cilium/values.yaml

# 5노드 모두 events.log 생성 확인
kubectl exec -n kube-system cilium-xxx -- ls -la /var/run/cilium/hubble/events.log
# -rw-r--r-- 1 root root 3469538 Mar 7 events.log
```

저장된 형식:

```json
{
  "flow": {
    "verdict": "DROPPED",
    "drop_reason": 133,
    "IP": {"source": "10.0.2.161", "destination": "10.0.2.91"},
    "l4": {"TCP": {"source_port": 38752, "destination_port": 8080}},
    "source": {"namespace": "monitoring", "pod_name": "prometheus-xxx"},
    "destination": {"namespace": "blog-system", "pod_name": "was-xxx"},
    "traffic_direction": "INGRESS"
  }
}
```

Wazuh 수집 설정:

```xml
<!-- /var/ossec/etc/ossec.conf -->
<localfile>
  <log_format>json</log_format>
  <location>/var/run/cilium/hubble/events.log</location>
</localfile>
```

탐지 규칙 검증 (`wazuh-logtest`):

```bash
echo '{"flow":{"verdict":"DROPPED","destination":{"labels":["k8s:io.kubernetes.pod.namespace=blog-system"]}}}' \
  | sudo /var/ossec/bin/wazuh-logtest

# 출력:
#   level: '8'
#   description: 'Cilium: DROPPED flow targeting blog-system namespace'
# → Rule 100310 매칭 확인
```

---

## 3단계: XDR — 교차 소스 상관 분석

### 왜 단일 소스 탐지로 부족한가

| 이벤트 | 소스 | 단독 해석 | 교차 해석 |
|--------|------|---------|---------|
| kubectl exec | Audit Log | 정상 운영일 수도 있음 | + Shell Spawned = 침투 확정 |
| Shell Spawned | Falco | 초기화 스크립트일 수도 있음 | + kubectl exec = 침투 확정 |
| Java RCE | Falco | 공격 의심 | + Egress DROPPED = RCE + C2 차단 확정 |
| Egress DROPPED | Cilium | 격리 Pod의 정상 동작 | + Java RCE = C2 통신 시도 확정 |

단독으로는 오탐 가능성이 있는 이벤트들이 조합되면 신뢰도가 올라간다.

### Wazuh 교차 규칙 구현

Wazuh의 `if_matched_sid`를 사용한다. "이전에 규칙 A가 트리거됐을 때, 현재 이벤트가 규칙 B에 해당하면 C를 발동"하는 구조다.

**Rule 100410: kubectl exec + Shell Spawned = 침투 후 실행 확정**

```xml
<rule id="100410" level="15">
  <if_matched_sid>100210</if_matched_sid>  <!-- kubectl exec 발생 이력 -->
  <if_sid>100140</if_sid>                  <!-- 현재 이벤트: Shell Spawned -->
  <description>XDR: kubectl exec followed by Shell Spawned
    - Container intrusion chain confirmed</description>
  <mitre>
    <id>T1609</id>  <!-- Container Administration Command -->
    <id>T1059</id>  <!-- Command and Scripting Interpreter -->
  </mitre>
</rule>
```

**Rule 100420: Java RCE + Egress DROPPED = RCE 후 C2 통신 시도 확정**

```xml
<rule id="100420" level="15">
  <if_matched_sid>100141</if_matched_sid>  <!-- Java RCE 발생 이력 -->
  <if_sid>100320</if_sid>                  <!-- 현재: Egress DROPPED to external -->
  <description>XDR: Java RCE followed by Egress DROPPED
    - RCE with blocked C2 attempt confirmed</description>
  <mitre>
    <id>T1190</id>  <!-- Exploit Public-Facing Application -->
    <id>T1071</id>  <!-- Application Layer Protocol (C2) -->
  </mitre>
</rule>
```

두 규칙 모두 Level 15 = Discord 즉시 알림이 발송된다.

### Wazuh XDR 구현 시 발견한 제약

교차 규칙을 작성하면서 Wazuh 문법 제약 두 가지를 만났다.

**제약 1**: `if_matched_sid` + `timeframe` 조합 불가

```xml
<!-- 이렇게 쓰면 에러 -->
<rule id="100410" level="15">
  <if_matched_sid>100210</if_matched_sid>
  <if_sid>100140</if_sid>
  <timeframe>300</timeframe>  <!-- ← Invalid option -->
</rule>
```

`timeframe`은 `frequency` 기반 에스컬레이션 규칙에서만 쓸 수 있다. `if_matched_sid + if_sid` 조합에서는 Wazuh 내부 캐시를 사용하며 timeframe 제한 없이 동작한다.

**제약 2**: `if_matched_sid` + `frequency` 조합 불가

두 조건을 같이 쓰면 역시 에러가 발생한다. 에스컬레이션이 필요하면 별도 규칙으로 분리해야 한다.

---

## 전체 구성 결과

```
공격 발생 시나리오: 외부 공격자 → kubectl exec → Shell 실행
                                                        │
K8s Audit Log ──── Rule 100210 (kubectl exec, Level 8) ─┤
                   Rule 100212 (×3/5분, Level 12 → Discord)
                                                        │
Falco ──────────── Rule 100140 (Shell Spawned, Level 8) ─┤
                   Rule 100142 (×2/5분, Level 15 → Discord)
                                                        │
XDR ────────────── Rule 100410 (100210 + 100140, Level 15 → Discord 즉시)
```

```
공격 발생 시나리오: Java RCE → Talon 격리 → C2 통신 시도
                                                        │
Falco ──────────── Rule 100141 (Java RCE, Level 15 → Discord 즉시)
Talon ──────────── NetworkPolicy 격리 (16초 이내)        │
                                                        │
Cilium hubble ──── Rule 100320 (Egress DROPPED, Level 10)
                                                        │
XDR ────────────── Rule 100420 (100141 + 100320, Level 15 → Discord 즉시)
```

### Before / After

| 항목 | Before (2026-03-05) | After (2026-03-08) |
|------|--------------------|--------------------|
| 탐지 소스 | Falco 1개 | Falco + K8s Audit Log + Cilium 3개 |
| 격리 | 수동 (dryRun) | 자동 (CRITICAL 16초 이내) |
| 상관 분석 | 같은 소스 내 반복 탐지만 | 3개 소스 교차 규칙 (XDR) |
| SIEM 규칙 수 | 7개 | 18개 (+K8s Audit 6개, +Cilium 4개, +XDR 2개) |
| Wazuh Level 15 알림 신뢰도 | 중간 (단일 소스 에스컬레이션) | 높음 (교차 소스 확인) |

---

## 남은 한계

**worker 노드 hubble 로그 미수집**: Cilium events.log는 각 노드의 hostPath에 저장된다. 현재 Wazuh는 k8s-cp에만 설치되어 있어 k8s-cp 노드 로그만 수집 가능하다. 실제 서비스 Pod는 worker 노드에서 실행되므로 해당 노드의 DROPPED 이벤트는 미수집 상태다.

해결 방향:
- Wazuh Agent를 각 worker 노드에 배포 (DaemonSet)
- 또는 Fluentd/Vector로 events.log를 k8s-cp로 집중화 후 수집

**XDR timeframe 미지원**: 교차 규칙이 "언제까지 이전 이벤트를 기억하는가"가 Wazuh 내부 캐시에 의존한다. 시간 제한을 걸 수 없어서 오래된 kubectl exec 이력이 오탐을 만들 수 있다. Wazuh 4.x의 상관 분석 엔진(Sigma 지원)으로 개선 가능하다.
