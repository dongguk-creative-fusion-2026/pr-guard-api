package com.prguard.index;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 한 시점(base 또는 head)의 Java 소스 인덱스. */
public final class RepoIndex {

    private final Map<String, TypeInfo> types;
    private final Map<String, MethodInfo> methods;
    private final List<CallSite> calls;
    private final Map<String, List<CallSite>> callersByCallee = new LinkedHashMap<>();
    private final Map<String, List<MethodInfo>> methodsByFile = new LinkedHashMap<>();
    private final int parsedFiles;
    private final List<String> failedFiles;

    RepoIndex(Map<String, TypeInfo> types, Map<String, MethodInfo> methods, List<CallSite> calls,
              int parsedFiles, List<String> failedFiles) {
        this.types = Map.copyOf(types);
        this.methods = Map.copyOf(methods);
        this.calls = List.copyOf(calls);
        this.parsedFiles = parsedFiles;
        this.failedFiles = List.copyOf(failedFiles);
        for (CallSite call : calls) {
            for (String callee : call.calleeIds()) {
                callersByCallee.computeIfAbsent(callee, k -> new ArrayList<>()).add(call);
            }
        }
        for (MethodInfo m : methods.values()) {
            if (!m.implicit()) {
                methodsByFile.computeIfAbsent(m.file(), k -> new ArrayList<>()).add(m);
            }
        }
    }

    public static RepoIndex empty() {
        return new RepoIndex(Map.of(), Map.of(), List.of(), 0, List.of());
    }

    public Map<String, TypeInfo> types() {
        return types;
    }

    public Map<String, MethodInfo> methods() {
        return methods;
    }

    public List<CallSite> calls() {
        return calls;
    }

    public int parsedFiles() {
        return parsedFiles;
    }

    public List<String> failedFiles() {
        return failedFiles;
    }

    public Optional<MethodInfo> method(String id) {
        return Optional.ofNullable(methods.get(id));
    }

    /** 이 메서드를 호출하는 곳 (호출 대상이 확정된 것만). */
    public List<CallSite> callersOf(String methodId) {
        return callersByCallee.getOrDefault(methodId, List.of());
    }

    /** 타입과 이름만 맞는 호출. 시그니처가 바뀌거나 지워진 메서드를 아직 부르는 곳을 찾을 때 쓴다. */
    public List<CallSite> callsByName(String typeFqn, String name) {
        Set<String> related = subtypesAndSelf(typeFqn);
        return calls.stream()
                .filter(c -> c.calleeName().equals(name) && c.scopeType() != null && related.contains(c.scopeType()))
                .toList();
    }

    public List<MethodInfo> methodsIn(String file) {
        return methodsByFile.getOrDefault(file, List.of());
    }

    /** 파일의 해당 라인을 감싸는 가장 안쪽 메서드. */
    public Optional<MethodInfo> methodAt(String file, int line) {
        return methodsIn(file).stream()
                .filter(m -> m.contains(line))
                .min((a, b) -> Integer.compare(a.endLine() - a.startLine(), b.endLine() - b.startLine()));
    }

    /** 자신과 레포 안의 상위 타입들 (가까운 순). */
    public List<TypeInfo> hierarchy(String typeFqn) {
        List<TypeInfo> result = new ArrayList<>();
        Deque<String> queue = new ArrayDeque<>(List.of(typeFqn));
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            String fqn = queue.poll();
            TypeInfo type = types.get(fqn);
            if (type == null || !seen.add(fqn)) {
                continue;
            }
            result.add(type);
            queue.addAll(type.superTypes());
        }
        return result;
    }

    /** 상속 계층에 레포 밖 타입이 섞여 있는가. 그러면 소스에 없는 메서드가 있을 수 있다. */
    public boolean hasExternalHierarchy(String typeFqn) {
        for (TypeInfo type : hierarchy(typeFqn)) {
            if (type.generated() || type.kind().equals("enum") || type.kind().equals("annotation")) {
                return true;
            }
            for (String sup : type.superTypes()) {
                if (!types.containsKey(sup) && !sup.equals("Object") && !sup.equals("java.lang.Object")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 이 타입을 상속·구현하는 레포 안 타입들과 자신. */
    public Set<String> subtypesAndSelf(String typeFqn) {
        Set<String> result = new HashSet<>();
        result.add(typeFqn);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (TypeInfo t : types.values()) {
                if (!result.contains(t.fqn()) && t.superTypes().stream().anyMatch(result::contains)) {
                    result.add(t.fqn());
                    grew = true;
                }
            }
        }
        return result;
    }

    public Collection<TypeInfo> typesIn(String file) {
        return types.values().stream().filter(t -> t.file().equals(file)).toList();
    }
}
