# Storage 관리 문서

> Kubernetes 스토리지 분석 및 최적화

---

## 📚 문서 목록

| 문서 | 설명 | 작성일 |
|------|------|--------|
| **[STORAGE-ANALYSIS.md](./STORAGE-ANALYSIS.md)** | Longhorn & Nextcloud 분석 및 최적화 | 2026-01-20 |

---

## 📊 요약

### 스토리지 최적화 (2026-01-20)

**작업 내용**:
- Longhorn 사용 현황 분석
- Nextcloud 삭제 (30Gi 절약)
- PVC 정리 (8개 → 5개)

**결과**:
```
Before: 120Gi (8 PVCs, 100 Pods)
After:   90Gi (5 PVCs,  98 Pods)
절약:    30Gi (25% 감소)
```

### 남은 PVC (5개)

```
blog-system:
└─ mysql-pvc (5Gi, longhorn)

monitoring:
├─ prometheus-data-pvc (50Gi, local-path)
├─ grafana-data-pvc (10Gi, local-path)
├─ pushgateway-data-pvc (5Gi, local-path)
└─ storage-loki-stack-0 (10Gi, longhorn)
```

### Longhorn 사용

**현재 사용량**: 15Gi (2개 PVC)
- blog-system MySQL: 5Gi
- monitoring Loki: 10Gi

**가치**:
- 📦 데이터 복제 (고가용성)
- 🔄 스냅샷 (백업/복구)
- 📈 동적 볼륨 확장

---

## 다음 작업

- ⏳ Longhorn 스냅샷 정책 설정
- ⏳ 스토리지 모니터링 대시보드 추가
- ⏳ PVC 용량 최적화 (필요 시)

---

**작성**: Claude Code
**최종 업데이트**: 2026-01-20
