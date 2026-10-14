package com.prguard.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 화면(pr-guard-web 서버)만 API 를 부르게 하는 토큰.
 *
 * @param token 비우면 검사하지 않는다 (로컬 개발)
 */
@ConfigurationProperties("prguard.api")
public record ApiTokenProperties(String token) {

    public boolean enabled() {
        return token != null && !token.isBlank();
    }
}
