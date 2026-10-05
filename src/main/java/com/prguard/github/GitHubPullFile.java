package com.prguard.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** PR 변경 파일. 바이너리나 너무 큰 파일은 patch 가 null 이다. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record GitHubPullFile(
        String filename,
        String status,
        int additions,
        int deletions,
        String patch,
        String previousFilename) {
}
