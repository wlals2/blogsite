#!/bin/bash
# ==============================================================================
# Worker 노드에서 실행할 스크립트
# ==============================================================================
# 목적: Worker 노드가 로컬 Docker Registry를 사용할 수 있도록 설정
#
# 사용 방법:
# 1. 이 파일을 k8s-worker1, k8s-worker2에 복사
# 2. 각 worker 노드에 SSH 접속
# 3. sudo ./worker-setup.sh 실행
#
# 이 스크립트가 하는 일:
# - /etc/docker/daemon.json에 insecure-registries 추가
# - Docker 데몬 재시작
# ==============================================================================

echo "🔧 Worker 노드 Docker Registry 설정 시작..."

# 1. 백업
echo "📦 기존 설정 백업..."
sudo cp /etc/docker/daemon.json /etc/docker/daemon.json.backup 2>/dev/null || true

# 2. 새 설정 작성
echo "✏️  새 설정 작성..."
sudo tee /etc/docker/daemon.json > /dev/null <<'EOF'
{
  "exec-opts": ["native.cgroupdriver=systemd"],
  "log-driver": "json-file",
  "log-opts": {
    "max-size": "100m"
  },
  "storage-driver": "overlay2",
  "insecure-registries": ["192.168.1.187:5000"]
}
EOF

# 3. Docker 재시작
echo "🔄 Docker 재시작..."
sudo systemctl restart docker

# 4. 검증
echo "✅ 설정 확인..."
cat /etc/docker/daemon.json | grep insecure-registries

# 5. Registry 접근 테스트
echo "🧪 Registry 접근 테스트..."
curl http://192.168.1.187:5000/v2/_catalog

echo ""
echo "✅ 설정 완료!"
echo "이제 이 노드에서 192.168.1.187:5000/blog-web:v1 이미지를 pull할 수 있습니다."
