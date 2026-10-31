package com.prguard.conflicts;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.prguard.analysis.*;
import com.prguard.diff.PatchParser;
import com.prguard.github.RepoRef;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConflictAnalyzerTest {
    private final ConflictService service = mock(ConflictService.class);
    private final RepoRef repo = new RepoRef("a", "b");
    @Test void turnsOverlapIntoNonBlockingFindingAndGroundsItsActualClaim() {
        var a = new ConflictReport.Pr(1, "self", "a", "https://github.com/a/b/pull/1");
        var b = new ConflictReport.Pr(2, "other", "a", "https://github.com/a/b/pull/2");
        var pair = new ConflictReport.Pair(a, b, List.of(new ConflictReport.Overlap("Old.java", List.of(List.of(2, 2)))), false);
        when(service.analyze(repo)).thenReturn(new ConflictReport("COMPLETE", Instant.now(), List.of(pair), List.of(),
                Map.of(1, "head"), List.of()));
        var ctx = new AnalysisContext(new PullInfo(repo, 1, "self", "", "a", "main", "feature", "head", List.of()),
                List.of(new ChangedFile("New.java", "renamed", 1, 1, "", "Old.java", PatchParser.parse(""))));
        var findings = new ConflictAnalyzer(service).analyze(ctx);
        assertThat(findings).hasSize(1);
        var f = findings.getFirst();
        assertThat(f.file()).isEqualTo("New.java");
        assertThat(f.line()).isNull();
        assertThat(f.severity()).isEqualTo(Severity.MINOR);
        assertThat(f.message()).contains("#2", "https://github.com/a/b/pull/2", "확인된 것은 아닙니다");
        var grounded = new FindingGrounder(new AnalysisProperties(500, 2, 0.5, 6000)).ground(findings, ctx, false);
        assertThat(grounded.getFirst().grounding().status()).isEqualTo(Grounding.Status.CONFIRMED);
        assertThat(new VerdictPolicy(1).decide(grounded)).isEqualTo(Verdict.MERGEABLE);
    }
    @Test void staleSnapshotIsNotAppliedToCurrentReview() {
        when(service.analyze(repo)).thenReturn(new ConflictReport("PARTIAL", Instant.now(), List.of(), List.of(),
                Map.of(1, "old"), List.of("partial coverage")));
        var ctx = new AnalysisContext(new PullInfo(repo, 1, "", "", "a", "main", "feature", "head", List.of()), List.of());
        assertThat(new ConflictAnalyzer(service).analyze(ctx)).isEmpty();
        assertThat(ctx.notes()).anyMatch(n -> n.contains("스냅샷"));
        assertThat(ctx.notes()).contains("partial coverage");
    }
}
