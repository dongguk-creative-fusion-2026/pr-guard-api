package com.prguard.pull;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.prguard.common.GlobalExceptionHandler;
import com.prguard.project.ProjectService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class InvariantSettingsControllerTest {
    private JdbcClient jdbc;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        ProjectService projects = mock(ProjectService.class);
        when(projects.get(anyLong())).thenReturn(null);
        mvc = MockMvcBuilders.standaloneSetup(new InvariantSettingsController(projects, jdbc))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void readsRulesForOnePull() throws Exception {
        when(jdbc.sql("SELECT invariant_rules FROM pull_requests WHERE project_id = :id AND number = :number")
                .param("id", 1L).param("number", 2).query(String.class).optional()).thenReturn(Optional.of("rule"));
        mvc.perform(get("/api/projects/1/pulls/2/invariant-settings"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rules").value("rule"));
    }

    @Test
    void savesRulesForOnePull() throws Exception {
        when(jdbc.sql("UPDATE pull_requests SET invariant_rules = :rules WHERE project_id = :id AND number = :number")
                .param("rules", "rule").param("id", 1L).param("number", 2).update()).thenReturn(1);
        mvc.perform(put("/api/projects/1/pulls/2/invariant-settings")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rules\":\"rule\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rules").value("rule"));
    }

    @Test
    void missingPullIsNotFoundAndNullRulesAreRejected() throws Exception {
        mvc.perform(get("/api/projects/1/pulls/2/invariant-settings")).andExpect(status().isNotFound());
        mvc.perform(put("/api/projects/1/pulls/2/invariant-settings")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rules\":null}"))
                .andExpect(status().isBadRequest());
    }
}
