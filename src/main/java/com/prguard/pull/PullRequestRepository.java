package com.prguard.pull;

import com.prguard.github.GitHubPull;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PullRequestRepository {

    private static final String SELECT = """
            SELECT pr.id, pr.project_id, pr.number, pr.title, pr.author, pr.html_url, pr.head_sha,
                   pr.head_ref, pr.base_ref, pr.state, pr.draft, pr.comment_id, pr.updated_at,
                   r.id AS latest_review_id, r.status AS latest_review_status
              FROM pull_requests pr
              LEFT JOIN LATERAL (
                   SELECT id, status FROM reviews
                    WHERE project_id = pr.project_id AND pr_number = pr.number
                    ORDER BY id DESC LIMIT 1) r ON true
            """;

    private final JdbcClient jdbc;

    public PullRequestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<PullRequest> findByProject(long projectId) {
        return jdbc.sql(SELECT + " WHERE pr.project_id = :projectId ORDER BY pr.state DESC, pr.number DESC")
                .param("projectId", projectId)
                .query(PullRequest.class)
                .list();
    }

    public Optional<PullRequest> find(long projectId, int number) {
        return jdbc.sql(SELECT + " WHERE pr.project_id = :projectId AND pr.number = :number")
                .param("projectId", projectId)
                .param("number", number)
                .query(PullRequest.class)
                .optional();
    }

    public void upsertOpen(long projectId, GitHubPull pull) {
        jdbc.sql("""
                        INSERT INTO pull_requests (project_id, number, title, body, author, html_url, head_sha,
                                                   head_ref, base_ref, state, draft, updated_at)
                        VALUES (:projectId, :number, :title, :body, :author, :htmlUrl, :headSha,
                                :headRef, :baseRef, 'open', :draft, now())
                        ON CONFLICT (project_id, number) DO UPDATE SET
                            title = EXCLUDED.title, body = EXCLUDED.body, author = EXCLUDED.author,
                            html_url = EXCLUDED.html_url,
                            head_sha = EXCLUDED.head_sha, head_ref = EXCLUDED.head_ref,
                            base_ref = EXCLUDED.base_ref, state = 'open', draft = EXCLUDED.draft,
                            updated_at = now()
                        """)
                .param("projectId", projectId)
                .param("number", pull.number())
                .param("title", pull.title())
                .param("body", pull.body())
                .param("author", pull.user() == null ? "unknown" : pull.user().login())
                .param("htmlUrl", pull.htmlUrl())
                .param("headSha", pull.head().sha())
                .param("headRef", pull.head().ref())
                .param("baseRef", pull.base().ref())
                .param("draft", pull.draft())
                .update();
    }

    /** 열린 목록에서 사라진 PR 은 닫힘(merge 포함)으로 본다. */
    public int closeMissing(long projectId, Collection<Integer> openNumbers) {
        Integer[] numbers = openNumbers.toArray(Integer[]::new);
        return jdbc.sql("""
                        UPDATE pull_requests SET state = 'closed', updated_at = now()
                         WHERE project_id = :projectId AND state = 'open' AND NOT (number = ANY(:numbers))
                        """)
                .param("projectId", projectId)
                .param("numbers", numbers)
                .update();
    }

    public void setCommentId(long id, long commentId) {
        jdbc.sql("UPDATE pull_requests SET comment_id = :commentId WHERE id = :id")
                .param("id", id)
                .param("commentId", commentId)
                .update();
    }
}
