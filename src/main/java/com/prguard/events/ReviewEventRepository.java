package com.prguard.events;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ReviewEventRepository {

    private final JdbcClient jdbc;

    public ReviewEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public ReviewEvent insert(long reviewId, Stage stage, StageStatus status, String message, String dataJson) {
        return jdbc.sql("""
                        INSERT INTO review_events (review_id, stage, status, message, data)
                        VALUES (:reviewId, :stage, :status, :message, CAST(:data AS jsonb))
                        RETURNING id, review_id, stage, status, message, data::text AS data, at
                        """)
                .param("reviewId", reviewId)
                .param("stage", stage.name())
                .param("status", status.name())
                .param("message", message)
                .param("data", dataJson)
                .query(ReviewEvent.class)
                .single();
    }

    public List<ReviewEvent> findByReview(long reviewId) {
        return jdbc.sql("""
                        SELECT id, review_id, stage, status, message, data::text AS data, at
                          FROM review_events WHERE review_id = :reviewId ORDER BY id
                        """)
                .param("reviewId", reviewId)
                .query(ReviewEvent.class)
                .list();
    }
}
