package com.prguard.execution;

import com.prguard.evidence.EvidenceTest;
import com.prguard.execution.JUnitReportParser.TestCase;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 증거 테스트 하나의 판정. 클래스 안 테스트 메서드들을 묶어 base · head 결과를 본다.
 *
 * <ul>
 *   <li>PROVEN: base 에서 전부 통과, head 에서 하나 이상 실패 → PR 이 이 동작을 바꿨다</li>
 *   <li>NO_CHANGE: 양쪽 다 통과 → 테스트가 겨냥한 동작은 그대로다</li>
 *   <li>INVALID: base 에서부터 실패 → 테스트가 원래 동작을 잘못 짚었다 (증거로 쓰지 않는다)</li>
 *   <li>NOT_RUN: 한쪽에서 결과가 없다 (컴파일 실패로 빠졌거나 돌지 않음)</li>
 * </ul>
 */
public record EvidenceVerdict(EvidenceTest test, Kind kind, Outcome base, Outcome head, String baseMessage,
                              String headMessage) {

    public enum Kind {
        PROVEN, NO_CHANGE, INVALID, NOT_RUN
    }

    public enum Outcome {
        PASSED, FAILED, NOT_RUN
    }

    public static List<EvidenceVerdict> judge(List<EvidenceTest> tests, List<TestCase> baseCases, List<TestCase> headCases,
                                              String baseDropped, String headDropped) {
        List<EvidenceVerdict> result = new ArrayList<>();
        for (EvidenceTest t : tests) {
            if (t.probe()) {
                continue;
            }
            List<TestCase> b = baseDropped == null ? of(baseCases, t.className()) : List.of();
            List<TestCase> h = headDropped == null ? of(headCases, t.className()) : List.of();
            Outcome bo = outcome(b);
            Outcome ho = outcome(h);
            Kind kind;
            if (bo == Outcome.NOT_RUN || ho == Outcome.NOT_RUN) {
                kind = Kind.NOT_RUN;
            } else if (bo == Outcome.FAILED) {
                kind = Kind.INVALID;
            } else if (ho == Outcome.FAILED) {
                kind = Kind.PROVEN;
            } else {
                kind = Kind.NO_CHANGE;
            }
            result.add(new EvidenceVerdict(t, kind, bo, ho, firstFailure(b), firstFailure(h)));
        }
        return result;
    }

    public Map<String, Object> view() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("className", test.className());
        m.put("target", test.target());
        m.put("intent", test.intent());
        m.put("code", test.code());
        m.put("kind", kind.name());
        m.put("base", base.name());
        m.put("head", head.name());
        m.put("baseMessage", baseMessage);
        m.put("headMessage", headMessage);
        return m;
    }

    private static List<TestCase> of(List<TestCase> cases, String className) {
        return cases.stream().filter(c -> c.name().startsWith(className + "#")).toList();
    }

    private static Outcome outcome(List<TestCase> cases) {
        List<TestCase> ran = cases.stream().filter(c -> c.status() != JUnitReportParser.Status.SKIPPED).toList();
        if (ran.isEmpty()) {
            return Outcome.NOT_RUN;
        }
        return ran.stream().anyMatch(TestCase::failed) ? Outcome.FAILED : Outcome.PASSED;
    }

    private static String firstFailure(List<TestCase> cases) {
        return cases.stream().filter(TestCase::failed).findFirst()
                .map(c -> c.name().substring(c.name().indexOf('#') + 1) + ": " + (c.message() == null ? "" : c.message()))
                .orElse(null);
    }
}
