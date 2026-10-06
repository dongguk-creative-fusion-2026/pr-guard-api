package com.prguard.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.prguard.common.ApiException;
import com.prguard.github.GitHubClient;
import com.prguard.github.GitHubException;
import com.prguard.github.GitHubWorkflowRun;
import com.prguard.github.RepoRef;
import com.prguard.project.ProjectService;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{id}/graph")
public class GraphController {

    private final RepoGraphRepository graphs;
    private final ProjectService projects;
    private final GitHubClient github;
    private final GraphProperties props;

    public GraphController(RepoGraphRepository graphs, ProjectService projects, GitHubClient github,
                           GraphProperties props) {
        this.graphs = graphs;
        this.projects = projects;
        this.github = github;
        this.props = props;
    }

    @GetMapping
    public RepoGraph get(@PathVariable long id) {
        projects.get(id);
        return graphs.find(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "GRAPH_NOT_FOUND", "그래프가 없습니다: " + id));
    }

    /** 기본 브랜치 최신 커밋으로 다시 만든다. */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RepoGraph rebuild(@PathVariable long id) {
        projects.get(id);
        graphs.enqueue(id);
        return get(id);
    }

    /**
     * 그래프 워크플로(.github/workflows/repo-graph.yml)가 결과를 보낸다.
     * 따로 비밀값을 두지 않고, runId 가 이 프로젝트용으로 실행 중인 우리 워크플로인지 GitHub 에 물어 확인한다.
     */
    @PostMapping("/result")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void result(@PathVariable long id, @Validated @RequestBody GraphResult result) {
        verifyRun(id, result.runId());
        boolean saved = result.graph() != null && result.commitSha() != null
                ? graphs.markDone(id, result.commitSha(), result.graph().toString())
                : graphs.markFailed(id, result.error() == null ? "워크플로가 결과 없이 끝났습니다" : result.error());
        if (!saved) {
            throw new ApiException(HttpStatus.CONFLICT, "GRAPH_NOT_RUNNING", "만드는 중인 그래프가 아닙니다: " + id);
        }
    }

    /** 그래프 워크플로가 진행 단계를 알린다 (등록 화면에서 실시간으로 보여 준다). */
    @PostMapping("/progress")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void progress(@PathVariable long id, @Validated @RequestBody GraphProgress progress) {
        GitHubWorkflowRun run = verifyRun(id, progress.runId());
        graphs.setRunUrl(id, run.htmlUrl());
        graphs.appendProgress(id, progress.stage(), progress.message(),
                progress.data() == null ? null : progress.data().toString());
    }

    private GitHubWorkflowRun verifyRun(long projectId, long runId) {
        GitHubWorkflowRun run;
        try {
            run = github.getWorkflowRun(RepoRef.parse(props.workflowRepo()), runId);
        } catch (GitHubException e) {
            throw new ApiException(HttpStatus.FORBIDDEN, "GRAPH_RUN_INVALID", "워크플로 실행을 찾을 수 없습니다: " + runId);
        }
        boolean ours = (".github/workflows/" + props.workflow()).equals(run.path())
                && "workflow_dispatch".equals(run.event())
                && ("graph " + projectId).equals(run.displayTitle())
                && !"completed".equals(run.status());
        if (!ours) {
            throw new ApiException(HttpStatus.FORBIDDEN, "GRAPH_RUN_INVALID",
                    "이 프로젝트의 실행 중인 그래프 워크플로가 아닙니다: " + runId);
        }
        return run;
    }

    /**
     * @param stage   started, cloned, indexed …
     * @param data    단계 수치 (파일 수 등)
     */
    public record GraphProgress(@NotNull Long runId, @NotNull String stage, String message, JsonNode data) {
    }

    /**
     * @param commitSha 그래프를 만든 커밋 (성공일 때)
     * @param graph     export.mjs 결과 (성공일 때)
     * @param error     실패 이유 (실패일 때)
     */
    public record GraphResult(@NotNull Long runId, String commitSha, JsonNode graph, String error) {
    }
}
