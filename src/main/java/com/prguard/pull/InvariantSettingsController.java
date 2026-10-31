package com.prguard.pull;

import com.prguard.common.ApiException;
import com.prguard.project.ProjectService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** PR별 규칙 초안과 해석. 사용자가 선택한 후보의 실행은 별도 Actions API가 담당한다. */
@RestController
public class InvariantSettingsController {
    private final ProjectService projects;
    private final JdbcClient jdbc;
    private final InvariantInterpreter interpreter;

    public InvariantSettingsController(ProjectService projects, JdbcClient jdbc, InvariantInterpreter interpreter) {
        this.projects = projects;
        this.jdbc = jdbc;
        this.interpreter = interpreter;
    }

    @PostMapping("/api/projects/{id}/pulls/{number}/invariant-settings/compile")
    public InvariantInterpreter.Interpretation compile(@PathVariable long id, @PathVariable int number,
                                                    @Valid @RequestBody InvariantSettings settings) {
        get(id, number); // 프로젝트와 PR의 존재를 확인한다.
        return interpreter.interpret(settings.rules());
    }

    @GetMapping("/api/projects/{id}/pulls/{number}/invariant-settings")
    public InvariantSettings get(@PathVariable long id, @PathVariable int number) {
        projects.get(id);
        return jdbc.sql("SELECT invariant_rules FROM pull_requests WHERE project_id = :id AND number = :number")
                .param("id", id).param("number", number).query(String.class).optional()
                .map(InvariantSettings::new).orElseThrow(InvariantSettingsController::notFound);
    }

    @PutMapping("/api/projects/{id}/pulls/{number}/invariant-settings")
    public InvariantSettings put(@PathVariable long id, @PathVariable int number, @Valid @RequestBody InvariantSettings settings) {
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
