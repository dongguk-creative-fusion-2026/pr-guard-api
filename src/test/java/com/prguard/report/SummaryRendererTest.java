package com.prguard.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.prguard.analysis.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class SummaryRendererTest {
    @Test void incompleteDependencyScanExplainsVerdictWithoutInventingRiskFindings() {
        var report = GuardDogReport.empty(GuardDogReport.Status.FAILED,
                List.of(new GuardDogReport.Problem(GuardDogReport.Reason.SOURCE_UNAVAILABLE, "소스 없음", null)), 0);
        String summary = render(report, Verdict.NEEDS_CHANGES);
        assertThat(summary).contains("의존성 검사: 검사 미완료", "머지 가능 판정을 보류", "다시 검사", "지적 사항 없음", "MAJOR 0");
    }

    @Test void disabledCheckDoesNotClaimPackagesWereChecked() {
        assertThat(render(GuardDogReport.empty(GuardDogReport.Status.DISABLED, List.of(), 0), Verdict.MERGEABLE))
                .contains("의존성 검사: 검사 꺼짐").doesNotContain("의존성 검사: 검사 완료", "머지 가능 판정을 보류");
    }

    @Test void summariesFromLegacyCallersWithoutDependencyReportStillRender() {
        assertThat(render(null, Verdict.MERGEABLE)).contains("머지 가능").doesNotContain("의존성 검사:");
    }

    private static String render(GuardDogReport report, Verdict verdict) {
        var index = new ContextSummary.IndexStats(0, 0, 0, 0, 0, 0);
        var context = new ContextSummary("base", "1234567890", index, index, List.of(), List.of(), List.of(),
                0, List.of(), List.of(), 0, GraphImpact.empty(), report);
        return new SummaryRenderer().render("1234567890", List.of(),
                new AnalysisResult(List.of(), verdict, "", "stats-only", context), null);
    }
}
