package com.prguard.analysis;

import java.util.List;

/**
 * @param summary  LLM 이 쓴 변경 요약 (LLM 이 꺼져 있거나 실패하면 안내 문구)
 * @param reviewer LLM 리뷰어 이름
 */
public record AnalysisResult(
        List<Finding> findings,
        Verdict verdict,
        String summary,
        String reviewer,
        ContextSummary context) {
}
