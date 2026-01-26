---
title: "Elasticsearch 기초: term이란 무엇인가?"
date: 2025-10-11T02:02:37-04:00
tags: ["Elasticsearch", "term", "match", "Analyzer", "검색엔진"]
categories: ["study", "Elasticsearch"]
draft: false
author: "늦찌민"
description: "검색 엔진을 위한 제일 작은 단위 term"
series: "ES구축기"
---

## 🔍 들어가며

엘라스틱서치를 처음 접하면 `term`, `text`, `keyword` 라는 단어가 정말 자주 등장합니다.  
“term 쿼리”, “keyword 필드”, “text 분석” 같은 개념들이죠.  

이 셋은 모두 **색인(Indexing)** 과정과 **검색(Search)** 과정의 핵심 개념입니다.  
이 글에서는 `term`이란 무엇인지,  
그리고 `text`와 `keyword`가 어떻게 다른지를 **직관적으로** 정리해봅니다.



## 📘  term이란 무엇인가?

> **term** = 엘라스틱서치가 색인할 때 만들어내는 “검색 가능한 최소 단어 단위”

엘라스틱서치는 데이터를 색인하기 전에 **Analyzer(분석기)** 를 거칩니다.  
문장을 그대로 저장하지 않고, 단어별로 잘게 쪼개서 저장하는 구조예요.

예를 들어 👇

```json
{
  "message": "My Server has 128GB Memory"
}
```

분석 과정을 거치면 이렇게 됩니다.

| 단계            | 결과                                         |
| ------------- | ------------------------------------------ |
| 원문            | "My Server has 128GB Memory"               |
| 소문자 변환        | "my server has 128gb memory"               |
| 토큰화(Tokenize) | ["my", "server", "has", "128gb", "memory"] |


#### ➡️ 이 각각의 단어 `"my"`, `"server"`, `"has"`, `"128gb"`, `"memory"` 가 바로 term이에요. 엘라스틱서치는 이 term 들을 역색인(inverted index)에 저장해둡니다.
---

### 🧠 term이 중요한 이유

> 엘라스틱서치의 모든 검색은 term 단위로 동작합니다.
즉, term이 어떻게 만들어졌느냐에 따라 검색 결과가 완전히 달라집니다.

예를 들어 `"Server"` 와 `"server"` 는 대소문자만 다르지만
색인 시 분석기에서 소문자로 변환되면 "Server" term은 존재하지 않게 됩니다.\
따라서 `"Server"` 로 term 쿼리를 날리면 검색이 되지 않습니다.
이게 바로 `term`과 `match` 쿼리의 근본적인 차이예요.

---
### ⚙️  term 쿼리 vs match 쿼리

| 구분           | Analyzer 사용 여부 | 특징                         | 사용 예             |
| ------------ | -------------- | -------------------------- | ---------------- |
| **term 쿼리**  | ❌ 사용 안 함       | 색인된 term 과 정확히 일치하는 문서만 검색 | 코드, ID, 상태값      |
| **match 쿼리** | ✅ 사용함          | 입력값을 분석 후 term으로 바꾼 뒤 검색   | 자연어 검색, 설명, 제목 등 |


### 💬 예시 요약

```json
# term 쿼리 (정확 일치)
GET /index/_search
{
  "query": {
    "term": { "status": "ACTIVE" }
  }
}

# match 쿼리 (Analyzer 사용)
GET /index/_search
{
  "query": {
    "match": { "message": "Server Memory" }
  }
}
```
---

### 🧱 text 타입 vs keyword 타입
이제 term이 어떻게 만들어지는지를 결정하는 필드 타입을 알아야 합니다.
엘라스틱서치의 문자열(string) 타입은 크게 두 가지예요:

| 타입          | Analyzer | 검색 방식                     | 용도               |
| ----------- | -------- | ------------------------- | ---------------- |
| **text**    | ✅ 사용함    | 문장을 단어로 쪼개서 term 생성 (역색인) | 본문, 제목, 설명 등     |
| **keyword** | ❌ 사용 안 함 | 전체 문자열을 하나의 term 으로 저장    | 코드, ID, 상태, 태그 등 |

### 🎯 예시로 비교

``` json
PUT index_test
{
  "mappings": {
    "properties": {
      "title":   { "type": "text" },
      "category": { "type": "keyword" }
    }
  }
}

POST index_test/_doc
{
  "title": "My First Elasticsearch Study",
  "category": "SearchEngine"
}
```

| 필드                 | 색인된 term                                  |
| ------------------ | ----------------------------------------- |
| title (text)       | ["my", "first", "elasticsearch", "study"] |
| category (keyword) | ["SearchEngine"]                          |

### 🔎  검색할 때 차이점

| 쿼리                                            | 동작 방식                                    |
| --------------------------------------------- | ---------------------------------------- |
| `"match": { "title": "Elasticsearch Study" }` | `"elasticsearch"`와 `"study"` 두 term으로 검색 |
| `"term": { "category": "SearchEngine" }`      | `"SearchEngine"` 이라는 하나의 term만 정확히 일치 검색 |
| `"term": { "category": "searchengine" }`      | ❌ 검색 안 됨 (keyword는 Analyzer를 안 쓰기 때문)    |

- text는 “문장 안 단어를 분석해서 검색”
- keyword는 “값 전체가 정확히 일치할 때만 검색”

### 💡 핵심 정리
> 엘라스틱서치 검색의 본질은 term이다. \
> text는 term을 여러 개 만들고, keyword는 term을 하나만 만든다. \
> 검색 시 term 쿼리는 정확 일치, match 쿼리는 자연어 검색에 사용한다.