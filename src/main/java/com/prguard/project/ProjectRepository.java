package com.prguard.project;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ProjectRepository {

    private static final String SELECT = """
            SELECT p.id, p.owner, p.name, p.html_url, p.default_branch,
                   (SELECT count(*) FROM pull_requests pr
                     WHERE pr.project_id = p.id AND pr.state = 'open')::int AS open_pull_count,
                   p.last_polled_at, p.last_poll_error, p.created_at,
                   p.comment_enabled, p.major_threshold, p.onboarded_at
              FROM projects p
            """;

    private final JdbcClient jdbc;

    public ProjectRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Project> findAll() {
        return jdbc.sql(SELECT + " ORDER BY p.id").query(Project.class).list();
    }

    public Optional<Project> findById(long id) {
        return jdbc.sql(SELECT + " WHERE p.id = :id").param("id", id).query(Project.class).optional();
    }

    /** 이미 등록된 레포면 빈 값. */
    public Optional<Long> insert(String owner, String name, String htmlUrl, String defaultBranch) {
        return jdbc.sql("""
                        INSERT INTO projects (owner, name, html_url, default_branch)
                        VALUES (:owner, :name, :htmlUrl, :defaultBranch)
                        ON CONFLICT (owner, name) DO NOTHING
                        RETURNING id
                        """)
                .param("owner", owner)
                .param("name", name)
                .param("htmlUrl", htmlUrl)
                .param("defaultBranch", defaultBranch)
                .query(Long.class)
                .optional();
    }

    public Optional<Long> findIdByRepo(String owner, String name) {
        return jdbc.sql("SELECT id FROM projects WHERE lower(owner) = lower(:owner) AND lower(name) = lower(:name)")
                .param("owner", owner)
                .param("name", name)
                .query(Long.class)
                .optional();
    }

    public void updateSettings(long id, boolean commentEnabled, Integer majorThreshold) {
        jdbc.sql("UPDATE projects SET comment_enabled = :comment, major_threshold = CAST(:threshold AS INT) WHERE id = :id")
                .param("id", id)
                .param("comment", commentEnabled)
                .param("threshold", majorThreshold)
                .update();
    }

    public void markOnboarded(long id) {
        jdbc.sql("UPDATE projects SET onboarded_at = COALESCE(onboarded_at, now()) WHERE id = :id").param("id", id).update();
    }

    public boolean delete(long id) {
        return jdbc.sql("DELETE FROM projects WHERE id = :id").param("id", id).update() > 0;
    }

    public String pullsEtag(long id) {
        return jdbc.sql("SELECT pulls_etag FROM projects WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    public void markPolled(long id, String etag) {
        jdbc.sql("UPDATE projects SET pulls_etag = :etag, last_polled_at = now(), last_poll_error = NULL WHERE id = :id")
                .param("id", id)
                .param("etag", etag)
                .update();
    }

    public void markPollFailed(long id, String error) {
        jdbc.sql("UPDATE projects SET last_polled_at = now(), last_poll_error = :error WHERE id = :id")
                .param("id", id)
                .param("error", error)
                .update();
    }
}
