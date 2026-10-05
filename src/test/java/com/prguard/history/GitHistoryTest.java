package com.prguard.history;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class GitHistoryTest {

    private static final String ENTITY = "src/main/java/a/Post.java";
    private static final String MIGRATION_DIR = "src/main/resources/db/migration";

    @Test
    void coChanges_entityAlwaysWithNewMigrationFile_reportsDirectoryPartner() {
        GitHistory history = new GitHistory(List.of(
                commit("c1", ENTITY, MIGRATION_DIR + "/V2__a.sql"),
                commit("c2", ENTITY, MIGRATION_DIR + "/V3__b.sql"),
                commit("c3", ENTITY, MIGRATION_DIR + "/V4__c.sql"),
                commit("c4", ENTITY, "README.md")));

        List<CoChange> result = history.coChanges(ENTITY, 3, 0.5, 10);

        assertThat(result).singleElement().satisfies(c -> {
            assertThat(c.partner()).isEqualTo(MIGRATION_DIR);
            assertThat(c.directory()).isTrue();
            assertThat(c.support()).isEqualTo(3);
            assertThat(c.confidence()).isEqualTo(0.75);
        });
    }

    @Test
    void coChanges_bulkCommit_isIgnored() {
        String[] many = new String[GitHistory.MAX_FILES_PER_COMMIT + 1];
        many[0] = ENTITY;
        for (int i = 1; i < many.length; i++) {
            many[i] = "x/F" + i + ".java";
        }
        GitHistory history = new GitHistory(List.of(commit("bulk", many)));

        assertThat(history.coChanges(ENTITY, 1, 0.1, 10)).isEmpty();
        assertThat(history.fileCommitCount(ENTITY)).isEqualTo(1);
    }

    @Test
    void parsePorcelain_repeatedCommit_reusesCommitInfo() {
        String sha = "a".repeat(40);
        String out = sha + " 10 10 2\n"
                + "author kim\nauthor-time 1700000000\nsummary 할인율 상한 추가\nfilename A.java\n"
                + "\tline ten\n"
                + sha + " 11 11\n"
                + "\tline eleven\n";

        List<BlameLine> lines = GitHistoryLoader.parsePorcelain("A.java", out);

        assertThat(lines).hasSize(2);
        assertThat(lines.get(1).summary()).isEqualTo("할인율 상한 추가");
        assertThat(lines.get(1).line()).isEqualTo(11);
    }

    @Test
    void ranges_consecutiveLines_areMerged() {
        assertThat(GitHistoryLoader.ranges(List.of(5, 3, 4, 9)))
                .extracting(r -> r[0] + "-" + r[1])
                .containsExactly("3-5", "9-9");
    }

    private static CommitInfo commit(String sha, String... files) {
        return new CommitInfo(sha, 0, "dev", "msg", List.of(files));
    }
}
