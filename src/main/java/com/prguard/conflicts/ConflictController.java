package com.prguard.conflicts;

import com.prguard.project.ProjectService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
public class ConflictController {
    private final ProjectService projects;
    private final ConflictService conflicts;

    public ConflictController(ProjectService projects, ConflictService conflicts) {
        this.projects = projects;
        this.conflicts = conflicts;
    }

    @GetMapping("/api/projects/{id}/pr-conflicts")
    public ConflictReport get(@PathVariable long id) {
        var repo = projects.get(id).ref();
        try {
            return conflicts.analyze(repo);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "PR 간 겹침 분석 실패", e);
        }
    }
}
