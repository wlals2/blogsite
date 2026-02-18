package com.jimin.board.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 게시글 작성 요청 DTO
 *
 * Why: Entity(Post)를 직접 받으면 id, viewCount 등을 조작할 수 있어서
 *      작성에 필요한 필드(title, content, author)만 받는 DTO를 사용
 *
 * record: Java 16+ 불변 데이터 클래스
 *  - 생성자, getter(title(), content(), author()), equals, hashCode, toString 자동 생성
 *  - setter 없음 → 값 변경 불가 (안전)
 */
public record PostCreateRequest(

        // @NotBlank: 빈 문자열(""), 공백("  "), null 모두 거부
        // → 제목 없이 게시글 작성 방지
        @NotBlank(message = "제목은 필수입니다")
        @Size(max = 200, message = "제목은 최대 200자입니다")
        String title,

        // content는 검증 없음 → 내용 없이도 작성 가능
        String content,

        // author는 필수 아님 → 비어있으면 Service에서 "익명" 처리
        @Size(max = 50, message = "작성자는 최대 50자입니다")
        String author
) {
}
