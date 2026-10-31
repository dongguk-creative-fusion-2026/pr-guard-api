package com.prguard.pull;

import com.prguard.project.ProjectService;
import jakarta.validation.Valid;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

@RestController
public class ProjectInvariantController {
    private final ProjectService projects;
    private final JdbcClient jdbc;

    public ProjectInvariantController(ProjectService projects, JdbcClient jdbc) {
        this.projects = projects;
        this.jdbc = jdbc;
    }

    @GetMapping("/api/projects/{id}/invariant-settings")
    public InvariantSettings get(@PathVariable long id) {
        projects.get(id);
        return new InvariantSettings(jdbc.sql("SELECT invariant_rules FROM projects WHERE id = :id")
                .param("id", id).query(String.class).single());
    }

    @PutMapping("/api/projects/{id}/invariant-settings")
    public InvariantSettings put(@PathVariable long id, @Valid @RequestBody InvariantSettings settings) {
        projects.get(id);
        jdbc.sql("UPDATE projects SET invariant_rules = :rules WHERE id = :id")
                .param("rules", settings.rules()).param("id", id).update();
        return settings;
    }
}
