package com.prguard.analysis.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.prguard.analysis.AnalysisContext;
import com.prguard.analysis.Category;
import com.prguard.analysis.ChangedFile;
import com.prguard.analysis.Finding;
import com.prguard.analysis.Severity;
import com.prguard.diff.Patch;
import java.util.List;
import org.junit.jupiter.api.Test;

class HardcodedSecretAnalyzerTest {

    private final HardcodedSecretAnalyzer analyzer = new HardcodedSecretAnalyzer();

    private static ChangedFile file(String name, String patch) {
        return new ChangedFile(name, "modified", 1, 0, patch, null, Patch.EMPTY);
    }

    private List<Finding> run(ChangedFile... files) {
        return analyzer.analyze(new AnalysisContext(null, List.of(files)));
    }

    @Test
    void namedConstantWithLiteralIsMajor() {
        List<Finding> found = run(file("src/main/java/a/UserController.java", """
                @@ -10,3 +10,5 @@ public class UserController {
                 public class UserController {
                +
                +    private static final String ADMIN_TOKEN = "board-admin-2026-secret";
                 """));
        assertThat(found).singleElement().satisfies(f -> {
            assertThat(f.category()).isEqualTo(Category.SECURITY);
            assertThat(f.severity()).isEqualTo(Severity.MAJOR);
            assertThat(f.line()).isEqualTo(12);
            assertThat(f.title()).contains("ADMIN_TOKEN");
            assertThat(f.evidence()).contains("boar…").doesNotContain("2026-secret");
        });
    }

    @Test
    void knownKeyFormatIsBlocker() {
        List<Finding> found = run(file("src/main/java/a/Ai.java", """
                @@ -1,1 +1,2 @@
                 class Ai {
                +    String key = "sk-proj-abcdefghijklmnopqrstuvwxyz123456";
                """));
        assertThat(found).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo(Severity.BLOCKER);
            assertThat(f.title()).contains("OpenAI");
        });
    }

    @Test
    void settingFileValueIsFound() {
        List<Finding> found = run(file("src/main/resources/application.yml", """
                @@ -3,1 +3,2 @@
                 spring:
                +  datasource.password: s3cretPassw0rd
                """));
        assertThat(found).singleElement().satisfies(f -> assertThat(f.line()).isEqualTo(4));
    }

    @Test
    void ignoresInjectedValuesHeaderNamesTestsAndRemovedLines() {
        List<Finding> found = run(
                file("src/main/resources/application.yml", """
                        @@ -1,1 +1,2 @@
                         jwt:
                        +  secret: ${JWT_SECRET}
                        """),
                file("src/main/java/a/Web.java", """
                        @@ -1,1 +1,3 @@
                         class Web {
                        +    static final String TOKEN_HEADER = "X-Admin-Token";
                        +    String message = "토큰이 만료되었습니다";
                        -    String password = "old-pass-1234";
                        """),
                file("src/test/java/a/AuthTest.java", """
                        @@ -1,1 +1,2 @@
                         class AuthTest {
                        +    String password = "test-pass-1234";
                        """));
        assertThat(found).isEmpty();
    }

    @Test
    void addedLinesFollowHunkNumbers() {
        assertThat(HardcodedSecretAnalyzer.addedLines("""
                @@ -5,3 +7,4 @@
                 a
                -b
                +c
                 d
                +e
                """)).extracting(HardcodedSecretAnalyzer.AddedLine::line).containsExactly(8, 10);
    }
}
