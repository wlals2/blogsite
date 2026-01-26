---
title: "Windows 가상화 충돌 해결: VMware & Docker Desktop"
date: 2025-10-09T10:36:27-04:00
draft: false
tags: ["troubleshooting", "windows", "virtualize", "vmware", "Hyper-V", "ssh", "WSL2"]
categories: ["study", "Troubleshooting", "Development"]
author: "지민 오"
description: "Windows에서 VMware, Docker Desktop, Hyper-V 간 가상화 충돌 문제 해결 방법과 AMD-V/VBS 설정 가이드"
---

## 개요

Windows에서 VMware(Ubuntu 22.04)와 Docker Desktop을 설치하는 과정에서 발생한 가상화 충돌 문제 해결 기록입니다. Docker Desktop은 KVM을 사용해야 하며, BIOS에서 Virtualized AMD-V를 설정했음에도 오류가 발생하여 실행되지 않는 문제를 해결했습니다.

---

## ✅ 현상 및 문제 발생 배경

### 💡 문제 상황

- VMware Workstation에서 VM 실행 시 **"Virtualized AMD-V/RVI is not supported on this platform"** 오류 발생
- Docker Desktop(WSL2 기반) 작동 불가
- VirtualBox에서도 유사한 가상화 지원 오류 발생
- BIOS에서는 SVM Mode(AMD-V)가 이미 Enabled로 설정됨
- 이전 Docker Desktop 설치 및 VirtualBox 혼합 사용으로 인해 설정이 꼬인 상태

### 💡 시스템 환경

- **CPU/메인보드**: AMD Ryzen 5 5600 + Aorus B550M (BIOS에서 가상화 활성화)
- **OS**: Windows 10
- **가상화 도구**: VirtualBox, Docker Desktop for Windows, VMware(Ubuntu 22.04)

---

## ✅ 원인: Windows 가상화 기능 (VBS/Hyper-V/WSL2)과 충돌

### 문제의 핵심

- **AMD-V**는 CPU의 하드웨어 가상화 기능
- **VMware, VirtualBox, Docker Desktop** 등은 이 기능을 직접 사용해야 성능 저하 없이 VM 실행 가능
- Windows에서 **Hyper-V, WSL2, Virtual Machine Platform, VBS(가상화 기반 보안), Credential Guard**가 켜져 있으면:
  - 하드웨어 가상화 자원을 Windows가 먼저 점유
  - VMware 등 타 가상화 소프트웨어는 AMD-V에 접근 불가
- **결과**: VMware/VirtualBox/Docker Desktop 모두 동시 사용 불가
- (Docker Desktop for Windows는 기본적으로 WSL2/Hyper-V에 의존)

### 🛑 주의사항

Docker Desktop for Windows를 한번이라도 설치했다면, 그 과정에서 설정이 변경되어 다른 가상화 프로그램 사용 시 문제가 발생하여 실행이 되지 않습니다.

---

## ✅ 문제 파악 과정

1. VMware/VirtualBox 실행 시 아래 오류 발생:
   ```

   Virtualized AMD-V/RVI is not supported on this platform.
   Continue without virtualized AMD-V/RVI?
   ```

2. `msinfo32` (시스템 정보)에서 **가상화 기반 보안: 실행 중** 표시

3. Docker Desktop에서 **WSL2/Hyper-V not available** 혹은 가상화 기능 충돌 메시지

## ✅ 문제 해결

### 1️⃣ BIOS 확인

SVM Mode (AMD-V) 옵션이 **Enabled** 상태인지 확인 → 하드웨어적으로 가상화 지원 정상

---

### 2️⃣ Windows 내 모든 가상화/보안 기능 비활성화

#### PowerShell 명령어 실행

**PowerShell을 관리자 권한으로 실행**하여 다음 명령어를 입력합니다:

```powershell
# Hyper-V 및 가상화 기능 비활성화
dism.exe /Online /Disable-Feature:Microsoft-Hyper-V-All
dism.exe /Online /Disable-Feature:VirtualMachinePlatform
dism.exe /Online /Disable-Feature:Microsoft-Windows-Subsystem-Linux
dism.exe /Online /Disable-Feature:Windows-Defender-ApplicationGuard

# 하이퍼바이저 부트 비활성화
bcdedit /set hypervisorlaunchtype off

```

#### Windows 기능 켜기/끄기에서 체크 해제

다음 항목들의 체크를 해제합니다:

- Hyper-V
- Windows 하이퍼바이저 플랫폼
- Virtual Machine Platform
- Windows Subsystem for Linux
- Windows Sandbox

#### 레지스트리 수정 (VBS 및 Credential Guard 비활성화)

`regedit`를 실행하여 다음 레지스트리 값을 수정합니다:

```

HKEY_LOCAL_MACHINE\SYSTEM\CurrentControlSet\Control\DeviceGuard
→ EnableVirtualizationBasedSecurity = 0 (DWORD)

HKEY_LOCAL_MACHINE\SYSTEM\CurrentControlSet\Control\Lsa
→ LsaCfgFlags = 0 (DWORD)

```

> **참고**: 해당 키나 값이 없으면 생성 후 설정합니다.

#### 시스템 재부팅

모든 설정 완료 후 **반드시 재부팅**합니다.

---

### 3️⃣ 결과 확인

재부팅 후 다음 사항을 확인합니다:

1. `msinfo32` 실행 → **'가상화 기반 보안: 사용 안 함'** 확인
2. VMware, VirtualBox 등에서 AMD-V(또는 VT-x) 오류 사라짐
3. 선택한 가상화 솔루션(VMware/VirtualBox 등) 정상 실행 가능

---

## 📋 최종 체크리스트

실전에서 사용할 수 있는 단계별 체크리스트입니다:

- [ ] BIOS에서 SVM Mode(AMD-V) 활성화
- [ ] Windows의 Hyper-V/WSL2/VM Platform 등 모든 가상화 옵션 비활성화
- [ ] 가상화 기반 보안(VBS), Credential Guard 완전 비활성화
- [ ] 코어 격리(메모리 무결성) 해제
- [ ] `bcdedit`로 하이퍼바이저 부트 옵션 완전히 OFF
- [ ] 재부팅 후 `msinfo32`로 VBS 상태 확인
- [ ] VMware 또는 VirtualBox 실행 테스트
- [ ] 원하는 가상화 솔루션 정상 작동 확인