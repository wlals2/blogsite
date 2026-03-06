package com.jimin.board.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * HTTP 보안 정책 설정
     *
     * Why STATELESS: JWT는 서버에 세션을 저장하지 않음
     *   → K8s에서 Pod가 2개여도 어느 Pod에서든 토큰 검증 가능
     *
     * Why csrf disable: REST API는 브라우저 폼 기반이 아니므로 CSRF 불필요
     *   → JWT로 인증하므로 CSRF 토큰 없어도 안전
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .authorizeHttpRequests(auth -> auth
                // Why GET만 공개: POST/PUT/DELETE를 permitAll하면
                // 인증 없이 글 작성/수정/삭제 가능 (IDOR 취약점)
                .requestMatchers(HttpMethod.GET, "/api/posts/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/posts/**").authenticated()
                .requestMatchers(HttpMethod.PUT, "/api/posts/**").authenticated()
                .requestMatchers(HttpMethod.DELETE, "/api/posts/**").authenticated()

                // 인증 없이 접근 가능한 경로
                .requestMatchers(
                    "/api/auth/**",       // 회원가입, 로그인
                    "/auth/**",           // GitHub OAuth (기존 Decap CMS용)
                    "/actuator/health",   // K8s Health Check
                    "/actuator/prometheus", // Prometheus 메트릭 수집 (인증 없이 허용)
                    "/swagger-ui/**",     // Swagger UI
                    "/v3/api-docs/**"     // OpenAPI 문서
                ).permitAll()
                .anyRequest().authenticated() // 나머지는 인증 필요
            );

        return http.build();
    }

    /**
     * 비밀번호 해시 알고리즘
     *
     * Why BCrypt:
     *   - 단방향 해시 (복호화 불가) → 비밀번호 원문 저장 안 함
     *   - Salt 자동 생성 → 같은 비밀번호도 매번 다른 해시값
     *   - strength=10: 해시 연산 2^10=1024회 반복 → Brute Force 방어
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
