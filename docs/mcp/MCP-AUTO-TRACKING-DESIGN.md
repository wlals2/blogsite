# MCP 자동 변경 추적 시스템 설계

## 목표
Claude 세션 간 자동 변경사항 공유 및 실시간 Context 업데이트

---

## 시스템 아키텍처

```
┌─────────────────────────────────────────────────────────────┐
│                     Git Repository                          │
│  - k8s-manifests (ResourceQuota, Rollout, etc.)            │
│  - blogsite (CI/CD workflows, source code)                 │
└────────────────┬────────────────────────────────────────────┘
                 │
                 │ Git Hook (post-commit, post-receive)
                 ↓
┌─────────────────────────────────────────────────────────────┐
│              Change Detection Service                        │
│  - File Watcher (inotify, chokidar)                        │
│  - Git Hook Handler                                         │
│  - Change Analyzer (diff, impact analysis)                  │
└────────────────┬────────────────────────────────────────────┘
                 │
                 │ Store changes
                 ↓
┌─────────────────────────────────────────────────────────────┐
│              Context Database (SQLite/PostgreSQL)           │
│  Table: changes                                             │
│  - id, timestamp, file_path, change_type, impact           │
│  - related_resources, recommendations                       │
└────────────────┬────────────────────────────────────────────┘
                 │
                 │ Query on session start
                 ↓
┌─────────────────────────────────────────────────────────────┐
│              MCP Server (Proactive Context)                 │
│  - Auto-inject recent changes into Claude context          │
│  - No user prompt needed                                    │
└────────────────┬────────────────────────────────────────────┘
                 │
                 │ Automatic context injection
                 ↓
┌─────────────────────────────────────────────────────────────┐
│              Claude Code Sessions (All)                     │
│  - Session A, B, C automatically aware of changes          │
└─────────────────────────────────────────────────────────────┘
```

---

## 1. Git Hook 기반 자동 감지

### A. Post-commit Hook
```bash
# .git/hooks/post-commit
#!/bin/bash

# 1. 변경된 파일 감지
CHANGED_FILES=$(git diff-tree --no-commit-id --name-only -r HEAD)

# 2. K8s/CI/CD 파일만 필터링
K8S_FILES=$(echo "$CHANGED_FILES" | grep -E '\\.yaml$|\\.yml$|workflows/')

if [ -n "$K8S_FILES" ]; then
  # 3. MCP Context Service에 알림
  curl -X POST http://localhost:3000/api/changes \
    -H "Content-Type: application/json" \
    -d "{
      \"repo\": \"$(pwd)\",
      \"commit\": \"$(git rev-parse HEAD)\",
      \"files\": $(echo "$K8S_FILES" | jq -R -s -c 'split("\n")[:-1]'),
      \"timestamp\": \"$(date -Iseconds)\"
    }"
fi
```

### B. File Watcher (실시간)
```typescript
// mcp-server/file-watcher.ts
import chokidar from 'chokidar';
import { ContextDB } from './context-db';

const watcher = chokidar.watch([
  '/home/jimin/k8s-manifests/**/*.yaml',
  '/home/jimin/blogsite/.github/workflows/*.yml'
], {
  persistent: true,
  ignoreInitial: true
});

watcher.on('change', async (path) => {
  console.log(`🔔 File changed: ${path}`);

  // 1. Git diff 분석
  const diff = await analyzeChange(path);

  // 2. 영향도 분석
  const impact = await analyzeImpact(diff);

  // 3. Context DB에 저장
  await ContextDB.insert({
    timestamp: new Date(),
    file_path: path,
    change_type: diff.type,
    impact: impact,
    recommendations: generateRecommendations(impact)
  });

  // 4. 모든 활성 Claude 세션에 알림 (WebSocket)
  notifyAllSessions({
    type: 'file_changed',
    path: path,
    impact: impact
  });
});
```

---

## 2. Context Database 스키마

```sql
-- Context 저장소
CREATE TABLE changes (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,

  -- 변경 정보
  repo VARCHAR(255),
  file_path VARCHAR(255),
  commit_hash VARCHAR(40),
  change_type VARCHAR(50),  -- 'modify', 'add', 'delete'

  -- 분석 결과
  impact_json TEXT,  -- JSON: { affected_resources: [...], severity: 'high' }
  recommendations_json TEXT,  -- JSON: [...]

  -- 상태
  acknowledged BOOLEAN DEFAULT FALSE,
  relevant_until DATETIME  -- 이 시간 이후 context에서 제외
);

-- 빠른 조회를 위한 인덱스
CREATE INDEX idx_timestamp ON changes(timestamp DESC);
CREATE INDEX idx_acknowledged ON changes(acknowledged);
```

---

## 3. MCP Server - Automatic Context Injection

### A. Resources (자동 Context 제공)

```typescript
// mcp-server/auto-context.ts
import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { ContextDB } from './context-db';

const server = new Server({
  name: "auto-k8s-context",
  version: "1.0.0",
}, {
  capabilities: {
    resources: {},  // ← 핵심: Claude가 자동으로 읽을 수 있는 리소스
  },
});

// Resources 등록 - Claude가 세션 시작 시 자동 조회
server.setRequestHandler("resources/list", async () => {
  // 최근 24시간 변경사항 자동 제공
  const recentChanges = await ContextDB.getRecent(24 * 60 * 60 * 1000);

  return {
    resources: [
      {
        uri: "context://recent-changes",
        name: "Recent K8s/CI Changes (Auto)",
        description: "Automatically tracked changes in last 24h",
        mimeType: "application/json"
      }
    ]
  };
});

server.setRequestHandler("resources/read", async (request) => {
  if (request.params.uri === "context://recent-changes") {
    const changes = await ContextDB.getRecent(24 * 60 * 60 * 1000);

    const summary = `
## 🔔 최근 K8s/CI/CD 변경사항 (자동 감지)

${changes.map(c => `
### ${new Date(c.timestamp).toLocaleString()}
- **파일**: ${c.file_path}
- **변경**: ${c.change_type}
- **영향**: ${c.impact_json.affected_resources.join(', ')}
- **권장사항**: ${c.recommendations_json.join(', ')}
`).join('\n')}
`;

    return {
      contents: [{
        uri: request.params.uri,
        mimeType: "text/markdown",
        text: summary
      }]
    };
  }
});
```

### B. Claude가 자동으로 Context 읽기

```json
// Claude Code 설정
{
  "mcpServers": {
    "auto-k8s-context": {
      "command": "node",
      "args": ["/home/jimin/mcp-servers/auto-context/build/index.js"],
      "autoLoad": true  // ← 세션 시작 시 자동 로드
    }
  }
}
```

**동작 방식**:
```
1. User가 Claude Code 실행
   ↓
2. MCP Server 자동 연결
   ↓
3. resources/list 자동 호출
   ↓
4. resources/read("context://recent-changes") 자동 호출
   ↓
5. Claude의 내부 Context에 자동 추가
   ↓
6. User: "WEB 배포해줘"
   ↓
7. Claude (내부적으로 recent-changes 인지):
   "⚠️ 주의: 최근 ResourceQuota가 20 cores로 증가했습니다.
   현재 WEB 배포 가능합니다."
```

---

## 4. 실시간 알림 (선택사항)

### WebSocket 기반 실시간 알림

```typescript
// mcp-server/websocket-notifier.ts
import WebSocket from 'ws';

const wss = new WebSocket.Server({ port: 8080 });
const activeSessions = new Set<WebSocket>();

wss.on('connection', (ws) => {
  activeSessions.add(ws);

  ws.on('close', () => {
    activeSessions.delete(ws);
  });
});

// 파일 변경 시 모든 세션에 알림
export function notifyAllSessions(change: Change) {
  const message = JSON.stringify({
    type: 'change_detected',
    data: change
  });

  activeSessions.forEach(ws => {
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(message);
    }
  });
}
```

---

## 5. 비교: 수동 vs 자동

| 항목 | 수동 (기존 MCP) | **자동 (새 시스템)** |
|------|----------------|---------------------|
| **트리거** | User 프롬프트 필요 | Git commit/File change 자동 |
| **Context 공유** | 같은 세션만 | **모든 세션 자동 공유** ✅ |
| **실시간성** | 요청 시점만 | **파일 변경 즉시** ✅ |
| **범위** | 특정 케이스만 | **모든 K8s/CI 변경** ✅ |
| **사용자 경험** | "확인해줘" 필요 | **자동으로 알아서 알려줌** ✅ |

---

## 6. 구현 로드맵

### Phase 1: 기본 자동 감지 (1-2일)
- [x] Git post-commit hook 설치
- [ ] File watcher (chokidar) 구현
- [ ] SQLite Context DB 설정
- [ ] 기본 변경 감지 및 저장

### Phase 2: MCP Resources 자동 제공 (1일)
- [ ] MCP Server resources/list 구현
- [ ] resources/read로 최근 변경사항 제공
- [ ] Claude Code 설정 (autoLoad)

### Phase 3: 영향도 분석 (2-3일)
- [ ] ResourceQuota 변경 → Rollout 영향 분석
- [ ] Workflow 변경 → 배포 영향 분석
- [ ] Rollout 변경 → LimitRange 호환성 검증

### Phase 4: 실시간 알림 (선택, 1-2일)
- [ ] WebSocket Server 구현
- [ ] Claude Code 확장으로 알림 표시

---

## 7. 예상 동작 시나리오

### Scenario 1: ResourceQuota 변경

```
[15:30] Jimin: ResourceQuota를 20 cores로 증가
         ↓ (Git commit)
[15:30] System: 변경 감지 → Context DB 저장
         ↓
[15:35] Jimin (새 Claude 세션): "WEB 배포 상태 확인해줘"
         ↓
Claude (자동): "최근 변경사항을 확인했습니다:
  • 15:30 - ResourceQuota: 15 → 20 cores
  • 영향: WEB/WAS Rollout 모두 배포 가능

  현재 WEB Rollout 상태: Progressing (Canary 배포 중)"
```

### Scenario 2: CI/CD Workflow 수정

```
[10:00] Jimin: deploy-was.yml에 stateless clone 추가
         ↓ (Git push)
[10:00] System: 변경 감지 및 분석
         - 변경: WAS 소스 복사 제거
         - 영향: 다음 배포부터 Git checkout 사용
         ↓
[11:00] Jimin (다른 세션): "WAS 배포해줘"
         ↓
Claude (자동): "⚠️ 주의: deploy-was.yml이 최근 업데이트되었습니다.
  • WAS 소스가 이제 Git에서 자동 checkout됨
  • 로컬 복사 스텝 제거됨

  새로운 Stateless 방식으로 배포를 진행하시겠습니까?"
```

---

## 다음 단계

실제로 구현해보시겠습니까?
- [ ] Phase 1부터 시작 (Git hook + File watcher)
- [ ] 또는 전체 설계 리뷰 후 수정
