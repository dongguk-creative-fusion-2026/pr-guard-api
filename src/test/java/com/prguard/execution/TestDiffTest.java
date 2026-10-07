package com.prguard.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.prguard.execution.JUnitReportParser.Status;
import com.prguard.execution.JUnitReportParser.TestCase;
import java.util.List;
import org.junit.jupiter.api.Test;

class TestDiffTest {

    private static TestCase pass(String name) {
        return new TestCase(name, Status.PASSED, null);
    }

    private static TestCase fail(String name) {
        return new TestCase(name, Status.FAILED, "boom");
    }

    @Test
    void compare_onlyBreaksIntroducedByHeadCountAsRegressions() {
        List<TestCase> base = List.of(pass("A#ok"), pass("A#broken"), fail("A#alreadyRed"), fail("A#fixed"));
        List<TestCase> head = List.of(pass("A#ok"), fail("A#broken"), fail("A#alreadyRed"), pass("A#fixed"),
                fail("A#newTest"), pass("A#newGreen"));

        TestDiff diff = TestDiff.compare(base, head);

        assertThat(diff.regressions()).extracting(TestCase::name).containsExactly("A#broken");
        assertThat(diff.newFailures()).extracting(TestCase::name).containsExactly("A#newTest");
        assertThat(diff.fixed()).extracting(TestCase::name).containsExactly("A#fixed");
        assertThat(diff.stillFailing()).extracting(TestCase::name).containsExactly("A#alreadyRed");
    }

    @Test
    void compare_skippedInBaseThenFailingInHead_isNotARegression() {
        TestDiff diff = TestDiff.compare(List.of(new TestCase("A#x", Status.SKIPPED, null)), List.of(fail("A#x")));

        assertThat(diff.regressions()).isEmpty();
        assertThat(diff.newFailures()).isEmpty();
    }
}
