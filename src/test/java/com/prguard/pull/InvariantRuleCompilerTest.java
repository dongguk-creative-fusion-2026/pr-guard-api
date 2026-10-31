package com.prguard.pull;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class InvariantRuleCompilerTest {
    @Test
    void compilesSupportedSentences() {
        for (String text : new String[] {"게시글 수정 전후 작성자 ID는 같아야 한다",
                "게시글을 수정해도 작성자 ID는 바뀌면 안 된다.", "게시글 수정 시 작성자 아이디가 유지되어야 한다"}) {
            var rule = InvariantRuleCompiler.compile(text);
            assertThat(rule.status()).isEqualTo("CANDIDATE");
            assertThat(rule.ruleId()).isEqualTo("post-author-preserved-on-update");
        }
    }

    @Test
    void rejectsAmbiguityNegationAndCompoundRules() {
        for (String text : new String[] {"", "재고는 0 이상이다", "게시글 수정 전후 작성자 ID는 같아야 한다는 규칙은 아니다",
                "관리자일 때만 게시글 수정 전후 작성자 ID는 같아야 한다", "게시글 수정 전후 작성자 ID는 같아야 한다\n재고는 0 이상이다"}) {
            assertThat(InvariantRuleCompiler.compile(text).status()).isEqualTo("UNSUPPORTED");
        }
    }
}
