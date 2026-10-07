package com.prguard.execution;

import com.prguard.execution.JUnitReportParser.TestCase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * base 와 head 의 테스트 결과 비교.
 *
 * @param regressions base 에서 통과하던 테스트가 head 에서 실패 (이번 PR 이 깨뜨림)
 * @param newFailures base 에 없던 테스트가 head 에서 실패 (새로 추가한 테스트가 실패)
 * @param fixed       base 에서 실패하던 테스트가 head 에서 통과
 * @param stillFailing 양쪽 다 실패 (원래 깨져 있던 것 — 이번 PR 탓으로 보지 않는다)
 */
public record TestDiff(List<TestCase> regressions, List<TestCase> newFailures, List<TestCase> fixed,
                       List<TestCase> stillFailing) {

    public static TestDiff compare(List<TestCase> base, List<TestCase> head) {
        Map<String, TestCase> before = base.stream()
                .collect(Collectors.toMap(TestCase::name, Function.identity(), (a, b) -> a.failed() ? a : b));
        List<TestCase> regressions = new ArrayList<>();
        List<TestCase> newFailures = new ArrayList<>();
        List<TestCase> fixed = new ArrayList<>();
        List<TestCase> stillFailing = new ArrayList<>();
        for (TestCase h : head) {
            TestCase b = before.get(h.name());
            if (h.failed()) {
                if (b == null) {
                    newFailures.add(h);
                } else if (b.status() == JUnitReportParser.Status.PASSED) {
                    regressions.add(h);
                } else if (b.failed()) {
                    stillFailing.add(h);
                }
            } else if (h.status() == JUnitReportParser.Status.PASSED && b != null && b.failed()) {
                fixed.add(h);
            }
        }
        return new TestDiff(regressions, newFailures, fixed, stillFailing);
    }
}
