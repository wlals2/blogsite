# ==============================================================================
# etcd-backup - etcd 백업용 커스텀 이미지
# ==============================================================================
# 목적: alpine 기반에 etcdctl/aws-cli/curl을 빌드 시 설치
# Why:  런타임 apk add + wget etcdctl 제거 → Falco 오탐 해소 + 빌드 시간 단축
#       Alpine 공식 repo에 etcdctl 패키지 없음 → GitHub Release에서 직접 설치
# 의존성: backup-cronjobs.yaml (CronJob에서 이 이미지를 참조)
# ==============================================================================

# --- Stage 1: etcdctl 바이너리 추출 ---
# Why: Alpine에 etcdctl 패키지 없음 → 공식 GitHub Release에서 가져와야 함
#      멀티스테이지로 분리하면 tar/wget이 최종 이미지에 남지 않아 공격 표면 축소
FROM alpine:3.19 AS etcdctl-builder

ARG ETCD_VERSION=v3.5.11
RUN wget -q "https://github.com/etcd-io/etcd/releases/download/${ETCD_VERSION}/etcd-${ETCD_VERSION}-linux-amd64.tar.gz" && \
    tar -xzf "etcd-${ETCD_VERSION}-linux-amd64.tar.gz" && \
    mv "etcd-${ETCD_VERSION}-linux-amd64/etcdctl" /usr/local/bin/etcdctl && \
    rm -rf "etcd-${ETCD_VERSION}-linux-amd64"*

# --- Stage 2: 최종 이미지 ---
FROM alpine:3.19

# Why: 런타임이 아닌 빌드 시점에 필요 도구 설치
#      aws-cli: S3 업로드/검증/retention 삭제
#      curl: Pushgateway 메트릭 전송
#      gzip: 스냅샷 압축 (필요 시)
RUN apk add --no-cache \
      aws-cli \
      curl \
      gzip

# Why: 멀티스테이지에서 etcdctl 바이너리만 복사 (tar, wget 등 빌드 도구 제외)
COPY --from=etcdctl-builder /usr/local/bin/etcdctl /usr/local/bin/etcdctl
