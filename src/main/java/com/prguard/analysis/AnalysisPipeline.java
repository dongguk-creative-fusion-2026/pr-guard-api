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
import com.prguard.review.Reviewer;
import com.prguard.workspace.Checkout;
import com.prguard.workspace.RepoWorkspace;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public AnalysisPipeline(RepoWorkspace workspace, JavaIndexer indexer, GitHistoryLoader historyLoader,
                            List<Analyzer> analyzers, Reviewer reviewer, VerdictPolicy verdictPolicy,
                            AnalysisProperties props) {
        this.workspace = workspace;
        this.indexer = indexer;
        this.historyLoader = historyLoader;
        this.analyzers = analyzers;
        this.reviewer = reviewer;
        this.verdictPolicy = verdictPolicy;
        this.props = props;
    }

    public AnalysisResult run(PullInfo pull, List<ChangedFile> files, long repoKb) {
        long started = System.currentTimeMillis();
        AnalysisContext ctx = new AnalysisContext(pull, files);
        Checkout checkout = null;
        try {
            checkout = prepare(ctx, repoKb);
            if (checkout != null) {
                index(ctx, checkout);
                history(ctx, checkout);
            }

            List<Finding> findings = new ArrayList<>();
            for (Analyzer analyzer : analyzers) {
                try {
                    findings.addAll(analyzer.analyze(ctx));
                } catch (RuntimeException e) {
                    log.warn("검사기 {} 실패: {}", analyzer.id(), e.toString());
                    ctx.note("검사기 " + analyzer.id() + " 실패: " + e.getMessage());
                }
            }

            String summary;
            try {
                LlmReview llm = reviewer.review(reviewInput(ctx));
                summary = llm.summary();
                findings.addAll(toFindings(llm, ctx));
            } catch (RuntimeException e) {
                log.warn("LLM 리뷰 실패: {}", e.toString());
                ctx.note("LLM 리뷰 실패: " + e.getMessage());
                summary = "LLM 리뷰를 하지 못했습니다. 정적 분석 결과만 표시합니다.";
            }

            findings.sort(Comparator.comparing(Finding::severity).thenComparing(f -> f.file() == null ? "" : f.file()));
            return new AnalysisResult(findings, verdictPolicy.decide(findings), summary, reviewer.name(),
                    summarize(ctx, System.currentTimeMillis() - started));
        } finally {
            if (checkout != null) {
                workspace.release(checkout);
            }
        }
    }

    private Checkout prepare(AnalysisContext ctx, long repoKb) {
        PullInfo pull = ctx.pull();
        try {
            Checkout checkout = workspace.prepare(pull.repo(), repoKb, pull.number(), pull.baseRef(), pull.headSha());
            ctx.setCheckout(checkout);
            return checkout;
        } catch (RuntimeException e) {
            log.warn("{}#{} 소스 준비 실패: {}", pull.repo().fullName(), pull.number(), e.getMessage());
            ctx.note("소스를 받지 못해 diff 만 분석했습니다: " + e.getMessage());
            return null;
        }
    }

    private void index(AnalysisContext ctx, Checkout checkout) {
        try {
            RepoIndex base = indexer.index(checkout.baseDir());
            RepoIndex head = indexer.index(checkout.headDir());
            List<ChangedMethod> changed = MethodDiff.compute(base, head);
            ctx.setIndexes(base, head, changed);
            if (head.parsedFiles() == 0) {
                ctx.note("Java 소스가 없어 레포 인덱스를 만들지 않았습니다");
            }
            if (!head.failedFiles().isEmpty()) {
                ctx.note("파싱하지 못한 파일 " + head.failedFiles().size() + "개: "
                        + String.join(", ", head.failedFiles().stream().limit(5).toList()));
            }
        } catch (RuntimeException e) {
            log.warn("인덱스 실패: {}", e.toString());
            ctx.note("레포 인덱스 실패: " + e.getMessage());
        }
    }

    private void history(AnalysisContext ctx, Checkout checkout) {
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
        } catch (RuntimeException e) {
            log.warn("이력 수집 실패: {}", e.toString());
            ctx.note("git 이력 수집 실패: " + e.getMessage());
        }
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
