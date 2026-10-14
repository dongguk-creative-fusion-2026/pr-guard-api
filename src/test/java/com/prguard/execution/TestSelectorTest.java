package com.prguard.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.prguard.analysis.ChangedFile;
import com.prguard.diff.PatchParser;
import com.prguard.execution.JUnitReportParser.Status;
import com.prguard.execution.JUnitReportParser.TestCase;
import com.prguard.execution.TestSelection.Reason;
import com.prguard.execution.TestSelection.TestClass;
import com.prguard.index.ChangedMethod;
import com.prguard.index.JavaIndexer;
import com.prguard.index.MethodDiff;
import com.prguard.index.RepoIndex;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestSelectorTest {

    @TempDir
    Path base;

    @TempDir
    Path head;

    private final JavaIndexer indexer = new JavaIndexer();

    private static final String SERVICE = """
            package shop;
            public class PostService {
                static final int LIMIT = 10;
                public String getPost(int id) {
                    return "post" + id;
                }
                public String listPosts() {
                    return "all";
                }
            }""";
    private static final String CONFIG = """
            package shop;
            public class AppConfig {
                public String bean() {
                    return "bean";
                }
            }""";

    /** 기준 기록: PostServiceTest → getPost, ListTest → listPosts, PriceTest → total. AppConfig#bean 은 테스트 밖에서만 불림 */
    private static final Trace REFERENCE = new Trace(
            List.of("shop.PostService#getPost", "shop.PostService#listPosts", "shop.Price#total", "shop.AppConfig#bean",
                    "shop.PostServiceTest#gets", "shop.ListTest#lists", "shop.PriceTest$Nested#sums"),
            List.of(3L, 1L, 2L, 1L, 1L, 1L, 1L),
            List.of(),
            Map.of("shop.PostServiceTest#gets", List.of(0),
                    "shop.ListTest#lists", List.of(1),
                    "shop.PriceTest$Nested#sums", List.of(2)));
    private static final TestSelection.Reference META = new TestSelection.Reference(7, 3, "abc", true, null, 3);

    @BeforeEach
    void project() throws IOException {
        for (Path root : List.of(base, head)) {
            write(root, "src/main/java/shop/PostService.java", SERVICE);
            write(root, "src/main/java/shop/AppConfig.java", CONFIG);
            write(root, "src/main/java/shop/Price.java", """
                    package shop;
                    public class Price {
                        public int total() { return 1; }
                    }""");
            write(root, "src/test/java/shop/PostServiceTest.java", """
                    package shop;
                    import org.junit.jupiter.api.Test;
                    class PostServiceTest {
                        @Test void gets() { new PostService().getPost(1); }
                    }""");
            write(root, "src/test/java/shop/ListTest.java", """
                    package shop;
                    import org.junit.jupiter.api.Test;
                    class ListTest {
                        @Test void lists() { new PostService().listPosts(); }
                    }""");
            write(root, "src/test/java/shop/PriceTest.java", """
                    package shop;
                    import org.junit.jupiter.api.Nested;
                    import org.junit.jupiter.api.Test;
                    class PriceTest {
                        @Nested class Nested2 {
                            @Test void sums() { new Price().total(); }
                        }
                    }""");
            // 테스트가 아닌 보조 코드
            write(root, "src/test/java/shop/Fixtures.java", """
                    package shop;
                    class Fixtures {
                        static String id() { return "1"; }
                    }""");
        }
    }

    private TestSelection select(List<ChangedFile> files, Trace reference, boolean shadow) {
        RepoIndex b = indexer.index(base);
        RepoIndex h = indexer.index(head);
        List<ChangedMethod> changed = MethodDiff.compute(b, h);
        return TestSelector.select(new TestSelector.Input(files, changed, b, h, reference,
                reference == null ? null : META, Map.of("shop.PostServiceTest", 1.5, "shop.ListTest", 4.0, "shop.PriceTest", 2.5),
                List.of(), 4, shadow));
    }

    private ChangedFile getPostChanged() throws IOException {
        write(head, "src/main/java/shop/PostService.java", SERVICE.replace("\"post\" + id", "\"post-\" + id"));
        return file("src/main/java/shop/PostService.java", "modified", """
                @@ -5,1 +5,1 @@
                -        return "post" + id;
                +        return "post-" + id;""");
    }

    @Test
    void bodyChange_runsOnlyTestsThatReachedTheMethod() throws IOException {
        TestSelection s = select(List.of(getPostChanged()), REFERENCE, false);

        assertThat(s.mode()).isEqualTo(TestSelection.SELECTED);
        assertThat(s.fullReasons()).isEmpty();
        assertThat(s.filter()).containsExactly("shop.PostServiceTest");
        assertThat(s.total()).isEqualTo(3);
        assertThat(s.run()).isEqualTo(1);
        TestClass post = test(s, "shop.PostServiceTest");
        assertThat(post.reasons()).extracting(Reason::kind).containsExactly("TRACE");
        assertThat(post.reasons().get(0).target()).isEqualTo("PostService#getPost");
        TestClass list = test(s, "shop.ListTest");
        assertThat(list.run()).isFalse();
        assertThat(list.reasons()).extracting(Reason::kind).containsExactly("NO_IMPACT");
        // 거른 테스트의 기준 시간 = 아낀 시간
        assertThat(s.skippedSeconds()).isEqualTo(6.5);
        assertThat(s.referenceSeconds()).isEqualTo(8.0);
        assertThat(s.changes()).singleElement().satisfies(c -> {
            assertThat(c.reach()).isEqualTo("TRACED");
            assertThat(c.tests()).containsExactly("shop.PostServiceTest");
        });
    }

    @Test
    void nestedTestClassInTrace_mapsToTopLevelClass() throws IOException {
        write(head, "src/main/java/shop/Price.java", """
                package shop;
                public class Price {
                    public int total() { return 2; }
                }""");
        ChangedFile f = file("src/main/java/shop/Price.java", "modified", """
                @@ -3,1 +3,1 @@
                -    public int total() { return 1; }
                +    public int total() { return 2; }""");

        TestSelection s = select(List.of(f), REFERENCE, false);

        assertThat(s.filter()).containsExactly("shop.PriceTest");
    }

    @Test
    void noReference_runsEverything() throws IOException {
        TestSelection s = select(List.of(getPostChanged()), null, false);

        assertThat(s.mode()).isEqualTo(TestSelection.FULL);
        assertThat(s.fullReasons()).anyMatch(r -> r.contains("기록이 아직 없음"));
        assertThat(s.filter()).isNull();
        assertThat(s.run()).isEqualTo(s.total());
        // 걸렀다면 어땠을지는 정적 분석으로 남긴다
        assertThat(test(s, "shop.PostServiceTest").impacted()).isTrue();
        assertThat(test(s, "shop.PostServiceTest").reasons()).extracting(Reason::kind).containsExactly("STATIC");
    }

    @Test
    void buildOrResourceChange_runsEverything_docsAreIgnored() throws IOException {
        ChangedFile readme = file("README.md", "modified", "@@ -1 +1 @@\n-a\n+b");
        assertThat(select(List.of(getPostChanged(), readme), REFERENCE, false).mode()).isEqualTo(TestSelection.SELECTED);

        ChangedFile gradle = file("build.gradle", "modified", "@@ -1 +1 @@\n-a\n+b");
        TestSelection s = select(List.of(getPostChanged(), gradle), REFERENCE, false);
        assertThat(s.mode()).isEqualTo(TestSelection.FULL);
        assertThat(s.fullReasons()).anyMatch(r -> r.contains("build.gradle"));

        ChangedFile yml = file("src/main/resources/application.yml", "modified", "@@ -1 +1 @@\n-a\n+b");
        assertThat(select(List.of(yml), REFERENCE, false).mode()).isEqualTo(TestSelection.FULL);
    }

    @Test
    void constantChangeOutsideMethods_runsEverything_importOnlyDoesNot() throws IOException {
        write(head, "src/main/java/shop/PostService.java", SERVICE.replace("LIMIT = 10", "LIMIT = 20"));
        ChangedFile constant = file("src/main/java/shop/PostService.java", "modified", """
                @@ -3,1 +3,1 @@
                -    static final int LIMIT = 10;
                +    static final int LIMIT = 20;""");
        TestSelection s = select(List.of(constant), REFERENCE, false);
        assertThat(s.mode()).isEqualTo(TestSelection.FULL);
        assertThat(s.fullReasons()).singleElement().asString().contains("메서드 밖 변경").contains("LIMIT");

        write(head, "src/main/java/shop/PostService.java", SERVICE.replace("package shop;", "package shop;\nimport java.util.List;"));
        ChangedFile imports = file("src/main/java/shop/PostService.java", "modified", """
                @@ -1,2 +1,3 @@
                 package shop;
                +import java.util.List;
                 public class PostService {""");
        TestSelection i = select(List.of(imports), REFERENCE, false);
        assertThat(i.mode()).isEqualTo(TestSelection.SELECTED);
        assertThat(i.filter()).isEmpty();
    }

    @Test
    void methodRunOnlyOutsideTests_runsEverything() throws IOException {
        write(head, "src/main/java/shop/AppConfig.java", CONFIG.replace("\"bean\"", "\"bean2\""));
        ChangedFile f = file("src/main/java/shop/AppConfig.java", "modified", """
                @@ -4,1 +4,1 @@
                -        return "bean";
                +        return "bean2";""");

        TestSelection s = select(List.of(f), REFERENCE, false);

        assertThat(s.mode()).isEqualTo(TestSelection.FULL);
        assertThat(s.fullReasons()).anyMatch(r -> r.contains("테스트 밖") && r.contains("AppConfig#bean"));
        assertThat(s.changes()).singleElement().satisfies(c -> assertThat(c.reach()).isEqualTo("OUTSIDE"));
    }

    @Test
    void changedTestClass_alwaysRuns_helperChangeRunsEverything() throws IOException {
        write(head, "src/test/java/shop/ListTest.java", """
                package shop;
                import org.junit.jupiter.api.Test;
                class ListTest {
                    @Test void lists() { new PostService().listPosts(); new PostService().listPosts(); }
                }""");
        ChangedFile test = file("src/test/java/shop/ListTest.java", "modified", """
                @@ -4,1 +4,1 @@
                -    @Test void lists() { new PostService().listPosts(); }
                +    @Test void lists() { new PostService().listPosts(); new PostService().listPosts(); }""");
        TestSelection s = select(List.of(test), REFERENCE, false);
        assertThat(s.filter()).containsExactly("shop.ListTest");
        assertThat(test(s, "shop.ListTest").reasons()).extracting(Reason::kind).contains("CHANGED_TEST");

        ChangedFile helper = file("src/test/java/shop/Fixtures.java", "modified", "@@ -3 +3 @@\n-a\n+b");
        TestSelection h = select(List.of(helper), REFERENCE, false);
        assertThat(h.mode()).isEqualTo(TestSelection.FULL);
        assertThat(h.fullReasons()).anyMatch(r -> r.contains("테스트 보조 코드"));
    }

    @Test
    void testMissingFromReference_runsAsUnknown() throws IOException {
        write(head, "src/test/java/shop/NewTest.java", """
                package shop;
                import org.junit.jupiter.api.Test;
                class NewTest {
                    @Test void fresh() { }
                }""");

        TestSelection s = select(List.of(getPostChanged()), REFERENCE, false);

        assertThat(s.filter()).containsExactlyInAnyOrder("shop.PostServiceTest", "shop.NewTest");
        assertThat(test(s, "shop.NewTest").reasons()).extracting(Reason::kind).containsExactly("UNKNOWN");
    }

    @Test
    void shadow_runsEverythingButRecordsTheSelection() throws IOException {
        TestSelection s = select(List.of(getPostChanged()), REFERENCE, true);

        assertThat(s.mode()).isEqualTo(TestSelection.SELECTED);
        assertThat(s.shadow()).isTrue();
        assertThat(s.filter()).isNull();
        assertThat(s.run()).isEqualTo(3);
        assertThat(s.impacted()).isEqualTo(1);
        assertThat(s.skippedSeconds()).isZero();
    }

    @Test
    void selectionResult_reportsFailuresInSkippedTestsAsMissed() throws IOException {
        TestSelection s = select(List.of(getPostChanged()), REFERENCE, true);
        TestDiff diff = TestDiff.compare(
                List.of(new TestCase("shop.ListTest#lists", Status.PASSED, null), new TestCase("shop.PostServiceTest#gets", Status.PASSED, null)),
                List.of(new TestCase("shop.ListTest#lists", Status.FAILED, "x"), new TestCase("shop.PostServiceTest#gets", Status.FAILED, "y")));

        Map<String, Object> result = TestRunService.selectionResult(s, diff);

        assertThat(result.get("missed")).asList().containsExactly("shop.ListTest#lists");
        assertThat(result.get("impacted")).isEqualTo(1);
    }

    @Test
    void topLevelHelpers() {
        assertThat(TestSelector.topLevel("a.b.FooTest.Inner", "src/test/java/a/b/FooTest.java")).contains("a.b.FooTest");
        assertThat(TestSelector.topLevelOfTrace("a.b.FooTest$Inner#t")).isEqualTo("a.b.FooTest");
        assertThat(TestSelector.topLevelOfTrace("a.b.FooTest#t")).isEqualTo("a.b.FooTest");
    }

    private static TestClass test(TestSelection s, String name) {
        return s.tests().stream().filter(t -> t.className().equals(name)).findFirst().orElseThrow();
    }

    private static ChangedFile file(String path, String status, String patch) {
        return new ChangedFile(path, status, 1, 1, patch, null, PatchParser.parse(patch));
    }

    private static void write(Path root, String path, String content) throws IOException {
        Path p = root.resolve(path);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }
}
