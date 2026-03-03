package com.jimin.board.repository;

import com.jimin.board.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * 로그인 시 username으로 사용자 조회
     * → SELECT * FROM users WHERE username = ?
     *
     * Optional인 이유: username이 없을 수 있음
     * → .orElseThrow()로 안전하게 처리
     */
    Optional<User> findByUsername(String username);

    /**
     * 회원가입 시 username 중복 체크
     * → SELECT COUNT(*) > 0 FROM users WHERE username = ?
     *
     * true = 이미 존재 → 가입 거부
     * false = 사용 가능 → 가입 진행
     */
    boolean existsByUsername(String username);
}
