package com.prguard.review;

import java.time.OffsetDateTime;

/** PR 의 특정 커밋(head_sha)에 대한 리뷰 작업 하나. */
public record Review(
        long id,
        long projectId,
        int prNumber,
        String headSha,
        ReviewStatus status,
        String reviewer,
        String result,
        String error,
        String commentUrl,
        OffsetDateTime createdAt,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt) {
}
