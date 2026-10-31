package com.prguard.conflicts;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prguard.github.GitHubPullFile;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConflictEngineTest {
    static ConflictProperties props() {
        String python = System.getenv("PR_CONFLICT_TEST_PYTHON");
        if (python == null) python = System.getProperty("os.name").startsWith("Windows") ? "python" : "python3";
        return new ConflictProperties(true, python, null, 30);
    }
    private final ConflictEngine engine = new ConflictEngine(props(), new ObjectMapper());
    static ConflictService.InputPr pr(int n, String base, String patch) {
        return new ConflictService.InputPr(n, "PR " + n, "same-author", "https://github.com/a/b/pull/" + n,
                false, "main", base, "head" + n,
                List.of(new GitHubPullFile("A.java", "modified", 1, 1, patch, null)));
    }
    @Test void detectsSameAncestorOverlapIncludingSameAuthorAndBuildsCluster() {
        var report = engine.analyze(Map.of("pulls", List.of(pr(1, "base", "@@ -10 +10 @@\n-old\n+new"),
                pr(2, "base", "@@ -10 +99 @@\n-old\n+other"), pr(3, "base", "@@ -10 +10 @@\n-old\n+third"))));
        assertThat(report.conflicts()).hasSize(3);
        assertThat(report.clusters()).hasSize(1);
        assertThat(report.clusters().getFirst().prs()).hasSize(3);
        assertThat(report.conflicts()).allMatch(p -> !p.verified());
        assertThat(report.conflicts().getFirst().files().getFirst().ranges()).contains(List.of(10, 10));
    }
    @Test void ignoresContextAndDifferentAncestors() {
        var report = engine.analyze(Map.of("pulls", List.of(
                pr(1, "base", "@@ -1,4 +1,4 @@\n context\n-old\n+new\n context\n context"),
                pr(2, "base", "@@ -3,3 +3,3 @@\n context\n-old\n+other\n context"),
                pr(3, "other-base", "@@ -2 +2 @@\n-old\n+other"))));
        assertThat(report.conflicts()).isEmpty();
    }
    @Test void tracksRenameDeletionAndInsertionAtSamePoint() {
        var rename = new ConflictService.InputPr(1, "rename", "a", "https://github.com/a/b/pull/1", false,
                "main", "base", "head1", List.of(new GitHubPullFile("New.java", "renamed", 1, 1,
                "@@ -2 +2 @@\n-old\n+new", "A.java")));
        assertThat(engine.analyze(Map.of("pulls", List.of(rename,
                pr(2, "base", "@@ -2 +2,0 @@\n-old")))).conflicts()).hasSize(1);
        assertThat(engine.analyze(Map.of("pulls", List.of(pr(1, "base", "@@ -2,0 +3 @@\n+new"),
                pr(2, "base", "@@ -2,0 +3 @@\n+other")))).conflicts()).hasSize(1);
    }
    @Test void adjacentReplacementsDoNotOverlap() {
        assertThat(engine.analyze(Map.of("pulls", List.of(
                pr(1, "base", "@@ -2 +2 @@\n-old\n+new"),
                pr(2, "base", "@@ -3 +3 @@\n-old\n+other")))).conflicts()).isEmpty();
    }
    @Test void missingInterpreterFailsExplicitly() {
        var broken = new ConflictEngine(new ConflictProperties(true, "python-not-installed-prguard", null, 30), new ObjectMapper());
        assertThatThrownBy(() -> broken.analyze(Map.of("pulls", List.of())))
                .hasMessageContaining("실행/보고서 오류");
    }
    @Test void workspacePermissionsAreRestrictedBeforeWritingInput() throws Exception {
        var directory = ConflictEngine.privateWorkspace();
        try {
            if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                assertThat(java.nio.file.Files.getPosixFilePermissions(directory))
                        .isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            } else {
                var acl = java.nio.file.Files.getFileAttributeView(directory, java.nio.file.attribute.AclFileAttributeView.class);
                assertThat(acl.getAcl()).hasSize(1);
                assertThat(acl.getAcl().getFirst().principal()).isEqualTo(acl.getOwner());
            }
            java.nio.file.Files.writeString(directory.resolve("probe"), "private");
            assertThat(java.nio.file.Files.readString(directory.resolve("probe"))).isEqualTo("private");
            java.nio.file.Files.delete(directory.resolve("probe"));
        } finally {
            java.nio.file.Files.delete(directory);
        }
    }
}
