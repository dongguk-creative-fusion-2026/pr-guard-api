package com.prguard.pipeline;

import com.prguard.github.GitHubClient;
import com.prguard.github.GitHubPullFile;
import com.prguard.project.Project;
import com.prguard.project.ProjectRepository;
import com.prguard.pull.PullRequest;
import com.prguard.pull.PullRequestRepository;
import com.prguard.report.CommentPublisher;
import com.prguard.report.SummaryRenderer;
import com.prguard.review.Review;
import com.prguard.review.ReviewInput;
import com.prguard.review.ReviewProperties;
import com.prguard.review.ReviewRepository;
import com.prguard.review.Reviewer;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** PENDING 리뷰를 하나씩 집어서 diff 조회 → 리뷰 → PR 코멘트까지 처리한다. */
@Component
public class ReviewWorker {

    private static final Logger log = LoggerFactory.getLogger(ReviewWorker.class);

    private final ReviewRepository reviews;
    private final ProjectRepository projects;
    private final PullRequestRepository pulls;
    private final GitHubClient github;
    private final Reviewer reviewer;
    private final SummaryRenderer renderer;
    private final CommentPublisher publisher;
    private final ReviewProperties props;

    public ReviewWorker(ReviewRepository reviews, ProjectRepository projects, PullRequestRepository pulls,
                        GitHubClient github, Reviewer reviewer, SummaryRenderer renderer,
                        CommentPublisher publisher, ReviewProperties props) {
        this.reviews = reviews;
        this.projects = projects;
        this.pulls = pulls;
        this.github = github;
        this.reviewer = reviewer;
        this.renderer = renderer;
        this.publisher = publisher;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${prguard.review.worker-delay}", initialDelayString = "PT10S")
    public void drain() {
        Optional<Review> next;
        while ((next = reviews.claimNext(props.staleAfter())).isPresent()) {
            process(next.get());
        }
    }

    void process(Review review) {
        try {
            Project project = projects.findById(review.projectId()).orElse(null);
            PullRequest pr = pulls.find(review.projectId(), review.prNumber()).orElse(null);
            // 큐에 있는 동안 PR 이 닫혔거나 새 커밋이 올라왔으면 이 리뷰는 의미가 없다
            if (project == null || pr == null || !pr.isOpen() || !pr.headSha().equals(review.headSha())) {
                reviews.markSuperseded(review.id());
                return;
            }

            ReviewInput input = toInput(project, pr, github.listPullFiles(project.ref(), pr.number()));
            String body = reviewer.review(input);
            String comment = renderer.render(input, reviewer.name(), body);
            String url = publisher.publish(project.ref(), pr, comment).orElse(null);

            reviews.markDone(review.id(), reviewer.name(), comment, url);
            log.info("리뷰 완료 {}#{} {} ({})", project.ref().fullName(), pr.number(),
                    review.headSha().substring(0, 7), url == null ? "dry-run" : url);
        } catch (RuntimeException e) {
            log.warn("리뷰 실패 id={}: {}", review.id(), e.toString());
            reviews.markFailed(review.id(), e.toString());
        }
    }

    private ReviewInput toInput(Project project, PullRequest pr, List<GitHubPullFile> files) {
        return new ReviewInput(
                project.ref().fullName(), pr.number(), pr.title(), null, pr.author(),
                pr.baseRef(), pr.headRef(), pr.headSha(),
                files.stream()
                        .map(f -> new ReviewInput.ChangedFile(f.filename(), f.status(), f.additions(), f.deletions(),
                                f.patch()))
                        .toList());
    }
}
