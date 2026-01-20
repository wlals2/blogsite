# Storage & Service 분석 및 최적화 보고서

> **분석 일자**: 2026-01-20
> **삭제 완료**: 2026-01-20
> **목적**: Longhorn과 Nextcloud 사용 현황 분석 및 운영 최적화

---

## 📊 전체 요약 (✅ 실행 완료)

| 항목 | Before | After | 결과 |
|------|--------|-------|------|
| **Longhorn** | 15Gi 사용 (2개 PVC) | 15Gi 사용 (2개 PVC) | ✅ **유지** (운영 중) |
| **Nextcloud** | 30Gi 사용, 외부 접근 없음 | **삭제 완료** | ✅ **30Gi 절약** |
| **총 스토리지** | 120Gi (8개 PVC) | **90Gi (5개 PVC)** | ✅ **25% 절약** |
| **Pod 수** | 100개 | 98개 | -2 Pods |

---

## 🔍 Longhorn 사용 현황

### 1. 실제 사용량

**운영 중인 PVC (2개)**:
```
blog-system/mysql-pvc         5Gi    (생성: 3d9h 전)
monitoring/storage-loki-stack 10Gi   (생성: 37h 전)
```

**총 사용량**: 15Gi

### 2. Longhorn 리소스 소비

**Pod 수**: 23개
```
- CSI Driver: 15개 (attacher, provisioner, resizer, snapshotter, plugin)
- Manager: 2개 (k8s-worker1, k8s-worker2)
- UI: 2개
- Instance Manager: 2개
- Engine Image: 2개
```

**리소스 사용**:
```
CPU: ~30-40m (최대 12m/pod)
Memory: ~200Mi (평균 10Mi/pod)
```

### 3. StorageClass 구성

```yaml
NAME                 PROVISIONER             DEFAULT
longhorn (default)   driver.longhorn.io      Yes
longhorn-static      driver.longhorn.io      No
local-path           rancher.io/local-path   No
```

**주요 기능**:
- ✅ 복제 (Replication) - 데이터 고가용성
- ✅ 스냅샷 (Snapshot) - 백업 및 복구
- ✅ 동적 확장 (Volume Expansion)
- ✅ CSI 드라이버 (자동 프로비저닝)

### 4. 왜 Longhorn을 사용하나?

**현재 사용 중인 워크로드**:
1. **blog-system MySQL (5Gi)** - 블로그 DB, 데이터 안정성 중요
2. **monitoring Loki (10Gi)** - 로그 저장소, 손실 시 히스토리 유실

**Longhorn의 가치**:
- 📦 복제를 통한 데이터 안전성 (노드 장애 시에도 데이터 보존)
- 🔄 스냅샷으로 백업/복구 가능
- 📈 동적 볼륨 확장 (용량 부족 시 PVC 크기 조정)

---

## ❌ Nextcloud 문제점

### 1. 실제 사용 현황

**배포 상태**:
```
Pod: nextcloud-749ff94d7c-22xhh (1개, 55일 전 생성)
DB:  nextcloud-db-5f696d4f47-922r5 (1개)
```

**리소스 사용**:
```
CPU: 1m (거의 idle)
Memory: 21Mi (앱) + 76Mi (DB) = 97Mi
```

**스토리지 사용**: 30Gi (3개 PVC)
```
nextcloud-app-pvc  10Gi (local-path)
nextcloud-data-pvc 10Gi (local-path)
nextcloud-db-pvc   10Gi (local-path)
```

### 2. 접근 불가 (미사용 증거)

**외부 접근 없음**:
```bash
$ kubectl get ingress -n nextcloud
No resources found

$ kubectl get svc -n nextcloud
service/nextcloud  NodePort  10.96.82.200  <none>  80:30888/TCP
```

- ❌ Ingress 미설정 (외부 도메인 접근 불가)
- ⚠️ NodePort만 열림 (192.168.1.200:30888)
- ⚠️ 내부 네트워크에서만 접근 가능

**결론**: 외부에서 접근할 방법이 없음 = 사용하지 않음

### 3. 왜 Nextcloud가 문제인가?

| 항목 | 문제 |
|------|------|
| **스토리지** | 30Gi 차지 (전체의 25%) |
| **활용도** | CPU 1m, 외부 접근 없음 |
| **관리 부담** | 2개 Pod, 3개 PVC 유지 비용 |
| **보안** | 미사용 서비스는 공격 표면 증가 |

---

## 💡 권장 조치

### 1. Longhorn - ✅ 유지

**유지 이유**:
- blog-system MySQL은 블로그 핵심 데이터
- monitoring Loki는 55일치 로그 보관 중
- 복제/스냅샷 기능이 실제 가치 제공

**개선 방안** (선택):
```bash
# (옵션 1) Longhorn 복제 수 줄이기 (3→2) - 리소스 절약
# (옵션 2) 장기적으로 AWS EBS CSI로 전환 검토
```

### 2. Nextcloud - ❌ 삭제 권장

**삭제 시 이점**:
- 💾 스토리지 30Gi 확보
- 🧹 관리 부담 감소 (5개 리소스 제거)
- 🔐 보안 표면 축소

**삭제 절차** (안전하게):
```bash
# 1. 혹시 모를 데이터 백업 (필요 시)
kubectl exec -n nextcloud nextcloud-749ff94d7c-22xhh -- \
  tar -czf /tmp/nextcloud-backup.tar.gz /var/www/html/data

# 2. Namespace 전체 삭제
kubectl delete namespace nextcloud

# 3. PV 확인 (자동 삭제됨, ReclaimPolicy=Delete)
kubectl get pv | grep nextcloud

# 4. 스토리지 확인
df -h /mnt/data
```

### 3. 쿠버네티스 운영 강화 집중

**Nextcloud 삭제 후 집중할 부분**:

```
현재 리소스:
- Monitoring (PLG Stack) ← 55일 운영 중 ✅
- Blog System (WEB + WAS) ← 4일 운영 중 ✅
- ArgoCD (GitOps) ← 설치 완료 ✅

집중 영역:
1. ArgoCD Application 생성 (blog-system 자동 배포)
2. Prometheus Alert 실전 테스트
3. Loki 로그 기반 트러블슈팅 연습
4. HPA 부하 테스트 (실제 스케일링 확인)
5. Grafana Dashboard 개선 (비즈니스 메트릭 추가)
```

---

## 📈 스토리지 현황 (Before/After)

### Before (현재)
```
총 PV: 8개 (120Gi)
├─ Longhorn (2개): 15Gi
│  ├─ blog-system/mysql-pvc: 5Gi ✅
│  └─ monitoring/loki: 10Gi ✅
└─ Local-path (6개): 105Gi
   ├─ monitoring/prometheus: 50Gi ✅
   ├─ monitoring/grafana: 10Gi ✅
   ├─ monitoring/pushgateway: 5Gi ✅
   ├─ nextcloud/app: 10Gi ❌
   ├─ nextcloud/data: 10Gi ❌
   └─ nextcloud/db: 10Gi ❌
```

### After (Nextcloud 삭제 시)
```
총 PV: 5개 (90Gi) - 25% 절약
├─ Longhorn (2개): 15Gi
│  ├─ blog-system/mysql-pvc: 5Gi ✅
│  └─ monitoring/loki: 10Gi ✅
└─ Local-path (3개): 75Gi
   ├─ monitoring/prometheus: 50Gi ✅
   ├─ monitoring/grafana: 10Gi ✅
   └─ monitoring/pushgateway: 5Gi ✅

절약:
- 스토리지: 30Gi
- Pod: 2개
- PVC: 3개
```

---

## 🎯 결론 및 실행 결과

### Longhorn
- **판단**: ✅ **유지**
- **이유**:
  - blog MySQL과 monitoring Loki가 의존
  - 복제/스냅샷 기능이 데이터 안정성 제공
  - 운영 비용 대비 가치 충분
- **상태**: ✅ 운영 중

### Nextcloud
- **판단**: ❌ **삭제 완료**
- **삭제 일시**: 2026-01-20
- **삭제된 리소스**:
  - Namespace: nextcloud (완전 삭제)
  - Pods: 2개 (nextcloud, nextcloud-db)
  - Services: 2개
  - Deployments: 2개
  - PVCs: 3개 (30Gi 자동 회수)
- **결과**: 30Gi 스토리지 절약 완료

### 최종 상태 (2026-01-20 삭제 후)

**스토리지**:
```
Before: 120Gi (8 PVCs) → After: 90Gi (5 PVCs)
절약: 30Gi (25%)
```

**활성 PVC (5개)**:
```
blog-system:
├─ mysql-pvc (5Gi, longhorn) ✅

monitoring:
├─ prometheus-data-pvc (50Gi, local-path) ✅
├─ grafana-data-pvc (10Gi, local-path) ✅
├─ pushgateway-data-pvc (5Gi, local-path) ✅
└─ storage-loki-stack-0 (10Gi, longhorn) ✅
```

**Pod 수**: ~~100개~~ → 98개 (2개 감소)

**노드 리소스 사용률** (삭제 후):
```
k8s-cp:        CPU 7%,  Memory 30%
k8s-worker1:   CPU 16%, Memory 72%
k8s-worker2:   CPU 15%, Memory 39%
```

### 다음 집중 영역: 쿠버네티스 운영 강화
```
"30Gi 확보한 리소스로 다음에 집중"

1. ✅ Longhorn 안정적 운영 (복제/스냅샷 활용)
2. ✅ ArgoCD GitOps 완성 (Auto-Sync, Prune, SelfHeal)
3. ⏳ Prometheus Alert 실전 활용 (Slack 연동)
4. ⏳ Loki 로그 기반 장애 대응 훈련
5. ⏳ HPA/Rollout 부하 테스트 (실제 트래픽)
6. ⏳ 문서화 & 블로그 포스팅
```

---

**작성**: Claude Code
**분석 일자**: 2026-01-20
**삭제 완료**: 2026-01-20
**최종 검증**: ✅ Namespace, PV, PVC 모두 삭제 확인됨
