package com.prguard.ast;

import java.util.List;
import java.util.Map;

/**
 * 바뀐 메서드 하나의 AST 단위 diff (GumTree).
 *
 * @param shape      변경 성격: COSMETIC(주석 · 공백 · 순서처럼 구조 변화 없음), RENAME(이름만), MOVE(위치만), LOGIC(로직)
 * @param counts     편집 종류별 수 (INSERT · DELETE · UPDATE · MOVE)
 * @param edits      편집 목록 (앞에서부터 일부)
 * @param signals    위험 패턴 (예외 대신 null, null 검사 삭제, 권한 어노테이션 삭제 …)
 * @param baseStart  baseSource 첫 줄의 파일 라인
 * @param headStart  headSource 첫 줄의 파일 라인
 */
public record MethodAstDiff(
        String methodId,
        String file,
        Shape shape,
        Map<Edit.Action, Integer> counts,
        List<Edit> edits,
        List<Signal> signals,
        String baseSource,
        String headSource,
        int baseStart,
        int headStart) {

    public enum Shape {
        COSMETIC, RENAME, MOVE, LOGIC
    }

    /**
     * @param type     AST 노드 종류 (MethodCallExpr, IfStmt …)
     * @param label    노드 값 (이름 · 리터럴). 수정이면 바뀌기 전 값
     * @param newLabel 수정이면 바뀐 값
     * @param baseLine base 쪽 라인 (삽입이면 0)
     * @param headLine head 쪽 라인 (삭제면 0)
     * @param text     노드 소스 (한 줄로 줄여서)
     */
    public record Edit(Action action, String type, String label, String newLabel, int baseLine, int headLine, String text) {

        public enum Action {
            INSERT, DELETE, UPDATE, MOVE
        }
    }

    /**
     * @param kind THROW_TO_NULL · EXCEPTION_REMOVED · NULL_CHECK_REMOVED · AUTH_REMOVED · EXCEPTION_SWALLOWED · CONDITION_CHANGED · CALL_TARGET_CHANGED
     * @param line head 라인 (head 에 없으면 base 라인, 음수)
     */
    public record Signal(String kind, int line, String detail) {
    }
}
