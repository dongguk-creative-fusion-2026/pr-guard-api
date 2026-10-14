package com.prguard.execution;

import com.prguard.execution.JUnitReportParser.TestCase;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** flaky 판별: 코드는 그대로인데 다시 돌리면 결과가 바뀌는 테스트 */
final class Flaky {

    private Flaky() {
    }

    /** 처음엔 실패(실패 · 오류)했고 다시 돌리니 통과한 테스트 */
    static List<String> find(List<TestCase> first, List<TestCase> rerun) {
        Set<String> passedAgain = new HashSet<>();
        rerun.stream().filter(c -> c.status() == JUnitReportParser.Status.PASSED).forEach(c -> passedAgain.add(c.name()));
        return first.stream().filter(TestCase::failed).map(TestCase::name).filter(passedAgain::contains).distinct().toList();
    }
}
