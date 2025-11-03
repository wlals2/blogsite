#!/bin/bash

# 설정
PUSHGATEWAY_URL="http://localhost:9091"
JOB_NAME="blogsite_build"
INSTANCE_NAME=$(hostname)

# 시작 시간 기록
START_TIME=$(date +%s)
echo "🚀 빌드 시작: $(date)"

# 메트릭 초기화
send_metric() {
    local metric_name=$1
    local metric_value=$2
    local metric_type=${3:-gauge}
    
    cat <<EOF | curl --data-binary @- "${PUSHGATEWAY_URL}/metrics/job/${JOB_NAME}/instance/${INSTANCE_NAME}" 2>/dev/null
# TYPE ${metric_name} ${metric_type}
${metric_name} ${metric_value}
EOF
}

# 빌드 시작 메트릭
send_metric "build_start_timestamp" "$START_TIME" "gauge"
send_metric "build_in_progress" "1" "gauge"

# 실제 빌드 명령 (여기를 수정하세요!)
# Hugo인 경우:
# hugo --minify
# Jekyll인 경우:
# bundle exec jekyll build
# npm인 경우:
# npm run build

# 임시 빌드 명령 (실제 명령으로 교체하세요)
echo "여기에 실제 빌드 명령을 넣으세요"
sleep 2  # 빌드 시뮬레이션

BUILD_EXIT_CODE=$?

# 종료 시간 및 빌드 시간 계산
END_TIME=$(date +%s)
BUILD_DURATION=$((END_TIME - START_TIME))

echo "⏱️  빌드 소요 시간: ${BUILD_DURATION}초"

# 빌드 결과 메트릭
if [ $BUILD_EXIT_CODE -eq 0 ]; then
    echo "✅ 빌드 성공!"
    send_metric "build_success" "1" "gauge"
    send_metric "build_failures_total" "0" "counter"
else
    echo "❌ 빌드 실패!"
    send_metric "build_success" "0" "gauge"
    send_metric "build_failures_total" "1" "counter"
fi

# 공통 메트릭
send_metric "build_duration_seconds" "$BUILD_DURATION" "gauge"
send_metric "build_timestamp" "$END_TIME" "gauge"
send_metric "build_in_progress" "0" "gauge"
send_metric "builds_total" "1" "counter"

echo "📊 메트릭이 Pushgateway로 전송되었습니다"
exit $BUILD_EXIT_CODE
