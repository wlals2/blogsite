# ==============================================================================
# mysql-backup - MySQL 백업용 커스텀 이미지
# ==============================================================================
# 목적: mysql:8.0-debian 기반에 awscli/curl/gzip을 빌드 시 설치
# Why:  런타임 apt-get install 제거 → Falco "Drop and execute new binary" 오탐 해소
#       베이스 이미지에 도구가 포함되므로 EXE_UPPER_LAYER 플래그가 발생하지 않음
# 의존성: backup-cronjobs.yaml (CronJob에서 이 이미지를 참조)
# ==============================================================================

FROM mysql:8.0-debian

# Why: 런타임이 아닌 빌드 시점에 필요 도구 설치
#      awscli: S3 업로드/검증
#      curl: Pushgateway 메트릭 전송
#      gzip: mysqldump 결과 압축
RUN apt-get update -qq && \
    apt-get install -y --no-install-recommends \
      awscli \
      curl \
      gzip && \
    rm -rf /var/lib/apt/lists/*
