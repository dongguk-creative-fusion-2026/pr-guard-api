package com.prguard.review;

import java.util.List;

/**
 * LLM 리뷰 결과. 판정은 들어 있지 않다 (코드가 계산한다).
 *
 * @param summary PR 변경 요약
 */
public record LlmReview(String summary, List<Item> findings) {

    /** @param line head 기준 라인. 특정할 수 없으면 null */
    public record Item(String severity, String file, Integer line, String title, String message) {
    }
}
