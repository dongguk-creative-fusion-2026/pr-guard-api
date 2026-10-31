package com.prguard.analysis.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prguard.analysis.Category;
import com.prguard.analysis.Finding;
import com.prguard.analysis.Severity;
import com.prguard.analysis.rules.SemgrepScanner.Hit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SemgrepScannerTest {

    private static final String SQLI = "java.lang.security.audit.sqli.jdbc-sqli.jdbc-sqli";

    private static Hit hit(String file, int line, String snippet) {
        return new Hit(file, SQLI, line, line, "Detected a formatted string in a SQL statement.", "ERROR", "security",
                "CWE-89: SQL Injection", snippet, null);
    }

    private static Hit hit(String checkId, String severity, String category, String fix) {
        return new Hit("A.java", checkId, 1, 1, "msg", severity, category, null, "code();", fix);
    }

    private static Finding one(Hit h, Set<String> teamIds) {
        List<Finding> found = SemgrepScanner.findings(List.of(h), List.of(), Map.of("A.java", Set.of(1)), teamIds);
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    @Test
    void reportsOnlyHeadHitsOnAddedLines() {
        String file = "src/main/java/a/PostRepository.java";
        List<Finding> found = SemgrepScanner.findings(
                List.of(hit(file, 12, "stmt.executeQuery(\"select * from post where id=\" + id);"),
                        hit(file, 40, "stmt.execute(sql);")),
                List.of(),
                Map.of(file, Set.of(12)),
                Set.of());

        assertThat(found).singleElement().satisfies(f -> {
            assertThat(f.ruleId()).isEqualTo("SEMGREP");
            assertThat(f.category()).isEqualTo(Category.SECURITY);
            assertThat(f.severity()).isEqualTo(Severity.MAJOR);
            assertThat(f.line()).isEqualTo(12);
            assertThat(f.title()).isEqualTo("Semgrep · 보안: jdbc-sqli");
            assertThat(f.evidence()).contains(SQLI).contains("CWE-89");
            assertThat(f.source()).isEqualTo(Finding.TOOL);
        });
    }

    @Test
    void dropsProblemAlreadyInBaseEvenIfLineMovedOrReindented() {
        String file = "src/main/java/a/PostRepository.java";
        List<Finding> found = SemgrepScanner.findings(
                List.of(hit(file, 20, "    stmt.execute(sql);")),
                List.of(hit(file, 15, "stmt.execute(sql);")),
                Map.of(file, Set.of(20)),
                Set.of());

        assertThat(found).isEmpty();
    }

    @Test
    void correctnessIsBugPatternInRisk() {
        Finding f = one(hit("java.lang.correctness.no-string-eqeq.no-string-eqeq", "WARNING", "correctness", null), Set.of());
        assertThat(f.category()).isEqualTo(Category.RISK);
        assertThat(f.severity()).isEqualTo(Severity.MINOR);
        assertThat(f.title()).isEqualTo("Semgrep · 버그 패턴: no-string-eqeq");
    }

    @Test
    void bestPracticeNeverBlocksMerge() {
        Finding f = one(hit("java.lang.best-practice.x", "ERROR", "best-practice", null), Set.of());
        assertThat(f.title()).startsWith("Semgrep · 모범 사례:");
        // ERROR 여도 MAJOR 로 세지 않는다
        assertThat(f.severity()).isEqualTo(Severity.MINOR);
    }

    @Test
    void migrationCarriesFixSuggestion() {
        Finding f = one(hit("tmp.prguard-semgrep-1.prguard-boxed-constructor", "WARNING", "migration", "Integer.valueOf(5)"),
                Set.of());
        assertThat(f.category()).isEqualTo(Category.RISK);
        assertThat(f.title()).isEqualTo("Semgrep · 마이그레이션: prguard-boxed-constructor");
        assertThat(f.message()).contains("수정 제안: `Integer.valueOf(5)`");
        assertThat(f.evidence()).endsWith("→ Integer.valueOf(5)");
    }

    @Test
    void repoRuleIsTeamRuleWhateverItsCategory() {
        // 로컬 규칙 파일의 check_id 는 경로가 앞에 붙는다
        Finding f = one(hit("base..semgrep.no-system-out", "WARNING", "security", null), Set.of("no-system-out"));
        assertThat(f.title()).isEqualTo("Semgrep · 팀 규칙: no-system-out");
        assertThat(f.category()).isEqualTo(Category.RISK);
    }

    @Test
    void classifiesByCategory() {
        assertThat(SemgrepPurpose.of("a", "security", Set.of())).isEqualTo(SemgrepPurpose.SECURITY);
        assertThat(SemgrepPurpose.of("a", "correctness", Set.of())).isEqualTo(SemgrepPurpose.BUG);
        assertThat(SemgrepPurpose.of("a", "compatibility", Set.of())).isEqualTo(SemgrepPurpose.MIGRATION);
        assertThat(SemgrepPurpose.of("a", "performance", Set.of())).isEqualTo(SemgrepPurpose.PRACTICE);
        assertThat(SemgrepPurpose.of("a", null, Set.of())).isEqualTo(SemgrepPurpose.PRACTICE);
        // id 가 다른 규칙 이름의 끝과 우연히 겹치는 것은 팀 규칙이 아니다
        assertThat(SemgrepPurpose.of("x.my-no-system-out", "correctness", Set.of("no-system-out")))
                .isEqualTo(SemgrepPurpose.BUG);
    }

    @Test
    void parsesSemgrepJsonForOneSide() throws Exception {
        Path head = Path.of("head", "src", "A.java").toAbsolutePath().normalize();
        Path base = Path.of("base", "src", "A.java").toAbsolutePath().normalize();
        String json = """
                {"results": [
                  {"check_id": "%s", "path": %s, "start": {"line": 2}, "end": {"line": 3},
                   "extra": {"message": "sqli", "severity": "WARNING", "lines": "requires login", "fix": "safe()",
                             "metadata": {"category": "security", "cwe": ["CWE-89: SQL Injection"]}}},
                  {"check_id": "x.y.other", "path": %s, "start": {"line": 1}, "end": {"line": 1},
                   "extra": {"message": "base only", "severity": "INFO", "metadata": {}}}
                ], "errors": []}
                """.formatted(SQLI, quote(head), quote(base));
        JsonNode out = new ObjectMapper().readTree(json);

        List<Hit> hits = SemgrepScanner.hits(out, Map.of(head, "src/A.java"), p -> "a\n  b();\n  c();\nd\n");

        assertThat(hits).singleElement().satisfies(h -> {
            assertThat(h.file()).isEqualTo("src/A.java");
            assertThat(h.start()).isEqualTo(2);
            assertThat(h.end()).isEqualTo(3);
            assertThat(h.severity()).isEqualTo("WARNING");
            assertThat(h.cwe()).isEqualTo("CWE-89: SQL Injection");
            assertThat(h.fix()).isEqualTo("safe()");
            // "requires login" 대신 파일에서 읽은 코드
            assertThat(h.snippet()).isEqualTo("b();\n  c();");
        });
    }

    @Test
    void findsRepoTeamRules(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(".semgrep.yml"), """
                rules:
                  - id: no-system-out
                    languages: [java]
                    severity: WARNING
                    message: Logger 를 쓴다
                    pattern: System.out.println(...)
                """);
        Path dir = Files.createDirectories(root.resolve(".semgrep/web"));
        Files.writeString(dir.resolve("layers.yaml"), "rules:\n  - id: no-repo-in-controller\n  - id: ''\n");
        Files.writeString(dir.resolve("README.md"), "not a rule");
        Files.writeString(root.resolve(".semgrep.yaml"), "rules: [oops");

        List<Path> files = SemgrepScanner.repoRuleFiles(root);

        assertThat(files).extracting(p -> root.relativize(p).toString().replace('\\', '/'))
                .containsExactly(".semgrep.yml", ".semgrep.yaml", ".semgrep/web/layers.yaml");
        assertThat(SemgrepScanner.ruleIds(files.get(0))).containsExactly("no-system-out");
        assertThat(SemgrepScanner.ruleIds(files.get(1))).isEmpty();
        assertThat(SemgrepScanner.ruleIds(files.get(2))).containsExactly("no-repo-in-controller");
    }

    @Test
    void bundledRulesAreOnClasspath() throws Exception {
        try (var in = getClass().getClassLoader().getResourceAsStream("semgrep/prguard-rules.yml")) {
            assertThat(in).isNotNull();
            String yaml = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(yaml).contains("prguard-boxed-constructor").contains("category: migration");
        }
    }

    @Test
    void mapsSeverityAndName() {
        assertThat(SemgrepScanner.severity("ERROR")).isEqualTo(Severity.MAJOR);
        assertThat(SemgrepScanner.severity("WARNING")).isEqualTo(Severity.MINOR);
        assertThat(SemgrepScanner.severity("INFO")).isEqualTo(Severity.INFO);
        assertThat(SemgrepScanner.shortName("plain")).isEqualTo("plain");
    }

    private static String quote(Path p) {
        return "\"" + p.toString().replace("\\", "\\\\") + "\"";
    }
}
