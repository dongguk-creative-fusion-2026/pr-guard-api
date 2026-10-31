package com.prguard.analysis.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prguard.analysis.Category;
import com.prguard.analysis.Finding;
import com.prguard.analysis.Severity;
import com.prguard.analysis.rules.SemgrepAnalyzer.Hit;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SemgrepAnalyzerTest {

    private static final String SQLI = "java.lang.security.audit.sqli.jdbc-sqli.jdbc-sqli";

    private static Hit hit(String file, int line, String snippet) {
        return new Hit(file, SQLI, line, line, "Detected a formatted string in a SQL statement.", "ERROR", "security",
                "CWE-89: SQL Injection", snippet);
    }

    @Test
    void reportsOnlyHeadHitsOnAddedLines() {
        String file = "src/main/java/a/PostRepository.java";
        List<Finding> found = SemgrepAnalyzer.findings(
                List.of(hit(file, 12, "stmt.executeQuery(\"select * from post where id=\" + id);"),
                        hit(file, 40, "stmt.execute(sql);")),
                List.of(),
                Map.of(file, Set.of(12)));

        assertThat(found).singleElement().satisfies(f -> {
            assertThat(f.ruleId()).isEqualTo("SEMGREP");
            assertThat(f.category()).isEqualTo(Category.SECURITY);
            assertThat(f.severity()).isEqualTo(Severity.MAJOR);
            assertThat(f.line()).isEqualTo(12);
            assertThat(f.title()).isEqualTo("Semgrep: jdbc-sqli");
            assertThat(f.evidence()).contains(SQLI).contains("CWE-89");
            assertThat(f.source()).isEqualTo(Finding.TOOL);
        });
    }

    @Test
    void dropsProblemAlreadyInBaseEvenIfLineMovedOrReindented() {
        String file = "src/main/java/a/PostRepository.java";
        List<Finding> found = SemgrepAnalyzer.findings(
                List.of(hit(file, 20, "    stmt.execute(sql);")),
                List.of(hit(file, 15, "stmt.execute(sql);")),
                Map.of(file, Set.of(20)));

        assertThat(found).isEmpty();
    }

    @Test
    void parsesSemgrepJsonForOneSide() throws Exception {
        Path head = Path.of("head", "src", "A.java").toAbsolutePath().normalize();
        Path base = Path.of("base", "src", "A.java").toAbsolutePath().normalize();
        String json = """
                {"results": [
                  {"check_id": "%s", "path": %s, "start": {"line": 2}, "end": {"line": 3},
                   "extra": {"message": "sqli", "severity": "WARNING", "lines": "requires login",
                             "metadata": {"category": "security", "cwe": ["CWE-89: SQL Injection"]}}},
                  {"check_id": "x.y.other", "path": %s, "start": {"line": 1}, "end": {"line": 1},
                   "extra": {"message": "base only", "severity": "INFO", "metadata": {}}}
                ], "errors": []}
                """.formatted(SQLI, quote(head), quote(base));
        JsonNode out = new ObjectMapper().readTree(json);

        List<Hit> hits = SemgrepAnalyzer.hits(out, Map.of(head, "src/A.java"), p -> "a\n  b();\n  c();\nd\n");

        assertThat(hits).singleElement().satisfies(h -> {
            assertThat(h.file()).isEqualTo("src/A.java");
            assertThat(h.start()).isEqualTo(2);
            assertThat(h.end()).isEqualTo(3);
            assertThat(h.severity()).isEqualTo("WARNING");
            assertThat(h.cwe()).isEqualTo("CWE-89: SQL Injection");
            // "requires login" 대신 파일에서 읽은 코드
            assertThat(h.snippet()).isEqualTo("b();\n  c();");
        });
    }

    @Test
    void mapsSeverityAndName() {
        assertThat(SemgrepAnalyzer.severity("ERROR")).isEqualTo(Severity.MAJOR);
        assertThat(SemgrepAnalyzer.severity("WARNING")).isEqualTo(Severity.MINOR);
        assertThat(SemgrepAnalyzer.severity("INFO")).isEqualTo(Severity.INFO);
        assertThat(SemgrepAnalyzer.shortName("plain")).isEqualTo("plain");
    }

    @Test
    void nonSecurityRuleIsGeneral() {
        Hit h = new Hit("A.java", "java.lang.correctness.eqeq", 1, 1, "x == x", "WARNING", "correctness", null, "a == a");
        List<Finding> found = SemgrepAnalyzer.findings(List.of(h), List.of(), Map.of("A.java", Set.of(1)));
        assertThat(found).singleElement().satisfies(f -> {
            assertThat(f.category()).isEqualTo(Category.GENERAL);
            assertThat(f.severity()).isEqualTo(Severity.MINOR);
        });
    }

    private static String quote(Path p) {
        return "\"" + p.toString().replace("\\", "\\\\") + "\"";
    }
}
