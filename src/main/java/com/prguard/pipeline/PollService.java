package com.prguard.pipeline;

import com.prguard.github.GitHubClient;
import com.prguard.github.GitHubPull;
import com.prguard.github.OpenPulls;
import com.prguard.project.Project;
import com.prguard.project.ProjectRepository;
import com.prguard.pull.PullRequestRepository;
import com.prguard.review.ReviewRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 열린 PR 을 조회해서 새 커밋(head SHA)이 보이면 리뷰 작업을 만든다. 리뷰 자체는 ReviewWorker 가 한다. */
@Service
public class PollService {

    private static final Logger log = LoggerFactory.getLogger(PollService.class);

    private final GitHubClient github;
    private final ProjectRepository projects;
    private final PullRequestRepository pulls;
    private final ReviewRepository reviews;

    public PollService(GitHubClient github, ProjectRepository projects, PullRequestRepository pulls,
                       ReviewRepository reviews) {
        this.github = github;
        this.projects = projects;
        this.pulls = pulls;
        this.reviews = reviews;
    }

    public List<PollResult> pollAll() {
        return projects.findAll().stream().map(this::poll).toList();
    }

    public PollResult poll(Project project) {
        try {
            OpenPulls open = github.listOpenPulls(project.ref(), projects.pullsEtag(project.id()));
            if (open.notModified()) {
                projects.markPolled(project.id(), open.etag());
                return new PollResult(project.id(), true, project.openPullCount(), 0, 0, null);
            }

            int queued = 0;
            for (GitHubPull pr : open.pulls()) {
                if (track(project.id(), pr)) {
                    queued++;
                }
            }
            int closed = pulls.closeMissing(project.id(), open.pulls().stream().map(GitHubPull::number).toList());
            projects.markPolled(project.id(), open.etag());
            if (queued > 0 || closed > 0) {
                log.info("{}: 열린 PR {}개, 새 리뷰 {}건, 닫힘 {}건",
                        project.ref().fullName(), open.pulls().size(), queued, closed);
            }
            return new PollResult(project.id(), false, open.pulls().size(), closed, queued, null);
        } catch (RuntimeException e) {
            log.warn("{} 폴링 실패: {}", project.ref().fullName(), e.getMessage());
            projects.markPollFailed(project.id(), e.getMessage());
            return PollResult.failed(project.id(), e.getMessage());
        }
    }

    /** 열린 PR 을 저장하고, head SHA 가 처음 보이면 리뷰 작업을 만든다 (예전 커밋의 대기 작업은 버림). 만들었으면 true. */
    boolean track(long projectId, GitHubPull pr) {
        pulls.upsertOpen(projectId, pr);
        String sha = pr.head().sha();
        if (!reviews.createPending(projectId, pr.number(), sha)) {
            return false;
        }
        reviews.supersedeOlder(projectId, pr.number(), sha);
        return true;
    }
}
