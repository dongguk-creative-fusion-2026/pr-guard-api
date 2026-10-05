package com.prguard.pipeline;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 프론트엔드 주소. PR 코멘트에 "분석 과정 보기" 링크를 넣을 때 쓴다.
 *
 * @param webUrl 예: https://pr-guard-web.vercel.app (비우면 링크를 넣지 않는다)
 */
@ConfigurationProperties("prguard.links")
public record WebLinks(String webUrl) {

    public String reviewUrl(long projectId, long reviewId) {
        if (webUrl == null || webUrl.isBlank()) {
            return null;
        }
        String base = webUrl.endsWith("/") ? webUrl.substring(0, webUrl.length() - 1) : webUrl;
        return base + "/projects/" + projectId + "/reviews/" + reviewId;
    }
}
