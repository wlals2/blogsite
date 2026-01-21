# ==============================================================================
# Spring Boot Board Dockerfile (Multi-stage Build)
# ==============================================================================
# 목적: Spring Boot 애플리케이션을 컨테이너 이미지로 빌드
#
# Multi-stage Build:
# - Stage 1 (builder): Maven으로 JAR 빌드 (JDK 17)
# - Stage 2 (runtime): JAR 실행만 (JRE 17)
# - 이미지 크기: ~150MB (JDK 포함 시 ~400MB)
# ==============================================================================

# ==============================================================================
# Stage 1: Builder - Maven 빌드
# ==============================================================================
FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /app

# Maven Wrapper 복사 (캐싱 최적화)
COPY .mvn .mvn
COPY mvnw .
COPY pom.xml .

# 의존성 다운로드 (캐싱 레이어)
# pom.xml이 변경되지 않으면 이 레이어 재사용
RUN ./mvnw dependency:go-offline

# 소스 코드 복사
COPY src ./src

# Maven 빌드 (테스트 스킵)
# -DskipTests: 빌드 시간 단축 (CI/CD에서 별도 테스트)
RUN ./mvnw clean package -DskipTests

# ==============================================================================
# Stage 2: Runtime - JAR 실행
# ==============================================================================
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Builder 스테이지에서 JAR 파일만 복사
COPY --from=builder /app/target/*.jar app.jar

# 포트 노출
EXPOSE 8080

# 헬스체크 (선택사항)
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost:8080/actuator/health || exit 1

# 애플리케이션 실행
ENTRYPOINT ["java", "-jar", "app.jar"]
