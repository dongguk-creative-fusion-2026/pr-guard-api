package com.prguard.analysis;

/**
 * 지적 사항 하나.
 *
 * @param ruleId    검사 규칙 (예: UNRESOLVED_METHOD, LLM_REVIEW)
 * @param line      head 기준 라인. 모르면 null
 * @param evidence  근거 (호출 위치, 통계 등). 사람이 읽는 텍스트
 * @param anchor    같은 문제인지 판단하는 기준. 라인 번호처럼 쉽게 바뀌는 값은 넣지 않는다
 * @param source    TOOL(정적 분석) 또는 LLM
 * @param grounding 층별 근거. {@link FindingGrounder} 를 거치기 전이면 null (LLM 지적은 종류 · 이름만 있음)
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
        String source,
        Grounding grounding) {

    public static final String TOOL = "TOOL";
    public static final String LLM = "LLM";

    public Finding(String ruleId, Category category, Severity severity, String file, Integer line, String title,
                   String message, String evidence, String anchor, String source) {
        this(ruleId, category, severity, file, line, title, message, evidence, anchor, source, null);
    }

    /** base/head 비교와 코멘트 중복 방지에 쓰는 식별자. */
    public String fingerprint() {
        return ruleId + "|" + file + "|" + anchor;
    }

    /** 인덱스와 어긋나 판정 · 라인 코멘트에서 빼는 지적. */
    public boolean refuted() {
        return grounding != null && grounding.refuted();
    }

    public Finding withLine(Integer newLine) {
        return new Finding(ruleId, category, severity, file, newLine, title, message, evidence, anchor, source, grounding);
    }

    public Finding withGrounding(Grounding newGrounding) {
        return new Finding(ruleId, category, severity, file, line, title, message, evidence, anchor, source, newGrounding);
    }
}
