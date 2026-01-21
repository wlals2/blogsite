package com.jimin.board;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * BoardApplication - Spring Boot 게시판 애플리케이션
 *
 * v1.4.0 Features:
 * - Swagger UI (API 문서 자동 생성)
 * - Pagination (페이징 지원)
 * - Error Response Standardization (RFC 7807)
 *
 * Note: Startup time is ~90 seconds due to Swagger initialization
 * Kubernetes Startup Probe configured with 210s timeout
 */
@SpringBootApplication
public class BoardApplication {

	public static void main(String[] args) {
		SpringApplication.run(BoardApplication.class, args);
	}

}
