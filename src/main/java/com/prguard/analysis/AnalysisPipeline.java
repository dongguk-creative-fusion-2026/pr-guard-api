package com.prguard.analysis;

import com.prguard.history.BlameLine;
import com.prguard.history.CoChange;
import com.prguard.history.GitHistory;
import com.prguard.history.GitHistoryLoader;
import com.prguard.index.CallSite;
import com.prguard.index.ChangedMethod;
import com.prguard.index.JavaIndexer;
import com.prguard.index.MethodDiff;
import com.prguard.index.RepoIndex;
import com.prguard.review.LlmReview;
import com.prguard.review.ReviewInput;
import com.prguard.events.EventSink;
import com.prguard.events.Stage;
import com.prguard.review.Reviewer;
import com.prguard.review.StatsOnlyReviewer;
import com.prguard.workspace.Checkout;
import com.prguard.workspace.RepoWorkspace;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * PR 하나를 분석한다.
 * <ol>
 *   <li>base(merge-base)·head 소스 꺼내기</li>
 *   <li>양쪽 레포 인덱스 → 바뀐 메서드</li>
 *   <li>git 이력 통계 · 바뀐 라인의 blame</li>
 *   <li>결정적 검사기 실행 (base 에도 있던 문제는 제외)</li>
 *   <li>LLM 리뷰 (구조 정보를 함께 넘김)</li>
 *   <li>판정 계산</li>
 * </ol>
 * 앞 단계가 실패해도 가능한 데까지 진행하고, 실패 이유는 notes 에 남긴다.
 */
@Component
public class AnalysisPipeline {

    private static final Logger log = LoggerFactory.getLogger(AnalysisPipeline.class);
    private static final int MAX_CALLERS = 20;
    private static final int MAX_CHANGED_METHODS = 100;

    private final RepoWorkspace workspace;
    private final JavaIndexer indexer;
    private final GitHistoryLoader historyLoader;
    private final List<Analyzer> analyzers;
    private final Reviewer reviewer;
    private final VerdictPolicy verdictPolicy;
    private final AnalysisProperties props;
    private final Executor executor;

    public AnalysisPipeline(RepoWorkspace workspace, JavaIndexer indexer, GitHistoryLoader historyLoader,
                            List<Analyzer> analyzers, Reviewer reviewer, VerdictPolicy verdictPolicy,
                            AnalysisProperties props, @Qualifier("analysisExecutor") Executor executor) {
        this.workspace = workspace;
        this.indexer = indexer;
        this.historyLoader = historyLoader;
        this.analyzers = analyzers;
        this.reviewer = reviewer;
        this.verdictPolicy = verdictPolicy;
        this.props = props;
        this.executor = executor;
    }

    public AnalysisResult run(PullInfo pull, List<ChangedFile> files, long repoKb) {
        return run(pull, files, repoKb, EventSink.NONE);
    }

    public AnalysisResult run(PullInfo pull, List<ChangedFile> files, long repoKb, EventSink sink) {
        return run(pull, files, repoKb, sink, null);
    }

    /** @param majorThreshold 프로젝트의 판정 기준 (null 이면 기본값) */
    public AnalysisResult run(PullInfo pull, List<ChangedFile> files, long repoKb, EventSink sink, Integer majorThreshold) {
        long started = System.currentTimeMillis();
        AnalysisContext ctx = new AnalysisContext(pull, files);
        Checkout checkout = null;
        try {
            checkout = prepare(ctx, repoKb, sink);
            if (checkout != null) {
                index(ctx, checkout, sink);
                history(ctx, checkout, sink);
            } else {
                for (Stage stage : List.of(Stage.INDEX_BASE, Stage.INDEX_HEAD, Stage.METHOD_DIFF, Stage.HISTORY)) {
                    sink.skipped(stage, "소스가 없어 건너뜀");
                }
            }

            // 검사기(분류별)와 LLM 리뷰는 서로의 결과를 쓰지 않으므로 동시에 돌린다
            Map<Stage, List<Analyzer>> groups = new EnumMap<>(Stage.class);
            for (Analyzer analyzer : analyzers) {
                groups.computeIfAbsent(checkStage(analyzer.category()), k -> new ArrayList<>()).add(analyzer);
            }
            List<CompletableFuture<List<Finding>>> checks = new ArrayList<>();
            for (Stage stage : List.of(Stage.CHECK_A, Stage.CHECK_B, Stage.CHECK_C, Stage.CHECK_D)) {
                List<Analyzer> group = groups.getOrDefault(stage, List.of());
                if (group.isEmpty()) {
                    sink.skipped(stage, "검사 규칙 준비 중");
                    continue;
                }
                checks.add(CompletableFuture.supplyAsync(() -> runChecks(stage, group, ctx, sink), executor));
            }
            CompletableFuture<LlmOutcome> llm = CompletableFuture.supplyAsync(() -> runLlm(ctx, sink), executor);

            List<Finding> findings = new ArrayList<>();
            checks.forEach(f -> findings.addAll(f.join()));
            LlmOutcome outcome = llm.join();
            findings.addAll(outcome.findings());
            findings.sort(Comparator.comparing(Finding::severity).thenComparing(f -> f.file() == null ? "" : f.file()));

            Verdict verdict = verdictPolicy.decide(findings, majorThreshold);
            sink.done(Stage.VERDICT, verdict.label(), data(
                    "verdict", verdict.name(),
                    "blocker", count(findings, Severity.BLOCKER),
                    "major", count(findings, Severity.MAJOR),
                    "minor", count(findings, Severity.MINOR),
                    "total", findings.size()));
            return new AnalysisResult(findings, verdict, outcome.summary(), reviewer.name(),
                    summarize(ctx, System.currentTimeMillis() - started));
        } finally {
            if (checkout != null) {
                workspace.release(checkout);
            }
        }
    }

    /** 분류 → 그래프 노드. 분류가 없는 기반 검사(컴파일 등)는 영향 분석(B) 노드에 묶는다. */
    private static Stage checkStage(Category category) {
        return switch (category) {
            case INTENT -> Stage.CHECK_A;
            case SECURITY -> Stage.CHECK_C;
            case RISK -> Stage.CHECK_D;
            case IMPACT, GENERAL -> Stage.CHECK_B;
        };
    }

    private List<Finding> runChecks(Stage stage, List<Analyzer> group, AnalysisContext ctx, EventSink sink) {
        long t = System.currentTimeMillis();
        sink.running(stage, String.join(", ", group.stream().map(Analyzer::id).toList()));
        List<Finding> result = new ArrayList<>();
        for (Analyzer analyzer : group) {
            try {
                result.addAll(analyzer.analyze(ctx));
            } catch (RuntimeException e) {
                log.warn("검사기 {} 실패: {}", analyzer.id(), e.toString());
                ctx.note("검사기 " + analyzer.id() + " 실패: " + e.getMessage());
            }
        }
        sink.done(stage, "지적 " + result.size() + "건", data(
                "analyzers", group.stream().map(Analyzer::id).toList(),
                "findings", findingViews(result),
                "ms", System.currentTimeMillis() - t));
        return result;
    }

    private record LlmOutcome(String summary, List<Finding> findings) {
    }

    private LlmOutcome runLlm(AnalysisContext ctx, EventSink sink) {
        long t = System.currentTimeMillis();
        if (reviewer instanceof StatsOnlyReviewer) {
            sink.skipped(Stage.LLM, "OPENAI_API_KEY 미설정");
            return new LlmOutcome(reviewer.review(reviewInput(ctx)).summary(), List.of());
        }
        sink.running(Stage.LLM, reviewer.name());
        try {
            LlmReview llm = reviewer.review(reviewInput(ctx));
            List<Finding> findings = toFindings(llm, ctx);
            sink.done(Stage.LLM, "지적 " + findings.size() + "건", data(
                    "model", reviewer.name(),
                    "findings", findingViews(findings),
                    "dropped", llm.findings().size() - findings.size(),
                    "ms", System.currentTimeMillis() - t));
            return new LlmOutcome(llm.summary(), findings);
        } catch (RuntimeException e) {
            log.warn("LLM 리뷰 실패: {}", e.toString());
            ctx.note("LLM 리뷰 실패: " + e.getMessage());
            sink.failed(Stage.LLM, e.getMessage());
            return new LlmOutcome("LLM 리뷰를 하지 못했습니다. 정적 분석 결과만 표시합니다.", List.of());
        }
    }

    private Checkout prepare(AnalysisContext ctx, long repoKb, EventSink sink) {
        PullInfo pull = ctx.pull();
        long t = System.currentTimeMillis();
        sink.running(Stage.CHECKOUT, pull.repo().fullName());
        try {
            Checkout checkout = workspace.prepare(pull.repo(), repoKb, pull.number(), pull.baseRef(), pull.headSha());
            ctx.setCheckout(checkout);
            sink.done(Stage.CHECKOUT, shortSha(checkout.baseSha()) + " → " + shortSha(checkout.headSha()), data(
                    "baseSha", checkout.baseSha(),
                    "headSha", checkout.headSha(),
                    "repoKb", repoKb,
                    "ms", System.currentTimeMillis() - t));
            return checkout;
        } catch (RuntimeException e) {
            log.warn("{}#{} 소스 준비 실패: {}", pull.repo().fullName(), pull.number(), e.getMessage());
            ctx.note("소스를 받지 못해 diff 만 분석했습니다: " + e.getMessage());
            sink.failed(Stage.CHECKOUT, e.getMessage());
            return null;
        }
    }

    private void index(AnalysisContext ctx, Checkout checkout, EventSink sink) {
        try {
            // base 와 head 는 서로 독립이라 동시에 파싱한다
            CompletableFuture<RepoIndex> base = CompletableFuture.supplyAsync(
                    () -> indexSide(Stage.INDEX_BASE, checkout.baseDir(), sink), executor);
            CompletableFuture<RepoIndex> head = CompletableFuture.supplyAsync(
                    () -> indexSide(Stage.INDEX_HEAD, checkout.headDir(), sink), executor);
            RepoIndex baseIndex = base.join();
            RepoIndex headIndex = head.join();

            long t = System.currentTimeMillis();
            sink.running(Stage.METHOD_DIFF, "메서드 비교");
            List<ChangedMethod> changed = MethodDiff.compute(baseIndex, headIndex);
            ctx.setIndexes(baseIndex, headIndex, changed);
            sink.done(Stage.METHOD_DIFF, "바뀐 메서드 " + changed.size() + "개", data(
                    "added", changed.stream().filter(m -> m.kind() == ChangedMethod.Kind.ADDED).count(),
                    "removed", changed.stream().filter(m -> m.kind() == ChangedMethod.Kind.REMOVED).count(),
                    "modified", changed.stream().filter(m -> m.kind() == ChangedMethod.Kind.MODIFIED).count(),
                    "signatureChanged", changed.stream().filter(ChangedMethod::signatureChanged).count(),
                    "callers", changed.stream().filter(m -> m.id() != null)
                            .mapToInt(m -> headIndex.callersOf(m.id()).size()).sum(),
                    "methods", impactViews(ctx, changed),
                    "ms", System.currentTimeMillis() - t));
            if (headIndex.parsedFiles() == 0) {
                ctx.note("Java 소스가 없어 레포 인덱스를 만들지 않았습니다");
            }
            if (!headIndex.failedFiles().isEmpty()) {
                ctx.note("파싱하지 못한 파일 " + headIndex.failedFiles().size() + "개: "
                        + String.join(", ", headIndex.failedFiles().stream().limit(5).toList()));
            }
        } catch (RuntimeException e) {
            log.warn("인덱스 실패: {}", e.toString());
            ctx.note("레포 인덱스 실패: " + e.getMessage());
            sink.failed(Stage.METHOD_DIFF, e.getMessage());
        }
    }

    private RepoIndex indexSide(Stage stage, Path dir, EventSink sink) {
        long t = System.currentTimeMillis();
        sink.running(stage, "Java 소스 파싱");
        try {
            RepoIndex index = indexer.index(dir);
            long methods = index.methods().values().stream().filter(m -> !m.implicit()).count();
            sink.done(stage, "Java " + index.parsedFiles() + "개 · 메서드 " + methods + "개", data(
                    "files", index.parsedFiles(),
                    "types", index.types().size(),
                    "methods", methods,
                    "calls", index.calls().size(),
                    "failedFiles", index.failedFiles().size(),
                    "ms", System.currentTimeMillis() - t));
            return index;
        } catch (RuntimeException e) {
            sink.failed(stage, e.getMessage());
            throw e;
        }
    }

    private void history(AnalysisContext ctx, Checkout checkout, EventSink sink) {
        long t = System.currentTimeMillis();
        sink.running(Stage.HISTORY, "git log · blame");
        try {
            GitHistory history = historyLoader.load(checkout, props.maxCommits());
            List<BlameLine> blame = new ArrayList<>();
            for (ChangedFile file : ctx.files()) {
                List<Integer> removed = List.copyOf(file.patch().removedLines());
                if (removed.isEmpty() || "added".equals(file.status())) {
                    continue;
                }
                String path = file.previousFilename() != null ? file.previousFilename() : file.filename();
                try {
                    blame.addAll(historyLoader.blame(checkout, path, removed));
                } catch (RuntimeException e) {
                    ctx.note("blame 실패 " + path + ": " + e.getMessage());
                }
            }
            ctx.setHistory(history, blame);

            List<CoChange> coChanges = new ArrayList<>();
            for (ChangedFile f : ctx.files()) {
                coChanges.addAll(history.coChanges(f.filename(), props.minCoSupport(), props.minConfidence(), 3));
            }
            coChanges.sort(Comparator.comparingDouble(CoChange::confidence).reversed());
            Set<String> seen = new LinkedHashSet<>();
            List<Map<String, Object>> origins = new ArrayList<>();
            for (BlameLine b : blame) {
                if (seen.add(b.sha()) && origins.size() < 5) {
                    origins.add(data("sha", b.sha(), "summary", b.summary(), "file", b.file()));
                }
            }
            sink.done(Stage.HISTORY, "커밋 " + history.commits().size() + "개 분석", data(
                    "commits", history.commits().size(),
                    "coChanges", coChanges.stream().limit(8).toList(),
                    "blameCommits", origins,
                    "ms", System.currentTimeMillis() - t));
        } catch (RuntimeException e) {
            log.warn("이력 수집 실패: {}", e.toString());
            ctx.note("git 이력 수집 실패: " + e.getMessage());
            sink.failed(Stage.HISTORY, e.getMessage());
        }
    }

    /** 영향 그래프용: 바뀐 메서드(테스트 아닌 것 먼저)와 그 호출부. */
    private List<Map<String, Object>> impactViews(AnalysisContext ctx, List<ChangedMethod> changed) {
        return changed.stream()
                .sorted(Comparator.comparing(ChangedMethod::test))
                .limit(12)
                .map(m -> data(
                        "id", m.id() != null ? m.id() : m.baseId(),
                        "baseId", m.baseId(),
                        "kind", m.kind().name(),
                        "signatureChanged", m.signatureChanged(),
                        "test", m.test(),
                        "callers", callerViews(m.id() == null ? List.of() : ctx.headIndex().callersOf(m.id())),
                        "stale", callerViews(staleCalls(ctx, m))))
                .toList();
    }

    private static List<Map<String, Object>> callerViews(List<CallSite> calls) {
        return calls.stream().limit(8)
                .map(c -> data("id", c.callerId(), "file", c.file(), "line", c.line()))
                .toList();
    }

    private static List<Map<String, Object>> findingViews(List<Finding> findings) {
        return findings.stream().limit(20)
                .map(f -> data("severity", f.severity().name(), "ruleId", f.ruleId(), "title", f.title(),
                        "file", f.file(), "line", f.line()))
                .toList();
    }

    private static long count(List<Finding> findings, Severity severity) {
        return findings.stream().filter(f -> f.severity() == severity).count();
    }

    /** null 값을 허용하는 순서 있는 맵 (Map.of 는 null 을 거부한다). */
    static Map<String, Object> data(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static String shortSha(String sha) {
        return sha == null ? "-" : sha.substring(0, Math.min(7, sha.length()));
    }

    private ReviewInput reviewInput(AnalysisContext ctx) {
        PullInfo p = ctx.pull();
        return new ReviewInput(p.repo().fullName(), p.number(), p.title(), p.body(), p.author(), p.baseRef(),
                p.headRef(), p.headSha(), p.commitMessages(), structureText(ctx),
                ctx.files().stream()
                        .map(f -> new ReviewInput.ChangedFile(f.filename(), f.status(), f.additions(), f.deletions(),
                                f.patchText()))
                        .toList());
    }

    /** LLM 이 diff 밖을 추측하지 않도록 정적 분석으로 확인한 사실만 넘긴다. */
    private String structureText(AnalysisContext ctx) {
        if (!ctx.indexed()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ChangedMethod m : ctx.changedMethods()) {
            if (sb.length() > props.maxStructureChars()) {
                sb.append("... (생략)\n");
                break;
            }
            sb.append("- ").append(kindLabel(m)).append(' ').append(m.id() != null ? m.id() : m.baseId());
            if (m.signatureChanged()) {
                sb.append(" (이전: ").append(m.baseId()).append(')');
            }
            List<CallSite> callers = m.id() != null ? ctx.headIndex().callersOf(m.id()) : List.of();
            List<CallSite> stale = staleCalls(ctx, m);
            if (m.kind() == ChangedMethod.Kind.ADDED) {
                sb.append(" — 호출하는 곳 ").append(callers.size()).append("곳");
            } else if (!callers.isEmpty()) {
                sb.append(" — 호출부: ").append(String.join(", ",
                        callers.stream().limit(5).map(c -> c.callerId() + " (" + c.file() + ":" + c.line() + ")").toList()));
            }
            if (!stale.isEmpty()) {
                sb.append(" — 옛 시그니처로 부르는 곳: ").append(String.join(", ",
                        stale.stream().limit(5).map(c -> c.file() + ":" + c.line()).toList()));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String kindLabel(ChangedMethod m) {
        return switch (m.kind()) {
            case ADDED -> "[추가]";
            case REMOVED -> "[삭제]";
            case MODIFIED -> m.signatureChanged() ? "[시그니처 변경]" : "[수정]";
        };
    }

    /** 지워졌거나 시그니처가 바뀐 메서드를 head 에서 아직 이름으로 부르는데 head 메서드로 연결되지 않은 곳. */
    private static List<CallSite> staleCalls(AnalysisContext ctx, ChangedMethod m) {
        if (m.kind() == ChangedMethod.Kind.ADDED || m.kind() == ChangedMethod.Kind.MODIFIED && !m.signatureChanged()) {
            return List.of();
        }
        RepoIndex head = ctx.headIndex();
        return head.callsByName(m.typeFqn(), m.name()).stream()
                .filter(c -> c.argCount() >= 0 && c.calleeIds().stream()
                        .map(head::method)
                        .flatMap(Optional::stream)
                        .noneMatch(h -> h.acceptsArgs(c.argCount())))
                .toList();
    }

    /** LLM 지적을 검증한다: 바뀐 파일이 아니면 버리고, 라인이 diff 밖이면 라인을 지운다. */
    private static List<Finding> toFindings(LlmReview llm, AnalysisContext ctx) {
        List<Finding> result = new ArrayList<>();
        for (LlmReview.Item item : llm.findings()) {
            ChangedFile file = ctx.file(item.file()).orElse(null);
            if (file == null) {
                continue;
            }
            Severity severity;
            try {
                severity = Severity.valueOf(item.severity().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                severity = Severity.MINOR;
            }
            Integer line = item.line() != null && file.patch().commentableLines().contains(item.line())
                    ? item.line() : null;
            result.add(new Finding("LLM_REVIEW", Category.GENERAL, severity, file.filename(), line, item.title(),
                    item.message(), null, item.title().strip().toLowerCase(Locale.ROOT), Finding.LLM));
        }
        return result;
    }

    private ContextSummary summarize(AnalysisContext ctx, long elapsedMs) {
        List<ContextSummary.ChangedMethodView> methods = ctx.changedMethods().stream()
                .limit(MAX_CHANGED_METHODS)
                .map(m -> new ContextSummary.ChangedMethodView(m.kind().name(), m.id(), m.baseId(), m.file(), m.line(),
                        m.signatureChanged(), m.bodyChanged(), m.annotationsChanged(), m.test(),
                        callers(m.id() == null ? List.of() : ctx.headIndex().callersOf(m.id())),
                        callers(staleCalls(ctx, m))))
                .toList();
        List<ContextSummary.FileHistory> history = new ArrayList<>();
        for (ChangedFile f : ctx.files()) {
            List<CoChange> coChanges = ctx.history().coChanges(f.filename(), props.minCoSupport(),
                    props.minConfidence(), 10);
            history.add(new ContextSummary.FileHistory(f.filename(), ctx.history().fileCommitCount(f.filename()),
                    coChanges));
        }
        return new ContextSummary(
                ctx.checkout().map(Checkout::baseSha).orElse(null),
                ctx.pull().headSha(),
                stats(ctx.baseIndex()), stats(ctx.headIndex()),
                ctx.files().stream()
                        .map(f -> new ContextSummary.FileChange(f.filename(), f.status(), f.additions(), f.deletions(),
                                f.previousFilename()))
                        .toList(),
                methods, history, ctx.history().commits().size(),
                ctx.blame().stream().limit(50).toList(),
                List.copyOf(ctx.notes()), elapsedMs);
    }

    private static List<ContextSummary.CallerView> callers(List<CallSite> calls) {
        return calls.stream().limit(MAX_CALLERS)
                .map(c -> new ContextSummary.CallerView(c.callerId(), c.file(), c.line()))
                .toList();
    }

    private static ContextSummary.IndexStats stats(RepoIndex index) {
        Set<CallSite.Resolution> unresolved = Set.of(CallSite.Resolution.MISSING);
        return new ContextSummary.IndexStats(index.parsedFiles(), index.types().size(),
                (int) index.methods().values().stream().filter(m -> !m.implicit()).count(), index.calls().size(),
                (int) index.calls().stream().filter(c -> unresolved.contains(c.resolution())).count(),
                index.failedFiles().size());
    }
}
