package com.jimin.board.dto;

import com.jimin.board.entity.Post;

import java.time.LocalDateTime;

/**
 * 게시글 응답 DTO
 *
 * Why: Entity를 직접 반환하면 내부 구조가 노출됨
 *      응답에 포함할 필드만 선택해서 안전하게 반환
 *
 * from() 팩토리 메서드: Post Entity → PostResponse 변환
 *  - "from"이라는 이름으로 의미가 명확 ("Post로부터 만든다")
 *  - static이므로 PostResponse.from(post)로 바로 호출 가능
 */
public record PostResponse(
        Long id,
        String title,
        String content,
        String author,
        Long viewCount,
        LocalDateTime createdAt
) {
    /**
     * Post Entity → PostResponse 변환 (팩토리 메서드)
     *
     * 사용법: PostResponse.from(post)
     *
     * static: 객체 생성 없이 클래스 이름으로 바로 호출 가능
     *         PostResponse.from(post) ← 이렇게
     *
     * @param post DB에서 조회한 Post Entity
     * @return API 응답용 PostResponse
     */
    public static PostResponse from(Post post) {
        return new PostResponse(
                post.getId(),
                post.getTitle(),
                post.getContent(),
                post.getAuthor(),
                post.getViewCount(),
                post.getCreatedAt()
        );
    }
}
