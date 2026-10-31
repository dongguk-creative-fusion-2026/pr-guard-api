package com.prguard.review;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/** OpenAI Responses API 로 기존 코드 충돌을 묻는다. 프롬프트는 resources/prompts/conflict-system.md */
public class OpenAiConflictReviewer implements ConflictReviewer {

    static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "additionalProperties", false,
            "required", List.of("findings"),
            "properties", Map.of(
                    "findings", Map.of(
                            "type", "array",
                            "items", Map.of(
                                    "type", "object",
                                    "additionalProperties", false,
                                    "required", List.of("severity", "method", "file", "line", "title", "message",
                                            "refs"),
                                    "properties", Map.of(
                                            "severity", Map.of("type", "string",
                                                    "enum", List.of("BLOCKER", "MAJOR", "MINOR")),
                                            "method", Map.of("type", "string"),
                                            "file", Map.of("type", "string"),
                                            "line", Map.of("type", List.of("integer", "null")),
                                            "title", Map.of("type", "string"),
                                            "message", Map.of("type", "string"),
                                            "refs", Map.of("type", "array",
                                                    "items", Map.of("type", "string")))))));

    private static final int MAX_REFS = 5;

    private final RestClient http;
    private final OpenAiProperties props;
    private final ObjectMapper mapper;
    private final String system;

    public OpenAiConflictReviewer(RestClient.Builder builder, OpenAiProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        try {
            this.system = new ClassPathResource("prompts/conflict-system.md").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofMinutes(4));
        this.http = builder.clone()
                .baseUrl(props.apiUrl())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiKey())
                .build();
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public ConflictReview review(Input input) {
        JsonNode res = http.post().uri("/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "model", props.model(),
                        "instructions", system,
                        "input", user(input),
                        "text", Map.of("format", Map.of(
                                "type", "json_schema",
                                "name", "contract_conflicts",
                                "strict", true,
                                "schema", SCHEMA))))
                .retrieve()
                .body(JsonNode.class);
        return parse(OpenAiReviewer.outputText(res));
    }

    static String user(Input in) {
        StringBuilder sb = new StringBuilder();
        sb.append("레포: ").append(in.repo()).append('\n');
        sb.append("PR #").append(in.number()).append(": ").append(in.title()).append('\n');
        if (in.body() != null && !in.body().isBlank()) {
            sb.append("\n## PR 본문\n").append(in.body().strip()).append('\n');
        }
        if (!in.commitMessages().isEmpty()) {
            sb.append("\n## 커밋 메시지\n");
            in.commitMessages().forEach(m -> sb.append("- ").append(m.strip().replace("\n", " / ")).append('\n'));
        }
        sb.append("\n## 바뀐 메서드와 기존 코드\n").append(in.context());
        return sb.toString();
    }

    ConflictReview parse(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            List<ConflictReview.Item> items = new ArrayList<>();
            for (JsonNode f : root.path("findings")) {
                List<String> refs = new ArrayList<>();
                for (JsonNode r : f.path("refs")) {
                    String ref = r.asText("").strip();
                    if (!ref.isEmpty() && refs.size() < MAX_REFS) {
                        refs.add(ref);
                    }
                }
                items.add(new ConflictReview.Item(
                        f.path("severity").asText("MINOR"),
                        f.path("method").asText(""),
                        f.path("file").asText(""),
                        f.path("line").isInt() ? f.path("line").asInt() : null,
                        f.path("title").asText(""),
                        f.path("message").asText(""),
                        refs));
            }
            return new ConflictReview(items);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("OpenAI 응답이 JSON 이 아닙니다: " + e.getOriginalMessage());
        }
    }
}
