package com.prguard.review;

import com.prguard.analysis.Verdict;
import java.time.OffsetDateTime;

/**
 * PR 의 특정 커밋(head_sha)에 대한 리뷰 작업 하나.
 *
 * @param run          같은 커밋을 다시 리뷰한 차수 (폴링이 만든 것은 1)
 * @param findingCount 지적 사항 수 (목록 조회에서만 채움)
 */
public record Review(
        long id,
        long projectId,
        int prNumber,
        String headSha,
        int run,
        ReviewStatus status,
        Verdict verdict,
        String reviewer,
        String summary,
        String result,
        String error,
        String commentUrl,
        int findingCount,
        OffsetDateTime createdAt,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt) {
}
