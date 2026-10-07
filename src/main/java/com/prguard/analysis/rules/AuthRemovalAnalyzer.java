package com.prguard.analysis.rules;

import com.prguard.analysis.AnalysisContext;
import com.prguard.analysis.Analyzer;
import com.prguard.analysis.Category;
import com.prguard.analysis.Finding;
import com.prguard.analysis.Severity;
import com.prguard.ast.MethodAstDiff;
import com.prguard.ast.MethodAstDiff.Signal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * C. 보안: 메서드에서 권한 어노테이션(@PreAuthorize · @Secured · @RolesAllowed …)이 사라짐 (AST diff).
 * AI 가 쓴 코드에서 가장 자주 빠지는 것이 권한 검사라, 이번 PR 이 지운 것은 BLOCKER 로 본다.
 */
@Component
public class AuthRemovalAnalyzer implements Analyzer {

    @Override
    public String id() {
        return "auth-removal";
    }

    @Override
    public Category category() {
        return Category.SECURITY;
    }

    @Override
    public List<Finding> analyze(AnalysisContext ctx) {
        List<Finding> result = new ArrayList<>();
        for (MethodAstDiff m : ctx.astDiffs().values()) {
            for (Signal s : m.signals()) {
                if (!"AUTH_REMOVED".equals(s.kind())) {
                    continue;
                }
                String name = AstBehaviorAnalyzer.shortName(m.methodId());
                result.add(new Finding("AST_AUTH_REMOVED", Category.SECURITY, Severity.BLOCKER, m.file(), null,
                        "권한 검사가 사라짐: " + name,
                        name + " 에 있던 권한 어노테이션이 이번 PR 에서 지워졌습니다. 누구나 이 기능을 호출할 수 있게 된 것은 아닌지 확인해 주세요.",
                        s.detail() + " (base " + (-s.line()) + "줄)", "auth-removed:" + m.methodId(), Finding.TOOL));
            }
        }
        return result;
    }
}
