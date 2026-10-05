package com.prguard.review;

import java.util.List;

/** OPENAI_API_KEY 가 없을 때 쓰는 리뷰어. LLM 판단 없이 정적 분석 결과만 남는다. */
public class StatsOnlyReviewer implements Reviewer {

    @Override
    public String name() {
        return "stats-only";
    }

    @Override
    public LlmReview review(ReviewInput input) {
        return new LlmReview("AI 리뷰가 꺼져 있습니다 (`OPENAI_API_KEY` 미설정). 정적 분석 결과만 표시합니다.", List.of());
    }
}
