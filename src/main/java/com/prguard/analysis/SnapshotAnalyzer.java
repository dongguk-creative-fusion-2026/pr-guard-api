package com.prguard.analysis;

import com.prguard.index.RepoIndex;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * base 와 head 를 똑같이 검사해서 head 에만 있는 지적만 남기는 검사기 (SonarQube "Clean as You Code").
 * 레포에 원래 있던 문제는 이번 PR 의 책임이 아니므로 보고하지 않는다.
 */
public abstract class SnapshotAnalyzer implements Analyzer {

    public enum Side {
        BASE,
        HEAD
    }

    /** 한쪽 시점을 검사한다. 같은 문제면 base 와 head 에서 같은 {@link Finding#anchor()} 를 만들어야 한다. */
    protected abstract List<Finding> analyzeSnapshot(Side side, RepoIndex index, AnalysisContext ctx);

    @Override
    public final List<Finding> analyze(AnalysisContext ctx) {
        if (!ctx.indexed()) {
            return List.of();
        }
        List<Finding> head = analyzeSnapshot(Side.HEAD, ctx.headIndex(), ctx);
        if (head.isEmpty()) {
            return head;
        }
        Set<String> existing = analyzeSnapshot(Side.BASE, ctx.baseIndex(), ctx).stream()
                .map(Finding::fingerprint)
                .collect(Collectors.toSet());
        return head.stream().filter(f -> !existing.contains(f.fingerprint())).toList();
    }
}
