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
 * AST diff(GumTree)로 찾은 동작 위험 패턴. 실행하지 않고 코드 구조만으로 판단한다.
 * <ul>
 *   <li>AST_THROW_TO_NULL: 예외를 던지던 곳이 null 을 돌려줌 (호출하는 쪽에서 NPE)</li>
 *   <li>AST_EXCEPTION_REMOVED: 예외를 던지던 경로가 사라짐</li>
 *   <li>AST_NULL_CHECK_REMOVED: null 검사 삭제</li>
 *   <li>AST_EXCEPTION_SWALLOWED: 빈 catch 추가</li>
 * </ul>
 * 변경 diff 에서만 나오므로 base 에 있던 문제는 애초에 잡히지 않는다.
 */
@Component
public class AstBehaviorAnalyzer implements Analyzer {

    @Override
    public String id() {
        return "ast-behavior";
    }

    @Override
    public Category category() {
        return Category.IMPACT;
    }

    @Override
    public List<Finding> analyze(AnalysisContext ctx) {
        List<Finding> result = new ArrayList<>();
        for (MethodAstDiff m : ctx.astDiffs().values()) {
            String name = shortName(m.methodId());
            int callers = ctx.headIndex().callersOf(m.methodId()).size();
            for (Signal s : m.signals()) {
                Integer line = s.line() > 0 ? s.line() : null;
                switch (s.kind()) {
                    case "THROW_TO_NULL" -> result.add(new Finding("AST_THROW_TO_NULL", Category.IMPACT, Severity.MAJOR, m.file(), line,
                            "예외 대신 null 을 돌려주도록 바뀜: " + name,
                            name + " 는 값이 없을 때 예외를 던졌지만 이제 null 을 돌려줍니다. "
                                    + (callers > 0 ? "호출하는 곳 " + callers + "곳이 null 을 처리하는지 확인해 주세요."
                                            : "이 메서드를 쓰는 쪽이 null 을 처리하는지 확인해 주세요."),
                            s.detail(), "throw-to-null:" + m.methodId(), Finding.TOOL));
                    case "EXCEPTION_REMOVED" -> result.add(new Finding("AST_EXCEPTION_REMOVED", Category.IMPACT, Severity.MINOR, m.file(),
                            line, "예외를 던지던 경로가 사라짐: " + name,
                            name + " 가 더 이상 예외를 던지지 않습니다. 실패를 예외로 알던 호출하는 쪽의 처리가 그대로 맞는지 확인해 주세요.",
                            s.detail(), "exception-removed:" + m.methodId(), Finding.TOOL));
                    case "NULL_CHECK_REMOVED" -> result.add(new Finding("AST_NULL_CHECK_REMOVED", Category.IMPACT, Severity.MAJOR, m.file(),
                            line, "null 검사 삭제: " + name,
                            name + " 에서 null 검사가 사라졌습니다. 이 값이 null 로 들어오지 않는다는 근거가 있는지 확인해 주세요.",
                            s.detail(), "null-check:" + m.methodId() + ":" + s.detail(), Finding.TOOL));
                    case "EXCEPTION_SWALLOWED" -> result.add(new Finding("AST_EXCEPTION_SWALLOWED", Category.IMPACT, Severity.MAJOR, m.file(),
                            line, "예외를 삼키는 빈 catch: " + name,
                            "빈 catch 는 실패를 숨깁니다. 로그를 남기거나 호출하는 쪽에 실패를 알려 주세요.",
                            s.detail(), "swallow:" + m.methodId(), Finding.TOOL));
                    default -> {
                    }
                }
            }
        }
        return result;
    }

    static String shortName(String methodId) {
        int hash = methodId.indexOf('#');
        String type = methodId.substring(0, hash);
        String method = methodId.substring(hash + 1);
        int paren = method.indexOf('(');
        return type.substring(type.lastIndexOf('.') + 1) + "." + (paren < 0 ? method : method.substring(0, paren)) + "()";
    }
}
