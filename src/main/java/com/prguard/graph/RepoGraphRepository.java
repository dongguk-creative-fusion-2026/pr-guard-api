package com.prguard.graph;

import java.time.Duration;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RepoGraphRepository {

    private static final String SELECT = """
            SELECT project_id, status, commit_sha, graph::text AS graph, error, created_at, started_at, finished_at
              FROM repo_graphs
            """;

    private final JdbcClient jdbc;

    public RepoGraphRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<RepoGraph> find(long projectId) {
        return jdbc.sql(SELECT + " WHERE project_id = :projectId")
                .param("projectId", projectId)
                .query(RepoGraph.class)
                .optional();
    }

    /** 그래프를 (다시) 만들도록 대기열에 넣는다. 이미 대기 중이거나 만드는 중이면 그대로 둔다. */
    public void enqueue(long projectId) {
        jdbc.sql("""
                        INSERT INTO repo_graphs (project_id, status) VALUES (:projectId, 'PENDING')
                        ON CONFLICT (project_id) DO UPDATE SET status = 'PENDING', error = NULL, created_at = now()
                         WHERE repo_graphs.status NOT IN ('PENDING', 'RUNNING')
                        """)
                .param("projectId", projectId)
                .update();
    }

    /**
     * 대기 중인 그래프 하나를 RUNNING 으로 바꾸고 프로젝트 id 를 돌려준다.
     * RUNNING 인 채로 staleAfter 가 지난 작업(프로세스가 죽은 경우)도 다시 집는다.
     */
    public Optional<Long> claimNext(Duration staleAfter) {
        return jdbc.sql("""
                        UPDATE repo_graphs SET status = 'RUNNING', started_at = now(), error = NULL
                         WHERE project_id = (
                               SELECT project_id FROM repo_graphs
                                WHERE status = 'PENDING'
                                   OR (status = 'RUNNING' AND started_at < now() - make_interval(secs => :staleSeconds))
                                ORDER BY created_at
                                LIMIT 1
                                FOR UPDATE SKIP LOCKED)
                        RETURNING project_id
                        """)
                .param("staleSeconds", staleAfter.toSeconds())
                .query(Long.class)
                .optional();
    }

    public void markDone(long projectId, String commitSha, String graphJson) {
        jdbc.sql("""
                        UPDATE repo_graphs SET status = 'DONE', commit_sha = :sha, graph = CAST(:graph AS jsonb),
                                               error = NULL, finished_at = now()
                         WHERE project_id = :projectId
                        """)
                .param("projectId", projectId)
                .param("sha", commitSha)
                .param("graph", graphJson)
                .update();
    }

    /** 실패해도 예전에 만든 그래프는 남겨 둔다. */
    public void markFailed(long projectId, String error) {
        jdbc.sql("UPDATE repo_graphs SET status = 'FAILED', error = :error, finished_at = now() WHERE project_id = :projectId")
                .param("projectId", projectId)
                .param("error", error)
                .update();
    }
}
