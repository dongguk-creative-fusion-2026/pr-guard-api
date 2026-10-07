package com.prguard.agents;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * 리뷰에서 반복된 실수 → AGENTS.md 규칙 제안 (피드백 루프).
 *
 * PR 마다 가장 최근에 끝난 리뷰의 지적만 본다 (같은 PR 을 다시 리뷰한 것을 두 번 세지 않는다).
 * 지적을 {@link MistakeType} 으로 묶고, 서로 다른 PR 몇 개에서 나왔는지로 반복 여부를 정한다.
 */
@Service
public class LessonService {

    /** 이만큼의 PR 에서 나오면 반복된 실수로 보고 규칙을 제안한다 */
    static final int REPEATED_PRS = 2;
    private static final int EXAMPLES = 4;

    private final JdbcClient jdbc;

    public LessonService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Row(int prNumber, long reviewId, String ruleId, String severity, String file, String title, String message) {
    }

    /**
     * @param prs      이 유형이 나온 PR 번호들
     * @param repeated 서로 다른 PR {@value #REPEATED_PRS}개 이상에서 나옴
     * @param worst    가장 높은 심각도
     */
    public record Lesson(String type, String label, String rule, List<Integer> prs, int findings, boolean repeated,
                         String worst, List<Example> examples) {
    }

    public record Example(int prNumber, long reviewId, String severity, String file, String title) {
    }

    public List<Lesson> lessons(long projectId) {
        List<Row> rows = jdbc.sql("""
                        WITH latest AS (
                            SELECT DISTINCT ON (pr_number) id, pr_number
                              FROM reviews
                             WHERE project_id = :projectId AND status = 'DONE'
                             ORDER BY pr_number, id DESC
                        )
                        SELECT l.pr_number, l.id AS review_id, f.rule_id, f.severity, f.file, f.title, f.message
                          FROM latest l JOIN findings f ON f.review_id = l.id
                         ORDER BY l.pr_number DESC, f.id
                        """)
                .param("projectId", projectId)
                .query(Row.class)
                .list();
        return group(rows);
    }

    static List<Lesson> group(List<Row> rows) {
        Map<MistakeType, List<Row>> byType = new EnumMap<>(MistakeType.class);
        for (Row r : rows) {
            MistakeType t = MistakeType.of(r.ruleId(), r.title(), r.message());
            if (t != null) {
                byType.computeIfAbsent(t, k -> new ArrayList<>()).add(r);
            }
        }
        List<Lesson> lessons = new ArrayList<>();
        byType.forEach((t, list) -> {
            Set<Integer> prs = new LinkedHashSet<>();
            list.forEach(r -> prs.add(r.prNumber()));
            String worst = list.stream().map(Row::severity).min(Comparator.comparingInt(LessonService::rank)).orElse("MINOR");
            List<Example> examples = new ArrayList<>();
            Set<Integer> shown = new LinkedHashSet<>();
            // 예시는 PR 마다 하나씩
            for (Row r : list) {
                if (examples.size() < EXAMPLES && shown.add(r.prNumber())) {
                    examples.add(new Example(r.prNumber(), r.reviewId(), r.severity(), r.file(), r.title()));
                }
            }
            lessons.add(new Lesson(t.name(), t.label(), t.rule(), List.copyOf(prs), list.size(),
                    prs.size() >= REPEATED_PRS, worst, examples));
        });
        lessons.sort(Comparator.comparing((Lesson l) -> !l.repeated())
                .thenComparing(l -> -l.prs().size())
                .thenComparingInt(l -> rank(l.worst())));
        return lessons;
    }

    private static int rank(String severity) {
        return switch (severity) {
            case "BLOCKER" -> 0;
            case "MAJOR" -> 1;
            case "MINOR" -> 2;
            default -> 3;
        };
    }
}
