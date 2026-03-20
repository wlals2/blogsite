# MCP Auto-Context 완전 가이드

> K8s/CI 변경사항 자동 추적 및 Claude Code 통합 시스템
>
> **프로젝트 목표**: Claude 세션 간 자동 변경사항 공유 및 실시간 Context 업데이트

**최종 업데이트:** 2026-01-22
**문서 버전:** 1.0
**시스템 상태:** ✅ 운영 중

---

## 목차

1. [프로젝트 개요](#1-프로젝트-개요)
2. [시스템 아키텍처](#2-시스템-아키텍처)
3. [구성 요소 상세](#3-구성-요소-상세)
4. [설치 및 설정](#4-설치-및-설정)
5. [사용 방법](#5-사용-방법)
6. [systemd 서비스 관리](#6-systemd-서비스-관리)
7. [감시 범위 및 확장](#7-감시-범위-및-확장)
8. [트러블슈팅](#8-트러블슈팅)
9. [현재 제약사항](#9-현재-제약사항)

---

## 1. 프로젝트 개요

### 무엇을 만들었는가?

Git 저장소의 K8s manifest 및 CI/CD 워크플로우 변경을 자동으로 감지하여 Claude Code 세션에 Context로 제공하는 시스템입니다.

**주요 특징:**
- ✅ 파일 변경 실시간 감지 (chokidar)
- ✅ Git commit 후 자동 기록 (post-commit hook)
- ✅ Context DB에 변경사항 영구 저장 (SQLite)
- ✅ Claude Code 세션 시작 시 자동 Context 주입
- ✅ 영향도 분석 (ResourceQuota, Rollout, Workflow)
- ✅ systemd 서비스로 백그라운드 상시 실행

### 시스템 규모

| 항목 | 수치 |
|------|------|
| **감시 경로** | 2개 (k8s-manifests, workflows) |
| **감시 리소스 타입** | 3개 (ResourceQuota, Rollout, Workflow) |
| **Context DB** | SQLite (~100KB) |
| **MCP Server 포트** | stdio (Claude Code 전용) |
| **File Watcher** | systemd 백그라운드 서비스 |

### 왜 이렇게 구축했는가?

**선택한 아키텍처: File Watcher + MCP Server 분리**

| 구성요소 | 대안 | 선택 이유 |
|----------|------|----------|
| **File Watcher** | K8s Watch API | Git이 Single Source of Truth (GitOps) |
| **Context DB** | Redis, PostgreSQL | SQLite 충분, 단일 노드 환경 |
| **MCP 통신** | HTTP, WebSocket | Claude Code가 stdio만 지원 |
| **서비스 실행** | Docker, 수동 | systemd가 Linux 표준, 자동 재시작 |

---

## 2. 시스템 아키텍처

### 전체 아키텍처

```
┌─────────────────────────────────────────────────────────────┐
│ File Watcher Daemon (systemd로 상시 실행)                    │
│ - 파일 변경 감지 (chokidar)                                  │
│ - Git commit 감지 (post-commit hook)                        │
│ - Context DB 업데이트 (SQLite)                              │
│ - 영향도 분석 (ImpactAnalyzer)                              │
└──────────────┬──────────────────────────────────────────────┘
               │ writes to
               ↓
┌─────────────────────────────────────────────────────────────┐
│ Context Database (SQLite)                                    │
│ 경로: /home/jimin/mcp-servers/auto-context/data/context.db  │
│ - 변경사항 저장 (timestamp, file_path, impact)              │
│ - 확인 상태 관리 (acknowledged)                             │
└──────────────┬──────────────────────────────────────────────┘
               │ reads from
               ↓
┌─────────────────────────────────────────────────────────────┐
│ MCP Server (Claude Code가 요청할 때만 실행)                  │
│ - Resources 제공 (context://recent-changes)                 │
│ - Tools 제공 (acknowledge_change, query_changes)            │
│ - stdio transport (Claude Code와 통신)                      │
└──────────────┬──────────────────────────────────────────────┘
               │ stdio
               ↓
┌─────────────────────────────────────────────────────────────┐
│ Claude Code Session                                          │
│ - 자동으로 MCP Server 시작                                   │
│ - Resources 읽기 (최근 24시간 변경사항)                      │
│ - 최근 변경사항 Context에 자동 포함                          │
└─────────────────────────────────────────────────────────────┘
```

### 데이터 플로우

```
1. 파일 수정 또는 git commit
   ↓
2. File Watcher Daemon 감지
   ↓
3. 영향도 분석 (ImpactAnalyzer)
   - ResourceQuota 변경 → 실행 중인 Rollout과 비교
   - Rollout 변경 → LimitRange 호환성 검증
   - Workflow 변경 → 영향받는 App 식별
   ↓
4. Context DB에 저장 (SQLite)
   ↓
5. Claude Code 세션 시작
   ↓
6. MCP Server 자동 시작 (stdio)
   ↓
7. resources/read("context://recent-changes")
   ↓
8. Claude가 최근 변경사항 인지 상태로 대화
```

---

## 3. 구성 요소 상세

### 3.1 File Watcher Daemon

**역할**: 파일 변경 실시간 감지 및 Context DB 업데이트

**파일 위치**: `/home/jimin/mcp-servers/auto-context/src/watcher-daemon.ts`

**감시 경로**:
```typescript
const watchPaths = [
  '/home/jimin/k8s-manifests/blog-system',
  '/home/jimin/blogsite/.github/workflows'
];
```

**기능**:
- chokidar로 파일 변경 감지 (add, change, unlink)
- Git commit hash 자동 추출
- ImpactAnalyzer로 영향도 분석
- Context DB에 변경사항 저장

### 3.2 Context Database

**역할**: 변경사항 영구 저장

**파일 위치**: `/home/jimin/mcp-servers/auto-context/data/context.db`

**스키마**:
```sql
CREATE TABLE changes (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,
  repo VARCHAR(255) NOT NULL,
  file_path VARCHAR(255) NOT NULL,
  commit_hash VARCHAR(40),
  change_type VARCHAR(50) NOT NULL,  -- 'modify', 'add', 'delete'
  impact_json TEXT NOT NULL,          -- JSON: { affected_resources, severity, category }
  recommendations_json TEXT NOT NULL,
  acknowledged BOOLEAN DEFAULT FALSE,
  relevant_until DATETIME
);
```

### 3.3 Impact Analyzer

**역할**: 변경사항의 영향도 분석

**파일 위치**: `/home/jimin/mcp-servers/auto-context/src/impact-analyzer.ts`

**분석 기능**:

| 리소스 타입 | 분석 내용 | 중요도 |
|-------------|----------|--------|
| **ResourceQuota** | 실행 중인 Rollout과 비교, 한도 초과 여부 | Critical |
| **Rollout** | LimitRange 호환성 검증, Pod 리소스 요청량 | High |
| **Workflow** | 영향받는 앱 식별 (WAS, WEB, Hugo) | High |

### 3.4 MCP Server

**역할**: Claude Code에 Context 제공

**파일 위치**: `/home/jimin/mcp-servers/auto-context/src/index.ts`

**제공 기능**:

| 타입 | 이름 | 설명 |
|------|------|------|
| **Resource** | `context://recent-changes` | 최근 24시간 변경사항 |
| **Resource** | `context://critical-changes` | 미확인 critical 변경사항 |
| **Tool** | `acknowledge_change` | 변경사항 확인 처리 |
| **Tool** | `query_changes` | 특정 파일 관련 변경사항 조회 |

---

## 4. 설치 및 설정

### 4.1 MCP Server 빌드

```bash
cd /home/jimin/mcp-servers/auto-context
npm install
npm run build
```

### 4.2 Claude Code 설정

**파일**: `~/.claude/config.json`

```json
{
  "mcpServers": {
    "auto-k8s-context": {
      "command": "node",
      "args": ["/home/jimin/mcp-servers/auto-context/build/index.js"],
      "env": {}
    }
  }
}
```

### 4.3 Git Hook 설치

**k8s-manifests**:
```bash
cat > /home/jimin/k8s-manifests/.git/hooks/post-commit << 'EOF'
#!/bin/bash
echo "🔔 K8s/CI 파일 변경 감지 (post-commit hook)"
echo "   Commit: $(git rev-parse HEAD)"
echo "   Message: $(git log -1 --pretty=%B)"
echo "   Files:"
git diff-tree --no-commit-id --name-only -r HEAD | sed 's/^/     - /'
echo ""
echo "✅ File Watcher가 변경사항을 자동 처리합니다."
EOF
chmod +x /home/jimin/k8s-manifests/.git/hooks/post-commit
```

### 4.4 systemd 서비스 설치

```bash
# 1. 서비스 파일 복사
sudo cp /home/jimin/mcp-servers/auto-context/mcp-auto-context.service \
  /etc/systemd/system/

# 2. daemon 리로드
sudo systemctl daemon-reload

# 3. 서비스 활성화 및 시작
sudo systemctl enable mcp-auto-context
sudo systemctl start mcp-auto-context

# 4. 상태 확인
sudo systemctl status mcp-auto-context
```

---

## 5. 사용 방법

### 5.1 자동 확인 (기본)

Claude Code 시작 시 자동으로 최근 변경사항이 로드됩니다.

**동작 원리**:
1. Claude Code 실행
2. MCP Server 자동 시작 (stdio)
3. `resources/list` 호출
4. `resources/read("context://recent-changes")` 호출
5. 최근 24시간 변경사항이 Claude의 context에 자동 포함

### 5.2 명시적으로 확인

```
사용자: "최근 K8s 변경사항 보여줘"
사용자: "ResourceQuota 관련 변경사항 조회"
사용자: "중요한 미확인 변경사항 있어?"
```

### 5.3 Context DB 직접 조회

```bash
# 전체 변경사항 확인
cd /home/jimin/mcp-servers/auto-context
node test-db.js

# 미확인 변경사항만 확인
node check-unacknowledged.js
```

---

## 6. systemd 서비스 관리

### 주요 명령어

```bash
# 상태 확인
sudo systemctl status mcp-auto-context

# 시작/중지/재시작
sudo systemctl start mcp-auto-context
sudo systemctl stop mcp-auto-context
sudo systemctl restart mcp-auto-context

# 로그 확인 (실시간)
sudo journalctl -u mcp-auto-context -f

# 최근 50줄
sudo journalctl -u mcp-auto-context -n 50
```

### 서비스 파일 내용

**위치**: `/etc/systemd/system/mcp-auto-context.service`

```ini
[Unit]
Description=MCP Auto-Context File Watcher Daemon
After=network.target

[Service]
Type=simple
User=jimin
WorkingDirectory=/home/jimin/mcp-servers/auto-context
ExecStart=/usr/bin/node /home/jimin/mcp-servers/auto-context/build/watcher-daemon.js
Restart=always
RestartSec=10
StandardOutput=journal
StandardError=journal
Environment="NODE_ENV=production"

[Install]
WantedBy=multi-user.target
```

### Heartbeat 확인

서비스는 60초마다 heartbeat 로그를 남깁니다:

```bash
sudo journalctl -u mcp-auto-context -f | grep "File Watcher alive"
```

---

## 7. 감시 범위 및 확장

### 현재 감시 중인 리소스

| 리소스 | 파일 패턴 | 중요도 | 영향 분석 |
|--------|----------|--------|----------|
| **ResourceQuota & LimitRange** | `resource-limits.yaml` | Critical | 실행 중인 Rollout과 비교 |
| **Rollout** | `*-rollout.yaml` | High | LimitRange 호환성 검증 |
| **Workflow** | `.github/workflows/*.yml` | High | 영향받는 App 식별 |

### 감시 경로

```
/home/jimin/k8s-manifests/blog-system/
  ├── resource-limits.yaml  ✅
  ├── was-rollout.yaml      ✅
  ├── web-rollout.yaml      ✅
  └── *.yaml                ✅ (기타 YAML)

/home/jimin/blogsite/.github/workflows/
  ├── deploy-web.yml        ✅
  ├── deploy-was.yml        ✅
  └── *.yml                 ✅
```

### 감시하지 않는 것

❌ **Git에 없는 K8s 리소스**
  - `kubectl apply -f <(echo "...")`로 직접 생성한 리소스
  - 클러스터 내부에서만 존재하는 ConfigMap/Secret

❌ **클러스터 내 직접 변경**
  - `kubectl edit deployment web`
  - `kubectl scale --replicas=5`
  - ArgoCD selfHeal로 Git 상태로 되돌려짐

### 확장 방법

**더 많은 리소스 감시** (`src/file-watcher.ts` 수정):

```typescript
const watchPaths = [
  '/home/jimin/k8s-manifests/blog-system',
  '/home/jimin/k8s-manifests/monitoring',    // 추가
  '/home/jimin/k8s-manifests/istio-system',  // 추가
  '/home/jimin/blogsite/.github/workflows'
];
```

---

## 8. 트러블슈팅

### File Watcher가 변경을 감지하지 못할 때

```bash
# 1. Watcher Daemon 실행 중인지 확인
sudo systemctl status mcp-auto-context

# 2. 로그 확인
sudo journalctl -u mcp-auto-context -n 50

# 3. 수동 재시작
sudo systemctl restart mcp-auto-context
```

### Claude Code에서 변경사항이 안 보일 때

```bash
# 1. Context DB 확인
cd /home/jimin/mcp-servers/auto-context
node test-db.js

# 2. MCP Server 설정 확인
cat ~/.claude/config.json | grep auto-k8s-context

# 3. Claude Code 재시작
```

### Context DB 초기화

```bash
# 주의: 모든 변경 이력 삭제됨
rm /home/jimin/mcp-servers/auto-context/data/context.db

# Watcher Daemon 재시작
sudo systemctl restart mcp-auto-context
```

### Context DB 정리 (오래된 데이터)

```bash
# 30일 이전 데이터 삭제
sqlite3 /home/jimin/mcp-servers/auto-context/data/context.db \
  "DELETE FROM changes WHERE timestamp < datetime('now', '-30 days')"

# DB 최적화
sqlite3 /home/jimin/mcp-servers/auto-context/data/context.db "VACUUM"
```

---

## 9. 현재 제약사항

| 제약사항 | 현재 상태 | 해결 방법 (선택사항) |
|----------|----------|---------------------|
| Git에 없는 리소스 감지 불가 | ✅ 설계 의도 (GitOps) | K8s Watch API 추가 |
| ConfigMap, Secret 변경 감지 안됨 | 🔶 부분적 | 파일로 관리하거나 Watch 추가 |
| kubectl edit 감지 안됨 | ✅ 설계 의도 (ArgoCD selfHeal) | 클러스터 감시 추가 (비권장) |
| 3개 리소스 타입만 분석 | 🔶 충분함 | 필요시 ImpactAnalyzer 확장 |

### 설계 원칙

**현재 시스템은 GitOps 환경에 최적화됨**:
- Git = Single Source of Truth
- 모든 변경은 Git을 통해 이루어짐
- ArgoCD가 Git → 클러스터 동기화
- File Watcher가 Git 변경 감지 → Context DB 업데이트
- Claude Code가 Context DB 읽어서 최신 상태 인지

---

## 파일 구조

```
/home/jimin/mcp-servers/auto-context/
├── src/
│   ├── index.ts              # MCP Server (Claude Code 통신)
│   ├── watcher-daemon.ts     # File Watcher Daemon (systemd)
│   ├── file-watcher.ts       # 파일 감시 로직
│   ├── context-db.ts         # SQLite 데이터베이스
│   └── impact-analyzer.ts    # 영향도 분석
├── build/                    # 컴파일된 JS 파일
├── data/
│   └── context.db            # SQLite 데이터베이스
├── docs/
│   └── ARCHITECTURE.md       # 아키텍처 문서
├── mcp-auto-context.service  # systemd 서비스 파일
├── test-db.js                # DB 테스트 스크립트
├── check-unacknowledged.js   # 미확인 변경사항 조회
├── package.json
├── tsconfig.json
├── README.md
├── USAGE.md
└── SYSTEMD.md
```

---

## 요약

| 항목 | 상태 | 비고 |
|------|------|------|
| **File Watcher Daemon** | 🟢 항상 실행 | systemd 서비스 |
| **MCP Server** | 🟡 필요시만 실행 | Claude Code가 자동 시작 |
| **Context DB** | 🟢 자동 업데이트 | Watcher Daemon이 관리 |
| **Claude Code 통합** | 🟢 자동 | `~/.claude/config.json` 설정됨 |
| **Git Hook** | 🟢 설치됨 | post-commit |
| **감시 범위** | 🟡 제한적 | ResourceQuota, Rollout, Workflow |
| **클러스터 감시** | ❌ 미구현 | Git 기반만 지원 (설계 의도) |

---

**작성일**: 2026-01-22
**작성자**: Claude
**다음 단계**: 필요시 감시 범위 확장 (monitoring, istio-system namespace)
