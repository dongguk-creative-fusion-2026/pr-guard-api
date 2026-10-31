package com.prguard.sonar;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prguard.analysis.AnalysisContext;
import com.prguard.analysis.Category;
import com.prguard.analysis.Finding;
import com.prguard.analysis.PullInfo;
import com.prguard.analysis.Severity;
import com.prguard.events.EventSink;
import com.prguard.events.StageStatus;
import com.prguard.github.GitHubClient;
import com.prguard.github.GitHubProperties;
import com.prguard.github.RepoRef;
import com.prguard.graph.GraphProperties;
import com.prguard.sonar.SonarService.Plan;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class SonarServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Plan PLAN = new Plan("https://sonarcloud.io", "org", "prguard_o_board", 7, "feat/x", "main");

    private static JsonNode json(String s) throws Exception {
        return MAPPER.readTree(s);
    }

    @Test
    void keepsOnlyIssuesOnAddedLines() throws Exception {
        JsonNode body = json("""
                {"total": 3, "issues": [
                  {"key": "AX1", "rule": "java:S2077", "component": "prguard_o_board:src/main/java/a/Repo.java",
                   "line": 12, "message": "Make sure using a dynamically formatted SQL query is safe here.",
                   "type": "VULNERABILITY", "severity": "CRITICAL", "hash": "h1"},
                  {"key": "AX2", "rule": "java:S1192", "component": "prguard_o_board:src/main/java/a/Repo.java",
                   "line": 50, "message": "Define a constant", "type": "CODE_SMELL", "severity": "MINOR"},
                  {"key": "AX3", "rule": "java:S1135", "component": "prguard_o_board:src/main/java/a/Repo.java",
                   "message": "file-level", "type": "CODE_SMELL", "severity": "INFO"}
                ]}
                """);

        List<Finding> found = SonarService.issueFindings(body, PLAN, Map.of("src/main/java/a/Repo.java", Set.of(12)), 50);

        assertThat(found).singleElement().satisfies(f -> {
            assertThat(f.ruleId()).isEqualTo("SONAR_ISSUE");
            assertThat(f.file()).isEqualTo("src/main/java/a/Repo.java");
            assertThat(f.line()).isEqualTo(12);
            assertThat(f.category()).isEqualTo(Category.SECURITY);
            assertThat(f.severity()).isEqualTo(Severity.MAJOR);
            assertThat(f.message()).contains("java:S2077");
            assertThat(f.evidence()).contains("pullRequest=7").contains("open=AX1");
            assertThat(f.anchor()).isEqualTo("sonar:java:S2077:h1");
        });
    }

    @Test
    void sonarAloneNeverBlocks() {
        assertThat(SonarService.severity("BUG", "BLOCKER")).isEqualTo(Severity.MAJOR);
        assertThat(SonarService.severity("BUG", "MAJOR")).isEqualTo(Severity.MINOR);
        assertThat(SonarService.severity("CODE_SMELL", "BLOCKER")).isEqualTo(Severity.MINOR);
        assertThat(SonarService.severity("CODE_SMELL", "MAJOR")).isEqualTo(Severity.INFO);
        assertThat(SonarService.category("BUG")).isEqualTo(Category.IMPACT);
        assertThat(SonarService.category("CODE_SMELL")).isEqualTo(Category.RISK);
    }

    @Test
    void readsPullRequestMeasuresFromPeriod() throws Exception {
        JsonNode body = json("""
                {"component": {"measures": [
                  {"metric": "new_coverage", "period": {"index": 1, "value": "62.5"}},
                  {"metric": "new_duplicated_lines_density", "periods": [{"index": 1, "value": "3.1"}]},
                  {"metric": "cognitive_complexity", "value": "41"}
                ]}}
                """);
        assertThat(SonarService.measures(body)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "new_coverage", "62.5", "new_duplicated_lines_density", "3.1", "cognitive_complexity", "41"));
    }

    @Test
    void listsFailedGateConditions() throws Exception {
        JsonNode gate = json("""
                {"status": "ERROR", "conditions": [
                  {"status": "ERROR", "metricKey": "new_coverage", "comparator": "LT", "errorThreshold": "80", "actualValue": "62.5"},
                  {"status": "OK", "metricKey": "new_duplicated_lines_density", "comparator": "GT", "errorThreshold": "3"}
                ]}
                """);
        assertThat(SonarService.failedConditions(gate)).containsExactly("new_coverage 62.5 (기준 LT 80)");
    }

    @Test
    void projectKeyIsSanitized() {
        assertThat(SonarService.projectKey("prguard_", new RepoRef("dongguk-creative", "pr guard!")))
                .isEqualTo("prguard_dongguk-creative_pr_guard_");
    }

    @Test
    void registersPlanAndHandsTokenOutOnce() {
        SonarProperties props = new SonarProperties(true, null, "org", "tok", null, null, null, null, null, 0);
        List<String> created = new ArrayList<>();
        SonarClient client = new SonarClient(RestClient.builder(), props) {
            @Override
            public void ensureProject(String key, String name) {
                created.add(key + " " + name);
            }
        };
        SonarService service = new SonarService(props, client, null, null);
        PullInfo pull = new PullInfo(new RepoRef("o", "board"), 7, "t", "", "me", "main", "feat/x", "abc", List.of());

        assertThat(service.register(pull, EventSink.NONE, true)).isTrue();

        assertThat(created).containsExactly("prguard_o_board o/board");
        assertThat(service.plan("abc")).hasValueSatisfying(p -> {
            assertThat(p.pullRequest()).isEqualTo(7);
            assertThat(p.branch()).isEqualTo("feat/x");
            assertThat(p.base()).isEqualTo("main");
        });
        assertThat(service.takeToken("abc")).contains("tok");
        assertThat(service.takeToken("abc")).isEmpty();
        assertThat(service.takeToken("other")).isEmpty();
    }

    @Test
    void withoutRunnerDispatchesActionsScanAndWaitsForItsResult() {
        SonarProperties props = new SonarProperties(true, null, "org", "tok", null, null, null, Duration.ofSeconds(1),
                null, 0);
        SonarClient client = new SonarClient(RestClient.builder(), props) {
            @Override
            public void ensureProject(String key, String name) {
            }
        };
        Map<String, String> dispatched = new HashMap<>();
        GitHubClient github = new GitHubClient(RestClient.builder(), new GitHubProperties("http://localhost", null, false)) {
            @Override
            public void dispatchWorkflow(RepoRef repo, String workflow, String ref, Map<String, String> inputs, String token) {
                dispatched.put("target", repo.fullName() + " " + workflow + "@" + ref);
                dispatched.putAll(inputs);
            }
        };
        GraphProperties workflows = new GraphProperties(null, "acme/pr-guard-api", "repo-graph.yml", "main", "dt",
                null, null, null, 0, 0, null, null);
        SonarService service = new SonarService(props, client, github, workflows);
        PullInfo pull = new PullInfo(new RepoRef("o", "board"), 7, "t", "", "me", "main", "feat/x", "abc", List.of());

        assertThat(service.register(pull, EventSink.NONE, false)).isTrue();

        assertThat(dispatched).containsEntry("target", "acme/pr-guard-api sonar.yml@main")
                .containsEntry("head_sha", "abc").containsEntry("pr", "7").containsEntry("base_ref", "main")
                .containsEntry("project_key", "prguard_o_board").containsEntry("organization", "org");
        assertThat(service.waitingForActions("abc")).isTrue();
        assertThat(service.takeToken("abc")).contains("tok");

        service.reported("abc", null, "sonar-scanner 실패");
        assertThat(service.waitingForActions("abc")).isFalse();
        List<String> failed = new ArrayList<>();
        EventSink sink = (stage, status, message, data) -> {
            if (status == StageStatus.FAILED) {
                failed.add(stage + " " + message);
            }
        };
        assertThat(service.collect(pull, new AnalysisContext(pull, List.of()), sink)).isEmpty();
        assertThat(failed).containsExactly("EXEC_SONAR sonar-scanner 실패");
    }

    @Test
    void actionsScanThatNeverReportsTimesOut() {
        SonarProperties props = new SonarProperties(true, null, "org", "tok", null, null, null, Duration.ofMillis(10),
                null, 0);
        SonarClient client = new SonarClient(RestClient.builder(), props) {
            @Override
            public void ensureProject(String key, String name) {
            }
        };
        GitHubClient github = new GitHubClient(RestClient.builder(), new GitHubProperties("http://localhost", null, false)) {
            @Override
            public void dispatchWorkflow(RepoRef repo, String workflow, String ref, Map<String, String> inputs, String token) {
            }
        };
        GraphProperties workflows = new GraphProperties(null, "acme/pr-guard-api", "repo-graph.yml", "main", null,
                null, null, null, 0, 0, null, null);
        SonarService service = new SonarService(props, client, github, workflows);
        PullInfo pull = new PullInfo(new RepoRef("o", "board"), 7, "t", "", "me", "main", "feat/x", "abc", List.of());
        service.register(pull, EventSink.NONE, false);

        List<String> failed = new ArrayList<>();
        EventSink sink = (stage, status, message, data) -> {
            if (status == StageStatus.FAILED) {
                failed.add(message);
            }
        };
        assertThat(service.collect(pull, new AnalysisContext(pull, List.of()), sink)).isEmpty();
        assertThat(failed).singleElement().asString().contains("GitHub Actions 스캔");
        assertThat(service.waitingForActions("abc")).isFalse();
    }

    @Test
    void inactiveWithoutToken() {
        SonarProperties props = new SonarProperties(true, null, "org", "", null, null, null, null, null, 0);
        SonarService service = new SonarService(props, null, null, null);
        PullInfo pull = new PullInfo(new RepoRef("o", "board"), 7, "t", "", "me", "main", "feat/x", "abc", List.of());
        assertThat(service.register(pull, EventSink.NONE, true)).isFalse();
        assertThat(service.plan("abc")).isEmpty();
    }
}
