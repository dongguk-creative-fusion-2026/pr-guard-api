package com.prguard.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prguard.common.ApiException;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * GitHub 웹훅 (Content type: application/json, 이벤트: Pull requests).
 * 폴링 주기를 기다리지 않고 PR 이 열리거나 커밋이 올라오면 바로 리뷰를 시작한다. 폴링은 놓친 이벤트를 메우는 용도로 그대로 돈다.
 */
@RestController
public class WebhookController {

    private final WebhookProperties props;
    private final WebhookService service;
    private final ObjectMapper mapper;

    public WebhookController(WebhookProperties props, WebhookService service, ObjectMapper mapper) {
        this.props = props;
        this.service = service;
        this.mapper = mapper;
    }

    @PostMapping("/api/github/webhook")
    public ResponseEntity<WebhookResult> receive(
            @RequestHeader(value = "X-GitHub-Event", required = false) String event,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody(required = false) byte[] body) {
        if (!props.enabled()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "WEBHOOK_DISABLED", "웹훅이 꺼져 있습니다 (GITHUB_WEBHOOK_SECRET)");
        }
        byte[] raw = body == null ? new byte[0] : body;
        // 본문을 읽기 전에 서명부터: secret 을 모르는 요청은 아무것도 바꾸지 못한다
        if (!WebhookSignature.valid(props.secret(), raw, signature)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "WEBHOOK_SIGNATURE_INVALID", "웹훅 서명이 맞지 않습니다");
        }
        WebhookEvent parsed;
        try {
            parsed = raw.length == 0 ? null : mapper.readValue(raw, WebhookEvent.class);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WEBHOOK_INVALID", "웹훅 본문을 읽지 못했습니다");
        }
        return ResponseEntity.accepted().body(service.handle(event, parsed));
    }
}
