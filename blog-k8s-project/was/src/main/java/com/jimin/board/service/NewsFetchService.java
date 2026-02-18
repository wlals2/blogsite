package com.jimin.board.service;

import com.jimin.board.entity.NewsArticle;
import com.jimin.board.entity.NewsSource;
import com.jimin.board.repository.NewsArticleRepository;
import com.jimin.board.repository.NewsSourceRepository;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

/**
 * RSS 뉴스 수집 Service
 *
 * 동작 방식:
 * 1. DB에서 active=true인 NewsSource 목록 조회
 * 2. 각 소스의 feedUrl(RSS)을 읽어서 기사 목록 파싱
 * 3. 이미 저장된 기사(link 중복)는 건너뜀
 * 4. 새 기사만 MySQL에 저장
 * 5. 매시간 자동 실행 (@Scheduled)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NewsFetchService {

    private final NewsSourceRepository sourceRepository;
    private final NewsArticleRepository articleRepository;

    /**
     * 모든 활성 소스에서 뉴스 수집
     * Why: 매시간(cron) 자동 실행하여 최신 뉴스를 계속 수집
     */
    @Scheduled(cron = "0 0 * * * *")  // 매시간 정각
    public void fetchAllSources() {
        List<NewsSource> sources = sourceRepository.findByActiveTrue();
        log.info("뉴스 수집 시작: {} 개 소스", sources.size());

        int totalNew = 0;
        for (NewsSource source : sources) {
            int count = fetchFromSource(source);
            totalNew += count;
        }

        log.info("뉴스 수집 완료: 총 {} 건 신규 저장", totalNew);
    }

    /**
     * 특정 소스에서 뉴스 수집
     * @return 신규 저장된 기사 수
     */
    @Transactional
    public int fetchFromSource(NewsSource source) {
        try {
            log.info("수집 중: {} ({})", source.getName(), source.getFeedUrl());

            // Why: Rome 라이브러리가 RSS XML을 Java 객체로 자동 파싱
            SyndFeedInput input = new SyndFeedInput();
            SyndFeed feed = input.build(new XmlReader(URI.create(source.getFeedUrl()).toURL()));

            int newCount = 0;
            for (SyndEntry entry : feed.getEntries()) {
                String link = entry.getLink();

                // Why: URL로 중복 체크 (이미 수집된 기사는 건너뜀)
                if (articleRepository.existsByLink(link)) {
                    continue;
                }

                NewsArticle article = new NewsArticle();
                article.setSource(source);
                article.setTitle(entry.getTitle());
                article.setLink(link);
                article.setPublishedAt(toLocalDateTime(entry.getPublishedDate()));
                article.setDescription(cleanHtml(entry.getDescription() != null
                        ? entry.getDescription().getValue() : null));

                articleRepository.save(article);
                newCount++;
            }

            // Why: 다음 수집 시 참고용 (현재는 URL 중복 체크로 충분)
            source.setLastFetchedAt(LocalDateTime.now());
            sourceRepository.save(source);

            log.info("  → {} 에서 {} 건 신규 저장", source.getName(), newCount);
            return newCount;

        } catch (Exception e) {
            log.error("수집 실패: {} - {}", source.getName(), e.getMessage());
            return 0;
        }
    }

    /**
     * java.util.Date → LocalDateTime 변환
     * Why: RSS의 날짜 형식(Date)을 JPA가 사용하는 LocalDateTime으로 변환
     */
    private LocalDateTime toLocalDateTime(Date date) {
        if (date == null) return LocalDateTime.now();
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
    }

    /**
     * HTML 태그 제거
     * Why: RSS description에 <p>, <a> 등 HTML 태그가 포함되어 있음
     */
    private String cleanHtml(String html) {
        if (html == null) return null;
        return html.replaceAll("<[^>]*>", "").trim();
    }
}
