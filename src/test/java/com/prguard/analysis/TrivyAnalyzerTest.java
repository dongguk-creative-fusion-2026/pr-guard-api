package com.prguard.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.prguard.diff.PatchParser;
import com.prguard.trivy.TrivyAnalyzer;
import com.prguard.trivy.TrivyProperties;
import com.prguard.trivy.TrivyReportParser.Report;
import com.prguard.trivy.TrivyScanner;
import com.prguard.workspace.Checkout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrivyAnalyzerTest {
    @TempDir Path base;
    @TempDir Path head;
    private final TrivyScanner scanner = mock(TrivyScanner.class);

    @Test
    void removesExistingFindingsWithoutRequiringJavaIndexAndFeedsVerdict() {
        Finding existing = finding("package-lock.json", "old", null);
        Finding added = finding("deploy/pod.yaml", "new", 10);
        when(scanner.scan(base, head)).thenReturn(pair(List.of(existing), List.of(existing, added)));
        AnalysisContext ctx = context(List.of());
        assertThat(ctx.indexed()).isFalse();
        List<Finding> result = analyzer(true).analyze(ctx);
        assertThat(result).containsExactly(added);
        Finding grounded = new FindingGrounder(new AnalysisProperties(500, 2, 0.5, 6000))
                .ground(result, ctx, false).get(0);
        assertThat(grounded.grounding().status()).isEqualTo(Grounding.Status.CONFIRMED);
        assertThat(grounded.grounding().mark(Grounding.LayerId.INDEX)).isEqualTo(Grounding.Mark.CONFIRMED);
        assertThat(grounded.grounding().mark(Grounding.LayerId.RUNTIME)).isEqualTo(Grounding.Mark.NONE);
        assertThat(new VerdictPolicy(1).decide(List.of(grounded))).isEqualTo(Verdict.NEEDS_CHANGES);
    }

    @Test
    void renameAndLineMovementDoNotReintroduceOldIssues() {
        var file = new ChangedFile("deploy/pod.yaml", "renamed", 0, 0, "", "pod.yaml", PatchParser.parse(""));
        when(scanner.scan(base, head)).thenReturn(pair(List.of(finding("pod.yaml", "same", 3)),
                List.of(finding("deploy/pod.yaml", "same", 15))));
        assertThat(analyzer(true).analyze(context(List.of(file)))).isEmpty();
    }

    @Test
    void countsAdditionalViolationsOfTheSameRuleInOneFile() {
        Finding old = finding("Dockerfile", "same", 2);
        Finding extra = finding("Dockerfile", "same", 8);
        when(scanner.scan(base, head)).thenReturn(pair(List.of(old), List.of(old, extra)));
        assertThat(analyzer(true).analyze(context(List.of()))).containsExactly(extra);
    }

    @Test
    void disabledAndMissingSourceAreExplicitSkips() {
        AnalysisContext disabled = context(List.of());
        assertThat(analyzer(false).analyze(disabled)).isEmpty();
        assertThat(disabled.notes()).anyMatch(n -> n.contains("꺼짐"));
        AnalysisContext noSource = new AnalysisContext(null, List.of());
        assertThat(analyzer(true).analyze(noSource)).isEmpty();
        assertThat(noSource.notes()).anyMatch(n -> n.contains("소스가 없어"));
        verifyNoInteractions(scanner);
    }

    @Test
    void scannerFailureIsNotTreatedAsAnEmptyBaseline() {
        when(scanner.scan(base, head)).thenThrow(new IllegalStateException("Trivy base 검사 실패"));
        assertThatThrownBy(() -> analyzer(true).analyze(context(List.of())))
                .hasMessageContaining("base 검사 실패");
    }

    @Test
    void warnsAboutUnscannedGradleModulesAndEmptyScan() throws Exception {
        Files.createDirectories(head.resolve("api"));
        Files.writeString(head.resolve("api/build.gradle.kts"), "plugins {}\n");
        when(scanner.scan(base, head)).thenReturn(new TrivyScanner.Pair(new Report(List.of(), 0), new Report(List.of(), 0)));
        AnalysisContext ctx = context(List.of());
        assertThat(analyzer(true).analyze(ctx)).isEmpty();
        assertThat(ctx.notes()).anyMatch(n -> n.contains("검사 범위가 없는"));
        assertThat(ctx.notes()).anyMatch(n -> n.contains("Gradle 모듈 1개"));
    }

    private TrivyAnalyzer analyzer(boolean enabled) {
        return new TrivyAnalyzer(new TrivyProperties(enabled, null, null, null, null), scanner);
    }

    private AnalysisContext context(List<ChangedFile> files) {
        var ctx = new AnalysisContext(null, files);
        ctx.setCheckout(new Checkout(base, base, head, "base-sha", "head-sha"));
        return ctx;
    }

    private static TrivyScanner.Pair pair(List<Finding> base, List<Finding> head) {
        return new TrivyScanner.Pair(new Report(base, 1), new Report(head, 1));
    }

    private static Finding finding(String file, String anchor, Integer line) {
        return new Finding("TRIVY_MISCONFIGURATION", Category.SECURITY, Severity.MAJOR, file, line,
                "Test finding", "Configuration issue", "Trivy HIGH", anchor, Finding.TOOL);
    }
}
