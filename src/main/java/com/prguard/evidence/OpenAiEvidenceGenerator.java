package com.prguard.evidence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prguard.review.OpenAiProperties;
import com.prguard.review.OpenAiReviewer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** OpenAI Responses API 로 증거 테스트를 쓴다. 프롬프트는 resources/prompts/evidence-system.md */
public class OpenAiEvidenceGenerator implements EvidenceGenerator {

    static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "additionalProperties", false,
            "required", List.of("tests", "probes"),
            "properties", Map.of(
                    "probes", Map.of(
                            "type", "array",
                            "items", Map.of(
                                    "type", "object",
                                    "additionalProperties", false,
                                    "required", List.of("target", "code"),
                                    "properties", Map.of(
                                            "target", Map.of("type", "string"),
                                            "code", Map.of("type", "string")))),
                    "tests", Map.of(
                            "type", "array",
                            "items", Map.of(
                                    "type", "object",
                                    "additionalProperties", false,
                                    "required", List.of("target", "intent", "code"),
                                    "properties", Map.of(
                                            "target", Map.of("type", "string"),
                                            "intent", Map.of("type", "string"),
                                            "code", Map.of("type", "string"))))));

    private final RestClient http;
    private final OpenAiProperties props;
    private final ObjectMapper mapper;
    private final String system;

    public OpenAiEvidenceGenerator(RestClient.Builder builder, OpenAiProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        try {
            this.system = new ClassPathResource("prompts/evidence-system.md").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        // 러너가 증거 테스트를 기다리는 시간(3분)보다 짧게
        factory.setReadTimeout(Duration.ofSeconds(150));
        this.http = builder.clone()
                .baseUrl(props.apiUrl())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiKey())
                .build();
    }

    @Override
    public String name() {
        return "openai:" + props.model();
    }

    @Override
    public List<EvidenceTest> generate(EvidenceRequest request) {
        JsonNode res = http.post().uri("/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "model", props.model(),
                        "instructions", system,
                        "input", user(request),
                        "text", Map.of("format", Map.of(
                                "type", "json_schema",
                                "name", "evidence_tests",
                                "strict", true,
                                "schema", SCHEMA))))
                .retrieve()
                .body(JsonNode.class);
        return parse(OpenAiReviewer.outputText(res), request);
    }

    static String user(EvidenceRequest r) {
        StringBuilder sb = new StringBuilder();
        sb.append("레포: ").append(r.repo()).append('\n');
        sb.append("PR #").append(r.number()).append(": ").append(r.title()).append('\n');
        if (r.body() != null && !r.body().isBlank()) {
            sb.append("\n## PR 본문\n").append(cut(r.body().strip(), 3000)).append('\n');
        }
        for (EvidenceRequest.Target t : r.targets()) {
            sb.append("\n# 바뀐 메서드: ").append(t.methodId()).append('\n');
            sb.append("파일: ").append(t.file()).append('\n');
            sb.append("만들 증거 테스트 클래스: ").append(t.className()).append('\n');
            sb.append("만들 관측 테스트 클래스: ").append(EvidenceTest.probeName(t.className())).append('\n');
            sb.append("테스트 라이브러리: ").append(String.join(", ", t.testLibraries())).append('\n');
            sb.append("\n## base (바뀌기 전)\n```java\n").append(t.baseSource()).append("\n```\n");
            sb.append("\n## head (바뀐 뒤)\n```java\n").append(t.headSource()).append("\n```\n");
            sb.append("\n## head 클래스 전체\n```java\n").append(t.headType()).append("\n```\n");
            if (t.existingTest() != null) {
                sb.append("\n## 같은 클래스의 기존 테스트 (준비 코드 참고)\n```java\n").append(t.existingTest()).append("\n```\n");
            }
        }
        return sb.toString();
    }

    /** 겨냥한 메서드와 클래스 이름이 맞는 것만 받는다 */
    List<EvidenceTest> parse(String json, EvidenceRequest request) {
        try {
            JsonNode root = mapper.readTree(json);
            List<EvidenceTest> tests = new ArrayList<>();
            for (JsonNode node : root.path("tests")) {
                String target = node.path("target").asText();
                String code = node.path("code").asText();
                request.targets().stream()
                        .filter(t -> t.methodId().equals(target))
                        .findFirst()
                        .filter(t -> declares(code, t.className()))
                        .ifPresent(t -> tests.add(new EvidenceTest(t.testPath(), t.className(), target,
                                node.path("intent").asText(""), code)));
            }
            for (JsonNode node : root.path("probes")) {
                String target = node.path("target").asText();
                String code = node.path("code").asText();
                request.targets().stream()
                        .filter(t -> t.methodId().equals(target))
                        .findFirst()
                        .filter(t -> declares(code, EvidenceTest.probeName(t.className())) && code.contains("PrGuardProbe.record("))
                        .ifPresent(t -> tests.add(new EvidenceTest(EvidenceTest.probeName(t.testPath()),
                                EvidenceTest.probeName(t.className()), target, "동작 관측", code)));
            }
            return tests;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("OpenAI 응답이 JSON 이 아닙니다: " + e.getOriginalMessage());
        }
    }

    static boolean declares(String code, String className) {
        int dot = className.lastIndexOf('.');
        String pkg = dot < 0 ? "" : className.substring(0, dot);
        String simple = className.substring(dot + 1);
        return code.contains("class " + simple) && (pkg.isEmpty() || code.contains("package " + pkg + ";"));
    }

    private static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "\n... (생략)" : s;
    }
}
