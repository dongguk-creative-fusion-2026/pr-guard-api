package com.prguard.review;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** OpenAI Responses API 로 리뷰한다. */
public class OpenAiReviewer implements Reviewer {

    private final RestClient http;
    private final OpenAiProperties props;
    private final ReviewPrompt prompt;

    public OpenAiReviewer(RestClient.Builder builder, OpenAiProperties props, ReviewPrompt prompt) {
        this.props = props;
        this.prompt = prompt;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofMinutes(3));
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
    public String review(ReviewInput input) {
        JsonNode res = http.post().uri("/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "model", props.model(),
                        "instructions", prompt.system(),
                        "input", prompt.user(input)))
                .retrieve()
                .body(JsonNode.class);
        return outputText(res);
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
