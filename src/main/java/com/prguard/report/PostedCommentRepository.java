package com.prguard.report;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** PR 에 이미 라인 코멘트로 단 지적 (fingerprint 기준). */
@Repository
public class PostedCommentRepository {

    private final JdbcClient jdbc;

    public PostedCommentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Set<String> fingerprints(long pullRequestId) {
        return new HashSet<>(jdbc.sql("SELECT fingerprint FROM posted_comments WHERE pull_request_id = :id")
                .param("id", pullRequestId)
                .query(String.class)
                .list());
    }

    public void saveAll(long pullRequestId, Collection<String> fingerprints) {
        for (String fp : fingerprints) {
            jdbc.sql("""
                            INSERT INTO posted_comments (pull_request_id, fingerprint) VALUES (:id, :fp)
                            ON CONFLICT (pull_request_id, fingerprint) DO NOTHING
                            """)
                    .param("id", pullRequestId)
                    .param("fp", fp)
                    .update();
        }
    }
}
