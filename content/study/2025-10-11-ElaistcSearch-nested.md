---
title: "Elasticsearch 기초: 색인과 역색인 이해하기"
tags: ["Elasticsearch", "Inverted Index", "검색엔진", "색인"]
categories: ["Elasticsearch", "Search Engine"]
date: 2025-10-11T01:27:54-04:00
draft: false
author: "늦찌민"
description: "배열 검색을 위한 nested 쿼리"
---
# 👏nested 설계에서 어떻게 사용할까 ? 
## 🧩 1️⃣ nested 쿼리는 왜 만들어졌을까?

**nested**는 단순히 “배열을 저장하려고” 만든 게 아님. \
**배열 안에 ‘하나의 논리적 객체 세트’가 존재할 때,** \
그 값들의 조합이 끊어지지 않게 하기 위해 만들어짐.
### 📦 예시 (일반 object 매핑일 때의 문제)
```json
{
  "spec": [
    { "cores": 6, "memory": 64 },
    { "cores": 12, "memory": 128 }
  ]
}
```
기본 object 매핑에서는 내부 필드들이 전부 평면화(flatten)된다.
```json
spec.cores: [6, 12]
spec.memory: [64, 128]
```

> 이 상태에서 cores=6 AND memory=128을 검색하면 ❌ \
→ **서로 다른 객체의 값이 섞여 매칭됩니다.** \
(즉, 존재하지 않는 조합이 나옴.)

### nested 사용
```json
spec[0] = {cores:6, memory:64}
spec[1] = {cores:12, memory:128}
```
nested로 색인하면 **각 객체(spec[0], spec[1])가 독립 문서처럼 분리**되어 \
“같은 객체 안의 값만 비교”하게 됩니다.

### 실제 서비스 사용처
| 상황                            | nested 필요    | 이유                             |
| ----------------------------- | ------------ | ------------------------------ |
| 상품의 여러 옵션 세트 (색상+사이즈+재고)      | ✅ 필요         | 옵션 세트 조합별로 정확 매칭               |
| 유저 프로필 안에 여러 자격증 {name, year} | ✅ 필요         | 같은 자격증 조합만 검색해야 함              |
| 로그 이벤트 안에 여러 key-value 쌍      | ⚠️ sometimes | log_type 단위로만 쿼리 시 유용          |
| 단순 배열 (tag, category 등)       | ❌ 불필요        | 단일 값 매칭만 하면 됨 (keyword 배열로 충분) |

### 💬 예시 요약
```json
PUT products
{
  "mappings": {
    "properties": {
      "options": {
        "type": "nested",
        "properties": {
          "color": { "type": "keyword" },
          "size":  { "type": "keyword" },
          "stock": { "type": "integer" }
        }
      }
    }
  }
}

POST products/_doc/1
{
  "name": "T-shirt",
  "options": [
    { "color": "red", "size": "M", "stock": 10 },
    { "color": "red", "size": "L", "stock": 5 },
    { "color": "blue", "size": "M", "stock": 8 }
  ]
}

GET products/_search
{
  "query": {
    "nested": {
      "path": "options",
      "query": {
        "bool": {
          "must": [
            { "term": { "options.color": "red" } },
            { "term": { "options.size": "M" } }
          ]
        }
      },
      "inner_hits": {}
    }
  }
}
```

### 💡  그렇다면 “복잡해서 못 쓰는 것 아닌가?”
실제 대규모 시스템에서는 아래 중 하나의 방식으로 **단순화**함.
| 접근 방식                 | 설명                                                         |
| --------------------- | ---------------------------------------------------------- |
| ✅ **데이터 미리 변형**       | 색인 전 단계(Logstash, ETL 등)에서 `color_size: "red_M"` 처럼 합쳐서 저장 |
| ✅ **정규화 분리 (별도 인덱스)** | 옵션이나 속성을 별도 인덱스로 저장 후 parent-child 관계로 조회                  |
| ⚙️ **nested 최소화**     | 꼭 필요한 필드(정확한 조합 검색)만 nested로 두고 나머지는 평면화                   |
