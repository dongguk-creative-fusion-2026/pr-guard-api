package com.prguard.analysis;

import com.prguard.ast.AstDiffer;
import com.prguard.ast.MethodAstDiff;
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
import com.prguard.evidence.EvidencePlan;
import com.prguard.evidence.EvidenceService;
import com.prguard.execution.TestSelection;
import com.prguard.execution.TestSelectionService;
import com.prguard.events.Stage;
import com.prguard.execution.TestRunService;
import com.prguard.graph.CallGraph;
import com.prguard.graph.CallGraphService;
import com.prguard.review.Reviewer;
import com.prguard.review.StatsOnlyReviewer;
import com.prguard.sonar.SonarService;
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
 *   <li>GitNexus 호출 그래프로 변경 영향 따라가기 (바뀐 함수를 부르는 함수 → 그 함수를 부르는 함수 …)</li>
 *   <li>git 이력 통계 · 바뀐 라인의 blame</li>
 *   <li>결정적 검사기 실행 (base 에도 있던 문제는 제외)</li>
 *   <li>LLM 리뷰 (구조 정보를 함께 넘김)</li>
 *   <li>근거 확인: 지적마다 인덱스 · 이력 · 실행 · AI 층을 맞춰 보고, 인덱스와 어긋나는 AI 지적은 판정에서 뺀다</li>
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
    private final TestRunService testRuns;
    private final EvidenceService evidence;
    private final FindingGrounder grounder;
    private final TestSelectionService selector;
    private final SonarService sonar;
    private final CallGraphService callGraphs;

    public AnalysisPipeline(RepoWorkspace workspace, JavaIndexer indexer, GitHistoryLoader historyLoader,
                            List<Analyzer> analyzers, Reviewer reviewer, VerdictPolicy verdictPolicy,
                            AnalysisProperties props, @Qualifier("analysisExecutor") Executor executor,
                            TestRunService testRuns, EvidenceService evidence, FindingGrounder grounder,
                            TestSelectionService selector, SonarService sonar, CallGraphService callGraphs) {
        this.workspace = workspace;
        this.indexer = indexer;
        this.historyLoader = historyLoader;
        this.analyzers = analyzers;
        this.reviewer = reviewer;
        this.verdictPolicy = verdictPolicy;
        this.props = props;
        this.executor = executor;
        this.testRuns = testRuns;
        this.evidence = evidence;
        this.grounder = grounder;
        this.selector = selector;
        this.sonar = sonar;
        this.callGraphs = callGraphs;
    }

    public AnalysisResult run(PullInfo pull, List<ChangedFile> files, long repoKb) {
        return run(pull, files, repoKb, EventSink.NONE);
    }

    public AnalysisResult run(PullInfo pull, List<ChangedFile> files, long repoKb, EventSink sink) {
        return run(pull, files, repoKb, sink, null);
    }

    /** @param majorThreshold 프로젝트의 판정 기준 (null 이면 기본값) */
    public AnalysisResult run(PullInfo pull, List<ChangedFile> files, long repoKb, EventSink sink, Integer majorThreshold) {
        return run(pull, files, repoKb, sink, majorThreshold, null);
    }

    /** @param reviewId 실행 검증 기록을 묶을 리뷰 (평가 등 리뷰 없이 돌릴 때는 null) */
    public AnalysisResult run(PullInfo pull, List<ChangedFile> files, long repoKb, EventSink sink, Integer majorThreshold,
                              Long reviewId) {
        return run(pull, files, repoKb, sink, majorThreshold, reviewId, true);
    }

    /**
     * @param execEnabled 프로젝트가 실행 검증을 켰나. 서버에 실행 환경이 있고 이것도 켜져 있어야 테스트를 돌린다
     */
    public AnalysisResult run(PullInfo pull, List<ChangedFile> files, long repoKb, EventSink sink, Integer majorThreshold,
                              Long reviewId, boolean execEnabled) {
        return run(pull, files, repoKb, sink, majorThreshold, reviewId, execEnabled, null);
    }

    /** @param projectId GitNexus 호출 그래프를 읽을 프로젝트 (없으면 변경 영향 단계를 건너뛴다) */
    public AnalysisResult run(PullInfo pull, List<ChangedFile> files, long repoKb, EventSink sink, Integer majorThreshold,
                              Long reviewId, boolean execEnabled, Long projectId) {
        long started = System.currentTimeMillis();
        boolean exec = testRuns.enabled() && execEnabled;
        AnalysisContext ctx = new AnalysisContext(pull, files);
        Checkout checkout = null;
        CompletableFuture<List<Finding>> execution = CompletableFuture.completedFuture(List.of());
        // 증거 테스트와 바뀐 메서드. 러너는 먼저 뜨고, 인덱스가 끝나면 채운다
        CompletableFuture<EvidencePlan> plan = new CompletableFuture<>();
        // 돌릴 테스트. 러너는 먼저 뜨고, 인덱스가 끝나면 고른다
        CompletableFuture<TestSelection> selection = new CompletableFuture<>();
        CompletableFuture<Void> planning = CompletableFuture.completedFuture(null);
        // SonarCloud: 실행 검증이 돌면 head 러너가 테스트 뒤에 (커버리지와 함께), 아니면 GitHub Actions 가 소스만 스캔한다
        boolean sonarPlanned = false;
        try {
            checkout = prepare(ctx, repoKb, sink);
            // 러너를 띄우기 전에 프로젝트와 설정을 준비한다 (러너는 빌드 전에 설정을 받아 간다)
            sonarPlanned = sonar.register(pull, sink, exec && checkout != null);
            // 실행 검증(base · head 테스트)은 오래 걸리고 다른 분석 결과를 쓰지 않으므로 소스가 준비되면 바로 시작한다
            if (!testRuns.enabled()) {
                testRuns.skipAll(sink, "실행 환경 준비 중");
            } else if (!execEnabled) {
                testRuns.skipAll(sink, "프로젝트 설정에서 실행 검증 꺼짐");
            } else if (checkout == null) {
                testRuns.skipAll(sink, "소스가 없어 건너뜀");
            } else {
                Checkout source = checkout;
                execution = CompletableFuture.supplyAsync(() -> testRuns.verify(reviewId, pull, source, sink, plan, selection),
                        executor);
            }
            if (checkout != null) {
                index(ctx, checkout, sink);
                if (exec) {
                    selection.complete(selector.select(reviewId, ctx, sink));
                    // LLM 으로 테스트를 쓰는 동안 이력 · 검사는 계속 진행한다
                    planning = CompletableFuture.runAsync(() -> plan.complete(evidence.plan(ctx, sink)), executor)
                            .exceptionally(e -> {
                                plan.complete(EvidencePlan.EMPTY);
                                return null;
                            });
                }
                history(ctx, checkout, sink);
            } else {
                for (Stage stage : List.of(Stage.INDEX_BASE, Stage.INDEX_HEAD, Stage.METHOD_DIFF, Stage.HISTORY)) {
                    sink.skipped(stage, "소스가 없어 건너뜀");
                }
            }
            // GitNexus 그래프는 미리 만들어 둔 것이라 소스를 받지 못해도 patch 만으로 따라갈 수 있다
            graphImpact(ctx, projectId, sink);

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
            findings.addAll(execution.join());
            if (sonarPlanned) {
                findings.addAll(sonar.collect(pull, ctx, sink));
            }
            planning.join();
            findings.sort(Comparator.comparing(Finding::severity).thenComparing(f -> f.file() == null ? "" : f.file()));
            List<Finding> grounded = ground(findings, ctx, sink, exec);

            Verdict verdict = verdictPolicy.decide(grounded, majorThreshold, ctx.guarddog());
            List<Finding> counted = grounded.stream().filter(f -> !f.refuted()).toList();
            sink.done(Stage.VERDICT, verdict.label(), data(
                    "verdict", verdict.name(),
                    "blocker", count(counted, Severity.BLOCKER),
                    "major", count(counted, Severity.MAJOR),
                    "minor", count(counted, Severity.MINOR),
                    "refuted", grounded.size() - counted.size(),
                    "total", counted.size(),
                    "guarddog", ctx.guarddog(),
                    "reason", ctx.guarddog() != null && ctx.guarddog().requiresAttention()
                            ? "의존성 검증 미완료: 실패 원인을 확인하고 다시 검사하세요." : null));
            return new AnalysisResult(grounded, verdict, outcome.summary(), reviewer.name(),
                    summarize(ctx, System.currentTimeMillis() - started));
        } finally {
            plan.complete(EvidencePlan.EMPTY);
            selection.complete(TestSelection.off("선택 없음"));
            if (sonarPlanned) {
                sonar.forget(pull.headSha());
            }
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
        Map<String, Object> details = data(
                "analyzers", group.stream().map(Analyzer::id).toList(),
                "findings", findingViews(result),
                "ms", System.currentTimeMillis() - t);
        boolean incomplete = stage == Stage.CHECK_C && ctx.guarddog() != null && ctx.guarddog().requiresAttention();
        if (stage == Stage.CHECK_C) details.put("guarddog", ctx.guarddog());
        sink.done(stage, (incomplete ? "의존성 검증 미완료 · " : "") + "지적 " + result.size() + "건", details);
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

    /** 근거 확인. 실패해도 지적은 그대로 두고 판정을 계속한다. */
    private List<Finding> ground(List<Finding> findings, AnalysisContext ctx, EventSink sink, boolean exec) {
        long t = System.currentTimeMillis();
        sink.running(Stage.GROUND, "지적 " + findings.size() + "건");
        try {
            List<Finding> grounded = grounder.ground(findings, ctx, exec);
            Map<Grounding.Status, Long> byStatus = new EnumMap<>(Grounding.Status.class);
            grounded.forEach(f -> byStatus.merge(f.grounding().status(), 1L, Long::sum));
            long confirmed = byStatus.getOrDefault(Grounding.Status.CONFIRMED, 0L);
            long aiOnly = byStatus.getOrDefault(Grounding.Status.AI_ONLY, 0L);
            long refuted = byStatus.getOrDefault(Grounding.Status.REFUTED, 0L);
            sink.done(Stage.GROUND, refuted > 0 ? "인덱스와 어긋난 지적 " + refuted + "건 뺌"
                    : "확인 " + confirmed + " · AI 만 " + aiOnly, data(
                    "confirmed", confirmed,
                    "aiOnly", aiOnly,
                    "refuted", refuted,
                    "findings", grounded.stream().limit(20)
                            .map(f -> data("severity", f.severity().name(), "title", f.title(), "file", f.file(),
                                    "line", f.line(), "category", f.category().name(), "kind", kindOf(f),
                                    "status", f.grounding().status().name(),
                                    "layers", f.grounding().layers()))
                            .toList(),
                    "ms", System.currentTimeMillis() - t));
            return grounded;
        } catch (RuntimeException e) {
            log.warn("근거 확인 실패: {}", e.toString());
            ctx.note("근거 확인 실패: " + e.getMessage());
            sink.failed(Stage.GROUND, e.getMessage());
            return findings;
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
            // 무엇이 어떻게 바뀌었는지: AST 단위 편집과 변경 성격 · 위험 패턴
            Map<String, MethodAstDiff> ast = Map.of();
            try {
                ast = AstDiffer.diff(checkout.baseDir(), checkout.headDir(), baseIndex, headIndex, changed);
                ctx.setAstDiffs(ast);
            } catch (RuntimeException e) {
                log.warn("AST diff 실패: {}", e.toString());
                ctx.note("AST diff 실패: " + e.getMessage());
            }
            sink.done(Stage.METHOD_DIFF, "바뀐 메서드 " + changed.size() + "개", data(
                    "added", changed.stream().filter(m -> m.kind() == ChangedMethod.Kind.ADDED).count(),
                    "removed", changed.stream().filter(m -> m.kind() == ChangedMethod.Kind.REMOVED).count(),
                    "modified", changed.stream().filter(m -> m.kind() == ChangedMethod.Kind.MODIFIED).count(),
                    "signatureChanged", changed.stream().filter(ChangedMethod::signatureChanged).count(),
                    "callers", changed.stream().filter(m -> m.id() != null)
                            .mapToInt(m -> headIndex.callersOf(m.id()).size()).sum(),
                    "methods", impactViews(ctx, changed),
                    "logic", ast.values().stream().filter(a -> a.shape() == MethodAstDiff.Shape.LOGIC).count(),
                    "ast", ast.values().stream()
                            .sorted(Comparator.comparing((MethodAstDiff a) -> a.signals().isEmpty())
                                    .thenComparing(a -> a.shape() != MethodAstDiff.Shape.LOGIC))
                            .limit(12).toList(),
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

    private void graphImpact(AnalysisContext ctx, Long projectId, EventSink sink) {
        if (projectId == null) {
            sink.skipped(Stage.IMPACT, "프로젝트 없음");
            return;
        }
        long t = System.currentTimeMillis();
        sink.running(Stage.IMPACT, "GitNexus 호출 그래프");
        try {
            Optional<CallGraph> graph = callGraphs.load(projectId);
            if (graph.isEmpty()) {
                sink.skipped(Stage.IMPACT, "GitNexus 그래프가 아직 없음");
                return;
            }
            String baseSha = ctx.checkout().map(Checkout::baseSha).orElse(null);
            GraphImpact impact = GraphImpactTracer.trace(graph.get(), ctx.files(), baseSha, ctx.headIndex(),
                    ctx.changedMethods());
            ctx.setGraphImpact(impact);
            if (!impact.baseMatches()) {
                ctx.note("GitNexus 그래프는 " + shortSha(impact.commitSha()) + " 기준이라 이 PR 의 base 와 줄 위치가 조금 다를 수 있습니다");
            }
            if (!impact.complete()) {
                ctx.note("GitNexus 그래프가 레포 일부만 담고 있어 빠진 호출이 있을 수 있습니다 (그래프를 다시 만들면 전체를 담습니다)");
            }
            sink.done(Stage.IMPACT, "바뀐 함수 " + impact.changed().size() + "개 → 영향 " + impact.impacted().size()
                    + "개 (" + impact.maxDepth() + "단계)", data(
                    "commitSha", impact.commitSha(),
                    "baseMatches", impact.baseMatches(),
                    "complete", impact.complete(),
                    "changed", impact.changed().stream().limit(30).toList(),
                    "impacted", impact.impacted().stream().limit(60).toList(),
                    "impactedFiles", impact.impactedFiles().stream().limit(50).toList(),
                    "unmapped", impact.unmapped().stream().limit(30).toList(),
                    "confirmed", impact.confirmedCount(),
                    "truncated", impact.truncated(),
                    "ms", System.currentTimeMillis() - t));
        } catch (RuntimeException e) {
            log.warn("변경 영향 추적 실패: {}", e.toString());
            ctx.note("GitNexus 변경 영향 추적 실패: " + e.getMessage());
            sink.failed(Stage.IMPACT, e.getMessage());
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
                        "file", f.file(), "line", f.line(), "category", f.category().name(), "kind", kindOf(f),
                        "evidence", clip(f.evidence())))
                .toList();
    }

    /** 단계 기록에 싣는 증거는 짧게 */
    private static String clip(String evidence) {
        return evidence == null || evidence.length() <= 300 ? evidence : evidence.substring(0, 300) + "…";
    }

    private static String kindOf(Finding f) {
        return f.grounding() == null ? null : f.grounding().kind();
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
        StringBuilder sb = new StringBuilder();
        for (ChangedMethod m : ctx.indexed() ? ctx.changedMethods() : List.<ChangedMethod>of()) {
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
        graphImpactText(ctx.graphImpact(), sb);
        return sb.toString();
    }

    /** GitNexus 로 따라간 영향: 바뀐 함수에서 부르는 쪽으로 몇 단계까지. JavaIndexer 가 못 보는 2단계 이상 · Java 밖 호출 */
    private void graphImpactText(GraphImpact impact, StringBuilder sb) {
        if (impact.impacted().isEmpty() || sb.length() > props.maxStructureChars()) {
            return;
        }
        sb.append("\n[GitNexus 호출 그래프로 따라간 변경 영향 (기본 브랜치 ").append(shortSha(impact.commitSha()))
                .append(" 기준)]\n");
        if (!impact.impactedFiles().isEmpty()) {
            sb.append("- 이 PR 이 고치지 않았지만 영향을 받는 파일: ")
                    .append(String.join(", ", impact.impactedFiles().stream().limit(10).toList())).append('\n');
        }
        for (GraphImpact.Fn f : impact.impacted()) {
            if (sb.length() > props.maxStructureChars()) {
                sb.append("... (생략)\n");
                break;
            }
            sb.append("- ").append(f.depth()).append("단계: ").append(f.file()).append('#').append(f.name())
                    .append(':').append(f.line()).append(" → ").append(f.via())
                    .append(f.confirmed() ? " (인덱스로도 확인)" : "").append('\n');
        }
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

    /** LLM 지적의 분류: 보안이라고 주장한 지적은 보안, 나머지는 일반 */
    static Category categoryOf(String kind) {
        return "SECURITY".equals(kind) ? Category.SECURITY : Category.GENERAL;
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
            result.add(new Finding("LLM_REVIEW", categoryOf(item.kind()), severity, file.filename(), line, item.title(),
                    item.message(), null, item.title().strip().toLowerCase(Locale.ROOT), Finding.LLM,
                    Grounding.claim(item.kind(), item.symbols())));
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
                List.copyOf(ctx.notes()), elapsedMs, ctx.graphImpact(), ctx.guarddog());
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
