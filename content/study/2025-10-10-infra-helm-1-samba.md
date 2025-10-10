---
title: "온프레미스 vs 클라우드 인프라: 내가 직접 비교해봤다 — 좀 더 고급으로 변하는 인프라 구조 (Helm, Values 1)"
date: 2025-06-27T00:50:00+09:00
author: "늦찌민"
slug: "infra-helm-values-1-samba-k8s"
categories: ["Infrastructure", "Kubernetes", "Helm"]
tags: ["on-prem", "cloud", "helm", "values.yaml", "kubernetes", "samba", "PVC", "PV", "NodePort", "HostPort", "troubleshooting"]
series: ["좀 더 고급으로 변하는 인프라 구조"]
draft: false
---

> 하루하루 새로운 것을 배워나가는 기분이다.  
> 이론서로 배운 내용을 실제로 구현해 보며 더 깊은 깨달음을 얻고, 그 속에서 보이는 것들이 분명해졌다.

<!--more-->

## 🎯 목표

- **Kubernetes에서 Samba 서버 구현**
- **트러블슈팅(특히 NodePort ↔ Windows SMB 통신 문제)**
- **PVC/PV 동적·정적 프로비저닝 이해 심화**

## 📦 기본 디렉터리 구성

```text
~/test/company-infra/
├─ templates/                    
│   ├─ mysql/
│   ├─ prometheus/
│   ├─ grafana/
│   ├─ apache2/
│   ├─ ftp/
│   ├─ mysqld-exporter/
│   ├─ nginx/
│   ├─ openvpn/
│   ├─ samba/
│   └─ jenkins/
├─ charts/
├─ values.yaml
├─ Chart.yaml
├─ helm-chart
├─ docker-compose/
│   └─ docker-compose.yml (SFTP)

~/test/company-infra-c/
├─ templates/
│   ├─ Elasticsearch/
│   ├─ Fluentbit/
│   └─ Kibana/
├─ charts/                    
├─ values.yaml
├─ Chart.yaml
└─ helm-chart
```

> �� Helm에서 템플릿 경로는 `templates/`로 맞춰야 하며, 오타(`tmemplates`)는 Chart 빌드 시 에러가 납니다.

## ⚙️ K8s — Samba (HostPort + Static PV/PVC)

NodePort로 SMB(445/139)에 접근 시 Windows는 고정 포트만 사용하므로 세션 성립이 어려울 수 있다.  
본 실습은 **HostPort**로 139/445를 직접 매핑하고, **정적 PV/PVC**로 `/srv/samba-share`를 마운트한다.

### Helm 실행

```bash
# 네임스페이스 생성
kubectl apply -f templates/namespace.yaml

# 노드에서 실제 디렉터리 준비
sudo mkdir -p /srv/samba-share && sudo chmod 0777 /srv/samba-share

# 설치
helm install company-infra ./

# 확인
kubectl -n company-infra get pods,svc,pv,pvc
```

## 🔓 트러블슈팅 핵심

- **문제**: NodePort(30445/30139)로는 Windows에서 SMB 세션 미성립 (0x80004005)
- **원인**: Windows SMB는 139/445 고정 포트에 민감
- **해결**: HostPort(139/445) 사용 및 정적 PV/PVC로 실제 경로 마운트

## ✅ K8s - Samba 서버 구현 및 트러블 슈팅
### 이번은 트러블 슈팅 과정 내에서 K8s nodeport로는 windows까지 닿지 못하는 경험을 하였다.
**Windows**는 고정 포트 번호를 사용하기 때문일 것이다. PortFowarding Port로는 절대  도달하지 못하였다.

---

 ### k8s - Samba
�� samba 도 FTP 와 같이 docker-compose로 구현하는 것이 더 나을 거라는 생각이 들었다.\
PVC 볼륨 외에도 nodeport 때문에 문제가 생기기 때문이다. \
이번엔 host port 를 사용하여 로컬 리눅스 포트 번호와 클라우드 포트번호를 native하게 연결이 되면서 해결이 되었다. 

```yaml
# test/company-infra/values.yaml
samba:
  enabled: true
  hostPath: /srv/samba-share
  nodePorts: [30139, 30445]
  storage: 1Gi
  config: |
    [share]
    path = /share
    read only = no
    browsable = yes
    guest ok = yes


# test/company-infra/templates/samba-configmap.yaml
{{- if .Values.samba.enabled }}
apiVersion: v1
kind: ConfigMap
metadata:
  name: samba-config
  namespace: company-infra
data:
  smb.conf: |-
    {{ .Values.samba.config | nindent 4 }}
{{- end }}


# test/company-infra/templates/samba-deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: samba
  namespace: company-infra
spec:
  replicas: 1
  selector:
    matchLabels:
      app: samba
  template:
    metadata:
      labels:
        app: samba
    spec:
      containers:
        - name: samba
          image: dperson/samba
          args: ["-s", "share;/share;yes;no;yes"]
          ports:
            - containerPort: 139
              hostPort: 139
            - containerPort: 445
              hostPort: 445
          volumeMounts:
            - name: samba-data
              mountPath: /share
      volumes:
        - name: samba-data
          persistentVolumeClaim:
            claimName: samba-pvc

# test/company-infra/templates/samba-pvc.yaml
{{- if .Values.samba.enabled }}
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: samba-pvc
  namespace: company-infra
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: {{ .Values.samba.storage }}
  volumeName: samba-pv
  storageClassName: ""
{{- end }}


#  test/company-infra/templates/samba-pv.yaml
{{- if .Values.samba.enabled }}
apiVersion: v1
kind: PersistentVolume
metadata:
  name: samba-pv
spec:
  capacity:
    storage: {{ .Values.samba.storage }}
  accessModes:
    - ReadWriteOnce
  persistentVolumeReclaimPolicy: Retain
  hostPath:
    path: {{ .Values.samba.hostPath }}
    type: DirectoryOrCreate
{{- end }}
~

# test/company-infra/templates/samba-service.yaml
{{- if .Values.samba.enabled }}
apiVersion: v1
kind: Service
metadata:
  name: samba
  namespace: company-infra
spec:
  type: NodePort
  ports:
    - name: netbios
      port: 139
      targetPort: 139
      nodePort: 30139
    - name: smb
      port: 445
      targetPort: 445
      nodePort: 30445
  selector:
    app: samba
{{- end }}

# 실행
helm install company-infra /home/ubuntu/test/company-infra/
```

 > 🔥  이번 실습으로 우리가 알아갈 수 있는 건 굉장히 중요하다고 생각한다.\
> 그리고 이것이 **PVC / PV** 높은 이해도를 줄 것이다. \
> 글 내용을 바탕으로 실습을 해보면서 생각해보고 느껴보면서 하면 좋겠다. 트러블 슈팅 내용과 함께 같이  적어 보겠다.

## 🔓 트러블 슈팅

### 초기 구성 

> - 쿠버네티스에 dperson/samba 이미지로 Samba 배포\
> - /Share 디렉터리를 PVC로 마운트해 윈도우 네트워크 드라이브 NodePort(30445/30139) 로 오픈\
> - 윈도우 에서 \\192.168.56.103\share 접근시 네트워크 경로 찾을 수 없음 (0x80004005)\
### Samba Pod 정상 

> - kubectl get pods, kubectl logs  pod 상태 및 samba 서비스 가동\
> - /share 디렉터리 퍼미션/마운트 상태 ( ls -ld /share, touch /share/testfile)\
> - smb.conf 주입시 ConfigMap subPath 사용으로 인해 read-onlt file system 에러 발생 
→ smb.conf 직접 마운트 대신 args로만 공유 설정
### 문제 파악

> - windows 에서는 30445 포트를 인식 그러나 445 포트를 인식하지 못함 \
> - iptables PREROUTING, socat  등으로 포트포워딩 시도 해봤지만 실패  (windows 자체 설정 때문이라고 예상) \
> - wireshark를 이용하여 SYN 과정 까지만 전송 그러나 ACK를 받거나 SMB 요청이 없음
→ SMB 세션 수립 자체가 불과
### 해결

> - HostPort를 사용하기로 하였다. \
> - → HostPort는   windows는 Container Port가아닌 local port로 접근 하게 해준다. Container 에서 직접 로컬이랑 포트 번호를 매칭 하기 때문이다.
PVC  동적 프로비저닝을 정적 으로 바꾸어 실제 Linux 시스템 접근할 수 있게 하였다.

![alt text](image.png)


### 🎤 이로써 우리는 K8s - Samba 실습을 맞추었다. PV/PVC 더 깊은 이해가 생겼을 거라 믿는다. 밑은 PV/PVC 간단하게 정리 한 것이다. 실습 정리를 서로 대입해 비교해보면 좋을 것 같다.

 

 

### PV(PersistentVolume)
```
관리자(운영자)가 직접 만들고 만드는 실제 스토리지 리소스
쿠버네티스 클러스터에 스토리지 풀로 등록
ex) /srv/samba-share,NFS,AWS EBS 등
```
### PVC(PersistentVolumeClaim)
```
개발자/앱이 클러스터에 스토리지를 요청 하는 객체
```
### 동적 프로비저닝
```
PVC에 storageClassName 지정하면  클러스터에 자동으로 PV를 생성/바인딩
```
### 정적 프로비저닝
```
미리 만들어진 PV에 PVC가 원하는 속성 (storage,accessMode) 등과 맞으면 매칭된다.  volumeName으로 PVC 지정이 가능하다.
```

### 🔥 우리는 기본적으로 동적 프로비저닝을 사용했으며 Samba에서는 정적 프로비저닝을 사용하였다.

> 확연한 차이는 정적프로비저닝을 통해 로컬 경로를 직접 정했다는 것이다.
> Samba / FTP 똑같이 직접 정한 경로를 사용할 수 있다면 많은 고민들이 해결 될 수 있다.
> 우리가 실질적으로 구현 해보면서도 느낄 수 있을 것이다 .
> 만약 동적을 계속 구현했다면 helm으로 내려갈때마다 우리는 경로 지정을 했을 것이다.
> 심지어 이미지가 환경설정으로 해주는 것이라면 또 다른 문제를 야기할 수도 있다.

 

### 동적  vs 정적  서로를 비교할 수있는 YAML 예시를 보여주며 Samba 실습을 마무리 하겠다.\

```yaml
# 동적프로비저닝
kind: PersistentVolumeClaim
spec:
  storageClassName: local-path
  accessModes: [ReadWriteOnce]
  resources:
    requests:
      storage: 1Gi


# 정적프로비저닝
# PV
kind: PersistentVolume
spec:
  hostPath:
    path: /srv/samba-share
  capacity:
    storage: 10Gi
  accessModes: [ReadWriteOnce]
  persistentVolumeReclaimPolicy: Retain

---
# PVC
kind: PersistentVolumeClaim
spec:
  accessModes: [ReadWriteOnce]
  resources:
    requests:
      storage: 10Gi
  volumeName: samba-pv  # 이 PV와 매칭!
  storageClassName: ""
  ```

 
#### In / Out 요약
| In(시도/설정) | Out(관찰/결론) |
|---|---|
| NodePort로 SMB 노출 | Windows는 139/445 고정 포트만 사용 → 세션 실패(0x80004005) |
| ConfigMap로 `smb.conf` 주입 | Read-only 충돌 가능 → 컨테이너 `args`로 공유 설정이 안전 |
| HostPort + 정적 PV/PVC | 로컬 경로 직접 매핑, 접근·디버깅 용이 |



<details>
<summary><strong>작성 체크리스트 (펼치기)</strong></summary>

- [ ] **환경 요약**: K8s 버전, 노출 방식(NodePort/HostPort), 스토리지 타입  
- [ ] **재현 절차**: 명령어/매니페스트/변수값(복붙 가능하게)  
- [ ] **증상 근거**: `kubectl logs`, 이벤트, 패킷 흐름(캡처/스크린샷)  
- [ ] **가설/반증**: 왜 실패했는지, 다른 방법은 어땠는지  
- [ ] **최종 설정**: 최종 YAML/명령어 + 채택 이유(트레이드오프 포함)  
- [ ] **보안/운영 메모**: 권한·포트·확장성·백업 관점  
- [ ] **회고**: 배운 점, 다음에 개선할 것(액션 아이템)
</deta

