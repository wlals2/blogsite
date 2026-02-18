package com.jimin.board.controller;

import com.jimin.board.dto.NewsArticleResponse;
import com.jimin.board.service.NewsFetchService;
import com.jimin.board.service.NewsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "News", description = "보안/AI 뉴스 자동 수집 API")
@RestController
@RequestMapping("/api/news")
@RequiredArgsConstructor
public class NewsController {

    private final NewsService newsService;
    private final NewsFetchService fetchService;

    @Operation(summary = "전체 뉴스 목록 (페이지네이션)")
    @GetMapping
    public ResponseEntity<Page<NewsArticleResponse>> getAllNews(
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(newsService.getAllNews(pageable));
    }

    @Operation(summary = "카테고리별 뉴스 조회 (security, ai)")
    @GetMapping("/category/{category}")
    public ResponseEntity<Page<NewsArticleResponse>> getByCategory(
            @PathVariable String category,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(newsService.getNewsByCategory(category, pageable));
    }

    @Operation(summary = "AI 미요약 기사 목록 (Claude Code 연동용)")
    @GetMapping("/unsummarized")
    public ResponseEntity<List<NewsArticleResponse>> getUnsummarized() {
        return ResponseEntity.ok(newsService.getUnsummarized());
    }

    @Operation(summary = "AI 요약 업데이트 (Claude Code가 호출)")
    @PatchMapping("/{id}/summary")
    public ResponseEntity<Void> updateSummary(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        newsService.updateAiSummary(id, body.get("aiSummary"), body.get("tag"));
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "수동 뉴스 수집 트리거")
    @PostMapping("/fetch")
    public ResponseEntity<Map<String, String>> triggerFetch() {
        fetchService.fetchAllSources();
        return ResponseEntity.ok(Map.of("status", "수집 완료"));
    }
}
