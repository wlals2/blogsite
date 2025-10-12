---
title: "『온프레미스 vs 클라우드 인프라: 내가 직접 비교해봤다』 — 『좀 더 고급으로 변하는 인프라 구조』 Helm, Values (4)"
subtitle: "좀 더 고급으로 변하는 인프라 구조 - Helm, Values ④"
date: 2025-10-10T16:37:00+09:00
author: "늦찌민"
tags: ["Kubernetes", "Helm", "Apache", "PVC", "Troubleshooting"]
description: "K8s 구조 이해와 Apache2 Helm 배포를 중심으로, values.yaml 기반 구성과 PVC 선택 판단까지 트러블슈팅 기록"
series: ["좀 더 고급으로 변하는 인프라 구조"]
---

쉽지 않은 여정이었다.  
처음 보는 에러 메시지에 적응하느라 시간이 걸렸지만, 그 속에서도 **컨테이너·K8s 이해**가 깊어졌다.

---

## 🎯 목표

- K8s 구조에 대한 이해
- Apache2 설치 트러블슈팅
- Apache2에 대한 더 깊은 이해

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

매번 구조가 바뀌니 혼동될 수 있다. 하지만 **새 방식을 도입하고 비교**하는 과정 자체가 실무 적응 훈련이었다.  
실수로 `rm`으로 파일을 날린 뒤, 자연스럽게 **Git push** 흐름을 정착시킨 것도 수확.  
여러 환경을 몸으로 겪으며 차이를 느낀다.

---

## ⚙️ Apache2: values.yaml 포함한 Helm 배포

우리가 다시 시작하는 방식은 **values.yaml에 설정을 모으는 패턴**이다.  
실무에선 서비스·파일이 무수히 많다. 그래서 템플릿(YAML)에서 **상수·환경값을 values로 끌어올려** 관리한다.

### ✅ values.yaml을 쓰는 이유

- **코드·설정 분리**: 하드코딩 최소화, 변수화로 가독성/관리성 향상  
- **환경별 대응**: 동일 템플릿을 여러 환경에 재사용 (코드 변경 없이 값만 교체)  
- **유지보수·협업**: 팀 단위 표준화와 빠른 롤백/수정  
- **보안 연계**: 민감정보는 `Secret`/외부 Vault와 연동해 안전하게 주입

---
```
🎤 매 번 구조가 바뀌기에 혼동될 수 있을 거라고 생각한다.
새로운 방식을 계속 도입하면서 바꿔야하는 환경은 실무를 이해하는데 아주 좋은 적응 훈련이라고 생각한다.
실질적으로 구현을 하면 어떤 차이점이 생기는지 이번 방식은 어떤 것이 다른지 확인 할 수 있으며 
자연스럽게 장점을 느끼게 된다.
이번 실습 과정에서 실수로 rm을 통해 모든 파일을 지워버렸다.
그럼으로써 자연스럽게 git code push 적용해 넣었다.
다양한 환경을 실습하면 차이를 몸으로 느낄 것이라고 믿는다.
```

### valeus.yaml 장점

- 코드와 설정의 분리
직접 하드코딩하지 않고 값을 변수로 빼서 관리가능
다양한 환경에 대응이 가능
- 재사용성과 유지보수 향상
동일한 템플릿을 여러환경 적용 가능 
코드 수정 없이 환경마다 다른 설정으로 빠르게 배포 가능
- 협업과 표준화
여러 개발자/운영자 설정값을 통일성 있게 관리가능
인프라.배포 파이프라인 표준에 도움을준다.
- 보안 및 민감 정보 관리 (Secret 연동)
민감한 정보는 kubenetes secret이나 외부 valut와 연계해서 관리

### ✅ apache2 values.yaml / apache2.yaml 파일 배포 
```yaml
# /home/ubuntu/test/company-infra/values.yaml
apache2:
  enabled: true
  nodePort: 30880
  config: |
    ServerRoot "/usr/local/apache2"
    Listen 80
    LoadModule mpm_event_module modules/mod_mpm_event.so
    LoadModule unixd_module modules/mod_unixd.so
    LoadModule authz_core_module modules/mod_authz_core.so
    LoadModule authz_host_module modules/mod_authz_host.so
    ServerName localhost
    DocumentRoot "/usr/local/apache2/htdocs"
    <Directory "/usr/local/apache2/htdocs">
        Require all granted
    </Directory>
    ErrorLog /proc/self/fd/2
    LogLevel warn
    

# /home/ubuntu/test/company-infra/templates/apache2-config.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: apache2-config
  namespace: company-infra
data:
  httpd.conf: |
    ServerRoot "/usr/local/apache2"
    Listen 80
    LoadModule mpm_event_module modules/mod_mpm_event.so
    LoadModule unixd_module modules/mod_unixd.so
    LoadModule authz_core_module modules/mod_authz_core.so
    LoadModule authz_host_module modules/mod_authz_host.so
    ServerName localhost
    DocumentRoot "/usr/local/apache2/htdocs"
    <Directory "/usr/local/apache2/htdocs">
        Require all granted
    </Directory>
    ErrorLog /proc/self/fd/2
    LogLevel warn

# /home/ubuntu/test/company-infra/templates/apache2-deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: apache2
  namespace: company-infra
spec:
  replicas: 1
  selector:
    matchLabels:
      app: apache2
  template:
    metadata:
      labels:
        app: apache2
    spec:
      containers:
        - name: apache2
          image: httpd:2.4-alpine
          ports:
            - containerPort: 80
          volumeMounts:
            - name: apache2-conf
              mountPath: /usr/local/apache2/conf/httpd.conf
              subPath: httpd.conf
            - name: indexhtml
              mountPath: /usr/local/apache2/htdocs/index.html
              subPath: index.html
      volumes:
        - name: apache2-conf
          configMap:
            name: apache2-config
        - name: indexhtml
          configMap:
            name: apache2-indexhtml


# /home/ubuntu/test/company-infra/templates/apache2-indexhtml-configmap.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: apache2-indexhtml
  namespace: company-infra
data:
  index.html: |
    <html>
    <head><title>Apache2 Test Page</title></head>
    <body>
      <h1>Hello from ConfigMap index.html!</h1>
    </body>
    </html>


# /home/ubuntu/test/company-infra/templates/apache2-service.yaml
apiVersion: v1
kind: Service
metadata:
  name: apache2
  namespace: company-infra
spec:
  type: NodePort
  ports:
    - port: 80
      targetPort: 80
      nodePort: 30880
      protocol: TCP
  selector:
    app: apache2
    

# helm install 실행 배포
helm install company-infra company-infra
```

> 🔥 우리는 helm을 이용하여 서비스를 띄울 것이다.
 helm 명령어는 어디서 실행하냐가 굉장히 중요하다. 실행될 주체가 있는 곳에서 명령어를 작성하면된다.
#/home/ubuntu/test/company-infra  helm install company-infra company-infra
위치를 주의하며 명령어를 사용해야한다.

### 🔑 문제 상황
**apache2**  pod가 작동을 하지 않았다. **pvc svc**까지 다 떴지만 유일하게 pod만 정상적이지 않았다.
당연스럽게도 웹 페이지도 뜨지 않는다.
의심이 되는 부분은 설정에 문제라고 생각이 들었다.

### 🔓 해결 과정
```yaml
buntu@ubuntu-VirtualBox:~/test$ kubectl describe pods apache2-689f44555-plsqm -n company-infra
Name:             apache2-689f44555-plsqm

      Reason:       CrashLoopBackOff
    Last State:     Terminated
      Reason:       Error
# Pod들은 작동하지 않았다.     
      
    Mounts:
      /usr/local/apache2/conf/httpd.conf from apache2-conf (rw,path="mywebsite.conf")
      /usr/local/apache2/htdocs from webroot (rw)
      /var/run/secrets/kubernetes.io/serviceaccount from kube-api-access-6bfcp (ro)
     

Volumes:
  apache2-conf:
    Type:      ConfigMap (a volume populated by a ConfigMap)
    Name:      apache2-config
    Optional:  false
  webroot:
    Type:       PersistentVolumeClaim (a reference to a PersistentVolumeClaim in the same namespace)
    ClaimName:  apache2-pvc
    ReadOnly:   false


ubuntu@ubuntu-VirtualBox:~/test$ kubectl logs apache2-689f44555-plsqm -n company-infra
httpd: Syntax error on line 3 of /usr/local/apache2/conf/httpd.conf: ServerRoot must be a valid directory


# Describe에서는 하나의 문제를 발견하였다.
# 설정파일이 mywebsite.conf로 되어 있었다. 
# 볼륨(Mount) 오류 가능성이 높아졌다.

#apache2-deployment.yaml
volumeMounts:
        - name: apache2-conf
          mountPath: /usr/local/apache2/conf/httpd.conf
          subPath: httpd.conf

#apache2 configmap key → httpd.conf 수정
#error mounting ".../volume-subpaths/apache2-conf/apache2/0" to rootfs at "/usr/local/apache2/conf/httpd.conf": 
#create mountpoint for .../httpd.conf mount: 
#cannot create subdirectories in ".../rootfs/usr/local/apache2/conf/httpd.conf": not a directory

# mount는 설정 값대로 변경이되었다. 그러나 crash..
# subpath를 사용하지 않아 파일이 아닌 디렉터리로 구현이 되었다.
#재수정
#        volumeMounts:
#        - name: apache2-conf
#          mountPath: /usr/local/apache2/conf/httpd.conf
#          subPath: httpd.conf

#이 과정에서 values.yaml에는 다른 서비스들도 있었기에 apache2만이 실행되는 것이 아니다.
#여기서도 values.yaml에 장점이 나타난다.
#values.yaml 파일을 복사하여 enable:false  를 통해 다른 서비스를 비활성화 하고 
#apache2  true를 통해 활성화하고 apache2만을 구현하고 사용해보았다.
```

🔥   이후에도 configmap 파일이나 deployment 파일을 제대로 가져가지 않았기 때문에 pvc 역할이 문제 일 것이라고 생각들었다.  
상황은 아래와 같이 계속 진행되기 때문이었다.

- index,html 파일 등 여러 설정 파일들이 노드(호스트)에서 생성해도 kubenetes pod안에서는 PVC에 제대로 마운트되지 않았다.
- PVC에 아무 파일 없는 상태에서 httpd 컨테이너가 뜬다면 pod 자체가 crash될 수 있음 \

무엇을해도 이미지를 alpine을 바꾸어도 설정 값을 아무리 변경해도 결국엔 오류가 났었다.

### 문제 원인
- PVC를 /usr/local/apache2/htdocs 마운트
공식 컨테이너 이미지에 기본 포함된 index.html 등 여러 파일이 빈 PVC로 덮어써짐
- 컨테이너 내부에 확인하면 아무 파일이 보이질 않는다.
- 파일 강제적으로 exec통해 생성시 권한 문제 발생

결국에는 정적배포 실무 환경에서는 PVC를 사용하지 않고 다른 코드로 재배포 하기로 하였다.
동적 데이터 파일 저장이 필요하다면 다른 방법을 찾을 예정이다.

### ⭕️ PVC가 필요한 경우

- cms/블로그/웹 서비스에서 사용자 파일업로드
- 웹 서버에서 주기적으로 생성하는 로그 리포트 파일
- 동적으로 컨텐츠가 변경되는 사내 웹 서비스
### ❌ PVC가 필요 없는 경우

- 정적 웹사이트 (react,vue,html ) 회사 소개사이트, 블로그, 포트폴리오
- 마이크로 서비스에서 동적 데이터가 DB/외부 저장소에만 있는 경우
 

간단히만 느껴졌던 apache2  하루종일 트러블 슈팅하니 보여지는 느낌이 달라졌다.
k8s 구조에 대한 많은 이해도가 생겨 만족스럽긴 했지만 쉽지는 않았다..

 