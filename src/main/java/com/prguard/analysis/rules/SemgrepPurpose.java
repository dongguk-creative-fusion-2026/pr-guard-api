package com.prguard.analysis.rules;

import com.prguard.analysis.Category;
import java.util.Locale;
import java.util.Set;

/**
 * Semgrep 결과를 어디에 쓰는가. 규칙의 metadata.category 로 나누고, 레포가 직접 둔 규칙은 팀 규칙으로 본다.
 * 보안만 C 노드로 가고 나머지는 코드 품질로 D(위험도) 노드에 묶는다.
 */
public enum SemgrepPurpose {
    /** SQL Injection · XSS · 시크릿 · 위험한 API */
    SECURITY("보안", Category.SECURITY),
    /** 실수하기 쉬운 코드 (항상 참인 조건, == 문자열 비교, 닫지 않은 자원 …) */
    BUG("버그 패턴", Category.RISK),
    /** 모범 사례 · 유지보수성 · 성능. 판정을 막지 않도록 MINOR 까지만 */
    PRACTICE("모범 사례", Category.RISK),
    /** 레포의 .semgrep.yml · .semgrep/ 에 팀이 둔 규칙 */
    TEAM("팀 규칙", Category.RISK),
    /** deprecated · 제거된 API (수정 제안이 붙는 경우가 많다) */
    MIGRATION("마이그레이션", Category.RISK);

    private final String label;
    private final Category category;

    SemgrepPurpose(String label, Category category) {
        this.label = label;
        this.category = category;
    }

    public String label() {
        return label;
    }

    public Category category() {
        return category;
    }

    /**
     * @param teamIds 레포 규칙 파일에 있는 규칙 id. 로컬 규칙의 check_id 는 "경로.점.id" 꼴이라 끝으로 맞춘다
     */
    static SemgrepPurpose of(String checkId, String category, Set<String> teamIds) {
        for (String id : teamIds) {
            if (checkId.equals(id) || checkId.endsWith("." + id)) {
                return TEAM;
            }
        }
        return switch (category == null ? "" : category.toLowerCase(Locale.ROOT)) {
            case "security" -> SECURITY;
            case "correctness" -> BUG;
            case "migration", "compatibility", "deprecated" -> MIGRATION;
            default -> PRACTICE;
        };
    }
}
