# WAS 트러블슈팅

> 문제 해결 가이드 - 증상, 원인, 해결 방법

---

## 목차

1. [외부 접근 불가 (404)](#외부-접근-불가-404)
2. [Istio mTLS 에러](#istio-mtls-에러)
3. [MySQL 연결 실패](#mysql-연결-실패)
4. [Port 충돌](#port-충돌)
5. [Lombok Annotation 인식 안 됨](#lombok-annotation-인식-안-됨)
6. [JAR 실행 시 Main Class 못 찾음](#jar-실행-시-main-class-못-찾음)
7. [Pod 재시작 반복](#pod-재시작-반복)
8. [Canary 배포 실패](#canary-배포-실패)
9. [배포 후 변경사항 적용 안 됨](#배포-후-변경사항-적용-안-됨)

---

## 외부 접근 불가 (404)

### 증상

```bash
curl https://blog.jiminhome.shop/api/posts
# → 404 Not Found
```

### 원인

**nginx → WAS 프록시 설정 누락**

현재 트래픽 흐름:
```
Client → Ingress → web-service → nginx Pod
                                    ↓ (프록시 없음!)
                                  404 Not Found
```

### 진단

```bash
# 1. nginx ConfigMap 확인
kubectl get configmap web-nginx-config -n blog-system -o yaml

# 2. /api/ location 블록 있는지 확인
# 없으면 → 프록시 설정 누락
```

### 해결

**web-nginx-config ConfigMap 수정:**

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: web-nginx-config
  namespace: blog-system
data:
  nginx.conf: |
    server {
        listen 80;
        server_name _;

        # Hugo 정적 파일
        location / {
            root /usr/share/nginx/html;
            try_files $uri $uri/ /index.html;
        }

        # WAS API 프록시 (NEW)
        location /api/ {
            proxy_pass http://was-service:8080;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;

            # Timeout
            proxy_connect_timeout 10s;
            proxy_send_timeout 10s;
            proxy_read_timeout 10s;
        }
    }
```

**적용:**
```bash
# 1. ConfigMap 수정
kubectl edit configmap web-nginx-config -n blog-system

# 2. nginx Pod 재시작
kubectl rollout restart deployment web -n blog-system

# 3. 재시작 대기
kubectl rollout status deployment web -n blog-system

# 4. 테스트
curl https://blog.jiminhome.shop/api/posts
```

### 확인

```bash
# 성공 응답 (200 OK)
curl -I https://blog.jiminhome.shop/api/posts
# HTTP/1.1 200 OK

# 실제 데이터 확인
curl https://blog.jiminhome.shop/api/posts
# [{"id":1,"title":"First Post",...}]
```

---

## Istio mTLS 에러

### 증상

```bash
# WAS 로그
TLS_error: WRONG_VERSION_NUMBER

# 또는
upstream connect error or disconnect/reset before headers
```

### 원인

**nginx → WAS 통신에서 mTLS 불일치**

- nginx가 Plain HTTP 전송
- WAS DestinationRule이 mTLS 요구
- 버전 불일치 에러 발생

### 진단

```bash
# 1. DestinationRule 확인
kubectl get destinationrule was-dest-rule -n blog-system -o yaml

# 2. mTLS 모드 확인
# trafficPolicy.tls.mode: ISTIO_MUTUAL
```

### 해결 옵션

#### 옵션 1: DestinationRule에서 nginx → WAS는 Plain HTTP 허용

```yaml
# was-destinationrule.yaml
apiVersion: networking.istio.io/v1beta1
kind: DestinationRule
metadata:
  name: was-dest-rule
spec:
  host: was-service
  trafficPolicy:
    tls:
      mode: DISABLE  # Plain HTTP 허용
    connectionPool:
      tcp:
        maxConnections: 100
      http:
        http1MaxPendingRequests: 50
        maxRequestsPerConnection: 2
```

**적용:**
```bash
cd ~/k8s-manifests/blog-system
git add was-destinationrule.yaml
git commit -m "fix: Disable mTLS for nginx → WAS"
git push origin main

# ArgoCD 동기화 대기
watch kubectl get application blog-system -n argocd
```

#### 옵션 2: PeerAuthentication PERMISSIVE 모드

```yaml
# mtls-peerauthentication.yaml
apiVersion: security.istio.io/v1beta1
kind: PeerAuthentication
metadata:
  name: mtls-permissive
  namespace: blog-system
spec:
  mtls:
    mode: PERMISSIVE  # mTLS 선택적 (Plain HTTP도 허용)
```

**이미 PERMISSIVE이면:**
- DestinationRule 확인 필요

#### 옵션 3: nginx → WAS 경로만 mTLS 제외

```yaml
# web Pod annotation
metadata:
  annotations:
    traffic.sidecar.istio.io/excludeOutboundIPRanges: "10.97.248.192/32"
```

**비추천 이유:**
- IP 하드코딩
- 유지보수 어려움

### 확인

```bash
# 1. WAS Pod 로그에서 mTLS 에러 없는지 확인
kubectl logs -n blog-system -l app=was -c spring-boot --tail=100 | grep -i tls

# 2. API 테스트
kubectl exec -n blog-system $(kubectl get pod -n blog-system -l app=web -o jsonpath='{.items[0].metadata.name}') \
  -c nginx -- curl -s http://was-service:8080/actuator/health
# {"status":"UP"}
```

---

## MySQL 연결 실패

### 증상

**로컬 개발:**
```
java.sql.SQLException: Access denied for user 'root'@'localhost'
```

**Kubernetes:**
```
Communications link failure
The last packet sent successfully to the server was 0 milliseconds ago
```

### 원인

1. **비밀번호 틀림**
2. **MySQL 서버 미실행**
3. **네트워크 문제** (Kubernetes)
4. **ConfigMap 설정 오류**

### 진단

```bash
# 로컬 개발
# 1. MySQL 실행 여부
docker ps | grep mysql

# 2. 비밀번호 확인
docker exec -it mysql-dev mysql -uroot -prootpassword -e "SELECT 1"

# 3. application.properties 확인
cat src/main/resources/application.properties | grep datasource
```

```bash
# Kubernetes
# 1. MySQL Pod 상태
kubectl get pods -n blog-system -l app=mysql

# 2. MySQL 서비스 확인
kubectl get svc mysql-service -n blog-system

# 3. WAS ConfigMap 확인
kubectl get configmap was-config -n blog-system -o yaml

# 4. Secret 확인
kubectl get secret mysql-secret -n blog-system -o jsonpath='{.data.mysql-root-password}' | base64 -d
```

### 해결

#### 로컬 개발

```bash
# 1. MySQL 재시작
docker stop mysql-dev
docker rm mysql-dev

docker run -d \
  --name mysql-dev \
  -e MYSQL_ROOT_PASSWORD=rootpassword \
  -e MYSQL_DATABASE=board \
  -p 3306:3306 \
  mysql:8.0.44

# 2. 연결 대기 (30초)
sleep 30

# 3. 연결 테스트
docker exec -it mysql-dev mysql -uroot -prootpassword -e "SHOW DATABASES;"
```

#### Kubernetes

```bash
# 1. WAS Pod에서 MySQL 연결 테스트
kubectl exec -n blog-system -it $(kubectl get pod -n blog-system -l app=was -o jsonpath='{.items[0].metadata.name}') \
  -c spring-boot -- bash -c "apt-get update && apt-get install -y mysql-client && mysql -h mysql-service -uroot -prootpassword -e 'SELECT 1'"

# 2. MySQL Pod 로그 확인
kubectl logs -n blog-system -l app=mysql --tail=100

# 3. ConfigMap 수정 (필요 시)
kubectl edit configmap was-config -n blog-system
```

### 확인

```bash
# WAS 로그에서 HikariCP 연결 성공 확인
kubectl logs -n blog-system -l app=was -c spring-boot --tail=100 | grep -i hikari
# HikariPool-1 - Start completed.
```

---

## Port 충돌

### 증상

**로컬 개발:**
```
Bind for 0.0.0.0:8080 failed: port is already allocated
```

**Docker:**
```
Error starting userland proxy: listen tcp4 0.0.0.0:8080: bind: address already in use
```

### 원인

- 다른 프로세스가 8080 포트 사용 중

### 진단

```bash
# 사용 중인 프로세스 확인
lsof -i :8080

# 또는
netstat -tulpn | grep :8080
```

### 해결

#### 옵션 1: 프로세스 종료

```bash
# PID 확인 후 종료
lsof -i :8080
# java    12345 user  ...

kill -9 12345
```

#### 옵션 2: 다른 포트 사용

```bash
# Maven
./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=8081

# JAR
java -jar target/board-0.0.1-SNAPSHOT.jar --server.port=8081

# Docker
docker run -p 8081:8080 board-was:local
```

### 확인

```bash
# 새 포트로 접근
curl http://localhost:8081/actuator/health
```

---

## Lombok Annotation 인식 안 됨

### 증상

**컴파일 에러:**
```
cannot find symbol: method getTitle()
```

### 원인

1. **IntelliJ**: Lombok 플러그인 미설치 또는 Annotation Processing 비활성화
2. **VS Code**: Extension Pack for Java 미설치
3. **Maven**: Clean 필요

### 진단

```bash
# Maven 빌드 확인
./mvnw clean compile
# 성공하면 IDE 문제
# 실패하면 pom.xml 확인
```

### 해결

#### IntelliJ IDEA

**1. Lombok 플러그인 설치:**
```
Settings → Plugins → "Lombok" 검색 → Install
```

**2. Annotation Processing 활성화:**
```
Settings → Build, Execution, Deployment → Compiler → Annotation Processors
→ Enable annotation processing ✅
```

**3. IDE 재시작**

#### VS Code

```bash
# Extension 설치
code --install-extension vscjava.vscode-java-pack
code --install-extension gabrielbb.vscode-lombok

# Reload Window
Ctrl+Shift+P → Reload Window
```

#### Maven

```bash
# Clean 후 재빌드
./mvnw clean compile
```

### 확인

```bash
# 빌드 성공 확인
./mvnw clean package -DskipTests
# BUILD SUCCESS
```

---

## JAR 실행 시 Main Class 못 찾음

### 증상

```bash
java -jar target/board-0.0.1-SNAPSHOT.jar
# no main manifest attribute
```

### 원인

**Spring Boot Maven Plugin 누락**

### 진단

```bash
# pom.xml 확인
cat pom.xml | grep -A 5 spring-boot-maven-plugin
```

### 해결

**pom.xml 수정:**
```xml
<build>
  <plugins>
    <plugin>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-maven-plugin</artifactId>
    </plugin>
  </plugins>
</build>
```

**재빌드:**
```bash
./mvnw clean package -DskipTests
```

### 확인

```bash
# JAR 실행
java -jar target/board-0.0.1-SNAPSHOT.jar
# Started BoardApplication in 3.5 seconds
```

---

## Pod 재시작 반복

### 증상

```bash
kubectl get pods -n blog-system -l app=was
# NAME         READY   STATUS    RESTARTS
# was-xxx      2/2     Running   5
```

### 원인 체크

1. **Liveness Probe 실패** (60초 이내 시작 못 함)
2. **OOMKilled** (메모리 부족)
3. **DB 연결 실패**
4. **애플리케이션 크래시**

### 진단

```bash
# 1. 재시작 이유 확인
kubectl describe pod -n blog-system POD_NAME | grep -i restart

# 2. 이전 로그 확인 (재시작 전)
kubectl logs -n blog-system POD_NAME -c spring-boot --previous

# 3. Events 확인
kubectl get events -n blog-system --sort-by='.lastTimestamp' | grep was

# 4. 리소스 사용량
kubectl top pod -n blog-system -l app=was
```

### 해결

#### Liveness Probe 실패

**증상:**
- 60초 이내 `/actuator/health` 응답 못 함

**해결:**
```yaml
# was-rollout.yaml
spec:
  template:
    spec:
      containers:
      - name: spring-boot
        livenessProbe:
          httpGet:
            path: /actuator/health
            port: 8080
          initialDelaySeconds: 90  # 60 → 90으로 증가
          periodSeconds: 10
```

#### OOMKilled

**증상:**
```bash
kubectl describe pod POD_NAME | grep -i oom
# Reason: OOMKilled
```

**해결:**
```yaml
# was-rollout.yaml
resources:
  limits:
    memory: 1Gi  # 512Mi → 1Gi로 증가
```

#### DB 연결 실패

**진단:**
```bash
kubectl logs -n blog-system POD_NAME -c spring-boot --previous | grep -i "mysql\|hikari"
```

**해결:** [MySQL 연결 실패](#mysql-연결-실패) 참고

### 확인

```bash
# RESTARTS 카운트가 증가하지 않는지 모니터링
watch kubectl get pods -n blog-system -l app=was
```

---

## Canary 배포 실패

### 증상

```bash
kubectl argo rollouts get rollout was -n blog-system
# Status: Degraded
# Message: AnalysisRun failed
```

### 원인

1. **AnalysisTemplate 실패** (메트릭 임계값 초과)
2. **수동 Pause 상태**
3. **VirtualService 경로 이름 불일치**

### 진단

```bash
# 1. Rollout 상세 정보
kubectl argo rollouts get rollout was -n blog-system

# 2. AnalysisRun 확인 (있다면)
kubectl get analysisrun -n blog-system

# 3. VirtualService 확인
kubectl get virtualservice was-retry-timeout -n blog-system -o yaml | grep "name: primary"
```

### 해결

#### 수동 Promote

```bash
# Canary → Stable 즉시 전환
kubectl argo rollouts promote was -n blog-system
```

#### 롤백

```bash
# 이전 버전으로 되돌리기
kubectl argo rollouts undo was -n blog-system
```

#### VirtualService 경로 이름 수정

```yaml
# was-retry-timeout.yaml
spec:
  http:
  - name: primary  # ← Rollout에서 참조하는 이름과 일치해야 함
    route:
    - destination:
        host: was-service
```

### 확인

```bash
# Healthy 상태 확인
kubectl argo rollouts get rollout was -n blog-system
# Status: Healthy
```

---

## 배포 후 변경사항 적용 안 됨

### 증상

**소스 코드 변경 후 배포했는데 변경사항 없음**

### 원인

1. **Docker 이미지 캐시**
2. **Kubernetes ImagePullPolicy**
3. **ArgoCD가 Manifest 변경 감지 못 함**

### 진단

```bash
# 1. 현재 이미지 태그 확인
kubectl get rollout was -n blog-system -o jsonpath='{.spec.template.spec.containers[0].image}'

# 2. Pod에서 실제 사용 중인 이미지
kubectl describe pod -n blog-system POD_NAME | grep "Image:"

# 3. ArgoCD 동기화 상태
kubectl get application blog-system -n argocd
```

### 해결

#### Docker 이미지 재빌드 (캐시 없이)

```bash
cd ~/blogsite/blog-k8s-project/was

# 캐시 없이 빌드
docker build --no-cache -t ghcr.io/wlals2/board-was:v11 .

# 푸시
docker push ghcr.io/wlals2/board-was:v11
```

#### 이미지 태그 변경

```bash
cd ~/k8s-manifests/blog-system

# 새 태그로 변경
yq eval '.spec.template.spec.containers[0].image = "ghcr.io/wlals2/board-was:v11"' \
  -i was-rollout.yaml

git add was-rollout.yaml
git commit -m "chore: Update WAS to v11"
git push origin main
```

#### ArgoCD 수동 동기화

```bash
# ArgoCD 수동 동기화
argocd app sync blog-system

# 또는
kubectl patch application blog-system -n argocd \
  --type merge \
  -p '{"operation":{"initiatedBy":{"username":"admin"},"sync":{"syncStrategy":{"hook":{}}}}}'
```

### 확인

```bash
# 1. Pod 재생성 확인 (AGE가 최근)
kubectl get pods -n blog-system -l app=was

# 2. 새 이미지 사용 확인
kubectl describe pod -n blog-system POD_NAME | grep "Image:"

# 3. 변경사항 테스트
curl https://blog.jiminhome.shop/api/posts
```

---

## 기타 팁

### 로그 실시간 모니터링

```bash
# 여러 Pod 동시 로그
kubectl logs -n blog-system -l app=was -c spring-boot -f --max-log-requests=10

# 에러만 필터링
kubectl logs -n blog-system -l app=was -c spring-boot --tail=100 | grep -i "error\|exception"
```

### 데이터베이스 백업

```bash
# MySQL Pod에서 백업
kubectl exec -n blog-system MYSQL_POD_NAME -- \
  mysqldump -uroot -prootpassword board > backup.sql

# 복원
kubectl exec -i -n blog-system MYSQL_POD_NAME -- \
  mysql -uroot -prootpassword board < backup.sql
```

### ConfigMap/Secret 변경 후 Pod 재시작

```bash
# ConfigMap 변경
kubectl edit configmap was-config -n blog-system

# Pod 재시작 (Rollout 재시작)
kubectl argo rollouts restart was -n blog-system
```

### Health Check 디버깅

```bash
# WAS Health Check (내부)
kubectl exec -n blog-system WAS_POD_NAME -c spring-boot -- \
  curl -s http://localhost:8080/actuator/health | jq .

# Istio Sidecar 없이 직접 접근
kubectl port-forward -n blog-system svc/was-service 8080:8080
curl http://localhost:8080/actuator/health
```

---

**작성일:** 2026-01-21
**마지막 업데이트:** 주요 문제 해결 방법 통합
