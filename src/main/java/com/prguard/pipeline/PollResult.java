package com.prguard.pipeline;

/**
 * 프로젝트 하나를 폴링한 결과.
 *
 * @param notModified GitHub 이 304 를 줘서 바뀐 게 없음
 * @param queuedReviews 새 커밋이 감지돼 생성된 리뷰 작업 수
 */
public record PollResult(long projectId, boolean notModified, int openPulls, int closedPulls, int queuedReviews,
                         String error) {

    static PollResult failed(long projectId, String error) {
        return new PollResult(projectId, false, 0, 0, 0, error);
    }
}
