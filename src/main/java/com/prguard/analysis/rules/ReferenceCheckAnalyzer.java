package com.prguard.analysis.rules;

import com.prguard.analysis.AnalysisContext;
import com.prguard.analysis.Category;
import com.prguard.analysis.Finding;
import com.prguard.analysis.Severity;
import com.prguard.analysis.SnapshotAnalyzer;
import com.prguard.index.CallSite;
import com.prguard.index.MethodInfo;
import com.prguard.index.RepoIndex;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 컴파일 없이 할 수 있는 참조 검사 (레포 인덱스 기반).
 * <ul>
 *   <li>UNRESOLVED_METHOD: 레포 안 타입에 없는 메서드를 호출 (지워졌거나 이름을 지어냄)</li>
 *   <li>ARITY_MISMATCH: 메서드는 있는데 인자 수가 어떤 선언과도 맞지 않음 (시그니처가 바뀌었는데 호출부를 안 고침)</li>
 * </ul>
 * 상속 계층에 라이브러리 타입이 섞여 있으면 소스에 없는 메서드가 있을 수 있어서 판단하지 않는다.
 */
@Component
public class ReferenceCheckAnalyzer extends SnapshotAnalyzer {

    @Override
    public String id() {
        return "reference-check";
    }

    @Override
    public Category category() {
        return Category.IMPACT;
    }

    @Override
    protected List<Finding> analyzeSnapshot(Side side, RepoIndex index, AnalysisContext ctx) {
        List<Finding> result = new ArrayList<>();
        for (CallSite call : index.calls()) {
            String target = call.calleeName().equals("<init>")
                    ? shortType(call.scopeType()) + " 생성자"
                    : shortType(call.scopeType()) + "." + call.calleeName() + "()";
            String caller = callerAnchor(call.callerId());
            if (call.resolution() == CallSite.Resolution.MISSING) {
                result.add(new Finding("UNRESOLVED_METHOD", Category.IMPACT, Severity.BLOCKER,
                        call.file(), call.line(),
                        "존재하지 않는 메서드 호출: " + target,
                        target + "가 레포 안 어디에도 선언되어 있지 않습니다. 지워졌거나 잘못된 이름이면 컴파일되지 않습니다.",
                        "호출 위치 " + call.callerId() + " · 대상 타입 " + call.scopeType(),
                        caller + "->" + call.scopeType() + "#" + call.calleeName(), Finding.TOOL));
                continue;
            }
            if (call.resolution() != CallSite.Resolution.RESOLVED || call.argCount() < 0) {
                continue;
            }
            List<MethodInfo> declared = call.calleeIds().stream()
                    .map(index::method)
                    .flatMap(Optional::stream)
                    .toList();
            if (!declared.isEmpty() && declared.stream().noneMatch(m -> m.acceptsArgs(call.argCount()))) {
                String signatures = String.join(", ", declared.stream().map(ReferenceCheckAnalyzer::signature).toList());
                result.add(new Finding("ARITY_MISMATCH", Category.IMPACT, Severity.BLOCKER,
                        call.file(), call.line(),
                        "인자 수가 맞지 않는 호출: " + target,
                        target + "를 인자 " + call.argCount() + "개로 호출하지만 선언은 " + signatures
                                + " 입니다. 시그니처가 바뀌었는데 이 호출부를 고치지 않았을 수 있습니다.",
                        "호출 위치 " + call.callerId(),
                        caller + "->" + call.scopeType() + "#" + call.calleeName() + "/" + call.argCount(),
                        Finding.TOOL));
            }
        }
        return result;
    }

    private static String signature(MethodInfo m) {
        return display(m.name()) + "(" + String.join(", ", m.paramTypes()) + ")";
    }

    private static String display(String name) {
        return name.equals("<init>") ? "생성자" : name + "()";
    }

    private static String shortType(String fqn) {
        if (fqn == null) {
            return "?";
        }
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }

    /** 호출하는 쪽 메서드의 시그니처가 바뀌어도 같은 문제로 보도록 파라미터는 뺀다. */
    private static String callerAnchor(String callerId) {
        int paren = callerId.indexOf('(');
        return paren < 0 ? callerId : callerId.substring(0, paren);
    }
}
