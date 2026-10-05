package com.prguard.review;

/** OPENAI_API_KEY 가 없을 때 쓰는 리뷰어. 변경 통계만 남기고 판단은 하지 않는다. */
public class StatsOnlyReviewer implements Reviewer {

    @Override
    public String name() {
        return "stats-only";
    }

    @Override
    public String review(ReviewInput input) {
        return "> AI 리뷰가 꺼져 있습니다 (`OPENAI_API_KEY` 미설정). 변경 통계만 표시합니다.";
    }
}
