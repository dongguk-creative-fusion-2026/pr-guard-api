package com.prguard.pull;

import java.util.regex.Pattern;

/** 제한된 자연어를 검증된 테스트 템플릿으로 변환한다. 사용자 문장을 코드로 실행하지 않는다. */
public final class InvariantRuleCompiler {
    static final String RULE_ID = "post-author-preserved-on-update";
    static final String ADAPTER = "post-update-v1";
    static final int VERSION = 1;
    private InvariantRuleCompiler() {}

    private static final Pattern AUTHOR = Pattern.compile(
            "게시글(?:을|의)?\\s*수정(?:해도|할\\s*때|\\s*시|\\s*전후|\\s*후)(?:에도)?\\s*[,，]?\\s*"
            + "작성자\\s*(?:ID|아이디)(?:는|가|도)?\\s*(?:수정\\s*전후\\s*)?"
            + "(?:같아야\\s*한다|동일해야\\s*한다|유지되어야\\s*한다|유지돼야\\s*한다|바뀌면\\s*안\\s*된다|변경되면\\s*안\\s*된다)[.!。]?",
            Pattern.CASE_INSENSITIVE);

    public record Candidate(String status, String source, String ruleId, int version,
                            String property, String precondition, String adapter, String message) {}

    public static Candidate compile(String source) {
        if (AUTHOR.matcher(source.strip()).matches()) {
            return new Candidate("CANDIDATE", source, RULE_ID, VERSION,
                    "author_before == author_after", "작성자의 유효한 수정 서비스 호출이 성공한 경우",
                    ADAPTER, "작성자 ID 보존 템플릿 후보입니다. 조건을 확인하고 테스트를 실행하세요.");
        }
        return new Candidate("UNSUPPORTED", source, null, 1, null, null, null,
                "현재는 게시글 수정 전후 작성자 ID 유지 규칙만 해석합니다. 조건을 덧붙이거나 여러 규칙을 입력한 문장은 자동 변환하지 않습니다.");
    }
}
