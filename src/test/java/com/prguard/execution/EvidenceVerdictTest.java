package com.prguard.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.prguard.evidence.EvidenceTest;
import com.prguard.execution.EvidenceVerdict.Kind;
import com.prguard.execution.JUnitReportParser.Status;
import com.prguard.execution.JUnitReportParser.TestCase;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvidenceVerdictTest {

    private static EvidenceTest test(String className) {
        return new EvidenceTest("src/test/java/a/" + className + ".java", "a." + className, "a.Foo#bar()", "", "");
    }

    private static TestCase pass(String name) {
        return new TestCase(name, Status.PASSED, null);
    }

    private static TestCase fail(String name) {
        return new TestCase(name, Status.FAILED, "expected NotFoundException");
    }

    @Test
    void judge_classifiesByBaseAndHeadOutcome() {
        List<EvidenceTest> tests = List.of(test("PrGuardEvidence1Test"), test("PrGuardEvidence2Test"),
                test("PrGuardEvidence3Test"), test("PrGuardEvidence4Test"));
        List<TestCase> base = List.of(pass("a.PrGuardEvidence1Test#x"), pass("a.PrGuardEvidence1Test#y"),
                pass("a.PrGuardEvidence2Test#x"), fail("a.PrGuardEvidence3Test#x"));
        List<TestCase> head = List.of(pass("a.PrGuardEvidence1Test#x"), fail("a.PrGuardEvidence1Test#y"),
                pass("a.PrGuardEvidence2Test#x"), fail("a.PrGuardEvidence3Test#x"));

        List<EvidenceVerdict> verdicts = EvidenceVerdict.judge(tests, base, head, null, null);

        assertThat(verdicts).extracting(EvidenceVerdict::kind)
                .containsExactly(Kind.PROVEN, Kind.NO_CHANGE, Kind.INVALID, Kind.NOT_RUN);
        assertThat(verdicts.get(0).headMessage()).isEqualTo("y: expected NotFoundException");
    }

    @Test
    void judge_droppedForCompileFailure_isNotRun() {
        List<EvidenceVerdict> verdicts = EvidenceVerdict.judge(List.of(test("PrGuardEvidence1Test")),
                List.of(pass("a.PrGuardEvidence1Test#x")), List.of(), null, "compile");

        assertThat(verdicts.get(0).kind()).isEqualTo(Kind.NOT_RUN);
    }

    @Test
    void isEvidence_matchesOnlyGeneratedClasses() {
        assertThat(EvidenceTest.isEvidence("a.b.PrGuardEvidence2Test#x")).isTrue();
        assertThat(EvidenceTest.isEvidence("a.b.PostServiceTest#x")).isFalse();
    }
}
