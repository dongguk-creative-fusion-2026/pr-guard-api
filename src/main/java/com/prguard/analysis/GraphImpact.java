package com.prguard.analysis;

import java.util.List;

/**
 * GitNexus 호출 그래프로 따라간 변경 영향. 바뀐 줄을 감싸는 함수(0단계)에서 시작해 그 함수를 부르는 함수를 몇 단계까지 따라간다.
 * 언어를 가리지 않고, JavaIndexer 가 직접 확인하지 못하는 2단계 이상 · Java 밖 호출을 메운다.
 *
 * @param commitSha     그래프를 만든 기본 브랜치 커밋
 * @param baseMatches   그래프 커밋이 이 PR 의 base 와 같은가 (다르면 줄 위치가 조금 어긋날 수 있다)
 * @param complete      레포 전체 호출 그래프였나 (false 면 빠진 호출이 있을 수 있다)
 * @param changed       바뀐 줄을 감싸는 함수 (0단계)
 * @param impacted      그 함수를 부르는 함수 (1단계부터)
 * @param impactedFiles 영향을 받지만 이 PR 이 고치지 않은 파일
 * @param unmapped      그래프에 없는 바뀐 파일 (새 파일 · 그래프가 모르는 언어 · 함수 밖 변경만 있는 파일)
 * @param truncated     상한에서 자른 함수가 있다
 */
public record GraphImpact(
        String commitSha,
        boolean baseMatches,
        boolean complete,
        List<Fn> changed,
        List<Fn> impacted,
        List<String> impactedFiles,
        List<String> unmapped,
        boolean truncated) {

    /**
     * @param depth     0 = 바뀐 함수, 1 = 그 함수를 부르는 함수, 2 = 1 을 부르는 함수 …
     * @param via       이 함수가 부르는 바로 앞 단계 함수 (0단계면 null)
     * @param confirmed JavaIndexer 도 같은 호출을 찾았나 (1단계 Java 호출만 확인할 수 있다)
     */
    public record Fn(String file, String name, int line, int depth, String via, boolean confirmed) {
    }

    public static GraphImpact empty() {
        return new GraphImpact(null, false, false, List.of(), List.of(), List.of(), List.of(), false);
    }

    public int maxDepth() {
        return impacted.stream().mapToInt(Fn::depth).max().orElse(0);
    }

    public long confirmedCount() {
        return impacted.stream().filter(Fn::confirmed).count();
    }
}
