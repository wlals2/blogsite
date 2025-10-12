---
title: "K8s Observability Stack 구축 및 mysqld-exporter 트러블슈팅"
date: 2025-10-12T10:10:33-04:00
draft: false
categories: ["Kubernetes", "Monitoring"]
tags: ["Grafana", "Prometheus", "Exporter", "Helm", "Troubleshooting"]
description: "모니터링 시스템을 컨테이너 말아보자"
author: "늦찌민"
series: ["좀 더 고급으로 변하는 인프라 구조"]
---


## 🎯 목표
Kubernetes 환경에서 **Grafana + Prometheus + Exporter** 기반 모니터링 스택을 Helm 템플릿으로 구축하고,  
`mysqld-exporter` CrashLoopBackOff 문제를 해결하는 과정을 정리한다.

---
## ✅ values.yaml
```yaml
prometheus:
  enabled: true
  nodePort: 30900
  storage: 1Gi
  config: |
    global:
      scrape_interval: 15s
    scrape_configs:
      - job_name: 'prometheus'
        static_configs:
          - targets: ['localhost:9090']
      - job_name: 'mysqld-exporter'
        static_configs:
          - targets: ['mysqld-exporter:9104']
      - job_name: 'node-exporter'
        static_configs:
          - targets: ['node-exporter:9100']
      - job_name: 'nginx-exporter'
        static_configs:
          - targets: ['nginx-exporter:9113']

grafana:
  enabled: true
  nodePort: 30300
  storage: 1Gi

nodeexporter:
  enabled: true
  nodePort: 31000

nginxexporter:
  enabled: true
  nodePort: 31113
  nginxScrapeUri: "http://nginx:80/stub_status"

mysqldexporter:
  enabled: true
  nodePort: 31004
```

## 🧩 Grafana 구성

**📁 `/home/ubuntu/test/company-infra/templates/grafana-deployment.yaml`**

```yaml
{{- if .Values.grafana.enabled }}
apiVersion: apps/v1
kind: Deployment
metadata:
  name: grafana
  namespace: company-infra
spec:
  replicas: 1
  selector:
    matchLabels:
      app: grafana
  template:
    metadata:
      labels:
        app: grafana
    spec:
      containers:
        - name: grafana
          image: grafana/grafana
          volumeMounts:
            - name: grafana-data
              mountPath: /var/lib/grafana
          ports:
            - containerPort: 3000
      volumes:
        - name: grafana-data
          persistentVolumeClaim:
            claimName: grafana-pvc
{{- end }}
```
### 📁 grafana-pvc.yaml
```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: grafana-pvc
  namespace: company-infra
spec:
  accessModes: [ "ReadWriteOnce" ]
  resources:
    requests:
      storage: {{ .Values.grafana.storage }}
  storageClassName: local-path
```

### 📁grafana-service.yaml
```yaml
apiVersion: v1
kind: Service
metadata:
  name: grafana
  namespace: company-infra
spec:
  type: NodePort
  ports:
    - port: 3000
      targetPort: 3000
      nodePort: {{ .Values.grafana.nodePort }}
  selector:
    app: grafana
```
---
## 🧠 Prometheus 구성
### 📁 prometheus-configmap.yaml
```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: prometheus-config
  namespace: company-infra
data:
  prometheus.yml: |-
    {{ .Values.prometheus.config | nindent 4 }}
```

### 📁 prometheus-deployment.yaml
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: prometheus
  namespace: company-infra
spec:
  replicas: 1
  selector:
    matchLabels:
      app: prometheus
  template:
    metadata:
      labels:
        app: prometheus
    spec:
      containers:
        - name: prometheus
          image: prom/prometheus
          volumeMounts:
            - name: prometheus-data
              mountPath: /prometheus
            - name: prometheus-config
              mountPath: /etc/prometheus/prometheus.yml
              subPath: prometheus.yml
          ports:
            - containerPort: 9090
      volumes:
        - name: prometheus-data
          persistentVolumeClaim:
            claimName: prometheus-pvc
        - name: prometheus-config
          configMap:
            name: prometheus-config
```
### 📁 prometheus-PVC.yaml
```yaml
{{- if .Values.prometheus.enabled }}
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: prometheus-pvc
  namespace: company-infra
spec:
  accessModes: [ "ReadWriteOnce" ]
  resources:
    requests:
      storage: {{ .Values.prometheus.storage }}
  storageClassName: local-path
{{- end }}
```

### 📁 prometheus-service.yaml
```yaml
{{- if .Values.prometheus.enabled }}
apiVersion: v1
kind: Service
metadata:
  name: prometheus
  namespace: company-infra
spec:
  type: NodePort
  ports:
    - port: 9090
      targetPort: 9090
      nodePort: {{ .Values.prometheus.nodePort }}
  selector:
    app: prometheus
{{- end }}
```

## 🛰 Exporter 구성
```yaml 
 # 📁 node-exporter-deployment.yaml
{{- if .Values.nodeexporter.enabled }}
apiVersion: apps/v1
kind: Deployment
metadata:
  name: node-exporter
  namespace: company-infra
spec:
  replicas: 1
  selector:
    matchLabels:
      app: node-exporter
  template:
    metadata:
      labels:
        app: node-exporter
    spec:
      containers:
        - name: node-exporter
          image: prom/node-exporter:latest
          ports:
            - containerPort: 9100
{{- end }}


 # 📁 node-exporter-service.yaml
{{- if .Values.nodeexporter.enabled }}
apiVersion: v1
kind: Service
metadata:
  name: node-exporter
  namespace: company-infra
spec:
  type: NodePort
  ports:
    - port: 9100
      targetPort: 9100
      nodePort: {{ .Values.nodeexporter.nodePort }}
  selector:
    app: node-exporter
{{- end }}


 # 📁 mysql-exporter-deployment.yaml
{{- if .Values.mysqldexporter.enabled }}
apiVersion: apps/v1
kind: Deployment
metadata:
  name: mysqld-exporter
  namespace: company-infra
spec:
  replicas: 1
  selector:
    matchLabels:
      app: mysqld-exporter
  template:
    metadata:
      labels:
        app: mysqld-exporter
    spec:
      containers:
        - name: mysqld-exporter
          image: prom/mysqld-exporter:v0.14.0
          args:
            - --config.my-cnf=/root/.my.cnf
          volumeMounts:
            - name: mycnf
              mountPath: /root/.my.cnf
              subPath: my.cnf
          ports:
            - containerPort: 9104
          securityContext:
            runAsUser: 0
      volumes:
        - name: mycnf
          secret:
            secretName: mysqld-mycnf
{{- end }}


 # 📁 mysql-exporter-service.yaml
{{- if .Values.mysqldexporter.enabled }}
apiVersion: v1
kind: Service
metadata:
  name: mysqld-exporter
  namespace: company-infra
spec:
  type: NodePort
  ports:
    - port: 9104
      targetPort: 9104
      nodePort: {{ .Values.mysqldexporter.nodePort }}
  selector:
    app: mysqld-exporter
{{- end }}


 # 📁 nginx-exporter-deployment.yaml
{{- if .Values.nginxexporter.enabled }}
apiVersion: apps/v1
kind: Deployment
metadata:
  name: nginx-exporter
  namespace: company-infra
spec:
  replicas: 1
  selector:
    matchLabels:
      app: nginx-exporter
  template:
    metadata:
      labels:
        app: nginx-exporter
    spec:
      containers:
        - name: nginx-exporter
          image: nginx/nginx-prometheus-exporter:0.11.0
          args:
            - "--nginx.scrape-uri={{ .Values.nginxexporter.nginxScrapeUri }}"
          ports:
            - containerPort: 9113
{{- end }}


 # 📁 nginx-exporter-service.yaml
{{- if .Values.nginxexporter.enabled }}
apiVersion: v1
kind: Service
metadata:
  name: nginx-exporter
  namespace: company-infra
spec:
  type: NodePort
  ports:
    - port: 9113
      targetPort: 9113
      nodePort: {{ .Values.nginxexporter.nodePort }}
  selector:
    app: nginx-exporter
{{- end }}
```
---

## 🔓 트러블 슈팅
### 트러블 슈팅은 Prometheus 에서 mysqld-exporter가 문제



- 기본 구성 및 증상:

> Prometheus Target에 mysqld-exporter가 노출되지 않으며 off되어 있음 \
Pod 상태는 `CrashLoopBackoff` \
로그인  `failed to validate config`  또는  `no user specified in section or parent` 
 

- mysqld-exporter 버전 호환성 문제
- pod 내부에서 파일 실제로 마운트 되었는지 확인
- Permission Denied 컨테이너 실행 유저 문제

**1. my.cnf 파일 Secret 마운트 경로 문제**
- my.cnf 시크릿이 올바르게 생성/마운트/오타 확인

```bash
# .my.cnf
[client]
user=root
password=supersecret
host=mysql

# secret 생성
kubectl create secret generic mysqld-mycnf --from-file=my.cnf=./my.cnf -n company-infra

# Deployment 마운트 추가
volumeMounts:
  - name: mycnf
    mountPath: /etc/mysql/my.cnf
    subPath: my.cnf
```
→  여전히 Pod Crash 동일에러

- Pod 내부에서 파일 확인
```yaml
# busybox.yaml
apiVersion: v1
kind: Pod
metadata:
  name: mycnf-debug
  namespace: company-infra
spec:
  containers:
    - name: busybox
      image: busybox
      command: [ "sleep", "3600" ]
      volumeMounts:
        - name: mycnf
          mountPath: /root/.my.cnf
          subPath: my.cnf
  volumes:
    - name: mycnf
      secret:
        secretName: mysqld-mycnf
        
# 실행 및 진입해서 확인
kubectl apply -f busybox.yaml
kubectl exec -it mycnf-debug -n company-infra -- cat /root/.my.cnf
```
→ 파일 정상확인 내용 문제는 없음
### 2. mysqld-exporter 버전 호환성 문제
```yaml
# /home/ubuntu/test/company-infra/mysqld-exporter-deployment.yaml
image: prom/mysqld-exporter:v0.14.0
```
→  Pod는 Crash였으나 log 내용이 Permission Denied 에러로 바뀜 config 설정이 사라짐

- **에러로그**
```bash
Error parsing my.cnf file=/root/.my.cnf err="failed reading ini file: open /root/.my.cnf: permission denied"


# /home/ubuntu/test/company-infra/templates/mysqld-exporter-deployment.yaml
securityContext:
  runAsUser: 0
```

 

`제대로된 마운트 설정과 함께 root 권한으로 container 실행으로 문제를 해결 하였다.`

 

> 🔥 이번에는 google 검색을 통해 커뮤니티 내용 글을 보고 버그를 눈치 챘다. \
이것 역시 찾아보지 않았다면 똑같은 행위를 계속 하고 있을 것이다. \
쌓이는 경험으로 점차 정확하고 빠르게 해결하는 것 같다.