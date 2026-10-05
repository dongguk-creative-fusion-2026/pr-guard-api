package com.prguard.review;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ReviewRepository {

    private static final String COLUMNS = """
            id, project_id, pr_number, head_sha, status, reviewer, result, error, comment_url,
            created_at, started_at, finished_at
            """;

    private final JdbcClient jdbc;

    public ReviewRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 같은 커밋에 대한 리뷰가 이미 있으면 false. */
    public boolean createPending(long projectId, int prNumber, String headSha) {
        return jdbc.sql("""
                        INSERT INTO reviews (project_id, pr_number, head_sha, status)
                        VALUES (:projectId, :prNumber, :headSha, 'PENDING')
                        ON CONFLICT (project_id, pr_number, head_sha) DO NOTHING
                        """)
                .param("projectId", projectId)
                .param("prNumber", prNumber)
                .param("headSha", headSha)
                .update() > 0;
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
        return jdbc.sql("""
                        UPDATE reviews SET status = 'RUNNING', started_at = now(), error = NULL
                         WHERE id = (
                               SELECT id FROM reviews
                                WHERE status = 'PENDING'
                                   OR (status = 'RUNNING' AND started_at < now() - make_interval(secs => :staleSeconds))
                                ORDER BY id
                                LIMIT 1
                                FOR UPDATE SKIP LOCKED)
                        RETURNING
                        """ + COLUMNS)
                .param("staleSeconds", staleAfter.toSeconds())
                .query(Review.class)
                .optional();
    }

    public void markDone(long id, String reviewer, String result, String commentUrl) {
        jdbc.sql("""
                        UPDATE reviews SET status = 'DONE', reviewer = :reviewer, result = :result,
                                           comment_url = :commentUrl, finished_at = now()
                         WHERE id = :id
                        """)
                .param("id", id)
                .param("reviewer", reviewer)
                .param("result", result)
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
        return jdbc.sql("SELECT " + COLUMNS + " FROM reviews WHERE project_id = :projectId ORDER BY id DESC LIMIT :limit")
                .param("projectId", projectId)
                .param("limit", limit)
                .query(Review.class)
                .list();
    }

    public Optional<Review> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM reviews WHERE id = :id")
                .param("id", id)
                .query(Review.class)
                .optional();
    }
}
