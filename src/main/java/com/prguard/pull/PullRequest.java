package com.prguard.pull;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.OffsetDateTime;

/** 폴링으로 본 PR 상태. latestReview* 는 이 PR 의 가장 최근 리뷰. */
public record PullRequest(
        long id,
        long projectId,
        int number,
        String title,
        String author,
        String htmlUrl,
        String headSha,
        String headRef,
        String baseRef,
        String state,
        boolean draft,
        Long commentId,
        OffsetDateTime updatedAt,
        Long latestReviewId,
        String latestReviewStatus) {

    @JsonIgnore
    public boolean isOpen() {
        return "open".equals(state);
    }
}
