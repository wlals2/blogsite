package com.jimin.board.repository;

import com.jimin.board.entity.Post;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * 조회수 원자적 증가 (Race Condition 방지)
     *
     * 기존 방식의 문제:
     *   1. SELECT로 viewCount 조회 (예: 10)
     *   2. Java에서 +1 (11)
     *   3. UPDATE로 저장
     *   → 동시 요청 시 데이터 손실 가능
     *
     * 개선된 방식:
     *   UPDATE posts SET view_count = view_count + 1 WHERE id = ?
     *   → DB 레벨에서 원자적(atomic) 증가
     *   → 동시 요청해도 정확한 카운트 보장
     *
     * @param id 게시글 ID
     * @return 업데이트된 행 수 (정상: 1, 없는 ID: 0)
     */
    @Modifying  // UPDATE/DELETE 쿼리임을 명시
    @Query("UPDATE Post p SET p.viewCount = p.viewCount + 1 WHERE p.id = :id")
    int incrementViewCount(@Param("id") Long id);
}
