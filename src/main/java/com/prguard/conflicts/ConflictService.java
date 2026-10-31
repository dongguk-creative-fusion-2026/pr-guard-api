package com.prguard.conflicts;

import com.prguard.github.GitHubClient;
import com.prguard.github.GitHubPullFile;
import com.prguard.github.RepoRef;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ConflictService {
    public record InputPr(int number, String title, String author, String url, boolean draft,
                          String baseRef, String mergeBase, String headSha, List<GitHubPullFile> files) { }
    private final GitHubClient github;
    private final ConflictEngine engine;
    private final ConflictProperties props;

    public ConflictService(GitHubClient github, ConflictEngine engine, ConflictProperties props) {
        this.github = github;
        this.engine = engine;
        this.props = props;
    }

    public ConflictReport analyze(RepoRef repo) {
        if (!props.enabled()) {
            return new ConflictReport("DISABLED", Instant.now(), List.of(), List.of(), Map.of(), List.of("PR 간 겹침 검사 꺼짐"));
        }
        var open = github.listOpenPulls(repo, null).pulls();
        List<String> notes = new ArrayList<>();
        if (open.size() >= props.maxPulls()) {
            notes.add("열린 PR 조회/분석 상한 " + props.maxPulls() + "개에 도달했습니다. 일부 PR이 빠질 수 있습니다.");
        }
        List<InputPr> inputs = new ArrayList<>();
        Map<Integer, String> heads = new LinkedHashMap<>();
        for (var pr : open.stream().limit(props.maxPulls()).toList()) {
            try {
                String ancestor = github.comparisonBase(repo, pr.base().sha(), pr.head().sha());
                var files = github.listPullFiles(repo, pr.number());
                var current = github.getPull(repo, pr.number());
                if (!"open".equals(current.state()) || !pr.head().sha().equals(current.head().sha())
                        || !pr.base().sha().equals(current.base().sha())) {
                    notes.add("PR #" + pr.number() + ": 분석 중 변경되어 제외했습니다. 다시 분석하세요.");
                    continue;
                }
                if (files.size() >= 300 || files.stream().anyMatch(f -> f.patch() == null || f.patch().isBlank())) {
                    notes.add("PR #" + pr.number() + ": 파일 수 상한 또는 누락된 patch로 검사 범위가 제한됩니다.");
                }
                inputs.add(new InputPr(pr.number(), pr.title(), pr.user() == null ? "unknown" : pr.user().login(),
                        pr.htmlUrl(), pr.draft(), pr.base().ref(), ancestor, pr.head().sha(), files));
                heads.put(pr.number(), pr.head().sha());
            } catch (RuntimeException e) {
                notes.add("PR #" + pr.number() + ": 변경 정보를 수집하지 못했습니다 (" + e.getClass().getSimpleName() + ").");
            }
        }
        boolean differentAncestors = inputs.stream().anyMatch(a -> inputs.stream().anyMatch(b ->
                a.baseRef().equals(b.baseRef()) && !a.mergeBase().equals(b.mergeBase())));
        if (differentAncestors) {
            notes.add("동일 대상 브랜치라도 merge-base가 다른 PR 쌍은 줄 좌표가 달라 비교하지 않았습니다. 브랜치를 최신화한 뒤 재분석하세요.");
        }
        var result = inputs.size() < 2 ? new ConflictReport.EngineResult(1, List.of(), List.of())
                : engine.analyze(Map.of("pulls", inputs));
        return new ConflictReport(notes.isEmpty() ? "COMPLETE" : "PARTIAL", Instant.now(),
                result.conflicts(), result.clusters(), Map.copyOf(heads), List.copyOf(notes));
    }
}
