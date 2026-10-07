package com.prguard.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 테스트가 도는 동안 실제로 일어난 호출 (runner/trace-agent 가 JVM 마다 남긴 trace-*.json 을 합친 것).
 *
 * @param methods 함수 이름 (예: com.a.Foo#bar, 생성자는 #&lt;init&gt;)
 * @param counts  함수별 불린 횟수
 * @param edges   [호출하는 쪽, 불린 쪽, 횟수]
 * @param tests   테스트 함수 이름 → 그 테스트가 지나간 함수 번호들
 */
public record Trace(List<String> methods, List<Long> counts, List<long[]> edges, Map<String, List<Integer>> tests) {

    /** 너무 큰 레포에서 행이 커지지 않게 */
    static final int MAX_METHODS = 20_000;
    static final int MAX_EDGES = 60_000;

    /** 여러 JVM 의 기록을 함수 이름 기준으로 합친다. 테스트 클래스의 생성자 같은 준비 단계는 테스트로 치지 않는다 */
    public static Trace merge(List<byte[]> parts, ObjectMapper mapper) {
        Map<String, Integer> ids = new LinkedHashMap<>();
        List<String> names = new ArrayList<>();
        List<Long> counts = new ArrayList<>();
        Map<Long, Long> edges = new LinkedHashMap<>();
        Map<String, Set<Integer>> tests = new LinkedHashMap<>();
        for (byte[] part : parts) {
            JsonNode root;
            try {
                root = mapper.readTree(part);
            } catch (IOException e) {
                continue;
            }
            JsonNode methods = root.path("methods");
            int[] local = new int[methods.size()];
            for (int i = 0; i < methods.size(); i++) {
                String name = methods.get(i).asText();
                Integer id = ids.get(name);
                if (id == null && ids.size() < MAX_METHODS) {
                    id = ids.size();
                    ids.put(name, id);
                    names.add(name);
                    counts.add(0L);
                }
                local[i] = id == null ? -1 : id;
                if (id != null) {
                    counts.set(id, counts.get(id) + root.path("counts").path(i).asLong());
                }
            }
            for (JsonNode e : root.path("edges")) {
                int a = map(local, e.path(0).asInt(-1));
                int b = map(local, e.path(1).asInt(-1));
                if (a < 0 || b < 0) {
                    continue;
                }
                long key = ((long) a << 32) | b;
                if (edges.containsKey(key) || edges.size() < MAX_EDGES) {
                    edges.merge(key, e.path(2).asLong(), Long::sum);
                }
            }
            root.path("tests").properties().forEach(entry -> {
                int t = map(local, parseInt(entry.getKey()));
                if (t < 0) {
                    return;
                }
                String name = names.get(t);
                if (name.endsWith("#<init>") || name.endsWith("#<clinit>")) {
                    return;
                }
                Set<Integer> seen = tests.computeIfAbsent(name, k -> new LinkedHashSet<>());
                for (JsonNode m : entry.getValue()) {
                    int id = map(local, m.asInt(-1));
                    if (id >= 0) {
                        seen.add(id);
                    }
                }
            });
        }
        List<long[]> edgeList = new ArrayList<>();
        edges.forEach((k, n) -> edgeList.add(new long[] {k >>> 32, k & 0xffffffffL, n}));
        Map<String, List<Integer>> testMap = new LinkedHashMap<>();
        tests.forEach((k, v) -> testMap.put(k, List.copyOf(v)));
        return new Trace(List.copyOf(ids.keySet()), counts, edgeList, testMap);
    }

    public boolean isEmpty() {
        return methods.isEmpty();
    }

    /** 이 함수를 지나간 테스트들. 중첩 클래스는 인덱스(.)와 기록($) 표기가 달라 맞춰 본다 */
    public List<String> testsReaching(String traced) {
        String want = normalize(traced);
        Set<Integer> targets = new LinkedHashSet<>();
        for (int i = 0; i < methods.size(); i++) {
            if (normalize(methods.get(i)).equals(want)) {
                targets.add(i);
            }
        }
        if (targets.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        tests.forEach((test, reached) -> {
            if (reached.stream().anyMatch(targets::contains)) {
                result.add(test);
            }
        });
        return result;
    }

    /** 이 함수가 불린 횟수 (같은 이름의 오버로드는 합친다) */
    public long callsOf(String traced) {
        String want = normalize(traced);
        long sum = 0;
        for (int i = 0; i < methods.size(); i++) {
            if (normalize(methods.get(i)).equals(want)) {
                sum += counts.get(i);
            }
        }
        return sum;
    }

    /** 화면 · 저장용 JSON 구조 */
    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("methods", methods);
        m.put("counts", counts);
        m.put("edges", edges);
        m.put("tests", tests);
        return m;
    }

    public static Trace fromJson(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) {
            return new Trace(List.of(), List.of(), List.of(), Map.of());
        }
        try {
            JsonNode root = mapper.readTree(json);
            List<String> methods = new ArrayList<>();
            root.path("methods").forEach(n -> methods.add(n.asText()));
            List<Long> counts = new ArrayList<>();
            root.path("counts").forEach(n -> counts.add(n.asLong()));
            List<long[]> edges = new ArrayList<>();
            root.path("edges").forEach(e -> edges.add(new long[] {e.path(0).asLong(), e.path(1).asLong(), e.path(2).asLong()}));
            Map<String, List<Integer>> tests = new HashMap<>();
            root.path("tests").properties().forEach(e -> {
                List<Integer> ids = new ArrayList<>();
                e.getValue().forEach(n -> ids.add(n.asInt()));
                tests.put(e.getKey(), ids);
            });
            return new Trace(methods, counts, edges, tests);
        } catch (IOException e) {
            return new Trace(List.of(), List.of(), List.of(), Map.of());
        }
    }

    private static String normalize(String name) {
        return name.replace('$', '.');
    }

    private static int map(int[] local, int i) {
        return i >= 0 && i < local.length ? local[i] : -1;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
