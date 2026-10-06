package com.prguard.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record GitHubRepo(
        String name,
        GitHubUser owner,
        @JsonProperty("private") boolean isPrivate,
        String htmlUrl,
        String defaultBranch,
        long size,
        String description,
        String language,
        int stargazersCount,
        String pushedAt) {
}
