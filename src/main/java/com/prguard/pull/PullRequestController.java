package com.prguard.pull;

import com.prguard.project.ProjectService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PullRequestController {

    private final ProjectService projects;
    private final PullRequestRepository pulls;

    public PullRequestController(ProjectService projects, PullRequestRepository pulls) {
        this.projects = projects;
        this.pulls = pulls;
    }

    @GetMapping("/api/projects/{id}/pulls")
    public List<PullRequest> list(@PathVariable long id) {
        projects.get(id);
        return pulls.findByProject(id);
    }
}
