package com.prguard.graph;

import com.prguard.github.GitHubClient;
import com.prguard.github.GitHubRepo;
import com.prguard.project.Project;
import com.prguard.project.ProjectRepository;
import com.prguard.workspace.RepoWorkspace;
import com.prguard.workspace.Snapshot;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 대기 중인 레포 그래프를 하나씩 만든다: 기본 브랜치 소스 꺼내기 → GitNexus → 저장. */
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
        long started = System.currentTimeMillis();
        try {
            // 등록 뒤 기본 브랜치가 바뀌었을 수 있어서 다시 묻는다
            GitHubRepo repo = github.getRepo(project.ref());
            Snapshot snapshot = workspace.checkoutBranch(project.ref(), repo.size(), repo.defaultBranch());
            try {
                graphs.markDone(projectId, snapshot.sha(), builder.build(snapshot.dir()));
                log.info("레포 그래프 완료 {} {} {}ms", project.ref().fullName(), snapshot.sha().substring(0, 7),
                        System.currentTimeMillis() - started);
            } finally {
                workspace.release(snapshot);
            }
        } catch (RuntimeException e) {
            log.warn("레포 그래프 실패 {}: {}", project.ref().fullName(), e.getMessage());
            graphs.markFailed(projectId, e.getMessage());
        }
    }
}
