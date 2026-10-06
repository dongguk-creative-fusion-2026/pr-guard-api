package com.prguard.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * GitHub Actions 실행 하나.
 *
 * @param path         워크플로 파일 (.github/workflows/x.yml)
 * @param displayTitle 워크플로의 run-name 으로 정한 제목
 * @param status       queued, in_progress, completed …
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record GitHubWorkflowRun(long id, String path, String event, String displayTitle, String status, String htmlUrl) {
}
