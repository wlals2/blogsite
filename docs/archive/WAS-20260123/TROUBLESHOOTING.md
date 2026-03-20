# WAS 트러블슈팅

> 문제 해결 가이드 - 증상, 원인, 해결 방법

---

## 목차

1. [외부 접근 불가 (404)](#외부-접근-불가-404)
2. [Istio mTLS 에러](#istio-mtls-에러) ⭐ **2026-01-21 업데이트**
3. [AuthorizationPolicy RBAC 에러 (mTLS DISABLE 환경)](#authorizationpolicy-rbac-에러-mtls-disable-환경) ⭐ **신규 추가**
4. [MySQL 연결 실패](#mysql-연결-실패)
5. [Port 충돌](#port-충돌)
6. [Lombok Annotation 인식 안 됨](#lombok-annotation-인식-안-됨)
7. [JAR 실행 시 Main Class 못 찾음](#jar-실행-시-main-class-못-찾음)
8. [Pod 재시작 반복](#pod-재시작-반복)
9. [Canary 배포 실패](#canary-배포-실패)
10. [배포 후 변경사항 적용 안 됨](#배포-후-변경사항-적용-안-됨)

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
# 외부 API 접근 시
curl https://blog.jiminhome.shop/api/posts
# → upstream connect error or disconnect/reset before headers.
#    retried and the latest reset reason: remote connection failure,
#    transport failure reason: TLS_error:|268435703:SSL routines:
#    OPENSSL_internal:WRONG_VERSION_NUMBER:TLS_error_end

# WAS Pod Istio Proxy 로그
kubectl logs -n blog-system -l app=was -c istio-proxy --tail=50
# [2026-01-21T03:11:48.062Z] "GET /actuator/health HTTP/1.1" 403 -
#  rbac_access_denied_matched_policy[none]
```

### 원인

**nginx → WAS 통신에서 mTLS 설정 불일치**

1. nginx는 Plain HTTP로 요청 전송 (upstream http://was-service:8080)
2. DestinationRule이 `tls.mode: ISTIO_MUTUAL` 강제
3. Istio sidecar가 mTLS 연결 시도 → TLS 버전 불일치 에러
4. **중요**: PeerAuthentication이 PERMISSIVE여도 DestinationRule이 우선

```
nginx (Plain HTTP) → Istio Sidecar (mTLS 강제) → ❌ TLS_error
```

### 진단

```bash
# 1. DestinationRule 확인
kubectl get destinationrule was-dest-rule -n blog-system -o jsonpath='{.spec.trafficPolicy.tls.mode}'
# 출력: ISTIO_MUTUAL (문제!)

# 2. PeerAuthentication 확인
kubectl get peerauthentication -n blog-system
# MODE: PERMISSIVE (이미 올바름)

# 3. nginx ConfigMap 확인
kubectl get configmap web-nginx-config -n blog-system -o yaml | grep proxy_pass
# proxy_pass http://was-service:8080 (Plain HTTP 사용)

# 4. 실제 에러 확인
curl -v https://blog.jiminhome.shop/api/posts 2>&1 | grep -i "tls\|error"
```

### 해결 방법 (권장)

**DestinationRule에서 mTLS DISABLE 설정**

```yaml
# was-destinationrule.yaml
apiVersion: networking.istio.io/v1beta1
kind: DestinationRule
metadata:
  name: was-dest-rule
  namespace: blog-system
spec:
  host: was-service
  trafficPolicy:
    tls:
      mode: DISABLE  # ✅ Plain HTTP 허용 (nginx → WAS 내부 통신)
    connectionPool:
      http:
        http1MaxPendingRequests: 100
        http2MaxRequests: 100
        maxRequestsPerConnection: 10
    loadBalancer:
      simple: ROUND_ROBIN
  subsets:
  - name: stable
  - name: canary
```

**적용 순서:**
```bash
# 1. 파일 수정
cd ~/k8s-manifests/blog-system
vim was-destinationrule.yaml
# tls.mode: ISTIO_MUTUAL → DISABLE

# 2. Git push
git add was-destinationrule.yaml
git commit -m "fix: Disable mTLS for nginx → WAS communication"
git push origin main

# 3. ArgoCD 동기화 확인 (자동)
kubectl get destinationrule was-dest-rule -n blog-system -o jsonpath='{.spec.trafficPolicy.tls.mode}'
# 출력: DISABLE ✅

# 4. Pod 재시작 (Istio sidecar 설정 적용)
kubectl delete pod -n blog-system -l app=was

# 5. 테스트
curl https://blog.jiminhome.shop/api/posts
# [{"id":1,"title":"First Post",...}] ✅
```

### 다른 해결 옵션 (비추천)

#### 옵션 2: nginx에서 mTLS 사용

```nginx
# web-nginx-config ConfigMap
location /api {
    proxy_pass https://was-service:8080;  # https로 변경
    proxy_ssl_verify off;  # 또는 인증서 설정
}
```

**단점:**
- nginx가 Istio mTLS 인증서 관리 필요
- 복잡도 증가
- 내부 통신에 불필요한 암호화

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

## AuthorizationPolicy RBAC 에러 (mTLS DISABLE 환경)

### 증상

```bash
# API 접근 시 403 Forbidden
curl https://blog.jiminhome.shop/api/posts
# RBAC: access denied

# Istio Proxy 로그
kubectl logs -n blog-system -l app=was -c istio-proxy --tail=10 | grep rbac
# [2026-01-21T03:11:48.062Z] "GET /api/posts HTTP/1.1" 403 -
#  rbac_access_denied_matched_policy[none]
```

**핵심**: `matched_policy[none]` → 어떤 정책도 매치되지 않음!

### 원인

**mTLS DISABLE 모드에서는 source identity를 사용할 수 없음**

1. DestinationRule: `tls.mode: DISABLE` (Plain HTTP)
2. AuthorizationPolicy: `source.principals` 또는 `source.namespaces` 조건 사용
3. **문제**: mTLS 없으면 Istio가 source identity를 알 수 없음
4. 결과: 모든 요청이 정책 매치 실패 → 403 Forbidden

```yaml
# ❌ 작동하지 않는 설정 (mTLS DISABLE 환경)
spec:
  rules:
  - from:
    - source:
        principals: ["cluster.local/ns/blog-system/sa/default"]  # mTLS 필요!
        namespaces: ["blog-system"]  # mTLS 필요!
    to:
    - operation:
        ports: ["8080"]
        paths: ["/api/*"]
```

**Istio Identity Flow:**
```
mTLS ENABLED:
  Client → [mTLS 인증서] → Istio → source.principal 파악 ✅

mTLS DISABLE:
  Client → [Plain HTTP] → Istio → source.principal 알 수 없음 ❌
```

### 진단

```bash
# 1. DestinationRule TLS 모드 확인
kubectl get destinationrule was-dest-rule -n blog-system -o jsonpath='{.spec.trafficPolicy.tls.mode}'
# 출력: DISABLE

# 2. AuthorizationPolicy 확인
kubectl get authorizationpolicy was-authz -n blog-system -o yaml

# 3. source 조건 있는지 확인
# from.source.principals 또는 from.source.namespaces 있으면 문제!

# 4. Istio 로그에서 RBAC 에러 확인
kubectl logs -n blog-system -l app=was -c istio-proxy --tail=50 | grep rbac
# rbac_access_denied_matched_policy[none] ← 정책 매치 실패!

# 5. 임시로 AuthorizationPolicy 삭제하고 테스트
kubectl delete authorizationpolicy was-authz -n blog-system
kubectl delete pod -n blog-system -l app=was
curl https://blog.jiminhome.shop/api/posts
# 성공하면 AuthorizationPolicy 설정 문제 확인됨
```

### 해결 방법

**from 조건 제거, to 조건만 사용**

```yaml
# authz-was.yaml
apiVersion: security.istio.io/v1beta1
kind: AuthorizationPolicy
metadata:
  name: was-authz
  namespace: blog-system
spec:
  selector:
    matchLabels:
      app: was
  action: ALLOW
  rules:
  # ✅ from 조건 없음 (mTLS DISABLE 모드)
  - to:
    - operation:
        ports: ["8080"]  # WAS 포트만 허용
        paths: ["/api/*", "/actuator/*"]  # 특정 경로만 허용
```

**적용 순서:**
```bash
# 1. 파일 수정
cd ~/k8s-manifests/blog-system
vim authz-was.yaml
# from 조건 전체 삭제, to 조건만 유지

# 2. Git push
git add authz-was.yaml
git commit -m "fix: Remove source conditions from WAS AuthorizationPolicy (mTLS DISABLE mode)"
git push origin main

# 3. ArgoCD 동기화 대기 또는 수동 적용
kubectl apply -f authz-was.yaml

# 4. Pod 재시작 (중요!)
kubectl delete pod -n blog-system -l app=was
kubectl wait --for=condition=ready pod -l app=was -n blog-system --timeout=60s

# 5. 테스트
curl https://blog.jiminhome.shop/api/posts
# [{"id":1,...}] ✅
```

### 보안 트레이드오프

**변경 전 (mTLS + principals):**
```yaml
# 강력한 보안
- from:
    source:
      principals: ["cluster.local/ns/blog-system/sa/default"]
      namespaces: ["blog-system"]
  to:
    operation:
      ports: ["8080"]
      paths: ["/api/*"]
```

**변경 후 (Plain HTTP + to only):**
```yaml
# 느슨한 보안 (port/path만 제어)
- to:
    operation:
      ports: ["8080"]
      paths: ["/api/*"]
```

**보안 수준 비교:**

| 조건 | 변경 전 | 변경 후 |
|------|--------|--------|
| **Source 검증** | ✅ namespace + ServiceAccount | ❌ 없음 |
| **Port 제한** | ✅ 8080만 | ✅ 8080만 |
| **Path 제한** | ✅ /api/*, /actuator/* | ✅ /api/*, /actuator/* |
| **외부 직접 접근** | ❌ 차단 (Ingress 없음) | ❌ 차단 (Ingress 없음) |
| **다른 namespace** | ❌ 차단 | ✅ 허용 (주의!) |

**추가 보안 계층:**
- WAS는 Ingress에 직접 노출 안 됨 (web nginx를 통해서만 접근)
- Kubernetes NetworkPolicy로 namespace 간 통신 제어 가능 (선택사항)

### 대안: mTLS 다시 활성화

더 강력한 보안이 필요하면:

```bash
# 1. DestinationRule mTLS 활성화
tls.mode: DISABLE → ISTIO_MUTUAL

# 2. AuthorizationPolicy source 조건 사용
from:
  - source:
      principals: ["cluster.local/ns/blog-system/sa/default"]

# 3. nginx도 mTLS 사용하도록 설정 (복잡)
# - Istio sidecar injection
# - nginx Envoy 통합
```

**비추천 이유**: 내부 통신에 과도한 복잡도

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
