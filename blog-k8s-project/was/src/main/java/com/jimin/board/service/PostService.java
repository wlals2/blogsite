package com.jimin.board.service;

import com.jimin.board.dto.PostCreateRequest;
import com.jimin.board.dto.PostResponse;
import com.jimin.board.dto.PostUpdateRequest;
import com.jimin.board.entity.Post;
import com.jimin.board.exception.PostNotFoundException;
import com.jimin.board.repository.PostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
     * @deprecated 페이징 적용된 getAllPostsPaged() 사용 권장
     */
    public List<Post> getAllPosts() {
        return postRepository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * 모든 게시글 조회 (페이징, 최신순)
     *
     * Pagination 장점:
     * 1. 성능 최적화: 필요한 만큼만 조회 (LIMIT, OFFSET 사용)
     * 2. 메모리 절약: 1,000개 게시글 중 10개만 로드
     * 3. 응답 속도 향상: 데이터 직렬화 시간 단축
     *
     * @param pageable 페이징 정보 (page, size, sort)
     * @return Page 객체 (content, totalElements, totalPages, etc.)
     *
     * 예시 요청: GET /api/posts?page=0&size=10
     * 예시 응답:
     * {
     *   "content": [ 게시글 10개 ],
     *   "totalElements": 100,
     *   "totalPages": 10,
     *   "number": 0,
     *   "size": 10,
     *   "first": true,
     *   "last": false
     * }
     */
    public Page<PostResponse> getAllPostsPaged(Pageable pageable) {
        // postRepository.findAll() → Page<Post> (Entity 목록)
        // .map(PostResponse::from) → Page<PostResponse> (DTO 목록)
        //
        // PostResponse::from 은 메서드 참조(Method Reference)
        // 풀어쓰면: .map(post -> PostResponse.from(post))
        // 뜻: "각 Post를 PostResponse로 변환해라"
        return postRepository.findAll(pageable)
                .map(PostResponse::from);
    }

    /**
     * ID로 게시글 조회 (조회수 증가 없음)
     * - 내부 로직용 (수정, 삭제 시 사용)
     *
     * @param id 게시글 ID
     * @return 게시글 (없으면 예외 발생)
     */
    public Post getPostById(Long id) {
        return postRepository.findById(id)
                .orElseThrow(() -> new PostNotFoundException(id));
    }

    /**
     * ID로 게시글 조회 + 조회수 증가 (원자적 UPDATE)
     * - API 조회용 (사용자가 게시글 상세 조회 시)
     * - DB 레벨에서 원자적 증가 → Race Condition 방지
     *
     * 기존 방식 (문제):
     *   SELECT → Java +1 → UPDATE (동시 요청 시 손실)
     *
     * 개선된 방식:
     *   UPDATE SET viewCount = viewCount + 1 (원자적)
     *   → SELECT (최신 데이터 조회)
     *
     * @param id 게시글 ID
     * @return 게시글 (조회수 증가됨)
     */
    @Transactional  // 쓰기 트랜잭션 (조회수 업데이트)
    public PostResponse getPostByIdWithView(Long id) {
        // 1. 원자적 조회수 증가 (DB 레벨)
        int updated = postRepository.incrementViewCount(id);

        // 2. 게시글이 없으면 예외 발생
        if (updated == 0) {
            throw new PostNotFoundException(id);
        }

        // 3. 최신 데이터 조회
        Post post = postRepository.findById(id)
                .orElseThrow(() -> new PostNotFoundException(id));

        // 4. Entity → DTO 변환 후 반환
        return PostResponse.from(post);
    }

    /**
     * 게시글 작성
     * @Transactional: 쓰기 작업이므로 readOnly = false (기본값)
     *
     * @param post 저장할 게시글
     * @return 저장된 게시글 (ID 포함)
     */
    @Transactional  // 쓰기 트랜잭션 (readOnly = false)
    public PostResponse createPost(PostCreateRequest request) {
        // 1. DTO → Entity 변환
        //    request에는 title, content, author만 있음
        //    id, viewCount, createdAt은 자동 설정됨
        Post post = new Post();
        post.setTitle(request.title());     // record의 getter는 title() (get 없음)
        post.setContent(request.content());
        post.setAuthor(request.author());

        // 2. 비즈니스 로직: 작성자가 비어있으면 기본값 설정
        //    제목은 @NotBlank로 검증되므로 여기서 체크 불필요
        if (post.getAuthor() == null || post.getAuthor().trim().isEmpty()) {
            post.setAuthor("익명");
        }

        // 3. DB 저장 → Entity → DTO 변환 후 반환
        Post savedPost = postRepository.save(post);
        return PostResponse.from(savedPost);
    }

    /**
     * 게시글 수정
     * @param id 수정할 게시글 ID
     * @param updatedPost 수정 내용
     * @return 수정된 게시글
     */
    @Transactional
    public PostResponse updatePost(Long id, PostUpdateRequest request) {
        // 1. 기존 게시글 조회
        Post existingPost = getPostById(id);

        // 2. Partial Update (부분 수정)
        //    null인 필드는 건너뜀 → 기존 값 유지
        //    예: {"title": "새 제목"} → title만 변경, content와 author는 그대로
        if (request.title() != null) {
            existingPost.setTitle(request.title());
        }
        if (request.content() != null) {
            existingPost.setContent(request.content());
        }
        if (request.author() != null) {
            existingPost.setAuthor(request.author());
        }

        // 3. 저장 → Entity → DTO 변환 후 반환
        Post savedPost = postRepository.save(existingPost);
        return PostResponse.from(savedPost);
    }

    /**
     * 게시글 삭제
     * @param id 삭제할 게시글 ID
     */
    @Transactional
    public void deletePost(Long id) {
        // 존재 여부 확인 후 삭제
        if (!postRepository.existsById(id)) {
            throw new PostNotFoundException(id);
        }
        postRepository.deleteById(id);
    }

    /**
     * 제목으로 게시글 검색
     * @param keyword 검색 키워드
     * @return 검색 결과 리스트
     */
    public List<PostResponse> searchPosts(String keyword) {
        // postRepository.findByTitleContaining() → List<Post>
        // .stream() → 목록을 하나씩 처리할 수 있는 "스트림"으로 변환
        // .map(PostResponse::from) → 각 Post를 PostResponse로 변환
        // .toList() → 다시 List로 모음
        return postRepository.findByTitleContaining(keyword)
                .stream()
                .map(PostResponse::from)
                .toList();
    }
}
