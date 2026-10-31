package com.prguard.pull;

import com.prguard.common.ApiException;
import com.prguard.project.ProjectService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** PR별 불변식 초안. 이 설정은 아직 실행 검증에 연결되지 않았다. */
@RestController
public class InvariantSettingsController {
    private final ProjectService projects;
    private final JdbcClient jdbc;

    public InvariantSettingsController(ProjectService projects, JdbcClient jdbc) {
        this.projects = projects;
        this.jdbc = jdbc;
    }

    public record Settings(@NotNull @Size(max = 4000) String rules) {}

    @GetMapping("/api/projects/{id}/pulls/{number}/invariant-settings")
    public Settings get(@PathVariable long id, @PathVariable int number) {
        projects.get(id);
        return jdbc.sql("SELECT invariant_rules FROM pull_requests WHERE project_id = :id AND number = :number")
                .param("id", id).param("number", number).query(String.class).optional()
                .map(Settings::new).orElseThrow(InvariantSettingsController::notFound);
    }

    @PutMapping("/api/projects/{id}/pulls/{number}/invariant-settings")
    public Settings put(@PathVariable long id, @PathVariable int number, @Valid @RequestBody Settings settings) {
        projects.get(id);
        int updated = jdbc.sql("UPDATE pull_requests SET invariant_rules = :rules WHERE project_id = :id AND number = :number")
                .param("rules", settings.rules()).param("id", id).param("number", number).update();
        if (updated == 0) throw notFound();
        return settings;
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "PULL_NOT_FOUND", "PR을 찾을 수 없습니다");
    }
}
