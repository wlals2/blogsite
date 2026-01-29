package com.jimin.board.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GitHubOAuthRequest {
    private String client_id;
    private String client_secret;
    private String code;
    private String redirect_uri;
}
