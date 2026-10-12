package com.prguard.analysis;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class FindingRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public FindingRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void saveAll(long reviewId, List<Finding> findings) {
        jdbc.sql("DELETE FROM findings WHERE review_id = :reviewId").param("reviewId", reviewId).update();
        for (Finding f : findings) {
            jdbc.sql("""
                            INSERT INTO findings (review_id, rule_id, category, severity, file, line, title, message,
                                                  evidence, fingerprint, source, grounding)
                            VALUES (:reviewId, :ruleId, :category, :severity, :file, :line, :title, :message,
                                    :evidence, :fingerprint, :source, CAST(:grounding AS jsonb))
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
                    .param("grounding", json(f.grounding()))
                    .update();
        }
    }

    public List<StoredFinding> findByReview(long reviewId) {
        return jdbc.sql("""
                        SELECT id, rule_id, category, severity, file, line, title, message, evidence, fingerprint, source,
                               grounding::text AS grounding
                          FROM findings WHERE review_id = :reviewId
                         ORDER BY CASE severity WHEN 'BLOCKER' THEN 0 WHEN 'MAJOR' THEN 1 WHEN 'MINOR' THEN 2 ELSE 3 END, id
                        """)
                .param("reviewId", reviewId)
                .query(StoredFinding.class)
                .list();
    }

    private String json(Grounding grounding) {
        if (grounding == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(grounding);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("근거를 JSON 으로 바꾸지 못했습니다", e);
        }
    }

    /**
     * API 응답용.
     *
     * @param grounding 층별 근거 (JSON 그대로). 근거 확인 전 리뷰면 null
     */
    public record StoredFinding(long id, String ruleId, Category category, Severity severity, String file,
                                Integer line, String title, String message, String evidence, String fingerprint,
                                String source, @JsonRawValue String grounding) {
    }
}
