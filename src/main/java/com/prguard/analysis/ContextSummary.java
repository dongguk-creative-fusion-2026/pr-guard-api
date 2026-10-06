package com.prguard.analysis;

import com.prguard.history.BlameLine;
import com.prguard.history.CoChange;
import java.util.List;

/**
 * 분석에 쓴 재료를 요약해 DB 에 남기는 값. 화면과 디버깅용이고, A~D 검사를 만들 때 어떤 근거가 나오는지 보는 데 쓴다.
 */
public record ContextSummary(
        String baseSha,
        String headSha,
        IndexStats baseIndex,
        IndexStats headIndex,
        List<FileChange> files,
        List<ChangedMethodView> changedMethods,
        List<FileHistory> history,
        int historyCommits,
        List<BlameLine> blame,
        List<String> notes,
        long elapsedMs) {

    /**
     * PR 에서 바뀐 파일 (화면에서 레포 그래프 위에 영향 범위를 그릴 때 쓴다).
     *
     * @param status       added, modified, removed, renamed …
     * @param previousPath 이름이 바뀐 파일의 예전 경로
     */
    public record FileChange(String path, String status, int additions, int deletions, String previousPath) {
    }

    public record IndexStats(int files, int types, int methods, int calls, int unresolvedCalls, int failedFiles) {
    }

    /**
     * @param callers    head 에서 이 메서드를 부르는 곳
     * @param staleCalls 지워졌거나 시그니처가 바뀐 메서드를 head 에서 아직 이름으로 부르는 곳
     */
    public record ChangedMethodView(String kind, String id, String baseId, String file, int line,
                                    boolean signatureChanged, boolean bodyChanged, boolean annotationsChanged,
                                    boolean test, List<CallerView> callers, List<CallerView> staleCalls) {
    }

    public record CallerView(String callerId, String file, int line) {
    }

    /** @param commits 이 파일이 바뀐 과거 커밋 수 */
    public record FileHistory(String file, int commits, List<CoChange> coChanges) {
    }
}
