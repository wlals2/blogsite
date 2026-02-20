package com.jimin.board.config;

import com.jimin.board.entity.NewsSource;
import com.jimin.board.repository.NewsSourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * RSS 뉴스 소스 초기화
 *
 * Why: DB에 소스를 코드로 관리하여 GitOps 방식으로 변경 가능.
 *      배포 시마다 실행되지만 existsByFeedUrl로 중복 삽입 방지 (멱등성 보장).
 *
 * 변경 이력:
 *   v1 → 외국 소스 (THN, BleepingComputer, Krebs 등)
 *   v2 → 국내 기술 블로그 (카카오, 토스, 당근 등)
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class NewsSourceInitializer {

    private final NewsSourceRepository sourceRepository;

    // 기존 외국 소스 feedUrl 목록 (비활성화 대상)
    private static final List<String> LEGACY_FEED_URLS = List.of(
        "https://feeds.feedburner.com/TheHackersNews",
        "https://www.bleepingcomputer.com/feed/",
        "https://krebsonsecurity.com/feed/",
        "https://www.theverge.com/rss/ai-artificial-intelligence/index.xml",
        "https://www.technologyreview.com/feed/"
    );

    // 국내 기술 블로그 소스 (category: tech)
    private static final List<Object[]> DOMESTIC_SOURCES = List.of(
        // { name, feedUrl, category }
        new Object[]{ "카카오 Tech",      "https://tech.kakao.com/feed/",                     "tech" },
        new Object[]{ "토스 Tech",        "https://toss.tech/rss.xml",                         "tech" },
        new Object[]{ "당근 Tech",        "https://medium.com/feed/daangn",                    "tech" },
        new Object[]{ "우아한형제들 Tech", "https://techblog.woowahan.com/feed/",               "tech" },
        new Object[]{ "네이버 D2",        "https://d2.naver.com/d2.atom",                      "tech" },
        new Object[]{ "카카오페이 Tech",   "https://tech.kakaopay.com/rss",                    "tech" },
        new Object[]{ "라인 엔지니어링",   "https://engineering.linecorp.com/ko/feed",         "tech" },
        new Object[]{ "쿠팡 엔지니어링",   "https://medium.com/feed/coupang-engineering",      "tech" },
        new Object[]{ "NHN Cloud",        "https://meetup.nhncloud.com/rss",                   "tech" }
    );

    @Bean
    @Transactional
    public ApplicationRunner initNewsSources() {
        return args -> {
            // 1. 기존 외국 소스 비활성화 (기사 데이터는 보존, 소스만 비활성화)
            List<NewsSource> allSources = sourceRepository.findAll();
            int deactivated = 0;
            for (NewsSource source : allSources) {
                if (LEGACY_FEED_URLS.contains(source.getFeedUrl()) && source.isActive()) {
                    source.setActive(false);
                    sourceRepository.save(source);
                    deactivated++;
                    log.info("외국 소스 비활성화: {}", source.getName());
                }
            }
            if (deactivated > 0) {
                log.info("기존 외국 소스 {}개 비활성화 완료", deactivated);
            }

            // 2. 국내 소스 추가 (feedUrl 기준 중복 체크)
            int added = 0;
            for (Object[] cfg : DOMESTIC_SOURCES) {
                String name    = (String) cfg[0];
                String feedUrl = (String) cfg[1];
                String category = (String) cfg[2];

                if (!sourceRepository.existsByFeedUrl(feedUrl)) {
                    NewsSource source = new NewsSource();
                    source.setName(name);
                    source.setFeedUrl(feedUrl);
                    source.setCategory(category);
                    source.setActive(true);
                    sourceRepository.save(source);
                    added++;
                    log.info("국내 소스 추가: {} ({})", name, category);
                }
            }

            if (added > 0) {
                log.info("국내 기술 블로그 소스 {}개 추가 완료", added);
            } else {
                log.info("국내 소스 초기화 완료 (추가 없음 - 이미 존재)");
            }
        };
    }
}
