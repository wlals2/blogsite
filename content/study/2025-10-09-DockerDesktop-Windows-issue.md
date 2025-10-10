---
title: "Docker Desktop Windows 컨테이너 모드 이슈 해결"
date: 2025-10-09T10:49:57-04:00
draft: false
tags: ["troubleshooting", "DockerDesktop", "issue", "npipe", "Windows", "Hyper-V"]
categories: ["Infra"]
author: "지민 오"
description: "Docker Desktop에서 npipe 마운트 오류 해결하기: Linux 컨테이너와 Windows 컨테이너 모드 전환 가이드"
---

## 개요

Docker Desktop을 Windows에서 사용하면서 `docker-compose` 파일 실행 시, 이미 만들어진 파일 및 현재 컨테이너 시스템에 맞지 않는 경우 제대로 구현이 되지 않는 문제를 겪었습니다. 이번 실습에서 겪었던 일과 해결 방법을 정리합니다.

---

## ❗ 오류 원인 분석

### 1. npipe는 Windows 컨테이너만의 특수한 마운트 방식

- **npipe 마운트**는 Windows OS의 named pipe 기능을 활용하여 Docker Engine과 같은 특수 서비스와 통신할 때 사용됩니다.
- Jenkins 등에서 Docker 빌드/컨테이너 실행을 제어하려면 Docker Engine에 직접 접근해야 하므로 이 마운트를 사용합니다.

### 2. Docker의 컨테이너 모드

Docker Desktop은 두 가지 컨테이너 모드를 지원합니다:

- **Linux 컨테이너 모드** (WSL2 기반, 기본값)
- **Windows 컨테이너 모드**

**중요**: Linux 컨테이너 모드에서는 **npipe 마운트가 지원되지 않습니다**.
→ Linux container를 Windows container로 변환해야 합니다.

### 3. 설치/환경 차이

- Docker Desktop 설치 시 **"Allow Windows containers to be used with this installation"** 옵션을 체크해야 컨테이너 모드 전환이 가능합니다.
- **Windows Home 에디션은 공식적으로 지원하지 않습니다** (Windows Pro/Enterprise 필요).

---

## 📝 정리

### 문제점

- Linux 컨테이너 모드에서 **npipe 마운트가 포함된 Compose 파일은 무조건 오류** 발생
- Compose override 파일에서 해당 볼륨 정의를 **"조건부"**로만 적용해야 함
- Linux 환경에서는 npipe 마운트가 아예 존재하지 않게 관리해야 함

### 해결 방법: Windows 컨테이너 모드 전환

1. 설치 시 옵션 체크
2. Hyper-V 및 Containers Windows 기능 활성화
3. **Windows Pro/Enterprise에서만 사용 가능**

---

## ✅ 해결 방법

Windows에서만 실행이 가능한 명령어나 기능(**npipe**, Windows 전용 이미지 등)이 있는 경우:

1. **Docker Desktop을 "Windows 컨테이너 모드"로 전환**
   - Docker Desktop 트레이 아이콘 우클릭 → **"Switch to Windows containers…"** 선택

2. **주의사항**
   - 이 모드 전환은 **Linux 컨테이너**와 **Windows 컨테이너** 중 **동시에 한 가지만 실행** 가능합니다.
   - 현재 컨테이너 형태가 맞지 않다면 Windows 관련 이미지 및 명령어가 실행되도록 switch해야 합니다.

---

## 📋 체크리스트

- [ ] Docker Desktop 설치 시 "Allow Windows containers to be used with this installation" 옵션 체크
- [ ] Hyper-V 및 Containers Windows 기능 활성화
- [ ] Windows Pro/Enterprise 에디션 확인
- [ ] Docker Desktop에서 "Switch to Windows containers" 실행
- [ ] npipe 마운트를 사용하는 docker-compose 파일 테스트