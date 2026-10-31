package com.prguard.events;

/**
 * 리뷰 파이프라인 단계. 화면의 그래프 노드와 1:1 로 대응한다.
 *
 * <pre>
 * COLLECT → CHECKOUT → INDEX_BASE ∥ INDEX_HEAD → METHOD_DIFF → IMPACT → HISTORY
 *        → CHECK_A ∥ CHECK_B ∥ CHECK_C ∥ CHECK_D ∥ LLM → GROUND → VERDICT → PUBLISH → REVIEW
 *
 * 실행 검증 레인 (CHECKOUT 뒤에서 나란히 돌고 GROUND 에 합류):
 *   EXEC_PREPARE → EXEC_POD_BASE → EXEC_TEST_BASE ┐
 *               → EXEC_POD_HEAD → EXEC_TEST_HEAD ┴→ EXEC_DIFF → GROUND
 *   METHOD_DIFF → EXEC_EVIDENCE → EXEC_TEST_BASE · EXEC_TEST_HEAD (증거 테스트를 함께 돌린다)
 *   METHOD_DIFF → EXEC_SELECT → EXEC_TEST_BASE · EXEC_TEST_HEAD (영향 있는 테스트만 돌린다)
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
    /** GitNexus 호출 그래프로 바뀐 함수를 부르는 함수를 여러 단계 따라간다 (언어 무관, 소스 없이도) */
    IMPACT,
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
    /** 바뀐 동작을 base 통과 · head 실패로 증명할 테스트 만들기 */
    EXEC_EVIDENCE,
    /** 바뀐 메서드와 기준 실행 기록으로 돌릴 테스트 고르기 (영향 테스트 선택) */
    EXEC_SELECT,
    /** base · head 에서 clone → 빌드 → 테스트 */
    EXEC_TEST_BASE,
    EXEC_TEST_HEAD,
    /** 바뀐 메서드에 작은 버그(변이)를 심어 테스트가 잡는지 (head 실행 안에서 PIT) */
    EXEC_MUTATION,
    /** base 에서 통과하던 테스트가 head 에서 실패하는지 비교 */
    EXEC_DIFF,
    /** 지적마다 인덱스 · 이력 · 실행 · AI 층의 근거를 맞춰 보고, 인덱스와 어긋나는 AI 지적을 뺀다 */
    GROUND,
    VERDICT,
    PUBLISH,
    /** 리뷰 전체. DONE/FAILED 가 오면 스트림이 끝난다 */
    REVIEW
}
