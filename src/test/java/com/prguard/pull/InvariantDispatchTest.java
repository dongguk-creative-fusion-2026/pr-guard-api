package com.prguard.pull;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prguard.common.ApiException;
import com.prguard.github.GitHubClient;
import com.prguard.github.GitHubPull;
import com.prguard.github.RepoRef;
import com.prguard.project.Project;
import com.prguard.project.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Map;

class InvariantDispatchTest {
    ProjectService projects = mock(ProjectService.class);
    GitHubClient github = mock(GitHubClient.class);
    JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
    JdbcClient.StatementSpec insert = mock(JdbcClient.StatementSpec.class, RETURNS_SELF);
    JdbcClient.StatementSpec finish = mock(JdbcClient.StatementSpec.class, RETURNS_SELF);
    InvariantExecutionService service = new InvariantExecutionService(projects, github, new InvariantRunRepository(jdbc, new ObjectMapper()), new ObjectMapper());
    RepoRef target = new RepoRef("dongguk-creative-fusion-2026", "pr-guard-sandbox");
    String head = "a".repeat(40), base = "b".repeat(40);

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "workflowRepo", "o/workflows");
        ReflectionTestUtils.setField(service, "workflowRef", "main");
        Project project = mock(Project.class);
        when(project.owner()).thenReturn(target.owner()); when(project.name()).thenReturn(target.name()); when(project.ref()).thenReturn(target);
        when(projects.get(1)).thenReturn(project);
        GitHubPull pull = mock(GitHubPull.class, RETURNS_DEEP_STUBS);
        when(pull.head().sha()).thenReturn(head); when(pull.base().sha()).thenReturn(base);
        when(github.getPull(target, 24)).thenReturn(pull); when(github.mergeBase(target, base, head)).thenReturn(base);
        when(jdbc.sql(startsWith("INSERT INTO invariant_runs"))).thenReturn(insert);
        when(jdbc.sql(startsWith("UPDATE invariant_runs SET status=:status"))).thenReturn(finish);
    }

    private InvariantRuleCompiler.Candidate candidate() { return InvariantRuleCompiler.compile("게시글 수정 전후 작성자 ID는 같아야 한다"); }

    @Test
    void dispatchesPinnedPlanAndReturnsQueuedWithoutStartingLocalProcesses() {
        var result = service.start(1, 24, candidate());
        assertThat(result.path("status").asText()).isEqualTo("QUEUED");
        verify(github).dispatchWorkflow(new RepoRef("o", "workflows"), "invariant-check.yml", "main",
                Map.of("run_id", result.path("id").asText(), "head_sha", head, "base_sha", base), null);
    }

    @Test
    void recordsDispatchFailureInsteadOfLeavingAnActiveSlot() {
        doThrow(new IllegalStateException("forbidden")).when(github).dispatchWorkflow(any(), anyString(), anyString(), anyMap(), isNull());
        assertThatThrownBy(() -> service.start(1, 24, candidate())).isInstanceOf(ApiException.class);
        verify(finish).param("status", "FAILED");
    }

    @Test
    void uniqueDatabaseSlotPreventsDuplicateDispatchAcrossInstances() {
        when(insert.update()).thenThrow(new DuplicateKeyException("busy"));
        assertThatThrownBy(() -> service.start(1, 24, candidate())).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status().value()).isEqualTo(429));
        verify(github, never()).dispatchWorkflow(any(), anyString(), anyString(), anyMap(), any());
    }
}
