package com.prguard.pull;

import com.prguard.common.ApiException;
import com.prguard.github.GitHubClient;
import com.prguard.github.GitHubPullFile;
import com.prguard.project.ProjectService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Suggests review-only properties from bounded PR patches and related tests at the PR head. */
@RestController
public class InvariantSuggestionController {
    private static final int MAX_CONTEXT = 30000;
    private final ProjectService projects;
    private final GitHubClient github;
    private final InvariantInterpreter interpreter;
    private final InvariantSettingsController settings;

    public record Suggestions(String headSha, List<String> files, boolean limited,
                              InvariantInterpreter.Interpretation interpretation) {}

    public InvariantSuggestionController(ProjectService projects, GitHubClient github,
                                         InvariantInterpreter interpreter, InvariantSettingsController settings) {
        this.projects = projects;
        this.github = github;
        this.interpreter = interpreter;
        this.settings = settings;
    }

    @PostMapping("/api/projects/{id}/pulls/{number}/invariant-suggestions")
    public Suggestions suggest(@PathVariable long id, @PathVariable int number) {
        settings.get(id, number);
        var repo = projects.get(id).ref();
        var pull = github.getPull(repo, number);
        String head = pull.head().sha();
        var changes = github.listPullFiles(repo, number);
        var tree = github.getTree(repo, head);
        if (tree == null || !tree.path("tree").isArray()) throw new ApiException(HttpStatus.BAD_GATEWAY,
                "INVARIANT_CONTEXT_FAILED", "PR 테스트 파일 목록을 가져오지 못했습니다.");
        var paths = new LinkedHashSet<String>();
        var stems = new ArrayList<String>();
        for (GitHubPullFile file : changes) {
            String name = file.filename();
            if (name == null) continue;
            if (isTest(name) && !"removed".equals(file.status())) paths.add(name);
            if (!isTest(name)) {
                String base = name.substring(name.lastIndexOf('/') + 1).replaceFirst("\\.[^.]+$", "").toLowerCase(Locale.ROOT);
                if (base.length() >= 4) stems.add(base);
            }
        }
        tree.path("tree").forEach(node -> {
            String path = node.path("path").asText();
            String lower = path.toLowerCase(Locale.ROOT);
            if ("blob".equals(node.path("type").asText()) && isTest(path)
                    && stems.stream().anyMatch(lower::contains)) paths.add(path);
        });
        StringBuilder context = new StringBuilder();
        var used = new ArrayList<String>();
        boolean limited = tree.path("truncated").asBoolean() || changes.size() >= 300 || paths.size() > 5;
        for (GitHubPullFile file : changes) {
            if (file.patch() == null) { limited = true; continue; }
            String patch = file.patch();
            if (patch.length() > 3000) { patch = patch.substring(0, 3000); limited = true; }
            String entry = "변경 파일: " + file.filename() + "\n" + patch;
            if (context.length() + entry.length() > 18000 || !append(context, entry)) { limited = true; break; }
            used.add(file.filename() + " (diff)");
        }
        int count = 0;
        for (String path : paths) {
            if (count++ >= 5) break;
            var file = github.getFile(repo, path, head);
            if (file == null || file.content().isBlank()) { limited = true; continue; }
            String content = file.content();
            if (content.length() > 6000) { content = content.substring(0, 6000); limited = true; }
            if (!append(context, "기존 테스트: " + path + "\n" + content)) { limited = true; break; }
            used.add(path + " (test)");
        }
        if (!head.equals(github.getPull(repo, number).head().sha())) throw new ApiException(HttpStatus.CONFLICT,
                "INVARIANT_PR_CHANGED", "분석 중 PR 커밋이 변경됐습니다. 다시 시도하세요.");
        if (context.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "INVARIANT_CONTEXT_EMPTY", "분석할 변경 코드나 테스트가 없습니다.");
        return new Suggestions(head, List.copyOf(used), limited, interpreter.suggest(context.toString()));
    }

    private static boolean append(StringBuilder out, String text) {
        if (out.length() + text.length() + 2 > MAX_CONTEXT) return false;
        out.append(text).append("\n\n");
        return true;
    }

    static boolean isTest(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        return p.matches(".*(?:^|/)(?:test|tests|__tests__)/.*") || p.matches(".*(?:test|tests|spec)\\.[a-z0-9]+$")
                || p.matches(".*(?:^|/)test_[^/]+\\.[a-z0-9]+$");
    }
}
