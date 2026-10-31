package com.prguard.sonar;

import com.prguard.common.ApiException;
import com.prguard.github.GitHubClient;
import com.prguard.github.GitHubException;
import com.prguard.github.GitHubWorkflowRun;
import com.prguard.github.RepoRef;
import com.prguard.graph.GraphProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * GitHub Actions 스캔 워크플로(.github/workflows/sonar.yml)가 부르는 곳.
 * runId 로 우리 레포의 실행 중인 그 워크플로이고 run-name 이 "sonar {headSha}" 인지 GitHub 에 확인한 뒤에만 받는다.
 */
@RestController
public class SonarController {

    private static final int MAX_TASK = 100;
    private static final int MAX_ERROR = 300;

    private final SonarService sonar;
    private final SonarProperties props;
    private final GitHubClient github;
    private final GraphProperties workflows;

    public SonarController(SonarService sonar, SonarProperties props, GitHubClient github, GraphProperties workflows) {
        this.sonar = sonar;
        this.props = props;
        this.github = github;
        this.workflows = workflows;
    }

    public record TokenRequest(@NotNull Long runId, @NotBlank String headSha) {
    }

    /** 스캔 직전에 한 번만 주는 SonarCloud 토큰. 기다리는 스캔이 없거나 이미 가져갔으면 204 */
    @PostMapping("/api/sonar/token")
    public ResponseEntity<String> token(@Validated @RequestBody TokenRequest request) {
        verifyRun(request.runId(), request.headSha());
        if (!sonar.waitingForActions(request.headSha())) {
            return ResponseEntity.noContent().build();
        }
        return sonar.takeToken(request.headSha())
                .map(t -> ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(t))
                .orElse(ResponseEntity.noContent().build());
    }

    /**
     * @param task  SonarCloud 처리 작업 id (스캔 성공)
     * @param error 스캔하지 못한 이유
     */
    public record ScanResult(@NotNull Long runId, @NotBlank String headSha, String task, String error) {
    }

    @PostMapping("/api/sonar/result")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void result(@Validated @RequestBody ScanResult result) {
        verifyRun(result.runId(), result.headSha());
        if (sonar.waitingForActions(result.headSha())) {
            String error = result.task() == null && result.error() == null ? "스캔 결과가 비어 있음" : result.error();
            sonar.reported(result.headSha(), limit(result.task(), MAX_TASK), limit(error, MAX_ERROR));
        }
    }

    private void verifyRun(long runId, String headSha) {
        GitHubWorkflowRun run;
        try {
            run = github.getWorkflowRun(RepoRef.parse(workflows.workflowRepo()), runId);
        } catch (GitHubException e) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SONAR_RUN_INVALID", "워크플로 실행을 찾을 수 없습니다: " + runId);
        }
        boolean ours = (".github/workflows/" + props.workflow()).equals(run.path())
                && "workflow_dispatch".equals(run.event())
                && ("sonar " + headSha).equals(run.displayTitle())
                && !"completed".equals(run.status());
        if (!ours) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SONAR_RUN_INVALID",
                    "이 커밋의 실행 중인 SonarCloud 스캔 워크플로가 아닙니다: " + runId);
        }
    }

    private static String limit(String text, int max) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return text.length() > max ? text.substring(0, max) : text;
    }
}
