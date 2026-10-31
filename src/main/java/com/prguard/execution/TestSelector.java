package com.prguard.execution;

import com.prguard.analysis.ChangedFile;
import com.prguard.evidence.EvidenceTest;
import com.prguard.execution.TestSelection.Change;
import com.prguard.execution.TestSelection.Reason;
import com.prguard.execution.TestSelection.Reference;
import com.prguard.execution.TestSelection.TestClass;
import com.prguard.index.CallSite;
import com.prguard.index.ChangedMethod;
import com.prguard.index.MethodInfo;
import com.prguard.index.RepoIndex;
import com.prguard.index.TypeInfo;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 영향 테스트 선택 (Test Impact Analysis).
 *
 * 1. 기준 기록: 예전에 전체 테스트를 돌릴 때 trace-agent 가 남긴 "테스트 → 지나간 메서드".
 * 2. 바뀐 메서드마다 그것을 지나간 테스트 클래스를 찾고, 호출 그래프(인덱스)에서 그것을 부르는 테스트도 더한다.
 * 3. 이번 PR 이 바꾼 테스트, 기준 기록에 없는 테스트는 늘 돌린다.
 * 4. 영향 범위를 확정할 수 없는 변경(빌드 · 설정 · 리소스, 메서드 밖 변경, 테스트 밖에서 실행되는 메서드 …)이 있으면 전체를 돌린다.
 *
 * 걸러지는 것은 "기준 기록에서 어떤 바뀐 메서드도 지나가지 않았고, 호출 그래프로도 닿지 않는 테스트" 뿐이다.
 */
public final class TestSelector {

    private static final Set<String> TEST_ANNOTATIONS = Set.of("Test", "ParameterizedTest", "RepeatedTest",
            "TestFactory", "TestTemplate");
    /** 테스트 결과에 영향을 줄 수 없는 파일 */
    private static final Pattern IGNORED_DIR = Pattern.compile("(?i)/?(?:docs?|\\.github|\\.idea|\\.vscode)/.*");
    private static final Pattern IGNORED_EXT = Pattern.compile(
            "(?i)\\.(?:md|markdown|txt|adoc|rst|png|jpe?g|gif|svg|ico|webp|css|scss|less)$");
    private static final Pattern IGNORED_NAME = Pattern.compile(
            "(?i)/?(?:license[^/]*|notice[^/]*|\\.gitignore|\\.gitattributes|\\.editorconfig)");
    private static final String STATIC = "src/main/resources/static/";
    /** 메서드 밖에 있어도 동작에 영향이 없는 줄 */
    private static final Pattern TRIVIAL_LINE = Pattern.compile(
            "^\\s*(?:$|//.*|/\\*.*|\\*.*|import\\s.*|package\\s.*|[{}();\\s]+)$");
    private static final String MAIN = "src/main/java/";
    private static final String TEST = "src/test/java/";
    private static final int MAX_LISTED = 30;

    private TestSelector() {
    }

    /**
     * @param reference      기준 기록 (전체 실행의 호출 기록). 없으면 null
     * @param referenceMeta  기준 기록이 어느 실행 것인지
     * @param seconds        기준 기록에서 테스트 클래스별로 걸린 시간 (초)
     * @param extraFullReasons 호출하는 쪽이 이미 찾은 전체 실행 이유 (기록이 오래됨 · 인덱스 밖 테스트 소스 …)
     */
    public record Input(List<ChangedFile> files, List<ChangedMethod> changed, RepoIndex base, RepoIndex head,
                       Trace reference, Reference referenceMeta, Map<String, Double> seconds,
                       List<String> extraFullReasons, int staticDepth, boolean shadow) {
    }

    public static TestSelection select(Input in) {
        List<String> full = new ArrayList<>(in.extraFullReasons());
        Trace ref = in.reference() == null ? Trace.fromJson(null, null) : in.reference();
        if (ref.tests().isEmpty()) {
            full.add("테스트별 실행 기록이 아직 없음 (이번 전체 실행이 다음 PR 의 기준이 된다)");
        }

        Map<String, String> headTests = testClasses(in.head());
        Map<String, String> baseTests = testClasses(in.base());
        Map<String, Set<Integer>> reached = reachedByClass(ref);

        // 테스트 클래스별 선택 이유
        Map<String, List<Reason>> reasons = new LinkedHashMap<>();
        headTests.keySet().forEach(c -> reasons.put(c, new ArrayList<>()));

        // 1. 파일: 영향 범위를 확정할 수 없는 변경과 이번 PR 이 바꾼 테스트
        for (ChangedFile f : in.files()) {
            String path = f.filename();
            if (ignored(path)) {
                continue;
            }
            if (!path.endsWith(".java")) {
                full.add("빌드 · 설정 · 리소스 변경: " + path);
                continue;
            }
            if (path.contains(TEST)) {
                List<String> classes = classesIn(path, headTests);
                if (classes.isEmpty()) {
                    classes = classesIn(path, baseTests);
                    if (!classes.isEmpty() || f.removed()) {
                        // 지운 테스트 클래스는 돌릴 것이 없다
                        continue;
                    }
                    full.add("테스트 보조 코드 변경 (여러 테스트가 함께 쓸 수 있음): " + path);
                    continue;
                }
                for (String c : classes) {
                    reasons.get(c).add(new Reason("CHANGED_TEST", null, "이번 PR 이 바꾼 테스트"));
                }
                continue;
            }
            if (!path.contains(MAIN)) {
                full.add("표준 위치(src/main/java · src/test/java) 밖의 Java 변경: " + path);
                continue;
            }
            if (!"added".equals(f.status()) && !f.removed()) {
                outsideMethods(f, in.base(), in.head()).ifPresent(line ->
                        full.add("메서드 밖 변경 (필드 · 상수 · 클래스 선언): " + path + " — " + line));
            }
        }

        // 2. 바뀐 메서드 → 닿는 테스트
        List<Change> changes = new ArrayList<>();
        for (ChangedMethod m : in.changed()) {
            if (m.test()) {
                continue;
            }
            String traced = traced(m, in.base(), in.head());
            Set<String> byTrace = new LinkedHashSet<>();
            for (String test : ref.testsReaching(traced)) {
                if (!EvidenceTest.isEvidence(test)) {
                    byTrace.add(topLevelOfTrace(test));
                }
            }
            Set<String> byStatic = staticTests(m, in.base(), in.head(), in.staticDepth());
            String reach;
            if (!byTrace.isEmpty()) {
                reach = "TRACED";
            } else if (interfaceMethod(m, in.head(), in.base())) {
                reach = "UNTRACKED";
                full.add("인터페이스 기본 메서드 변경 (실행 기록을 남기지 않는 위치): " + m.typeFqn() + "#" + m.name());
            } else if (ref.callsOf(traced) > 0) {
                // 기록에는 불렸는데 어떤 테스트에도 묶이지 않음 = 애플리케이션 시작이나 다른 스레드에서 실행됨
                reach = "OUTSIDE";
                full.add("테스트 밖(애플리케이션 시작 · 다른 스레드)에서 실행되는 메서드 변경: " + m.typeFqn() + "#" + m.name());
            } else {
                reach = byStatic.isEmpty() ? "NONE" : "STATIC";
            }
            String label = label(m);
            for (String c : byTrace) {
                reasons.computeIfPresent(c, (k, v) -> add(v, new Reason("TRACE", label, "기록상 " + label + " 를 지나감")));
            }
            for (String c : byStatic) {
                if (!byTrace.contains(c)) {
                    reasons.computeIfPresent(c, (k, v) -> add(v, new Reason("STATIC", label, "호출 그래프상 " + label + " 를 부름")));
                }
            }
            Set<String> all = new LinkedHashSet<>(byTrace);
            all.addAll(byStatic);
            all.retainAll(headTests.keySet());
            changes.add(new Change(label, m.kind().name(), m.file(), m.line(), reach, List.copyOf(all)));
        }

        // 3. 기준 기록에 없는 테스트는 무엇을 지나가는지 모르니 돌린다
        if (!ref.tests().isEmpty()) {
            for (String c : headTests.keySet()) {
                if (!reached.containsKey(c) && reasons.get(c).isEmpty()) {
                    reasons.get(c).add(new Reason("UNKNOWN", null, "기준 기록에 없는 테스트 (기록 뒤에 생겼거나 돌지 않았음)"));
                }
            }
        }

        boolean fullRun = !full.isEmpty();
        List<TestClass> tests = new ArrayList<>();
        double referenceSeconds = 0;
        double skippedSeconds = 0;
        for (Map.Entry<String, String> e : headTests.entrySet()) {
            String c = e.getKey();
            List<Reason> why = reasons.get(c);
            boolean impacted = !why.isEmpty();
            boolean run = impacted || fullRun || in.shadow();
            Set<Integer> methods = reached.get(c);
            if (!impacted) {
                why = List.of(new Reason("NO_IMPACT", null, methods == null ? "바뀐 메서드에 닿지 않음"
                        : "기록상 메서드 " + methods.size() + "개를 지나가지만 바뀐 메서드는 없음"));
            }
            double s = in.seconds().getOrDefault(c, 0.0);
            referenceSeconds += s;
            if (!run) {
                skippedSeconds += s;
            }
            tests.add(new TestClass(c, e.getValue(), impacted, run, List.copyOf(why), s, methods == null ? -1 : methods.size()));
        }
        tests.sort(Comparator.comparing((TestClass t) -> !t.impacted()).thenComparing(TestClass::className));
        int impacted = (int) tests.stream().filter(TestClass::impacted).count();
        int run = (int) tests.stream().filter(TestClass::run).count();
        return new TestSelection(fullRun ? TestSelection.FULL : TestSelection.SELECTED, in.shadow(),
                dedupe(full), in.referenceMeta(), changes, tests, tests.size(), impacted, run,
                round(referenceSeconds), round(skippedSeconds), null);
    }

    /** 실행할 수 있는 테스트 클래스 (최상위 클래스 FQN → 파일). @Test 류 메서드가 있는 타입의 최상위 클래스 */
    static Map<String, String> testClasses(RepoIndex index) {
        Map<String, String> result = new java.util.TreeMap<>();
        for (MethodInfo m : index.methods().values()) {
            if (!m.test() || m.implicit() || TEST_ANNOTATIONS.stream().noneMatch(m::hasAnnotation)) {
                continue;
            }
            TypeInfo type = index.types().get(m.typeFqn());
            if (type == null || !type.file().contains(TEST)) {
                continue;
            }
            topLevel(type.fqn(), type.file()).ifPresent(top -> result.put(top, type.file()));
        }
        return result;
    }

    /** 중첩 클래스 FQN(com.a.FooTest.Inner)을 파일 이름으로 최상위 클래스(com.a.FooTest)로 */
    static Optional<String> topLevel(String fqn, String file) {
        String simple = file.substring(file.lastIndexOf('/') + 1).replaceFirst("\\.java$", "");
        String[] parts = fqn.split("\\.");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            sb.append(sb.length() == 0 ? "" : ".").append(p);
            if (p.equals(simple)) {
                return Optional.of(sb.toString());
            }
        }
        return Optional.empty();
    }

    /** 기록의 테스트 이름(com.a.FooTest$Inner#t) → 최상위 클래스 */
    static String topLevelOfTrace(String test) {
        String type = test.contains("#") ? test.substring(0, test.indexOf('#')) : test;
        return type.contains("$") ? type.substring(0, type.indexOf('$')) : type;
    }

    /** 기준 기록에서 테스트 클래스별로 지나간 메서드 */
    private static Map<String, Set<Integer>> reachedByClass(Trace ref) {
        Map<String, Set<Integer>> result = new LinkedHashMap<>();
        ref.tests().forEach((test, methods) -> {
            if (!EvidenceTest.isEvidence(test)) {
                result.computeIfAbsent(topLevelOfTrace(test), k -> new HashSet<>()).addAll(methods);
            }
        });
        return result;
    }

    private static List<String> classesIn(String path, Map<String, String> tests) {
        return tests.entrySet().stream().filter(e -> e.getValue().equals(path)).map(Map.Entry::getKey).toList();
    }

    /** 기록의 이름. 지운 · 바뀐 메서드는 기록(전체 실행 당시) 쪽 이름이 base 와 같다 */
    private static String traced(ChangedMethod m, RepoIndex base, RepoIndex head) {
        boolean constructor = Optional.ofNullable(m.id()).flatMap(head::method)
                .or(() -> Optional.ofNullable(m.baseId()).flatMap(base::method))
                .map(MethodInfo::constructor).orElse(false);
        return m.typeFqn() + "#" + (constructor ? "<init>" : m.name());
    }

    private static String label(ChangedMethod m) {
        String simple = m.typeFqn().substring(m.typeFqn().lastIndexOf('.') + 1);
        return simple + "#" + m.name();
    }

    private static boolean interfaceMethod(ChangedMethod m, RepoIndex head, RepoIndex base) {
        TypeInfo t = Optional.ofNullable(head.types().get(m.typeFqn())).orElse(base.types().get(m.typeFqn()));
        return t != null && "interface".equals(t.kind().toLowerCase(Locale.ROOT)) && !m.signatureChanged()
                && m.kind() == ChangedMethod.Kind.MODIFIED && m.bodyChanged();
    }

    /** 호출 그래프에서 바뀐 메서드를 (거슬러 올라가) 부르는 테스트 클래스 */
    private static Set<String> staticTests(ChangedMethod m, RepoIndex base, RepoIndex head, int depth) {
        Set<String> result = new LinkedHashSet<>();
        if (m.id() != null) {
            walkCallers(m.id(), head, depth, result);
        }
        if (m.baseId() != null) {
            walkCallers(m.baseId(), base, depth, result);
        }
        return result;
    }

    private static void walkCallers(String start, RepoIndex index, int depth, Set<String> out) {
        Set<String> seen = new HashSet<>();
        Deque<String[]> queue = new ArrayDeque<>();
        queue.add(new String[] {start, "0"});
        seen.add(start);
        while (!queue.isEmpty()) {
            String[] cur = queue.poll();
            int d = Integer.parseInt(cur[1]);
            for (CallSite call : index.callersOf(cur[0])) {
                Optional<MethodInfo> caller = index.method(call.callerId());
                if (caller.isEmpty()) {
                    continue;
                }
                MethodInfo c = caller.get();
                if (c.test() && c.file().contains(TEST)) {
                    TypeInfo type = index.types().get(c.typeFqn());
                    if (type != null) {
                        topLevel(type.fqn(), type.file()).ifPresent(out::add);
                    }
                }
                if (d + 1 < depth && seen.add(c.id())) {
                    queue.add(new String[] {c.id(), String.valueOf(d + 1)});
                }
            }
        }
    }

    /** 메서드 밖에서 바뀐 의미 있는 줄 하나 (없으면 비어 있음). patch 가 없으면 확인할 수 없어 바뀐 것으로 본다 */
    static Optional<String> outsideMethods(ChangedFile f, RepoIndex base, RepoIndex head) {
        if (f.patchText() == null) {
            return Optional.of("patch 없음");
        }
        String basePath = f.previousFilename() == null ? f.filename() : f.previousFilename();
        int oldLine = 0;
        int newLine = 0;
        for (String raw : f.patchText().split("\n", -1)) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (line.startsWith("@@")) {
                java.util.regex.Matcher h = HUNK.matcher(line);
                if (h.find()) {
                    oldLine = Integer.parseInt(h.group(1));
                    newLine = Integer.parseInt(h.group(2));
                }
                continue;
            }
            if (line.startsWith("\\")) {
                continue;
            }
            if (line.startsWith("+")) {
                if (!trivial(line.substring(1)) && head.methodAt(f.filename(), newLine).isEmpty()) {
                    return Optional.of(newLine + "행 " + cut(line.substring(1).strip()));
                }
                newLine++;
            } else if (line.startsWith("-")) {
                if (!trivial(line.substring(1)) && base.methodAt(basePath, oldLine).isEmpty()) {
                    return Optional.of("base " + oldLine + "행 " + cut(line.substring(1).strip()));
                }
                oldLine++;
            } else {
                oldLine++;
                newLine++;
            }
        }
        return Optional.empty();
    }

    private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");

    private static boolean trivial(String line) {
        return TRIVIAL_LINE.matcher(line).matches();
    }

    private static String cut(String s) {
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }

    private static <T> List<T> add(List<T> list, T item) {
        if (!list.contains(item)) {
            list.add(item);
        }
        return list;
    }

    private static List<String> dedupe(List<String> reasons) {
        List<String> result = new ArrayList<>(new LinkedHashSet<>(reasons));
        if (result.size() > MAX_LISTED) {
            int more = result.size() - MAX_LISTED;
            result = new ArrayList<>(result.subList(0, MAX_LISTED));
            result.add("외 " + more + "건");
        }
        return result;
    }

    private static double round(double seconds) {
        return Math.round(seconds * 10) / 10.0;
    }

    /** 테스트 결과에 영향을 줄 수 없는 파일인지. 앞에 .* 를 붙인 정규식은 긴 경로에서 느려서 나눠 본다 */
    static boolean ignored(String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        return IGNORED_DIR.matcher(path).matches() || IGNORED_EXT.matcher(path).find()
                || IGNORED_NAME.matcher(path).matches() || lower.startsWith(STATIC) || lower.contains("/" + STATIC);
    }
}
