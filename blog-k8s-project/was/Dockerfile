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
# -DskipTests: Docker 빌드 환경에 MySQL 없음 → 통합 테스트 실패 방지
# 테스트는 별도 CI 단계에서 testcontainers 또는 H2로 실행 권장
RUN ./mvnw clean package -DskipTests

# ==============================================================================
# Stage 2: Runtime - JAR 실행
# ==============================================================================
FROM eclipse-temurin:17-jre-alpine

# Non-Root User 생성 (보안 강화)
# - 컨테이너 탈출 시 호스트 root 접근 방지
# - PodSecurityStandard 준수
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

# Builder 스테이지에서 JAR 파일만 복사
COPY --from=builder /app/target/*.jar app.jar

# 파일 소유권 변경
RUN chown -R appuser:appgroup /app

# Non-Root User로 전환
USER appuser:appgroup

# 포트 노출
EXPOSE 8080

# 헬스체크 (선택사항)
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost:8080/actuator/health || exit 1

# ==============================================================================
# JVM 튜닝 옵션 설명
# ==============================================================================
# -Xms256m: 최소 힙 256MB (시작 시 할당)
# -Xmx512m: 최대 힙 512MB (상한선, 메모리 제한)
# -XX:+UseG1GC: G1 Garbage Collector (짧은 GC pause 목표)
# -XX:MaxGCPauseMillis=100: GC pause 100ms 이하 목표 (응답성 유지)
# -XX:+UseContainerSupport: 컨테이너 메모리 제한 인식 (JDK 10+ 기본)
# ==============================================================================

# 애플리케이션 실행 (JVM 튜닝 적용)
ENTRYPOINT ["java", \
  "-Xms256m", \
  "-Xmx512m", \
  "-XX:+UseG1GC", \
  "-XX:MaxGCPauseMillis=100", \
  "-XX:+UseContainerSupport", \
  "-jar", "app.jar"]
