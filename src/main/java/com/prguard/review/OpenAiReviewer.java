package com.prguard.review;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** OpenAI Responses API 로 리뷰한다. 응답 형식은 JSON 스키마로 강제한다 (Structured Outputs). */
public class OpenAiReviewer implements Reviewer {

    /** 판정 필드는 일부러 없다. 판정은 지적 사항으로 코드가 계산한다. */
    static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "additionalProperties", false,
            "required", List.of("summary", "findings"),
            "properties", Map.of(
                    "summary", Map.of("type", "string"),
                    "findings", Map.of(
                            "type", "array",
                            "items", Map.of(
                                    "type", "object",
                                    "additionalProperties", false,
                                    "required", List.of("severity", "file", "line", "title", "message"),
                                    "properties", Map.of(
                                            "severity", Map.of("type", "string",
                                                    "enum", List.of("BLOCKER", "MAJOR", "MINOR")),
                                            "file", Map.of("type", "string"),
                                            "line", Map.of("type", List.of("integer", "null")),
                                            "title", Map.of("type", "string"),
                                            "message", Map.of("type", "string"))))));

    private final RestClient http;
    private final OpenAiProperties props;
    private final ReviewPrompt prompt;
    private final ObjectMapper mapper;

    public OpenAiReviewer(RestClient.Builder builder, OpenAiProperties props, ReviewPrompt prompt,
                          ObjectMapper mapper) {
        this.props = props;
        this.prompt = prompt;
        this.mapper = mapper;
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
    public String name() {
        return "openai:" + props.model();
    }

    @Override
    public LlmReview review(ReviewInput input) {
        JsonNode res = http.post().uri("/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "model", props.model(),
                        "instructions", prompt.system(),
                        "input", prompt.user(input),
                        "text", Map.of("format", Map.of(
                                "type", "json_schema",
                                "name", "pr_review",
                                "strict", true,
                                "schema", SCHEMA))))
                .retrieve()
                .body(JsonNode.class);
        return parse(outputText(res));
    }

    LlmReview parse(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            List<LlmReview.Item> items = new ArrayList<>();
            for (JsonNode f : root.path("findings")) {
                items.add(new LlmReview.Item(
                        f.path("severity").asText("MINOR"),
                        f.path("file").asText(""),
                        f.path("line").isInt() ? f.path("line").asInt() : null,
                        f.path("title").asText(""),
                        f.path("message").asText("")));
            }
            return new LlmReview(root.path("summary").asText(""), items);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("OpenAI 응답이 JSON 이 아닙니다: " + e.getOriginalMessage());
        }
    }

    /** output[] 중 message 의 output_text 를 이어 붙인다. */
    static String outputText(JsonNode res) {
        StringBuilder sb = new StringBuilder();
        if (res != null) {
            for (JsonNode item : res.path("output")) {
                if (!"message".equals(item.path("type").asText())) {
                    continue;
                }
                for (JsonNode c : item.path("content")) {
                    if ("output_text".equals(c.path("type").asText())) {
                        sb.append(c.path("text").asText());
                    } else if ("refusal".equals(c.path("type").asText())) {
                        throw new IllegalStateException("OpenAI 가 리뷰를 거절했습니다: " + c.path("refusal").asText());
                    }
                }
            }
        }
        if (sb.isEmpty()) {
            throw new IllegalStateException("OpenAI 응답에 텍스트가 없습니다");
        }
        return sb.toString().strip();
    }
}
