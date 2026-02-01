---
title: "알람하나 받기 이렇게나 힘들다니: 홈랩 모니터링 구축 실패와 성공기"
date: 2026-02-01T15:24:00+09:00
draft: false
description: 모니터링 시스템 구축하였지만 알람이 없네 뭔 의미가 있나
tags:
  - prometheus
  - grafana
  - discord
  - alertmanager
  - slack
categories:
  - study
---
## 알람이 오지 않는다.

Alaram 시스템은 있었다 그러나 발동하지 않았다. 반나절이나 블로그가 죽어있었으나 몰랐다.. \
기존에 설정했던 rule 들이 아키텍처 변경된 부분들을 업데이트가 되지 않았기 때문이었다. \
gmail 외에 다른채널 도 이용해보고 싶어 discord 와 Telegram을 설정하려다가 엄청난 트러블 슈팅을 경험 했다.\
그 이야기를 공유 해보겠다

## Part 1. AlertManager와 Discord 프로토콜 불일치

- - -

가장 먼저 부딫힌 문제는 Alertmanager와 알람 수신하는 Discord 사이의 통신 실패다. \
업계 표준이라고 불리는 Alertmanager는 왜 Discord와 호화 되지 않았을까

#### 1. 문제

Alertmanager 설정(alertmanager.yaml)에 Discord Webhook URL을 넣고 테스트 했을때 로그에 다음과 같은 에러가 반복되 었다.

```
level=error msg="Notify for alerts failed" err="... unexpected status code 400: body: {\"code\": 50006, \"message\": \"Cannot send an empty message\"}"
```

#### 2. 원인 분석

Alertmanager는 기본적으로 Slack API 포맷을 표준으로 채택하기에 Discord도 같은 JSON 형식을 사용했다.\
그러나 Json Payload 구조가 미세하게 달랐다.

* **AlertManager가 보내는 Payload(Slack 표준)**: Alertmanager는 메시지를 풍부하게 표현 하기 위해 `attachments` 필드 안에 배열 을 담음\
  **예시**

```
{
  "username": "Alertmanager",
  "attachments": [
    {
      "title": "[CRITICAL] Blog Down",
      "text": "Description...",
      "color": "#FF0000",
      "mrkdwn_in": ["text"]
    }
  ]
}
```

### 결론:

Discord Slack 호환모드는 `attachments` 필드의 모든 속성을 완벽하게 지원하지 못함\
특히 복잡한 중첩구조들을 해석하지 못해 **본문이 없는 빈 메시지로 판단**\
새로운 방법을 찾아야했어 **Grafana Alerting**을 사용하기로 하였다.

## Part 2: Prometheus와 Grafna의 데이터 포맷 호환성

- - -

Grafna Alerting으로 넘어왔으나 또 다른 문제가 발생 오류 알람이 발생하거나 오판을 하는 것이였다.\
이는 두 시스템이 데이터를 바라보는 **관점(Dimension)**이 달랐기 때문

![](1.png)

### 1. 문제

Grafana Alert Rule을 생성하자마자 다음과 같은 에러메시지와 함께 알람 설정이 문제

* `reducer mode 'strict' is not supported`
* `frame cannot uniquely be identified by its labels: has duplicate results`
* `input data must be a wide series but got type long (input refid)`

### 2. 해결

#### 차원의 문제 (Vector vs Scalar)

* **Promehteus** 는 기본적으로 식나의 흐름에 따른 데이터 배열을 준다.\
  예시 : `[13:00=1, 13:01=1, 13:02=0, ...]` (X축과 Y축이 있는 그래프 데이터)
* **Grafana**는 알람 엔진은 그래프가 필요 없음 현재 데이터인 하나의 숫자가 필요

#### 다중 차원의 문제 (Multiple Series)

* **쿼리:** `up{job="kubernetes-nodes"}` 
* **결과:** 클러스터 내 노드 4개의 상태가 동시에 리턴됨.\
  node-1 : 1\
  node-2 : 1

### 3. 해결

Grafana가 데이터를 이해할 수 있도록 **\*파이프 라인**을 재설게헀다.

* #### Step1. Query 단순화
 과거의 데이터를 모두 가져오는 Range Query 대신, **"지금 이 순간"**의 데이터만 가져오는 Instant Query로 변경했습니다.\
up (X) → vector(1) or last_over_time (O)

* #### Step2. 차원 축소
Prometheus가 준 시계열 데이터를 단일 값으로 압축하는 Reduce 단계를 명시적으로 추가했습니다.
> Function: Last (가장 최근 값만 취한다)\
> Mode: Strict 제거 (데이터가 비어있을 때 에러를 내지 않고 유연하게 처리)
* #### 명확한 임계값
압축된 단일 값을 기준선과 비교합니다.
> Input: Reduce 단계의 결과물 (B)\
> Condition: Is below 1 (1보다 작으면 비정상)

##  교훈
---

### Key Takeaways (핵심 교훈)
1. SaaS 통합의 함정: "표준"이라고 해서 모든 도구가 100% 호환되는 것은 아니다. (Alertmanager ↔ Discord) 프로토콜이 맞지 않을 때는 Native 지원 도구로 빠르게 선회하는 것이 엔지니어링 비용을 줄인다.

2. 데이터 쉐이핑(Data Shaping): 모니터링 도구 간 데이터를 주고받을 때는 **"주는 쪽의 형태(Vector)"**와 **"받는 쪽의 기대(Scalar)"**를 정확히 파악하고, 중간에서 적절히 가공(Reduce)해 주어야 한다.

3. 쿼리의 정확성: 알람 쿼리는 그래프 쿼리와 다르다. "전체 흐름"이 아니라 "현재 상태"를 명확히 지칭(Single Instance & Instant)해야 오탐을 막을 수 있다.
