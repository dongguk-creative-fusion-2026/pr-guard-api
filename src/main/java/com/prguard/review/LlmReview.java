package com.prguard.review;

import java.util.List;

/**
 * LLM 리뷰 결과. 판정은 들어 있지 않다 (코드가 계산한다).
 *
 * @param summary PR 변경 요약
 */
public record LlmReview(String summary, List<Item> findings) {

    /**
     * @param line    head 기준 라인. 특정할 수 없으면 null
     * @param kind    지적 종류 ({@link #KINDS}). 인덱스로 검증할 방법을 고르는 데 쓴다
     * @param symbols 지적이 근거로 삼는 코드 이름. MISSING_SYMBOL · MISSING_CHECK 면 "없다"고 말하는 그 이름
     */
    public record Item(String severity, String file, Integer line, String title, String message, String kind,
                       List<String> symbols) {
    }

    public static final List<String> KINDS = List.of("MISSING_SYMBOL", "BROKEN_CALLER", "MISSING_CHECK",
            "BEHAVIOR_CHANGE", "SECURITY", "DATA", "INTENT", "OTHER");
}
