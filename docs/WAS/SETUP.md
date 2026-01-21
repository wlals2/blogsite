# WAS 설정 가이드

> 로컬 개발 환경부터 Kubernetes 배포까지

---

## 로컬 개발 환경

### 1. 사전 요구사항

```bash
# Java 17
java -version
# openjdk version "17.0.x"

# Maven (또는 ./mvnw 사용)
mvn -version

# MySQL 8.0 (Docker 권장)
docker --version

# (선택) IntelliJ IDEA / VS Code
```

### 2. MySQL 실행

```bash
# Docker로 MySQL 실행
docker run -d \
  --name mysql-dev \
  -e MYSQL_ROOT_PASSWORD=rootpassword \
  -e MYSQL_DATABASE=board \
  -p 3306:3306 \
  mysql:8.0.44

# 연결 확인
docker exec -it mysql-dev mysql -uroot -prootpassword -e "SHOW DATABASES;"
```

### 3. application.properties 설정

```bash
cd ~/blogsite/blog-k8s-project/was

cat > src/main/resources/application.properties <<EOF
spring.application.name=Board

# MySQL 연결
spring.datasource.url=jdbc:mysql://localhost:3306/board
spring.datasource.username=root
spring.datasource.password=rootpassword

# JPA 설정
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=true
spring.jpa.properties.hibernate.format_sql=true

# Actuator
management.endpoints.web.exposure.include=health,info
EOF
```

### 4. WAS 실행

```bash
# Maven Wrapper로 실행
./mvnw spring-boot:run

# 또는 JAR 빌드 후 실행
./mvnw clean package -DskipTests
java -jar target/board-0.0.1-SNAPSHOT.jar
```

### 5. API 테스트

```bash
# Health Check
curl http://localhost:8080/actuator/health

# 게시글 조회
curl http://localhost:8080/api/posts

# 게시글 작성
curl -X POST http://localhost:8080/api/posts \
  -H "Content-Type: application/json" \
  -d '{
    "title": "로컬 테스트",
    "content": "로컬에서 작성한 글",
    "author": "개발자"
  }'
```

---

## IntelliJ IDEA 설정

### 1. 프로젝트 임포트

```
File → Open → ~/blogsite/blog-k8s-project/was/pom.xml
→ Open as Project
```

### 2. Lombok 플러그인 설치

```
Settings → Plugins → "Lombok" 검색 → Install
Settings → Build, Execution, Deployment → Compiler → Annotation Processors
  → Enable annotation processing ✅
```

### 3. Run Configuration

```
Run → Edit Configurations → + → Application
  - Name: BoardApplication
  - Main class: com.jimin.board.BoardApplication
  - VM options: -Dspring.profiles.active=local
  - Use classpath of module: board
```

### 4. 실행 및 디버깅

```
Shift+F10: 실행
Shift+F9: 디버깅
```

---

## VS Code 설정

### 1. Extensions 설치

```bash
code --install-extension vscjava.vscode-java-pack
code --install-extension vscjava.vscode-spring-boot
code --install-extension gabrielbb.vscode-lombok
```

### 2. launch.json

```json
{
  "version": "0.2.0",
  "configurations": [
    {
      "type": "java",
      "name": "Spring Boot",
      "request": "launch",
      "mainClass": "com.jimin.board.BoardApplication",
      "projectName": "board",
      "args": "",
      "envFile": "${workspaceFolder}/.env"
    }
  ]
}
```

### 3. .env 파일

```bash
SPRING_DATASOURCE_URL=jdbc:mysql://localhost:3306/board
SPRING_DATASOURCE_USERNAME=root
SPRING_DATASOURCE_PASSWORD=rootpassword
```

---

## Docker 빌드

### 1. Dockerfile 확인

```dockerfile
# ~/blogsite/blog-k8s-project/was/Dockerfile

# Stage 1: Build
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Runtime
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### 2. 로컬 빌드

```bash
cd ~/blogsite/blog-k8s-project/was

# 빌드
docker build -t board-was:local .

# 실행
docker run -d \
  --name board-was \
  --network host \
  -e SPRING_DATASOURCE_URL=jdbc:mysql://localhost:3306/board \
  -e SPRING_DATASOURCE_USERNAME=root \
  -e SPRING_DATASOURCE_PASSWORD=rootpassword \
  board-was:local

# 로그 확인
docker logs -f board-was

# 테스트
curl http://localhost:8080/api/posts
```

### 3. Docker Compose (전체 스택)

```yaml
# docker-compose.yml (TODO)
version: '3.8'
services:
  mysql:
    image: mysql:8.0.44
    environment:
      MYSQL_ROOT_PASSWORD: rootpassword
      MYSQL_DATABASE: board
    ports:
      - "3306:3306"
    volumes:
      - mysql-data:/var/lib/mysql

  was:
    build: .
    environment:
      SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/board
      SPRING_DATASOURCE_USERNAME: root
      SPRING_DATASOURCE_PASSWORD: rootpassword
    ports:
      - "8080:8080"
    depends_on:
      - mysql

volumes:
  mysql-data:
```

```bash
docker-compose up -d
```

---

## Kubernetes 배포

### 1. 이미지 빌드 및 푸시

```bash
# GitHub Actions 워크플로우 수동 실행
# https://github.com/wlals2/blogsite/actions/workflows/deploy-was.yml
# → Run workflow

# 또는 로컬에서 빌드 (권장하지 않음)
docker build -t ghcr.io/wlals2/board-was:v10 .
docker push ghcr.io/wlals2/board-was:v10
```

### 2. Manifest 업데이트 (GitOps)

```bash
cd ~/k8s-manifests/blog-system

# 이미지 태그 변경
yq eval '.spec.template.spec.containers[0].image = "ghcr.io/wlals2/board-was:v10"' \
  -i was-rollout.yaml

# Git Commit & Push
git add was-rollout.yaml
git commit -m "chore: Update WAS image to v10"
git push origin main
```

### 3. ArgoCD 자동 배포 확인

```bash
# ArgoCD 동기화 대기 (3분 이내)
watch kubectl get application blog-system -n argocd

# Rollout 상태 확인
kubectl argo rollouts get rollout was -n blog-system --watch

# Pod 상태 확인
kubectl get pods -n blog-system -l app=was
```

### 4. 배포 검증

```bash
# Health Check
kubectl exec -n blog-system $(kubectl get pod -n blog-system -l app=web -o jsonpath='{.items[0].metadata.name}') \
  -c nginx -- curl -s http://was-service:8080/actuator/health

# API 테스트 (외부)
curl https://blog.jiminhome.shop/api/posts
```

---

## 트러블슈팅

### MySQL 연결 실패

**에러:**
```
java.sql.SQLException: Access denied for user 'root'@'localhost'
```

**해결:**
```bash
# 비밀번호 확인
docker exec -it mysql-dev mysql -uroot -prootpassword -e "SELECT 1"

# application.properties 확인
cat src/main/resources/application.properties | grep datasource
```

### Port 충돌

**에러:**
```
Bind for 0.0.0.0:8080 failed: port is already allocated
```

**해결:**
```bash
# 사용 중인 프로세스 확인
lsof -i :8080

# 프로세스 종료
kill -9 <PID>

# 또는 다른 포트 사용
./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=8081
```

### Lombok Annotation 인식 안 됨

**에러:**
```
cannot find symbol: method getTitle()
```

**해결:**
```bash
# IntelliJ: Lombok 플러그인 설치 + Annotation Processing 활성화
# VS Code: Extension Pack for Java 설치
# Maven: ./mvnw clean compile
```

### JAR 실행 시 Main Class 못 찾음

**에러:**
```
no main manifest attribute
```

**해결:**
```xml
<!-- pom.xml 확인 -->
<build>
  <plugins>
    <plugin>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-maven-plugin</artifactId>
    </plugin>
  </plugins>
</build>
```

---

## 테스트

### Unit Test

```bash
# 전체 테스트
./mvnw test

# 특정 클래스
./mvnw test -Dtest=PostServiceTest

# 특정 메서드
./mvnw test -Dtest=PostServiceTest#testCreatePost
```

### Integration Test

```bash
# MySQL TestContainers 사용 (TODO)
./mvnw verify
```

### API Test (Postman)

```bash
# Collection 실행
newman run postman_collection.json \
  --environment postman_environment.json
```

---

## 성능 테스트

### Apache Bench

```bash
# 100개 요청, 동시 10개
ab -n 100 -c 10 http://localhost:8080/api/posts

# 결과 분석
# - Requests per second (처리량)
# - Time per request (응답 시간)
```

### JMeter (TODO)

```bash
# Thread Group: 100 users
# Ramp-up: 10s
# Loop: 10
# Total: 1000 requests
```

---

## 프로파일링

### JVM 메모리

```bash
# Heap Dump
jmap -dump:live,format=b,file=heap.hprof <PID>

# 분석 (VisualVM, Eclipse MAT)
```

### 쿼리 분석

```properties
# application.properties
spring.jpa.show-sql=true
spring.jpa.properties.hibernate.format_sql=true
logging.level.org.hibernate.SQL=DEBUG
logging.level.org.hibernate.type.descriptor.sql.BasicBinder=TRACE
```

---

## 환경별 설정

### application-local.properties

```properties
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=true
logging.level.com.jimin.board=DEBUG
```

### application-prod.properties

```properties
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.show-sql=false
logging.level.com.jimin.board=INFO
```

### 프로파일 활성화

```bash
# 로컬
./mvnw spring-boot:run -Dspring-boot.run.profiles=local

# 운영 (Kubernetes)
# was-rollout.yaml에서 환경변수 추가
env:
- name: SPRING_PROFILES_ACTIVE
  value: prod
```

---

**작성일:** 2026-01-21
**마지막 업데이트:** v9
