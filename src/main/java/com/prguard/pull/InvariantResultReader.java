package com.prguard.pull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.zip.ZipInputStream;

/** Validates bounded Actions artifacts without extracting files or trusting reported verdicts. */
final class InvariantResultReader {
    private static final int MAX_JSON_BYTES = 64_000;
    private final ObjectMapper mapper;

    InvariantResultReader(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    JsonNode read(byte[] archive, String head, String base) {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            var entry = zip.getNextEntry();
            if (entry == null || !"invariant-result.json".equals(entry.getName()) || entry.isDirectory()) throw new IllegalArgumentException();
            byte[] bytes = zip.readNBytes(MAX_JSON_BYTES + 1);
            if (bytes.length > MAX_JSON_BYTES || zip.getNextEntry() != null) throw new IllegalArgumentException();
            JsonNode result = mapper.readTree(bytes);
            if (result == null || !result.isObject()) throw new IllegalArgumentException();
            String status = result.path("status").asText();
            if ("execution_error".equals(status)) {
                String reason = result.path("error").asText("테스트 실행 실패");
                return mapper.valueToTree(Map.of("status", status, "error", reason.substring(0, Math.min(1500, reason.length()))));
            }
            if (!head.equals(result.path("headSha").asText()) || !base.equals(result.path("baseSha").asText())
                    || !InvariantRuleCompiler.RULE_ID.equals(result.path("ruleId").asText())) throw new IllegalArgumentException();
            if ("no_counterexample".equals(status)) {
                if (result.path("max_examples").asInt() != 100 || result.path("baseExecuted").asBoolean(true)) throw new IllegalArgumentException();
            } else {
                if (!result.path("input").path("title").asText().matches("[abcXYZ0123]{1,20}")
                        || !result.path("input").path("content").asText().matches("[abcXYZ0123]{1,20}")) throw new IllegalArgumentException();
                boolean[] b = outcomes(result.path("base")), h = outcomes(result.path("head"));
                String calculated = classify(b, h);
                if (!calculated.equals(status) || !result.path("baseExecuted").asBoolean()) throw new IllegalArgumentException();
            }
            return result;
        } catch (Exception e) { throw new IllegalArgumentException("Invalid result artifact", e); }
    }

    private static String classify(boolean[] base, boolean[] head) {
        if (base[0] != base[1] || head[0] != head[1] || !head[0]) return "flaky";
        return base[0] ? "existing_violation" : "new_violation";
    }

    private static boolean[] outcomes(JsonNode observations) {
        if (!observations.isArray() || observations.size() != 2) throw new IllegalArgumentException();
        boolean[] result = new boolean[2];
        for (int i = 0; i < 2; i++) {
            JsonNode o = observations.get(i);
            if (o.path("status").asInt() != 200 || !o.path("author_before").isIntegralNumber() || !o.path("author_after").isIntegralNumber()) throw new IllegalArgumentException();
            result[i] = !o.path("author_before").equals(o.path("author_after"));
        }
        return result;
    }

}
