package com.jimin.board.exception;

import com.jimin.board.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.stream.Collectors;

/**
 * GlobalExceptionHandler - 전역 예외 처리
 *
 * @RestControllerAdvice: 모든 @RestController에 적용
 * @ExceptionHandler: 특정 Exception 발생 시 자동 호출
 *
 * 장점:
 * 1. Controller에서 try-catch 제거 → 코드 간결
 * 2. 모든 API에서 일관된 에러 응답
 * 3. 새로운 Exception 추가 쉬움
 * 4. 에러 로깅 중앙화
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * PostNotFoundException 처리
     * GET /api/posts/{id} 에서 게시글 없을 때
     *
     * @return 404 Not Found + ErrorResponse
     */
    @ExceptionHandler(PostNotFoundException.class)
    public ResponseEntity<ErrorResponse> handlePostNotFound(
            PostNotFoundException ex,
            HttpServletRequest request
    ) {
        ErrorResponse error = new ErrorResponse(
                LocalDateTime.now().toString(),
                HttpStatus.NOT_FOUND.value(),
                HttpStatus.NOT_FOUND.getReasonPhrase(),
                ex.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(error);
    }

    /**
     * Validation 에러 처리
     * @Valid 검증 실패 시 (예: @NotBlank, @Size)
     *
     * POST /api/posts 에서 title이 빈 문자열일 때
     *
     * @return 400 Bad Request + ErrorResponse
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationError(
            MethodArgumentNotValidException ex,
            HttpServletRequest request
    ) {
        // 모든 필드 에러를 하나의 메시지로 합침
        String validationErrors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));

        ErrorResponse error = new ErrorResponse(
                LocalDateTime.now().toString(),
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                "입력값 검증 실패: " + validationErrors,
                request.getRequestURI()
        );

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(error);
    }

    /**
     * 그 외 모든 예외 처리 (Fallback)
     * 예상하지 못한 에러 발생 시
     *
     * @return 500 Internal Server Error + ErrorResponse
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneralError(
            Exception ex,
            HttpServletRequest request
    ) {
        ErrorResponse error = new ErrorResponse(
                LocalDateTime.now().toString(),
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
                "서버 내부 오류가 발생했습니다: " + ex.getMessage(),
                request.getRequestURI()
        );

        // 실제 운영 환경에서는 로깅 추가
        // log.error("Unexpected error", ex);

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error);
    }
}
