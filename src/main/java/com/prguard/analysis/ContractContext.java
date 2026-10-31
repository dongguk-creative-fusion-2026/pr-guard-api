package com.prguard.analysis;

import com.prguard.index.CallSite;
import com.prguard.index.ChangedMethod;
import com.prguard.index.MethodInfo;
import com.prguard.index.RepoIndex;
import com.prguard.index.TypeInfo;
import com.prguard.workspace.Checkout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 바뀐 메서드가 기대던 기존 코드 묶음. "기존 코드의 의미 오해"(기존 코드가 지키던 규칙과 충돌하는 변경)를 LLM 에 따로 묻는 재료.
 *
 * <p>본문 · 시그니처가 바뀐 메서드마다 모은다: base 본문, base 에서 부르다 head 에서 안 부르는 메서드, head 에서 새로 부르는 메서드,
 * base 경로에서는 던지던 예외 타입, 이 메서드를 부르는 곳, 이 메서드를 지나가는 테스트. 모두 인덱스로 찾고 LLM 은 쓰지 않는다.
 *
 * <p>조각마다 id(C1, C2 …)를 붙여 LLM 이 근거로 인용하게 하고, 인용한 id 가 이 묶음에 있는지로 근거를 확인한다
 * (없는 id 를 든 지적은 근거를 지어낸 것으로 본다).
 */
public final class ContractContext {

    /** 조각 하나에 싣는 최대 줄 수 */
    private static final int MAX_SNIPPET_LINES = 30;
    /** 호출부는 호출한 줄 앞뒤로 이만큼 */
    private static final int CALLER_WINDOW = 6;
    private static final int MAX_CALLERS = 3;
    private static final int MAX_TESTS = 3;
    private static final int MAX_CALLEES = 4;
    private static final Pattern NEW_TYPE = Pattern.compile("\\bnew\\s+([A-Z]\\w*)\\s*\\(");

    public static final ContractContext EMPTY = new ContractContext(List.of());

    public enum Role {
        BASE_BODY("PR 전 본문"),
        DROPPED_CALLEE("PR 전에는 부르다 이제 안 부르는 메서드"),
        NEW_CALLEE("이번에 새로 부르는 메서드"),
        LOST_EXCEPTION("PR 전 경로에서는 던지던 예외 (이제 이 경로에서 안 던짐)"),
        CALLER("이 메서드를 부르는 곳"),
        TEST("이 메서드를 부르는 테스트");

        private final String label;

        Role(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * 기존 코드 조각 하나.
     *
     * @param ref    LLM 이 인용하는 id (C1, C2 …)
     * @param side   base 또는 head 소스
     * @param symbol 조각이 가리키는 코드 이름 (메서드 id 또는 타입 FQN)
     * @param code   줄 번호를 붙인 소스
     */
    public record Snippet(String ref, Role role, String side, String symbol, String file, int startLine, int endLine,
                          String code) {

        public String location() {
            return fileName(file) + ":" + startLine + (endLine > startLine ? "-" + endLine : "");
        }

        /** 사람이 읽는 한 줄: "PR 전에는 부르다 이제 안 부르는 메서드 PostService#getPost(Long) (PostService.java:28-31)" */
        public String describe() {
            return role.label() + " " + shortId(symbol) + " (" + location() + ")";
        }
    }

    /** @param headCode 바뀐 메서드의 head 본문 (줄 번호 붙음) */
    public record Target(ChangedMethod method, String headCode, List<Snippet> snippets) {
    }

    private final List<Target> targets;

    private ContractContext(List<Target> targets) {
        this.targets = List.copyOf(targets);
    }

    public List<Target> targets() {
        return targets;
    }

    public boolean isEmpty() {
        return targets.stream().allMatch(t -> t.snippets().isEmpty());
    }

    public int snippetCount() {
        return targets.stream().mapToInt(t -> t.snippets().size()).sum();
    }

    public Optional<Snippet> snippet(String ref) {
        String key = ref == null ? "" : ref.strip().replaceAll("^\\[|]$", "");
        return targets.stream().flatMap(t -> t.snippets().stream()).filter(s -> s.ref().equals(key)).findFirst();
    }

    /**
     * @param maxMethods 볼 바뀐 메서드 수 (기존 코드에 기대는 것 먼저)
     * @param maxChars   조각 전체의 최대 길이. 넘으면 뒤 조각부터 뺀다
     */
    public static ContractContext collect(AnalysisContext ctx, int maxMethods, int maxChars) {
        Optional<Checkout> checkout = ctx.checkout();
        if (!ctx.indexed() || checkout.isEmpty()) {
            return EMPTY;
        }
        RepoIndex base = ctx.baseIndex();
        RepoIndex head = ctx.headIndex();
        Source baseSrc = new Source(checkout.get().baseDir());
        Source headSrc = new Source(checkout.get().headDir());

        // 생성자는 대부분 의존성 주입 배선이라 뺀다
        List<ChangedMethod> candidates = ctx.changedMethods().stream()
                .filter(m -> !m.test() && m.kind() == ChangedMethod.Kind.MODIFIED && m.id() != null && m.baseId() != null)
                .filter(m -> !"<init>".equals(m.name()))
                .filter(m -> m.bodyChanged() || m.signatureChanged())
                // 부르던 메서드가 바뀐 것(기존 코드에 기대던 것) 먼저, 그다음 호출부가 많은 것
                .sorted(Comparator.comparing((ChangedMethod m) -> droppedCallees(base, head, m).isEmpty())
                        .thenComparing(m -> -head.callersOf(m.id()).size()))
                .limit(maxMethods)
                .toList();

        int[] counter = {0};
        int budget = maxChars;
        List<Target> targets = new ArrayList<>();
        for (ChangedMethod m : candidates) {
            Optional<MethodInfo> hm = head.method(m.id());
            Optional<MethodInfo> bm = base.method(m.baseId());
            if (hm.isEmpty() || bm.isEmpty()) {
                continue;
            }
            List<Snippet> snippets = new ArrayList<>();
            List<Snippet> wanted = snippets(m, bm.get(), hm.get(), base, head, baseSrc, headSrc, counter);
            for (Snippet s : wanted) {
                if (s.code().length() > budget) {
                    break;
                }
                snippets.add(s);
                budget -= s.code().length();
            }
            if (!snippets.isEmpty()) {
                targets.add(new Target(m, headSrc.code(hm.get().file(), hm.get().startLine(), hm.get().endLine()),
                        snippets));
            }
        }
        return new ContractContext(targets);
    }

    private static List<Snippet> snippets(ChangedMethod m, MethodInfo bm, MethodInfo hm, RepoIndex base,
                                          RepoIndex head, Source baseSrc, Source headSrc, int[] counter) {
        List<Snippet> result = new ArrayList<>();
        String baseBody = baseSrc.code(bm.file(), bm.startLine(), bm.endLine());
        result.add(snippet(counter, Role.BASE_BODY, "base", bm.id(), bm.file(), bm.startLine(), bm.endLine(), baseBody));

        List<MethodInfo> dropped = droppedCallees(base, head, m).stream().limit(MAX_CALLEES).toList();
        for (MethodInfo c : dropped) {
            result.add(snippet(counter, Role.DROPPED_CALLEE, "base", c.id(), c.file(), c.startLine(), c.endLine(),
                    baseSrc.code(c.file(), c.startLine(), c.endLine())));
        }
        Set<String> baseCallees = callees(base, bm.id());
        Set<String> headCallees = callees(head, hm.id());
        headCallees.stream()
                .filter(id -> !baseCallees.contains(id))
                .map(head::method)
                .flatMap(Optional::stream)
                .filter(c -> !c.test() && !c.implicit() && !c.constructor())
                .limit(MAX_CALLEES)
                .forEach(c -> result.add(snippet(counter, Role.NEW_CALLEE, "head", c.id(), c.file(), c.startLine(),
                        c.endLine(), headSrc.code(c.file(), c.startLine(), c.endLine()))));

        // PR 전 경로(본문 + 한 단계 아래 메서드)에서 던지던 예외 중 지금 경로에는 없는 것
        Set<String> thrownBefore = thrown(baseBody, baseCallees, base, baseSrc);
        Set<String> thrownNow = thrown(headSrc.code(hm.file(), hm.startLine(), hm.endLine()), headCallees, head, headSrc);
        for (String name : thrownBefore) {
            if (thrownNow.contains(name)) {
                continue;
            }
            base.types().values().stream()
                    .filter(t -> t.simpleName().equals(name) && !t.test())
                    .findFirst()
                    .ifPresent(t -> result.add(typeSnippet(counter, t, baseSrc)));
        }

        head.callersOf(hm.id()).stream()
                .filter(c -> head.method(c.callerId()).map(x -> !x.test()).orElse(false))
                .limit(MAX_CALLERS)
                .forEach(c -> callerSnippet(counter, Role.CALLER, c, head, headSrc).ifPresent(result::add));

        // 테스트: 이 메서드를 부르거나, 이제 안 부르게 된 메서드를 부르는 것 (그 메서드의 원래 동작을 검증한다)
        Set<String> testTargets = new LinkedHashSet<>();
        testTargets.add(hm.id());
        dropped.forEach(c -> testTargets.add(c.id()));
        testTargets.stream()
                .flatMap(id -> head.callersOf(id).stream())
                .filter(c -> head.method(c.callerId()).map(MethodInfo::test).orElse(false))
                .map(CallSite::callerId)
                .distinct()
                .limit(MAX_TESTS)
                .map(head::method)
                .flatMap(Optional::stream)
                .forEach(t -> result.add(snippet(counter, Role.TEST, "head", t.id(), t.file(), t.startLine(),
                        t.endLine(), headSrc.code(t.file(), t.startLine(), t.endLine()))));
        return result.stream().filter(s -> !s.code().isBlank()).toList();
    }

    /** base 버전은 부르는데 head 버전은 부르지 않는 레포 안 메서드. 생성자는 뺀다 (예외 생성은 LOST_EXCEPTION 으로 따로 본다) */
    static List<MethodInfo> droppedCallees(RepoIndex base, RepoIndex head, ChangedMethod m) {
        if (m.id() == null || m.baseId() == null) {
            return List.of();
        }
        Set<String> now = callees(head, m.id());
        return callees(base, m.baseId()).stream()
                .filter(id -> !now.contains(id))
                .map(base::method)
                .flatMap(Optional::stream)
                .filter(c -> !c.test() && !c.implicit() && !c.constructor())
                .toList();
    }

    private static Set<String> callees(RepoIndex index, String methodId) {
        Set<String> result = new LinkedHashSet<>();
        for (CallSite c : index.calls()) {
            if (c.callerId().equals(methodId) && c.resolution() == CallSite.Resolution.RESOLVED) {
                result.addAll(c.calleeIds());
            }
        }
        return result;
    }

    /** 본문과 한 단계 아래 메서드 본문에서 new 로 만드는 예외 타입 이름. */
    private static Set<String> thrown(String body, Set<String> callees, RepoIndex index, Source src) {
        Set<String> result = new LinkedHashSet<>(exceptionTypes(body));
        for (String id : callees) {
            index.method(id).ifPresent(c -> result.addAll(exceptionTypes(src.code(c.file(), c.startLine(), c.endLine()))));
        }
        return result;
    }

    private static Set<String> exceptionTypes(String code) {
        Set<String> result = new LinkedHashSet<>();
        Matcher m = NEW_TYPE.matcher(code);
        while (m.find()) {
            if (m.group(1).endsWith("Exception") || m.group(1).endsWith("Error")) {
                result.add(m.group(1));
            }
        }
        return result;
    }

    /** 타입 선언부: 어노테이션부터 여는 중괄호까지 (예: @ResponseStatus(NOT_FOUND) class NotFoundException …) */
    private static Snippet typeSnippet(int[] counter, TypeInfo t, Source src) {
        List<String> lines = src.lines(t.file());
        int end = t.line();
        while (end < lines.size() && end < t.line() + 8 && !lines.get(end - 1).contains("{")) {
            end++;
        }
        return snippet(counter, Role.LOST_EXCEPTION, "base", t.fqn(), t.file(), t.line(), end,
                src.code(t.file(), t.line(), end));
    }

    /** 호출한 줄 앞뒤만 (호출하는 메서드 범위 안에서). */
    private static Optional<Snippet> callerSnippet(int[] counter, Role role, CallSite call, RepoIndex index, Source src) {
        return index.method(call.callerId()).map(caller -> {
            int from = Math.max(caller.startLine(), call.line() - CALLER_WINDOW);
            int to = Math.min(caller.endLine(), call.line() + CALLER_WINDOW);
            return snippet(counter, role, "head", caller.id(), caller.file(), from, to, src.code(caller.file(), from, to));
        });
    }

    private static Snippet snippet(int[] counter, Role role, String side, String symbol, String file, int from, int to,
                                   String code) {
        counter[0]++;
        return new Snippet("C" + counter[0], role, side, symbol, file, from, to, code);
    }

    /** LLM 에 넘기는 본문. 바뀐 메서드마다 head 본문과 기존 코드 조각들. */
    public String render() {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Target t : targets) {
            if (t.snippets().isEmpty()) {
                continue;
            }
            n++;
            ChangedMethod m = t.method();
            sb.append("\n### 바뀐 메서드 ").append(n).append(": ").append(m.id())
                    .append(" (").append(m.file()).append(")\n");
            if (m.signatureChanged()) {
                sb.append("이전 시그니처: ").append(m.baseId()).append('\n');
            }
            sb.append("PR 후 본문:\n```java\n").append(t.headCode()).append("\n```\n");
            for (Snippet s : t.snippets()) {
                sb.append("\n[").append(s.ref()).append("] ").append(s.describe()).append('\n')
                        .append("```java\n").append(s.code()).append("\n```\n");
            }
        }
        return sb.toString();
    }

    static String shortId(String id) {
        int hash = id.indexOf('#');
        String type = hash < 0 ? id : id.substring(0, hash);
        return type.substring(type.lastIndexOf('.') + 1) + (hash < 0 ? "" : id.substring(hash));
    }

    private static String fileName(String path) {
        return path == null ? "-" : path.substring(path.lastIndexOf('/') + 1);
    }

    /** base 또는 head 소스. 파일은 한 번만 읽는다. */
    private static final class Source {
        private final Path dir;
        private final Map<String, List<String>> files = new HashMap<>();

        Source(Path dir) {
            this.dir = dir;
        }

        List<String> lines(String file) {
            return files.computeIfAbsent(file, f -> {
                try {
                    return Files.readAllLines(dir.resolve(f), StandardCharsets.UTF_8);
                } catch (IOException | RuntimeException e) {
                    return List.of();
                }
            });
        }

        /** 줄 번호를 붙인 소스. 길면 앞부분만 */
        String code(String file, int from, int to) {
            List<String> lines = lines(file);
            int start = Math.max(1, from);
            int end = Math.min(lines.size(), Math.min(to, start + MAX_SNIPPET_LINES - 1));
            StringBuilder sb = new StringBuilder();
            for (int i = start; i <= end; i++) {
                sb.append(i).append(": ").append(lines.get(i - 1)).append('\n');
            }
            if (to > end && end >= start) {
                sb.append("... (생략)\n");
            }
            return sb.toString().stripTrailing();
        }
    }
}
