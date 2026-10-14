package com.prguard.pipeline;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * GitHub 웹훅. 레포(또는 조직) 설정의 Webhooks 에 같은 secret 을 넣으면 PR 이벤트를 바로 받는다.
 *
 * @param secret 비우면 웹훅을 받지 않는다 (폴링만)
 */
@ConfigurationProperties("prguard.webhook")
public record WebhookProperties(String secret) {

    public boolean enabled() {
        return secret != null && !secret.isBlank();
    }
}
