package com.prguard.report;

import com.prguard.github.GitHubClient;
import com.prguard.github.GitHubComment;
import com.prguard.github.GitHubException;
import com.prguard.github.RepoRef;
import com.prguard.pull.PullRequest;
import com.prguard.pull.PullRequestRepository;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** PR 당 요약 코멘트 하나를 유지한다. 처음엔 만들고, 그 뒤로는 같은 코멘트를 고친다. */
@Component
public class CommentPublisher {

    private final GitHubClient github;
    private final PullRequestRepository pulls;

    public CommentPublisher(GitHubClient github, PullRequestRepository pulls) {
        this.github = github;
        this.pulls = pulls;
    }

    /** 토큰이 없으면 아무것도 하지 않는다 (dry-run). 반환값은 코멘트 URL. */
    public Optional<String> publish(RepoRef repo, PullRequest pr, String body) {
        if (!github.canWrite()) {
            return Optional.empty();
        }
        if (pr.commentId() != null) {
            try {
                return Optional.of(github.updateComment(repo, pr.commentId(), body).htmlUrl());
            } catch (GitHubException e) {
                // 누가 코멘트를 지웠으면 새로 단다
                if (e.status() != 404) {
                    throw e;
                }
            }
        }
        GitHubComment created = github.createComment(repo, pr.number(), body);
        pulls.setCommentId(pr.id(), created.id());
        return Optional.of(created.htmlUrl());
    }
}
