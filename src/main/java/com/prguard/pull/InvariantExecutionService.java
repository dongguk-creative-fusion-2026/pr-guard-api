package com.prguard.pull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prguard.common.ApiException;
import com.prguard.github.GitHubClient;
import com.prguard.github.RepoRef;
import com.prguard.project.ProjectService;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import com.prguard.pull.InvariantRunRepository.Active;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Dispatch and artifact collection only: the API host never executes PR code. */
@Service
@org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
public class InvariantExecutionService {
    static final String WORKFLOW = "invariant-check.yml";
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(InvariantExecutionService.class);
    private final ProjectService projects;
    private final GitHubClient github;
    private final InvariantRunRepository runs;
    private final ObjectMapper mapper;
    private final InvariantResultReader results;
    @Value("${INVARIANT_EXEC_ENABLED:false}") private boolean enabled;
    @Value("${INVARIANT_WORKFLOW_REPO:dongguk-creative-fusion-2026/pr-guard-api}") private String workflowRepo;
    @Value("${INVARIANT_WORKFLOW_REF:main}") private String workflowRef;

    public InvariantExecutionService(ProjectService projects, GitHubClient github, InvariantRunRepository runs, ObjectMapper mapper) {
        this.projects = projects;
        this.github = github;
        this.runs = runs;
        this.mapper = mapper;
        this.results = new InvariantResultReader(mapper);
    }

    static void validate(InvariantRuleCompiler.Candidate c) {
        if (c == null || !"CANDIDATE".equals(c.status()) || c.version() != InvariantRuleCompiler.VERSION
                || !InvariantRuleCompiler.RULE_ID.equals(c.ruleId()) || !InvariantRuleCompiler.ADAPTER.equals(c.adapter())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVARIANT_NOT_EXECUTABLE", "이 규칙에는 실행 가능한 어댑터가 없습니다.");
        }
    }

    public JsonNode start(long id, int number, InvariantRuleCompiler.Candidate candidate) {
        validate(candidate);
        if (!enabled) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "INVARIANT_DISABLED", "GitHub Actions 불변식 실행이 꺼져 있습니다.");
        var project = projects.get(id);
        if (!"dongguk-creative-fusion-2026".equals(project.owner()) || !"pr-guard-sandbox".equals(project.name())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVARIANT_ADAPTER_MISMATCH", "현재 실행 어댑터는 pr-guard-sandbox만 지원합니다.");
        }
        UUID runId = UUID.randomUUID();
        boolean inserted = false;
        try {
            var pull = github.getPull(project.ref(), number);
            String head = pull.head().sha();
            String base = github.mergeBase(project.ref(), pull.base().sha(), head);
            if (!head.matches("[0-9a-f]{40}") || !base.matches("[0-9a-f]{40}")) throw new IllegalStateException("Invalid commits");
            runs.create(runId, id, number, head, base, mapper.writeValueAsString(candidate), workflowRepo, workflowRef);
            inserted = true;
            github.dispatchWorkflow(RepoRef.parse(workflowRepo), WORKFLOW, workflowRef,
                    Map.of("run_id", runId.toString(), "head_sha", head, "base_sha", base), null);
            return mapper.valueToTree(Map.of("id", runId.toString(), "status", "QUEUED", "headSha", head, "baseSha", base));
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "INVARIANT_BUSY", "다른 불변식 테스트가 대기 또는 실행 중입니다.");
        } catch (Exception e) {
            if (inserted) fail(runId, "Actions 실행 요청에 실패했습니다. 워크플로 등록과 Actions 쓰기 권한을 확인하세요.");
            log.warn("Invariant Actions dispatch failed: {}", e.getClass().getSimpleName());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "INVARIANT_START_FAILED", "Actions 실행을 요청하지 못했습니다. 워크플로 등록과 GitHub 토큰 권한을 확인하세요.");
        }
    }

    public JsonNode get(long id, int number, UUID runId) {
        projects.get(id);
        return runs.find(id, number, runId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "INVARIANT_RUN_NOT_FOUND", "실행 기록이 없습니다."));
    }

    @Scheduled(fixedDelayString = "${INVARIANT_POLL_INTERVAL:PT30S}", initialDelayString = "PT10S")
    public void collect() {
        // Existing jobs continue to be collected after new dispatches are disabled or the API restarts.
        for (var active : runs.active()) {
            try {
                if (active.createdAt().isBefore(OffsetDateTime.now().minusMinutes(30))) {
                    if (active.workflowRunId() != null) {
                        try { github.cancelInvariantWorkflow(RepoRef.parse(active.workflowRepo()), active.workflowRunId()); }
                        catch (Exception ignored) { log.warn("Could not cancel expired Actions job {}", active.id()); }
                    }
                    fail(active.id(), "Actions 실행이 30분 안에 완료되지 않았습니다. 대기열·취소·사용 한도를 확인하세요.");
                } else collect(active);
            } catch (Exception e) {
                log.warn("Invariant Actions collection {} failed: {}", active.id(), e.getClass().getSimpleName());
            }
        }
    }

    private void collect(Active active) {
        RepoRef repo = RepoRef.parse(active.workflowRepo());
        JsonNode run = null;
        if (active.workflowRunId() != null) run = github.workflowDetails(repo, active.workflowRunId());
        else {
            for (JsonNode possible : github.invariantWorkflowRuns(repo, WORKFLOW, active.workflowRef()).path("workflow_runs")) {
                if (matches(possible, active.id(), active.workflowRef())) { run = possible; break; }
            }
        }
        if (run == null) return;
        if (!matches(run, active.id(), active.workflowRef())) { fail(active.id(), "실행 정보가 요청한 워크플로와 일치하지 않습니다."); return; }
        long workflowId = run.path("id").asLong();
        String state = "in_progress".equals(run.path("status").asText()) || "completed".equals(run.path("status").asText()) ? "RUNNING" : "QUEUED";
        runs.track(active.id(), workflowId, run.path("html_url").asText(), state);
        if (!"completed".equals(run.path("status").asText())) return;
        String name = "invariant-" + active.id() + "-1";
        for (JsonNode artifact : github.workflowArtifacts(repo, workflowId).path("artifacts")) {
            if (!name.equals(artifact.path("name").asText()) || artifact.path("expired").asBoolean()) continue;
            if (artifact.path("size_in_bytes").asLong() > 1_000_000) { fail(active.id(), "결과 파일 크기 제한 초과"); return; }
            JsonNode result;
            try {
                result = results.read(github.invariantArtifact(repo, artifact.path("id").asLong()), active.headSha(), active.baseSha());
            } catch (IllegalArgumentException e) { fail(active.id(), "유효한 Hypothesis 결과 파일이 아닙니다."); return; }
            boolean error = "execution_error".equals(result.path("status").asText());
            if (!"success".equals(run.path("conclusion").asText()) && !error) {
                fail(active.id(), "Actions 작업이 정상 종료되지 않아 결과를 채택하지 않았습니다.");
            } else finish(active.id(), error ? "FAILED" : "DONE", result);
            return;
        }
        if (active.completedSeenAt() == null) {
            runs.waitForArtifact(active.id());
        } else if (active.completedSeenAt().isBefore(OffsetDateTime.now().minusMinutes(2))) {
            fail(active.id(), "Actions가 종료됐지만 결과 파일이 없습니다. 실행 실패·취소·타임아웃 또는 artifact 업로드 오류를 확인하세요.");
        }
    }

    static boolean matches(JsonNode run, UUID request, String ref) {
        return ("invariant " + request).equals(run.path("display_title").asText())
                && (".github/workflows/" + WORKFLOW).equals(run.path("path").asText())
                && "workflow_dispatch".equals(run.path("event").asText())
                && ref.equals(run.path("head_branch").asText()) && run.path("run_attempt").asInt() == 1;
    }

    private void fail(UUID id, String reason) {
        finish(id, "FAILED", mapper.valueToTree(Map.of("status", "execution_error", "error", reason)));
    }

    private void finish(UUID id, String status, JsonNode result) {
        runs.finish(id, status, result);
    }
}
