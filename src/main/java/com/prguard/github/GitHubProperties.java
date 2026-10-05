package com.prguard.github;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("prguard.github")
public record GitHubProperties(String apiUrl, String token) {

    /** 토큰이 없으면 읽기만 한다 (rate limit 60/h, 코멘트 불가). */
    public boolean hasToken() {
        return token != null && !token.isBlank();
    }
}
