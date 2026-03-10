#!/bin/bash
# ==============================================================================
# cleanup-build-cache.sh - Docker 빌드 캐시 월간 정리
# ==============================================================================
# Purpose: BuildKit 내부 캐시가 방치되면 수십 GB까지 쌓임
#          10GB 상한 + 30일 미사용 레이어 삭제로 캐시 freshness 유지
# Usage:   ./cleanup-build-cache.sh [--dry-run]
# Schedule: cron - 매월 1일 03:00 KST
# ==============================================================================

set -euo pipefail

DRY_RUN=false
LOG_PREFIX="[build-cache-cleanup]"

if [[ "${1:-}" == "--dry-run" ]]; then
    DRY_RUN=true
    echo "$LOG_PREFIX DRY-RUN 모드"
fi

echo "$LOG_PREFIX 시작: $(date '+%Y-%m-%d %H:%M:%S')"

# 정리 전 크기 확인
BEFORE=$(docker system df --format "{{.BuildCache}}" 2>/dev/null | tail -1 || echo "측정불가")
echo "$LOG_PREFIX 정리 전 Build Cache: $BEFORE"
echo "$LOG_PREFIX type=local 캐시:"
du -sh /mnt/data/ci/cache/buildkit-web/ /mnt/data/ci/cache/buildkit-was/ 2>/dev/null || true

if [[ "$DRY_RUN" == "true" ]]; then
    echo "$LOG_PREFIX [DRY-RUN] 실제 삭제 생략"
    echo "$LOG_PREFIX 실행 시 명령어: docker buildx prune --keep-storage 10GB --filter until=720h -f"
    exit 0
fi

# Why: --keep-storage 10GB → 10GB 초과분만 삭제 (HIT율 높은 최근 레이어 보호)
#      --filter until=720h  → 30일(720h) 이상 미사용 레이어만 대상
#      -f                   → 확인 프롬프트 생략 (cron 자동화용)
docker buildx prune --keep-storage 10GB --filter until=720h -f

AFTER=$(docker system df --format "{{.BuildCache}}" 2>/dev/null | tail -1 || echo "측정불가")
echo "$LOG_PREFIX 정리 후 Build Cache: $AFTER"
echo "$LOG_PREFIX 완료: $(date '+%Y-%m-%d %H:%M:%S')"
