package com.prguard.project;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService service;

    public ProjectController(ProjectService service) {
        this.service = service;
    }

    @GetMapping
    public List<Project> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public Project get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Project register(@Valid @RequestBody RegisterRequest request) {
        return service.register(request.url());
    }

    @GetMapping("/{id}/repo")
    public ProjectService.RepoInfo repo(@PathVariable long id) {
        return service.repoInfo(id);
    }

    @PatchMapping("/{id}/settings")
    public Project updateSettings(@PathVariable long id, @RequestBody SettingsRequest request) {
        return service.updateSettings(id, request.commentEnabled(), request.majorThreshold());
    }

    /** 등록 화면(온보딩)을 끝까지 보거나 건너뛰었다 */
    @PostMapping("/{id}/onboarded")
    public Project onboarded(@PathVariable long id) {
        return service.finishOnboarding(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        service.delete(id);
    }

    public record RegisterRequest(@NotBlank String url) {
    }

    /** @param majorThreshold null 이면 서버 기본값 */
    public record SettingsRequest(boolean commentEnabled, Integer majorThreshold) {
    }
}
