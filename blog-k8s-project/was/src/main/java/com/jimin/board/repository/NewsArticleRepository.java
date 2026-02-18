package com.jimin.board.repository;

import com.jimin.board.entity.NewsArticle;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface NewsArticleRepository extends JpaRepository<NewsArticle, Long> {

    // Why: 같은 URL의 기사가 이미 존재하는지 중복 체크
    boolean existsByLink(String link);

    // Why: 최신 기사부터 페이지네이션 조회 (API용)
    Page<NewsArticle> findAllByOrderByPublishedAtDesc(Pageable pageable);

    // Why: 소스 카테고리별 필터링 (보안 뉴스만, AI 뉴스만)
    Page<NewsArticle> findBySourceCategoryOrderByPublishedAtDesc(String category, Pageable pageable);

    // Why: Claude Code가 아직 요약하지 않은 기사 목록
    List<NewsArticle> findByAiSummaryIsNullOrderByPublishedAtDesc();

    // Why: 특정 기간의 기사 조회 (주간 요약용)
    List<NewsArticle> findByPublishedAtBetweenOrderByPublishedAtDesc(
            LocalDateTime start, LocalDateTime end);
}
