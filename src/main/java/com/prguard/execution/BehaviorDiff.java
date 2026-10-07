package com.prguard.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prguard.evidence.EvidenceTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 동작 diff: 관측 테스트(PrGuardProbe…)가 base · head 에서 같은 입력으로 남긴 결과를 맞댄다.
 * 코드 diff 대신 "이 입력에 대해 결과가 이렇게 달라졌다" 를 보여 준다.
 */
public final class BehaviorDiff {

    /** 한 실행에서 받을 관측 수 상한 */
    static final int MAX_OBSERVATIONS = 400;
    private static final int MAX_OUT = 300;

    private BehaviorDiff() {
    }

    /** @param probe 관측 테스트 클래스 이름 (PrGuardProbe1Test) */
    public record Observation(String probe, String label, String out) {
    }

    /**
     * @param target  겨냥한 바뀐 메서드
     * @param base    base 결과 (없으면 null: 그 쪽에서 돌지 않음)
     * @param changed base · head 결과가 다르다 (한쪽이 없으면 비교하지 않는다)
     */
    public record Row(String target, String probe, String label, String base, String head, boolean changed) {
    }

    /** probe.jsonl 들을 읽는다. 깨진 줄은 건너뛴다 */
    public static List<Observation> parse(List<byte[]> parts, ObjectMapper mapper) {
        List<Observation> result = new ArrayList<>();
        for (byte[] part : parts) {
            for (String line : new String(part, StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank() || result.size() >= MAX_OBSERVATIONS) {
                    continue;
                }
                try {
                    JsonNode n = mapper.readTree(line);
                    result.add(new Observation(n.path("probe").asText(), cut(n.path("label").asText()), cut(n.path("out").asText())));
                } catch (IOException e) {
                    // 테스트가 중간에 죽으면 마지막 줄이 잘릴 수 있다
                }
            }
        }
        return result;
    }

    public static List<Observation> fromJson(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<List<Observation>>() {});
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    /** 관측 테스트 · 입력 설명이 같은 것끼리 맞댄다. 순서는 head 에서 기록한 순서 (없으면 base) */
    public static List<Row> compare(List<EvidenceTest> tests, List<Observation> base, List<Observation> head) {
        Map<String, String> targetOf = new LinkedHashMap<>();
        for (EvidenceTest t : tests) {
            if (t.probe()) {
                targetOf.put(t.className().substring(t.className().lastIndexOf('.') + 1), t.target());
            }
        }
        Map<String, String> baseOut = index(base);
        Map<String, String> headOut = index(head);
        Set<String> keys = new LinkedHashSet<>(headOut.keySet());
        keys.addAll(baseOut.keySet());
        List<Row> rows = new ArrayList<>();
        for (String key : keys) {
            int sep = key.indexOf('\u0000');
            String probe = key.substring(0, sep);
            String b = baseOut.get(key);
            String h = headOut.get(key);
            rows.add(new Row(targetOf.getOrDefault(probe, probe), probe, key.substring(sep + 1), b, h,
                    b != null && h != null && !b.equals(h)));
        }
        return rows;
    }

    /** 결과가 달라진 줄을 메서드별로 */
    public static Map<String, List<Row>> changedByTarget(List<Row> rows) {
        Map<String, List<Row>> result = new LinkedHashMap<>();
        for (Row r : rows) {
            if (r.changed()) {
                result.computeIfAbsent(r.target(), k -> new ArrayList<>()).add(r);
            }
        }
        return result;
    }

    /** 같은 입력 설명이 여러 번 기록되면(반복문 등) 첫 결과를 쓴다 */
    private static Map<String, String> index(List<Observation> observations) {
        Map<String, String> m = new LinkedHashMap<>();
        for (Observation o : observations) {
            m.putIfAbsent(o.probe() + '\u0000' + o.label(), o.out());
        }
        return m;
    }

    private static String cut(String s) {
        return s.length() > MAX_OUT ? s.substring(0, MAX_OUT) + "…" : s;
    }
}
