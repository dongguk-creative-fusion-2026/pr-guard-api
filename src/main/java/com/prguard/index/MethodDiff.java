package com.prguard.index;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** base 인덱스와 head 인덱스를 메서드 단위로 비교한다. */
public final class MethodDiff {

    private MethodDiff() {
    }

    public static List<ChangedMethod> compute(RepoIndex base, RepoIndex head) {
        List<ChangedMethod> result = new ArrayList<>();
        List<MethodInfo> removed = new ArrayList<>();
        List<MethodInfo> added = new ArrayList<>();

        for (MethodInfo b : base.methods().values()) {
            if (b.implicit()) {
                continue;
            }
            MethodInfo h = head.methods().get(b.id());
            if (h == null) {
                removed.add(b);
            } else {
                boolean body = !b.bodyHash().equals(h.bodyHash());
                boolean annotations = !b.annotationHash().equals(h.annotationHash());
                if (body || annotations) {
                    result.add(modified(b, h, false, body, annotations));
                }
            }
        }
        for (MethodInfo h : head.methods().values()) {
            if (!h.implicit() && !base.methods().containsKey(h.id())) {
                added.add(h);
            }
        }

        // 같은 타입·같은 이름이 한쪽에서 사라지고 한쪽에 생겼으면 시그니처 변경으로 묶는다
        Set<MethodInfo> paired = new HashSet<>();
        for (MethodInfo b : removed) {
            List<MethodInfo> sameName = added.stream()
                    .filter(h -> !paired.contains(h) && h.typeFqn().equals(b.typeFqn()) && h.name().equals(b.name()))
                    .toList();
            long baseSameName = removed.stream()
                    .filter(r -> r.typeFqn().equals(b.typeFqn()) && r.name().equals(b.name()))
                    .count();
            if (sameName.size() == 1 && baseSameName == 1) {
                MethodInfo h = sameName.get(0);
                paired.add(h);
                paired.add(b);
                result.add(modified(b, h, true, !b.bodyHash().equals(h.bodyHash()),
                        !b.annotationHash().equals(h.annotationHash())));
            }
        }
        for (MethodInfo b : removed) {
            if (!paired.contains(b)) {
                result.add(new ChangedMethod(ChangedMethod.Kind.REMOVED, null, b.id(), b.typeFqn(), b.name(), b.file(),
                        b.startLine(), false, false, false, b.test()));
            }
        }
        for (MethodInfo h : added) {
            if (!paired.contains(h)) {
                result.add(new ChangedMethod(ChangedMethod.Kind.ADDED, h.id(), null, h.typeFqn(), h.name(), h.file(),
                        h.startLine(), false, false, false, h.test()));
            }
        }
        result.sort(Comparator.comparing(ChangedMethod::file).thenComparingInt(ChangedMethod::line));
        return result;
    }

    private static ChangedMethod modified(MethodInfo b, MethodInfo h, boolean signature, boolean body,
                                          boolean annotations) {
        return new ChangedMethod(ChangedMethod.Kind.MODIFIED, h.id(), b.id(), h.typeFqn(), h.name(), h.file(),
                h.startLine(), signature, body, annotations, h.test());
    }
}
