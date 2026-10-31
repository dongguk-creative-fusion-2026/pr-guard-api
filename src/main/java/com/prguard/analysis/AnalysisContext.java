package com.prguard.analysis;

import com.prguard.ast.MethodAstDiff;
import com.prguard.history.BlameLine;
import com.prguard.history.GitHistory;
import com.prguard.index.ChangedMethod;
import com.prguard.index.MethodInfo;
import com.prguard.index.RepoIndex;
import com.prguard.rulemap.RuleMap;
import com.prguard.workspace.Checkout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 검사기가 쓰는 재료 전부. 단계가 실패하면 해당 재료는 비어 있고 {@link #notes()} 에 이유가 남는다.
 *
 * <p>재료: PR 텍스트(의도) · 바뀐 파일과 patch · base/head 소스 인덱스 · 바뀐 메서드 · git 이력 · blame ·
 * 규칙 지도(base 와 최신 기본 브랜치)
 */
public final class AnalysisContext {

    private final PullInfo pull;
    private final List<ChangedFile> files;
    private Checkout checkout;
    private RepoIndex baseIndex = RepoIndex.empty();
    private RepoIndex headIndex = RepoIndex.empty();
    private List<ChangedMethod> changedMethods = List.of();
    private GitHistory history = GitHistory.empty();
    private List<BlameLine> blame = List.of();
    private Map<String, MethodAstDiff> astDiffs = Map.of();
    private GuardDogReport guarddog;
    private RuleMap baseRules = RuleMap.EMPTY;
    private RuleMap latestRules;
    private GraphImpact graphImpact = GraphImpact.empty();
    // 검사기들이 동시에 남길 수 있다
    private final List<String> notes = Collections.synchronizedList(new ArrayList<>());
    // 여러 검사기가 나눠 쓰는 결과 (Semgrep 한 번 실행 → 보안 · 코드 품질)
    private final Map<String, CompletableFuture<Object>> shared = new ConcurrentHashMap<>();

    public AnalysisContext(PullInfo pull, List<ChangedFile> files) {
        this.pull = pull;
        this.files = List.copyOf(files);
    }

    public PullInfo pull() {
        return pull;
    }

    public List<ChangedFile> files() {
        return files;
    }

    public Optional<ChangedFile> file(String filename) {
        return files.stream().filter(f -> f.filename().equals(filename)).findFirst();
    }

    public Optional<Checkout> checkout() {
        return Optional.ofNullable(checkout);
    }

    public boolean indexed() {
        return checkout != null && headIndex.parsedFiles() > 0;
    }

    public RepoIndex baseIndex() {
        return baseIndex;
    }

    public RepoIndex headIndex() {
        return headIndex;
    }

    public List<ChangedMethod> changedMethods() {
        return changedMethods;
    }

    public GitHistory history() {
        return history;
    }

    public List<BlameLine> blame() {
        return blame;
    }

    /** 바뀐 메서드(head id) → AST 단위 diff. 본문 · 어노테이션이 바뀐 메서드만 */
    public Map<String, MethodAstDiff> astDiffs() {
        return astDiffs;
    }

    /** PR 이 출발한 커밋(base)의 규칙 지도. 없으면 빈 지도 */
    public RuleMap baseRules() {
        return baseRules;
    }

    /** 지금 기본 브랜치 끝 커밋의 규칙 지도. base 와 같은 커밋이거나 못 만들었으면 비어 있다 */
    public Optional<RuleMap> latestRules() {
        return Optional.ofNullable(latestRules);
    }

    /** GitNexus 호출 그래프로 따라간 변경 영향. 그래프가 없으면 비어 있다 */
    public GraphImpact graphImpact() {
        return graphImpact;
    }

    public List<String> notes() {
        return notes;
    }

    public GuardDogReport guarddog() { return guarddog; }

    void setGuarddog(GuardDogReport report) { this.guarddog = report; }

    /**
     * key 마다 한 번만 계산한다. 다른 검사기가 계산 중이면 끝날 때까지 기다렸다가 같은 결과를 받는다.
     * 계산이 실패하면 기다리던 쪽도 같은 예외를 받는다.
     */
    @SuppressWarnings("unchecked")
    public <T> T once(String key, Supplier<T> compute) {
        CompletableFuture<Object> mine = new CompletableFuture<>();
        CompletableFuture<Object> running = shared.putIfAbsent(key, mine);
        if (running != null) {
            try {
                return (T) running.join();
            } catch (CompletionException e) {
                throw e.getCause() instanceof RuntimeException r ? r : e;
            }
        }
        try {
            T value = compute.get();
            mine.complete(value);
            return value;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        }
    }

    /** patch 의 추가 라인을 감싸는 head 메서드들. A 에서 hunk 를 메서드에 연결할 때 쓴다. */
    public Set<MethodInfo> methodsTouchedBy(ChangedFile file) {
        Set<MethodInfo> result = new LinkedHashSet<>();
        for (int line : file.patch().addedLines()) {
            headIndex.methodAt(file.filename(), line).ifPresent(result::add);
        }
        return result;
    }

    void setCheckout(Checkout checkout) {
        this.checkout = checkout;
    }

    void setIndexes(RepoIndex base, RepoIndex head, List<ChangedMethod> changed) {
        this.baseIndex = base;
        this.headIndex = head;
        this.changedMethods = List.copyOf(changed);
    }

    void setAstDiffs(Map<String, MethodAstDiff> astDiffs) {
        this.astDiffs = Map.copyOf(astDiffs);
    }

    void setRuleMaps(RuleMap base, RuleMap latest) {
        this.baseRules = base == null ? RuleMap.EMPTY : base;
        this.latestRules = latest;
    }

    void setGraphImpact(GraphImpact graphImpact) {
        this.graphImpact = graphImpact;
    }

    void setHistory(GitHistory history, List<BlameLine> blame) {
        this.history = history;
        this.blame = List.copyOf(blame);
    }

    void note(String message) {
        notes.add(message);
    }
}
