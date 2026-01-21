package com.jimin.board.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ErrorResponse - 표준화된 에러 응답 DTO
 *
 * RFC 7807 (Problem Details for HTTP APIs) 스타일
 * 모든 API 에러가 이 형식으로 응답
 *
 * 예시 응답:
 * {
 *   "timestamp": "2026-01-21T15:30:00",
 *   "status": 404,
 *   "error": "Not Found",
 *   "message": "게시글을 찾을 수 없습니다. ID: 999",
 *   "path": "/api/posts/999"
 * }
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ErrorResponse {

    /**
     * 에러 발생 시각 (ISO 8601)
     */
    private String timestamp;

    /**
     * HTTP 상태 코드 (404, 400, 500 등)
     */
    private int status;

    /**
     * HTTP 상태 이름 ("Not Found", "Bad Request" 등)
     */
    private String error;

    /**
     * 사용자에게 보여줄 에러 메시지
     */
    private String message;

    /**
     * 에러가 발생한 API 경로
     */
    private String path;
}
