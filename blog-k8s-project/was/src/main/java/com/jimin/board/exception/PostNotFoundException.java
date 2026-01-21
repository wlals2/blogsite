package com.jimin.board.exception;

/**
 * PostNotFoundException - 게시글을 찾을 수 없을 때 발생하는 예외
 *
 * RuntimeException을 상속하여 Unchecked Exception으로 구현
 * 이유: 게시글 없음은 예외적 상황이지만, 복구 가능한 상황이므로 클라이언트에게 404 응답
 */
public class PostNotFoundException extends RuntimeException {

    private final Long postId;

    /**
     * @param postId 찾지 못한 게시글 ID
     */
    public PostNotFoundException(Long postId) {
        super("게시글을 찾을 수 없습니다. ID: " + postId);
        this.postId = postId;
    }

    public Long getPostId() {
        return postId;
    }
}
