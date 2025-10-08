---
title: "Ubuntu 서버에서 NAS와 Samba 연동하기 (Windows·Linux 공유폴더 구축)"
date: 2025-10-08T21:00:00+09:00
draft: false
tags: ["NAS", "Samba", "Linux", "Ubuntu", "네트워크스토리지"]
categories: ["Infra", "Storage"]
weight: 97
---

## 개요

이번 글에서는 **Ubuntu 서버에 NAS 또는 Samba 공유를 연동하는 방법**을 정리합니다.  
주 목적은 다음과 같습니다:

- NAS를 **백업/저장소용으로 마운트**
- Windows와 Ubuntu 간 **공유폴더(Samba)** 운영
- Hugo 블로그 등 정적 웹 파일을 **양쪽 환경에서 동시에 관리**

---

## 1️⃣ NAS 마운트 방식 (NFS or CIFS)

NAS가 이미 네트워크에 연결되어 있고,  
Ubuntu에서 해당 NAS의 폴더를 **직접 마운트**해서 사용하는 방법입니다.

### 📂 1. NAS 공유폴더 준비

NAS 관리자 페이지에서 다음 중 하나를 활성화합니다:

| 프로토콜 | 권장 용도 | 비고 |
|-----------|-------------|------|
| **NFS** | Linux ↔ Linux | 빠르고 권한 관리 유리 |
| **SMB(CIFS)** | Windows ↔ Linux | 범용성 높음 |

예:  
- NFS 경로: `192.168.1.10:/volume1/blog_backup`  
- SMB 경로: `//192.168.1.10/share`

---

### ⚙️ 2. Ubuntu에서 마운트

#### ▪ NFS 방식
```bash
sudo apt install -y nfs-common
sudo mkdir -p /mnt/nas
sudo mount -t nfs 192.168.1.10:/volume1/blog_backup /mnt/nas
```
#### ▪ SMB(CIFS) 방식
```bash
sudo apt install -y cifs-utils
sudo mkdir -p /mnt/nas
sudo mount -t cifs //192.168.1.10/share /mnt/nas \
  -o username=nasuser,password=비밀번호,uid=jimin,gid=jimin
```

### 🔁 3. 부팅 시 자동 마운트 설정
/etc/fstab 파일 아래에 추가
#### ▪ NFS
```bash
192.168.1.10:/volume1/blog_backup /mnt/nas nfs defaults 0 0
```
#### ▪ SMB
```bash
# credentials 파일을 사용하는 방식이 더 안전
//192.168.1.10/share /mnt/nas cifs credentials=/etc/cifs-cred,noperm,uid=jimin,gid=jimin 0 0
```


## 2️⃣ Samba를 이용한 Windows ↔ Ubuntu 공유폴더
NAS가 없어도, Ubuntu 서버 자체를 “작은 NAS”처럼 만들어
Windows 탐색기에서 직접 접근할 수 있습니다.

### ⚙️ 1. Samba 설치 및 폴더 준비
```bash
sudo apt update
sudo apt install -y samba
sudo mkdir -p /home/jimin/share
sudo chown -R jimin:jimin /home/jimin/share
```

### ⚙️ 2. Samba 설정 파일 수정
```bash
sudo vi /etc/samba/smb.conf
# 맨 아래에 추가
[blogshare]
   comment = Hugo Blog 공유폴더
   path = /home/jimin/share
   browseable = yes
   read only = no
   writable = yes
   guest ok = no
   valid users = jimin
   create mask = 0664
   directory mask = 0775
```
### 🔐 3. Samba 사용자 등록
```bash
sudo smbpasswd -a jimin
sudo systemctl restart smbd
sudo systemctl enable smbd
```
### 🌐 4. 방화벽 열기
```bash
sudo ufw allow samba
sudo ufw reload
```

### 💻 5. Windows에서 접근하기
```bash
\\192.168.1.10\blogshare
```

### ✅ 결론
- NAS 마운트는 백업·저장소 중심으로,
Samba 공유는 실시간 협업용(Windows↔Ubuntu)으로,
- 두 방법을 병행하면 로컬·원격에서 모두 손쉽게 Hugo 블로그나 개발 리소스를 관리할 수 있습니다.

