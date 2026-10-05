package com.prguard.review;

/** PR 하나를 LLM 으로 리뷰한다. 구현을 바꿔 끼우는 지점. */
public interface Reviewer {

    /** 코멘트와 DB 에 남는 리뷰어 이름 (예: openai:gpt-5-mini). */
    String name();

    LlmReview review(ReviewInput input);
}
