package com.prguard.pipeline;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.prguard.github.GitHubPull;
import com.prguard.github.GitHubUser;

/** pull_request 웹훅 본문에서 쓰는 부분. pull_request 는 REST 의 PR 과 같은 모양이다. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record WebhookEvent(String action, GitHubPull pullRequest, Repository repository) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Repository(String name, GitHubUser owner) {
    }
}
