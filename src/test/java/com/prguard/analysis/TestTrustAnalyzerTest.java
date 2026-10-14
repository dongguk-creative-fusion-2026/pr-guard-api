package com.prguard.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.prguard.analysis.rules.TestTrustAnalyzer;
import com.prguard.diff.Patch;
import com.prguard.index.JavaIndexer;
import com.prguard.index.MethodDiff;
import com.prguard.index.RepoIndex;
import com.prguard.workspace.Checkout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestTrustAnalyzerTest {

    @TempDir
    Path base;

    @TempDir
    Path head;

    private final JavaIndexer indexer = new JavaIndexer();
    private final TestTrustAnalyzer analyzer = new TestTrustAnalyzer();

    private static final String SERVICE = """
            package b;
            public class PostService {
                public void delete(long id, String user) {
                    if (!"owner".equals(user)) throw new IllegalStateException("denied");
                }
                public void deleteById(long id) { }
            }""";
    private static final String TEST_PATH = "src/test/java/b/PostServiceTest.java";

    /** PR #18 의 base: 권한 없는 삭제를 막는지 검증하는 테스트 */
    private static final String BASE_TEST = """
            package b;
            import static org.assertj.core.api.Assertions.assertThatThrownBy;
            import org.junit.jupiter.api.Test;
            class PostServiceTest {
                private final PostService service = new PostService();
                @Test
                void delete_byNonOwner_doesNotDelete() {
                    assertThatThrownBy(() -> service.delete(10L, "other")).isInstanceOf(IllegalStateException.class);
                }
                @Test
                void delete_byOwner() {
                    service.delete(10L, "owner");
                    org.junit.jupiter.api.Assertions.assertTrue(check());
                }
                private boolean check() { return true; }
            }""";

    @BeforeEach
    void project() throws IOException {
        write(base, "src/main/java/b/PostService.java", SERVICE);
        write(head, "src/main/java/b/PostService.java", SERVICE);
        write(base, TEST_PATH, BASE_TEST);
    }

    private List<Finding> analyze() {
        RepoIndex b = indexer.index(base);
        RepoIndex h = indexer.index(head);
        AnalysisContext ctx = new AnalysisContext(null,
                List.of(new ChangedFile(TEST_PATH, "modified", 1, 1, null, null, Patch.EMPTY)));
        ctx.setCheckout(new Checkout(base, base, head, "b", "h"));
        ctx.setIndexes(b, h, MethodDiff.compute(b, h));
        return analyzer.analyze(ctx);
    }

    @Test
    void pr18_removingTheNegativePathTestWhileCodeRemains_isMajor() throws IOException {
        // AI 가 권한 테스트를 지우고 아무것도 지키지 않는 테스트로 바꿈
        write(head, TEST_PATH, """
                package b;
                import org.junit.jupiter.api.Test;
                class PostServiceTest {
                    private final PostService service = new PostService();
                    @Test
                    void delete_deletesById() {
                        service.deleteById(10L);
                    }
                    @Test
                    void delete_byOwner() {
                        service.delete(10L, "owner");
                        org.junit.jupiter.api.Assertions.assertTrue(check());
                    }
                    private boolean check() { return true; }
                }""");

        List<Finding> findings = analyze();

        assertThat(findings).anySatisfy(f -> {
            assertThat(f.ruleId()).isEqualTo("TEST_REMOVED");
            assertThat(f.severity()).isEqualTo(Severity.MAJOR);
            assertThat(f.title()).contains("실패 경로");
            assertThat(f.evidence()).contains("delete_byNonOwner_doesNotDelete");
        });
        // 새로 쓴 테스트는 검증이 없다
        assertThat(findings).anySatisfy(f -> {
            assertThat(f.ruleId()).isEqualTo("TEST_NO_ASSERTION");
            assertThat(f.title()).contains("delete_deletesById");
        });
    }

    @Test
    void weakenedDisabledTrivialAndSwallowed() throws IOException {
        write(head, TEST_PATH, """
                package b;
                import org.junit.jupiter.api.Disabled;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;
                class PostServiceTest {
                    private final PostService service = new PostService();
                    @Test
                    void delete_byNonOwner_doesNotDelete() {
                        try {
                            service.delete(10L, "other");
                        } catch (IllegalStateException e) {
                        }
                    }
                    @Test
                    @Disabled
                    void delete_byOwner() {
                        service.delete(10L, "owner");
                        assertTrue(true);
                    }
                    private boolean check() { return true; }
                }""");

        List<Finding> findings = analyze();

        assertThat(findings).extracting(Finding::ruleId)
                .contains("TEST_WEAKENED", "TEST_DISABLED", "TEST_SWALLOWED_EXCEPTION");
        assertThat(findings).filteredOn(f -> f.ruleId().equals("TEST_WEAKENED"))
                .singleElement().satisfies(f -> assertThat(f.severity()).isEqualTo(Severity.MAJOR));
        // 끈 테스트는 약한 테스트로 또 지적하지 않는다
        assertThat(findings).noneMatch(f -> f.ruleId().equals("TEST_TRIVIAL_ASSERTION"));
    }

    @Test
    void trivialAssertionOnly_andHelperAssertionCounts() throws IOException {
        write(head, TEST_PATH, BASE_TEST
                .replace("assertTrue(check());", "assertEquals(1, 1);")
                .replace("private boolean check()", """
                @Test
                void viaHelper() {
                    service.delete(10L, "owner");
                    expectOk();
                }
                private void expectOk() { org.junit.jupiter.api.Assertions.assertNotNull(service); }
                private boolean check()"""));

        List<Finding> findings = analyze();

        assertThat(findings).anySatisfy(f -> {
            assertThat(f.ruleId()).isEqualTo("TEST_TRIVIAL_ASSERTION");
            assertThat(f.evidence()).contains("assertEquals(1, 1)");
        });
        assertThat(findings).noneMatch(f -> f.title().contains("viaHelper"));
    }

    @Test
    void unchangedTests_andRemovedTestWhoseCodeIsGone_areQuiet() throws IOException {
        write(head, TEST_PATH, BASE_TEST);
        assertThat(analyze()).isEmpty();

        // 기능(PostService#delete)을 지우면서 그 테스트도 지움 → 가벼운 지적
        write(head, "src/main/java/b/PostService.java", """
                package b;
                public class PostService {
                    public void deleteById(long id) { }
                }""");
        write(head, TEST_PATH, """
                package b;
                class PostServiceTest {
                }""");
        assertThat(analyze()).extracting(Finding::ruleId).containsOnly("TEST_REMOVED_WITH_CODE");
    }

    @Test
    void classDisabled_isMajor() throws IOException {
        write(head, TEST_PATH, BASE_TEST.replace("class PostServiceTest {", "@org.junit.jupiter.api.Disabled\nclass PostServiceTest {"));

        assertThat(analyze()).anySatisfy(f -> {
            assertThat(f.ruleId()).isEqualTo("TEST_DISABLED");
            assertThat(f.title()).contains("통째로");
        });
    }

    private static void write(Path root, String path, String content) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
