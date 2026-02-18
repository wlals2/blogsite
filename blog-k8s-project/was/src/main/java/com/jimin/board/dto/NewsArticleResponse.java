package com.jimin.board.dto;

import com.jimin.board.entity.NewsArticle;

import java.time.LocalDateTime;

/**
 * 뉴스 기사 응답 DTO
 *
 * Entity를 직접 노출하지 않고 필요한 필드만 전달
 * from() 팩토리 메서드로 Entity → DTO 변환
 */
public record NewsArticleResponse(
        Long id,
        String title,
        String link,
        String sourceName,
        String sourceCategory,
        LocalDateTime publishedAt,
        String description,
        String aiSummary,
        String tag
) {
    public static NewsArticleResponse from(NewsArticle article) {
        return new NewsArticleResponse(
                article.getId(),
                article.getTitle(),
                article.getLink(),
                article.getSource().getName(),
                article.getSource().getCategory(),
                article.getPublishedAt(),
                article.getDescription(),
                article.getAiSummary(),
                article.getTag()
        );
    }
}
