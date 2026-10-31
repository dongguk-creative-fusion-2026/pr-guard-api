package com.prguard.pull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence for dispatch, restart recovery and terminal result transitions. */
@Repository
public class InvariantRunRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public InvariantRunRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    record Active(UUID id, String headSha, String baseSha, String workflowRepo, String workflowRef,
                  Long workflowRunId, OffsetDateTime createdAt, OffsetDateTime completedSeenAt) {}

    public void create(UUID run, long project, int number, String head, String base, String candidate, String repo, String ref) {
        jdbc.sql("INSERT INTO invariant_runs(id,project_id,pr_number,head_sha,base_sha,candidate,status,workflow_repo,workflow_ref) VALUES (:run,:id,:number,:head,:base,CAST(:candidate AS jsonb),'QUEUED',:repo,:ref)")
                .param("run", run).param("id", project).param("number", number).param("head", head).param("base", base)
                .param("candidate", candidate).param("repo", repo).param("ref", ref).update();
    }

    public Optional<JsonNode> find(long project, int number, UUID run) {
        return jdbc.sql("SELECT json_build_object('id',id,'status',status,'headSha',head_sha,'baseSha',base_sha,'runUrl',run_url,'result',result)::text FROM invariant_runs WHERE id=:run AND project_id=:id AND pr_number=:number")
                .param("run", run).param("id", project).param("number", number).query(String.class).optional().map(this::tree);
    }

    List<Active> active() {
        return jdbc.sql("SELECT id,head_sha,base_sha,workflow_repo,workflow_ref,workflow_run_id,created_at,completed_seen_at FROM invariant_runs WHERE status IN ('QUEUED','RUNNING')")
                .query(Active.class).list();
    }

    void track(UUID id, long workflow, String url, String state) {
        jdbc.sql("UPDATE invariant_runs SET workflow_run_id=:workflow,run_url=:url,status=:state WHERE id=:id AND status IN ('QUEUED','RUNNING')")
                .param("workflow", workflow).param("url", url).param("state", state).param("id", id).update();
    }

    void waitForArtifact(UUID id) {
        jdbc.sql("UPDATE invariant_runs SET completed_seen_at=COALESCE(completed_seen_at,now()) WHERE id=:id")
                .param("id", id).update();
    }

    void finish(UUID id, String status, JsonNode result) {
        jdbc.sql("UPDATE invariant_runs SET status=:status,result=CAST(:result AS jsonb),finished_at=now() WHERE id=:id AND status IN ('QUEUED','RUNNING')")
                .param("status", status).param("result", result.toString()).param("id", id).update();
    }

    private JsonNode tree(String text) {
        try { return mapper.readTree(text); }
        catch (Exception e) { throw new IllegalStateException("Invalid stored invariant run", e); }
    }
}
