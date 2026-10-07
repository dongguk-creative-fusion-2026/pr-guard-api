package com.prguard.execution;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TestRunRepository {

    private static final String SELECT = """
            SELECT id, review_id, side, sha, status, token_hash, runner_name, phase, message, exit_code,
                   tests, failures, errors, skipped, results::text AS results, log_tail, error, created_at, finished_at
              FROM test_runs
            """;

    private final JdbcClient jdbc;

    public TestRunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long create(Long reviewId, String side, String sha, String tokenHash) {
        return jdbc.sql("""
                        INSERT INTO test_runs (review_id, side, sha, status, token_hash)
                        VALUES (:reviewId, :side, :sha, 'QUEUED', :tokenHash)
                        RETURNING id
                        """)
                .param("reviewId", reviewId)
                .param("side", side)
                .param("sha", sha)
                .param("tokenHash", tokenHash)
                .query(Long.class)
                .single();
    }

    public Optional<TestRun> find(long id) {
        return jdbc.sql(SELECT + " WHERE id = :id").param("id", id).query(TestRun.class).optional();
    }

    public List<TestRun> findByReview(long reviewId) {
        return jdbc.sql(SELECT + " WHERE review_id = :reviewId ORDER BY id").param("reviewId", reviewId)
                .query(TestRun.class).list();
    }

    public void started(long id, String runnerName) {
        jdbc.sql("UPDATE test_runs SET status = 'STARTED', runner_name = :name WHERE id = :id")
                .param("id", id)
                .param("name", runnerName)
                .update();
    }

    /** 러너가 알린 진행 단계. 끝난 실행은 바꾸지 않는다. */
    public boolean progress(long id, String phase, String message) {
        return jdbc.sql("UPDATE test_runs SET phase = :phase, message = :message WHERE id = :id AND status = 'STARTED'")
                .param("id", id)
                .param("phase", phase)
                .param("message", message)
                .update() > 0;
    }

    public boolean complete(long id, int exitCode, int tests, int failures, int errors, int skipped, String resultsJson,
                            String logTail) {
        return jdbc.sql("""
                        UPDATE test_runs SET status = 'DONE', exit_code = :exitCode, tests = :tests, failures = :failures,
                                             errors = :errors, skipped = :skipped, results = CAST(:results AS jsonb),
                                             log_tail = :logTail, phase = 'reported', finished_at = now()
                         WHERE id = :id AND status = 'STARTED'
                        """)
                .param("id", id)
                .param("exitCode", exitCode)
                .param("tests", tests)
                .param("failures", failures)
                .param("errors", errors)
                .param("skipped", skipped)
                .param("results", resultsJson)
                .param("logTail", logTail)
                .update() > 0;
    }

    public boolean fail(long id, String error, Integer exitCode, String logTail) {
        return jdbc.sql("""
                        UPDATE test_runs SET status = 'FAILED', error = :error, exit_code = :exitCode,
                                             log_tail = COALESCE(:logTail, log_tail), finished_at = now()
                         WHERE id = :id AND status IN ('QUEUED', 'STARTED')
                        """)
                .param("id", id)
                .param("error", error)
                .param("exitCode", exitCode)
                .param("logTail", logTail)
                .update() > 0;
    }
}
