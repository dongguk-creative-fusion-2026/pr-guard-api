package com.prguard.pull;

import static org.assertj.core.api.Assertions.*;
import com.prguard.common.ApiException;
import org.junit.jupiter.api.Test;

class InvariantExecutionServiceTest {
    @Test
    void onlyKnownVersionAndAdapterCanExecute() {
        assertThatCode(() -> InvariantExecutionService.validate(InvariantRuleCompiler.compile(
                "게시글 수정 전후 작성자 ID는 같아야 한다"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> InvariantExecutionService.validate(null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> InvariantExecutionService.validate(new InvariantRuleCompiler.Candidate(
                "NEEDS_REVIEW", "source", null, 1, "stock >= 0", "condition", null, "review")))
                .isInstanceOf(ApiException.class);
    }
}
