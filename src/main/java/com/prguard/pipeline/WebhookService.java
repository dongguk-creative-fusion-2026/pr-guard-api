package com.prguard.pipeline;

import com.prguard.github.GitHubPull;
import com.prguard.project.ProjectRepository;
import com.prguard.pull.PullRequestRepository;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 서명을 확인한 GitHub 웹훅을 폴링과 같은 방식으로 반영하고, 새 리뷰 작업이 생기면 워커를 바로 깨운다. */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);
    /** PR 정보나 head SHA 가 바뀌는 동작. 새 커밋이면 리뷰 작업이 생긴다 */
    private static final Set<String> OPEN_ACTIONS = Set.of("opened", "reopened", "synchronize", "edited",
            "ready_for_review", "converted_to_draft");

    private final ProjectRepository projects;
    private final PullRequestRepository pulls;
    private final PollService poller;
    private final ReviewWorker worker;

    public WebhookService(ProjectRepository projects, PullRequestRepository pulls, PollService poller,
                          ReviewWorker worker) {
        this.projects = projects;
        this.pulls = pulls;
        this.poller = poller;
        this.worker = worker;
    }

    WebhookResult handle(String event, WebhookEvent body) {
        if ("ping".equals(event)) {
            return new WebhookResult("PONG", null, null, null);
        }
        if (!"pull_request".equals(event)) {
            return WebhookResult.ignored("처리하지 않는 이벤트: " + event);
        }
        if (body == null || body.pullRequest() == null || body.pullRequest().head() == null
                || body.repository() == null || body.repository().owner() == null) {
            return WebhookResult.ignored("pull_request 본문이 비었음");
        }
        String owner = body.repository().owner().login();
        String name = body.repository().name();
        Optional<Long> projectId = projects.findIdByRepo(owner, name);
        if (projectId.isEmpty()) {
            return WebhookResult.ignored("등록되지 않은 레포: " + owner + "/" + name);
        }
        long id = projectId.get();
        GitHubPull pr = body.pullRequest();
        String action = body.action();

        if ("closed".equals(action)) {
            pulls.close(id, pr.number());
            log.info("웹훅 {}/{}#{} 닫힘", owner, name, pr.number());
            return new WebhookResult("CLOSED", id, pr.number(), null);
        }
        if (!OPEN_ACTIONS.contains(action)) {
            return new WebhookResult("IGNORED", id, pr.number(), "처리하지 않는 동작: " + action);
        }
        if (!poller.track(id, pr)) {
            return new WebhookResult("UPDATED", id, pr.number(), null);
        }
        log.info("웹훅 {}/{}#{} {} → 리뷰 대기", owner, name, pr.number(), action);
        worker.wake();
        return new WebhookResult("QUEUED", id, pr.number(), null);
    }
}
