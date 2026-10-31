package com.prguard.analysis.rules;

import com.prguard.analysis.AnalysisContext;
import com.prguard.analysis.Analyzer;
import com.prguard.analysis.Category;
import com.prguard.analysis.ChangedFile;
import com.prguard.analysis.Finding;
import com.prguard.analysis.Severity;
import com.prguard.index.ChangedMethod;
import com.prguard.index.MethodInfo;
import com.prguard.index.RepoIndex;
import com.prguard.index.TypeInfo;
import com.prguard.rulemap.Rule;
import com.prguard.rulemap.RuleExtractor;
import com.prguard.rulemap.RuleMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * D. 위험도: 새로 만든 코드가 레포의 관례(규칙 지도)를 따르지 않음. 바꾼 메서드는 PR 전 본문과 비교할 수 있지만(기존 코드 충돌 ·
 * AST 검사), 새 메서드는 비교할 것이 없어 같은 종류의 기존 코드가 지키는 관례에 비춰 본다.
 *
 * <ul>
 *   <li>서비스의 새 수정·삭제 메서드가 검사(OwnershipGuard 등)를 부르지 않음 → MAJOR (보안)</li>
 *   <li>새 쓰기 API 에 권한 어노테이션이 없음 → MAJOR (보안)</li>
 *   <li>새 응답 DTO(또는 이번에 바꾼 응답 DTO)에 민감 필드가 있음 → MAJOR (보안)</li>
 *   <li>새 API 가 민감 필드를 가진 타입(엔티티 등)을 응답 DTO 없이 그대로 돌려줌 → MAJOR (보안)</li>
 *   <li>서비스의 새 조회 메서드가 예외 대신 null 을 돌려줌 → MINOR</li>
 * </ul>
 */
@Component
public class ConventionAnalyzer implements Analyzer {

    private static final Pattern RETURNS_NULL = Pattern.compile("\\breturn\\s+null\\s*;|\\.orElse\\(\\s*null\\s*\\)");

    @Override
    public String id() {
        return "convention";
    }

    @Override
    public Category category() {
        return Category.RISK;
    }

    @Override
    public List<Finding> analyze(AnalysisContext ctx) {
        RuleMap rules = ctx.baseRules();
        if (rules.conventions().isEmpty() || !ctx.indexed()) {
            return List.of();
        }
        RepoIndex head = ctx.headIndex();
        List<Finding> result = new ArrayList<>();
        for (ChangedMethod cm : ctx.changedMethods()) {
            if (cm.kind() != ChangedMethod.Kind.ADDED || cm.test() || cm.id() == null) {
                continue;
            }
            Optional<MethodInfo> found = head.method(cm.id());
            if (found.isEmpty() || found.get().constructor()) {
                continue;
            }
            MethodInfo m = found.get();
            TypeInfo type = head.types().get(m.typeFqn());
            String name = RuleExtractor.shortId(m.id());

            Optional<Rule> guard = rules.convention(RuleExtractor.WRITE_GUARD);
            if (guard.isPresent() && RuleExtractor.isWriteServiceMethod(m, head)
                    && RuleExtractor.guardCalls(m, head).isEmpty()) {
                result.add(finding("CONVENTION_GUARD_MISSING", Category.SECURITY, Severity.MAJOR, ctx, m,
                        "새 수정·삭제 메서드에 검사가 없음: " + name,
                        "이 레포의 서비스 수정·삭제 메서드는 " + guard.get().support() + "/" + guard.get().total()
                                + "곳에서 검사를 부르는데" + example(guard.get()) + ", 새로 만든 " + name
                                + " 는 부르지 않습니다. 다른 사람의 데이터를 바꿀 수 있는지 확인해 주세요.",
                        guard.get()));
            }
            Optional<Rule> auth = rules.convention(RuleExtractor.WRITE_AUTH);
            if (auth.isPresent() && type != null && RuleExtractor.isWriteEndpoint(m, type)
                    && !RuleExtractor.hasAuth(m, type)) {
                result.add(finding("CONVENTION_AUTH_MISSING", Category.SECURITY, Severity.MAJOR, ctx, m,
                        "새 쓰기 API 에 권한 어노테이션이 없음: " + name,
                        "이 레포의 쓰기 API 는 " + auth.get().support() + "/" + auth.get().total() + "곳에서 권한 어노테이션을 다는데"
                                + example(auth.get()) + ", 새로 만든 " + name + " 에는 없습니다.",
                        auth.get()));
            }
            Optional<Rule> sensitive = rules.convention(RuleExtractor.RESPONSE_NO_SENSITIVE);
            if (sensitive.isPresent() && type != null && RuleExtractor.isEndpoint(m, type)) {
                RuleExtractor.returnedType(m, head)
                        .filter(t -> !RuleExtractor.isResponseType(t) && !RuleExtractor.sensitiveFields(t).isEmpty())
                        .ifPresent(t -> result.add(finding("CONVENTION_SENSITIVE_RESPONSE", Category.SECURITY,
                                Severity.MAJOR, ctx, m, "새 API 가 민감 필드가 있는 " + t.simpleName() + " 를 그대로 돌려줌: " + name,
                                "이 레포의 응답 DTO " + sensitive.get().total() + "개는 email · password 같은 민감 필드를 담지 않는데, "
                                        + name + " 는 " + t.simpleName() + " 를 그대로 돌려주어 "
                                        + String.join(", ", RuleExtractor.sensitiveFields(t))
                                        + " 가 응답에 나갑니다. 응답 DTO 로 감싸 필요한 값만 내보내 주세요.",
                                sensitive.get())));
            }
            Optional<Rule> lookup = rules.convention(RuleExtractor.LOOKUP_THROWS);
            if (lookup.isPresent() && RuleExtractor.isLookupServiceMethod(m, head)
                    && RETURNS_NULL.matcher(body(ctx, m)).find()) {
                result.add(finding("CONVENTION_LOOKUP_NULL", Category.IMPACT, Severity.MINOR, ctx, m,
                        "새 조회 메서드가 예외 대신 null 을 돌려줌: " + name,
                        "이 레포의 서비스 조회 메서드는 찾는 값이 없으면 null 대신 예외를 던지는데" + example(lookup.get())
                                + ", 새로 만든 " + name + " 는 null 을 돌려줍니다. 부르는 쪽이 null 을 확인하는지 봐 주세요.",
                        lookup.get()));
            }
        }

        Optional<Rule> sensitive = rules.convention(RuleExtractor.RESPONSE_NO_SENSITIVE);
        if (sensitive.isPresent()) {
            Set<String> changedFiles = ctx.files().stream().map(ChangedFile::filename).collect(Collectors.toSet());
            for (TypeInfo t : head.types().values()) {
                if (t.test() || !changedFiles.contains(t.file()) || !RuleExtractor.isResponseType(t)) {
                    continue;
                }
                List<String> fields = RuleExtractor.sensitiveFields(t);
                // 이번 PR 이 넣은 필드만 (PR 전에도 있던 것은 이 PR 의 문제가 아니다)
                TypeInfo before = ctx.baseIndex().types().get(t.fqn());
                List<String> introduced = fields.stream()
                        .filter(f -> before == null || !before.fields().containsKey(f))
                        .toList();
                if (introduced.isEmpty()) {
                    continue;
                }
                result.add(new Finding("CONVENTION_SENSITIVE_FIELD", Category.SECURITY, Severity.MAJOR, t.file(),
                        commentable(ctx, t.file(), t.line()),
                        "응답 DTO 에 민감 필드: " + t.simpleName() + "." + String.join(", ", introduced),
                        "이 레포의 응답 DTO " + sensitive.get().total() + "개는 email · password 같은 민감 필드를 담지 않는데, "
                                + t.simpleName() + " 에 " + String.join(", ", introduced)
                                + " 가 들어갔습니다. 응답으로 노출해도 되는 값인지 확인해 주세요.",
                        "관례: " + sensitive.get().describe(), "convention-sensitive:" + t.fqn(), Finding.TOOL));
            }
        }
        return result;
    }

    private static Finding finding(String ruleId, Category category, Severity severity, AnalysisContext ctx,
                                   MethodInfo m, String title, String message, Rule convention) {
        return new Finding(ruleId, category, severity, m.file(), commentable(ctx, m.file(), m.startLine()), title,
                message, "관례: " + convention.describe() + " · 따르는 곳: " + String.join(", ",
                convention.members().stream().limit(3).map(RuleExtractor::shortId).toList()),
                ruleId.toLowerCase() + ":" + m.id(), Finding.TOOL);
    }

    private static String example(Rule convention) {
        return convention.detail() == null || convention.detail().isBlank() ? "" : " (예: " + convention.detail() + ")";
    }

    private static Integer commentable(AnalysisContext ctx, String file, int line) {
        return ctx.file(file)
                .map(ChangedFile::patch)
                .filter(p -> p.commentableLines().contains(line))
                .map(p -> line)
                .orElse(null);
    }

    private static String body(AnalysisContext ctx, MethodInfo m) {
        return ctx.checkout().map(c -> {
            try {
                List<String> lines = java.nio.file.Files.readAllLines(c.headDir().resolve(m.file()));
                return String.join("\n", lines.subList(Math.max(0, m.startLine() - 1), Math.min(lines.size(), m.endLine())));
            } catch (java.io.IOException | RuntimeException e) {
                return "";
            }
        }).orElse("");
    }
}
