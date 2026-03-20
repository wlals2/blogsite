# MCP Auto-Context 문서

> K8s/CI 변경사항 자동 추적 시스템 문서 인덱스

---

## 문서 목록

| 문서 | 설명 | 상태 |
|------|------|------|
| **[COMPLETE-MCP-GUIDE.md](./COMPLETE-MCP-GUIDE.md)** | 완전한 시스템 가이드 (설치, 사용법, 관리) | ✅ 최신 |
| [MCP-AUTO-TRACKING-DESIGN.md](./MCP-AUTO-TRACKING-DESIGN.md) | 초기 설계 문서 | 📜 아카이브 |

---

## 빠른 시작

### 1. 서비스 상태 확인

```bash
sudo systemctl status mcp-auto-context
```

### 2. 최근 변경사항 확인

```bash
cd /home/jimin/mcp-servers/auto-context
node test-db.js
```

### 3. Claude Code에서 자동 확인

Claude Code 시작 시 최근 24시간 변경사항이 자동으로 Context에 포함됩니다.

---

## 주요 경로

| 항목 | 경로 |
|------|------|
| **MCP Server 소스** | `/home/jimin/mcp-servers/auto-context/` |
| **Context DB** | `/home/jimin/mcp-servers/auto-context/data/context.db` |
| **systemd 서비스** | `/etc/systemd/system/mcp-auto-context.service` |
| **Claude Code 설정** | `~/.claude/config.json` |

---

## 자주 쓰는 명령어

```bash
# 서비스 재시작
sudo systemctl restart mcp-auto-context

# 로그 확인 (실시간)
sudo journalctl -u mcp-auto-context -f

# 미확인 변경사항 조회
cd /home/jimin/mcp-servers/auto-context && node check-unacknowledged.js
```

---

**최종 업데이트**: 2026-01-22
