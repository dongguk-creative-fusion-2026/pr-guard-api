package com.prguard.pull;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prguard.common.ApiException;
import com.prguard.github.*;
import com.prguard.project.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class InvariantSuggestionControllerTest {
    @Test
    void includesChangedCodeAndRelatedExistingTestsAtHead() throws Exception {
        var projects = mock(ProjectService.class);
        var github = mock(GitHubClient.class);
        var interpreter = mock(InvariantInterpreter.class);
        var settings = mock(InvariantSettingsController.class);
        var repo = new RepoRef("owner", "repo");
        var project = mock(Project.class);
        when(project.ref()).thenReturn(repo);
        when(projects.get(1)).thenReturn(project);
        var pull = mock(GitHubPull.class, RETURNS_DEEP_STUBS);
        when(pull.head().sha()).thenReturn("abc123");
        when(github.getPull(repo, 2)).thenReturn(pull);
        when(github.listPullFiles(repo, 2)).thenReturn(List.of(new GitHubPullFile("src/OrderService.java", "modified", 1, 1, "+ update", null)));
        when(github.getTree(repo, "abc123")).thenReturn(new ObjectMapper().readTree("""
                {"tree":[{"type":"blob","path":"src/test/OrderServiceTest.java"},{"type":"blob","path":"src/test/OtherTest.java"}]}
                """));
        when(github.getFile(repo, "src/test/OrderServiceTest.java", "abc123")).thenReturn(new GitHubClient.RepoFile("x", "y", "z", "assert stock >= 0"));
        when(interpreter.suggest(anyString())).thenReturn(new InvariantInterpreter.Interpretation("openai:model", List.of()));
        var result = new InvariantSuggestionController(projects, github, interpreter, settings).suggest(1, 2);
        assertThat(result.files()).containsExactly("src/OrderService.java (diff)", "src/test/OrderServiceTest.java (test)");
        assertThat(result.limited()).isFalse();
        verify(interpreter).suggest(argThat(s -> s.contains("+ update") && s.contains("assert stock >= 0")));
        verify(github, never()).getFile(eq(repo), eq("src/test/OtherTest.java"), anyString());
    }

    @Test
    void refusesSuggestionWhenPullChangesDuringCollection() throws Exception {
        var projects = mock(ProjectService.class);
        var github = mock(GitHubClient.class);
        var interpreter = mock(InvariantInterpreter.class);
        var project = mock(Project.class);
        var repo = new RepoRef("owner", "repo");
        when(project.ref()).thenReturn(repo);
        when(projects.get(1)).thenReturn(project);
        var before = mock(GitHubPull.class, RETURNS_DEEP_STUBS);
        var after = mock(GitHubPull.class, RETURNS_DEEP_STUBS);
        when(before.head().sha()).thenReturn("old");
        when(after.head().sha()).thenReturn("new");
        when(github.getPull(repo, 2)).thenReturn(before, after);
        when(github.getTree(repo, "old")).thenReturn(new ObjectMapper().readTree("{\"tree\":[]}"));
        assertThatThrownBy(() -> new InvariantSuggestionController(projects, github, interpreter,
                mock(InvariantSettingsController.class)).suggest(1, 2)).isInstanceOf(ApiException.class);
        verifyNoInteractions(interpreter);
    }
}
