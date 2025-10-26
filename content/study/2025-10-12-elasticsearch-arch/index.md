---
title: "ES의 analyzer 중 edge_ngram에 알아보자"
date: 2025-10-12T10:42:36
draft: false
categories: ["DevOps", "Elasitcsearch", "GitOps","data-pipeline"]
tags: ["Elasticsearch", "Json"]
description: "ES의 analyzer 중 edge_ngram에 알아보자"
author: "늦찌민"
series: ["ES구축기"]
---


## A LAB  함께 배우는 analyer (edge_ngram)

- **인덱스 만들기**

```json
PUT shop-v1
{
  "settings": {
    "analysis": {
      "analyzer": {
        "my_edge": {
          "type": "custom",
          "tokenizer": "standard",
          "filter": ["lowercase", "edge_ngrams_1_15"]
        }
      },
      "filter": {
        "edge_ngrams_1_15": {
          "type": "edge_ngram",
          "min_gram": 1,
          "max_gram": 15
        }
      }
    }
  },
  "mappings": {
    "properties": {
      "product_name": {
        "type": "text",
        "analyzer": "my_edge",
        "search_analyzer": "standard",
        "fields": {
          "raw": {
            "type": "keyword"
          }
        }
      }
    }
  }
}
```

- **문서 색인(indexing)**

```json
POST shop-v1/_doc
{ "product_name": "Samsung Galaxy S23 Ultra" }

POST shop-v1/_doc
{ "product_name": "Apple iPhone 15 Pro" }
```

- **검색/집계 (aggregate)**

```json
POST shop-v1/_search
{
  "query": { "match": { "product_name": "gal s23" } }
}

POST shop-v1/_search
{
  "size": 0,
  "aggs": {
    "names": { "terms": { "field": "product_name.raw" } }
  }
}
```

---

### 정리

**Lab A에서 정의한 analyer:**

```json
"my_edge": {
  "type": "custom",
  "tokenizer": "standard",
  "filter": ["lowercase", "edge_ngrams_1_15"]
}
```

**filter 정의**

```json
"edge_ngrams_1_15": {
  "type": "edge_ngram",
  "min_gram": 1,
  "max_gram": 15
}
```

### **1️⃣ 문장이 들어오면 먼저 tokenizer가 단어로 자름**

문서의 `"product_name": "Samsung Galaxy S23 Ultra”` 들어

| 단계 | 결과 | 설명 |
| --- | --- | --- |
| 입력 텍스트 | Samsung Galaxy S23 Ultra | 원문 |
| tokenizer (`standard`) | `["Samsung", "Galaxy", "S23", "Ultra"]` | 공백과 문장 부호를 기준으로 단어 단위 분리 |

### 2️⃣ 그 다음 filter가 순서대로 작동

filter 순서는 `lowcase` → `edge_ngrams_1_15` 

**① lowercase**

→ 모든 토큰을 소문자로 변환

```json
["samsung", "galaxy", "s23", "ultra"]
```

**② edge_ngrams_1_15**

이 필터가 바로 **부분검색의 비밀**이에요.

`edge_ngram`은 “단어의 접두어(prefix)”를 잘라 여러 토큰으로 만드는 역할을 합니다.

예를 들어 `"galaxy"`라는 토큰은:

| min_gram=1, max_gram=15 | 생성된 토큰 |
| --- | --- |
| 1~15 | `["g", "ga", "gal", "gala", "galax", "galaxy"]` |
- 즉, “galaxy” 한 단어를 여러 개의 **앞부분 토큰(prefix)** 으로 쪼갭니다.

```json
["s", "sa", "sam", "sams", "samsu", "samsun", "samsung",
 "g", "ga", "gal", "gala", "galax", "galaxy",
 "s", "s2", "s23",
 "u", "ul", "ult", "ultr", "ultra"]

```

### **3️⃣ 이 토큰들이 색인(index)에 저장됩니다.**

즉, **Elasticsearch**는 문서를 색인할 때 이렇게 “prefix 조각”들을 전부 역색인 테이블에 넣습니다.
이제 검색할 때 match 쿼리(`"gal s23"`)가 들어오면… ⇒ **search_analyzer=standard** 

검색 쿼리 `"gal s23"` → standard analyzer로 단어 단위로 자름:

```json
["gal", "s23"]

Elasticsearch는 이 토큰들을 역색인된 토큰 목록에서 검색합니다.
색인 시 이미 "gal" 과 "s23" 이라는 토큰들이 존재함
따라서 "Galaxy S23 Ultra" 문서가 매칭됨 ✅
```

### ✅ min_gram / max_gram 조합의 의미

| 설정 | 결과 | 용도 |
| --- | --- | --- |
| min_gram=1, max_gram=15 | “g”, “ga”, “gal” ... | **부분검색, 자동완성** |
| min_gram=3, max_gram=5 | “gal”, “gala”, “galax” | **짧은 조각은 버리고 효율 개선** |
| min_gram=2, max_gram=3 | “ga”, “gal”, “al” | **짧은 prefix 기반 검색** |

### 🚀 실무에서 이게 쓰이는 곳

| 사용 사례 | 설명 |
| --- | --- |
| **검색창 자동완성** | 사용자가 “gal” 입력 중에도 “Galaxy S23 Ultra” 결과가 뜸 |
| **상품명 부분검색** | “ultra” “gal s23” 등으로도 매칭 |
| **이름 검색** | “ji”로 “Jimin” 찾기 가능 |
| **주소/태그/도시명 검색** | 긴 문자열을 부분 일치로 빠르게 검색 |
