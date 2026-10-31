package com.prguard.review;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 기존 코드 충돌 검사 (LLM 재질문).
 *
 * @param enabled    OPENAI_API_KEY 가 있어도 끌 수 있다
 * @param maxMethods 볼 바뀐 메서드 수
 * @param maxChars   기존 코드 조각 전체의 최대 길이 (토큰 비용 상한)
 */
@ConfigurationProperties("prguard.conflict")
public record ConflictProperties(boolean enabled, int maxMethods, int maxChars) {
}
