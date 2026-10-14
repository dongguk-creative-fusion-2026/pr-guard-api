package com.prguard.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.prguard.analysis.Finding;
import com.prguard.analysis.Severity;
import com.prguard.execution.MutationReport.MethodScore;
import com.prguard.execution.MutationReport.Mutant;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** pit/mutations.xml: sandbox 의 PostService 에 PIT 를 실제로 돌린 결과 (WSL 러너 시험대) */
class MutationReportTest {

    private static final String SERVICE = "com.example.board.service.PostService";
    private static final String FILE = "src/main/java/com/example/board/service/PostService.java";
    /** update 42~48줄, delete 50~55줄 (@Transactional 포함) */
    private static final MutationPlan PLAN = new MutationPlan(List.of(SERVICE),
            List.of("com.example.board.service.PostServiceTest"),
            List.of(new MutationPlan.Target("PostService#update", SERVICE, "update", FILE, 42, 48),
                    new MutationPlan.Target("PostService#delete", SERVICE, "delete", FILE, 50, 55)),
            300);

    private static List<Mutant> realReport() {
        InputStream xml = MutationReportTest.class.getResourceAsStream("/pit/mutations.xml");
        return MutationReport.parse(List.of(xml), PLAN);
    }

    @Test
    void parse_keepsOnlyMutantsInChangedMethods_withMethodLevelKillers() {
        List<Mutant> mutants = realReport();

        assertThat(mutants).isNotEmpty().allMatch(m -> m.line() >= 42 && m.line() <= 55);
        // 권한 검사 호출을 지운 변이는 PR #18 이 지운 바로 그 테스트가 잡는다
        assertThat(mutants).anySatisfy(m -> {
            assertThat(m.target()).isEqualTo("PostService#delete");
            assertThat(m.description()).contains("requireOwner");
            assertThat(m.killed()).isTrue();
            assertThat(m.killers()).contains("com.example.board.service.PostServiceTest#delete_byNonOwner_doesNotDelete()");
            assertThat(m.mutator()).isEqualTo("VoidMethodCall");
        });
        // update 의 반환값을 null 로 바꿔도 테스트가 통과한다 (반환값을 검증하지 않음)
        assertThat(mutants).anySatisfy(m -> {
            assertThat(m.target()).isEqualTo("PostService#update");
            assertThat(m.survived()).isTrue();
            assertThat(m.covering()).contains("com.example.board.service.PostServiceTest#update_byOwner_changesTitleAndContent()");
        });
    }

    @Test
    void byMethod_andFindings() {
        List<MethodScore> scores = MutationReport.byMethod(realReport(), PLAN);

        assertThat(scores).extracting(MethodScore::id).containsExactly("PostService#update", "PostService#delete");
        MethodScore update = scores.get(0);
        assertThat(update.survived()).isPositive();
        assertThat(update.killed() + update.survived() + update.uncovered()).isEqualTo(update.total());

        List<Finding> findings = TestRunService.mutationFindings(scores);
        assertThat(findings).anySatisfy(f -> {
            assertThat(f.ruleId()).isEqualTo("EXEC_MUTANT_SURVIVED");
            assertThat(f.title()).contains("PostService#update");
            assertThat(f.evidence()).contains("null").contains("지나간 테스트: PostServiceTest#update_byOwner_changesTitleAndContent()");
        });
        // 지나가는 테스트가 있는데 하나도 못 잡으면 MAJOR, 일부만 놓치면 MINOR
        findings.forEach(f -> {
            MethodScore s = scores.stream().filter(x -> f.title().contains(x.id())).findFirst().orElseThrow();
            assertThat(f.severity()).isEqualTo(s.killed() == 0 && s.survived() >= 2 ? Severity.MAJOR : Severity.MINOR);
        });
    }

    @Test
    void byTest_countsKilledAndCoveredOnlyPerTestClass() {
        Map<String, int[]> tests = MutationReport.byTest(realReport());

        assertThat(tests).containsKey("com.example.board.service.PostServiceTest");
        assertThat(tests.get("com.example.board.service.PostServiceTest")[0]).isPositive();
    }

    @Test
    void zeroKillsWithCoverage_isMajor() {
        Mutant a = new Mutant("A#f", "a.A", "f", 3, "NegateConditionals", "negated conditional", "SURVIVED", List.of(), List.of("a.ATest#t()"));
        Mutant b = new Mutant("A#f", "a.A", "f", 4, "NullReturnVals", "replaced return value with null", "SURVIVED", List.of(), List.of("a.ATest#t()"));
        List<Finding> findings = TestRunService.mutationFindings(List.of(new MethodScore("A#f", "A.java", 3, 2, 0, 2, 0, List.of(a, b))));

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo(Severity.MAJOR);
            assertThat(f.title()).contains("하나도 못 잡음");
        });
    }

    @Test
    void testNames_parsesJUnit5AndJUnit4() {
        assertThat(MutationReport.testNames("a.FooTest.[engine:junit-jupiter]/[class:a.FooTest]/[nested-class:In]/[method:x()]|a.BarTest.y(a.BarTest)"))
                .containsExactly("a.FooTest#x()", "a.BarTest#y()");
        assertThat(MutationReport.testNames("")).isEmpty();
    }

    @Test
    void planSurvivesTheSelectionJsonRoundTrip() throws Exception {
        // 계획은 실행 기록의 selection 에 실려 저장되고, 러너가 /mutation 으로 받아 갈 때 다시 읽힌다
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        TestSelection s = TestSelection.off("x").withMutation(PLAN);

        TestSelection back = mapper.readValue(mapper.writeValueAsString(s), TestSelection.class);

        assertThat(back.mutation()).isEqualTo(PLAN);
    }

    @Test
    void hostileXml_isIgnored() {
        String evil = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><mutations><mutation status='KILLED'>&e;</mutation></mutations>";
        assertThat(MutationReport.parse(List.of(new ByteArrayInputStream(evil.getBytes(StandardCharsets.UTF_8))), PLAN)).isEmpty();
    }
}
