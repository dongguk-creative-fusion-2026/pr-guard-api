package com.prguard.review;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class ReviewerConfig {

    private static final Logger log = LoggerFactory.getLogger(ReviewerConfig.class);

    @Bean
    Reviewer reviewer(RestClient.Builder builder, OpenAiProperties openai, ReviewProperties review) {
        if (!openai.enabled()) {
            log.warn("OPENAI_API_KEY 가 없어 stats-only 리뷰어로 동작합니다");
            return new StatsOnlyReviewer();
        }
        return new OpenAiReviewer(builder, openai, new ReviewPrompt(review.maxPatchChars()));
    }
}
