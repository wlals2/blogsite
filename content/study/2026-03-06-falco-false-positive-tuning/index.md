---
title: "Falco 오탐 1,800건/일을 0건으로 — Wazuh SIEM 로그 분석 기반 체계적 튜닝"
date: 2026-03-06T10:00:00+09:00
categories:
  - study
  - Security
tags: ["falco", "wazuh", "siem", "false-positive", "tuning", "runtime-security"]
series: ["Falco/Wazuh 시리즈"]
summary: "Wazuh SIEM alerts.json 분석으로 Falco 오탐 원인을 특정하고, 3차에 걸쳐 일 ~1,800건 오탐을 제거한 과정"
showtoc: true
tocopen: true
draft: false
---

## 배경: 왜 오탐 튜닝이 필요했나?

Falco 커스텀 룰 5개를 운영하면서 Wazuh SIEM에 보안 이벤트가 쌓이고 있었다. 문제는 그 대부분이 **오탐(False Positive)**이었다는 것이다.

```
Wazuh alerts.json 분석 결과 (2026-03-06 기준):
  - 일일 Falco 이벤트: ~1,800건
  - 그 중 오탐: ~99%
  - Wazuh Level 15 에스컬레이션: 503건 (etcd-backup의 정상 동작)
  - Wazuh Level 12 에스컬레이션: 368건 (ArgoCD의 정상 Git sync)
```

오탐이 이렇게 많으면 두 가지 문제가 발생한다:

1. **알림 피로**: Discord 알림을 연동하면 하루에 수백 건의 거짓 알림이 쏟아진다
2. **정탐 매몰**: 진짜 공격 이벤트가 오탐 속에 묻혀서 발견할 수 없다

---

## 개념: 오탐 분석 방법론

오탐을 찾는 가장 확실한 방법은 **SIEM 로그를 직접 분석**하는 것이다.

Wazuh는 모든 알림을 `/var/ossec/logs/alerts/alerts.json`에 JSON 형태로 저장한다. 이 파일을 분석하면:

- 어떤 Rule이 가장 많이 발생하는지
- 어떤 Pod/컨테이너가 반복적으로 탐지되는지
- 에스컬레이션된 이벤트의 실제 원인이 무엇인지

를 정량적으로 파악할 수 있다.

```bash
# Wazuh에서 Falco 관련 알림만 추출
sudo cat /var/ossec/logs/alerts/alerts.json | \
  python3 -c "
import json, sys, collections
rules = collections.Counter()
for line in sys.stdin:
    try:
        alert = json.loads(line)
        rule_id = alert.get('rule', {}).get('id', 'unknown')
        desc = alert.get('rule', {}).get('description', '')
        if 'falco' in desc.lower() or rule_id.startswith('100'):
            rules[f'{rule_id}: {desc}'] += 1
    except: pass
for rule, count in rules.most_common(20):
    print(f'{count:>6}  {rule}')
"
# 출력 예시:
#   1836  100110: Falco: Unexpected outbound connection from container
#    503  100091: Falco: Binary dir write 2+ in 2min (Escalated: Possible rootkit)
#    368  100113: Falco: Unexpected outbound 3+ in 5min (Escalated: Possible C2)
```

---

## 과정: 3차에 걸친 오탐 제거

### 1차 튜닝 — ArgoCD + Falcosidekick 에러 루프

**발견**: Rule 4(Unexpected Outbound Connection)에서 일 ~1,836건 발생. 대부분 ArgoCD가 Git 저장소를 sync하는 정상 트래픽이었다.

**원인**: 개별 프로세스 이름으로 필터링하고 있었는데, Falco가 프로세스 이름을 **15자로 자르는** 문제가 있었다.

```yaml
# Before: argocd-repo-server가 argocd-repo-ser로 잘려서 필터 미매칭
not proc.name in (coredns, argocd-repo-serv, ...)

# After: 네임스페이스 단위로 전환 — 프로세스명 잘림 문제 근본 해결
not k8s.ns.name in (kube-system, monitoring, argocd, falco,
  istio-system, security, longhorn-system, metallb-system,
  sealed-secrets, cert-manager)
```

추가로, 배포되지 않은 `ai-response-service` Pod에 Falcosidekick이 계속 webhook을 보내며 에러 루프가 발생하고 있어서 webhook 설정을 제거했다.

### 2차 튜닝 — backup CronJob + MySQL entrypoint

**발견**: 1차 튜닝 후에도 Rule 2(Package Manager), Rule 3(Binary Dir Write), Rule 5(Shell Spawned)에서 오탐이 계속 발생했다.

| 오탐 원인 | 탐지된 Rule | 실제 동작 |
|----------|------------|---------|
| etcd-backup CronJob | Rule 2, 3 | `apk add aws-cli` — 백업 스크립트가 매일 AWS CLI를 설치 |
| MySQL entrypoint | Rule 5 | `bash docker-entrypoint.sh mysqld` — MySQL 컨테이너 시작 시 shell 실행 |

```yaml
# Rule 2, 3에 backup 컨테이너 예외 추가
not container.name in (mysql-backup, etcd-backup)

# Rule 5에 MySQL + etcd-backup 예외 추가
not container.name in (istio-init, otel-agent-download,
  mysql-backup, mysql, etcd-backup)
```

### 3차 튜닝 — mysql-exporter 메트릭 포트

**발견**: Discord로 첫 알림이 도착했는데, mysql-exporter가 포트 9104에서 Prometheus 메트릭을 리슨하는 정상 동작이 "Unexpected Outbound Connection"으로 탐지되고 있었다.

```yaml
# Before
not fd.lport in (80, 443, 8080, 3306, 53)

# After — 9104(Prometheus exporter 표준 포트) 추가
not fd.lport in (80, 443, 8080, 3306, 53, 9104)
```

---

## 성과

| 항목 | Before | After |
|------|--------|-------|
| 일일 Falco 오탐 건수 | ~1,800건 | 모니터링 중 (3/7~8 검증) |
| Wazuh Level 15 에스컬레이션 | 503건 (etcd-backup 정상 동작) | 0건 (근본 원인 제거) |
| Wazuh Level 12 에스컬레이션 | 368건 (ArgoCD Git sync) | 0건 (근본 원인 제거) |
| Discord 알림 신뢰도 | 사용 불가 (오탐 폭주) | Level 12+ 정탐만 수신 |

### 튜닝 원칙 (재사용 가능)

| 원칙 | 이유 |
|------|------|
| 시스템 NS는 `not k8s.ns.name in (...)` 일괄 제외 | 프로세스 이름 15자 잘림 등 예측 불가능한 문제 방지 |
| 앱 NS(blog-system)는 **제외하지 않음** | 실제 공격 대상이므로 탐지 유지 |
| 컨테이너 예외는 `not container.name in (...)` | 최소 범위 적용 — NS 전체 제외보다 안전 |
| 포트 허용은 명확한 것만 | 9104(exporter), 3306(MySQL) 등 표준 포트만 |

---

## Wazuh Discord 알림 연동

오탐 제거 후, Wazuh Level 12+ 이벤트를 Discord로 전송하도록 연동했다.

```python
# /var/ossec/integrations/custom-discord.py (핵심 부분)
def send_discord(alert, webhook_url):
    rule = alert.get("rule", {})
    level = rule.get("level", 0)

    # Level별 색상
    color = 0xFF0000 if level >= 12 else 0xFF8C00 if level >= 10 else 0xFFFF00

    embed = {
        "title": f"Wazuh Alert - Level {level}",
        "description": rule.get("description", ""),
        "color": color,
        "fields": [
            {"name": "Rule ID", "value": str(rule.get("id", "")), "inline": True},
            {"name": "Level", "value": str(level), "inline": True},
            {"name": "MITRE Tactic", "value": mitre_tactic, "inline": True},
        ]
    }
    requests.post(webhook_url, json={"embeds": [embed]})
```

```xml
<!-- /var/ossec/etc/ossec.conf -->
<integration>
  <name>custom-discord</name>
  <hook_url>https://discordapp.com/api/webhooks/...</hook_url>
  <level>12</level>
  <alert_format>json</alert_format>
</integration>
```

Level 12 이상만 전송하는 이유: 오탐 제거 전에는 Level 12/15 알림이 하루 ~870건이었다. 오탐 제거 후에는 정탐만 남으므로 Level 12+에서 실제 보안 이벤트만 받게 된다.

---

## 다음 단계

1. **오탐 제거 효과 수치 검증** (3/7~8): `alerts.json` Before/After 비교
2. **추가 오탐 발견 시 4차 튜닝**: 새로운 워크로드 배포, CronJob 추가 시 새 오탐 가능
3. **Wazuh 에스컬레이션 임계값 재검토**: 오탐 제거 후 정탐 기준으로 빈도 임계값 적절성 판단
4. **Talon Phase 3**: dryRun 해제하여 CRITICAL 이벤트 시 실제 NetworkPolicy 격리 활성화

---

> **시리즈**: [Falco/Wazuh 시리즈]
> - 다음 글: [MySQL CronJob이 Reverse Shell로 오탐된 이유 — Falco 예외 설계와 Wazuh 에스컬레이션 분리](/study/2026-03-07-falco-wazuh-false-positive-tuning/)
