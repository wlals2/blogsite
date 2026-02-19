package com.jimin.board.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 전역 CORS 설정
 *
 * Why: /news 페이지에서 브라우저 JS fetch()가 /api/news를 호출할 때
 *      브라우저는 OPTIONS preflight를 먼저 보낸다.
 *      Spring Boot가 CORS 헤더(Access-Control-Allow-Origin)를 응답에 포함해야
 *      브라우저가 실제 GET 요청을 허용한다.
 *
 *      Istio AuthorizationPolicy에서 OPTIONS를 허용해도,
 *      WAS가 CORS 헤더를 안 보내면 브라우저가 최종 차단한다.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(@NonNull CorsRegistry registry) {
        registry.addMapping("/api/news")
                .allowedOrigins("https://blog.jiminhome.shop")
                .allowedMethods("GET", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);

        registry.addMapping("/api/news/category/*")
                .allowedOrigins("https://blog.jiminhome.shop")
                .allowedMethods("GET", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
