package com.jimin.board.repository;

import com.jimin.board.entity.NewsSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NewsSourceRepository extends JpaRepository<NewsSource, Long> {

    // Why: 활성화된 소스만 수집 대상 (active=false인 소스는 건너뜀)
    List<NewsSource> findByActiveTrue();

    // Why: 카테고리별 소스 조회 (보안, AI 등)
    List<NewsSource> findByCategoryAndActiveTrue(String category);

    boolean existsByFeedUrl(String feedUrl);
}
