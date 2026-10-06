package com.prguard.graph;

import com.prguard.common.ApiException;
import com.prguard.project.ProjectService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{id}/graph")
public class GraphController {

    private final RepoGraphRepository graphs;
    private final ProjectService projects;

    public GraphController(RepoGraphRepository graphs, ProjectService projects) {
        this.graphs = graphs;
        this.projects = projects;
    }

    @GetMapping
    public RepoGraph get(@PathVariable long id) {
        projects.get(id);
        return graphs.find(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "GRAPH_NOT_FOUND", "그래프가 없습니다: " + id));
    }

    /** 기본 브랜치 최신 커밋으로 다시 만든다. */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RepoGraph rebuild(@PathVariable long id) {
        projects.get(id);
        graphs.enqueue(id);
        return get(id);
    }
}
