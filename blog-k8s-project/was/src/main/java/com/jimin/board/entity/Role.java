package com.jimin.board.entity;

/**
 * 사용자 권한 Enum
 *
 * USER  — 일반 사용자 (게시글 CRUD)
 * ADMIN — 관리자 (모든 게시글 삭제, 회원 관리)
 *
 * DB에는 문자열 "USER", "ADMIN"으로 저장됨 (@Enumerated(EnumType.STRING))
 */
public enum Role {
    USER,
    ADMIN
}
