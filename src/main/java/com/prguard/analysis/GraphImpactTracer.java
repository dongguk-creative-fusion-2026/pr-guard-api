package com.prguard.analysis;

import com.prguard.diff.Patch;
import com.prguard.graph.CallGraph;
import com.prguard.index.CallSite;
import com.prguard.index.ChangedMethod;
import com.prguard.index.RepoIndex;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 바뀐 줄 → GitNexus 함수 → 그 함수를 부르는 함수 순으로 변경 영향을 따라간다.
 *
 * <p>그래프는 기본 브랜치(base 쪽) 기준이라 바뀐 위치는 patch 의 base 쪽 줄(hunk 의 old 범위)로 찾는다.
 * 지운 파일은 그 파일의 함수 전부, 새 파일은 그래프에 없으므로 {@link GraphImpact#unmapped()} 로 남긴다.
 * 1단계 호출 중 JavaIndexer 가 head 에서 같은 호출을 찾은 것은 확인됨으로 표시한다.
 */
public final class GraphImpactTracer {

    /** 부르는 쪽을 따라갈 단계 수 */
    static final int MAX_DEPTH = 3;
    /** 0단계 · 영향 함수를 합쳐 이 수까지만 담는다 */
    static final int MAX_FUNCTIONS = 200;

    private GraphImpactTracer() {
    }

    public static GraphImpact trace(CallGraph graph, List<ChangedFile> files, String baseSha, RepoIndex head,
                                    List<ChangedMethod> changedMethods) {
        Map<CallGraph.Function, Integer> depth = new LinkedHashMap<>();
        Map<CallGraph.Function, CallGraph.Function> via = new HashMap<>();
        Set<String> unmapped = new LinkedHashSet<>();
        Set<String> touched = new LinkedHashSet<>();
        Deque<CallGraph.Function> queue = new ArrayDeque<>();
        boolean truncated = false;

        for (ChangedFile file : files) {
            touched.add(file.filename());
            String basePath = file.previousFilename() != null ? file.previousFilename() : file.filename();
            touched.add(basePath);
            List<CallGraph.Function> hit = "added".equals(file.status()) || !graph.hasFile(basePath)
                    ? List.of()
                    : file.removed() ? graph.functionsIn(basePath) : touchedFunctions(graph, basePath, file.patch());
            if (hit.isEmpty()) {
                unmapped.add(file.filename());
            }
            for (CallGraph.Function f : hit) {
                if (depth.putIfAbsent(f, 0) == null) {
                    queue.add(f);
                }
            }
        }

        while (!queue.isEmpty()) {
            CallGraph.Function callee = queue.poll();
            int d = depth.get(callee);
            if (d >= MAX_DEPTH) {
                continue;
            }
            for (CallGraph.Function caller : graph.callersOf(callee)) {
                if (depth.containsKey(caller)) {
                    continue;
                }
                if (depth.size() >= MAX_FUNCTIONS) {
                    truncated = true;
                    break;
                }
                depth.put(caller, d + 1);
                via.put(caller, callee);
                queue.add(caller);
            }
        }

        List<CallSite> javaCallers = javaCallers(head, changedMethods);
        List<GraphImpact.Fn> changed = new ArrayList<>();
        List<GraphImpact.Fn> impacted = new ArrayList<>();
        Set<String> impactedFiles = new LinkedHashSet<>();
        depth.forEach((f, d) -> {
            CallGraph.Function prev = via.get(f);
            boolean confirmed = d == 1 && confirmedByIndex(f, javaCallers, touched);
            GraphImpact.Fn fn = new GraphImpact.Fn(f.file(), f.name(), f.line(), d,
                    prev == null ? null : prev.label(), confirmed);
            if (d == 0) {
                changed.add(fn);
            } else {
                impacted.add(fn);
                if (!touched.contains(f.file())) {
                    impactedFiles.add(f.file());
                }
            }
        });
        boolean baseMatches = baseSha != null && baseSha.equals(graph.commitSha());
        return new GraphImpact(graph.commitSha(), baseMatches, graph.complete(), changed, impacted,
                List.copyOf(impactedFiles), List.copyOf(unmapped), truncated);
    }

    /** hunk 의 base 쪽 범위와 겹치는 함수. 줄을 넣기만 한 hunk 는 넣은 자리(바로 앞 줄)를 본다 */
    private static List<CallGraph.Function> touchedFunctions(CallGraph graph, String file, Patch patch) {
        Set<CallGraph.Function> result = new LinkedHashSet<>();
        for (Patch.Hunk h : patch.hunks()) {
            int from = Math.max(1, h.oldStart());
            int to = h.oldStart() + Math.max(h.oldLines(), 1) - 1;
            // hunk 앞뒤의 주변 줄은 바뀌지 않았으므로 실제로 지운 줄이 있으면 그 줄만 본다
            if (!h.removedLines().isEmpty()) {
                for (int line : h.removedLines()) {
                    result.addAll(graph.functionsAt(file, line, line));
                }
                continue;
            }
            int at = insertionPoint(h);
            List<CallGraph.Function> around = graph.functionsAt(file, at, at);
            result.addAll(around.isEmpty() ? graph.functionsAt(file, from, to) : around);
        }
        return List.copyOf(result);
    }

    /** 넣기만 한 hunk 에서 새 줄이 들어간 base 쪽 위치: 첫 추가 줄 바로 앞의 주변 줄 */
    private static int insertionPoint(Patch.Hunk h) {
        // 주변 줄 없이 넣기만 하면 oldStart 가 곧 "이 줄 뒤에 넣었다"는 위치다
        if (h.addedLines().isEmpty() || h.oldLines() == 0) {
            return Math.max(1, h.oldStart());
        }
        int firstAdded = h.addedLines().get(0);
        long before = h.contextLines().stream().filter(l -> l < firstAdded).count();
        return Math.max(1, h.oldStart() + (int) before - 1);
    }

    private static List<CallSite> javaCallers(RepoIndex head, List<ChangedMethod> changedMethods) {
        List<CallSite> result = new ArrayList<>();
        for (ChangedMethod m : changedMethods) {
            if (m.id() != null) {
                result.addAll(head.callersOf(m.id()));
            }
        }
        return result;
    }

    /**
     * JavaIndexer 가 같은 함수 안에서 바뀐 메서드를 부르는 곳을 찾았나.
     * PR 이 고친 파일은 head 와 그래프의 줄이 어긋나므로 파일만 맞춰 본다.
     */
    private static boolean confirmedByIndex(CallGraph.Function f, List<CallSite> javaCallers, Set<String> touched) {
        for (CallSite c : javaCallers) {
            if (!f.file().equals(c.file())) {
                continue;
            }
            if (touched.contains(f.file()) || f.covers(c.line(), c.line())) {
                return true;
            }
        }
        return false;
    }
}
