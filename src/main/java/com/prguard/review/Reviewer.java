package com.prguard.review;

/** PR 하나를 보고 마크다운 리뷰를 만든다. 구현을 바꿔 끼우는 지점. */
public interface Reviewer {

    /** 코멘트와 DB 에 남는 리뷰어 이름 (예: openai:gpt-5-mini). */
    String name();

    String review(ReviewInput input);
}
