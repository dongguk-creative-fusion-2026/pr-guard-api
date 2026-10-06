package com.prguard.graph;

import com.prguard.github.GitHubClient;
import com.prguard.github.GitHubException;
import com.prguard.github.GitHubRepo;
import com.prguard.github.RepoRef;
import com.prguard.project.Project;
import com.prguard.project.ProjectRepository;
import com.prguard.workspace.RepoWorkspace;
import com.prguard.workspace.Snapshot;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 대기 중인 레포 그래프를 하나씩 처리한다.
 * ACTIONS: GitHub Actions 워크플로를 실행하고, 결과는 워크플로가 {@link GraphController} 로 보낸다 (그동안 RUNNING).
 * LOCAL: 기본 브랜치 소스를 꺼내 이 서버에서 GitNexus 를 돌린다.
 */
@Component
public class GraphWorker {

    private static final Logger log = LoggerFactory.getLogger(GraphWorker.class);

    private final RepoGraphRepository graphs;
    private final ProjectRepository projects;
    private final GitHubClient github;
    private final RepoWorkspace workspace;
    private final GraphBuilder builder;
    private final GraphProperties props;

    public GraphWorker(RepoGraphRepository graphs, ProjectRepository projects, GitHubClient github,
                       RepoWorkspace workspace, GraphBuilder builder, GraphProperties props) {
        this.graphs = graphs;
        this.projects = projects;
        this.github = github;
        this.workspace = workspace;
        this.builder = builder;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${prguard.graph.worker-delay}", initialDelayString = "PT15S")
    public void drain() {
        Optional<Long> next;
        while ((next = graphs.claimNext(props.staleAfter())).isPresent()) {
            process(next.get());
        }
    }

    void process(long projectId) {
        Project project = projects.findById(projectId).orElse(null);
        if (project == null) {
            return; // 그 사이 삭제됨 (그래프 행도 같이 지워진다)
        }
        try {
            // 등록 뒤 기본 브랜치가 바뀌었을 수 있어서 다시 묻는다
            GitHubRepo repo = github.getRepo(project.ref());
            if (props.runner() == GraphProperties.Runner.ACTIONS) {
                dispatch(project, repo.defaultBranch());
            } else {
                buildLocally(project, repo);
            }
        } catch (RuntimeException e) {
            log.warn("레포 그래프 실패 {}: {}", project.ref().fullName(), e.getMessage());
            graphs.markFailed(projectId, e.getMessage());
        }
    }

    private void dispatch(Project project, String branch) {
        RepoRef workflowRepo = RepoRef.parse(props.workflowRepo());
        try {
            github.dispatchWorkflow(workflowRepo, props.workflow(), props.workflowRef(), Map.of(
                    "project_id", String.valueOf(project.id()),
                    "repo", project.ref().fullName(),
                    "branch", branch), props.dispatchToken());
        } catch (GitHubException e) {
            if (e.status() == 401 || e.status() == 403 || e.status() == 404) {
                throw new GraphException("그래프 워크플로를 실행할 권한이 없습니다 (" + e.status() + "). "
                        + props.workflowRepo() + " 에 Actions 쓰기 권한이 있는 토큰을 GRAPH_DISPATCH_TOKEN 에 넣어야 합니다");
            }
            throw e;
        }
        graphs.appendProgress(project.id(), "dispatched", "GitHub Actions 에 분석 요청", null);
        log.info("레포 그래프 워크플로 실행 {} ({})", project.ref().fullName(), branch);
    }

    private void buildLocally(Project project, GitHubRepo repo) {
        long started = System.currentTimeMillis();
        Snapshot snapshot = workspace.checkoutBranch(project.ref(), repo.size(), repo.defaultBranch());
        graphs.appendProgress(project.id(), "cloned", "레포 받기 완료", null);
        try {
            graphs.markDone(project.id(), snapshot.sha(), builder.build(snapshot.dir()));
            log.info("레포 그래프 완료 {} {} {}ms", project.ref().fullName(), snapshot.sha().substring(0, 7),
                    System.currentTimeMillis() - started);
        } finally {
            workspace.release(snapshot);
        }
    }
}
