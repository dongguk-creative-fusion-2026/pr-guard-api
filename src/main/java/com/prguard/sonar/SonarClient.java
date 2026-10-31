package com.prguard.sonar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/** SonarCloud Web API. 토큰이 없으면 만들기만 하고 부르지 않는다 ({@link SonarService} 가 먼저 확인). */
@Component
public class SonarClient {

    private static final int PAGE_SIZE = 500;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient http;
    /** 토큰 없이 부르는 클라이언트. 우리 프로젝트는 public 이라 이슈 · 지표 · Gate 는 누구나 읽을 수 있다 */
    private final RestClient anonymous;
    private final SonarProperties props;

    public SonarClient(RestClient.Builder builder, SonarProperties props) {
        this.props = props;
        RestClient.Builder b = builder.clone()
                .baseUrl(props.url())
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {
                    throw new SonarException(req.getMethod() + " " + req.getURI().getPath() + " -> " + res.getStatusCode().value()
                            + reason(res.getBody().readAllBytes()));
                });
        this.anonymous = b.clone().build();
        if (props.token() != null && !props.token().isBlank()) {
            b.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.token());
        }
        this.http = b.build();
    }

    /**
     * 결과 읽기. Scoped Organization Token 은 분석 · 프로젝트 만들기용이라 조회 API 를 거절할 수 있다 (issues/search -> 400).
     * 토큰으로 실패하면 토큰 없이 다시 읽는다 (public 프로젝트).
     */
    private JsonNode read(Function<RestClient, JsonNode> call) {
        try {
            return call.apply(http);
        } catch (SonarException withToken) {
            try {
                return call.apply(anonymous);
            } catch (SonarException withoutToken) {
                throw new SonarException("토큰: " + withToken.getMessage() + " / 토큰 없이: " + withoutToken.getMessage());
            }
        }
    }

    /** SonarCloud 오류 본문 {"errors":[{"msg":…}]} 의 사유. 없으면 본문 앞부분 */
    static String reason(byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8).strip();
        if (text.isEmpty()) {
            return "";
        }
        try {
            List<String> msgs = new ArrayList<>();
            for (JsonNode e : MAPPER.readTree(text).path("errors")) {
                msgs.add(e.path("msg").asText());
            }
            if (!msgs.isEmpty()) {
                return " (" + String.join("; ", msgs) + ")";
            }
        } catch (IOException e) {
            // JSON 이 아니면 본문을 그대로 줄여 남긴다
        }
        return " (" + (text.length() > 200 ? text.substring(0, 200) + "…" : text) + ")";
    }

    /** 조직에 프로젝트가 없으면 public 으로 만든다 (무료 플랜은 public 프로젝트만 무제한) */
    public void ensureProject(String key, String name) {
        JsonNode found = http.get()
                .uri("/api/projects/search?organization={o}&projects={k}", props.organization(), key)
                .retrieve().body(JsonNode.class);
        if (found != null && found.path("components").size() > 0) {
            return;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("organization", props.organization());
        form.add("project", key);
        form.add("name", name);
        form.add("visibility", "public");
        http.post().uri("/api/projects/create")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve().toBodilessEntity();
    }

    /** 분석 처리 작업. status: PENDING · IN_PROGRESS · SUCCESS · FAILED · CANCELED */
    public JsonNode task(String id) {
        JsonNode body = http.get().uri("/api/ce/task?id={id}", id).retrieve().body(JsonNode.class);
        return body == null ? null : body.path("task");
    }

    /** PR 분석에서 열린 이슈 (한 페이지) */
    public JsonNode issues(String key, int pr) {
        return read(c -> c.get()
                .uri("/api/issues/search?organization={o}&componentKeys={k}&pullRequest={pr}&resolved=false&ps={n}",
                        props.organization(), key, pr, PAGE_SIZE)
                .retrieve().body(JsonNode.class));
    }

    public JsonNode measures(String key, int pr, List<String> metrics) {
        return read(c -> c.get()
                .uri("/api/measures/component?component={k}&pullRequest={pr}&metricKeys={m}", key, pr,
                        String.join(",", metrics))
                .retrieve().body(JsonNode.class));
    }

    public JsonNode qualityGate(String key, int pr) {
        JsonNode body = read(c -> c.get()
                .uri("/api/qualitygates/project_status?projectKey={k}&pullRequest={pr}", key, pr)
                .retrieve().body(JsonNode.class));
        return body == null ? null : body.path("projectStatus");
    }
}
