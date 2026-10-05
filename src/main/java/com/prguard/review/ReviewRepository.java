package com.prguard.review;

import com.prguard.analysis.Verdict;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ReviewRepository {

    private static final String SELECT = """
            SELECT r.id, r.project_id, r.pr_number, r.head_sha, r.run, r.status, r.verdict, r.reviewer, r.summary,
                   r.result, r.error, r.comment_url,
                   (SELECT count(*) FROM findings f WHERE f.review_id = r.id)::int AS finding_count,
                   r.created_at, r.started_at, r.finished_at
              FROM reviews r
            """;

    private final JdbcClient jdbc;

    public ReviewRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 폴링이 새 커밋을 봤을 때. 같은 커밋 리뷰가 이미 있으면 false. */
    public boolean createPending(long projectId, int prNumber, String headSha) {
        return jdbc.sql("""
                        INSERT INTO reviews (project_id, pr_number, head_sha, run, status)
                        VALUES (:projectId, :prNumber, :headSha, 1, 'PENDING')
                        ON CONFLICT (project_id, pr_number, head_sha, run) DO NOTHING
                        """)
                .param("projectId", projectId)
                .param("prNumber", prNumber)
                .param("headSha", headSha)
                .update() > 0;
    }

    /** 같은 커밋을 다시 리뷰한다 (분석 로직이 바뀌었을 때, 평가할 때). */
    public long createRerun(long projectId, int prNumber, String headSha) {
        return jdbc.sql("""
                        INSERT INTO reviews (project_id, pr_number, head_sha, run, status)
                        SELECT :projectId, :prNumber, :headSha, COALESCE(max(run), 0) + 1, 'PENDING'
                          FROM reviews
                         WHERE project_id = :projectId AND pr_number = :prNumber AND head_sha = :headSha
                        RETURNING id
                        """)
                .param("projectId", projectId)
                .param("prNumber", prNumber)
                .param("headSha", headSha)
                .query(Long.class)
                .single();
    }

    /** 새 커밋이 올라오면 아직 시작 안 한 예전 커밋 리뷰는 버린다. */
    public int supersedeOlder(long projectId, int prNumber, String headSha) {
        return jdbc.sql("""
                        UPDATE reviews SET status = 'SUPERSEDED', finished_at = now()
                         WHERE project_id = :projectId AND pr_number = :prNumber
                           AND head_sha <> :headSha AND status = 'PENDING'
                        """)
                .param("projectId", projectId)
                .param("prNumber", prNumber)
                .param("headSha", headSha)
                .update();
    }

    /**
     * 대기 중인 리뷰 하나를 RUNNING 으로 바꾸고 가져온다. 인스턴스가 여러 개여도 SKIP LOCKED 로 겹치지 않는다.
     * RUNNING 인 채로 staleAfter 가 지난 작업(프로세스가 죽은 경우)도 다시 집는다.
     */
    public Optional<Review> claimNext(Duration staleAfter) {
        Optional<Long> id = jdbc.sql("""
                        UPDATE reviews SET status = 'RUNNING', started_at = now(), error = NULL
                         WHERE id = (
                               SELECT id FROM reviews
                                WHERE status = 'PENDING'
                                   OR (status = 'RUNNING' AND started_at < now() - make_interval(secs => :staleSeconds))
                                ORDER BY id
                                LIMIT 1
                                FOR UPDATE SKIP LOCKED)
                        RETURNING id
                        """)
                .param("staleSeconds", staleAfter.toSeconds())
                .query(Long.class)
                .optional();
        return id.flatMap(this::findById);
    }

    public void markDone(long id, Verdict verdict, String reviewer, String summary, String result, String contextJson,
                         String commentUrl) {
        jdbc.sql("""
                        UPDATE reviews SET status = 'DONE', verdict = :verdict, reviewer = :reviewer, summary = :summary,
                                           result = :result, context = CAST(:context AS jsonb),
                                           comment_url = :commentUrl, finished_at = now()
                         WHERE id = :id
                        """)
                .param("id", id)
                .param("verdict", verdict.name())
                .param("reviewer", reviewer)
                .param("summary", summary)
                .param("result", result)
                .param("context", contextJson)
                .param("commentUrl", commentUrl)
                .update();
    }

    public void markFailed(long id, String error) {
        jdbc.sql("UPDATE reviews SET status = 'FAILED', error = :error, finished_at = now() WHERE id = :id")
                .param("id", id)
                .param("error", error)
                .update();
    }

    public void markSuperseded(long id) {
        jdbc.sql("UPDATE reviews SET status = 'SUPERSEDED', finished_at = now() WHERE id = :id")
                .param("id", id)
                .update();
    }

    public List<Review> findByProject(long projectId, int limit) {
        return jdbc.sql(SELECT + " WHERE r.project_id = :projectId ORDER BY r.id DESC LIMIT :limit")
                .param("projectId", projectId)
                .param("limit", limit)
                .query(Review.class)
                .list();
    }

    public Optional<Review> findById(long id) {
        return jdbc.sql(SELECT + " WHERE r.id = :id")
                .param("id", id)
                .query(Review.class)
                .optional();
    }

    /** 분석 재료 요약 (JSON 문자열). */
    public Optional<String> context(long id) {
        return jdbc.sql("SELECT context::text FROM reviews WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .list().stream()
                .filter(Objects::nonNull)
                .findFirst();
    }
}
