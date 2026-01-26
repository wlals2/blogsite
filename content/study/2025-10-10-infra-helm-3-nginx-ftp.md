---
title: "『온프레미스 vs 클라우드 인프라: 내가 직접 비교해봤다』 — 『좀 더 고급으로 변하는 인프라 구조』 Helm, Values (3)"
subtitle: "좀 더 고급으로 변하는 인프라 구조 - Helm, Values ③"
date: 2025-10-10T16:34:00+09:00
author: "늦찌민"
tags: ["Kubernetes", "Helm", "Nginx", "SFTP", "MySQL", "Troubleshooting"]
categories: ["study", "Kubernetes"]
description: "낯선 K8s 환경에서 Nginx, SFTP, MySQL을 Helm 기반으로 배포하며 겪은 시행착오와 해결 방법 정리"
series: ["좀 더 고급으로 변하는 인프라 구조"]
---

낯선 환경에서 트러블슈팅하는 건 리눅스 나라에서 **K8s**로 이사 간 느낌이다.  
모든 게 낯설다. 하지만 해야 한다.

---

## 🎯 목표

- k8s-nginx 트러블슈팅 및 구현  
- k8s-ftp 트러블슈팅 및 (방향 전환 포함) 구현  
- k8s-mysql 트러블슈팅 및 구현

---

## 📦 기본 구성

```

~/test/company-infra/
├─ templates/
│ ├─ mysql/
│ ├─ prometheus/
│ ├─ grafana/
│ ├─ apache2/
│ ├─ ftp/
│ ├─ mysqld-exporter/
│ ├─ nginx/
│ ├─ openvpn/
│ ├─ samba/
│ └─ jenkins/
├─ charts/
├─ values.yaml
├─ Chart.yaml
├─ helm-chart/
├─ docker-compose/
│ └─ docker-compose.yml (SFTP)

~/test/company-infra-c/
├─ templates/
│ ├─ Elasticsearch/
│ ├─ Fluentbit/
│ └─ Kibana/
├─ charts/
├─ values.yaml
├─ Chart.yaml
└─ helm-chart/

```

🎤 서비스를 하나하나 구현하고 있다. 쉽지 않다.

```

온프레미스 환경이랑 완전히 다르다. 온프레 미스에서는 명령어 한줄 만 치거나 마운트 한번이면 되는 것들이 여기서는 순서 환경 실행가능한지  다 확인하여야한다.
apt-repo 처럼 다듬어져 있는 파일들이 아니다보니 임의적으로 다듬어야한다.
하지만 나의 성장이 느껴지는 실습이었다.

```

## ⚙️ Nginx 구축 및 트러블 슈팅

🎤 온프레미스에서 nginx는 그저 repo를 통한 설치 이후 service start만 하면 되는 수준이다. \
하지만  k8s에서는 맞는 환경과 동시에 그 환경이 잘 조잘되도록 설정 해줘야한다.이 과정 중에 하나라도 문제가 발생하면 제대로 작동되지 않는다.

### ✅  Nginx  구현 및 트러블 슈팅

```yaml
# /home/ubuntu/test/company-infra/values.yaml
nginx:
  enabled: true
  nodePort: 30888
  config: |
    user  nginx;
    worker_processes  auto;
    error_log  /var/log/nginx/error.log warn;
    pid        /var/run/nginx.pid;
    events {
      worker_connections 1024;
    }
    http {
      include       /etc/nginx/mime.types;
      default_type  application/octet-stream;
      sendfile        on;
      keepalive_timeout  65;
      server {
        listen       80;
        server_name  localhost;
        location / {
          root   /usr/share/nginx/html;
          index  index.html index.htm;
        }

        location /stub_status{
          stub_status;
          allow all;
          access_log off;
        }
      }
    }
  storage: 1Gi

  
# /home/ubuntu/test/company-infra/templates/nginx-configmap.yaml
{{- if .Values.nginx.enabled }}
apiVersion: v1
kind: ConfigMap
metadata:
  name: nginx-config
  namespace: company-infra
data:
  nginx.conf: |-
    {{ .Values.nginx.config | nindent 4 }}
{{- end }}


# /home/ubuntu/test/company-infra/templates/nginx-deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: nginx
  namespace: company-infra
spec:
  replicas: 1
  selector:
    matchLabels:
      app: nginx
  template:
    metadata:
      labels:
        app: nginx
    spec:
      initContainers:
        - name: init-html
          image: busybox
          command: ['sh','-c','echo "<h1>Welcome to nginx!</h1>" > /mnt/index.html']
          volumeMounts:
            - name: webroot
              mountPath: /mnt
          secuityContext:
            runAsUser: 0

      containers:
        - name: nginx
          image: nginx:latest
          ports:
            - containerPort: 80
          volumeMounts:
            - name: nginx-conf
              mountPath: /etc/nginx/nginx.conf
              subPath: nginx.conf
            - name: webroot
              mountPath: /usr/share/nginx/html
      volumes:
        - name: nginx-conf
          configMap:
            name: nginx-config
        - name: webroot
          persistentVolumeClaim:
            claimName: nginx-pvc



# /home/ubuntu/test/company-infra/templates/nginx-pvc.yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: nginx-pvc
  namespace: company-infra
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: {{ .Values.nginx.storage }}
  storageClassName: local-path

# /home/ubuntu/test/company-infra/templates/nginx-service.yaml
{{- if .Values.nginx.enabled }}
apiVersion: v1
kind: Service
metadata:
  name: nginx
  namespace: company-infra
spec:
  type: NodePort
  ports:
    - port: 80
      targetPort: 80
      nodePort: {{ .Values.nginx.nodePort }}
  selector:
    app: nginx
{{- end }}

# /home/ubuntu/test/
helm install company-infra company-infra

```

🎤 온프레미스에서 nginx를 구동하는 거랑은 차원이 다르다. 신경써야할 부분이나 설정하는 부분 모든 게 다르다. 
지금부터는 어떻게 설정을 했는지 어떤 식으로 생각을 잡으면 되는지 적어보겠다.

### 🚩 k8s-nginx 목표와 구조
nginx를 k8s( PVC, ConfigMap ), helm  사용 코드 기반 인프라로 완전히 배포

- nginx.conf:values.yaml  → ConfigMap으로 주입
- /usr/share/nginx/html: PVC 마운트, index.html 제공
- prometheus-nginx-exporter로 /stub_status를 통해 모니터링
 