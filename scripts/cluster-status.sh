#!/bin/bash
# ==============================================================================
# cluster-status.sh
# ==============================================================================
# Purpose: Kubernetes 클러스터 전체 상태를 한 눈에 확인
# Usage: ./cluster-status.sh [--help] [--ns NAMESPACE] [--full]
# Why: 장애 발생 시 노드/Pod/Endpoints/Istio를 하나씩 치기 번거로움
# ==============================================================================

set -euo pipefail

# 색상
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

NAMESPACE=""
FULL=false

# --help
if [[ "${1:-}" == "--help" ]]; then
  echo "Usage: $0 [--ns NAMESPACE] [--full]"
  echo ""
  echo "Options:"
  echo "  --ns NAMESPACE   특정 namespace만 진단 (기본: blog-system)"
  echo "  --full           전체 namespace 비정상 Pod 포함"
  echo "  --help           이 도움말"
  echo ""
  echo "Examples:"
  echo "  $0                  # blog-system 기본 진단"
  echo "  $0 --ns monitoring  # monitoring namespace 진단"
  echo "  $0 --full           # 전체 클러스터 진단"
  exit 0
fi

# 인자 파싱
while [[ $# -gt 0 ]]; do
  case $1 in
    --ns) NAMESPACE="$2"; shift 2;;
    --full) FULL=true; shift;;
    *) shift;;
  esac
done

# 기본값
[[ -z "$NAMESPACE" ]] && NAMESPACE="blog-system"

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE} Cluster Status Check$(date +'  %Y-%m-%d %H:%M:%S')${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""

# 1. 노드 상태
echo -e "${YELLOW}[1/5] 노드 상태${NC}"
echo "---"
kubectl get nodes -o wide 2>/dev/null | while IFS= read -r line; do
  if echo "$line" | grep -q "NotReady"; then
    echo -e "${RED}${line}${NC}"
  elif echo "$line" | grep -q "Ready"; then
    echo -e "${GREEN}${line}${NC}"
  else
    echo "$line"
  fi
done
echo ""

# 2. 대상 namespace Pod 상태
echo -e "${YELLOW}[2/5] ${NAMESPACE} Pod 상태${NC}"
echo "---"
PODS=$(kubectl get pods -n "$NAMESPACE" -o wide 2>/dev/null)
if [[ -z "$PODS" ]]; then
  echo -e "${RED}Pod가 없습니다!${NC}"
else
  echo "$PODS" | while IFS= read -r line; do
    if echo "$line" | grep -qE "Error|CrashLoop|Pending|ImagePull"; then
      echo -e "${RED}${line}${NC}"
    elif echo "$line" | grep -q "Running"; then
      echo -e "${GREEN}${line}${NC}"
    else
      echo "$line"
    fi
  done
fi
echo ""

# 3. Endpoints (빈 Endpoints = 트래픽 못 받음)
echo -e "${YELLOW}[3/5] ${NAMESPACE} Endpoints${NC}"
echo "---"
kubectl get endpoints -n "$NAMESPACE" 2>/dev/null | while IFS= read -r line; do
  if echo "$line" | grep -q "<none>"; then
    echo -e "${RED}${line}  ← Endpoints 비어있음!${NC}"
  else
    echo -e "${GREEN}${line}${NC}"
  fi
done
echo ""

# 4. Istio 라우팅 (VirtualService + DestinationRule)
echo -e "${YELLOW}[4/5] ${NAMESPACE} Istio 라우팅${NC}"
echo "---"
echo "VirtualService:"
kubectl get virtualservice -n "$NAMESPACE" 2>/dev/null || echo "  (없음)"
echo ""
echo "DestinationRule:"
kubectl get destinationrule -n "$NAMESPACE" 2>/dev/null || echo "  (없음)"
echo ""

# 5. 종합 판단
echo -e "${YELLOW}[5/5] 종합 판단${NC}"
echo "---"
ISSUES=0

# 노드 체크
NOTREADY_NODES=$(kubectl get nodes --no-headers 2>/dev/null | grep "NotReady" || true)
NOTREADY_COUNT=$(echo "$NOTREADY_NODES" | grep -c "NotReady" || true)
if [[ $NOTREADY_COUNT -gt 0 ]]; then
  echo -e "  ${RED}[문제] 노드 ${NOTREADY_COUNT}개 NotReady${NC}"
  echo "$NOTREADY_NODES" | while read -r line; do
    NODE_NAME=$(echo "$line" | awk '{print $1}')
    echo -e "         └ ${NODE_NAME} — kubelet 중단 또는 VM 꺼짐"
  done
  ISSUES=$((ISSUES + 1))
else
  echo -e "  ${GREEN}[정상] 노드: 전체 Ready${NC}"
fi

# Pod 체크 (대상 namespace)
POD_TOTAL=$(kubectl get pods -n "$NAMESPACE" --no-headers 2>/dev/null | wc -l || true)
POD_RUNNING=$(kubectl get pods -n "$NAMESPACE" --no-headers 2>/dev/null | grep -c "Running" || true)
POD_PROBLEM=$(kubectl get pods -n "$NAMESPACE" --no-headers 2>/dev/null | grep -cE "Error|CrashLoop|Pending|ImagePull" || true)
if [[ $POD_TOTAL -eq 0 ]]; then
  echo -e "  ${RED}[문제] ${NAMESPACE}: Pod가 없음${NC}"
  ISSUES=$((ISSUES + 1))
elif [[ $POD_PROBLEM -gt 0 ]]; then
  echo -e "  ${RED}[문제] ${NAMESPACE}: 비정상 Pod ${POD_PROBLEM}개 / 전체 ${POD_TOTAL}개${NC}"
  ISSUES=$((ISSUES + 1))
else
  echo -e "  ${GREEN}[정상] ${NAMESPACE}: ${POD_RUNNING}/${POD_TOTAL} Running${NC}"
fi

# Endpoints 체크
EMPTY_EP=$(kubectl get endpoints -n "$NAMESPACE" --no-headers 2>/dev/null | grep "<none>" || true)
EMPTY_EP_COUNT=$(echo "$EMPTY_EP" | grep -c "<none>" || true)
if [[ $EMPTY_EP_COUNT -gt 0 ]]; then
  echo -e "  ${YELLOW}[주의] Endpoints 비어있음 ${EMPTY_EP_COUNT}개${NC}"
  echo "$EMPTY_EP" | while read -r line; do
    SVC_NAME=$(echo "$line" | awk '{print $1}')
    echo -e "         └ ${SVC_NAME} — 트래픽 수신 불가"
  done
  ISSUES=$((ISSUES + 1))
else
  echo -e "  ${GREEN}[정상] Endpoints: 전체 연결됨${NC}"
fi

# Ingress Gateway 체크
GW_READY=$(kubectl get pods -n istio-system -l app=istio-ingressgateway --no-headers 2>/dev/null | grep -c "Running" || true)
if [[ $GW_READY -eq 0 ]]; then
  echo -e "  ${RED}[문제] Istio Ingress Gateway: Pod 없음 → 외부 접근 불가${NC}"
  ISSUES=$((ISSUES + 1))
else
  echo -e "  ${GREEN}[정상] Istio Ingress Gateway: ${GW_READY}개 Running${NC}"
fi

# 전체 클러스터 비정상 Pod
PENDING_ALL=$(kubectl get pods -A --field-selector=status.phase=Pending --no-headers 2>/dev/null | wc -l || true)
FAILED_ALL=$(kubectl get pods -A --field-selector=status.phase=Failed --no-headers 2>/dev/null | wc -l || true)
if [[ $((PENDING_ALL + FAILED_ALL)) -gt 0 ]]; then
  echo -e "  ${YELLOW}[주의] 전체 클러스터: Pending ${PENDING_ALL}개, Failed ${FAILED_ALL}개${NC}"
  ISSUES=$((ISSUES + 1))
fi

echo ""
if [[ $ISSUES -eq 0 ]]; then
  echo -e "${GREEN}결과: 전 구간 정상${NC}"
else
  echo -e "${RED}결과: ${ISSUES}개 구간에서 문제 발견${NC}"
fi

# --full: 비정상 Pod 상세
if [[ "$FULL" == true ]]; then
  echo ""
  echo -e "${YELLOW}[상세] 전체 클러스터 비정상 Pod${NC}"
  echo "---"
  ABNORMAL=$(kubectl get pods -A --field-selector=status.phase!=Running,status.phase!=Succeeded --no-headers 2>/dev/null || true)
  if [[ -z "$ABNORMAL" ]]; then
    echo -e "${GREEN}비정상 Pod 없음${NC}"
  else
    echo -e "${RED}${ABNORMAL}${NC}"
  fi
fi

echo ""
echo -e "${BLUE}========================================${NC}"
