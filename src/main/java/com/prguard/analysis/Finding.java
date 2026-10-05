package com.prguard.analysis;

/**
 * 지적 사항 하나.
 *
 * @param ruleId   검사 규칙 (예: UNRESOLVED_METHOD, LLM_REVIEW)
 * @param line     head 기준 라인. 모르면 null
 * @param evidence 근거 (호출 위치, 통계 등). 사람이 읽는 텍스트
 * @param anchor   같은 문제인지 판단하는 기준. 라인 번호처럼 쉽게 바뀌는 값은 넣지 않는다
 * @param source   TOOL(정적 분석) 또는 LLM
 */
public record Finding(
        String ruleId,
        Category category,
        Severity severity,
        String file,
        Integer line,
        String title,
        String message,
        String evidence,
        String anchor,
        String source) {

    public static final String TOOL = "TOOL";
    public static final String LLM = "LLM";

    /** base/head 비교와 코멘트 중복 방지에 쓰는 식별자. */
    public String fingerprint() {
        return ruleId + "|" + file + "|" + anchor;
    }

    public Finding withLine(Integer newLine) {
        return new Finding(ruleId, category, severity, file, newLine, title, message, evidence, anchor, source);
    }
}
