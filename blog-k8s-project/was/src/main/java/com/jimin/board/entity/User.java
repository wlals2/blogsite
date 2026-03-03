package com.jimin.board.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "사용자명은 필수입니다")
    @Size(max = 50, message = "사용자명은 최대 50자입니다")
    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @NotBlank(message = "비밀번호는 필수입니다")
    @Column(nullable = false, length = 200)
    private String password;

    @Size(max = 100, message = "이메일은 최대 100자입니다")
    @Column(length = 100)
    private String email;

    // Why: EnumType.STRING → DB에 "USER", "ADMIN" 문자열 저장
    //      ORDINAL(숫자)은 Enum 순서 변경 시 의미가 뒤바뀌므로 절대 사용 금지
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role = Role.USER;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
