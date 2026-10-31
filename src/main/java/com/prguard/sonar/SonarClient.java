package com.prguard.sonar;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
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

    private final RestClient http;
    private final SonarProperties props;

    public SonarClient(RestClient.Builder builder, SonarProperties props) {
        this.props = props;
        RestClient.Builder b = builder.clone()
                .baseUrl(props.url())
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {
                    throw new SonarException(req.getMethod() + " " + req.getURI().getPath() + " -> " + res.getStatusCode().value());
                });
        if (props.token() != null && !props.token().isBlank()) {
            b.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.token());
        }
        this.http = b.build();
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
        return http.get()
                .uri("/api/issues/search?componentKeys={k}&pullRequest={pr}&resolved=false&ps={n}", key, pr, PAGE_SIZE)
                .retrieve().body(JsonNode.class);
    }

    public JsonNode measures(String key, int pr, List<String> metrics) {
        return http.get()
                .uri("/api/measures/component?component={k}&pullRequest={pr}&metricKeys={m}", key, pr,
                        String.join(",", metrics))
                .retrieve().body(JsonNode.class);
    }

    public JsonNode qualityGate(String key, int pr) {
        JsonNode body = http.get()
                .uri("/api/qualitygates/project_status?projectKey={k}&pullRequest={pr}", key, pr)
                .retrieve().body(JsonNode.class);
        return body == null ? null : body.path("projectStatus");
    }
}
