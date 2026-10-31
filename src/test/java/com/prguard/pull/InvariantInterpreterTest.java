package com.prguard.pull;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prguard.common.ApiException;
import com.prguard.review.OpenAiProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.web.client.RestClient;

class InvariantInterpreterTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void usesExistingApiAndPreservesConditionsWithoutGrantingExecution() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var request = new AtomicReference<String>();
        var auth = new AtomicReference<String>();
        String rules = """
                {"rules":[{"property":"재고 >= 0", "precondition":"주문 확정 시에만", "clarification":""},
                {"property":"취소 시 재고 복원", "precondition":"미출하 주문", "clarification":"복원할 창고를 확인하세요"}]}
                """;
        byte[] response = mapper.writeValueAsBytes(Map.of("status", "completed", "output", List.of(
                Map.of("type", "message", "content", List.of(Map.of("type", "output_text", "text", rules))))));
        server.createContext("/v1/responses", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
        var service = new InvariantInterpreter(RestClient.builder(), new OpenAiProperties(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-key", "review-model"), mapper);
        var result = service.interpret("주문 확정 시에만 재고는 0 이상이어야 하고 미출하 주문 취소 시 재고를 복원해야 한다");
        assertThat(result.candidates()).hasSize(2).allSatisfy(c -> {
            assertThat(c.status()).isEqualTo("NEEDS_REVIEW");
            assertThat(c.ruleId()).isNull();
            assertThat(c.adapter()).isNull();
        });
        assertThat(result.candidates().get(0).precondition()).isEqualTo("주문 확정 시에만");
        assertThat(auth.get()).isEqualTo("Bearer test-key");
        assertThat(mapper.readTree(request.get()).path("model").asText()).isEqualTo("review-model");
        assertThat(mapper.readTree(request.get()).at("/text/format/type").asText()).isEqualTo("json_schema");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void noKeyUsesExplicitTemplateFallback() {
        var service = new InvariantInterpreter(RestClient.builder(), new OpenAiProperties("https://example.test", null, "model"), mapper);
        assertThat(service.interpret("재고는 0 이상이다").engine()).isEqualTo("template:no-api-key");
    }

    @Test
    void rejectsMalformedResults() {
        var service = new InvariantInterpreter(RestClient.builder(), new OpenAiProperties("https://example.test", "key", "model"), mapper);
        for (String json : List.of("not json", "{}", "{\"rules\":[{\"property\":\"\",\"precondition\":\"\",\"clarification\":\"\"}]}")) {
            assertThatThrownBy(() -> service.parse("source", json)).isInstanceOf(ApiException.class);
        }
    }

    @Test
    void executableTemplateStillRejectsAmbiguousInterpretation() {
        var service = new InvariantInterpreter(RestClient.builder(), new OpenAiProperties("https://example.test", "key", "model"), mapper);
        var supported = service.parse("source", """
                {"rules":[{"property":"작성자 보존","precondition":"작성자 수정 성공","clarification":"","template":"POST_AUTHOR_PRESERVED"}]}
                """);
        assertThat(supported.candidates().get(0).status()).isEqualTo("CANDIDATE");
        var unclear = service.parse("source", """
                {"rules":[{"property":"작성자 보존","precondition":"관리자만","clarification":"관리자 예외 확인","template":"POST_AUTHOR_PRESERVED"}]}
                """);
        assertThat(unclear.candidates().get(0).status()).isEqualTo("NEEDS_REVIEW");
    }
}
