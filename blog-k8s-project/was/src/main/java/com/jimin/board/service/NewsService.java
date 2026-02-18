package com.jimin.board.service;

import com.jimin.board.dto.NewsArticleResponse;
import com.jimin.board.entity.NewsArticle;
import com.jimin.board.repository.NewsArticleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 뉴스 조회 Service
 *
 * 역할: API 요청에 대한 뉴스 데이터 조회 (읽기 전용)
 * 수집은 NewsFetchService가 담당
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NewsService {

    private final NewsArticleRepository articleRepository;

    /**
     * 전체 뉴스 페이지네이션 조회
     */
    public Page<NewsArticleResponse> getAllNews(Pageable pageable) {
        return articleRepository.findAllByOrderByPublishedAtDesc(pageable)
                .map(NewsArticleResponse::from);
    }

    /**
     * 카테고리별 뉴스 조회 (security, ai)
     */
    public Page<NewsArticleResponse> getNewsByCategory(String category, Pageable pageable) {
        return articleRepository.findBySourceCategoryOrderByPublishedAtDesc(category, pageable)
                .map(NewsArticleResponse::from);
    }

    /**
     * AI 요약이 안 된 기사 목록 (Claude Code 연동용)
     */
    public List<NewsArticleResponse> getUnsummarized() {
        return articleRepository.findByAiSummaryIsNullOrderByPublishedAtDesc()
                .stream()
                .map(NewsArticleResponse::from)
                .toList();
    }

    /**
     * AI 요약 업데이트 (Claude Code가 호출)
     */
    @Transactional
    public void updateAiSummary(Long articleId, String summary, String tag) {
        NewsArticle article = articleRepository.findById(articleId)
                .orElseThrow(() -> new RuntimeException("기사를 찾을 수 없습니다. ID: " + articleId));
        article.setAiSummary(summary);
        article.setTag(tag);
        articleRepository.save(article);
    }

    /**
     * 특정 기간 뉴스 조회 (주간 요약용)
     */
    public List<NewsArticleResponse> getNewsBetween(LocalDateTime start, LocalDateTime end) {
        return articleRepository.findByPublishedAtBetweenOrderByPublishedAtDesc(start, end)
                .stream()
                .map(NewsArticleResponse::from)
                .toList();
    }
}
