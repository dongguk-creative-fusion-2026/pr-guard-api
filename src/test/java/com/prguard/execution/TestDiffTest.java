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

    @Test
    void flaky_failedThenPassedOnRerun_isRemovedFromRegressionsAndNewFailures() {
        List<TestCase> first = List.of(pass("A#ok"), fail("A#broken"), fail("A#timing"), fail("A#newFlaky"));
        List<TestCase> rerun = List.of(fail("A#broken"), pass("A#timing"), pass("A#newFlaky"));
        List<String> flaky = Flaky.find(first, rerun);
        assertThat(flaky).containsExactly("A#timing", "A#newFlaky");

        TestDiff diff = TestDiff.compare(List.of(pass("A#ok"), pass("A#broken"), pass("A#timing")), first);
        List<String> removed = new java.util.ArrayList<>();
        TestDiff kept = diff.without(new java.util.HashSet<>(flaky), removed);

        // 다시 돌려도 실패한 것만 회귀로 남는다
        assertThat(kept.regressions()).extracting(TestCase::name).containsExactly("A#broken");
        assertThat(kept.newFailures()).isEmpty();
        assertThat(removed).containsExactlyInAnyOrder("A#timing", "A#newFlaky");
    }
}
