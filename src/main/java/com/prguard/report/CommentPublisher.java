package com.prguard.report;

import com.prguard.analysis.Finding;
import com.prguard.github.GitHubClient;
import com.prguard.github.GitHubComment;
import com.prguard.github.GitHubException;
import com.prguard.github.RepoRef;
import com.prguard.pull.PullRequest;
import com.prguard.pull.PullRequestRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * PR 에 결과를 남긴다.
 * <ul>
 *   <li>요약: PR 당 코멘트 하나. 처음엔 만들고 그 뒤로는 같은 코멘트를 고친다</li>
 *   <li>라인 코멘트: 라인을 특정한 지적만, 이 PR 에 이미 단 지적은 다시 달지 않는다</li>
 * </ul>
 * 토큰이 없으면 아무것도 하지 않는다 (dry-run).
 */
@Component
public class CommentPublisher {

    private static final Logger log = LoggerFactory.getLogger(CommentPublisher.class);
    private static final int MAX_INLINE = 20;

    private final GitHubClient github;
    private final PullRequestRepository pulls;
    private final PostedCommentRepository posted;
    private final SummaryRenderer renderer;

    public CommentPublisher(GitHubClient github, PullRequestRepository pulls, PostedCommentRepository posted,
                            SummaryRenderer renderer) {
        this.github = github;
        this.pulls = pulls;
        this.posted = posted;
        this.renderer = renderer;
    }

    /** 반환값은 요약 코멘트 URL. */
    public Optional<String> publishSummary(RepoRef repo, PullRequest pr, String body) {
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

    /** 반환값은 새로 단 라인 코멘트 수. */
    public int publishInline(RepoRef repo, PullRequest pr, String headSha, List<Finding> findings) {
        if (!github.canWrite()) {
            return 0;
        }
        Set<String> already = posted.fingerprints(pr.id());
        List<Finding> targets = findings.stream()
                .filter(f -> f.line() != null && f.file() != null && !already.contains(f.fingerprint()))
                .limit(MAX_INLINE)
                .toList();
        if (targets.isEmpty()) {
            return 0;
        }
        List<GitHubClient.ReviewComment> comments = new ArrayList<>();
        for (Finding f : targets) {
            comments.add(new GitHubClient.ReviewComment(f.file(), f.line(), "RIGHT", renderer.inline(f)));
        }
        try {
            github.createReview(repo, pr.number(), headSha,
                    "PR Guard 라인 코멘트 " + comments.size() + "건 · 전체 결과는 PR 의 요약 코멘트를 보세요.", comments);
        } catch (GitHubException e) {
            // 라인이 diff 밖이면 422. 요약 코멘트에는 이미 들어 있으니 라인 코멘트만 포기한다
            log.warn("라인 코멘트 실패 {}#{}: {}", repo.fullName(), pr.number(), e.getMessage());
            return 0;
        }
        posted.saveAll(pr.id(), targets.stream().map(Finding::fingerprint).toList());
        return comments.size();
    }
}
