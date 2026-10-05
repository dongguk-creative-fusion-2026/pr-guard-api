package com.prguard.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubCommit(String sha, Detail commit) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Detail(String message) {
    }
}
