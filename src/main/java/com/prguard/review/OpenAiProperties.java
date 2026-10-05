package com.prguard.review;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("prguard.openai")
public record OpenAiProperties(String apiUrl, String apiKey, String model) {

    public boolean enabled() {
        return apiKey != null && !apiKey.isBlank();
    }
}
