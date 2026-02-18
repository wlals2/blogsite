package com.jimin.board.dto;

import jakarta.validation.constraints.Size;

/**
 * 게시글 수정 요청 DTO
 *
 * Why: CreateRequest와 달리 모든 필드가 선택사항 (Partial Update)
 *      null인 필드는 기존 값을 유지함
 *
 * 예시:
 *  {"title": "새 제목"}           → 제목만 변경, 나머지 유지
 *  {"content": "새 내용"}         → 내용만 변경, 나머지 유지
 *  {"title": "X", "author": "Y"} → 제목+작성자 변경, 내용 유지
 */
public record PostUpdateRequest(

        // Why: @NotBlank 없음 → title을 안 보내면 null → 기존 제목 유지
        @Size(max = 200, message = "제목은 최대 200자입니다")
        String title,

        String content,

        @Size(max = 50, message = "작성자는 최대 50자입니다")
        String author
) {
}
