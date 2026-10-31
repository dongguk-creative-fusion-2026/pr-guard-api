package com.prguard.rulemap;

import com.prguard.github.GitHubClient;
import com.prguard.project.Project;
import com.prguard.project.ProjectService;
import java.util.List;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 규칙 지도: 기본 브랜치 코드가 지키는 규칙. 팀원 · AI 가 "이 레포는 이렇게 짠다"를 보는 화면용 */
@RestController
public class RuleMapController {

    private final ProjectService projects;
    private final GitHubClient github;
    private final RuleMapService service;

    public RuleMapController(ProjectService projects, GitHubClient github, RuleMapService service) {
        this.projects = projects;
        this.github = github;
        this.service = service;
    }

    /**
     * @param previousSha 비교한 지난 지도의 커밋. 처음 만든 지도면 null
     * @param added       지난 지도에 없던 규칙
     * @param removed     지난 지도에 있었는데 사라진 규칙
     */
    public record View(String repo, String branch, String commitSha, boolean cached, int version, List<Rule> rules,
                       String previousSha, List<Rule> added, List<Rule> removed) {
    }

    /** 기본 브랜치 끝 커밋의 규칙 지도. 저장해 둔 것이 없으면 이번에 만든다 (처음에는 clone 때문에 오래 걸릴 수 있다) */
    @GetMapping("/api/projects/{id}/rules")
    public View rules(@PathVariable long id) {
        Project project = projects.get(id);
        long repoKb = github.getRepo(project.ref()).size();
        RuleMapService.BranchMap result = service.forBranch(project.ref(), repoKb, project.defaultBranch());
        RuleMap map = result.map();
        RuleMap previous = result.previous();
        Set<String> now = map.ids();
        Set<String> before = previous == null ? Set.of() : previous.ids();
        return new View(project.ref().fullName(), result.branch(), map.commitSha(), map.cached(), RuleExtractor.VERSION,
                map.rules(), previous == null ? null : previous.commitSha(),
                previous == null ? List.of() : map.rules().stream().filter(r -> !before.contains(r.id())).toList(),
                previous == null ? List.of() : previous.rules().stream().filter(r -> !now.contains(r.id())).toList());
    }
}
