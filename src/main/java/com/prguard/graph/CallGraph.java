package com.prguard.graph;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GitNexus 가 만든 함수 단위 호출 그래프 (기본 브랜치의 한 커밋 기준). 언어를 가리지 않는다.
 * 리뷰가 바뀐 줄 → 그 줄을 감싸는 함수 → 그 함수를 부르는 함수 순으로 변경 영향을 따라갈 때 쓴다.
 *
 * <p>JSON 은 두 가지를 읽는다.
 * <ul>
 *   <li>{@code graph.impact}: {files, functions: [[파일 번호, 이름, 시작 줄, 끝 줄]], calls: [[부르는 쪽, 불리는 쪽]]}</li>
 *   <li>예전 그래프의 화면용 {@code functions: [{id, name, file, line, endLine}]}, {@code calls: [{source, target}]}</li>
 * </ul>
 *
 * @param commitSha 그래프를 만든 커밋
 * @param full      레포 전체를 담았나 (false 면 화면용으로 잘린 그래프라 빠진 호출이 있을 수 있다)
 */
public final class CallGraph {

    /** 함수 하나. 줄은 1 부터, 그래프를 만든 커밋 기준 */
    public record Function(int index, String file, String name, int line, int endLine) {

        public boolean covers(int from, int to) {
            return line <= to && from <= endLine;
        }

        public String label() {
            return file + "#" + name;
        }
    }

    private final String commitSha;
    private final boolean full;
    private final boolean truncated;
    private final List<Function> functions;
    private final Map<String, List<Function>> byFile = new HashMap<>();
    private final List<List<Integer>> callers;

    private CallGraph(String commitSha, boolean full, boolean truncated, List<Function> functions,
                      List<int[]> calls) {
        this.commitSha = commitSha;
        this.full = full;
        this.truncated = truncated;
        this.functions = List.copyOf(functions);
        for (Function f : functions) {
            byFile.computeIfAbsent(f.file(), k -> new ArrayList<>()).add(f);
        }
        this.callers = new ArrayList<>(functions.size());
        for (int i = 0; i < functions.size(); i++) {
            callers.add(new ArrayList<>());
        }
        for (int[] call : calls) {
            if (call[0] != call[1]) {
                callers.get(call[1]).add(call[0]);
            }
        }
    }

    public static CallGraph parse(String commitSha, boolean full, JsonNode root) {
        List<Function> functions = new ArrayList<>();
        List<int[]> calls = new ArrayList<>();
        JsonNode fns = root.path("functions");
        if (fns.size() > 0 && fns.get(0).isArray()) {
            JsonNode files = root.path("files");
            for (JsonNode f : fns) {
                functions.add(new Function(functions.size(), files.path(f.path(0).asInt()).asText(),
                        f.path(1).asText(), f.path(2).asInt(), f.path(3).asInt()));
            }
            int n = functions.size();
            for (JsonNode c : root.path("calls")) {
                int source = c.path(0).asInt(-1);
                int target = c.path(1).asInt(-1);
                if (source >= 0 && source < n && target >= 0 && target < n) {
                    calls.add(new int[] {source, target});
                }
            }
        } else {
            Map<String, Integer> ids = new HashMap<>();
            for (JsonNode f : fns) {
                ids.put(f.path("id").asText(), functions.size());
                functions.add(new Function(functions.size(), f.path("file").asText(), f.path("name").asText(),
                        f.path("line").asInt(), f.path("endLine").asInt()));
            }
            for (JsonNode c : root.path("calls")) {
                Integer source = ids.get(c.path("source").asText());
                Integer target = ids.get(c.path("target").asText());
                if (source != null && target != null) {
                    calls.add(new int[] {source, target});
                }
            }
        }
        return new CallGraph(commitSha, full, root.path("truncated").asBoolean(false), functions, calls);
    }

    public String commitSha() {
        return commitSha;
    }

    /** 레포 전체 호출 그래프이고 상한에서 자르지도 않았나 */
    public boolean complete() {
        return full && !truncated;
    }

    public int size() {
        return functions.size();
    }

    public boolean hasFile(String file) {
        return byFile.containsKey(file);
    }

    public List<Function> functionsIn(String file) {
        return byFile.getOrDefault(file, List.of());
    }

    /** 이 줄 범위와 겹치는 함수들. 안쪽 함수(람다 · 중첩 함수)와 바깥 함수가 함께 걸릴 수 있다 */
    public List<Function> functionsAt(String file, int from, int to) {
        return functionsIn(file).stream().filter(f -> f.covers(from, to)).toList();
    }

    /** 이 함수를 직접 부르는 함수들 */
    public Set<Function> callersOf(Function f) {
        Set<Function> result = new LinkedHashSet<>();
        for (int i : callers.get(f.index())) {
            result.add(functions.get(i));
        }
        return result;
    }
}
