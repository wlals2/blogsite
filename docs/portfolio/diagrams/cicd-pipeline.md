# Slide 9: CI/CD Pipeline (GitOps)

> **GitHub Actions → ArgoCD → Canary Deployment**

---

## Mermaid Diagram

```mermaid
flowchart TD
    Dev[👨‍💻 Developer<br/>git push main]

    Actions[⚙️ GitHub Actions<br/>Hugo Build<br/>Docker Build]

    GHCR[📦 GHCR<br/>ghcr.io/wlals2/board-web:v60]

    GitOps[📝 GitOps Repo<br/>k8s-manifests<br/>image: v60]

    ArgoCD[🔄 ArgoCD<br/>Auto-Sync (3초)<br/>Self-Healing]

    Rollout[🎯 Argo Rollouts<br/>Canary 10%→50%→90%→100%]

    Stable[✅ Stable Pods<br/>v59<br/>90% 트래픽]

    Canary[🆕 Canary Pods<br/>v60<br/>10% 트래픽]

    Cache[☁️ Cloudflare<br/>Cache Purge]

    Email[📧 Email<br/>배포 완료]

    Dev -->|1. Push| Actions
    Actions -->|2. Build| GHCR
    Actions -->|3. Update| GitOps
    GitOps -->|4. Sync| ArgoCD
    ArgoCD -->|5. Apply| Rollout
    Rollout -->|6. Create| Canary
    Rollout -->|7. Keep| Stable
    Actions -->|8. Purge| Cache
    Actions -->|9. Notify| Email

    style Dev fill:#e1f5ff
    style Actions fill:#ffd700
    style GHCR fill:#90ee90
    style GitOps fill:#ffb6c1
    style ArgoCD fill:#dda0dd
    style Rollout fill:#87ceeb
    style Stable fill:#d3d3d3
    style Canary fill:#ffa07a
    style Cache fill:#ffd700
    style Email fill:#e1f5ff
```

---

## 배포 플로우 (9-Step)

| Step | 단계 | 시간 | 설명 |
|------|------|------|------|
| **1** | git push | 0초 | Developer 커밋 |
| **2** | GitHub Actions | 50초 | Hugo + Docker Build |
| **3** | GHCR Push | 20초 | Private Registry 업로드 |
| **4** | GitOps Update | 10초 | image tag 변경 (v60) |
| **5** | ArgoCD Sync | 3초 | 자동 동기화 |
| **6** | Canary 배포 | 180초 | 10% → 50% → 90% → 100% |
| **7** | Cache Purge | 5초 | Cloudflare 캐시 삭제 |
| **8** | Email | 5초 | 배포 완료 알림 |
| **합계** | | **~2분** | (Canary 자동 진행 제외) |

---

## Canary 배포 상세

### 단계별 트래픽 분산

```
Step 1: 10% Canary
├─ Stable v59: 90%
├─ Canary v60: 10%
└─ Wait: 60초

Step 2: 50% Canary
├─ Stable v59: 50%
├─ Canary v60: 50%
└─ Wait: 60초

Step 3: 90% Canary
├─ Stable v59: 10%
├─ Canary v60: 90%
└─ Wait: 60초

Step 4: Promote to Stable
├─ Stable v60: 100%
└─ v59 Pod 종료
```

---

## GitOps 4원칙

### 1. Git = Single Source of Truth
```yaml
# ✅ 올바른 방법
git commit -m "scale: replicas 2 → 3"
git push

# ❌ 금지된 방법
kubectl scale deployment web --replicas=3
```

### 2. Declarative
- 명령형 (❌): `kubectl create`, `kubectl edit`
- 선언형 (✅): YAML 파일 + `git push`

### 3. Automated
- ArgoCD Auto-Sync (3초 Polling)
- Self-Healing (수동 변경 → 자동 복구)

### 4. Continuous Reconciliation
- Desired State (Git) vs Current State (Cluster)
- Diff 발견 → 자동 동기화

---

## Rollback 시나리오

### Scenario 1: Canary 단계에서 오류 발견
```bash
# ArgoCD에서 자동 중단
kubectl argo rollouts abort web -n blog-system

# 이전 버전으로 롤백
kubectl argo rollouts undo web -n blog-system
```
**소요 시간**: ~30초
**사용자 영향**: 10% (Canary 트래픽만)

### Scenario 2: Git Revert
```bash
cd ~/k8s-manifests
git revert HEAD
git push
# ArgoCD 자동 동기화 → v59로 복구
```
**소요 시간**: ~1분

---

## 성공 사례

### Canary로 장애 방지 (v45 → v46)
```
v46 배포 시작
├─ Canary 10% 단계
├─ WAS API 500 에러 증가 발견
├─ 자동 중단 (Abort)
└─ 90% 사용자 무영향 ✅
```

**교훈**:
- ✅ Canary 배포 = 안전장치
- ✅ 메트릭 기반 자동 판단 가능
- ✅ Rollback 30초 (빠른 복구)

---

## 핵심 포인트

### 1. 완전 자동화
- Developer는 `git push`만
- Build, Test, Deploy, Cache Purge, Notify 자동

### 2. 안전한 배포
- Canary 10% → 90% 사용자 보호
- Health Check → 자동 Rollback
- Git Revert → 1분 복구

### 3. Self-Healing
- kubectl 수동 변경 → 3초 후 Git 상태로 복구
- 일관성 보장 (Git = SSOT)

---

**핵심 메시지**: **GitOps + Canary = 안전하고 빠른 배포** (2분, 무중단)
