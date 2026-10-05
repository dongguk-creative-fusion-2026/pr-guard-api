package com.prguard.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record GitHubPull(
        int number,
        String title,
        String body,
        String state,
        boolean draft,
        String htmlUrl,
        GitHubUser user,
        Ref head,
        Ref base) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Ref(String ref, String sha) {
    }
}
