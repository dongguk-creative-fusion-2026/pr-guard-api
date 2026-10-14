package com.prguard.pipeline;

/**
 * 웹훅 하나를 처리한 결과 (GitHub 의 Recent Deliveries 응답에 그대로 보인다).
 *
 * @param outcome QUEUED 새 리뷰 작업, UPDATED PR 정보만 갱신, CLOSED 닫힘, IGNORED 처리하지 않음, PONG 연결 확인
 */
public record WebhookResult(String outcome, Long projectId, Integer prNumber, String reason) {

    static WebhookResult ignored(String reason) {
        return new WebhookResult("IGNORED", null, null, reason);
    }
}
