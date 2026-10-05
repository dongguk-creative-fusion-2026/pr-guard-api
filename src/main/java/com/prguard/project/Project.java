package com.prguard.project;

import com.prguard.github.RepoRef;
import java.time.OffsetDateTime;

public record Project(
        long id,
        String owner,
        String name,
        String htmlUrl,
        String defaultBranch,
        int openPullCount,
        OffsetDateTime lastPolledAt,
        String lastPollError,
        OffsetDateTime createdAt) {

    public RepoRef ref() {
        return new RepoRef(owner, name);
    }
}
