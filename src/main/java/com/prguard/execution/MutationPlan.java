package com.prguard.execution;

import com.prguard.index.ChangedMethod;
import com.prguard.index.MethodInfo;
import com.prguard.index.RepoIndex;
import com.prguard.index.TypeInfo;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * head 실행에서 PIT 로 변이를 심을 곳.
 *
 * @param classes        변이를 심을 최상위 클래스 (중첩 클래스는 러너가 Outer* 로 포함)
 * @param tests          변이를 잡아 볼 테스트 클래스 (영향 있는 테스트, 없으면 head 의 테스트 전부)
 * @param methods        결과를 걸러 볼 바뀐 메서드 (head 줄 범위)
 * @param timeoutSeconds 러너에서 PIT 에 주는 시간
 */
public record MutationPlan(List<String> classes, List<String> tests, List<Target> methods, long timeoutSeconds) {

    /** @param id 화면 표기 (예: PostService#delete) */
    public record Target(String id, String typeFqn, String name, String file, int startLine, int endLine) {

        /** PIT 결과(클래스 · 줄)가 이 메서드 안인가. 메서드 안의 람다 · 익명 클래스도 줄 범위로 포함한다 */
        boolean matches(String mutatedClass, int line) {
            String type = mutatedClass.replace('$', '.');
            boolean sameType = type.equals(typeFqn) || type.startsWith(typeFqn + ".");
            return sameType && line >= startLine && line <= endLine;
        }
    }

    /**
     * 본문이 바뀌었거나 새로 생긴 메서드(테스트 제외)가 있는 클래스. 바뀐 메서드가 많은 클래스부터 maxClasses 개.
     *
     * @return 심을 곳이 없거나 돌릴 테스트가 없으면 비어 있음
     */
    public static Optional<MutationPlan> of(List<ChangedMethod> changed, RepoIndex head, TestSelection selection,
                                            int maxClasses, long timeoutSeconds) {
        Map<String, List<Target>> byClass = new LinkedHashMap<>();
        for (ChangedMethod m : changed) {
            if (m.test() || m.id() == null || m.kind() == ChangedMethod.Kind.REMOVED
                    || (m.kind() == ChangedMethod.Kind.MODIFIED && !m.bodyChanged())) {
                continue;
            }
            Optional<MethodInfo> info = head.method(m.id());
            TypeInfo type = head.types().get(m.typeFqn());
            if (info.isEmpty() || type == null || info.get().implicit() || !type.file().contains("src/main/java/")) {
                continue;
            }
            Optional<String> top = TestSelector.topLevel(type.fqn(), type.file());
            if (top.isEmpty()) {
                continue;
            }
            String simple = m.typeFqn().substring(m.typeFqn().lastIndexOf('.') + 1);
            byClass.computeIfAbsent(top.get(), k -> new ArrayList<>()).add(new Target(simple + "#" + m.name(), m.typeFqn(),
                    info.get().constructor() ? "<init>" : m.name(), m.file(), info.get().startLine(), info.get().endLine()));
        }
        if (byClass.isEmpty()) {
            return Optional.empty();
        }
        List<String> classes = byClass.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, List<Target>> e) -> -e.getValue().size()))
                .limit(maxClasses).map(Map.Entry::getKey).toList();
        List<Target> methods = classes.stream().flatMap(c -> byClass.get(c).stream()).toList();
        Set<String> tests = new LinkedHashSet<>();
        selection.tests().stream().filter(TestSelection.TestClass::impacted).forEach(t -> tests.add(t.className()));
        if (tests.isEmpty()) {
            selection.tests().forEach(t -> tests.add(t.className()));
        }
        if (tests.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new MutationPlan(classes, List.copyOf(tests), methods, timeoutSeconds));
    }
}
