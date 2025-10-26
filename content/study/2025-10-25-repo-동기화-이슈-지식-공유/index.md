---
title: "repo 동기화 이슈 지식 공유"
date: 2025-10-26T12:10:36+09:00
draft: false
categories: ["repo","yum","linux"]
tags: ["ubuntu","repo","동기화","이슈","트러블 슈팅","시간"]
description: "repo 동기화 이슈 지식 공유"
author: "늦찌민"
---

---
## 🎯 오늘의 이슈
- 증상: `apt-get update` 중 `Hash Sum mismatch` 발생
- 영향: APT 인덱스 일부( 특히 `jammy-updates/universe i386 Packages`) 검증 실패 -> 업데이트 중단
- 시점 시점: (로그 기준) Release file created at: `Fri, 24 Oct 2025 21:48:16 +0000`
`Last modification reported: Fri, 24 Oct 2025 21:51:58 +0000`

> 증거 해시 값들이 달라 내용이 다른 버전과 교차됨 
`by-hash/SHA256/...` 경로에서 무결성 검증 실패.
---
## ✅ 이해
- 미러 동기화 레이스: **Release/Packages**가 부분적으로 갱신되는 짧은 구간에 우리가 `apt-get update`를 수행 -> CDN/미러/중간 캐시 어딘가 에서 **신·구 파일이 섞여** 내려오며 해시깨짐 



## 트러블 슈팅

1. **시간 확인/교정**: `timedatectl set-ntp true`
2. **APT 캐시 초기화**: 

    ```bash
    sudo rm -rf /var/lib/apt/lists/*
    sudo apt-get clean
    ```
>**기대 효과**: 미러 정리: http://us.archive.ubuntu.com → HTTPS 미러로 변경(예: https://archive.ubuntu.com/ubuntu 또는 지역 안정 미러).


## 알게된 점
- APT는 **Release에 기록된 해시와 실제 받은 파일의 해시가 조금이라도 다르면 즉시 실패**한다. 안전하지만, 갱신 직후 수분간은 레이스에 취약.
- **HTTPS 미러 사용**과 **APT 리스트 초기화**가 가장 빠른 복구 방식.
- 네트워크에 **투명 캐시/보안장비/프록시**가 끼어 있으면 이런 현상이 더 잘 난다. (WARP·Split Tunnel, 혹은 캐시 무력화로 경로를 단순화하면 확 줄어듦)


## 한 줄 요약
> 방금 릴리스된 미러 동기화 타이밍에 우리가 apt-get update를 때려서 신·구 인덱스가 섞였고, APT의 무결성 검증이 이를 잡아낸 사건.
HTTPS 미러 + 리스트 초기화로 즉시 해결, 다음엔 몇 분만 늦춰서 시도하거나 다른 HTTPS 미러로 바꾸면 끝.




