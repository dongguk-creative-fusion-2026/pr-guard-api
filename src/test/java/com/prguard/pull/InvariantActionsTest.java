package com.prguard.pull;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.prguard.github.GitHubClient;
import com.prguard.github.RepoRef;
import com.prguard.project.ProjectService;
import java.io.ByteArrayOutputStream;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class InvariantActionsTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final String head = "a".repeat(40), base = "b".repeat(40);

    private byte[] zip(String name, String json) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(name)); zip.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
        }
        return bytes.toByteArray();
    }
    private String noCounterexample() throws Exception {
        return mapper.writeValueAsString(Map.of("status", "no_counterexample", "max_examples", 100,
                "baseExecuted", false, "headSha", head, "baseSha", base, "ruleId", "post-author-preserved-on-update"));
    }

    @Test
    void bindsWorkflowToRequestRefAndFirstAttempt() throws Exception {
        UUID request = UUID.randomUUID();
        var run = mapper.valueToTree(Map.of("display_title", "invariant " + request, "path", ".github/workflows/invariant-check.yml",
                "event", "workflow_dispatch", "head_branch", "main", "run_attempt", 1));
        assertThat(InvariantExecutionService.matches(run, request, "main")).isTrue();
        assertThat(InvariantExecutionService.matches(run, UUID.randomUUID(), "main")).isFalse();
        assertThat(InvariantExecutionService.matches(run, request, "untrusted")).isFalse();
        ((com.fasterxml.jackson.databind.node.ObjectNode) run).put("run_attempt", 2);
        assertThat(InvariantExecutionService.matches(run, request, "main")).isFalse();
    }

    @Test
    void validatesArchivePathsSizeCommitsAndObservedProperty() throws Exception {
        var reader = new InvariantResultReader(mapper);
        assertThat(reader.read(zip("invariant-result.json", noCounterexample()), head, base).path("status").asText())
                .isEqualTo("no_counterexample");
        assertThatThrownBy(() -> reader.read(zip("../invariant-result.json", noCounterexample()), head, base)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader.read(zip("invariant-result.json", "x".repeat(64001)), head, base)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader.read(zip("invariant-result.json", noCounterexample()), "c".repeat(40), base)).isInstanceOf(IllegalArgumentException.class);
        JsonNode result = mapper.readTree(noCounterexample());
        ((com.fasterxml.jackson.databind.node.ObjectNode) result).put("status", "new_violation");
        assertThatThrownBy(() -> reader.read(zip("invariant-result.json", result.toString()), head, base)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void collectorAcceptsOnlyCompletedMatchingWorkflowAndArtifact() throws Exception {
        var jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        var github = mock(GitHubClient.class);
        var service = new InvariantExecutionService(mock(ProjectService.class), github, new InvariantRunRepository(jdbc, mapper), mapper);
        UUID id = UUID.randomUUID();
        var active = new InvariantRunRepository.Active(id, head, base, "o/r", "main", 42L, OffsetDateTime.now(), null);
        when(jdbc.sql(startsWith("SELECT id,head_sha")).query(InvariantRunRepository.Active.class).list()).thenReturn(List.of(active));
        when(github.workflowDetails(new RepoRef("o", "r"), 42L)).thenReturn(mapper.valueToTree(Map.of(
                "id", 42, "display_title", "invariant " + id, "path", ".github/workflows/invariant-check.yml",
                "event", "workflow_dispatch", "head_branch", "main", "run_attempt", 1, "status", "completed", "conclusion", "success")));
        when(github.workflowArtifacts(new RepoRef("o", "r"), 42L)).thenReturn(mapper.valueToTree(Map.of("artifacts", List.of(
                Map.of("id", 99, "name", "invariant-" + id + "-1", "expired", false, "size_in_bytes", 500)))));
        when(github.invariantArtifact(new RepoRef("o", "r"), 99L)).thenReturn(zip("invariant-result.json", noCounterexample()));
        var finished = mock(JdbcClient.StatementSpec.class, RETURNS_SELF);
        when(jdbc.sql(startsWith("UPDATE invariant_runs SET status=:status"))).thenReturn(finished);
        service.collect();
        verify(finished).param("status", "DONE");
        verify(finished).param("id", id);
    }

    @Test
    void acceptsReproducedRegressionAndRejectsMislabeledVerdict() throws Exception {
        var reader = new InvariantResultReader(mapper);
        var result = mapper.createObjectNode();
        result.put("status", "new_violation").put("headSha", head).put("baseSha", base)
                .put("ruleId", "post-author-preserved-on-update").put("baseExecuted", true);
        result.set("input", mapper.valueToTree(Map.of("title", "0", "content", "0")));
        result.set("base", mapper.valueToTree(List.of(
                Map.of("status", 200, "author_before", 1, "author_after", 1),
                Map.of("status", 200, "author_before", 3, "author_after", 3))));
        result.set("head", mapper.valueToTree(List.of(
                Map.of("status", 200, "author_before", 173, "author_after", 174),
                Map.of("status", 200, "author_before", 175, "author_after", 176))));
        assertThat(reader.read(zip("invariant-result.json", result.toString()), head, base).path("status").asText())
                .isEqualTo("new_violation");
        result.put("status", "existing_violation");
        assertThatThrownBy(() -> reader.read(zip("invariant-result.json", result.toString()), head, base))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void expiredWorkNeverStaysRunningForever() {
        var jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        var github = mock(GitHubClient.class);
        var service = new InvariantExecutionService(null, github, new InvariantRunRepository(jdbc, mapper), mapper);
        var active = new InvariantRunRepository.Active(UUID.randomUUID(), head, base, "o/r", "main", null,
                OffsetDateTime.now().minusMinutes(31), null);
        when(jdbc.sql(startsWith("SELECT id,head_sha")).query(InvariantRunRepository.Active.class).list()).thenReturn(List.of(active));
        var finished = mock(JdbcClient.StatementSpec.class, RETURNS_SELF);
        when(jdbc.sql(startsWith("UPDATE invariant_runs SET status=:status"))).thenReturn(finished);
        service.collect();
        verify(finished).param("status", "FAILED");
        verifyNoInteractions(github);
    }
}
