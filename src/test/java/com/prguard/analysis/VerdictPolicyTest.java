package com.prguard.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

class VerdictPolicyTest {
    private final VerdictPolicy policy = new VerdictPolicy(1);

    @ParameterizedTest
    @EnumSource(value = GuardDogReport.Status.class, names = {"FAILED", "PARTIAL"})
    void incompleteDependencyCheckRequiresReviewEvenWithNoFindingsOrHighProjectThreshold(GuardDogReport.Status status) {
        var report = GuardDogReport.empty(status, List.of(), 0);
        assertThat(policy.decide(List.of(), 999, report)).isEqualTo(Verdict.NEEDS_CHANGES);
    }

    @ParameterizedTest
    @EnumSource(value = GuardDogReport.Status.class, names = {"DISABLED", "NO_CHANGES", "COMPLETED"})
    void disabledNoTargetOrCompleteScanKeepsNormalPolicy(GuardDogReport.Status status) {
        assertThat(policy.decide(List.of(), null, GuardDogReport.empty(status, List.of(), 0)))
                .isEqualTo(Verdict.MERGEABLE);
    }

    @ParameterizedTest
    @EnumSource(GuardDogReport.Status.class)
    void realBlockerIsNeverDowngradedByScanStatus(GuardDogReport.Status status) {
        assertThat(policy.decide(List.of(finding(Severity.BLOCKER)), 999,
                GuardDogReport.empty(status, List.of(), 0))).isEqualTo(Verdict.NOT_RECOMMENDED);
    }

    @Test void existingThresholdAndRefutationStillApply() {
        Finding major = finding(Severity.MAJOR);
        assertThat(policy.decide(List.of(major), 2)).isEqualTo(Verdict.MERGEABLE);
        assertThat(policy.decide(List.of(major), 1)).isEqualTo(Verdict.NEEDS_CHANGES);
        Finding refuted = finding(Severity.BLOCKER).withGrounding(new Grounding(Grounding.Status.REFUTED,
                null, List.of(), List.of()));
        assertThat(policy.decide(List.of(refuted))).isEqualTo(Verdict.MERGEABLE);
        assertThat(policy.decide(List.of(refuted), null, GuardDogReport.empty(GuardDogReport.Status.FAILED, List.of(), 0)))
                .isEqualTo(Verdict.NEEDS_CHANGES);
    }

    private static Finding finding(Severity severity) {
        return new Finding("TEST", Category.SECURITY, severity, "test.java", null, "test", "test", "test", "test", Finding.TOOL);
    }
}
