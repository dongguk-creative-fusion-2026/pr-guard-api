package com.prguard.execution;

import java.util.List;

/**
 * 이번 PR 에서 어떤 테스트 클래스를 돌리고 어떤 것을 걸렀는지, 그리고 그 이유. 화면의 "테스트 선택"이 그대로 그린다.
 *
 * @param mode        SELECTED: 영향 있는 테스트만, FULL: 전체 (fullReasons 에 이유), OFF: 선택을 쓰지 않음
 * @param shadow      전체를 돌리되 선택 결과만 기록 (놓친 실패가 없는지 확인용)
 * @param fullReasons 전체를 돌려야 하는 이유. 비어 있으면 선택 실행
 * @param reference   테스트 → 지나간 메서드를 알려 준 전체 실행 기록. 없으면 null
 * @param changes     바뀐 메서드마다 어떤 테스트가 닿는지
 * @param tests       head 의 테스트 클래스 전부 (영향 있는 것 먼저)
 * @param total       테스트 클래스 수
 * @param impacted    영향 있는 테스트 클래스 수
 * @param run         실제로 돌릴 테스트 클래스 수 (전체 실행이면 total)
 * @param referenceSeconds 기준 기록에서 전체 테스트가 걸린 시간 (초). 기록에 시간이 없으면 0
 * @param skippedSeconds   거른 테스트가 기준 기록에서 걸린 시간 (초) = 아낀 시간 추정
 */
public record TestSelection(
        String mode,
        boolean shadow,
        List<String> fullReasons,
        Reference reference,
        List<Change> changes,
        List<TestClass> tests,
        int total,
        int impacted,
        int run,
        double referenceSeconds,
        double skippedSeconds) {

    public static final String SELECTED = "SELECTED";
    public static final String FULL = "FULL";
    public static final String OFF = "OFF";

    /** 선택을 쓰지 않을 때 (꺼짐 · 인덱스 없음 · 오류) */
    public static TestSelection off(String reason) {
        return new TestSelection(OFF, false, List.of(reason), null, List.of(), List.of(), 0, 0, 0, 0, 0);
    }

    /** 러너에 넘길 클래스 목록. null 이면 전체를 돌린다 */
    public List<String> filter() {
        if (!SELECTED.equals(mode) || shadow) {
            return null;
        }
        return tests.stream().filter(TestClass::run).map(TestClass::className).toList();
    }

    /**
     * @param sameAsBase 기록한 커밋이 이번 PR 의 base 와 같은가
     */
    public record Reference(long runId, Integer prNumber, String sha, boolean sameAsBase, String finishedAt, int tests) {
    }

    /**
     * 바뀐 메서드 하나.
     *
     * @param reach TRACED: 기록에서 테스트가 지나감, STATIC: 호출 그래프로만 닿음, NONE: 어떤 테스트도 닿지 않음,
     *              OUTSIDE: 테스트 밖(애플리케이션 시작 · 다른 스레드)에서만 실행됨, UNTRACKED: 기록하지 않는 위치(인터페이스 기본 메서드)
     * @param tests 닿는 테스트 클래스
     */
    public record Change(String id, String kind, String file, int line, String reach, List<String> tests) {
    }

    /**
     * @param impacted 이번 PR 의 영향을 받는가
     * @param run      실제로 돌리는가 (전체 실행 · 그림자 모드면 영향이 없어도 돈다)
     * @param seconds  기준 기록에서 걸린 시간 (초)
     * @param referenceMethods 기준 기록에서 이 클래스의 테스트가 지나간 메서드 수 (기록에 없으면 -1)
     */
    public record TestClass(String className, String file, boolean impacted, boolean run, List<Reason> reasons,
                            double seconds, int referenceMethods) {
    }

    /**
     * @param kind   TRACE: 기록상 바뀐 메서드를 지나감, STATIC: 호출 그래프상 바뀐 메서드를 부름, CHANGED_TEST: 이번 PR 이 바꾼 테스트,
     *               UNKNOWN: 기준 기록에 없는 테스트, NO_IMPACT: 바뀐 메서드에 닿지 않음
     * @param target 관련된 바뀐 메서드 (없으면 null)
     */
    public record Reason(String kind, String target, String detail) {
    }
}
