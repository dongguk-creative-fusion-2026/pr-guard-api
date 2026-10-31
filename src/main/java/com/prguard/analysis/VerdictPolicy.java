package com.prguard.analysis;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 지적 사항으로 판정을 계산한다. LLM 이 판정을 내리지 않게 하려고 규칙을 코드로 둔다.
 * 코드 인덱스와 어긋나는 지적({@link Finding#refuted()})은 세지 않는다.
 *
 * @param majorThreshold MAJOR 가 이 개수 이상이면 "수정 후 머지"
 */
@ConfigurationProperties("prguard.verdict")
public record VerdictPolicy(int majorThreshold) {

    public Verdict decide(List<Finding> findings) {
        return decide(findings, null);
    }

    /** @param projectThreshold 프로젝트 설정 (null 이면 기본값) */
    public Verdict decide(List<Finding> all, Integer projectThreshold) {
        return decide(all, projectThreshold, null);
    }

    /** A required dependency check that did not complete cannot produce a mergeable verdict. */
    public Verdict decide(List<Finding> all, Integer projectThreshold, GuardDogReport guarddog) {
        List<Finding> findings = all.stream().filter(f -> !f.refuted()).toList();
        if (findings.stream().anyMatch(f -> f.severity() == Severity.BLOCKER)) {
            return Verdict.NOT_RECOMMENDED;
        }
        int threshold = projectThreshold != null ? projectThreshold : majorThreshold;
        long majors = findings.stream().filter(f -> f.severity() == Severity.MAJOR).count();
        return majors >= Math.max(1, threshold) || (guarddog != null && guarddog.requiresAttention())
                ? Verdict.NEEDS_CHANGES : Verdict.MERGEABLE;
    }
}
