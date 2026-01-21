package com.jimin.board.repository;

import com.jimin.board.entity.Post;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * PostRepository - 게시글 DB 접근 인터페이스
 *
 * JpaRepository<Post, Long>을 상속받으면:
 *  - Post: 엔티티 타입
 *  - Long: Primary Key 타입 (id의 타입)
 *
 * 자동으로 제공되는 메서드:
 *  - findAll() → 모든 게시글 조회
 *  - findById(id) → ID로 게시글 조회
 *  - save(post) → 게시글 저장 (INSERT 또는 UPDATE)
 *  - deleteById(id) → 게시글 삭제
 *  - count() → 게시글 개수
 */
@Repository  // Spring Bean으로 등록 (자동 의존성 주입)
public interface PostRepository extends JpaRepository<Post, Long> {

    /**
     * 커스텀 쿼리: 제목으로 게시글 검색
     * Spring Data JPA가 메서드 이름으로 자동 쿼리 생성
     *
     * findByTitle → SELECT * FROM posts WHERE title = ?
     * findByTitleContaining → SELECT * FROM posts WHERE title LIKE %?%
     */
    List<Post> findByTitleContaining(String keyword);

    /**
     * 커스텀 쿼리: 작성자로 게시글 검색
     * findByAuthor → SELECT * FROM posts WHERE author = ?
     */
    List<Post> findByAuthor(String author);

    /**
     * 커스텀 쿼리: 최신순으로 게시글 조회
     * OrderBy + 필드명 + Desc → 내림차순 정렬
     * SELECT * FROM posts ORDER BY created_at DESC
     */
    List<Post> findAllByOrderByCreatedAtDesc();
}
