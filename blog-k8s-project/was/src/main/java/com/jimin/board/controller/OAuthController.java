package com.jimin.board.controller;

import com.jimin.board.dto.GitHubOAuthRequest;
import com.jimin.board.dto.GitHubOAuthResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@Slf4j
@RestController
@RequestMapping("/auth")
public class OAuthController {

    @Value("${github.oauth.client-id}")
    private String clientId;

    @Value("${github.oauth.client-secret}")
    private String clientSecret;

    @Value("${github.oauth.redirect-uri}")
    private String redirectUri;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * GitHub OAuth 시작 엔드포인트
     *
     * Decap CMS가 "Login with GitHub"를 클릭하면 이 URL로 요청합니다:
     * https://blog.jiminhome.shop/auth?provider=github&site_id=...
     *
     * 이 메서드가 하는 일:
     * 1. GitHub OAuth 인증 URL 생성
     * 2. GitHub 로그인 페이지로 리다이렉트
     */
    @GetMapping("")
    public void initiateOAuth(@RequestParam(required = false) String provider,
                              @RequestParam(required = false, name = "site_id") String siteId,
                              HttpServletResponse response) throws IOException {

        log.info("OAuth initiation requested. provider: {}, site_id: {}", provider, siteId);

        // GitHub OAuth 인증 URL 생성
        // scope=repo: 리포지토리 읽기/쓰기 권한 요청
        String authUrl = String.format(
            "https://github.com/login/oauth/authorize?client_id=%s&redirect_uri=%s&scope=repo",
            clientId,
            redirectUri
        );

        log.info("Redirecting to GitHub OAuth: {}", authUrl);

        // GitHub OAuth 페이지로 리다이렉트
        response.sendRedirect(authUrl);
    }

    /**
     * GitHub OAuth Callback 엔드포인트
     *
     * GitHub가 인증 후 이 URL로 리다이렉트합니다:
     * https://blog.jiminhome.shop/auth/callback?code=abc123xyz
     *
     * 이 메서드가 하는 일:
     * 1. code 파라미터 받기
     * 2. GitHub API에 code → token 교환 요청
     * 3. token을 HTML로 postMessage 전송 (CMS가 받음)
     */
    @GetMapping("/callback")
    public void handleCallback(@RequestParam String code,
                                HttpServletRequest request,
                                HttpServletResponse response) throws IOException {

        // 보안: IP 로깅
        String clientIp = request.getRemoteAddr();
        log.info("OAuth callback received from IP: {}, code: {}...",
                 clientIp, code.substring(0, Math.min(5, code.length())));

        try {
            // Step 1: GitHub API로 토큰 교환 요청
            String token = exchangeCodeForToken(code);

            // Step 2: 성공 페이지 반환 (postMessage로 CMS에 토큰 전달)
            String html = generateSuccessPage(token);
            response.setContentType("text/html; charset=UTF-8");
            response.getWriter().write(html);

            log.info("OAuth token exchange successful for IP: {}", clientIp);

        } catch (Exception e) {
            log.error("OAuth token exchange failed for IP: {}", clientIp, e);

            // Step 3: 실패 페이지 반환
            String html = generateErrorPage(e.getMessage());
            response.setContentType("text/html; charset=UTF-8");
            response.getWriter().write(html);
        }
    }

    /**
     * GitHub API를 호출해서 code를 token으로 교환
     *
     * POST https://github.com/login/oauth/access_token
     * Content-Type: application/json
     * Body: {
     *   "client_id": "...",
     *   "client_secret": "...",
     *   "code": "...",
     *   "redirect_uri": "..."
     * }
     *
     * 응답: {
     *   "access_token": "ghp_xxxxxxxxxxxx",
     *   "token_type": "bearer",
     *   "scope": "repo"
     * }
     */
    private String exchangeCodeForToken(String code) {
        String url = "https://github.com/login/oauth/access_token";

        // GitHub API 요청 생성
        GitHubOAuthRequest request = GitHubOAuthRequest.builder()
                .client_id(clientId)
                .client_secret(clientSecret)  // ← 이것이 WAS에만 있는 Secret!
                .code(code)
                .redirect_uri(redirectUri)
                .build();

        // HTTP 헤더 설정 (JSON 응답 요청)
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Accept", "application/json");

        HttpEntity<GitHubOAuthRequest> entity = new HttpEntity<>(request, headers);

        // GitHub API 호출
        ResponseEntity<GitHubOAuthResponse> response = restTemplate.exchange(
                url,
                HttpMethod.POST,
                entity,
                GitHubOAuthResponse.class
        );

        if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
            return response.getBody().getAccessToken();
        } else {
            throw new RuntimeException("Failed to exchange code for token");
        }
    }

    /**
     * 성공 시 반환할 HTML 페이지
     *
     * postMessage API를 사용해서 CMS(부모 창)에 토큰 전달:
     * window.opener.postMessage({ token: "ghp_xxx" }, "https://blog.jiminhome.shop")
     */
    private String generateSuccessPage(String token) {
        return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>GitHub 인증 성공</title>
    <style>
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
            display: flex;
            justify-content: center;
            align-items: center;
            height: 100vh;
            margin: 0;
            background: #f6f8fa;
        }
        .container {
            text-align: center;
            background: white;
            padding: 40px;
            border-radius: 8px;
            box-shadow: 0 2px 8px rgba(0,0,0,0.1);
        }
        .success {
            color: #28a745;
            font-size: 48px;
            margin-bottom: 20px;
        }
        h1 {
            color: #24292e;
            margin: 0 0 10px 0;
        }
        p {
            color: #586069;
        }
    </style>
</head>
<body>
    <div class="container">
        <div class="success">✓</div>
        <h1>인증 성공!</h1>
        <p>잠시 후 자동으로 닫힙니다...</p>
    </div>
    <script>
        // Decap CMS에 토큰 전달
        if (window.opener) {
            // Decap CMS 공식 OAuth 프로토콜
            window.opener.postMessage(
                "authorizing:github",
                "*"
            );

            window.opener.postMessage(
                'authorization:github:success:{"token":"TOKEN_VALUE","provider":"github"}',
                "*"
            );

            // 3초 후 창 닫기
            setTimeout(function() {
                window.close();
            }, 3000);
        }
    </script>
</body>
</html>
""".replace("TOKEN_VALUE", token);
    }

    /**
     * 실패 시 반환할 HTML 페이지
     */
    private String generateErrorPage(String errorMessage) {
        return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>GitHub 인증 실패</title>
    <style>
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
            display: flex;
            justify-content: center;
            align-items: center;
            height: 100vh;
            margin: 0;
            background: #f6f8fa;
        }
        .container {
            text-align: center;
            background: white;
            padding: 40px;
            border-radius: 8px;
            box-shadow: 0 2px 8px rgba(0,0,0,0.1);
            max-width: 500px;
        }
        .error {
            color: #d73a49;
            font-size: 48px;
            margin-bottom: 20px;
        }
        h1 {
            color: #24292e;
            margin: 0 0 10px 0;
        }
        p {
            color: #586069;
        }
        .error-detail {
            background: #f6f8fa;
            padding: 10px;
            border-radius: 4px;
            margin-top: 20px;
            font-family: monospace;
            font-size: 14px;
            color: #d73a49;
        }
    </style>
</head>
<body>
    <div class="container">
        <div class="error">✗</div>
        <h1>인증 실패</h1>
        <p>GitHub 인증 중 오류가 발생했습니다.</p>
        <div class="error-detail">ERROR_MESSAGE</div>
    </div>
    <script>
        if (window.opener) {
            window.opener.postMessage(
                'authorization:github:error:' + 'ERROR_MESSAGE',
                'https://blog.jiminhome.shop'
            );
        }
    </script>
</body>
</html>
""".replace("ERROR_MESSAGE", errorMessage);
    }
}
