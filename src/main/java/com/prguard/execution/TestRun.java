package com.prguard.execution;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonRawValue;
import java.time.OffsetDateTime;

/**
 * 리뷰 하나에서 base 또는 head 쪽 테스트를 한 번 돌린 기록.
 *
 * @param status     QUEUED → STARTED → DONE(보고서 받음) | FAILED(빌드 실패 · 시간 초과 · 실행 실패)
 * @param runnerName 쿠버네티스 Job 또는 docker 컨테이너 이름
 * @param phase      러너가 알린 진행 단계 (cloning, building …)
 * @param results    테스트 케이스별 결과 [{name, status, message}]
 */
public record TestRun(
        long id,
        Long reviewId,
        String side,
        String sha,
        String status,
        @JsonIgnore String tokenHash,
        String runnerName,
        String phase,
        String message,
        Integer exitCode,
        Integer tests,
        Integer failures,
        Integer errors,
        Integer skipped,
        @JsonRawValue String results,
        String logTail,
        String error,
        OffsetDateTime createdAt,
        OffsetDateTime finishedAt) {

    public boolean finished() {
        return "DONE".equals(status) || "FAILED".equals(status);
    }
}
