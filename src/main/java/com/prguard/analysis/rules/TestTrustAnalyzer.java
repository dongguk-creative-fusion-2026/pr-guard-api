package com.prguard.analysis.rules;

import com.prguard.analysis.AnalysisContext;
import com.prguard.analysis.Analyzer;
import com.prguard.analysis.Category;
import com.prguard.analysis.ChangedFile;
import com.prguard.analysis.Finding;
import com.prguard.analysis.Severity;
import com.prguard.analysis.TestFacts;
import com.prguard.index.CallSite;
import com.prguard.index.ChangedMethod;
import com.prguard.index.MethodInfo;
import com.prguard.index.RepoIndex;
import com.prguard.workspace.Checkout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * D. 테스트 신뢰도 (정적): 이번 PR 이 테스트를 약하게 만들었거나, 새로 쓴 테스트가 아무것도 검증하지 않는지.
 *
 * 약화 (MAJOR): 검증이 있던 테스트를 지웠는데 그 테스트가 부르던 코드는 남아 있음 · 실패 경로 검증(assertThrows, never() …)이 사라짐 ·
 *              @Disabled · @Ignore 추가. AI 가 테스트를 통과시키려고 테스트 쪽을 고치는 실수를 잡는다.
 * 약한 테스트 (MINOR): 새로 쓰거나 고친 테스트에 검증이 없음 · 항상 참인 검증 · 예외를 삼키는 빈 catch.
 */
@Component
public class TestTrustAnalyzer implements Analyzer {

    private static final int MAX_LISTED = 10;

    @Override
    public String id() {
        return "test-trust";
    }

    @Override
    public Category category() {
        return Category.RISK;
    }

    @Override
    public List<Finding> analyze(AnalysisContext ctx) {
        Checkout checkout = ctx.checkout().orElse(null);
        if (checkout == null || !ctx.indexed()) {
            return List.of();
        }
        Sources src = new Sources(checkout);
        List<Finding> result = new ArrayList<>();
        // 지운 테스트는 파일별로 한 지적에 묶는다
        Map<String, List<String>> removedLive = new LinkedHashMap<>();
        Map<String, List<String>> removedWithCode = new LinkedHashMap<>();
        Map<String, Integer> removedNegative = new LinkedHashMap<>();

        for (ChangedMethod m : ctx.changedMethods()) {
            if (!m.test()) {
                continue;
            }
            String name = m.name();
            switch (m.kind()) {
                case REMOVED -> {
                    TestFacts base = TestFacts.of(src.base(m.file()), name, m.line()).orElse(null);
                    if (base == null || !base.test() || base.assertions() == 0) {
                        continue;
                    }
                    String label = name + " (검증 " + base.assertions() + "개" + (base.negative() > 0 ? ", 실패 경로 " + base.negative() + "개" : "") + ")";
                    if (stillTested(m, ctx.baseIndex(), ctx.headIndex())) {
                        removedLive.computeIfAbsent(m.file(), k -> new ArrayList<>()).add(label);
                        removedNegative.merge(m.file(), base.negative(), Integer::sum);
                    } else {
                        removedWithCode.computeIfAbsent(m.file(), k -> new ArrayList<>()).add(label);
                    }
                }
                case ADDED, MODIFIED -> {
                    TestFacts head = TestFacts.of(src.head(m.file()), name, m.line()).orElse(null);
                    if (head == null || !head.test()) {
                        continue;
                    }
                    TestFacts base = m.kind() == ChangedMethod.Kind.MODIFIED && m.baseId() != null
                            ? baseFacts(m, ctx, src, name).orElse(null) : null;
                    result.addAll(weakened(m, head, base));
                    result.addAll(weak(m, head));
                }
            }
        }
        removedLive.forEach((file, names) -> {
            int negative = removedNegative.getOrDefault(file, 0);
            result.add(new Finding("TEST_REMOVED", Category.RISK, Severity.MAJOR, file, null,
                    "검증하던 테스트가 사라짐: " + names.size() + "개" + (negative > 0 ? " (실패 경로 검증 포함)" : ""),
                    "이번 PR 이 지운 테스트가 부르던 코드는 head 에 그대로 남아 있습니다. 그 동작을 이제 아무 테스트도 지키지 않을 수 있습니다."
                            + (negative > 0 ? " 특히 예외 · 거부 경로(권한 없음, 없는 데이터 …)를 검증하던 테스트입니다." : "")
                            + " 테스트를 통과시키려고 테스트 쪽을 지운 것은 아닌지 확인해 주세요.",
                    String.join("\n", limit(names)), "test-removed:" + file, Finding.TOOL));
        });
        removedWithCode.forEach((file, names) -> result.add(new Finding("TEST_REMOVED_WITH_CODE", Category.RISK, Severity.MINOR,
                file, null, "테스트가 사라짐: " + names.size() + "개 (검증하던 코드도 함께 지워짐)",
                "지운 테스트가 부르던 코드도 이번 PR 에서 사라졌습니다. 기능을 뺀 것이면 괜찮습니다.",
                String.join("\n", limit(names)), "test-removed-with-code:" + file, Finding.TOOL)));
        result.addAll(disabledClasses(ctx, src));
        return result;
    }

    /** 고친 테스트가 예전보다 약해짐 */
    private static List<Finding> weakened(ChangedMethod m, TestFacts head, TestFacts base) {
        List<Finding> out = new ArrayList<>();
        if (base == null) {
            return out;
        }
        String label = m.typeFqn().substring(m.typeFqn().lastIndexOf('.') + 1) + "#" + m.name();
        if (head.disabled() && !base.disabled()) {
            out.add(new Finding("TEST_DISABLED", Category.RISK, Severity.MAJOR, m.file(), m.line(),
                    "테스트를 끔: " + label,
                    "이번 PR 이 이 테스트에 @Disabled(@Ignore)를 붙였습니다. 깨지는 테스트를 끄는 대신 원인을 고쳐 주세요.",
                    "@Disabled 추가", "test-disabled:" + m.id(), Finding.TOOL));
        }
        if (base.negative() > 0 && head.negative() < base.negative()) {
            out.add(new Finding("TEST_WEAKENED", Category.RISK, Severity.MAJOR, m.file(), m.line(),
                    "실패 경로 검증이 사라짐: " + label,
                    "예외 · 거부 경로를 검증하던 부분(assertThrows, never() …)이 " + base.negative() + "개 → " + head.negative()
                            + "개로 줄었습니다. 테스트를 통과시키려고 기대를 낮춘 것은 아닌지 확인해 주세요.",
                    "실패 경로 검증 " + base.negative() + " → " + head.negative() + " · 전체 검증 " + base.assertions() + " → " + head.assertions(),
                    "test-weakened:" + m.id(), Finding.TOOL));
        } else if (head.assertions() < base.assertions() && head.assertions() > 0) {
            out.add(new Finding("TEST_WEAKENED", Category.RISK, Severity.MINOR, m.file(), m.line(),
                    "검증이 줄어든 테스트: " + label,
                    "이 테스트의 검증 호출이 " + base.assertions() + "개 → " + head.assertions() + "개로 줄었습니다.",
                    "검증 " + base.assertions() + " → " + head.assertions(), "test-weakened:" + m.id(), Finding.TOOL));
        }
        return out;
    }

    /** 새로 쓰거나 고친 테스트가 검증을 하지 않음 */
    private static List<Finding> weak(ChangedMethod m, TestFacts head) {
        List<Finding> out = new ArrayList<>();
        if (head.disabled()) {
            return out;
        }
        String label = m.typeFqn().substring(m.typeFqn().lastIndexOf('.') + 1) + "#" + m.name();
        if (head.assertions() == 0) {
            out.add(new Finding("TEST_NO_ASSERTION", Category.RISK, Severity.MINOR, m.file(), m.line(),
                    "검증이 없는 테스트: " + label,
                    "이 테스트는 코드를 실행하기만 하고 결과를 검증하지 않습니다 (assert · verify 없음). 예외만 안 나면 통과합니다.",
                    "호출: " + String.join(", ", head.calls().stream().limit(8).toList()), "test-no-assertion:" + m.id(), Finding.TOOL));
        } else if (!head.trivial().isEmpty() && head.trivial().size() >= head.assertions()) {
            out.add(new Finding("TEST_TRIVIAL_ASSERTION", Category.RISK, Severity.MINOR, m.file(), m.line(),
                    "항상 참인 검증뿐인 테스트: " + label,
                    "이 테스트의 검증은 코드와 상관없이 항상 통과합니다.",
                    String.join("\n", head.trivial()), "test-trivial:" + m.id(), Finding.TOOL));
        }
        if (head.swallowed()) {
            out.add(new Finding("TEST_SWALLOWED_EXCEPTION", Category.RISK, Severity.MINOR, m.file(), m.line(),
                    "예외를 삼키는 테스트: " + label,
                    "빈 catch 가 예외를 삼켜서, 테스트 대상이 실패해도 테스트는 통과합니다. assertThrows 를 쓰거나 try 안에 fail() 을 넣어 주세요.",
                    "빈 catch (try 안에 fail() 없음)", "test-swallowed:" + m.id(), Finding.TOOL));
        }
        return out;
    }

    /** 이번 PR 이 테스트 클래스 전체에 @Disabled 를 붙임 */
    private static List<Finding> disabledClasses(AnalysisContext ctx, Sources src) {
        List<Finding> out = new ArrayList<>();
        for (ChangedFile f : ctx.files()) {
            if (!f.isJava() || !f.filename().contains("src/test/") || f.removed()) {
                continue;
            }
            Set<String> head = TestFacts.disabledClasses(src.head(f.filename()));
            Set<String> base = TestFacts.disabledClasses(src.base(f.previousFilename() == null ? f.filename() : f.previousFilename()));
            for (String c : head) {
                if (!base.contains(c) && !"added".equals(f.status())) {
                    out.add(new Finding("TEST_DISABLED", Category.RISK, Severity.MAJOR, f.filename(), null,
                            "테스트 클래스를 통째로 끔: " + c,
                            "이번 PR 이 테스트 클래스에 @Disabled(@Ignore)를 붙여 안의 테스트가 모두 돌지 않습니다.",
                            "@Disabled 추가", "test-class-disabled:" + f.filename() + "#" + c, Finding.TOOL));
                }
            }
        }
        return out;
    }

    private static Optional<TestFacts> baseFacts(ChangedMethod m, AnalysisContext ctx, Sources src, String name) {
        Optional<MethodInfo> b = ctx.baseIndex().method(m.baseId());
        return b.flatMap(info -> TestFacts.of(src.base(info.file()), info.name(), info.startLine()));
    }

    /** 지운 테스트가 부르던 (테스트가 아닌) 코드가 head 에 남아 있나 */
    private static boolean stillTested(ChangedMethod m, RepoIndex base, RepoIndex head) {
        if (m.baseId() == null) {
            return false;
        }
        Set<String> targets = new LinkedHashSet<>();
        for (CallSite c : base.calls()) {
            if (m.baseId().equals(c.callerId())) {
                for (String callee : c.calleeIds()) {
                    base.method(callee).filter(x -> !x.test()).ifPresent(x -> targets.add(x.id()));
                }
            }
        }
        return targets.stream().anyMatch(id -> head.method(id).isPresent());
    }

    private static List<String> limit(List<String> names) {
        if (names.size() <= MAX_LISTED) {
            return names;
        }
        List<String> out = new ArrayList<>(names.subList(0, MAX_LISTED));
        out.add("외 " + (names.size() - MAX_LISTED) + "개");
        return out;
    }

    /** base · head 파일 원문 (파일마다 한 번만 읽는다) */
    private static final class Sources {
        private final Checkout checkout;
        private final Map<String, String> cache = new LinkedHashMap<>();

        Sources(Checkout checkout) {
            this.checkout = checkout;
        }

        String head(String file) {
            return read(checkout.headDir(), "H:" + file, file);
        }

        String base(String file) {
            return read(checkout.baseDir(), "B:" + file, file);
        }

        private String read(Path root, String key, String file) {
            return cache.computeIfAbsent(key, k -> {
                try {
                    Path p = root.resolve(file).normalize();
                    return p.startsWith(root) ? Files.readString(p) : null;
                } catch (IOException | RuntimeException e) {
                    return null;
                }
            });
        }
    }
}
