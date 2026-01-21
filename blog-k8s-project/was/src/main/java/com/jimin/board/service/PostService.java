package com.jimin.board.service;

import com.jimin.board.entity.Post;
import com.jimin.board.repository.PostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * PostService - 게시글 비즈니스 로직 처리
 *
 * @Transactional: DB 트랜잭션 관리
 *  - readOnly = true: 읽기 전용 (성능 최적화)
 *  - 메서드 실패 시 자동 롤백
 */
@Service  // Spring Bean으로 등록
@RequiredArgsConstructor  // Lombok: final 필드 자동 생성자 주입
@Transactional(readOnly = true)  // 기본적으로 읽기 전용 트랜잭션
public class PostService {

    private final PostRepository postRepository;  // 자동 주입

    /**
     * 모든 게시글 조회 (최신순)
     * @return 게시글 리스트
     */
    public List<Post> getAllPosts() {
        return postRepository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * ID로 게시글 조회
     * @param id 게시글 ID
     * @return 게시글 (없으면 예외 발생)
     */
    public Post getPostById(Long id) {
        return postRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("게시글을 찾을 수 없습니다. ID: " + id));
    }

    /**
     * 게시글 작성
     * @Transactional: 쓰기 작업이므로 readOnly = false (기본값)
     *
     * @param post 저장할 게시글
     * @return 저장된 게시글 (ID 포함)
     */
    @Transactional  // 쓰기 트랜잭션 (readOnly = false)
    public Post createPost(Post post) {
        // 비즈니스 로직: 제목이 비어있으면 기본값 설정
        if (post.getTitle() == null || post.getTitle().trim().isEmpty()) {
            post.setTitle("제목 없음");
        }

        // 비즈니스 로직: 작성자가 비어있으면 기본값 설정
        if (post.getAuthor() == null || post.getAuthor().trim().isEmpty()) {
            post.setAuthor("익명");
        }

        return postRepository.save(post);
    }

    /**
     * 게시글 수정
     * @param id 수정할 게시글 ID
     * @param updatedPost 수정 내용
     * @return 수정된 게시글
     */
    @Transactional
    public Post updatePost(Long id, Post updatedPost) {
        // 1. 기존 게시글 조회
        Post existingPost = getPostById(id);

        // 2. 수정할 필드만 업데이트 (Partial Update)
        if (updatedPost.getTitle() != null) {
            existingPost.setTitle(updatedPost.getTitle());
        }
        if (updatedPost.getContent() != null) {
            existingPost.setContent(updatedPost.getContent());
        }
        if (updatedPost.getAuthor() != null) {
            existingPost.setAuthor(updatedPost.getAuthor());
        }

        // 3. 저장 (UPDATE 쿼리 실행)
        return postRepository.save(existingPost);
    }

    /**
     * 게시글 삭제
     * @param id 삭제할 게시글 ID
     */
    @Transactional
    public void deletePost(Long id) {
        // 존재 여부 확인 후 삭제
        if (!postRepository.existsById(id)) {
            throw new RuntimeException("게시글을 찾을 수 없습니다. ID: " + id);
        }
        postRepository.deleteById(id);
    }

    /**
     * 제목으로 게시글 검색
     * @param keyword 검색 키워드
     * @return 검색 결과 리스트
     */
    public List<Post> searchPosts(String keyword) {
        return postRepository.findByTitleContaining(keyword);
    }
}
