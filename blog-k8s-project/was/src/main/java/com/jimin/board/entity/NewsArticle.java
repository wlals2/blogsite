package com.jimin.board.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * NewsArticle Entity - 수집된 뉴스 기사
 *
 * DB 테이블: news_articles
 * 역할: RSS에서 수집한 개별 기사를 저장
 * 관계: NewsArticle N:1 NewsSource (여러 기사가 하나의 소스에 속함)
 */
@Entity
@Table(name = "news_articles")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NewsArticle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Why: 어떤 소스에서 수집했는지 (외래키)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_id", nullable = false)
    private NewsSource source;

    @Column(nullable = false, length = 500)
    private String title;

    // Why: UNIQUE 제약으로 같은 기사 중복 저장 방지
    @Column(nullable = false, length = 1000, unique = true)
    private String link;

    // Why: 기사 원문의 발행일 (RSS에서 제공)
    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    // Why: RSS에서 제공하는 기사 요약 (원문 그대로 저장)
    @Column(columnDefinition = "TEXT")
    private String description;

    // Why: Claude Code가 나중에 채워넣는 AI 요약 (초기에는 null)
    @Column(name = "ai_summary", columnDefinition = "TEXT")
    private String aiSummary;

    // Why: CVE, 랜섬웨어, AI동향 등 세부 분류 (Claude Code가 채움)
    @Column(length = 50)
    private String tag;

    // Why: 수집된 시간 (RSS 발행일과 별개)
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
