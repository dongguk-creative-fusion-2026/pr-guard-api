package com.prguard.analysis;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class FindingRepository {

    private final JdbcClient jdbc;

    public FindingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void saveAll(long reviewId, List<Finding> findings) {
        jdbc.sql("DELETE FROM findings WHERE review_id = :reviewId").param("reviewId", reviewId).update();
        for (Finding f : findings) {
            jdbc.sql("""
                            INSERT INTO findings (review_id, rule_id, category, severity, file, line, title, message,
                                                  evidence, fingerprint, source)
                            VALUES (:reviewId, :ruleId, :category, :severity, :file, :line, :title, :message,
                                    :evidence, :fingerprint, :source)
                            """)
                    .param("reviewId", reviewId)
                    .param("ruleId", f.ruleId())
                    .param("category", f.category().name())
                    .param("severity", f.severity().name())
                    .param("file", f.file())
                    .param("line", f.line())
                    .param("title", f.title())
                    .param("message", f.message())
                    .param("evidence", f.evidence())
                    .param("fingerprint", f.fingerprint())
                    .param("source", f.source())
                    .update();
        }
    }

    public List<StoredFinding> findByReview(long reviewId) {
        return jdbc.sql("""
                        SELECT id, rule_id, category, severity, file, line, title, message, evidence, fingerprint, source
                          FROM findings WHERE review_id = :reviewId
                         ORDER BY CASE severity WHEN 'BLOCKER' THEN 0 WHEN 'MAJOR' THEN 1 WHEN 'MINOR' THEN 2 ELSE 3 END, id
                        """)
                .param("reviewId", reviewId)
                .query(StoredFinding.class)
                .list();
    }

    /** API 응답용. */
    public record StoredFinding(long id, String ruleId, Category category, Severity severity, String file,
                                Integer line, String title, String message, String evidence, String fingerprint,
                                String source) {
    }
}
