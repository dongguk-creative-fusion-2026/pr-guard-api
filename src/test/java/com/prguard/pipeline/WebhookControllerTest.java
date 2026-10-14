package com.prguard.pipeline;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prguard.common.GlobalExceptionHandler;
import com.prguard.github.GitHubPull;
import com.prguard.project.ProjectRepository;
import com.prguard.pull.PullRequestRepository;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WebhookControllerTest {

    private static final String SECRET = "s3cret";

    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final PullRequestRepository pulls = mock(PullRequestRepository.class);
    private final PollService poller = mock(PollService.class);
    private final ReviewWorker worker = mock(ReviewWorker.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = mvc(SECRET);
        when(projects.findIdByRepo("octo", "shop")).thenReturn(Optional.of(7L));
    }

    private MockMvc mvc(String secret) {
        WebhookService service = new WebhookService(projects, pulls, poller, worker);
        return MockMvcBuilders
                .standaloneSetup(new WebhookController(new WebhookProperties(secret), service, new ObjectMapper()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static String pullRequest(String action, String sha) {
        return """
                {"action":"%s","number":3,
                 "pull_request":{"number":3,"title":"t","body":"b","state":"open","draft":false,
                   "html_url":"https://github.com/octo/shop/pull/3","user":{"login":"kim"},
                   "head":{"ref":"feat","sha":"%s"},"base":{"ref":"main","sha":"b1"}},
                 "repository":{"name":"shop","owner":{"login":"octo"}}}""".formatted(action, sha);
    }

    private ResultActions send(MockMvc mvc, String event, String body, String signature) throws Exception {
        var request = post("/api/github/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-GitHub-Event", event)
                .content(body);
        if (signature != null) {
            request.header("X-Hub-Signature-256", signature);
        }
        return mvc.perform(request);
    }

    private ResultActions signed(String event, String body) throws Exception {
        return send(mvc, event, body, WebhookSignature.header(SECRET, body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void newCommit_queuesReviewAndWakesWorker() throws Exception {
        when(poller.track(eq(7L), any())).thenReturn(true);

        signed("pull_request", pullRequest("synchronize", "abc1234"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.outcome").value("QUEUED"))
                .andExpect(jsonPath("$.prNumber").value(3));

        verify(poller).track(eq(7L), any(GitHubPull.class));
        verify(worker).wake();
    }

    @Test
    void sameCommit_onlyUpdatesPull_noWake() throws Exception {
        when(poller.track(eq(7L), any())).thenReturn(false);

        signed("pull_request", pullRequest("edited", "abc1234"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.outcome").value("UPDATED"));

        verify(worker, never()).wake();
    }

    @Test
    void closed_marksPullClosed() throws Exception {
        signed("pull_request", pullRequest("closed", "abc1234"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.outcome").value("CLOSED"));

        verify(pulls).close(7L, 3);
        verify(poller, never()).track(anyLong(), any());
    }

    @Test
    void unregisteredRepo_isIgnored() throws Exception {
        when(projects.findIdByRepo("octo", "shop")).thenReturn(Optional.empty());

        signed("pull_request", pullRequest("opened", "abc1234"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.outcome").value("IGNORED"));

        verifyNoInteractions(poller, worker);
    }

    @Test
    void ping_andOtherEvents_doNothing() throws Exception {
        signed("ping", "{\"zen\":\"Keep it logically awesome.\"}")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.outcome").value("PONG"));
        signed("push", "{\"ref\":\"refs/heads/main\"}")
                .andExpect(jsonPath("$.outcome").value("IGNORED"));
        signed("pull_request", pullRequest("labeled", "abc1234"))
                .andExpect(jsonPath("$.outcome").value("IGNORED"));

        verifyNoInteractions(poller, worker);
        verify(pulls, never()).close(anyLong(), anyInt());
    }

    @Test
    void wrongOrMissingSignature_isRejectedBeforeAnything() throws Exception {
        String body = pullRequest("opened", "abc1234");

        send(mvc, "pull_request", body, WebhookSignature.header("other", body.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("WEBHOOK_SIGNATURE_INVALID"));
        send(mvc, "pull_request", body, null)
                .andExpect(status().isUnauthorized());
        send(mvc, "pull_request", body, "sha256=zz")
                .andExpect(status().isUnauthorized());
        // 서명한 뒤 본문을 바꾼 요청
        String tampered = pullRequest("opened", "evil999");
        send(mvc, "pull_request", tampered, WebhookSignature.header(SECRET, body.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(projects, pulls, poller, worker);
    }

    @Test
    void noSecret_disablesWebhook() throws Exception {
        String body = pullRequest("opened", "abc1234");

        send(mvc(""), "pull_request", body, WebhookSignature.header(SECRET, body.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WEBHOOK_DISABLED"));

        verifyNoInteractions(projects, pulls, poller, worker);
    }

    @Test
    void signedButMalformedJson_isBadRequest() throws Exception {
        signed("pull_request", "{not json")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WEBHOOK_INVALID"));
    }
}
