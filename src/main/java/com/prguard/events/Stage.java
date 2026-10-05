package com.prguard.events;

/**
 * 리뷰 파이프라인 단계. 화면의 그래프 노드와 1:1 로 대응한다.
 *
 * <pre>
 * COLLECT → CHECKOUT → INDEX_BASE ∥ INDEX_HEAD → METHOD_DIFF → HISTORY
 *        → CHECK_A ∥ CHECK_B ∥ CHECK_C ∥ CHECK_D ∥ LLM → VERDICT → PUBLISH → REVIEW
 * </pre>
 */
public enum Stage {
    /** PR 본문·커밋·변경 파일 수집 */
    COLLECT,
    /** 레포 clone, base/head 소스 준비 */
    CHECKOUT,
    INDEX_BASE,
    INDEX_HEAD,
    /** base↔head 메서드 비교와 호출부 */
    METHOD_DIFF,
    /** git 동시 변경 통계와 blame */
    HISTORY,
    CHECK_A,
    CHECK_B,
    CHECK_C,
    CHECK_D,
    LLM,
    VERDICT,
    PUBLISH,
    /** 리뷰 전체. DONE/FAILED 가 오면 스트림이 끝난다 */
    REVIEW
}
