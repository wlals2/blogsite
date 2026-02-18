package com.jimin.board.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * NewsSource Entity - RSS 피드 소스 관리
 *
 * DB 테이블: news_sources
 * 역할: "어떤 사이트에서 뉴스를 수집할 것인가?"를 관리
 * 관계: NewsSource 1:N NewsArticle (하나의 소스에서 여러 기사 수집)
 */
@Entity
@Table(name = "news_sources")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NewsSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Why: RSS 소스를 식별하는 이름 (예: "The Hacker News", "BleepingComputer")
    @Column(nullable = false, length = 100)
    private String name;

    // Why: 실제 RSS 피드 URL (WAS가 이 URL을 주기적으로 읽음)
    @Column(name = "feed_url", nullable = false, length = 500, unique = true)
    private String feedUrl;

    // Why: 보안/AI 등 대분류 (뉴스 목록에서 필터링용)
    @Column(nullable = false, length = 50)
    private String category;

    // Why: false로 설정하면 수집 중단 (코드 수정 없이 DB에서 관리)
    @Column(nullable = false)
    private boolean active = true;

    // Why: 마지막 수집 시간 기록 → 다음 수집 시 이 시간 이후 기사만 가져옴
    @Column(name = "last_fetched_at")
    private LocalDateTime lastFetchedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
