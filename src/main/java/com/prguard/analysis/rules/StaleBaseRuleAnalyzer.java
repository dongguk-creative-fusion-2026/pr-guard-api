package com.prguard.analysis.rules;

import com.prguard.analysis.AnalysisContext;
import com.prguard.analysis.Analyzer;
import com.prguard.analysis.Category;
import com.prguard.analysis.ChangedFile;
import com.prguard.analysis.Finding;
import com.prguard.analysis.Severity;
import com.prguard.index.CallSite;
import com.prguard.index.ChangedMethod;
import com.prguard.rulemap.Rule;
import com.prguard.rulemap.RuleExtractor;
import com.prguard.rulemap.RuleMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * B. 영향: PR 을 만든 뒤 기본 브랜치에서 바뀐 규칙. PR 은 base 커밋을 보고 짰는데, 그 사이 기본 브랜치에서 이 PR 이 부르는 메서드의
 * 규칙이 바뀌었으면(예외 대신 null, 검사 제거, 정렬 제거 …) 머지 뒤 PR 의 가정이 깨진다. git 은 충돌로 보지 않는다 (다른 파일).
 *
 * <p>규칙 지도 두 장(base · 최신 기본 브랜치)을 비교한다. PR 이 직접 바꾼 메서드는 보지 않는다 (그건 이 PR 의 변경이다).
 */
@Component
public class StaleBaseRuleAnalyzer implements Analyzer {

    /** 사라지면 PR 의 가정이 깨지는 규칙 */
    private static final Set<Rule.Kind> GUARANTEES = Set.of(Rule.Kind.THROWS, Rule.Kind.GUARD, Rule.Kind.AUTH,
            Rule.Kind.ORDERED, Rule.Kind.ATOMIC_UPDATE);
    private static final int MAX_FINDINGS = 5;

    @Override
    public String id() {
        return "stale-base-rule";
    }

    @Override
    public Category category() {
        return Category.IMPACT;
    }

    @Override
    public List<Finding> analyze(AnalysisContext ctx) {
        if (ctx.latestRules().isEmpty() || ctx.baseRules().isEmpty() || !ctx.indexed()) {
            return List.of();
        }
        RuleMap base = ctx.baseRules();
        RuleMap latest = ctx.latestRules().get();
        Set<String> changedByPr = ctx.changedMethods().stream()
                .flatMap(m -> java.util.stream.Stream.of(m.id(), m.baseId()))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());

        // PR 이 바꾸거나 만든 메서드가 부르는 메서드 → 처음 부르는 곳
        Map<String, CallSite> relied = new LinkedHashMap<>();
        for (ChangedMethod m : ctx.changedMethods()) {
            if (m.id() == null || m.test()) {
                continue;
            }
            for (CallSite c : ctx.headIndex().calls()) {
                if (c.callerId().equals(m.id()) && c.resolution() == CallSite.Resolution.RESOLVED) {
                    c.calleeIds().stream().filter(id -> !changedByPr.contains(id)).forEach(id -> relied.putIfAbsent(id, c));
                }
            }
        }

        List<Finding> result = new ArrayList<>();
        for (Map.Entry<String, CallSite> e : relied.entrySet()) {
            String subject = e.getKey();
            Set<String> before = ids(base.about(subject));
            Set<String> after = ids(latest.about(subject));
            List<Rule> removed = base.about(subject).stream().filter(r -> !after.contains(r.id())).toList();
            List<Rule> added = latest.about(subject).stream().filter(r -> !before.contains(r.id())).toList();
            if (removed.isEmpty() && added.isEmpty()) {
                continue;
            }
            boolean breaking = removed.stream().anyMatch(r -> GUARANTEES.contains(r.kind()))
                    || added.stream().anyMatch(r -> r.kind() == Rule.Kind.RETURNS_NULL);
            CallSite call = e.getValue();
            String callee = RuleExtractor.shortId(subject);
            String caller = RuleExtractor.shortId(call.callerId());
            StringBuilder change = new StringBuilder();
            removed.forEach(r -> change.append("\n- 사라짐: ").append(r.statement()));
            added.forEach(r -> change.append("\n- 생김: ").append(r.statement()));
            result.add(new Finding("STALE_BASE_RULE", Category.IMPACT, breaking ? Severity.MAJOR : Severity.MINOR,
                    call.file(), commentable(ctx, call), "PR 을 만든 뒤 기본 브랜치에서 " + callee + " 의 규칙이 바뀜",
                    "이 PR 은 " + shortSha(base.commitSha()) + " 를 기준으로 짰는데, 그 뒤 " + ctx.pull().baseRef() + "("
                            + shortSha(latest.commitSha()) + ")에서 " + callee + " 의 규칙이 바뀌었습니다." + change
                            + "\n이 PR 의 " + caller + " 가 이 메서드를 부르므로, 기본 브랜치를 받아(rebase · merge) 바뀐 규칙에 맞는지 확인해 주세요."
                            + " git 은 다른 파일이라 충돌로 알려 주지 않습니다.",
                    String.join(" · ", removed.stream().map(r -> "사라짐 " + r.statement()).toList())
                            + (removed.isEmpty() || added.isEmpty() ? "" : " · ")
                            + String.join(" · ", added.stream().map(r -> "생김 " + r.statement()).toList()),
                    "stale-rule:" + subject, Finding.TOOL));
            if (result.size() >= MAX_FINDINGS) {
                break;
            }
        }
        return result;
    }

    private static Set<String> ids(List<Rule> rules) {
        return rules.stream().map(Rule::id).collect(Collectors.toSet());
    }

    private static Integer commentable(AnalysisContext ctx, CallSite call) {
        return ctx.file(call.file())
                .map(ChangedFile::patch)
                .filter(p -> p.commentableLines().contains(call.line()))
                .map(p -> call.line())
                .orElse(null);
    }

    private static String shortSha(String sha) {
        return sha == null ? "-" : sha.substring(0, Math.min(7, sha.length()));
    }
}
