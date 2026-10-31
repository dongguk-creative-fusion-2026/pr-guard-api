package com.prguard.conflicts;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prguard.github.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConflictServiceTest {
    private final RepoRef repo = new RepoRef("a", "b");
    private final GitHubClient github = mock(GitHubClient.class);
    private final ConflictEngine engine = new ConflictEngine(ConflictEngineTest.props(), new ObjectMapper());
    private final ConflictService service = new ConflictService(github, engine, ConflictEngineTest.props());

    private GitHubPull pr(int n, String head) {
        return new GitHubPull(n, "PR " + n, "", "open", false, "https://github.com/a/b/pull/" + n,
                null, new GitHubPull.Ref("branch" + n, head), new GitHubPull.Ref("main", "target"));
    }
    private void givenPulls() {
        var a = pr(1, "head1"); var b = pr(2, "head2");
        when(github.listOpenPulls(repo, null)).thenReturn(new OpenPulls(false, null, List.of(a, b)));
        when(github.getPull(repo, 1)).thenReturn(a); when(github.getPull(repo, 2)).thenReturn(b);
        when(github.comparisonBase(eq(repo), anyString(), anyString())).thenReturn("ancestor");
        when(github.listPullFiles(eq(repo), anyInt())).thenReturn(List.of(new GitHubPullFile("A.java", "modified", 1, 1,
                "@@ -2 +2 @@\n-old\n+new", null)));
    }
    @Test void reportsAllPairsWithHeadSnapshotAndNoFalseVerification() {
        givenPulls();
        var report = service.analyze(repo);
        assertThat(report.status()).isEqualTo("COMPLETE");
        assertThat(report.conflicts()).hasSize(1);
        assertThat(report.heads()).containsEntry(1, "head1").containsEntry(2, "head2");
        assertThat(report.conflicts().getFirst().verified()).isFalse();
    }
    @Test void marksChangingPullAndMissingPatchAsPartial() {
        givenPulls();
        when(github.getPull(repo, 2)).thenReturn(pr(2, "new-head"));
        when(github.listPullFiles(repo, 1)).thenReturn(List.of(new GitHubPullFile("image.png", "modified", 0, 0, null, null)));
        var report = service.analyze(repo);
        assertThat(report.status()).isEqualTo("PARTIAL");
        assertThat(report.notes()).anyMatch(s -> s.contains("분석 중 변경"));
        assertThat(report.notes()).anyMatch(s -> s.contains("patch"));
        assertThat(report.heads()).doesNotContainKey(2);
    }
    @Test void differentMergeBasesAreNotComparedAndAreReported() {
        givenPulls();
        when(github.comparisonBase(repo, "target", "head2")).thenReturn("different");
        var report = service.analyze(repo);
        assertThat(report.conflicts()).isEmpty();
        assertThat(report.status()).isEqualTo("PARTIAL");
        assertThat(report.notes()).anyMatch(s -> s.contains("merge-base"));
    }
    @Test void collectionFailureCannotBeMistakenForCompleteCleanReport() {
        when(github.listOpenPulls(repo, null)).thenThrow(new IllegalStateException("rate limit"));
        assertThatThrownBy(() -> service.analyze(repo)).hasMessageContaining("rate limit");
        reset(github);
        givenPulls();
        when(github.listPullFiles(repo, 1)).thenThrow(new IllegalStateException("rate limit"));
        assertThat(service.analyze(repo).status()).isEqualTo("PARTIAL");
    }
    @Test void disabledFeatureDoesNotCallGithubOrPython() {
        var disabled = new ConflictService(github, engine, new ConflictProperties(false, null, null, 30));
        assertThat(disabled.analyze(repo).status()).isEqualTo("DISABLED");
        verifyNoInteractions(github);
    }
}
