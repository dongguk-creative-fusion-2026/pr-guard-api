package com.prguard.events;

/**
 * 리뷰 파이프라인 단계. 화면의 그래프 노드와 1:1 로 대응한다.
 *
 * <pre>
 * COLLECT → CHECKOUT → INDEX_BASE ∥ INDEX_HEAD → METHOD_DIFF → HISTORY
 *        → CHECK_A ∥ CHECK_B ∥ CHECK_C ∥ CHECK_D ∥ LLM → VERDICT → PUBLISH → REVIEW
 *
 * 실행 검증 레인 (CHECKOUT 뒤에서 나란히 돌고 VERDICT 에 합류):
 *   EXEC_PREPARE → EXEC_POD_BASE → EXEC_TEST_BASE ┐
 *               → EXEC_POD_HEAD → EXEC_TEST_HEAD ┴→ EXEC_DIFF → VERDICT
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
    /** 실행 검증: 러너 이미지와 base · head 실행(Job) 만들기 */
    EXEC_PREPARE,
    /** base · head Pod(컨테이너) 스케줄 · 이미지 받기 · 기동 */
    EXEC_POD_BASE,
    EXEC_POD_HEAD,
    /** base · head 에서 clone → 빌드 → 테스트 */
    EXEC_TEST_BASE,
    EXEC_TEST_HEAD,
    /** base 에서 통과하던 테스트가 head 에서 실패하는지 비교 */
    EXEC_DIFF,
    VERDICT,
    PUBLISH,
    /** 리뷰 전체. DONE/FAILED 가 오면 스트림이 끝난다 */
    REVIEW
}
