package com.prguard.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prguard.evidence.EvidenceTest;
import com.prguard.execution.BehaviorDiff.Observation;
import com.prguard.execution.BehaviorDiff.Row;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class BehaviorDiffTest {

    private static final EvidenceTest PROBE = new EvidenceTest("src/test/java/a/PrGuardProbe1Test.java", "a.PrGuardProbe1Test",
            "a.PostService#getPost(Long)", "", "");

    @Test
    void parse_skipsBrokenLines() {
        byte[] jsonl = """
                {"probe":"PrGuardProbe1Test","label":"getPost(999)","out":"throws NotFoundException"}
                {"probe":"PrGuardProbe1Test","label":"getPost(1)",
                """.getBytes(StandardCharsets.UTF_8);

        List<Observation> obs = BehaviorDiff.parse(List.of(jsonl), new ObjectMapper());

        assertThat(obs).containsExactly(new Observation("PrGuardProbe1Test", "getPost(999)", "throws NotFoundException"));
    }

    @Test
    void compare_matchesByProbeAndLabel() {
        List<Observation> base = List.of(
                new Observation("PrGuardProbe1Test", "getPost(999) — 없는 글", "throws NotFoundException"),
                new Observation("PrGuardProbe1Test", "getPost(1)", "Post{title=\"a\"}"),
                new Observation("PrGuardProbe1Test", "getPost(-1)", "throws IllegalArgumentException"));
        List<Observation> head = List.of(
                new Observation("PrGuardProbe1Test", "getPost(999) — 없는 글", "null"),
                new Observation("PrGuardProbe1Test", "getPost(1)", "Post{title=\"a\"}"));

        List<Row> rows = BehaviorDiff.compare(List.of(PROBE), base, head);

        assertThat(rows).extracting(Row::label).containsExactly("getPost(999) — 없는 글", "getPost(1)", "getPost(-1)");
        assertThat(rows).extracting(Row::changed).containsExactly(true, false, false);
        assertThat(rows.get(0).target()).isEqualTo("a.PostService#getPost(Long)");
        assertThat(rows.get(2).head()).isNull();
        assertThat(BehaviorDiff.changedByTarget(rows)).containsOnlyKeys("a.PostService#getPost(Long)");
    }
}
