package com.jimin.board;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * BoardApplication - Spring Boot 게시판 + 뉴스 수집 애플리케이션
 *
 * v2.0.0 Features:
 * - Board CRUD (게시판)
 * - News RSS Auto-Fetch (보안/AI 뉴스 자동 수집)
 * - Swagger UI (API 문서 자동 생성)
 * - Pagination (페이징 지원)
 *
 * Note: Startup time is ~90 seconds due to Swagger initialization
 * Kubernetes Startup Probe configured with 210s timeout
 */
@SpringBootApplication
@EnableScheduling  // Why: @Scheduled 어노테이션 활성화 (뉴스 매시간 수집)
public class BoardApplication {

	public static void main(String[] args) {
		SpringApplication.run(BoardApplication.class, args);
	}

}
