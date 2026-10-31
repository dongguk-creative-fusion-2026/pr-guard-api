package com.prguard.trivy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prguard.analysis.Category;
import com.prguard.analysis.Finding;
import com.prguard.analysis.Severity;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrivyReportParserTest {
    @TempDir Path source;
    private final ObjectMapper mapper = new ObjectMapper();
    private final TrivyReportParser parser = new TrivyReportParser(mapper);

    @Test
    void readsCveAndFailedConfigurationWithoutLeakingSecretsOrInventingLines() {
        var report = parser.parse("""
                {"SchemaVersion":2,"ArtifactType":"filesystem","Results":[
                  {"Target":"gradle.lockfile","Type":"gradle","Vulnerabilities":[
                    {"VulnerabilityID":"CVE-2021-44228","PkgName":"org.apache.logging.log4j:log4j-core",
                     "InstalledVersion":"2.14.1","FixedVersion":"2.17.1","Severity":"CRITICAL",
                     "Title":"Log4Shell","PrimaryURL":"https://avd.aquasec.com/nvd/cve-2021-44228"}]},
                  {"Target":"deploy/pod.yaml","Type":"kubernetes","Misconfigurations":[
                    {"ID":"KSV001","Status":"FAIL","Severity":"HIGH","Title":"Privileged container",
                     "Message":"Container is privileged","Resolution":"Set privileged to false",
                     "CauseMetadata":{"Resource":"Pod.demo","StartLine":12}},
                    {"ID":"KSV002","Status":"PASS","Severity":"HIGH"}],
                   "Secrets":[{"Match":"DO-NOT-REPORT-THIS"}]}]}
                """, source);
        assertThat(report.targets()).isEqualTo(2);
        assertThat(report.findings()).hasSize(2);
        Finding cve = report.findings().get(0);
        assertThat(cve.ruleId()).isEqualTo("TRIVY_VULNERABILITY");
        assertThat(cve.category()).isEqualTo(Category.SECURITY);
        assertThat(cve.severity()).isEqualTo(Severity.MAJOR);
        assertThat(cve.source()).isEqualTo(Finding.TOOL);
        assertThat(cve.line()).isNull();
        assertThat(cve.message()).contains("2.14.1", "2.17.1", "별도 확인");
        Finding config = report.findings().get(1);
        assertThat(config.file()).isEqualTo("deploy/pod.yaml");
        assertThat(config.line()).isEqualTo(12);
        assertThat(config.message()).contains("Set privileged to false");
        assertThat(report.toString()).doesNotContain("DO-NOT-REPORT-THIS");
    }

    @Test
    void vulnerableVersionChangeAndMissingFixDoNotChangeIdentity() {
        String json = """
                {"SchemaVersion":2,"ArtifactType":"filesystem","Results":[
                 {"Target":"package-lock.json","Type":"npm","Vulnerabilities":[
                  {"VulnerabilityID":"CVE-example","PkgName":"example","InstalledVersion":"1.0",
                   "Severity":"MEDIUM"}]}]}
                """;
        Finding old = parser.parse(json, source).findings().get(0);
        Finding updated = parser.parse(json.replace("1.0", "1.1"), source).findings().get(0);
        assertThat(old.fingerprint()).isEqualTo(updated.fingerprint());
        assertThat(old.message()).contains("공개된 수정 버전 없음");
        assertThat(old.severity()).isEqualTo(Severity.MINOR);
    }

    @Test
    void retainsSeparateResourcesButDeduplicatesIdenticalResults() {
        String item = """
                {"ID":"KSV001","Status":"FAIL","Severity":"HIGH",
                 "CauseMetadata":{"Resource":"Pod.first","StartLine":5}}
                """;
        String json = """
                {"SchemaVersion":2,"ArtifactType":"filesystem","Results":[
                 {"Target":"pod.yaml","Type":"kubernetes","Misconfigurations":[%s,%s,%s]}]}
                """.formatted(item, item, item.replace("Pod.first", "Pod.second").replace("5", "20"));
        assertThat(parser.parse(json, source).findings()).hasSize(2);
        Finding shifted = parser.parse(json.replace("20", "30"), source).findings().get(1);
        assertThat(shifted.fingerprint()).isEqualTo(parser.parse(json, source).findings().get(1).fingerprint());
    }

    @Test
    void normalizesAbsoluteAndWindowsSeparatedTargets() throws Exception {
        String json = mapper.writeValueAsString(Map.of("SchemaVersion", 2, "ArtifactType", "filesystem",
                "Results", List.of(Map.of("Target", source.resolve("Dockerfile").toString()))));
        assertThat(parser.parse(json, source).targets()).isEqualTo(1);
        assertThat(parser.parse(json.replace("Dockerfile", "deploy\\\\Dockerfile"), source).targets()).isEqualTo(1);
    }

    @Test
    void acceptsAnExplicitEmptyScanButRejectsCorruptOrUnexpectedReports() {
        assertThat(parser.parse("{\"SchemaVersion\":2,\"ArtifactType\":\"filesystem\"}", source).findings()).isEmpty();
        for (String json : List.of("", "null", "{}", "not-json", "[]",
                "{\"SchemaVersion\":2,\"ArtifactType\":\"filesystem\",\"Results\":{}}")) {
            assertThatThrownBy(() -> parser.parse(json, source)).isInstanceOf(IllegalArgumentException.class);
        }
        String escaping = """
                {"SchemaVersion":2,"ArtifactType":"filesystem","Results":[{"Target":"../outside"}]}
                """;
        assertThatThrownBy(() -> parser.parse(escaping, source)).hasMessageContaining("outside");
        String incomplete = """
                {"SchemaVersion":2,"ArtifactType":"filesystem","Results":[
                 {"Target":"pom.xml","Vulnerabilities":[{"PkgName":"example"}]}]}
                """;
        assertThatThrownBy(() -> parser.parse(incomplete, source)).hasMessageContaining("VulnerabilityID");
    }
}
