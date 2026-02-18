package com.jimin.board.controller;

import com.jimin.board.dto.PostCreateRequest;
import com.jimin.board.dto.PostResponse;
import com.jimin.board.dto.PostUpdateRequest;
import com.jimin.board.service.PostService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * PostController - 게시글 REST API 엔드포인트
 *
 * @RestController: JSON 응답을 반환하는 컨트롤러
 * @RequestMapping: 모든 API가 /api/posts로 시작
 * @Tag: Swagger UI에서 API 그룹화
 */
@RestController
@RequestMapping("/api/posts")  // 기본 경로
@RequiredArgsConstructor  // Lombok: final 필드 자동 생성자 주입
@Tag(name = "게시글 API", description = "게시글 CRUD 및 검색 API")
public class PostController {

    private final PostService postService;  // 자동 주입

    /**
     * 1. 모든 게시글 조회 (Pagination 지원)
     * GET /api/posts
     * GET /api/posts?page=0&size=10
     *
     * @param page 페이지 번호 (0부터 시작, 기본값: 0)
     * @param size 페이지당 항목 수 (기본값: 10)
     * @return Page<Post> (페이징 정보 포함)
     *
     * 예시 요청: GET /api/posts?page=0&size=10
     * 예시 응답:
     * {
     *   "content": [
     *     {
     *       "id": 1,
     *       "title": "첫 번째 글",
     *       "content": "안녕하세요!",
     *       "author": "지민",
     *       "viewCount": 42,
     *       "createdAt": "2026-01-16T10:00:00"
     *     }
     *   ],
     *   "totalElements": 100,
     *   "totalPages": 10,
     *   "number": 0,
     *   "size": 10,
     *   "first": true,
     *   "last": false
     * }
     */
    @GetMapping
    @Operation(summary = "게시글 목록 조회", description = "페이징을 지원하는 게시글 목록 조회 API")
    public ResponseEntity<Page<PostResponse>> getAllPosts(
            @Parameter(description = "페이지 번호 (0부터 시작)", example = "0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지당 항목 수", example = "10")
            @RequestParam(defaultValue = "10") int size
    ) {
        // Pageable 객체 생성: page, size, 정렬(최신순)
        Pageable pageable = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<PostResponse> posts = postService.getAllPostsPaged(pageable);
        return ResponseEntity.ok(posts);  // 200 OK
    }

    /**
     * 2. 특정 게시글 조회 (조회수 자동 증가)
     * GET /api/posts/{id}
     *
     * @param id 게시글 ID (경로 변수)
     * @return 게시글 (200 OK) 또는 404 Not Found
     *
     * 예시 요청: GET /api/posts/1
     * 예시 응답:
     * {
     *   "id": 1,
     *   "title": "첫 번째 글",
     *   "content": "안녕하세요!",
     *   "author": "지민",
     *   "viewCount": 42,
     *   "createdAt": "2026-01-16T10:00:00"
     * }
     *
     * 에러 처리: GlobalExceptionHandler가 자동 처리
     */
    @GetMapping("/{id}")
    @Operation(summary = "게시글 상세 조회", description = "게시글 조회 시 조회수가 자동으로 1 증가합니다")
    public ResponseEntity<PostResponse> getPostById(@PathVariable Long id) {
        PostResponse post = postService.getPostByIdWithView(id);  // 조회수 증가
        return ResponseEntity.ok(post);  // 200 OK
    }

    /**
     * 3. 게시글 작성
     * POST /api/posts
     *
     * @param post 작성할 게시글 (JSON 형식)
     * @Valid: 유효성 검증 (@NotBlank, @Size 등)
     *
     * @return 저장된 게시글 (201 Created)
     *
     * 예시 요청 Body:
     * {
     *   "title": "새 글",
     *   "content": "내용입니다",
     *   "author": "지민"
     * }
     *
     * 예시 응답: (201 Created)
     * {
     *   "id": 2,
     *   "title": "새 글",
     *   "content": "내용입니다",
     *   "author": "지민",
     *   "createdAt": "2026-01-16T10:05:00"
     * }
     */
    @PostMapping
    public ResponseEntity<PostResponse> createPost(
            @Valid @RequestBody PostCreateRequest request) {
        // @Valid: PostCreateRequest의 @NotBlank, @Size 검증 실행
        // @RequestBody: JSON → PostCreateRequest로 변환 (Jackson)
        //
        // 이제 사용자가 {"id": 1, "viewCount": 999}를 보내도
        // PostCreateRequest에는 해당 필드가 없으므로 무시됨 (안전!)
        PostResponse savedPost = postService.createPost(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(savedPost);  // 201 Created
    }

    /**
     * 4. 게시글 수정
     * PUT /api/posts/{id}
     *
     * @param id 수정할 게시글 ID
     * @param post 수정 내용 (JSON 형식)
     * @return 수정된 게시글 (200 OK) 또는 404 Not Found
     *
     * 예시 요청: PUT /api/posts/1
     * 예시 Body:
     * {
     *   "title": "수정된 제목",
     *   "content": "수정된 내용"
     * }
     *
     * 예시 응답: (200 OK)
     * {
     *   "id": 1,
     *   "title": "수정된 제목",
     *   "content": "수정된 내용",
     *   "author": "지민",
     *   "createdAt": "2026-01-16T10:00:00"
     * }
     *
     * 에러 처리: GlobalExceptionHandler가 자동 처리
     */
    @PutMapping("/{id}")
    public ResponseEntity<PostResponse> updatePost(
            @PathVariable Long id,
            @Valid @RequestBody PostUpdateRequest request) {
        PostResponse updatedPost = postService.updatePost(id, request);
        return ResponseEntity.ok(updatedPost);  // 200 OK
    }

    /**
     * 5. 게시글 삭제
     * DELETE /api/posts/{id}
     *
     * @param id 삭제할 게시글 ID
     * @return 204 No Content (성공) 또는 404 Not Found
     *
     * 예시 요청: DELETE /api/posts/1
     * 예시 응답: (204 No Content, 응답 Body 없음)
     *
     * 에러 처리: GlobalExceptionHandler가 자동 처리
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePost(@PathVariable Long id) {
        postService.deletePost(id);
        return ResponseEntity.noContent().build();  // 204 No Content
    }

    /**
     * 6. 게시글 검색
     * GET /api/posts/search?keyword=XXX
     *
     * @param keyword 검색 키워드
     * @return 검색 결과 리스트 (200 OK)
     *
     * 예시 요청: GET /api/posts/search?keyword=안녕
     * 예시 응답:
     * [
     *   {
     *     "id": 1,
     *     "title": "안녕하세요",
     *     "content": "...",
     *     "author": "지민",
     *     "createdAt": "..."
     *   }
     * ]
     */
    @GetMapping("/search")
    public ResponseEntity<List<PostResponse>> searchPosts(@RequestParam String keyword) {
        List<PostResponse> posts = postService.searchPosts(keyword);
        return ResponseEntity.ok(posts);  // 200 OK
    }
}
