---
title: "Redis Session 가이드 정리"
date: 2026-01-08
summary: "Spring Session + Redis 구현 문서 통합"
tags: ["Redis", "Session", "Spring Boot"]
---

## 오늘 배운 것

bespin-project의 세션 관련 문서를 정리했다.

### 핵심 내용

1. **JSON 직렬화** 사용 이유
   - `GenericJackson2JsonRedisSerializer`로 세션 데이터를 JSON으로 저장
   - redis-cli로 세션 내용 확인 가능 (디버깅 용이)

2. **쿠키와 세션 TTL 일치**
   - 세션 TTL: 30분
   - 쿠키 MaxAge: 30분
   - 불일치하면 로그인 유지 문제 발생

3. **세션 확인 명령어**
   ```bash
   kubectl exec -it redis-master-0 -n petclinic -- redis-cli KEYS "spring:session:*"
   ```

### 생성한 문서

- `docs/session/SESSION-COMPLETE-GUIDE.md` (18KB)
- `docs/session/README.md`
