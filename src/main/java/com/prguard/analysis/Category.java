package com.prguard.analysis;

/** 파이프라인 검사 항목 (노션 "파이프라인 구성안"의 A~D). */
public enum Category {
    /** A. 의도 대비 검증 */
    INTENT("A", "의도"),
    /** B. 변경 영향 분석 */
    IMPACT("B", "영향"),
    /** C. 보안 */
    SECURITY("C", "보안"),
    /** D. 변경 위험도 */
    RISK("D", "위험도"),
    /** 기반 검사(컴파일 등)나 LLM 일반 리뷰 */
    GENERAL("-", "일반");

    private final String code;
    private final String label;

    Category(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }
}
