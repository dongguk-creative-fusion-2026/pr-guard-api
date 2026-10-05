package com.prguard.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prguard.analysis.AnalysisPipeline;
import com.prguard.analysis.AnalysisResult;
import com.prguard.analysis.ChangedFile;
import com.prguard.analysis.FindingRepository;
import com.prguard.analysis.PullInfo;
import com.prguard.diff.PatchParser;
import com.prguard.events.EventSink;
import com.prguard.events.ReviewEventBus;
import com.prguard.events.Stage;
import com.prguard.github.GitHubClient;
import com.prguard.github.GitHubPull;
import com.prguard.github.RepoRef;
import com.prguard.project.Project;
import com.prguard.project.ProjectRepository;
import com.prguard.pull.PullRequest;
import com.prguard.pull.PullRequestRepository;
import com.prguard.report.CommentPublisher;
import com.prguard.report.SummaryRenderer;
import com.prguard.review.Review;
import com.prguard.review.ReviewProperties;
import com.prguard.review.ReviewRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** PENDING 리뷰를 하나씩 집어서 수집 → 분석 → 저장 → PR 코멘트까지 처리한다. */
@Component
public class ReviewWorker {

    private static final Logger log = LoggerFactory.getLogger(ReviewWorker.class);

    private final ReviewRepository reviews;
    private final ProjectRepository projects;
    private final PullRequestRepository pulls;
    private final FindingRepository findings;
    private final GitHubClient github;
    private final AnalysisPipeline pipeline;
    private final SummaryRenderer renderer;
    private final CommentPublisher publisher;
    private final ReviewProperties props;
    private final ObjectMapper mapper;
    private final ReviewEventBus events;
    private final WebLinks links;

    public ReviewWorker(ReviewRepository reviews, ProjectRepository projects, PullRequestRepository pulls,
                        FindingRepository findings, GitHubClient github, AnalysisPipeline pipeline,
                        SummaryRenderer renderer, CommentPublisher publisher, ReviewProperties props,
                        ObjectMapper mapper, ReviewEventBus events, WebLinks links) {
        this.reviews = reviews;
        this.projects = projects;
        this.pulls = pulls;
        this.findings = findings;
        this.github = github;
        this.pipeline = pipeline;
        this.renderer = renderer;
        this.publisher = publisher;
        this.props = props;
        this.mapper = mapper;
        this.events = events;
        this.links = links;
    }

    @Scheduled(fixedDelayString = "${prguard.review.worker-delay}", initialDelayString = "PT10S")
    public void drain() {
        Optional<Review> next;
        while ((next = reviews.claimNext(props.staleAfter())).isPresent()) {
            process(next.get());
        }
    }

    void process(Review review) {
        EventSink sink = events.sinkFor(review.id());
        sink.running(Stage.REVIEW, "PR #" + review.prNumber() + " · " + review.headSha().substring(0, 7));
        try {
            Project project = projects.findById(review.projectId()).orElse(null);
            PullRequest pr = pulls.find(review.projectId(), review.prNumber()).orElse(null);
            // 큐에 있는 동안 PR 이 닫혔거나 새 커밋이 올라왔으면 이 리뷰는 의미가 없다
            if (project == null || pr == null || !pr.isOpen() || !pr.headSha().equals(review.headSha())) {
                reviews.markSuperseded(review.id());
                sink.skipped(Stage.REVIEW, "PR 이 닫혔거나 새 커밋이 올라와 건너뜀");
                return;
            }
            RepoRef repo = project.ref();
            long collectStarted = System.currentTimeMillis();
            sink.running(Stage.COLLECT, "PR 본문 · 커밋 · 변경 파일");
            GitHubPull ghPull = github.getPull(repo, pr.number());
            if (!ghPull.head().sha().equals(review.headSha())) {
                reviews.markSuperseded(review.id());
                sink.skipped(Stage.REVIEW, "새 커밋이 올라와 건너뜀");
                return;
            }

            List<ChangedFile> files = github.listPullFiles(repo, pr.number()).stream()
                    .map(f -> new ChangedFile(f.filename(), f.status(), f.additions(), f.deletions(), f.patch(),
                            f.previousFilename(), PatchParser.parse(f.patch())))
                    .toList();
            PullInfo pull = new PullInfo(repo, pr.number(), ghPull.title(), ghPull.body(),
                    ghPull.user() == null ? pr.author() : ghPull.user().login(),
                    ghPull.base().ref(), ghPull.head().ref(), review.headSha(),
                    github.listPullCommitMessages(repo, pr.number()));
            long repoKb = github.getRepo(repo).size();
            sink.done(Stage.COLLECT, "변경 파일 " + files.size() + "개 · 커밋 " + pull.commitMessages().size() + "개",
                    Map.of("title", pull.title(),
                            "files", files.size(),
                            "additions", files.stream().mapToInt(ChangedFile::additions).sum(),
                            "deletions", files.stream().mapToInt(ChangedFile::deletions).sum(),
                            "commits", pull.commitMessages().size(),
                            "hasBody", pull.body() != null && !pull.body().isBlank(),
                            "ms", System.currentTimeMillis() - collectStarted));

            AnalysisResult result = pipeline.run(pull, files, repoKb, sink);
            String comment = renderer.render(review.headSha(), files, result,
                    links.reviewUrl(project.id(), review.id()));

            findings.saveAll(review.id(), result.findings());
            long publishStarted = System.currentTimeMillis();
            String url = null;
            int inline = 0;
            if (github.canWrite()) {
                sink.running(Stage.PUBLISH, "요약 · 라인 코멘트");
                url = publisher.publishSummary(repo, pr, comment).orElse(null);
                inline = publisher.publishInline(repo, pr, review.headSha(), result.findings(), files);
                sink.done(Stage.PUBLISH, "요약 1건 · 라인 " + inline + "건", Map.of(
                        "commentUrl", url == null ? "" : url,
                        "inline", inline,
                        "ms", System.currentTimeMillis() - publishStarted));
            } else {
                sink.skipped(Stage.PUBLISH, "코멘트 꺼짐 (dry-run)");
            }

            reviews.markDone(review.id(), result.verdict(), result.reviewer(), result.summary(), comment,
                    json(result), url);
            sink.done(Stage.REVIEW, result.verdict().label(), Map.of(
                    "verdict", result.verdict().name(),
                    "findings", result.findings().size(),
                    "ms", result.context().elapsedMs()));
            log.info("리뷰 완료 {}#{} {} run={} {} 지적 {}건 (라인 {}건) {}ms {}", repo.fullName(), pr.number(),
                    review.headSha().substring(0, 7), review.run(), result.verdict(), result.findings().size(),
                    inline, result.context().elapsedMs(), url == null ? "dry-run" : url);
        } catch (RuntimeException e) {
            log.warn("리뷰 실패 id={}: {}", review.id(), e.toString());
            reviews.markFailed(review.id(), e.toString());
            sink.failed(Stage.REVIEW, e.getMessage());
        }
    }

    private String json(AnalysisResult result) {
        try {
            return mapper.writeValueAsString(result.context());
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
