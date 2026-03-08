---
title: "『온프레미스 vs 클라우드 인프라: 내가 직접 비교해봤다』 — 『좀 더 고급으로 변하는 인프라 구조』 Helm, Values (2)"
subtitle: "좀 더 고급으로 변하는 인프라 구조 - Helm, Values ②"
date: 2025-10-10T13:30:00+09:00
author: "늦찌민"
tags: ["Kubernetes", "Helm", "EFK", "ELK", "Fluent Bit", "Troubleshooting"]
categories:
  - "study"
  - "Kubernetes"
series: ["좀 더 고급으로 변하는 인프라 구조"]---

> 며칠 동안 **EFK**에 매달렸다.  
> 처음은 **ELFK**로 구현하려 했지만,  
> 굉장히 많은 오류가 발생했고, 오류를 해결하면 다른 곳에서 또 문제가 터졌다.  
> 실제로 이론적으로는 가능하지만, 이렇게 구현하는 사람도 없었다…
> 그래서 방향을 **ELFK → ELK → EFK**로 바꿨다.  
> 이번 며칠 간의 고생을 기록해보겠다.
> Container 환경에서 Fluentbit를 사용하는 것이 나아보였다. 지금 나의 환경에서는 고급 스러운 파싱 이 필요해 보이지 않았다.

---

## 목표
###  K8s EFK 구현 및 트러블슈팅

## 📦 기본 구성

```

~/test/company-infra/
├─ templates/
│ ├─ mysql/
│ ├─ prometheus/
│ ├─ grafana/
│ ├─ apache2
│ ├─ ftp
│ ├─ mysqld-exporter
│ ├─ nginx
│ ├─ openvpn
│ ├─ samba
│ └─ jenkins/
├─ charts/
├─ values.yaml
├─ Chart.yaml
├─ helm-chart
├─ docker-compose/
│ ├─ docker-compose.yml (SFTP)

~/test/company-infra-c/
├─ templates/
│ ├─ Elasticsearch/
│ ├─ Fluentbit/
│ ├─ Kibana/
├─ charts/
├─ values.yaml
├─ Chart.yaml
├─ helm-chart

```

### 🎤 폴더를 구분한 이유:

```

  EFK에서는 많은 설정 변경이랑 트러블 슈팅이 많이 일어나기 때문에
구분지어 관리를 하도록 하였다. 많은 혼란이 일어날 수 있다. 구현하는 과정이 너무나 험난했다.

샘플파일을 다섯번 여섯번 갈아치운 것 같다. 위에 언급처럼 아키텍처도 자주 변경됨으로써 산으로 가나 했으나 
깔끔하게 마무리 지었다.

처음부터 많은 것을 붙여서 구현하는 게 아닌 기본적인 거에서부터 하나씩 하나씩 붙이는 선택을 했다.

이 방법은 통했고 더 손 쉽게 마무리한 것 같다.  기존에 하던 방식에서 벗어나 기본적인 것 만을 사용하니 정말 마법같이 해결되었다. 아쉬운 것은 Elaistcsearch 버전을 7.x.x를 사용했다는 것이다. selft-certficate까지 구현하기에는 지금 나에게는 너무 어렵다고 생각헀다. 계속 오류가 일어나며 권한 문제가 계속 발생했기 때문이다.

이번 실습을 통해 더욱더 많은 것을 배우게 된 것같다.  이번 프로젝트를 끝내고 EFK를 보안적인 요소까지 챙겨서 꼭 다시 구현해보고 싶다.

```

### helm 차트로 ELK(+ Fluent bit) 통합 배포

- 전체적인 파드 로그 수집 설정 아무 문제가 없었다. 그러나 kibana 인덱스 Logstash가 찍어준 logstash_*만 보이며 Fleunt bit로 쓰이는 인덱스는 보이지 않았다.
### 시도해본 방법들
 - hostpath 로 실제 로그 파일을 긁어온다. →  로그를 가져오지못함 (실패)
- 권한 문제  예상 securityContext.privileged: true 추가  →  실패
- configmap / Parser 구성 오류 Fluent-bit.conf 설정 오류 확인  →  belong to parsers_file 오류 해결
- Elaistcsearch 출력 플러그인 호환성 문제 ES버전이 8.x.x를 사용했기 때문에 _type 설정을 할 수 없기에
- Suppress_Type_Name On  Logstash_Format Off 설정을 포함한다. →  설정 구문 오류 사라짐
- 이후 Bulk 전송 성공 & JSON 응답 처리 에러  ES에서 반환하는 구조를 Fluent bit 제대로 파싱을 하지 못함

결국에는 flunet-* 인덱스가 생성되지 않는 이유를 찾지 못했다.. 잘못된 배에 구멍을 하나하나 막는 것보다
새로운 배를 만드는 것이 더 바람직한 방법이었다.
ELK + Fluentbit 버전을 포기하고 ELK / EFK 가기로 정했다.
그러나 logstash는 이전부터 사용해본 경험이 많기 때문에 개인적으로 Fluentbit를 선택하였다.

 
또한 새로운 환경을 만들더라도 이전에 사용한 것을 말끔히 지우기 위해서는 
K8s에서 사용하던 helm 과 Kubectl 네임서버를 삭제하면 된다.

### ⚙️ EFK 서버 구현 및 트러블 슈팅
 

🎤 Fluentbit와 logstash를 같이 구현헀을 때 이론적으로는 Fleuntbit 로그 수집으로 logstash를 통해 형식변환및 ES로 전송을 생각했다. 문제는 logstash는 index로 떳으나 Fluentbit가 뜨질 않았다. 
전혀 예상과 빗나간 결과 였다. 처음부터 안되던 것을 고치면서 계속 사용해보려했으나 많은 고생 끝에 실패를 하고 다른 샘플 파일을 만들었다. 이렇게 많이 헤맬 때는 다시 만드는 것이 좋은 방법인 듯하다.

 
---

## values.yaml

```yaml
replicaCount: 1

image:
  elasticsearch:
    repository: docker.elastic.co/elasticsearch/elasticsearch
    tag: "7.17.0"
  kibana:
    repository: docker.elastic.co/kibana/kibana
    tag: "7.17.0"
  fluentbit:
    repository: fluent/fluent-bit
    tag: "2.2"

service:
  type: NodePort

  elasticsearch:
    port:     9200
    nodePort: 32020

  kibana:
    port:     5601
    nodePort: 31601

  fluentbit:
    httpPort:         2020
    httpNodePort:     31020
    metricsPort:      2021
    metricsNodePort:  31021

elasticsearch:
  resources:
    requests:
      cpu:    "200m"
      memory: "512Mi"
    limits:
      cpu:    "1"
      memory: "1Gi"
  extraEnvs:
    - name: discovery.type
      value: single-node
    - name: xpack.security.enabled
      value: false
    - name: ES_JAVA_OPTS
      value: "-Xms512m -Xmx512m"
  volume:
    size: 5Gi

fluentbit:
  backend:
    host:  company-infra-c-elasticsearch
    port:  9200
    index: kubernetes-logs

```

## Elasitcsearch *.yaml

```yaml
# elasticsearch-configmap
apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ .Release.Name }}-elasticsearch-config
data:
  elasticsearch.yml: |
    # single-node 운영, 보안 비활성화
    discovery.type: single-node
    xpack.security.enabled: false



# elasitcsearch-service.yaml
apiVersion: v1
kind: Service
metadata:
  name: {{ .Release.Name }}-elasticsearch
  labels:
    app: efk
    component: elasticsearch
spec:
  type: {{ .Values.service.type }}
  selector:
    app: efk
    component: elasticsearch
  ports:
  - name: http
    port: {{ .Values.service.elasticsearch.port }}
    targetPort: http
    nodePort: {{ .Values.service.elasticsearch.nodePort }}



# elasticsearch-statefulset.yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: {{ .Release.Name }}-elasticsearch
  labels:
    app: efk
    component: elasticsearch
spec:
  serviceName: {{ .Release.Name }}-elasticsearch
  replicas: {{ .Values.replicaCount }}
  selector:
    matchLabels:
      app: efk
      component: elasticsearch
  template:
    metadata:
      labels:
        app: efk
        component: elasticsearch
    spec:
      containers:
      - name: elasticsearch
        image: "{{ .Values.image.elasticsearch.repository }}:{{ .Values.image.elasticsearch.tag }}"
        env:
        - name: discovery.type
          value: single-node
        - name: xpack.security.enabled
          value: "false"
        ports:
        - name: http
          containerPort: 9200
        - name: transport
          containerPort: 9300
        resources:
          requests:
            cpu:    {{ .Values.elasticsearch.resources.requests.cpu }}
            memory: {{ .Values.elasticsearch.resources.requests.memory }}
          limits:
            cpu:    {{ .Values.elasticsearch.resources.limits.cpu }}
            memory: {{ .Values.elasticsearch.resources.limits.memory }}
        volumeMounts:
        - name: data
          mountPath: /usr/share/elasticsearch/data
  volumeClaimTemplates:
  - metadata:
      name: data
    spec:
      accessModes: ["ReadWriteOnce"]
      resources:
        requests:
          storage: {{ .Values.elasticsearch.volume.size }}

```

## Fluentbit *.yaml

```yaml
#fluentbit-configmap.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ .Release.Name }}-fluentbit-config
  labels:
    app: efk
    component: fluentbit
data:
  fluent-bit.conf: |
    [SERVICE]
        Flush        1
        Daemon       Off
        Log_Level    info
        Parsers_File parsers.conf

    [INPUT]
        Name           tail
        Path           /var/log/containers/*.log
        Parser         docker
        Tag            kube.*
        Mem_Buf_Limit  5MB
        Skip_Long_Lines On

    [FILTER]
        Name   kubernetes
        Match  kube.*

    [OUTPUT]
        Name  es
        Match *
        Host  {{ .Values.fluentbit.backend.host }}
        Port  {{ .Values.fluentbit.backend.port }}
        Index {{ .Values.fluentbit.backend.index }}

  parsers.conf: |
    [PARSER]
        Name        docker
        Format      json
        Time_Key    time
        Time_Format %Y-%m-%dT%H:%M:%S.%L



#fluentbit-daemonset.yaml
apiVersion: apps/v1
kind: DaemonSet
metadata:
  name: {{ .Release.Name }}-fluentbit
  labels:
    app: efk
    component: fluentbit
spec:
  selector:
    matchLabels:
      app: efk
      component: fluentbit
  template:
    metadata:
      labels:
        app: efk
        component: fluentbit
    spec:
      containers:
      - name: fluentbit
        image: "{{ .Values.image.fluentbit.repository }}:{{ .Values.image.fluentbit.tag }}"
        volumeMounts:
        - name: config
          mountPath: /fluent-bit/etc/fluent-bit.conf
          subPath: fluent-bit.conf
        - name: config
          mountPath: /fluent-bit/etc/parsers.conf
          subPath: parsers.conf
        - name: varlog
          mountPath: /var/log
        - name: varlibdocker
          mountPath: /var/lib/docker/containers
      volumes:
      - name: config
        configMap:
          name: {{ .Release.Name }}-fluentbit-config
      - name: varlog
        hostPath:
          path: /var/log
      - name: varlibdocker
        hostPath:
          path: /var/lib/docker/containers


#fluentbit-service.yaml
apiVersion: v1
kind: Service
metadata:
  name: {{ .Release.Name }}-fluentbit
  labels:
    app: efk
    component: fluentbit
spec:
  type: {{ .Values.service.type }}
  selector:
    app: efk
    component: fluentbit
  ports:
  - name: http
    port: {{ .Values.service.fluentbit.httpPort }}
    targetPort: http
    nodePort: {{ .Values.service.fluentbit.httpNodePort }}
  - name: metrics
    port: {{ .Values.service.fluentbit.metricsPort }}
    targetPort: metrics
    nodePort: {{ .Values.service.fluentbit.metricsNodePort }}

```

## kibana*.yaml

```yaml
# kibana-configmap.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ .Release.Name }}-kibana-config
data:
  kibana.yml: |
    server.name: kibana
    server.host: "0.0.0.0"
    elasticsearch.hosts: ["http://{{ .Release.Name }}-elasticsearch:{{ .Values.service.elasticsearch.port }}"]
    xpack.security.enabled: false
    
    
    
# kibana-deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ .Release.Name }}-kibana
  labels:
    app: efk
    component: kibana
spec:
  replicas: {{ .Values.replicaCount }}
  selector:
    matchLabels:
      app: efk
      component: kibana
  template:
    metadata:
      labels:
        app: efk
        component: kibana
    spec:
      containers:
      - name: kibana
        image: "{{ .Values.image.kibana.repository }}:{{ .Values.image.kibana.tag }}"
        env:
        - name: ELASTICSEARCH_HOSTS
          value: "http://{{ .Release.Name }}-elasticsearch:{{ .Values.service.elasticsearch.port }}"
        - name: SERVER_HOST
          value: "0.0.0.0"
        - name: XPACK_SECURITY_ENABLED
          value: "false"
        ports:
        - name: http
          containerPort: {{ .Values.service.kibana.port }}



# kibana-service.yaml
apiVersion: v1
kind: Service
metadata:
  name: {{ .Release.Name }}-kibana
  labels:
    app: efk
    component: kibana
spec:
  type: {{ .Values.service.type }}
  selector:
    app: efk
    component: kibana
  ports:
  - name: http
    port: {{ .Values.service.kibana.port }}
    targetPort: http
    nodePort: {{ .Values.service.kibana.nodePort }}

```

## 🔓 트러블 슈팅
이번 트러블 슈팅은  kubectl logs ['pod명'] 을 통해 확인헀으며 설정상 문제는 kubectl describe 를 통해 파악했다.
이외에 실수할 수 있는 부분은 버츄얼박스 NAT Portfowarding 꼭 해야한다는 것이다.

 

### 1.  "kibana server is not ready yet"

- 진단: kibana가 ES에 연결 실패  → 내부에서 대기
- 점검: 
```bash
curl http://<노드IP>:32020/_cluster/health?pretty
kubectl logs -f deployment/company-infra-c-kibana
kubectl exec -it <kibana-pod> -- curl -I http://company-infra-c-elasticsearch:9200

```
- 해결:
복잡한 configMap 대신 Env-vars로만 설정한다.
kibana Deployment에  아래 내용을 추가한다.

```yaml
env:
- name: ELASTICSEARCH_HOSTS
  value: "http://company-infra-c-elasticsearch:9200"
- name: SERVER_HOST
  value: "0.0.0.0"
- name: XPACK_SECURITY_ENABLED
  value: "false"
 ```

### 2. Elasticsearch "Connection refused"

- 증상: 환경설정 후에도 kibana dashboard 진입은 가능해 졌으나 ES log에 에러 메시지가 남아있음

``` bash
curl: (7) Failed to connect to localhost port 32020
원인 : network.host 가 localhost로 만 바인딩 되어 있어 다른 곳에서는 들어 올수 없었음

```

- 해결:
```yaml
env:
- name: discovery.type
  value: single-node
- name: network.host
  value: "0.0.0.0"
- name: xpack.security.enabled
  value: "false"
#StatefulSet컨테이너에 Env-var를 추가 CnfgiMap 마운트 부분을 삭제한다.
 ```

### 3. Fluent Bit DNS 오류

```

getaddrinfo(host='efk-elasticsearch', err=4): Domain name not found
 ```

사실 상 통틀어서 제일 큰 문제 였을지도 모른다. \
기존에 다른 아키텍처에서도 Fluent bit에서 es 연결이 되지 않으며 도착까지 못한 이유는 서비스 이름이 서로 불일치 하지 않아서 일까라는 생각이 들었다. 이전에는 config 설정만 찾아댕겼지 막상 host는 한번도 생각하지 못했다.
(EFK 성공하기 위해 몇날 며칠을 고생했다..)
- 해결: 정답을 알고 나면 확실히 쉽고 허무하게 느껴질 수는 있다. 하지만  배워간 것들이 더 많다고 생각한다.
``` yaml
fluentbit:
  backend:
    host: company-infra-c-elasticsearch   # 실제 Svc 이름
    port: 9200
    index: kubernetes-logs
    

```

 

* (네임스페이스가 다르다면)  FQDN을 사용한다.

comapny-infra-c-elasticseach.efk.svc.cluster.local 을 사용한다. 위 host명을 바꾸어주면 된다.

### 고생한거에 비해 글이 짧아 적으면서도 놀랐다. 

```

트러블슈팅들을 정리해보니 계속 하던 것을 바꾸던지 기존에도 잘된 것도 values에 옮겨 실행하는 둥 
쓸때 없는 행동들도 많았던 것 같다. 템플릿을 직접 수정하는 데는 들여쓰기에도 많은 오류가 나타났다.

특히 EFK 에서는 버전 별로 사라진 타입이나 개념들이 너무 많았다.

이런 부분은 레퍼런스를 확인 하는 것이 빨랐다. 도중에 ELK+Fluentbit 아케텍처를 계속 사용하려 헀다면 아직 까지 이 실습은 

안 끝났을지도 모른다. 
정말 너무 안될 때는 새로 만들어 보는 것도 하나의 방법이라는 것을 느꼈다.

차차 배경지식들을 쌓아가면서 더 높고 빠른 해결 능력이 갖출 것이라고 믿는 다.

```

 