package com.jimin.board.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Post Entity - 게시글 테이블과 매핑
 *
 * DB 테이블: posts
 * 컬럼:
 *  - id: Primary Key (자동 증가)
 *  - title: 제목 (필수, 최대 200자)
 *  - content: 내용 (TEXT 타입)
 *  - author: 작성자 (최대 50자)
 *  - createdAt: 작성일 (자동 생성)
 */
@Entity  // 이 클래스가 DB 테이블과 매핑됨
@Table(name = "posts")  // 테이블 이름 지정
@Data  // Lombok: getter, setter, toString 자동 생성
@NoArgsConstructor  // Lombok: 기본 생성자 자동 생성
@AllArgsConstructor  // Lombok: 모든 필드 포함 생성자 자동 생성
public class Post {

    @Id  // Primary Key
    @GeneratedValue(strategy = GenerationType.IDENTITY)  // Auto Increment (MySQL)
    private Long id;

    @NotBlank(message = "제목은 필수입니다")  // 빈 값 검증
    @Size(max = 200, message = "제목은 최대 200자입니다")  // 길이 검증
    @Column(nullable = false, length = 200)  // DB 컬럼 설정
    private String title;

    @Column(columnDefinition = "TEXT")  // TEXT 타입 (긴 문자열)
    private String content;

    @Size(max = 50, message = "작성자는 최대 50자입니다")
    @Column(length = 50)
    private String author;

    @Column(name = "created_at", nullable = false, updatable = false)  // 수정 불가
    private LocalDateTime createdAt;

    /**
     * 엔티티가 DB에 저장되기 전에 자동 실행
     * → createdAt을 현재 시간으로 자동 설정
     */
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
