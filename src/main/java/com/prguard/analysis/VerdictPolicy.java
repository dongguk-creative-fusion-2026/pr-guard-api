package com.prguard.analysis;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 지적 사항으로 판정을 계산한다. LLM 이 판정을 내리지 않게 하려고 규칙을 코드로 둔다.
 *
 * @param majorThreshold MAJOR 가 이 개수 이상이면 "수정 후 머지"
 */
@ConfigurationProperties("prguard.verdict")
public record VerdictPolicy(int majorThreshold) {

    public Verdict decide(List<Finding> findings) {
        if (findings.stream().anyMatch(f -> f.severity() == Severity.BLOCKER)) {
            return Verdict.NOT_RECOMMENDED;
        }
        long majors = findings.stream().filter(f -> f.severity() == Severity.MAJOR).count();
        return majors >= Math.max(1, majorThreshold) ? Verdict.NEEDS_CHANGES : Verdict.MERGEABLE;
    }
}
