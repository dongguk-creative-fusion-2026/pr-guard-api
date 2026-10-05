package com.prguard.github;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param commentEnabled false 면 토큰이 있어도 PR 에 코멘트를 달지 않는다 (로컬 개발, 평가 재실행용)
 */
@ConfigurationProperties("prguard.github")
public record GitHubProperties(String apiUrl, String token, boolean commentEnabled) {

    /** 토큰이 없으면 읽기만 한다 (rate limit 60/h, 코멘트 불가). */
    public boolean hasToken() {
        return token != null && !token.isBlank();
    }
}
