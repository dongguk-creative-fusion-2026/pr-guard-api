package com.prguard.graph;

import com.fasterxml.jackson.annotation.JsonRawValue;
import java.time.OffsetDateTime;

/**
 * 프로젝트 레포의 파일 의존성 그래프.
 *
 * @param commitSha 그래프를 만든 기본 브랜치 커밋
 * @param graph     GitNexus 결과를 파일 단위로 묶은 JSON ({nodes, edges, communities, stats}). 처음 만들기 전에는 null
 * @param error     마지막 실패 이유. 실패해도 예전 그래프는 남는다
 */
public record RepoGraph(
        long projectId,
        GraphStatus status,
        String commitSha,
        @JsonRawValue String graph,
        String error,
        OffsetDateTime createdAt,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt) {
}
